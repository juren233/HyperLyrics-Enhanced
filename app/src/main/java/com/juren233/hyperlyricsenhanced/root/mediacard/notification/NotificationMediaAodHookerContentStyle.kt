/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("PrivateApi")

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
import com.juren233.hyperlyricsenhanced.root.PureMusicKeywords
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

internal fun NotificationMediaAodLyricHooker.currentContent(style: AodTextStyleConfig): AodLyricContent {
    val currentLine = LyriconDataBridge.currentLyricLine
    val removeCjkLyricSpaces = currentLine != null && prefs?.getBoolean(
        RootConstants.KEY_HOOK_REMOVE_CJK_LYRIC_SPACES,
        RootConstants.DEFAULT_HOOK_REMOVE_CJK_LYRIC_SPACES,
    ) == true && currentLine.metadata?.getBoolean(
        SongPreprocessor.KEY_TITLE_LINE
    ) != true
    /** 只处理 AOD 展示文本，发音 roma 不进入此处理。 */
    fun displayText(text: String?): String? = if (removeCjkLyricSpaces) {
        CjkLyricWhitespacePolicy.transformText(text)
    } else {
        text
    }
    val suppressNoLyricPlaceholder =
        AodMediaLyricPolicy.shouldSuppressNoLyricPlaceholder(
            isTextMode = LyriconDataBridge.isTextMode,
            hasActualLyrics = currentActualLyrics().isNotEmpty(),
        ) || hidesPureMusicPlaceholder(style)
    val line = LyriconDataBridge.currentLyricLine.takeUnless {
        suppressNoLyricPlaceholder
    }
    val nextLine = LyriconDataBridge.currentNextLyricLine.takeUnless {
        suppressNoLyricPlaceholder
    }
    val metadata = line?.metadata
    val isOverlappingGroup = metadata?.getBoolean(
        LyricMetadataKeys.OVERLAPPING_LYRICS_GROUP
    ) == true
    val main = if (suppressNoLyricPlaceholder) {
        ""
    } else {
        line?.text?.trim().orEmpty().ifBlank {
            LyriconDataBridge.currentLyric?.trim().orEmpty()
        }
    }
    val mainAlignedRight = line?.isAlignedRight == true
    val backingAlignedRight = metadata?.getBoolean(
        LyricMetadataKeys.CONCURRENT_SECONDARY_ALIGNED_RIGHT,
        mainAlignedRight
    ) ?: mainAlignedRight
    val primaryBacking = if (isOverlappingGroup) {
        metadata.getString(LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING)
    } else {
        line?.secondary
    }
    val primaryBackingTranslation = if (isOverlappingGroup) {
        metadata.getString(
            LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING_TRANSLATION
        )
    } else {
        metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
    }
    val overlappingMain = if (isOverlappingGroup) line.secondary else null
    val overlappingTranslation = if (isOverlappingGroup) {
        metadata.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_TRANSLATION)
    } else {
        null
    }
    val overlappingBacking = if (isOverlappingGroup) {
        metadata.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING)
    } else {
        null
    }
    val overlappingBackingTranslation = if (isOverlappingGroup) {
        metadata.getString(
            LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING_TRANSLATION
        )
    } else {
        null
    }
    return AodMediaLyricPolicy.assembleContent(
        main = displayText(main),
        translation = displayText(line?.translation),
        backing = displayText(primaryBacking),
        backingTranslation = displayText(primaryBackingTranslation),
        roma = line?.roma,
        overlappingMain = displayText(overlappingMain),
        overlappingTranslation = displayText(overlappingTranslation),
        overlappingBacking = displayText(overlappingBacking),
        overlappingBackingTranslation = displayText(overlappingBackingTranslation),
        next = displayText(nextLine?.text),
        showNext = style.showNextLyric,
        mainAlignedRight = mainAlignedRight,
        backingAlignedRight = mainAlignedRight,
        overlappingAlignedRight = backingAlignedRight,
        overlappingBackingAlignedRight = backingAlignedRight,
        mainGroupVocals =
            line?.metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true,
        nextAlignedRight = nextLine?.isAlignedRight == true,
        nextGroupVocals =
            nextLine?.metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true,
        duetLyrics = style.duetLyrics,
        centerNonDuetSong = style.centerNonDuetSong &&
            LyriconDataBridge.currentSong?.lyrics.orEmpty().none { it.isAlignedRight },
        centerGroupVocals = style.centerGroupVocals,
        translationDisplayMode = style.translationDisplayMode,
        translationFallback = style.translationFallback,
    )
}

internal fun NotificationMediaAodLyricHooker.appendNextSongPreview(
    content: AodLyricContent,
    style: AodTextStyleConfig,
    context: Context,
    packageName: String?,
): AodLyricContent {
    if (!style.nextSongPreview || packageName.isNullOrBlank()) return content
    val actualLyrics = currentActualLyrics()
    val mediaInfo = MediaMetadataHelper.getMediaInfo(context, packageName, HookLogger)
    val duration = LyriconDataBridge.currentSong?.duration?.takeIf { it > 0L }
        ?: mediaInfo.duration
    val position = LyriconDataBridge.estimatedPosition()
        ?: LyriconDataBridge.currentPosition
    if (
        !AodMediaLyricPolicy.shouldShowNextSongPreview(
            enabled = true,
            positionMs = position,
            durationMs = duration,
            // 占位歌词已隐藏时按无歌词处理，只在结尾前预告，不从占位行开始一直显示。
            hasActualLyrics = actualLyrics.isNotEmpty() && !hidesPureMusicPlaceholder(style),
            lastLyricStartMs = actualLyrics.maxOfOrNull { it.begin } ?: -1L,
        )
    ) {
        return content
    }
    val nextSong = MediaMetadataHelper.getNextMediaInfo(
        context = context,
        packageName = packageName,
        current = mediaInfo,
    )
    val preview = AodMediaLyricPolicy.formatNextSongPreview(
        title = nextSong.title,
        artist = nextSong.artist,
    )
    return if (preview.isBlank()) {
        content
    } else {
        content.copy(
            next = preview,
            nextAlignment = AodMediaLyricPolicy.nextSongPreviewAlignment(
                style.nextSongPreviewPosition
            ),
        )
    }
}

/** 开启「纯音乐隐藏占位歌词」且当前歌曲按「纯音乐判定关键词」判为纯音乐：占位歌词不显示。 */
internal fun NotificationMediaAodLyricHooker.hidesPureMusicPlaceholder(style: AodTextStyleConfig): Boolean {
    if (!style.hidePureMusicPlaceholder) return false
    val currentPrefs = prefs ?: return false
    return LyriconDataBridge.isPureMusic(PureMusicKeywords.of(currentPrefs))
}

internal fun NotificationMediaAodLyricHooker.currentActualLyrics() =
    LyriconDataBridge.currentSong?.lyrics.orEmpty().filterNot { line ->
        line.metadata?.getBoolean(SongPreprocessor.KEY_TITLE_LINE) == true
    }

internal fun NotificationMediaAodLyricHooker.lockScreenAodTextStyle(): AodTextStyleConfig =
    lockScreenAodTextStyle(prefs)

/** 锁屏 AOD 的显示设置；[prefs] 为空时全部取默认值。 */
internal fun lockScreenAodTextStyle(prefs: SharedPreferences?): AodTextStyleConfig = with(prefs) { AodTextStyleConfig(
    mainTextSize = readAodTextSize(
        key = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE,
        defaultValue = RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE,
        min = RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
    ),
    backingTextSize = readAodTextSize(
        key = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE,
        defaultValue = RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE,
        min = RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
    ),
    translationTextSize = readAodTextSize(
        key = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE,
        defaultValue = RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE,
        min = RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
    ),
    showNextLyric = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_SHOW_NEXT_LYRIC,
        RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
    nextLyricStyle = readAodNextLyricStyle(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_LYRIC_STYLE
    ),
    duetLyrics = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_DUET_LYRICS,
        RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
    centerNonDuetSong = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_CENTER_NON_DUET_SONG,
        RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
    centerGroupVocals = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_CENTER_GROUP_VOCALS,
        RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
    pauseStyle = readAodPauseStyle(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_PAUSE_STYLE
    ),
    translationDisplayMode = AodMediaLyricPolicy.readTranslationPronunciationMode(
        prefs = prefs,
        key = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_DISPLAY,
        defaultValue = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY_MODE,
    ),
    translationFallback = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_FALLBACK,
        RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_FALLBACK,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_FALLBACK,
    swapTranslation = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_SWAP_TRANSLATION,
        RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
    nextSongPreview = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_SONG_PREVIEW,
        RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
    nextSongPreviewPosition = readAodNextSongPreviewPosition(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_SONG_PREVIEW_POSITION
    ),
    hidePureMusicPlaceholder = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_HIDE_PURE_MUSIC_PLACEHOLDER,
        RootConstants.DEFAULT_HOOK_AOD_HIDE_PURE_MUSIC_PLACEHOLDER,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_HIDE_PURE_MUSIC_PLACEHOLDER,
    wordSyncEffect = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_WORD_SYNC_EFFECT,
        RootConstants.DEFAULT_HOOK_AOD_WORD_SYNC_EFFECT,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_WORD_SYNC_EFFECT,
    interludeDots = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_INTERLUDE_DOTS,
        RootConstants.DEFAULT_HOOK_AOD_INTERLUDE_DOTS,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_INTERLUDE_DOTS,
    lyricScroll = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_LYRIC_SCROLL,
        RootConstants.DEFAULT_HOOK_AOD_LYRIC_SCROLL,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_LYRIC_SCROLL,
) }

internal fun NotificationMediaAodLyricHooker.classicAodTextStyle(): AodTextStyleConfig =
    classicAodTextStyle(prefs)

/** 自定义 AOD 的显示设置；[prefs] 为空时全部取默认值。 */
internal fun classicAodTextStyle(prefs: SharedPreferences?): AodTextStyleConfig = with(prefs) { AodTextStyleConfig(
    mainTextSize = readAodTextSize(
        key = RootConstants.KEY_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE,
        defaultValue = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE,
        min = RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
    ),
    backingTextSize = readAodTextSize(
        key = RootConstants.KEY_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE,
        defaultValue = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE,
        min = RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
    ),
    translationTextSize = readAodTextSize(
        key = RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE,
        defaultValue = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE,
        min = RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
    ),
    showNextLyric = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_SHOW_NEXT_LYRIC,
        RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
    nextLyricStyle = readAodNextLyricStyle(
        RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_LYRIC_STYLE
    ),
    duetLyrics = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_DUET_LYRICS,
        RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
    centerNonDuetSong = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_CENTER_NON_DUET_SONG,
        RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
    centerGroupVocals = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_CENTER_GROUP_VOCALS,
        RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
    pauseStyle = readAodPauseStyle(
        RootConstants.KEY_HOOK_CLASSIC_AOD_PAUSE_STYLE
    ),
    translationDisplayMode = AodMediaLyricPolicy.readTranslationPronunciationMode(
        prefs = prefs,
        key = RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_DISPLAY,
        defaultValue = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY_MODE,
    ),
    translationFallback = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_FALLBACK,
        RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_FALLBACK,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_FALLBACK,
    swapTranslation = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_SWAP_TRANSLATION,
        RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
    nextSongPreview = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_SONG_PREVIEW,
        RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
    nextSongPreviewPosition = readAodNextSongPreviewPosition(
        RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_SONG_PREVIEW_POSITION
    ),
    hidePureMusicPlaceholder = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_HIDE_PURE_MUSIC_PLACEHOLDER,
        RootConstants.DEFAULT_HOOK_AOD_HIDE_PURE_MUSIC_PLACEHOLDER,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_HIDE_PURE_MUSIC_PLACEHOLDER,
    wordSyncEffect = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_WORD_SYNC_EFFECT,
        RootConstants.DEFAULT_HOOK_AOD_WORD_SYNC_EFFECT,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_WORD_SYNC_EFFECT,
    interludeDots = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_INTERLUDE_DOTS,
        RootConstants.DEFAULT_HOOK_AOD_INTERLUDE_DOTS,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_INTERLUDE_DOTS,
    lyricScroll = prefs?.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_LYRIC_SCROLL,
        RootConstants.DEFAULT_HOOK_AOD_LYRIC_SCROLL,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_LYRIC_SCROLL,
) }

internal fun SharedPreferences?.readAodTextSize(
    key: String,
    defaultValue: Int,
    min: Int,
    max: Int,
): Int = AodMediaLyricPolicy.sanitizeTextSize(
    value = this?.getInt(key, defaultValue) ?: defaultValue,
    defaultValue = defaultValue,
    min = min,
    max = max,
)

internal fun SharedPreferences?.readAodNextLyricStyle(key: String): Int =
    AodMediaLyricPolicy.sanitizeNextLyricStyle(
        this?.getInt(
            key,
            RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE
    )

internal fun SharedPreferences?.readAodNextSongPreviewPosition(key: String): Int =
    AodMediaLyricPolicy.sanitizeNextSongPreviewPosition(
        this?.getInt(
            key,
            RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION
    )

internal fun SharedPreferences?.readAodPauseStyle(key: String): Int =
    (this?.getInt(
        key,
        RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE,
    ) ?: RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE).coerceIn(
        RootConstants.AOD_PAUSE_STYLE_RESTORE,
        RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS,
    )

