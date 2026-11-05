package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.i18n.LocaleChain
import java.util.Locale

/** Only the private install journal can produce a completed-update notice.
 * Download metadata or a workflow READY hint is never a completed update. */
internal object UpdateNotice {
    data class Item(val kind: String, val version: String, val code: Int, val notes: String, val reason: String?, val remaining: Long)
    fun read(snapshot: UpdateInstallStore.Snapshot, currentCode: Int, now: UpdateInstallTime, locale: Locale): Item? {
        val pending = snapshot.pending
        if (pending?.phase == "TRIAL" && pending.to.code == currentCode) {
            val left = UpdateInstallStore.noticeRemaining(pending.created, pending.notice_ms, now) ?: return null
            return Item("updated", pending.to.version, pending.to.code, notes(pending.notes, locale), null, left)
        }
        val result = UpdateInstallStore.recent(snapshot.history.result, now) ?: return null
        // A history file from an older healthy update cannot label a different
        // manually installed version as having just completed that transaction.
        if (result.kind == "updated" && result.code != currentCode) return null
        val remaining = UpdateInstallStore.noticeRemaining(result.recorded, result.notice_ms, now) ?: return null
        return Item(result.kind, result.version, result.code, notes(result.notes, locale), result.reason, remaining)
    }
    private fun notes(values: Map<String, String>, locale: Locale) = LocaleChain.candidates(locale).firstNotNullOfOrNull { values[it] }.orEmpty()
}
