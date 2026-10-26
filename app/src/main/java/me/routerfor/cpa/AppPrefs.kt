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

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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
}
