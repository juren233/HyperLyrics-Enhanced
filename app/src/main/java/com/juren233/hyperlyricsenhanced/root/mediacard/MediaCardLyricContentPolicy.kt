/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.AodLyricAlignment
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.AodLyricRow
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.AodMediaLyricPolicy

internal enum class MediaCardLyricTextRole {
    MAIN,
    BACKING,
    TRANSLATION,
    PREVIEW,
}

internal enum class MediaCardLyricAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

internal data class MediaCardLyricRowContent(
    val text: String,
    val role: MediaCardLyricTextRole,
    val alignment: MediaCardLyricAlignment,
)

internal data class MediaCardLyricGroupContent(
    val rows: List<MediaCardLyricRowContent>,
    val blurDistance: Int,
)

internal data class MediaCardLyricContent(
    val groups: List<MediaCardLyricGroupContent>,
) {
    fun isEmpty(): Boolean = groups.none { group -> group.rows.any { it.text.isNotBlank() } }
}

internal object MediaCardLyricContentPolicy {
    fun lyricGroup(
        line: IRichLyricLine?,
        fallbackMain: String?,
        config: MediaCardLyricConfig,
        songHasDuet: Boolean,
        blurDistance: Int,
    ): MediaCardLyricGroupContent {
        val metadata = line?.metadata
        val overlapping = metadata?.getBoolean(LyricMetadataKeys.OVERLAPPING_LYRICS_GROUP) == true
        val main = line?.text?.trim().orEmpty().ifBlank { fallbackMain?.trim().orEmpty() }
        val mainAlignedRight = line?.isAlignedRight == true
        val secondaryAlignedRight = metadata?.getBoolean(
            LyricMetadataKeys.CONCURRENT_SECONDARY_ALIGNED_RIGHT,
            mainAlignedRight,
        ) ?: mainAlignedRight
        val content = AodMediaLyricPolicy.assembleContent(
            main = main,
            translation = line?.translation,
            backing = if (overlapping) {
                metadata.getString(LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING)
            } else {
                line?.secondary
            },
            backingTranslation = if (overlapping) {
                metadata.getString(LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING_TRANSLATION)
            } else {
                metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
            },
            roma = line?.roma,
            overlappingMain = line?.secondary.takeIf { overlapping },
            overlappingTranslation = metadata
                ?.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_TRANSLATION)
                .takeIf { overlapping },
            overlappingBacking = metadata
                ?.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING)
                .takeIf { overlapping },
            overlappingBackingTranslation = metadata
                ?.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING_TRANSLATION)
                .takeIf { overlapping },
            showNext = false,
            mainAlignedRight = mainAlignedRight,
            backingAlignedRight = mainAlignedRight,
            overlappingAlignedRight = secondaryAlignedRight,
            overlappingBackingAlignedRight = secondaryAlignedRight,
            mainGroupVocals = metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true,
            duetLyrics = config.duetLyrics,
            centerNonDuetSong = config.centerNonDuetSong && !songHasDuet,
            centerGroupVocals = config.centerGroupVocals,
            translationDisplayMode = config.translationDisplayMode,
            translationFallback = config.translationFallback,
        )
        val rows = AodMediaLyricPolicy.orderedLyricRows(config.swapTranslation)
            .mapNotNull { row -> rowContent(row, content) }
        return MediaCardLyricGroupContent(rows = rows, blurDistance = blurDistance)
    }

    fun previewGroup(
        text: String,
        position: Int,
    ): MediaCardLyricGroupContent = MediaCardLyricGroupContent(
        rows = listOf(
            MediaCardLyricRowContent(
                text = text.trim(),
                role = MediaCardLyricTextRole.PREVIEW,
                alignment = when (position) {
                    RootConstants.MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION_LEFT ->
                        MediaCardLyricAlignment.LEFT
                    RootConstants.MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION_RIGHT ->
                        MediaCardLyricAlignment.RIGHT
                    else -> MediaCardLyricAlignment.CENTER
                },
            )
        ).filter { it.text.isNotBlank() },
        blurDistance = 0,
    )

    fun shouldShowNextSongPreview(
        enabled: Boolean,
        positionMs: Long,
        durationMs: Long,
        hasActualLyrics: Boolean,
        lastLyricStartMs: Long,
    ): Boolean = AodMediaLyricPolicy.shouldShowNextSongPreview(
        enabled = enabled,
        positionMs = positionMs,
        durationMs = durationMs,
        hasActualLyrics = hasActualLyrics,
        lastLyricStartMs = lastLyricStartMs,
    )

    fun formatNextSongPreview(title: String, artist: String): String =
        AodMediaLyricPolicy.formatNextSongPreview(title, artist)

    private fun rowContent(
        row: AodLyricRow,
        content: com.juren233.hyperlyricsenhanced.root.mediacard.notification.AodLyricContent,
    ): MediaCardLyricRowContent? {
        val text: String
        val role: MediaCardLyricTextRole
        val alignment: AodLyricAlignment
        when (row) {
            AodLyricRow.MAIN -> {
                text = content.main
                role = MediaCardLyricTextRole.MAIN
                alignment = content.mainAlignment
            }
            AodLyricRow.TRANSLATION -> {
                text = content.translation
                role = MediaCardLyricTextRole.TRANSLATION
                alignment = content.mainAlignment
            }
            AodLyricRow.BACKING -> {
                text = content.backing
                role = MediaCardLyricTextRole.BACKING
                alignment = content.backingAlignment
            }
            AodLyricRow.BACKING_TRANSLATION -> {
                text = content.backingTranslation
                role = MediaCardLyricTextRole.TRANSLATION
                alignment = content.backingAlignment
            }
            AodLyricRow.OVERLAPPING_MAIN -> {
                text = content.overlappingMain
                role = MediaCardLyricTextRole.MAIN
                alignment = content.overlappingAlignment
            }
            AodLyricRow.OVERLAPPING_TRANSLATION -> {
                text = content.overlappingTranslation
                role = MediaCardLyricTextRole.TRANSLATION
                alignment = content.overlappingAlignment
            }
            AodLyricRow.OVERLAPPING_BACKING -> {
                text = content.overlappingBacking
                role = MediaCardLyricTextRole.BACKING
                alignment = content.overlappingBackingAlignment
            }
            AodLyricRow.OVERLAPPING_BACKING_TRANSLATION -> {
                text = content.overlappingBackingTranslation
                role = MediaCardLyricTextRole.TRANSLATION
                alignment = content.overlappingBackingAlignment
            }
            AodLyricRow.NEXT -> return null
        }
        return text.trim().takeIf { it.isNotBlank() }?.let {
            MediaCardLyricRowContent(
                text = it,
                role = role,
                alignment = alignment.toMediaCardAlignment(),
            )
        }
    }

    private fun AodLyricAlignment.toMediaCardAlignment(): MediaCardLyricAlignment = when (this) {
        AodLyricAlignment.LEFT -> MediaCardLyricAlignment.LEFT
        AodLyricAlignment.CENTER -> MediaCardLyricAlignment.CENTER
        AodLyricAlignment.RIGHT -> MediaCardLyricAlignment.RIGHT
    }
}
