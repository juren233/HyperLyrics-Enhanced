package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport.TAG
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

internal object IslandModuleRestoreHooker {

    class AdapterUpdateViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                if (!IslandProbeUtils.isSuperIslandEnabled()) return@runCatching
                val moduleType = chain.args.getOrNull(0) as? String
                val data = chain.args.getOrNull(2)
                val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(data) ?: return@runCatching

                if (!IslandTextHookerSupport.isCurrentLyricIsland(mediaInfo)) return@runCatching
                val holderRoot = IslandProbeUtils.getHolderRootView(
                    IslandProbeUtils.getHolder(chain.thisObject, moduleType)
                )
                if (!IslandTextHookerSupport.shouldRenderInjectedIsland()) {
                    holderRoot?.let { IslandHostFacade.clearInjectedViews(it) }
                    return@runCatching
                }

                IslandTextHookerSupport.restoreAdapterModule(chain.thisObject, moduleType, "adapter.updateView")
            }.onFailure { e ->
                HookLogger.e(TAG, "adapter.updateView 后恢复歌词视图失败", e)
            }

            return result
        }
    }

    class UpdateModuleViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                if (!IslandProbeUtils.isSuperIslandEnabled()) return@runCatching
                val moduleType = chain.args.getOrNull(0) as? String
                val data = chain.args.getOrNull(2)
                val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(data) ?: return@runCatching

                if (!IslandTextHookerSupport.isCurrentLyricIsland(mediaInfo)) return@runCatching
                val adapter = IslandTextHookerSupport.findFieldValue(chain.thisObject, "islandAdapter")
                val holderRoot = IslandProbeUtils.getHolderRootView(
                    IslandProbeUtils.getHolder(adapter, moduleType)
                )
                if (!IslandTextHookerSupport.shouldRenderInjectedIsland()) {
                    holderRoot?.let { IslandHostFacade.clearInjectedViews(it) }
                    return@runCatching
                }

                IslandTextHookerSupport.restoreAdapterModule(adapter, moduleType, "updateModuleView")
            }.onFailure { e ->
                HookLogger.e(TAG, "updateModuleView 后恢复歌词视图失败", e)
            }

            return result
        }
    }
}
