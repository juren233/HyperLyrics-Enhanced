/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

internal fun effectiveOnlineSourceSelection(
    storedSource: String?,
    confirmedSource: String?,
    onlineContentConsumed: Boolean,
): String? = if (onlineContentConsumed) confirmedSource ?: storedSource else null

internal fun sourceMenuPresentation(
    actualSource: String?,
    pendingTargetSource: String?,
    failedSource: String?,
): OnlineSourceMenuPresentation? = when {
    pendingTargetSource != null -> OnlineSourceMenuPresentation(
        source = pendingTargetSource,
        status = OnlineSourceMenuStatus.SWITCHING,
    )
    failedSource != null -> OnlineSourceMenuPresentation(
        source = failedSource,
        status = OnlineSourceMenuStatus.FAILED,
    )
    actualSource != null -> OnlineSourceMenuPresentation(
        source = actualSource,
        status = OnlineSourceMenuStatus.STABLE,
    )
    else -> null
}

internal fun sourceMenuLabel(
    source: String,
    contentType: String,
    status: OnlineSourceMenuStatus = OnlineSourceMenuStatus.STABLE,
): String {
    val sourceLabel = if (source == "QM") "QQ" else "网易"
    val contentLabel = if (contentType == "pronunciation") "发音" else "翻译"
    return when (status) {
        OnlineSourceMenuStatus.STABLE -> sourceLabel + contentLabel
        OnlineSourceMenuStatus.SWITCHING -> "切换中"
        OnlineSourceMenuStatus.FAILED -> "切换失败"
    }
}

internal fun sourceMenuWidth(vararg candidates: Int): Int =
    candidates.asSequence().filter { it > 0 }.maxOrNull() ?: 1

internal fun shouldDeferNativeTranslationPresentationRefresh(
    activeMenuSongId: String?,
    popupShowing: Boolean,
    expectedSongId: String?,
): Boolean =
    popupShowing &&
        !activeMenuSongId.isNullOrBlank() &&
        (expectedSongId.isNullOrBlank() || activeMenuSongId == expectedSongId)
