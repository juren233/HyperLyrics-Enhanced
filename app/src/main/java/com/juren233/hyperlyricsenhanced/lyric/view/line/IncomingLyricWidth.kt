/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Paint
import android.graphics.Typeface
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.LyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.measureLyricTextWidth
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil

/** Snapshot only text metrics; neither a View nor a native island width crosses threads. */
internal class IncomingLyricWidth {
    private class Metrics(paint: Paint, val base: Typeface, val narrow: Typeface?) {
        private val paint = Paint(paint)

        fun matches(other: Paint, base: Typeface, narrow: Typeface?): Boolean =
            this.base == base && this.narrow == narrow && paint.equalsForTextMeasurement(other) &&
                paint.startHyphenEdit == other.startHyphenEdit && paint.endHyphenEdit == other.endHyphenEdit

        fun measure(text: String): Float = measureLyricTextWidth(
            Paint(paint), text, MixedTypefaceText.typefaceSelector(base, narrow),
        )
    }

    private val cache = PreparedWidthCache<String>(worker, recordOrigins = BuildConfig.DEBUG)
    private var metrics: Metrics? = null
    private var preparedLyrics: List<RichLyricLine>? = null
    private var preparationGate: WidthPreparationGate? = null
    private var preparedConsumersLogged = 0
    private var fallbackConsumersLogged = 0

    private fun configure(paint: Paint, base: Typeface, narrow: Typeface?): Metrics {
        metrics?.takeIf { it.matches(paint, base, narrow) }?.let { return it }
        cache.clear()
        preparedLyrics = null
        return Metrics(paint, base, narrow).also { metrics = it }
    }

    fun prepare(lyrics: List<RichLyricLine>?, gate: WidthPreparationGate, paint: Paint, base: Typeface, narrow: Typeface?) {
        if (lyrics == null) { clear(); return }
        val snapshot = configure(paint, base, narrow)
        if (preparedLyrics === lyrics && preparationGate === gate) return
        preparedLyrics = lyrics
        preparationGate = gate
        // Every displayed row uses the same text-key cache, so translation/display-mode
        // changes cannot reuse another row's width. New or late text remains a safe miss.
        val texts = lyrics.flatMap { line ->
            listOfNotNull(line.text, line.secondary, line.translation, line.roma,
                line.words?.takeIf { it.isNotEmpty() }?.joinToString("") { it.text.orEmpty() },
                line.secondaryWords?.takeIf { it.isNotEmpty() }?.joinToString("") { it.text.orEmpty() },
                line.translationWords?.takeIf { it.isNotEmpty() }?.joinToString("") { it.text.orEmpty() })
        }.filter { it.isNotBlank() }
        var firstBackgroundMeasurement = true
        cache.prepare(texts, gate) { text ->
            snapshot.measure(text).also {
                if (BuildConfig.DEBUG && firstBackgroundMeasurement) {
                    firstBackgroundMeasurement = false
                    HookLogger.i("IslandWidthPreparation", "后台自然宽度准备已执行: rows=${texts.size}")
                }
            }
        }
    }

    fun measure(line: LyricLine?, paint: Paint, base: Typeface, narrow: Typeface?, indicatorWidth: Float): Int {
        if (line?.text.isNullOrBlank()) return 0
        val snapshot = configure(paint, base, narrow)
        val width = if (line?.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) == true) {
            indicatorWidth
        } else {
            val text = line!!.normalize().text.orEmpty()
            val cached = cachedWidth(text, consumer = 1)
            cached ?: snapshot.measure(text).also { cache.put(text, it) }
        }
        // Shadows are a live addition to natural text width, never part of a stale cache key.
        val shadow = if (paint.shadowLayerRadius > 0f) ceil(paint.shadowLayerRadius + abs(paint.shadowLayerDx)).toInt() else 0
        return ceil(width).toInt() + shadow
    }

    /** Live binding consumes prewarmed width too; other views retain their original measurement. */
    fun preparedTextWidth(text: String, paint: Paint, base: Typeface, narrow: Typeface?): Float? {
        if (preparedLyrics == null) return null
        configure(paint, base, narrow)
        return cachedWidth(text, consumer = 2)
    }

    private fun cachedWidth(text: String, consumer: Int): Float? {
        if (BuildConfig.DEBUG && preparedConsumersLogged and consumer == 0) {
            val lookup = cache.inspect(text)
            val source = if (consumer == 2) "bind" else "predict"
            if (lookup.width != null && lookup.backgroundPrepared) {
                preparedConsumersLogged = preparedConsumersLogged or consumer
                HookLogger.i("IslandWidthPreparation", "后台宽度首次实际消费: consumer=$source cache=${System.identityHashCode(this)} chars=${text.length} width=${lookup.width}")
            } else if (fallbackConsumersLogged and consumer == 0 && text.isNotBlank()) {
                fallbackConsumersLogged = fallbackConsumersLogged or consumer
                HookLogger.i("IslandWidthPreparation", "后台宽度首次未就绪: consumer=$source cache=${System.identityHashCode(this)} cached=${lookup.width != null}")
            }
            return lookup.width
        }
        return cache.get(text)
    }

    fun clear() {
        cache.clear()
        preparedLyrics = null
        preparationGate = null
        metrics = null
    }

    private companion object {
        val worker = ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(16), { task ->
            Thread(task, "HLE-lyric-width").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        })
    }
}
