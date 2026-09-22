package com.juren233.hyperlyricsenhanced.root.island.view

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.BuildConfig

/**
 * 限制最大测量宽度的 FrameLayout 容器。
 * 取代了原先以匿名类实现的测量逻辑，规避了 Xposed 对 maxWidthPx 的反射调用。
 */
class MaxWidthFrameLayout(context: Context) : FrameLayout(context) {

    /**
     * 最大宽度（像素）。设置为 -1（默认）则不限制。
     */
    var maxWidthPx: Int = -1

    /**
     * Used only by injected Super Island test blocks.
     */
    var keepVisible: Boolean = false

    /** Last parent constraints, populated only for Debug geometry snapshots. */
    internal var diagnosticWidthSpec: Int? = null
        private set
    internal var diagnosticHeightSpec: Int? = null
        private set

    override fun setVisibility(visibility: Int) {
        if (keepVisible && visibility != View.VISIBLE) {
            super.setVisibility(View.VISIBLE)
            return
        }
        super.setVisibility(visibility)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (BuildConfig.DEBUG) {
            diagnosticWidthSpec = widthMeasureSpec
            diagnosticHeightSpec = heightMeasureSpec
        }
        val givenWidth = MeasureSpec.getSize(widthMeasureSpec)
        // AT_MOST 0 is a real limit (for example no room beside status-bar icons).
        // Only UNSPECIFIED means that the parent did not provide a width bound.
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val newWidth = if (maxWidthPx > 0 && (unbounded || givenWidth > maxWidthPx)) maxWidthPx else givenWidth
        super.onMeasure(MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.AT_MOST), heightMeasureSpec)
        if (maxWidthPx > 0 && measuredWidth > maxWidthPx) {
            setMeasuredDimension(maxWidthPx, measuredHeight)
        }
    }
}
