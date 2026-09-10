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

internal fun AppleMusicProviderOrchestrator.startHooks() {
    hookModules().asSequence()
        .filter { hookModule -> !hookModule.debugOnly || BuildConfig.DEBUG }
        .forEach { hookModule ->
            hookRegistrar.withModule(hookModule.id, hookModule::installHooks)
        }
}

internal fun AppleMusicProviderOrchestrator.hookModuleIdsForBuild(debug: Boolean): List<String> =
    hookModules()
        .filter { hookModule -> !hookModule.debugOnly || debug }
        .map { hookModule -> hookModule.id }

internal fun AppleMusicProviderOrchestrator.hookModules() = listOf(
    FunctionalAppleMusicHookModule(
        "hookMetadataSurfaceLifecycle",
        installer = { inAppMetadata.metadataSurfaceRuntime.installLifecycleHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookTranslationPreference",
        installer = { lyricsPlayback.lyricsHooks.hookTranslationPreference() },
    ),
    FunctionalAppleMusicHookModule(
        "hookMediaApiLocalization",
        installer = { catalogLanguage.contentLocalizationHooks.installMediaApiLocalization() },
    ),
    FunctionalAppleMusicHookModule(
        "hookContentHttpLocalization",
        installer = { catalogLanguage.contentLocalizationHooks.installContentHttpLocalization() },
    ),
    FunctionalAppleMusicHookModule(
        "hookExoMediaPlayer",
        installer = { lyricsPlayback.playbackHooks.installExoMediaPlayer() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAtmosVolumeDiagnostics",
        debugOnly = true,
        installer = { lyricsPlayback.atmosphereVolumeDiagnostics.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookMediaMetadataChange",
        installer = { lyricsPlayback.playbackMetadataHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookContentItemMetadata",
        installer = { inAppMetadata.contentItemMetadataHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppLibraryEntities",
        installer = { inAppMetadata.librarySurfaceHooks.installEntityHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookCollectionPageMetadataRefresh",
        installer = { inAppMetadata.collectionSurfaceHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookArtistProfileTopSongs",
        installer = { inAppMetadata.artistSurfaceHooks.installTopSongHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookArtistProfileMetadata",
        installer = { inAppMetadata.artistSurfaceHooks.installProfileHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookRecentlySearchedMetadata",
        installer = { inAppMetadata.mediaApiMetadataCoordinator.installRecentlySearchedHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppArtworkContinuity",
        installer = { inAppMetadata.inAppArtworkContinuityHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppListenNowArtworkContinuity",
        installer = { inAppMetadata.listenNowHooks.installArtworkContinuityHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppLibraryEpoxyRefresh",
        installer = { inAppMetadata.librarySurfaceHooks.installEpoxyHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppLibraryComposeRefresh",
        installer = { inAppMetadata.librarySurfaceHooks.installComposeHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookDebugListenNowArtworkLifecycle",
        debugOnly = true,
        installer = { inAppMetadata.listenNowHooks.installDebugArtworkLifecycleHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookVisibleMetadataDiagnostics",
        debugOnly = true,
        installer = { inAppMetadata.visibleMetadataDiagnostics.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppDataBindingRefresh",
        installer = { inAppMetadata.dataBindingHooks.installDataBindingHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppListenNowMetadataBinding",
        installer = { inAppMetadata.listenNowHooks.installMetadataBindingHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookRecyclerViewCentralBinding",
        installer = { inAppMetadata.dataBindingHooks.installRecyclerHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppMetadata",
        installer = { inAppMetadata.queueMetadataHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppPlaybackItemConversion",
        installer = { inAppMetadata.playbackItemConversionHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppActionSheetMetadata",
        installer = { inAppMetadata.actionSheetMetadataHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookSettingsCellularDataEntry",
        installer = {
            io.github.proify.lyricon.amprovider.xposed.hooks.AppleCellularDataSettingsHooks(
                runtime,
                preferences = { catalogLanguage.contentUiLanguagePrefs },
            ).install()
        },
    ),
    FunctionalAppleMusicHookModule(
        "hookCellularAvailability",
        installer = {
            io.github.proify.lyricon.amprovider.xposed.hooks.AppleCellularDataSettingsHooks(
                runtime,
                preferences = { catalogLanguage.contentUiLanguagePrefs },
            ).installCellularAvailability()
        },
    ),
    FunctionalAppleMusicHookModule(
        "hookMediaSessionMetadata",
        installer = { inAppMetadata.frameworkMetadataHooks.installMediaSessionMetadata() },
    ),
    FunctionalAppleMusicHookModule(
        "hookMediaSessionQueue",
        installer = { inAppMetadata.frameworkMetadataHooks.installMediaSessionQueue() },
    ),
    FunctionalAppleMusicHookModule(
        "hookPlaybackNotificationMetadata",
        installer = { inAppMetadata.frameworkMetadataHooks.installPlaybackNotificationMetadata() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleOfficialPronunciationLanguageMatching",
        installer = { lyricsPlayback.lyricsHooks.hookAppleOfficialPronunciationLanguageMatching() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsPreferredLanguages",
        installer = { lyricsPlayback.lyricsHooks.hookAppleLyricsPreferredLanguages() },
    ),
    FunctionalAppleMusicHookModule(
        "hookApplePronunciationWordRendering",
        installer = { lyricsPlayback.lyricsHooks.hookApplePronunciationWordRendering() },
    ),
    FunctionalAppleMusicHookModule(
        "hookLyricBuildMethod",
        installer = { lyricsPlayback.lyricsHooks.hookLyricBuildMethod() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleNativeLyricsPresentation",
        installer = { lyricsPlayback.lyricsHooks.hookAppleNativeLyricsPresentation() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleSystemFontWeight",
        installer = { lyricsPlayback.lyricsHooks.hookAppleSystemFontWeight() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsBlurEffect",
        installer = { lyricsPlayback.lyricsHooks.hookAppleLyricsBlurEffect() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsUiDiagnostics",
        debugOnly = true,
        installer = { lyricsPlayback.lyricsHooks.hookAppleLyricsUiDiagnostics() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsBindingDiagnostics",
        debugOnly = true,
        installer = { lyricsPlayback.lyricsHooks.hookAppleLyricsBindingDiagnostics() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsSourceMenu",
        installer = { lyricsPlayback.onlineSourceMenuHooks.installSourceMenu() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleMissingLyricsSupplement",
        installer = { lyricsPlayback.missingLyricsHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookLyricsNetworkRequest",
        debugOnly = true,
        installer = { lyricsPlayback.debugNetworkHooks.installLyricsNetworkRequest() },
    ),
    FunctionalAppleMusicHookModule(
        "hookLyricsCookies",
        debugOnly = true,
        installer = { lyricsPlayback.debugNetworkHooks.installLyricsCookies() },
    ),
    FunctionalAppleMusicHookModule(
        "hookFinalLyricsHttp",
        debugOnly = true,
        installer = { lyricsPlayback.debugNetworkHooks.installFinalLyricsHttp() },
    ),
)
