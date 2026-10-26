package me.routerfor.cpa

/**
 * Keeps the management panel's own theme store in step with the app palette.
 *
 * The panel is a standalone SPA with its own zustand store persisted under
 * localStorage "cli-proxy-theme" (plain JSON, no obfuscation — unlike the auth store).
 * It offers auto / white / light / dark, where "white" is its warm paper variant. Nothing
 * in the app ever wrote it, so the panel always booted on "auto" and followed the system
 * instead of the palette the user picked, which is exactly the mismatch that shows up as
 * "面板与主页面不同步".
 *
 * Mapping:
 *   Palette DAY / SYSTEM-light -> light
 *   Palette NIGHT              -> dark
 *   Palette PAPER              -> white   (the panel's warm paper surface)
 */
object PanelTheme {

    private const val KEY = "cli-proxy-theme"

    /** The zustand persisted shape: { state: { theme, resolvedTheme }, version: 0 }. */
    fun payload(mode: Palette.Resolved): String {
        val theme = when (mode) {
            Palette.Resolved.NIGHT -> "dark"
            Palette.Resolved.PAPER -> "white"
            Palette.Resolved.DAY -> "light"
        }
        val resolved = if (mode == Palette.Resolved.NIGHT) "dark" else "light"
        return "{\"state\":{\"theme\":\"" + theme + "\",\"resolvedTheme\":\"" +
            resolved + "\"},\"version\":0}"
    }

    /**
     * Script to run in the page. Writes the store only when the value changed, because
     * rewriting it on every page load would fight a user who changed the theme inside
     * the panel itself.
     */
    fun script(mode: Palette.Resolved): String {
        val value = payload(mode)
        return "(function(){try{" +
            "var v=" + quote(value) + ";" +
            "if(localStorage.getItem(" + quote(KEY) + ")!==v){" +
            "localStorage.setItem(" + quote(KEY) + ",v);}" +
            "}catch(e){console.warn('cpa theme seed failed',e);}})();"
    }

    /** Inline script for the document head: applies the theme before the SPA boots. */
    fun htmlSeed(mode: Palette.Resolved): String =
        "<script id=\"cpa-panel-theme\">" + script(mode) + "</script>"

    private const val DQ = "\""

    private fun quote(value: String): String =
        DQ + value.replace("\\", "\\\\").replace(DQ, "\\" + DQ) + DQ
}
