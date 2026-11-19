/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.content.res.Configuration
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/**
 * 超级岛当前是否横屏，供「横屏状态」歌词模式取用。
 *
 * 取岛视图自己的 Resources 方向，与原生 `ConfigUtils.isLandscape(getView().getContext())` 同一来源
 * （miui.systemui.plugin 18.3.2.67.0 反编译）。SystemUI Application 的 ComponentCallbacks 在旋转时
 * 不回调（250011 真机：多次 ROTATION_0/90 切换，一次都没收到），不能作为来源。
 * 旋转后原生必然重算大岛宽度，由 `calculateBigIslandWidth` 的 Hook 在注入前调用 [observe]，
 * 本次计算即按新方向的模式注入。
 */
internal object IslandOrientationState {
    private const val TAG = "IslandOrientation"

    @Volatile
    var isLandscape: Boolean = false
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    fun start(onChanged: () -> Unit) {
        this.onChanged = onChanged
    }

    fun stop() {
        onChanged = null
    }

    /** 按岛视图的方向更新；变化时让运行时配置失效并通知。返回是否变化。 */
    fun observe(islandView: View): Boolean {
        val next = islandView.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (next == isLandscape) return false
        isLandscape = next
        if (BuildConfig.DEBUG) HookLogger.d(TAG, "方向变化: landscape=$next")
        IslandSlotRuntimeConfig.invalidate()
        onChanged?.invoke()
        return true
    }
}
