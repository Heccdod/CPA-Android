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

    /** One measured limit: what it is called, how much is used, and when it resets. */
    data class Window(
        val label: String,
        /** 0..100, or null when the provider only reports a boolean state. */
        val usedPercent: Double?,
        val resetLabel: String?
    )

    data class Result(
        val windows: List<Window> = emptyList(),
        /** True when the provider answered but has no quota concept (pay-as-you-go, API key). */
        val unsupported: Boolean = false,
        val error: String? = null
    ) {
        val hasData: Boolean get() = windows.isNotEmpty()
    }

    private const val BASE = "http://127.0.0.1:8317"
    private const val MARKER = "Bearer \$TOKEN\$"

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
        when (entry.provider.lowercase()) {
            "antigravity" -> antigravity(entry)
            "claude" -> claude(entry)
            "codex" -> codex(entry)
            "xai" -> xai(entry)
            "kimi" -> kimi(entry)
            else -> Result(unsupported = true)
        }
    }.getOrElse { Result(error = it.message ?: it.javaClass.simpleName) }

    // --------------------------------------------------------------- providers

    private fun antigravity(entry: Credentials.Entry): Result {
        val project = entry.projectId
        if (project.isNullOrBlank()) return Result(error = "缺少 project_id，请重新授权")
        val body = JSONObject().put("project", project).toString()
        var lastStatus = 0
        for (host in ANTIGRAVITY_HOSTS) {
            val resp = apiCall(
                entry.authIndex, "POST", host,
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
                // ("Gemini Models", "Claude and GPT models"), so the group has to stay in the
                // label or the list reads as four duplicated rows.
                val family = friendlyGroup(groupName)
                val base = friendlyLabel(bucket.optString("displayName"), window)
                windows.add(
                    Window(
                        label = if (family.isEmpty()) base else family + " " + base,
                        usedPercent = (1.0 - fraction) * 100.0,
                        resetLabel = resetLabel(reset)
                    )
                )
            }
        }
        return Result(windows = windows)
    }

    private fun claude(entry: Credentials.Entry): Result {
        val resp = apiCall(
            entry.authIndex, "GET", "https://api.anthropic.com/api/oauth/usage",
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

    private fun codex(entry: Credentials.Entry): Result {
        val resp = apiCall(
            entry.authIndex, "GET", "https://chatgpt.com/backend-api/wham/usage",
            mapOf(
                "Authorization" to MARKER,
                "Content-Type" to "application/json",
                "User-Agent" to CODEX_UA
            ),
            null
        ) ?: return Result(error = "请求未发出")
        if (resp.status !in 200..299) return Result(error = "上游返回 " + resp.status)
        val root = runCatching { JSONObject(resp.body) }.getOrNull() ?: return Result()
        val rate = root.optJSONObject("rate_limit") ?: root
        val windows = mutableListOf<Window>()
        for ((key, label) in CODEX_WINDOWS) {
            val node = rate.optJSONObject(key) ?: continue
            val used = node.optDouble("used_percent", Double.NaN)
            if (used.isNaN()) continue
            val reset = node.optLong("resets_in_seconds", 0L)
            windows.add(Window(label, used, secondsLabel(reset)))
        }
        return Result(windows = windows)
    }

    private fun xai(entry: Credentials.Entry): Result {
        val resp = apiCall(
            entry.authIndex, "GET", "https://cli-chat-proxy.grok.com/v1/billing?format=credits",
            mapOf("Authorization" to MARKER), null
        ) ?: return Result(error = "请求未发出")
        if (resp.status !in 200..299) return Result(unsupported = true)
        val root = runCatching { JSONObject(resp.body) }.getOrNull() ?: return Result()
        val used = root.optDouble("used_percent", Double.NaN)
        if (used.isNaN()) return Result(unsupported = true)
        return Result(windows = listOf(Window("额度", used, null)))
    }

    private fun kimi(entry: Credentials.Entry): Result {
        val hosts = listOf("https://api.kimi.com/coding/v1/usages", "https://api.kimi.ai/coding/v1/usages")
        for (host in hosts) {
            val resp = apiCall(entry.authIndex, "GET", host, mapOf("Authorization" to MARKER), null) ?: continue
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
        headers: Map<String, String>,
        body: String?
    ): ApiResponse? = runCatching {
        if (authIndex.isBlank()) return null
        val payload = JSONObject().apply {
            put("auth_index", authIndex)
            put("method", method)
            put("url", url)
            put("header", JSONObject(headers as Map<*, *>))
            if (body != null) put("data", body)
        }
        android.util.Log.i("CpaQuota", "payload=" + payload.toString().take(400))
        val text = request("POST", "/v8/management/requests/api-call", payload.toString())
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

    private fun request(method: String, path: String, body: String?): String? {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 30000
        conn.setRequestProperty("Authorization", "Bearer " + WebBridgeCompat.managementKey())
        conn.setRequestProperty("Accept", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // --------------------------------------------------------------- formatting

    private fun resetLabel(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        val parsed = runCatching { java.time.Instant.parse(iso) }.getOrNull() ?: return null
        val minutes = java.time.Duration.between(java.time.Instant.now(), parsed).toMinutes()
        return secondsLabel(minutes * 60)
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

    private fun secondsLabel(seconds: Long): String? {
        if (seconds <= 0) return null
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours >= 24 -> (hours / 24).toString() + " 天"
            hours >= 1 -> hours.toString() + " 小时"
            else -> minutes.toString() + " 分钟"
        }
    }

    private val CLAUDE_WINDOWS = listOf(
        "five_hour" to "5 小时",
        "seven_day" to "7 天",
        "seven_day_oauth_apps" to "7 天 OAuth",
        "seven_day_opus" to "7 天 Opus",
        "seven_day_sonnet" to "7 天 Sonnet"
    )

    private val CODEX_WINDOWS = listOf(
        "primary" to "主额度",
        "secondary" to "次额度"
    )
}
