/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.online.source.ttml

import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Keeps x-bg descendants in their own timed stream instead of flattening a paragraph. */
internal object TtmlVocalParser {
    private const val TTM_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"
    private const val ROLE_TRANSLATION = "x-translation"
    private const val ROLE_ROMAN = "x-roman"

    data class Vocals(
        val primary: List<LyricsWord>,
        val secondary: List<LyricsWord>,
        val hasWordTiming: Boolean,
        /** Inline x-translation text carried by the paragraph itself (AMLL TTML DB style). */
        val translation: String? = null,
        /** Inline x-roman text carried by the paragraph itself (AMLL TTML DB style). */
        val romanization: String? = null,
    )

    private data class Timing(val begin: Long, val end: Long)

    fun parse(paragraph: Element, lineBegin: Long, lineEnd: Long): Vocals {
        val primary = mutableListOf<LyricsWord>()
        val secondary = mutableListOf<LyricsWord>()
        var timedWords = 0
        val translations = mutableListOf<Pair<Boolean, String>>()
        val romanizations = mutableListOf<Pair<Boolean, String>>()

        fun append(text: String, background: Boolean, timing: Timing?) {
            if (text.isEmpty()) return
            val target = if (background) secondary else primary
            if (text.isBlank()) {
                // Literal inline separators belong to their DOM voice, even around an x-bg
                // container. Pretty-print indentation is not a lyric separator.
                if ('\n' !in text && '\r' !in text && target.isNotEmpty()) {
                    val last = target.lastIndex
                    target[last] = target[last].copy(text = target[last].text + text)
                }
                return
            }
            val begin = (timing?.begin ?: lineBegin).coerceAtLeast(lineBegin)
            val end = (timing?.end ?: lineEnd).coerceIn(begin + 1, lineEnd.coerceAtLeast(begin + 1))
            target += LyricsWord(begin, end, text)
            if (timing != null) timedWords++
        }

        fun visit(node: Node, inheritedBackground: Boolean, inheritedTiming: Timing?) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                    append(node.nodeValue.orEmpty(), inheritedBackground, inheritedTiming)
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    val background = inheritedBackground || isBackground(element)
                    // Inline annotations are not sung words: keep them out of the word streams.
                    when (roleOf(element)) {
                        ROLE_TRANSLATION -> {
                            collectAnnotation(translations, background, element)
                            return
                        }
                        ROLE_ROMAN -> {
                            collectAnnotation(romanizations, background, element)
                            return
                        }
                    }
                    val ownTiming = timing(element)
                    val currentTiming = ownTiming ?: inheritedTiming
                    if (ownTiming != null && !mustVisitChildren(element, background)) {
                        // Untimed inline styling is part of this word. A timed container with
                        // timed children, or with another voice inside, must be traversed instead.
                        append(element.textContent.orEmpty(), background, ownTiming)
                    } else {
                        var child = node.firstChild
                        while (child != null) {
                            visit(child, background, currentTiming)
                            child = child.nextSibling
                        }
                    }
                }
            }
        }

        // The paragraph's bounds are fallback timing, not evidence of per-word timing.
        var child = paragraph.firstChild
        while (child != null) {
            visit(child, isBackground(paragraph), null)
            child = child.nextSibling
        }
        fun List<LyricsWord>.ordered() = sortedBy(LyricsWord::start)
            .distinctBy { Triple(it.start, it.end, it.text) }
        return Vocals(
            primary = primary.ordered(),
            secondary = secondary.ordered(),
            hasWordTiming = timedWords >= 2,
            translation = preferMainVoice(translations),
            romanization = preferMainVoice(romanizations),
        )
    }

    private fun collectAnnotation(
        target: MutableList<Pair<Boolean, String>>,
        background: Boolean,
        element: Element,
    ) {
        val text = element.textContent.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (text.isNotEmpty()) target += background to text
    }

    /** The main voice's annotation wins; a background-only annotation is the fallback. */
    private fun preferMainVoice(entries: List<Pair<Boolean, String>>): String? =
        entries.firstOrNull { !it.first }?.second ?: entries.firstOrNull()?.second

    private fun roleOf(element: Element): String =
        element.getAttributeNS(TTM_NAMESPACE, "role").ifBlank { element.getAttribute("ttm:role") }

    private fun isBackground(element: Element): Boolean = roleOf(element) == "x-bg"

    private fun timing(element: Element): Timing? {
        if (element.localName != "span" && element.nodeName != "span") return null
        val begin = TtmlLyricsParser.parseTimeMs(element.getAttribute("begin")) ?: return null
        val end = TtmlLyricsParser.parseTimeMs(element.getAttribute("end")) ?: return null
        return if (end > begin) Timing(begin, end) else null
    }

    private fun mustVisitChildren(element: Element, background: Boolean): Boolean {
        val spans = element.getElementsByTagNameNS("*", "span")
        for (index in 0 until spans.length) {
            val descendant = spans.item(index) as? Element ?: continue
            if (timing(descendant) != null || (!background && isBackground(descendant))) return true
        }
        return false
    }
}
