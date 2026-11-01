package moe.kiwi.thinkos.cast.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** AudioSpecificConfig bytes and ADTS frames: the small arithmetic of AAC that both the transcoder and its tests need. */
internal object Aac {
    val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun rateIndex(sampleRate: Int): Int = RATES.indexOf(sampleRate).let { if (it < 0) 4 else it }

    /** AAC-ELD (object type 39) at 480 samples per frame, what an iPhone sends with its screen: the 4-byte config RPiPlay hard-codes as F8E85000 for 44.1 kHz stereo. */
    fun eldConfig(sampleRate: Int, channels: Int): ByteArray {
        var bits = 0L
        var n = 0
        fun put(value: Int, width: Int) { bits = (bits shl width) or (value.toLong() and ((1L shl width) - 1)); n += width }
        put(31, 5); put(7, 6)                                  // object type escape: 32 + 7 = 39
        put(rateIndex(sampleRate), 4); put(channels, 4)
        put(1, 1); put(0, 4); put(0, 4)                        // frameLengthFlag = 1 (480 samples), no resilience or SBR, extension terminator
        bits = bits shl (32 - n)
        return byteArrayOf((bits ushr 24).toByte(), (bits ushr 16).toByte(), (bits ushr 8).toByte(), bits.toByte())
    }

    /** AAC-LC: object type 2, then rate, channels and three zero flags. */
    fun lcConfig(sampleRate: Int, channels: Int): ByteArray {
        val bits = (2 shl 11) or (rateIndex(sampleRate) shl 7) or (channels shl 3)
        return byteArrayOf((bits ushr 8).toByte(), bits.toByte())
    }
}

/**
 * Turns the audio a phone sends with its screen into something the player's transport stream reader can use. The phone sends AAC-ELD
 * (480 samples a frame), which Android can decode but ExoPlayer's TS and LATM readers cannot time correctly (they assume 1024 samples
 * a frame). So each frame is decoded to PCM and encoded again as AAC-LC with the platform's own codecs; the ADTS frames go to [onFrame].
 *
 * One thread; [feed] never blocks. Frames that arrive while the codecs are behind are dropped (the oldest), not queued without end.
 */
internal class AacTranscoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val onFrame: (adts: ByteArray, ptsUs: Long) -> Unit,
    private val log: (String) -> Unit,
    /** The format of what comes in: ELD from an iPhone. Anything else is handed over as it is. */
    private val inputConfig: ByteArray = Aac.eldConfig(sampleRate, channels),
) : AudioFrames {
    private class Packet(val frame: ByteArray, val ptsUs: Long)

    private val queue = ArrayBlockingQueue<Packet>(QUEUE)
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var dropped = 0L
        private set
    @Volatile var produced = 0L
        private set

    override fun start() {
        running = true
        thread = Thread({ run() }, "ThinkOS-AirPlay-Audio").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1; start() }
    }

    override fun feed(frame: ByteArray, ptsUs: Long) {
        if (!queue.offer(Packet(frame, ptsUs))) { queue.poll(); queue.offer(Packet(frame, ptsUs)); dropped++ }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread?.let { if (it !== Thread.currentThread()) it.join(1000) }
        thread = null
    }

    private fun run() {
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(inputConfig))
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                }, null, null, 0)
                it.start()
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            val info = MediaCodec.BufferInfo()
            while (running) {
                val packet = queue.poll(100, TimeUnit.MILLISECONDS)
                if (packet != null) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        buffer.clear(); buffer.put(packet.frame)
                        decoder.queueInputBuffer(index, 0, packet.frame.size, packet.ptsUs, 0)
                    } else dropped++
                }
                drainDecoder(decoder, encoder, info)
                drainEncoder(encoder, info)
            }
        } catch (error: InterruptedException) {
            // stopped
        } catch (error: Exception) {
            log("AirPlay audio could not be converted: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
        }
    }

    private fun drainDecoder(decoder: MediaCodec, encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val out = decoder.dequeueOutputBuffer(info, 0)
            if (out < 0) return
            if (info.size > 0) {
                val pcm = decoder.getOutputBuffer(out)!!
                pcm.position(info.offset); pcm.limit(info.offset + info.size)
                var presentation = info.presentationTimeUs
                while (pcm.hasRemaining()) {
                    val slot = encoder.dequeueInputBuffer(10_000)
                    if (slot < 0) { dropped++; break }
                    val into = encoder.getInputBuffer(slot)!!
                    into.clear()
                    val take = minOf(into.remaining(), pcm.remaining())
                    val chunk = pcm.duplicate().also { it.limit(it.position() + take) }
                    into.put(chunk); pcm.position(pcm.position() + take)
                    encoder.queueInputBuffer(slot, 0, take, presentation, 0)
                    presentation += take / (2L * channels) * 1_000_000L / sampleRate
                }
            }
            decoder.releaseOutputBuffer(out, false)
        }
    }

    private fun drainEncoder(encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val out = encoder.dequeueOutputBuffer(info, 0)
            if (out < 0) return
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val raw = ByteArray(info.size)
                encoder.getOutputBuffer(out)!!.also { it.position(info.offset); it.get(raw) }
                onFrame(TsMuxer.adtsHeader(raw.size, sampleRate, channels) + raw, info.presentationTimeUs)
                produced++
            }
            encoder.releaseOutputBuffer(out, false)
        }
    }

    private companion object { const val QUEUE = 64 }
}
