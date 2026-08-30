/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys

/**
 * Pure presentation assembly shared by notification-card and lock-screen consumers.
 * It converts one immutable snapshot into fixed semantic slots; View creation,
 * geometry, transition timing and native reflection remain outside this class.
 */
object LyricPresentationAssembler {
    fun assemble(
        snapshot: MediaLyricSnapshot,
        config: LyricPresentationConfig,
    ): LyricPresentationModel {
        val groups = buildList {
            add(groupFor(LyricPresentationGroup.CURRENT, snapshot.current, snapshot, config, 0))
            if (config.showNextLyric) {
                add(groupFor(LyricPresentationGroup.NEXT, snapshot.next, snapshot, config, 1))
                add(groupFor(LyricPresentationGroup.NEXT_NEXT, snapshot.nextNext, snapshot, config, 2))
            }
        }

        return LyricPresentationModel(
            snapshotSequence = snapshot.sequence,
            songKey = snapshot.songKey,
            packageName = snapshot.packageName,
            positionMs = snapshot.positionMs,
            isPlaying = snapshot.isPlaying,
            isTextMode = snapshot.isTextMode,
            groups = groups,
        )
    }

    private fun groupFor(
        group: LyricPresentationGroup,
        line: MediaLyricLineSnapshot?,
        snapshot: MediaLyricSnapshot,
        config: LyricPresentationConfig,
        blurDistance: Int,
    ): LyricPresentationGroupModel {
        val content = line?.let { ContentParts.from(it, config) } ?: ContentParts.empty()
        val lines = orderedSlots(config.swapTranslation).map { slot ->
            val alignment = content.alignment(slot, snapshot, config)
            LyricPresentationLine(
                group = group,
                slot = slot,
                role = content.role(slot),
                text = content.text(slot).orEmpty(),
                alignment = alignment,
                blurDistance = blurDistance,
            )
        }
        return LyricPresentationGroupModel(group, lines)
    }

    private fun orderedSlots(swapTranslation: Boolean): List<LyricPresentationSlot> =
        if (swapTranslation) {
            listOf(
                LyricPresentationSlot.TRANSLATION,
                LyricPresentationSlot.MAIN,
                LyricPresentationSlot.BACKING_TRANSLATION,
                LyricPresentationSlot.BACKING,
                LyricPresentationSlot.OVERLAPPING_TRANSLATION,
                LyricPresentationSlot.OVERLAPPING_MAIN,
                LyricPresentationSlot.OVERLAPPING_BACKING_TRANSLATION,
                LyricPresentationSlot.OVERLAPPING_BACKING,
                LyricPresentationSlot.NEXT,
            )
        } else {
            listOf(
                LyricPresentationSlot.MAIN,
                LyricPresentationSlot.TRANSLATION,
                LyricPresentationSlot.BACKING,
                LyricPresentationSlot.BACKING_TRANSLATION,
                LyricPresentationSlot.OVERLAPPING_MAIN,
                LyricPresentationSlot.OVERLAPPING_TRANSLATION,
                LyricPresentationSlot.OVERLAPPING_BACKING,
                LyricPresentationSlot.OVERLAPPING_BACKING_TRANSLATION,
                LyricPresentationSlot.NEXT,
            )
        }

    private data class ContentParts(
        val main: String,
        val translation: String,
        val backing: String,
        val backingTranslation: String,
        val overlappingMain: String,
        val overlappingTranslation: String,
        val overlappingBacking: String,
        val overlappingBackingTranslation: String,
        val next: String,
        val mainAlignment: Boolean,
        val backingAlignment: Boolean,
        val overlappingAlignment: Boolean,
        val overlappingBackingAlignment: Boolean,
        val groupVocals: Boolean,
    ) {
        fun text(slot: LyricPresentationSlot): String? {
            val value = when (slot) {
                LyricPresentationSlot.MAIN -> main
                LyricPresentationSlot.TRANSLATION -> translation
                LyricPresentationSlot.BACKING -> backing
                LyricPresentationSlot.BACKING_TRANSLATION -> backingTranslation
                LyricPresentationSlot.OVERLAPPING_MAIN -> overlappingMain
                LyricPresentationSlot.OVERLAPPING_TRANSLATION -> overlappingTranslation
                LyricPresentationSlot.OVERLAPPING_BACKING -> overlappingBacking
                LyricPresentationSlot.OVERLAPPING_BACKING_TRANSLATION -> overlappingBackingTranslation
                LyricPresentationSlot.NEXT -> next
            }
            return value.trim().takeIf { it.isNotBlank() }
        }

        fun role(slot: LyricPresentationSlot): LyricPresentationRole = when (slot) {
            LyricPresentationSlot.MAIN,
            LyricPresentationSlot.OVERLAPPING_MAIN -> LyricPresentationRole.MAIN
            LyricPresentationSlot.TRANSLATION,
            LyricPresentationSlot.BACKING_TRANSLATION,
            LyricPresentationSlot.OVERLAPPING_TRANSLATION,
            LyricPresentationSlot.OVERLAPPING_BACKING_TRANSLATION ->
                LyricPresentationRole.TRANSLATION
            LyricPresentationSlot.BACKING,
            LyricPresentationSlot.OVERLAPPING_BACKING -> LyricPresentationRole.BACKING
            LyricPresentationSlot.NEXT -> LyricPresentationRole.PREVIEW
        }

        fun alignment(
            slot: LyricPresentationSlot,
            snapshot: MediaLyricSnapshot,
            config: LyricPresentationConfig,
        ): LyricPresentationAlignment {
            val rightAligned = when (slot) {
                LyricPresentationSlot.MAIN,
                LyricPresentationSlot.TRANSLATION -> mainAlignment
                LyricPresentationSlot.BACKING,
                LyricPresentationSlot.BACKING_TRANSLATION -> backingAlignment
                LyricPresentationSlot.OVERLAPPING_MAIN,
                LyricPresentationSlot.OVERLAPPING_TRANSLATION -> overlappingAlignment
                LyricPresentationSlot.OVERLAPPING_BACKING,
                LyricPresentationSlot.OVERLAPPING_BACKING_TRANSLATION -> overlappingBackingAlignment
                LyricPresentationSlot.NEXT -> mainAlignment
            }
            return when {
                !config.duetLyrics -> LyricPresentationAlignment.CENTER
                config.centerNonDuetSong && !snapshot.songHasDuet ->
                    LyricPresentationAlignment.CENTER
                config.centerGroupVocals && groupVocals && slot in setOf(
                    LyricPresentationSlot.MAIN,
                    LyricPresentationSlot.TRANSLATION,
                ) -> LyricPresentationAlignment.CENTER
                rightAligned -> LyricPresentationAlignment.RIGHT
                else -> LyricPresentationAlignment.LEFT
            }
        }

        companion object {
            fun empty(): ContentParts = ContentParts(
                main = "",
                translation = "",
                backing = "",
                backingTranslation = "",
                overlappingMain = "",
                overlappingTranslation = "",
                overlappingBacking = "",
                overlappingBackingTranslation = "",
                next = "",
                mainAlignment = false,
                backingAlignment = false,
                overlappingAlignment = false,
                overlappingBackingAlignment = false,
                groupVocals = false,
            )

            fun from(
                line: MediaLyricLineSnapshot,
                config: LyricPresentationConfig,
            ): ContentParts {
                val metadata = line.metadata
                val normalizedMain = line.text.trim().ifBlank {
                    line.words.joinToString(separator = "") { it.text }.trim()
                }
                val rawTranslation = line.translation.trim().ifBlank {
                    line.translationWords.joinToString(separator = "") { it.text }.trim()
                }.takeUnless { it == normalizedMain }.orEmpty()
                val overlapping = metadata[LyricMetadataKeys.OVERLAPPING_LYRICS_GROUP]
                    .toBoolean()
                val rawBacking = (if (overlapping) {
                    metadata[LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING]
                } else {
                    line.secondary.ifBlank {
                        line.secondaryWords.joinToString(separator = "") { it.text }
                    }
                }).orEmpty().trim().takeUnless { it == normalizedMain }.orEmpty()
                val rawBackingTranslation = (if (overlapping) {
                    metadata[LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING_TRANSLATION]
                } else {
                    metadata[LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION]
                }).orEmpty().trim().takeIf { rawBacking.isNotBlank() && it != rawBacking }.orEmpty()
                val rawOverlappingMain = line.secondary.trim()
                    .takeIf { overlapping && it != normalizedMain }.orEmpty()
                val rawOverlappingTranslation = metadata[LyricMetadataKeys.OVERLAPPING_SECONDARY_TRANSLATION]
                    .orEmpty().trim().takeIf {
                        rawOverlappingMain.isNotBlank() && it != rawOverlappingMain
                    }.orEmpty()
                val rawOverlappingBacking = metadata[LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING]
                    .orEmpty().trim().takeIf {
                        rawOverlappingMain.isNotBlank() && it != rawOverlappingMain
                    }.orEmpty()
                val rawOverlappingBackingTranslation = metadata[
                    LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING_TRANSLATION
                ].orEmpty().trim().takeIf {
                    rawOverlappingBacking.isNotBlank() && it != rawOverlappingBacking
                }.orEmpty()
                val rawRoma = line.roma.trim().takeUnless { it == normalizedMain }.orEmpty()

                var translation = ""
                var backingTranslation = ""
                var overlappingTranslation = ""
                var overlappingBackingTranslation = ""
                when (config.translationDisplayMode) {
                    LyricTranslationDisplayMode.TRANSLATION -> {
                        translation = rawTranslation
                        backingTranslation = rawBackingTranslation
                        overlappingTranslation = rawOverlappingTranslation
                        overlappingBackingTranslation = rawOverlappingBackingTranslation
                        if (
                            translation.isBlank() && config.translationFallback &&
                            rawRoma.isNotBlank() && rawBacking.isBlank()
                        ) {
                            translation = rawRoma
                        }
                    }
                    LyricTranslationDisplayMode.PRONUNCIATION -> {
                        translation = if (rawRoma.isNotBlank() && rawBacking.isBlank()) rawRoma else ""
                        if (translation.isBlank() && config.translationFallback) {
                            translation = rawTranslation
                            backingTranslation = rawBackingTranslation
                            overlappingTranslation = rawOverlappingTranslation
                            overlappingBackingTranslation = rawOverlappingBackingTranslation
                        }
                    }
                    LyricTranslationDisplayMode.OFF -> Unit
                }

                return ContentParts(
                    main = normalizedMain,
                    translation = translation,
                    backing = rawBacking,
                    backingTranslation = backingTranslation,
                    overlappingMain = rawOverlappingMain,
                    overlappingTranslation = overlappingTranslation,
                    overlappingBacking = rawOverlappingBacking,
                    overlappingBackingTranslation = overlappingBackingTranslation,
                    next = "",
                    mainAlignment = line.alignedRight,
                    backingAlignment = line.alignedRight,
                    overlappingAlignment = metadata[
                        LyricMetadataKeys.CONCURRENT_SECONDARY_ALIGNED_RIGHT
                    ].toBoolean(),
                    overlappingBackingAlignment = metadata[
                        LyricMetadataKeys.CONCURRENT_SECONDARY_ALIGNED_RIGHT
                    ].toBoolean(),
                    groupVocals = metadata[LyricMetadataKeys.GROUP_VOCALS].toBoolean(),
                )
            }
        }
    }
}
