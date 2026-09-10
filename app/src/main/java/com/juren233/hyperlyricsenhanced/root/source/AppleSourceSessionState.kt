/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager

/**
 * Owns the SystemUI-side local media-session tracker registration and the cached
 * [AudioManager].
 *
 * Install/remove stay paired here so the listener cannot be dropped without its manager
 * (and vice versa), and the fallback path clears both fields as one unit. [register] must run
 * on the main thread like the inline code it replaces; it never holds a monitor across the
 * system-service calls.
 */
internal class AppleLocalMediaSessionState {
    private var manager: MediaSessionManager? = null
    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var audio: AudioManager? = null

    val isRegistered: Boolean get() = listener != null

    fun currentManager(): MediaSessionManager? = manager

    fun currentListener(): MediaSessionManager.OnActiveSessionsChangedListener? = listener

    /**
     * Installs the listener and stores the pair. [onFailure] runs when the platform call
     * throws, after both fields are reset, mirroring the original fallback.
     */
    fun register(
        context: Context,
        onSessions: (Set<String>?) -> Unit,
        onFailure: (Throwable) -> Unit,
        onRegistered: () -> Unit,
    ) {
        if (listener != null) return
        runCatching {
            val service = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val activeListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                onSessions(controllers?.mapNotNull { it.packageName }?.toSet())
            }
            service.addOnActiveSessionsChangedListener(activeListener, null)
            manager = service
            listener = activeListener
            onSessions(service.getActiveSessions(null).mapNotNull { it.packageName }.toSet())
            onRegistered()
        }.onFailure { error ->
            manager = null
            listener = null
            onFailure(error)
        }
    }

    /** Removes the listener from its manager, if both are present. */
    fun unregister() {
        val currentManager = manager
        val currentListener = listener
        if (currentManager != null && currentListener != null) {
            runCatching { currentManager.removeOnActiveSessionsChangedListener(currentListener) }
        }
        manager = null
        listener = null
    }

    /** True when audio is currently playing; defaults to true while the service is unknown. */
    fun isAnyMusicActive(context: Context): Boolean {
        if (audio == null) {
            audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
        return audio?.isMusicActive ?: true
    }
}

/**
 * Owns the de-duplication key for the "原名"（Apple 原始标题/歌手）metadata lookup request.
 *
 * 只保存一个 mediaId：登记与判断在同一控制点，切歌与偏好变更按现有入口清理。
 */
internal class AppleOriginalMetadataRequestKey {
    private var mediaId: String? = null

    fun isCurrent(candidate: String?): Boolean = mediaId == candidate

    fun register(value: String?) {
        mediaId = value
    }

    fun clear() {
        mediaId = null
    }
}
