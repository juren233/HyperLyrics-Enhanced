/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleContentLocalizationHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleDebugNetworkHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleAtmosVolumeDiagnostics
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.ApplePlaybackHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.FunctionalAppleMusicHookModule
import io.github.proify.lyricon.amprovider.xposed.lyrics.AppleOnlineSourceMenuHooks
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalReentryGuard
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.serialization.decodeFromString
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun AppleMusicProviderOrchestrator.initializeContentUiLanguage() {
    val prefs = runCatching {
        module.getRemotePreferences(UIConstants.PREF_NAME)
    }.getOrNull() ?: return
    contentUiLanguagePrefs = prefs
    AppleLyricTextTransform.initialize(application) {
        lyricsHooks.isSimplifyTraditionalLyricsEnabled()
    }
    internalCatalogResolver.setPersistentLocalizedCacheEnabled(
        prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
        )
    )
    applyConfiguredContentUiLanguage(prefs)
    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
        when (key) {
            RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE -> {
                applyConfiguredContentUiLanguage(changed)
                metadataConfigurationDispatcher.dispatch()
            }
            RootConstants.KEY_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE ->
                metadataConfigurationDispatcher.dispatch()
            RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA ->
                metadataConfigurationDispatcher.dispatch()
            RootConstants.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE ->
                applyConfiguredContentUiLanguage(changed)
            RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS ->
                lyricsHooks.refreshAppleLyricsDisplay()
            RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN -> {
                lyricsHooks.clearPendingApplePronunciationRenderPlans()
                lyricsHooks.refreshAppleLyricsSupplementPresentation()
            }
            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION,
            RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
            RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
            RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
            RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX ->
                lyricsHooks.refreshAppleLyricsBlurEffect()
            RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT,
            RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT ->
                lyricsHooks.refreshAppleSystemFont()
            RootConstants.KEY_HOOK_APPLE_MUSIC_VOLUME_BALANCE -> {
                val enabled = changed.getBoolean(
                    key,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_VOLUME_BALANCE,
                )
                ProviderLogger.info(
                    "[AtmosVolumeDiag] event=preference_changed," +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime()}," +
                        "enabled=$enabled"
                )
                playbackHooks.onVolumeBalancePreferenceChanged()
            }
            RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
            RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION -> {
                if (!lyricsHooks.isNativeOnlineTranslationEnabled()) {
                    lyricsHooks.nativeOnlineTranslationStore.clear()
                    lyricsHooks.refreshAppleLyricsSupplementPresentation()
                }
            }
            RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
            RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS ->
                missingLyricsHooks.onPreferenceChanged()
            RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS -> {
                playbackHooks.onAodPreferenceChanged()
            }
            else -> {
                if (com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences.isSourcePreference(key)) {
                    currentLyricsMenuSongId(
                        playbackSongId = playbackMetadataCoordinator.currentPlaybackQueueMediaId(),
                        visibleLyricsSongId = lyricsHooks.currentSongId(),
                    )?.let { songId ->
                        onlineSourceMenuHooks.refreshActiveMenu(songId)
                    }
                }
            }
        }
    }
    contentUiLanguagePreferenceListener = listener
    prefs.registerOnSharedPreferenceChangeListener(listener)
    ProviderLogger.diagnostic(
        "[AtmosVolumeDiag] event=preference_listener_registered," +
            "listener=${System.identityHashCode(listener)}"
    )
}

internal fun AppleMusicProviderOrchestrator.applyConfiguredContentUiLanguage(
    prefs: android.content.SharedPreferences? = contentUiLanguagePrefs
) {
    prefs ?: return
    val selection = prefs.getInt(
        RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
    )
    internalCatalogResolver.applyContentUiLanguage(selection)
    internalCatalogResolver.setPersistentLocalizedCacheEnabled(
        prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
        )
    )
}

internal fun AppleMusicProviderOrchestrator.configuredContentUiLanguage(): Int {
    val prefs = contentUiLanguagePrefs
    return prefs?.getInt(
        RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
}

internal fun AppleMusicProviderOrchestrator.shouldOverrideAccountLanguage(selection: Int): Boolean {
    if (selection == RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE) return false
    return contentUiLanguagePrefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
    ) == true
}

internal fun AppleMusicProviderOrchestrator.shouldRestoreCjkOriginalMetadata(
    metadata: MediaMetadataCache.Metadata,
): Boolean = isRestoreCjkOriginalMetadataEnabled() &&
    AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
        mediaId = metadata.id,
        title = metadata.title,
        artist = metadata.artist,
        genre = metadata.genre,
    )

internal fun AppleMusicProviderOrchestrator.shouldRetryOriginalMetadataCacheProbe(mediaId: String): Boolean =
    shouldRetryOriginalMetadataCacheProbe(
        originalResolved = metadataOverrideStore.isOriginalResolved(mediaId),
        lastMissUptimeMillis = metadataOverrideStore.originalCacheMissUptimeMillis(mediaId),
        nowUptimeMillis = SystemClock.uptimeMillis(),
    )

internal fun AppleMusicProviderOrchestrator.shouldRequestInAppMetadataOverride(mediaId: String): Boolean =
    metadataResolutionCoordinator.shouldRequestOverride(mediaId)

internal fun AppleMusicProviderOrchestrator.isRestoreCjkOriginalMetadataEnabled(): Boolean =
    contentUiLanguagePrefs?.getBoolean(
    RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
) == true

internal fun AppleMusicProviderOrchestrator.isSimplifyTraditionalLyricsEnabled(): Boolean =
    contentUiLanguagePrefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
    ) == true

internal fun AppleMusicProviderOrchestrator.isAodLyricsEnabled(): Boolean = contentUiLanguagePrefs?.getBoolean(
    RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
    RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
) ?: RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS
