/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.internal.ArgumentRewriteHook
import io.github.proify.lyricon.amprovider.xposed.internal.CallbackHook
import io.github.proify.lyricon.amprovider.xposed.internal.ConditionalVoidSkipHook
import io.github.proify.lyricon.amprovider.xposed.internal.ResultOverrideHook
import io.github.proify.lyricon.amprovider.xposed.internal.ScopedCallbackHook
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal class AppleHookRegistrar(
    private val module: XposedModule,
) {
    private val activeModuleId = ThreadLocal<String?>()
    private val firstCallbackModuleIds = ConcurrentHashMap.newKeySet<String>()

    fun <T> withModule(moduleId: String, block: () -> T): T {
        val previous = activeModuleId.get()
        activeModuleId.set(moduleId)
        return try {
            block()
        } finally {
            if (previous == null) activeModuleId.remove() else activeModuleId.set(previous)
        }
    }

    fun install(
        executable: Executable,
        before: ((Chain) -> Unit)? = null,
        after: ((Chain, Any?) -> Unit)? = null,
    ) = installHooker(executable, CallbackHook(before, after))

    fun installScoped(
        executable: Executable,
        enter: (Chain) -> Boolean,
        after: (Chain, Any?) -> Unit,
        exit: () -> Unit,
    ) = installHooker(executable, ScopedCallbackHook(enter, after, exit))

    fun installConditionalVoidSkip(
        executable: Executable,
        shouldSkip: (Chain) -> Boolean,
    ) {
        require(executable is Method && executable.returnType == Void.TYPE) {
            "Conditional skip hooks require a void method: $executable"
        }
        installHooker(executable, ConditionalVoidSkipHook(shouldSkip))
    }

    fun installResultOverride(
        executable: Executable,
        override: (Chain, Any?) -> Any?,
    ) = installHooker(executable, ResultOverrideHook(override))

    fun installArgumentRewrite(
        executable: Executable,
        rewrite: (Chain) -> Array<Any?>?,
    ) = installHooker(executable, ArgumentRewriteHook(rewrite))

    private fun installHooker(executable: Executable, hooker: Hooker) {
        runCatching { module.deoptimize(executable) }
        val moduleId = activeModuleId.get() ?: "unscoped"
        module.hook(executable).intercept(
            if (BuildConfig.DEBUG) tracingHook(moduleId, executable, hooker) else hooker
        )
    }

    private fun tracingHook(
        moduleId: String,
        executable: Executable,
        delegate: Hooker,
    ): Hooker = object : Hooker {
        override fun intercept(chain: Chain): Any? = withModule(moduleId) {
            if (firstCallbackModuleIds.add(moduleId)) {
                ProviderLogger.diagnostic(
                    "Apple Music Hook 首次回调: module=$moduleId, target=$executable"
                )
            }
            delegate.intercept(chain)
        }
    }
}
