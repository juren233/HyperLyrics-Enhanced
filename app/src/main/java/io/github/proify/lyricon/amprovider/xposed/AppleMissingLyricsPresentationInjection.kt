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

/** I2 是 Apple 原生歌词主结果消费者；在补充改写之前记录真实原生结果。 */
internal fun AppleMissingLyricsHooks.recordAppleNativePresentationResult(pointer: Any?) {
    if (isSupplementPointer(pointer)) return
    val pointerSongId = pointer?.let { sourcePointer ->
        val songNative = runCatching {
            AppleReflection.call(
                sourcePointer,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
                ),
            )
        }.getOrNull()
        songNative?.let { nativeSong -> runCatching {
            (AppleReflection.call(
                nativeSong,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD
                ),
            ) as? Number)?.toLong()?.takeIf { it > 0L }?.toString()
        }.getOrNull() }
    }
    val songId = pointerSongId
        ?: currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
        ?: return
    val lineCount = pointer?.let(::readNativeLineCount) ?: 0
    val wordTimedLineCount = pointer?.let(::readNativeWordTimedLineCount) ?: 0
    if (lineCount > 0 && wordTimedLineCount >= 0) {
        val stats = AppleNativeLyricsTimingStats(
            lineCount = lineCount,
            wordTimedLineCount = wordTimedLineCount,
        )
        val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
        val identity = store.playbackIdentity(queueSongId)
        val contentSongId = when {
            identity?.adamId?.toString() == songId -> identity.contentSongId
            queueSongId == songId -> queueSongId
            else -> songId
        }
        nativeAlternatives.rememberTiming(songId, contentSongId, stats)
    }
    onNativeLyricsState(songId = songId, hasLines = lineCount > 0)
    if (BuildConfig.DEBUG) {
        ProviderLogger.diagnostic(
            "Apple Music 原生歌词主结果: id=$songId, lines=$lineCount, " +
                "wordTimedLines=$wordTimedLineCount, pointer=${pointer != null}"
        )
    }
}

/**
 * 原生歌词模型入参改写（I2 结果消费与 buildTimeRangeToLyricsMap 共用）：
 * 普通补充仅在 Apple 没有原生歌词时改写；LunaBeat 被选中时允许用其逐字模型
 * 替换 Apple 原生模型，并保留原生指针供用户临时切回。
 */
internal fun AppleMissingLyricsHooks.rewriteNativeModelArgs(chain: Chain): Array<Any?>? {
    if (chain.args.isEmpty()) return null
    val originalPointer = chain.args.firstOrNull()
    val songId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
        ?: currentSupplementSongId()
    if (isSupplementPointer(originalPointer)) {
        if (shouldPreferLunaBeat(songId)) return null
        val nativePointer = songId?.let { nativeAlternatives.pointer(it) }
            ?.takeIf { readNativeLineCount(it) > 0 }
            ?: return null
        ProviderLogger.debug(
            "Apple Music 歌词来源临时切回原生模型: id=$songId, pointer=$nativePointer"
        )
        return arrayOf(nativePointer)
    }
    val hasNativeLyrics = originalPointer != null &&
        readNativeLineCount(originalPointer) > 0
    if (hasNativeLyrics && !songId.isNullOrBlank()) {
        nativeAlternatives.rememberPointer(songId, requireNotNull(originalPointer))
    }
    // LunaBeat 被临时选择时允许替换 Apple 原生模型；其他补充来源仍只服务无歌词歌曲。
    if (hasNativeLyrics && !shouldPreferLunaBeat(songId)) return null
    val supplementPointer = supplementPointerForInjection() ?: return null
    ProviderLogger.debug(
        "Apple Music 无歌词补充注入原生模型: " +
            "originalPointer=$originalPointer, pointer=$supplementPointer"
    )
    return arrayOf(supplementPointer)
}

internal fun AppleMissingLyricsHooks.supplementPointerForInjection(): Any? {
    if (!isEnabled()) return null
    val songId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
        ?: currentSupplementSongId()
        ?: return null
    if (!candidates.isAccepted(songId)) return null
    val identity = store.playbackIdentity(songId) ?: return null
    if (hasKnownNativeLyrics(songId, identity.adamId) && !shouldPreferLunaBeat(songId)) return null
    val pointer = store.nativeSongInfoPointer(songId) ?: return null
    if (!store.hasContent(songId)) return null
    return pointer
}

/**
 * 歌词可用性：当前播放条目是「原生无歌词且已有三方补充」的歌曲时，
 * 向 Apple Music 暴露歌词可用，使播放页歌词按钮保持可点、歌词页可进入。
 * 原生歌词存在的歌曲绝不干预。
 */
internal fun AppleMissingLyricsHooks.shouldExposeSupplementLyrics(item: Any?): Boolean {
    val enabled = isEnabled()
    val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
    val itemSongId = itemMediaId(item)?.takeIf(String::isNotBlank)
    // 优先使用当前被查询条目的具体 media ID；当条目自身无法提取 media ID 时，
    // 再使用当前队列歌曲 ID 或从 Adam ID 反推的临时身份。
    // 避免在切歌时，因队列 ID 仍为上一首歌曲而误将上一首的补充歌词可用性判定给新歌曲。
    var songId = itemSongId ?: queueSongId
    var provisionalIdentity: AppleMissingLyricsPlaybackIdentity? = null
    if (enabled && songId == null) {
        // 连 media ID getter 都不可用时，再从 Apple Song/PlaybackItem 的
        // Adam ID 反推内容 ID，保证磁盘缓存仍有机会在首次判定前恢复。
        provisionalIdentity = capturePlaybackIdentity(item)
        songId = provisionalIdentity?.contentSongId
    }
    // 冷启动时队列/播放页可能先于桥接回放调用 hasLyrics()。按钮可用性第一次被
    // 计算并缓存前，必须已从磁盘恢复补充歌词；否则页面会永久记录 false。
    if (enabled && songId != null && !store.hasContent(songId)) {
        restoreCachedSupplement(songId)
        if (!store.hasContent(songId)) {
            val promoted = PlaybackManager.promoteCurrentCacheAfterNativeUnavailable(
                songId = songId,
                nativeLyricsKnown = hasKnownNativeLyrics(songId),
                storeHasContent = false,
            )
            if (promoted && BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 最终原生可用性为 false，已请求降级当前缓存候选: " +
                        "id=$songId"
                )
            }
        }
    }
    val hasContent = store.hasContent(songId)
    val takeoverDecision = songId?.let { takeoverDecision(it) }
        ?: AppleNativeLyricsTakeoverDecision(false, "song_id_missing")
    if (songId != null && hasContent && takeoverDecision.allowed) {
        candidates.accept(songId)
    } else if (songId != null) {
        scheduleTakeoverRecheck(songId)
    }
    val identity = if (enabled && songId != null && hasContent) {
        when {
            itemSongId != null && itemSongId == songId -> capturePlaybackIdentity(item)
                ?: store.playbackIdentity(itemSongId)
            queueSongId != null && queueSongId == songId -> store.playbackIdentity(queueSongId)
                ?: run {
                    if (itemSongId == queueSongId) {
                        capturePlaybackIdentity(item)
                    } else {
                        null
                    }
                }
            else -> capturePlaybackIdentity(item)
        }
    } else {
        store.playbackIdentity(songId) ?: provisionalIdentity
    }
    val nativeLyricsKnown = songId != null && hasKnownNativeLyrics(
        songId = songId,
        adamId = identity?.adamId,
    )
    // 队列 ID 已确认时不再要求当前 hasLyrics() 的 PlaybackItem 身份匹配；
    // 队列 ID 缺失的兜底路径仍必须由捕获到的条目身份证明这是同一首歌。
    val identityAvailable = (queueSongId != null && queueSongId == songId) || identity?.contentSongId == songId
    val shouldExpose = songId != null && shouldExposeSupplementAvailability(
        enabled = enabled,
        hasSupplementContent = hasContent,
        identityAvailable = identityAvailable,
        hasKnownNativeLyrics = nativeLyricsKnown,
    )
    if (shouldExpose) {
        candidates.markAvailable(songId)
        if (candidates.isAccepted(songId)) {
            scheduleNativeLyricsModel(songId)
        }
    }
    logAvailabilityDecision(
        enabled = enabled,
        queueSongId = queueSongId,
        songId = songId,
        hasContent = hasContent,
        itemMediaId = itemSongId,
        identity = identity,
        nativeLyricsKnown = nativeLyricsKnown,
        shouldExpose = shouldExpose,
        supplementAvailabilityExposed =
            songId != null && candidates.wasAvailable(songId),
        presentationAccepted = songId != null && candidates.isAccepted(songId),
        nativeResolutionReason = takeoverDecision.reason,
    )
    return shouldExpose
}

internal fun AppleMissingLyricsHooks.logAvailabilityDecision(
    enabled: Boolean,
    queueSongId: String?,
    songId: String?,
    hasContent: Boolean,
    itemMediaId: String?,
    identity: AppleMissingLyricsPlaybackIdentity?,
    nativeLyricsKnown: Boolean,
    shouldExpose: Boolean,
    supplementAvailabilityExposed: Boolean,
    presentationAccepted: Boolean,
    nativeResolutionReason: String,
) {
    if (!BuildConfig.DEBUG) return
    val signature = listOf(
        enabled,
        queueSongId,
        songId,
        hasContent,
        itemMediaId,
        identity?.adamId,
        identity?.queueId,
        nativeLyricsKnown,
        shouldExpose,
        supplementAvailabilityExposed,
        presentationAccepted,
        nativeResolutionReason,
    ).joinToString("|")
    if (!availabilityDiagnostics.shouldLog(signature)) return
    ProviderLogger.diagnostic(
        "Apple Music 无歌词补充可用性判定: enabled=$enabled, " +
            "queueSongId=$queueSongId, contentId=$songId, hasContent=$hasContent, " +
            "itemMediaId=$itemMediaId, adamId=${identity?.adamId}, " +
            "queueId=${identity?.queueId}, nativeLyricsKnown=$nativeLyricsKnown, " +
            "override=$shouldExpose, " +
            "supplementAvailabilityExposed=$supplementAvailabilityExposed, " +
            "presentationAccepted=$presentationAccepted, " +
            "nativeResolution=$nativeResolutionReason"
    )
}

internal fun AppleMissingLyricsHooks.itemMediaId(item: Any?): String? {
    item ?: return null
    val subscriptionStoreId = runCatching {
        AppleReflection.call(
            item,
            playbackItemTarget.runtimeMemberName(
                AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_SUBSCRIPTION_STORE_ID_METHOD
            ),
        ) as? String
    }.getOrNull()
    if (!subscriptionStoreId.isNullOrBlank()) return subscriptionStoreId
    val persistentId = runCatching {
        AppleReflection.call(
            item,
            playbackItemTarget.runtimeMemberName(
                AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_PERSISTENT_ID_METHOD
            ),
        ) as? Long
    }.getOrNull() ?: 0L
    return persistentId.takeIf { it > 0L }?.toString()
}
