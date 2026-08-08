/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import io.github.proify.lyricon.amprovider.xposed.parser.LyricsSectionParser.mergeLyrics

object AppleSongParser {

    internal fun parser(songNative: Any, access: AppleLyricsParserAccess): AppleSong =
        AppleSong().apply {
            adamId = access.call(songNative, AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD)
                .toString()

            access.call(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_AGENTS_METHOD)?.let {
                agents = LyricsAgentParser.parserAgentVector(it, access)
            }

            duration = access.call(
                songNative,
                AppleMusicRuntimeMember.LYRICS_NATIVE_DURATION_METHOD,
            ) as? Int ?: 0

            runCatching {
                access.call(
                    songNative,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
                )?.let { StringVectorParser.parserStringVectorNative(it, access) }
            }.getOrNull()?.let { pronunciationLanguages = it }

            val sections = access.call(
                songNative,
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD,
            )
            if (sections != null) {
                lyrics = LyricsSectionParser.parserSectionVector(sections, access).mergeLyrics()
            }
            adamId?.let {
                MediaMetadataCache.getMetadataById(it)
                    ?.let { metadata ->
                        name = metadata.title
                        artist = metadata.artist
                        album = metadata.album
                        genre = metadata.genre
                        originalTitle = metadata.originalTitle
                        originalArtist = metadata.originalArtist
                        originalAlbum = metadata.originalAlbum
                    }
            }
        }
}
