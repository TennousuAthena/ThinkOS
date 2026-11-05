package moe.kiwi.thinkos.apps

import java.text.Collator
import java.util.Locale

data class AppEntry(val id: String, val kind: String, val title: String, val icon: String = "", val image: String = "") {
    fun toMap(): Map<String, String> = mapOf("id" to id, "kind" to kind, "title" to title, "icon" to icon, "image" to image)
}

/** Fixed identifiers and filtering shared by discovery, launch validation and image sources. */
object AppCatalog {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun packageName(value: String): Boolean = value.length <= 255 && packagePattern.matches(value)
    fun androidId(packageName: String) = "android:$packageName"
    fun label(value: String) = value.filter { it.code >= 32 && it.code != 127 }.take(80)

    fun list(pages: List<AppEntry>, installed: List<Pair<String, String>>, ownPackage: String,
             showAndroid: Boolean, hidden: Set<String>, locale: Locale): List<AppEntry> {
        val collator = Collator.getInstance(locale)
        val ordering = Comparator<AppEntry> { a,b -> collator.compare(a.title, b.title).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
        val local = pages.distinctBy { it.id }.sortedWith(ordering)
        val android = if (!showAndroid) emptyList() else installed.asSequence()
            .filter { (id, _) -> packageName(id) && id != ownPackage && id !in hidden }
            .distinctBy { it.first }.map { (id, title) ->
                AppEntry(androidId(id), "android", label(title).ifBlank { id }, image = "app:$id")
            }.sortedWith(ordering).toList()
        require(local.size + android.size <= 512) { "Application catalog exceeds 512 entries" }
        return local + android
    }
}
