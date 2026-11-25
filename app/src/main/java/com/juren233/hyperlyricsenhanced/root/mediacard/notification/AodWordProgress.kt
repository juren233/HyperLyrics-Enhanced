/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.LyricWordsMetadataCodec
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.extensions.deepCopy
import com.juren233.hyperlyricsenhanced.lyric.model.extensions.normalize
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.RelativeWordBuilder
import com.juren233.hyperlyricsenhanced.lyric.view.isTitleLine
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.IdentityHashMap

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
    private const val NORMALIZED_CACHE_SIZE = 8
    private const val MIN_SWEEP_MS = 120L
    private const val PROGRESS_SAMPLE_INTERVAL_MS = 1_000L
    private const val MAX_LOGGED_WORDS = 24

    /** 帧统计与不一致日志只在 debug 构建采集；效果本身由「动态效果」开关控制。 */
    private val diagnosticsEnabled: Boolean get() = BuildConfig.DEBUG

    fun wordsOf(words: List<LyricWord>?): List<LyricWord>? =
        words?.takeIf { list -> list.any { effectiveEnd(it) > it.begin } }?.let(::normalized)

    /** 与超级岛逐字渲染同一口径的词尾：end 无效时用 duration 兜底。 */
    internal fun effectiveEnd(word: LyricWord): Long = when {
        word.end > word.begin -> word.end
        word.duration > 0L -> word.begin + word.duration
        else -> word.end
    }

    private val normalizedCache = IdentityHashMap<List<LyricWord>, List<LyricWord>>()

    /**
     * 时长短得没法看出扫字过程的词（数据里常见 1ms、10ms），如果和下一个词之间还有空档，
     * 就让它一直扫到下一个词开始，避免一个词瞬间从头亮到尾。只改规整后的副本。
     */
    internal fun stretchInstantWords(words: List<LyricWord>) {
        for (index in 0 until words.lastIndex) {
            val word = words[index]
            val nextBegin = words[index + 1].begin
            if (word.end - word.begin < MIN_SWEEP_MS && nextBegin > word.end) {
                word.end = nextBegin
                word.duration = nextBegin - word.begin
            }
        }
    }

    /**
     * 超级岛逐字渲染前会把每行逐字 [normalize]（按时间排序、无效时间的词并入相邻词、
     * 拆开的英文片段合并成一个词），息屏过去直接用原始列表。这里换成同一份规范化结果，
     * 同一原始列表复用同一个实例，避免逐字视图把它当作新内容重绘。
     */
    internal fun normalized(words: List<LyricWord>): List<LyricWord> {
        normalizedCache[words]?.let { return it }
        if (normalizedCache.size >= NORMALIZED_CACHE_SIZE) normalizedCache.clear()
        val result = words.deepCopy().normalize().takeIf { it.isNotEmpty() }
            ?.also(::stretchInstantWords) ?: words
        normalizedCache[words] = result
        return result
    }

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

    /**
     * 伴唱行的逐字数据：非重叠分组时伴唱就是当前行的 secondary；
     * 重叠分组时取第一句自己的伴唱逐字（分组时编码在元数据里）。
     */
    fun backingWords(): List<LyricWord>? {
        val line = displayLine() ?: return null
        if (!isOverlappingGroup(line)) return wordsOf(line.secondaryWords)
        return wordsOf(metadataWords(line, LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING_WORDS))
    }

    /** 重叠分组中第二句的伴唱逐字数据。 */
    fun overlappingBackingWords(): List<LyricWord>? =
        displayLine()?.takeIf(::isOverlappingGroup)?.let {
            wordsOf(metadataWords(it, LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING_WORDS))
        }

    private val decodedWordsCache = HashMap<String, List<LyricWord>>()

    /** 解码结果按原字符串缓存，保证每帧拿到同一个列表实例，规整缓存才能命中。 */
    private fun metadataWords(line: IRichLyricLine, key: String): List<LyricWord>? {
        val encoded = line.metadata?.getString(key)?.takeIf { it.isNotBlank() } ?: return null
        decodedWordsCache[encoded]?.let { return it }
        val decoded = LyricWordsMetadataCodec.decode(encoded) ?: return null
        if (decodedWordsCache.size >= NORMALIZED_CACHE_SIZE) decodedWordsCache.clear()
        decodedWordsCache[encoded] = decoded
        return decoded
    }

    /** 重叠分组中第二句主行的逐字数据（分组行的 secondary 即该句）。 */
    fun overlappingMainWords(): List<LyricWord>? =
        displayLine()?.takeIf(::isOverlappingGroup)?.let { wordsOf(it.secondaryWords) }

    /** 当前行是前奏/间奏指示器时返回其起止时间（歌词时间轴毫秒），否则 null。 */
    fun interludeRange(): LongRange? {
        val line = displayLine() ?: return null
        if (line.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) != true) return null
        return line.begin..line.end
    }

    /** 当前显示行预计被换掉的时刻（下一句开始、提前预览或间奏接手），未知为 null。 */
    fun displayLeaveMs(): Long? = LyriconDataBridge.displayLeaveMs()

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
            val end = effectiveEnd(word)
            if (positionMs >= end) {
                revealed += length
            } else {
                if (positionMs > word.begin && end > word.begin) {
                    revealed += length * (positionMs - word.begin).toFloat() / (end - word.begin)
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

    private var lastLoggedWords: List<LyricWord>? = null

    /** 仅 debug：某一行绑定了新的逐字列表时记下每个词的时间（排查息屏与岛上进度不一致）。 */
    fun logBoundWords(row: String, text: String, words: List<LyricWord>?) {
        if (!diagnosticsEnabled || words == null || words === lastLoggedWords) return
        lastLoggedWords = words
        val timings = words.take(MAX_LOGGED_WORDS).joinToString(" ") { word ->
            "${word.begin}-${effectiveEnd(word)}:${word.text.orEmpty().replace(' ', '_')}"
        }
        val more = if (words.size > MAX_LOGGED_WORDS) " …+${words.size - MAX_LOGGED_WORDS}" else ""
        HookLogger.i(
            TAG,
            "息屏逐字绑定: row=$row, textLen=${text.length}, words=${words.size}, " +
                "line=${displayLine()?.let { "${it.begin}-${it.end}" }}, timings=$timings$more",
        )
    }

    private val lastSampleMs = HashMap<String, Long>()

    /** 仅 debug：每行每秒记一次绘制时的位置与已唱字符，直接看进度是连续还是卡住/跳。 */
    fun logProgressSample(row: String, positionMs: Long, chars: Float, length: Int) {
        if (!diagnosticsEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastSampleMs[row] ?: 0L) < PROGRESS_SAMPLE_INTERVAL_MS) return
        lastSampleMs[row] = now
        HookLogger.i(
            TAG,
            "息屏逐字进度采样: row=$row, position=$positionMs, chars=${"%.2f".format(chars)}/$length, " +
                "bridgePosition=${LyriconDataBridge.currentPosition}",
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
