/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import kotlinx.serialization.Serializable

object MediaMetadataCache {
    private val metadataCache = object : LinkedHashMap<String, Metadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Metadata>?): Boolean =
            size > 100
    }

    /** 缓存从 Apple Music 内部播放队列中提取的歌曲元数据。 */
    fun put(metadata: Metadata) {
        metadataCache[metadata.id] = metadata
    }

    fun getMetadataById(mediaId: String): Metadata? = metadataCache[mediaId]

    @Serializable
    data class Metadata(
        val id: String,
        val title: String?,
        val artist: String?,
        val duration: Long,
        val queueId: Long
    )
}
