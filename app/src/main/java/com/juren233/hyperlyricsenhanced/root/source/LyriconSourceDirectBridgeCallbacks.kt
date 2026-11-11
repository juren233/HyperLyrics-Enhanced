/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.AppleMetadataFlowDiagnostics
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.contract.AppleDirectBridgeContract
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.contract.AppleSourceSwitchPerformanceDiagnostics
import io.github.proify.lyricon.lyric.model.Song as LyriconSong

internal fun LyriconSource.onDirectSongChanged(song: LyriconSong?) {
    val localSong = song?.toLocalSong()
    diagnostic(
        "stage=direct_song_callback, id=${localSong?.id}, title=${localSong?.name}, " +
            "lyrics=${localSong?.lyrics.orEmpty().size}, " +
            "activeCentralPlayer=$activeCentralPlayerPackageName",
    )
    applePositionState.setDirectSongId(localSong?.id)
    applePositionState.setDirectReference(null)
    val acceptDirect = AppleDirectSongRecoveryPolicy.shouldAccept(
        activePlayerPackage = activeCentralPlayerPackageName,
        activeProviderPackage = activeProviderPackageName,
        centralSongAvailable = centralAppleSongAvailable,
        appleMusicPackage = LyriconSource.APPLE_MUSIC_PACKAGE,
        builtInProviderPackage = LyriconSource.BUILT_IN_PROVIDER_PACKAGE,
    )
    if (BuildConfig.DEBUG) AppleMetadataFlowDiagnostics.record("direct_received") {
        "accepted=$acceptDirect centralAvailable=$centralAppleSongAvailable " +
            "player=$activeCentralPlayerPackageName provider=$activeProviderPackageName " +
            "incoming=${AppleMetadataFlowDiagnostics.local(localSong)} " +
            "current=${AppleMetadataFlowDiagnostics.local(currentAppleSong)}"
    }
    if (!acceptDirect) {
        diagnostic(
            "stage=direct_song_callback_dropped, reason=central_song_authoritative, " +
                "centralSongAvailable=$centralAppleSongAvailable"
        )
        // 只有别的 App 是活动播放者时才存入应用内槽位；Apple Music 自己是活动播放者时
        // （Central 快照已权威）被拒的直连歌曲照旧丢弃，正式路径行为不变。
        if (activeCentralPlayerPackageName != LyriconSource.APPLE_MUSIC_PACKAGE) {
            acceptInAppAppleSong(localSong)
        }
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
    handleAppleSong(localSong)
}

internal fun LyriconSource.onDirectPlaybackStateChanged(isPlaying: Boolean) {
    // Apple 直连桥的播放态来自 app 进程内（缓冲期仍为播放中），转发给统一时间轴
    // 作播放态提示；MediaSession 假暂停期间据此维持渲染层播放态，避免岛缩回。
    sink?.onSourcePlaybackHint(isPlaying, LyriconSource.APPLE_MUSIC_PACKAGE)
    diagnostic("来源侧播放态已转发为提示: playing=$isPlaying")
}

internal fun LyriconSource.onDirectPositionChanged(position: Long) {
    if (fallbackSongActive) return
    if (hasActiveCentralPlayer() && !centralAppleProviderActive) return
    if (centralAppleProviderActive && !isBuiltInAppleCentralProviderActive()) return
    if (centralAppleProviderActive && !directSongMatchesCurrentAppleSong()) return
    val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
    applePositionState.setDirectReference(AppleCentralPositionPolicy.DirectReference(
        songGeneration = applePositionState.songGeneration(),
        position = adjustedPosition,
        observedAtMs = SystemClock.elapsedRealtime(),
    ))
    val resolution = resolveApplePosition(adjustedPosition, explicitSeek = false)
    applePositionState.applyRestorablePosition(resolution)
    logAppleTimingDiagnostic(
        if (centralAppleProviderActive) "direct_primary" else "direct",
        position,
        adjustedPosition,
        resolution = resolution,
    )
    resolution.position?.let {
        maybeCommitPendingOnlineTranslation(it)
    }
}

internal fun LyriconSource.onDirectSeekTo(position: Long) {
    if (fallbackSongActive) return
    if (hasActiveCentralPlayer() && !centralAppleProviderActive) return
    if (centralAppleProviderActive && !isBuiltInAppleCentralProviderActive()) return
    if (centralAppleProviderActive && !directSongMatchesCurrentAppleSong()) return
    val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
    applePositionState.setDirectReference(AppleCentralPositionPolicy.DirectReference(
        songGeneration = applePositionState.songGeneration(),
        position = adjustedPosition,
        observedAtMs = SystemClock.elapsedRealtime(),
    ))
    val resolution = resolveApplePosition(adjustedPosition, explicitSeek = true)
    applePositionState.applyRestorablePosition(resolution)
    logAppleTimingDiagnostic(
        "direct_seek",
        position,
        adjustedPosition,
        resolution = resolution,
        force = true,
    )
    resolution.position?.let {
        maybeCommitPendingOnlineTranslation(it)
    }
}

private fun LyriconSource.directSongMatchesCurrentAppleSong(): Boolean {
    val directSongId = applePositionState.directSongId() ?: return false
    val currentSongId = currentAppleSong?.id ?: return false
    return directSongId == currentSongId
}

internal fun LyriconSource.isBuiltInAppleCentralProviderActive(): Boolean =
    centralAppleProviderActive && activeProviderPackageName == LyriconSource.BUILT_IN_PROVIDER_PACKAGE

internal fun LyriconSource.onDirectText(text: String?) {
    diagnostic("忽略来源侧纯文本帧；统一时间轴只接受完整 Song")
}

internal fun LyriconSource.activeSourceSwitchTraceRequest(songId: String?): OnlineSourceSwitchRequest? {
    val targetId = songId?.takeIf(String::isNotBlank) ?: return null
    val now = SystemClock.elapsedRealtime()
    return (
        manualSourceRequests.pendingRequests() +
            inAppApple.target.manual.pendingRequests() +
            listOfNotNull(latestSourceSwitchTraceRequest)
        )
        .firstOrNull { request ->
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
    val sourceControl = isAlwaysShowSourceSwitchEnabled()
    if (contentType == AppleDirectBridgeContract.CONTENT_TYPE_SOURCE_STATUS) {
        requestAppleSourceStatuses(requestId, songId)
        return
    }
    // 歌词页显示的歌若不是活动播放者的 Apple 歌曲，而在应用内槽位里：按「仅应用内」处理，
    // 检索与结果都只在应用内槽位，不上岛。
    val inAppSong = if (sourceControl && currentAppleSong?.id != songId) {
        inAppApple.songFor(songId)
    } else {
        null
    }
    val target = if (inAppSong != null) inAppApple.target else activeOnlineTranslationTarget
    val nativeSong = inAppSong ?: currentAppleSong
    if (
        contentType == "lyrics" &&
        sourceName == LyriconSource.APPLE_NATIVE_LYRICS_SOURCE
    ) {
        if (target.inAppOnly) {
            restoreInAppNativeLyricsSource(requestId, songId)
            return
        }
        if (restoreAppleNativeLyricsSource(requestId, songId)) return
    }
    if (
        sourceControl &&
        sourceName == LyriconSource.APPLE_NATIVE_LYRICS_SOURCE &&
        (contentType == "translation" || contentType == "pronunciation")
    ) {
        restoreAppleNativeOnlineContent(requestId, songId, requireNotNull(contentType), target)
        return
    }
    val requestedSource = runCatching { Source.valueOf(sourceName.orEmpty()) }
        .getOrNull()
    AppleSourceSwitchPerformanceDiagnostics.coreStage(
        requestId = requestId,
        songId = songId,
        stage = "request_received",
        details = "contentType=${contentType ?: "none"},source=${sourceName ?: "none"}," +
            "currentSongId=${nativeSong?.id ?: "none"}," +
            "fallbackGeneration=${target.fallback.generation()}," +
            "onlineTranslationGeneration=${target.request.snapshot().generation}",
    )
    if (
        nativeSong == null ||
        songId.isNullOrBlank() ||
        songId != nativeSong.id ||
        requestedSource == null ||
        contentType !in setOf("translation", "pronunciation", "lyrics") ||
        // 翻译/发音候选以实际参与检索的来源为准（Apple Music 下含已启用的目录源）。
        (contentType != "lyrics" &&
            requestedSource !in configuredTranslationSources(LyriconSource.APPLE_MUSIC_PACKAGE)) ||
        (contentType == "lyrics" && requestedSource !in configuredOnlineSources()) ||
        // 搜索类来源的歌词只在「为无歌词的歌曲补充歌词」开启时自动使用；
        // 「始终显示切换源按钮」下的手动选择不受这个自动开关限制。
        (contentType == "lyrics" && !requestedSource.isTtmlCatalog &&
            !isFillMissingLyricsEnabled() && !sourceControl)
    ) {
        AppleSourceSwitchPerformanceDiagnostics.coreStage(
            requestId = requestId,
            songId = songId,
            stage = "request_rejected",
            details = "contentType=${contentType ?: "none"},source=${sourceName ?: "none"}," +
                "currentSongId=${nativeSong?.id ?: "none"},requestedSource=$requestedSource," +
                "fillMissingLyrics=${isFillMissingLyricsEnabled()},sourceControl=$sourceControl",
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
        details = "fallbackGeneration=${target.fallback.generation()}," +
            "onlineTranslationGeneration=${target.request.snapshot().generation}",
    )
    when (contentType) {
        "translation" -> {
            target.manual.acceptTranslationRequest(request, requestedSource)
        }
        "pronunciation" -> {
            target.manual.acceptPronunciationRequest(request, requestedSource)
        }
        "lyrics" -> {
            target.manual.acceptLyricsRequest(request)
            diagnostic(
                "Apple Music 歌词来源切换接受: requestId=$requestId, " +
                    "songId=$songId, source=$requestedSource, inAppOnly=${target.inAppOnly}"
            )
            val previousFallback = target.fallback.snapshot()
            cancelFallback(
                clearAppleSong = false,
                reason = "temporary_lyrics_source_switched",
                target = target,
            )
            sourceSwitchCoreStage(
                request = request,
                stage = "previous_fallback_cancelled",
                details = "generation=${previousFallback.generation}->" +
                    "${target.fallback.generation()}," +
                    "jobActive=${previousFallback.jobActive}," +
                    "delayPending=${previousFallback.delayPending}",
            )
            // 歌词正文即将换成新来源，旧翻译任务必须作废并重新按新时间轴匹配；
            // 但已显示的翻译继续保留到新补充载荷到达，避免 Apple Music、超级岛
            // 和 AOD 在切换窗口先被主动清空。
            cancelOnlineTranslation(
                clearAttempt = true,
                clearMatched = false,
                reason = "temporary_lyrics_source_switched",
                target = target,
            )
            sourceSwitchCoreStage(
                request = request,
                stage = "previous_translation_cancelled",
                details = "onlineTranslationGeneration=${target.request.snapshot().generation}",
            )
            scheduleFallback(
                baseSong = nativeSong,
                delayMs = 0L,
                preferredSourceOverride = requestedSource,
                strictSource = true,
                target = target,
            )
            sourceSwitchCoreStage(
                request = request,
                stage = "fallback_schedule_returned",
                details = "fallbackGeneration=${target.fallback.generation()}",
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
        target = target,
    )
    sourceSwitchCoreStage(
        request = request,
        stage = "previous_translation_cancelled",
        details = "onlineTranslationGeneration=${target.request.snapshot().generation}",
    )
    if (!scheduleOnlineTranslation(nativeSong, target)) {
        failPendingOnlineSourceSwitchRequest(request, target)
    }
}

/**
 * 来源弹窗打开时由 Apple Music 侧发来：检索全部已启用来源并回传各来源状态，不改动显示内容。
 * 结果随在线翻译载荷的来源元数据回传，这里不单独应答。
 */
private fun LyriconSource.requestAppleSourceStatuses(requestId: Long, songId: String?) {
    val activeSong = currentAppleSong
    // 歌词页显示的歌不是活动播放者的 Apple 歌曲时，命中应用内槽位也要检索（仅应用内，不上岛）。
    val inAppSong = if (activeSong?.id != songId) inAppApple.songFor(songId) else null
    val nativeSong = activeSong?.takeIf { it.id == songId } ?: inAppSong
    if (
        !isAlwaysShowSourceSwitchEnabled() ||
        nativeSong == null ||
        songId.isNullOrBlank()
    ) {
        diagnostic(
            "Apple Music 来源状态请求忽略: requestId=$requestId, songId=$songId, " +
                "currentSongId=${activeSong?.id}, inAppSongId=${inAppApple.song()?.id}, " +
                "enabled=${isAlwaysShowSourceSwitchEnabled()}"
        )
        return
    }
    val target = if (inAppSong != null) inAppApple.target else activeOnlineTranslationTarget
    // 此前的检索若只为自动补全而跑（没有来源弹窗的统计），同一首歌的尝试记录会让这次调度
    // 被当作重复而跳过；先作废那次尝试，已显示的内容保留到新结果到达。
    val searchedForDialog = wantsSourceControlSearch(nativeSong, target)
    target.manual.noteSourceStatusRequest(songId)
    if (!searchedForDialog) {
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = false,
            reason = "source_status_requested",
            target = target,
        )
    }
    // 同一首歌、同一 attempt、同样歌词行且取词结果仍在有效期：重开弹窗直接重发上一轮的完整结果，
    // 不重新检索，也不逐个来源刷新（正式路径与应用内路径同一处判定）。
    val reusable = target.reusableFinalPayload(nativeSong, translationIdentity(nativeSong))
    if (reusable != null) {
        directBridge?.publishOnlineTranslation(song = reusable)
        diagnostic(
            "Apple Music 来源状态请求: requestId=$requestId, songId=$songId, " +
                "inAppOnly=${target.inAppOnly}, reused=true"
        )
        return
    }
    val scheduled = !hasUntimedAppleNativeLyrics(nativeSong) &&
        scheduleOnlineTranslation(nativeSong, target)
    // 应用内的无歌词歌曲没有可匹配的翻译基准：改为检索各歌词来源，把状态回传给弹窗（不应用任何歌词）。
    if (target.inAppOnly && nativeSong.lyrics.isNullOrEmpty() && !target.fallback.snapshot().pending) {
        scheduleFallback(
            baseSong = nativeSong,
            delayMs = 0L,
            preferredSourceOverride = null,
            strictSource = false,
            target = target,
        )
    }
    diagnostic(
        "Apple Music 来源状态请求: requestId=$requestId, songId=$songId, " +
            "inAppOnly=${target.inAppOnly}, scheduled=$scheduled"
    )
}

/**
 * 翻译或发音改回「Apple Music 原生」：该内容类型本曲只用原生，立即按已发布内容还原，
 * 再重排一次检索，让后续结果（含另一内容类型的手动选择）按新状态合成。
 */
private fun LyriconSource.restoreAppleNativeOnlineContent(
    requestId: Long,
    songId: String?,
    contentType: String,
    target: OnlineTranslationTarget,
) {
    val nativeSong = (if (target.inAppOnly) inAppApple.song() else currentAppleSong)
        ?.takeIf { !songId.isNullOrBlank() && it.id == songId }
    if (nativeSong == null) {
        directBridge?.publishOnlineTranslationSourceSwitchResult(
            requestId = requestId,
            songId = songId,
            contentType = contentType,
            requestedSource = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
            actualSource = null,
            successful = false,
        )
        return
    }
    val translation = contentType == "translation"
    if (translation) {
        target.manual.selectNativeTranslation()
    } else {
        target.manual.selectNativePronunciation()
    }
    diagnostic(
        "Apple Music 在线内容改回原生: requestId=$requestId, songId=$songId, " +
            "contentType=$contentType"
    )
    cancelOnlineTranslation(
        clearAttempt = true,
        clearMatched = false,
        reason = "temporary_" + contentType + "_native_selected",
        target = target,
    )
    val sourceKey = if (translation) {
        LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE
    } else {
        LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE
    }
    val overrideKey = if (translation) {
        LyricMetadataKeys.ONLINE_TRANSLATION_OVERRIDES_NATIVE
    } else {
        LyricMetadataKeys.ONLINE_PRONUNCIATION_OVERRIDES_NATIVE
    }
    fun restored(song: LocalSong): LocalSong = with(OnlineSourceOverride) {
        val content = if (translation) {
            restoreNativeTranslation(song, nativeSong)
        } else {
            restoreNativePronunciation(song, nativeSong)
        }
        content.withMetadataValue(sourceKey, null).withMetadataFlag(overrideKey, false)
    }
    val published = (if (target.inAppOnly) inAppApple.publishedSong() else currentPublishedAppleSong)
        ?.takeIf { isSameTrack(it, nativeSong) }
    if (published != null && OnlineSourceOverride.changesContent(nativeSong, published)) {
        val restoredSong = restored(published)
        if (target.inAppOnly) {
            // 应用内模式不上岛；应用内页面的内容随下面重排的检索结果重新回传。
            inAppApple.recordPublished(restoredSong)
        } else {
            val stillOnline = OnlineSourceOverride.changesContent(nativeSong, restoredSong)
            publication.acceptEnrichment(stillOnline)
            publishAppleSong(
                restoredSong,
                restorePosition = true,
                onlineTranslationMatched = stillOnline,
                publishToSink = isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE),
            )
        }
    }
    directBridge?.publishOnlineTranslationSourceSwitchResult(
        requestId = requestId,
        songId = songId,
        contentType = contentType,
        requestedSource = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
        actualSource = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
        successful = true,
    )
    // 应用内歌词页的内容随检索结果重新发布；缓存命中时只是本地重新合成。
    if (!hasUntimedAppleNativeLyrics(nativeSong)) {
        scheduleOnlineTranslation(nativeSong, target, selectionChanged = true)
    }
}

/**
 * 应用内槽位切回「Apple 原生」歌词：槽位歌曲回到原生基准并记下选择，只回传结果、不上岛；
 * 翻译/发音按原生时间轴重新检索（与正式路径同一规则）。
 */
private fun LyriconSource.restoreInAppNativeLyricsSource(requestId: Long, songId: String?) {
    val target = inAppApple.target
    val nativeSong = inAppApple.nativeSong()?.takeIf { candidate ->
        !songId.isNullOrBlank() && candidate.id == songId && hasAppleNativeLyrics(candidate)
    }
    if (nativeSong == null) {
        directBridge?.publishOnlineTranslationSourceSwitchResult(
            requestId = requestId,
            songId = songId,
            contentType = "lyrics",
            requestedSource = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
            actualSource = null,
            successful = false,
        )
        return
    }
    cancelFallback(clearAppleSong = false, reason = "temporary_apple_lyrics_selected", target = target)
    cancelOnlineTranslation(
        clearAttempt = true,
        clearMatched = false,
        reason = "temporary_apple_lyrics_selected",
        target = target,
    )
    inAppApple.restoreNative(ConfirmedLyricsSourceSelection(
        songId = requireNotNull(nativeSong.id),
        source = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
    ))
    inAppApple.recordPublished(null)
    if (wantsSourceControlSearch(nativeSong, target) && !hasUntimedAppleNativeLyrics(nativeSong)) {
        scheduleOnlineTranslation(nativeSong, target)
    }
    directBridge?.publishOnlineTranslationSourceSwitchResult(
        requestId = requestId,
        songId = songId,
        contentType = "lyrics",
        requestedSource = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
        actualSource = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
        successful = true,
    )
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
    publication.confirmLyricsSource(ConfirmedLyricsSourceSelection(
        songId = requireNotNull(nativeSong.id),
        source = LyriconSource.APPLE_NATIVE_LYRICS_SOURCE,
    ))
    publication.acceptAppleInput(nativeSong, true)
    publication.cancelAppleFallback(clearSong = false)
    stopMediaPositionPolling()
    publishAppleSong(
        nativeSong,
        restorePosition = true,
        origin = LyricPublicationOrigin.MANUAL,
    )
    if (
        (needsOnlineEnrichment(nativeSong) && isAppleTranslationEnrichmentEnabled()) ||
        wantsSourceControlSearch(nativeSong)
    ) {
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
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
) {
    val request = target.manual.lyricsRequestToComplete(requestedSource) ?: return
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
    target.manual.clearLyricsRequest(request)
    if (actualSource == request.requestedSource) {
        val selection = ConfirmedLyricsSourceSelection(
            songId = request.songId,
            source = actualSource.name,
        )
        if (target.inAppOnly) inAppApple.confirmLyricsSource(selection) else publication.confirmLyricsSource(selection)
    }
    publishOnlineSourceSwitchResult(request, actualSource)
}

internal fun LyriconSource.completePendingOnlineSourceSwitchRequests(
    song: LocalSong?,
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
) {
    val translationSource = song
        ?.metadata
        ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
        ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
    val pronunciationSource = song
        ?.metadata
        ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
        ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
    target.manual.onlineRequestsToComplete(
        translationSource = translationSource,
        pronunciationSource = pronunciationSource,
    ).forEach { (request, actualSource) ->
        sourceSwitchCoreStage(
            request = request,
            stage = "online_result_resolved",
            details = "actual=${actualSource ?: "none"},lines=${song?.lyrics.orEmpty().size}",
        )
        publishOnlineSourceSwitchResult(request, actualSource)
    }
    target.manual.clearOnlineRequests()
}

/** 槽位里还没完成的翻译/发音切换请求都以失败结束（回传给插件）。 */
internal fun LyriconSource.failPendingOnlineSourceSwitchRequests(target: OnlineTranslationTarget) {
    target.manual.pendingRequests()
        .filter { it.contentType == "translation" || it.contentType == "pronunciation" }
        .forEach { failPendingOnlineSourceSwitchRequest(it, target) }
}

private fun LyriconSource.failPendingOnlineSourceSwitchRequest(
    request: OnlineSourceSwitchRequest,
    target: OnlineTranslationTarget,
) {
    sourceSwitchCoreStage(
        request = request,
        stage = "source_switch_failed_before_publish",
    )
    target.manual.failRequest(request)
    publishOnlineSourceSwitchResult(request, actualSource = null)
}

internal fun LyriconSource.publishOnlineSourceSwitchResult(
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
