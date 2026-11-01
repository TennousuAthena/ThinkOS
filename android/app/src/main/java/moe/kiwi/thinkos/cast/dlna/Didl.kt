package moe.kiwi.thinkos.cast.dlna

import moe.kiwi.thinkos.cast.MiniXml
import moe.kiwi.thinkos.media.MediaSubtitle
import java.net.URI
import java.util.Locale

/** What a controller says about the item it pushed (DIDL-Lite). Every field is optional: controllers differ a lot. */
internal data class DidlInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val art: String = "",
    /** `video`, `audio`, or null when neither the class nor the MIME type says. */
    val kind: String? = null,
    val mime: String? = null,
    val subtitles: List<MediaSubtitle> = emptyList(),
)

internal object Didl {
    /** Never throws: metadata is a courtesy, and a controller whose DIDL we cannot read must still be able to cast. */
    fun parse(xml: String, uri: String): DidlInfo {
        if (xml.isBlank() || xml == "NOT_IMPLEMENTED") return DidlInfo(kind = kindOfUrl(uri))
        val root = try { MiniXml.parse(xml) } catch (error: IllegalArgumentException) { return DidlInfo(kind = kindOfUrl(uri)) }
        val item = (if (root.local == "item") root else root.descendants("item").firstOrNull()) ?: return DidlInfo(kind = kindOfUrl(uri))
        fun text(name: String) = item.child(name)?.text?.trim().orEmpty()
        val resources = item.children.filter { it.local == "res" }
        // The controller usually lists the playable resource first; prefer the one whose address is the one it pushed.
        val main = resources.firstOrNull { it.text.trim() == uri } ?: resources.firstOrNull { subtitleMime(it.attributes["protocolInfo"]) == null }
        val mime = main?.attributes?.get("protocolInfo")?.split(':')?.getOrNull(2)?.takeIf { '/' in it && it != "*" }?.lowercase(Locale.ROOT)
        val kind = when {
            text("class").contains("videoItem", ignoreCase = true) -> "video"
            text("class").contains("audioItem", ignoreCase = true) || text("class").contains("musicTrack", ignoreCase = true) -> "audio"
            mime?.startsWith("video/") == true -> "video"
            mime?.startsWith("audio/") == true -> "audio"
            else -> kindOfUrl(uri)
        }
        val subtitles = mutableListOf<MediaSubtitle>()
        fun addSubtitle(url: String, mime: String?) {
            val clean = url.trim()
            if (clean.isEmpty() || subtitles.size >= 4 || subtitles.any { it.url == clean }) return
            runCatching { subtitles += MediaSubtitle(clean, mime ?: MediaSubtitle.mimeForName(clean) ?: MediaSubtitle.SRT) }
        }
        for (res in resources) subtitleMime(res.attributes["protocolInfo"])?.let { addSubtitle(res.text, it) }
        // Samsung and PacketVideo put the track in vendor elements instead.
        for (name in listOf("subtitleFileUri", "CaptionInfoEx", "CaptionInfo")) item.descendants(name).forEach { addSubtitle(it.text, null) }
        return DidlInfo(
            title = text("title").take(512), artist = (text("artist").ifEmpty { text("creator") }).take(512),
            album = text("album").take(512), art = text("albumArtURI").takeIf { it.startsWith("http") }.orEmpty(),
            kind = kind, mime = mime, subtitles = subtitles,
        )
    }

    private val SUBTITLE_MIMES = mapOf(
        "text/srt" to MediaSubtitle.SRT, "application/x-subrip" to MediaSubtitle.SRT, "text/vtt" to MediaSubtitle.VTT,
        "text/x-ssa" to MediaSubtitle.SSA, "text/ssa" to MediaSubtitle.SSA, "application/ttml+xml" to MediaSubtitle.TTML,
    )

    private fun subtitleMime(protocolInfo: String?): String? =
        protocolInfo?.split(':')?.getOrNull(2)?.lowercase(Locale.ROOT)?.let(SUBTITLE_MIMES::get)

    /** Last resort: the file name. Most casts are films, so an address that says nothing is treated as video. */
    fun kindOfUrl(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase(Locale.ROOT)
        return when {
            AUDIO_EXTENSIONS.any { path.endsWith(it) } -> "audio"
            else -> "video"
        }
    }

    private val AUDIO_EXTENSIONS = listOf(".mp3", ".flac", ".wav", ".aac", ".m4a", ".ogg", ".opus", ".wma", ".ape")
}
