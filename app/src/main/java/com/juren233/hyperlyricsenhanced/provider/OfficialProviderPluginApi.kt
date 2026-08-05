/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState

/**
 * Stable boundary implemented by code loaded from an official Provider Pack.
 *
 * This API deliberately contains no libxposed types. The static host owns every
 * Xposed API call, which keeps Pack code compatible with runtime API protection.
 */
interface OfficialProviderPlugin {
    fun install(host: OfficialProviderHost)
}

interface OfficialProviderHost {
    val packageName: String

    fun hookApplication(callback: OfficialProviderApplicationCallback)

    fun hookMediaSession(
        playbackStateCallback: OfficialProviderPlaybackStateCallback,
        metadataCallback: OfficialProviderMetadataCallback,
    )
}

fun interface OfficialProviderApplicationCallback {
    fun onApplicationCreated(application: Application)
}

fun interface OfficialProviderPlaybackStateCallback {
    fun onPlaybackStateChanged(state: PlaybackState?)
}

fun interface OfficialProviderMetadataCallback {
    fun onMetadataChanged(metadata: MediaMetadata?)
}
