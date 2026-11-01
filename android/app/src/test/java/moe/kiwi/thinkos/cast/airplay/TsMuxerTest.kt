package moe.kiwi.thinkos.cast.airplay

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class TsMuxerTest {
    private fun which(name: String): String? = (System.getenv("PATH").orEmpty().split(':') + listOf("/opt/homebrew/bin", "/usr/local/bin")).map { File(it, name) }.firstOrNull { it.canExecute() }?.path

    private fun run(vararg command: String, input: File? = null): Pair<Int, String> {
        val p = ProcessBuilder(*command).redirectErrorStream(true).also { if (input != null) it.redirectInput(input) }.start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to text
    }

    private class Sink { val packets = ArrayList<ByteArray>(); val fn: (ByteArray) -> Unit = { packets += it } }

    private fun pid(p: ByteArray) = ((p[1].toInt() and 0x1f) shl 8) or (p[2].toInt() and 0xff)

    /** Splits an Annex-B stream that carries access unit delimiters into access units. */
    private fun accessUnits(stream: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 4 < stream.size) {
            if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 0 && stream[i + 3].toInt() == 1 && (stream[i + 4].toInt() and 0x1f) == 9) { starts += i; i += 4 } else i++
        }
        return starts.mapIndexed { n, at -> stream.copyOfRange(at + 6, if (n + 1 < starts.size) starts[n + 1] else stream.size) }      // drop the delimiter itself; the muxer writes its own
    }

    @Test fun everyPacketIsWholeAndTheTablesNameTheStreams() {
        val sink = Sink(); val mux = TsMuxer(sink.fn, audioSampleRate = 44100)
        mux.setConfig(Avc.Config(byteArrayOf(0x67, 0x42, 0x00, 0x1F, 0x8C.toByte()), byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())))
        mux.writeVideo(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3, 4, 5), 1_000_000)
        mux.writeAudio(TsMuxer.adtsHeader(10, 44100, 2) + ByteArray(10), 1_000_000)
        assertTrue(sink.packets.all { it.size == 188 && it[0].toInt() == 0x47 })
        assertEquals(0, pid(sink.packets[0]))                                              // PAT first
        val pmt = sink.packets[1]
        assertEquals(TsMuxer.PID_PMT, pid(pmt))
        val section = pmt.copyOfRange(5, 5 + 3 + (((pmt[6].toInt() and 0xf) shl 8) or (pmt[7].toInt() and 0xff)))
        assertEquals(0x1B, section[12].toInt()); assertEquals(0x0F, section[17].toInt())
        assertEquals(TsMuxer.PID_VIDEO, ((section[13].toInt() and 0x1f) shl 8) or (section[14].toInt() and 0xff))
        assertEquals(TsMuxer.PID_AUDIO, ((section[18].toInt() and 0x1f) shl 8) or (section[19].toInt() and 0xff))
        // The section's CRC is the CRC of everything before it.
        var c = 0xFFFFFFFFL
        for (b in section) { c = c xor ((b.toLong() and 0xff) shl 24); repeat(8) { c = if (c and 0x80000000L != 0L) (c shl 1) xor 0x04C11DB7L else c shl 1; c = c and 0xFFFFFFFFL } }
        assertEquals(0L, c)
    }

    @Test fun withoutAudioThereIsNoAudioStream() {
        val sink = Sink(); val mux = TsMuxer(sink.fn)
        mux.writeVideo(byteArrayOf(0, 0, 0, 1, 0x65, 1), 0); mux.writeAudio(ByteArray(20), 0)
        assertTrue(sink.packets.none { pid(it) == TsMuxer.PID_AUDIO })
        val pmt = sink.packets[1]
        assertEquals(9 + 5 + 4, ((pmt[6].toInt() and 0xf) shl 8) or (pmt[7].toInt() and 0xff))                        // header, one stream entry, CRC
    }

    @Test fun continuityCountersRunPerPidAndAFrameFillsWholePackets() {
        val sink = Sink(); val mux = TsMuxer(sink.fn)
        repeat(20) { mux.writeVideo(byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(1000 + it * 37) { 7 }, it * 33_333L) }
        val video = sink.packets.filter { pid(it) == TsMuxer.PID_VIDEO }
        var last = -1
        for (p in video) { val cc = p[3].toInt() and 0xf; if (last >= 0) assertEquals((last + 1) and 0xf, cc); last = cc }
        assertTrue(video.first()[1].toInt() and 0x40 != 0)                              // payload_unit_start on the first packet of a PES
        assertTrue("the first packet carries a PCR", video.first()[3].toInt() and 0x20 != 0 && video.first()[5].toInt() and 0x10 != 0)
    }

    @Test fun ffprobeAndFfmpegAcceptWhatWeMux() {
        val ffmpeg = which("ffmpeg"); val ffprobe = which("ffprobe")
        assumeTrue("ffmpeg and ffprobe are needed", ffmpeg != null && ffprobe != null)
        val dir = File.createTempFile("tsmux", "").let { it.delete(); it.mkdirs(); it }
        try {
            val h264 = File(dir, "in.h264"); val aac = File(dir, "in.aac")
            assertEquals(0, run(ffmpeg!!, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=duration=3:size=640x360:rate=30", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "baseline",
                "-g", "15", "-bf", "0", "-x264-params", "aud=1", "-f", "h264", h264.path).first)
            assertEquals(0, run(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3:sample_rate=44100", "-ac", "2", "-c:a", "aac", "-f", "adts", aac.path).first)
            val units = accessUnits(h264.readBytes())
            assertTrue("x264 gave ${units.size} access units", units.size >= 80)
            // SPS and PPS come from the stream's first access unit, as they would from a sender's avcC record.
            val first = units.first(); val types = Avc.nalTypes(first)
            assertTrue(7 in types && 8 in types && 5 in types)
            fun nal(au: ByteArray, type: Int): ByteArray {
                var i = 0
                while (i + 3 < au.size) { if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1 && (au[i + 3].toInt() and 0x1f) == type) { var j = i + 3; while (j + 2 < au.size && !(au[j].toInt() == 0 && au[j + 1].toInt() == 0 && (au[j + 2].toInt() == 1 || (au[j + 2].toInt() == 0)))) j++; if (j + 2 >= au.size) j = au.size; return au.copyOfRange(i + 3, j) }; i++ }
                error("no NAL $type")
            }
            val out = ByteArrayOutputStream()
            val mux = TsMuxer({ out.write(it) }, audioSampleRate = 44100)
            mux.setConfig(Avc.Config(nal(first, 7), nal(first, 8)))
            // Audio: split the ADTS file into frames.
            val adts = aac.readBytes(); val frames = ArrayList<ByteArray>(); var at = 0
            while (at + 7 < adts.size) { val len = ((adts[at + 3].toInt() and 3) shl 11) or ((adts[at + 4].toInt() and 0xff) shl 3) or ((adts[at + 5].toInt() and 0xff) shr 5); if (len < 7 || at + len > adts.size) break; frames += adts.copyOfRange(at, at + len); at += len }
            var v = 0; var a = 0
            val videoStep = 1_000_000.0 / 30; val audioStep = 1024 * 1_000_000.0 / 44100
            while (v < units.size || a < frames.size) {
                val tv = if (v < units.size) v * videoStep else Double.MAX_VALUE; val ta = if (a < frames.size) a * audioStep else Double.MAX_VALUE
                if (tv <= ta) { mux.writeVideo(units[v], (2_000_000 + tv).toLong()); v++ } else { mux.writeAudio(frames[a], (2_000_000 + ta).toLong()); a++ }
            }
            val ts = File(dir, "out.ts"); ts.writeBytes(out.toByteArray())
            val (code, info) = run(ffprobe!!, "-v", "error", "-show_entries", "stream=codec_name,codec_type,width,height,sample_rate,channels", "-of", "compact", ts.path)
            assertEquals(info, 0, code)
            assertTrue(info, "codec_name=h264|codec_type=video|width=640|height=360" in info)
            assertTrue(info, "codec_name=aac|codec_type=audio|sample_rate=44100|channels=2" in info)
            val (decode, log) = run(ffmpeg, "-v", "error", "-i", ts.path, "-f", "null", "-")
            assertEquals(log, 0, decode); assertEquals("ffmpeg complained: $log", "", log.trim())
            val (_, count) = run(ffprobe, "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", ts.path)
            assertEquals(units.size, count.lines().first { it.isNotBlank() }.trim().toInt())
            val (_, start) = run(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=start_time", "-of", "csv=p=0", ts.path)
            assertEquals(2.0, start.lines().first { it.isNotBlank() }.trim().toDouble(), 0.05)                            // the PTS we gave is the PTS that comes out
        } finally { dir.deleteRecursively() }
    }
}
