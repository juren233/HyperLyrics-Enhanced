/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun canonicalOriginalLanguage(language: String): String {
    val normalized = language.trim()
    return when (normalized.lowercase()) {
        "zh-hans-cn", "zh-hant-hk", "zh-hant-tw", "zh-hk", "zh-mo", "zh-tw", "zh-cn" ->
            "zh-Hans-CN"
        else -> normalized
    }
}

internal fun supportedOriginalLanguageOrNull(language: String): String? =
    canonicalOriginalLanguage(language).takeIf { canonical ->
        storefrontForOriginalLanguage(canonical) != null
    }

internal fun storefrontForOriginalLanguage(language: String): String? = when (
    canonicalOriginalLanguage(language)
) {
    "ja-JP" -> "jp"
    "ko-KR" -> "kr"
    "zh-Hans-CN" -> "cn"
    "th-TH" -> "th"
    "ru-RU" -> "ru"
    "uk-UA" -> "ua"
    "ar-SA" -> "sa"
    "he-IL" -> "il"
    "hi-IN" -> "in"
    "el-GR" -> "gr"
    "bg-BG" -> "bg"
    else -> null
}

internal fun isLegacyTraditionalChineseLanguage(language: String): Boolean =
    language.trim().lowercase() in setOf(
        "zh-hant-hk",
        "zh-hant-tw",
        "zh-hk",
        "zh-mo",
        "zh-tw",
    )

internal fun canonicalCachedOriginalAlias(alias: Alias): Alias? {
    if (isLegacyTraditionalChineseLanguage(alias.language)) return null
    val canonicalLanguage = supportedOriginalLanguageOrNull(alias.language) ?: return null
    if (!isAcceptableOriginalAlias(alias, canonicalLanguage)) return null
    return if (canonicalLanguage == alias.language) alias
    else alias.copy(language = canonicalLanguage)
}

internal fun regionalOriginalAliases(
    aliases: Collection<Alias>,
    languages: Collection<String>,
): List<Alias> {
    val canonicalLanguages = languages.mapNotNull(::supportedOriginalLanguageOrNull).toSet()
    if (canonicalLanguages.isEmpty()) return emptyList()
    return aliases.filter { alias ->
        val aliasLanguage = supportedOriginalLanguageOrNull(alias.language)
        aliasLanguage in canonicalLanguages &&
            isAcceptableOriginalAlias(alias, requireNotNull(aliasLanguage))
    }
}

internal fun isAcceptableOriginalAlias(alias: Alias, sourceLanguage: String): Boolean {
    if (alias.title.isBlank() && alias.artist.isBlank()) return false
    if (canonicalOriginalLanguage(sourceLanguage) != "zh-Hans-CN") return true
    return containsHanCharacters(alias.title) || containsHanCharacters(alias.artist)
}

internal fun selectExactOriginalEntityAlias(
    mediaId: String,
    lookupIds: Collection<String>,
    resolved: Map<String, Alias>,
    sourceLanguage: String,
): Alias? {
    resolved[mediaId.trim()]?.takeIf { alias ->
        alias.title.isNotBlank() || alias.artist.isNotBlank()
    }?.let { return it }
    return lookupIds.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filterNot { it == mediaId.trim() }
        .distinct()
        .firstNotNullOfOrNull { id ->
        resolved[id]?.takeIf { isAcceptableOriginalAlias(it, sourceLanguage) }
    }
}

internal fun selectExactIdentityAlias(
    aliases: Collection<Alias>,
    sourceLanguage: String,
): Alias? {
    val canonicalLanguage = canonicalOriginalLanguage(sourceLanguage)
    return aliases.firstOrNull { alias ->
        canonicalOriginalLanguage(alias.language) == canonicalLanguage &&
            (alias.title.isNotBlank() || alias.artist.isNotBlank())
    }
}

internal fun containsHanCharacters(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
            return true
        }
        index += Character.charCount(codePoint)
    }
    return false
}

internal fun storefrontForContentUiLanguage(selection: Int): String? = when (selection) {
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN -> "cn"
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US -> "us"
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK -> "hk"
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW -> "tw"
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR -> "kr"
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP -> "jp"
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE -> null
    else -> null
}

internal fun languageTagsForContentUiLanguage(selection: Int): List<String> = when (selection) {
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN -> listOf("zh-CN")
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US ->
        listOf("zh-Hans", "zh-CN")
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK -> listOf("zh-HK")
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW -> listOf("zh-TW")
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR -> listOf("ko-KR")
    RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP -> listOf("ja-JP")
    else -> emptyList()
}

internal fun languageTagForContentUiLanguage(selection: Int): String? =
    languageTagsForContentUiLanguage(selection).firstOrNull()

internal fun localizedStorefrontHeaderValue(
    storefront: String,
    currentValue: String?,
): String? {
    val storefrontId = when (storefront.trim().lowercase()) {
        "us" -> "143441"
        "gr" -> "143448"
        "jp" -> "143462"
        "hk" -> "143463"
        "cn" -> "143465"
        "kr" -> "143466"
        "in" -> "143467"
        "ru" -> "143469"
        "tw" -> "143470"
        "th" -> "143475"
        "sa" -> "143479"
        "il" -> "143491"
        "ua" -> "143492"
        "bg" -> "143526"
        else -> null
    } ?: return null
    val suffix = currentValue.orEmpty().dropWhile(Char::isDigit)
    return storefrontId + suffix
}

internal fun normalizedArtistNameKey(value: String): String = value
    .trim()
    .lowercase()
    .replace(Regex("\\s+"), " ")

internal fun artistCacheKey(
    selection: Int,
    key: String,
    language: String? = null,
): String {
    val normalizedLanguage = language
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.replace('_', '-')
        ?.lowercase()
    return if (normalizedLanguage == null) {
        "$selection:ARTIST_ALIAS:$ARTIST_ALIAS_CACHE_SCHEMA:${key.trim()}"
    } else {
        "$selection:ARTIST_ALIAS:$ARTIST_ALIAS_CACHE_SCHEMA:" +
            "$normalizedLanguage:${key.trim()}"
    }
}

internal fun storefrontFromContentPath(pathSegments: List<String>): String? = when {
    pathSegments.size >= 3 && pathSegments[0] == "v1" &&
        pathSegments[1] in setOf("catalog", "editorial", "recommendations") ->
        pathSegments[2].takeIf { it.length == 2 }
    else -> null
}

internal fun isAccountScopedPlaybackPath(pathSegments: List<String>): Boolean =
    pathSegments.any { segment ->
        segment.equals("radio", ignoreCase = true) ||
            segment.equals("station", ignoreCase = true) ||
            segment.equals("stations", ignoreCase = true)
    }

internal fun selectLocalizedArtistName(
    attributeArtist: String,
    relationshipArtists: List<String>,
    language: String,
): String {
    val names = relationshipArtists
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    if (names.isEmpty()) return attributeArtist
    val separator = if (language.startsWith("zh-")) "、" else ", "
    return names.joinToString(separator)
}

internal fun selectOriginalAlias(
    variants: List<Alias>,
    localizedTitle: String,
    localizedArtist: String
): Alias? {
    val localizedKey = "${normalize(localizedTitle)}|${normalize(localizedArtist)}"
    val candidates = variants
        .filter { alias ->
            val aliasKey = "${normalize(alias.title)}|${normalize(alias.artist)}"
            aliasKey != localizedKey &&
                nonLatinLetterCount(alias.title) + nonLatinLetterCount(alias.artist) > 0
        }
        .distinctBy { alias -> "${normalize(alias.title)}|${normalize(alias.artist)}" }
    val score = compareBy<Alias> { alias -> nonLatinLetterCount(alias.title) }
        .thenBy { alias -> nonLatinLetterCount(alias.artist) }
    val originalTitle = candidates
        .filter { alias -> isOriginalTitle(alias, localizedTitle) }
        .maxWithOrNull(score)
    if (originalTitle != null) return originalTitle
    if (nonLatinLetterCount(localizedTitle) == 0) return null
    if (isCollaborationArtistName(localizedArtist)) return null
    return candidates.maxWithOrNull(score)
}

internal fun isConfidentOriginalSongAlias(
    alias: Alias,
    localizedTitle: String,
    localizedArtist: String,
): Boolean = isOriginalTitle(alias, localizedTitle) ||
    nonLatinLetterCount(localizedTitle) > 0

internal fun isReusableOriginalSongAlias(
    alias: Alias,
    localizedTitle: String,
    localizedArtist: String,
): Boolean = isAcceptableOriginalAlias(
    alias = alias,
    sourceLanguage = canonicalOriginalLanguage(alias.language),
) && isConfidentOriginalSongAlias(
    alias = alias,
    localizedTitle = localizedTitle,
    localizedArtist = localizedArtist,
)

internal fun isCollaborationArtistName(artist: String): Boolean {
    val normalized = artist.trim()
    if (normalized.isEmpty()) return false
    return COLLABORATION_ARTIST_PATTERNS.any { pattern -> pattern.containsMatchIn(normalized) }
}

internal fun isOriginalTitle(alias: Alias, localizedTitle: String): Boolean =
    normalize(alias.title) != normalize(localizedTitle) &&
        nonLatinLetterCount(alias.title) > 0

internal fun shouldResolve(metadata: MediaMetadataCache.Metadata): Boolean =
    AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
        mediaId = metadata.id,
        title = metadata.title,
        artist = metadata.artist,
        genre = metadata.genre,
    )

internal fun nonLatinLetterCount(value: String): Int {
    var count = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (Character.isLetter(codePoint) &&
            Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
        ) {
            count++
        }
        index += Character.charCount(codePoint)
    }
    return count
}

internal fun normalize(value: String): String = value
    .trim()
    .lowercase()
    .replace(Regex("[\\s\\p{Punct}]+"), "")

internal val COLLABORATION_ARTIST_PATTERNS = listOf(
    Regex(
        "(?:^|[\\s(\\[])(?:feat\\.?|ft\\.?|featuring|with)(?:\\s|[:.)\\]])",
        RegexOption.IGNORE_CASE,
    ),
    Regex("\\s[&×]\\s"),
    Regex("\\s[xX]\\s"),
    Regex("[,、;/／]"),
)

internal fun shouldCacheCatalogIdentity(
    isrc: String?,
    genres: Collection<String>,
): Boolean = !isrc.isNullOrBlank() ||
    languageTagsForOriginalMetadata(
        genre = null,
        catalogGenres = genres,
        isrc = null,
    ).isNotEmpty()

internal fun shouldRetryEmptyCatalogIdentity(
    mediaId: String?,
    title: String?,
    artist: String?,
    genre: String?,
    isrc: String?,
    catalogGenres: Collection<String>,
): Boolean = !shouldCacheCatalogIdentity(isrc, catalogGenres) &&
    AppleOriginalMetadataPolicy.shouldResolveCjkOriginalMetadata(
        mediaId = mediaId,
        title = title,
        artist = artist,
        genre = genre,
    )

/**
 * 原名专辑与标题/歌手别名置信度解耦：即使整首歌曲别名因标题相同或
 * 合作署名被拒绝，也保留已解析的原地区专辑名，供桥接元数据与在线检索使用。
 */
internal fun originalAlbumFromResolution(
alias: Alias?,
acceptableResults: List<Alias>,
): String? = alias?.album?.takeIf(String::isNotBlank)
?: acceptableResults.firstNotNullOfOrNull { result ->
    result.album.takeIf(String::isNotBlank)
}

