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
    const val UPDATE_MEDIA_BACKGROUND = "updateMediaBackground"
    const val OS4_LOAD_LAYOUT = "loadLayout"
    const val OS4_UPDATE_LAYOUT = "updateLayout\$1"
    const val LEGACY_LOAD_LAYOUT = "loadLayout\$1"
    const val LEGACY_UPDATE_LAYOUT = "updateLayout\$6"

    val layoutRefreshMethodNames = listOf(
        OS4_LOAD_LAYOUT,
        OS4_UPDATE_LAYOUT,
        LEGACY_LOAD_LAYOUT,
        LEGACY_UPDATE_LAYOUT,
    )

    fun isZeroArgVoid(method: Method, name: String): Boolean {
        return method.name == name &&
            method.parameterCount == 0 &&
            method.returnType == Void.TYPE
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
