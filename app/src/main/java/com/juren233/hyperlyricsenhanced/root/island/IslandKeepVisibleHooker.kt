/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.os.Handler
import android.os.Looper
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * 始终保持超级岛显示（键 [RootConstants.KEY_HOOK_ISLAND_KEEP_VISIBLE]）。
 *
 * 原生依据（miui.systemui.plugin 18.3.2.67.0 反编译，2026-11-19）：
 * - 状态栏窗口显隐变化：`DynamicIslandWindowViewController.statusBarVisible(Bundle)` →
 *   `statusBarAppearance(!visible)` → `windowState.updateStatusBarDisappearance` 与
 *   `view.updateStatusBarVisible`，状态栏隐藏（全屏）时超级岛随之隐藏。原生没有「横屏即隐藏」的判断。
 * - 处在岛的来源 App 内：`handleAppOrMiniWindowEvent` 与 `ACTION_REQUEST_HAS_ISLAND` 都经
 *   `canEnterAppState(DynamicIslandContentView)` 决定岛是否收进 App / 小窗。
 * 拦截点与 HyperIsland（1812z/HyperIsland，MIT）`TempHiddenBehaviorHook` 相同；本模块只放行音乐岛不收进 App。
 */
internal object IslandKeepVisibleHooker {
    private const val TAG = "IslandKeepVisible"
    private const val WINDOW_VIEW_CONTROLLER_CLASS =
        "miui.systemui.dynamicisland.window.DynamicIslandWindowViewController"
    private const val CONTENT_VIEW_CLASS = "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val bypass = ThreadLocal.withInitial { false }

    @Volatile private var controllerRef: WeakReference<Any>? = null
    @Volatile private var statusBarMethod: Method? = null
    /** 原生最近一次给出的「状态栏已隐藏」。未收到过为 null。 */
    @Volatile private var nativeStatusBarHidden: Boolean? = null

    /** 交给原生的「状态栏已隐藏」。 */
    internal fun forwardedStatusBarHidden(hidden: Boolean, mode: Int): Boolean =
        hidden && mode == RootConstants.ISLAND_KEEP_VISIBLE_OFF

    /** 音乐岛在全场景下不收进来源 App；其余情况交原生判断。 */
    internal fun blocksAppState(mode: Int, isMediaIsland: Boolean): Boolean =
        mode == RootConstants.ISLAND_KEEP_VISIBLE_ALL && isMediaIsland

    fun mode(): Int {
        val prefs = HookEntry.instance?.prefs ?: return RootConstants.DEFAULT_HOOK_ISLAND_KEEP_VISIBLE
        return IslandRuntimePreferenceReader.getInt(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_KEEP_VISIBLE,
            RootConstants.DEFAULT_HOOK_ISLAND_KEEP_VISIBLE,
        ).coerceIn(RootConstants.ISLAND_KEEP_VISIBLE_OFF, RootConstants.ISLAND_KEEP_VISIBLE_ALL)
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        val controllerClass = cl.loadClass(WINDOW_VIEW_CONTROLLER_CLASS)
        val contentViewClass = cl.loadClass(CONTENT_VIEW_CLASS)
        controllerClass.declaredMethods
            .filter {
                it.name == "statusBarAppearance" &&
                    it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }
            .forEach { method ->
                method.isAccessible = true
                statusBarMethod = method
                module.deoptimize(method)
                module.hook(method).intercept(StatusBarAppearanceHook())
                HookLogger.d(TAG, "已 Hook statusBarAppearance: $method")
            }
        controllerClass.declaredMethods
            .filter {
                it.name == "canEnterAppState" &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.contentEquals(arrayOf(contentViewClass))
            }
            .forEach { method ->
                method.isAccessible = true
                module.deoptimize(method)
                module.hook(method).intercept(CanEnterAppStateHook())
                HookLogger.d(TAG, "已 Hook canEnterAppState: $method")
            }
    }

    /** 设置变化后按原生最近一次的状态栏状态重新走一遍（经本 Hook 重新判断）。 */
    fun reapply() {
        mainHandler.post { invokeStatusBarAppearance(bypassHook = false) }
    }

    /** 模块退役（热重载）时把原生状态交还给系统。 */
    fun restoreNative() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invokeStatusBarAppearance(bypassHook = true)
        } else {
            mainHandler.post { invokeStatusBarAppearance(bypassHook = true) }
        }
    }

    private fun invokeStatusBarAppearance(bypassHook: Boolean) {
        val controller = controllerRef?.get() ?: return
        val method = statusBarMethod ?: return
        val hidden = nativeStatusBarHidden ?: return
        runCatching {
            bypass.set(bypassHook)
            try {
                method.invoke(controller, hidden)
            } finally {
                bypass.set(false)
            }
        }.onFailure { HookLogger.e(TAG, "重新应用状态栏显隐失败", it) }
    }

    class StatusBarAppearanceHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired || bypass.get() == true) return chain.proceed()
            val hidden = chain.args.getOrNull(0) as? Boolean ?: return chain.proceed()
            controllerRef = WeakReference(chain.thisObject)
            nativeStatusBarHidden = hidden
            val mode = mode()
            val forwarded = forwardedStatusBarHidden(hidden, mode)
            if (BuildConfig.DEBUG) {
                HookLogger.d(TAG, "statusBarAppearance: hidden=$hidden mode=$mode forwarded=$forwarded")
            }
            return if (forwarded == hidden) chain.proceed() else chain.proceed(arrayOf(forwarded))
        }
    }

    class CanEnterAppStateHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val mode = mode()
            if (mode != RootConstants.ISLAND_KEEP_VISIBLE_ALL) return chain.proceed()
            val contentView = chain.args.getOrNull(0)
            val media = contentView != null &&
                IslandProbeUtils.isMediaIsland(IslandProbeUtils.getCurrentIslandData(contentView))
            if (!blocksAppState(mode, media)) return chain.proceed()
            if (BuildConfig.DEBUG) HookLogger.d(TAG, "canEnterAppState: 音乐岛保持显示，不收进 App")
            return false
        }
    }
}
