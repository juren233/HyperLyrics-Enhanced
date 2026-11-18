/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.ttml

import java.util.Locale

/** One translation candidate of a TTML line, in document order. */
internal data class TtmlTranslation(
    val text: String,
    val languageTag: String?,
    /** Annotation of a background (x-bg) voice rather than the main line. */
    val background: Boolean = false,
)

/**
 * Picks one translation language for a whole TTML document that carries several
 * (Issue #77: AMLL lines with ten `x-translation` languages).
 *
 * Languages are tried in order; the first one present anywhere in the document wins
 * (user decision 2026-11-18):
 * 1. the system language;
 * 2. for a non-Chinese system, English, then Chinese (simplified preferred);
 * 3. none of them present: each line keeps its first translation.
 *
 * Once a language is chosen, every line uses its best translation in that language and a
 * line without one gets none — the document evidently leaves lines that are already in
 * that language untranslated.
 */
internal object TtmlTranslationLanguage {
    private const val NO_MATCH = 0
    private const val SAME_LANGUAGE = 1
    private const val SAME_LANGUAGE_AND_SCRIPT = 2

    data class Preference(val language: String, val script: String)

    class Plan internal constructor(
        /** Null: no preferred language is in the document, keep each line's first translation. */
        val preference: Preference?,
    ) {
        fun select(candidates: List<TtmlTranslation>): String? {
            if (candidates.isEmpty()) return null
            val preference = preference
                ?: return (candidates.firstOrNull { !it.background } ?: candidates.first()).text
            return candidates
                .withIndex()
                .map { (index, candidate) -> Triple(rank(candidate.languageTag, preference), index, candidate) }
                .filter { it.first > NO_MATCH }
                .maxWithOrNull(
                    compareBy<Triple<Int, Int, TtmlTranslation>>(
                        { it.first },
                        { !it.third.background },
                        { -it.second },
                    )
                )
                ?.third
                ?.text
        }
    }

    private val ENGLISH = Preference(language = "en", script = "")
    private val SIMPLIFIED_CHINESE = Preference(language = "zh", script = "hans")

    fun plan(systemLocale: Locale, documentLanguageTags: List<String?>): Plan {
        val tags = documentLanguageTags.filterNotNull().distinct()
        val chosen = preferenceChain(systemLocale).firstOrNull { preference ->
            tags.any { rank(it, preference) > NO_MATCH }
        }
        return Plan(chosen)
    }

    internal fun preferenceChain(systemLocale: Locale): List<Preference> {
        val system = preferenceOf(systemLocale)
        if (system.language == SIMPLIFIED_CHINESE.language) return listOf(system)
        return listOf(system, ENGLISH, SIMPLIFIED_CHINESE).distinctBy(Preference::language)
    }

    fun preferenceOf(locale: Locale): Preference = Preference(
        language = locale.language.lowercase(Locale.ROOT),
        script = scriptOf(locale),
    )

    internal fun rank(languageTag: String?, preference: Preference): Int {
        val tag = languageTag?.trim()?.replace('_', '-')?.takeIf(String::isNotEmpty) ?: return NO_MATCH
        val locale = Locale.forLanguageTag(tag)
        val language = locale.language.lowercase(Locale.ROOT)
        if (language.isEmpty() || language != preference.language) return NO_MATCH
        return if (scriptOf(locale) == preference.script) SAME_LANGUAGE_AND_SCRIPT else SAME_LANGUAGE
    }

    /** Chinese tags often omit the script: zh-CN/zh-SG mean Hans, zh-TW/zh-HK/zh-MO mean Hant. */
    private fun scriptOf(locale: Locale): String {
        val script = locale.script.lowercase(Locale.ROOT)
        if (script.isNotEmpty() || locale.language != "zh") return script
        return when (locale.country.uppercase(Locale.ROOT)) {
            "TW", "HK", "MO" -> "hant"
            else -> "hans"
        }
    }
}
