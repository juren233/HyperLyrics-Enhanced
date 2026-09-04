/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * OS4 notification-media glass identifiers verified from the original MiuiSystemUI DEX.
 *
 * Do not replace these binary names with JADX display aliases. The mirrored path is
 * `MediaViewBlurEffect.apply` followed by `MediaViewGlassEffect.apply`, with the blend half
 * inlined from `NotificationUtil.applyElementViewBlend(Context, View, Z, [I, Z)V`.
 *
 * Never call `NotificationUtil.applyElementViewBlend` itself on the expanded island view: its
 * body runs `setRoundRect(view, …)` before the blend, installing a `NotificationUtil` outline
 * provider that rebuilds against the plugin view's own Resources and throws
 * `Resources$NotFoundException` for the main-package dimen `notification_item_bg_radius`
 * (`150207`: repeated SystemUI crashes). The blend steps themselves live in the main-package
 * `com.miui.systemui.util.MiBlurCompat` (classes3.dex definitions below) and are safe on any
 * view, so they are replicated here without the outline provider.
 *
 * Class-loader ownership (OS4.0.0.6 dexdump, class definitions): [MI_BLUR_COMPAT_CLASS] and
 * [MI_GLASS_COMPAT_CLASS] are defined in the main MiuiSystemUI APK classes3.dex;
 * MIUISystemUIPlugin defines neither. They must be resolved with the host SystemUI class loader
 * captured at hook time — the plugin loader derived from the expanded island view throws
 * `ClassNotFoundException` (`150206` runtime evidence) and silently downgrades the light theme
 * to the opaque drawable fallback.
 */
internal object IslandExpandedMediaNotificationGlassProfile {
    const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val MI_BLUR_COMPAT_CLASS = "com.miui.systemui.util.MiBlurCompat"
    const val MI_GLASS_COMPAT_CLASS = "com.miui.systemui.util.MiGlassCompat"

    const val GET_BACKGROUND_BLUR_OPENED_METHOD = "getBackgroundBlurOpened"
    const val SET_MI_VIEW_BLUR_MODE_METHOD = "setMiViewBlurModeCompat"
    const val CLEAR_MI_BACKGROUND_BLEND_COLOR_METHOD = "clearMiBackgroundBlendColorCompat"
    const val SET_MI_BACKGROUND_BLEND_COLORS_METHOD = "setMiBackgroundBlendColors\$default"
    const val SET_MI_VIEW_MATERIAL_TYPE_METHOD = "setMiViewMaterialTypeCompat"
    const val SET_MI_GLASS_METHOD = "setMiGlassCompat"

    const val TRANSPARENT_BACKGROUND_DRAWABLE = "notification_heads_up_transparent_bg"
    const val SHADE_COLOR_1 = "media_notification_element_blend_shade_color_1"
    const val SHADE_COLOR_2 = "media_notification_element_blend_shade_color_2"
    const val SHADE_COLOR_3 = "media_notification_element_blend_shade_color_3"
    const val BLEND_MODE_LINEAR_LIGHT = "media_notification_blend_mode_linear_light"
    const val BLEND_MODE_LAB = "media_notification_blend_mode_lab"
    const val BLEND_MODE_PURE = "media_notification_blend_mode_pure"

    fun isGetBackgroundBlurOpened(method: Method): Boolean = isGetBackgroundBlurOpened(
        name = method.name,
        isStatic = Modifier.isStatic(method.modifiers),
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
    )

    internal fun isGetBackgroundBlurOpened(
        name: String,
        isStatic: Boolean,
        returnTypeName: String,
        parameterTypeNames: List<String>,
    ): Boolean {
        // (Context)Z only — the same class also declares a Configuration overload.
        return name == GET_BACKGROUND_BLUR_OPENED_METHOD &&
            isStatic &&
            returnTypeName == Boolean::class.javaPrimitiveType!!.name &&
            parameterTypeNames == listOf("android.content.Context")
    }

    fun isSetMiViewBlurMode(method: Method): Boolean = isSetMiViewBlurMode(
        name = method.name,
        isStatic = Modifier.isStatic(method.modifiers),
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
    )

    internal fun isSetMiViewBlurMode(
        name: String,
        isStatic: Boolean,
        returnTypeName: String,
        parameterTypeNames: List<String>,
    ): Boolean {
        // Main-package descriptor is (int, View) — the plugin variant reverses the arguments.
        return name == SET_MI_VIEW_BLUR_MODE_METHOD &&
            isStatic &&
            returnTypeName == Void.TYPE.name &&
            parameterTypeNames == listOf(
                Int::class.javaPrimitiveType!!.name,
                "android.view.View",
            )
    }

    fun isClearMiBackgroundBlendColor(method: Method): Boolean = isClearMiBackgroundBlendColor(
        name = method.name,
        isStatic = Modifier.isStatic(method.modifiers),
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
    )

    internal fun isClearMiBackgroundBlendColor(
        name: String,
        isStatic: Boolean,
        returnTypeName: String,
        parameterTypeNames: List<String>,
    ): Boolean {
        return name == CLEAR_MI_BACKGROUND_BLEND_COLOR_METHOD &&
            isStatic &&
            returnTypeName == Void.TYPE.name &&
            parameterTypeNames == listOf("android.view.View")
    }

    fun isSetMiBackgroundBlendColors(method: Method): Boolean = isSetMiBackgroundBlendColors(
        name = method.name,
        isStatic = Modifier.isStatic(method.modifiers),
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
    )

    internal fun isSetMiBackgroundBlendColors(
        name: String,
        isStatic: Boolean,
        returnTypeName: String,
        parameterTypeNames: List<String>,
    ): Boolean {
        // Exactly the synthetic (View, int[])V bridge the notification path calls; the same
        // class also declares setMiBackgroundBlendColors(View, [I, F) and a "…New$default".
        return name == SET_MI_BACKGROUND_BLEND_COLORS_METHOD &&
            isStatic &&
            returnTypeName == Void.TYPE.name &&
            parameterTypeNames == listOf(
                "android.view.View",
                IntArray::class.java.name,
            )
    }

    fun isSetMiViewMaterialType(method: Method): Boolean = isSetMiViewMaterialType(
        name = method.name,
        isStatic = Modifier.isStatic(method.modifiers),
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
    )

    internal fun isSetMiViewMaterialType(
        name: String,
        isStatic: Boolean,
        returnTypeName: String,
        parameterTypeNames: List<String>,
    ): Boolean {
        return name == SET_MI_VIEW_MATERIAL_TYPE_METHOD &&
            isStatic &&
            returnTypeName == Void.TYPE.name &&
            parameterTypeNames == listOf(
                Int::class.javaPrimitiveType!!.name,
                "android.view.View",
            )
    }

    fun isSetMiGlass(method: Method): Boolean = isSetMiGlass(
        name = method.name,
        isStatic = Modifier.isStatic(method.modifiers),
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
    )

    internal fun isSetMiGlass(
        name: String,
        isStatic: Boolean,
        returnTypeName: String,
        parameterTypeNames: List<String>,
    ): Boolean {
        return name == SET_MI_GLASS_METHOD &&
            isStatic &&
            returnTypeName == Void.TYPE.name &&
            parameterTypeNames == listOf(
                "android.view.View",
                FloatArray::class.java.name,
            )
    }

    /** Exact 42-float array embedded by OS4 `MediaViewGlassEffect.apply`. */
    fun defaultGlassParams(): FloatArray = floatArrayOf(
        0.67f, 0.16f, 0.09f, 0.0f, 0.24f, 1.4f, -0.02f,
        0.3f, 0.6f, 1.0f, 0.03f, 1.0f, 1.0f, 1.0f,
        0.1f, 0.2f, 0.3f, 1.0f, 1.0f, 72.0f, 3.8f,
        80.0f, 800.0f, 1.2f, 1.0f, -0.4f, 0.6f, -0.8f,
        1.4f, 0.7f, 0.8f, 1.15f, 4.0f, 2.0f,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
    )
}
