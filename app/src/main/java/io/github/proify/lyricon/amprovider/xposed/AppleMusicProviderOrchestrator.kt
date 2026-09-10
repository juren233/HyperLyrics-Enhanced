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

/**
 * 按 Apple 播放页原生回调顺序刷新无歌词补充状态。
 *
 * 先重放媒体元数据回调：Apple 的 PlayerSongViewFragment 会在这里读取
 * ReturnToLyrics 标志、歌词按钮 selected 状态和 hasLyrics()，满足条件时自动调用
 * 原生歌词页切换；随后再刷新 DataBinding，覆盖按钮尚未重新绑定的冷启动时序。
 */
internal fun refreshMissingLyricsNowPlaying(
    mediaId: String?,
    refreshMetadataCallbacks: (String?) -> Unit,
    refreshPlaybackItemBindings: (String?) -> Unit,
) {
    refreshMetadataCallbacks(mediaId)
    refreshPlaybackItemBindings(mediaId)
}

/** Internal lifecycle and module coordinator behind [AppleMusicProvider]. */
internal object AppleMusicProviderOrchestrator {
    internal const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    internal val initialized = AtomicBoolean(false)
    internal lateinit var runtime: AppleMusicProviderRuntime
    internal val application: Application
        get() = runtime.application
    internal val classLoader: ClassLoader
        get() = runtime.classLoader
    internal val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    internal val module: XposedModule
        get() = runtime.module
    internal val hookRegistrar
        get() = runtime.hookRegistrar
    internal lateinit var contentLocalizationHooks: AppleContentLocalizationHooks
    internal lateinit var debugNetworkHooks: AppleDebugNetworkHooks
    internal lateinit var atmosphereVolumeDiagnostics: AppleAtmosVolumeDiagnostics
    internal lateinit var frameworkMetadataHooks: AppleFrameworkMetadataHooks
    internal lateinit var lyricsHooks: AppleLyricsSupplementHooks
    internal lateinit var onlineSourceMenuHooks: AppleOnlineSourceMenuHooks
    internal lateinit var missingLyricsHooks: AppleMissingLyricsHooks
    internal lateinit var playbackHooks: ApplePlaybackHooks
    internal lateinit var queueMetadataHooks: AppleQueueMetadataHooks
    internal lateinit var listenNowHooks: AppleListenNowHooks
    internal lateinit var librarySurfaceHooks: AppleLibrarySurfaceHooks
    internal lateinit var dataBindingHooks: AppleDataBindingMetadataHooks
    internal lateinit var collectionSurfaceHooks: AppleCollectionSurfaceHooks
    internal lateinit var artistSurfaceHooks: AppleArtistSurfaceHooks
    internal lateinit var inAppArtworkContinuityHooks: AppleInAppArtworkContinuityHooks
    internal lateinit var actionSheetMetadataHooks: AppleActionSheetMetadataHooks
    internal lateinit var playbackItemConversionHooks: ApplePlaybackItemConversionHooks
    internal lateinit var metadataSurfaceRuntime: AppleMetadataSurfaceRuntime
    internal lateinit var visibleMetadataDiagnostics: AppleVisibleMetadataDiagnostics
    internal lateinit var metadataConfigurationDispatcher: AppleMetadataConfigurationDispatcher
    internal lateinit var playbackMetadataCoordinator: ApplePlaybackMetadataCoordinator
    internal lateinit var playbackMetadataHooks: ApplePlaybackMetadataHooks
    internal lateinit var media3MetadataCoordinator: AppleMedia3MetadataCoordinator
    internal lateinit var contentItemMetadataHooks: AppleContentItemMetadataHooks
    internal lateinit var mediaApiMetadataCoordinator: AppleMediaApiMetadataCoordinator
    internal lateinit var metadataResolutionCoordinator: AppleInAppMetadataResolutionCoordinator
    internal var directPlayer: AppleDirectPlayer? = null
    internal lateinit var lyricRequester: LyricRequester
    internal lateinit var internalCatalogResolver: AppleInternalCatalogResolver
    internal var contentUiLanguagePrefs: android.content.SharedPreferences? = null
    internal var contentUiLanguagePreferenceListener:
        android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    internal val metadataOverrideStore = AppleMetadataOverrideStore()
    internal val inAppMetadataRegistry = AppleInAppMetadataRegistry()
    internal lateinit var inAppMetadataApplier: AppleInAppMetadataApplier
    internal lateinit var metadataOverrideApplicationCoordinator:
        AppleMetadataOverrideApplicationCoordinator
    internal lateinit var metadataRegistrationCoordinator:
        AppleInAppMetadataRegistrationCoordinator
    internal val mainHandler: Handler
        get() = runtime.mainHandler
    internal val metadataTraceSequence = AtomicLong(0L)

    @Synchronized
    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (::runtime.isInitialized) {
            ProviderLogger.info("Apple Music 内置歌词提供器生命周期 Hook 已存在")
            return
        }
        runtime = AppleMusicProviderRuntime(module, classLoader)
        val onCreate = Application::class.java.getDeclaredMethod("onCreate")
        hookRegistrar.withModule("provider-lifecycle") {
            hookRegistrar.installHook(onCreate, after = { chain, _ ->
                (chain.thisObject as? Application)?.let(::onAppCreate)
            })
        }
        ProviderLogger.info("Apple Music 内置歌词提供器生命周期 Hook 已安装")
    }

    private fun onAppCreate(app: Application) {
        if (!initialized.compareAndSet(false, true)) return
        val appleMusicVersion = runCatching {
            val packageInfo = app.packageManager.getPackageInfo(APPLE_MUSIC_PACKAGE, 0)
            AppleMusicVersion(
                versionName = packageInfo.versionName,
                versionCode = packageInfo.longVersionCode,
            )
        }.getOrElse {
            AppleMusicVersion(versionName = null, versionCode = null)
        }
        val hookResolver = AppleMusicHookResolver(
            version = appleMusicVersion,
            application = app,
            nativeLibraryDir = module.getModuleApplicationInfo().nativeLibraryDir,
        )
        runtime.attach(app, hookResolver)
        ProviderLogger.info(
            "Apple Music Hook 版本档案已加载: app=${appleMusicVersion.displayName}, " +
                "profile=${hookResolver.profile?.id ?: "compatibility-fallback"}"
        )

        runCatching {
            PreferencesMonitor.initialize(application, hookResolver)
            PreferencesMonitor.listener = object : PreferencesMonitor.Listener {
                override fun onTranslationSelectedChanged(selected: Boolean) {
                    playbackHooks.setDisplayTranslation(selected)
                    lyricsHooks.refreshAppleLyricsSupplementPresentation()
                }

                override fun onPronunciationSelectedChanged(selected: Boolean) {
                    lyricsHooks.refreshAppleLyricsSupplementPresentation()
                }
            }
            DiskSongManager.initialize(application)
            internalCatalogResolver = AppleInternalCatalogResolver(
                context = application,
                classLoader = classLoader,
                hookResolver = hookResolver,
                mainHandler = Handler(Looper.getMainLooper())
            )
            playbackMetadataCoordinator = ApplePlaybackMetadataCoordinator(
                hookResolver = runtime.hookResolver,
                catalogResolver = internalCatalogResolver,
                metadataStore = metadataOverrideStore,
                host = DefaultApplePlaybackMetadataCoordinatorHost(
                    activePlayerFn = {
                        if (::playbackHooks.isInitialized) playbackHooks.activePlayer() else null
                    },
                    configuredContentUiLanguageFn = { configuredContentUiLanguage() },
                    shouldOverrideAccountLanguageFn = { selection ->
                        shouldOverrideAccountLanguage(selection)
                    },
                    shouldRestoreCjkOriginalMetadataFn = { metadata ->
                        shouldRestoreCjkOriginalMetadata(metadata)
                    },
                    ensureContentItemMetadataHooksFn = { contentItemClass ->
                        contentItemMetadataHooks.ensureHooks(contentItemClass)
                    },
                    setMetadataPlaybackMediaIdFn = { mediaId ->
                        setMetadataPlaybackMediaId(mediaId)
                    },
                    onCurrentPlaybackItemFn = { mediaId, playbackItem, queueId ->
                        if (::missingLyricsHooks.isInitialized) {
                            missingLyricsHooks.onCurrentPlaybackItem(
                                contentSongId = mediaId,
                                item = playbackItem,
                                queueId = queueId,
                            )
                        }
                    },
                    effectiveMetadataAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    applyPlaybackMetadataOverrideFn = {
                        mediaId, alias, rememberLocalizedArtist, originalMetadata,
                        originalMetadataConfirmed,
                        ->
                        applyPlaybackMetadataOverride(
                            mediaId = mediaId,
                            alias = alias,
                            rememberLocalizedArtist = rememberLocalizedArtist,
                            originalMetadata = originalMetadata,
                            originalMetadataConfirmed = originalMetadataConfirmed,
                        )
                    },
                    logMetadataIdentityFn = { event, details ->
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    },
                    shouldShareOriginalSongLanguageFn = { localizedTitle, localizedArtist, alias ->
                        metadataResolutionCoordinator.shouldShareOriginalSongLanguage(
                            localizedTitle = localizedTitle,
                            localizedArtist = localizedArtist,
                            alias = alias,
                        )
                    },
                    rememberOriginalLanguageForArtistFn = { mediaId, language ->
                        metadataResolutionCoordinator.rememberOriginalLanguageForArtist(
                            mediaId,
                            language,
                        )
                    },
                    isRestoreOriginalMetadataEnabledFn = { isRestoreCjkOriginalMetadataEnabled() },
                ),
            )
            contentLocalizationHooks = AppleContentLocalizationHooks(
                runtime = runtime,
                preferences = { contentUiLanguagePrefs },
                catalogResolver = { internalCatalogResolver },
            )
            debugNetworkHooks = AppleDebugNetworkHooks(runtime)
            lyricsHooks = AppleLyricsSupplementHooks(
                runtime = runtime,
                preferences = { contentUiLanguagePrefs },
                playbackHooks = { playbackHooks },
                onlineSourceMenuHooks = { onlineSourceMenuHooks },
                lyricRequester = { lyricRequester },
                catalogResolver = {
                    if (::internalCatalogResolver.isInitialized) internalCatalogResolver else null
                },
                applyConfiguredContentUiLanguageCallback = {
                    applyConfiguredContentUiLanguage()
                },
                recordLyricsRequestSource = { requestId, source ->
                    debugNetworkHooks.recordLyricsRequestSource(requestId, source)
                },
                currentPlaybackQueueMediaId =
                    playbackMetadataCoordinator::currentPlaybackQueueMediaId,
                registeredPlaybackItems = inAppMetadataRegistry::livePlaybackItems,
                registeredPlaybackItemId = inAppMetadataRegistry::playbackItemId,
                epoxyDataBindingFromHolderCallback = { holder ->
                    dataBindingHooks.bindingFromHolder(holder)
                },
                missingLyricsSupplement = { missingLyricsHooks },
            )
                missingLyricsHooks = AppleMissingLyricsHooks(
                runtime = runtime,
                preferences = { contentUiLanguagePrefs },
                currentPlaybackQueueMediaId =
                    playbackMetadataCoordinator::currentPlaybackQueueMediaId,
                currentVisibleLyricsSongId = lyricsHooks::currentSongId,
                requestPresentationRefresh = { pointer, fragment, playbackItem ->
                    val refreshStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                    val refreshSongId = lyricsHooks.currentSongId()
                    lyricsHooks.requestMissingLyricsPresentationRefresh(
                        supplementPointer = pointer,
                        fragmentOverride = fragment,
                        currentPlaybackItem = playbackItem,
                    )
                    AppleSourceSwitchPerformanceDiagnostics.record(
                        songId = refreshSongId,
                        event = "presentation_refresh_dispatch",
                        durationNanos = SystemClock.elapsedRealtimeNanos() - refreshStartedAtNanos,
                        details = "pointer=${pointer != null},fragment=${fragment != null}," +
                            "playbackItem=${playbackItem != null}",
                    )
                },
                requestBlankNativeLyricsPageRecovery = { fragment ->
                    lyricsHooks.scheduleBlankNativeLyricsPageRecovery(fragment)
                },
                refreshVisibleSupplementTranslation = { update ->
                    lyricsHooks.refreshVisibleMissingLyricsTranslation(update)
                },
                refreshNowPlaying = { mediaId ->
                    val refreshStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                        songId = mediaId,
                        stage = "refresh_now_playing_dispatch_started",
                        details = "thread=${Thread.currentThread().name}"
                    )
                    refreshMissingLyricsNowPlaying(
                        mediaId = mediaId,
                        refreshMetadataCallbacks = { id ->
                            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                                songId = id,
                                stage = "refresh_metadata_dispatch_started",
                                details = "thread=${Thread.currentThread().name}"
                            )
                            inAppMetadataApplier.refreshMetadataCallbacks(id)
                            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                                songId = id,
                                stage = "refresh_metadata_dispatch_finished",
                                details = "thread=${Thread.currentThread().name}"
                            )
                        },
                        refreshPlaybackItemBindings = { id ->
                            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                                songId = id,
                                stage = "refresh_playback_binding_dispatch_started",
                                details = "thread=${Thread.currentThread().name}"
                            )
                            inAppMetadataApplier.refreshPlaybackItemBindings(id)
                            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                                songId = id,
                                stage = "refresh_playback_binding_dispatch_finished",
                                details = "thread=${Thread.currentThread().name}"
                            )
                        },
                    )
                    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                        songId = mediaId,
                        stage = "refresh_now_playing_dispatch_finished",
                        details = "thread=${Thread.currentThread().name}"
                    )
                    AppleSourceSwitchPerformanceDiagnostics.record(
                        songId = mediaId,
                        event = "refresh_now_playing_dispatch_total",
                        durationNanos =
                            SystemClock.elapsedRealtimeNanos() - refreshStartedAtNanos,
                        details = "mediaId=${mediaId ?: "none"}",
                    )
                },
            )
            onlineSourceMenuHooks = AppleOnlineSourceMenuHooks(
                runtime = runtime,
                nativeTranslationStore = lyricsHooks.nativeOnlineTranslationStore,
                currentSongId = {
                    currentLyricsMenuSongId(
                        playbackSongId = playbackMetadataCoordinator.currentPlaybackQueueMediaId(),
                        visibleLyricsSongId = lyricsHooks.currentSongId(),
                    )
                },
                visibleLyricsSongId = lyricsHooks::currentSongId,
                shouldHideMandarinPronunciation = lyricsHooks::shouldHideMandarinPronunciation,
                hasOnlineContentConsumption = lyricsHooks::hasCurrentOnlineContentConsumption,
                missingLyricsSourceInfo = { songId ->
                    if (::missingLyricsHooks.isInitialized) {
                        missingLyricsHooks.sourceInfo(songId)
                    } else {
                        null
                    }
                },
                hasMissingLyricsSupplement = { songId ->
                    ::missingLyricsHooks.isInitialized &&
                        missingLyricsHooks.hasSupplementContent(songId)
                },
                missingLyricsTranslationSource = { songId ->
                    if (::missingLyricsHooks.isInitialized) {
                        missingLyricsHooks.translationSource(songId)
                    } else {
                        null
                    }
                },
                missingLyricsTranslationMatchPercentage = { songId, source ->
                    if (::missingLyricsHooks.isInitialized) {
                        missingLyricsHooks.translationMatchPercentage(songId, source)
                    } else {
                        null
                    }
                },
                missingLyricsPronunciationMatchPercentage = { songId, source ->
                    if (::missingLyricsHooks.isInitialized) {
                        missingLyricsHooks.pronunciationMatchPercentage(songId, source)
                    } else {
                        null
                    }
                },
                missingLyricsPronunciationSource = { songId ->
                    if (::missingLyricsHooks.isInitialized) {
                        missingLyricsHooks.pronunciationSource(songId)
                    } else {
                        null
                    }
                },
                requestOnlineSource = { requestId, songId, contentType, source ->
                    directPlayer?.requestOnlineLyricContentSource(
                        requestId = requestId,
                        songId = songId,
                        contentType = contentType,
                        source = source,
                    ) == true
                },
                debugValue = lyricsHooks::debugAppleLyricsValue,
                configuredOrderedSources = {
                    com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
                        .orderedSources(contentUiLanguagePrefs)
                        .map { it.name }
                },
                availableLyricsSources = { songId ->
                    if (::missingLyricsHooks.isInitialized) {
                        val available = missingLyricsHooks.availableLyricsSources(songId)
                        if ("APPLE" in available) {
                            available
                        } else if (
                            contentUiLanguagePrefs?.getBoolean(
                                RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
                                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
                            ) != true
                        ) {
                            available
                        } else {
                            (available +
                                com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
                                    .orderedSources(contentUiLanguagePrefs)
                                    .map { it.name })
                                .distinct()
                        }
                    } else {
                        emptyList()
                    }
                },
            )
            queueMetadataHooks = AppleQueueMetadataHooks(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                host = DefaultAppleQueueMetadataHost(
                    activePlaybackIdentityFn = {
                        this@AppleMusicProviderOrchestrator.activePlaybackMediaIdentity()
                    },
                    logMetadataIdentityFn = { event, identity, details ->
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(event, identity, details)
                    },
                    media3MetadataIdFn = { metadata, fallback, trustedFallback ->
                        this@AppleMusicProviderOrchestrator.media3MetadataId(
                            metadata = metadata,
                            fallback = fallback,
                            trustedFallback = trustedFallback,
                        )
                    },
                    media3MetadataDetailsFn = { metadata ->
                        this@AppleMusicProviderOrchestrator.media3MetadataDetails(metadata)
                    },
                    registerMetadataFn = { mediaId, metadata, requestResolution, preBind, priority ->
                        registerInAppMetadata(
                            mediaId = mediaId,
                            metadata = metadata,
                            requestResolution = requestResolution,
                            preBind = preBind,
                            priority = priority,
                        )
                    },
                    registerPlaybackItemFn = { mediaId, playbackItem, notifyChange, analyzeMetadata ->
                        registerInAppPlaybackItem(
                            mediaId = mediaId,
                            playbackItem = playbackItem,
                            notifyChange = notifyChange,
                            analyzeMetadata = analyzeMetadata,
                        )
                    },
                    contentItemMediaIdFn = { contentItem, refresh ->
                        contentItemMetadataHooks.mediaId(contentItem, refresh)
                    },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    applyAliasToPlaybackItemFn = { playbackItem, alias, notifyChange ->
                        applyAliasToInAppPlaybackItem(playbackItem, alias, notifyChange)
                    },
                    shouldRequestOverrideFn = { mediaId ->
                        shouldRequestInAppMetadataOverride(mediaId)
                    },
                    ensureOverrideFn = { mediaId, preBind, priority ->
                        metadataResolutionCoordinator.ensureOverride(
                            mediaId = mediaId,
                            preBind = preBind,
                            priority = priority,
                        )
                    },
                    ensureOverridesFn = { mediaIds, preBind, originalResolutionLimit ->
                        metadataResolutionCoordinator.ensureOverrides(
                            mediaIds = mediaIds,
                            preBind = preBind,
                            originalResolutionLimit = originalResolutionLimit,
                        )
                    },
                    readPlaybackItemValueFn = { playbackItem, field, contract ->
                        this@AppleMusicProviderOrchestrator.readInAppPlaybackItemValue(
                            playbackItem = playbackItem,
                            field = field,
                            contract = contract,
                        )
                    },
                    markMetadataVisibleFn = { mediaIds ->
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    },
                    isCurrentMetadataSurfaceMediaIdFn = { mediaId ->
                        this@AppleMusicProviderOrchestrator.isCurrentMetadataSurfaceMediaId(mediaId)
                    },
                    registry = inAppMetadataRegistry,
                ),
            )
            listenNowHooks = AppleListenNowHooks(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                catalogResolver = internalCatalogResolver,
                host = DefaultAppleListenNowHost(
                    mediaApiEntityAttributesFn = { entity ->
                        mediaApiMetadataCoordinator.entityAttributes(entity)
                    },
                    mediaApiEntityCatalogIdFn = { entity, knownAttributes ->
                        mediaApiMetadataCoordinator.entityCatalogId(
                            entity,
                            knownAttributes,
                        )
                    },
                    registerLibraryEntityFn = { mediaId, entity, kind, knownAttributes, requestResolution, retainEntityRef ->
                        librarySurfaceHooks.registerEntity(
                            mediaId = mediaId,
                            entity = entity,
                            kind = kind,
                            knownAttributes = knownAttributes,
                            requestResolution = requestResolution,
                            retainEntityRef = retainEntityRef,
                        )
                    },
                    enrichLibraryEntityFn = { mediaId, entity, kind, attributes ->
                        librarySurfaceHooks.enrichEntity(mediaId, entity, kind, attributes)
                    },
                    isRestoreOriginalMetadataEnabledFn = { isRestoreCjkOriginalMetadataEnabled() },
                    shouldRetryOriginalMetadataCacheProbeFn = { mediaId ->
                        this@AppleMusicProviderOrchestrator
                            .shouldRetryOriginalMetadataCacheProbe(mediaId)
                    },
                    rememberOriginalMetadataOverrideFn = { mediaId, alias, confirmed ->
                        metadataOverrideApplicationCoordinator.rememberOriginalMetadataOverride(
                            mediaId = mediaId,
                            alias = alias,
                            confirmed = confirmed,
                        )
                    },
                    rememberOriginalLanguageForArtistFn = { mediaId, language ->
                        metadataResolutionCoordinator.rememberOriginalLanguageForArtist(
                            mediaId,
                            language,
                        )
                    },
                    resolveCachedOriginalEntityForInAppFn = { mediaId, entityType, preBind, priority ->
                        metadataResolutionCoordinator.resolveCachedOriginalEntity(
                            mediaId = mediaId,
                            entityType = entityType,
                            preBind = preBind,
                            priority = priority,
                        )
                    },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    applyAliasToLibraryEntityFn = { entity, kind, alias ->
                        librarySurfaceHooks.applyAliasToEntity(
                            entity = entity,
                            kind = kind,
                            alias = alias,
                        )
                    },
                    shouldRequestOverrideFn = { mediaId ->
                        shouldRequestInAppMetadataOverride(mediaId)
                    },
                    markMetadataVisibleFn = { mediaIds ->
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    },
                    scheduleMetadataResolutionFn = { mediaIds, priority, originalResolutionMode ->
                        metadataResolutionCoordinator.schedule(
                            mediaIds = mediaIds,
                            priority = priority,
                            originalResolutionMode = originalResolutionMode,
                        )
                    },
                    logMetadataIdentityFn = { event, details ->
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    },
                    isDataBindingInstanceFn = { candidate ->
                        dataBindingHooks.isBindingInstance(candidate)
                    },
                    dataBindingFromHolderFn = { argument ->
                        dataBindingHooks.bindingFromHolder(argument)
                    },
                    beginDataBindingModelBindFn = { binding ->
                        dataBindingHooks.beginModelBind(binding)
                    },
                    clearDataBindingMediaIdFn = { binding ->
                        dataBindingHooks.clearMediaId(binding)
                    },
                    dataBindingGenerationFn = { binding ->
                        dataBindingHooks.generation(binding)
                    },
                    captureDataBindingFn = { binding ->
                        dataBindingHooks.capture(binding)
                    },
                    registerDataBindingFn = { mediaId, binding ->
                        dataBindingHooks.register(mediaId, binding)
                    },
                    aliasValuesFn = { mediaId, alias, binding ->
                        dataBindingAliasValues(
                            mediaId = mediaId,
                            alias = alias,
                            binding = binding,
                        )
                    },
                    renderedTextsFn = { binding ->
                        dataBindingHooks.renderedTexts(binding)
                    },
                    appliedAliasFn = { binding ->
                        dataBindingHooks.appliedAlias(binding)
                    },
                    rememberAppliedAliasFn = { binding, alias ->
                        dataBindingHooks.rememberAppliedAlias(binding, alias)
                    },
                    applyAliasVariablesFn = { binding, values ->
                        dataBindingHooks.applyAliasVariables(binding, values)
                    },
                    invalidateDataBindingFn = { binding ->
                        dataBindingHooks.invalidate(binding)
                    },
                    executePendingDataBindingsFn = { binding ->
                        dataBindingHooks.executePending(binding)
                    },
                    traceSequence = metadataTraceSequence,
                ),
            )
            librarySurfaceHooks = AppleLibrarySurfaceHooks(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                host = DefaultAppleLibrarySurfaceHost(
                    contentItemMediaIdFn = { source ->
                        contentItemMetadataHooks.mediaId(source)
                    },
                    primeLibrarySourceFn = { source ->
                        mediaApiMetadataCoordinator.primeLibrarySource(source)
                    },
                    mediaApiEntityAttributesFn = { entity ->
                        mediaApiMetadataCoordinator.entityAttributes(entity)
                    },
                    mediaApiEntityCatalogIdFn = { entity, knownAttributes ->
                        mediaApiMetadataCoordinator.entityCatalogId(
                            entity,
                            knownAttributes,
                        )
                    },
                    mediaApiEntityLookupIdsFn = { entity, knownAttributes ->
                        mediaApiMetadataCoordinator.entityLookupIds(
                            entity,
                            knownAttributes,
                        )
                    },
                    mergePlaybackAccountMetadataFn = { mediaId, title, artist ->
                        this@AppleMusicProviderOrchestrator.mergePlaybackAccountMetadata(
                            mediaId = mediaId,
                            title = title,
                            artist = artist,
                            reconcileArtistAssociations = false,
                        )
                    },
                    requestPriorityForMediaIdFn = { mediaId ->
                        this@AppleMusicProviderOrchestrator.requestPriorityForMediaId(mediaId)
                    },
                    enrichEntityAssociationsFn = { mediaId, entity, kind, attributes, originalName, originalArtist, originalAlbum ->
                        mediaApiMetadataCoordinator.enrichLibraryEntityAssociations(
                            mediaId = mediaId,
                            entity = entity,
                            kind = kind,
                            attributes = attributes,
                            originalName = originalName,
                            originalArtist = originalArtist,
                            originalAlbum = originalAlbum,
                        )
                    },
                    recordCurrentRecyclerMediaIdFn = { mediaId ->
                        this@AppleMusicProviderOrchestrator.recordCurrentRecyclerMediaId(mediaId)
                    },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    normalizeMediaIdsFn = { mediaIds ->
                        normalizedRecyclerBindingMediaIds(mediaIds).toList()
                    },
                    markMetadataVisibleFn = { mediaIds ->
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    },
                    applyAliasToMetadataRefsFn = { mediaId, alias ->
                        applyAliasToInAppMetadataRefs(
                            mediaId = mediaId,
                            alias = alias,
                            forceRebind = true,
                            notifyModelChange = true,
                        )
                    },
                    scheduleMetadataResolutionFn = { mediaIds, priority ->
                        metadataResolutionCoordinator.schedule(mediaIds, priority)
                    },
                    isRefreshableMediaIdFn = { mediaId ->
                        isRefreshableInAppMediaId(mediaId)
                    },
                    logMetadataIdentityFn = { event, details ->
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(event = event, details = details)
                    },
                    debugStackSummaryFn = {
                        visibleMetadataDiagnostics.stackSummary()
                    },
                    controllerBuildStrategyFn = { controller ->
                        inAppLibraryControllerBuildStrategy(
                            hasAlbumBuildData = collectionSurfaceHooks.hasAlbumBuildData(controller),
                            hasArtistBuildData = artistSurfaceHooks.hasBuildData(controller),
                            isPlaylistPageController =
                                collectionSurfaceHooks.isPlaylistController(controller),
                        )
                    },
                    controllerAppliedAliasFn = { controller, mediaId, alias ->
                        collectionSurfaceHooks.controllerAppliedAlias(
                            controller = controller,
                            mediaId = mediaId,
                            alias = alias,
                        )
                    },
                    controllerAlbumTrackMediaIdsFn = { controller ->
                        collectionSurfaceHooks.albumTrackMediaIds(controller)
                    },
                    requestControllerBuildFn = { controller, strategy ->
                        requestInAppLibraryControllerBuild(controller, strategy)
                    },
                    traceSequence = metadataTraceSequence,
                ),
            )
            dataBindingHooks = AppleDataBindingMetadataHooks(
                runtime = runtime,
                host = DefaultAppleDataBindingMetadataHost(
                    contentItemMediaIdFn = { contentItem ->
                        contentItemMetadataHooks.mediaId(contentItem)
                    },
                    onBeginBindingModelFn = { binding ->
                        artistSurfaceHooks.onBeginBindingModel(binding)
                    },
                    onBindingMediaIdChangedFn = { binding, previousMediaId, mediaId ->
                        artistSurfaceHooks.onBindingMediaIdChanged(binding, mediaId)
                    },
                    originalResolutionModeFn = { binding ->
                        artistSurfaceHooks.originalResolutionMode(binding)
                    },
                    shouldInvalidateAppliedAliasFn = { binding, mediaId, appliedAlias, pendingAlias, renderedTexts ->
                        val effectiveAlias = metadataResolutionCoordinator.effectiveAlias(mediaId)
                        if (effectiveAlias == null) {
                            false
                        } else {
                            artistSurfaceHooks.shouldInvalidateAppliedAlias(
                                binding = binding,
                                mediaId = mediaId,
                                appliedAlias = appliedAlias,
                                pendingAlias = pendingAlias,
                                effectiveAlias = effectiveAlias,
                                expectedTitle = dataBindingAliasValues(
                                    mediaId = mediaId,
                                    alias = effectiveAlias,
                                    binding = binding,
                                ).title,
                                renderedTexts = renderedTexts,
                            )
                        }
                    },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    aliasValuesFn = { mediaId, alias, binding ->
                        dataBindingAliasValues(mediaId, alias, binding)
                    },
                    isCurrentSurfaceMediaIdFn = { mediaId ->
                        isCurrentMetadataSurfaceMediaId(mediaId)
                    },
                    hasVisibleConsumerFn = { mediaId ->
                        hasVisibleInAppConsumer(mediaId)
                    },
                    isRefreshableMediaIdFn = { mediaId ->
                        isRefreshableInAppMediaId(mediaId)
                    },
                    enrichEntitiesForResolutionFn = { mediaIds ->
                        librarySurfaceHooks.enrichEntitiesForResolution(mediaIds)
                    },
                    markMetadataVisibleFn = { mediaIds ->
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    },
                    scheduleMetadataResolutionFn = { mediaIds, priority, originalResolutionMode ->
                        metadataResolutionCoordinator.schedule(
                            mediaIds = mediaIds,
                            priority = priority,
                            originalResolutionMode = originalResolutionMode,
                        )
                    },
                    isAppleLyricsRecyclerAdapterFn = { adapter ->
                        lyricsHooks.isAppleLyricsRecyclerAdapter(adapter)
                    },
                    isQueueAdapterFn = { adapter ->
                        queueMetadataHooks.isQueueAdapter(adapter)
                    },
                    isArtistProfileRecyclerAdapterFn = { adapter ->
                        artistSurfaceHooks.isRecyclerAdapter(adapter)
                    },
                    entityMediaIdFn = { value ->
                        librarySurfaceHooks.entityMediaId(value)
                    },
                    attributeBindingMediaIdFn = { value ->
                        librarySurfaceHooks.attributeBindingMediaId(value)
                    },
                    liveEntitiesFn = { mediaId ->
                        librarySurfaceHooks.liveEntities(mediaId)
                    },
                    registry = inAppMetadataRegistry,
                    traceSequence = metadataTraceSequence,
                ),
            )
            collectionSurfaceHooks = AppleCollectionSurfaceHooks(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                librarySurfaceHooks = librarySurfaceHooks,
                dataBindingHooks = dataBindingHooks,
                host = DefaultAppleCollectionSurfaceHost(
                    mediaApiEntityAttributesFn = { entity ->
                        mediaApiMetadataCoordinator.entityAttributes(entity)
                    },
                    mediaApiEntityCatalogIdFn = { entity, knownAttributes ->
                        mediaApiMetadataCoordinator.entityCatalogId(
                            entity,
                            knownAttributes,
                        )
                    },
                    mediaApiAttributeFn = { attributes, attribute ->
                        mediaApiMetadataCoordinator.attribute(attributes, attribute)
                    },
                    registerLibraryEntityFn = { mediaId, entity, kind, knownAttributes, requestResolution, retainEntityRef ->
                        mediaApiMetadataCoordinator.registerLibraryEntity(
                            mediaId = mediaId,
                            entity = entity,
                            kind = kind,
                            knownAttributes = knownAttributes,
                            requestResolution = requestResolution,
                            retainEntityRef = retainEntityRef,
                        )
                    },
                    markMetadataVisibleFn = { mediaIds ->
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    },
                    enrichLibraryEntitiesForResolutionFn = { mediaIds ->
                        mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(mediaIds)
                    },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    applyAliasToMetadataRefsFn = { mediaId, alias, notifyModelChange ->
                        applyAliasToInAppMetadataRefs(
                            mediaId = mediaId,
                            alias = alias,
                            forceRebind = true,
                            notifyModelChange = notifyModelChange,
                        )
                    },
                    shouldRequestOverrideFn = { mediaId ->
                        shouldRequestInAppMetadataOverride(mediaId)
                    },
                    scheduleMetadataResolutionFn = { mediaIds, priority, originalResolutionMode ->
                        metadataResolutionCoordinator.schedule(
                            mediaIds = mediaIds,
                            priority = priority,
                            originalResolutionMode = originalResolutionMode,
                        )
                    },
                    dataBindingAliasValuesFn = { mediaId, alias, binding ->
                        this@AppleMusicProviderOrchestrator.dataBindingAliasValues(
                            mediaId = mediaId,
                            alias = alias,
                            binding = binding,
                        )
                    },
                    sharedAssociatedArtistIdFn = { mediaId ->
                        metadataResolutionCoordinator.sharedAssociatedArtistId(mediaId)
                    },
                    onMetadataPageAttachedFn = { owner, recycler ->
                        this@AppleMusicProviderOrchestrator.onMetadataPageAttached(owner, recycler)
                    },
                    onMetadataPageDetachedFn = { owner ->
                        this@AppleMusicProviderOrchestrator.onMetadataPageDetached(owner)
                    },
                    handleArtistFinalBindingFn = { model, finalHolder, position ->
                        artistSurfaceHooks.handleFinalBinding(model, finalHolder, position)
                    },
                    logMetadataIdentityFn = { event, details ->
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    },
                    traceSequence = metadataTraceSequence,
                ),
            )
            artistSurfaceHooks = AppleArtistSurfaceHooks(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                librarySurfaceHooks = librarySurfaceHooks,
                dataBindingHooks = dataBindingHooks,
                host = DefaultAppleArtistSurfaceHost(
                    mediaApiEntityAttributesFn = { entity ->
                        mediaApiMetadataCoordinator.entityAttributes(entity)
                    },
                    mediaApiEntityCatalogIdFn = { entity, knownAttributes ->
                        mediaApiMetadataCoordinator.entityCatalogId(
                            entity,
                            knownAttributes,
                        )
                    },
                    mediaApiAttributeFn = { attributes, attribute ->
                        mediaApiMetadataCoordinator.attribute(attributes, attribute)
                    },
                    registerLibraryEntityFn = { mediaId, entity, kind, knownAttributes ->
                        mediaApiMetadataCoordinator.registerLibraryEntity(
                            mediaId = mediaId,
                            entity = entity,
                            kind = kind,
                            knownAttributes = knownAttributes,
                            requestResolution = false,
                            retainEntityRef = true,
                        )
                    },
                    enrichLibraryEntityFn = { mediaId, entity, kind, attributes ->
                        mediaApiMetadataCoordinator.enrichLibraryEntity(
                            mediaId,
                            entity,
                            kind,
                            attributes,
                        )
                    },
                    markMetadataVisibleFn = { mediaIds ->
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    },
                    enrichLibraryEntitiesForResolutionFn = { mediaIds ->
                        mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(mediaIds)
                    },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    applyAliasToMetadataRefsFn = { mediaId, alias, notifyModelChange ->
                        applyAliasToInAppMetadataRefs(
                            mediaId = mediaId,
                            alias = alias,
                            forceRebind = true,
                            notifyModelChange = notifyModelChange,
                        )
                    },
                    shouldRequestOverrideFn = { mediaId ->
                        shouldRequestInAppMetadataOverride(mediaId)
                    },
                    scheduleMetadataResolutionFn = { mediaIds, priority, originalResolutionMode ->
                        metadataResolutionCoordinator.schedule(
                            mediaIds = mediaIds,
                            priority = priority,
                            originalResolutionMode = originalResolutionMode,
                        )
                    },
                    activeMetadataPageOwnerFn = {
                        metadataSurfaceRuntime.activePageOwner()
                    },
                    knownArtistProfileCreditsFn = { artistId ->
                        mediaApiMetadataCoordinator.knownArtistProfileCredits(artistId)
                    },
                    onMetadataPageAttachedFn = { owner, recycler ->
                        this@AppleMusicProviderOrchestrator.onMetadataPageAttached(owner, recycler)
                    },
                    onMetadataPageDetachedFn = { owner ->
                        this@AppleMusicProviderOrchestrator.onMetadataPageDetached(owner)
                    },
                    logMetadataIdentityFn = { event, details ->
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    },
                    traceSequence = metadataTraceSequence,
                ),
            )
            metadataSurfaceRuntime = AppleMetadataSurfaceRuntime(
                runtime = runtime,
                host = DefaultAppleMetadataSurfaceHost(
                    catalogResolverFn = {
                        if (::internalCatalogResolver.isInitialized) {
                            internalCatalogResolver
                        } else {
                            null
                        }
                    },
                    overrideStore = metadataOverrideStore,
                    hasVisibleExactConsumerFn = { mediaId ->
                        dataBindingHooks.hasVisibleExactConsumer(mediaId)
                    },
                    hasGenericRecyclerConsumerFn = { mediaId ->
                        dataBindingHooks.hasGenericRecyclerRefs(mediaId)
                    },
                    detachControllerFn = { owner ->
                        librarySurfaceHooks.detachController(owner)
                    },
                    logMetadataIdentityFn = { event, details ->
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    },
                    describeViewFn = { view ->
                        visibleMetadataDiagnostics.viewDescription(view)
                    },
                ),
            )
            inAppArtworkContinuityHooks = AppleInAppArtworkContinuityHooks(
                runtime = runtime,
                host = DefaultAppleInAppArtworkContinuityHost(
                    onArtworkDelegateResolvedFn = { delegate, liveData, urls ->
                        listenNowHooks.onArtworkDelegateResolved(delegate, liveData, urls)
                    },
                    logMetadataIdentityFn = { event, details ->
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    },
                ),
            )
            actionSheetMetadataHooks = AppleActionSheetMetadataHooks(
                runtime = runtime,
                host = DefaultAppleActionSheetMetadataHost(
                    activePlaybackIdentityFn = {
                        activePlaybackMediaIdentity()
                    },
                    markMetadataVisibleFn = { mediaIds ->
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    },
                    rawContentItemValueFn = { contentItem, runtimeMember ->
                        this@AppleMusicProviderOrchestrator.rawContentItemValue(
                            contentItem,
                            runtimeMember,
                        )
                    },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    shouldRequestOverrideFn = { mediaId ->
                        shouldRequestInAppMetadataOverride(mediaId)
                    },
                    ensureOverrideFn = { mediaId, priority ->
                        metadataResolutionCoordinator.ensureOverride(mediaId = mediaId, priority = priority)
                    },
                    localizedTextFn = { field, alias ->
                        localizedVisibleText(field, alias)
                    },
                    logMetadataIdentityFn = { event, identity, details ->
                        if (identity == null) {
                            this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                                event = event,
                                details = details,
                            )
                        } else {
                            this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                                event = event,
                                identity = identity,
                                details = details,
                            )
                        }
                    },
                    contentItemArtistCacheKeysFn = { item, rawTitle ->
                        contentItemArtistCacheKeys(item, rawTitle)
                    },
                    mergePlaybackAssociatedArtistIdsFn = { mediaId, artistIds ->
                        metadataResolutionCoordinator.mergePlaybackAssociatedArtistIds(
                            mediaId = mediaId,
                            artistIds = artistIds,
                        )
                    },
                    contentItemCatalogLookupIdsFn = { item, mediaId ->
                        contentItemCatalogLookupIds(item, mediaId = mediaId)
                    },
                    overrideStore = metadataOverrideStore,
                    registry = inAppMetadataRegistry,
                ),
            )
            playbackItemConversionHooks = ApplePlaybackItemConversionHooks(
                runtime = runtime,
                host = DefaultApplePlaybackItemConversionHost(
                    containerKindFn = { containerItem ->
                        inAppContainerKind(containerItem)
                    },
                    metadataIdFn = { metadata, fallback ->
                        media3MetadataId(metadata, fallback)
                    },
                    activePlaybackIdentityFn = {
                        activePlaybackMediaIdentity()
                    },
                    metadataDetailsFn = { metadata ->
                        media3MetadataDetails(metadata)
                    },
                    logMetadataIdentityFn = { event, identity, details ->
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            identity = identity,
                            details = details,
                        )
                    },
                    markContainerNavigationItemFn = { containerItem, kind, mediaId ->
                        markInAppContainerNavigationItem(containerItem, kind, mediaId)
                    },
                    markMetadataVisibleFn = { mediaIds ->
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    },
                    registerContainerItemFn = { mediaId, containerItem, kind ->
                        registerInAppContainerItem(mediaId, containerItem, kind)
                    },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    applyAliasToContainerItemFn = { containerItem, kind, alias ->
                        this@AppleMusicProviderOrchestrator.applyAliasToInAppContainerItem(
                            containerItem,
                            kind,
                            alias,
                        )
                    },
                    contentItemMediaIdFn = { contentItem ->
                        contentItemMetadataHooks.mediaId(contentItem)
                    },
                    registerPlaybackItemFn = { mediaId, playbackItem ->
                        registerInAppPlaybackItem(mediaId, playbackItem)
                    },
                    applyAliasToPlaybackItemFn = { playbackItem, alias ->
                        this@AppleMusicProviderOrchestrator.applyAliasToInAppPlaybackItem(
                            playbackItem,
                            alias,
                        )
                    },
                    shouldRequestOverrideFn = { mediaId ->
                        shouldRequestInAppMetadataOverride(mediaId)
                    },
                    ensureOverrideFn = { mediaId, priority ->
                        metadataResolutionCoordinator.ensureOverride(mediaId = mediaId, priority = priority)
                    },
                ),
            )
            contentItemMetadataHooks = AppleContentItemMetadataHooks(
                runtime = runtime,
                host = DefaultAppleContentItemMetadataHost(
                    containerNavigationBindingFn = { contentItem ->
                        inAppContainerNavigationBinding(contentItem)
                    },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    registerContainerItemFn = { mediaId, contentItem, kind ->
                        registerInAppContainerItem(mediaId, contentItem, kind)
                    },
                    localizedEntityTypeFn = { contentItem ->
                        contentItemLocalizedEntityType(contentItem)
                    },
                    recordComposeMediaIdFn = { mediaId ->
                        librarySurfaceHooks.recordComposeMediaId(mediaId)
                    },
                    recordCurrentRecyclerMediaIdFn = { mediaId ->
                        this@AppleMusicProviderOrchestrator
                            .recordCurrentRecyclerMediaId(mediaId)
                    },
                    requestPriorityFn = { mediaId ->
                        requestPriorityForMediaId(mediaId)
                    },
                    shouldResolveFromGetterFn = { priority ->
                        shouldResolveMetadataFromGetter(priority)
                    },
                    registerPlaybackItemFn = { mediaId, playbackItem, notifyChange, analyzeMetadata ->
                        registerInAppPlaybackItem(
                            mediaId = mediaId,
                            playbackItem = playbackItem,
                            notifyChange = notifyChange,
                            analyzeMetadata = analyzeMetadata,
                        )
                    },
                    shouldRequestOverrideFn = { mediaId ->
                        shouldRequestInAppMetadataOverride(mediaId)
                    },
                    applyAliasToPlaybackItemFn = { playbackItem, alias, notifyChange ->
                        applyAliasToInAppPlaybackItem(
                            playbackItem = playbackItem,
                            alias = alias,
                            notifyChange = notifyChange,
                        )
                    },
                    metadataOverrideFn = { entityType, getter, alias, original ->
                        contentItemMetadataOverride(
                            entityType = entityType,
                            getter = getter,
                            alias = alias,
                            original = original,
                        )
                    },
                ),
            )
            mediaApiMetadataCoordinator = AppleMediaApiMetadataCoordinator(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                catalogResolver = internalCatalogResolver,
                librarySurfaceHooks = librarySurfaceHooks,
                artistSurfaceHooks = artistSurfaceHooks,
                host = DefaultAppleMediaApiMetadataHost(
                    contentItemMediaIdFn = { contentItem ->
                        contentItemMetadataHooks.mediaId(contentItem)
                    },
                    registerPlaybackItemFn = { mediaId, playbackItem, notifyChange, analyzeMetadata ->
                        registerInAppPlaybackItem(
                            mediaId = mediaId,
                            playbackItem = playbackItem,
                            notifyChange = notifyChange,
                            analyzeMetadata = analyzeMetadata,
                        )
                    },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    applyAliasToPlaybackItemFn = { playbackItem, alias, notifyChange ->
                        applyAliasToInAppPlaybackItem(playbackItem, alias, notifyChange)
                    },
                    shouldShareOriginalSongLanguageFn = { localizedTitle, localizedArtist, alias ->
                        metadataResolutionCoordinator.shouldShareOriginalSongLanguage(
                            localizedTitle = localizedTitle,
                            localizedArtist = localizedArtist,
                            alias = alias,
                        )
                    },
                    rememberOriginalLanguageForArtistFn = { mediaId, language ->
                        metadataResolutionCoordinator.rememberOriginalLanguageForArtist(
                            mediaId,
                            language,
                        )
                    },
                    hydrateSharedArtistOverridesFn = { mediaId ->
                        metadataResolutionCoordinator.hydrateSharedArtistOverrides(mediaId)
                    },
                    markMetadataVisibleFn = { mediaIds ->
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    },
                    applyAliasToMetadataRefsFn = { mediaId, alias, forceRebind, notifyModelChange ->
                        applyAliasToInAppMetadataRefs(
                            mediaId = mediaId,
                            alias = alias,
                            forceRebind = forceRebind,
                            notifyModelChange = notifyModelChange,
                        )
                    },
                    shouldRequestOverrideFn = { mediaId ->
                        shouldRequestInAppMetadataOverride(mediaId)
                    },
                    scheduleMetadataResolutionFn = { mediaIds, priority, originalResolutionMode ->
                        metadataResolutionCoordinator.schedule(
                            mediaIds = mediaIds,
                            priority = priority,
                            originalResolutionMode = originalResolutionMode,
                        )
                    },
                    configuredContentUiLanguageFn = {
                        this@AppleMusicProviderOrchestrator.configuredContentUiLanguage()
                    },
                    traceSequence = metadataTraceSequence,
                ),
            )
            metadataResolutionCoordinator = AppleInAppMetadataResolutionCoordinator(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                catalogResolver = internalCatalogResolver,
                host = DefaultAppleInAppMetadataResolutionHost(
                    currentPlaybackMetadataIdFn = {
                        playbackMetadataCoordinator.currentMetadataId()
                    },
                    configuredContentUiLanguageFn = { configuredContentUiLanguage() },
                    shouldOverrideAccountLanguageFn = { selection ->
                        shouldOverrideAccountLanguage(selection)
                    },
                    isRestoreOriginalEnabledFn = { isRestoreCjkOriginalMetadataEnabled() },
                    refreshRequestScopeFn = { metadataSurfaceRuntime.refreshRequestScope() },
                    enrichLibraryEntitiesForResolutionFn = { mediaIds ->
                        mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(mediaIds)
                    },
                    applyAliasToMetadataRefsFn = { mediaId, alias, forceRebind, notifyModelChange ->
                        applyAliasToInAppMetadataRefs(
                            mediaId = mediaId,
                            alias = alias,
                            forceRebind = forceRebind,
                            notifyModelChange = notifyModelChange,
                        )
                    },
                    applyPlaybackMetadataOverrideFn = {
                        mediaId, alias, forceInAppRebind, rememberLocalizedArtist,
                        originalMetadata, originalMetadataConfirmed, artistOnly,
                        propagateArtistEntity,
                        ->
                        applyPlaybackMetadataOverride(
                            mediaId = mediaId,
                            alias = alias,
                            forceInAppRebind = forceInAppRebind,
                            rememberLocalizedArtist = rememberLocalizedArtist,
                            originalMetadata = originalMetadata,
                            originalMetadataConfirmed = originalMetadataConfirmed,
                            artistOnly = artistOnly,
                            propagateArtistEntity = propagateArtistEntity,
                        )
                    },
                    logMetadataIdentityFn = { event, details ->
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    },
                    traceSequence = metadataTraceSequence,
                ),
            )
            frameworkMetadataHooks = AppleFrameworkMetadataHooks(
                runtime = runtime,
                preferences = { contentUiLanguagePrefs },
                metadataStore = metadataOverrideStore,
                effectiveMetadataAlias = metadataResolutionCoordinator::effectiveAlias,
                activePlaybackIdentity = ::activePlaybackMediaIdentity,
                logMetadataIdentity = { event, identity, details ->
                    logMetadataIdentity(event, identity, details)
                },
            )
            visibleMetadataDiagnostics = AppleVisibleMetadataDiagnostics(
                runtime = runtime,
                host = DefaultAppleVisibleMetadataDiagnosticsHost(
                    activePlaybackIdentityFn = { activePlaybackMediaIdentity() },
                    effectiveAliasFn = { mediaId ->
                        metadataResolutionCoordinator.effectiveAlias(mediaId)
                    },
                    frameworkMetadataFn = { mediaId ->
                        frameworkMetadataHooks.originalMetadata(mediaId)
                    },
                    overrideStore = metadataOverrideStore,
                    registry = inAppMetadataRegistry,
                    traceSequence = metadataTraceSequence,
                ),
            )
            playbackHooks = ApplePlaybackHooks(
                runtime = runtime,
                isAodLyricsEnabled = ::isAodLyricsEnabled,
                currentMetadataId = playbackMetadataCoordinator::currentMetadataId,
                currentLyricsSongId = lyricsHooks::currentSongId,
                queueItemMediaId = playbackMetadataCoordinator::queueItemMediaId,
                refreshCurrentQueueItem = playbackMetadataCoordinator::refreshCurrentQueueItem,
                isVolumeBalanceEnabled = {
                    contentUiLanguagePrefs?.getBoolean(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_VOLUME_BALANCE,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_VOLUME_BALANCE,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_VOLUME_BALANCE
                },
            )
            atmosphereVolumeDiagnostics = AppleAtmosVolumeDiagnostics(runtime)
            playbackMetadataHooks = ApplePlaybackMetadataHooks(
                runtime = runtime,
                playbackHooks = { playbackHooks },
                metadataCoordinator = playbackMetadataCoordinator,
            )
            media3MetadataCoordinator = AppleMedia3MetadataCoordinator(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                resolutionCoordinator = metadataResolutionCoordinator,
                frameworkMetadataHooks = frameworkMetadataHooks,
                queueMetadataHooks = queueMetadataHooks,
                playbackMetadataCoordinator = playbackMetadataCoordinator,
                traceSequence = metadataTraceSequence,
            )
            inAppMetadataApplier = AppleInAppMetadataApplier(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                registry = inAppMetadataRegistry,
                contentItemMetadataHooks = contentItemMetadataHooks,
                librarySurfaceHooks = librarySurfaceHooks,
                collectionSurfaceHooks = collectionSurfaceHooks,
                artistSurfaceHooks = artistSurfaceHooks,
                dataBindingHooks = dataBindingHooks,
                listenNowHooks = listenNowHooks,
                queueMetadataHooks = queueMetadataHooks,
                traceSequence = metadataTraceSequence,
                logMetadataIdentity = { event, details ->
                    logMetadataIdentity(event = event, details = details)
                },
            )
            metadataRegistrationCoordinator = AppleInAppMetadataRegistrationCoordinator(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                registry = inAppMetadataRegistry,
                resolutionCoordinator = metadataResolutionCoordinator,
                catalogResolver = internalCatalogResolver,
                contentItemMetadataHooks = contentItemMetadataHooks,
                metadataApplier = inAppMetadataApplier,
                surfaceRuntime = metadataSurfaceRuntime,
                dataBindingHooks = dataBindingHooks,
                configuredContentUiLanguage = ::configuredContentUiLanguage,
            )
            metadataOverrideApplicationCoordinator =
                AppleMetadataOverrideApplicationCoordinator(
                    runtime = runtime,
                    metadataStore = metadataOverrideStore,
                    registry = inAppMetadataRegistry,
                    resolutionCoordinator = metadataResolutionCoordinator,
                    catalogResolver = internalCatalogResolver,
                    surfaceRuntime = metadataSurfaceRuntime,
                    metadataApplier = inAppMetadataApplier,
                    librarySurfaceHooks = librarySurfaceHooks,
                    dataBindingHooks = dataBindingHooks,
                    listenNowHooks = listenNowHooks,
                    actionSheetMetadataHooks = actionSheetMetadataHooks,
                    playbackMetadataCoordinator = playbackMetadataCoordinator,
                    frameworkMetadataHooks = frameworkMetadataHooks,
                    visibleMetadataDiagnostics = visibleMetadataDiagnostics,
                    media3MetadataCoordinator = media3MetadataCoordinator,
                    configuredContentUiLanguage = ::configuredContentUiLanguage,
                    traceSequence = metadataTraceSequence,
                )
            metadataConfigurationDispatcher = AppleMetadataConfigurationDispatcher(
                clearStateOwners = listOf(
                    metadataOverrideStore::onConfigurationChanged,
                    librarySurfaceHooks::clearConfigurationState,
                    dataBindingHooks::clearConfigurationState,
                    listenNowHooks::clearMetadataState,
                    {
                        inAppMetadataApplier.clearCallbackState()
                        metadataResolutionCoordinator.clearDeferredResolutions()
                    },
                ),
                restoreCapturedModels = inAppMetadataApplier::restoreCapturedModels,
                scheduleConsumerRefresh = {
                    mainHandler.post {
                        frameworkMetadataHooks.restoreMediaSessionMetadata()
                        frameworkMetadataHooks.restoreMediaSessionQueue()
                        inAppMetadataApplier.refreshMetadataCallbacks()
                        playbackHooks.activePlayer()?.let { mediaPlayer ->
                            playbackMetadataCoordinator.refreshCurrentQueueItem(
                                mediaPlayer,
                                "Apple Music 语言覆盖设置变更",
                            )
                        }
                    }
                },
            )
            initializeContentUiLanguage()
            playbackHooks.initializeScreenStateMonitor()
            initProvider()
            startHooks()
            ProviderLogger.info("Apple Music 内置歌词提供器初始化完成")
        }.onFailure {
            initialized.set(false)
            ProviderLogger.error("Apple Music 内置歌词提供器初始化失败", it)
        }
    }


}
