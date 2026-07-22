package com.juren233.hyperlyricsenhanced.root.island

import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport.TAG
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

internal object IslandWidthHooker {

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
            }.onFailure { e ->
            HookLogger.e(TAG, "计算大岛宽度前准备歌词视图失败", e)
            }

            return chain.proceed()
        }
    }
}
