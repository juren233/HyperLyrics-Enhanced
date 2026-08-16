/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

/**
 * 把三方在线源歌词编译成 Apple Music 原生 TTML 字符串，并调用 Apple 自己的
 * TTMLParserNative 生成原生 SongInfo 模型，从而把补充歌词塞进 Apple Music
 * 歌词页的原生显示链路（buildTimeRangeToLyricsMap -> R2 -> 原生适配器）。
 *
 * 有真实逐字时间轴时生成 Apple Word timing/span 结构；只有整行时间轴时
 * 生成 Line timing 并把正文直接放在 `<p>`。不能把整句包成单个 Word span：
 * Apple 会使用 `lyrics_karaoke_non_breaking_span` 渲染它，长句将无法换行。
 */
internal object AppleMissingLyricsTtml {
    private const val TTML_NAMESPACE = "http://www.w3.org/ns/ttml"
    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
    private const val TTM_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"

    fun build(lines: List<AppleMissingLyricsLine>, durationMs: Long): String {
        // 来源的 line-only 歌词可能带一个覆盖整句的伪 word。只有至少
        // 一行拥有两个以上的真实分词时才启用 Word timing。
        val usesWordTiming = lines.any { it.words.size >= 2 }
        val timing = if (usesWordTiming) "Word" else "Line"
        val body = StringBuilder()
        body.append(
            "<tt xmlns=\"$TTML_NAMESPACE\" xmlns:itunes=\"$ITUNES_NAMESPACE\" " +
                "xmlns:ttm=\"$TTM_NAMESPACE\" " +
                "itunes:timing=\"$timing\" xml:lang=\"zh-Hans\" " +
                "xml:space=\"preserve\">"
        )
        body.append(
            "<head><metadata><ttm:agent type=\"person\" xml:id=\"v1\"/>" +
                "<iTunesMetadata xmlns=\"$ITUNES_NAMESPACE\"/>" +
                "</metadata></head>"
        )
        body.append("<body dur=\"${duration(durationMs)}\">")
        val firstBegin = lines.firstOrNull()?.begin ?: 0L
        val lastEnd = lines.lastOrNull()?.end ?: durationMs
        body.append(
            "<div begin=\"${seconds(firstBegin)}\" end=\"${seconds(lastEnd)}\">"
        )
        for ((lineIndex, line) in lines.withIndex()) {
            val lineEnd = line.end.coerceAtLeast(line.begin + 1)
            body.append(
                "<p begin=\"${seconds(line.begin)}\" end=\"${seconds(lineEnd)}\" " +
                    "ttm:agent=\"v1\" itunes:key=\"L${lineIndex + 1}\">"
            )
            if (usesWordTiming && line.words.isNotEmpty()) {
                for (word in line.words) {
                    val wordEnd = word.end.coerceAtLeast(word.begin + 1)
                    body.append(
                        "<span begin=\"${seconds(word.begin)}\" " +
                            "end=\"${seconds(wordEnd)}\">"
                    )
                    body.append(escape(word.text))
                    body.append("</span>")
                }
            } else if (usesWordTiming) {
                // 混合时间轴中的单个 line-only 句子仍需要有可显示的 span。
                body.append(
                    "<span begin=\"${seconds(line.begin)}\" " +
                        "end=\"${seconds(lineEnd)}\">"
                )
                body.append(escape(line.text))
                body.append("</span>")
            } else {
                // Line timing 由 Apple 的整行 TextView 自然换行，不创建 non-breaking span。
                body.append(escape(line.text))
            }
            body.append("</p>")
        }
        body.append("</div>")
        body.append("</body>")
        body.append("</tt>")
        return body.toString()
    }

    /** Apple 真实 TTML 使用「s.mmm」十进制秒时间戳。 */
    fun seconds(milliseconds: Long): String {
        val total = milliseconds.coerceAtLeast(0L)
        val seconds = total / 1_000L
        val millis = total % 1_000L
        return "%d.%03d".format(seconds, millis)
    }

    /** body dur 使用「m:ss.mmm」格式（与 Apple 真实返回一致）。 */
    fun duration(milliseconds: Long): String {
        val total = milliseconds.coerceAtLeast(0L)
        val minutes = total / 60_000L
        val seconds = (total % 60_000L) / 1_000L
        val millis = total % 1_000L
        return "%d:%02d.%03d".format(minutes, seconds, millis)
    }

    private fun escape(text: String): String = buildString(text.length + 16) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(char)
            }
        }
    }
}

/**
 * 通过 Apple 自身的 TTML 原生解析器把 TTML 字符串解析成原生歌词模型指针。
 */
internal class AppleMissingLyricsNativeParser(
    private val runtime: AppleMusicProviderRuntime,
) {
    private val resolvedParser by lazy {
        runtime.hookResolver.resolveMethod(AppleMusicHookPoint.LYRICS_TTML_PARSER)
    }

    /** @return SongInfo$SongInfoPtr，解析失败时返回 null。 */
    fun parse(ttml: String): Any? {
        val resolved = runCatching { resolvedParser }.getOrNull() ?: return null
        return runCatching {
            val parser = resolved.method.declaringClass.getConstructor().newInstance()
            resolved.method.invoke(parser, ttml)
        }.onFailure {
            ProviderLogger.error("Apple Music TTML 原生解析失败", it)
        }.getOrNull()
    }
}
