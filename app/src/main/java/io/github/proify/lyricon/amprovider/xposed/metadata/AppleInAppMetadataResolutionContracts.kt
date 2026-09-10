/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal interface AppleInAppMetadataResolutionHost {
    fun currentPlaybackMetadataId(): String?

    fun configuredContentUiLanguage(): Int

    fun shouldOverrideAccountLanguage(selection: Int): Boolean

    fun isRestoreOriginalEnabled(): Boolean

    fun refreshRequestScope()

    fun enrichLibraryEntitiesForResolution(mediaIds: Collection<String>)

    fun applyAliasToMetadataRefs(
        mediaId: String,
        alias: Alias,
        forceRebind: Boolean,
        notifyModelChange: Boolean,
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
    )

    fun logMetadataIdentity(event: String, details: String)

    fun nextTraceSequence(): Long
}

/** 依赖显式注入的默认宿主；trace 序列为根单例构造期值，可安全直捕。 */
internal class DefaultAppleInAppMetadataResolutionHost(
    private val currentPlaybackMetadataIdFn: () -> String?,
    private val configuredContentUiLanguageFn: () -> Int,
    private val shouldOverrideAccountLanguageFn: (Int) -> Boolean,
    private val isRestoreOriginalEnabledFn: () -> Boolean,
    private val refreshRequestScopeFn: () -> Unit,
    private val enrichLibraryEntitiesForResolutionFn: (Collection<String>) -> Unit,
    private val applyAliasToMetadataRefsFn: (String, Alias, Boolean, Boolean) -> Unit,
    private val applyPlaybackMetadataOverrideFn: (
        String, Alias, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean,
    ) -> Unit,
    private val logMetadataIdentityFn: (String, String) -> Unit,
    private val traceSequence: AtomicLong,
) : AppleInAppMetadataResolutionHost {
    override fun currentPlaybackMetadataId(): String? = currentPlaybackMetadataIdFn()

    override fun configuredContentUiLanguage(): Int = configuredContentUiLanguageFn()

    override fun shouldOverrideAccountLanguage(selection: Int): Boolean =
        shouldOverrideAccountLanguageFn(selection)

    override fun isRestoreOriginalEnabled(): Boolean = isRestoreOriginalEnabledFn()

    override fun refreshRequestScope() {
        refreshRequestScopeFn()
    }

    override fun enrichLibraryEntitiesForResolution(mediaIds: Collection<String>) {
        enrichLibraryEntitiesForResolutionFn(mediaIds)
    }

    override fun applyAliasToMetadataRefs(
        mediaId: String,
        alias: Alias,
        forceRebind: Boolean,
        notifyModelChange: Boolean,
    ) {
        applyAliasToMetadataRefsFn(mediaId, alias, forceRebind, notifyModelChange)
    }

    override fun applyPlaybackMetadataOverride(
        mediaId: String,
        alias: Alias,
        forceInAppRebind: Boolean,
        rememberLocalizedArtist: Boolean,
        originalMetadata: Boolean,
        originalMetadataConfirmed: Boolean,
        artistOnly: Boolean,
        propagateArtistEntity: Boolean,
    ) {
        applyPlaybackMetadataOverrideFn(
            mediaId,
            alias,
            forceInAppRebind,
            rememberLocalizedArtist,
            originalMetadata,
            originalMetadataConfirmed,
            artistOnly,
            propagateArtistEntity,
        )
    }

    override fun logMetadataIdentity(event: String, details: String) {
        logMetadataIdentityFn(event, details)
    }

    override fun nextTraceSequence(): Long = traceSequence.incrementAndGet()
}
