/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.timeline.model

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * 时间轴快照的曲目身份。
 *
 * 生产者（模块应用进程）与消费者（SystemUI）各自从媒体会话元数据构建本对象；
 * [matches] 是两侧唯一的身份判定入口。拼接键规则与应用进程既有
 * [com.juren233.hyperlyricsenhanced.service.source.MediaSongIdentity] 保持一致
 * （trim 后以 \u001F 连接），保证两侧对同一首歌得到相同键。
 *
 * 任何模糊匹配（标题相似、时长接近）都禁止进入身份判定；模糊候选只能用于 shadow 对比。
 */
@Serializable
data class TrackIdentity(
    val packageName: String,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
) {
    private fun normalizedParts(): List<String> = listOf(
        packageName.trim(),
        title.trim(),
        artist.trim(),
        album.trim(),
        durationMs.toString(),
    )

    /**
     * 规范化组合键（不含 mediaId）：与应用进程 MediaSongIdentity 规则逐字段一致。
     */
    private fun compositeKey(): String = normalizedParts().joinToString(SEPARATOR.toString())

    /** QQ HD 的 MediaSession ID 是会话序号，不能作为切歌身份。 */
    private fun qqMusicHdKey(): String? {
        if (packageName.trim() != QQ_MUSIC_HD_PACKAGE) return null
        return listOf(packageName.trim(), qqMusicHdText(title), qqMusicHdText(artist))
            .joinToString(SEPARATOR.toString())
    }

    private fun qqMusicHdText(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    /** 规范化键。QQ HD 使用歌名和歌手，其余播放器优先使用 mediaId。 */
    fun normalizedKey(): String {
        qqMusicHdKey()?.let { return it }
        val id = mediaId?.trim().orEmpty()
        return if (id.isNotEmpty()) {
            listOf(packageName.trim(), id).joinToString(SEPARATOR.toString())
        } else {
            compositeKey()
        }
    }

    /**
     * 严格身份匹配：
     * - 两侧都有非空 mediaId → 包名 + mediaId 必须相等，且不再看其他字段；
     * - 任一侧缺 mediaId → 双方组合键必须完全相等（此时 mediaId 差异不参与判定，
     *   因此这里必须用组合键而不是 [normalizedKey]，后者会被自身的 mediaId 短路）。
     */
    fun matches(other: TrackIdentity): Boolean {
        if (packageName.trim() == QQ_MUSIC_HD_PACKAGE ||
            other.packageName.trim() == QQ_MUSIC_HD_PACKAGE
        ) {
            return qqMusicHdKey() != null && qqMusicHdKey() == other.qqMusicHdKey()
        }
        val selfId = mediaId?.trim().orEmpty()
        val otherId = other.mediaId?.trim().orEmpty()
        if (selfId.isNotEmpty() && otherId.isNotEmpty()) {
            return packageName.trim() == other.packageName.trim() && selfId == otherId
        }
        return compositeKey() == other.compositeKey()
    }

    /**
     * SystemUI 进程内来源的确定性部分匹配。只比较双方都提供的字段，不做相似度、时长
     * 容差或简繁转换；两侧都有 mediaId 时仍以包名 + mediaId 为唯一判据。
     */
    fun matchesAvailableFields(other: TrackIdentity): Boolean {
        val selfPackage = packageName.trim()
        val otherPackage = other.packageName.trim()
        if (selfPackage.isNotEmpty() && otherPackage.isNotEmpty() && selfPackage != otherPackage) {
            return false
        }

        if (selfPackage == QQ_MUSIC_HD_PACKAGE || otherPackage == QQ_MUSIC_HD_PACKAGE) {
            val selfTitle = qqMusicHdText(title)
            val otherTitle = qqMusicHdText(other.title)
            val selfArtist = qqMusicHdText(artist)
            val otherArtist = other.qqMusicHdText(other.artist)
            return selfPackage == otherPackage &&
                selfTitle.isNotEmpty() && otherTitle.isNotEmpty() &&
                selfArtist.isNotEmpty() && otherArtist.isNotEmpty() &&
                selfTitle == otherTitle && selfArtist == otherArtist
        }

        val selfId = mediaId?.trim().orEmpty()
        val otherId = other.mediaId?.trim().orEmpty()
        if (selfId.isNotEmpty() && otherId.isNotEmpty()) return selfId == otherId

        val selfTitle = title.trim()
        val otherTitle = other.title.trim()
        if (selfTitle.isNotEmpty() && otherTitle.isNotEmpty() && selfTitle != otherTitle) return false

        val selfArtist = artist.trim()
        val otherArtist = other.artist.trim()
        if (selfArtist.isNotEmpty() && otherArtist.isNotEmpty() && selfArtist != otherArtist) return false

        val selfAlbum = album.trim()
        val otherAlbum = other.album.trim()
        if (selfAlbum.isNotEmpty() && otherAlbum.isNotEmpty() && selfAlbum != otherAlbum) return false

        return selfId.isNotEmpty() || otherId.isNotEmpty() ||
            (selfTitle.isNotEmpty() && otherTitle.isNotEmpty())
    }

    companion object {
        private const val SEPARATOR = '\u001F'
        private const val QQ_MUSIC_HD_PACKAGE = "com.tencent.qqmusicpad"
    }
}
