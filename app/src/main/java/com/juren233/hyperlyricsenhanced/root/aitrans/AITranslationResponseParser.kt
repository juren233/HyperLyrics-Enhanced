package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.common.extensions.json

internal object AITranslationResponseParser {
    private const val TAG = "AITranslationResponseParser"
    private const val MAX_LOG_BODY_LENGTH = 1000

    fun parse(content: String, requestIndices: Set<Int>): List<TranslationItem> {
        val jsonPayload = extractJsonFromLlmContent(content) ?: return emptyList()
        val items = decodeTranslationItems(jsonPayload)
        val validItems = normalizeTranslationItems(items, requestIndices)
        HookLogger.d(TAG, "解析翻译响应完成: parsed=${items.size}, accepted=${validItems.size}")
        return validItems
    }

    private fun extractJsonFromLlmContent(raw: String): String? {
        val regex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val trimmed = regex.find(raw)?.groupValues?.get(1)?.trim() ?: raw.trim()
        if (trimmed.isEmpty()) return null

        val objectStart = trimmed.indexOf('{')
        if (objectStart >= 0) {
            val objectEnd = findMatchingBrace(trimmed, objectStart, '{', '}')
            if (objectEnd > objectStart) {
                return trimmed.substring(objectStart, objectEnd + 1)
            }
        }

        val arrayStart = trimmed.indexOf('[')
        if (arrayStart >= 0) {
            val arrayEnd = findMatchingBrace(trimmed, arrayStart, '[', ']')
            if (arrayEnd > arrayStart) {
                return trimmed.substring(arrayStart, arrayEnd + 1)
            }
        }

        HookLogger.w(
            "AITranslationResponseParser",
            "翻译响应缺少 JSON: body=${trimForLog(trimmed)}"
        )
        return null
    }

    private fun findMatchingBrace(text: String, startIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        for (i in startIndex until text.length) {
            when (text[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun decodeTranslationItems(content: String): List<TranslationItem> {
        runCatching {
            return json.decodeFromString<TranslationResponse>(content).translations
        }
        return json.decodeFromString<List<TranslationItem>>(content)
    }

    private fun normalizeTranslationItems(
        items: List<TranslationItem>,
        requestIndices: Set<Int>
    ): List<TranslationItem> {
        val accepted = LinkedHashMap<Int, TranslationItem>()
        items.forEach { item ->
            val trans = item.trans.trim()
            if (item.index in requestIndices && trans.isNotBlank() && item.index !in accepted) {
                accepted[item.index] = item.copy(trans = trans)
            }
        }
        return accepted.values.toList()
    }

    private fun trimForLog(value: String): String =
        if (value.length <= MAX_LOG_BODY_LENGTH) value else value.take(MAX_LOG_BODY_LENGTH) + "..."
}

