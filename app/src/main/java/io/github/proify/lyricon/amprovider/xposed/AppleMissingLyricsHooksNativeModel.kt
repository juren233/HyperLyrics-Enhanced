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

internal fun AppleMissingLyricsHooks.currentPlaybackItem(songId: String): Any? {
    val reference = currentPlaybackItemReference ?: return null
    if (reference.identity.contentSongId != songId) return null
    if (store.playbackIdentity(songId) != reference.identity) return null
    return reference.item.get()
}

internal fun AppleMissingLyricsHooks.hasKnownNativeLyrics(songId: String, adamId: Long? = null): Boolean =
    nativeLyricsKnowledge.contains(
        contentId = songId,
        adamId = adamId?.toString(),
        storedAdamId = store.playbackIdentity(songId)?.adamId?.toString(),
    )

internal fun AppleMissingLyricsHooks.capturePlaybackIdentity(
    item: Any?,
    expectedContentSongId: String? = null,
    queueIdOverride: Long? = null,
): AppleMissingLyricsPlaybackIdentity? {
    item ?: return null
    val runtimeAdamId = runCatching {
        AppleReflection.call(
            item,
            lyricsSongTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_SONG_ID_METHOD
            ),
        )?.toString()?.toLongOrNull()
    }.getOrNull()
    val itemMediaId = itemMediaId(item)
    val adamId = selectPlaybackAdamId(
        runtimeAdamId = runtimeAdamId,
        itemMediaId = itemMediaId,
        expectedContentSongId = expectedContentSongId,
    ) ?: return null
    val itemQueueId = runCatching {
        (AppleReflection.call(
            item,
            lyricsSongTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_SONG_QUEUE_ID_METHOD
            ),
        ) as? Number)?.toLong()
    }.getOrNull() ?: 0L
    val queueId = queueIdOverride?.takeIf { it > 0L } ?: itemQueueId
    val contentSongId = expectedContentSongId
        ?.takeIf(String::isNotBlank)
        ?: currentPlaybackQueueMediaId()
        ?.takeIf(String::isNotBlank)
        ?: itemMediaId
        ?: adamId.toString()
    if (
        !itemMediaId.isNullOrBlank() &&
        itemMediaId != contentSongId &&
        adamId.toString() != contentSongId
    ) {
        ProviderLogger.debug(
            "Apple Music 无歌词补充忽略过期 PlaybackItem: " +
                "contentId=$contentSongId, itemMediaId=$itemMediaId, adamId=$adamId"
        )
        return null
    }
    val identity = AppleMissingLyricsPlaybackIdentity(
        contentSongId = contentSongId,
        adamId = adamId,
        queueId = queueId,
    )
    if (store.updatePlaybackIdentity(identity)) {
        ProviderLogger.debug(
            "Apple Music 无歌词补充已捕获播放身份: " +
                "contentId=$contentSongId, adamId=$adamId, queueId=$queueId"
        )
    }
    return identity
}

/**
 * Apple 自身链路在 TTML 解析后会调用 SongInfoNative.setAdamId/setQueueId
 * （6.5.1 原始 DEX：ttml/f#e）。歌词页 I2 会严格比较 adamId 与当前条目 ID。
 */
internal fun AppleMissingLyricsHooks.applyNativeSongIdentity(
    pointer: Any,
    identity: AppleMissingLyricsPlaybackIdentity,
): Boolean {
    val songNative = runCatching {
        AppleReflection.call(
            pointer,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
            ),
        )
    }.getOrNull() ?: return false
    val writeResult = runCatching {
        AppleReflection.call(
            songNative,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_SET_ADAM_ID_METHOD
            ),
            identity.adamId,
        )
        AppleReflection.call(
            songNative,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_SET_QUEUE_ID_METHOD
            ),
            identity.queueId,
        )
    }
    if (writeResult.isFailure) {
        ProviderLogger.error(
            "Apple Music 无歌词补充歌曲身份写入失败",
            writeResult.exceptionOrNull(),
        )
        return false
    }
    val actualAdamId = runCatching {
        (AppleReflection.call(
            songNative,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD
            ),
        ) as? Number)?.toLong()
    }.getOrNull()
    val actualQueueId = runCatching {
        (AppleReflection.call(
            songNative,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_QUEUE_ID_METHOD
            ),
        ) as? Number)?.toLong()
    }.getOrNull()
    if (actualAdamId != identity.adamId || actualQueueId != identity.queueId) {
        ProviderLogger.error(
            "Apple Music 无歌词补充歌曲身份校验失败: " +
                "expectedAdamId=${identity.adamId}, actualAdamId=$actualAdamId, " +
                "expectedQueueId=${identity.queueId}, actualQueueId=$actualQueueId"
        )
        return false
    }
    return true
}

internal fun AppleMissingLyricsHooks.nativePointerAddress(pointer: Any): Long? = runCatching {
    (AppleReflection.call(
        pointer,
        lyricsNativeTarget.runtimeMemberName(
            AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_ADDRESS_METHOD
        ),
    ) as? Number)?.toLong()
}.getOrNull()

internal fun AppleMissingLyricsHooks.releaseNativePointer(pointer: Any?) {
    pointer ?: return
    runCatching { AppleReflection.call(pointer, "deallocate") }
        .onFailure {
            ProviderLogger.debug(
                "Apple Music 补充歌词未绑定原生指针释放失败: ${it.message}"
            )
        }
}

internal fun AppleMissingLyricsHooks.firstNativeLine(pointer: Any): Any? {
    val songNative = runCatching {
        AppleReflection.call(
            pointer,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
            ),
        )
    }.getOrNull() ?: return null
    val sections = runCatching {
        AppleReflection.call(
            songNative,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD
            ),
        )
    }.getOrNull() ?: return null
    val firstSectionPointer = runCatching {
        AppleReflection.call(
            sections,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
            ),
            0L,
        )
    }.recoverCatching {
        AppleReflection.call(
            sections,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
            ),
            0,
        )
    }.getOrNull() ?: return null
    val firstSection = runCatching {
        AppleReflection.call(
            firstSectionPointer,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
            ),
        )
    }.getOrNull() ?: return null
    val linesVector = runCatching {
        AppleReflection.call(
            firstSection,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD
            ),
        )
    }.getOrNull() ?: return null
    val firstLinePointer = runCatching {
        AppleReflection.call(
            linesVector,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
            ),
            0L,
        )
    }.recoverCatching {
        AppleReflection.call(
            linesVector,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
            ),
            0,
        )
    }.getOrNull() ?: return null
    return runCatching {
        AppleReflection.call(
            firstLinePointer,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
            ),
        )
    }.getOrNull()
}

internal fun AppleMissingLyricsHooks.readNativeLineCount(pointer: Any): Int {
    val songNative = runCatching {
        AppleReflection.call(
            pointer,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
            ),
        )
    }.getOrNull() ?: return -1
    val sections = runCatching {
        AppleReflection.call(
            songNative,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD
            ),
        )
    }.getOrNull() ?: return -1
    var totalLines = 0
    val sectionCount = runCatching {
        (AppleReflection.call(
            sections,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD
            ),
        ) as? Number)?.toInt()
    }.getOrNull() ?: return -1
    for (sectionIndex in 0 until sectionCount.coerceAtMost(16)) {
        val sectionPointer = runCatching {
            AppleReflection.call(
                sections,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
                ),
                sectionIndex.toLong(),
            )
        }.recoverCatching {
            AppleReflection.call(
                sections,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
                ),
                sectionIndex,
            )
        }.getOrNull() ?: continue
        val section = runCatching {
            AppleReflection.call(
                sectionPointer,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
                ),
            )
        }.getOrNull() ?: continue
        val linesVector = runCatching {
            AppleReflection.call(
                section,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD
                ),
            )
        }.getOrNull() ?: continue
        val lineCount = runCatching {
            (AppleReflection.call(
                linesVector,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD
                ),
            ) as? Number)?.toInt()
        }.getOrNull() ?: continue
        totalLines += lineCount
    }
    return totalLines
}

internal fun AppleMissingLyricsHooks.readNativeWordTimedLineCount(pointer: Any): Int {
    val songNative = runCatching {
        AppleReflection.call(
            pointer,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
            ),
        )
    }.getOrNull() ?: return -1
    val sections = runCatching {
        AppleReflection.call(
            songNative,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD
            ),
        )
    }.getOrNull() ?: return -1
    val sectionCount = nativeVectorSize(sections)
    if (sectionCount < 0) return -1
    var wordTimedLines = 0
    for (sectionIndex in 0 until sectionCount.coerceAtMost(16)) {
        val section = nativeVectorNativeItem(sections, sectionIndex) ?: continue
        val linesVector = runCatching {
            AppleReflection.call(
                section,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD
                ),
            )
        }.getOrNull() ?: continue
        val lineCount = nativeVectorSize(linesVector)
        if (lineCount <= 0) continue
        for (lineIndex in 0 until lineCount.coerceAtMost(512)) {
            val line = nativeVectorNativeItem(linesVector, lineIndex) ?: continue
            val wordsVector = runCatching {
                AppleReflection.call(
                    line,
                    lyricsNativeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD
                    ),
                )
            }.getOrNull() ?: continue
            if (nativeVectorSize(wordsVector) > 0) wordTimedLines += 1
        }
    }
    return wordTimedLines
}

internal fun AppleMissingLyricsHooks.nativeVectorSize(vector: Any): Int = runCatching {
    (AppleReflection.call(
        vector,
        lyricsNativeTarget.runtimeMemberName(
            AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD
        ),
    ) as? Number)?.toInt()
}.getOrNull() ?: -1

internal fun AppleMissingLyricsHooks.nativeVectorNativeItem(vector: Any, index: Int): Any? {
    val pointer = runCatching {
        AppleReflection.call(
            vector,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
            ),
            index.toLong(),
        )
    }.recoverCatching {
        AppleReflection.call(
            vector,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
            ),
            index,
        )
    }.getOrNull() ?: return null
    return runCatching {
        AppleReflection.call(
            pointer,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
            ),
        )
    }.getOrNull()
}

internal fun AppleMissingLyricsHooks.readNativeWordCount(pointer: Any): Int {
    val firstLine = firstNativeLine(pointer) ?: return -1
    val wordsVector = runCatching {
        AppleReflection.call(
            firstLine,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD
            ),
        )
    }.getOrNull() ?: return -1
    return runCatching {
        (AppleReflection.call(
            wordsVector,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD
            ),
        ) as? Number)?.toInt()
    }.getOrNull() ?: -1
}
