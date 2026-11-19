/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/**
 * 「单侧长度」换算：文字上限 = 预算 − 该侧图标（左封面、右律动）当前被原生预留的宽度。
 *
 * 原生模块布局里图标与文字容器同在一个 FrameLayout，原生靠文字容器在图标一侧的外边距给图标留位置
 * （左起始、右末端，见 [IslandViewHelper.iconSideMarginPx]）；图标不占位时 [IslandHostFacade.applyHostSettings]
 * 会把该边距清零。因此直接读这个边距：图标尺寸随类型和封面样式变化，不使用常数；图标关闭时预留为 0，
 * 那一侧的文字自然更长。
 */
internal object IslandIconReservation {
    /** 预算为 [budgetPx] 时的文字上限，至少 1px。 */
    fun textLimitPx(budgetPx: Int, reservedPx: Int): Int =
        (budgetPx - reservedPx.coerceAtLeast(0)).coerceAtLeast(1)

    /** 图标此刻占位时的预留宽度；不占位为 0。[container] 为文字容器。 */
    fun reservedPx(container: View?, isLeft: Boolean, iconPlaced: Boolean): Int {
        if (!iconPlaced || container == null) return 0
        return IslandViewHelper.iconSideMarginPx(container, isLeft)
    }

    /** 按根视图下的模块名找到文字容器。 */
    fun containerOf(rootView: View, parentName: String): View? {
        val root = rootView as? ViewGroup ?: return null
        val parent = IslandViewHelper.findViewByName(root, parentName) as? ViewGroup ?: return null
        return IslandViewHelper.findViewByName(parent, IslandProbeUtils.TEXT_CONTAINER_NAME)
    }

    private val lastLogged = arrayOf("", "")

    /**
     * 仅调试版：记录换算输入与结果（只在变化时），同时给出容器两侧外边距，
     * 用于真机核对「右侧预留是否落在末端边距」。
     */
    fun debugLog(container: View?, isLeft: Boolean, iconPlaced: Boolean, budgetPx: Int, reservedPx: Int, limitPx: Int) {
        if (!BuildConfig.DEBUG) return
        val lp = container?.layoutParams as? ViewGroup.MarginLayoutParams
        val message = "side=${if (isLeft) "left" else "right"} placed=$iconPlaced budget=$budgetPx " +
            "reserved=$reservedPx limit=$limitPx marginStart=${lp?.marginStart} marginEnd=${lp?.marginEnd}"
        val index = if (isLeft) 0 else 1
        synchronized(lastLogged) {
            if (lastLogged[index] == message) return
            lastLogged[index] = message
        }
        HookLogger.d("IslandSideLength", message)
    }
}
