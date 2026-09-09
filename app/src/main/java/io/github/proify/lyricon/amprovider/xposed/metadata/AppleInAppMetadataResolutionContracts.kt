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

