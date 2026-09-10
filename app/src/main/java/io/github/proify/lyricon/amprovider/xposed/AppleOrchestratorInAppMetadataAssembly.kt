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
 * R6 元数据表面组装群。
 *
 * 持有应用内元数据各页面 Hook、表面运行时、诊断与覆盖应用协调器。
 * 对歌词/播放组装群暴露上述窄入口；其余组件保持构造群私有。
 */
internal class AppleOrchestratorInAppMetadataAssembly(
    private val runtime: AppleMusicProviderRuntime,
    private val catalogLanguage: AppleOrchestratorCatalogLanguageAssembly,
    private val lyricsPlayback: AppleOrchestratorLyricsPlaybackAssembly,
    private val metadataOverrideStore: AppleMetadataOverrideStore,
    private val inAppMetadataRegistry: AppleInAppMetadataRegistry,
    private val metadataTraceSequence: AtomicLong,
) {
    internal lateinit var queueMetadataHooks: AppleQueueMetadataHooks
        private set
    internal lateinit var listenNowHooks: AppleListenNowHooks
        private set
    internal lateinit var librarySurfaceHooks: AppleLibrarySurfaceHooks
        private set
    internal lateinit var dataBindingHooks: AppleDataBindingMetadataHooks
        private set
    internal lateinit var collectionSurfaceHooks: AppleCollectionSurfaceHooks
        private set
    internal lateinit var artistSurfaceHooks: AppleArtistSurfaceHooks
        private set
    internal lateinit var metadataSurfaceRuntime: AppleMetadataSurfaceRuntime
        private set
    internal lateinit var inAppArtworkContinuityHooks: AppleInAppArtworkContinuityHooks
        private set
    internal lateinit var actionSheetMetadataHooks: AppleActionSheetMetadataHooks
        private set
    internal lateinit var playbackItemConversionHooks: ApplePlaybackItemConversionHooks
        private set
    internal lateinit var contentItemMetadataHooks: AppleContentItemMetadataHooks
        private set
    internal lateinit var mediaApiMetadataCoordinator: AppleMediaApiMetadataCoordinator
        private set
    internal lateinit var metadataResolutionCoordinator: AppleInAppMetadataResolutionCoordinator
        private set
    internal lateinit var frameworkMetadataHooks: AppleFrameworkMetadataHooks
        private set
    internal lateinit var visibleMetadataDiagnostics: AppleVisibleMetadataDiagnostics
        private set
    internal lateinit var media3MetadataCoordinator: AppleMedia3MetadataCoordinator
        private set
    internal lateinit var inAppMetadataApplier: AppleInAppMetadataApplier
        private set
    internal lateinit var metadataRegistrationCoordinator: AppleInAppMetadataRegistrationCoordinator
        private set
    internal lateinit var metadataOverrideApplicationCoordinator:
        AppleMetadataOverrideApplicationCoordinator
        private set
    internal lateinit var metadataConfigurationDispatcher: AppleMetadataConfigurationDispatcher
        private set

    fun assemble() {
        queueMetadataHooks = AppleQueueMetadataHooks(
            runtime = runtime,
            metadataStore = metadataOverrideStore,
            host = DefaultAppleQueueMetadataHost(
                activePlaybackIdentityFn = {
                    activePlaybackMediaIdentity()
                },
                logMetadataIdentityFn = { event, identity, details ->
                    logMetadataIdentity(event, identity, details)
                },
                media3MetadataIdFn = { metadata, fallback, trustedFallback ->
                    media3MetadataId(
                        metadata = metadata,
                        fallback = fallback,
                        trustedFallback = trustedFallback,
                    )
                },
                media3MetadataDetailsFn = { metadata ->
                    media3MetadataDetails(metadata)
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
                    readInAppPlaybackItemValue(
                        playbackItem = playbackItem,
                        field = field,
                        contract = contract,
                    )
                },
                markMetadataVisibleFn = { mediaIds ->
                    markMetadataVisible(mediaIds)
                },
                isCurrentMetadataSurfaceMediaIdFn = { mediaId ->
                    isCurrentMetadataSurfaceMediaId(mediaId)
                },
                registry = inAppMetadataRegistry,
            ),
        )
        listenNowHooks = AppleListenNowHooks(
            runtime = runtime,
            metadataStore = metadataOverrideStore,
            catalogResolver = catalogLanguage.internalCatalogResolver,
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
                isRestoreOriginalMetadataEnabledFn = { catalogLanguage.isRestoreCjkOriginalMetadataEnabled() },
                shouldRetryOriginalMetadataCacheProbeFn = { mediaId ->
                    shouldRetryOriginalMetadataCacheProbeFor(mediaId)
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
                    markMetadataVisible(mediaIds)
                },
                scheduleMetadataResolutionFn = { mediaIds, priority, originalResolutionMode ->
                    metadataResolutionCoordinator.schedule(
                        mediaIds = mediaIds,
                        priority = priority,
                        originalResolutionMode = originalResolutionMode,
                    )
                },
                logMetadataIdentityFn = { event, details ->
                    logMetadataIdentity(
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
                    mergePlaybackAccountMetadata(
                        mediaId = mediaId,
                        title = title,
                        artist = artist,
                        reconcileArtistAssociations = false,
                    )
                },
                requestPriorityForMediaIdFn = { mediaId ->
                    requestPriorityForMediaId(mediaId)
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
                    recordCurrentRecyclerMediaId(mediaId)
                },
                effectiveAliasFn = { mediaId ->
                    metadataResolutionCoordinator.effectiveAlias(mediaId)
                },
                normalizeMediaIdsFn = { mediaIds ->
                    normalizedRecyclerBindingMediaIds(mediaIds).toList()
                },
                markMetadataVisibleFn = { mediaIds ->
                    markMetadataVisible(mediaIds)
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
                    logMetadataIdentity(event = event, details = details)
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
                    markMetadataVisible(mediaIds)
                },
                scheduleMetadataResolutionFn = { mediaIds, priority, originalResolutionMode ->
                    metadataResolutionCoordinator.schedule(
                        mediaIds = mediaIds,
                        priority = priority,
                        originalResolutionMode = originalResolutionMode,
                    )
                },
                isAppleLyricsRecyclerAdapterFn = { adapter ->
                    lyricsPlayback.lyricsHooks.isAppleLyricsRecyclerAdapter(adapter)
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
                    markMetadataVisible(mediaIds)
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
                    dataBindingAliasValues(
                        mediaId = mediaId,
                        alias = alias,
                        binding = binding,
                    )
                },
                sharedAssociatedArtistIdFn = { mediaId ->
                    metadataResolutionCoordinator.sharedAssociatedArtistId(mediaId)
                },
                onMetadataPageAttachedFn = { owner, recycler ->
                    onMetadataPageAttached(owner, recycler)
                },
                onMetadataPageDetachedFn = { owner ->
                    onMetadataPageDetached(owner)
                },
                handleArtistFinalBindingFn = { model, finalHolder, position ->
                    artistSurfaceHooks.handleFinalBinding(model, finalHolder, position)
                },
                logMetadataIdentityFn = { event, details ->
                    logMetadataIdentity(
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
                    markMetadataVisible(mediaIds)
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
                    onMetadataPageAttached(owner, recycler)
                },
                onMetadataPageDetachedFn = { owner ->
                    onMetadataPageDetached(owner)
                },
                logMetadataIdentityFn = { event, details ->
                    logMetadataIdentity(
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
                    catalogLanguage.catalogResolverOrNull
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
                    logMetadataIdentity(
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
                    logMetadataIdentity(
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
                    markMetadataVisible(mediaIds)
                },
                rawContentItemValueFn = { contentItem, runtimeMember ->
                    rawContentItemValue(
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
                        logMetadataIdentity(
                            event = event,
                            details = details,
                        )
                    } else {
                        logMetadataIdentity(
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
                    logMetadataIdentity(
                        event = event,
                        identity = identity,
                        details = details,
                    )
                },
                markContainerNavigationItemFn = { containerItem, kind, mediaId ->
                    markInAppContainerNavigationItem(containerItem, kind, mediaId)
                },
                markMetadataVisibleFn = { mediaIds ->
                    markMetadataVisible(mediaIds)
                },
                registerContainerItemFn = { mediaId, containerItem, kind ->
                    registerInAppContainerItem(mediaId, containerItem, kind)
                },
                effectiveAliasFn = { mediaId ->
                    metadataResolutionCoordinator.effectiveAlias(mediaId)
                },
                applyAliasToContainerItemFn = { containerItem, kind, alias ->
                    applyAliasToInAppContainerItem(
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
                    applyAliasToInAppPlaybackItem(
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
                    recordCurrentRecyclerMediaId(mediaId)
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
            catalogResolver = catalogLanguage.internalCatalogResolver,
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
                    markMetadataVisible(mediaIds)
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
                    catalogLanguage.configuredContentUiLanguage()
                },
                traceSequence = metadataTraceSequence,
            ),
        )
        metadataResolutionCoordinator = AppleInAppMetadataResolutionCoordinator(
            runtime = runtime,
            metadataStore = metadataOverrideStore,
            catalogResolver = catalogLanguage.internalCatalogResolver,
            host = DefaultAppleInAppMetadataResolutionHost(
                currentPlaybackMetadataIdFn = { lyricsPlayback.playbackMetadataCoordinator.currentMetadataId() },
                configuredContentUiLanguageFn = { catalogLanguage.configuredContentUiLanguage() },
                shouldOverrideAccountLanguageFn = { selection ->
                    catalogLanguage.shouldOverrideAccountLanguage(selection)
                },
                isRestoreOriginalEnabledFn = { catalogLanguage.isRestoreCjkOriginalMetadataEnabled() },
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
                    logMetadataIdentity(
                        event = event,
                        details = details,
                    )
                },
                traceSequence = metadataTraceSequence,
            ),
        )
        frameworkMetadataHooks = AppleFrameworkMetadataHooks(
            runtime = runtime,
            preferences = { catalogLanguage.contentUiLanguagePrefs },
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
        media3MetadataCoordinator = AppleMedia3MetadataCoordinator(
            runtime = runtime,
            metadataStore = metadataOverrideStore,
            resolutionCoordinator = metadataResolutionCoordinator,
            frameworkMetadataHooks = frameworkMetadataHooks,
            queueMetadataHooks = queueMetadataHooks,
            playbackMetadataCoordinator = lyricsPlayback.playbackMetadataCoordinator,
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
            catalogResolver = catalogLanguage.internalCatalogResolver,
            contentItemMetadataHooks = contentItemMetadataHooks,
            metadataApplier = inAppMetadataApplier,
            surfaceRuntime = metadataSurfaceRuntime,
            dataBindingHooks = dataBindingHooks,
            configuredContentUiLanguage = catalogLanguage::configuredContentUiLanguage,
        )
        metadataOverrideApplicationCoordinator =
            AppleMetadataOverrideApplicationCoordinator(
                runtime = runtime,
                metadataStore = metadataOverrideStore,
                registry = inAppMetadataRegistry,
                resolutionCoordinator = metadataResolutionCoordinator,
                catalogResolver = catalogLanguage.internalCatalogResolver,
                surfaceRuntime = metadataSurfaceRuntime,
                metadataApplier = inAppMetadataApplier,
                librarySurfaceHooks = librarySurfaceHooks,
                dataBindingHooks = dataBindingHooks,
                listenNowHooks = listenNowHooks,
                actionSheetMetadataHooks = actionSheetMetadataHooks,
                playbackMetadataCoordinator = lyricsPlayback.playbackMetadataCoordinator,
                frameworkMetadataHooks = frameworkMetadataHooks,
                visibleMetadataDiagnostics = visibleMetadataDiagnostics,
                media3MetadataCoordinator = media3MetadataCoordinator,
                configuredContentUiLanguage = catalogLanguage::configuredContentUiLanguage,
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
                runtime.mainHandler.post {
                    frameworkMetadataHooks.restoreMediaSessionMetadata()
                    frameworkMetadataHooks.restoreMediaSessionQueue()
                    inAppMetadataApplier.refreshMetadataCallbacks()
                    lyricsPlayback.playbackHooks.activePlayer()?.let { mediaPlayer ->
                        lyricsPlayback.playbackMetadataCoordinator.refreshCurrentQueueItem(
                            mediaPlayer,
                            "Apple Music 语言覆盖设置变更",
                        )
                    }
                }
            },
        )
    }

    // ————————————————————— 跨组装群窄入口（保持原 Orchestrator 扩展语义） —————————————————————

    fun markMetadataVisible(mediaIds: Collection<String>) =
        metadataSurfaceRuntime.markVisible(mediaIds)

    fun setMetadataPlaybackMediaId(mediaId: String?) =
        metadataSurfaceRuntime.setPlaybackMediaId(mediaId)

    fun metadataRequestContext(mediaId: String) =
        metadataSurfaceRuntime.requestContext(mediaId)

    fun isCurrentMetadataSurfaceMediaId(mediaId: String): Boolean =
        metadataSurfaceRuntime.isCurrentMediaId(mediaId)

    fun hasVisibleInAppConsumer(mediaId: String): Boolean =
        metadataSurfaceRuntime.hasVisibleConsumer(mediaId)

    fun isRefreshableInAppMediaId(mediaId: String): Boolean =
        metadataSurfaceRuntime.isRefreshable(mediaId)

    fun requestPriorityForMediaId(mediaId: String): RequestPriority =
        metadataRequestContext(mediaId).priority

    fun onMetadataPageAttached(owner: Any, recycler: RecyclerView) =
        metadataSurfaceRuntime.onPageAttached(owner, recycler)

    fun onMetadataPageDetached(owner: Any) =
        metadataSurfaceRuntime.onPageDetached(owner)

    fun recordCurrentRecyclerMediaId(mediaId: String): Boolean =
        dataBindingHooks.recordCurrentRecyclerMediaId(mediaId)

    fun media3MetadataId(
        metadata: Any,
        fallback: String?,
        trustedFallback: Boolean = false,
    ): String? = media3MetadataCoordinator.mediaId(metadata, fallback, trustedFallback)

    fun media3MetadataDetails(metadata: Any): String =
        media3MetadataCoordinator.details(metadata)

    fun activePlaybackMediaIdentity(): ActivePlaybackMediaIdentity =
        media3MetadataCoordinator.activePlaybackIdentity()

    fun logMetadataIdentity(
        event: String,
        identity: ActivePlaybackMediaIdentity = activePlaybackMediaIdentity(),
        details: String,
    ) = media3MetadataCoordinator.logIdentity(event, identity, details)

    fun registerInAppMetadata(
        mediaId: String,
        metadata: Any,
        requestResolution: Boolean = true,
        preBind: Boolean = false,
        priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
    ) = metadataRegistrationCoordinator.registerMetadata(
        mediaId = mediaId,
        metadata = metadata,
        requestResolution = requestResolution,
        preBind = preBind,
        priority = priority,
    )

    fun registerInAppPlaybackItem(
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

    fun mergePlaybackAccountMetadata(
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

    fun registerInAppContainerItem(
        mediaId: String,
        containerItem: Any,
        kind: InAppContainerKind,
    ) = metadataRegistrationCoordinator.registerContainerItem(mediaId, containerItem, kind)

    fun inAppContainerKind(containerItem: Any): InAppContainerKind? =
        metadataRegistrationCoordinator.containerKind(containerItem)

    fun markInAppContainerNavigationItem(
        containerItem: Any,
        kind: InAppContainerKind,
        mediaId: String,
    ) = metadataRegistrationCoordinator.markContainerNavigationItem(containerItem, kind, mediaId)

    fun inAppContainerNavigationBinding(
        containerItem: Any,
    ): InAppContainerNavigationRef? =
        metadataRegistrationCoordinator.containerNavigationBinding(containerItem)

    fun rawContentItemValue(
        contentItem: Any,
        runtimeMember: AppleMusicRuntimeMember,
    ): Any? = metadataRegistrationCoordinator.rawContentItemValue(contentItem, runtimeMember)

    fun inAppPlaybackItemContract(playbackItem: Any): InAppPlaybackItemContract =
        metadataRegistrationCoordinator.playbackItemContract(playbackItem)

    fun readInAppPlaybackItemValue(
        playbackItem: Any,
        field: InAppPlaybackItemField,
        contract: InAppPlaybackItemContract = inAppPlaybackItemContract(playbackItem),
    ): String? = metadataRegistrationCoordinator.readPlaybackItemValue(
        playbackItem = playbackItem,
        field = field,
        contract = contract,
    )

    fun contentItemCatalogLookupIds(contentItem: Any, mediaId: String): Set<String> =
        metadataRegistrationCoordinator.contentItemCatalogLookupIds(contentItem, mediaId)

    fun contentItemArtistCacheKeys(
        contentItem: Any,
        rawArtist: String?,
    ): Set<String> = metadataRegistrationCoordinator.contentItemArtistCacheKeys(
        contentItem,
        rawArtist,
    )

    fun contentItemLocalizedEntityType(contentItem: Any): LocalizedEntityType? =
        metadataRegistrationCoordinator.contentItemLocalizedEntityType(contentItem)

    fun applyAliasToInAppMetadataRefs(
        mediaId: String,
        alias: Alias,
        forceRebind: Boolean = true,
        notifyModelChange: Boolean = true,
    ) = inAppMetadataApplier.applyAliasToMetadataRefs(
        mediaId = mediaId,
        alias = alias,
        forceRebind = forceRebind,
        notifyModelChange = notifyModelChange,
    )

    fun requestInAppLibraryControllerBuild(
        controller: Any,
        strategy: InAppLibraryControllerBuildStrategy,
    ) = inAppMetadataApplier.requestLibraryControllerBuild(controller, strategy)

    fun dataBindingAliasValues(
        mediaId: String,
        alias: Alias,
        binding: Any?,
    ): DataBindingAliasValues = inAppMetadataApplier.dataBindingAliasValues(
        mediaId = mediaId,
        alias = alias,
        binding = binding,
    )

    fun applyAliasToInAppContainerItem(
        containerItem: Any,
        kind: InAppContainerKind,
        alias: Alias,
        notifyChange: Boolean = true,
    ) = inAppMetadataApplier.applyAliasToContainerItem(
        containerItem = containerItem,
        kind = kind,
        alias = alias,
        notifyChange = notifyChange,
    )

    fun applyAliasToInAppPlaybackItem(
        playbackItem: Any,
        alias: Alias,
        notifyChange: Boolean = true,
    ) = inAppMetadataApplier.applyAliasToPlaybackItem(
        playbackItem = playbackItem,
        alias = alias,
        notifyChange = notifyChange,
    )

    fun applyPlaybackMetadataOverride(
        mediaId: String,
        alias: Alias,
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

    // ————————————————————— 歌词/播放组装群（B）使用的窄入口 —————————————————————

    fun ensureContentItemMetadataHooks(contentItemClass: Class<*>) =
        contentItemMetadataHooks.ensureHooks(contentItemClass)

    fun effectiveMetadataAlias(mediaId: String): Alias? =
        metadataResolutionCoordinator.effectiveAlias(mediaId)

    fun shouldShareOriginalSongLanguage(
        localizedTitle: String?,
        localizedArtist: String?,
        alias: Alias?,
    ): Boolean = metadataResolutionCoordinator.shouldShareOriginalSongLanguage(
        localizedTitle = localizedTitle,
        localizedArtist = localizedArtist,
        alias = alias,
    )

    fun rememberOriginalLanguageForArtist(mediaId: String, language: String) =
        metadataResolutionCoordinator.rememberOriginalLanguageForArtist(mediaId, language)

    fun dataBindingFromHolder(holder: Any?): Any? = dataBindingHooks.bindingFromHolder(holder)

    fun refreshMetadataCallbacks(mediaId: String?) =
        inAppMetadataApplier.refreshMetadataCallbacks(mediaId = mediaId)

    fun refreshPlaybackItemBindings(mediaId: String?) =
        inAppMetadataApplier.refreshPlaybackItemBindings(mediaId)

    fun shouldRequestInAppMetadataOverride(mediaId: String): Boolean =
        metadataResolutionCoordinator.shouldRequestOverride(mediaId)

    /** 与旧 Orchestrator 扩展一致：读取当前解析状态与未命中时间，不缓存。 */
    fun shouldRetryOriginalMetadataCacheProbeFor(mediaId: String): Boolean =
        shouldRetryOriginalMetadataCacheProbe(
            originalResolved = metadataOverrideStore.isOriginalResolved(mediaId),
            lastMissUptimeMillis = metadataOverrideStore.originalCacheMissUptimeMillis(mediaId),
            nowUptimeMillis = SystemClock.uptimeMillis(),
        )

}
