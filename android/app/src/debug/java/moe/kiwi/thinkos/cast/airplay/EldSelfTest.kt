package moe.kiwi.thinkos.cast.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug builds only. Makes two seconds of AAC-ELD with the platform's own encoder (an iPhone is not here to send any) and runs it through
 * [AacTranscoder]; reports how many AAC-LC frames came out and what the codecs said. This is the one part of the mirror path no host test can reach.
 */
object EldSelfTest {
    fun run(report: (String) -> Unit) {
        try {
            val rate = 44100; val channels = 2
            val encoder = try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                    it.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD); setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                    }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    it.start()
                }
            } catch (error: Exception) { report("no-eld-encoder ${error.javaClass.simpleName}: ${error.message}"); return }
            val frames = ArrayList<ByteArray>()
            val output = AtomicInteger(); val bytes = AtomicInteger(); val done = CountDownLatch(1)

            // 2 s of a 440 Hz tone through the ELD encoder.
            val info = MediaCodec.BufferInfo(); var fed = 0L; val total = rate * 2L; var presentation = 0L; var config: ByteArray? = null
            val deadline = System.nanoTime() + 10_000_000_000L
            var ended = false
            while (!ended && System.nanoTime() < deadline) {
                if (fed < total) {
                    val slot = encoder.dequeueInputBuffer(5_000)
                    if (slot >= 0) {
                        val buffer = encoder.getInputBuffer(slot)!!; buffer.clear()
                        val samples = minOf(480, (total - fed).toInt())
                        for (i in 0 until samples) { val v = (Math.sin(2 * Math.PI * 440 * (fed + i) / rate) * 8000).toInt().toShort(); repeat(channels) { buffer.putShort(v) } }
                        encoder.queueInputBuffer(slot, 0, samples * 2 * channels, presentation, if (fed + samples >= total) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        fed += samples; presentation += samples * 1_000_000L / rate
                    }
                }
                val out = encoder.dequeueOutputBuffer(info, 5_000)
                if (out >= 0) {
                    if (info.size > 0) {
                        val data = ByteArray(info.size); encoder.getOutputBuffer(out)!!.also { it.position(info.offset); it.get(data) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = data else frames += data
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                    encoder.releaseOutputBuffer(out, false)
                }
            }
            encoder.stop(); encoder.release()
            report("encoded ${frames.size} ELD frames, config=${config?.joinToString("") { "%02x".format(it) }} (ours ${Aac.eldConfig(rate, channels).joinToString("") { "%02x".format(it) }})")
            // The platform's ELD encoder chooses its own layout (here 1024 samples a frame with SBR), not the 480-sample one an iPhone uses, so the
            // decoder is told what this encoder wrote. What this proves is that the platform can decode ELD and that the conversion chain runs.
            val transcoder = AacTranscoder(rate, channels, { adts, _ -> output.incrementAndGet(); bytes.addAndGet(adts.size) }, { report("log: $it") }, config ?: Aac.eldConfig(rate, channels))
            transcoder.start()
            var t = 0L
            for (frame in frames) { transcoder.feed(frame, t); t += 1024L * 1_000_000 / rate; Thread.sleep(8) }
            Thread.sleep(800)
            transcoder.stop()
            report("transcoded ${output.get()} AAC-LC frames, ${bytes.get()} bytes, dropped ${transcoder.dropped}")
        } catch (error: Throwable) {
            report("failed ${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
