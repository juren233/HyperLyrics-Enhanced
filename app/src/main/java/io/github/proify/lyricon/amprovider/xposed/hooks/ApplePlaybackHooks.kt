/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.media.AudioRouting
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState as AndroidPlaybackState
import android.os.Handler
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicOptimizationGate
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.PlaybackPositionSource
import io.github.proify.lyricon.amprovider.xposed.PlaybackState
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.resolvePlaybackPositionSource
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

internal class ApplePlaybackHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val isAodLyricsEnabled: () -> Boolean,
    private val currentMetadataId: () -> String?,
    private val currentLyricsSongId: () -> String?,
    private val queueItemMediaId: (Any) -> String?,
    private val refreshCurrentQueueItem: (Any?, String) -> Unit,
    private val isNetworkAutoSkipPreventionEnabled: () -> Boolean,
    private val isVolumeBalanceEnabled: () -> Boolean,
) {
    @Volatile
    private var playbackPositionSource: PlaybackPositionSource? = null
    @Volatile
    private var activePlaybackPlayer: Any? = null
    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    private var progressJob: Job? = null
    private var remotePlayer: RemotePlayer? = null
    /** Playback surface activity. BUFFERING remains active. */
    private var playing = false
    /** True only while the media clock may advance. BUFFERING is false. */
    private var timelineAdvancing = false
    private val exoPlaybackSignals =
        WeakIdentityMap<Any, AppleExoPlaybackIntentPolicy.Resolution>()
    private val networkErrorPlayer = ThreadLocal<Any?>()
    private val networkRetryRequested = ThreadLocal<Boolean>()
    private val networkRetryStates = WeakIdentityMap<Any, NetworkRetryState>()
    private val networkRetryRevision = AtomicLong()
    private var networkRetryAccess: NetworkRetryAccess? = null
    private var zeroPositionReadCount = 0
    private var hasLoggedNonZeroPosition = false
    private var lastTimingSamplePosition = -1L
    private var lastTimingSampleAtMs = 0L
    private var lastTimingTraceAtMs = 0L
    private var lastTimingStateSignature: String? = null
    private var lastExplicitSeekAtMs = 0L
    private var lastExplicitSeekPosition = -1L
    private var lastPlaybackAnchorAtMs = 0L
    private val atmosphereSessionCallbackHit = AtomicBoolean(false)
    private val atmosphereVariantCallbackHit = AtomicBoolean(false)
    private val exoPlayerStateCallbackHit = AtomicBoolean(false)
    private val mediaSessionStateCallbackHit = AtomicBoolean(false)
    private val atmosphereVolumeProcessor = AppleAtmosVolumeProcessor(isVolumeBalanceEnabled)
    private val atmosphereLoudnessMetadataHooks = AppleAtmosLoudnessMetadataHooks(runtime)
    private val atmospherePcmMonitor = AppleAtmosPcmMonitor(
        runtime = runtime,
        captureContext = atmosphereVolumeProcessor::capturePcmContext,
        onWindow = atmosphereVolumeProcessor::onPcmWindow,
        onDiscontinuity = atmosphereVolumeProcessor::onPcmDiscontinuity,
    )
    private val atmosphereRoutingListeners =
        WeakIdentityMap<AudioTrack, AudioRouting.OnRoutingChangedListener>()
    private val atmosphereReleaseSessionIds = WeakIdentityMap<AudioTrack, Int>()
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
                if (timelineAdvancing) resumeCoroutineTask()
            }

            override fun onScreenOff() {
                if (timelineAdvancing && isAodLyricsEnabled()) resumeCoroutineTask()
                else pauseCoroutineTask()
            }

            override fun onScreenUnlocked() {
                if (timelineAdvancing && progressJob == null) resumeCoroutineTask()
            }
        })
    }

    fun onAodPreferenceChanged() {
        if (ScreenStateMonitor.state != ScreenStateMonitor.ScreenState.OFF) return
        if (timelineAdvancing && isAodLyricsEnabled()) resumeCoroutineTask()
        else pauseCoroutineTask()
    }

    fun onVolumeBalancePreferenceChanged() {
        atmosphereVolumeProcessor.onPreferenceChanged()
    }

    fun onNetworkAutoSkipPreferenceChanged() {
        networkRetryRevision.incrementAndGet()
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
        val playerStateTarget = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.EXO_PLAYER_STATE_CHANGED
        )
        runtime.hookRegistrar.installHook(playerStateTarget.method, after = { chain, _ ->
            val player = chain.thisObject ?: return@installHook
            val playWhenReady = chain.args.getOrNull(0) as? Boolean ?: return@installHook
            val state = chain.args.getOrNull(1) as? Int ?: return@installHook
            onExoPlayerStateChanged(player, playWhenReady, state)
        })
        ProviderLogger.info(
            "Apple Music Exo 播放意图 Hook 已安装: " +
                "${playerStateTarget.method.declaringClass.name}#" +
                "${playerStateTarget.method.name}(boolean,int)"
        )
        hookPlatformMediaSessionPlaybackState()
        hookExoPlaybackLifecycle(exoPlayerClass)
        runCatching { hookNetworkAutoSkip(exoPlayerClass) }
            .onFailure {
                ProviderLogger.error(
                    "Apple Music 弱网自动重试 Hook 安装失败，已禁用该可选功能",
                    it,
                )
            }
        hookAtmosVolumeBalance()

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
                    if (activePlaybackPlayer === activeMediaPlayer) {
                        val signal = activeMediaPlayer?.let(exoPlaybackSignals::get)
                        if (signal?.playbackActive == true) {
                            applyExoPlaybackResolution(
                                player = activeMediaPlayer,
                                resolution = signal,
                                source = "LocalMediaPlayerController.retained_exo_intent",
                            )
                        } else {
                            stopSyncAction()
                        }
                    }
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
        if (playing && timelineAdvancing) return
        playing = true
        timelineAdvancing = true
        currentPositionMs()?.let { publishPlaybackAnchor(it, playing = true, force = true) }
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        playing = false
        timelineAdvancing = false
        currentPositionMs()?.let { publishPlaybackAnchor(it, playing = false, force = true) }
            ?: remotePlayer?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive && timelineAdvancing) {
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
            runtime.hookRegistrar.installHook(
                method,
                before = { chain ->
                    // The platform MediaSession PAUSED publication can happen inside these
                    // methods. Remove retained play intent before the original call so an
                    // explicit pause/stop/release is never rewritten as BUFFERING or retried.
                    chain.thisObject?.let { player ->
                        exoPlaybackSignals.remove(player)
                        cancelNetworkRetry(player)
                    }
                },
                after = { chain, _ ->
                    if (runtimeMember == AppleMusicRuntimeMember.EXO_RELEASE_METHOD) {
                        chain.thisObject?.let(atmosphereVolumeProcessor::onPlayerReleased)
                    }
                    if (playbackPositionSource?.player === chain.thisObject) {
                        stopSyncAction()
                        if (runtimeMember == AppleMusicRuntimeMember.EXO_RELEASE_METHOD) {
                            playbackPositionSource = null
                            if (activePlaybackPlayer === chain.thisObject) {
                                activePlaybackPlayer = null
                            }
                        }
                    }
                },
            )
        }
        ProviderLogger.info("Apple Music 播放生命周期 Hook 已安装")
    }

    private fun hookNetworkAutoSkip(exoPlayerClass: Class<*>) {
        val shouldSkipMethod = AppleReflection.findMethod(
            exoPlayerClass,
            member(AppleMusicRuntimeMember.EXO_SHOULD_SKIP_TO_NEXT_ITEM_METHOD),
            parameterCount = 3,
        )
        check(
            Modifier.isStatic(shouldSkipMethod.modifiers) &&
                shouldSkipMethod.returnType == Boolean::class.javaPrimitiveType,
        ) {
            "Apple Music 自动切歌决策方法签名不符合预期: $shouldSkipMethod"
        }

        val onPlayerErrorMethod = AppleReflection.findMethod(
            exoPlayerClass,
            member(AppleMusicRuntimeMember.EXO_PLAYER_ERROR_METHOD),
            parameterCount = 1,
        )
        check(
            !Modifier.isStatic(onPlayerErrorMethod.modifiers) &&
                onPlayerErrorMethod.returnType == Void.TYPE,
        ) {
            "Apple Music 播放错误方法签名不符合预期: $onPlayerErrorMethod"
        }

        val playerField = exoPlayerClass.getDeclaredField(
            member(AppleMusicRuntimeMember.EXO_PLAYER_FIELD)
        ).apply { isAccessible = true }
        val eventHandlerField = exoPlayerClass.getDeclaredField(
            member(AppleMusicRuntimeMember.EXO_EVENT_HANDLER_FIELD)
        ).apply { isAccessible = true }
        check(
            !Modifier.isStatic(playerField.modifiers) &&
                !Modifier.isStatic(eventHandlerField.modifiers) &&
                Handler::class.java.isAssignableFrom(eventHandlerField.type),
        ) {
            "Apple Music 自动重试字段签名不符合预期: " +
                "player=$playerField, eventHandler=$eventHandlerField"
        }
        val retryMethod = AppleReflection.findMethod(
            playerField.type,
            member(AppleMusicRuntimeMember.EXO_PLAYER_RETRY_METHOD),
            parameterCount = 0,
        )
        check(
            !Modifier.isStatic(retryMethod.modifiers) && retryMethod.returnType == Void.TYPE,
        ) {
            "ExoPlayer retry 方法签名不符合预期: $retryMethod"
        }
        networkRetryAccess = NetworkRetryAccess(
            playerField = playerField,
            eventHandlerField = eventHandlerField,
            retryMethod = retryMethod,
        )

        runtime.hookRegistrar.installScopedHook(
            onPlayerErrorMethod,
            enter = { chain ->
                val player = chain.thisObject ?: return@installScopedHook false
                networkErrorPlayer.set(player)
                networkRetryRequested.set(false)
                true
            },
            after = { chain, _ ->
                val player = chain.thisObject ?: return@installScopedHook
                if (
                    networkRetryRequested.get() == true &&
                    isNetworkAutoSkipPreventionEnabled()
                ) {
                    scheduleNetworkRetry(player)
                }
            },
            exit = {
                networkRetryRequested.remove()
                networkErrorPlayer.remove()
            },
        )
        runtime.hookRegistrar.installResultOverrideHook(shouldSkipMethod) { chain, original ->
            if (original != true || !isNetworkAutoSkipPreventionEnabled()) {
                return@installResultOverrideHook original
            }
            val player = networkErrorPlayer.get() ?: return@installResultOverrideHook original
            val exception = chain.args.getOrNull(0) as? Throwable
            val errorType = (chain.args.getOrNull(1) as? Number)?.toInt()
            if (!AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(exception, errorType)) {
                return@installResultOverrideHook original
            }
            networkRetryRequested.set(true)
            ProviderLogger.diagnostic(
                "Apple Music 弱网失败阻止自动切歌并等待重试: " +
                    "player=${System.identityHashCode(player)}, " +
                    "exception=${exception?.javaClass?.name}, errorType=$errorType"
            )
            false
        }
        ProviderLogger.info(
            "Apple Music 弱网自动重试 Hook 已安装: " +
                "${shouldSkipMethod.declaringClass.name}#${shouldSkipMethod.name}" +
                "(Exception,int,MediaPlayerContext)"
        )
    }

    private fun scheduleNetworkRetry(player: Any) {
        val access = networkRetryAccess ?: return
        val handler = access.eventHandlerField.get(player) as? Handler
            ?: error("Apple Music ExoMediaPlayer eventHandler 不可用")
        val previous = networkRetryStates[player]
        previous?.handler?.get()?.removeCallbacks(previous.runnable)
        val attempt = (previous?.attempt ?: 0) + 1
        val delayMs = NETWORK_RETRY_DELAYS_MS[
            (attempt - 1).coerceAtMost(NETWORK_RETRY_DELAYS_MS.lastIndex)
        ]
        val featureRevision = networkRetryRevision.get()
        val optimizationRevision = AppleMusicOptimizationGate.revision()
        val playerReference = WeakReference(player)
        lateinit var retryTask: Runnable
        retryTask = Runnable {
            val currentPlayer = playerReference.get() ?: return@Runnable
            val currentState = networkRetryStates[currentPlayer] ?: return@Runnable
            if (currentState.runnable !== retryTask) return@Runnable
            if (
                featureRevision != networkRetryRevision.get() ||
                optimizationRevision != AppleMusicOptimizationGate.revision() ||
                !AppleMusicOptimizationGate.isEnabled() ||
                !isNetworkAutoSkipPreventionEnabled() ||
                exoPlaybackSignals[currentPlayer]?.playbackActive != true
            ) {
                cancelNetworkRetry(currentPlayer)
                return@Runnable
            }
            runCatching {
                val exoPlayer = access.playerField.get(currentPlayer)
                    ?: error("Apple Music ExoPlayer 实例不可用")
                access.retryMethod.invoke(exoPlayer)
            }.onSuccess {
                ProviderLogger.diagnostic(
                    "Apple Music 弱网播放已发起自动重试: " +
                        "player=${System.identityHashCode(currentPlayer)}, attempt=$attempt"
                )
            }.onFailure {
                cancelNetworkRetry(currentPlayer)
                ProviderLogger.error("Apple Music 弱网播放自动重试失败", it)
            }
        }
        networkRetryStates[player] = NetworkRetryState(
            handler = WeakReference(handler),
            runnable = retryTask,
            attempt = attempt,
        )
        if (!handler.postDelayed(retryTask, delayMs)) {
            networkRetryStates.remove(player)
            error("Apple Music 弱网播放自动重试任务提交失败")
        }
        ProviderLogger.diagnostic(
            "Apple Music 弱网播放自动重试已安排: " +
                "player=${System.identityHashCode(player)}, attempt=$attempt, delayMs=$delayMs"
        )
    }

    private fun cancelNetworkRetry(player: Any) {
        val state = networkRetryStates[player] ?: return
        networkRetryStates.remove(player)
        state.handler.get()?.removeCallbacks(state.runnable)
    }

    private fun hookPlatformMediaSessionPlaybackState() {
        val method = MediaSession::class.java.getDeclaredMethod(
            "setPlaybackState",
            AndroidPlaybackState::class.java,
        )
        runtime.hookRegistrar.installArgumentRewriteHook(method) { chain ->
            val incoming = chain.args.firstOrNull() as? AndroidPlaybackState
                ?: return@installArgumentRewriteHook null
            val activePlayer = activePlaybackPlayer
            val activeResolution = activePlayer?.let(exoPlaybackSignals::get)
            if (BuildConfig.DEBUG && mediaSessionStateCallbackHit.compareAndSet(false, true)) {
                ProviderLogger.diagnostic(
                    "Apple MediaSession 播放态首次回调: state=${incoming.state}, " +
                        "position=${incoming.position}, activePlayer=" +
                        "${activePlayer?.let(System::identityHashCode)}, " +
                        "exoPublication=${activeResolution?.publication}, " +
                        "exoActive=${activeResolution?.playbackActive}"
                )
            }
            val decision = AppleExoPlaybackIntentPolicy.decideMediaSessionPause(
                incomingPaused = incoming.state == AndroidPlaybackState.STATE_PAUSED,
                activeResolution = activeResolution,
            )
            if (decision != AppleExoPlaybackIntentPolicy.MediaSessionPauseDecision.REWRITE_BUFFERING) {
                return@installArgumentRewriteHook null
            }

            val rewritten = AndroidPlaybackState.Builder(incoming)
                .setState(
                    AndroidPlaybackState.STATE_BUFFERING,
                    incoming.position,
                    0.0f,
                    incoming.lastPositionUpdateTime.takeIf { it > 0L }
                        ?: SystemClock.elapsedRealtime(),
                )
                .build()
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple MediaSession 假暂停已改写: original=PAUSED, " +
                        "replacement=BUFFERING, position=${incoming.position}, " +
                        "activePlayer=${activePlayer?.let(System::identityHashCode)}, " +
                        "exoPublication=${activeResolution?.publication}"
                )
            }
            arrayOf(rewritten)
        }
        ProviderLogger.info(
            "Apple Music 系统 MediaSession 缓冲态 Hook 已安装: " +
                "${method.declaringClass.name}#${method.name}(PlaybackState)"
        )
    }

    private fun onExoPlayerStateChanged(
        player: Any,
        playWhenReady: Boolean,
        state: Int,
    ) {
        val resolution = AppleExoPlaybackIntentPolicy.resolve(playWhenReady, state)
        if (state == AppleExoPlaybackIntentPolicy.STATE_READY || !resolution.playbackActive) {
            cancelNetworkRetry(player)
        }
        exoPlaybackSignals[player] = resolution
        if (BuildConfig.DEBUG && exoPlayerStateCallbackHit.compareAndSet(false, true)) {
            ProviderLogger.diagnostic(
                "Exo 播放意图首次回调: player=${System.identityHashCode(player)}, " +
                    "playWhenReady=$playWhenReady, state=$state, " +
                    "publication=${resolution.publication}"
            )
        }
        if (resolution.playbackActive && activePlaybackPlayer !== player) {
            activatePlaybackPlayer(player, "ExoMediaPlayer.onPlayerStateChanged")
            refreshCurrentQueueItem(player, "onPlayerStateChanged")
        }
        if (activePlaybackPlayer !== player) return
        applyExoPlaybackResolution(
            player = player,
            resolution = resolution,
            source = "ExoMediaPlayer.onPlayerStateChanged",
        )
    }

    private fun applyExoPlaybackResolution(
        player: Any,
        resolution: AppleExoPlaybackIntentPolicy.Resolution,
        source: String,
    ) {
        if (activePlaybackPlayer !== player) return
        playing = resolution.playbackActive
        timelineAdvancing = resolution.advancesTimeline
        val position = currentPositionMs()?.coerceAtLeast(0L) ?: 0L
        publishPlaybackState(
            position = position,
            publication = resolution.publication,
            force = true,
        )
        if (resolution.advancesTimeline) resumeCoroutineTask() else pauseCoroutineTask()
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Exo 播放意图发布: source=$source, player=${System.identityHashCode(player)}, " +
                    "publication=${resolution.publication}, active=${resolution.playbackActive}, " +
                    "advancing=${resolution.advancesTimeline}, position=$position"
            )
        }
    }

    private fun hookAtmosVolumeBalance() {
        atmosphereLoudnessMetadataHooks.installHooks()
        atmospherePcmMonitor.installHooks()
        hookAtmosAudioTrackLifecycle()
        val audioSessionMethod = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.EXO_AUDIO_SESSION_ID
        ).method
        runtime.hookRegistrar.installHook(audioSessionMethod, after = { chain, _ ->
            val player = chain.thisObject ?: return@installHook
            val audioSessionId = chain.args.firstOrNull() as? Int ?: return@installHook
            if (BuildConfig.DEBUG && atmosphereSessionCallbackHit.compareAndSet(false, true)) {
                ProviderLogger.diagnostic(
                    "Dolby Atmos 音量平衡首次收到音频会话：session=$audioSessionId"
                )
            }
            ProviderLogger.diagnostic(
                "[AtmosVolumeDiag] event=player_session," +
                    "elapsedMs=${SystemClock.elapsedRealtime()}," +
                    "thread=${Thread.currentThread().name}," +
                    "player=${player.javaClass.name}@${System.identityHashCode(player)}," +
                    "sessionId=$audioSessionId," +
                    "activePlayer=${activePlaybackPlayer?.let(System::identityHashCode)}"
            )
            atmosphereVolumeProcessor.onAudioSessionId(player, audioSessionId)
        })

        val audioVariantTarget = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED
        )
        runtime.hookRegistrar.installHook(audioVariantTarget.method, after = { chain, _ ->
            val player = chain.args.firstOrNull() ?: return@installHook
            val audioVariant = chain.args.getOrNull(1) as? Int ?: return@installHook
            if (BuildConfig.DEBUG && atmosphereVariantCallbackHit.compareAndSet(false, true)) {
                ProviderLogger.diagnostic(
                    "Dolby Atmos 音量平衡首次收到音源变体：variant=$audioVariant"
                )
            }
            val periodId = chain.args.getOrNull(2) as? Long ?: 0L
            val format = chain.args.getOrNull(4)
            val loudness = format?.let {
                runCatching {
                    AppleReflection.field(
                        it,
                        audioVariantTarget.target.runtimeMemberName(
                            AppleMusicRuntimeMember.DEBUG_FORMAT_LOUDNESS_FIELD
                        ),
                    ) as? Float
                }.getOrNull()
            } ?: Float.NaN
            val channelCount = format?.let {
                runCatching {
                    AppleReflection.field(
                        it,
                        audioVariantTarget.target.runtimeMemberName(
                            AppleMusicRuntimeMember.DEBUG_FORMAT_CHANNEL_COUNT_FIELD
                        ),
                    ) as? Int
                }.getOrNull()
            } ?: 2
            val peakMetadata = atmosphereLoudnessMetadataHooks.metadataForFormat(format)
            ProviderLogger.diagnostic(
                "[AtmosVolumeDiag] event=player_variant," +
                    "elapsedMs=${SystemClock.elapsedRealtime()}," +
                    "thread=${Thread.currentThread().name}," +
                    "player=${player.javaClass.name}@${System.identityHashCode(player)}," +
                    "variant=$audioVariant,periodId=$periodId,loudness=$loudness," +
                    "channelCount=$channelCount,truePeakDbfs=${peakMetadata?.truePeakDbfs}," +
                    "samplePeakDbfs=${peakMetadata?.samplePeakDbfs}," +
                    "peakAssociation=${peakMetadata?.associationSource}," +
                    "activePlayer=${activePlaybackPlayer?.let(System::identityHashCode)}"
            )
            atmosphereVolumeProcessor.onAudioVariantChanged(
                player = player,
                audioVariant = audioVariant,
                periodId = periodId,
                loudness = loudness,
                channelCount = channelCount,
                peakMetadata = peakMetadata,
            )
        })
        ProviderLogger.info("Apple Music Dolby Atmos 音量平衡 Hook 已安装")
    }

    private fun hookAtmosAudioTrackLifecycle() {
        val playMethod = AudioTrack::class.java.getDeclaredMethod("play")
        runtime.hookRegistrar.installHook(playMethod, after = { chain, _ ->
            val audioTrack = chain.thisObject as? AudioTrack ?: return@installHook
            ensureAtmosRoutingListener(audioTrack)
            atmosphereVolumeProcessor.onAudioTrackPlayed(
                audioSessionId = audioTrackSessionId(audioTrack),
                trackIdentity = System.identityHashCode(audioTrack),
                routedDeviceType = audioTrackRoutedDeviceType(audioTrack),
            )
        })

        val stopMethod = AudioTrack::class.java.getDeclaredMethod("stop")
        runtime.hookRegistrar.installHook(stopMethod, after = { chain, _ ->
            val audioTrack = chain.thisObject as? AudioTrack ?: return@installHook
            atmosphereVolumeProcessor.onAudioTrackStopped(
                audioSessionId = audioTrackSessionId(audioTrack),
                trackIdentity = System.identityHashCode(audioTrack),
                source = "stop",
            )
        })

        val releaseMethod = AudioTrack::class.java.getDeclaredMethod("release")
        runtime.hookRegistrar.installHook(
            releaseMethod,
            before = { chain ->
                val audioTrack = chain.thisObject as? AudioTrack ?: return@installHook
                atmosphereReleaseSessionIds[audioTrack] = audioTrackSessionId(audioTrack)
                removeAtmosRoutingListener(audioTrack)
            },
            after = { chain, _ ->
                val audioTrack = chain.thisObject as? AudioTrack ?: return@installHook
                val sessionId = atmosphereReleaseSessionIds[audioTrack]
                    ?: audioTrackSessionId(audioTrack)
                atmosphereReleaseSessionIds.remove(audioTrack)
                atmosphereVolumeProcessor.onAudioTrackStopped(
                    audioSessionId = sessionId,
                    trackIdentity = System.identityHashCode(audioTrack),
                    source = "release",
                )
            },
        )
        ProviderLogger.info("Apple Music AudioTrack 生命周期与路由 Hook 已安装")
    }

    private fun ensureAtmosRoutingListener(audioTrack: AudioTrack) {
        if (atmosphereRoutingListeners[audioTrack] != null) return
        val listener = object : AudioRouting.OnRoutingChangedListener {
            override fun onRoutingChanged(router: AudioRouting) {
                val routedTrack = router as? AudioTrack ?: return
                atmosphereVolumeProcessor.onAudioTrackRouteChanged(
                    audioSessionId = audioTrackSessionId(routedTrack),
                    trackIdentity = System.identityHashCode(routedTrack),
                    routedDeviceType = audioTrackRoutedDeviceType(routedTrack),
                )
            }
        }
        atmosphereRoutingListeners[audioTrack] = listener
        runCatching {
            audioTrack.addOnRoutingChangedListener(listener, runtime.mainHandler)
        }.onFailure { error ->
            atmosphereRoutingListeners.remove(audioTrack)
            ProviderLogger.error("Apple Music AudioTrack 路由监听注册失败", error)
        }
    }

    private fun removeAtmosRoutingListener(audioTrack: AudioTrack) {
        val listener = atmosphereRoutingListeners[audioTrack] ?: return
        runCatching { audioTrack.removeOnRoutingChangedListener(listener) }
        atmosphereRoutingListeners.remove(audioTrack)
    }

    private fun audioTrackSessionId(audioTrack: AudioTrack): Int =
        runCatching(audioTrack::getAudioSessionId).getOrDefault(0)

    private fun audioTrackRoutedDeviceType(audioTrack: AudioTrack): Int? =
        runCatching { audioTrack.routedDevice?.type }.getOrNull()

    private fun activatePlaybackPlayer(mediaPlayer: Any?, source: String) {
        if (mediaPlayer == null) return
        ProviderLogger.diagnostic(
            "Timing activate: source=$source, requested=${System.identityHashCode(mediaPlayer)}, " +
                "previousActive=${activePlaybackPlayer?.let(System::identityHashCode)}, " +
                "previousSource=${playbackPositionSource?.player?.let(System::identityHashCode)}, " +
                "metadataId=${currentMetadataId()}, lyricsSongId=${currentLyricsSongId()}"
        )
        activePlaybackPlayer = mediaPlayer
        atmosphereVolumeProcessor.onPlayerActivated(mediaPlayer)
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
        publishPlaybackState(
            position = position,
            publication = if (playing) {
                AppleExoPlaybackIntentPolicy.Publication.PLAYING
            } else {
                AppleExoPlaybackIntentPolicy.Publication.PAUSED
            },
            force = force,
        )
    }

    private fun publishPlaybackState(
        position: Long,
        publication: AppleExoPlaybackIntentPolicy.Publication,
        force: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPlaybackAnchorAtMs < PLAYBACK_ANCHOR_INTERVAL_MS) return

        lastPlaybackAnchorAtMs = now
        val androidState = when (publication) {
            AppleExoPlaybackIntentPolicy.Publication.PLAYING ->
                AndroidPlaybackState.STATE_PLAYING
            AppleExoPlaybackIntentPolicy.Publication.BUFFERING ->
                AndroidPlaybackState.STATE_BUFFERING
            AppleExoPlaybackIntentPolicy.Publication.PAUSED ->
                AndroidPlaybackState.STATE_PAUSED
        }
        val state = AndroidPlaybackState.Builder()
            .setState(
                androidState,
                position.coerceAtLeast(0L),
                if (publication == AppleExoPlaybackIntentPolicy.Publication.PLAYING) 1.0f
                else 0.0f,
                now,
            )
            .build()
        val success = remotePlayer?.setPlaybackState(state) == true
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Timing playback anchor: position=$position, publication=$publication, " +
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

    private data class NetworkRetryAccess(
        val playerField: Field,
        val eventHandlerField: Field,
        val retryMethod: Method,
    )

    private data class NetworkRetryState(
        val handler: WeakReference<Handler>,
        val runnable: Runnable,
        val attempt: Int,
    )

    private companion object {
        private const val PLAYBACK_ANCHOR_INTERVAL_MS = 5_000L
        private val NETWORK_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)
    }
}
