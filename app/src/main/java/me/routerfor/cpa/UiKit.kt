package me.routerfor.cpa

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Shared look for every screen so the three palettes and the control shapes stay in one place.
 *
 * The switch here is a hand-drawn pill rather than [com.google.android.material.materialswitch.MaterialSwitch]:
 * the M3 switch draws an outline + glow that the user read as "持续阴影", and it cannot do the
 * plain "grey when off, green when on, white knob on the right" shape that was asked for.
 */
class UiKit(private val context: Context, val colors: Palette.Colors) {

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Context themed for this palette, so dialogs stop falling back to M3 lavender. */
    fun dialogContext(): Context = androidx.appcompat.view.ContextThemeWrapper(
        context,
        when (colors.resolved) {
            Palette.Resolved.DAY -> R.style.ThemeOverlay_Cpa_Dialog_Day
            Palette.Resolved.NIGHT -> R.style.ThemeOverlay_Cpa_Dialog_Night
            Palette.Resolved.PAPER -> R.style.ThemeOverlay_Cpa_Dialog_Paper
        }
    )

    fun card(radius: Int = 20, bottomMargin: Int = 14): MaterialCardView = MaterialCardView(context).apply {
        this.radius = dp(radius).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = colors.stroke
        setCardBackgroundColor(colors.surfaceCard)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.bottomMargin = dp(bottomMargin) }
    }

    fun title(text: String, size: Float = 22f): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colors.textPrimary)
    }

    fun label(text: String, secondary: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(if (secondary) colors.textSecondary else colors.textPrimary)
    }

    fun sectionTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        letterSpacing = 0.04f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colors.textSecondary)
        setPadding(dp(4), dp(16), 0, dp(8))
    }

    fun divider(leftMargin: Int = 18): View = View(context).apply {
        setBackgroundColor(colors.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            this.leftMargin = dp(leftMargin)
        }
    }

    /**
     * One control shape for every action: the primary action is filled, everything else is an
     * outlined chip. The old home screen mixed a filled 管理面板 with outlined siblings, which read
     * as a different kind of control.
     */
    fun button(label: String, primary: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(
            context,
            null,
            if (primary) 0 else com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            cornerRadius = dp(14)
            minHeight = dp(48)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(14), dp(12), dp(14), dp(12))
            if (primary) {
                setBackgroundColor(colors.brand)
                setTextColor(colors.onBrand)
            } else {
                strokeColor = ColorStateList.valueOf(colors.stroke)
                setTextColor(colors.brand)
            }
            setOnClickListener { onClick() }
        }

    fun rowWeight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    fun gap(width: Int, height: Int = 0): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(width, height)
    }

    /** Circular icon button: flat, no ripple ring left behind after a tap. */
    fun iconButton(resId: Int, description: String, size: Int = 40, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(resId)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(colors.textSecondary))
            background = null
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }

    /**
     * The classic switch asked for: track grey when off / green when on, round white knob,
     * knob sits on the right when on. Fully custom so it looks identical in all three palettes.
     */
    class ClassicSwitch(context: Context, private val colors: Palette.Colors) : View(context) {

        var onCheckedChange: ((Boolean) -> Unit)? = null

        var isChecked: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                animate().cancel()
                animate().scaleX(1.06f).scaleY(1.06f).setDuration(90).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                }.start()
                invalidate()
            }

        private val track = GradientDrawable()
        private val knob = GradientDrawable()

        init {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                isChecked = !isChecked
                onCheckedChange?.invoke(isChecked)
                invalidate()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = (TRACK_WIDTH_DP * resources.displayMetrics.density).toInt()
            val h = (TRACK_HEIGHT_DP * resources.displayMetrics.density).toInt()
            setMeasuredDimension(
                resolveSize(w + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(h + paddingTop + paddingBottom, heightMeasureSpec)
            )
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val d = resources.displayMetrics.density
            val w = TRACK_WIDTH_DP * d
            val h = TRACK_HEIGHT_DP * d
            val trackColor = if (isChecked) colors.ok else colors.switchOff
            track.setColor(trackColor)
            track.cornerRadius = h / 2f
            track.setBounds(0, 0, w.toInt(), h.toInt())
            track.draw(canvas)

            val inset = 2.5f * d
            val radius = h / 2f - inset
            val cx = if (isChecked) w - h / 2f else h / 2f
            knob.setColor(0xFFFFFFFF.toInt())
            knob.setBounds(
                (cx - radius).toInt(), (h / 2f - radius).toInt(),
                (cx + radius).toInt(), (h / 2f + radius).toInt()
            )
            knob.cornerRadius = radius
            knob.draw(canvas)
        }

        companion object {
            private const val TRACK_WIDTH_DP = 52f
            private const val TRACK_HEIGHT_DP = 32f
        }
    }
}