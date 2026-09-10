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
 * R6 歌词/播放组装群。
 *
 * 持有歌词补充、播放事件、在线来源菜单与远程播放器初始化所需的组件。
 * 跨领域引用（元数据表面组装群）通过 [inAppMetadata] 延迟 provider 在调用期解析，
 * 与迁移前的 lambda 解析时机一致，不做构造期快照。
 */
internal class AppleOrchestratorLyricsPlaybackAssembly(
    private val runtime: AppleMusicProviderRuntime,
    private val catalogLanguage: AppleOrchestratorCatalogLanguageAssembly,
    private val metadataOverrideStore: AppleMetadataOverrideStore,
    private val inAppMetadataRegistry: AppleInAppMetadataRegistry,
    private val inAppMetadataProvider: () -> AppleOrchestratorInAppMetadataAssembly,
) {
    internal lateinit var playbackMetadataCoordinator: ApplePlaybackMetadataCoordinator
        private set
    internal lateinit var debugNetworkHooks: AppleDebugNetworkHooks
        private set
    internal lateinit var lyricsHooks: AppleLyricsSupplementHooks
        private set
    internal lateinit var missingLyricsHooks: AppleMissingLyricsHooks
        private set
    internal lateinit var onlineSourceMenuHooks: AppleOnlineSourceMenuHooks
        private set
    internal lateinit var playbackHooks: ApplePlaybackHooks
        private set
    internal lateinit var atmosphereVolumeDiagnostics: AppleAtmosVolumeDiagnostics
        private set
    internal lateinit var playbackMetadataHooks: ApplePlaybackMetadataHooks
        private set
    internal var directPlayer: AppleDirectPlayer? = null
        private set
    internal lateinit var lyricRequester: LyricRequester
        private set

    private fun inAppMetadata(): AppleOrchestratorInAppMetadataAssembly = inAppMetadataProvider()

    fun assemble() {
        PreferencesMonitor.initialize(runtime.application, runtime.hookResolver)
        PreferencesMonitor.listener = object : PreferencesMonitor.Listener {
            override fun onTranslationSelectedChanged(selected: Boolean) {
                playbackHooks.setDisplayTranslation(selected)
                lyricsHooks.refreshAppleLyricsSupplementPresentation()
            }

            override fun onPronunciationSelectedChanged(selected: Boolean) {
                lyricsHooks.refreshAppleLyricsSupplementPresentation()
            }
        }
        playbackMetadataCoordinator = ApplePlaybackMetadataCoordinator(
            hookResolver = runtime.hookResolver,
            catalogResolver = catalogLanguage.internalCatalogResolver,
            metadataStore = metadataOverrideStore,
            host = DefaultApplePlaybackMetadataCoordinatorHost(
                activePlayerFn = {
                    if (this::playbackHooks.isInitialized) playbackHooks.activePlayer() else null
                },
                configuredContentUiLanguageFn = { catalogLanguage.configuredContentUiLanguage() },
                shouldOverrideAccountLanguageFn = { selection ->
                    catalogLanguage.shouldOverrideAccountLanguage(selection)
                },
                shouldRestoreCjkOriginalMetadataFn = { metadata ->
                    catalogLanguage.shouldRestoreCjkOriginalMetadata(metadata)
                },
                ensureContentItemMetadataHooksFn = { contentItemClass ->
                    inAppMetadata().ensureContentItemMetadataHooks(contentItemClass)
                },
                setMetadataPlaybackMediaIdFn = { mediaId ->
                    inAppMetadata().setMetadataPlaybackMediaId(mediaId)
                },
                onCurrentPlaybackItemFn = { mediaId, playbackItem, queueId ->
                    if (this::missingLyricsHooks.isInitialized) {
                        missingLyricsHooks.onCurrentPlaybackItem(
                            contentSongId = mediaId,
                            item = playbackItem,
                            queueId = queueId,
                        )
                    }
                },
                effectiveMetadataAliasFn = { mediaId ->
                    inAppMetadata().effectiveMetadataAlias(mediaId)
                },
                applyPlaybackMetadataOverrideFn = {
                    mediaId, alias, rememberLocalizedArtist, originalMetadata,
                    originalMetadataConfirmed,
                    ->
                    inAppMetadata().applyPlaybackMetadataOverride(
                        mediaId = mediaId,
                        alias = alias,
                        rememberLocalizedArtist = rememberLocalizedArtist,
                        originalMetadata = originalMetadata,
                        originalMetadataConfirmed = originalMetadataConfirmed,
                    )
                },
                logMetadataIdentityFn = { event, details ->
                    inAppMetadata().logMetadataIdentity(
                        event = event,
                        details = details,
                    )
                },
                shouldShareOriginalSongLanguageFn = { localizedTitle, localizedArtist, alias ->
                    inAppMetadata().shouldShareOriginalSongLanguage(
                        localizedTitle = localizedTitle,
                        localizedArtist = localizedArtist,
                        alias = alias,
                    )
                },
                rememberOriginalLanguageForArtistFn = { mediaId, language ->
                    inAppMetadata().rememberOriginalLanguageForArtist(
                        mediaId,
                        language,
                    )
                },
                isRestoreOriginalMetadataEnabledFn = { catalogLanguage.isRestoreCjkOriginalMetadataEnabled() },
            ),
        )
        debugNetworkHooks = AppleDebugNetworkHooks(runtime)
        lyricsHooks = AppleLyricsSupplementHooks(
            runtime = runtime,
            preferences = { catalogLanguage.contentUiLanguagePrefs },
            playbackHooks = { playbackHooks },
            onlineSourceMenuHooks = { onlineSourceMenuHooks },
            lyricRequester = { lyricRequester },
            catalogResolver = {
                catalogLanguage.catalogResolverOrNull
            },
            applyConfiguredContentUiLanguageCallback = {
                catalogLanguage.applyConfiguredContentUiLanguage()
            },
            recordLyricsRequestSource = { requestId, source ->
                debugNetworkHooks.recordLyricsRequestSource(requestId, source)
            },
            currentPlaybackQueueMediaId =
                playbackMetadataCoordinator::currentPlaybackQueueMediaId,
            registeredPlaybackItems = inAppMetadataRegistry::livePlaybackItems,
            registeredPlaybackItemId = inAppMetadataRegistry::playbackItemId,
            epoxyDataBindingFromHolderCallback = { holder ->
                inAppMetadata().dataBindingFromHolder(holder)
            },
            missingLyricsSupplement = { missingLyricsHooks },
        )
            missingLyricsHooks = AppleMissingLyricsHooks(
            runtime = runtime,
            preferences = { catalogLanguage.contentUiLanguagePrefs },
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
                        inAppMetadata().refreshMetadataCallbacks(id)
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
                        inAppMetadata().refreshPlaybackItemBindings(id)
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
                if (this::missingLyricsHooks.isInitialized) {
                    missingLyricsHooks.sourceInfo(songId)
                } else {
                    null
                }
            },
            hasMissingLyricsSupplement = { songId ->
                this::missingLyricsHooks.isInitialized &&
                    missingLyricsHooks.hasSupplementContent(songId)
            },
            missingLyricsTranslationSource = { songId ->
                if (this::missingLyricsHooks.isInitialized) {
                    missingLyricsHooks.translationSource(songId)
                } else {
                    null
                }
            },
            missingLyricsTranslationMatchPercentage = { songId, source ->
                if (this::missingLyricsHooks.isInitialized) {
                    missingLyricsHooks.translationMatchPercentage(songId, source)
                } else {
                    null
                }
            },
            missingLyricsPronunciationMatchPercentage = { songId, source ->
                if (this::missingLyricsHooks.isInitialized) {
                    missingLyricsHooks.pronunciationMatchPercentage(songId, source)
                } else {
                    null
                }
            },
            missingLyricsPronunciationSource = { songId ->
                if (this::missingLyricsHooks.isInitialized) {
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
                    .orderedSources(catalogLanguage.contentUiLanguagePrefs)
                    .map { it.name }
            },
            availableLyricsSources = { songId ->
                if (this::missingLyricsHooks.isInitialized) {
                    val available = missingLyricsHooks.availableLyricsSources(songId)
                    if ("APPLE" in available) {
                        available
                    } else if (
                        catalogLanguage.contentUiLanguagePrefs?.getBoolean(
                            RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
                            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
                        ) != true
                    ) {
                        available
                    } else {
                        (available +
                            com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
                                .orderedSources(catalogLanguage.contentUiLanguagePrefs)
                                .map { it.name })
                            .distinct()
                    }
                } else {
                    emptyList()
                }
            },
        )
        playbackHooks = ApplePlaybackHooks(
            runtime = runtime,
            isAodLyricsEnabled = catalogLanguage::isAodLyricsEnabled,
            currentMetadataId = playbackMetadataCoordinator::currentMetadataId,
            currentLyricsSongId = lyricsHooks::currentSongId,
            queueItemMediaId = playbackMetadataCoordinator::queueItemMediaId,
            refreshCurrentQueueItem = playbackMetadataCoordinator::refreshCurrentQueueItem,
            isVolumeBalanceEnabled = {
                catalogLanguage.contentUiLanguagePrefs?.getBoolean(
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
    }

    fun initializeProvider() {
        val directPlayer = AppleDirectPlayer(
            context = runtime.application,
            onOriginalMetadataRequested =
                playbackMetadataCoordinator::resolveOriginalMetadataOnDemand,
            onOnlineTranslationReceived = lyricsHooks::receiveNativeOnlineTranslation,
            onOnlineTranslationCleared = lyricsHooks::clearNativeOnlineTranslation,
            onMissingLyricsSupplementReceived = lyricsHooks::receiveMissingLyricsSupplement,
            onMissingLyricsSupplementCleared = missingLyricsHooks::clearSupplement,
            onOnlineTranslationSourceSwitchResult =
                { requestId, songId, contentType, requestedSource, actualSource, successful ->
                    var effectiveActualSource = actualSource
                    var effectiveSuccessful = successful
                    if (contentType == "lyrics") {
                        val appliedSource = missingLyricsHooks.onLyricsSourceSelectionChanged(
                            songId = songId,
                            source = actualSource,
                            successful = successful,
                        )
                        if (successful && appliedSource != null) {
                            effectiveActualSource = appliedSource
                            if (requestedSource != null && appliedSource != requestedSource) {
                                effectiveSuccessful = false
                            }
                        }
                    }
                    onlineSourceMenuHooks.receiveSourceSwitchResult(
                        requestId,
                        songId,
                        contentType,
                        requestedSource,
                        effectiveActualSource,
                        effectiveSuccessful,
                    )
                },
        ).also { it.start() }
        this.directPlayer = directPlayer
        val helper = runCatching {
            LyriconFactory.createProvider(
                context = runtime.application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = AppleMusicProviderOrchestrator.APPLE_MUSIC_PACKAGE,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            ).also { it.register() }
        }.onFailure {
            ProviderLogger.error("Lyricon Central 提供器注册失败，使用内置直连", it)
        }.getOrNull()
        val activePlayer = helper?.player?.let { CompositeRemotePlayer(it, directPlayer) }
            ?: directPlayer
        lyricRequester = LyricRequester(runtime.hookResolver, runtime.application)
        PlaybackManager.init(
            remotePlayer = activePlayer,
            requester = lyricRequester,
            hookResolver = runtime.hookResolver,
            onMissingLyricsSupplementBuilt = lyricsHooks::receiveModuleMissingLyrics,
            hasKnownNativeLyrics = missingLyricsHooks::hasKnownNativeLyricsFor,
        )
        playbackHooks.attachRemotePlayer(activePlayer)
        playbackHooks.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
    }
}
