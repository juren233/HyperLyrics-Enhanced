/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.os.Looper
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException

internal object IslandMediaOutput {
    private const val TAG = "IslandMediaOutput"
    @Volatile private var api: IslandMediaOutputApi? = null
    private var ctaBridge: IslandMediaOutputCtaBridge? = null

    fun initialize(module: XposedModule, loader: ClassLoader) {
        ctaBridge?.release()
        ctaBridge = null
        api = runCatching {
            val resolved = IslandMediaOutputApi(loader)
            ctaBridge = IslandMediaOutputCtaBridge(module, resolved.method(
                IslandMediaOutputProfile.LISTENER_TYPE, IslandMediaOutputProfile.ON_CLICK,
                "void", "android.view.View",
            ))
            resolved
        }.onSuccess { resolved ->
                if (BuildConfig.DEBUG) runCatching { IslandMediaOutputDiagnostics.install(module, resolved) }
                    .onFailure { HookLogger.w(TAG, "音乐流转诊断安装失败: ${it.javaClass.simpleName}") }
                HookLogger.i(TAG, "音乐流转原生入口已解析")
            }
            .onFailure { HookLogger.w(TAG, "音乐流转原生入口不可用: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    fun releaseForReload() {
        api = null
        ctaBridge?.releaseForReload()
        ctaBridge = null
        if (BuildConfig.DEBUG) IslandMediaOutputDiagnostics.releaseForReload()
    }

    fun open(source: View?, packageName: String): Boolean {
        val request = if (BuildConfig.DEBUG) IslandMediaOutputDiagnostics.request(packageName, source) else 0L
        return try {
            if (Looper.myLooper() != Looper.getMainLooper()) throw MediaOutputUnavailable("not_main_thread")
            if (source == null || !source.isAttachedToWindow || !source.isShown || source.alpha <= 0f) {
                throw MediaOutputUnavailable("island_source_unavailable")
            }
            val entry = (api ?: throw MediaOutputUnavailable("api_unavailable")).prepare()
            val bridge = ctaBridge ?: throw MediaOutputUnavailable("cta_bridge_unavailable")
            // This must be the current island View, not a hidden notification icon.
            // The native listener walks its parents to select dynamic_island presentation.
            if (BuildConfig.DEBUG) {
                IslandMediaOutputDiagnostics.invokeNative(request) { bridge.invoke(entry, source) }
            } else {
                bridge.invoke(entry, source)
            }
            true
        } catch (error: Exception) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            if (BuildConfig.DEBUG) IslandMediaOutputDiagnostics.rejected(
                request, (cause as? MediaOutputUnavailable)?.reason ?: "native_exception", cause.javaClass.simpleName,
            )
            false
        }
    }
}
