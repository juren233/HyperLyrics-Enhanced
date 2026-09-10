/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ChineseLyricsPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.TraditionalLyricsSimplifier
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.common.media.NextTrackMetadataCache
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSource
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.lunabeat.LunaBeatLookupResult
import com.juren233.hyperlyricsenhanced.online.source.lunabeat.LunaBeatTtmlRepository
import com.juren233.hyperlyricsenhanced.online.utils.ChineseUtils
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.proify.lyricon.amprovider.xposed.AppleDirectBridgeContract
import io.github.proify.lyricon.amprovider.xposed.AppleSourceSwitchPerformanceDiagnostics
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

internal fun LyriconSource.formatMetric(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

internal fun LyriconSource.applyOnlineTranslationResult(
    generation: Int,
    baseSong: LocalSong,
    selection: OnlineTranslationSelection?,
    publicationStage: LyriconSource.OnlineTranslationPublicationStage = LyriconSource.OnlineTranslationPublicationStage.SINGLE,
) {
    onlineTranslationRequest.deliver(generation) {
        applyCurrentOnlineTranslationResult(generation, baseSong, selection, publicationStage)
    }
}

private fun LyriconSource.applyCurrentOnlineTranslationResult(
    generation: Int,
    baseSong: LocalSong,
    selection: OnlineTranslationSelection?,
    publicationStage: LyriconSource.OnlineTranslationPublicationStage = LyriconSource.OnlineTranslationPublicationStage.SINGLE,
) {
    val appleRequest = activeCentralPlayerPackageName == LyriconSource.APPLE_MUSIC_PACKAGE ||
        currentThirdPartySong == null
    val nativeSong = if (appleRequest) currentAppleSong else currentThirdPartySong
    val generationMatches = generation == onlineTranslationGeneration
    val sameTrack = nativeSong != null && isSameTrack(nativeSong, baseSong)
    val nativeLyricsAvailable = if (appleRequest) {
        hasAppleLyricsForOnlineEnrichment(
            song = nativeSong,
            confirmedNativeLyrics = currentAppleHasNativeLyrics,
        )
    } else {
        !nativeSong?.lyrics.isNullOrEmpty()
    }
    val enrichmentNeeded = needsOnlineEnrichment(nativeSong)
    val matchingEnabled = if (appleRequest) {
        isAppleTranslationEnrichmentEnabled()
    } else {
        isOnlineTranslationEnabledFor(activeCentralPlayerPackageName)
    }
    val overlayPublicationEnabled = !appleRequest ||
        isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE)
    val requestStillCurrent = generationMatches && nativeSong != null && sameTrack &&
        nativeLyricsAvailable && enrichmentNeeded && matchingEnabled
    pronunciationDiagnostic(
        "stage=apply_guard, generation=$generation, id=${baseSong.id}, " +
            "accepted=$requestStillCurrent, currentGeneration=$onlineTranslationGeneration, " +
            "generationMatches=$generationMatches, sameTrack=$sameTrack, " +
            "nativeLyrics=$nativeLyricsAvailable, enrichmentNeeded=$enrichmentNeeded, " +
            "matchingEnabled=$matchingEnabled, resultPresent=${selection != null}, " +
            "candidateSources=${selection?.onlineLinesBySource?.keys?.joinToString("+").orEmpty()}"
    )
    if (!requestStillCurrent) {
        diagnostic(
            "在线翻译匹配结果已过期: player=$activeCentralPlayerPackageName, " +
                "title=${baseSong.name}, " +
                "generation=$generation, currentGeneration=$onlineTranslationGeneration"
        )
        return
    }

    val latestNativeSong = nativeSong
    val currentPublishedSong = (if (appleRequest) {
        currentPublishedAppleSong
    } else {
        currentPublishedThirdPartySong
    })
        ?.takeIf { isSameTrack(it, latestNativeSong) }
        ?.let { publishedSong ->
            ApplePronunciationVisibilityPolicy.filterSong(
                song = publishedSong,
                hideMandarinPinyin = isHideMandarinPinyinEnabled(),
            )
        }
    val nativeLyricsChangedDuringRequest = baseSong.lyrics != latestNativeSong.lyrics
    val mergedResult = (selection ?: OnlineTranslationSelection()).compose(
        latestNativeSong = latestNativeSong,
        currentPublishedSong = currentPublishedSong,
    )
    val candidateResults = selection
        ?.matchCandidates(latestNativeSong)
        .orEmpty()
    pronunciationDiagnostic(
        "stage=result_rebased, generation=$generation, id=${baseSong.id}, " +
            "nativeLyricsChanged=$nativeLyricsChangedDuringRequest, " +
            "requestLines=${baseSong.lyrics.orEmpty().size}, " +
            "latestLines=${latestNativeSong.lyrics.orEmpty().size}, " +
            "candidateSources=${selection?.onlineLinesBySource?.keys?.joinToString("+").orEmpty()}"
    )
    val hasPendingSourceSwitch = manualSourceRequests.hasPendingOnlineRequest(baseSong.id)
    val hasOnlineEnrichment = mergedResult != null &&
        (
            OnlineTranslationMatcher.contributesTranslation(latestNativeSong, mergedResult) ||
                OnlineTranslationMatcher.contributesPronunciation(
                    latestNativeSong,
                    mergedResult,
                )
            )
    val mergedRomanizedLines = mergedResult?.song?.lyrics.orEmpty().count {
        !it.roma.isNullOrBlank()
    }
    pronunciationDiagnostic(
        "stage=apply_decision, generation=$generation, id=${baseSong.id}, " +
            "mergedPresent=${mergedResult != null}, mergedRomanized=$mergedRomanizedLines, " +
            "hasOnlineEnrichment=$hasOnlineEnrichment, sourceSwitch=$hasPendingSourceSwitch"
    )
    if (mergedResult == null || (!hasOnlineEnrichment && !hasPendingSourceSwitch)) {
        diagnostic(
            "在线翻译匹配未命中: player=$activeCentralPlayerPackageName, " +
                "title=${baseSong.name}"
        )
        HookLogger.i(
            LyriconSource.TAG,
            "在线翻译匹配未命中: player=$activeCentralPlayerPackageName, " +
                "title=${baseSong.name}"
        )
        if (appleRequest && requestOriginalMetadata(baseSong, "translation_match_miss")) return
        completePendingOnlineSourceSwitchRequests(null)
        if (!onlineMatchedTranslationActive && overlayPublicationEnabled) {
            if (
                !currentPublishedAppleOnlineTranslationMatched &&
                (if (appleRequest) currentPublishedAppleSong else currentPublishedThirdPartySong) !=
                    latestNativeSong
            ) {
                if (appleRequest) publishAppleSong(latestNativeSong, restorePosition = true)
                else {
                    publishThirdPartySong(latestNativeSong, restorePosition = true)
                }
            }
            sink?.onOnlineTranslationUnavailable(nativeSong)
        }
        return
    }

    publication.acceptEnrichment(hasOnlineEnrichment)
    if (
        publicationStage == LyriconSource.OnlineTranslationPublicationStage.RACE_FIRST &&
        hasOnlineEnrichment
    ) {
        onlineTranslationRequest.markFirstAccepted(generation)
    }
    diagnostic(
        "在线翻译结果接受: player=$activeCentralPlayerPackageName, " +
            "title=${baseSong.name}, " +
            "matched=${mergedResult.matchedCount}, enriched=$hasOnlineEnrichment, " +
            "sourceSwitch=$hasPendingSourceSwitch, total=${baseSong.lyrics.orEmpty().size}"
    )
    HookLogger.i(
        LyriconSource.TAG,
        "在线翻译结果接受: player=$activeCentralPlayerPackageName, " +
            "title=${baseSong.name}, " +
            "matched=${mergedResult.matchedCount}, enriched=$hasOnlineEnrichment, " +
            "sourceSwitch=$hasPendingSourceSwitch, total=${baseSong.lyrics.orEmpty().size}"
    )
    val unmatched = mergedResult.song.lyrics.orEmpty().mapIndexedNotNull { index, line ->
        val missing = buildList {
            if (!OnlineTranslationContentPolicy.isMeaningful(line.translation)) {
                add("translation")
            }
            if (line.roma.isNullOrBlank()) add("pronunciation")
        }
        missing.takeIf { it.isNotEmpty() }?.let {
            "$index@${line.begin}[${it.joinToString("+")}]:${line.text.orEmpty().take(48)}"
        }
    }
    if (unmatched.isNotEmpty()) {
        diagnostic(
            "在线翻译未匹配行: player=$activeCentralPlayerPackageName, " +
                "title=${baseSong.name}, " +
                unmatched.joinToString(separator = " | ")
            )
    }
    if (BuildConfig.DEBUG && selection != null) {
        val contributions = OnlineTranslationDiagnostics.contributions(
            baseSong = latestNativeSong,
            resultSong = mergedResult.song,
            candidates = candidateResults,
            translationOrder = actualSourceFirst(
                mergedResult.song.metadata
                    ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE),
                selection.sourceOrder.ifEmpty { selection.requestedSources },
            ),
            pronunciationOrder = actualSourceFirst(
                mergedResult.song.metadata
                    ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE),
                selection.sourceOrder.ifEmpty { selection.requestedSources },
            ),
        )
        if (contributions.isNotEmpty()) {
            pronunciationDiagnostic(
                "stage=line_contributions, generation=$generation, id=${baseSong.id}, " +
                    contributions.joinToString("|") { contribution ->
                        "line=${contribution.index}@${contribution.begin}, " +
                            "translation=${contribution.translationSource ?: "none"}, " +
                            "background=${contribution.backgroundTranslationSource ?: "none"}, " +
                            "pronunciation=${contribution.pronunciationSource ?: "none"}"
                    }
            )
        }
        val missingLines = OnlineTranslationDiagnostics.missingLines(
            resultSong = mergedResult.song,
            requestedSources = selection.requestedSources.ifEmpty { selection.sourceOrder },
            onlineLinesBySource = selection.onlineLinesBySource,
            candidates = candidateResults,
            pronunciationRequested = selection.pronunciationRequested,
        )
        if (missingLines.isNotEmpty()) {
            pronunciationDiagnostic(
                "stage=line_missing_diagnostics, generation=$generation, id=${baseSong.id}, " +
                    missingLines.joinToString("|") { missing ->
                        "line=${missing.index}@${missing.begin}, missing=${missing.missing.joinToString("+")}, " +
                            "reasons=${missing.reasonsBySource.entries.joinToString(",") { (source, reasons) ->
                                "$source:${reasons.joinToString("+")}"
                            }}"
                    }
            )
        }
    }
    if (
        publicationStage == LyriconSource.OnlineTranslationPublicationStage.RACE_FINAL &&
        onlineRaceFirstAcceptedGeneration == generation &&
        selection != null &&
        currentPublishedSong != null &&
        !sameOnlineTranslationContent(currentPublishedSong, mergedResult.song)
    ) {
        val targetPosition = OnlineTranslationBoundaryPolicy.nextCommitPosition(
            lines = latestNativeSong.lyrics.orEmpty(),
            currentPosition = LyriconDataBridge.currentPosition,
        )
        if (targetPosition != null && targetPosition > LyriconDataBridge.currentPosition) {
            onlineTranslationRequest.defer(generation, LyriconSource.PendingOnlineTranslationCommit(
                generation = generation,
                baseSong = baseSong,
                selection = selection,
                targetPosition = targetPosition,
            ))
            pronunciationDiagnostic(
                "stage=race_final_deferred, generation=$generation, id=${baseSong.id}, " +
                    "targetPosition=$targetPosition, currentPosition=${LyriconDataBridge.currentPosition}"
            )
            return
        }
    }
    if (publicationStage == LyriconSource.OnlineTranslationPublicationStage.RACE_FINAL_COMMIT) {
        pronunciationDiagnostic(
            "stage=race_final_commit, generation=$generation, id=${baseSong.id}, " +
                "position=${LyriconDataBridge.currentPosition}"
        )
        onlineTranslationRequest.clearPending(generation)
    }
    val nativePublicationEnabled = appleRequest && isNativeOnlineTranslationEnabled()
    pronunciationDiagnostic(
        "stage=publish_attempt, generation=$generation, id=${baseSong.id}, " +
            "enabled=$nativePublicationEnabled, enriched=$hasOnlineEnrichment, " +
            "romanizedLines=$mergedRomanizedLines, bridgePresent=${directBridge != null}, " +
            "publicationStage=$publicationStage, overlayEnabled=$overlayPublicationEnabled"
    )
    if (nativePublicationEnabled && hasOnlineEnrichment) {
        val published = directBridge?.publishOnlineTranslation(
            song = mergedResult.song,
            generation = generation,
        ) == true
        pronunciationDiagnostic(
            "stage=publish_call_result, generation=$generation, id=${baseSong.id}, " +
                "success=$published, publicationStage=$publicationStage"
        )
    }
    completePendingOnlineSourceSwitchRequests(mergedResult.song)
    if (appleRequest) {
        publishAppleSong(
            mergedResult.song,
            restorePosition = true,
            onlineTranslationMatched = hasOnlineEnrichment,
            publishToSink = overlayPublicationEnabled,
        )
    } else {
        publishThirdPartySong(
            mergedResult.song,
            restorePosition = true,
            onlineTranslationMatched = hasOnlineEnrichment,
        )
    }
}

internal fun LyriconSource.sameOnlineTranslationContent(first: LocalSong, second: LocalSong): Boolean {
    if (!isSameTrack(first, second)) return false
    if (first.lyrics.orEmpty().size != second.lyrics.orEmpty().size) return false
    val firstTranslationSource = first.metadata
        ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
    val secondTranslationSource = second.metadata
        ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
    val firstPronunciationSource = first.metadata
        ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
    val secondPronunciationSource = second.metadata
        ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
    if (
        firstTranslationSource != secondTranslationSource ||
        firstPronunciationSource != secondPronunciationSource
    ) return false
    return first.lyrics.orEmpty().zip(second.lyrics.orEmpty()).all { (left, right) ->
        left.translation == right.translation &&
            left.roma == right.roma &&
            left.metadata == right.metadata
    }
}

internal fun LyriconSource.actualSourceFirst(sourceName: String?, fallbackOrder: List<Source>): List<Source> {
    val actualSource = sourceName
        ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
        ?: return fallbackOrder
    return listOf(actualSource) + fallbackOrder.filterNot { it == actualSource }
}

internal fun LyriconSource.maybeCommitPendingOnlineTranslation(position: Long) {
    val pending = onlineTranslationRequest.takePending { position >= it.targetPosition } ?: return
    pronunciationDiagnostic(
        "stage=race_commit_boundary_reached, generation=${pending.generation}, " +
            "id=${pending.baseSong.id}, targetPosition=${pending.targetPosition}, position=$position"
    )
    mainHandler.post {
        applyOnlineTranslationResult(
            generation = pending.generation,
            baseSong = pending.baseSong,
            selection = pending.selection,
            publicationStage = LyriconSource.OnlineTranslationPublicationStage.RACE_FINAL_COMMIT,
        )
    }
}

internal fun LyriconSource.requestOriginalMetadata(baseSong: LocalSong, reason: String): Boolean {
    val mediaId = baseSong.id?.takeIf { it.all(Char::isDigit) } ?: return false
    val hasOriginalMetadata = !baseSong.metadata
        ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE)
        .isNullOrBlank() || !baseSong.metadata
        ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST)
        .isNullOrBlank()
    val originalMetadataResolved = baseSong.metadata
        ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_METADATA_RESOLVED)
        .toBoolean()
    if (
        hasOriginalMetadata ||
        originalMetadataResolved ||
        originalMetadataRequest.isCurrent(mediaId)
    ) return false
    val application = app ?: return false
    originalMetadataRequest.register(mediaId)
    application.sendBroadcast(
        Intent(AppleDirectBridgeContract.ACTION_RESOLVE_ORIGINAL_METADATA)
            .setPackage(LyriconSource.APPLE_MUSIC_PACKAGE)
            .putExtra(AppleDirectBridgeContract.EXTRA_MEDIA_ID, mediaId)
    )
    HookLogger.i(
        LyriconSource.TAG,
        "Apple Music 三方检索未命中，请求多地区原名: " +
            "id=$mediaId, title=${baseSong.name}, reason=$reason"
    )
    return true
}

internal fun LyriconSource.shouldRequestOriginalMetadataForOnlineLookup(song: LocalSong): Boolean {
    if (!shouldPreferAppleOriginalMetadata()) return false
    if (
        song.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_METADATA_RESOLVED)
            .toBoolean()
    ) return false
    return AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
        mediaId = song.id,
        title = song.name,
        artist = song.artist,
        genre = song.metadata?.getString(LyricMetadataKeys.APPLE_CATALOG_GENRE),
    )
}

internal fun LyriconSource.cancelOnlineTranslation(
    clearAttempt: Boolean,
    clearMatched: Boolean,
    reason: String
) {
    if (onlineTranslationRunning || onlineTranslationResultReady || onlineMatchedTranslationActive) {
        diagnostic(
            "Apple Music 在线翻译匹配取消: reason=$reason, " +
                "title=${currentAppleSong?.name}"
        )
    }
    onlineTranslationRequest.cancel(clearAttempt)
    if (clearMatched) {
        directBridge?.clearOnlineTranslation(currentAppleSong?.id)
        publication.cancelEnrichment()
    }
}

