/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 双播冲突窗口的媒体岛主动重建。
 *
 * 原生数据层断供时（updateBigIslandView 不再被调用、岛容器 attached 但内容非媒体），
 * 用该包名最后一次见过的媒体岛数据主动调用被 hook 的同一条原生更新入口，驱动原生
 * 重建媒体模块子树；重建走既有 hook 路径，register 与注入随之自动闭环。原生下一次
 * 真实媒体事件到达后接管刷新，缓存数据自然被覆盖。
 *
 * 按包名各自缓存：断供期间原生不会为新会话供数，单包缓存在跨 App 切换后必然失配
 * （2026-09-29 00:11 真机：酷我缓存触发一次后切 Apple 即永不重试）。
 */
internal object IslandMediaReinstater {
    private const val TAG = "IslandReinstate"
    private const val UPDATE_METHOD_NAME = "updateBigIslandView"
    private const val MAX_CACHED_PACKAGES = 8

    /**
     * 总开关：主动重放三轮未收口且用户报告「彻底不上岛」（时间相关，因果未定），
     * 已在 DEBUGGING_MISTAKES.md 立案。禁用后回到 210130 基线（退役+重挂）。
     * 重新启用必须有原生侧新证据（见错误簿「下一份判别证据」）。
     */
    private const val ENABLED = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cachedMediaDataByPkg = ConcurrentHashMap<String, Any>()

    @Volatile
    private var lastAttemptAtMs = 0L

    /** hook 侧见到携带媒体信息的数据时按包名缓存，供断供窗口重放。 */
    fun rememberMediaData(data: Any?, packageName: String) {
        if (!ENABLED) return
        if (data == null || packageName.isEmpty()) return
        if (cachedMediaDataByPkg.size >= MAX_CACHED_PACKAGES &&
            !cachedMediaDataByPkg.containsKey(packageName)
        ) {
            cachedMediaDataByPkg.clear()
        }
        cachedMediaDataByPkg[packageName] = data
    }

    fun reinstateIfEligible(lyricPkg: String, reason: String) {
        if (!ENABLED) return
        if (lyricPkg.isEmpty()) return
        val data = cachedMediaDataByPkg[lyricPkg] ?: return
        val screenInteractive = runCatching {
            val context = IslandViewRegistry.snapshotCandidates().firstOrNull()?.context
            context?.getSystemService(PowerManager::class.java)?.isInteractive
        }.getOrNull() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!IslandMediaReinstatePolicy.shouldReinstate(
                cachedDataAvailable = true,
                cachedPkgMatchesLyric = true,
                playbackActive = BaseIslandRenderer.currentPlaybackActive(),
                screenInteractive = screenInteractive,
                msSinceLastAttempt = if (lastAttemptAtMs == 0L) null else now - lastAttemptAtMs,
            )
        ) {
            return
        }
        mainHandler.post {
            lastAttemptAtMs = android.os.SystemClock.elapsedRealtime()
            // 岛存在 fake/real 与多个 contentView 实例，attached 不代表在屏；
            // 无法可靠辨别当前展示实例时对全部候选投喂：离屏实例消费无害，
            // 在屏实例重建后走既有 hook 闭环恢复。
            val hosts = IslandViewRegistry.snapshotCandidates()
            var invokedCount = 0
            for (host in hosts) {
                if (invokeNativeUpdate(host, data, lyricPkg, reason)) {
                    invokedCount++
                }
            }
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "主动重建媒体岛: reason=$reason, package=$lyricPkg, invoked=$invokedCount/${hosts.size}",
                )
            }
        }
    }

    /** 返回 true 表示某个宿主上的原生更新调用已成功发出。 */
    private fun invokeNativeUpdate(host: ViewGroup, data: Any, lyricPkg: String, reason: String): Boolean {
        val method = findUpdateMethod(host)
        if (method == null) {
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "未找到原生更新方法，跳过候选: host=${host.javaClass.simpleName}@${System.identityHashCode(host).toString(16)}",
                )
            }
            return false
        }
        // suspend 方法的 continuation 参数是宿主 classloader 的混淆类型（如 L0.d），
        // 模块侧 Continuation 与之不兼容；用原生接口的动态代理承载，方法按参数
        // 数量分派，不依赖混淆后的名字。
        return runCatching {
            method.invoke(host, data, false, createNativeContinuation(method))
        }.onFailure { e ->
            HookLogger.w(TAG, "主动重建媒体岛调用失败: reason=$reason, package=$lyricPkg", e)
        }.isSuccess
    }

    private fun findUpdateMethod(host: ViewGroup): Method? {
        return runCatching {
            host.javaClass.methods.find { it.name == UPDATE_METHOD_NAME && it.parameterTypes.size == 3 }
        }.getOrNull()
    }

    private fun createNativeContinuation(method: Method): Any {
        val continuationClass = method.parameterTypes[2]
        val contextClass = continuationClass.methods
            .firstOrNull { it.parameterTypes.isEmpty() }?.returnType
        val contextProxy = contextClass?.let { clazz ->
            runCatching {
                Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, m, args ->
                    when (m.parameterTypes.size) {
                        0 -> null
                        1 -> args?.getOrNull(0)
                        else -> args?.getOrNull(1) ?: args?.getOrNull(0)
                    }
                }
            }.getOrNull()
        }
        return Proxy.newProxyInstance(
            continuationClass.classLoader,
            arrayOf(continuationClass),
        ) { _, m, args ->
            when (m.parameterTypes.size) {
                0 -> contextProxy
                else -> null
            }
        }
    }
}
