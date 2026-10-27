package me.routerfor.cpa

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A ScrollView that stops growing past a given height.
 *
 * The account card lists every provider and every credential, so its natural height is unbounded:
 * with four providers the quick-action buttons ended up below the fold and the card looked like
 * the whole screen. Clamping it here keeps the card a fixed part of the layout while its contents
 * stay reachable by scrolling inside it.
 *
 * Implemented by rewriting the incoming height spec rather than by trimming the child, because a
 * clipped child would still report its full height to the parent and push everything down.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** Cap in pixels; [Int.MAX_VALUE] means "no cap", the platform default. */
    var maxHeight: Int = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = maxHeight
        if (cap == Int.MAX_VALUE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val given = MeasureSpec.getSize(heightMeasureSpec)
        // Only tighten: an exact parent height (match_parent) or a smaller bound wins.
        if (mode == MeasureSpec.UNSPECIFIED || given > cap) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
