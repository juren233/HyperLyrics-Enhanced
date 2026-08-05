/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.media.session.PlaybackState as AndroidPlaybackState
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.PlaybackPositionSource
import io.github.proify.lyricon.amprovider.xposed.PlaybackState
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.resolvePlaybackPositionSource
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class ApplePlaybackHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val isAodLyricsEnabled: () -> Boolean,
    private val currentMetadataId: () -> String?,
    private val currentLyricsSongId: () -> String?,
    private val queueItemMediaId: (Any) -> String?,
    private val refreshCurrentQueueItem: (Any?, String) -> Unit,
) {
    @Volatile
    private var playbackPositionSource: PlaybackPositionSource? = null
    @Volatile
    private var activePlaybackPlayer: Any? = null
    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    private var progressJob: Job? = null
    private var remotePlayer: RemotePlayer? = null
    private var playing = false
    private var zeroPositionReadCount = 0
    private var hasLoggedNonZeroPosition = false
    private var lastTimingSamplePosition = -1L
    private var lastTimingSampleAtMs = 0L
    private var lastTimingTraceAtMs = 0L
    private var lastTimingStateSignature: String? = null
    private var lastExplicitSeekAtMs = 0L
    private var lastExplicitSeekPosition = -1L
    private var lastPlaybackAnchorAtMs = 0L
    private lateinit var exoTarget: AppleMusicHookTarget
    private val playbackTarget by lazy {
        runtime.hookResolver.resolveClass(
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE
        ).target
    }

    fun attachRemotePlayer(player: RemotePlayer) {
        remotePlayer = player
    }

    fun setDisplayTranslation(selected: Boolean) {
        remotePlayer?.setDisplayTranslation(selected)
    }

    fun initializeScreenStateMonitor() {
        ScreenStateMonitor.initialize(runtime.application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (playing) resumeCoroutineTask()
            }

            override fun onScreenOff() {
                if (playing && isAodLyricsEnabled()) resumeCoroutineTask()
                else pauseCoroutineTask()
            }

            override fun onScreenUnlocked() {
                if (playing && progressJob == null) resumeCoroutineTask()
            }
        })
    }

    fun onAodPreferenceChanged() {
        if (ScreenStateMonitor.state != ScreenStateMonitor.ScreenState.OFF) return
        if (playing && isAodLyricsEnabled()) resumeCoroutineTask()
        else pauseCoroutineTask()
    }

    fun installExoMediaPlayer() {
        val resolvedExo = runtime.hookResolver.resolveClass(AppleMusicHookPoint.EXO_MEDIA_PLAYER)
        exoTarget = resolvedExo.target
        val exoPlayerClass = resolvedExo.clazz
        exoPlayerClass.declaredConstructors.forEach { constructor ->
            runtime.hookRegistrar.installHook(constructor, after = { chain, _ ->
                capturePlaybackPositionSource(
                    mediaPlayer = chain.thisObject,
                    source = "ExoMediaPlayer.<init>",
                    replace = false,
                )
            })
        }
        hookExoPlaybackLifecycle(exoPlayerClass)

        val seekMethod = AppleReflection.findMethod(
            exoPlayerClass,
            member(AppleMusicRuntimeMember.EXO_SEEK_METHOD),
            parameterCount = 1,
        )
        runtime.hookRegistrar.installHook(seekMethod, after = { chain, _ ->
            val position = chain.args.firstOrNull() as? Long ?: 0L
            if (BuildConfig.DEBUG) {
                lastExplicitSeekAtMs = SystemClock.elapsedRealtime()
                lastExplicitSeekPosition = position
                ProviderLogger.diagnostic(
                    "Timing seek: requested=$position, callbackPlayer=" +
                        "${chain.thisObject?.let(System::identityHashCode)}, " +
                        "activePlayer=${activePlaybackPlayer?.let(System::identityHashCode)}, " +
                        "sourcePlayer=${playbackPositionSource?.player?.let(System::identityHashCode)}"
                )
            }
            if (playing) remotePlayer?.seekTo(position)
        })

        val stateMethod = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE
        ).method
        runtime.hookRegistrar.installHook(stateMethod, after = { chain, _ ->
            val activeMediaPlayer = chain.args.firstOrNull()
            val playbackState = PlaybackState.of(chain.args.getOrNull(2) as? Int ?: -1)
            ProviderLogger.diagnostic(
                "Timing lifecycle: source=onPlaybackStateChanged, state=$playbackState, " +
                    "callbackPlayer=${activeMediaPlayer?.let(System::identityHashCode)}, " +
                    "activePlayer=${activePlaybackPlayer?.let(System::identityHashCode)}, " +
                    "sourcePlayer=${playbackPositionSource?.player?.let(System::identityHashCode)}"
            )
            when (playbackState) {
                PlaybackState.PLAYING -> {
                    activatePlaybackPlayer(
                        mediaPlayer = activeMediaPlayer,
                        source = "LocalMediaPlayerController.onPlaybackStateChanged",
                    )
                    refreshCurrentQueueItem(activeMediaPlayer, "onPlaybackStateChanged")
                    startSyncAction()
                }
                else -> {
                    if (activePlaybackPlayer === activeMediaPlayer) stopSyncAction()
                }
            }
        })
    }

    fun isPlaying(): Boolean = playing

    fun activePlayer(): Any? = activePlaybackPlayer

    fun currentPositionMs(): Long? =
        runCatching { playbackPositionSource?.readPosition() }.getOrNull()
            ?: lastTimingSamplePosition.takeIf { it >= 0L }

    private fun startSyncAction() {
        if (playing) return
        playing = true
        currentPositionMs()?.let { publishPlaybackAnchor(it, playing = true, force = true) }
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        playing = false
        currentPositionMs()?.let { publishPlaybackAnchor(it, playing = false, force = true) }
            ?: remotePlayer?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive && playing) {
                runCatching {
                    playbackPositionSource?.readPosition()?.let { position ->
                        logPositionSyncState(position)
                        remotePlayer?.setPosition(position)
                        publishPlaybackAnchor(position, playing = true, force = false)
                    }
                }.onFailure {
                    ProviderLogger.error("读取 Apple Music 当前播放进度失败", it)
                }
                delay(positionUpdateInterval())
            }
        }
    }

    private fun hookExoPlaybackLifecycle(exoPlayerClass: Class<*>) {
        val playMethod = AppleReflection.findMethod(
            exoPlayerClass,
            member(AppleMusicRuntimeMember.EXO_PLAY_METHOD),
            parameterCount = 0,
        )
        runtime.hookRegistrar.installHook(playMethod, after = { chain, _ ->
            activatePlaybackPlayer(
                mediaPlayer = chain.thisObject,
                source = "ExoMediaPlayer.play",
            )
            refreshCurrentQueueItem(chain.thisObject, "ExoMediaPlayer.play")
            startSyncAction()
        })

        listOf(
            AppleMusicRuntimeMember.EXO_PAUSE_METHOD,
            AppleMusicRuntimeMember.EXO_STOP_METHOD,
            AppleMusicRuntimeMember.EXO_RELEASE_METHOD,
        ).forEach { runtimeMember ->
            val methodName = member(runtimeMember)
            val method = AppleReflection.findMethod(
                exoPlayerClass,
                methodName,
                parameterCount = 0,
            )
            runtime.hookRegistrar.installHook(method, after = { chain, _ ->
                if (playbackPositionSource?.player === chain.thisObject) {
                    stopSyncAction()
                    if (runtimeMember == AppleMusicRuntimeMember.EXO_RELEASE_METHOD) {
                        playbackPositionSource = null
                        if (activePlaybackPlayer === chain.thisObject) {
                            activePlaybackPlayer = null
                        }
                    }
                }
            })
        }
        ProviderLogger.info("Apple Music 播放生命周期 Hook 已安装")
    }

    private fun activatePlaybackPlayer(mediaPlayer: Any?, source: String) {
        if (mediaPlayer == null) return
        ProviderLogger.diagnostic(
            "Timing activate: source=$source, requested=${System.identityHashCode(mediaPlayer)}, " +
                "previousActive=${activePlaybackPlayer?.let(System::identityHashCode)}, " +
                "previousSource=${playbackPositionSource?.player?.let(System::identityHashCode)}, " +
                "metadataId=${currentMetadataId()}, lyricsSongId=${currentLyricsSongId()}"
        )
        activePlaybackPlayer = mediaPlayer
        capturePlaybackPositionSource(
            mediaPlayer = mediaPlayer,
            source = source,
            replace = true,
        )
    }

    private fun capturePlaybackPositionSource(
        mediaPlayer: Any?,
        source: String,
        replace: Boolean,
    ) {
        if (mediaPlayer == null || (!replace && playbackPositionSource != null)) return
        val resolved = resolvePlaybackPositionSource(
            mediaPlayer,
            member(AppleMusicRuntimeMember.EXO_CURRENT_POSITION_METHOD),
        )
        if (resolved == null) {
            ProviderLogger.error(
                "Apple Music 播放器缺少 getCurrentPosition：class=${mediaPlayer.javaClass.name}"
            )
            return
        }
        val previous = playbackPositionSource
        playbackPositionSource = resolved
        if (previous?.player !== mediaPlayer) {
            zeroPositionReadCount = 0
            hasLoggedNonZeroPosition = false
            lastTimingSamplePosition = -1L
            lastTimingSampleAtMs = 0L
            lastTimingStateSignature = null
            lastPlaybackAnchorAtMs = 0L
            ProviderLogger.info(
                "播放进度源已绑定：source=$source, class=${mediaPlayer.javaClass.name}, " +
                    "instance=${System.identityHashCode(mediaPlayer)}"
            )
        }
    }

    private fun logPositionSyncState(position: Long) {
        logPlaybackTimingDiagnostic(position)
        if (position > 0L) {
            if (!hasLoggedNonZeroPosition) {
                hasLoggedNonZeroPosition = true
                ProviderLogger.info("播放进度同步已启动：position=$position")
            }
            zeroPositionReadCount = 0
            return
        }
        zeroPositionReadCount += 1
        if (zeroPositionReadCount == 10) {
            val source = playbackPositionSource
            ProviderLogger.info(
                "播放进度连续为 0：class=${source?.player?.javaClass?.name}, " +
                    "instance=${source?.player?.let(System::identityHashCode)}"
            )
        }
    }

    private fun logPlaybackTimingDiagnostic(position: Long) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val source = playbackPositionSource
        val activeIdentity = activePlaybackPlayer?.let(System::identityHashCode)
        val sourceIdentity = source?.player?.let(System::identityHashCode)
        val stateSignature = listOf(
            activeIdentity,
            sourceIdentity,
            currentMetadataId(),
            currentLyricsSongId(),
            playing,
        ).joinToString("|")
        val sampleElapsed = (now - lastTimingSampleAtMs).takeIf { lastTimingSampleAtMs > 0L }
        val positionDelta = (position - lastTimingSamplePosition)
            .takeIf { lastTimingSamplePosition >= 0L }
        val recentExplicitSeek = now - lastExplicitSeekAtMs <= 2_000L
        val unexpectedJump = sampleElapsed != null && positionDelta != null &&
            sampleElapsed in 1L..2_000L &&
            abs(positionDelta - sampleElapsed) > 1_500L &&
            !recentExplicitSeek
        val shouldTrace = unexpectedJump || stateSignature != lastTimingStateSignature ||
            now - lastTimingTraceAtMs >= 5_000L

        if (shouldTrace) {
            val queueItem = runCatching {
                source?.player?.let {
                    AppleReflection.call(
                        it,
                        playbackMember(
                            AppleMusicRuntimeMember.PLAYBACK_PLAYER_CURRENT_ITEM_METHOD
                        ),
                    )
                }
            }.getOrNull()
            val queueMediaId = queueItem?.let(queueItemMediaId)
            val queueId = queueItem?.let {
                runCatching {
                    AppleReflection.call(
                        it,
                        playbackMember(AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ID_METHOD),
                    ) as? Long
                }
                    .getOrNull()
            }
            ProviderLogger.diagnostic(
                "Timing sample: reason=${if (unexpectedJump) "unexpected_jump" else "periodic"}, " +
                    "rawPosition=$position, positionDelta=$positionDelta, elapsedDelta=$sampleElapsed, " +
                    "activePlayer=$activeIdentity, sourcePlayer=$sourceIdentity, " +
                    "playerMismatch=${activePlaybackPlayer !== source?.player}, " +
                    "queueMediaId=$queueMediaId, queueId=$queueId, " +
                    "metadataId=${currentMetadataId()}, lyricsSongId=${currentLyricsSongId()}, " +
                    "isPlaying=$playing, recentSeek=$recentExplicitSeek, " +
                    "seekPosition=$lastExplicitSeekPosition"
            )
            lastTimingTraceAtMs = now
            lastTimingStateSignature = stateSignature
        }
        lastTimingSampleAtMs = now
        lastTimingSamplePosition = position
    }

    private fun publishPlaybackAnchor(position: Long, playing: Boolean, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPlaybackAnchorAtMs < PLAYBACK_ANCHOR_INTERVAL_MS) return

        lastPlaybackAnchorAtMs = now
        val state = AndroidPlaybackState.Builder()
            .setState(
                if (playing) AndroidPlaybackState.STATE_PLAYING
                else AndroidPlaybackState.STATE_PAUSED,
                position.coerceAtLeast(0L),
                if (playing) 1.0f else 0.0f,
                now,
            )
            .build()
        val success = remotePlayer?.setPlaybackState(state) == true
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Timing playback anchor: position=$position, playing=$playing, " +
                    "force=$force, success=$success"
            )
        }
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun positionUpdateInterval(): Long = if (
        ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.OFF &&
        isAodLyricsEnabled()
    ) {
        250L
    } else {
        ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL
    }

    private fun member(member: AppleMusicRuntimeMember): String =
        exoTarget.runtimeMemberName(member)

    private fun playbackMember(member: AppleMusicRuntimeMember): String =
        playbackTarget.runtimeMemberName(member)

    private companion object {
        private const val PLAYBACK_ANCHOR_INTERVAL_MS = 5_000L
    }
}
