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
import com.juren233.hyperlyricsenhanced.root.utils.AppleMetadataFlowDiagnostics
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ChineseLyricsPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.root.LyricsAvailability
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

internal fun LyriconSource.handleAppleSong(incomingSong: LocalSong?) {
    val previousSong = currentAppleSong
    if (incomingSong != null && !isMissingLyricsSupplement(incomingSong)) {
        publication.rememberNative(incomingSong)
    }
    var claimedInApp: AppleInAppSong.Claim? = null
    val sameTrack = previousSong != null && incomingSong != null &&
        isSameTrack(previousSong, incomingSong)
    // The Pack withdrew the catalog supplement for this song: the catalog selection no longer
    // describes what Apple Music shows, so drop it and let the native lyrics take over.
    val supplementRevoked = sameTrack && hasConfirmedAppleNativeLyrics(incomingSong) &&
        incomingSong.metadata?.getString(LyricMetadataKeys.APPLE_SUPPLEMENT_REVOKED).toBoolean()
    if (supplementRevoked) {
        publication.revokeLyricsSelection(incomingSong.id)
        catalogRevokedSongId = incomingSong.id
        debug("Pack 已撤销目录源补充，回到原生歌词基准: id=${incomingSong.id}")
    }
    val authoritativeLyricsSource = confirmedLyricsSourceSelection
        ?.takeIf { selection ->
            sameTrack &&
                selection.songId == previousSong.id &&
                selection.songId == incomingSong.id
        }
        ?.source
    val mergedSong = mergeMissingLyricsSupplementMetadata(
        previousSong = previousSong,
        incomingSong = incomingSong,
        sameTrack = sameTrack,
        authoritativeSource = authoritativeLyricsSource,
        manualSourceReplacesNative = isAlwaysShowSourceSwitchEnabled(),
    )
    // 正文与来源按合并结果保留;标题/歌手仍跟随最新回调,避免保留正文时丢失晚到的显示元数据。
    val song = mergeRetainedSongDisplayMetadata(
        mergedSong = mergedSong,
        previousSong = previousSong,
        incomingSong = incomingSong,
    )
    if (BuildConfig.DEBUG) AppleMetadataFlowDiagnostics.record("source_merge") {
        "sameTrack=$sameTrack authoritative=$authoritativeLyricsSource " +
            "incoming=${AppleMetadataFlowDiagnostics.local(incomingSong)} " +
            "previous=${AppleMetadataFlowDiagnostics.local(previousSong)} " +
            "merged=${AppleMetadataFlowDiagnostics.local(song)}"
    }
    val authoritativeNativeTransition = sameTrack &&
        isMissingLyricsSupplement(previousSong) &&
        hasConfirmedAppleNativeLyrics(song)
    if (authoritativeNativeTransition) {
        diagnostic(
            "Apple Music 原生歌词权威接管: id=${song?.id}, " +
                "previousLines=${previousSong.lyrics.orEmpty().size}, " +
                "nativeLines=${song?.lyrics.orEmpty().size}"
        )
    }
    if (mergedSong === previousSong && incomingSong !== previousSong && authoritativeLyricsSource != null) {
        diagnostic(
            "忽略过期 Apple Music 歌词来源回传: id=${previousSong?.id}, " +
                "authoritative=$authoritativeLyricsSource, incoming=" +
                incomingSong?.metadata
                    ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
        )
    }
    diagnostic(
        "Apple Music 歌曲入口: id=${song?.id}, title=${song?.name}, " +
            "artist=${song?.artist}, duration=${song?.duration}, " +
            "lyrics=${song?.lyrics.orEmpty().size}, supplement=${isMissingLyricsSupplement(song)}, " +
            "onlineEnabled=${isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE)}"
    )
    val preservesCurrentLyrics = previousSong != null && song != null &&
        AppleSongUpdatePolicy.shouldPreserveCurrentLyrics(previousSong, song, sameTrack)
    if (preservesCurrentLyrics) {
        refreshRetainedAppleMetadata(song)
        debug("忽略同一首歌的空歌词降级: title=${song.name}")
        return
    }
    val originalMetadataChanged = sameTrack &&
        AppleOnlineTranslationRequestPolicy.originalMetadataChanged(previousSong, song)
    val untimedNativeLyrics = hasUntimedAppleNativeLyrics(song)
    if (sameTrack && untimedNativeLyrics && !originalMetadataChanged &&
        (fallbackSongActive || appleFallbackRequest.snapshot().pending)
    ) {
        refreshRetainedAppleMetadata(song)
        publication.acceptAppleInput(song, hasAppleNativeLyrics(song))
        debug("Apple Music 无时间轴原生歌词保留在线检索或岛端结果: id=${song?.id}")
        return
    }
    val repeatedEmptySong = sameTrack && song != null && !originalMetadataChanged &&
        song.lyrics.isNullOrEmpty() &&
        (fallbackSongActive || appleFallbackRequest.snapshot().pending)
    if (repeatedEmptySong) {
        refreshRetainedAppleMetadata(song)
        publication.acceptAppleInput(song, false)
        // 在线兜底在途时，Provider 发来的「歌词确认缺失」占位也走到这里被当作重复占位丢弃，
        // 时间轴因此收不到第一方缺失上报，兜底未命中后也永远不会汇总成最终无歌词。
        if (song.metadata?.getBoolean(LyricMetadataKeys.LYRICS_UNAVAILABLE) == true) {
            LyricsAvailability.onFirstPartyReported()
        }
        debug("忽略同一首歌的重复空歌词占位: title=${song.name}")
        return
    }
    // 原生歌词与无歌词补充共用同一套在线翻译匹配任务。同一首歌的后续
    // Apple 回调只更新基准歌词，不能取消正在进行中的翻译任务；否则保留的
    // attempt key 会让兜底结果里的重新调度被误判为重复请求。
    val repeatedLyricsNeedingEnrichment = shouldKeepRunningAppleEnrichment(
        sameTrack = sameTrack,
        authoritativeNativeTransition = authoritativeNativeTransition,
        hasLyrics = !song?.lyrics.isNullOrEmpty(),
        // 来源弹窗的手动选择与自动补全一样，不能被同曲的重复回调打断后清空重来。
        needsEnrichment = !untimedNativeLyrics &&
            (needsOnlineEnrichment(song) || wantsSourceControlSearch(song)),
        originalMetadataChanged = originalMetadataChanged,
        enrichmentRunning =
            onlineTranslationRunning || onlineTranslationResultReady || onlineMatchedTranslationActive,
    )
    if (repeatedLyricsNeedingEnrichment) {
        refreshRetainedAppleMetadata(song)
        publication.acceptAppleInput(song, hasAppleNativeLyrics(song))
        debug("忽略同一首歌的重复待补全歌词: title=${song?.name}")
        return
    }
    cancelFallback(clearAppleSong = false, reason = "apple_song_updated")
    val incomingHasTranslation = hasTranslation(song)
    val incomingNeedsEnrichment = needsOnlineEnrichment(song)
    cancelOnlineTranslation(
        clearAttempt = shouldClearAppleOnlineTranslationAttempt(
            sameTrack = sameTrack,
            authoritativeNativeTransition = authoritativeNativeTransition,
            needsEnrichment = incomingNeedsEnrichment || wantsSourceControlSearch(song),
            originalMetadataChanged = originalMetadataChanged,
        ),
        clearMatched = true,
        reason = "apple_song_updated"
    )
    publication.acceptAppleInput(song, hasAppleNativeLyrics(song))
    if (!sameTrack) {
        applePositionState.beginSongGeneration()
        originalMetadataRequest.clear()
        manualSourceRequests.clearForTrackChange()
        // Apple Music 此前不是活动播放者时，应用内槽位里同一首歌的手动选择在此转正，其他歌曲作废。
        claimedInApp = inAppApple.claimFor(incomingSong, ::isMissingLyricsSupplement)
        claimedInApp?.selection?.let { manualSourceRequests.restoreSelection(it) }
        publication.beginAppleTrack(incomingSong?.takeUnless(::isMissingLyricsSupplement))
        refreshAppleMediaPositionReference()
    }
    val originalMetadataPlan = AppleOnlineTranslationRequestPolicy.originalMetadataLookupPlan(
        song != null && shouldRequestOriginalMetadataForOnlineLookup(song)
    )
    pronunciationDiagnostic(
        "stage=request_entry_gate, id=${song?.id}, generation=${applePositionState.songGeneration()}, " +
            "prefsPresent=${prefs != null}, matchingEnabled=${isAppleTranslationEnrichmentEnabled()}, " +
            "lyrics=${song?.lyrics.orEmpty().size}, needsEnrichment=${needsOnlineEnrichment(song)}, " +
            "titlePresent=${!song?.name.isNullOrBlank()}, " +
            "requestOriginal=${originalMetadataPlan.requestOriginalMetadata}, " +
            "waitOriginal=${originalMetadataPlan.waitForResult}, sameTrack=$sameTrack, " +
            "originalMetadataChanged=$originalMetadataChanged"
    )
    if (song != null && originalMetadataPlan.requestOriginalMetadata) {
        requestOriginalMetadata(song, "setting_enabled")
    }

    runCatching {
        publishAppleSong(song, restorePosition = sameTrack)
    }.onFailure {
        debugError("Apple Music 歌曲发布失败: title=${song?.name}", it)
    }

    val lineToWordSource = enabledTtmlCatalogSources().firstOrNull()
    debug(
        "逐行转逐字判定: id=${song?.id}, 补充歌词=${isMissingLyricsSupplement(song)}, " +
            "开关=${isLineToWordLyricsEnabled()}, 首个目录源=$lineToWordSource, " +
            "无时间轴原生=$untimedNativeLyrics, 原生歌词=${hasAppleNativeLyrics(song)}"
    )
    if (
        song != null &&
        !isMissingLyricsSupplement(song) &&
        isLineToWordLyricsEnabled() &&
        lineToWordSource != null &&
        !untimedNativeLyrics &&
        song.id != catalogRevokedSongId
    ) {
        scheduleFallback(
            baseSong = song,
            delayMs = 0L,
            preferredSourceOverride = lineToWordSource,
            strictSource = hasAppleNativeLyrics(song) || !isFillMissingLyricsEnabled(),
        )
    } else if (
        song != null &&
        needsMissingLyricsSourceRecovery(song) &&
        (
            isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE) ||
                isFillMissingLyricsEnabled() || untimedNativeLyrics
            )
    ) {
        HookLogger.i(
            LyriconSource.TAG,
            "Apple Music 缺少可用原生时间轴，立即预取在线候选: " +
                "title=${song.name}, untimedNative=$untimedNativeLyrics"
        )
        // 候选检索可以与 Apple 原生请求并行；Apple 进程内的 takeover gate
        // 仍会在原生状态未确认前禁止呈现，因此这里不再额外等待 5 秒。
        scheduleFallback(song, 0L)
    }
    if (
        song != null &&
        !originalMetadataPlan.waitForResult &&
        // 来源弹窗：检索时机为「开始播放时」，或本曲已有手动选择/状态请求（规则见该函数）。
        wantsOnlineSearchAfterSongUpdate(song)
    ) {
        HookLogger.i(
            LyriconSource.TAG,
            "Apple Music 歌词待补全: title=${song.name}, " +
                "supplement=${isMissingLyricsSupplement(song)}, " +
                "lines=${song.lyrics.orEmpty().size}, " +
                "hasTranslation=$incomingHasTranslation"
        )
        if (originalMetadataChanged) {
            HookLogger.i(
                LyriconSource.TAG,
                "Apple Music 原名已更新，重新匹配在线翻译: " +
                    "title=${song.name}, originalTitle=${song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE)}"
            )
        }
        scheduleOnlineTranslation(song)
    }
    claimedInApp?.let(::restoreClaimedInAppLyricsSource)
}

/**
 * 直连歌曲被活动播放者规则拒收（别的 App 才是活动播放者）：不发布到岛端、不改动活动播放者状态，
 * 只存入应用内槽位，让 Apple Music 歌词页的来源弹窗仍能对它检索与手动切换来源。
 */
internal fun LyriconSource.acceptInAppAppleSong(song: LocalSong?) {
    val accepted = inAppApple.accept(song, ::hasAppleNativeLyrics, ::isMissingLyricsSupplement)
    diagnostic(
        "stage=in_app_apple_song_accepted, update=${accepted.update}, id=${song?.id}, " +
            "title=${song?.name}, lyrics=${song?.lyrics.orEmpty().size}"
    )
    val inAppSong = inAppApple.song()
    if (accepted.update != AppleInAppSong.Update.SAME_TRACK || inAppSong == null) return
    // 歌词晚到、原名到达等同曲更新：与正式路径同一条规则决定是否重新检索；
    // 同一 attempt（含原名元数据）已在进行或已完成时不重复触发。
    if (
        wantsOnlineSearchAfterSongUpdate(inAppSong, inAppApple.target) &&
        translationIdentity(inAppSong) != inAppApple.target.request.snapshot().attempt
    ) {
        scheduleOnlineTranslation(inAppSong, inAppApple.target)
    }
}

/**
 * 活动播放者离开 Apple Music：当前 Apple 歌曲、它的本曲手动选择和已确认的歌词来源转入应用内槽位。
 * 岛端显示归属相关的状态（发布、位置、检索任务）仍由调用处照旧清理。
 * 待完成的切换请求都得到确定的结局：翻译/发音请求随歌曲转入槽位并重新检索完成，歌词来源请求
 * （歌词检索任务不随歌曲移交）以失败回传。
 */
internal fun LyriconSource.retireAppleSongToInApp(reason: String) {
    val song = currentAppleSong ?: return
    val lyricsPending = manualSourceRequests.lyricsRequestToComplete(null)
    inAppApple.adopt(
        song = song,
        nativeSong = currentAppleNativeSong,
        hasNativeLyrics = currentAppleHasNativeLyrics,
        lyricsSelection = confirmedLyricsSourceSelection,
        selection = manualSourceRequests.selection(),
    )
    manualSourceRequests.clearForTrackChange()
    diagnostic("stage=apple_song_retired_to_in_app, reason=$reason, id=${song.id}, title=${song.name}")
    lyricsPending?.let { publishOnlineSourceSwitchResult(it, actualSource = null) }
    val target = inAppApple.target
    if (target.manual.hasPendingOnlineRequest(song.id)) {
        val scheduled = !hasUntimedAppleNativeLyrics(song) && scheduleOnlineTranslation(song, target)
        if (!scheduled) failPendingOnlineSourceSwitchRequests(target)
    }
}

/**
 * Apple Music 成为活动播放者后，应用内槽位里同一首歌已确认的歌词来源转正：
 * 补充歌曲成为正式的 Apple 输入并正常上岛，翻译/发音按新基准重新检索。
 */
internal fun LyriconSource.restoreClaimedInAppLyricsSource(claim: AppleInAppSong.Claim) {
    val supplement = claim.supplementSong ?: return
    val selection = claim.lyricsSelection ?: return
    val application = app ?: return
    publication.confirmLyricsSource(selection)
    publication.acceptAppleInput(supplement, false)
    selectAppleFallbackAtMediaPosition(application)
    cancelOnlineTranslation(
        clearAttempt = true,
        clearMatched = false,
        reason = "in_app_lyrics_source_claimed",
    )
    publishAppleSong(
        supplement,
        restorePosition = true,
        onlineTranslationMatched = hasTranslation(supplement),
    )
    if (wantsOnlineSearchAfterSongUpdate(supplement)) scheduleOnlineTranslation(supplement)
    // 在线歌词显示状态下直连位置回调不采用，进度改由 MediaSession 轮询提供（与检索结果落地一致）。
    startMediaPositionPolling()
}
