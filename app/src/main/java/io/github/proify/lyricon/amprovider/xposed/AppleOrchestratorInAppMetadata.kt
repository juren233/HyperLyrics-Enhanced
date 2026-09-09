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

internal fun AppleMusicProviderOrchestrator.onMetadataPageAttached(owner: Any, recycler: RecyclerView) {
    metadataSurfaceRuntime.onPageAttached(owner, recycler)
}

internal fun AppleMusicProviderOrchestrator.onMetadataPageDetached(owner: Any) {
    metadataSurfaceRuntime.onPageDetached(owner)
}

internal fun AppleMusicProviderOrchestrator.markMetadataVisible(
    mediaIds: Collection<String>,
): AppleMetadataSurfaceCoordinator.SurfaceSnapshot =
    metadataSurfaceRuntime.markVisible(mediaIds)

internal fun AppleMusicProviderOrchestrator.setMetadataPlaybackMediaId(
    mediaId: String?,
): AppleMetadataSurfaceCoordinator.SurfaceSnapshot =
    metadataSurfaceRuntime.setPlaybackMediaId(mediaId)

internal fun AppleMusicProviderOrchestrator.metadataRequestContext(
    mediaId: String,
): AppleMetadataSurfaceCoordinator.RequestContext =
    metadataSurfaceRuntime.requestContext(mediaId)

internal fun AppleMusicProviderOrchestrator.isCurrentMetadataSurfaceMediaId(mediaId: String): Boolean =
    metadataSurfaceRuntime.isCurrentMediaId(mediaId)

internal fun AppleMusicProviderOrchestrator.hasVisibleInAppConsumer(mediaId: String): Boolean =
    metadataSurfaceRuntime.hasVisibleConsumer(mediaId)

internal fun AppleMusicProviderOrchestrator.isRefreshableInAppMediaId(mediaId: String): Boolean =
    metadataSurfaceRuntime.isRefreshable(mediaId)

internal fun AppleMusicProviderOrchestrator.requestPriorityForMediaId(
    mediaId: String,
): RequestPriority = metadataRequestContext(mediaId).priority

internal fun AppleMusicProviderOrchestrator.recordCurrentRecyclerMediaId(mediaId: String): Boolean =
    dataBindingHooks.recordCurrentRecyclerMediaId(mediaId)

internal fun AppleMusicProviderOrchestrator.media3MetadataId(
    metadata: Any,
    fallback: String?,
    trustedFallback: Boolean = false,
): String? = media3MetadataCoordinator.mediaId(metadata, fallback, trustedFallback)

internal fun AppleMusicProviderOrchestrator.media3MetadataDetails(metadata: Any): String =
    media3MetadataCoordinator.details(metadata)

internal fun AppleMusicProviderOrchestrator.activePlaybackMediaIdentity(): ActivePlaybackMediaIdentity =
    media3MetadataCoordinator.activePlaybackIdentity()

internal fun AppleMusicProviderOrchestrator.logMetadataIdentity(
    event: String,
    identity: ActivePlaybackMediaIdentity = activePlaybackMediaIdentity(),
    details: String,
) = media3MetadataCoordinator.logIdentity(event, identity, details)

internal fun AppleMusicProviderOrchestrator.registerInAppMetadata(
    mediaId: String,
    metadata: Any,
    requestResolution: Boolean = true,
    preBind: Boolean = false,
    priority: RequestPriority =
        RequestPriority.ACTIVE_PAGE,
) = metadataRegistrationCoordinator.registerMetadata(
    mediaId = mediaId,
    metadata = metadata,
    requestResolution = requestResolution,
    preBind = preBind,
    priority = priority,
)

internal fun AppleMusicProviderOrchestrator.registerInAppPlaybackItem(
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

internal fun AppleMusicProviderOrchestrator.mergePlaybackAccountMetadata(
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

internal fun AppleMusicProviderOrchestrator.registerInAppContainerItem(
    mediaId: String,
    containerItem: Any,
    kind: InAppContainerKind,
) = metadataRegistrationCoordinator.registerContainerItem(mediaId, containerItem, kind)

internal fun AppleMusicProviderOrchestrator.inAppContainerKind(containerItem: Any): InAppContainerKind? =
    metadataRegistrationCoordinator.containerKind(containerItem)

internal fun AppleMusicProviderOrchestrator.markInAppContainerNavigationItem(
    containerItem: Any,
    kind: InAppContainerKind,
    mediaId: String,
) = metadataRegistrationCoordinator.markContainerNavigationItem(containerItem, kind, mediaId)

internal fun AppleMusicProviderOrchestrator.inAppContainerNavigationBinding(
    containerItem: Any,
): InAppContainerNavigationRef? =
    metadataRegistrationCoordinator.containerNavigationBinding(containerItem)

internal fun AppleMusicProviderOrchestrator.rawContentItemValue(
    contentItem: Any,
    runtimeMember: AppleMusicRuntimeMember,
): Any? = metadataRegistrationCoordinator.rawContentItemValue(contentItem, runtimeMember)

internal fun AppleMusicProviderOrchestrator.inAppPlaybackItemContract(playbackItem: Any): InAppPlaybackItemContract =
    metadataRegistrationCoordinator.playbackItemContract(playbackItem)

internal fun AppleMusicProviderOrchestrator.readInAppPlaybackItemValue(
    playbackItem: Any,
    field: InAppPlaybackItemField,
    contract: InAppPlaybackItemContract = inAppPlaybackItemContract(playbackItem),
): String? = metadataRegistrationCoordinator.readPlaybackItemValue(
    playbackItem = playbackItem,
    field = field,
    contract = contract,
)

internal fun AppleMusicProviderOrchestrator.contentItemCatalogLookupIds(contentItem: Any, mediaId: String): Set<String> =
    metadataRegistrationCoordinator.contentItemCatalogLookupIds(contentItem, mediaId)

internal fun AppleMusicProviderOrchestrator.contentItemArtistCacheKeys(
    contentItem: Any,
    rawArtist: String?,
): Set<String> = metadataRegistrationCoordinator.contentItemArtistCacheKeys(
    contentItem,
    rawArtist,
)

internal fun AppleMusicProviderOrchestrator.contentItemLocalizedEntityType(
    contentItem: Any,
): LocalizedEntityType? =
    metadataRegistrationCoordinator.contentItemLocalizedEntityType(contentItem)

internal fun AppleMusicProviderOrchestrator.applyAliasToInAppMetadataRefs(
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

internal fun AppleMusicProviderOrchestrator.requestInAppLibraryControllerBuild(
    controller: Any,
    strategy: InAppLibraryControllerBuildStrategy,
) = inAppMetadataApplier.requestLibraryControllerBuild(controller, strategy)

internal fun AppleMusicProviderOrchestrator.dataBindingAliasValues(
    mediaId: String,
    alias: Alias,
    binding: Any?,
): DataBindingAliasValues = inAppMetadataApplier.dataBindingAliasValues(
    mediaId = mediaId,
    alias = alias,
    binding = binding,
)

internal fun AppleMusicProviderOrchestrator.applyAliasToInAppContainerItem(
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

internal fun AppleMusicProviderOrchestrator.applyAliasToInAppPlaybackItem(
    playbackItem: Any,
    alias: Alias,
    notifyChange: Boolean = true,
) = inAppMetadataApplier.applyAliasToPlaybackItem(
    playbackItem = playbackItem,
    alias = alias,
    notifyChange = notifyChange,
)

internal fun AppleMusicProviderOrchestrator.applyPlaybackMetadataOverride(
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

