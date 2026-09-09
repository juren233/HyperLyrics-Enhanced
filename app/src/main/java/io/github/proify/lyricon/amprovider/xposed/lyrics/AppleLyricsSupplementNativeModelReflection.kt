/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

/** Apple 原生歌词模型（SongInfo/LyricsSection/LyricsLine/LyricsWord）的只读反射访问。 */
internal fun AppleLyricsSupplementHooks.appleLyricsCurrentPlaybackPositionMs(): Long? =
    playbackHooks().currentPositionMs()

internal fun AppleLyricsSupplementHooks.isAppleLyricsRecyclerView(recyclerView: Any): Boolean =
    blurHooks.isAppleLyricsRecyclerView(recyclerView)

internal fun AppleLyricsSupplementHooks.resolveAppleLyricsRecyclerView(fragment: Any): Any? =
    blurHooks.resolveAppleLyricsRecyclerView(fragment)

internal fun AppleLyricsSupplementHooks.isAppleRecyclerViewInstance(value: Any): Boolean =
    blurHooks.isAppleRecyclerViewInstance(value)

internal fun AppleLyricsSupplementHooks.isAppleRecyclerViewClass(clazz: Class<*>): Boolean =
    blurHooks.isAppleRecyclerViewClass(clazz)

internal fun AppleLyricsSupplementHooks.appleRecyclerAdapter(recyclerView: Any): Any? =
    blurHooks.appleRecyclerAdapter(recyclerView)

internal fun AppleLyricsSupplementHooks.appleRecyclerAdapterItemCount(adapter: Any): Int =
    blurHooks.appleRecyclerAdapterItemCount(adapter)

internal fun AppleLyricsSupplementHooks.appleRecyclerNotifyDataSetChanged(adapter: Any) =
    blurHooks.appleRecyclerNotifyDataSetChanged(adapter)

internal fun AppleLyricsSupplementHooks.onlineTranslationForNativeLine(line: Any?): String? {
    if (line == null) return null
    val begin = (
        lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number
        )?.toLong()
        ?: return null
    val end = (
        lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD) as? Number
        )?.toLong()
        ?: return null
    val text = AppleLyricTextTransform.withRawReads {
        lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
            as? String
    }
    return nativeOnlineTranslationStore.translation(
        songId = currentAppleLyricsSongId,
        begin = begin,
        end = end,
        text = text,
    ) ?: missingLyricsSupplement().translationForLine(
        songId = currentAppleLyricsSongId,
        begin = begin,
        end = end,
        text = text,
    )
}

internal fun AppleLyricsSupplementHooks.hasAnyOnlineTranslation(songId: String?): Boolean =
    (isNativeOnlineTranslationEnabled() && nativeOnlineTranslationStore.hasTranslation(songId)) ||
        missingLyricsSupplement().hasTranslation(songId)
// 注意：这里刻意不计入 hasLunaBeatSource。LB 来源的歌曲靠
// ensureMissingLyricsTranslationButtonVisible（hasSupplementContent）强制保留
// translations_button 作为来源菜单入口，与“是否有翻译”无关；若把 LB 计入
// 翻译可用性，Apple 会在无翻译的 LB 歌曲菜单里渲染无效的「隐藏翻译」项。

internal fun AppleLyricsSupplementHooks.onlinePronunciationForNativeLine(line: Any?): String? {
    if (line == null || !isNativeOnlineTranslationEnabled()) return null
    if (shouldHideMandarinPronunciation()) return null
    val begin = (
        lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number
        )?.toLong()
        ?: return null
    val end = (
        lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD) as? Number
        )?.toLong()
        ?: return null
    val text = AppleLyricTextTransform.withRawReads {
        lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
            as? String
    }
    return RomanizationPolicy.sanitize(
        originalText = text,
        pronunciation = nativeOnlineTranslationStore.pronunciation(
            songId = currentAppleLyricsSongId,
            begin = begin,
            end = end,
            text = text,
        ),
    )
}

internal fun AppleLyricsSupplementHooks.nativeOriginalLineText(line: Any?): String? {
    if (line == null) return null
    return nativeRawLineText(
        line,
        lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD),
    )
}

internal fun AppleLyricsSupplementHooks.nativeOriginalBackgroundLineText(line: Any?): String? {
    if (line == null) return null
    return nativeRawLineText(
        line,
        lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD),
    )
}

internal fun AppleLyricsSupplementHooks.nativeRawLineText(line: Any, getter: String): String? =
    AppleLyricTextTransform.withRawReads {
        runCatching { AppleReflection.call(line, getter) as? String }.getOrNull()
    }

internal fun AppleLyricsSupplementHooks.nativeRawWordVectorText(vector: Any?): String? =
    AppleLyricTextTransform.withRawReads {
        nativeVectorItems(vector, limit = 256)
            .joinToString(separator = "") { word ->
                runCatching {
                    lyricsNativeCall(word, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
                        as? String
                }.getOrNull().orEmpty()
            }
            .trim()
            .takeIf(String::isNotEmpty)
    }

internal fun AppleLyricsSupplementHooks.nativeRenderableWordBegins(vector: Any?): List<Int> =
    AppleLyricTextTransform.withRawReads {
        nativeVectorItems(vector, limit = 256).mapNotNull { word ->
            val isWhitespace = runCatching {
                lyricsNativeCall(
                    word,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_WHITESPACE_METHOD,
                ) as? Boolean
            }.getOrNull() == true
            val text = runCatching {
                lyricsNativeCall(word, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
                    as? String
            }.getOrNull()?.trim().orEmpty()
            val begin = runCatching {
                (lyricsNativeCall(
                    word,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD,
                ) as? Number)?.toInt()
            }.getOrNull()
            begin?.takeIf { !isWhitespace && text.isNotEmpty() && it >= 0 }
        }
    }

internal fun AppleLyricsSupplementHooks.nativeSongId(songNative: Any?): String? = songNative?.let { song ->
    runCatching {
        lyricsNativeCall(song, AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD)?.toString()
    }.getOrNull()?.takeIf(String::isNotBlank)
}

internal fun AppleLyricsSupplementHooks.nativePronunciationLanguages(songNative: Any?): List<String> =
    nativeVectorStrings(
        songNative?.let { song ->
            runCatching {
                lyricsNativeCall(
                    song,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
                )
            }.getOrNull()
        }
    )

internal fun AppleLyricsSupplementHooks.nativeVectorItems(vector: Any?, limit: Int): List<Any> {
    vector ?: return emptyList()
    val size = nativeVectorSize(vector)
    return buildList {
        repeat(minOf(size, limit)) { index ->
            val pointer = runCatching {
                lyricsNativeCall(
                    vector,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                    index.toLong(),
                )
            }.recoverCatching {
                lyricsNativeCall(
                    vector,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                    index,
                )
            }.getOrNull() ?: return@repeat
            val value = runCatching {
                lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
            }.getOrNull() ?: pointer
            add(value)
        }
    }
}

internal fun AppleLyricsSupplementHooks.nativeVectorStrings(vector: Any?, limit: Int = 16): List<String> {
    vector ?: return emptyList()
    val size = minOf(nativeVectorSize(vector), limit)
    return buildList {
        repeat(size) { index ->
            val value = runCatching {
                lyricsNativeCall(
                    vector,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                    index.toLong(),
                ) as? String
            }.recoverCatching {
                lyricsNativeCall(
                    vector,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                    index,
                ) as? String
            }.getOrNull()?.takeIf(String::isNotBlank)
            if (value != null) add(value)
        }
    }
}

internal fun AppleLyricsSupplementHooks.nativeVectorSize(vector: Any?): Int = vector?.let {
    runCatching {
        (lyricsNativeCall(
            it,
            AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD,
        ) as? Number)?.toInt()
    }.getOrNull()?.coerceAtLeast(0)
} ?: 0
