/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.common.RootConstants
import java.lang.reflect.Method

/**
 * 「柔光玻璃」材质下的卡片主题。
 *
 * OS4.0.4 的 MediaViewGlassEffect.apply 先调 MediaViewBlurEffect.apply(view, context)
 * （只有这里读 context：背景 drawable 和三组 blend 颜色），再切成玻璃材质，然后
 * setMiGlassCompat(mediaBg, 一组写死的 42 个 float)。这组参数与资源
 * notification_glass_params_normal 相同，且该资源没有 night 变体，所以只换 context
 * 在玻璃材质下看不出差别。这里在系统 apply 之后再写一次玻璃参数。
 *
 * 参数取自同一 APK 里系统自己的一对通知玻璃：
 * - 深色：HeadsUpNotificationGlassDarkEffect（NotifEffectType.GLASS_HEADS_UP_DARK）
 * - 浅色：HeadsUpNotificationGlassEffect（NotifEffectType.GLASS）
 *
 * 「轻透磨砂」（MaterialType.BLUR → MediaViewBlurEffect）颜色全部来自 context，
 * 已由换 context 处理，这里不动。锁屏的三个玻璃效果把参数缓存在静态 glassParamsArray
 * 里，也不在这里处理。
 */
internal object NotificationMediaGlassTheme {
    /** 只处理通知中心的柔光玻璃；锁屏/息屏玻璃不在范围内。 */
    const val GLASS_EFFECT_SIMPLE_NAME = "MediaViewGlassEffect"

    private const val MI_GLASS_COMPAT_CLASS = "com.miui.systemui.util.MiGlassCompat"
    private const val MI_GLASS_COMPAT_METHOD = "setMiGlassCompat"
    private const val VIEW_SET_MI_GLASS = "setMiGlass"
    private const val MEDIA_VIEW_HOLDER_GETTER = "getMediaViewHolder"
    private const val MEDIA_BG_FIELD = "mediaBg"

    /** HeadsUpNotificationGlassDarkEffect.apply 的参数（OS4.0.4.0.XOCCNXM 反编译核对）。 */
    val DARK_GLASS_PARAMS: FloatArray = floatArrayOf(
        0.8f, 1.0f, 0.0f, 1.0f, 0.2f, 2.0f, 0.14f, 0.1f, -0.001f, 0.0f,
        0.02f, 0.27f, 0.27f, 0.27f, 0.6f, 0.0f, 0.2f, 1.2f, 1.0f, 72.0f,
        3.8f, 80.0f, 600.0f, 1.0f, 0.8f, -0.4f, 0.6f, -0.8f, 1.5f, 1.0f,
        0.8f, 1.15f, 3.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f,
    )

    /** HeadsUpNotificationGlassEffect.apply 的参数（OS4.0.4.0.XOCCNXM 反编译核对）。 */
    val LIGHT_GLASS_PARAMS: FloatArray = floatArrayOf(
        0.5f, 1.0f, 0.0f, 0.8f, 0.5f, 1.2f, 0.0f, 0.2f, -0.001f, 0.0f,
        0.03f, 1.0f, 1.0f, 1.0f, 1.5f, 0.0f, 0.6f, 0.6f, 1.0f, 62.0f,
        3.8f, 80.0f, 600.0f, 1.0f, 0.8f, -0.4f, 0.6f, -0.8f, 1.2f, 0.6f,
        0.8f, 1.15f, 3.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f,
    )

    /** 该效果在该主题下需要补写的玻璃参数；null 表示保持系统原样。 */
    fun paramsFor(effectSimpleName: String?, theme: Int): FloatArray? {
        if (effectSimpleName != GLASS_EFFECT_SIMPLE_NAME) return null
        return when (theme) {
            RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK -> DARK_GLASS_PARAMS
            RootConstants.MEDIA_CARD_THEME_ALWAYS_LIGHT -> LIGHT_GLASS_PARAMS
            else -> null
        }
    }

    /** 把玻璃参数写到目标 view 上。真机走系统的 MiGlassCompat，单测可替换。 */
    fun interface GlassWriter {
        fun write(effect: Any, target: Any, params: FloatArray)
    }

    /**
     * 系统 apply 结束后调用：主题需要时，对 view.getMediaViewHolder().mediaBg 补写参数。
     * 返回是否写入。任何一步不可用都只返回 false，不影响系统已经画好的材质。
     */
    fun applyAfterSystem(
        effect: Any?,
        view: Any,
        theme: Int,
        writer: GlassWriter? = null,
    ): Boolean {
        if (effect == null) return false
        val params = paramsFor(effect.javaClass.simpleName, theme) ?: return false
        val holder = view.javaClass.methods
            .firstOrNull { it.name == MEDIA_VIEW_HOLDER_GETTER && it.parameterCount == 0 }
            ?.invoke(view) ?: return false
        val mediaBg = findField(holder.javaClass, MEDIA_BG_FIELD)?.get(holder) ?: return false
        (writer ?: ReflectiveGlassWriter).write(effect, mediaBg, params.copyOf())
        return true
    }

    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field? {
        var cls: Class<*>? = start
        while (cls != null) {
            cls.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * 优先用 SystemUI 自己的 MiGlassCompat.setMiGlassCompat(View, float[])（与系统写法一致，
     * 内部已 try/catch）；拿不到时退回 framework 的 View.setMiGlass(float[])。
     */
    private object ReflectiveGlassWriter : GlassWriter {
        @Volatile private var cachedLoader: ClassLoader? = null
        @Volatile private var cachedCompat: Method? = null

        override fun write(effect: Any, target: Any, params: FloatArray) {
            val compat = compatMethod(effect.javaClass.classLoader)
            if (compat != null) {
                compat.invoke(null, target, params)
                return
            }
            target.javaClass.getMethod(VIEW_SET_MI_GLASS, FloatArray::class.java)
                .invoke(target, params)
        }

        private fun compatMethod(loader: ClassLoader?): Method? {
            if (loader == null) return null
            if (cachedLoader === loader) return cachedCompat
            val method = runCatching {
                val viewClass = Class.forName("android.view.View")
                loader.loadClass(MI_GLASS_COMPAT_CLASS)
                    .getMethod(MI_GLASS_COMPAT_METHOD, viewClass, FloatArray::class.java)
            }.getOrNull()
            cachedCompat = method
            cachedLoader = loader
            return method
        }
    }
}
