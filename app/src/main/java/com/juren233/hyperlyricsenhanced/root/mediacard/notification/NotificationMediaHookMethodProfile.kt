/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import java.lang.reflect.Method

/** Exact method-name/signature candidates verified against the HyperOS 4 media-card DEX. */
internal object NotificationMediaHookMethodProfile {
    const val VIEW_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl"
    const val LAYOUT_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl"

    const val UPDATE_FOREGROUND_COLORS = "updateForegroundColors"
    const val OS4_LOAD_LAYOUT = "loadLayout"
    const val OS4_UPDATE_LAYOUT = "updateLayout\$1"
    const val LEGACY_LOAD_LAYOUT = "loadLayout\$1"
    const val LEGACY_UPDATE_LAYOUT = "updateLayout\$6"

    // Known decompiler-only alias, rejected: "updateMediaBackground" exists only on
    // com.android.notification.tinypanel.FlipRowContainerController (flip cover panel) and
    // never on any HyperOS 4 media controller — verified in the original MiuiSystemUI dex of
    // OS4.0.0.6 / OS4.0.0.8 / OS4.0.0.34 (2026-09-12). Do not reintroduce it as a media target.
    const val REJECTED_GHOST_UPDATE_MEDIA_BACKGROUND = "updateMediaBackground"

    /**
     * Media card material effects from NotificationViewEffectHelper.mediaViewEffectsMap
     * (OS4.0.0.6 / OS4.0.0.8 / OS4.0.0.34 classes2.dex, verified 2026-09-12). Every effect
     * resolves its day/night background drawable, blend colors and integers from the
     * [android.content.Context] argument of the apply(Object, Context) entry declared by
     * NotificationViewEffectInterface — overriding that argument's uiMode is the only
     * binary-supported way to re-skin the native card background.
     */
    val mediaViewEffectClassNames = listOf(
        "$MEDIA_VIEW_EFFECT_PACKAGE.MediaViewNormalEffect",
        "$MEDIA_VIEW_EFFECT_PACKAGE.MediaViewBlurEffect",
        "$MEDIA_VIEW_EFFECT_PACKAGE.MediaViewBlurOnKeyguardEffect",
        "$MEDIA_VIEW_EFFECT_PACKAGE.MediaViewGlassEffect",
        "$MEDIA_VIEW_EFFECT_PACKAGE.MediaViewGlassOnKeyguardEffect",
        "$MEDIA_VIEW_EFFECT_PACKAGE.MediaViewGlassOnKeyguardLightWallPaperEffect",
        "$MEDIA_VIEW_EFFECT_PACKAGE.MediaViewGlassFullAodEffect",
    )
    const val MEDIA_VIEW_EFFECT_PACKAGE =
        "com.android.systemui.statusbar.notification.style.vieweffect"
    const val EFFECT_APPLY_METHOD = "apply"

    val layoutRefreshMethodNames = listOf(
        OS4_LOAD_LAYOUT,
        OS4_UPDATE_LAYOUT,
        LEGACY_LOAD_LAYOUT,
        LEGACY_UPDATE_LAYOUT,
    )

    /** Keep the XML-loading and ConstraintSet-application phases distinct. */
    val layoutLoadMethodNames = listOf(OS4_LOAD_LAYOUT, LEGACY_LOAD_LAYOUT)
    val layoutApplyMethodNames = listOf(OS4_UPDATE_LAYOUT, LEGACY_UPDATE_LAYOUT)

    fun isZeroArgVoid(method: Method, name: String): Boolean {
        return method.name == name &&
            method.parameterCount == 0 &&
            method.returnType == Void.TYPE
    }

    /** Pure night-mode override used to theme a material effect's resolution context. */
    fun overrideNightMode(uiMode: Int, dark: Boolean): Int {
        val nightMode = if (dark) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
        return (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    }

    fun isLayoutRefresh(method: Method): Boolean {
        return isLayoutRefresh(
            declaringClassName = method.declaringClass.name,
            name = method.name,
            returnTypeName = method.returnType.name,
            parameterCount = method.parameterCount,
        )
    }

    internal fun isLayoutRefresh(
        declaringClassName: String,
        name: String,
        returnTypeName: String,
        parameterCount: Int,
    ): Boolean {
        return declaringClassName == LAYOUT_CONTROLLER_CLASS &&
            name in layoutRefreshMethodNames &&
            parameterCount == 0 &&
            returnTypeName == Void.TYPE.name
    }
}
