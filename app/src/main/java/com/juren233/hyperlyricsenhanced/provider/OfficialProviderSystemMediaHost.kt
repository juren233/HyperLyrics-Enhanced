/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * SystemUI-side MediaSession observer exposed to SystemMedia Provider Packs.
 * It never loads classes from, or installs hooks into, the player process.
 */
internal class OfficialProviderSystemMediaHostImpl(
    override val application: Application,
    override val playerPackageName: String,
) : OfficialProviderSystemMediaHost {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = application.getSystemService(
        Application.MEDIA_SESSION_SERVICE,
    ) as MediaSessionManager
    private val subscriptions = CopyOnWriteArraySet<Subscription>()
    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateControllers(controllers.orEmpty())
        }

    private var trackedController: MediaController? = null
    private var trackedCallback: MediaController.Callback? = null
    private var started = false

    override fun subscribe(
        callback: OfficialProviderSystemMediaCallback,
    ): OfficialProviderSystemMediaSubscription {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SystemMedia Provider 订阅必须在主线程创建"
        }
        val subscription = Subscription(callback)
        subscriptions += subscription
        if (!started) {
            started = true
            manager.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                null,
                handler,
            )
        }
        updateControllers(runCatching { manager.getActiveSessions(null) }.getOrDefault(emptyList()))
        return subscription
    }

    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(::release)
            return
        }
        subscriptions.forEach { it.released = true }
        subscriptions.clear()
        unregisterTrackedController()
        if (started) {
            runCatching { manager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
            started = false
        }
    }

    private fun updateControllers(controllers: List<MediaController>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { updateControllers(controllers) }
            return
        }
        val selected = selectController(controllers)
        if (selected?.sessionToken != trackedController?.sessionToken) {
            unregisterTrackedController()
            trackedController = selected
            if (selected != null) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = dispatch()

                    override fun onPlaybackStateChanged(state: PlaybackState?) = dispatch()

                    override fun onSessionDestroyed() {
                        updateControllers(
                            runCatching { manager.getActiveSessions(null) }
                                .getOrDefault(emptyList()),
                        )
                    }
                }
                trackedCallback = callback
                runCatching { selected.registerCallback(callback, handler) }
                    .onFailure {
                        Log.w(TAG, "SystemMedia MediaController 回调注册失败", it)
                    }
            }
        }
        dispatch()
    }

    private fun selectController(controllers: List<MediaController>): MediaController? {
        var latest: MediaController? = null
        var latestUpdate = Long.MIN_VALUE
        controllers.forEach { controller ->
            if (controller.packageName != playerPackageName) return@forEach
            val state = controller.playbackState
            if (state?.state == PlaybackState.STATE_PLAYING) {
                latest = controller
                latestUpdate = Long.MAX_VALUE
                return@forEach
            }
            val update = state?.lastPositionUpdateTime ?: 0L
            if (latest == null || update > latestUpdate) {
                latest = controller
                latestUpdate = update
            }
        }
        return latest
    }

    private fun dispatch() {
        val controller = trackedController
        val metadata = controller?.metadata
        val state = controller?.playbackState
        subscriptions.forEach { subscription ->
            if (!subscription.released) {
                runCatching { subscription.callback.onMediaChanged(metadata, state) }
                    .onFailure {
                        Log.w(TAG, "SystemMedia Provider 回调失败", it)
                    }
            }
        }
    }

    private fun unregisterTrackedController() {
        val controller = trackedController
        val callback = trackedCallback
        if (controller != null && callback != null) {
            runCatching { controller.unregisterCallback(callback) }
        }
        trackedController = null
        trackedCallback = null
    }

    private inner class Subscription(
        val callback: OfficialProviderSystemMediaCallback,
    ) : OfficialProviderSystemMediaSubscription {
        @Volatile
        var released = false

        override fun release() {
            if (released) return
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post { release() }
                return
            }
            released = true
            subscriptions.remove(this)
            if (subscriptions.isEmpty()) {
                unregisterTrackedController()
                if (started) {
                    runCatching { manager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
                    started = false
                }
            }
        }
    }

    private companion object {
        const val TAG = "HLEProvider/SystemMedia"
    }
}
