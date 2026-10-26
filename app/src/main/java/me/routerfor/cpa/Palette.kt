package me.routerfor.cpa

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * Three palettes — day, night, paper — plus "follow system".
 *
 * Android has no "paper" resource qualifier, and "follow system" resolves at runtime anyway, so
 * the palettes are flat named colours picked here instead of through values-night. Every screen
 * reads its colours from [Palette.load]; nothing hardcodes R.color.<name> any more, which is what
 * previously made a second theme impossible.
 */
object Palette {

    /** What the user picked. */
    enum class Mode(val id: String) {
        SYSTEM("system"),
        DAY("day"),
        NIGHT("night"),
        PAPER("paper");

        companion object {
            fun from(id: String?): Mode = entries.firstOrNull { it.id == id } ?: SYSTEM
        }
    }

    /** What is actually drawn right now; SYSTEM collapses to DAY or NIGHT. */
    enum class Resolved { DAY, NIGHT, PAPER }

    data class Colors(
        val resolved: Resolved,
        val brand: Int,
        val brandDark: Int,
        val surface: Int,
        val surfaceCard: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textTertiary: Int,
        val stroke: Int,
        val divider: Int,
        val ok: Int,
        val warn: Int,
        val err: Int,
        val switchOff: Int,
        val onBrand: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int
    ) {
        /** The status bar needs dark icons on a light palette and light icons on a dark one. */
        val lightStatusBar: Boolean get() = resolved != Resolved.NIGHT
    }

    fun resolve(mode: Mode, context: Context): Resolved = when (mode) {
        Mode.DAY -> Resolved.DAY
        Mode.NIGHT -> Resolved.NIGHT
        Mode.PAPER -> Resolved.PAPER
        Mode.SYSTEM -> {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            if (night) Resolved.NIGHT else Resolved.DAY
        }
    }

    fun load(context: Context, resolved: Resolved = resolve(ThemeManager.mode(context), context)): Colors {
        // Resolve to ARGB here. Storing R.color ids would hand setBackgroundColor() a small
        // integer it reads as a translucent ARGB value (0x7f05xxxx), which tinted every screen.
        fun c(id: Int): Int = androidx.core.content.ContextCompat.getColor(context, id)
        return when (resolved) {
            Resolved.DAY -> Colors(
                resolved = resolved,
                brand = c(R.color.day_brand),
                brandDark = c(R.color.day_brand_dark),
                surface = c(R.color.day_surface),
                surfaceCard = c(R.color.day_surface_card),
                textPrimary = c(R.color.day_text_primary),
                textSecondary = c(R.color.day_text_secondary),
                textTertiary = c(R.color.day_text_tertiary),
                stroke = c(R.color.day_stroke),
                divider = c(R.color.day_divider),
                ok = c(R.color.day_ok),
                warn = c(R.color.day_warn),
                err = c(R.color.day_err),
                switchOff = c(R.color.day_switch_off),
                onBrand = c(R.color.day_on_brand),
                primaryContainer = c(R.color.day_primary_container),
                onPrimaryContainer = c(R.color.day_on_primary_container)
            )
            Resolved.NIGHT -> Colors(
                resolved = resolved,
                brand = c(R.color.night_brand),
                brandDark = c(R.color.night_brand_dark),
                surface = c(R.color.night_surface),
                surfaceCard = c(R.color.night_surface_card),
                textPrimary = c(R.color.night_text_primary),
                textSecondary = c(R.color.night_text_secondary),
                textTertiary = c(R.color.night_text_tertiary),
                stroke = c(R.color.night_stroke),
                divider = c(R.color.night_divider),
                ok = c(R.color.night_ok),
                warn = c(R.color.night_warn),
                err = c(R.color.night_err),
                switchOff = c(R.color.night_switch_off),
                onBrand = c(R.color.night_on_brand),
                primaryContainer = c(R.color.night_primary_container),
                onPrimaryContainer = c(R.color.night_on_primary_container)
            )
            Resolved.PAPER -> Colors(
                resolved = resolved,
                brand = c(R.color.paper_brand),
                brandDark = c(R.color.paper_brand_dark),
                surface = c(R.color.paper_surface),
                surfaceCard = c(R.color.paper_surface_card),
                textPrimary = c(R.color.paper_text_primary),
                textSecondary = c(R.color.paper_text_secondary),
                textTertiary = c(R.color.paper_text_tertiary),
                stroke = c(R.color.paper_stroke),
                divider = c(R.color.paper_divider),
                ok = c(R.color.paper_ok),
                warn = c(R.color.paper_warn),
                err = c(R.color.paper_err),
                switchOff = c(R.color.paper_switch_off),
                onBrand = c(R.color.paper_on_brand),
                primaryContainer = c(R.color.paper_primary_container),
                onPrimaryContainer = c(R.color.paper_on_primary_container)
            )
        }
    }
}

/** Persists the theme choice. */
object ThemeManager {

    private const val FILE = "cpa-theme"
    private const val KEY_MODE = "theme-mode"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(context: Context): Palette.Mode =
        Palette.Mode.from(prefs(context).getString(KEY_MODE, Palette.Mode.SYSTEM.id))

    fun setMode(context: Context, mode: Palette.Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.id).apply()
    }

    fun colors(context: Context): Palette.Colors = Palette.load(context)

    fun isDark(context: Context): Boolean =
        Palette.resolve(mode(context), context) == Palette.Resolved.NIGHT

    /**
     * Paints the system bars with the current palette.
     *
     * Leaving statusBarColor transparent only works if something behind it is drawn: the window
     * background is the theme's, not ours, so a transparent bar showed the stock white strip above
     * a themed page (and white-on-white icons in the night theme). The bar is now filled with the
     * palette surface and the icon tint follows [Palette.Colors.lightStatusBar].
     */
    fun applySystemBars(activity: android.app.Activity, colors: Palette.Colors) {
        val window = activity.window
        window.statusBarColor = colors.surface
        window.navigationBarColor = colors.surface
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility
        flags = flags or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        flags = if (colors.lightStatusBar) {
            flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            flags = if (colors.lightStatusBar) {
                flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
        @Suppress("DEPRECATION")
        run { window.decorView.systemUiVisibility = flags }
    }

    /**
     * The top-bar button cycles 日间 → 黑夜 → 羊皮纸 → 跟随系统. When the mode is SYSTEM the
     * button shows whichever glyph the OS is currently using, and MainActivity listens for
     * uiMode changes so the glyph follows a live system switch.
     */
    fun next(mode: Palette.Mode): Palette.Mode = when (mode) {
        Palette.Mode.DAY -> Palette.Mode.NIGHT
        Palette.Mode.NIGHT -> Palette.Mode.PAPER
        Palette.Mode.PAPER -> Palette.Mode.SYSTEM
        Palette.Mode.SYSTEM -> Palette.Mode.DAY
    }
}