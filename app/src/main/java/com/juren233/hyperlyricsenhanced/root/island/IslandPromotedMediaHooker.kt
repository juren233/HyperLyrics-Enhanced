/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import android.service.notification.StatusBarNotification
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.Collections
import java.util.WeakHashMap

/**
 * PromotedNotificationParamUtils.isNotificationPromotedOngoing 的放行 Hook。
 *
 * 原生判定为 false 且通知属于「正在播的歌词包的媒体通知」时返回 true，其余场景
 * 保持原值。见 [IslandPromotedMediaGatePolicy] 的断供背景与放行语义。
 */
internal object IslandPromotedMediaHooker {
    private const val TAG = "IslandPromote"

    /**
     * 总开关：放行使媒体通知进入原生 promoted 模板管线后，关岛动画在
     * IslandIconViewHolder.playAnimation 稳定 NPE 崩溃系统界面（2026-09-29
     * 07:34 真机，崩溃循环两轮）。原生 promoted 模板的图标动画资源链对
     * 非原生标记的媒体通知不完整，此路不通，已在 DEBUGGING_MISTAKES.md 立案。
     * 关闭时安装侧也必须跳过（见 IslandTextHooker）：不留 hook 与 deoptimize
     * 残迹，SystemUI 对停用功能零足迹。
     */
    internal const val ENABLED = false

    private val debugLoggedKeys: MutableSet<String> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    class PromotedOngoingGateHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            if (!ENABLED) return chain.proceed()
            val result = chain.proceed()
            if (result == java.lang.Boolean.TRUE) return result
            val sbn = chain.args.getOrNull(0) as? StatusBarNotification ?: return result
            // Notification.isMediaNotification() 是 hidden API；媒体通知必带
            // MediaSession token（EXTRA_MEDIA_SESSION），以此等价判定。
            val isMedia = runCatching {
                sbn.notification.extras?.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) == true
            }.getOrDefault(false)
            val gate = IslandPromotedMediaGatePolicy.shouldPromote(
                sbnIsMediaNotification = isMedia,
                sbnPackageName = sbn.packageName,
                lyricPackageName = LyriconDataBridge.currentLyricPackageName,
                playbackActive = BaseIslandRenderer.currentPlaybackActive(),
            )
            if (!gate) return result
            if (BuildConfig.DEBUG && debugLoggedKeys.add(sbn.key)) {
                HookLogger.d(
                    TAG,
                    "断供放行: 媒体通知进入焦点管线: pkg=${sbn.packageName}, key=${sbn.key}",
                )
            }
            return java.lang.Boolean.TRUE
        }
    }
}
