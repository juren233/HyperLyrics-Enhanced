/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import android.os.Handler
import android.os.Looper
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference
import java.lang.reflect.Field

/**
 * 原生媒体岛控制器的观测与重建入口（**已停用、未接线**：全仓库无调用方，
 * installHook 与 scheduleNativeRebuild 均不会执行，留档防误复用）。
 *
 * 沿革：210139 曾设想守卫两刀否决后，发现容器脱离窗口时走
 * MiuiIslandMediaControllerImpl 的原生 ADD 重建装回容器。210140 真机证伪——
 * 同 key ADD 重建连续五次仍为幽灵态（「旧/新背景同一对象」前提已被撤回）；
 * 210143 定位多播掉岛真因为 ViewGroup 同线程重入 removeView 的索引错位，
 * 修复落在 [IslandReentrantBackgroundRemovalHooker]，本文件随之不再接线。
 *
 * 重启启用前必须：先拿到 DEBUGGING_MISTAKES.md「岛消失」条目要求的原生侧
 * 新证据；再按原始 DEX 核对目标字段可见性——`fieldOf` 只查 public 字段，
 * topMediaData/currentKey/uiHandler/miuiPlayerHolder 等若为 private 则静默
 * 找不到，不得凭反编译显示名假定可访问。
 */
internal object IslandMediaControllerObserver {
    private const val TAG = "IslandGuard"
    // 210140 真机中同 key ADD 重建连续五次仍无法重挂脱窗容器，停止无效重放。
    private const val REBUILD_ENABLED = false

    private const val CONTROLLER_CLASS =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaControllerImpl"

    @Volatile
    private var controllerRef: WeakReference<Any>? = null

    @Volatile
    private var lastRebuildKey: String? = null

    @Volatile
    private var lastRebuildAtMs: Long = 0L

    @Volatile
    private var consecutiveAttempts = 0

    /** 观测 addDynamicIslandView 调用，缓存控制器实例（主 APK 类，SystemUI 进程内）。 */
    class MediaAddObserverHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            controllerRef = WeakReference(chain.thisObject)
            return chain.proceed()
        }
    }

    fun installHook(module: io.github.libxposed.api.XposedModule, cl: ClassLoader) {
        val controllerClass = cl.loadClass(CONTROLLER_CLASS)
        val hooked = controllerClass.declaredMethods
            .filter { it.name == "addDynamicIslandView" && it.parameterTypes.size == 3 }
            .onEach { method ->
                method.isAccessible = true
                module.deoptimize(method)
                module.hook(method).intercept(MediaAddObserverHook())
                HookLogger.d(TAG, "已 Hook MiuiIslandMediaControllerImpl.addDynamicIslandView: $method")
            }
            .size
        if (hooked == 0) {
            HookLogger.w(TAG, "未找到 MiuiIslandMediaControllerImpl.addDynamicIslandView，重建恢复不可用")
        }
    }

    /**
     * 设计上的重建入口（当前未接线、且 REBUILD_ENABLED=false 双重停用）：
     * 供守卫否决后发现容器脱离窗口时调用，满足策略（在播歌词包 + 无近期同
     * key 合法移除 + 节流）则在控制器 uiHandler 上重放原生 ADD。
     */
    fun scheduleNativeRebuild(
        detachedIslandKey: String?,
        lastLegitRemoveKey: String?,
        lastLegitRemoveAtMs: Long,
    ) {
        if (!REBUILD_ENABLED) return
        val nowMs = System.currentTimeMillis()
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
        if (!IslandSelfHealGuardPolicy.shouldRecoverAfterDetach(
                detachedIslandKey = detachedIslandKey,
                lyricPackageName = lyricPkg,
                playbackActive = com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer.currentPlaybackActive(),
                lastLegitRemoveKey = lastLegitRemoveKey,
                lastLegitRemoveAtMs = lastLegitRemoveAtMs,
                nowMs = nowMs,
            )
        ) {
            return
        }
        if (!IslandSelfHealGuardPolicy.shouldAttemptRebuild(
                rebuildKey = detachedIslandKey,
                lastRebuildKey = lastRebuildKey,
                lastRebuildAtMs = lastRebuildAtMs,
                consecutiveAttempts = consecutiveAttempts,
                nowMs = nowMs,
            )
        ) {
            return
        }
        val controller = controllerRef?.get() ?: return
        if (detachedIslandKey != lastRebuildKey) {
            consecutiveAttempts = 0
        }
        lastRebuildKey = detachedIslandKey
        lastRebuildAtMs = nowMs
        consecutiveAttempts += 1
        val handler = readField(controller, "uiHandler") as? Handler ?: Handler(Looper.getMainLooper())
        handler.post {
            runRebuild(controller, detachedIslandKey)
        }
    }

    private fun runRebuild(controller: Any, islandKey: String?) {
        runCatching {
            val data = readField(controller, "topMediaData") ?: return
            val playing = readField(data, "isPlaying") as? Boolean ?: false
            val pkg = readField(data, "packageName") as? String
            val lyricPkg = LyriconDataBridge.currentLyricPackageName
            if (!playing || pkg != lyricPkg || pkg == null) {
                if (BuildConfig.DEBUG) {
                    HookLogger.d(TAG, "重建取消: topMediaData 非在播歌词包: pkg=$pkg, playing=$playing")
                }
                return
            }
            val holder = readField(controller, "miuiPlayerHolder") ?: return
            val dummyHolder = readField(controller, "miuiDummyPlayerHolder")
            val addMethod = controller.javaClass.methods
                .find { it.name == "addDynamicIslandView" && it.parameterTypes.size == 3 }
                ?: return
            // 重置 currentKey 使其走全新 ADD 路径（currentKey==null 分支），
            // 由原生替换状态表中的陈旧视图并重建窗口容器。
            setField(controller, "currentKey", null)
            addMethod.isAccessible = true
            addMethod.invoke(controller, holder, dummyHolder, data)
            if (BuildConfig.DEBUG) {
                HookLogger.d(TAG, "已触发原生媒体岛重建: key=$islandKey, pkg=$pkg, 尝试=$consecutiveAttempts")
            }
        }.onFailure { e ->
            HookLogger.e(TAG, "原生媒体岛重建失败: key=$islandKey", e)
        }
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        fieldOf(target, name)?.get(target)
    }.getOrNull()

    private fun setField(target: Any, name: String, value: Any?) {
        runCatching { fieldOf(target, name)?.set(target, value) }
    }

    // javaClass.fields 只含 public 字段（含继承）；启用前须按原始 DEX 核对各
    // 目标字段的可见性，private 字段在此永远解析不到。
    private fun fieldOf(target: Any, name: String): Field? =
        target.javaClass.fields.find { it.name == name }
}
