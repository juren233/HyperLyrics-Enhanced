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
    private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    private val initialized = AtomicBoolean(false)
    private lateinit var runtime: AppleMusicProviderRuntime
    private val application: Application
        get() = runtime.application
    private val classLoader: ClassLoader
        get() = runtime.classLoader
    private val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    private val module: XposedModule
        get() = runtime.module
    private val hookRegistrar
        get() = runtime.hookRegistrar
    private lateinit var contentLocalizationHooks: AppleContentLocalizationHooks
    private lateinit var debugNetworkHooks: AppleDebugNetworkHooks
    private lateinit var frameworkMetadataHooks: AppleFrameworkMetadataHooks
    private lateinit var lyricsHooks: AppleLyricsSupplementHooks
    private lateinit var onlineSourceMenuHooks: AppleOnlineSourceMenuHooks
    private lateinit var missingLyricsHooks: AppleMissingLyricsHooks
    private lateinit var playbackHooks: ApplePlaybackHooks
    private lateinit var queueMetadataHooks: AppleQueueMetadataHooks
    private lateinit var listenNowHooks: AppleListenNowHooks
    private lateinit var librarySurfaceHooks: AppleLibrarySurfaceHooks
    private lateinit var dataBindingHooks: AppleDataBindingMetadataHooks
    private lateinit var collectionSurfaceHooks: AppleCollectionSurfaceHooks
    private lateinit var artistSurfaceHooks: AppleArtistSurfaceHooks
    private lateinit var inAppArtworkContinuityHooks: AppleInAppArtworkContinuityHooks
    private lateinit var actionSheetMetadataHooks: AppleActionSheetMetadataHooks
    private lateinit var playbackItemConversionHooks: ApplePlaybackItemConversionHooks
    private lateinit var metadataSurfaceRuntime: AppleMetadataSurfaceRuntime
    private lateinit var visibleMetadataDiagnostics: AppleVisibleMetadataDiagnostics
    private lateinit var metadataConfigurationDispatcher: AppleMetadataConfigurationDispatcher
    private lateinit var playbackMetadataCoordinator: ApplePlaybackMetadataCoordinator
    private lateinit var playbackMetadataHooks: ApplePlaybackMetadataHooks
    private lateinit var media3MetadataCoordinator: AppleMedia3MetadataCoordinator
    private lateinit var contentItemMetadataHooks: AppleContentItemMetadataHooks
    private lateinit var mediaApiMetadataCoordinator: AppleMediaApiMetadataCoordinator
    private lateinit var metadataResolutionCoordinator: AppleInAppMetadataResolutionCoordinator
    private var directPlayer: AppleDirectPlayer? = null
    private lateinit var lyricRequester: LyricRequester
    private lateinit var internalCatalogResolver: AppleInternalCatalogResolver
    private var contentUiLanguagePrefs: android.content.SharedPreferences? = null
    private val metadataOverrideStore = AppleMetadataOverrideStore()
    private val inAppMetadataRegistry = AppleInAppMetadataRegistry()
    private lateinit var inAppMetadataApplier: AppleInAppMetadataApplier
    private lateinit var metadataOverrideApplicationCoordinator:
        AppleMetadataOverrideApplicationCoordinator
    private lateinit var metadataRegistrationCoordinator:
        AppleInAppMetadataRegistrationCoordinator
    private val mainHandler: Handler
        get() = runtime.mainHandler
    private val metadataTraceSequence = AtomicLong(0L)

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
                host = object : ApplePlaybackMetadataCoordinatorHost {
                    override fun activePlayer(): Any? =
                        if (::playbackHooks.isInitialized) playbackHooks.activePlayer() else null

                    override fun configuredContentUiLanguage(): Int =
                        this@AppleMusicProviderOrchestrator.configuredContentUiLanguage()

                    override fun shouldOverrideAccountLanguage(selection: Int): Boolean =
                        this@AppleMusicProviderOrchestrator.shouldOverrideAccountLanguage(selection)

                    override fun shouldRestoreCjkOriginalMetadata(
                        metadata: MediaMetadataCache.Metadata,
                    ): Boolean = this@AppleMusicProviderOrchestrator
                        .shouldRestoreCjkOriginalMetadata(metadata)

                    override fun ensureContentItemMetadataHooks(contentItemClass: Class<*>) {
                        contentItemMetadataHooks.ensureHooks(contentItemClass)
                    }

                    override fun setMetadataPlaybackMediaId(mediaId: String) {
                        this@AppleMusicProviderOrchestrator.setMetadataPlaybackMediaId(mediaId)
                    }

                    override fun onCurrentPlaybackItem(
                        mediaId: String,
                        playbackItem: Any,
                        queueId: Long,
                    ) {
                        if (::missingLyricsHooks.isInitialized) {
                            missingLyricsHooks.onCurrentPlaybackItem(
                                contentSongId = mediaId,
                                item = playbackItem,
                                queueId = queueId,
                            )
                        }
                    }

                    override fun effectiveMetadataAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun applyPlaybackMetadataOverride(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                        rememberLocalizedArtist: Boolean,
                        originalMetadata: Boolean,
                        originalMetadataConfirmed: Boolean,
                    ) {
                        this@AppleMusicProviderOrchestrator.applyPlaybackMetadataOverride(
                            mediaId = mediaId,
                            alias = alias,
                            rememberLocalizedArtist = rememberLocalizedArtist,
                            originalMetadata = originalMetadata,
                            originalMetadataConfirmed = originalMetadataConfirmed,
                        )
                    }

                    override fun logMetadataIdentity(event: String, details: String) {
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    }

                    override fun validatedOriginalSongAlias(
                        alias: AppleInternalCatalogResolver.Alias?,
                        localizedTitle: String?,
                        localizedArtist: String?,
                    ): AppleInternalCatalogResolver.Alias? =
                        io.github.proify.lyricon.amprovider.xposed.validatedOriginalSongAlias(
                            alias = alias,
                            localizedTitle = localizedTitle,
                            localizedArtist = localizedArtist,
                        )

                    override fun shouldShareOriginalSongLanguage(
                        localizedTitle: String?,
                        localizedArtist: String?,
                        alias: AppleInternalCatalogResolver.Alias?,
                    ): Boolean = metadataResolutionCoordinator.shouldShareOriginalSongLanguage(
                            localizedTitle = localizedTitle,
                            localizedArtist = localizedArtist,
                            alias = alias,
                        )

                    override fun rememberOriginalLanguageForArtist(
                        mediaId: String,
                        language: String,
                    ) {
                        metadataResolutionCoordinator.rememberOriginalLanguageForArtist(
                            mediaId,
                            language,
                        )
                    }

                    override fun isRestoreOriginalMetadataEnabled(): Boolean =
                        isRestoreCjkOriginalMetadataEnabled()
                },
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
                refreshVisibleSupplementTranslation = { songId ->
                    lyricsHooks.refreshVisibleMissingLyricsTranslation(songId)
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
            )
            queueMetadataHooks = AppleQueueMetadataHooks(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                host = object : AppleQueueMetadataHost {
                    override fun activePlaybackIdentity(): ActivePlaybackMediaIdentity =
                        this@AppleMusicProviderOrchestrator.activePlaybackMediaIdentity()

                    override fun logMetadataIdentity(
                        event: String,
                        identity: ActivePlaybackMediaIdentity,
                        details: String,
                    ) {
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(event, identity, details)
                    }

                    override fun media3MetadataId(
                        metadata: Any,
                        fallback: String?,
                        trustedFallback: Boolean,
                    ): String? = this@AppleMusicProviderOrchestrator.media3MetadataId(
                        metadata = metadata,
                        fallback = fallback,
                        trustedFallback = trustedFallback,
                    )

                    override fun media3MetadataDetails(metadata: Any): String =
                        this@AppleMusicProviderOrchestrator.media3MetadataDetails(metadata)

                    override fun registerMetadata(
                        mediaId: String,
                        metadata: Any,
                        requestResolution: Boolean,
                        preBind: Boolean,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                    ) {
                        registerInAppMetadata(
                            mediaId = mediaId,
                            metadata = metadata,
                            requestResolution = requestResolution,
                            preBind = preBind,
                            priority = priority,
                        )
                    }

                    override fun markPlaybackItemHistory(playbackItem: Any) {
                        inAppMetadataRegistry.markPlaybackItemContract(
                            playbackItem,
                            InAppPlaybackItemContract.HISTORY,
                        )
                    }

                    override fun registerPlaybackItem(
                        mediaId: String,
                        playbackItem: Any,
                        notifyChange: Boolean,
                        analyzeMetadata: Boolean,
                    ) {
                        registerInAppPlaybackItem(
                            mediaId = mediaId,
                            playbackItem = playbackItem,
                            notifyChange = notifyChange,
                            analyzeMetadata = analyzeMetadata,
                        )
                    }

                    override fun contentItemMediaId(
                        contentItem: Any,
                        refresh: Boolean,
                    ): String? = contentItemMetadataHooks.mediaId(contentItem, refresh)

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun applyAliasToPlaybackItem(
                        playbackItem: Any,
                        alias: AppleInternalCatalogResolver.Alias,
                        notifyChange: Boolean,
                    ) {
                        applyAliasToInAppPlaybackItem(playbackItem, alias, notifyChange)
                    }

                    override fun shouldRequestOverride(mediaId: String): Boolean =
                        shouldRequestInAppMetadataOverride(mediaId)

                    override fun ensureOverride(
                        mediaId: String,
                        preBind: Boolean,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                    ) {
                        metadataResolutionCoordinator.ensureOverride(
                            mediaId = mediaId,
                            preBind = preBind,
                            priority = priority,
                        )
                    }

                    override fun ensureOverrides(
                        mediaIds: Collection<String>,
                        preBind: Boolean,
                        originalResolutionLimit: Int,
                    ) {
                        metadataResolutionCoordinator.ensureOverrides(
                            mediaIds = mediaIds,
                            preBind = preBind,
                            originalResolutionLimit = originalResolutionLimit,
                        )
                    }

                    override fun readPlaybackItemValue(
                        playbackItem: Any,
                        field: InAppPlaybackItemField,
                        contract: InAppPlaybackItemContract,
                    ): String? = this@AppleMusicProviderOrchestrator.readInAppPlaybackItemValue(
                        playbackItem = playbackItem,
                        field = field,
                        contract = contract,
                    )

                    override fun markMetadataVisible(mediaIds: Collection<String>) {
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    }

                    override fun isCurrentMetadataSurfaceMediaId(mediaId: String): Boolean =
                        this@AppleMusicProviderOrchestrator.isCurrentMetadataSurfaceMediaId(mediaId)

                    override fun hasLivePlaybackItem(mediaId: String): Boolean =
                        inAppMetadataRegistry.hasLivePlaybackItem(mediaId)
                },
            )
            listenNowHooks = AppleListenNowHooks(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                catalogResolver = internalCatalogResolver,
                host = object : AppleListenNowHost {
                    override fun mediaApiEntityAttributes(entity: Any): Any? =
                        mediaApiMetadataCoordinator.entityAttributes(entity)

                    override fun mediaApiEntityCatalogId(
                        entity: Any,
                        knownAttributes: Any?,
                    ): String? = mediaApiMetadataCoordinator.entityCatalogId(
                        entity,
                        knownAttributes,
                    )

                    override fun registerLibraryEntity(
                        mediaId: String,
                        entity: Any,
                        kind: InAppLibraryEntityKind,
                        knownAttributes: Any?,
                        requestResolution: Boolean,
                        retainEntityRef: Boolean,
                    ) {
                        librarySurfaceHooks.registerEntity(
                            mediaId = mediaId,
                            entity = entity,
                            kind = kind,
                            knownAttributes = knownAttributes,
                            requestResolution = requestResolution,
                            retainEntityRef = retainEntityRef,
                        )
                    }

                    override fun enrichLibraryEntity(
                        mediaId: String,
                        entity: Any,
                        kind: InAppLibraryEntityKind,
                        attributes: Any,
                    ) {
                        librarySurfaceHooks.enrichEntity(mediaId, entity, kind, attributes)
                    }

                    override fun isRestoreOriginalMetadataEnabled(): Boolean =
                        isRestoreCjkOriginalMetadataEnabled()

                    override fun shouldRetryOriginalMetadataCacheProbe(
                        mediaId: String,
                    ): Boolean = this@AppleMusicProviderOrchestrator
                        .shouldRetryOriginalMetadataCacheProbe(mediaId)

                    override fun rememberOriginalMetadataOverride(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                        confirmed: Boolean,
                    ) {
                        metadataOverrideApplicationCoordinator.rememberOriginalMetadataOverride(
                            mediaId = mediaId,
                            alias = alias,
                            confirmed = confirmed,
                        )
                    }

                    override fun rememberOriginalLanguageForArtist(
                        mediaId: String,
                        language: String,
                    ) {
                        metadataResolutionCoordinator.rememberOriginalLanguageForArtist(
                            mediaId,
                            language,
                        )
                    }

                    override fun resolveCachedOriginalEntityForInApp(
                        mediaId: String,
                        entityType: AppleInternalCatalogResolver.LocalizedEntityType,
                        preBind: Boolean,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                    ) {
                        metadataResolutionCoordinator.resolveCachedOriginalEntity(
                            mediaId = mediaId,
                            entityType = entityType,
                            preBind = preBind,
                            priority = priority,
                        )
                    }

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun applyAliasToLibraryEntity(
                        entity: Any,
                        kind: InAppLibraryEntityKind,
                        alias: AppleInternalCatalogResolver.Alias,
                    ): Boolean = librarySurfaceHooks.applyAliasToEntity(
                        entity = entity,
                        kind = kind,
                        alias = alias,
                    )

                    override fun shouldRequestOverride(mediaId: String): Boolean =
                        shouldRequestInAppMetadataOverride(mediaId)

                    override fun markMetadataVisible(mediaIds: Collection<String>) {
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    }

                    override fun scheduleMetadataResolution(
                        mediaIds: Collection<String>,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                        originalResolutionMode: InAppOriginalResolutionMode,
                    ) {
                        metadataResolutionCoordinator.schedule(
                            mediaIds = mediaIds,
                            priority = priority,
                            originalResolutionMode = originalResolutionMode,
                        )
                    }

                    override fun nextMetadataTraceSequence(): Long =
                        metadataTraceSequence.incrementAndGet()

                    override fun logMetadataIdentity(event: String, details: String) {
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    }

                    override fun isDataBindingInstance(candidate: Any): Boolean =
                        dataBindingHooks.isBindingInstance(candidate)

                    override fun dataBindingFromHolder(argument: Any?): Any? =
                        dataBindingHooks.bindingFromHolder(argument)

                    override fun beginDataBindingModelBind(binding: Any) {
                        dataBindingHooks.beginModelBind(binding)
                    }

                    override fun clearDataBindingMediaId(binding: Any) {
                        dataBindingHooks.clearMediaId(binding)
                    }

                    override fun dataBindingGeneration(binding: Any): Long =
                        dataBindingHooks.generation(binding)

                    override fun captureDataBinding(binding: Any) {
                        dataBindingHooks.capture(binding)
                    }

                    override fun registerDataBinding(mediaId: String, binding: Any) {
                        dataBindingHooks.register(mediaId, binding)
                    }

                    override fun aliasValues(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                        binding: Any?,
                    ): DataBindingAliasValues = dataBindingAliasValues(
                        mediaId = mediaId,
                        alias = alias,
                        binding = binding,
                    )

                    override fun renderedTexts(binding: Any): List<String> =
                        dataBindingHooks.renderedTexts(binding)

                    override fun appliedAlias(binding: Any): AppliedMetadataAlias? =
                        dataBindingHooks.appliedAlias(binding)

                    override fun rememberAppliedAlias(
                        binding: Any,
                        alias: AppliedMetadataAlias,
                    ) {
                        dataBindingHooks.rememberAppliedAlias(binding, alias)
                    }

                    override fun applyAliasVariables(
                        binding: Any,
                        values: DataBindingAliasValues,
                    ): DataBindingVariableApplyResult =
                        dataBindingHooks.applyAliasVariables(binding, values)

                    override fun invalidateDataBinding(binding: Any) {
                        dataBindingHooks.invalidate(binding)
                    }

                    override fun executePendingDataBindings(binding: Any) {
                        dataBindingHooks.executePending(binding)
                    }
                },
            )
            librarySurfaceHooks = AppleLibrarySurfaceHooks(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                host = object : AppleLibrarySurfaceHost {
                    override fun contentItemMediaId(source: Any): String? =
                        contentItemMetadataHooks.mediaId(source)

                    override fun primeLibrarySource(source: Any?) {
                        mediaApiMetadataCoordinator.primeLibrarySource(source)
                    }

                    override fun mediaApiEntityAttributes(entity: Any): Any? =
                        mediaApiMetadataCoordinator.entityAttributes(entity)

                    override fun mediaApiEntityCatalogId(
                        entity: Any,
                        knownAttributes: Any?,
                    ): String? = mediaApiMetadataCoordinator.entityCatalogId(
                        entity,
                        knownAttributes,
                    )

                    override fun mediaApiEntityLookupIds(
                        entity: Any,
                        knownAttributes: Any?,
                    ): Set<String> = mediaApiMetadataCoordinator.entityLookupIds(
                        entity,
                        knownAttributes,
                    )

                    override fun mergePlaybackAccountMetadata(
                        mediaId: String,
                        title: String?,
                        artist: String?,
                    ) {
                        this@AppleMusicProviderOrchestrator.mergePlaybackAccountMetadata(
                            mediaId = mediaId,
                            title = title,
                            artist = artist,
                            reconcileArtistAssociations = false,
                        )
                    }

                    override fun requestPriorityForMediaId(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.RequestPriority =
                        this@AppleMusicProviderOrchestrator.requestPriorityForMediaId(mediaId)

                    override fun enrichEntityAssociations(
                        mediaId: String,
                        entity: Any,
                        kind: InAppLibraryEntityKind,
                        attributes: Any,
                        originalName: String?,
                        originalArtist: String?,
                        originalAlbum: String?,
                    ) {
                        mediaApiMetadataCoordinator.enrichLibraryEntityAssociations(
                            mediaId = mediaId,
                            entity = entity,
                            kind = kind,
                            attributes = attributes,
                            originalName = originalName,
                            originalArtist = originalArtist,
                            originalAlbum = originalAlbum,
                        )
                    }

                    override fun recordCurrentRecyclerMediaId(mediaId: String) {
                        this@AppleMusicProviderOrchestrator.recordCurrentRecyclerMediaId(mediaId)
                    }

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun normalizeMediaIds(mediaIds: Collection<String>): List<String> =
                        normalizedRecyclerBindingMediaIds(mediaIds).toList()

                    override fun markMetadataVisible(mediaIds: Collection<String>) {
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    }

                    override fun applyAliasToMetadataRefs(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                    ) {
                        applyAliasToInAppMetadataRefs(
                            mediaId = mediaId,
                            alias = alias,
                            forceRebind = true,
                            notifyModelChange = true,
                        )
                    }

                    override fun scheduleMetadataResolution(
                        mediaIds: Collection<String>,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                    ) {
                        metadataResolutionCoordinator.schedule(mediaIds, priority)
                    }

                    override fun isRefreshableMediaId(mediaId: String): Boolean =
                        isRefreshableInAppMediaId(mediaId)

                    override fun nextMetadataTraceSequence(): Long =
                        metadataTraceSequence.incrementAndGet()

                    override fun logMetadataIdentity(event: String, details: String) {
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(event = event, details = details)
                    }

                    override fun debugStackSummary(): String =
                        visibleMetadataDiagnostics.stackSummary()

                    override fun controllerBuildStrategy(
                        controller: Any,
                    ): InAppLibraryControllerBuildStrategy =
                        inAppLibraryControllerBuildStrategy(
                            hasAlbumBuildData = collectionSurfaceHooks.hasAlbumBuildData(controller),
                            hasArtistBuildData = artistSurfaceHooks.hasBuildData(controller),
                            isPlaylistPageController =
                                collectionSurfaceHooks.isPlaylistController(controller),
                        )

                    override fun controllerAppliedAlias(
                        controller: Any,
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                    ): AppliedMetadataAlias = collectionSurfaceHooks.controllerAppliedAlias(
                        controller = controller,
                        mediaId = mediaId,
                        alias = alias,
                    )

                    override fun controllerAlbumTrackMediaIds(
                        controller: Any,
                    ): Collection<String> =
                        collectionSurfaceHooks.albumTrackMediaIds(controller)

                    override fun requestControllerBuild(
                        controller: Any,
                        strategy: InAppLibraryControllerBuildStrategy,
                    ) {
                        requestInAppLibraryControllerBuild(controller, strategy)
                    }

                },
            )
            dataBindingHooks = AppleDataBindingMetadataHooks(
                runtime = runtime,
                host = object : AppleDataBindingMetadataHost {
                    override fun contentItemMediaId(contentItem: Any): String? =
                        contentItemMetadataHooks.mediaId(contentItem)

                    override fun bindingCandidateMediaId(value: Any): String? =
                        inAppMetadataRegistry.metadataId(value)
                            ?: inAppMetadataRegistry.playbackItemId(value)
                            ?: librarySurfaceHooks.entityMediaId(value)
                            ?: librarySurfaceHooks.attributeBindingMediaId(value)

                    override fun onBeginBindingModel(binding: Any) {
                        artistSurfaceHooks.onBeginBindingModel(binding)
                    }

                    override fun onBindingMediaIdChanged(
                        binding: Any,
                        previousMediaId: String?,
                        mediaId: String,
                    ) {
                        artistSurfaceHooks.onBindingMediaIdChanged(binding, mediaId)
                    }

                    override fun originalResolutionMode(
                        binding: Any,
                    ): InAppOriginalResolutionMode =
                        artistSurfaceHooks.originalResolutionMode(binding)

                    override fun shouldInvalidateAppliedAlias(
                        binding: Any,
                        mediaId: String,
                        appliedAlias: AppliedMetadataAlias,
                        pendingAlias: AppliedMetadataAlias?,
                        renderedTexts: Collection<String>,
                    ): Boolean {
                        val effectiveAlias = metadataResolutionCoordinator.effectiveAlias(mediaId)
                            ?: return false
                        return artistSurfaceHooks.shouldInvalidateAppliedAlias(
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

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun aliasValues(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                        binding: Any?,
                    ): DataBindingAliasValues = dataBindingAliasValues(mediaId, alias, binding)

                    override fun isCurrentSurfaceMediaId(mediaId: String): Boolean =
                        isCurrentMetadataSurfaceMediaId(mediaId)

                    override fun hasVisibleConsumer(mediaId: String): Boolean =
                        hasVisibleInAppConsumer(mediaId)

                    override fun isRefreshableMediaId(mediaId: String): Boolean =
                        isRefreshableInAppMediaId(mediaId)

                    override fun boundModelCandidates(mediaId: String): List<Any> =
                        inAppMetadataRegistry.livePlaybackItems(mediaId) +
                            librarySurfaceHooks.liveEntities(mediaId)

                    override fun enrichEntitiesForResolution(mediaIds: Collection<String>) {
                        librarySurfaceHooks.enrichEntitiesForResolution(mediaIds)
                    }

                    override fun markMetadataVisible(mediaIds: Collection<String>) {
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    }

                    override fun scheduleMetadataResolution(
                        mediaIds: Collection<String>,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                        originalResolutionMode: InAppOriginalResolutionMode,
                    ) {
                        metadataResolutionCoordinator.schedule(
                            mediaIds = mediaIds,
                            priority = priority,
                            originalResolutionMode = originalResolutionMode,
                        )
                    }

                    override fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean =
                        lyricsHooks.isAppleLyricsRecyclerAdapter(adapter)

                    override fun isQueueAdapter(adapter: Any): Boolean =
                        queueMetadataHooks.isQueueAdapter(adapter)

                    override fun isArtistProfileRecyclerAdapter(adapter: Any): Boolean =
                        artistSurfaceHooks.isRecyclerAdapter(adapter)

                    override fun nextMetadataTraceSequence(): Long =
                        metadataTraceSequence.incrementAndGet()
                },
            )
            collectionSurfaceHooks = AppleCollectionSurfaceHooks(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                librarySurfaceHooks = librarySurfaceHooks,
                dataBindingHooks = dataBindingHooks,
                host = object : AppleCollectionSurfaceHost {
                    override fun mediaApiEntityAttributes(entity: Any): Any? =
                        mediaApiMetadataCoordinator.entityAttributes(entity)

                    override fun mediaApiEntityCatalogId(
                        entity: Any,
                        knownAttributes: Any?,
                    ): String? = mediaApiMetadataCoordinator.entityCatalogId(
                        entity,
                        knownAttributes,
                    )

                    override fun mediaApiAttribute(
                        attributes: Any,
                        attribute: AppleMediaApiTextAttribute,
                    ): String? = mediaApiMetadataCoordinator.attribute(attributes, attribute)

                    override fun registerLibraryEntity(
                        mediaId: String,
                        entity: Any,
                        kind: InAppLibraryEntityKind,
                        knownAttributes: Any?,
                        requestResolution: Boolean,
                        retainEntityRef: Boolean,
                    ) {
                        mediaApiMetadataCoordinator.registerLibraryEntity(
                            mediaId = mediaId,
                            entity = entity,
                            kind = kind,
                            knownAttributes = knownAttributes,
                            requestResolution = requestResolution,
                            retainEntityRef = retainEntityRef,
                        )
                    }

                    override fun markMetadataVisible(mediaIds: Collection<String>) {
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    }

                    override fun enrichLibraryEntitiesForResolution(
                        mediaIds: Collection<String>,
                    ) {
                        mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(mediaIds)
                    }

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun applyAliasToMetadataRefs(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                        notifyModelChange: Boolean,
                    ) {
                        applyAliasToInAppMetadataRefs(
                            mediaId = mediaId,
                            alias = alias,
                            forceRebind = true,
                            notifyModelChange = notifyModelChange,
                        )
                    }

                    override fun shouldRequestOverride(mediaId: String): Boolean =
                        shouldRequestInAppMetadataOverride(mediaId)

                    override fun scheduleMetadataResolution(
                        mediaIds: Collection<String>,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                        originalResolutionMode: InAppOriginalResolutionMode,
                    ) {
                        metadataResolutionCoordinator.schedule(
                            mediaIds = mediaIds,
                            priority = priority,
                            originalResolutionMode = originalResolutionMode,
                        )
                    }

                    override fun dataBindingAliasValues(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                        binding: Any?,
                    ): DataBindingAliasValues = this@AppleMusicProviderOrchestrator.dataBindingAliasValues(
                        mediaId = mediaId,
                        alias = alias,
                        binding = binding,
                    )

                    override fun sharedAssociatedArtistId(mediaId: String): String? =
                        metadataResolutionCoordinator.sharedAssociatedArtistId(mediaId)

                    override fun onMetadataPageAttached(owner: Any, recycler: RecyclerView) {
                        this@AppleMusicProviderOrchestrator.onMetadataPageAttached(owner, recycler)
                    }

                    override fun onMetadataPageDetached(owner: Any) {
                        this@AppleMusicProviderOrchestrator.onMetadataPageDetached(owner)
                    }

                    override fun handleArtistFinalBinding(
                        model: Any,
                        finalHolder: Any?,
                        position: Int?,
                    ) {
                        artistSurfaceHooks.handleFinalBinding(model, finalHolder, position)
                    }

                    override fun nextMetadataTraceSequence(): Long =
                        metadataTraceSequence.incrementAndGet()

                    override fun logMetadataIdentity(event: String, details: String) {
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    }
                },
            )
            artistSurfaceHooks = AppleArtistSurfaceHooks(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                librarySurfaceHooks = librarySurfaceHooks,
                dataBindingHooks = dataBindingHooks,
                host = object : AppleArtistSurfaceHost {
                    override fun mediaApiEntityAttributes(entity: Any): Any? =
                        mediaApiMetadataCoordinator.entityAttributes(entity)

                    override fun mediaApiEntityCatalogId(
                        entity: Any,
                        knownAttributes: Any?,
                    ): String? = mediaApiMetadataCoordinator.entityCatalogId(
                        entity,
                        knownAttributes,
                    )

                    override fun mediaApiAttribute(
                        attributes: Any,
                        attribute: AppleMediaApiTextAttribute,
                    ): String? = mediaApiMetadataCoordinator.attribute(attributes, attribute)

                    override fun registerLibraryEntity(
                        mediaId: String,
                        entity: Any,
                        kind: InAppLibraryEntityKind,
                        knownAttributes: Any?,
                    ) {
                        mediaApiMetadataCoordinator.registerLibraryEntity(
                            mediaId = mediaId,
                            entity = entity,
                            kind = kind,
                            knownAttributes = knownAttributes,
                            requestResolution = false,
                            retainEntityRef = true,
                        )
                    }

                    override fun enrichLibraryEntity(
                        mediaId: String,
                        entity: Any,
                        kind: InAppLibraryEntityKind,
                        attributes: Any,
                    ) {
                        mediaApiMetadataCoordinator.enrichLibraryEntity(
                            mediaId,
                            entity,
                            kind,
                            attributes,
                        )
                    }

                    override fun markMetadataVisible(mediaIds: Collection<String>) {
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    }

                    override fun enrichLibraryEntitiesForResolution(
                        mediaIds: Collection<String>,
                    ) {
                        mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(mediaIds)
                    }

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun applyAliasToMetadataRefs(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                        notifyModelChange: Boolean,
                    ) {
                        applyAliasToInAppMetadataRefs(
                            mediaId = mediaId,
                            alias = alias,
                            forceRebind = true,
                            notifyModelChange = notifyModelChange,
                        )
                    }

                    override fun shouldRequestOverride(mediaId: String): Boolean =
                        shouldRequestInAppMetadataOverride(mediaId)

                    override fun scheduleMetadataResolution(
                        mediaIds: Collection<String>,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                        originalResolutionMode: InAppOriginalResolutionMode,
                    ) {
                        metadataResolutionCoordinator.schedule(
                            mediaIds = mediaIds,
                            priority = priority,
                            originalResolutionMode = originalResolutionMode,
                        )
                    }

                    override fun activeMetadataPageOwner(): Any? =
                        metadataSurfaceRuntime.activePageOwner()

                    override fun knownArtistProfileCredits(artistId: String): Set<String> =
                        mediaApiMetadataCoordinator.knownArtistProfileCredits(artistId)

                    override fun onMetadataPageAttached(owner: Any, recycler: RecyclerView) {
                        this@AppleMusicProviderOrchestrator.onMetadataPageAttached(owner, recycler)
                    }

                    override fun onMetadataPageDetached(owner: Any) {
                        this@AppleMusicProviderOrchestrator.onMetadataPageDetached(owner)
                    }

                    override fun nextMetadataTraceSequence(): Long =
                        metadataTraceSequence.incrementAndGet()

                    override fun logMetadataIdentity(event: String, details: String) {
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    }
                },
            )
            metadataSurfaceRuntime = AppleMetadataSurfaceRuntime(
                runtime = runtime,
                host = object : AppleMetadataSurfaceHost {
                    override fun catalogResolver(): AppleInternalCatalogResolver? =
                        if (::internalCatalogResolver.isInitialized) {
                            internalCatalogResolver
                        } else {
                            null
                        }

                    override fun associatedArtistIds(mediaId: String): Collection<String> =
                        metadataOverrideStore.associatedArtistIds(mediaId).orEmpty()

                    override fun hasVisibleExactConsumer(mediaId: String): Boolean =
                        dataBindingHooks.hasVisibleExactConsumer(mediaId)

                    override fun hasGenericRecyclerConsumer(mediaId: String): Boolean =
                        dataBindingHooks.hasGenericRecyclerRefs(mediaId)

                    override fun detachController(owner: Any): Int =
                        librarySurfaceHooks.detachController(owner)

                    override fun logMetadataIdentity(event: String, details: String) {
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    }

                    override fun describeView(view: View): String =
                        visibleMetadataDiagnostics.viewDescription(view)
                },
            )
            inAppArtworkContinuityHooks = AppleInAppArtworkContinuityHooks(
                runtime = runtime,
                host = object : AppleInAppArtworkContinuityHost {
                    override fun onArtworkDelegateResolved(
                        delegate: Any,
                        liveData: Any?,
                        urls: List<String>,
                    ) {
                        listenNowHooks.onArtworkDelegateResolved(delegate, liveData, urls)
                    }

                    override fun logMetadataIdentity(event: String, details: String) {
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    }
                },
            )
            actionSheetMetadataHooks = AppleActionSheetMetadataHooks(
                runtime = runtime,
                host = object : AppleActionSheetMetadataHost {
                    override fun activePlaybackIdentity(): ActivePlaybackMediaIdentity =
                        activePlaybackMediaIdentity()

                    override fun markMetadataVisible(mediaIds: Collection<String>) {
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    }

                    override fun rawContentItemValue(
                        contentItem: Any,
                        runtimeMember: AppleMusicRuntimeMember,
                    ): Any? = this@AppleMusicProviderOrchestrator.rawContentItemValue(
                        contentItem,
                        runtimeMember,
                    )

                    override fun recordArtistAssociation(
                        mediaId: String,
                        item: Any,
                        rawTitle: String?,
                    ) {
                        val artistKeys = contentItemArtistCacheKeys(item, rawTitle)
                        if (artistKeys.isNotEmpty()) {
                            metadataOverrideStore.mergeArtistKeys(mediaId, artistKeys)
                        }
                        metadataResolutionCoordinator.mergePlaybackAssociatedArtistIds(
                            mediaId = mediaId,
                            artistIds = artistIdsFromAssociationKeys(artistKeys) +
                                contentItemCatalogLookupIds(item, mediaId = "")
                                    .filterNot { it == mediaId },
                        )
                    }

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun knownValues(
                        mediaId: String,
                        field: VisibleTextField,
                    ): Set<String> {
                        val alias = metadataResolutionCoordinator.effectiveAlias(mediaId)
                        val account = metadataOverrideStore.accountMetadata(mediaId)
                        return buildSet {
                            when (field) {
                                VisibleTextField.ARTIST -> {
                                    account?.artist?.let(::add)
                                    alias?.artist?.let(::add)
                                    inAppMetadataRegistry.livePlaybackItemRefs(mediaId)
                                        .forEach { ref ->
                                        ref.originalArtist?.toString()?.let(::add)
                                    }
                                }
                                VisibleTextField.ALBUM -> {
                                    alias?.album?.let(::add)
                                    inAppMetadataRegistry.livePlaybackItemRefs(mediaId)
                                        .forEach { ref ->
                                        ref.originalCollectionName?.let(::add)
                                    }
                                }
                                VisibleTextField.TITLE -> Unit
                            }
                        }
                    }

                    override fun shouldRequestOverride(mediaId: String): Boolean =
                        shouldRequestInAppMetadataOverride(mediaId)

                    override fun ensureOverride(
                        mediaId: String,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                    ) {
                        metadataResolutionCoordinator.ensureOverride(mediaId = mediaId, priority = priority)
                    }

                    override fun localizedText(
                        field: VisibleTextField,
                        alias: AppleInternalCatalogResolver.Alias,
                    ): String = localizedVisibleText(field, alias)

                    override fun logMetadataIdentity(
                        event: String,
                        identity: ActivePlaybackMediaIdentity?,
                        details: String,
                    ) {
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
                    }
                },
            )
            playbackItemConversionHooks = ApplePlaybackItemConversionHooks(
                runtime = runtime,
                host = object : ApplePlaybackItemConversionHost {
                    override fun containerKind(containerItem: Any): InAppContainerKind? =
                        inAppContainerKind(containerItem)

                    override fun metadataId(metadata: Any, fallback: String?): String? =
                        media3MetadataId(metadata, fallback)

                    override fun activePlaybackIdentity(): ActivePlaybackMediaIdentity =
                        activePlaybackMediaIdentity()

                    override fun metadataDetails(metadata: Any): String =
                        media3MetadataDetails(metadata)

                    override fun logMetadataIdentity(
                        event: String,
                        identity: ActivePlaybackMediaIdentity,
                        details: String,
                    ) {
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            identity = identity,
                            details = details,
                        )
                    }

                    override fun markContainerNavigationItem(
                        containerItem: Any,
                        kind: InAppContainerKind,
                        mediaId: String,
                    ) {
                        markInAppContainerNavigationItem(containerItem, kind, mediaId)
                    }

                    override fun markMetadataVisible(mediaIds: Collection<String>) {
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    }

                    override fun registerContainerItem(
                        mediaId: String,
                        containerItem: Any,
                        kind: InAppContainerKind,
                    ) {
                        registerInAppContainerItem(mediaId, containerItem, kind)
                    }

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun applyAliasToContainerItem(
                        containerItem: Any,
                        kind: InAppContainerKind,
                        alias: AppleInternalCatalogResolver.Alias,
                    ) {
                        this@AppleMusicProviderOrchestrator.applyAliasToInAppContainerItem(
                            containerItem,
                            kind,
                            alias,
                        )
                    }

                    override fun contentItemMediaId(contentItem: Any): String? =
                        contentItemMetadataHooks.mediaId(contentItem)

                    override fun registerPlaybackItem(mediaId: String, playbackItem: Any) {
                        registerInAppPlaybackItem(mediaId, playbackItem)
                    }

                    override fun applyAliasToPlaybackItem(
                        playbackItem: Any,
                        alias: AppleInternalCatalogResolver.Alias,
                    ) {
                        this@AppleMusicProviderOrchestrator.applyAliasToInAppPlaybackItem(
                            playbackItem,
                            alias,
                        )
                    }

                    override fun shouldRequestOverride(mediaId: String): Boolean =
                        shouldRequestInAppMetadataOverride(mediaId)

                    override fun ensureOverride(
                        mediaId: String,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                    ) {
                        metadataResolutionCoordinator.ensureOverride(mediaId = mediaId, priority = priority)
                    }
                },
            )
            contentItemMetadataHooks = AppleContentItemMetadataHooks(
                runtime = runtime,
                host = object : AppleContentItemMetadataHost {
                    override fun containerNavigationBinding(
                        contentItem: Any,
                    ): InAppContainerNavigationRef? =
                        inAppContainerNavigationBinding(contentItem)

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun registerContainerItem(
                        mediaId: String,
                        contentItem: Any,
                        kind: InAppContainerKind,
                    ) {
                        registerInAppContainerItem(mediaId, contentItem, kind)
                    }

                    override fun localizedEntityType(
                        contentItem: Any,
                    ): AppleInternalCatalogResolver.LocalizedEntityType? =
                        contentItemLocalizedEntityType(contentItem)

                    override fun recordComposeMediaId(mediaId: String) {
                        librarySurfaceHooks.recordComposeMediaId(mediaId)
                    }

                    override fun recordCurrentRecyclerMediaId(mediaId: String) {
                        this@AppleMusicProviderOrchestrator
                            .recordCurrentRecyclerMediaId(mediaId)
                    }

                    override fun requestPriority(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.RequestPriority =
                        requestPriorityForMediaId(mediaId)

                    override fun shouldResolveFromGetter(
                        priority: AppleInternalCatalogResolver.RequestPriority,
                    ): Boolean = shouldResolveMetadataFromGetter(priority)

                    override fun registerPlaybackItem(
                        mediaId: String,
                        playbackItem: Any,
                        notifyChange: Boolean,
                        analyzeMetadata: Boolean,
                    ) {
                        registerInAppPlaybackItem(
                            mediaId = mediaId,
                            playbackItem = playbackItem,
                            notifyChange = notifyChange,
                            analyzeMetadata = analyzeMetadata,
                        )
                    }

                    override fun shouldRequestOverride(mediaId: String): Boolean =
                        shouldRequestInAppMetadataOverride(mediaId)

                    override fun applyAliasToPlaybackItem(
                        playbackItem: Any,
                        alias: AppleInternalCatalogResolver.Alias,
                        notifyChange: Boolean,
                    ) {
                        applyAliasToInAppPlaybackItem(
                            playbackItem = playbackItem,
                            alias = alias,
                            notifyChange = notifyChange,
                        )
                    }

                    override fun metadataOverride(
                        entityType: AppleInternalCatalogResolver.LocalizedEntityType,
                        getter: AppleContentItemGetter,
                        alias: AppleInternalCatalogResolver.Alias,
                        original: String?,
                    ): String? = contentItemMetadataOverride(
                        entityType = entityType,
                        getter = getter,
                        alias = alias,
                        original = original,
                    )
                },
            )
            mediaApiMetadataCoordinator = AppleMediaApiMetadataCoordinator(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                catalogResolver = internalCatalogResolver,
                librarySurfaceHooks = librarySurfaceHooks,
                artistSurfaceHooks = artistSurfaceHooks,
                host = object : AppleMediaApiMetadataHost {
                    override fun contentItemMediaId(contentItem: Any): String? =
                        contentItemMetadataHooks.mediaId(contentItem)

                    override fun registerPlaybackItem(
                        mediaId: String,
                        playbackItem: Any,
                        notifyChange: Boolean,
                        analyzeMetadata: Boolean,
                    ) {
                        registerInAppPlaybackItem(
                            mediaId = mediaId,
                            playbackItem = playbackItem,
                            notifyChange = notifyChange,
                            analyzeMetadata = analyzeMetadata,
                        )
                    }

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun applyAliasToPlaybackItem(
                        playbackItem: Any,
                        alias: AppleInternalCatalogResolver.Alias,
                        notifyChange: Boolean,
                    ) {
                        applyAliasToInAppPlaybackItem(playbackItem, alias, notifyChange)
                    }

                    override fun shouldShareOriginalSongLanguage(
                        localizedTitle: String?,
                        localizedArtist: String?,
                        alias: AppleInternalCatalogResolver.Alias?,
                    ): Boolean = metadataResolutionCoordinator.shouldShareOriginalSongLanguage(
                            localizedTitle = localizedTitle,
                            localizedArtist = localizedArtist,
                            alias = alias,
                        )

                    override fun rememberOriginalLanguageForArtist(
                        mediaId: String,
                        language: String,
                    ) {
                        metadataResolutionCoordinator.rememberOriginalLanguageForArtist(
                            mediaId,
                            language,
                        )
                    }

                    override fun hydrateSharedArtistOverrides(mediaId: String) {
                        metadataResolutionCoordinator.hydrateSharedArtistOverrides(mediaId)
                    }

                    override fun markMetadataVisible(mediaIds: Collection<String>) {
                        this@AppleMusicProviderOrchestrator.markMetadataVisible(mediaIds)
                    }

                    override fun applyAliasToMetadataRefs(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                        forceRebind: Boolean,
                        notifyModelChange: Boolean,
                    ) {
                        applyAliasToInAppMetadataRefs(
                            mediaId = mediaId,
                            alias = alias,
                            forceRebind = forceRebind,
                            notifyModelChange = notifyModelChange,
                        )
                    }

                    override fun shouldRequestOverride(mediaId: String): Boolean =
                        shouldRequestInAppMetadataOverride(mediaId)

                    override fun scheduleMetadataResolution(
                        mediaIds: Collection<String>,
                        priority: AppleInternalCatalogResolver.RequestPriority,
                        originalResolutionMode: InAppOriginalResolutionMode,
                    ) {
                        metadataResolutionCoordinator.schedule(
                            mediaIds = mediaIds,
                            priority = priority,
                            originalResolutionMode = originalResolutionMode,
                        )
                    }

                    override fun configuredContentUiLanguage(): Int =
                        this@AppleMusicProviderOrchestrator.configuredContentUiLanguage()

                    override fun nextTraceSequence(): Long =
                        metadataTraceSequence.incrementAndGet()
                },
            )
            metadataResolutionCoordinator = AppleInAppMetadataResolutionCoordinator(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                catalogResolver = internalCatalogResolver,
                host = object : AppleInAppMetadataResolutionHost {
                    override fun currentPlaybackMetadataId(): String? =
                        playbackMetadataCoordinator.currentMetadataId()

                    override fun configuredContentUiLanguage(): Int =
                        this@AppleMusicProviderOrchestrator.configuredContentUiLanguage()

                    override fun shouldOverrideAccountLanguage(selection: Int): Boolean =
                        this@AppleMusicProviderOrchestrator.shouldOverrideAccountLanguage(selection)

                    override fun isRestoreOriginalEnabled(): Boolean =
                        isRestoreCjkOriginalMetadataEnabled()

                    override fun refreshRequestScope() {
                        metadataSurfaceRuntime.refreshRequestScope()
                    }

                    override fun enrichLibraryEntitiesForResolution(
                        mediaIds: Collection<String>,
                    ) {
                        mediaApiMetadataCoordinator.enrichLibraryEntitiesForResolution(mediaIds)
                    }

                    override fun applyAliasToMetadataRefs(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                        forceRebind: Boolean,
                        notifyModelChange: Boolean,
                    ) {
                        applyAliasToInAppMetadataRefs(
                            mediaId = mediaId,
                            alias = alias,
                            forceRebind = forceRebind,
                            notifyModelChange = notifyModelChange,
                        )
                    }

                    override fun applyPlaybackMetadataOverride(
                        mediaId: String,
                        alias: AppleInternalCatalogResolver.Alias,
                        forceInAppRebind: Boolean,
                        rememberLocalizedArtist: Boolean,
                        originalMetadata: Boolean,
                        originalMetadataConfirmed: Boolean,
                        artistOnly: Boolean,
                        propagateArtistEntity: Boolean,
                    ) {
                        this@AppleMusicProviderOrchestrator.applyPlaybackMetadataOverride(
                            mediaId = mediaId,
                            alias = alias,
                            forceInAppRebind = forceInAppRebind,
                            rememberLocalizedArtist = rememberLocalizedArtist,
                            originalMetadata = originalMetadata,
                            originalMetadataConfirmed = originalMetadataConfirmed,
                            artistOnly = artistOnly,
                            propagateArtistEntity = propagateArtistEntity,
                        )
                    }

                    override fun logMetadataIdentity(event: String, details: String) {
                        this@AppleMusicProviderOrchestrator.logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    }

                    override fun nextTraceSequence(): Long =
                        metadataTraceSequence.incrementAndGet()
                },
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
                host = object : AppleVisibleMetadataDiagnosticsHost {
                    override fun activePlaybackIdentity(): ActivePlaybackMediaIdentity =
                        activePlaybackMediaIdentity()

                    override fun effectiveAlias(
                        mediaId: String,
                    ): AppleInternalCatalogResolver.Alias? =
                        metadataResolutionCoordinator.effectiveAlias(mediaId)

                    override fun activeMetadataValues(mediaId: String): Set<String> {
                        val account = metadataOverrideStore.accountMetadata(mediaId)
                        val alias = metadataResolutionCoordinator.effectiveAlias(mediaId)
                        val framework = frameworkMetadataHooks.originalMetadata(mediaId)
                        return buildSet {
                            listOf(
                                account?.title,
                                account?.artist,
                                alias?.title,
                                alias?.artist,
                                framework?.getString(MediaMetadata.METADATA_KEY_TITLE),
                                framework?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                                framework?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                                framework?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                            ).filterNotNull()
                                .map(String::trim)
                                .filter(String::isNotEmpty)
                                .forEach(::add)
                            inAppMetadataRegistry.livePlaybackItemRefs(mediaId).forEach { ref ->
                                ref.originalTitle?.toString()?.trim()
                                    ?.takeIf(String::isNotEmpty)?.let(::add)
                                ref.originalArtist?.toString()?.trim()
                                    ?.takeIf(String::isNotEmpty)?.let(::add)
                            }
                        }
                    }

                    override fun nextTraceSequence(): Long =
                        metadataTraceSequence.incrementAndGet()
                },
            )
            playbackHooks = ApplePlaybackHooks(
                runtime = runtime,
                isAodLyricsEnabled = ::isAodLyricsEnabled,
                currentMetadataId = playbackMetadataCoordinator::currentMetadataId,
                currentLyricsSongId = lyricsHooks::currentSongId,
                queueItemMediaId = playbackMetadataCoordinator::queueItemMediaId,
                refreshCurrentQueueItem = playbackMetadataCoordinator::refreshCurrentQueueItem,
            )
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

    private fun initializeContentUiLanguage() {
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
                RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
                RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX ->
                    lyricsHooks.refreshAppleLyricsBlurEffect()
                RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT ->
                    lyricsHooks.refreshAppleSystemFontWeight()
                RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
                RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION -> {
                    if (!lyricsHooks.isNativeOnlineTranslationEnabled()) {
                        lyricsHooks.nativeOnlineTranslationStore.clear()
                        lyricsHooks.refreshAppleLyricsSupplementPresentation()
                    }
                }
                RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS ->
                    missingLyricsHooks.onPreferenceChanged()
                RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS -> {
                    playbackHooks.onAodPreferenceChanged()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun applyConfiguredContentUiLanguage(
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

    private fun initProvider() {
        val directPlayer = AppleDirectPlayer(
            context = application,
            onOriginalMetadataRequested =
                playbackMetadataCoordinator::resolveOriginalMetadataOnDemand,
            onOnlineTranslationReceived = lyricsHooks::receiveNativeOnlineTranslation,
            onOnlineTranslationCleared = lyricsHooks::clearNativeOnlineTranslation,
            onMissingLyricsSupplementReceived = lyricsHooks::receiveMissingLyricsSupplement,
            onMissingLyricsSupplementCleared = missingLyricsHooks::clearSupplement,
            onOnlineTranslationSourceSwitchResult = onlineSourceMenuHooks::receiveSourceSwitchResult,
        ).also { it.start() }
        this.directPlayer = directPlayer
        val helper = runCatching {
            LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = APPLE_MUSIC_PACKAGE,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            ).also { it.register() }
        }.onFailure {
            ProviderLogger.error("Lyricon Central 提供器注册失败，使用内置直连", it)
        }.getOrNull()
        val activePlayer = helper?.player?.let { CompositeRemotePlayer(it, directPlayer) }
            ?: directPlayer
        lyricRequester = LyricRequester(hookResolver, application)
        PlaybackManager.init(
            remotePlayer = activePlayer,
            requester = lyricRequester,
            hookResolver = hookResolver,
            onMissingLyricsSupplementBuilt = lyricsHooks::receiveModuleMissingLyrics,
            hasKnownNativeLyrics = missingLyricsHooks::hasKnownNativeLyricsFor,
        )
        playbackHooks.attachRemotePlayer(activePlayer)
        playbackHooks.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
    }

    private fun startHooks() {
        hookModules().asSequence()
            .filter { hookModule -> !hookModule.debugOnly || BuildConfig.DEBUG }
            .forEach { hookModule ->
                hookRegistrar.withModule(hookModule.id, hookModule::installHooks)
            }
    }

    internal fun hookModuleIdsForBuild(debug: Boolean): List<String> =
        hookModules()
            .filter { hookModule -> !hookModule.debugOnly || debug }
            .map { hookModule -> hookModule.id }

    private fun hookModules() = listOf(
        FunctionalAppleMusicHookModule(
            "hookMetadataSurfaceLifecycle",
            installer = { metadataSurfaceRuntime.installLifecycleHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookTranslationPreference",
            installer = { lyricsHooks.hookTranslationPreference() },
        ),
        FunctionalAppleMusicHookModule(
            "hookMediaApiLocalization",
            installer = { contentLocalizationHooks.installMediaApiLocalization() },
        ),
        FunctionalAppleMusicHookModule(
            "hookContentHttpLocalization",
            installer = { contentLocalizationHooks.installContentHttpLocalization() },
        ),
        FunctionalAppleMusicHookModule(
            "hookExoMediaPlayer",
            installer = { playbackHooks.installExoMediaPlayer() },
        ),
        FunctionalAppleMusicHookModule(
            "hookMediaMetadataChange",
            installer = { playbackMetadataHooks.installHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookContentItemMetadata",
            installer = { contentItemMetadataHooks.installHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookInAppLibraryEntities",
            installer = { librarySurfaceHooks.installEntityHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookCollectionPageMetadataRefresh",
            installer = { collectionSurfaceHooks.installHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookArtistProfileTopSongs",
            installer = { artistSurfaceHooks.installTopSongHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookArtistProfileMetadata",
            installer = { artistSurfaceHooks.installProfileHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookRecentlySearchedMetadata",
            installer = { mediaApiMetadataCoordinator.installRecentlySearchedHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookInAppArtworkContinuity",
            installer = { inAppArtworkContinuityHooks.installHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookInAppListenNowArtworkContinuity",
            installer = { listenNowHooks.installArtworkContinuityHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookInAppLibraryEpoxyRefresh",
            installer = { librarySurfaceHooks.installEpoxyHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookInAppLibraryComposeRefresh",
            installer = { librarySurfaceHooks.installComposeHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookDebugListenNowArtworkLifecycle",
            debugOnly = true,
            installer = { listenNowHooks.installDebugArtworkLifecycleHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookVisibleMetadataDiagnostics",
            debugOnly = true,
            installer = { visibleMetadataDiagnostics.installHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookInAppDataBindingRefresh",
            installer = { dataBindingHooks.installDataBindingHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookInAppListenNowMetadataBinding",
            installer = { listenNowHooks.installMetadataBindingHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookRecyclerViewCentralBinding",
            installer = { dataBindingHooks.installRecyclerHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookInAppMetadata",
            installer = { queueMetadataHooks.installHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookInAppPlaybackItemConversion",
            installer = { playbackItemConversionHooks.installHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookInAppActionSheetMetadata",
            installer = { actionSheetMetadataHooks.installHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookMediaSessionMetadata",
            installer = { frameworkMetadataHooks.installMediaSessionMetadata() },
        ),
        FunctionalAppleMusicHookModule(
            "hookMediaSessionQueue",
            installer = { frameworkMetadataHooks.installMediaSessionQueue() },
        ),
        FunctionalAppleMusicHookModule(
            "hookPlaybackNotificationMetadata",
            installer = { frameworkMetadataHooks.installPlaybackNotificationMetadata() },
        ),
        FunctionalAppleMusicHookModule(
            "hookAppleOfficialPronunciationLanguageMatching",
            installer = { lyricsHooks.hookAppleOfficialPronunciationLanguageMatching() },
        ),
        FunctionalAppleMusicHookModule(
            "hookAppleLyricsPreferredLanguages",
            installer = { lyricsHooks.hookAppleLyricsPreferredLanguages() },
        ),
        FunctionalAppleMusicHookModule(
            "hookApplePronunciationWordRendering",
            installer = { lyricsHooks.hookApplePronunciationWordRendering() },
        ),
        FunctionalAppleMusicHookModule(
            "hookLyricBuildMethod",
            installer = { lyricsHooks.hookLyricBuildMethod() },
        ),
        FunctionalAppleMusicHookModule(
            "hookAppleNativeLyricsPresentation",
            installer = { lyricsHooks.hookAppleNativeLyricsPresentation() },
        ),
        FunctionalAppleMusicHookModule(
            "hookAppleSystemFontWeight",
            installer = { lyricsHooks.hookAppleSystemFontWeight() },
        ),
        FunctionalAppleMusicHookModule(
            "hookAppleLyricsBlurEffect",
            installer = { lyricsHooks.hookAppleLyricsBlurEffect() },
        ),
        FunctionalAppleMusicHookModule(
            "hookAppleLyricsUiDiagnostics",
            debugOnly = true,
            installer = { lyricsHooks.hookAppleLyricsUiDiagnostics() },
        ),
        FunctionalAppleMusicHookModule(
            "hookAppleLyricsBindingDiagnostics",
            debugOnly = true,
            installer = { lyricsHooks.hookAppleLyricsBindingDiagnostics() },
        ),
        FunctionalAppleMusicHookModule(
            "hookAppleLyricsSourceMenu",
            installer = { onlineSourceMenuHooks.installSourceMenu() },
        ),
        FunctionalAppleMusicHookModule(
            "hookAppleMissingLyricsSupplement",
            installer = { missingLyricsHooks.installHooks() },
        ),
        FunctionalAppleMusicHookModule(
            "hookLyricsNetworkRequest",
            debugOnly = true,
            installer = { debugNetworkHooks.installLyricsNetworkRequest() },
        ),
        FunctionalAppleMusicHookModule(
            "hookLyricsCookies",
            debugOnly = true,
            installer = { debugNetworkHooks.installLyricsCookies() },
        ),
        FunctionalAppleMusicHookModule(
            "hookFinalLyricsHttp",
            debugOnly = true,
            installer = { debugNetworkHooks.installFinalLyricsHttp() },
        ),
    )

    private fun onMetadataPageAttached(owner: Any, recycler: RecyclerView) {
        metadataSurfaceRuntime.onPageAttached(owner, recycler)
    }

    private fun onMetadataPageDetached(owner: Any) {
        metadataSurfaceRuntime.onPageDetached(owner)
    }

    private fun markMetadataVisible(
        mediaIds: Collection<String>,
    ): AppleMetadataSurfaceCoordinator.SurfaceSnapshot =
        metadataSurfaceRuntime.markVisible(mediaIds)

    private fun setMetadataPlaybackMediaId(
        mediaId: String?,
    ): AppleMetadataSurfaceCoordinator.SurfaceSnapshot =
        metadataSurfaceRuntime.setPlaybackMediaId(mediaId)

    private fun metadataRequestContext(
        mediaId: String,
    ): AppleMetadataSurfaceCoordinator.RequestContext =
        metadataSurfaceRuntime.requestContext(mediaId)

    private fun isCurrentMetadataSurfaceMediaId(mediaId: String): Boolean =
        metadataSurfaceRuntime.isCurrentMediaId(mediaId)

    private fun hasVisibleInAppConsumer(mediaId: String): Boolean =
        metadataSurfaceRuntime.hasVisibleConsumer(mediaId)

    private fun isRefreshableInAppMediaId(mediaId: String): Boolean =
        metadataSurfaceRuntime.isRefreshable(mediaId)

    private fun requestPriorityForMediaId(
        mediaId: String,
    ): AppleInternalCatalogResolver.RequestPriority = metadataRequestContext(mediaId).priority

    private fun recordCurrentRecyclerMediaId(mediaId: String): Boolean =
        dataBindingHooks.recordCurrentRecyclerMediaId(mediaId)

    private fun media3MetadataId(
        metadata: Any,
        fallback: String?,
        trustedFallback: Boolean = false,
    ): String? = media3MetadataCoordinator.mediaId(metadata, fallback, trustedFallback)

    private fun media3MetadataDetails(metadata: Any): String =
        media3MetadataCoordinator.details(metadata)

    private fun activePlaybackMediaIdentity(): ActivePlaybackMediaIdentity =
        media3MetadataCoordinator.activePlaybackIdentity()

    private fun logMetadataIdentity(
        event: String,
        identity: ActivePlaybackMediaIdentity = activePlaybackMediaIdentity(),
        details: String,
    ) = media3MetadataCoordinator.logIdentity(event, identity, details)

    private fun registerInAppMetadata(
        mediaId: String,
        metadata: Any,
        requestResolution: Boolean = true,
        preBind: Boolean = false,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
    ) = metadataRegistrationCoordinator.registerMetadata(
        mediaId = mediaId,
        metadata = metadata,
        requestResolution = requestResolution,
        preBind = preBind,
        priority = priority,
    )

    private fun registerInAppPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean = true,
        analyzeMetadata: Boolean = true,
    ) = metadataRegistrationCoordinator.registerPlaybackItem(
        mediaId = mediaId,
        playbackItem = playbackItem,
        notifyChange = notifyChange,
        analyzeMetadata = analyzeMetadata,
    )

    private fun mergePlaybackAccountMetadata(
        mediaId: String,
        title: String?,
        artist: String?,
        reconcileArtistAssociations: Boolean = true,
    ) = metadataRegistrationCoordinator.mergePlaybackAccountMetadata(
        mediaId = mediaId,
        title = title,
        artist = artist,
        reconcileArtistAssociations = reconcileArtistAssociations,
    )

    private fun registerInAppContainerItem(
        mediaId: String,
        containerItem: Any,
        kind: InAppContainerKind,
    ) = metadataRegistrationCoordinator.registerContainerItem(mediaId, containerItem, kind)

    private fun inAppContainerKind(containerItem: Any): InAppContainerKind? =
        metadataRegistrationCoordinator.containerKind(containerItem)

    private fun markInAppContainerNavigationItem(
        containerItem: Any,
        kind: InAppContainerKind,
        mediaId: String,
    ) = metadataRegistrationCoordinator.markContainerNavigationItem(containerItem, kind, mediaId)

    private fun inAppContainerNavigationBinding(
        containerItem: Any,
    ): InAppContainerNavigationRef? =
        metadataRegistrationCoordinator.containerNavigationBinding(containerItem)

    private fun rawContentItemValue(
        contentItem: Any,
        runtimeMember: AppleMusicRuntimeMember,
    ): Any? = metadataRegistrationCoordinator.rawContentItemValue(contentItem, runtimeMember)

    private fun inAppPlaybackItemContract(playbackItem: Any): InAppPlaybackItemContract =
        metadataRegistrationCoordinator.playbackItemContract(playbackItem)

    private fun readInAppPlaybackItemValue(
        playbackItem: Any,
        field: InAppPlaybackItemField,
        contract: InAppPlaybackItemContract = inAppPlaybackItemContract(playbackItem),
    ): String? = metadataRegistrationCoordinator.readPlaybackItemValue(
        playbackItem = playbackItem,
        field = field,
        contract = contract,
    )

    private fun contentItemCatalogLookupIds(contentItem: Any, mediaId: String): Set<String> =
        metadataRegistrationCoordinator.contentItemCatalogLookupIds(contentItem, mediaId)

    private fun contentItemArtistCacheKeys(
        contentItem: Any,
        rawArtist: String?,
    ): Set<String> = metadataRegistrationCoordinator.contentItemArtistCacheKeys(
        contentItem,
        rawArtist,
    )

    private fun contentItemLocalizedEntityType(
        contentItem: Any,
    ): AppleInternalCatalogResolver.LocalizedEntityType? =
        metadataRegistrationCoordinator.contentItemLocalizedEntityType(contentItem)

    private fun applyAliasToInAppMetadataRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceRebind: Boolean = true,
        notifyModelChange: Boolean = true,
    ) = inAppMetadataApplier.applyAliasToMetadataRefs(
        mediaId = mediaId,
        alias = alias,
        forceRebind = forceRebind,
        notifyModelChange = notifyModelChange,
    )

    private fun requestInAppLibraryControllerBuild(
        controller: Any,
        strategy: InAppLibraryControllerBuildStrategy,
    ) = inAppMetadataApplier.requestLibraryControllerBuild(controller, strategy)

    private fun dataBindingAliasValues(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        binding: Any?,
    ): DataBindingAliasValues = inAppMetadataApplier.dataBindingAliasValues(
        mediaId = mediaId,
        alias = alias,
        binding = binding,
    )

    private fun applyAliasToInAppContainerItem(
        containerItem: Any,
        kind: InAppContainerKind,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean = true,
    ) = inAppMetadataApplier.applyAliasToContainerItem(
        containerItem = containerItem,
        kind = kind,
        alias = alias,
        notifyChange = notifyChange,
    )

    private fun applyAliasToInAppPlaybackItem(
        playbackItem: Any,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean = true,
    ) = inAppMetadataApplier.applyAliasToPlaybackItem(
        playbackItem = playbackItem,
        alias = alias,
        notifyChange = notifyChange,
    )

    private fun applyPlaybackMetadataOverride(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean = true,
        rememberLocalizedArtist: Boolean = true,
        originalMetadata: Boolean = false,
        originalMetadataConfirmed: Boolean = false,
        artistOnly: Boolean = false,
        propagateArtistEntity: Boolean = true,
    ) = metadataOverrideApplicationCoordinator.apply(
        mediaId = mediaId,
        alias = alias,
        forceInAppRebind = forceInAppRebind,
        rememberLocalizedArtist = rememberLocalizedArtist,
        originalMetadata = originalMetadata,
        originalMetadataConfirmed = originalMetadataConfirmed,
        artistOnly = artistOnly,
        propagateArtistEntity = propagateArtistEntity,
    )

    private fun configuredContentUiLanguage(): Int {
        val prefs = contentUiLanguagePrefs
        return prefs?.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
        ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
    }

    private fun shouldOverrideAccountLanguage(selection: Int): Boolean {
        if (selection == RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE) return false
        return contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
        ) == true
    }

    private fun shouldRestoreCjkOriginalMetadata(
        metadata: MediaMetadataCache.Metadata,
    ): Boolean = isRestoreCjkOriginalMetadataEnabled() &&
        AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
            mediaId = metadata.id,
            title = metadata.title,
            artist = metadata.artist,
            genre = metadata.genre,
        )

    private fun shouldRetryOriginalMetadataCacheProbe(mediaId: String): Boolean =
        shouldRetryOriginalMetadataCacheProbe(
            originalResolved = metadataOverrideStore.isOriginalResolved(mediaId),
            lastMissUptimeMillis = metadataOverrideStore.originalCacheMissUptimeMillis(mediaId),
            nowUptimeMillis = SystemClock.uptimeMillis(),
        )

    private fun shouldRequestInAppMetadataOverride(mediaId: String): Boolean =
        metadataResolutionCoordinator.shouldRequestOverride(mediaId)

    private fun isRestoreCjkOriginalMetadataEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
    ) == true

    private fun isSimplifyTraditionalLyricsEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
        ) == true

    private fun isAodLyricsEnabled(): Boolean = contentUiLanguagePrefs?.getBoolean(
        RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
        RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
    ) ?: RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS

}
