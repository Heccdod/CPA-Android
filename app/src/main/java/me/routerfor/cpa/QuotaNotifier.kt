package me.routerfor.cpa

import android.content.Context
import android.content.Intent

/**
 * Optional quota summary in the expanded gateway notification.
 *
 * Off by default: the notification normally says only "running + address", and a user who did
 * not ask for quota has no reason to see account names on a lock screen. With the setting on,
 * the expanded body lists every enabled account and its remaining quota, so the notification
 * answers "do I still have quota?" without opening the app.
 *
 * Refresh strategy: the body is a cached snapshot, recomputed whenever a quota fetch finishes
 * or the gateway state is refreshed — never on a timer. A polling loop inside a foreground
 * service is exactly what Android trims, and a silently frozen number is worse than a stale one
 * that carries the time it was collected at.
 */
object QuotaNotifier {

    private var cached: String = ""
    private var cachedAt: Long = 0L

    /** Last data handed in, so a bare repaint keeps showing it instead of blanking it. */
    private var lastEntries: List<Credentials.Entry> = emptyList()
    private var lastResults: Map<String, Quota.Result> = emptyMap()

    /**
     * Recomputes the snapshot and asks the service to repaint.
     *
     * Called with real data after a quota fetch, and with no arguments by the home screen's
     * status refresh. The no-argument form must not clear the cache: refresh() runs on a timer,
     * and clearing there made the quota block vanish a second after it appeared.
     */
    fun publish(
        context: Context,
        entries: List<Credentials.Entry>? = null,
        results: Map<String, Quota.Result>? = null
    ) {
        if (!AppPrefs.statusBarQuota(context)) {
            cached = ""
            cachedAt = 0L
            lastEntries = emptyList()
            lastResults = emptyMap()
            requestRepaint(context)
            return
        }
        if (entries != null && results != null) {
            lastEntries = entries
            lastResults = results
            val body = buildBody(entries, results)
            cached = body
            cachedAt = if (body.isEmpty()) 0L else System.currentTimeMillis()
        } else if (lastEntries.isEmpty()) {
            // Nothing collected yet; leave the notification as it is.
            return
        }
        requestRepaint(context)
    }

    /** The text the service should put in the expanded notification, or "" for none. */
    fun cachedBody(): String = cached

    /** Drops the cached snapshot, used when the setting is turned off. */
    fun clear(context: Context) {
        cached = ""
        cachedAt = 0L
        lastEntries = emptyList()
        lastResults = emptyMap()
        requestRepaint(context)
    }

    private fun requestRepaint(context: Context) {
        val intent = Intent(context, ProxyForegroundService::class.java)
            .setAction(ProxyForegroundService.ACTION_REPAINT)
        val result = runCatching { context.startService(intent) }
        android.util.Log.i(
            "CpaQuota",
            "repaint requested, cached=" + cached.length + " ok=" + result.isSuccess +
                " err=" + (result.exceptionOrNull()?.message ?: "-")
        )
    }

    private fun buildBody(
        entries: List<Credentials.Entry>,
        results: Map<String, Quota.Result>
    ): String {
        if (entries.isEmpty() || results.isEmpty()) return ""
        val lines = mutableListOf<String>()
        for (entry in entries) {
            if (entry.disabled) continue
            val result = results[entry.name] ?: continue
            val label = Provider.labelOf(entry.provider)
            val detail = when {
                result.error != null -> result.error
                result.unsupported || !result.hasData -> "额度不可用"
                else -> result.windows.joinToString("  ") { w ->
                    val left = w.usedPercent?.let { (100.0 - it).toInt() }
                    if (left != null) w.label + " " + left + "%" else w.label
                }
            }
            lines.add(label + " · " + detail)
        }
        if (lines.isEmpty()) return ""
        // The clock is read here rather than from cachedAt: publish() builds the body before it
        // stores the timestamp, so reading the field produced a permanent "更新于 —".
        return lines.joinToString("\n") + "\n更新于 " + clock(System.currentTimeMillis())
    }

    private fun clock(millis: Long): String {
        if (millis <= 0L) return "—"
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(millis))
    }
}
