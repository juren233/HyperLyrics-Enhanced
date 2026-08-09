/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

internal object QishuiPackageBlockRuntimeIdentifiers {
    const val PACKAGE_NAME = "com.luna.music"

    // Verified from the original 20.4.0 classes20.dex, not a JADX display alias.
    const val DELEGATE_CLASS_DESCRIPTOR =
        "Lcom/luna/biz/main/init/blockpackage/PackageBlockDelegate;"
    const val BLOCK_CALLBACK_METHOD_NAME = "l"
    const val BLOCK_CALLBACK_METHOD_DESCRIPTOR =
        "(Lcom/luna/biz/main/init/blockpackage/PackageBlockDelegate;Z)V"

    val delegateClassName: String
        get() = DELEGATE_CLASS_DESCRIPTOR
            .removePrefix("L")
            .removeSuffix(";")
            .replace('/', '.')
}

internal object QishuiPackageBlockCompat {
    private const val TAG = "QishuiPackageBlockCompat"

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val identifiers = QishuiPackageBlockRuntimeIdentifiers
        val delegateClass = classLoader.loadClass(identifiers.delegateClassName)
        val blockCallback = delegateClass.getDeclaredMethod(
            identifiers.BLOCK_CALLBACK_METHOD_NAME,
            delegateClass,
            Boolean::class.javaPrimitiveType!!,
        )
        require(Modifier.isStatic(blockCallback.modifiers) && blockCallback.returnType == Void.TYPE) {
            "Unexpected Qishui package-block callback signature: $blockCallback"
        }

        blockCallback.isAccessible = true
        module.deoptimize(blockCallback)
        module.hook(blockCallback).intercept(BlockCallbackHooker())
        HookLogger.i(
            TAG,
            "汽水安全阻断兼容 Hook 已安装: " +
                "target=${identifiers.DELEGATE_CLASS_DESCRIPTOR}->" +
                "${identifiers.BLOCK_CALLBACK_METHOD_NAME}" +
                identifiers.BLOCK_CALLBACK_METHOD_DESCRIPTOR,
        )
    }

    internal fun shouldSuppressBlockCallback(isBlocked: Boolean): Boolean = isBlocked

    private class BlockCallbackHooker : Hooker {
        private val firstCallbackLogged = AtomicBoolean(false)

        override fun intercept(chain: Chain): Any? {
            val isBlocked = chain.args.getOrNull(1) as? Boolean
            val shouldSuppress = isBlocked?.let(::shouldSuppressBlockCallback) == true
            if (BuildConfig.DEBUG && firstCallbackLogged.compareAndSet(false, true)) {
                HookLogger.i(
                    TAG,
                    "汽水安全阻断兼容 Hook 首次回调: " +
                        "blocked=$isBlocked suppressed=$shouldSuppress",
                )
            }
            return if (shouldSuppress) null else chain.proceed()
        }
    }
}
