/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import java.lang.ref.WeakReference

internal data class ActiveOnlineSourceMenu(
    val popup: WeakReference<PopupWindow>,
    val menu: WeakReference<LinearLayout>,
    val anchor: WeakReference<View>,
    val songId: String,
    val nativeMinimumWidth: Int,
)

internal data class PendingOnlineSourceSwitch(
    val requestId: Long,
    val songId: String,
    val contentType: String,
    val previousSource: String,
    val targetSource: String,
)

internal data class FailedOnlineSourceSwitch(
    val requestId: Long,
    val songId: String,
    val contentType: String,
    val displayedSource: String,
)

internal data class ConfirmedOnlineSourceSelection(
    val songId: String,
    val contentType: String,
    val source: String,
)

internal enum class OnlineSourceMenuStatus {
    STABLE,
    SWITCHING,
    FAILED,
}

internal data class OnlineSourceMenuPresentation(
    val source: String,
    val status: OnlineSourceMenuStatus,
)
