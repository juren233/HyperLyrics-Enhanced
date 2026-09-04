package com.juren233.hyperlyricsenhanced.root.island

import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport.TAG
import com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport.findFieldValue
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import kotlin.math.min

internal object IslandWidthHooker {

    /**
     * calculateBigIslandWidth 的歌词岛计算窗口标记。
     * 仅在该窗口内降低系统最小岛宽下限，避免影响通知、充电等原生岛。
     */
    @Volatile
    var lyricWidthCalculationActive: Boolean = false

    class CalculateWidthHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            runCatching {
                if (!IslandProbeUtils.isSuperIslandEnabled()) return@runCatching
                val contentView = chain.thisObject as? ViewGroup ?: return@runCatching
                val currentData = IslandProbeUtils.getCurrentIslandData(contentView)
                val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(currentData) ?: return@runCatching
                if (!IslandTextHookerSupport.isCurrentLyricIsland(mediaInfo)) return@runCatching
                if (!IslandTextHookerSupport.shouldRenderInjectedIsland()) {
                    IslandTextHookerSupport.clearInjectedIsland(contentView, suppressRelayout = true)
                    return@runCatching
                }

                if (IslandLyricTextInjector.restoreExistingSlotsLightweight(contentView)) {
                    IslandLyricTextInjector.refreshCurrentContent(contentView)
                } else {
                    IslandLyricTextInjector.injectSlots(contentView, reconfigureExisting = false)
                    IslandLyricTextInjector.refreshCurrentContent(contentView)
                }
                // proceed 内的测量规格与上次相同且子树无 FORCE_LAYOUT 标志时会整体短路，
                // 动态长度开启时必须先标记区域子树，换行后的新文字宽度才能进入岛宽计算。
                IslandViewHelper.forceLayoutIslandAreasIfDynamicWidth(contentView)
                lyricWidthCalculationActive = true
            }.onFailure { e ->
            HookLogger.e(TAG, "计算大岛宽度前准备歌词视图失败", e)
            }

            val result = chain.proceed()
            lyricWidthCalculationActive = false
            return result
        }
    }

    /**
     * 动态长度开启时，把系统最小岛宽下限降低到"岛高 + 小余量"。
     *
     * 系统原生下限（本机实测 330px ≈ pill 宽度）会让两侧都短的内容无法继续收缩，
     * 导致"该短的时候不短"。下限取岛高保证收窄后仍保持圆角胶囊形状、并完全
     * 覆盖摄像头挖孔。仅在歌词岛宽度计算窗口内生效。
     */
    class BigIslandMinWidthHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            return runCatching {
                if (!lyricWidthCalculationActive) return@runCatching result
                if (!IslandViewHelper.isDynamicWidthEnabled()) return@runCatching result
                val original = (result as? Number)?.toInt() ?: return@runCatching result
                val helper = chain.thisObject ?: return@runCatching result
                val height = helper.javaClass.methods.firstOrNull {
                    it.name == "getIslandViewHeight" && it.parameterTypes.isEmpty()
                }?.invoke(helper) as? Int ?: return@runCatching result
                if (height <= 0) return@runCatching result
                val margin = findFieldValue(helper, "context")?.let { context ->
                    runCatching {
                        val metrics = (context as android.content.Context).resources.displayMetrics
                        (2 * metrics.density).toInt()
                    }.getOrNull()
                } ?: 4
                val floor = height + margin
                val adjusted = min(original, floor)
                if (adjusted != original) {
                    HookLogger.d(TAG, "动态长度下限已调整: original=$original floor=$floor")
                }
                adjusted
            }.getOrDefault(result)
        }
    }
}
