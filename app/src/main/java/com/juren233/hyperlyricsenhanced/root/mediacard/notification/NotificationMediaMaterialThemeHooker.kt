/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import android.content.Context
import android.content.res.Configuration
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Card-theme support for the native notification-center media card background.
 *
 * The system re-skins the media card background exclusively through
 * NotificationViewEffectHelper.mediaViewEffectsMap effects: each apply(Object, Context)
 * resolves the day/night background drawable, blend colors and integers from the context
 * argument handed over by NotificationMaterialStateInteractor.getFixUiModeContext.
 * Swapping the controller's context alone (CardThemeApi) therefore never reaches the
 * background — verified against OS4.0.0.6/.0.0.8/.0.0.34 dex (2026-09-12).
 *
 * This hooker intercepts every media effect's apply and, when the card theme preference is
 * not FOLLOW_SYSTEM, substitutes a night-mode-overridden copy of the very same context.
 * Captured (view, effect, context) triples are re-invoked on preference refresh so the
 * switch is live for already-bound cards.
 */
internal object NotificationMediaMaterialThemeHooker {
    private const val TAG = "NotificationMediaMaterialThemeHooker"

    private val hookedClassLoaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())

    /** Media header view -> material applied by which effect, through which method, with which base context. */
    private val appliedViews = Collections.synchronizedMap(WeakHashMap<Any, AppliedMaterial>())

    /**
     * Views currently inside an outermost apply. Glass delegates to the blur effect on the
     * same view, so inner entries must not re-wrap the already-themed context nor overwrite
     * the captured base context.
     */
    private val inFlightViews = ThreadLocal.withInitial {
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    }

    /**
     * apply 是效果对象上的实例方法：效果对象是接收者，view 是第一个参数。
     * 重放必须用同一个效果对象当接收者，不能把 view 当接收者。静态实现时 effect 为 null。
     */
    internal class AppliedMaterial(val effect: Any?, val method: Method, val baseContext: Context)

    fun install(xposedModule: XposedInterface, classLoader: ClassLoader, themeProvider: () -> Int) {
        if (!hookedClassLoaders.add(classLoader)) return
        var installed = 0
        for (className in NotificationMediaHookMethodProfile.mediaViewEffectClassNames) {
            val effectClass = runCatching { classLoader.loadClass(className) }.getOrNull()
            if (effectClass == null) {
                if (BuildConfig.DEBUG) {
                    HookLogger.w(TAG, "媒体材质效果类缺失: class=$className")
                }
                continue
            }
            val applyMethod = runCatching {
                effectClass.getDeclaredMethod(
                    NotificationMediaHookMethodProfile.EFFECT_APPLY_METHOD,
                    Object::class.java,
                    Context::class.java,
                )
            }.getOrNull()
            if (applyMethod == null) {
                HookLogger.w(TAG, "媒体材质效果 apply 签名缺失: class=$className")
                continue
            }
            runCatching {
                applyMethod.isAccessible = true
                xposedModule.deoptimize(applyMethod)
                xposedModule.hook(applyMethod).intercept(EffectApplyHook(applyMethod, themeProvider))
                installed++
            }.onFailure { error ->
                HookLogger.w(TAG, "安装媒体材质主题 Hook 失败: class=$className reason=${error.message}")
            }
        }
        if (installed == 0) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "媒体材质效果不可用，卡片背景颜色将无法跟随主题")
        } else {
            HookLogger.i(TAG, "媒体材质主题 Hook 已初始化: effects=$installed")
        }
    }

    /** Re-applies the last-known material for every captured card with the current theme. */
    fun refresh() {
        val entries = appliedViews.toMap()
        if (entries.isEmpty()) return
        for ((view, applied) in entries) {
            runCatching {
                applied.method.invoke(applied.effect, view, applied.baseContext)
            }.onFailure { error ->
                HookLogger.w(
                    TAG,
                    "重放媒体材质背景失败: view=${view.javaClass.name} reason=${error.message}",
                )
            }
        }
    }

    fun releaseAll() {
        appliedViews.clear()
    }

    fun overrideNightMode(context: Context, theme: Int): Context {
        val dark = theme == RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = NotificationMediaHookMethodProfile.overrideNightMode(
            configuration.uiMode,
            dark,
        )
        return context.createConfigurationContext(configuration)
    }

    internal class EffectApplyHook(
        private val applyMethod: Method,
        private val themeProvider: () -> Int,
        private val glassWriter: NotificationMediaGlassTheme.GlassWriter? = null,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val effect = chain.thisObject
            val view = chain.getArg(0) ?: return chain.proceed()
            val context = chain.getArg(1) as? Context ?: return chain.proceed()
            val inFlight = inFlightViews.get()
            if (!inFlight.add(view)) return chain.proceed()
            try {
                appliedViews[view] = AppliedMaterial(effect, applyMethod, context)
                val theme = themeProvider()
                if (theme == RootConstants.MEDIA_CARD_THEME_FOLLOW_SYSTEM) {
                    return chain.proceed()
                }
                val result = chain.proceed(arrayOf(view, overrideNightMode(context, theme)))
                // 柔光玻璃的玻璃参数是写死的，不随 context 变化，需要在系统 apply 之后补写。
                runCatching {
                    NotificationMediaGlassTheme.applyAfterSystem(effect, view, theme, glassWriter)
                }.onFailure { error ->
                    HookLogger.w(
                        TAG,
                        "补写柔光玻璃主题参数失败: effect=${effect?.javaClass?.name} reason=${error.message}",
                    )
                }
                return result
            } finally {
                inFlight.remove(view)
            }
        }
    }
}
