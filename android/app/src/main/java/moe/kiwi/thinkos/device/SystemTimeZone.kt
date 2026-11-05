package moe.kiwi.thinkos.device

import android.os.Build
import moe.kiwi.thinkos.update.UpdaterClient
import moe.kiwi.thinkos.update.UpdaterProtocol
import java.util.TimeZone

/** Always resolves against the actual ROM list before asking the optional privileged module.
 * TimeZone.getTimeZone(unknown) is deliberately never used: it silently returns GMT. */
internal object SystemTimeZone {
    val aliases: Map<String, String> = mapOf(
        "Europe/Kyiv" to "Europe/Kiev",
        "America/Ciudad_Juarez" to "America/Denver",
        "America/Coyhaique" to "America/Punta_Arenas",
    )
    fun resolve(id: String, available: Set<String>): String? {
        if (!UpdaterProtocol.zoneId(id)) return null
        if (id in available) return id
        return aliases[id]?.takeIf { it in available }
    }
    suspend fun available(client: UpdaterClient): Boolean = Build.VERSION.SDK_INT == 27 &&
        client.status().getOrNull()?.setup?.timezone == true
    suspend fun set(client: UpdaterClient, id: String, tapElapsed: Long): Boolean {
        if (Build.VERSION.SDK_INT != 27) return false
        val resolved = resolve(id, TimeZone.getAvailableIDs().toSet()) ?: return false
        return client.setTimeZone(resolved, tapElapsed).isSuccess
    }
}
