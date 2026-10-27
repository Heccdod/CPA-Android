package me.routerfor.cpa

/**
 * Removes third-party relay-station promotion from the management panel before the WebView
 * renders it.
 *
 * The shipped panel bundle carries sponsored provider entries — "APIKEY.FUN" behind a dedicated
 * `/quick-start` page, plus "FennoAI" — each with an affiliate link, a dashboard link and
 * hard-coded relay endpoints wired into the provider form as a convenience preset. This app is a
 * personal loopback gateway, so that content is dead weight at best and an affiliate funnel at
 * worst. The panel is re-downloaded from the upstream release on every engine start, so patching
 * the file on disk does not survive; it is rewritten here, in `shouldInterceptRequest`.
 *
 * Two lessons from the first cut of this file, both learned the hard way when upstream shipped a
 * new bundle:
 *
 * 1. **Never key a rule off a minified identifier.** The first version replaced literals such as
 *    `cS=https://apikey.fan/register?aff=AKCPA`. The next upstream build renamed every one of
 *    them (`cS` became `GS`), added a second sponsor, and swapped the protocol schemas — so the
 *    sanitiser matched nothing at all while still reporting success. Every rule below keys off a
 *    domain, a display string, or an array member, all of which survive a rebuild.
 * 2. **Repoint the route, do not delete it.** Cutting the `/quick-start` router entry would mean
 *    surgery on a minified array whose commas have to stay balanced — a syntax error there blanks
 *    the whole panel. Pointing `fixedBrand` at a stock provider leaves the route alive, valid,
 *    and harmless.
 */
object PanelSanitizer {

    /** Every appearance of these hosts is a relay-station funnel, so the whole URL goes. */
    private val SPONSOR_HOST =
        Regex("""https?://[A-Za-z0-9.-]*(?:apikey\.fan|fenno\.ai)[^\s`"')]*""")

    /** Referral tags riding on otherwise legitimate provider links. */
    private val AFFILIATE_QUERY = Regex("""[?&]aff=[A-Za-z0-9_-]+""")

    /**
     * Sponsor brands in the provider array. The backticks are part of the literal: removing a
     * bare `apikeyFun` would also hit the descriptor keys and the `fixedBrand` value, leaving
     * the array valid but the brand still reachable from the picker.
     */
    private val SPONSOR_ARRAY_ENTRIES =
        listOf(",`apikeyFun`", ",`fennoAI`")

    /** Display strings, in all seven languages the panel ships. */
    private val REPLACEMENTS: List<Pair<String, String>> = listOf(
        // Keep the route, lose the brand: an OpenAI-compatible provider form is a useful page.
        "fixedBrand:`apikeyFun`" to "fixedBrand:`openaiCompatibility`",
        "APIKEY.FUN setup" to "快速配置",
        "APIKEY.FUN 快速設定" to "快速配置",
        "APIKEY.FUN 快速设定" to "快速配置",
        "Настройка APIKEY.FUN" to "Быстрая настройка",
        "Thiết lập APIKEY.FUN" to "Thiết lập nhanh",
        "APIKEY.FUN 설정" to "빠른 설정",
        "APIKEY.FUN" to "API 提供商",
        "FennoAI" to "API 提供商"
    )

    /** Returns the panel HTML with relay-station promotion removed. */
    fun strip(html: String): String {
        var out = SPONSOR_HOST.replace(html, "")
        out = AFFILIATE_QUERY.replace(out, "")
        for (entry in SPONSOR_ARRAY_ENTRIES) out = out.replace(entry, "")
        for ((from, to) in REPLACEMENTS) {
            if (from.isNotEmpty()) out = out.replace(from, to)
        }
        return out
    }

    /**
     * True when the document still advertises a third-party relay station.
     *
     * Deliberately broader than [strip]: it also looks for the referral tags, so a rule that
     * blanks a host but leaves its `aff` parameter behind still reports as a failure.
     */
    fun hasSponsorContent(html: String): Boolean =
        html.contains("apikey.fan") ||
            html.contains("fenno.ai") ||
            html.contains("APIKEY.FUN") ||
            html.contains("FennoAI") ||
            html.contains("aff=AKCPA") ||
            html.contains("aff=DQFAMNB6CBLY")
}
