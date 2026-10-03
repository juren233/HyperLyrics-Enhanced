/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.content.Context
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Preserves the native View/parent chain and consent result, repairing only our null argument. */
internal class IslandMediaOutputCtaBridge(private val module: XposedModule, private val nativeClick: Method) {
    private val scope = MediaOutputCtaCallScope<Context>()
    private val handles = mutableMapOf<Method, HookHandle>()

    fun invoke(entry: NativeMediaOutputEntry, source: View) {
        if (SystemUiHookLifetime.retired) throw MediaOutputUnavailable("module_retired")
        ensureHook(entry.plugin)
        scope.invoke(entry.plugin, source.context) { entry.listener.onClick(source) }
    }

    private fun ensureHook(plugin: Any) {
        // Resolve on the live instance: the host may wrap or replace a plugin on reload.
        // The name and full descriptor are verified against the original host interface.
        val method = plugin.javaClass.getMethod(IslandMediaOutputProfile.CTA_CHECK, Context::class.java)
        check(method.returnType == Boolean::class.javaPrimitiveType &&
            !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers))
        if (method in handles) return
        module.deoptimize(nativeClick)
        handles[method] = module.hook(method).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                if (SystemUiHookLifetime.retired) return chain.proceed()
                val original = chain.args.firstOrNull() as? Context
                val context = scope.argument(chain.thisObject, original)
                // No forced true result: the original implementation still reads real consent.
                val result = if (context !== original) chain.proceed(arrayOf(context)) else chain.proceed()
                if (BuildConfig.DEBUG) IslandMediaOutputDiagnostics.ctaCheck(method, original, context, result)
                return result
            }
        })
        HookLogger.i("IslandMediaOutput", "音乐流转 Context 适配已安装")
        if (BuildConfig.DEBUG) IslandMediaOutputDiagnostics.ctaHookInstalled(method)
    }

    fun release() {
        handles.values.forEach { runCatching { it.unhook() } }
        releaseForReload()
    }

    fun releaseForReload() {
        // onHotReloading holds the framework's old-module monitor while waiting for main.
        // Unhooking here would wait for that same monitor. The framework retains these
        // handles and passes them to onHotReloaded after releasing the monitor.
        handles.clear()
        scope.clear()
    }
}
