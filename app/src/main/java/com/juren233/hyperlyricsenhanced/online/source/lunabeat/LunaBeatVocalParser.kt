/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.online.source.lunabeat

import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Keeps x-bg descendants in their own timed stream instead of flattening a paragraph. */
internal object LunaBeatVocalParser {
    private const val TTM_NAMESPACE = "http://www.w3.org/ns/ttml#metadata"

    data class Vocals(
        val primary: List<LyricsWord>,
        val secondary: List<LyricsWord>,
        val hasWordTiming: Boolean,
    )

    private data class Timing(val begin: Long, val end: Long)

    fun parse(paragraph: Element, lineBegin: Long, lineEnd: Long): Vocals {
        val primary = mutableListOf<LyricsWord>()
        val secondary = mutableListOf<LyricsWord>()
        var timedWords = 0

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
        return Vocals(primary.ordered(), secondary.ordered(), timedWords >= 2)
    }

    private fun isBackground(element: Element): Boolean =
        element.getAttributeNS(TTM_NAMESPACE, "role")
            .ifBlank { element.getAttribute("ttm:role") } == "x-bg"

    private fun timing(element: Element): Timing? {
        if (element.localName != "span" && element.nodeName != "span") return null
        val begin = LunaBeatTtmlParser.parseTimeMs(element.getAttribute("begin")) ?: return null
        val end = LunaBeatTtmlParser.parseTimeMs(element.getAttribute("end")) ?: return null
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
