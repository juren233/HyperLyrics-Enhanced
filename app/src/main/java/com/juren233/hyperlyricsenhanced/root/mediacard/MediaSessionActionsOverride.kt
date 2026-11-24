/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

import android.app.StatusBarManager
import android.os.UserHandle
import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 让指定 App 的媒体卡片按钮改用会话 PlaybackState 的标准动作。
 *
 * Android 13 起 SystemUI 用 StatusBarManager.useMediaSessionActionsForApp 决定媒体卡片按钮来源：
 * 兼容变更 MEDIA_CONTROL_SESSION_ACTIONS（203800354）只对 targetSdk ≥ 33 的 App 生效，
 * 其余 App 沿用通知 actions 和它们自带的图标。
 *
 * QQ 音乐简洁版（com.tencent.freemusic 1.0.1）targetSdk 30，通知 actions 用的是 App 自绘图标；
 * 它的 QQFreeMusicSession 会话 PlaybackState 声明了 823 =
 * STOP|PAUSE|PLAY|SKIP_TO_PREVIOUS|SKIP_TO_NEXT|SEEK_TO|PLAY_PAUSE，会话回调也都接到了播放器，
 * 所以对它改走会话动作，卡片就显示系统标准的上一首/播放暂停/下一首。
 * 只放行这里列出的包：会话没声明动作的老 App 强开会话动作会丢按钮。
 */
internal object MediaSessionActionsOverride {
    private const val TAG = "MediaSessionActions"
    private const val TARGET_METHOD = "useMediaSessionActionsForApp"

    internal val PACKAGES: Set<String> = setOf("com.tencent.freemusic")

    private val loggedFirstHit = AtomicBoolean(false)
    private val loggedOverride = AtomicBoolean(false)

    fun install(module: XposedModule) {
        val method = StatusBarManager::class.java.getDeclaredMethod(
            TARGET_METHOD,
            String::class.java,
            UserHandle::class.java,
        )
        module.deoptimize(method)
        module.hook(method).intercept(OverrideHooker())
        HookLogger.i(TAG, "媒体卡片会话按钮 Hook 已安装: packages=$PACKAGES")
    }

    internal fun shouldForce(packageName: String?, original: Boolean): Boolean =
        !original && packageName != null && packageName in PACKAGES

    class OverrideHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (SystemUiHookLifetime.retired) return result
            val packageName = chain.args.firstOrNull() as? String
            if (loggedFirstHit.compareAndSet(false, true)) {
                HookLogger.i(TAG, "媒体卡片会话按钮 Hook 首次命中: package=$packageName original=$result")
            }
            if (!shouldForce(packageName, result == true)) return result
            if (loggedOverride.compareAndSet(false, true)) {
                HookLogger.i(TAG, "媒体卡片按钮改用会话动作: package=$packageName")
            }
            return true
        }
    }
}
