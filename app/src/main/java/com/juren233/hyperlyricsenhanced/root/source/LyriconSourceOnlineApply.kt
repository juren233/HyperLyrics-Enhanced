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
import com.juren233.hyperlyricsenhanced.common.bridge.sendAppleDirectBroadcast
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
import com.juren233.hyperlyricsenhanced.online.utils.ChineseUtils
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.island.traceIslandPerformance
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import com.juren233.hyperlyricsenhanced.contract.AppleDirectBridgeContract
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
import kotlinx.coroutines.CoroutineStart
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
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
) {
    // Register with the serial queue before returning so RACE_FINAL cannot overtake RACE_FIRST.
    target.request.markResultReady(generation)
    fallbackScope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (!target.request.beginPreparation(generation)) return@launch
        try {
            onlineTranslationPreparation.prepareAndCommit(
                capture = {
                    if (generation != target.request.snapshot().generation) null
                    else captureTranslationApplySnapshot(baseSong, target)?.detached() ?: run {
                        target.request.deliver(generation) { }
                        null
                    }
                },
                prepare = { snapshot ->
                    traceIslandPerformance("HLE.translation.prepare") {
                        val published = snapshot.publishedSong?.let {
                            ApplePronunciationVisibilityPolicy.filterSong(
                                song = it,
                                hideMandarinPinyin = snapshot.hideMandarinPinyin,
                            )
                        }
                        val activeSelection = selection ?: OnlineTranslationSelection()
                        val candidates = activeSelection.matchCandidates(snapshot.nativeSong)
                        PreparedOnlineTranslation(
                            publishedSong = published,
                            candidates = candidates,
                            merged = activeSelection.composeMatched(
                                latestNativeSong = snapshot.nativeSong,
                                currentPublishedSong = published,
                                rebasedCandidates = candidates,
                            ),
                        )
                    }
                },
                commit = { snapshot, prepared ->
                    val delivered = target.request.deliver(
                        generation,
                        stillCurrent = { snapshot == captureTranslationApplySnapshot(baseSong, target) },
                    ) {
                        traceIslandPerformance("HLE.translation.commit") {
                            applyCurrentOnlineTranslationResult(
                                generation, baseSong, selection, publicationStage, snapshot, prepared,
                                target,
                            )
                        }
                    }
                    delivered || generation != target.request.snapshot().generation
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Release the ready flag only for this generation; a later receipt may retry it.
            target.request.deliver(generation) { }
            HookLogger.e(LyriconSource.TAG, "在线翻译结果准备失败", error)
        } finally {
            target.request.finishPreparation(generation)
        }
    }
}

private data class TranslationApplySnapshot(
    val playerPackage: String?,
    val appleRequest: Boolean,
    val nativeSong: LocalSong,
    val publishedSong: LocalSong?,
    val hideMandarinPinyin: Boolean,
) {
    // Song and its lyric lines are mutable models; the worker must not share their live instances.
    fun detached() = copy(nativeSong = nativeSong.deepCopy(), publishedSong = publishedSong?.deepCopy())
}

private data class PreparedOnlineTranslation(
    val publishedSong: LocalSong?,
    val candidates: Map<Source, OnlineTranslationMatcher.Result>,
    val merged: OnlineTranslationMatcher.Result?,
)

private fun LyriconSource.captureTranslationApplySnapshot(
    baseSong: LocalSong,
    target: OnlineTranslationTarget,
): TranslationApplySnapshot? {
    if (target.inAppOnly) {
        // 应用内歌曲：基准与已回传结果都来自独立槽位，不看活动播放者，也不读正式的发布状态。
        val inAppSong = inAppApple.song()?.takeIf { isSameTrack(it, baseSong) } ?: return null
        val inAppPublished = inAppApple.publishedSong()?.takeIf { isSameTrack(it, inAppSong) }
        return TranslationApplySnapshot(
            null, true, inAppSong, inAppPublished, isHideMandarinPinyinEnabled(),
        )
    }
    val playerPackage = activeCentralPlayerPackageName
    val appleRequest = playerPackage == LyriconSource.APPLE_MUSIC_PACKAGE || currentThirdPartySong == null
    val nativeSong = (if (appleRequest) currentAppleSong else currentThirdPartySong)
        ?.takeIf { isSameTrack(it, baseSong) } ?: return null
    val published = (if (appleRequest) currentPublishedAppleSong else currentPublishedThirdPartySong)
        ?.takeIf { isSameTrack(it, nativeSong) }
    return TranslationApplySnapshot(
        playerPackage, appleRequest, nativeSong, published, isHideMandarinPinyinEnabled(),
    )
}

private fun LyriconSource.applyCurrentOnlineTranslationResult(
    generation: Int,
    baseSong: LocalSong,
    selection: OnlineTranslationSelection?,
    publicationStage: LyriconSource.OnlineTranslationPublicationStage,
    snapshot: TranslationApplySnapshot,
    prepared: PreparedOnlineTranslation,
    target: OnlineTranslationTarget,
) {
    val inAppOnly = target.inAppOnly
    val appleRequest = snapshot.appleRequest
    val nativeSong = snapshot.nativeSong
    val generationMatches = generation == target.request.snapshot().generation
    val sameTrack = isSameTrack(nativeSong, baseSong)
    val nativeLyricsAvailable = if (appleRequest) {
        hasAppleLyricsForOnlineEnrichment(
            song = nativeSong,
            confirmedNativeLyrics = if (inAppOnly) {
                inAppApple.hasNativeLyrics()
            } else {
                currentAppleHasNativeLyrics
            },
        )
    } else {
        !nativeSong.lyrics.isNullOrEmpty()
    }
    val enrichmentNeeded = needsOnlineEnrichment(nativeSong)
    val matchingEnabled = if (appleRequest) {
        isAppleTranslationEnrichmentEnabled()
    } else {
        isOnlineTranslationEnabledFor(activeCentralPlayerPackageName)
    }
    val overlayPublicationEnabled = !appleRequest ||
        isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE)
    // 来源弹窗（始终显示切换源按钮）发起的检索不依赖自动开关，也不要求原生内容有缺。
    val sourceControl = appleRequest && selection?.control != null &&
        isAlwaysShowSourceSwitchEnabled()
    // 应用内检索只为来源弹窗而存在，没有自动补全这条放行路径。
    val requestStillCurrent = generationMatches && sameTrack && nativeLyricsAvailable &&
        ((!inAppOnly && enrichmentNeeded && matchingEnabled) || sourceControl)
    pronunciationDiagnostic(
        "stage=apply_guard, generation=$generation, id=${baseSong.id}, " +
            "accepted=$requestStillCurrent, currentGeneration=${target.request.snapshot().generation}, " +
            "generationMatches=$generationMatches, sameTrack=$sameTrack, " +
            "nativeLyrics=$nativeLyricsAvailable, enrichmentNeeded=$enrichmentNeeded, " +
            "matchingEnabled=$matchingEnabled, sourceControl=$sourceControl, " +
            "resultPresent=${selection != null}, " +
            "candidateSources=${selection?.onlineLinesBySource?.keys?.joinToString("+").orEmpty()}"
    )
    if (!requestStillCurrent) {
        diagnostic(
            "在线翻译匹配结果已过期: player=$activeCentralPlayerPackageName, " +
                "title=${baseSong.name}, " +
                "generation=$generation, currentGeneration=${target.request.snapshot().generation}"
        )
        return
    }

    val latestNativeSong = nativeSong
    val currentPublishedSong = prepared.publishedSong
    val nativeLyricsChangedDuringRequest = baseSong.lyrics != latestNativeSong.lyrics
    val mergedResult = prepared.merged
    val candidateResults = prepared.candidates
    pronunciationDiagnostic(
        "stage=result_rebased, generation=$generation, id=${baseSong.id}, " +
            "nativeLyricsChanged=$nativeLyricsChangedDuringRequest, " +
            "requestLines=${baseSong.lyrics.orEmpty().size}, " +
            "latestLines=${latestNativeSong.lyrics.orEmpty().size}, " +
            "candidateSources=${selection?.onlineLinesBySource?.keys?.joinToString("+").orEmpty()}"
    )
    val hasPendingSourceSwitch = target.manual.hasPendingOnlineRequest(baseSong.id)
    val hasOnlineEnrichment = mergedResult != null &&
        (
            OnlineTranslationMatcher.contributesTranslation(latestNativeSong, mergedResult) ||
                OnlineTranslationMatcher.contributesPronunciation(
                    latestNativeSong,
                    mergedResult,
                ) ||
                // 手动选的来源会替换原生已有的行，不只是补缺。
                (sourceControl &&
                    OnlineSourceOverride.changesContent(latestNativeSong, mergedResult.song))
            )
    val mergedRomanizedLines = mergedResult?.song?.lyrics.orEmpty().count {
        !it.roma.isNullOrBlank()
    }
    pronunciationDiagnostic(
        "stage=apply_decision, generation=$generation, id=${baseSong.id}, " +
            "mergedPresent=${mergedResult != null}, mergedRomanized=$mergedRomanizedLines, " +
            "hasOnlineEnrichment=$hasOnlineEnrichment, sourceSwitch=$hasPendingSourceSwitch"
    )
    if (publicationStage == LyriconSource.OnlineTranslationPublicationStage.SOURCE_STATUS_PROGRESS &&
        mergedResult != null
    ) {
        // 渐进版只把已答来源的菜单状态发给来源弹窗，歌词内容保持当前已发布的版本：
        // 手动切换请求的成败判定依据最终合成，慢来源未返回前不得提前定论。
        if (currentPublishedSong != null &&
            appleRequest && (isNativeOnlineTranslationEnabled() || sourceControl)
        ) {
            directBridge?.publishOnlineTranslation(
                song = currentPublishedSong.withSourceMenuMetadataFrom(mergedResult.song),
                generation = generation,
            )
        }
        return
    }
    if (mergedResult == null || (!hasOnlineEnrichment && !hasPendingSourceSwitch)) {
        if (sourceControl && mergedResult != null) {
            // 没有可显示的在线内容，但来源弹窗仍要拿到各来源的检索状态与匹配度。
            directBridge?.publishOnlineTranslation(song = mergedResult.song, generation = generation)
            target.finalPayload.record(translationIdentity(baseSong), latestNativeSong, mergedResult.song)
            if (selection?.control?.automaticFill != true) {
                // 这次检索只为来源弹窗发起，不走自动补全的未命中处理；
                // 已显示的在线内容若不再成立（如改选了原生），恢复成原生内容。
                if (
                    currentPublishedSong != null &&
                    OnlineSourceOverride.changesContent(latestNativeSong, currentPublishedSong)
                ) {
                    if (inAppOnly) {
                        inAppApple.recordPublished(mergedResult.song)
                    } else {
                        publication.cancelEnrichment()
                        publishAppleSong(
                            mergedResult.song,
                            restorePosition = true,
                            onlineTranslationMatched = false,
                            publishToSink = overlayPublicationEnabled,
                        )
                    }
                }
                return
            }
        }
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
        completePendingOnlineSourceSwitchRequests(null, target)
        // 应用内检索不涉及岛端，没有「在线翻译不可用」之类面向岛端的通知。
        if (inAppOnly) return
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

    if (!inAppOnly) publication.acceptEnrichment(hasOnlineEnrichment)
    if (
        publicationStage == LyriconSource.OnlineTranslationPublicationStage.RACE_FIRST &&
        hasOnlineEnrichment
    ) {
        target.request.markFirstAccepted(generation)
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
        !inAppOnly &&
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
            // Deferral only protects what the lyrics show. The menus' per-source state is not
            // lyric content, so publish it now with the displayed lyrics left unchanged.
            if (appleRequest && (isNativeOnlineTranslationEnabled() || sourceControl)) {
                directBridge?.publishOnlineTranslation(
                    song = currentPublishedSong.withSourceMenuMetadataFrom(mergedResult.song),
                    generation = generation,
                )
            }
            return
        }
    }
    if (publicationStage == LyriconSource.OnlineTranslationPublicationStage.RACE_FINAL_COMMIT) {
        pronunciationDiagnostic(
            "stage=race_final_commit, generation=$generation, id=${baseSong.id}, " +
                "position=${LyriconDataBridge.currentPosition}"
        )
        target.request.clearPending(generation)
    }
    val nativePublicationEnabled = appleRequest &&
        (isNativeOnlineTranslationEnabled() || sourceControl)
    pronunciationDiagnostic(
        "stage=publish_attempt, generation=$generation, id=${baseSong.id}, " +
            "enabled=$nativePublicationEnabled, enriched=$hasOnlineEnrichment, " +
            "romanizedLines=$mergedRomanizedLines, bridgePresent=${directBridge != null}, " +
            "publicationStage=$publicationStage, overlayEnabled=$overlayPublicationEnabled"
    )
    if (nativePublicationEnabled && (hasOnlineEnrichment || sourceControl)) {
        val published = directBridge?.publishOnlineTranslation(
            song = mergedResult.song,
            generation = generation,
        ) == true
        pronunciationDiagnostic(
            "stage=publish_call_result, generation=$generation, id=${baseSong.id}, " +
                "success=$published, publicationStage=$publicationStage"
        )
        // 完整结果（非赛马首版）记下来，重开弹窗时原样重发而不是整轮重新检索。
        if (sourceControl && publicationStage != LyriconSource.OnlineTranslationPublicationStage.RACE_FIRST) {
            target.finalPayload.record(translationIdentity(baseSong), latestNativeSong, mergedResult.song)
        }
    }
    completePendingOnlineSourceSwitchRequests(mergedResult.song, target)
    if (inAppOnly) {
        // 只经上面的 directBridge 回传给 Apple Music 应用内；岛端归活动播放者，这里不发布。
        inAppApple.recordPublished(mergedResult.song)
    } else if (appleRequest) {
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
    // 岛端歌词来源的「下一句切换」与在线翻译的挂起提交共用同一个位置回调入口。
    fallbackIslandSwitch.takeDue(position) { appleFallbackRequest.isCurrent(it) }?.commit?.invoke()
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
    application.sendAppleDirectBroadcast(
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
    reason: String,
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
) {
    if (target.inAppOnly) {
        // [clearMatched] 对应用内检索无意义（没有岛端的已匹配发布状态）；活动播放者的检索不受影响。
        cancelInAppOnlineTranslation(reason)
        return
    }
    if (onlineTranslationRunning || onlineTranslationResultReady || onlineMatchedTranslationActive) {
        diagnostic(
            "Apple Music 在线翻译匹配取消: reason=$reason, " +
                "title=${currentAppleSong?.name}"
        )
    }
    onlineTranslationRequest.cancel(clearAttempt)
    if (clearAttempt) activeOnlineTranslationTarget.finalPayload.clear()
    if (clearMatched) {
        directBridge?.clearOnlineTranslation(currentAppleSong?.id)
        publication.cancelEnrichment()
    }
}

/** 只取消应用内检索；活动播放者正在进行的在线翻译与歌词兜底不受影响。 */
internal fun LyriconSource.cancelInAppOnlineTranslation(reason: String) {
    if (inAppApple.target.request.snapshot().running) {
        diagnostic("Apple Music 应用内在线翻译匹配取消: reason=$reason, title=${inAppApple.song()?.name}")
    }
    inAppApple.target.request.cancel(clearAttempt = true)
    inAppApple.target.finalPayload.clear()
}

/** Keeps this song's displayed content and takes only the source-menu metadata from [latest]. */
private fun LocalSong.withSourceMenuMetadataFrom(latest: LocalSong): LocalSong {
    val menuKeys = setOf(
        LyricMetadataKeys.ONLINE_TRANSLATION_MATCH_STATS,
        LyricMetadataKeys.ONLINE_PRONUNCIATION_MATCH_STATS,
        LyricMetadataKeys.ONLINE_SOURCE_CONTENT_STATES,
        LyricMetadataKeys.ONLINE_LYRICS_SOURCE_STATUSES,
    )
    val entries = metadata?.entries.orEmpty()
        .filterNot { it.key in menuKeys }
        .map { it.key to it.value }
        .toMutableList()
    menuKeys.forEach { key ->
        latest.metadata?.getString(key)?.let { entries += key to it }
    }
    return copy(metadata = lyricMetadataOf(*entries.toTypedArray()))
}
