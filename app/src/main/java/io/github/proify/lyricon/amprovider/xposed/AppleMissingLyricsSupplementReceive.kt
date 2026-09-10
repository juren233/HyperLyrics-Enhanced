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
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal fun AppleMissingLyricsHooks.receiveSupplement(song: Song) {
    if (!isEnabled()) {
        ProviderLogger.debug("Apple Music 无歌词补充被忽略: reason=feature_disabled")
        return
    }
    val songId = song.id?.takeIf(String::isNotBlank) ?: return
    val receiveStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    val incomingLines = song.lyrics.orEmpty()
    val incomingWordLines = incomingLines.count { it.words.orEmpty().size >= 2 }
    val lunaBeatSupplement = isLunaBeatSupplement(song) &&
        isLunaBeatEligibleForSong(
            songId = songId,
            sourceInfo = song.metadata?.let { metadata ->
                com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata.decode(
                    selectedSource = metadata.getString(
                        com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE
                    ),
                    encodedStatuses = metadata.getString(
                        com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES
                    ),
                )
            },
            lineCountOverride = incomingLines.size,
        )
    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
        songId = songId,
        stage = "supplement_store_receive_started",
        details = "lines=${incomingLines.size},wordLines=$incomingWordLines," +
            "thread=${Thread.currentThread().name}"
    )
    AppleSourceSwitchPerformanceDiagnostics.record(
        songId = songId,
        event = "supplement_received",
        units = incomingLines.size.toLong(),
        details = "wordLines=$incomingWordLines,thread=${Thread.currentThread().name}",
    )
    nativeTakeoverGate.observe(songId)
    if (hasKnownNativeLyrics(songId) && !lunaBeatSupplement) {
        ProviderLogger.info(
            "Apple Music 无歌词补充被原生歌词拒绝: id=$songId, " +
                "reason=native_lyrics_present"
        )
        discardSupplementForConfirmedNativeLyrics(songId)
        return
    }
    if (lunaBeatSupplement) {
        candidates.accept(songId)
    }
    val storeUpdateStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
        songId = songId,
        stage = "supplement_store_update_started",
        details = "thread=${Thread.currentThread().name}"
    )
    val receipt = store.receive(song)
    val updateResult = receipt.result
    val hadContent = receipt.hadContent
    val revisionBefore = receipt.revisionBefore
    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
        songId = songId,
        stage = "supplement_store_update_finished",
        details = "kind=${updateResult.kind},changed=${updateResult.requiresNativeRebuild}," +
            "revision=$revisionBefore->${receipt.nativeModelRevision}," +
            "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - storeUpdateStartedAtNanos) / 1_000_000.0}," +
            "thread=${Thread.currentThread().name}"
    )
    AppleSourceSwitchPerformanceDiagnostics.record(
        songId = songId,
        event = "store_update",
        durationNanos = SystemClock.elapsedRealtimeNanos() - storeUpdateStartedAtNanos,
        units = incomingLines.size.toLong(),
        details = "kind=${updateResult.kind}," +
            "changed=${updateResult.requiresNativeRebuild}," +
            "revision=$revisionBefore->${receipt.nativeModelRevision}",
    )
    if (updateResult.requiresNativeRebuild) {
        ProviderLogger.info(
            "Apple Music 无歌词补充已接收: id=$songId, " +
                "lines=${incomingLines.size}, wordLines=$incomingWordLines"
        )
        val diskWriteStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val diskSaved = DiskSongManager.saveMissingLyrics(song)
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "disk_cache_write",
            durationNanos = SystemClock.elapsedRealtimeNanos() - diskWriteStartedAtNanos,
            details = "saved=$diskSaved,contentChanged=true",
        )
        if (!diskSaved) {
            ProviderLogger.debug(
                "Apple Music 无歌词补充磁盘缓存写入失败: id=$songId"
            )
        }
        // 候选第一次到达就刷新播放页可用性，让 hasLyrics() 立即重新判定并
        // 保持歌词按钮可进入；这不代表允许构建或注入三方模型。
        if (!hadContent && currentSupplementSongId() == songId) {
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 无歌词补充候选已就绪，刷新按钮可用性: id=$songId"
                )
            }
            refreshNowPlaying(songId)
        }
        // Apple 原生请求仍在进行时不得构建或注入补充模型。最终呈现继续由
        // takeover gate 决定，与上面的按钮可用性刷新相互独立。
        maybeActivateSupplement(songId, trigger = "supplement_received")
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "receive_supplement_total",
            durationNanos = SystemClock.elapsedRealtimeNanos() - receiveStartedAtNanos,
            details = "changed=true,hadContent=$hadContent",
        )
        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
            songId = songId,
            stage = "supplement_store_receive_finished",
            details = "kind=${updateResult.kind},changed=true,hadContent=$hadContent,totalMs=" +
                ((SystemClock.elapsedRealtimeNanos() - receiveStartedAtNanos) / 1_000_000.0) +
                ",thread=${Thread.currentThread().name}"
        )
        return
    }
    // 正文/时间轴没有变化时保留现有 native pointer。翻译变化只重绑当前可见行，
    // 元数据变化只持久化，完全相同的竞速载荷不再写盘或刷新播放页。
    if (updateResult.shouldPersist && store.hasContent(songId)) {
        val diskWriteStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
            songId = songId,
            stage = "supplement_metadata_disk_write_started",
            details = "thread=${Thread.currentThread().name}"
        )
        val diskSaved = DiskSongManager.saveMissingLyrics(song)
        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
            songId = songId,
            stage = "supplement_metadata_disk_write_finished",
            details = "saved=$diskSaved,elapsedMs=" +
                ((SystemClock.elapsedRealtimeNanos() - diskWriteStartedAtNanos) / 1_000_000.0) +
                ",thread=${Thread.currentThread().name}"
        )
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "disk_cache_write",
            durationNanos = SystemClock.elapsedRealtimeNanos() - diskWriteStartedAtNanos,
            details = "saved=$diskSaved,contentChanged=false,kind=${updateResult.kind}",
        )
    }
    if (updateResult.kind == AppleMissingLyricsUpdateKind.TRANSLATION_ONLY) {
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "supplement_translation_visible_refresh_requested",
        )
        refreshVisibleSupplementTranslation(receipt.presentation)
    }
    AppleSourceSwitchPerformanceDiagnostics.record(
        songId = songId,
        event = "receive_supplement_total",
        durationNanos = SystemClock.elapsedRealtimeNanos() - receiveStartedAtNanos,
        details = "kind=${updateResult.kind},changed=false,hadContent=$hadContent",
    )
    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
        songId = songId,
        stage = "supplement_store_receive_finished",
        details = "kind=${updateResult.kind},changed=false,hadContent=$hadContent,totalMs=" +
            ((SystemClock.elapsedRealtimeNanos() - receiveStartedAtNanos) / 1_000_000.0) +
            ",thread=${Thread.currentThread().name}"
    )
}

internal fun AppleMissingLyricsHooks.clearSupplement(songId: String?) {
    candidates.invalidateRestoreWindow()
    songId?.takeIf(String::isNotBlank)?.let { id ->
        candidates.revokeAcceptance(id)
        candidates.revokeAvailability(id)
        candidates.forgetRestore(id)
        DiskSongManager.deleteMissingLyrics(id)
    }
    if (store.clear(songId)) {
        ProviderLogger.debug("Apple Music 无歌词补充已清除: id=$songId")
        refreshNowPlaying(songId)
    }
}

/** 全中文歌词不携带在线翻译：磁盘缓存恢复前剥离旧版本构建写入的翻译列。 */
internal fun AppleMissingLyricsHooks.stripFullyChineseTranslations(song: Song): Song {
    if (!ChineseLyricsPolicy.isFullyChinese(song)) return song
    return song.copy(
        lyrics = song.lyrics.orEmpty().map { line -> line.copy(translation = null) },
    )
}

internal fun AppleMissingLyricsHooks.restoreCachedSupplement(songId: String) {
    if (store.hasContent(songId)) return
    val currentContentSongId = store.contentSongId()
    if (!candidates.beginRestore(songId, currentContentSongId)) return
    val loaded = DiskSongManager.loadMissingLyrics(songId) ?: run {
        ProviderLogger.debug(
            "Apple Music 无歌词补充磁盘恢复未命中: id=$songId"
        )
        return
    }
    if (loaded.id != songId) {
        ProviderLogger.debug(
            "Apple Music 无歌词补充磁盘缓存 ID 不匹配: " +
                "expected=$songId, loaded=${loaded.id}"
        )
        return
    }
    // 全中文翻译门禁同样适用于磁盘恢复：旧版本构建写入的缓存可能携带在线假翻译。
    val cached = stripFullyChineseTranslations(loaded)
    val cachedIsLunaBeat = cached.metadata
        ?.getString(com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE) ==
        AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT
    val cachedLunaBeatEligible = cachedIsLunaBeat && isLunaBeatEligibleForSong(
        songId = songId,
        sourceInfo = cached.metadata?.let { metadata ->
            com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata.decode(
                selectedSource = metadata.getString(
                    com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE
                ),
                encodedStatuses = metadata.getString(
                    com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES
                ),
            )
        },
        lineCountOverride = cached.lyrics.orEmpty().size,
    )
    if (hasKnownNativeLyrics(songId) && !cachedLunaBeatEligible) {
        DiskSongManager.deleteMissingLyrics(songId)
        ProviderLogger.debug(
            "Apple Music 无歌词补充磁盘缓存被原生歌词拒绝: id=$songId"
        )
        return
    }
    nativeTakeoverGate.observe(songId)
    if (!store.update(cached)) return
    if (cachedLunaBeatEligible) candidates.accept(songId)
    ProviderLogger.info(
        "Apple Music 无歌词补充已从磁盘恢复: id=$songId, " +
            "lines=${cached.lyrics.orEmpty().size}"
    )
    maybeActivateSupplement(songId, trigger = "disk_restore")
}

internal fun AppleMissingLyricsHooks.onPreferenceChanged() {
    candidates.resetRestoreAttempts()
    val currentSongId = currentPlaybackQueueMediaId()
    val currentIsLunaBeat = store.sourceInfo(store.contentSongId())
        ?.selectedSource == AppleMissingLyricsHooks.Companion.SourceName.LUNA_BEAT
    if (!isLunaBeatWordLyricsEnabled() && currentIsLunaBeat) {
        currentSongId?.let {
            lyricsSourceSelection.remove(it)
            candidates.revokeAcceptance(it)
            candidates.revokeAvailability(it)
        }
        if (store.clear()) refreshNowPlaying(currentSongId)
        return
    }
    if (!isEnabled()) {
        val songId = currentSongId
        songId?.let {
            candidates.revokeAcceptance(it)
            candidates.revokeAvailability(it)
            takeoverRechecks.remove(it)
            nativeTakeoverGate.clear(it)
        }
        if (store.clear()) {
            refreshNowPlaying(songId)
        }
    }
}

internal fun AppleMissingLyricsHooks.scheduleNativeLyricsModel(songId: String) {
    AppleSourceSwitchPerformanceDiagnostics.record(
        songId = songId,
        event = "native_model_schedule_call",
        details = "revision=${store.revision()}",
    )
    if (!candidates.isAccepted(songId)) {
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "native_model_deferred",
            details = "reason=native_resolution_pending",
        )
        if (BuildConfig.DEBUG) {
            ProviderLogger.debug(
                "Apple Music 无歌词补充暂缓原生模型构建: " +
                    "id=$songId, reason=native_resolution_pending"
            )
        }
        scheduleTakeoverRecheck(songId)
        return
    }
    val identity = store.playbackIdentity(songId) ?: run {
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "native_model_deferred",
            details = "reason=playback_identity_missing",
        )
        ProviderLogger.debug(
            "Apple Music 无歌词补充暂缓原生模型构建: " +
                "id=$songId, reason=playback_identity_missing"
        )
        return
    }
    val key = AppleMissingLyricsHooks.NativeBuildKey(
        contentRevision = store.revision(),
        identity = identity,
    )
    if (!nativeBuildState.begin(key)) {
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "native_model_schedule_deduplicated",
            details = "revision=${key.contentRevision}",
        )
        return
    }
    val queuedAtNanos = SystemClock.elapsedRealtimeNanos()
    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
        songId = songId,
        stage = "native_model_main_posted",
        details = "revision=${key.contentRevision},thread=${Thread.currentThread().name}"
    )
    mainHandler.post {
        if (!nativeBuildState.isCurrent(key)) {
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = songId,
                stage = "native_model_main_skipped",
                details = "reason=key_replaced,revision=${key.contentRevision}," +
                    "thread=${Thread.currentThread().name}"
            )
            return@post
        }
        val mainStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
            songId = songId,
            stage = "native_model_main_started",
            details = "revision=${key.contentRevision},queueWaitMs=" +
                ((mainStartedAtNanos - queuedAtNanos) / 1_000_000.0) +
                ",thread=${Thread.currentThread().name}"
        )
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "native_model_main_queue_wait",
            durationNanos = SystemClock.elapsedRealtimeNanos() - queuedAtNanos,
            details = "revision=${key.contentRevision}",
        )
        try {
            buildNativeLyricsModel(key)
        } finally {
            nativeBuildState.clearIfCurrent(key)
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = songId,
                stage = "native_model_main_finished",
                details = "revision=${key.contentRevision},elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - mainStartedAtNanos) / 1_000_000.0) +
                    ",thread=${Thread.currentThread().name}"
            )
        }
    }
}

internal fun AppleMissingLyricsHooks.buildNativeLyricsModel(key: AppleMissingLyricsHooks.NativeBuildKey) {
    val identity = key.identity
    val songId = identity.contentSongId
    val totalStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    if (!isEnabled() || key.contentRevision != store.revision()) return
    if (!candidates.isAccepted(songId)) return
    if (!store.isCurrentIdentity(identity)) return
    if (hasKnownNativeLyrics(songId, identity.adamId) && !shouldPreferLunaBeat(songId)) {
        ProviderLogger.debug(
            "Apple Music 无歌词补充跳过原生模型构建: reason=native_lyrics_present"
        )
        return
    }
    if (store.nativeSongInfoPointer(songId) != null) return
    val lines = store.lines(songId)
    if (lines.isEmpty()) return
    val ttmlStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    val nativeTtml = store.nativeTtml(songId) ?: return
    val ttml = nativeTtml.content
    AppleSourceSwitchPerformanceDiagnostics.record(
        songId = songId,
        event = if (nativeTtml.kind == AppleMissingLyricsNativeTtmlKind.LUNA_BEAT_RAW) {
            "raw_ttml_selected"
        } else {
            "ttml_build"
        },
        durationNanos = SystemClock.elapsedRealtimeNanos() - ttmlStartedAtNanos,
        units = lines.size.toLong(),
        details = "bytes=${ttml.length},kind=${nativeTtml.kind}",
    )
    val parseStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    val pointer = nativeBuildState.withinScope {
        nativeParser.parse(ttml)
    }
    AppleSourceSwitchPerformanceDiagnostics.record(
        songId = songId,
        event = "native_ttml_parse",
        durationNanos = SystemClock.elapsedRealtimeNanos() - parseStartedAtNanos,
        units = lines.size.toLong(),
        details = "pointer=${pointer != null}",
    )
    if (pointer == null) {
        ProviderLogger.error(
            "Apple Music 无歌词补充原生模型构建失败: id=$songId, " +
                "ttmlBytes=${ttml.length}"
        )
        return
    }
    if (!applyNativeSongIdentity(pointer, identity)) {
        releaseNativePointer(pointer)
        return
    }
    if (!store.updateNativeSongInfoPointer(pointer, identity)) {
        ProviderLogger.debug(
            "Apple Music 无歌词补充原生模型已丢弃: " +
                "id=$songId, reason=stale_identity"
        )
        return
    }
    val parsedLines = readNativeLineCount(pointer)
    val parsedWords = readNativeWordCount(pointer)
    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
        songId = songId,
        stage = "native_model_pointer_installed",
        details = "revision=${key.contentRevision}," +
            "pointer=${pointer.javaClass.name}@${System.identityHashCode(pointer)}," +
            "parsedLines=$parsedLines,parsedWords=$parsedWords",
    )
    ProviderLogger.info(
        "Apple Music 无歌词补充原生模型已生成: id=$songId, " +
            "adamId=${identity.adamId}, queueId=${identity.queueId}, " +
            "ttmlBytes=${ttml.length}, parsedLines=$parsedLines, " +
            "parsedWords=$parsedWords"
    )
    AppleSourceSwitchPerformanceDiagnostics.record(
        songId = songId,
        event = "native_model_build_total",
        durationNanos = SystemClock.elapsedRealtimeNanos() - totalStartedAtNanos,
        units = parsedLines.coerceAtLeast(0).toLong(),
        details = "parsedWords=$parsedWords,revision=${key.contentRevision}",
    )
    val presentationStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    requestPresentationRefresh(pointer, null, currentPlaybackItem(songId))
    AppleSourceSwitchPerformanceDiagnostics.record(
        songId = songId,
        event = "presentation_refresh_request",
        durationNanos = SystemClock.elapsedRealtimeNanos() - presentationStartedAtNanos,
        details = "pointer=true",
    )
}
