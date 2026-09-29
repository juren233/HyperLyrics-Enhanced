/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 210143 real-device trace: removing an old island background cancels its animation.
 * The cancellation callback reenters ViewGroup.removeView for the same old background.
 * The inner call removes it first; the outer call then removes the next child by its
 * stale index, which is already the new music island. Skip only that duplicate call.
 */
internal object IslandReentrantBackgroundRemovalHooker {
    private const val TAG = "IslandRemovalGuard"

    // Verified original plugin DEX descriptors, not decompiler-generated names.
    const val WINDOW_CLASS = "miui.systemui.dynamicisland.window.DynamicIslandWindowView"
    const val BACKGROUND_CLASS = "miui.systemui.dynamicisland.DynamicIslandBackgroundView"

    fun isTarget(windowClass: String, childClass: String): Boolean =
        windowClass == WINDOW_CLASS && childClass == BACKGROUND_CLASS

    private val gate = IslandReentrantRemovalGate()
    private val firstVetoLogged = AtomicBoolean(false)

    fun install(module: XposedModule) {
        val method = ViewGroup::class.java.getDeclaredMethod("removeView", View::class.java)
        module.hook(method).intercept(RemoveViewHook())
        HookLogger.i(TAG, "已 Hook 同背景重入 removeView 守卫")
    }

    class RemoveViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val window = chain.thisObject as? ViewGroup ?: return chain.proceed()
            val background = chain.args.getOrNull(0) as? View ?: return chain.proceed()
            if (!isTarget(window.javaClass.name, background.javaClass.name)) {
                return chain.proceed()
            }
            if (!gate.tryEnter(window, background)) {
                if (BuildConfig.DEBUG && firstVetoLogged.compareAndSet(false, true)) {
                    HookLogger.i(
                        TAG,
                        "已否决同背景重入删除: window=${id(window)}, background=${id(background)}",
                    )
                }
                return null
            }
            try {
                return chain.proceed()
            } finally {
                gate.leave(window, background)
            }
        }
    }

    private fun id(value: Any): String = System.identityHashCode(value).toString(16)
}

/** Per-thread identity gate: a nested removal of the same child is redundant. */
internal class IslandReentrantRemovalGate {
    private val active = ThreadLocal.withInitial {
        IdentityHashMap<Any, MutableSet<Any>>()
    }

    fun tryEnter(parent: Any, child: Any): Boolean {
        val byParent = requireNotNull(active.get())
        val children = byParent.getOrPut(parent) {
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        }
        return children.add(child)
    }

    fun leave(parent: Any, child: Any) {
        val byParent = requireNotNull(active.get())
        val children = byParent[parent] ?: return
        children.remove(child)
        if (children.isEmpty()) byParent.remove(parent)
        if (byParent.isEmpty()) active.remove()
    }
}
