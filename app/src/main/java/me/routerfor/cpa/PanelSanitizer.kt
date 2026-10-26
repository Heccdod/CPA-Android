package me.routerfor.cpa

/**
 * Removes third-party relay-station promotion from the management panel before the WebView
 * renders it.
 *
 * The shipped panel bundle carries a sponsored "APIKEY.FUN" quick-start page: an affiliate
 * link, a dashboard link and three hard-coded relay endpoints (api./slb. apikey.fan), wired
 * into the provider form as a convenience preset. This app is a personal loopback gateway, so
 * that content is dead weight at best and an affiliate funnel at worst. It is stripped here
 * instead of forking the panel, because the panel is re-downloaded from the upstream release
 * whenever the engine starts and an in-place patch would not survive.
 *
 * Every substitution is a no-op if upstream renames a page, so a panel update degrades to
 * "the promotion is back" rather than "the panel is broken".
 */
object PanelSanitizer {

    /** Returns the panel HTML with the relay-station promotion removed. */
    fun strip(html: String): String {
        var out = html
        for ((from, to) in REPLACEMENTS) {
            if (from.isNotEmpty()) out = out.replace(from, to)
        }
        return out
    }

    /** True when the document still advertises a third-party relay station. */
    fun hasSponsorContent(html: String): Boolean =
        html.contains("apikey.fan") || html.contains("APIKEY")

    private val REPLACEMENTS: List<Pair<String, String>> = listOf(
        // Drop the sponsored /quick-start route plus its catch-all redirect.
        "{path:`/quick-start`,element:(0,V.jsx)(XE,{fixedBrand:`apikeyFun`})},{path:`/quick-start/*`,element:(0,V.jsx)(ni,{to:`/quick-start`,replace:!0})}," to "",
        // The sidebar only appends that entry through this conditional.
        "...pe?[]:[me]" to "...[]",
        // Blank the affiliate / dashboard / relay endpoints so nothing can dial out.
        "cS=`https://apikey.fan/register?aff=AKCPA`" to "cS=``",
        "lS=`https://apikey.fan/dashboard`" to "lS=``",
        "uS=`https://api.apikey.fan`" to "uS=``",
        "dS=`https://slb.apikey.fan`" to "dS=``",
        "?.baseUrl??`https://api.apikey.fan`" to "?.baseUrl??``",
        // Re-label every remaining mention in place.
        "APIKEY.FUN setup" to "快速配置",
        "APIKEY.FUN 快速設定" to "快速配置",
        "APIKEY.FUN 快速设定" to "快速配置",
        "APIKEY.FUN 설정" to "빠른 설정",
        "Настройка APIKEY.FUN" to "Быстрая настройка",
        "Thiết lập APIKEY.FUN" to "Thiết lập nhanh",
        "APIKEY.FUN" to "API 提供商"
    )
}
