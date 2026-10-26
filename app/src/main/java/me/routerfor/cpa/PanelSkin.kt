package me.routerfor.cpa

/**
 * Server-side skin for the management panel document.
 *
 * Two things in the shipped bundle read as visual bugs on a phone and cannot be fixed reliably
 * from the injected-script side (the SPA mounts asynchronously and re-renders its shell):
 *
 *  1. `.top-gradient-blur` — a fixed 114px grey gradient the desktop layout puts above the
 *     content area, anchored at `left:var(--sidebar-active-width)`. On a 394px-wide viewport that
 *     leaves a hard-edged grey slab across the top-right of the screen. It is decoration for the
 *     collapsed-sidebar state and means nothing on a phone.
 *  2. Box shadows and backdrop blurs on the floating header keep painting after a tap, which the
 *     user reads as a stuck shadow under the "三" button.
 *
 * Both are dropped here, in the document itself, so they are gone from the first paint instead of
 * arriving one script evaluation later. Also removes the Cloudflare RUM beacon, whose only effect
 * on a loopback engine is a 404 in the log every few seconds.
 */
object PanelSkin {

    /** Applies the skin (and drops the beacon) to an already-sanitised panel document. */
    fun inject(html: String, mode: Palette.Resolved, colors: Palette.Colors): String {
        var out = html
        out = BEACON.replace(out, "")
        out = addHeadStart(out, PanelTheme.htmlSeed(mode) + paletteStyle(colors))
        val at = out.lastIndexOf("</head>", ignoreCase = true)
        if (at < 0) return out
        return out.substring(0, at) + STYLE + "\n  " + out.substring(at)
    }

    /** Inserts markup immediately after the opening <head> tag. */
    private fun addHeadStart(html: String, markup: String): String {
        val head = html.indexOf("<head", ignoreCase = true)
        if (head < 0) return html
        val gt = html.indexOf('>', head)
        if (gt < 0) return html
        return html.substring(0, gt + 1) + markup + html.substring(gt + 1)
    }

    /**
     * Repaints the panel with the app palette.
     *
     * The panel ships exactly four themes (auto/white/light/dark) and none of them is the warm
     * paper surface the app offers, so seeding its store is not enough: on paper the app went
     * beige while the panel stayed white. Overriding its CSS custom properties is what actually
     * makes the two surfaces one app. It is also the only way to get the app's own accent in
     * there, since the bundle hardcodes a grey "primary-color".
     *
     * The selectors are attribute-qualified and !important so they win over both :root and
     * [data-theme=...] rules, whichever theme the panel decided to apply.
     */
    private fun paletteStyle(c: Palette.Colors): String {
        fun hex(v: Int): String = String.format("#%06X", 0xFFFFFF and v)
        val vars = listOf(
            "--bg-primary" to hex(c.surfaceCard),
            "--bg-secondary" to hex(c.surface),
            "--bg-tertiary" to hex(mix(c.surface, c.surfaceCard)),
            "--bg-quinary" to hex(c.surfaceCard),
            "--floating-surface" to hex(c.surfaceCard),
            "--text-primary" to hex(c.textPrimary),
            "--text-secondary" to hex(c.textSecondary),
            "--text-tertiary" to hex(c.textTertiary),
            "--text-quaternary" to hex(c.textTertiary),
            "--text-muted" to hex(c.textTertiary),
            "--border-color" to hex(c.divider),
            "--border-primary" to hex(c.stroke),
            "--border-secondary" to hex(c.divider),
            "--border-hover" to hex(c.stroke),
            "--primary-color" to hex(c.brand),
            "--primary-hover" to hex(c.brandDark),
            "--primary-active" to hex(c.brandDark),
            "--primary-contrast" to hex(c.onBrand),
            "--success-color" to hex(c.ok),
            "--viz-success" to hex(c.ok),
            "--warning-color" to hex(c.warn),
            "--destructive-color" to hex(c.err),
            "--viz-failure" to hex(c.err)
        )
        val body = vars.joinToString("") { (k, v) -> "$k:$v!important;" }
        val sel = "html,html[data-theme],html[data-theme=white],html[data-theme=dark]," +
            "html[data-theme=light],body"
        return "<style id=\"cpa-palette\">$sel{$body}" +
            "html,body{background:" + hex(c.surface) + "!important}</style>"
    }

    /** Midpoint of two colours, used for the panel's tertiary surface. */
    private fun mix(a: Int, b: Int): Int {
        fun ch(shift: Int): Int = (((a shr shift) and 0xFF) + ((b shr shift) and 0xFF)) / 2
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val BEACON = Regex(
        """\s*<script[^>]*src="https://static\.cloudflareinsights\.com/[^"]*"[^>]*>\s*</script>""",
        RegexOption.IGNORE_CASE
    )

    private const val STYLE = """<style id="cpa-skin">
    /* Desktop-only decorative gradient; on a phone it is a grey slab over the content. */
    .top-gradient-blur{display:none!important;opacity:0!important;background:none!important}
    /* Decorative shadows and glass blurs: they stroke after a tap and read as stuck shadows. */
    *,*::before,*::after{box-shadow:none!important;-webkit-box-shadow:none!important;
      backdrop-filter:none!important;-webkit-backdrop-filter:none!important}
    /* The WebView hides its own scrollbars; hide the document's too so nothing doubles up. */
    ::-webkit-scrollbar{width:0!important;height:0!important;display:none!important}
    html,body{overscroll-behavior:none;-webkit-tap-highlight-color:transparent}
    *{outline:none!important}
  </style>"""
}
