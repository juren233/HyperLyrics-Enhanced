package com.juren233.hyperlyricsenhanced.common.lyric

/**
 * Normalizes translation text returned by third-party online lyric sources.
 *
 * QQ Music and NetEase can use slash-only text such as `//` or `// //` as a
 * missing-translation placeholder. It must never be treated as real content.
 */
internal object OnlineTranslationContentPolicy {
    fun sanitize(text: String?): String? {
        val normalized = text
            ?.trim { Character.isWhitespace(it) || Character.isSpaceChar(it) }
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val compact = normalized.filterNot {
            Character.isWhitespace(it) || Character.isSpaceChar(it)
        }
        return normalized.takeUnless {
            compact.isNotEmpty() && compact.all { character -> character == '/' }
        }
    }

    fun isMeaningful(text: String?): Boolean = sanitize(text) != null
}
