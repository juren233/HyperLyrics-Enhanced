/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus

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
    val sourceLabel = when (source) {
        "QM" -> "QQ"
        "KUWO" -> "酷我"
        "KUGOU" -> "酷狗"
        else -> "网易"
    }
    val contentLabel = when (contentType) {
        "pronunciation" -> "发音"
        "lyrics" -> "歌词"
        else -> "翻译"
    }
    return when (status) {
        OnlineSourceMenuStatus.STABLE -> sourceLabel + contentLabel
        OnlineSourceMenuStatus.SWITCHING -> "切换中"
        OnlineSourceMenuStatus.FAILED -> "切换失败"
    }
}

/**
 * 旧版补充歌词缓存只保存了正文和时间轴，无法可靠反推出当初采用的平台。
 * 在来源状态重新检索完成前，必须使用中性标题，不能假装它来自默认的网易。
 */
internal fun missingLyricsSourceMenuLabel(source: String?): String =
    source?.let { sourceMenuLabel(it, "lyrics") } ?: "歌词来源"

/**
 * `searched=true, found=false` 只表示没有匹配到可用歌词，并不等于请求失败。
 * 当前跨进程状态没有携带网络/解析异常原因，因此 UI 不得伪造“检索失败”。
 */
internal fun missingLyricsSourceStatusLabel(status: AppleMissingLyricsSourceStatus): String =
    when {
        status.searched && status.found ->
            "${if (status.wordTimed) "逐字歌词" else "逐行歌词"} · " +
                "${status.lineCount} 句"
        status.searched -> "未找到匹配歌词"
        else -> "正在获取来源信息"
    }

internal fun isMissingLyricsSourceSelectable(
    status: AppleMissingLyricsSourceStatus,
    selected: Boolean,
): Boolean = status.searched && status.found && !selected

internal fun currentLyricsMenuSongId(
    playbackSongId: String?,
    visibleLyricsSongId: String?,
): String? = playbackSongId?.trim()?.takeIf(String::isNotEmpty)
    ?: visibleLyricsSongId?.trim()?.takeIf(String::isNotEmpty)

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
