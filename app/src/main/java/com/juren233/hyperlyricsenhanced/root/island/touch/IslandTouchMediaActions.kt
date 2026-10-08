/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.IslandTouchAction
import com.juren233.hyperlyricsenhanced.common.IslandTouchBinding
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaCardClick

internal object IslandTouchMediaActions {
    private const val TAG = "IslandTouch"

    fun controller(context: Context, packageName: String): MediaController? {
        val candidates = context.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(null)
            ?.filter { it.packageName == packageName }.orEmpty()
        if (candidates.size <= 1) return candidates.firstOrNull()
        // playbackState can cross Binder: snapshot once per candidate, not per comparison.
        return candidates.map { it to it.playbackState }
            .maxWithOrNull(compareBy<Pair<MediaController, PlaybackState?>> {
                it.second?.state == PlaybackState.STATE_PLAYING
            }.thenBy { it.second?.lastPositionUpdateTime ?: 0L })?.first
    }

    fun execute(context: Context, packageName: String, controller: MediaController?, binding: IslandTouchBinding,
        source: android.view.View? = null): Boolean {
        if (binding.action == IslandTouchAction.NONE || binding.action == IslandTouchAction.EXPAND_ISLAND ||
            binding.action == IslandTouchAction.OPEN_APP_FREEFORM ||
            binding.action == IslandTouchAction.VOLUME_CONTINUOUS) return false
        return runCatching {
            when (binding.action) {
                IslandTouchAction.NONE, IslandTouchAction.EXPAND_ISLAND, IslandTouchAction.OPEN_APP_FREEFORM,
                IslandTouchAction.VOLUME_CONTINUOUS -> Unit
                IslandTouchAction.OPEN_APP -> {
                    if (!NotificationMediaCardClick.launch(packageName, controller?.sessionToken)) {
                        if (BuildConfig.DEBUG) HookLogger.d(TAG, "当前音乐通知没有可用的点击入口")
                        return@runCatching false
                    }
                }
                IslandTouchAction.COPY_CURRENT_LYRIC -> {
                    return@runCatching IslandTouchLyricClipboard.copy(context, packageName)
                }
                IslandTouchAction.OPEN_MEDIA_OUTPUT -> {
                    return@runCatching IslandMediaOutput.open(source, packageName)
                }
                IslandTouchAction.VOLUME_UP, IslandTouchAction.VOLUME_DOWN, IslandTouchAction.TOGGLE_MUTE -> {
                    val direction = when (binding.action) {
                        IslandTouchAction.VOLUME_UP -> AudioManager.ADJUST_RAISE
                        IslandTouchAction.VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                        else -> AudioManager.ADJUST_TOGGLE_MUTE
                    }
                    if (controller?.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
                        // Remote sessions do not expose a standard mute state. Never mute the
                        // unrelated local stream or restore a guessed remote volume instead.
                        if (binding.action == IslandTouchAction.TOGGLE_MUTE) return@runCatching false
                        controller.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
                    } else {
                        val audio = context.getSystemService(AudioManager::class.java) ?: return@runCatching false
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                    }
                }
                else -> {
                    controller ?: return@runCatching false
                    val controls = controller.transportControls
                    val state = controller.playbackState
                    val supported = state?.actions ?: 0L
                    when (binding.action) {
                        IslandTouchAction.PLAY_PAUSE -> {
                            val playing = state?.state in setOf(PlaybackState.STATE_PLAYING,
                                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)
                            when {
                                playing && supported and PlaybackState.ACTION_PAUSE != 0L -> controls.pause()
                                !playing && supported and PlaybackState.ACTION_PLAY != 0L -> controls.play()
                                else -> mediaKey(controller, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                            }
                        }
                        IslandTouchAction.PREVIOUS -> if (supported and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
                            controls.skipToPrevious() else mediaKey(controller, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        IslandTouchAction.NEXT -> if (supported and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
                            controls.skipToNext() else mediaKey(controller, KeyEvent.KEYCODE_MEDIA_NEXT)
                        IslandTouchAction.FORWARD, IslandTouchAction.BACKWARD, IslandTouchAction.RESTART -> {
                            if (supported and PlaybackState.ACTION_SEEK_TO == 0L) return@runCatching false
                            val position = MediaMetadataHelper.estimatePlaybackPosition(state)
                            val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                            val target = IslandTouchSeekPolicy.target(position, duration, binding) ?: return@runCatching false
                            controls.seekTo(target)
                            if (binding.action == IslandTouchAction.RESTART && supported and PlaybackState.ACTION_PLAY != 0L) {
                                controls.play()
                            }
                        }
                    }
                }
            }
            if (BuildConfig.DEBUG) HookLogger.d(TAG, "action=${binding.action} dispatched")
            true
        }.getOrElse {
            HookLogger.w(TAG, "媒体触控操作失败: ${it.javaClass.simpleName}")
            false
        }
    }

    private fun mediaKey(controller: MediaController, key: Int) {
        // Always send to this island's session; a global media key could control another player.
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }
}
