/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

/**
 * 超级岛自愈误杀守卫 Hook（见 [IslandSelfHealGuardPolicy] 的根因背景）。
 *
 * checkError 自愈有**两刀**，都要拦（2026-09-29 09:30 真机实证：只拦第一刀时
 * 岛仍被拆）：
 *  1. `DynamicIslandEventCoordinator.dispatchEvent(Deleted, view)` 强制删除事件；
 *  2. `DynamicIslandWindowView.clearAfterDelete(data, key, z)` 直接拆除窗口岛
 *     （checkError 在 dispatchEvent 之后立即直调，不经事件派发）。
 *
 * 合法移除标记入口：`DynamicIslandWindowViewController.removeDynamicIslandView
 * (String, boolean)`——用户划掉、媒体控制器 remove、配置重建等一切合法移除的
 * 必经入口，先于两刀执行。deskclock 等其他包的岛删除不受守卫影响。
 */
internal object IslandSelfHealGuardHooker {
    private const val TAG = "IslandGuard"

    /** 止损开关：守卫若引发异常行为，置 false 即回到纯原生自愈语义。 */
    private const val ENABLED = true

    @Volatile
    private var lastLegitRemoveKey: String? = null

    @Volatile
    private var lastLegitRemoveAtMs: Long = 0L

    class RemoveDynamicIslandViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            (chain.args.getOrNull(0) as? String)?.let { key ->
                lastLegitRemoveKey = key
                lastLegitRemoveAtMs = System.currentTimeMillis()
            }
            return chain.proceed()
        }
    }

    class DispatchEventHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            if (!ENABLED) return chain.proceed()
            val event = chain.args.getOrNull(0) ?: return chain.proceed()
            if (event.javaClass.simpleName != "DeletedDynamicIsland") return chain.proceed()
            val view = chain.args.getOrNull(1) ?: return chain.proceed()
            val islandKey = IslandProbeUtils.getCurrentIslandData(view).readIslandKey()
            return if (guardShouldVeto(islandKey)) {
                logVeto("强制删除事件", islandKey)
                null
            } else {
                chain.proceed()
            }
        }

        private fun Any?.readIslandKey(): String? = runCatching {
            this?.javaClass?.methods
                ?.find { it.name == "getKey" && it.parameterTypes.isEmpty() }
                ?.invoke(this) as? String
        }.getOrNull()
    }

    class ClearAfterDeleteHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            if (!ENABLED) return chain.proceed()
            val islandKey = chain.args.getOrNull(1) as? String
            return if (guardShouldVeto(islandKey)) {
                logVeto("窗口拆除", islandKey)
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun guardShouldVeto(islandKey: String?): Boolean =
        IslandSelfHealGuardPolicy.shouldVetoForceDelete(
            flaggedIslandKey = islandKey,
            lyricPackageName = LyriconDataBridge.currentLyricPackageName,
            playbackActive = BaseIslandRenderer.currentPlaybackActive(),
            lastLegitRemoveKey = lastLegitRemoveKey,
            lastLegitRemoveAtMs = lastLegitRemoveAtMs,
            nowMs = System.currentTimeMillis(),
        )

    private fun logVeto(action: String, islandKey: String?) {
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                TAG,
                "已否决自愈$action: key=$islandKey, lyricPkg=${LyriconDataBridge.currentLyricPackageName}",
            )
        }
    }
}
