/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import io.github.proify.lyricon.amprovider.xposed.AppleSourceSwitchPerformanceDiagnostics
import io.github.proify.lyricon.lyric.model.Song as LyriconSong

internal fun LyriconSource.onDirectSongChanged(song: LyriconSong?) {
    val localSong = song?.toLocalSong()
    diagnostic(
        "stage=direct_song_callback, id=${localSong?.id}, title=${localSong?.name}, " +
            "lyrics=${localSong?.lyrics.orEmpty().size}, " +
            "activeCentralPlayer=$activeCentralPlayerPackageName",
    )
    currentDirectAppleSongId = localSong?.id
    appleDirectPositionReference = null
    val acceptDirect = AppleDirectSongRecoveryPolicy.shouldAccept(
        activePlayerPackage = activeCentralPlayerPackageName,
        activeProviderPackage = activeProviderPackageName,
        centralSongAvailable = centralAppleSongAvailable,
        appleMusicPackage = LyriconSource.APPLE_MUSIC_PACKAGE,
        builtInProviderPackage = LyriconSource.BUILT_IN_PROVIDER_PACKAGE,
    )
    if (!acceptDirect) {
        diagnostic(
            "stage=direct_song_callback_dropped, reason=central_song_authoritative, " +
                "centralSongAvailable=$centralAppleSongAvailable"
        )
        return
    }
    if (localSong != null) {
        logPlayerVersionSnapshot(
            playerPackageName = LyriconSource.APPLE_MUSIC_PACKAGE,
            providerPackageName = LyriconSource.BUILT_IN_PROVIDER_PACKAGE,
            processName = LyriconSource.APPLE_MUSIC_PACKAGE,
            source = "apple_direct",
        )
    }
    val providerPackage = activeProviderPackageName ?: LyriconSource.BUILT_IN_PROVIDER_PACKAGE
    activeProviderPackageName = providerPackage
    activeProviderDelayMs = readProviderDelay(providerPackage)
    LyriconDataBridge.updateLyricPackage(LyriconSource.APPLE_MUSIC_PACKAGE)
    handleAppleSong(localSong)
}

internal fun LyriconSource.onDirectPlaybackStateChanged(isPlaying: Boolean) {
    if (!hasActiveCentralPlayer() && !fallbackSongActive) {
        sink?.onPlaybackStateChanged(isPlaying)
    }
}

internal fun LyriconSource.onDirectPositionChanged(position: Long) {
    if (fallbackSongActive) return
    if (hasActiveCentralPlayer() && !centralAppleProviderActive) return
    if (centralAppleProviderActive && !isBuiltInAppleCentralProviderActive()) return
    if (centralAppleProviderActive && !directSongMatchesCurrentAppleSong()) return
    val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
    appleDirectPositionReference = AppleCentralPositionPolicy.DirectReference(
        songGeneration = appleSongGeneration,
        position = adjustedPosition,
        observedAtMs = SystemClock.elapsedRealtime(),
    )
    val resolution = resolveApplePosition(adjustedPosition, explicitSeek = false)
    lastAdjustedPosition = AppleCentralPositionPolicy.restorablePosition(
        previousPosition = lastAdjustedPosition,
        resolution = resolution,
    )
    logAppleTimingDiagnostic(
        if (centralAppleProviderActive) "direct_primary" else "direct",
        position,
        adjustedPosition,
        resolution = resolution,
    )
    resolution.position?.let {
        maybeCommitPendingOnlineTranslation(it)
        sink?.onPositionChanged(it)
    }
}

internal fun LyriconSource.onDirectSeekTo(position: Long) {
    if (fallbackSongActive) return
    if (hasActiveCentralPlayer() && !centralAppleProviderActive) return
    if (centralAppleProviderActive && !isBuiltInAppleCentralProviderActive()) return
    if (centralAppleProviderActive && !directSongMatchesCurrentAppleSong()) return
    val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
    appleDirectPositionReference = AppleCentralPositionPolicy.DirectReference(
        songGeneration = appleSongGeneration,
        position = adjustedPosition,
        observedAtMs = SystemClock.elapsedRealtime(),
    )
    val resolution = resolveApplePosition(adjustedPosition, explicitSeek = true)
    lastAdjustedPosition = AppleCentralPositionPolicy.restorablePosition(
        previousPosition = lastAdjustedPosition,
        resolution = resolution,
    )
    logAppleTimingDiagnostic(
        "direct_seek",
        position,
        adjustedPosition,
        resolution = resolution,
        force = true,
    )
    resolution.position?.let {
        maybeCommitPendingOnlineTranslation(it)
        sink?.onSeekTo(it)
    }
}

private fun LyriconSource.directSongMatchesCurrentAppleSong(): Boolean {
    val directSongId = currentDirectAppleSongId ?: return false
    val currentSongId = currentAppleSong?.id ?: return false
    return directSongId == currentSongId
}

internal fun LyriconSource.isBuiltInAppleCentralProviderActive(): Boolean =
    centralAppleProviderActive && activeProviderPackageName == LyriconSource.BUILT_IN_PROVIDER_PACKAGE

internal fun LyriconSource.onDirectText(text: String?) {
    if (!hasActiveCentralPlayer() && !fallbackSongActive) {
        sink?.onPlainText(simplifyAppleTextForDisplay(text))
    }
}

internal fun LyriconSource.activeSourceSwitchTraceRequest(songId: String?): OnlineSourceSwitchRequest? {
    val targetId = songId?.takeIf(String::isNotBlank) ?: return null
    val now = SystemClock.elapsedRealtime()
    return listOfNotNull(
        pendingLyricsSourceRequest,
        pendingTranslationSourceRequest,
        pendingPronunciationSourceRequest,
        latestSourceSwitchTraceRequest,
    ).firstOrNull { request ->
        request.songId == targetId &&
            now - request.startedAtMs <= LyriconSource.SOURCE_SWITCH_DIAGNOSTIC_WINDOW_MS
    }
}

internal fun LyriconSource.sourceSwitchCoreStage(
    request: OnlineSourceSwitchRequest?,
    stage: String,
    details: String = "",
) {
    if (!BuildConfig.DEBUG || request == null) return
    val elapsedMs = SystemClock.elapsedRealtime() - request.startedAtMs
    val context =
        "contentType=${request.contentType},requested=${request.requestedSource}," +
            "requestElapsedMs=$elapsedMs"
    AppleSourceSwitchPerformanceDiagnostics.coreStage(
        requestId = request.requestId,
        songId = request.songId,
        stage = stage,
        details = if (details.isBlank()) context else "$context,$details",
    )
}

internal fun LyriconSource.sourceSwitchCoreStage(
    songId: String?,
    stage: String,
    details: String = "",
) {
    sourceSwitchCoreStage(activeSourceSwitchTraceRequest(songId), stage, details)
}

internal fun LyriconSource.onDirectOnlineLyricContentSourceRequested(
    requestId: Long,
    songId: String?,
    contentType: String?,
    sourceName: String?,
) {
    val nativeSong = currentAppleSong
    if (
        contentType == "lyrics" &&
        sourceName == LyriconSource.APPLE_NATIVE_LYRICS_SOURCE &&
        restoreAppleNativeLyricsSource(requestId, songId)
    ) {
        return
    }
    val requestedSource = runCatching { Source.valueOf(sourceName.orEmpty()) }
        .getOrNull()
    AppleSourceSwitchPerformanceDiagnostics.coreStage(
        requestId = requestId,
        songId = songId,
        stage = "request_received",
        details = "contentType=${contentType ?: "none"},source=${sourceName ?: "none"}," +
            "currentSongId=${nativeSong?.id ?: "none"},fallbackGeneration=$fallbackGeneration," +
            "onlineTranslationGeneration=$onlineTranslationGeneration",
    )
    if (
        nativeSong == null ||
        songId.isNullOrBlank() ||
        songId != nativeSong.id ||
        requestedSource == null ||
        contentType !in setOf("translation", "pronunciation", "lyrics") ||
        (requestedSource == Source.LB && contentType != "lyrics") ||
        (contentType == "lyrics" && requestedSource == Source.LB &&
            !isLunaBeatWordLyricsEnabled()) ||
        (contentType == "lyrics" && requestedSource != Source.LB &&
            !isFillMissingLyricsEnabled())
    ) {
        AppleSourceSwitchPerformanceDiagnostics.coreStage(
            requestId = requestId,
            songId = songId,
            stage = "request_rejected",
            details = "contentType=${contentType ?: "none"},source=${sourceName ?: "none"}," +
                "currentSongId=${nativeSong?.id ?: "none"},requestedSource=$requestedSource," +
                "fillMissingLyrics=${isFillMissingLyricsEnabled()}",
        )
        diagnostic(
            "Apple Music 在线翻译来源切换拒绝: requestId=$requestId, " +
                "songId=$songId, currentSongId=${nativeSong?.id}, " +
                "contentType=$contentType, source=$sourceName"
        )
        directBridge?.publishOnlineTranslationSourceSwitchResult(
            requestId = requestId,
            songId = songId,
            contentType = contentType,
            requestedSource = sourceName,
            actualSource = null,
            successful = false,
        )
        return
    }
    val request = OnlineSourceSwitchRequest(
        requestId = requestId,
        songId = requireNotNull(songId),
        contentType = requireNotNull(contentType),
        requestedSource = requestedSource,
        startedAtMs = SystemClock.elapsedRealtime(),
    )
    latestSourceSwitchTraceRequest = request
    sourceSwitchCoreStage(
        request = request,
        stage = "request_accepted",
        details = "fallbackGeneration=$fallbackGeneration," +
            "onlineTranslationGeneration=$onlineTranslationGeneration",
    )
    when (contentType) {
        "translation" -> {
            temporaryTranslationSource = requestedSource
            pendingTranslationSourceRequest = request
        }
        "pronunciation" -> {
            temporaryPronunciationSource = requestedSource
            pendingPronunciationSourceRequest = request
        }
        "lyrics" -> {
            pendingLyricsSourceRequest = request
            diagnostic(
                "Apple Music 歌词来源切换接受: requestId=$requestId, " +
                    "songId=$songId, source=$requestedSource"
            )
            val previousFallbackGeneration = fallbackGeneration
            val previousFallbackJobActive = fallbackJob?.isActive == true
            val previousFallbackDelayPending = fallbackDelayRunnable != null
            cancelFallback(clearAppleSong = false, reason = "temporary_lyrics_source_switched")
            sourceSwitchCoreStage(
                request = request,
                stage = "previous_fallback_cancelled",
                details = "generation=$previousFallbackGeneration->$fallbackGeneration," +
                    "jobActive=$previousFallbackJobActive," +
                    "delayPending=$previousFallbackDelayPending",
            )
            // 歌词正文即将换成新来源，旧翻译任务必须作废并重新按新时间轴匹配；
            // 但已显示的翻译继续保留到新补充载荷到达，避免 Apple Music、超级岛
            // 和 AOD 在切换窗口先被主动清空。
            cancelOnlineTranslation(
                clearAttempt = true,
                clearMatched = false,
                reason = "temporary_lyrics_source_switched",
            )
            sourceSwitchCoreStage(
                request = request,
                stage = "previous_translation_cancelled",
                details = "onlineTranslationGeneration=$onlineTranslationGeneration",
            )
            scheduleFallback(
                baseSong = nativeSong,
                delayMs = 0L,
                preferredSourceOverride = requestedSource,
                strictSource = true,
            )
            sourceSwitchCoreStage(
                request = request,
                stage = "fallback_schedule_returned",
                details = "fallbackGeneration=$fallbackGeneration",
            )
            return
        }
    }
    diagnostic(
        "Apple Music 在线翻译来源切换接受: requestId=$requestId, " +
            "songId=$songId, contentType=$contentType, source=$requestedSource"
    )
    cancelOnlineTranslation(
        clearAttempt = true,
        clearMatched = false,
        reason = "temporary_" + contentType + "_source_switched",
    )
    sourceSwitchCoreStage(
        request = request,
        stage = "previous_translation_cancelled",
        details = "onlineTranslationGeneration=$onlineTranslationGeneration",
    )
    if (!scheduleOnlineTranslation(nativeSong)) {
        failPendingOnlineSourceSwitchRequest(request)
    }
}

private fun LyriconSource.restoreAppleNativeLyricsSource(requestId: Long, songId: String?): Boolean {
    val nativeSong = currentAppleNativeSong?.takeIf { candidate ->
        !songId.isNullOrBlank() && candidate.id == songId && hasAppleNativeLyrics(candidate)
    } ?: run {
        directBridge?.publishOnlineTranslationSourceSwitchResult(
            requestId = requestId,
            songId = songId,
            contentType = "lyrics",
            requestedSource = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
            actualSource = null,
            successful = false,
        )
        return true
    }
    cancelFallback(clearAppleSong = false, reason = "temporary_apple_lyrics_selected")
    cancelOnlineTranslation(
        clearAttempt = true,
        clearMatched = false,
        reason = "temporary_apple_lyrics_selected",
    )
    confirmedLyricsSourceSelection = ConfirmedLyricsSourceSelection(
        songId = requireNotNull(nativeSong.id),
        source = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
    )
    currentAppleSong = nativeSong
    currentAppleHasNativeLyrics = true
    fallbackSongActive = false
    stopMediaPositionPolling()
    publishAppleSong(nativeSong, restorePosition = true)
    if (needsOnlineEnrichment(nativeSong) && isAppleTranslationEnrichmentEnabled()) {
        scheduleOnlineTranslation(nativeSong)
    }
    directBridge?.publishOnlineTranslationSourceSwitchResult(
        requestId = requestId,
        songId = songId,
        contentType = "lyrics",
        requestedSource = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
        actualSource = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
        successful = true,
    )
    return true
}

internal fun LyriconSource.completePendingLyricsSourceRequest(
    song: LocalSong?,
    requestedSource: Source?,
) {
    val request = pendingLyricsSourceRequest ?: return
    if (requestedSource != null && request.requestedSource != requestedSource) return
    val actualSource = song
        ?.metadata
        ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
        ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
    sourceSwitchCoreStage(
        request = request,
        stage = "lyrics_result_resolved",
        details = "requestedArgument=${requestedSource ?: "none"}," +
            "actual=${actualSource ?: "none"},lines=${song?.lyrics.orEmpty().size}",
    )
    pendingLyricsSourceRequest = null
    if (actualSource == request.requestedSource) {
        confirmedLyricsSourceSelection = ConfirmedLyricsSourceSelection(
            songId = request.songId,
            source = actualSource.name,
        )
    }
    publishOnlineSourceSwitchResult(request, actualSource)
}

internal fun LyriconSource.completePendingOnlineSourceSwitchRequests(song: LocalSong?) {
    val translationSource = song
        ?.metadata
        ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
        ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
    val pronunciationSource = song
        ?.metadata
        ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
        ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
    listOfNotNull(
        pendingTranslationSourceRequest?.let { it to translationSource },
        pendingPronunciationSourceRequest?.let { it to pronunciationSource },
    ).forEach { (request, actualSource) ->
        sourceSwitchCoreStage(
            request = request,
            stage = "online_result_resolved",
            details = "actual=${actualSource ?: "none"},lines=${song?.lyrics.orEmpty().size}",
        )
        publishOnlineSourceSwitchResult(request, actualSource)
    }
    pendingTranslationSourceRequest = null
    pendingPronunciationSourceRequest = null
}

private fun LyriconSource.failPendingOnlineSourceSwitchRequest(request: OnlineSourceSwitchRequest) {
    sourceSwitchCoreStage(
        request = request,
        stage = "source_switch_failed_before_publish",
    )
    when (request.contentType) {
        "translation" -> {
            if (pendingTranslationSourceRequest?.requestId == request.requestId) {
                pendingTranslationSourceRequest = null
            }
        }
        "pronunciation" -> {
            if (pendingPronunciationSourceRequest?.requestId == request.requestId) {
                pendingPronunciationSourceRequest = null
            }
        }
    }
    publishOnlineSourceSwitchResult(request, actualSource = null)
}

private fun LyriconSource.publishOnlineSourceSwitchResult(
    request: OnlineSourceSwitchRequest,
    actualSource: Source?,
) {
    val successful = actualSource == request.requestedSource
    sourceSwitchCoreStage(
        request = request,
        stage = "result_binder_publish_started",
        details = "actual=${actualSource ?: "none"},successful=$successful," +
            "bridgePresent=${directBridge != null}",
    )
    diagnostic(
        "Apple Music 在线翻译来源切换完成: requestId=${request.requestId}, " +
            "songId=${request.songId}, contentType=${request.contentType}, " +
            "requested=${request.requestedSource}, actual=${actualSource ?: "none"}, " +
            "successful=$successful"
    )
    val publishStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    val published = directBridge?.publishOnlineTranslationSourceSwitchResult(
        requestId = request.requestId,
        songId = request.songId,
        contentType = request.contentType,
        requestedSource = request.requestedSource.name,
        actualSource = actualSource?.name,
        successful = successful,
    ) == true
    sourceSwitchCoreStage(
        request = request,
        stage = "result_binder_publish_finished",
        details = "actual=${actualSource ?: "none"},successful=$successful," +
            "published=$published,elapsedMs=" +
            ((SystemClock.elapsedRealtimeNanos() - publishStartedAtNanos) / 1_000_000.0),
    )
}
