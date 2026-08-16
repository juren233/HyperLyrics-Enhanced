/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys

internal data class AppleMissingLyricsWord(
    val begin: Long,
    val end: Long,
    val text: String,
)

internal data class AppleMissingLyricsLine(
    val begin: Long,
    val end: Long,
    val text: String,
    val words: List<AppleMissingLyricsWord>,
    val translation: String? = null,
)

internal data class AppleMissingLyricsPlaybackIdentity(
    val contentSongId: String,
    val adamId: Long,
    val queueId: Long,
)

/**
 * 保存三方在线源为「Apple Music 原生无歌词」歌曲补充的完整歌词。
 * 只承载原始歌词文本与逐字时间轴，不参与翻译/发音补全。
 */
internal class AppleMissingLyricsStore {
    private data class Content(
        val songId: String,
        val durationMs: Long,
        val lines: List<AppleMissingLyricsLine>,
        val sourceInfo: AppleMissingLyricsSourceInfo?,
        val translationSource: String?,
        val pronunciationSource: String?,
    )

    private data class NativeModel(
        val pointer: Any,
        val contentRevision: Long,
        val identity: AppleMissingLyricsPlaybackIdentity,
    )

    @Volatile
    private var content: Content? = null

    @Volatile
    private var contentRevision = 0L

    @Volatile
    private var playbackIdentity: AppleMissingLyricsPlaybackIdentity? = null

    @Volatile
    private var nativeModel: NativeModel? = null

    /**
     * Apple 可能在切歌后的媒体元数据回调中继续读取上一首 SongInfoPtr。
     * 已经交给 Apple 的指针不能在切歌时立即 deallocate，否则 Java wrapper
     * 仍然存在但底层地址会变成 NULL，最终在 PlayerLyricsViewFragment.N2() 崩溃。
     * 这些指针保留到 Apple Music 进程结束，由进程生命周期统一回收。
     */
    private val retainedNativePointers = ArrayList<Any>()

    @Synchronized
    fun update(song: Song): Boolean {
        val songId = song.id?.takeIf(String::isNotBlank) ?: return false
        val previousContent = content?.takeIf { it.songId == songId }
        val previousLines = previousContent?.lines
        val lines = song.lyrics.orEmpty().mapNotNull { line ->
            // Apple 原生歌词的行首/行尾不能携带布局空白；中日韩歌词连行内空白也去掉。
            val trimmedText = line.text?.trim().orEmpty()
            val containsCjk = containsCjk(trimmedText)
            val text = if (containsCjk) removeWhitespace(trimmedText) else trimmedText
            if (text.isEmpty() || line.begin < 0L || line.end <= line.begin) {
                return@mapNotNull null
            }
            AppleMissingLyricsLine(
                begin = line.begin,
                end = line.end,
                text = text,
                words = normalizeWords(line, text, containsCjk),
                translation = line.translation?.trim()?.takeIf(String::isNotEmpty)
                    ?: matchingPreviousTranslation(previousLines, line.begin, line.end, text),
            )
        }
        if (lines.isEmpty()) return false
        val updated = Content(
            songId = songId,
            durationMs = song.duration.coerceAtLeast(lines.last().end),
            lines = lines,
            sourceInfo = AppleMissingLyricsSourceMetadata.decode(
                selectedSource = song.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE),
                encodedStatuses = song.metadata
                    ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES),
            ),
            translationSource = song.metadata
                ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
                ?: previousContent?.translationSource,
            pronunciationSource = song.metadata
                ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
                ?: previousContent?.pronunciationSource,
        )
        if (content == updated) return false
        // TTML/native 模型只依赖主歌词文本与逐字时间轴；翻译和来源状态变化不需要
        // 重建模型，否则在线翻译赛马的多个同曲载荷会反复清空已暴露的 native model，
        // 造成歌词页多层错位、回顶再回到当前句的“抽搐”。
        val current = content
        val lyricsChanged = current == null ||
            current.songId != updated.songId ||
            current.durationMs != updated.durationMs ||
            current.lines.map { it.copy(translation = null) } !=
                updated.lines.map { it.copy(translation = null) }
        val translationChanged = current != null &&
            current.lines.map { it.translation } != updated.lines.map { it.translation }
        // 已经暴露给 Apple 的 native model 不包含后来才到达的翻译。翻译内容真正变化
        // 时必须重建一次，否则磁盘缓存先构建、翻译后到的时序下页面永远没有翻译，
        // 左下角翻译按钮也会因 translationAvailable=false 消失。
        // 同曲 RACE 载荷只有来源元数据变化时 translationChanged=false，仍不重建。
        val requiresNativeRebuild = lyricsChanged ||
            (translationChanged && nativeModel != null)
        if (!lyricsChanged) {
            content = updated
            if (requiresNativeRebuild) {
                clearNativeModelLocked()
                contentRevision += 1
                return true
            }
            if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "Apple Music 补充歌词翻译/来源已更新但不重建: " +
                        "id=$songId, revision=$contentRevision"
                )
            }
            return false
        }
        clearNativeModelLocked()
        content = updated
        contentRevision += 1
        if (playbackIdentity?.contentSongId != songId) {
            playbackIdentity = null
        }
        return true
    }

    /**
     * 歌词来源切换的载荷只携带主歌词/逐字时间轴，不携带翻译。翻译由独立在线翻译
     * 链路补齐，因此在重排内容时按行时间轴复用当前翻译，避免来源切换造成翻译与
     * 左下角翻译按钮闪断；独立翻译结果到达后再正常覆盖。
     */
    private fun matchingPreviousTranslation(
        previousLines: List<AppleMissingLyricsLine>?,
        begin: Long,
        end: Long,
        text: String,
    ): String? {
        previousLines ?: return null
        val normalized = AppleNativeOnlineTranslationStore.normalizeText(text)
        val exact = previousLines.firstOrNull { previous ->
            previous.begin == begin &&
                previous.end == end &&
                AppleNativeOnlineTranslationStore.normalizeText(previous.text) == normalized
        }?.translation
        if (!exact.isNullOrBlank()) return exact
        return previousLines
            .asSequence()
            .filter { previous ->
                AppleNativeOnlineTranslationStore.normalizeText(previous.text) == normalized &&
                    !previous.translation.isNullOrBlank()
            }
            .minByOrNull { previous ->
                kotlin.math.abs(previous.begin - begin) +
                    kotlin.math.abs(previous.end - end)
            }
            ?.translation
    }

    /**
     * 统一迁移新旧载荷中的逐字文本。
     *
     * 旧版本可能已经把 words 保存成「Hotel」「California」，但 line.text 仍然是
     * 「Hotel California」。这里按整行文本的顺序重新投影边界空白，避免必须删除
     * 整个磁盘缓存才能恢复英文空格；如果整行无法匹配，则保留源片段原文。
     */
    private fun normalizeWords(
        line: RichLyricLine,
        sourceText: String,
        containsCjk: Boolean,
    ): List<AppleMissingLyricsWord> {
        var sourceCursor = 0
        val normalized = ArrayList<AppleMissingLyricsWord>(line.words.orEmpty().size)

        for (word in line.words.orEmpty()) {
            val rawText = word.text.orEmpty()
            val cleanText = if (containsCjk) removeWhitespace(rawText) else rawText.trim()
            if (cleanText.isEmpty()) continue
            val matchStart = if (cleanText.isNotEmpty()) {
                sourceText.indexOf(cleanText, sourceCursor)
            } else {
                -1
            }
            val normalizedText = if (matchStart >= sourceCursor) {
                val prefix = sourceText.substring(sourceCursor, matchStart)
                val currentPrefix = if (normalized.isNotEmpty()) {
                    // 把分隔空格挂到前一个 span 尾部，避免 Apple 换行后新行首出现空格。
                    appendToPrevious(normalized, prefix)
                    ""
                } else {
                    // 行首只保留标点等正文，丢弃源数据可能带入的布局空白。
                    prefix.trimStart()
                }
                sourceCursor = matchStart + cleanText.length
                currentPrefix + cleanText
            } else if (
                !containsCjk &&
                    normalized.isNotEmpty() &&
                    normalized.last().end < word.begin &&
                    needsAsciiWordSeparator(normalized.last().text, cleanText)
            ) {
                // 某些旧载荷的整行文本也已丢失空格；仅在真实时间间隔和 ASCII 词边界
                // 同时成立时把空格补到前一个片段，避免新行首出现前导空格。
                appendToPrevious(normalized, " ")
                cleanText
            } else {
                cleanText
            }
            val converted = AppleMissingLyricsWord(
                begin = word.begin,
                end = word.end,
                text = normalizedText,
            )
            normalized += converted
        }
        return normalized
    }

    /** 修改列表中的最后一个逐字片段，保持分隔符位于前一个片段的末尾。 */
    private fun appendToPrevious(
        normalized: MutableList<AppleMissingLyricsWord>,
        suffix: String,
    ) {
        if (suffix.isEmpty() || normalized.isEmpty()) return
        val index = normalized.lastIndex
        val previous = normalized[index]
        normalized[index] = previous.copy(text = previous.text + suffix)
    }

    /** 判断两个文本片段是否构成需要空格的 ASCII 英文词边界。 */
    private fun needsAsciiWordSeparator(previous: String, current: String): Boolean {
        if (previous.lastOrNull()?.isWhitespace() == true) return false
        val previousChar = previous.lastOrNull() ?: return false
        val currentChar = current.firstOrNull() ?: return false
        return previousChar.code < 128 &&
            currentChar.code < 128 &&
            previousChar.isLetterOrDigit() &&
            currentChar.isLetterOrDigit()
    }

    /** 判断一行是否包含汉字、平假名、片假名或谚文。 */
    private fun containsCjk(text: String): Boolean = text.codePoints().anyMatch { codePoint ->
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> true
            else -> false
        }
    }

    /** 删除中日韩行和逐字片段中的 Unicode 空白，避免 Apple 重新排版时显示空格。 */
    private fun removeWhitespace(text: String): String = buildString(text.length) {
        text.codePoints().forEach { codePoint ->
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                appendCodePoint(codePoint)
            }
        }
    }

    /**
     * 记录 Apple 歌词页当前 PlaybackItem 的真实身份。
     *
     * [contentSongId] 是跨进程补充载荷使用的 ID；[adamId]/[queueId] 则必须来自
     * Apple Music 实际传给 PlayerLyricsViewModel.loadLyrics() 的 PlaybackItem。
     */
    @Synchronized
    fun updatePlaybackIdentity(identity: AppleMissingLyricsPlaybackIdentity): Boolean {
        if (playbackIdentity == identity) return false
        clearNativeModelLocked()
        playbackIdentity = identity
        return true
    }

    fun playbackIdentity(songId: String?): AppleMissingLyricsPlaybackIdentity? {
        if (songId.isNullOrBlank()) return null
        return playbackIdentity?.takeIf { it.contentSongId == songId }
    }

    fun isCurrentIdentity(identity: AppleMissingLyricsPlaybackIdentity): Boolean =
        content?.songId == identity.contentSongId && playbackIdentity == identity

    /**
     * 绑定由 Apple 原生 TTML 解析器生成的模型。
     * 只有内容修订与 PlaybackItem 身份仍完全一致时才接受，避免切歌后复用旧指针。
     */
    @Synchronized
    fun updateNativeSongInfoPointer(
        pointer: Any,
        identity: AppleMissingLyricsPlaybackIdentity,
    ): Boolean {
        val currentContent = content
        if (
            currentContent == null ||
            currentContent.songId != identity.contentSongId ||
            playbackIdentity != identity
        ) {
            deallocate(pointer)
            return false
        }
        val currentModel = nativeModel
        if (
            currentModel?.pointer === pointer &&
            currentModel.contentRevision == contentRevision &&
            currentModel.identity == identity
        ) {
            return true
        }
        clearNativeModelLocked()
        nativeModel = NativeModel(
            pointer = pointer,
            contentRevision = contentRevision,
            identity = identity,
        )
        return true
    }

    fun nativeSongInfoPointer(songId: String? = null): Any? {
        val currentContent = content ?: return null
        if (!songId.isNullOrBlank() && currentContent.songId != songId) return null
        val currentIdentity = playbackIdentity ?: return null
        val model = nativeModel ?: return null
        return model.pointer.takeIf {
            model.contentRevision == contentRevision &&
                model.identity == currentIdentity &&
                currentContent.songId == currentIdentity.contentSongId
        }
    }

    /**
     * 返回本进程创建且可能仍被 Apple 歌词链持有的全部原生指针快照。
     *
     * 当前模型用于后续注入；保留模型已经失去注入资格，但 Apple 的异步回调仍可能
     * 继续读取它们。两者都必须被识别为补充歌词，避免旧模型被误记为 Apple 原生歌词。
     */
    @Synchronized
    fun knownNativeSongInfoPointers(): List<Any> = buildList {
        nativeModel?.pointer?.let(::add)
        retainedNativePointers.forEach { retained ->
            if (none { it === retained }) add(retained)
        }
    }

    @Synchronized
    fun clear(songId: String? = null): Boolean {
        val current = content ?: run {
            clearNativeModelLocked()
            return false
        }
        if (!songId.isNullOrBlank() && current.songId != songId) return false
        content = null
        contentRevision += 1
        clearNativeModelLocked()
        return true
    }

    private fun clearNativeModelLocked() {
        nativeModel?.pointer?.let(::retainNativePointerLocked)
        nativeModel = null
    }

    /** 将已经暴露给 Apple 的旧指针转入进程级保留集，避免异步回调读取悬空地址。 */
    private fun retainNativePointerLocked(pointer: Any) {
        if (retainedNativePointers.any { it === pointer }) return
        retainedNativePointers += pointer
        ProviderLogger.debug(
            "Apple Music 补充歌词旧原生指针进入保留集: count=${retainedNativePointers.size}"
        )
    }

    private fun deallocate(pointer: Any?) {
        pointer ?: return
        runCatching { AppleReflection.call(pointer, "deallocate") }
            .onFailure {
                ProviderLogger.debug(
                    "Apple Music 补充歌词原生指针释放失败: ${it.message}"
                )
            }
    }

    fun revision(): Long = contentRevision

    fun lines(songId: String?): List<AppleMissingLyricsLine> {
        if (songId.isNullOrBlank()) return emptyList()
        return content?.takeIf { it.songId == songId }?.lines.orEmpty()
    }

    fun durationMs(songId: String?): Long {
        if (songId.isNullOrBlank()) return 0L
        return content?.takeIf { it.songId == songId }?.durationMs ?: 0L
    }

    fun hasContent(songId: String?): Boolean = lines(songId).isNotEmpty()

    /** 当前 Store 正在服务的歌曲 ID；无内容时为 null。 */
    fun contentSongId(): String? = content?.songId

    fun hasTranslation(songId: String?): Boolean = lines(songId).any {
        !it.translation.isNullOrBlank()
    }

    fun translationSource(songId: String?): String? =
        content?.takeIf { songId.isNullOrBlank() || it.songId == songId }?.translationSource

    fun pronunciationSource(songId: String?): String? =
        content?.takeIf { songId.isNullOrBlank() || it.songId == songId }?.pronunciationSource

    fun translation(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): String? {
        val current = content?.takeIf { it.songId == songId } ?: return null
        val normalized = AppleNativeOnlineTranslationStore.normalizeText(text)
        return current.lines.firstOrNull { line ->
            line.begin == begin && line.end == end &&
                AppleNativeOnlineTranslationStore.normalizeText(line.text) == normalized
        }?.translation ?: current.lines.singleOrNull { line ->
            line.begin == begin && line.end == end
        }?.translation
    }

    fun sourceInfo(songId: String?): AppleMissingLyricsSourceInfo? =
        content?.takeIf { it.songId == songId }?.sourceInfo
}
