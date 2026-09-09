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

internal fun LyriconSource.scheduleOnlineTranslation(baseSong: LocalSong): Boolean {
    val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
    val effectivePlayerPackage = activeCentralPlayerPackageName ?: LyriconSource.APPLE_MUSIC_PACKAGE
    val matchingEnabled = if (effectivePlayerPackage == LyriconSource.APPLE_MUSIC_PACKAGE) {
        isAppleTranslationEnrichmentEnabled()
    } else {
        isOnlineTranslationEnabledFor(effectivePlayerPackage)
    }
    val nativeLineCount = baseSong.lyrics.orEmpty().size
    val enrichmentNeeded = needsOnlineEnrichment(baseSong)
    val titlePresent = !baseSong.name.isNullOrBlank()
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "translation_schedule_evaluated",
        details = "matchingEnabled=$matchingEnabled,nativeLines=$nativeLineCount," +
            "enrichmentNeeded=$enrichmentNeeded,titlePresent=$titlePresent," +
            "generation=$onlineTranslationGeneration",
    )
    if (!matchingEnabled || nativeLineCount == 0 || !enrichmentNeeded || !titlePresent) {
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_schedule_skipped",
            details = "reason=precondition_failed",
        )
        pronunciationDiagnostic(
            "stage=request_schedule_skipped, reason=precondition_failed, " +
                "id=${baseSong.id}, prefsPresent=${prefs != null}, " +
                "matchingEnabled=$matchingEnabled, nativeLines=$nativeLineCount, " +
                "needsEnrichment=$enrichmentNeeded, titlePresent=$titlePresent"
        )
        return false
    }
    val attemptKey = translationIdentity(baseSong)
    val requestSnapshot = onlineTranslationRequest.snapshot()
    if (isAppleOnlineTranslationAttemptAlive(
            attemptMatches = onlineTranslationAttemptKey == attemptKey,
            requestRunning = requestSnapshot.running,
            resultReady = requestSnapshot.resultReady,
            matchedActive = onlineMatchedTranslationActive,
        )
    ) {
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_schedule_skipped",
            details = "reason=attempt_already_recorded",
        )
        pronunciationDiagnostic(
            "stage=request_schedule_skipped, reason=attempt_already_recorded, " +
                "id=${baseSong.id}, attempt=$attemptKey"
        )
        return false
    }

    val previousJobActive = onlineTranslationRunning
    val generation = onlineTranslationRequest.begin(attemptKey, allowRestart = true) ?: return false
    pronunciationDiagnostic(
        "stage=request_scheduled, generation=$generation, id=${baseSong.id}, " +
            "attempt=$attemptKey, nativeLines=${baseSong.lyrics.orEmpty().size}"
    )
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "translation_job_scheduled",
        details = "generation=$generation,cancelledJobActive=$previousJobActive",
    )

    fun postTranslationApply(
        selection: OnlineTranslationSelection?,
        publicationStage: LyriconSource.OnlineTranslationPublicationStage,
        reason: String,
    ) {
        val postedAtNanos = SystemClock.elapsedRealtimeNanos()
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_apply_posted",
            details = "generation=$generation,publicationStage=$publicationStage," +
                "reason=$reason,resultPresent=${selection != null}",
        )
        onlineTranslationRequest.markResultReady(generation)
        mainHandler.post {
            val applyStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_apply_main_started",
                details = "generation=$generation,publicationStage=$publicationStage," +
                    "queueWaitMs=" +
                    ((applyStartedAtNanos - postedAtNanos) / 1_000_000.0),
            )
            try {
                applyOnlineTranslationResult(
                    generation = generation,
                    baseSong = baseSong,
                    selection = selection,
                    publicationStage = publicationStage,
                )
            } finally {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_apply_main_finished",
                    details = "generation=$generation,publicationStage=$publicationStage," +
                        "elapsedMs=" +
                        ((SystemClock.elapsedRealtimeNanos() - applyStartedAtNanos) /
                            1_000_000.0),
                )
            }
        }
    }

    onlineTranslationRequest.launch(fallbackScope, generation) {
        val mutexWaitStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_mutex_wait_started",
            details = "generation=$generation",
        )
        try {
            fallbackRequestMutex.withLock {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_mutex_acquired",
                    details = "generation=$generation,waitMs=" +
                        ((SystemClock.elapsedRealtimeNanos() - mutexWaitStartedAtNanos) /
                            1_000_000.0),
                )
                if (generation != onlineTranslationGeneration) {
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "translation_search_abandoned",
                        details = "generation=$generation," +
                            "currentGeneration=$onlineTranslationGeneration",
                    )
                    pronunciationDiagnostic(
                        "stage=request_abandoned, generation=$generation, id=${baseSong.id}, " +
                            "reason=generation_changed_before_start, " +
                            "currentGeneration=$onlineTranslationGeneration"
                    )
                    return@withLock
                }
                val application = app ?: run {
                    pronunciationDiagnostic(
                        "stage=request_abandoned, generation=$generation, id=${baseSong.id}, " +
                            "reason=application_unavailable"
                    )
                    return@withLock
                }
                val configuredSources = configuredOnlineSources()
                if (configuredSources.isEmpty()) {
                    postTranslationApply(
                        selection = null,
                        publicationStage = LyriconSource.OnlineTranslationPublicationStage.SINGLE,
                        reason = "no_configured_sources",
                    )
                    return@withLock
                }
                val automaticSelection =
                    OnlineTranslationSourcePreferences.isAutoSelectBestSourceEnabled(prefs)
                val preferredSource = configuredSources.first()
                val alternativeSource = configuredSources.drop(1).firstOrNull()
                val requestedFirstSource =
                    pendingTranslationSourceRequest?.requestedSource
                        ?: pendingPronunciationSourceRequest?.requestedSource
                val raceEnabled = automaticSelection && requestedFirstSource == null &&
                    temporaryTranslationSource == null && temporaryPronunciationSource == null
                val firstSource = requestedFirstSource
                    ?.takeIf(configuredSources::contains)
                    ?: preferredSource
                val remainingSources = listOf(firstSource) + configuredSources.filterNot {
                    it == firstSource
                }
                val playerPackage = activeCentralPlayerPackageName ?: LyriconSource.APPLE_MUSIC_PACKAGE
                val completeOnlinePronunciation = playerPackage == LyriconSource.APPLE_MUSIC_PACKAGE &&
                    ApplePronunciationVisibilityPolicy.allowsOnlineSupplementation(
                        song = baseSong,
                        hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                    )
                val totalLineCount = baseSong.lyrics.orEmpty().sumOf { line ->
                    if (line.text.isNullOrBlank()) {
                        0
                    } else {
                        (if (!OnlineTranslationContentPolicy.isMeaningful(line.translation)) 1 else 0) +
                        (if (
                            completeOnlinePronunciation && line.roma.isNullOrBlank()
                        ) 1 else 0)
                    }
                }
                val mediaInfo = MediaMetadataHelper.getMediaInfo(application, playerPackage)
                val searchDuration = if (playerPackage == LyriconSource.APPLE_MUSIC_PACKAGE) {
                    AppleOnlineTranslationSearchDurationPolicy.resolve(
                        song = baseSong,
                        media = AppleOnlineTranslationSearchDurationPolicy.MediaSnapshot(
                            title = mediaInfo.title,
                            artist = mediaInfo.artist,
                            durationMs = mediaInfo.duration,
                        ),
                    )
                } else {
                    AppleOnlineTranslationSearchDurationPolicy.Resolution(
                        durationMs = baseSong.duration.takeIf { it > 0L }
                            ?: mediaInfo.duration,
                        mediaIdentityMatched = false,
                    )
                }
                diagnostic(
                    "在线歌词补全开始: player=$playerPackage, title=${baseSong.name}, " +
                        "artist=${baseSong.artist}, preferred=$preferredSource, " +
                        "first=$firstSource, requested=${requestedFirstSource ?: "none"}"
                )
                pronunciationDiagnostic(
                    "stage=search_duration_resolved, generation=$generation, id=${baseSong.id}, " +
                        "lyricDuration=${baseSong.duration}, mediaDuration=${mediaInfo.duration}, " +
                        "mediaTitle=${mediaInfo.title}, mediaArtist=${mediaInfo.artist}, " +
                        "identityMatched=${searchDuration.mediaIdentityMatched}, " +
                        "selectedDuration=${searchDuration.durationMs}"
                )
                pronunciationDiagnostic(
                    "stage=request_started, generation=$generation, id=${baseSong.id}, " +
                        "preferred=$preferredSource, order=${remainingSources.joinToString("+")}, " +
                        "pronunciationAllowed=$completeOnlinePronunciation, " +
                    "targetContent=$totalLineCount"
                )
                val searchStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_search_started",
                    details = "generation=$generation,order=${remainingSources.joinToString("+")}," +
                        "race=$raceEnabled,targetContent=$totalLineCount",
                )
                val fetchedCandidates = linkedMapOf<Source, OnlineTranslationSelector.Candidate>()
                if (raceEnabled && remainingSources.size > 1) {
                    pronunciationDiagnostic(
                        "stage=race_started, generation=$generation, id=${baseSong.id}, " +
                            "sources=${remainingSources.joinToString("+")}"
                    )
                    fetchedCandidates += OnlineTranslationRace.run(
                        sources = remainingSources,
                        clockMs = SystemClock::elapsedRealtime,
                        fetch = { source ->
                            fetchOnlineTranslationCandidate(
                                application = application,
                                baseSong = baseSong,
                                source = source,
                                totalLineCount = totalLineCount,
                                generation = generation,
                                searchDurationMs = searchDuration.durationMs,
                            )
                        },
                        onCompletion = { completion ->
                            val source = completion.source
                            val candidate = completion.value
                            pronunciationDiagnostic(
                                "stage=race_source_finished, generation=$generation, " +
                                    "id=${baseSong.id}, source=$source, elapsedMs=${completion.elapsedMs}, " +
                                    "found=${candidate != null}, error=${completion.error?.javaClass?.name ?: "none"}"
                            )
                            completion.error?.let { error ->
                                debugError(
                                    "在线翻译赛马来源失败: title=${baseSong.name}, source=$source",
                                    error,
                                )
                            }
                            if (candidate != null) {
                                if (onlineRaceFirstPublishedGeneration != generation &&
                                    candidate.matchedContentCount > 0
                                ) {
                                    val firstSelection = OnlineTranslationSelection(
                                        onlineLinesBySource = mapOf(source to candidate.onlineLines),
                                        requestedSources = listOf(source),
                                        defaultTranslationSource = source.takeIf {
                                            OnlineTranslationMatcher.contributesTranslation(
                                                baseSong,
                                                candidate.result,
                                            )
                                        },
                                        defaultPronunciationSource = source.takeIf {
                                            OnlineTranslationMatcher.contributesPronunciation(
                                                baseSong,
                                                candidate.result,
                                            )
                                        },
                                        sourceOrder = listOf(source),
                                        pronunciationRequested = completeOnlinePronunciation,
                                    )
                                    onlineTranslationRequest.markFirstPublished(generation)
                                    pronunciationDiagnostic(
                                        "stage=race_first_ready, generation=$generation, " +
                                            "id=${baseSong.id}, source=$source, " +
                                            "matchedContent=${candidate.matchedContentCount}"
                                    )
                                    postTranslationApply(
                                        selection = firstSelection,
                                        publicationStage =
                                            LyriconSource.OnlineTranslationPublicationStage.RACE_FIRST,
                                        reason = "race_first_ready",
                                    )
                                }
                            }
                        },
                    )
                } else {
                    remainingSources.forEachIndexed { index, source ->
                        val previous = fetchedCandidates.values.lastOrNull()
                        val explicitlyRequested = temporaryTranslationSource == source ||
                            temporaryPronunciationSource == source
                        if (index > 0 && !automaticSelection && !explicitlyRequested &&
                            !OnlineTranslationSelector.shouldTryAlternative(previous, totalLineCount)
                        ) {
                            return@forEachIndexed
                        }
                        if (index > 0) {
                            diagnostic(
                                "在线翻译继续尝试后续来源: " +
                                    "title=${baseSong.name}, source=$source"
                            )
                        }
                        fetchOnlineTranslationCandidate(
                            application = application,
                            baseSong = baseSong,
                            source = source,
                            totalLineCount = totalLineCount,
                            generation = generation,
                            searchDurationMs = searchDuration.durationMs,
                        )?.let { fetchedCandidates[source] = it }
                    }
                }
                val candidates = fetchedCandidates
                val rankedCandidates = if (automaticSelection) {
                    OnlineTranslationSelector.rank(
                        candidates = candidates.values,
                        totalLineCount = totalLineCount,
                        tieBreakOrder = OnlineTranslationSourcePreferences.defaultOrder,
                    )
                } else {
                    val preferredCandidate = candidates[preferredSource]
                    val alternativeCandidate = alternativeSource?.let(candidates::get)
                    val selectedCandidate = OnlineTranslationSelector.select(
                        preferred = preferredCandidate,
                        alternative = alternativeCandidate,
                        totalLineCount = totalLineCount,
                    )
                    buildList {
                        selectedCandidate?.let(::add)
                        listOfNotNull(preferredCandidate, alternativeCandidate)
                            .filterNot(::contains)
                            .forEach(::add)
                        candidates.values.filterNot(::contains).forEach(::add)
                    }
                }
                val selected = rankedCandidates.firstOrNull()
                val supplementalCandidates = rankedCandidates.drop(1)
                val defaultTranslationCandidate = rankedCandidates
                    .firstOrNull {
                        OnlineTranslationMatcher.contributesTranslation(baseSong, it.result)
                    }
                val defaultPronunciationCandidate = rankedCandidates
                    .firstOrNull {
                        OnlineTranslationMatcher.contributesPronunciation(baseSong, it.result)
                    }
                val selection = OnlineTranslationSelection(
                    onlineLinesBySource = candidates.mapValues { it.value.onlineLines },
                    requestedSources = remainingSources,
                    defaultTranslationSource = defaultTranslationCandidate?.source,
                    defaultPronunciationSource = defaultPronunciationCandidate?.source,
                    forcedTranslationSource = temporaryTranslationSource,
                    forcedPronunciationSource = temporaryPronunciationSource,
                    sourceOrder = if (automaticSelection) {
                        rankedCandidates.map { it.source }
                    } else {
                        configuredSources
                    },
                    pronunciationRequested = completeOnlinePronunciation,
                )
                val currentPublishedSong = (if (playerPackage == LyriconSource.APPLE_MUSIC_PACKAGE) {
                    currentPublishedAppleSong
                } else {
                    currentPublishedThirdPartySong
                })
                    ?.takeIf { isSameTrack(it, baseSong) }
                    ?.let { publishedSong ->
                        ApplePronunciationVisibilityPolicy.filterSong(
                            song = publishedSong,
                            hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                        )
                    }
                val mergedResult = selection.compose(baseSong, currentPublishedSong)
                val selectedTranslationSource = mergedResult
                    ?.song
                    ?.metadata
                    ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
                val selectedPronunciationSource = mergedResult
                    ?.song
                    ?.metadata
                    ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
                diagnostic(
                    "在线翻译来源选择: player=$playerPackage, title=${baseSong.name}, " +
                    "preferred=$preferredSource, selected=${selected?.source}, " +
                    "translation=$selectedTranslationSource, " +
                    "pronunciation=$selectedPronunciationSource, " +
                    "compared=${rankedCandidates.size > 1}, automatic=$automaticSelection"
                )
                HookLogger.i(
                    LyriconSource.TAG,
                    "在线翻译来源选择: player=$playerPackage, title=${baseSong.name}, " +
                        "selected=${selected?.source}, " +
                        "translation=$selectedTranslationSource, " +
                        "pronunciation=$selectedPronunciationSource, " +
                        "compared=${rankedCandidates.size > 1}, automatic=$automaticSelection"
                )
                val selectedMatchedCount = selected?.result?.matchedCount ?: 0
                if (mergedResult != null && mergedResult.matchedCount > selectedMatchedCount) {
                    diagnostic(
                        "在线翻译已由后续来源补齐: player=$playerPackage, title=${baseSong.name}, " +
                            "source=${supplementalCandidates.joinToString("+") { it.source.name }}, " +
                            "matched=$selectedMatchedCount->${mergedResult.matchedCount}"
                    )
                }
                pronunciationDiagnostic(
                    "stage=selection_composed, generation=$generation, id=${baseSong.id}, " +
                        "candidateSources=${candidates.keys.joinToString("+")}, " +
                        "selected=${selected?.source}, translationSource=$selectedTranslationSource, " +
                        "pronunciationSource=$selectedPronunciationSource, " +
                        "resultPresent=${mergedResult != null}, " +
                        "resultRomanized=${mergedResult?.song?.lyrics.orEmpty().count { !it.roma.isNullOrBlank() }}"
                )
                if (raceEnabled) {
                    pronunciationDiagnostic(
                        "stage=race_finished, generation=$generation, id=${baseSong.id}, " +
                            "ranking=${rankedCandidates.joinToString(",") { candidate ->
                                "${candidate.source}:${formatMetric(OnlineTranslationSelector.quality(candidate, totalLineCount))}"
                            }}"
                    )
                }
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_search_finished",
                    details = "generation=$generation,elapsedMs=" +
                        ((SystemClock.elapsedRealtimeNanos() - searchStartedAtNanos) /
                            1_000_000.0) +
                        ",candidates=${candidates.keys.joinToString("+")}," +
                        "selected=${selected?.source ?: "none"}",
                )
                postTranslationApply(
                    selection = selection,
                    publicationStage = if (raceEnabled) {
                        LyriconSource.OnlineTranslationPublicationStage.RACE_FINAL
                    } else {
                        LyriconSource.OnlineTranslationPublicationStage.SINGLE
                    },
                    reason = "search_finished",
                )
            }
        } catch (e: CancellationException) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_job_cancelled",
                details = "generation=$generation," +
                    "currentGeneration=$onlineTranslationGeneration",
            )
            pronunciationDiagnostic(
                "stage=request_cancelled, generation=$generation, id=${baseSong.id}, " +
                    "currentGeneration=$onlineTranslationGeneration"
            )
            throw e
        } catch (e: Exception) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_job_failed",
                details = "generation=$generation,error=${e.javaClass.simpleName}",
            )
            pronunciationDiagnostic(
                "stage=request_failed, generation=$generation, id=${baseSong.id}, " +
                    "error=${e.javaClass.name}, message=${e.message}"
            )
            postTranslationApply(
                selection = null,
                publicationStage = LyriconSource.OnlineTranslationPublicationStage.SINGLE,
                reason = "search_failed",
            )
            debugError(
                "在线翻译匹配失败: player=$activeCentralPlayerPackageName, " +
                    "title=${baseSong.name}",
                e,
            )
        }
    }
    return true
}

internal suspend fun LyriconSource.fetchOnlineTranslationCandidate(
    application: Application,
    baseSong: LocalSong,
    source: Source,
    totalLineCount: Int,
    generation: Int,
    searchDurationMs: Long,
): OnlineTranslationSelector.Candidate? {
    val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
    val fetchStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "translation_source_fetch_started",
        details = "generation=$generation,source=$source,durationMs=$searchDurationMs",
    )
    pronunciationDiagnostic(
        "stage=candidate_fetch_started, generation=$generation, id=${baseSong.id}, source=$source"
    )
    val mediaInfo = MediaMetadataHelper.getMediaInfo(application, LyriconSource.APPLE_MUSIC_PACKAGE, HookLogger)
    val originalAlbum = baseSong.metadata
        ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ALBUM)
        ?.takeIf(String::isNotBlank)
    val albumForSearch = originalAlbum ?: mediaInfo.album
    val fetchOutcome = OnlineLyricTargeter.fetchBestLyricWithNearMiss(
        context = application,
        pkgName = LyriconSource.APPLE_MUSIC_PACKAGE,
        title = baseSong.name.orEmpty(),
        artist = baseSong.artist.orEmpty(),
        durationMs = searchDurationMs,
        originalTitle = baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
        originalArtist = baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST),
        preferOriginalMetadata = shouldPreferAppleOriginalMetadata(),
        preferredSource = source,
        requireTranslation = false,
        fallbackToOtherSources = false,
        album = albumForSearch,
        originalAlbum = originalAlbum,
    )
    val onlineLines = fetchOutcome.lines ?: fetchOutcome.nearMiss?.lines
    if (onlineLines == null) {
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_source_fetch_finished",
            details = "generation=$generation,source=$source,elapsedMs=" +
                ((SystemClock.elapsedRealtimeNanos() - fetchStartedAtNanos) /
                    1_000_000.0) +
                ",found=false",
        )
        pronunciationDiagnostic(
            "stage=candidate_fetch_finished, generation=$generation, id=${baseSong.id}, " +
                "source=$source, found=false"
        )
        diagnostic(
            "在线翻译候选未命中: player=$activeCentralPlayerPackageName, " +
                "title=${baseSong.name}, source=$source"
        )
        return null
    }
    val appleRequest = activeCentralPlayerPackageName == LyriconSource.APPLE_MUSIC_PACKAGE ||
        currentThirdPartySong == null
    val filteredOnlineLines = ApplePronunciationVisibilityPolicy.filterOnlineLines(
        song = baseSong,
        onlineLines = onlineLines,
        hideMandarinPinyin = appleRequest && isHideMandarinPinyinEnabled(),
    ).let { lines ->
        if (appleRequest) lines else lines.map { it.copy(romanization = null) }
    }
    val result = OnlineTranslationMatcher.apply(baseSong, filteredOnlineLines)
    val baseLines = baseSong.lyrics.orEmpty()
    val enrichedLines = result.song.lyrics.orEmpty()
    val matchedTranslationCount = baseLines.indices.count { index ->
        !OnlineTranslationContentPolicy.isMeaningful(baseLines[index].translation) &&
            OnlineTranslationContentPolicy.isMeaningful(
                enrichedLines.getOrNull(index)?.translation
            )
    }
    val matchedPronunciationCount = baseLines.indices.count { index ->
        baseLines[index].roma.isNullOrBlank() &&
            !enrichedLines.getOrNull(index)?.roma.isNullOrBlank()
    }
    if (fetchOutcome.nearMiss != null) {
        val verification = AppleOnlineTranslationNearMissPolicy.VerificationInputs(
            score = fetchOutcome.nearMiss.score,
            missingTranslationCount = baseLines.count {
                !OnlineTranslationContentPolicy.isMeaningful(it.translation)
            },
            matchedTranslationCount = matchedTranslationCount,
            missingPronunciationCount = baseLines.count { it.roma.isNullOrBlank() },
            matchedPronunciationCount = matchedPronunciationCount,
            averageMatchScore = result.averageMatchScore,
            durationVerified = fetchOutcome.nearMiss.durationVerified,
        )
        val coverage = AppleOnlineTranslationNearMissPolicy.contentCoverage(verification)
        if (!AppleOnlineTranslationNearMissPolicy.accepts(verification)) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_source_fetch_finished",
                details = "generation=$generation,source=$source,elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - fetchStartedAtNanos) /
                        1_000_000.0) +
                    ",found=false,reason=near_miss_rejected," +
                    "matchedTranslation=$matchedTranslationCount," +
                    "matchedPronunciation=$matchedPronunciationCount",
            )
            pronunciationDiagnostic(
                "stage=near_miss_rejected, generation=$generation, id=${baseSong.id}, " +
                    "source=$source, score=${fetchOutcome.nearMiss.score}, " +
                    "coverage=${formatMetric(coverage)}, " +
                    "confidence=${formatMetric(result.averageMatchScore)}, " +
                    "matchedTranslation=$matchedTranslationCount, " +
                    "matchedPronunciation=$matchedPronunciationCount, " +
                    "durationVerified=${fetchOutcome.nearMiss.durationVerified}"
            )
            diagnostic(
                "在线翻译近失候选未通过歌词重叠校验: " +
                    "player=$activeCentralPlayerPackageName, " +
                    "title=${baseSong.name}, source=$source, " +
                    "score=${fetchOutcome.nearMiss.score}, coverage=${formatMetric(coverage)}"
            )
            return null
        }
        pronunciationDiagnostic(
            "stage=near_miss_accepted, generation=$generation, id=${baseSong.id}, " +
                "source=$source, score=${fetchOutcome.nearMiss.score}, " +
                "coverage=${formatMetric(coverage)}, " +
                "confidence=${formatMetric(result.averageMatchScore)}, " +
                "matchedTranslation=$matchedTranslationCount, " +
                "matchedPronunciation=$matchedPronunciationCount, " +
                "durationVerified=${fetchOutcome.nearMiss.durationVerified}"
        )
    }
    val candidate = OnlineTranslationSelector.Candidate(
        source = source,
        onlineLineCount = onlineLines.size,
        translatedLineCount = onlineLines.count {
            OnlineTranslationContentPolicy.isMeaningful(it.translation)
        },
        result = result,
        romanizedLineCount = filteredOnlineLines.count {
            !it.romanization.isNullOrBlank()
        },
        matchedContentCount = matchedTranslationCount + matchedPronunciationCount,
        onlineLines = filteredOnlineLines,
    )
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "translation_source_fetch_finished",
        details = "generation=$generation,source=$source,elapsedMs=" +
            ((SystemClock.elapsedRealtimeNanos() - fetchStartedAtNanos) /
                1_000_000.0) +
            ",found=true,rawLines=${onlineLines.size}," +
            "matchedTranslation=$matchedTranslationCount," +
            "matchedPronunciation=$matchedPronunciationCount",
    )
    pronunciationDiagnostic(
        "stage=candidate_fetch_finished, generation=$generation, id=${baseSong.id}, " +
            "source=$source, found=true, rawLines=${onlineLines.size}, " +
            "rawRomanized=${onlineLines.count { !it.romanization.isNullOrBlank() }}, " +
            "filteredLines=${filteredOnlineLines.size}, " +
            "filteredRomanized=${candidate.romanizedLineCount}"
    )
    pronunciationDiagnostic(
        "stage=matcher_finished, generation=$generation, id=${baseSong.id}, source=$source, " +
            "matchedLines=${result.matchedCount}, matchedTranslation=$matchedTranslationCount, " +
            "matchedPronunciation=$matchedPronunciationCount, " +
            "resultRomanized=${enrichedLines.count { !it.roma.isNullOrBlank() }}"
    )
    diagnostic(
        "在线翻译候选: player=$activeCentralPlayerPackageName, " +
            "title=${baseSong.name}, source=$source, " +
            "lines=${candidate.onlineLineCount}, " +
            "translated=${candidate.translatedLineCount}, " +
            "romanized=${candidate.romanizedLineCount}, " +
            "matchedLines=${result.matchedCount}, " +
            "matchedContent=${candidate.matchedContentCount}/$totalLineCount, " +
            "coverage=${formatMetric(OnlineTranslationSelector.coverage(candidate, totalLineCount))}, " +
            "confidence=${formatMetric(result.averageMatchScore)}, " +
            "quality=${formatMetric(OnlineTranslationSelector.quality(candidate, totalLineCount))}"
    )
    return candidate
}

