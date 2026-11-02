package me.routerfor.cpa

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Per-credential quota, read the same way the management panel reads it.
 *
 * The kernel exposes no normalised "how much quota is left" endpoint: /v8/management/credentials
 * only carries whatever watermarks the provider happened to return on the last real request
 * (quota.signals), which is empty on a fresh account. What the panel does instead is proxy a
 * real call to each provider's own quota endpoint through POST /v8/management/requests/api-call,
 * where the kernel substitutes a freshly refreshed access token for the literal marker
 * "Bearer \$TOKEN\$" in the header map. That is the only path that works for every provider, so
 * it is the path used here too.
 *
 * Each provider answers in its own shape, so parsing is per-provider. Anything unrecognised
 * degrades to "quota unavailable" instead of an error banner: a provider that simply has no
 * quota API is normal, not a failure.
 */
object Quota {

    /**
     * One measured limit.
     *
     * [family] is the model family the limit belongs to ("Gemini", "Claude"), because a
     * provider reports the same 5-hour / weekly pair once per family and each pair is a
     * separate budget — merging them would show one number where the account actually has two.
     * It is empty for providers that only report one budget.
     */
    data class Window(
        val label: String,
        /** 0..100, or null when the provider only reports a boolean state. */
        val usedPercent: Double?,
        val resetLabel: String?,
        val family: String = ""
    )

    data class Result(
        val windows: List<Window> = emptyList(),
        /** True when the provider answered but has no quota concept (pay-as-you-go, API key). */
        val unsupported: Boolean = false,
        val error: String? = null
    ) {
        val hasData: Boolean get() = windows.isNotEmpty()
    }

    private const val MARKER = "Bearer \$TOKEN\$"

    /**
     * Wall-clock budget for one credential, and the socket timeouts underneath it.
     *
     * These used to be 8 s connect + 30 s read per hop, and a provider that answers from three
     * hosts in turn multiplied that by three: five accounts could keep the refresh running for
     * about 190 seconds, long after the user had left the screen. Twelve seconds is more than a
     * healthy provider needs and short enough that a dead one is reported as an error the user
     * can see instead of a spinner that never stops.
     */
    private const val ACCOUNT_BUDGET_MS = 12_000L
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 6_000

    // Header sets copied from the panel bundle so the providers see the client they expect.
    private const val ANTIGRAVITY_UA = "antigravity/hub/2.23.0 darwin/arm64"
    private const val CLAUDE_UA = "claude-cli/2.1.280 (external, cli)"
    private const val CODEX_UA =
        "codex-tui/0.149.1 (Mac OS 26.5.2; arm64) iTerm.app/3.6.11 (codex-tui; 0.149.1)"

    private val ANTIGRAVITY_HOSTS = listOf(
        "https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary",
        "https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal:retrieveUserQuotaSummary",
        "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary"
    )

    /** Fetches one credential's quota. Blocking; call from a worker thread. */
    fun fetch(entry: Credentials.Entry): Result = runCatching {
        val budget = Budget(ACCOUNT_BUDGET_MS)
        when (entry.provider.lowercase()) {
            "antigravity" -> antigravity(entry, budget)
            "claude" -> claude(entry, budget)
            "codex" -> codex(entry, budget)
            "xai" -> xai(entry, budget)
            "kimi" -> kimi(entry, budget)
            else -> Result(unsupported = true)
        }
    }.getOrElse { Result(error = it.message ?: it.javaClass.simpleName) }

    /**
     * The time one credential's fetch may still spend, shared by every hop it makes.
     *
     * Per-call timeouts are not enough on their own: a provider with three candidate hosts would
     * otherwise get three full timeouts, which is how one account could take a minute.
     */
    private class Budget(budgetMs: Long) {
        private val deadline = System.nanoTime() + budgetMs * 1_000_000L

        fun remainingMs(): Long = (deadline - System.nanoTime()) / 1_000_000L

        fun expired(): Boolean = remainingMs() <= 0L
    }

    // --------------------------------------------------------------- providers

    private fun antigravity(entry: Credentials.Entry, budget: Budget): Result {
        val project = entry.projectId
        if (project.isNullOrBlank()) return Result(error = "缺少 project_id，请重新授权")
        val body = JSONObject().put("project", project).toString()
        var lastStatus = 0
        for (host in ANTIGRAVITY_HOSTS) {
            if (budget.expired()) break
            val resp = apiCall(
                entry.authIndex, "POST", host, budget,
                mapOf(
                    "Authorization" to MARKER,
                    "Content-Type" to "application/json",
                    // The quota endpoint identifies the client by this UA. Without it the call
                    // returns 403 "You do not have a valid license of this product", which
                    // looks like an account problem but is only a missing header.
                    "User-Agent" to ANTIGRAVITY_UA
                ),
                body
            ) ?: continue
            lastStatus = resp.status
            if (resp.status !in 200..299) continue
            val parsed = parseAntigravity(resp.body)
            if (parsed.windows.isNotEmpty()) return parsed
        }
        return Result(
            error = if (lastStatus == 403 || lastStatus == 404) "该项目没有可用的额度接口"
            else "上游没有返回额度数据"
        )
    }

    private fun parseAntigravity(raw: String): Result {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return Result()
        val groups = root.optJSONArray("groups") ?: return Result()
        val windows = mutableListOf<Window>()
        for (i in 0 until groups.length()) {
            val group = groups.optJSONObject(i) ?: continue
            val groupName = group.optString("displayName").ifEmpty { group.optString("display_name") }
            val buckets = group.optJSONArray("buckets") ?: continue
            for (j in 0 until buckets.length()) {
                val bucket = buckets.optJSONObject(j) ?: continue
                val fraction = bucket.optDouble("remainingFraction", bucket.optDouble("remaining_fraction", Double.NaN))
                if (fraction.isNaN()) continue
                val window = bucket.optString("window")
                val reset = bucket.optString("resetTime").ifEmpty { bucket.optString("reset_time") }
                // Antigravity reports the same weekly/hourly pair once per model family
                // ("Gemini Models", "Claude and GPT models"). Those are two independent
                // budgets, so the family is carried as its own field and drawn as a heading
                // with the pair underneath — not folded into one summary number.
                val family = friendlyGroup(groupName)
                val base = friendlyLabel(bucket.optString("displayName"), window)
                windows.add(
                    Window(
                        label = base,
                        usedPercent = (1.0 - fraction) * 100.0,
                        resetLabel = resetLabel(reset),
                        family = family
                    )
                )
            }
        }
        return Result(windows = windows)
    }

    private fun claude(entry: Credentials.Entry, budget: Budget): Result {
        val resp = apiCall(
            entry.authIndex, "GET", "https://api.anthropic.com/api/oauth/usage", budget,
            mapOf(
                "Authorization" to MARKER,
                "Content-Type" to "application/json",
                "anthropic-beta" to "oauth-2025-04-20",
                "User-Agent" to CLAUDE_UA
            ),
            null
        ) ?: return Result(error = "请求未发出")
        if (resp.status !in 200..299) return Result(error = "上游返回 " + resp.status)
        val root = runCatching { JSONObject(resp.body) }.getOrNull() ?: return Result()
        val windows = mutableListOf<Window>()
        for ((key, label) in CLAUDE_WINDOWS) {
            val node = root.optJSONObject(key) ?: continue
            val used = node.optDouble("utilization", Double.NaN)
            if (used.isNaN()) continue
            windows.add(Window(label, used, resetLabel(node.optString("resets_at"))))
        }
        return Result(windows = windows)
    }

    private fun codex(entry: Credentials.Entry, budget: Budget): Result {
        val resp = apiCall(
            entry.authIndex, "GET", "https://chatgpt.com/backend-api/wham/usage", budget,
            mapOf(
                "Authorization" to MARKER,
                "Content-Type" to "application/json",
                "User-Agent" to CODEX_UA
            ),
            null
        ) ?: return Result(error = "请求未发出")
        if (resp.status !in 200..299) return Result(error = "上游返回 " + resp.status)
        return CodexUsage.parse(resp.body)
    }

    private fun xai(entry: Credentials.Entry, budget: Budget): Result {
        val resp = apiCall(
            entry.authIndex, "GET", "https://cli-chat-proxy.grok.com/v1/billing?format=credits", budget,
            mapOf("Authorization" to MARKER), null
        ) ?: return Result(error = "请求未发出")
        if (resp.status !in 200..299) return Result(unsupported = true)
        val root = runCatching { JSONObject(resp.body) }.getOrNull() ?: return Result()
        val used = root.optDouble("used_percent", Double.NaN)
        if (used.isNaN()) return Result(unsupported = true)
        return Result(windows = listOf(Window("额度", used, null)))
    }

    private fun kimi(entry: Credentials.Entry, budget: Budget): Result {
        val hosts = listOf("https://api.kimi.com/coding/v1/usages", "https://api.kimi.ai/coding/v1/usages")
        for (host in hosts) {
            if (budget.expired()) break
            val resp = apiCall(entry.authIndex, "GET", host, budget, mapOf("Authorization" to MARKER), null) ?: continue
            if (resp.status !in 200..299) continue
            val root = runCatching { JSONObject(resp.body) }.getOrNull() ?: continue
            val used = root.optDouble("used_percent", Double.NaN)
            if (used.isNaN()) continue
            return Result(windows = listOf(Window("额度", used, null)))
        }
        return Result(unsupported = true)
    }

    // --------------------------------------------------------------- transport

    private class ApiResponse(val status: Int, val body: String)

    /**
     * One proxied upstream call. The kernel swaps "Bearer \$TOKEN\$" for a live access token of
     * the named credential, refreshing it first when it is close to expiry — which is why quota
     * can be read without the app ever touching a token itself.
     */
    private fun apiCall(
        authIndex: String,
        method: String,
        url: String,
        budget: Budget,
        headers: Map<String, String>,
        body: String?
    ): ApiResponse? = runCatching {
        if (authIndex.isBlank()) return null
        if (budget.expired()) return null
        val payload = JSONObject().apply {
            put("auth_index", authIndex)
            put("method", method)
            put("url", url)
            put("header", JSONObject(headers as Map<*, *>))
            if (body != null) put("data", body)
        }
        android.util.Log.i("CpaQuota", "payload=" + payload.toString().take(400))
        val text = request("POST", "/v8/management/requests/api-call", payload.toString(), budget)
        if (text == null) {
            android.util.Log.w("CpaQuota", "api-call rejected for " + url)
            return null
        }
        val node = JSONObject(text)
        val resp = ApiResponse(node.optInt("status_code", 0), node.optString("body"))
        android.util.Log.i(
            "CpaQuota",
            "api-call " + url + " -> " + resp.status + " bodyLen=" + resp.body.length +
                (if (resp.status !in 200..299) " body=" + resp.body.take(300) else "")
        )
        resp
    }.getOrElse {
        android.util.Log.w("CpaQuota", "api-call failed for " + url, it)
        null
    }

    /**
     * One loopback call to the management API.
     *
     * Both timeouts are clamped to what is left of the credential's budget, so the last hop of a
     * slow provider cannot outlive the row that is waiting for it.
     */
    private fun request(method: String, path: String, body: String?, budget: Budget): String? {
        val left = budget.remainingMs()
        if (left <= 0L) return null
        val conn = URL(GatewayPort.base() + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = left.coerceAtMost(CONNECT_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(1)
        conn.readTimeout = left.coerceAtMost(READ_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(1)
        conn.setRequestProperty("Authorization", "Bearer " + WebBridgeCompat.managementKey())
        conn.setRequestProperty("Accept", "application/json")
        // Everything from the first byte written to the last one read is inside one try/finally.
        // Writing the body used to sit outside it: a write that failed against a half-dead
        // gateway (RST, engine just killed) skipped disconnect() and leaked the connection.
        return try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // --------------------------------------------------------------- formatting

    /**
     * Reset time as an absolute clock value ("14:12" today, "10月31日 14:12" later on).
     *
     * "4 天" reads nicely but is useless when deciding whether to start a long task; the actual
     * moment is what the panel shows and what was asked for.
     */
    private fun resetLabel(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        val parsed = runCatching { java.time.Instant.parse(iso) }.getOrNull() ?: return null
        val local = parsed.atZone(java.time.ZoneId.systemDefault())
        val now = java.time.ZonedDateTime.now()
        val pattern = if (local.toLocalDate() == now.toLocalDate()) "HH:mm" else "M月d日 HH:mm"
        return local.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    }

    /**
     * The quota endpoints answer with English display names ("Weekly Limit Remaining"),
     * sometimes including a whole sentence of description. The UI is Chinese, so the common
     * window names are mapped and anything else falls back to its window key.
     */
    private fun friendlyLabel(displayName: String, window: String): String {
        val lower = displayName.lowercase()
        val w = window.lowercase()
        return when {
            lower.contains("weekly") || w == "weekly" -> "每周额度"
            lower.contains("five hour") || lower.contains("5 hour") || w == "five_hour" -> "5 小时额度"
            lower.contains("daily") || w == "daily" -> "每日额度"
            lower.contains("monthly") || w == "monthly" -> "每月额度"
            displayName.isBlank() -> "额度"
            // A long English sentence is worse than nothing; keep the window key instead.
            displayName.length > 24 -> w.ifEmpty { "额度" }
            else -> displayName
        }
    }

    /** Short Chinese name for a quota group ("Gemini Models" -> "Gemini"). */
    private fun friendlyGroup(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("gemini") -> "Gemini"
            lower.contains("claude") || lower.contains("gpt") -> "Claude"
            name.length > 16 -> ""
            else -> name
        }
    }

    /**
     * Groups the measured windows by model family, in a stable display order.
     *
     * Each family is its own budget: Gemini can be exhausted while Claude still has room, so
     * they must be drawn as separate blocks with their own 5-hour and weekly rows. Providers
     * that report a single budget come back as one group with an empty name.
     */
    fun byFamily(windows: List<Window>): List<Pair<String, List<Window>>> {
        if (windows.isEmpty()) return emptyList()
        val order = mutableListOf<String>()
        val buckets = LinkedHashMap<String, MutableList<Window>>()
        for (w in windows) {
            if (!buckets.containsKey(w.family)) {
                order.add(w.family)
                buckets[w.family] = mutableListOf()
            }
            buckets.getValue(w.family).add(w)
        }
        // Five-hour first, then weekly, then anything else with a stable name.
        fun rank(w: Window): Int = when {
            w.label.contains("5 小时") -> 0
            w.label.contains("每周") -> 1
            else -> 2
        }
        return order.map { family ->
            family to buckets.getValue(family).sortedBy { rank(it) }
        }
    }

    private val CLAUDE_WINDOWS = listOf(
        "five_hour" to "5 小时",
        "seven_day" to "7 天",
        "seven_day_oauth_apps" to "7 天 OAuth",
        "seven_day_opus" to "7 天 Opus",
        "seven_day_sonnet" to "7 天 Sonnet"
    )

    /**
     * Codex reports a rolling pair of budgets whose *labels* move between plans.
     *
     * The rule below is taken from the panel bundle's own window classifier, which keys off
     * `limit_window_seconds` rather than window order:
     *
     *     18000    -> the 5-hour budget
     *     604800   -> the weekly budget
     *     2419200..2678400 (28..31 days) -> the monthly budget of team plans
     *
     * That distinction is the whole point of this class. A Pro subscription has no 5-hour
     * window at all, so hard-coding "primary = 5 hours" mislabels its only window as a 5-hour
     * budget that does not exist - and, worse, hides the weekly one behind the wrong name.
     * Classifying by seconds means the rows the account does not have simply are not drawn.
     *
     * Order fallback mirrors the panel's `allowOrderFallback`: when a window carries no
     * seconds at all (older payloads), primary is assumed to be the 5-hour slot and secondary
     * the weekly one - but only after both have failed to identify themselves.
     */
    internal object CodexWindows {
        const val FIVE_HOUR = 18_000L
        const val WEEKLY = 604_800L
        const val MONTH_MIN = 2_419_200L
        const val MONTH_MAX = 2_678_400L
    }

    /**
     * Parses one `/backend-api/wham/usage` body into display windows.
     *
     * Kept free of Android and network types so the classification is testable on the JVM: the
     * bug this replaces was a pure mapping error, and a screenshot cannot tell a correctly
     * labelled empty window from a missing one.
     */
    internal object CodexUsage {

        fun parse(raw: String): Result {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return Result()
            val windows = mutableListOf<Window>()

            val main = root.optJSONObject("rate_limit") ?: root.optJSONObject("rateLimit")
            addPair(windows, main, prefix = "")

            val review = root.optJSONObject("code_review_rate_limit")
                ?: root.optJSONObject("codeReviewRateLimit")
            addPair(windows, review, prefix = "代码审查 ")

            // Per-feature budgets ("additional_rate_limits"), each carrying its own pair. The
            // panel draws them as separate rows named after the feature; the name is kept as a
            // prefix so "GPT-5 Codex 5 小时" stays distinguishable from the main budget.
            val extras = root.optJSONArray("additional_rate_limits")
                ?: root.optJSONArray("additionalRateLimits")
            if (extras != null) {
                for (i in 0 until extras.length()) {
                    val item = extras.optJSONObject(i) ?: continue
                    val block = item.optJSONObject("rate_limit") ?: item.optJSONObject("rateLimit") ?: continue
                    val name = item.optString("limit_name")
                        .ifEmpty { item.optString("limitName") }
                        .ifEmpty { item.optString("metered_feature") }
                        .ifEmpty { item.optString("meteredFeature") }
                        .ifEmpty { "附加 " + (i + 1) }
                    addPair(windows, block, prefix = name.trim() + " ")
                }
            }

            return Result(windows = windows)
        }

        /**
         * Classifies one block's primary/secondary windows and appends whatever it actually has.
         *
         * A block whose main budget is gone (free plan, or a plan that simply has no such
         * limit) yields nothing, which is the correct rendering: no row beats a row of zeros.
         */
        private fun addPair(out: MutableList<Window>, block: JSONObject?, prefix: String) {
            if (block == null) return
            val primary = block.optJSONObject("primary_window") ?: block.optJSONObject("primaryWindow")
            val secondary = block.optJSONObject("secondary_window") ?: block.optJSONObject("secondaryWindow")
            val reached = block.optBoolean("limit_reached", block.optBoolean("limitReached", false))

            var fiveHour: JSONObject? = null
            var weekly: JSONObject? = null
            var monthly: JSONObject? = null
            for (node in listOf(primary, secondary)) {
                if (node == null) continue
                val seconds = windowSeconds(node)
                if (seconds == null) continue
                if (seconds == CodexWindows.FIVE_HOUR && fiveHour == null) fiveHour = node
                else if (seconds == CodexWindows.WEEKLY && weekly == null) weekly = node
                else if (seconds >= CodexWindows.MONTH_MIN && seconds <= CodexWindows.MONTH_MAX && monthly == null) {
                    monthly = node
                }
            }
            // Nothing identified itself: fall back to the panel's order assumption, but only then.
            if (fiveHour == null && weekly == null && monthly == null) {
                fiveHour = primary
                weekly = secondary
            }
            emit(out, fiveHour, prefix + "5 小时额度", reached)
            // The long window is called weekly or monthly depending on the plan, and the label
            // has to follow the data: a team account's only long window is a month, not a week.
            if (monthly != null) emit(out, monthly, prefix + "每月额度", reached)
            else emit(out, weekly, prefix + "每周额度", reached)
        }

        private fun emit(out: MutableList<Window>, node: JSONObject?, label: String, reached: Boolean) {
            if (node == null) return
            val raw = node.optDouble("used_percent", node.optDouble("usedPercent", Double.NaN))
            // limit_reached is the only signal for accounts whose payload carries no percentage
            // at all; without it those rows would silently disappear instead of showing "used up".
            val used = if (!raw.isNaN()) raw else if (reached) 100.0 else return
            out.add(Window(label, used, resetLabelOf(node)))
        }

        /** `limit_window_seconds` when present, otherwise derived from a reset timestamp. */
        private fun windowSeconds(node: JSONObject): Long? {
            val direct = node.optLong("limit_window_seconds", -1L)
                .let { if (it >= 0) it else node.optLong("limitWindowSeconds", -1L) }
            if (direct >= 0) return direct
            // Older payloads gave only "resets in N seconds" for each window. A 5-hour budget
            // resets within 5 hours and a weekly one within 7 days, so the countdown itself
            // identifies the window; anything longer than a week is treated as monthly.
            val inSeconds = node.optLong("resets_in_seconds", -1L)
                .let { if (it >= 0) it else node.optLong("resetsInSeconds", -1L) }
            return when {
                inSeconds < 0 -> null
                inSeconds <= CodexWindows.FIVE_HOUR -> CodexWindows.FIVE_HOUR
                inSeconds <= CodexWindows.WEEKLY -> CodexWindows.WEEKLY
                inSeconds <= CodexWindows.MONTH_MAX -> CodexWindows.MONTH_MIN
                else -> null
            }
        }

        /**
         * When the window resets, from whichever field the provider chose to send.
         *
         * reset_at is normally ISO-8601, but some payloads carry an epoch number instead. The
         * old version returned resetLabel(at) unconditionally, so a numeric reset_at parsed to
         * null and returned early — the reset_after_seconds fallback below was unreachable, and
         * the row silently lost its "XX 重置" suffix with no way to tell why.
         */
        private fun resetLabelOf(node: JSONObject): String? {
            val at = node.optString("reset_at").ifEmpty { node.optString("resetAt") }
            if (at.isNotBlank()) {
                val epoch = at.toLongOrNull()
                val fromAbsolute = if (epoch != null) {
                    // Seconds vs milliseconds: anything below 1e11 cannot be millis until 1973.
                    val millis = if (epoch < 100_000_000_000L) epoch * 1000L else epoch
                    resetLabel(java.time.Instant.ofEpochMilli(millis).toString())
                } else {
                    resetLabel(at)
                }
                if (fromAbsolute != null) return fromAbsolute
            }
            val after = node.optLong("reset_after_seconds", -1L)
                .let { if (it >= 0) it else node.optLong("resetAfterSeconds", -1L) }
            if (after < 0) return null
            return resetLabel(java.time.Instant.now().plusSeconds(after).toString())
        }
    }
}
