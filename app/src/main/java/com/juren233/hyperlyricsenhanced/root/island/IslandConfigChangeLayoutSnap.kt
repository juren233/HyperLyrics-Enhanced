/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 转屏等配置变化时，大岛内容区与背景一起直接到位。
 *
 * 原生依据（miui.systemui.plugin 18.3.2.67.0 反编译，2026-11-19）：
 * - 配置变化走 `BigIslandChanged(noAnim=true)` → `DynamicIslandAnimationDelegate.bigIslandChangedNoAnimation(view)`：
 *   先 `view.updateBigIslandLayout()`，再用 `setTo` 把背景（大岛动画状态）直接设到新宽度。
 * - `updateBigIslandLayout()` → `DynamicIslandBaseContentView.doBigIslandAnim(...)`：真实岛视图一律用 Folme
 *   `.to()` 动画 `area_left` / `area_right` / `big_container` 的宽度与 `big_island_view` 的 translationX，
 *   不看这次转场是否 noAnim。
 * 结果是背景先跳到新宽度、内容从旧宽度展开（2026-11-19 录屏：横屏全岛 626 → 竖屏 1118）。
 *
 * 只在「noAnim 转场 + 窗口处于配置变化」期间，doBigIslandAnim 之后对同样几项再 `setTo` 目标值；其他转场原样保留原生动画。
 *
 * Folme 依据（同一插件内置 miuix.animation）：`.to()` 只登记待启动转场，`AnimManager.setupTransition`
 * 在启动时发现「所有属性已被 setTo 处理」就取消该转场；`end(property)` 只结束运行中的动画，对尚未启动的
 * 转场无效（250013 真机：end 之后 big_container 仍从 678 慢慢变到 711，用户反馈问题依旧）。
 */
internal object IslandConfigChangeLayoutSnap {
    private const val TAG = "IslandConfigChangeSnap"
    private const val DELEGATE_CLASS = "miui.systemui.dynamicisland.anim.DynamicIslandAnimationDelegate"
    private const val BASE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    private const val DO_ANIM_PARAM_COUNT = 11

    /** doBigIslandAnim 参数里依次为左区、右区、大岛容器、大岛视图（translationX）。 */
    private const val ARG_LEFT_AREA = 4
    private const val ARG_RIGHT_AREA = 5
    private const val ARG_CONTAINER = 6
    private const val ARG_ISLAND_VIEW = 7

    private val snapDepth = ThreadLocal.withInitial { 0 }

    private class FolmeApi(
        val use: Method,
        val state: Method,
        val setTo: Method,
        val animStateClass: Class<*>,
        val addInt: Method,
        val addFloat: Method,
        val animConfigArrayType: Class<*>,
        val width: Any,
        val translationX: Any,
    )

    @Volatile private var folme: FolmeApi? = null

    /** noAnim 转场是否同时处于配置变化（窗口状态 configChange 为 true）。 */
    internal fun shouldSnap(configChange: Boolean?): Boolean = configChange == true

    fun install(module: XposedModule, cl: ClassLoader) {
        folme = resolveFolme(cl)
        val delegateClass = cl.loadClass(DELEGATE_CLASS)
        val baseClass = cl.loadClass(BASE_CONTENT_VIEW_CLASS)
        var hooked = 0
        delegateClass.declaredMethods
            .filter { it.name == "bigIslandChangedNoAnimation" && it.parameterTypes.size == 1 }
            .forEach { method ->
                method.isAccessible = true
                module.deoptimize(method)
                module.hook(method).intercept(NoAnimationHook())
                hooked++
                HookLogger.d(TAG, "已 Hook bigIslandChangedNoAnimation: $method")
            }
        baseClass.declaredMethods
            .filter { it.name == "doBigIslandAnim" && it.parameterTypes.size == DO_ANIM_PARAM_COUNT }
            .forEach { method ->
                method.isAccessible = true
                module.deoptimize(method)
                module.hook(method).intercept(DoBigIslandAnimHook())
                hooked++
                HookLogger.d(TAG, "已 Hook doBigIslandAnim: $method")
            }
        if (hooked < 2) HookLogger.w(TAG, "配置变化内容区直接到位未完整安装: hooked=$hooked")
    }

    private fun resolveFolme(cl: ClassLoader): FolmeApi? = runCatching {
        val folmeClass = cl.loadClass("miuix.animation.Folme")
        val folmeInterface = cl.loadClass("miuix.animation.IFolme")
        val folmeStyle = cl.loadClass("miuix.animation.FolmeStyle")
        val animState = cl.loadClass("miuix.animation.controller.AnimState")
        val animConfig = cl.loadClass("miuix.animation.base.AnimConfig")
        val viewProperty = cl.loadClass("miuix.animation.property.ViewProperty")
        val animConfigArray = java.lang.reflect.Array.newInstance(animConfig, 0).javaClass
        FolmeApi(
            use = folmeClass.getMethod("use", View::class.java),
            state = folmeInterface.getMethod("state"),
            setTo = folmeStyle.getMethod("setTo", Any::class.java, animConfigArray),
            animStateClass = animState,
            addInt = animState.getMethod("add", viewProperty, Int::class.javaPrimitiveType, LongArray::class.java),
            addFloat = animState.getMethod("add", viewProperty, Float::class.javaPrimitiveType, LongArray::class.java),
            animConfigArrayType = animConfig,
            width = viewProperty.getField("WIDTH").get(null)!!,
            translationX = viewProperty.getField("TRANSLATION_X").get(null)!!,
        )
    }.onFailure {
        HookLogger.w(TAG, "Folme 接口不可用，配置变化时内容区保持原生动画: ${it.javaClass.simpleName}")
    }.getOrNull()

    private fun configChange(view: Any?): Boolean? = runCatching {
        val windowState = view?.javaClass?.getMethod("getWindowState")?.invoke(view) ?: return null
        windowState.javaClass.getMethod("getConfigChange").invoke(windowState) as? Boolean
    }.getOrNull()

    /** 对 view 的指定属性 Folme `setTo`：直接设到目标值，并让同属性尚未启动的 `.to()` 转场在启动时被取消。 */
    private fun setTo(api: FolmeApi, view: Any?, property: Any, value: Number) {
        if (view !is View) return
        val target = api.animStateClass.getDeclaredConstructor().newInstance()
        if (value is Float) api.addFloat.invoke(target, property, value, LongArray(0))
        else api.addInt.invoke(target, property, value.toInt(), LongArray(0))
        val state = api.state.invoke(api.use.invoke(null, view)) ?: return
        api.setTo.invoke(state, target, java.lang.reflect.Array.newInstance(api.animConfigArrayType, 0))
    }

    /** 原生只在 `needUpdateTransition()` 且未锁定位置（第 11 参数为 0）时动画 translationX。 */
    private fun nativeAnimatesTranslation(host: Any?, positionMode: Any?): Boolean {
        if (positionMode != 0) return false
        return runCatching {
            host?.javaClass?.getMethod("needUpdateTransition")?.invoke(host) as? Boolean
        }.getOrNull() == true
    }

    class NoAnimationHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val view = chain.args.getOrNull(0)
            if (!shouldSnap(configChange(view))) return chain.proceed()
            snapDepth.set(snapDepth.get() + 1)
            return try {
                chain.proceed()
            } finally {
                snapDepth.set(snapDepth.get() - 1)
            }
        }
    }

    class DoBigIslandAnimHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (SystemUiHookLifetime.retired || snapDepth.get() <= 0) return result
            val api = folme ?: return result
            runCatching {
                val args = chain.args
                setTo(api, args[ARG_LEFT_AREA], api.width, args[0] as Int)
                setTo(api, args[ARG_RIGHT_AREA], api.width, args[1] as Int)
                setTo(api, args[ARG_CONTAINER], api.width, args[2] as Int)
                if (nativeAnimatesTranslation(chain.thisObject, args[10])) {
                    setTo(api, args[ARG_ISLAND_VIEW], api.translationX, args[3] as Float)
                }
                if (BuildConfig.DEBUG) {
                    HookLogger.d(TAG, "配置变化内容区直接到位: left=${args[0]} right=${args[1]} width=${args[2]} fake=${args[9]}")
                }
            }.onFailure { HookLogger.w(TAG, "配置变化内容区直接到位失败: ${it.javaClass.simpleName}") }
            return result
        }
    }
}
