package moe.kiwi.thinkos.cast.airplay

/**
 * Packs H.264 access units and AAC frames into an MPEG transport stream, so that what a phone mirrors reaches the player the way every
 * other stream does (the engine's `MirrorSource`, ADR-031). One video PES per access unit, one audio PES per AAC frame, PAT and PMT
 * repeated at every keyframe and at least ten times a second, a PCR on the video PID with every keyframe and every 40 ms.
 *
 * Timestamps are microseconds on one clock chosen by the caller; they go out as 90 kHz PTS (= DTS: the senders do not use B-frames).
 * Not thread-safe: one writer.
 */
internal class TsMuxer(private val sink: (ByteArray) -> Unit, private val audioSampleRate: Int = 0, private val audioChannels: Int = 2) {
    private val counters = IntArray(4)            // video, audio, PAT, PMT
    private var lastTables = Long.MIN_VALUE
    private var lastPcr = Long.MIN_VALUE
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    val hasAudio: Boolean get() = audioSampleRate > 0

    fun setConfig(config: Avc.Config) { sps = config.sps; pps = config.pps }

    /** [annexB] is one access unit (start codes). SPS and PPS go in front of every keyframe. */
    fun writeVideo(annexB: ByteArray, ptsUs: Long) {
        val keyframe = Avc.isKeyframe(annexB)
        val body = java.io.ByteArrayOutputStream(annexB.size + 64)
        body.write(AUD)
        if (keyframe) {
            val s = sps; val p = pps
            if (s != null && p != null && 7 !in Avc.nalTypes(annexB)) { body.write(Avc.START_CODE); body.write(s); body.write(Avc.START_CODE); body.write(p) }
        }
        body.write(annexB)
        val pts = ptsUs * 9 / 100
        if (keyframe || pts - lastTables > 9_000) writeTables(pts)
        val pcr = if (keyframe || lastPcr == Long.MIN_VALUE || pts - lastPcr >= 3_600) pts - PCR_LEAD else null
        if (pcr != null) lastPcr = pts
        writePes(PID_VIDEO, 0, 0xE0, body.toByteArray(), pts, pcr, unbounded = true, keyframe = keyframe)
    }

    /** One AAC-LC access unit as an ADTS frame. */
    fun writeAudio(adtsFrame: ByteArray, ptsUs: Long) {
        if (!hasAudio) return
        writePes(PID_AUDIO, 1, 0xC0, adtsFrame, ptsUs * 9 / 100, null, unbounded = false, keyframe = false)
    }

    // ---- tables ---------------------------------------------------------------------------------------------------------

    private fun writeTables(pts: Long) {
        lastTables = pts
        writeSection(PID_PAT, byteArrayOf(
            0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01, (0xE0 or (PID_PMT shr 8)).toByte(), (PID_PMT and 0xff).toByte()).let(::crc))
        val streams = java.io.ByteArrayOutputStream()
        streams.write(byteArrayOf(0x1B, (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xff).toByte(), 0xF0.toByte(), 0x00))
        if (hasAudio) streams.write(byteArrayOf(0x0F, (0xE0 or (PID_AUDIO shr 8)).toByte(), (PID_AUDIO and 0xff).toByte(), 0xF0.toByte(), 0x00))
        val es = streams.toByteArray()
        val length = 9 + es.size + 4
        val pmt = byteArrayOf(0x02, (0xB0 or (length shr 8)).toByte(), (length and 0xff).toByte(), 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xff).toByte(), 0xF0.toByte(), 0x00) + es
        writeSection(PID_PMT, crc(pmt))
    }

    private fun crc(section: ByteArray): ByteArray {
        var c = 0xFFFFFFFFL
        for (b in section) {
            c = c xor ((b.toLong() and 0xff) shl 24)
            repeat(8) { c = if (c and 0x80000000L != 0L) (c shl 1) xor 0x04C11DB7L else c shl 1; c = c and 0xFFFFFFFFL }
        }
        return section + byteArrayOf((c shr 24).toByte(), (c shr 16).toByte(), (c shr 8).toByte(), c.toByte())
    }

    private fun writeSection(pid: Int, section: ByteArray) {
        val packet = ByteArray(PACKET) { 0xFF.toByte() }
        packet[0] = SYNC; packet[1] = (0x40 or (pid shr 8)).toByte(); packet[2] = (pid and 0xff).toByte()
        packet[3] = (0x10 or (counters[if (pid == PID_PAT) 2 else 3]++ and 0xf)).toByte()
        packet[4] = 0                                   // pointer_field
        System.arraycopy(section, 0, packet, 5, section.size)
        sink(packet)
    }

    // ---- PES ---------------------------------------------------------------------------------------------------------------

    private fun writePes(pid: Int, index: Int, streamId: Int, payload: ByteArray, pts: Long, pcr: Long?, unbounded: Boolean, keyframe: Boolean) {
        val header = java.io.ByteArrayOutputStream(19)
        header.write(byteArrayOf(0, 0, 1, streamId.toByte()))
        val pesLength = if (unbounded) 0 else payload.size + 8
        header.write(pesLength shr 8); header.write(pesLength and 0xff)
        header.write(0x80); header.write(0x80); header.write(5)          // PTS only
        header.write((0x21 or ((pts shr 29) and 0x0E).toInt())); header.write(((pts shr 22) and 0xff).toInt())
        header.write((((pts shr 14) and 0xFE) or 1).toInt()); header.write(((pts shr 7) and 0xff).toInt()); header.write((((pts shl 1) and 0xFE) or 1).toInt())
        val data = header.toByteArray() + payload
        var offset = 0
        var first = true
        while (offset < data.size) {
            offset += emit(pid, index, first, if (first) pcr else null, first && keyframe, data, offset, data.size - offset)
            first = false
        }
    }

    /** One transport packet carrying as much of [data] as fits; an adaptation field holds the PCR and the random-access flag, or stuffs the last packet. */
    private fun emit(pid: Int, index: Int, start: Boolean, pcr: Long?, randomAccess: Boolean, data: ByteArray, offset: Int, remaining: Int): Int {
        val packet = ByteArray(PACKET)
        packet[0] = SYNC
        packet[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte(); packet[2] = (pid and 0xff).toByte()
        val minimum = if (pcr == null && !randomAccess) 0 else 2 + (if (pcr != null) 6 else 0)
        val take = minOf(remaining, 184 - minimum)
        val adaptation = 184 - take                                      // every byte of the packet that is not payload
        val counter = counters[index]++ and 0xf
        if (adaptation == 0) packet[3] = (0x10 or counter).toByte()
        else {
            packet[3] = (0x30 or counter).toByte()
            packet[4] = (adaptation - 1).toByte()
            if (adaptation >= 2) {
                packet[5] = ((if (randomAccess) 0x40 else 0) or (if (pcr != null) 0x10 else 0)).toByte()
                var at = 6
                if (pcr != null) {
                    val base = pcr and 0x1FFFFFFFFL
                    packet[at++] = (base shr 25).toByte(); packet[at++] = (base shr 17).toByte(); packet[at++] = (base shr 9).toByte(); packet[at++] = (base shr 1).toByte()
                    packet[at++] = (((base and 1) shl 7) or 0x7E).toByte(); packet[at++] = 0
                }
                while (at < 4 + adaptation) packet[at++] = 0xFF.toByte()
            }
        }
        System.arraycopy(data, offset, packet, 4 + adaptation, take)
        sink(packet)
        return take
    }

    companion object {
        const val PACKET = 188
        private const val SYNC: Byte = 0x47
        const val PID_PAT = 0
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x101
        const val PID_AUDIO = 0x102
        private const val PCR_LEAD = 4_500L                 // the PCR runs 50 ms ahead of the PTS it accompanies
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        /** The 7-byte ADTS header for an AAC-LC frame of [size] bytes (header included in the length field). */
        fun adtsHeader(size: Int, sampleRate: Int, channels: Int): ByteArray {
            val index = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350).indexOf(sampleRate).let { if (it < 0) 4 else it }
            val length = size + 7
            return byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (index shl 2) or (channels shr 2)).toByte(),
                (((channels and 3) shl 6) or (length shr 11)).toByte(), ((length shr 3) and 0xff).toByte(), (((length and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
        }
    }
}
