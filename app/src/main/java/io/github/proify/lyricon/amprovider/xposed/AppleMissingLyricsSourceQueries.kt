/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.ChineseLyricsPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import io.github.libxposed.api.XposedInterface.Chain
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal fun AppleMissingLyricsHooks.hasTranslation(songId: String?): Boolean =
    store.hasTranslation(resolveSupplementContentId(songId))

internal fun AppleMissingLyricsHooks.translationSource(songId: String?): String? =
    store.translationSource(resolveSupplementContentId(songId))

internal fun AppleMissingLyricsHooks.translationMatchPercentage(songId: String?, source: String): Int? =
    store.translationMatchPercentage(resolveSupplementContentId(songId), source)

internal fun AppleMissingLyricsHooks.pronunciationMatchPercentage(songId: String?, source: String): Int? =
    store.pronunciationMatchPercentage(resolveSupplementContentId(songId), source)

internal fun AppleMissingLyricsHooks.pronunciationSource(songId: String?): String? =
    store.pronunciationSource(resolveSupplementContentId(songId))

internal fun AppleMissingLyricsHooks.translationForLine(
    songId: String?,
    begin: Long,
    end: Long,
    text: String?,
): String? = store.translation(resolveSupplementContentId(songId), begin, end, text)

internal fun AppleMissingLyricsHooks.sourceInfo(songId: String?): AppleMissingLyricsSourceInfo? {
    songId?.takeIf(String::isNotBlank)?.let(::restoreCachedSupplement)
    val resolvedSongId = resolveStoredSupplementContentId(songId)
    val info = store.sourceInfo(resolvedSongId) ?: return null
    val selected = resolvedSongId?.let({ lyricsSourceSelection.selected(it) })
        ?: info.selectedSource
    return info.copy(selectedSource = selected)
}

internal fun AppleMissingLyricsHooks.availableLyricsSources(songId: String?): List<String> {
    val contentSongId = songId?.takeIf(String::isNotBlank) ?: return emptyList()
    val info = sourceInfo(contentSongId) ?: return emptyList()
    val lunaBeatAvailable = hasLunaBeatSource(contentSongId)
    val foundSources = info.statuses
        .filter { status ->
            status.found && (status.source != AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT || lunaBeatAvailable)
        }
        .mapTo(linkedSetOf()) { it.source }
    if (AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT in foundSources && hasKnownNativeLyrics(contentSongId)) {
        return listOf(AppleMissingLyricsHooks.Companion.SourceName.APPLE_NATIVE, AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT)
    }
    return buildList {
        if (hasKnownNativeLyrics(contentSongId)) add(AppleMissingLyricsHooks.Companion.SourceName.APPLE_NATIVE)
        addAll(info.statuses.map { it.source }.distinct())
    }
}

internal fun AppleMissingLyricsHooks.hasLunaBeatSource(songId: String?): Boolean {
    val contentSongId = resolveStoredSupplementContentId(songId) ?: return false
    val info = store.sourceInfo(contentSongId) ?: return false
    return isLunaBeatEligibleForSong(contentSongId, info)
}

internal fun AppleMissingLyricsHooks.isLunaBeatEligibleForSong(
    songId: String,
    sourceInfo: AppleMissingLyricsSourceInfo? = store.sourceInfo(songId),
    lineCountOverride: Int? = null,
): Boolean {
    if (!isLunaBeatWordLyricsEnabled()) return false
    val status = sourceInfo?.statuses.orEmpty().firstOrNull {
        it.source == AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT && it.found && it.wordTimed
    }
    val lunaBeatLineCount = lineCountOverride ?: status?.lineCount ?: return false
    return shouldUseLunaBeatOverAppleNativeLyrics(
        nativeStats = nativeLyricsTimingStats(songId),
        lunaBeatLineCount = lunaBeatLineCount,
    )
}

internal fun AppleMissingLyricsHooks.eligibleLunaBeatSourceInfo(
    songId: String,
    sourceInfo: AppleMissingLyricsSourceInfo?,
): AppleMissingLyricsSourceInfo? = sourceInfo?.takeIf {
    isLunaBeatEligibleForSong(songId, it)
}

internal fun AppleMissingLyricsHooks.nativeLyricsTimingStats(songId: String): AppleNativeLyricsTimingStats? {
    nativeAlternatives.timing(songId)?.let { return it }
    val identity = store.playbackIdentity(songId)
    return identity?.adamId?.toString()?.let({ nativeAlternatives.timing(it) })
}

internal fun AppleMissingLyricsHooks.isLunaBeatSupplement(song: Song): Boolean =
    song.metadata
        ?.getString(com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE) ==
        AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT

internal fun AppleMissingLyricsHooks.shouldPreferLunaBeat(songId: String?): Boolean {
    val contentSongId = songId?.takeIf(String::isNotBlank) ?: return false
    if (!isLunaBeatWordLyricsEnabled() || !store.hasContent(contentSongId)) return false
    val selectedSource = lyricsSourceSelection.selected(contentSongId)
        ?: store.sourceInfo(contentSongId)?.selectedSource
    return selectedSource == AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT &&
        isLunaBeatEligibleForSong(contentSongId)
}

internal fun AppleMissingLyricsHooks.onLyricsSourceSelectionChanged(
    songId: String?,
    source: String?,
    successful: Boolean,
): String? {
    val contentSongId = songId?.takeIf(String::isNotBlank) ?: return null
    if (!successful || source !in setOf(AppleMissingLyricsHooks.Companion.SourceName.APPLE_NATIVE, AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT)) {
        return null
    }
    val effectiveSource = if (
        source == AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT && !isLunaBeatEligibleForSong(contentSongId)
    ) {
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple Music LunaBeat 来源切换被原生逐字歌词拒绝: id=$contentSongId"
            )
        }
        AppleMissingLyricsHooks.Companion.SourceName.APPLE_NATIVE
    } else {
        requireNotNull(source)
    }
    lyricsSourceSelection.select(contentSongId, effectiveSource)

    val lunaBeatPointer = store.nativeSongInfoPointer(contentSongId)
    val appleNativePointer = nativeAlternatives.pointer(contentSongId)
        ?.takeIf { readNativeLineCount(it) > 0 }
    val action = appleLyricsSourcePresentationAction(
        source = effectiveSource,
        hasLunaBeatPointer = lunaBeatPointer != null,
        hasAppleNativePointer = appleNativePointer != null,
    )
    when (action) {
        AppleLyricsSourcePresentationAction.PRESENT_LUNA_BEAT -> {
            candidates.accept(contentSongId)
            requestLyricsSourcePresentation(
                songId = contentSongId,
                source = AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT,
                pointer = requireNotNull(lunaBeatPointer),
            )
        }
        AppleLyricsSourcePresentationAction.BUILD_LUNA_BEAT -> {
            candidates.accept(contentSongId)
            scheduleNativeLyricsModel(contentSongId)
        }
        AppleLyricsSourcePresentationAction.PRESENT_APPLE_NATIVE -> {
            requestLyricsSourcePresentation(
                songId = contentSongId,
                source = AppleMissingLyricsHooks.Companion.SourceName.APPLE_NATIVE,
                pointer = requireNotNull(appleNativePointer),
            )
        }
        AppleLyricsSourcePresentationAction.REFRESH_ONLY -> {
            if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "Apple Music 歌词来源呈现仅刷新播放页: " +
                        "id=$contentSongId, source=$effectiveSource, reason=target_pointer_missing"
                )
            }
        }
    }
    refreshNowPlaying(contentSongId)
    return effectiveSource
}

internal fun AppleMissingLyricsHooks.requestLyricsSourcePresentation(
    songId: String,
    source: String,
    pointer: Any,
) {
    val pointerIdentity = "${pointer.javaClass.name}@${System.identityHashCode(pointer)}"
    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
        songId = songId,
        stage = "lyrics_source_presentation_refresh_requested",
        details = "source=$source,pointer=$pointerIdentity,address=${nativePointerAddress(pointer)}",
    )
    if (BuildConfig.DEBUG) {
        ProviderLogger.diagnostic(
            "Apple Music 歌词来源显式呈现刷新: " +
                "id=$songId, source=$source, pointer=$pointerIdentity, " +
                "address=${nativePointerAddress(pointer)}"
        )
    }
    requestPresentationRefresh(pointer, null, currentPlaybackItem(songId))
}

/**
 * Debug-only timing/source description used to correlate source switching with
 * Apple's adapter coordinates. This intentionally exposes no lyric text; it only
 * records the source metadata, line/word counts, and begin-time fingerprints.
 */
internal fun AppleMissingLyricsHooks.timingDebugSnapshot(songId: String?): String {
    if (!BuildConfig.DEBUG) return "disabled"
    val contentSongId = songId?.takeIf(String::isNotBlank)
        ?: return "song=none"
    restoreCachedSupplement(contentSongId)
    val resolvedSongId = resolveStoredSupplementContentId(contentSongId)
    val lines = store.lines(resolvedSongId)
    val info = store.sourceInfo(resolvedSongId)
    val wordLines = lines.count { it.words.size >= 2 }
    val wordCount = lines.sumOf { it.words.size }
    var beginFingerprint = 1
    lines.forEach { line ->
        beginFingerprint = 31 * beginFingerprint + line.begin.hashCode()
        beginFingerprint = 31 * beginFingerprint + line.end.hashCode()
    }
    val beginSample = lines
        .take(8)
        .joinToString(",") { "${it.begin}-${it.end}" }
    val status = info?.statuses.orEmpty().joinToString(";") {
        "${it.source}:${it.lineCount}:${it.wordTimed}:${it.found}"
    }
    return "source=${info?.selectedSource ?: "none"},statuses=$status," +
        "lines=${lines.size},wordLines=$wordLines,words=$wordCount," +
        "beginFingerprint=$beginFingerprint,begins=[$beginSample]," +
        "revision=${store.revision()}"
}

internal fun AppleMissingLyricsHooks.hasSupplementContent(songId: String?): Boolean {
    val contentSongId = songId?.takeIf(String::isNotBlank) ?: return false
    // 冷启动时来源菜单可能先于可用性回调查询 Store；此处同样允许磁盘恢复。
    restoreCachedSupplement(contentSongId)
    maybeActivateSupplement(contentSongId, trigger = "source_menu_query")
    val storedSongId = resolveStoredSupplementContentId(contentSongId) ?: return false
    val activeSongId = resolveSupplementContentId(contentSongId)
    val normalSupplementMenu = activeSongId?.let { activeId ->
        shouldShowMissingLyricsSourceMenu(
            hasSupplementContent = store.hasContent(activeId),
            hasKnownNativeLyrics = hasKnownNativeLyrics(activeId),
        )
    } == true
    return shouldShowStoredSupplementSourceMenu(
        normalSupplementMenu = normalSupplementMenu,
        lunaBeatEnabled = isLunaBeatWordLyricsEnabled(),
        storedSourceInfo = eligibleLunaBeatSourceInfo(
            songId = storedSongId,
            sourceInfo = store.sourceInfo(storedSongId),
        ),
    )
}

internal fun AppleMissingLyricsHooks.resolveSupplementContentId(songId: String?): String? =
    songId?.takeIf { candidates.isAccepted(it) && store.hasContent(it) }

internal fun AppleMissingLyricsHooks.resolveStoredSupplementContentId(songId: String?): String? {
    val requestedSongId = songId?.takeIf(String::isNotBlank) ?: return null
    if (store.hasContent(requestedSongId)) return requestedSongId
    val storedSongId = store.contentSongId()?.takeIf(store::hasContent) ?: return null
    val identity = store.playbackIdentity(storedSongId)
    return storedSongId.takeIf {
        identity?.adamId?.toString() == requestedSongId ||
            identity?.queueId?.toString() == requestedSongId
    }
}

/** 判断指针是否为补充歌词生成的原生模型（不应被记为 Apple 原生歌词）。 */
internal fun AppleMissingLyricsHooks.isSupplementPointer(pointer: Any?): Boolean =
    nativeBuildState.isScopeActive() || isKnownSupplementPointer(
        pointer = pointer,
        supplementPointers = store.knownNativeSongInfoPointers(),
        nativeAddress = ::nativePointerAddress,
    )

/** 由原生歌词呈现 Hook 回传：hasLines=true 表示 Apple 为该歌曲构建了原生歌词。 */
internal fun AppleMissingLyricsHooks.onNativeLyricsState(songId: String?, hasLines: Boolean) {
    if (songId.isNullOrBlank()) return
    val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
    val identity = store.playbackIdentity(queueSongId)
    val contentSongId = when {
        identity?.adamId?.toString() == songId -> identity.contentSongId
        queueSongId == songId -> queueSongId
        else -> songId
    }
    if (hasLines) {
        nativeLyricsKnowledge.remember(
            adamId = songId,
            contentId = when {
                identity?.adamId?.toString() == songId -> identity.contentSongId
                contentSongId == songId -> contentSongId
                else -> null
            },
        )
        nativeTakeoverGate.onNativeResult(contentSongId, hasLyrics = true)
        if (songId != contentSongId) {
            nativeTakeoverGate.onNativeResult(songId, hasLyrics = true)
        }
        if (shouldPreferLunaBeat(contentSongId)) {
            candidates.accept(contentSongId)
            scheduleNativeLyricsModel(contentSongId)
            mainHandler.post { refreshNowPlaying(contentSongId) }
        } else {
            val storedSourceInfo = eligibleLunaBeatSourceInfo(
                songId = contentSongId,
                sourceInfo = store.sourceInfo(contentSongId),
            )
            val retainLunaBeatAlternative =
                shouldRetainLunaBeatAlternativeAfterNativePresentation(
                    lunaBeatEnabled = isLunaBeatWordLyricsEnabled(),
                    storedSourceInfo = storedSourceInfo,
                )
            candidates.revokeAcceptance(contentSongId)
            candidates.revokeAvailability(contentSongId)
            takeoverRechecks.remove(contentSongId)
            if (retainLunaBeatAlternative) {
                if (BuildConfig.DEBUG) {
                    ProviderLogger.diagnostic(
                        "Apple Music 原生歌词已呈现，保留 LunaBeat 备选: " +
                            "id=$contentSongId, selected=${lyricsSourceSelection.selected(contentSongId)}, " +
                            "stored=$storedSourceInfo"
                    )
                }
            } else {
                discardSupplementForConfirmedNativeLyrics(contentSongId)
            }
        }
    } else {
        val acceptedEmptyResult = nativeTakeoverGate.onNativeResult(
            songId = contentSongId,
            hasLyrics = false,
        ) || (songId != contentSongId && nativeTakeoverGate.onNativeResult(
            songId = songId,
            hasLyrics = false,
        ))
        if (acceptedEmptyResult) {
            ProviderLogger.debug(
                "Apple Music 原生歌词请求完成: id=$contentSongId, result=empty"
            )
            maybeActivateSupplement(contentSongId, trigger = "native_empty_result")
        } else if (BuildConfig.DEBUG) {
            ProviderLogger.debug(
                "Apple Music 原生空歌词状态忽略: id=$contentSongId, " +
                    "reason=no_matching_request"
            )
        }
    }
    ProviderLogger.debug(
        "Apple Music 无歌词补充原生歌词状态: adamId=$songId, " +
            "contentId=$contentSongId, hasLines=$hasLines"
    )
}

internal fun AppleMissingLyricsHooks.discardSupplementForConfirmedNativeLyrics(songId: String) {
    val deleted = DiskSongManager.deleteMissingLyrics(songId)
    val cleared = store.clear(songId)
    if (!cleared && deleted) return
    ProviderLogger.info(
        "Apple Music 原生歌词优先，撤销三方补充: id=$songId, " +
            "storeCleared=$cleared, diskDeleted=$deleted"
    )
    if (cleared) {
        mainHandler.post { refreshNowPlaying(songId) }
    }
}

/** 当前歌曲已确认 Apple 原生歌词时，阻止旧缓存迁移为补充歌词。 */
internal fun AppleMissingLyricsHooks.hasKnownNativeLyricsFor(songId: String): Boolean = hasKnownNativeLyrics(songId)

/** 捕获 Apple 歌词 ViewModel 实际消费的 PlaybackItem 身份。 */
internal fun AppleMissingLyricsHooks.onLyricsItem(item: Any?) {
    if (!isEnabled()) return
    val identity = capturePlaybackIdentity(item) ?: return
    nativeTakeoverGate.observe(identity.contentSongId)
    val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
    if (queueSongId != null && identity.contentSongId != queueSongId) {
        ProviderLogger.debug(
            "Apple Music 无歌词补充 loadLyrics 条目与当前队列不同: " +
                "itemId=${identity.contentSongId}, queueId=$queueSongId"
        )
    }
    // 页面打开或切歌时先恢复磁盘缓存，保证 buildTimeRangeToLyricsMap 与可用性回调在同一帧内可用。
    restoreCachedSupplement(identity.contentSongId)
    maybeActivateSupplement(identity.contentSongId, trigger = "lyrics_item")
}

/**
 * 播放页在歌词按钮可用性计算之前就会经过当前队列项路径；从这里捕获真实
 * PlaybackItem 身份，避免“按钮可进入后才调用 loadLyrics()”的循环依赖。
 */
internal fun AppleMissingLyricsHooks.onCurrentPlaybackItem(contentSongId: String, item: Any?, queueId: Long) {
    if (!isEnabled() || contentSongId.isBlank()) return
    if (lyricsSourceSelection.beginPlayback(contentSongId)) {
        nativeAlternatives.retainTrack(contentSongId)
    }
    nativeTakeoverGate.observe(contentSongId)
    scheduleTakeoverRecheck(contentSongId)
    restoreCachedSupplement(contentSongId)
    val identity = capturePlaybackIdentity(
        item = item,
        expectedContentSongId = contentSongId,
        queueIdOverride = queueId,
    ) ?: return
    item?.let { playbackItemBinding.remember(identity, it) }
    val activated = maybeActivateSupplement(
        identity.contentSongId,
        trigger = "current_playback_item",
    )
    if (activated) {
        // 覆盖“补充载荷先到、队列身份后到”的冷启动时序。身份就绪后再重放一次，
        // 同时刷新 PlaybackItem DataBinding；此时 hasLyrics()/hasTimeSyncedLyrics()
        // 已具备返回 true 的完整前提。
        refreshNowPlaying(identity.contentSongId)
    }
}
