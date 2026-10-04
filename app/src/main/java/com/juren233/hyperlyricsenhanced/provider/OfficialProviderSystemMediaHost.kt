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
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanup
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
    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private var trackedController: MediaController? = null
    private var trackedCallback: MediaController.Callback? = null
    private var started = false
    private var generation = 0L
    @Volatile private var released = false
    private var cleanupFailure: Throwable? = null

    override fun subscribe(
        callback: OfficialProviderSystemMediaCallback,
    ): OfficialProviderSystemMediaSubscription {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SystemMedia Provider 订阅必须在主线程创建"
        }
        check(!released) { "SystemMedia host has been released" }
        cleanupFailure?.let { throw it }
        val subscription = Subscription(callback)
        subscriptions += subscription
        if (!started) {
            started = true
            val expectedGeneration = ++generation
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                updateControllers(controllers.orEmpty(), expectedGeneration)
            }
            activeSessionsListener = listener
            manager.addOnActiveSessionsChangedListener(
                listener,
                null,
                handler,
            )
        }
        updateControllers(runCatching { manager.getActiveSessions(null) }.getOrDefault(emptyList()))
        return subscription
    }

    fun release() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SystemMedia host release must finish on the main thread"
        }
        released = true
        subscriptions.forEach { it.released = true }
        subscriptions.clear()
        releaseObservers()
    }

    private fun releaseObservers() {
        val listener = activeSessionsListener
        activeSessionsListener = null
        started = false
        generation++
        val cleanup = RuntimeResourceCleanup()
        cleanup.attempt("previous SystemMedia host cleanup") { cleanupFailure?.let { throw it } }
        cleanup.attempt("SystemMedia queued callbacks") { handler.removeCallbacksAndMessages(null) }
        cleanup.attempt("SystemMedia controller callback") { unregisterTrackedController() }
        cleanup.attempt("SystemMedia session listener") {
            listener?.let(manager::removeOnActiveSessionsChangedListener)
        }
        cleanupFailure = cleanup.failureOrNull()
        cleanupFailure?.let { throw it }
    }

    private fun updateControllers(controllers: List<MediaController>, expectedGeneration: Long = generation) {
        if (released || !started || generation != expectedGeneration || cleanupFailure != null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { updateControllers(controllers, expectedGeneration) }
            return
        }
        val selected = selectController(controllers)
        if (selected?.sessionToken != trackedController?.sessionToken) {
            try {
                unregisterTrackedController()
            } catch (failure: Throwable) {
                // Do not create a replacement until the previous registration is known released.
                cleanupFailure = failure
                return
            }
            trackedController = selected
            if (selected != null) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        if (trackedCallback === this) dispatch()
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (trackedCallback === this) dispatch()
                    }

                    override fun onSessionDestroyed() {
                        if (trackedCallback !== this) return
                        updateControllers(
                            runCatching { manager.getActiveSessions(null) }
                                .getOrDefault(emptyList()), expectedGeneration,
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
        if (released || !started || cleanupFailure != null) return
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
        trackedController = null
        trackedCallback = null
        if (controller != null && callback != null) controller.unregisterCallback(callback)
    }

    private inner class Subscription(
        val callback: OfficialProviderSystemMediaCallback,
    ) : OfficialProviderSystemMediaSubscription {
        @Volatile
        var released = false

        override fun release() {
            if (released) return
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post {
                    runCatching { release() }.onFailure { failure ->
                        // releaseObservers retains the failure for the runtime owner's cleanup.
                        runCatching { Log.w(TAG, "SystemMedia 订阅清理失败", failure) }
                    }
                }
                return
            }
            released = true
            subscriptions.remove(this)
            if (subscriptions.isEmpty()) {
                releaseObservers()
            }
        }
    }

    private companion object {
        const val TAG = "HLEProvider/SystemMedia"
    }
}
