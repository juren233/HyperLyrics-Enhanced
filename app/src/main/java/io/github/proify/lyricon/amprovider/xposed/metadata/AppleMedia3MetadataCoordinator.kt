/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.media.MediaMetadata
import android.os.Bundle
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/** Resolves Media3 metadata identity and owns the shared metadata-chain diagnostics. */
internal class AppleMedia3MetadataCoordinator(
    runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val resolutionCoordinator: AppleInAppMetadataResolutionCoordinator,
    private val frameworkMetadataHooks: io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks,
    private val queueMetadataHooks: AppleQueueMetadataHooks,
    private val playbackMetadataCoordinator: ApplePlaybackMetadataCoordinator,
    private val traceSequence: AtomicLong,
) {
    private val metadataTarget = runtime.hookResolver.resolveMethod(
        AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT,
    ).target

    fun mediaId(
        metadata: Any,
        fallback: String?,
        trustedFallback: Boolean = false,
    ): String? {
        val bundleId = runCatching {
            (AppleReflection.field(
                metadata,
                member(AppleMusicRuntimeMember.MEDIA3_METADATA_BUNDLE_FIELD),
            ) as? Bundle)?.getString(MEDIA3_METADATA_ID_KEY)
        }.getOrNull()
        bundleId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.let { return it }
        accountMatches(metadata).singleOrNull()?.let { return it }
        return fallback
            ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?.takeIf { trustedFallback || matchesId(metadata, it) }
    }

    fun details(metadata: Any): String {
        val bundleId = runCatching {
            (AppleReflection.field(
                metadata,
                member(AppleMusicRuntimeMember.MEDIA3_METADATA_BUNDLE_FIELD),
            ) as? Bundle)?.getString(MEDIA3_METADATA_ID_KEY)
        }.getOrNull()
        val title = runCatching {
            AppleReflection.field(
                metadata,
                member(AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD),
            )
        }.getOrNull()
        val artist = runCatching {
            AppleReflection.field(
                metadata,
                member(AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD),
            )
        }.getOrNull()
        val matches = accountMatches(metadata)
        return "bundleId=$bundleId, title=$title, artist=$artist, " +
            "accountMatches=$matches, matchCount=${matches.size}"
    }

    fun activePlaybackIdentity(): ActivePlaybackMediaIdentity {
        val candidates = listOf(
            "queue" to playbackMetadataCoordinator.currentMetadataId(),
            "in_app_now_playing" to queueMetadataHooks.currentNowPlayingRefresh()?.mediaId,
            "framework_session" to frameworkMetadataHooks.currentMediaId(),
            "playback_refresh" to playbackMetadataCoordinator.currentRefreshMediaId(),
        )
        val selected = candidates.firstOrNull { (_, mediaId) ->
            !mediaId.isNullOrBlank() && mediaId.all(Char::isDigit)
        }
        return ActivePlaybackMediaIdentity(
            mediaId = selected?.second,
            source = selected?.first ?: "none",
            candidates = candidates.joinToString(prefix = "[", postfix = "]") { (source, id) ->
                "$source=$id"
            },
        )
    }

    fun logIdentity(
        event: String,
        identity: ActivePlaybackMediaIdentity = activePlaybackIdentity(),
        details: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val sequence = traceSequence.incrementAndGet()
        val alias = identity.mediaId?.let(resolutionCoordinator::effectiveAlias)
        ProviderLogger.info(
            "Apple Music 元数据链路: seq=$sequence, event=$event, " +
                "selected=${identity.mediaId}, source=${identity.source}, " +
                "candidates=${identity.candidates}, aliasHit=${alias != null}, " +
                "alias=${alias?.title}/${alias?.artist}/${alias?.album}, $details"
        )
    }

    private fun matchesId(metadata: Any, mediaId: String): Boolean {
        val title = textField(metadata, AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD)
        val artist = textField(metadata, AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD)
        if (title == null && artist == null) return false

        val account = metadataStore.accountMetadata(mediaId)
        val alias = resolutionCoordinator.effectiveAlias(mediaId)
        val cached = MediaMetadataCache.getMetadataById(mediaId)
        val framework = frameworkMetadataHooks.originalMetadata(mediaId)
        val knownTitles = sequenceOf(
            account?.title,
            alias?.title,
            cached?.title,
            framework?.getString(MediaMetadata.METADATA_KEY_TITLE),
            framework?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        ).filterNotNull().filter(String::isNotBlank).toSet()
        val knownArtists = sequenceOf(
            account?.artist,
            alias?.artist,
            cached?.artist,
            framework?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            framework?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
        ).filterNotNull().filter(String::isNotBlank).toSet()
        return (title == null || title in knownTitles) &&
            (artist == null || artist in knownArtists)
    }

    private fun accountMatches(metadata: Any): List<String> {
        val title = textField(metadata, AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD)
            ?: return emptyList()
        val artist = textField(metadata, AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD)
            ?: return emptyList()
        return metadataStore.accountMetadataSnapshot().entries.mapNotNull { (mediaId, account) ->
            val alias = resolutionCoordinator.effectiveAlias(mediaId)
            val titleMatches = title == account.title || title == alias?.title
            val artistMatches = artist == account.artist || artist == alias?.artist
            mediaId.takeIf { titleMatches && artistMatches }
        }
    }

    private fun textField(metadata: Any, runtimeMember: AppleMusicRuntimeMember): String? =
        runCatching {
            AppleReflection.field(metadata, member(runtimeMember)) as? CharSequence
        }.getOrNull()?.toString()?.takeIf(String::isNotBlank)

    private fun member(runtimeMember: AppleMusicRuntimeMember): String =
        metadataTarget.runtimeMemberName(runtimeMember)

    private companion object {
        const val MEDIA3_METADATA_ID_KEY = Constants.APPLE_MEDIA3_METADATA_ID_KEY
    }
}
