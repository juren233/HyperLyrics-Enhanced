/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.RelativeWordBuilder
import com.juren233.hyperlyricsenhanced.lyric.view.isTitleLine
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/**
 * AOD「动态效果」（逐字效果、灵动圆点）的数据侧：把逐字时间轴换算成「已唱字符数」，
 * 取前奏/间奏区间，并（仅 debug 构建）统计息屏下的出帧情况以验证面板刷新率与耗电。
 *
 * 当前行没有逐字数据、或展示文本与逐字文本对不上（如去除了 CJK 空格）时返回 null，
 * 调用方按原来的整行显示处理，不做猜测性回退。
 */
internal object AodWordProgress {

    private const val TAG = "AodWordProgress"
    private const val STATS_WINDOW_MS = 5_000L

    /** 帧统计与不一致日志只在 debug 构建采集；效果本身由「动态效果」开关控制。 */
    private val diagnosticsEnabled: Boolean get() = BuildConfig.DEBUG

    fun wordsOf(words: List<LyricWord>?): List<LyricWord>? =
        words?.takeIf { list -> list.any { it.end > it.begin } }

    private fun displayLine(): IRichLyricLine? =
        if (LyriconDataBridge.isTextMode) null else LyriconDataBridge.currentLyricLine

    private fun isOverlappingGroup(line: IRichLyricLine): Boolean =
        line.metadata?.getBoolean(LyricMetadataKeys.OVERLAPPING_LYRICS_GROUP) == true

    /** 主行的逐字数据；功能未开启、纯文本模式或没有逐字时间轴时为 null。 */
    fun mainWords(): List<LyricWord>? = wordsOf(displayLine()?.words)

    private val relativeWordBuilder = RelativeWordBuilder()
    private var lineSpanKey: Pair<IRichLyricLine, String>? = null
    private var lineSpanCache: List<LyricWord>? = null

    /**
     * 当前主行没有逐字时间轴（逐行歌词）时，把整行展示文本 [text] 当作一个进度单元铺满该行的起止时间：
     * [progressChars] 按非空白字符匀速推进，中文按字、英文按字母，合成代码与岛上相对进度共用，但只受息屏逐字开关控制。
     * 间奏指示、标题占位行、没有有效时长的行返回 null（照旧整行显示）。同一行重复绑定复用同一列表，
     * 避免逐字视图把它当作新内容重绘。
     */
    fun lineSpanWords(text: String): List<LyricWord>? {
        val line = displayLine() ?: return null
        if (!line.words.isNullOrEmpty()) return null
        if (line.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) == true) return null
        if (line.isTitleLine()) return null
        return lineSpanWordsFor(line, text)?.also { lineSpanCache = it }
    }

    internal fun lineSpanWordsFor(line: IRichLyricLine, text: String): List<LyricWord>? {
        if (text.isBlank() || line.begin < 0L || line.end <= line.begin) return null
        val key = line to text
        if (key == lineSpanKey) lineSpanCache?.let { return it }
        lineSpanKey = key
        // 与岛上「相对进度歌词」同一合成：整行一个进度单元，铺满该行起止时间。
        return relativeWordBuilder.build(line, text, null).also { lineSpanCache = it }
    }

    /** 伴唱行的逐字数据：非重叠分组时伴唱就是当前行的 secondary。 */
    fun backingWords(): List<LyricWord>? =
        displayLine()?.takeUnless(::isOverlappingGroup)?.let { wordsOf(it.secondaryWords) }

    /** 重叠分组中第二句主行的逐字数据（分组行的 secondary 即该句）。 */
    fun overlappingMainWords(): List<LyricWord>? =
        displayLine()?.takeIf(::isOverlappingGroup)?.let { wordsOf(it.secondaryWords) }

    /** 当前行是前奏/间奏指示器时返回其起止时间（歌词时间轴毫秒），否则 null。 */
    fun interludeRange(): LongRange? {
        val line = displayLine() ?: return null
        if (line.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) != true) return null
        return line.begin..line.end
    }

    /** 当前播放位置：与 AOD 位置轮询同源的估算位置。 */
    fun currentPositionMs(): Long =
        LyriconDataBridge.estimatedPosition() ?: LyriconDataBridge.currentPosition

    /**
     * [text] 中已唱的字符位置（含小数，当前字内按时间比例连续推进），范围 0..text.length。
     * 逐字文本与 [text] 只比较非空白字符：空白的数量与位置差异不影响对齐；
     * 非空白字符对不上时返回 null。
     */
    fun progressChars(text: String, words: List<LyricWord>, positionMs: Long): Float? {
        val expected = text.filterNot(Char::isWhitespace)
        val actual = words.joinToString("") { it.text.orEmpty() }.filterNot(Char::isWhitespace)
        if (expected != actual) return null
        var revealed = 0f
        for (word in words) {
            val length = word.text.orEmpty().count { !it.isWhitespace() }
            if (positionMs >= word.end) {
                revealed += length
            } else {
                if (positionMs > word.begin && word.end > word.begin) {
                    revealed += length * (positionMs - word.begin).toFloat() / (word.end - word.begin)
                }
                break
            }
        }
        if (revealed <= 0f) return 0f
        if (revealed >= expected.length) return text.length.toFloat()
        // 把「已唱非空白字符数」换算成 text 中的位置：落在第 k+1 个非空白字符内。
        val whole = revealed.toInt()
        val fraction = revealed - whole
        var seen = 0
        for (index in text.indices) {
            if (text[index].isWhitespace()) continue
            if (seen == whole) return index + fraction
            seen++
        }
        return text.length.toFloat()
    }

    private var lastMismatch: String? = null

    /** 仅 debug：逐字文本与展示文本对不上时记一条（按内容去重），用于定位真实歌曲的不一致原因。 */
    fun logMismatch(row: String, text: String, words: List<LyricWord>) {
        if (!diagnosticsEnabled) return
        val joined = words.joinToString("") { it.text.orEmpty() }
        val key = "$row|$text|$joined"
        if (key == lastMismatch) return
        lastMismatch = key
        val expected = text.filterNot(Char::isWhitespace)
        val actual = joined.filterNot(Char::isWhitespace)
        val diff = expected.zip(actual).indexOfFirst { (a, b) -> a != b }
            .let { if (it >= 0) it else minOf(expected.length, actual.length) }
        fun around(value: String) = value.substring(
            (diff - 8).coerceAtLeast(0), (diff + 8).coerceAtMost(value.length),
        )
        HookLogger.i(
            TAG,
            "逐字文本与展示文本对不上: row=$row, textLen=${text.length}, joinedLen=${joined.length}, " +
                "words=${words.size}, firstDiff=$diff, text=[${around(expected)}], joined=[${around(actual)}]",
        )
    }

    private var windowStartMs = 0L
    private var loopFrames = 0
    private var draws = 0

    /** 逐字进度的帧循环推进了一帧。 */
    fun recordLoopFrame() {
        if (!diagnosticsEnabled) return
        loopFrames++
        flushStatsIfDue()
    }

    /** 逐字主行实际执行了一次绘制，对照帧循环次数判断息屏是否真的出帧。 */
    fun recordDraw() {
        if (!diagnosticsEnabled || windowStartMs == 0L) return
        draws++
    }

    private fun flushStatsIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (windowStartMs == 0L) {
            windowStartMs = now
            return
        }
        val elapsed = now - windowStartMs
        if (elapsed < STATS_WINDOW_MS) return
        HookLogger.i(
            TAG,
            "息屏逐字进度帧统计: windowMs=$elapsed, loopFrames=$loopFrames, draws=$draws",
        )
        windowStartMs = now
        loopFrames = 0
        draws = 0
    }
}
