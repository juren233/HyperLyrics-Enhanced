/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.model

import kotlinx.serialization.Serializable

@Serializable
data class AppleSong(
    var name: String? = null,
    var artist: String? = null,
    var genre: String? = null,
    var originalTitle: String? = null,
    var originalArtist: String? = null,
    var originalAlbum: String? = null,
    var originalMetadataResolved: Boolean = false,
    var lyricsSource: String? = null,
    var adamId: String? = null,
    var pronunciationLanguages: MutableList<String> = mutableListOf(),
    var agents: MutableList<LyricAgent> = mutableListOf(),
    var duration: Int = 0,
    var lyrics: MutableList<LyricLine> = mutableListOf()
)
