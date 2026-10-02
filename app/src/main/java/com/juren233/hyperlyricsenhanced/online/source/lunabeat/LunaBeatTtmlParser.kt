/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.lunabeat

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal data class LunaBeatParsedLyrics(
    val wordLines: List<LyricsLine>,
    val lrcLines: List<LrcLine>,
)

/** Parses the Apple-compatible TTML subset published by LunaBeat TTML Hub. */
internal object LunaBeatTtmlParser {
    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
    private const val MAX_LINES = 2_000
    private const val MAX_WORDS = 30_000

    fun parseWordTimed(bytes: ByteArray): LunaBeatParsedLyrics? {
        if (bytes.isEmpty()) return null
        val prefix = bytes.decodeToString(0, minOf(bytes.size, 4_096)).lowercase()
        if ("<!doctype" in prefix || "<!entity" in prefix) return null

        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { isXIncludeAware = false }
                runCatching { isExpandEntityReferences = false }
                setSecureFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setSecureFeature("http://xml.org/sax/features/external-general-entities", false)
                setSecureFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setSecureFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }.getOrNull() ?: return null

        val root = document.documentElement ?: return null
        val timing = root.getAttributeNS(ITUNES_NAMESPACE, "timing")
            .ifBlank { root.getAttribute("itunes:timing") }
        if (!timing.equals("Word", ignoreCase = true)) return null

        val translations = metadataTextByLineKey(document, "translation")
        val transliterations = metadataTextByLineKey(document, "transliteration")
        val paragraphs = document.getElementsByTagNameNS("*", "p")
        if (paragraphs.length == 0 || paragraphs.length > MAX_LINES) return null

        val wordLines = ArrayList<LyricsLine>(paragraphs.length)
        val lrcLines = ArrayList<LrcLine>(paragraphs.length)
        var totalWords = 0
        var hasRealWordTiming = false
        for (index in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(index) as? Element ?: continue
            if (!hasAncestor(paragraph, "body")) continue
            val begin = parseTimeMs(paragraph.getAttribute("begin")) ?: continue
            val end = parseTimeMs(paragraph.getAttribute("end"))
                ?.takeIf { it > begin }
                ?: continue
            val key = paragraph.getAttributeNS(ITUNES_NAMESPACE, "key")
                .ifBlank { paragraph.getAttribute("itunes:key") }
            val vocals = LunaBeatVocalParser.parse(paragraph, begin, end)
            // A standalone background-only paragraph still has visible content.
            val words = vocals.primary.ifEmpty { vocals.secondary }
            val secondaryWords = if (vocals.primary.isEmpty()) emptyList() else vocals.secondary
            totalWords += words.size + secondaryWords.size
            if (totalWords > MAX_WORDS) return null
            if (vocals.hasWordTiming) hasRealWordTiming = true
            if (words.isEmpty()) continue
            val text = words.joinToString("") { it.text }
                .ifBlank { paragraph.textContent.orEmpty().trim() }
            if (text.isBlank()) continue
            wordLines += LyricsLine(start = begin, end = end, words = words, secondaryWords = secondaryWords)
            lrcLines += LrcLine(
                startTimeMs = begin,
                content = text,
                translation = translations[key]?.takeIf(String::isNotBlank),
                romanization = transliterations[key]?.takeIf(String::isNotBlank),
            )
        }
        if (!hasRealWordTiming || wordLines.isEmpty()) return null
        return LunaBeatParsedLyrics(
            wordLines = wordLines.sortedBy(LyricsLine::start),
            lrcLines = lrcLines.sortedBy(LrcLine::startTimeMs),
        )
    }

    private fun metadataTextByLineKey(
        document: org.w3c.dom.Document,
        containerName: String,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val containers = document.getElementsByTagNameNS("*", containerName)
        for (containerIndex in 0 until containers.length) {
            val container = containers.item(containerIndex) as? Element ?: continue
            val textNodes = container.getElementsByTagNameNS("*", "text")
            for (index in 0 until textNodes.length) {
                val text = textNodes.item(index) as? Element ?: continue
                val lineKey = text.getAttribute("for").trim()
                val value = text.textContent.orEmpty().replace(Regex("\\s+"), " ").trim()
                if (lineKey.isNotEmpty() && value.isNotEmpty()) result[lineKey] = value
            }
        }
        return result
    }

    private fun hasAncestor(node: Node, localName: String): Boolean {
        var current = node.parentNode
        while (current != null) {
            if (current.localName == localName || current.nodeName == localName) return true
            current = current.parentNode
        }
        return false
    }

    internal fun parseTimeMs(raw: String?): Long? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val parts = value.split(':')
        val seconds = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> parts[0].toLongOrNull()?.times(60.0)
                ?.plus(parts[1].toDoubleOrNull() ?: return null)
            3 -> parts[0].toLongOrNull()?.times(3_600.0)
                ?.plus((parts[1].toLongOrNull() ?: return null) * 60.0)
                ?.plus(parts[2].toDoubleOrNull() ?: return null)
            else -> null
        } ?: return null
        if (!seconds.isFinite() || seconds < 0.0) return null
        return (seconds * 1_000.0).toLong()
    }

    private fun DocumentBuilderFactory.setSecureFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }
}
