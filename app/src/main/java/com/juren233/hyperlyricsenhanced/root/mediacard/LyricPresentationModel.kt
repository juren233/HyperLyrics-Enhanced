/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

enum class LyricPresentationGroup {
    CURRENT,
    NEXT,
    NEXT_NEXT,
}

enum class LyricPresentationSlot {
    MAIN,
    TRANSLATION,
    BACKING,
    BACKING_TRANSLATION,
    OVERLAPPING_MAIN,
    OVERLAPPING_TRANSLATION,
    OVERLAPPING_BACKING,
    OVERLAPPING_BACKING_TRANSLATION,
    NEXT,
}

enum class LyricPresentationRole {
    MAIN,
    TRANSLATION,
    BACKING,
    PREVIEW,
}

enum class LyricPresentationAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

enum class LyricTranslationDisplayMode {
    OFF,
    TRANSLATION,
    PRONUNCIATION,
}

data class LyricPresentationLine(
    val group: LyricPresentationGroup,
    val slot: LyricPresentationSlot,
    val role: LyricPresentationRole,
    val text: String,
    val alignment: LyricPresentationAlignment,
    val blurDistance: Int,
) {
    val isVisible: Boolean
        get() = text.isNotBlank()
}

data class LyricPresentationGroupModel(
    val group: LyricPresentationGroup,
    val lines: List<LyricPresentationLine>,
) {
    val isEmpty: Boolean
        get() = lines.none(LyricPresentationLine::isVisible)
}

data class LyricPresentationModel(
    val snapshotSequence: Long,
    val songKey: String?,
    val packageName: String?,
    val positionMs: Long,
    val isPlaying: Boolean?,
    val isTextMode: Boolean,
    val groups: List<LyricPresentationGroupModel>,
) {
    val isEmpty: Boolean
        get() = groups.all(LyricPresentationGroupModel::isEmpty)

    val visibleLineCount: Int
        get() = groups.sumOf { group -> group.lines.count(LyricPresentationLine::isVisible) }

    fun linesInStableSlotOrder(): List<LyricPresentationLine> = groups
        .flatMap { it.lines }
        .sortedWith(compareBy({ it.group.ordinal }, { it.slot.ordinal }))
}

data class LyricPresentationConfig(
    val translationDisplayMode: LyricTranslationDisplayMode = LyricTranslationDisplayMode.OFF,
    val translationFallback: Boolean = false,
    val swapTranslation: Boolean = false,
    val showNextLyric: Boolean = false,
    val duetLyrics: Boolean = false,
    val centerNonDuetSong: Boolean = false,
    val centerGroupVocals: Boolean = false,
)
