package me.routerfor.cpa

import android.content.Context
import android.content.SharedPreferences

/**
 * Every user-facing switch lives here so the engine-facing code (HotSoLoader, UpdateManager)
 * can read the same values the settings screen writes. "热更新默认关闭" is the shipped default:
 * the packaged kernel is the only one loaded until the user opts in.
 */
object AppPrefs {

    private const val FILE = "cpa-prefs"

    const val KEY_PROXY = "proxy-url"
    const val KEY_WORDS = "sensitive-words"
    const val KEY_LAN = "allow-lan"
    const val KEY_HOT_UPDATE = "hot-update-enabled"
    const val KEY_RULE_SYNC = "rule-sync-enabled"
    const val KEY_STATUS_QUOTA = "status-bar-quota"
    const val KEY_THINKING = "thinking-visible"
    const val KEY_WANTED = "gateway-wanted"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Whether the user wants the gateway running.
     *
     * Persisted because a stop has to survive a process restart: the foreground service is
     * START_STICKY, so without this the system would restart a gateway the user had
     * deliberately stopped. Defaults to true — a fresh install is expected to serve.
     */
    fun gatewayWanted(context: Context): Boolean = get(context).getBoolean(KEY_WANTED, true)

    fun setGatewayWanted(context: Context, wanted: Boolean) {
        get(context).edit().putBoolean(KEY_WANTED, wanted).apply()
    }

    fun proxyUrl(context: Context): String = get(context).getString(KEY_PROXY, "") ?: ""

    fun allowLan(context: Context): Boolean = get(context).getBoolean(KEY_LAN, false)

    fun sensitiveWords(context: Context): String =
        get(context).getString(KEY_WORDS, PanelJs.defaultSensitiveWordsCsv())
            ?: PanelJs.defaultSensitiveWordsCsv()

    /** Off by default: loading a downloaded .so is the single riskiest thing this app can do. */
    fun hotUpdateEnabled(context: Context): Boolean = get(context).getBoolean(KEY_HOT_UPDATE, false)

    fun setHotUpdateEnabled(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_HOT_UPDATE, enabled).apply()
    }

    fun ruleSyncEnabled(context: Context): Boolean = get(context).getBoolean(KEY_RULE_SYNC, false)

    fun setRuleSyncEnabled(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_RULE_SYNC, enabled).apply()
    }

    /**
     * Shows the per-account quota block in the expanded gateway notification.
     *
     * Off by default: account names on a lock screen are not something to opt someone into.
     */
    fun statusBarQuota(context: Context): Boolean = get(context).getBoolean(KEY_STATUS_QUOTA, false)

    fun setStatusBarQuota(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_STATUS_QUOTA, enabled).apply()
    }

    /**
     * How much of the reply budget the model may spend thinking before it answers.
     *
     * Defaults to compact. Measured on gemini-3.8-flash-high with the same prompt at
     * max_tokens=300 (SillyTavern's default): leaving thinking unconstrained returned 12-29
     * characters of reply, asking for visible reasoning returned 12-19, and capping the budget
     * at 0 returned 167-208. Showing the chain of thought costs the whole reply for a
     * short-budget client, so it is opt-in rather than the default.
     *
     * The stored value used to be a Boolean ("是否显示思维链"), so [thinkingMode] also accepts
     * "true"/"false" and maps them onto the new modes instead of losing the user's choice.
     */
    const val THINKING_COMPACT = "compact"
    const val THINKING_VISIBLE = "visible"
    const val THINKING_OFF = "off"

    fun thinkingMode(context: Context): String {
        val stored = get(context).getString(KEY_THINKING, THINKING_COMPACT) ?: THINKING_COMPACT
        return when (stored) {
            THINKING_COMPACT, THINKING_VISIBLE, THINKING_OFF -> stored
            "true" -> THINKING_VISIBLE
            "false" -> THINKING_OFF
            else -> THINKING_COMPACT
        }
    }

    fun setThinkingMode(context: Context, mode: String) {
        get(context).edit().putString(KEY_THINKING, mode).apply()
    }

    /**
     * One line describing what the current mode costs, shown under the settings row so the
     * choice is not a guess.
     *
     * The numbers come from measurement, not estimation: at max_tokens=300 the compact mode
     * returned 167-208 characters of reply, visible reasoning returned 12-19.
     */
    fun thinkingSummary(context: Context): String = when (thinkingMode(context)) {
        THINKING_VISIBLE -> "显示思考过程；预算低于约 1000 时正文会被挤短"
        THINKING_OFF -> "不写规则；模型仍会自行思考，只是不回传"
        else -> "不思考，正文最长（默认）"
    }
}
