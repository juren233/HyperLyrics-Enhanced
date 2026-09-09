/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.ClassicAodSongInfoConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.CjkLyricWhitespacePolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.view.SongPreprocessor
import com.juren233.hyperlyricsenhanced.root.ClassicAodFocusNotificationRecovery
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.DisplayDiagnosticLogger
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal data class AodLyricContent(
    val main: String,
    val translation: String,
    val backing: String,
    val backingTranslation: String,
    val overlappingMain: String,
    val overlappingTranslation: String,
    val overlappingBacking: String,
    val overlappingBackingTranslation: String,
    val next: String,
    val mainAlignment: AodLyricAlignment,
    val backingAlignment: AodLyricAlignment,
    val overlappingAlignment: AodLyricAlignment,
    val overlappingBackingAlignment: AodLyricAlignment,
    val nextAlignment: AodLyricAlignment,
)

internal enum class AodLyricAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

internal enum class AodLyricRow {
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

internal data class AodTextStyleConfig(
    val mainTextSize: Int,
    val backingTextSize: Int,
    val translationTextSize: Int,
    val showNextLyric: Boolean,
    val nextLyricStyle: Int,
    val duetLyrics: Boolean,
    val centerNonDuetSong: Boolean,
    val centerGroupVocals: Boolean,
    val pauseStyle: Int,
    val translationDisplayMode: Int,
    val translationFallback: Boolean,
    val swapTranslation: Boolean,
    val nextSongPreview: Boolean,
    val nextSongPreviewPosition: Int,
) {
    val translationDisplay: Boolean
        get() = translationDisplayMode != RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
}

internal data class AodHorizontalMargins(
    val left: Int,
    val right: Int,
)

internal object AodMediaLyricPolicy {
    private const val NO_LYRIC_PREVIEW_DURATION_MS = 5_000L

    fun embeddedSongInfoGravity(position: Int): Int = when (position) {
        RootConstants.AOD_SONG_INFO_POSITION_LEFT ->
            Gravity.LEFT or Gravity.CENTER_VERTICAL
        RootConstants.AOD_SONG_INFO_POSITION_RIGHT ->
            Gravity.RIGHT or Gravity.CENTER_VERTICAL
        else -> Gravity.CENTER
    }

    fun shouldShow(
        enabled: Boolean,
        fullAod: Boolean,
        playing: Boolean,
        hasLyric: Boolean,
        packageMatches: Boolean,
        pauseStyle: Int = RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE,
    ): Boolean = enabled &&
        fullAod &&
        (playing || pauseStyle == RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS) &&
        hasLyric &&
        packageMatches

    fun shouldShowNextSongPreview(
        enabled: Boolean,
        positionMs: Long,
        durationMs: Long,
        hasActualLyrics: Boolean,
        lastLyricStartMs: Long,
    ): Boolean {
        if (!enabled || positionMs < 0L || durationMs <= 0L) return false
        val previewStartMs = if (hasActualLyrics) {
            if (lastLyricStartMs < 0L) return false
            lastLyricStartMs
        } else {
            (durationMs - NO_LYRIC_PREVIEW_DURATION_MS).coerceAtLeast(0L)
        }
        return positionMs >= previewStartMs && positionMs < durationMs
    }

    fun formatNextSongPreview(title: String, artist: String): String {
        val songInfo = listOf(title.trim(), artist.trim())
            .filter { it.isNotBlank() }
            .joinToString("-")
        return songInfo.takeIf { it.isNotBlank() }?.let { "下一首：$it" }.orEmpty()
    }

    fun sanitizeNextSongPreviewPosition(value: Int): Int =
        value.takeIf {
            it in RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_LEFT..
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_RIGHT
        } ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION

    fun nextSongPreviewAlignment(position: Int): AodLyricAlignment =
        when (sanitizeNextSongPreviewPosition(position)) {
            RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_LEFT -> AodLyricAlignment.LEFT
            RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_RIGHT -> AodLyricAlignment.RIGHT
            else -> AodLyricAlignment.CENTER
        }

    fun shouldSuppressNoLyricPlaceholder(
        isTextMode: Boolean,
        hasActualLyrics: Boolean,
    ): Boolean = !isTextMode && !hasActualLyrics

    fun shouldCompactClassicMain(lineCount: Int): Boolean = lineCount <= 1

    fun requiredCardHeight(
        nativeCardHeight: Int,
        lyricBottom: Int,
        bottomPadding: Int
    ): Int = maxOf(
        nativeCardHeight.coerceAtLeast(0),
        lyricBottom.coerceAtLeast(0) + bottomPadding.coerceAtLeast(0)
    )

    fun lockScreenTargetCardHeight(
        nativeCardHeight: Int,
        lyricBottom: Int,
        bottomPadding: Int,
    ): Int = requiredCardHeight(
        nativeCardHeight = nativeCardHeight,
        lyricBottom = lyricBottom,
        bottomPadding = bottomPadding,
    )

    fun lockScreenBackgroundTargetHeight(
        targetCardHeight: Int,
    ): Int = targetCardHeight.coerceAtLeast(0)

    fun lockScreenNativeCardHeight(
        fullAod: Boolean,
        fullAodBaseHeight: Int,
        playerBaseHeight: Int
    ): Int = if (fullAod) {
        fullAodBaseHeight.coerceAtLeast(0)
    } else {
        playerBaseHeight.coerceAtLeast(0)
    }

    fun lockScreenHeightNeedsReassert(
        appliedHeight: Int?,
        currentLayoutHeight: Int?
    ): Boolean = appliedHeight != null &&
        appliedHeight > 0 &&
        currentLayoutHeight != null &&
        currentLayoutHeight != appliedHeight

    fun lockScreenHeightNeedsRestore(
        appliedHeight: Int?,
        heightAnimationActive: Boolean,
    ): Boolean = appliedHeight != null || heightAnimationActive

    fun lockScreenLyricTop(
        anchorBottom: Int,
        topGap: Int
    ): Int = anchorBottom.coerceAtLeast(0) + topGap.coerceAtLeast(0)

    fun contentAnchorBottom(albumBottom: Int, artistBottom: Int): Int =
        maxOf(albumBottom.coerceAtLeast(0), artistBottom.coerceAtLeast(0))

    fun lockScreenHorizontalMargins(
        playerWidth: Int,
        cardLeft: Int,
        cardRight: Int,
        albumLeft: Int,
        extraInset: Int = 0,
    ): AodHorizontalMargins {
        val safePlayerWidth = playerWidth.coerceAtLeast(0)
        val safeCardLeft = cardLeft.coerceIn(0, safePlayerWidth)
        val safeCardRight = cardRight.coerceIn(safeCardLeft, safePlayerWidth)
        val cardWidth = safeCardRight - safeCardLeft
        val inset = (albumLeft - safeCardLeft + extraInset.coerceAtLeast(0))
            .coerceIn(0, cardWidth / 2)
        return AodHorizontalMargins(
            left = safeCardLeft + inset,
            right = safePlayerWidth - safeCardRight + inset,
        )
    }

    fun classicOverlayHeight(contentHeight: Int, availableHeight: Int): Int =
        minOf(
            contentHeight.coerceAtLeast(1),
            availableHeight.coerceAtLeast(1)
        )

    fun isLockScreenAodActive(
        fullAod: Boolean,
        interactive: Boolean,
        playerShown: Boolean
    ): Boolean = fullAod || (!interactive && playerShown)

    fun readTranslationPronunciationMode(
        prefs: SharedPreferences?,
        key: String,
        defaultValue: Int = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY_MODE
    ): Int {
        if (prefs == null) return defaultValue
        val raw = try {
            prefs.all[key]
        } catch (_: Exception) {
            null
        }
        return when (raw) {
            is Int -> raw.coerceIn(
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            )
            is Number -> raw.toInt().coerceIn(
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            )
            is Boolean -> if (raw) RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
            else RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
            is String -> raw.toIntOrNull()?.coerceIn(
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            ) ?: defaultValue
            else -> defaultValue
        }
    }

    fun assembleContent(
        main: String?,
        translation: String?,
        backing: String?,
        backingTranslation: String?,
        roma: String?,
        overlappingMain: String? = null,
        overlappingTranslation: String? = null,
        overlappingBacking: String? = null,
        overlappingBackingTranslation: String? = null,
        next: String? = null,
        showNext: Boolean = false,
        mainAlignedRight: Boolean = false,
        backingAlignedRight: Boolean = mainAlignedRight,
        overlappingAlignedRight: Boolean = mainAlignedRight,
        overlappingBackingAlignedRight: Boolean = overlappingAlignedRight,
        mainGroupVocals: Boolean = false,
        nextAlignedRight: Boolean = false,
        nextGroupVocals: Boolean = false,
        duetLyrics: Boolean = RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        centerNonDuetSong: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        centerGroupVocals: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        translationDisplayMode: Int = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY_MODE,
        translationFallback: Boolean = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_FALLBACK,
    ): AodLyricContent {
        val normalizedMain = main.normalized()
        val rawTranslation = translation.normalized()
            .takeUnless { it == normalizedMain }
            .orEmpty()
        val rawBacking = backing.normalized()
            .takeUnless { it == normalizedMain }
            .orEmpty()
        val rawBackingTranslation = backingTranslation.normalized()
            .takeIf { rawBacking.isNotBlank() && it != rawBacking }
            .orEmpty()
        val rawOverlappingMain = overlappingMain.normalized()
            .takeUnless { it == normalizedMain }
            .orEmpty()
        val rawOverlappingTranslation = overlappingTranslation.normalized()
            .takeIf {
                rawOverlappingMain.isNotBlank() &&
                    it != rawOverlappingMain
            }
            .orEmpty()
        val rawOverlappingBacking = overlappingBacking.normalized()
            .takeIf {
                rawOverlappingMain.isNotBlank() &&
                    it != rawOverlappingMain
            }
            .orEmpty()
        val rawOverlappingBackingTranslation = overlappingBackingTranslation.normalized()
            .takeIf {
                rawOverlappingBacking.isNotBlank() &&
                    it != rawOverlappingBacking
            }
            .orEmpty()
        val rawRoma = roma.normalized()
            .takeUnless { it == normalizedMain }
            .orEmpty()

        var finalTranslation = ""
        var finalBackingTranslation = ""
        var finalOverlappingTranslation = ""
        var finalOverlappingBackingTranslation = ""

        when (translationDisplayMode) {
            RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION -> {
                finalTranslation = rawTranslation
                finalBackingTranslation = rawBackingTranslation
                finalOverlappingTranslation = rawOverlappingTranslation
                finalOverlappingBackingTranslation = rawOverlappingBackingTranslation
                if (finalTranslation.isBlank() && translationFallback && rawRoma.isNotBlank() && rawBacking.isBlank()) {
                    finalTranslation = rawRoma
                }
            }
            RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION -> {
                finalTranslation = if (rawRoma.isNotBlank() && rawBacking.isBlank()) rawRoma else ""
                if (finalTranslation.isBlank() && translationFallback) {
                    finalTranslation = rawTranslation
                    finalBackingTranslation = rawBackingTranslation
                    finalOverlappingTranslation = rawOverlappingTranslation
                    finalOverlappingBackingTranslation = rawOverlappingBackingTranslation
                }
            }
        }

        val hasDisplayedTranslation = finalTranslation.isNotBlank() ||
            finalBackingTranslation.isNotBlank()
        val normalizedNext = next.normalized()
            .takeIf {
                showNext &&
                    !hasDisplayedTranslation &&
                    it != normalizedMain
            }
            .orEmpty()
        return AodLyricContent(
            main = normalizedMain,
            translation = finalTranslation,
            backing = rawBacking,
            backingTranslation = finalBackingTranslation,
            overlappingMain = rawOverlappingMain,
            overlappingTranslation = finalOverlappingTranslation,
            overlappingBacking = rawOverlappingBacking,
            overlappingBackingTranslation = finalOverlappingBackingTranslation,
            next = normalizedNext,
            mainAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = mainAlignedRight,
                groupVocals = mainGroupVocals,
                centerGroupVocals = centerGroupVocals,
            ),
            backingAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = backingAlignedRight,
                groupVocals = false,
                centerGroupVocals = centerGroupVocals,
            ),
            overlappingAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = overlappingAlignedRight,
                groupVocals = false,
                centerGroupVocals = centerGroupVocals,
            ),
            overlappingBackingAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = overlappingBackingAlignedRight,
                groupVocals = false,
                centerGroupVocals = centerGroupVocals,
            ),
            nextAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = nextAlignedRight,
                groupVocals = nextGroupVocals,
                centerGroupVocals = centerGroupVocals,
            ),
        )
    }

    fun assembleContent(
        main: String?,
        translation: String?,
        backing: String?,
        backingTranslation: String?,
        roma: String?,
        overlappingMain: String? = null,
        overlappingTranslation: String? = null,
        overlappingBacking: String? = null,
        overlappingBackingTranslation: String? = null,
        next: String? = null,
        showNext: Boolean = false,
        mainAlignedRight: Boolean = false,
        backingAlignedRight: Boolean = mainAlignedRight,
        overlappingAlignedRight: Boolean = mainAlignedRight,
        overlappingBackingAlignedRight: Boolean = overlappingAlignedRight,
        mainGroupVocals: Boolean = false,
        nextAlignedRight: Boolean = false,
        nextGroupVocals: Boolean = false,
        duetLyrics: Boolean = RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        centerNonDuetSong: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        centerGroupVocals: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        translationDisplay: Boolean,
    ): AodLyricContent = assembleContent(
        main = main,
        translation = translation,
        backing = backing,
        backingTranslation = backingTranslation,
        roma = roma,
        overlappingMain = overlappingMain,
        overlappingTranslation = overlappingTranslation,
        overlappingBacking = overlappingBacking,
        overlappingBackingTranslation = overlappingBackingTranslation,
        next = next,
        showNext = showNext,
        mainAlignedRight = mainAlignedRight,
        backingAlignedRight = backingAlignedRight,
        overlappingAlignedRight = overlappingAlignedRight,
        overlappingBackingAlignedRight = overlappingBackingAlignedRight,
        mainGroupVocals = mainGroupVocals,
        nextAlignedRight = nextAlignedRight,
        nextGroupVocals = nextGroupVocals,
        duetLyrics = duetLyrics,
        centerNonDuetSong = centerNonDuetSong,
        centerGroupVocals = centerGroupVocals,
        translationDisplayMode = if (translationDisplay) RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
        else RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
        translationFallback = false,
    )

    fun orderedLyricRows(swapTranslation: Boolean): List<AodLyricRow> =
        if (swapTranslation) {
            listOf(
                AodLyricRow.TRANSLATION,
                AodLyricRow.MAIN,
                AodLyricRow.BACKING_TRANSLATION,
                AodLyricRow.BACKING,
                AodLyricRow.OVERLAPPING_TRANSLATION,
                AodLyricRow.OVERLAPPING_MAIN,
                AodLyricRow.OVERLAPPING_BACKING_TRANSLATION,
                AodLyricRow.OVERLAPPING_BACKING,
                AodLyricRow.NEXT,
            )
        } else {
            listOf(
                AodLyricRow.MAIN,
                AodLyricRow.TRANSLATION,
                AodLyricRow.BACKING,
                AodLyricRow.BACKING_TRANSLATION,
                AodLyricRow.OVERLAPPING_MAIN,
                AodLyricRow.OVERLAPPING_TRANSLATION,
                AodLyricRow.OVERLAPPING_BACKING,
                AodLyricRow.OVERLAPPING_BACKING_TRANSLATION,
                AodLyricRow.NEXT,
            )
        }

    fun lyricAlignment(
        duetLyrics: Boolean,
        centerNonDuetSong: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        alignedRight: Boolean,
        groupVocals: Boolean,
        centerGroupVocals: Boolean,
    ): AodLyricAlignment = when {
        !duetLyrics -> AodLyricAlignment.CENTER
        centerNonDuetSong -> AodLyricAlignment.CENTER
        groupVocals && centerGroupVocals -> AodLyricAlignment.CENTER
        alignedRight -> AodLyricAlignment.RIGHT
        else -> AodLyricAlignment.LEFT
    }

    fun sanitizeTextSize(value: Int, defaultValue: Int, min: Int, max: Int): Int =
        value.takeIf { it in min..max } ?: defaultValue

    fun sanitizeNextLyricStyle(value: Int): Int = value.takeIf {
        it == RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING ||
            it == RootConstants.AOD_NEXT_LYRIC_STYLE_TRANSLATION
    } ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE

    private fun String?.normalized(): String = this?.trim().orEmpty()
}
