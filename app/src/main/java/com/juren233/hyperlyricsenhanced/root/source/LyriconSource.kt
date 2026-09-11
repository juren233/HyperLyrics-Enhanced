package com.juren233.hyperlyricsenhanced.root.source

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ChineseLyricsPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.TraditionalLyricsSimplifier
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.common.media.NextTrackMetadataCache
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSource
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.lunabeat.LunaBeatLookupResult
import com.juren233.hyperlyricsenhanced.online.source.lunabeat.LunaBeatTtmlRepository
import com.juren233.hyperlyricsenhanced.online.utils.ChineseUtils
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.proify.lyricon.amprovider.xposed.AppleDirectBridgeContract
import io.github.proify.lyricon.amprovider.xposed.AppleSourceSwitchPerformanceDiagnostics
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class LyriconSource : LyricSource {

    companion object {
        internal const val TAG = "LyriconSource"
        internal const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        internal const val BUILT_IN_PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced"
        internal const val APPLE_LYRICS_GRACE_MS = 5_000L
        private const val SALT_LOCAL_LYRICS_GRACE_MS = 3_000L
        private const val APPLE_MEDIA_MONITOR_INTERVAL_MS = 1_000L
        private const val SAME_TRACK_DURATION_TOLERANCE_MS = 2_000L
        internal const val TIMING_DIAGNOSTIC_INTERVAL_MS = 5_000L
        internal const val SOURCE_SWITCH_DIAGNOSTIC_WINDOW_MS = 20_000L
        internal const val APPLE_NATIVE_LYRICS_SOURCE = "APPLE"
        private const val PRONUNCIATION_DIAGNOSTIC_TAG = "ApplePronunciationDiag"
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }

    internal fun LyriconSong.toLocalSong(): LocalSong {
        val jsonString = json.encodeToString(this)
        return json.decodeFromString(jsonString)
    }

    override val id = "lyricon"
    override val displayName = "Lyricon"

    @Volatile
    internal var sink: LyricSink? = null
    internal var app: Application? = null
    @Volatile
    internal var subscriber: LyriconSubscriber? = null

    internal var activeProviderPackageName: String? = null
    @Volatile
    internal var activeCentralPlayerPackageName: String? = null
    internal var activeProviderDelayMs: Int = RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
    internal var prefs: android.content.SharedPreferences? = null
    internal var onCentralConnected: (() -> Unit)? = null
    internal var onCentralConnectTimeout: (() -> Unit)? = null
    internal var directBridge: AppleMusicDirectBridge? = null
    internal val loggedPlayerVersionSnapshots = ConcurrentHashMap.newKeySet<String>()
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val fallbackRequestMutex = Mutex()
    internal val publication = LyriconPublication()
    internal val currentAppleSong get() = publication.currentAppleSong
    internal val currentAppleNativeSong get() = publication.currentAppleNativeSong
    internal val currentAppleHasNativeLyrics get() = publication.currentAppleHasNativeLyrics
    internal val currentPublishedAppleSong get() = publication.currentPublishedAppleSong
    internal val currentPublishedAppleOnlineTranslationMatched get() = publication.currentPublishedAppleOnlineTranslationMatched
    internal val currentThirdPartySong get() = publication.currentThirdPartySong
    internal val currentPublishedThirdPartySong get() = publication.currentPublishedThirdPartySong
    internal val fallbackSongActive get() = publication.fallbackSongActive
    internal val thirdPartyFallbackSongActive get() = publication.thirdPartyFallbackSongActive
    internal val onlineMatchedTranslationActive get() = publication.onlineMatchedTranslationActive
    internal val confirmedLyricsSourceSelection get() = publication.confirmedLyricsSourceSelection
    internal val onlineTranslationRequest = OnlineTranslationRequest<PendingOnlineTranslationCommit>()
    internal val onlineTranslationGeneration get() = onlineTranslationRequest.snapshot().generation
    internal val onlineTranslationAttemptKey get() = onlineTranslationRequest.snapshot().attempt
    internal val onlineTranslationRunning get() = onlineTranslationRequest.snapshot().running
    internal val onlineTranslationResultReady get() = onlineTranslationRequest.snapshot().resultReady
    internal val onlineRaceFirstPublishedGeneration get() = onlineTranslationRequest.snapshot().firstPublished
    internal val onlineRaceFirstAcceptedGeneration get() = onlineTranslationRequest.snapshot().firstAccepted

    internal val mediaPositionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val appleFallbackRequest = AppleFallbackRequest()
    internal var mediaPositionJob: Job? = null
    internal val thirdPartyFallbackRequest = DelayedFallbackRequest<LocalSong?>(
        scope = fallbackScope,
        post = { task, delayMs -> mainHandler.postDelayed(task, delayMs) },
        remove = { task -> mainHandler.removeCallbacks(task) },
    )
    internal val originalMetadataRequest = AppleOriginalMetadataRequestKey()
    internal val manualSourceRequests = AppleManualSourceRequestState()
    @Volatile
    internal var latestSourceSwitchTraceRequest: OnlineSourceSwitchRequest? = null
    internal val applePositionState = ApplePlaybackPositionState()
    internal val lastAdjustedPosition get() = applePositionState.lastAdjustedPosition
    internal val centralPlaybackPositionWitness = CentralPlaybackPositionWitness()
    internal val localMediaSessionState = AppleLocalMediaSessionState()
    internal val activeMediaSessionGate = ActiveMediaSessionGate(
        nowElapsedMs = SystemClock::elapsedRealtime,
        nowWallClockMs = System::currentTimeMillis,
        isMusicActive = { isAnyMusicActive() },
    )
    internal val mediaSessionGateRecovery = MediaSessionGateRecoveryTracker()

    internal var lastTimingDiagnosticAtMs = 0L
    internal var lastTimingDiagnosticPosition = -1L
    internal var lastTimingDiagnosticState: String? = null
    internal var lastCentralPositionDiagnosticAtMs = 0L

    internal enum class OnlineTranslationPublicationStage {
        SINGLE,
        RACE_FIRST,
        RACE_FINAL,
        RACE_FINAL_COMMIT,
    }

    internal data class PendingOnlineTranslationCommit(
        val generation: Int,
        val baseSong: LocalSong,
        val selection: OnlineTranslationSelection,
        val targetPosition: Long,
    )

    internal val appleMediaMonitor = object : Runnable {
        override fun run() {
            if (sink == null) return
            observeAppleMediaSession()
            mainHandler.postDelayed(this, APPLE_MEDIA_MONITOR_INTERVAL_MS)
        }
    }

    @Volatile
    internal var centralAppleProviderActive = false
    @Volatile
    internal var centralAppleSongAvailable = false


    override fun isAvailable(): Boolean = true

    override fun start(sink: LyricSink) {
        diagnostic(
            "stage=source_start_requested, appPresent=${app != null}, " +
                "prefsPresent=${prefs != null}, subscriberPresent=${subscriber != null}, " +
                "directBridgePresent=${directBridge != null}",
        )
        if (this.subscriber != null) {
            HookLogger.d(TAG, "跳过重复启动: reason=already_running")
            diagnostic(
                "stage=source_start_skipped, reason=already_running, " +
                    "subscriberType=${subscriber?.javaClass?.name}",
            )
            return
        }
        this.sink = sink
        val application = app ?: run {
            HookLogger.w(TAG, "数据源启动延后: reason=application_unavailable")
            return
        }
        registerLocalMediaSessionTracker()
        diagnostic("stage=direct_bridge_starting")
        directBridge = AppleMusicDirectBridge(application, this).also { it.start() }
        diagnostic("stage=direct_bridge_started")
        initializeSubscriber(application)
        startAppleMediaMonitor()
        HookLogger.i(TAG, "数据源已启动")
        diagnostic(
            "stage=source_start_completed, subscriberType=${subscriber?.javaClass?.name}, " +
                "directBridgePresent=${directBridge != null}",
        )
    }

    override fun stop() {
        stopAppleMediaMonitor()
        unregisterLocalMediaSessionTracker()
        cancelFallback(clearAppleSong = true, reason = "source_stopped")
        cancelThirdPartyFallback(reason = "source_stopped")
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "source_stopped"
        )
        try {
            directBridge?.stop()
            directBridge = null
            subscriber?.unsubscribeActivePlayer(activePlayerListener)
            subscriber?.unregister()
            subscriber?.destroy()
        } catch (e: Exception) {
            HookLogger.e(TAG, "清理歌词订阅连接失败", e)
        } finally {
            centralAppleProviderActive = false
            centralAppleSongAvailable = false
            activeCentralPlayerPackageName = null
            activeProviderPackageName = null
            publication.reset()
            applePositionState.clearReferences()
            subscriber = null
            centralPlaybackPositionWitness.reset()
            sink?.onStop()
            sink = null
        }
        HookLogger.i(TAG, "数据源已停止")
    }

    fun initialize(
        app: Application,
        prefs: android.content.SharedPreferences?,
        onCentralConnected: (() -> Unit)? = null,
        onCentralConnectTimeout: (() -> Unit)? = null,
    ) {
        this.app = app
        this.prefs = prefs
        registerLocalMediaSessionTracker()
        onActiveMediaSessionSnapshotChanged(
            prefs?.getString(RootConstants.KEY_ACTIVE_MEDIA_SESSION_PACKAGES, null),
            reason = "source_initialized",
        )
        this.onCentralConnected = onCentralConnected
        this.onCentralConnectTimeout = onCentralConnectTimeout
        diagnostic(
            "stage=source_initialized, appPackage=${app.packageName}, " +
                "prefsPresent=${prefs != null}",
        )

        LyriconDataBridge.onAiTranslationComplete = {
            BaseIslandRenderer.refreshActiveIsland()
        }
    }

    internal fun isMissingLyricsSupplement(song: LocalSong?): Boolean = song?.metadata
        ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
        .toBoolean()

    internal fun hasAppleNativeLyrics(song: LocalSong?): Boolean =
        !song?.lyrics.isNullOrEmpty() && !isMissingLyricsSupplement(song)

    /**
     * A cache migrated from a pre-source-selection build has no selected source. Re-query it
     * once so the Apple process receives a truthful source list instead of inventing a label.
     */
    internal fun needsMissingLyricsSourceRecovery(song: LocalSong?): Boolean =
        song != null && (
            song.lyrics.isNullOrEmpty() ||
                (
                    isMissingLyricsSupplement(song) &&
                        song.metadata
                            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
                            .isNullOrBlank()
                    )
            )

    internal fun handleThirdPartySong(song: LocalSong?) {
        val previousSong = currentThirdPartySong
        val sameTrack = previousSong != null && song != null && isSameTrack(previousSong, song)
        val sameContent = sameTrack && previousSong == song
        val preferOnline = isSaltPreferOnlineEnabled()
        val request = thirdPartyFallbackRequest.snapshot()
        if (ThirdPartySongUpdatePolicy.preserveOnline(
                sameTrack = sameTrack,
                sameContent = sameContent,
                enrichmentRunningOrMatched = onlineTranslationRunning ||
                    onlineTranslationResultReady ||
                    onlineMatchedTranslationActive,
                fallbackRunning = request.running,
                fallbackPending = request.pending,
                fallbackSelected = thirdPartyFallbackSongActive,
                preferOnline = preferOnline,
                incomingHasLyrics = !song?.lyrics.isNullOrEmpty(),
            )
        ) {
            publication.acceptThirdPartyInput(song)
            debug("忽略同一首歌的三方回调（保留在线结果）: title=${song?.name}")
            return
        }
        cancelThirdPartyFallback(reason = "third_party_song_updated")
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "third_party_song_updated",
        )
        publication.acceptThirdPartyInput(song)
        publishThirdPartySong(song, restorePosition = sameTrack, origin = LyricPublicationOrigin.NATIVE)
        if (song == null) return
        val playerPackage = activeCentralPlayerPackageName
        if (!isOnlineTranslationEnabledFor(playerPackage)) return
        if (playerPackage == OnlineTranslationSourcePreferences.SALT_PACKAGE &&
            (preferOnline || song.lyrics.isNullOrEmpty())
        ) {
            // 椒盐音乐：本地无歌词时在线兜底；“优先使用在线源”时立即在线取词。
            // 未开启优先在线源时先等本地歌词的宽限期，避免无谓的在线请求。
            val graceNeeded = !preferOnline && song.lyrics.isNullOrEmpty()
            scheduleThirdPartyFallback(
                baseSong = song,
                delayMs = if (graceNeeded) SALT_LOCAL_LYRICS_GRACE_MS else 0L,
            )
        } else if (needsOnlineEnrichment(song)) {
            scheduleOnlineTranslation(song)
        }
    }

    internal fun publishAppleSong(
        song: LocalSong?,
        restorePosition: Boolean,
        onlineTranslationMatched: Boolean = false,
        publishToSink: Boolean = true,
        origin: LyricPublicationOrigin = if (onlineTranslationMatched || isMissingLyricsSupplement(song))
            LyricPublicationOrigin.AUTOMATIC else LyricPublicationOrigin.NATIVE,
    ) {
        if (!publishToSink) return
        if (BuildConfig.DEBUG) {
            HookLogger.i(
                TAG,
                "[debug] Timing publish: songId=${song?.id}, restorePosition=$restorePosition, " +
                    "title=${song?.name}, artist=${song?.artist}, origin=$origin, " +
                    "currentInputTitle=${currentAppleSong?.name}, " +
                    "lastAdjustedPosition=$lastAdjustedPosition, " +
                    "onlineTranslationMatched=$onlineTranslationMatched, " +
                    "centralPlayer=$activeCentralPlayerPackageName, fallback=$fallbackSongActive"
            )
        }
        publication.publishApple(
            LyricPublicationEvent(song, origin, onlineTranslationMatched),
        ) { selected, matched ->
            dispatchAppleSong(selected, restorePosition, matched)
        }
    }

    internal fun refreshRetainedAppleMetadata(incoming: LocalSong?) {
        val updatedInput = AppleSongUpdatePolicy.refreshDisplayMetadata(currentAppleSong, incoming)
        if (updatedInput != null) {
            publication.acceptAppleInput(updatedInput, currentAppleHasNativeLyrics)
        }
        val refreshed = publication.refreshAppleDisplayMetadata(incoming) { selected, matched ->
            dispatchAppleSong(selected, restorePosition = true, onlineTranslationMatched = matched)
        }
        diagnostic(
            "stage=retained_metadata_refresh, incomingId=${incoming?.id}, " +
                "incomingTitle=${incoming?.name}, publishedId=${currentPublishedAppleSong?.id}, " +
                "publishedTitle=${currentPublishedAppleSong?.name}, refreshed=$refreshed",
        )
    }

    private fun dispatchAppleSong(
        song: LocalSong?,
        restorePosition: Boolean,
        onlineTranslationMatched: Boolean,
    ) {
        publishSong(
            song = AppleSongDisplayPolicy.copyForDisplay(song)
                ?.let(::filterApplePronunciationForDisplay)
                ?.let(::simplifyAppleSongForDisplay),
            restorePosition = restorePosition,
            onlineTranslationMatched = onlineTranslationMatched,
        )
    }

    internal fun publishThirdPartySong(
        song: LocalSong?,
        restorePosition: Boolean,
        onlineTranslationMatched: Boolean = false,
        origin: LyricPublicationOrigin = LyricPublicationOrigin.AUTOMATIC,
    ) {
        publication.publishThirdParty(LyricPublicationEvent(song, origin, onlineTranslationMatched)) { selected, matched ->
            publishSong(selected, restorePosition, matched)
        }
    }

    private fun publishSong(
        song: LocalSong?,
        restorePosition: Boolean,
        onlineTranslationMatched: Boolean = false
    ) {
        val preservedSameSongState = restorePosition &&
            song != null &&
            !song.lyrics.isNullOrEmpty() &&
            LyriconDataBridge.replaceSameSongContent(song)
        if (!preservedSameSongState) {
            LyriconDataBridge.updateSong(song)
        }
        if (onlineTranslationMatched) {
            sink?.onOnlineTranslationMatched(song)
        } else {
            sink?.onSongChanged(song)
        }
        BaseIslandRenderer.refreshActiveIsland()
        if (restorePosition && song != null && !song.lyrics.isNullOrEmpty()) {
            sink?.onPositionChanged(lastAdjustedPosition)
        }
    }

    private fun simplifyAppleSongForDisplay(song: LocalSong): LocalSong {
        if (!isSimplifyTraditionalLyricsEnabled()) return song
        val application = app ?: return song
        return TraditionalLyricsSimplifier.simplify(song) { text ->
            ChineseUtils.toSimplified(application, text)
        }
    }

    private fun filterApplePronunciationForDisplay(song: LocalSong): LocalSong =
        ApplePronunciationVisibilityPolicy.filterSong(
            song = song,
            hideMandarinPinyin = isHideMandarinPinyinEnabled(),
        )

    internal fun simplifyAppleTextForDisplay(text: String?): String? {
        text ?: return null
        if (!isSimplifyTraditionalLyricsEnabled()) return text
        val application = app ?: return text
        return ChineseUtils.toSimplified(application, text)
    }

    internal fun isSaltPreferOnlineEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE,
        RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE
    ) ?: RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE

    internal fun configuredOnlineSources(): List<Source> =
        OnlineTranslationSourcePreferences.orderedSources(prefs)

    internal fun isOnlineTranslationEnabledFor(packageName: String?): Boolean =
        OnlineTranslationSourcePreferences.isAppEnabled(
            prefs,
            packageName ?: APPLE_MUSIC_PACKAGE,
        ) &&
            configuredOnlineSources().isNotEmpty()

    internal fun isAppleTranslationEnrichmentEnabled(): Boolean =
        isOnlineTranslationEnabledFor(APPLE_MUSIC_PACKAGE) || isNativeOnlineTranslationEnabled()

    internal fun isNativeOnlineTranslationEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION

    internal fun isFillMissingLyricsEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS

    internal fun isLunaBeatWordLyricsEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS

    internal fun isHideMandarinPinyinEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN

    internal fun shouldPreferAppleOriginalMetadata(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA

    private fun isSimplifyTraditionalLyricsEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS

    internal fun hasTranslation(song: LocalSong?): Boolean = song?.lyrics?.any {
        OnlineTranslationContentPolicy.isMeaningful(it.translation)
    } == true

    internal fun needsOnlineEnrichment(song: LocalSong?): Boolean {
        song ?: return false
        val completeOnlinePronunciation =
            ApplePronunciationVisibilityPolicy.allowsOnlineSupplementation(
                song = song,
                hideMandarinPinyin = isHideMandarinPinyinEnabled(),
            )
        // 全中文歌词不需要在线翻译：语气词（whoa/oh/ayy 等，含符号连接）不改变判定，
        // 避免「所有行都缺翻译」的中文歌被当成外文歌触发在线抓取。
        val fullyChinese = ChineseLyricsPolicy.isFullyChinese(song)
        return song.lyrics?.any {
            !it.text.isNullOrBlank() &&
                (
                    (!fullyChinese && !OnlineTranslationContentPolicy.isMeaningful(it.translation)) ||
                        (completeOnlinePronunciation && it.roma.isNullOrBlank())
                    )
        } == true
    }

    internal fun translationIdentity(song: LocalSong): String =
        AppleOnlineTranslationRequestPolicy.attemptKey(song)

    internal fun hasActiveCentralPlayer(): Boolean = activeCentralPlayerPackageName != null

    internal fun isSameTrack(first: LocalSong, second: LocalSong): Boolean =
        SourceTrackIdentity.of(first).matches(SourceTrackIdentity.of(second))

    internal fun songIdentity(song: LocalSong): String = SourceTrackIdentity.of(song).legacyKey()

    internal fun debug(message: String) {
        if (BuildConfig.DEBUG) HookLogger.d(TAG, message)
    }

    internal fun diagnostic(message: String) {
        if (BuildConfig.DEBUG) HookLogger.w(TAG, "[debug] $message")
    }

    internal fun pronunciationDiagnostic(message: String) {
        if (BuildConfig.DEBUG) Log.i(PRONUNCIATION_DIAGNOSTIC_TAG, message)
    }

    internal fun debugError(message: String, error: Throwable) {
        if (BuildConfig.DEBUG) HookLogger.e(TAG, message, error)
    }


internal val connectionListener = object : ConnectionListener {
    override fun onConnected(subscriber: LyriconSubscriber) {
        MediaCardDiagnosticLogger.log(
            stage = "subscriber",
            event = "connected",
            details = "subscriber=${MediaCardDiagnosticLogger.identity(subscriber)}",
        )
        HookLogger.i(TAG, "订阅连接已建立")
        diagnostic("stage=subscriber_connected")
        mainHandler.post { onCentralConnected?.invoke() }
    }

    override fun onReconnected(subscriber: LyriconSubscriber) {
        MediaCardDiagnosticLogger.log(
            stage = "subscriber",
            event = "reconnected",
            details = "subscriber=${MediaCardDiagnosticLogger.identity(subscriber)}",
        )
        HookLogger.i(TAG, "订阅连接已恢复")
        diagnostic("stage=subscriber_reconnected")
        mainHandler.post { onCentralConnected?.invoke() }
    }

    override fun onDisconnected(subscriber: LyriconSubscriber) {
        MediaCardDiagnosticLogger.log(
            stage = "subscriber",
            event = "disconnected",
            details = "subscriber=${MediaCardDiagnosticLogger.identity(subscriber)},activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)}",
        )
        centralAppleProviderActive = false
        centralAppleSongAvailable = false
        activeCentralPlayerPackageName = null
        activeProviderPackageName = null
        cancelThirdPartyFallback(reason = "subscriber_disconnected")
        HookLogger.w(TAG, "订阅连接已断开")
        diagnostic("stage=subscriber_disconnected")
    }

    override fun onConnectTimeout(subscriber: LyriconSubscriber) {
        MediaCardDiagnosticLogger.log(
            stage = "subscriber",
            event = "connect_timeout",
            details = "subscriber=${MediaCardDiagnosticLogger.identity(subscriber)}",
        )
        centralAppleProviderActive = false
        centralAppleSongAvailable = false
        activeCentralPlayerPackageName = null
        activeProviderPackageName = null
        cancelThirdPartyFallback(reason = "subscriber_connect_timeout")
        HookLogger.w(TAG, "订阅连接超时")
        diagnostic("stage=subscriber_connect_timeout")
        mainHandler.post {
            onCentralConnectTimeout?.invoke()
            diagnostic("stage=subscriber_retry_requested")
            subscriber.register()
            diagnostic("stage=subscriber_retry_returned")
        }
    }
}
internal val activePlayerListener = object : ActivePlayerListener {
    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        val playerPackageName = providerInfo?.playerPackageName
        MediaCardDiagnosticLogger.log(
            stage = "central",
            event = "active_provider_changed_begin",
            details = "provider=${MediaCardDiagnosticLogger.sanitize(providerInfo?.providerPackageName)},player=${MediaCardDiagnosticLogger.sanitize(playerPackageName)},process=${MediaCardDiagnosticLogger.sanitize(providerInfo?.processName)}",
        )
        diagnostic(
            "stage=central_active_provider_callback, " +
                "provider=${providerInfo?.providerPackageName}, " +
                "player=$playerPackageName, process=${providerInfo?.processName}",
        )
        logPlayerVersionSnapshot(
            playerPackageName = playerPackageName,
            providerPackageName = providerInfo?.providerPackageName,
            processName = providerInfo?.processName,
            source = "central_provider",
        )
        activeCentralPlayerPackageName = playerPackageName
        centralAppleSongAvailable = false
        evaluateActiveMediaSessionGate()
        if (playerPackageName == null && currentAppleSong != null) {
            centralAppleProviderActive = false
            diagnostic(
                "忽略 Central 空提供者状态: directTitle=${currentAppleSong?.name}"
            )
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "active_provider_dropped",
                reason = "empty_player_preserved_direct_song",
                details = "directTitle=${MediaCardDiagnosticLogger.sanitize(currentAppleSong?.name)}",
            )
            return
        }

        val preserveDirectAppleSong =
            playerPackageName == LyriconSource.APPLE_MUSIC_PACKAGE &&
                providerInfo.providerPackageName == BUILT_IN_PROVIDER_PACKAGE &&
                currentAppleSong != null
        cancelFallback(
            clearAppleSong = !preserveDirectAppleSong,
            reason = "central_provider_changed",
        )
        cancelThirdPartyFallback(reason = "central_provider_changed")
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "central_provider_changed"
        )
        applePositionState.lastAdjustedPosition = 0L
        centralPlaybackPositionWitness.onSinkStopped()
        sink?.onStop()
        centralAppleProviderActive =
            playerPackageName == LyriconSource.APPLE_MUSIC_PACKAGE
        if (!centralAppleProviderActive) {
            publication.resetPublishedApple()
        }
        publication.resetThirdParty()
        activeProviderPackageName = providerInfo?.providerPackageName
        activeProviderDelayMs = providerInfo?.providerPackageName
            ?.let(::readProviderDelay)
            ?: RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
        LyriconDataBridge.updateLyricPackage(playerPackageName)
        MediaCardDiagnosticLogger.log(
            stage = "central",
            event = "active_provider_changed_complete",
            details = "provider=${MediaCardDiagnosticLogger.sanitize(activeProviderPackageName)},player=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},apple=$centralAppleProviderActive,delayMs=$activeProviderDelayMs,preserveDirectAppleSong=$preserveDirectAppleSong",
        )
        if (preserveDirectAppleSong) {
            currentAppleSong?.let { directSong ->
                diagnostic(
                    "stage=direct_song_preserved_until_central_snapshot, " +
                        "id=${directSong.id}, title=${directSong.name}"
                )
                publishAppleSong(directSong, restorePosition = true)
            }
        }
    }


    override fun onSongChanged(song: LyriconSong?) {
        val localSong = song?.toLocalSong()
        MediaCardDiagnosticLogger.log(
            stage = "central",
            event = "song_callback_begin",
            details = "incomingId=${MediaCardDiagnosticLogger.sanitize(localSong?.id)},incomingTitle=${MediaCardDiagnosticLogger.sanitize(localSong?.name)},incomingLines=${localSong?.lyrics.orEmpty().size},activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},provider=${MediaCardDiagnosticLogger.sanitize(activeProviderPackageName)}",
        )
        diagnostic(
            "stage=central_song_callback, id=${localSong?.id}, title=${localSong?.name}, " +
                "lyrics=${localSong?.lyrics.orEmpty().size}, " +
                "translated=${localSong?.lyrics.orEmpty().count { !it.translation.isNullOrBlank() }}, " +
                "activePlayer=$activeCentralPlayerPackageName, " +
                "centralAppleProviderActive=$centralAppleProviderActive",
        )
        if (isCentralPlayerBlockedByMediaSession()) {
            diagnostic(
                "stage=central_song_dropped, reason=no_active_media_session, " +
                    "title=${localSong?.name}",
            )
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "song_callback_dropped",
                reason = "no_active_media_session",
                details = "incomingId=${MediaCardDiagnosticLogger.sanitize(localSong?.id)},incomingTitle=${MediaCardDiagnosticLogger.sanitize(localSong?.name)}",
            )
            return
        }
        if (centralAppleProviderActive) {
            centralAppleSongAvailable = !localSong?.lyrics.isNullOrEmpty()
            cancelThirdPartyFallback(reason = "central_apple_song")
            handleAppleSong(localSong)
        } else {
            if (activeCentralPlayerPackageName == null) {
                diagnostic(
                    "忽略无活动提供者的 Central 歌曲回调: " +
                        "title=${localSong?.name}, directTitle=${currentAppleSong?.name}"
                )
                return
            }
            cancelFallback(clearAppleSong = true, reason = "central_non_apple_song")
            cancelOnlineTranslation(
                clearAttempt = true,
                clearMatched = true,
                reason = "central_non_apple_song"
            )
            handleThirdPartySong(localSong)
        }
        MediaCardDiagnosticLogger.log(
            stage = "central",
            event = "song_callback_complete",
            details = "incomingId=${MediaCardDiagnosticLogger.sanitize(localSong?.id)},publishedId=${MediaCardDiagnosticLogger.sanitize(LyriconDataBridge.currentSong?.id)},apple=$centralAppleProviderActive,sink=${sink != null}",
        )
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        val blocked = isCentralPlayerBlockedByMediaSession()
        MediaCardDiagnosticLogger.log(
            stage = "central",
            event = "playback_state_callback",
            details = "isPlaying=$isPlaying,blocked=$blocked,activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},provider=${MediaCardDiagnosticLogger.sanitize(activeProviderPackageName)},sink=${sink != null}",
        )
        if (blocked) {
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "playback_state_dropped",
                reason = "no_active_media_session",
                details = "isPlaying=$isPlaying",
            )
            return
        }
        val shouldForward = shouldForwardCentralPlaybackState(
            hasActiveCentralPlayer = hasActiveCentralPlayer(),
            centralAppleProviderActive = centralAppleProviderActive,
            fallbackSongActive = fallbackSongActive,
        )
        if (!shouldForward) {
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "playback_state_dropped",
                reason = "forward_policy_rejected",
                details = "isPlaying=$isPlaying,activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},apple=$centralAppleProviderActive,fallback=$fallbackSongActive",
            )
            return
        }
        centralPlaybackPositionWitness.onSinkPlaybackState(isPlaying)
        sink?.onPlaybackStateChanged(isPlaying)
        MediaCardDiagnosticLogger.log(
            stage = "central",
            event = "playback_state_forwarded",
            details = "isPlaying=$isPlaying",
        )
    }

    override fun onPositionChanged(position: Long) {
        if (!hasActiveCentralPlayer()) {
            logCentralPositionDiagnostic(position, null, "dropped_no_active_player")
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "position_dropped",
                reason = "no_active_player",
                details = "rawPosition=$position",
                positionSample = true,
            )
            return
        }
        val blocked = isCentralPlayerBlockedByMediaSession()
        if (blocked) {
            logCentralPositionDiagnostic(position, null, "dropped_no_active_media_session")
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "position_dropped",
                reason = "no_active_media_session",
                details = "rawPosition=$position,activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)}",
                positionSample = true,
            )
            return
        }
        if (isBuiltInAppleCentralProviderActive() &&
            centralPlaybackPositionWitness.observeActivePosition(SystemClock.elapsedRealtime())
        ) {
            diagnostic(
                "stage=central_playback_recovered_from_position_witness, " +
                    "position=$position, player=$activeCentralPlayerPackageName, " +
                    "provider=$activeProviderPackageName",
            )
            sink?.onPlaybackStateChanged(true)
        }
        if (centralAppleProviderActive && fallbackSongActive) {
            logCentralPositionDiagnostic(position, null, "dropped_apple_fallback_active")
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "position_dropped",
                reason = "apple_fallback_active",
                details = "rawPosition=$position",
                positionSample = true,
            )
            return
        }
        val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
        if (centralAppleProviderActive) {
            val resolution = resolveApplePosition(adjustedPosition, explicitSeek = false)
            applePositionState.applyRestorablePosition(resolution)
            logAppleTimingDiagnostic(
                path = "central",
                rawPosition = position,
                adjustedPosition = adjustedPosition,
                resolution = resolution,
            )
            val resolvedPosition = resolution.position
            if (resolvedPosition == null) {
                logCentralPositionDiagnostic(position, null, "dropped_apple_resolution")
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "position_dropped",
                    reason = "apple_resolution_null",
                    details = "rawPosition=$position,adjustedPosition=$adjustedPosition",
                    positionSample = true,
                )
                return
            }
            maybeCommitPendingOnlineTranslation(resolvedPosition)
            sink?.onPositionChanged(resolvedPosition)
            logCentralPositionDiagnostic(position, resolvedPosition, "forwarded_apple")
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "position_forwarded",
                details = "rawPosition=$position,forwardedPosition=$resolvedPosition,apple=true,sink=${sink != null}",
                positionSample = true,
            )
            return
        }
        maybeCommitPendingOnlineTranslation(adjustedPosition)
        sink?.onPositionChanged(adjustedPosition)
        logCentralPositionDiagnostic(position, adjustedPosition, "forwarded_non_apple")
        MediaCardDiagnosticLogger.log(
            stage = "central",
            event = "position_forwarded",
            details = "rawPosition=$position,forwardedPosition=$adjustedPosition,apple=false,sink=${sink != null}",
            positionSample = true,
        )
    }


    override fun onSeekTo(position: Long) {
        val blocked = isCentralPlayerBlockedByMediaSession()
        MediaCardDiagnosticLogger.log(
            stage = "central",
            event = "seek_callback",
            details = "rawPosition=$position,activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},blocked=$blocked,apple=$centralAppleProviderActive,sink=${sink != null}",
            positionSample = true,
        )
        if (!hasActiveCentralPlayer()) return
        if (blocked) return
        if (centralAppleProviderActive && fallbackSongActive) return
        val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
        if (centralAppleProviderActive) {
            val resolution = resolveApplePosition(adjustedPosition, explicitSeek = true)
            applePositionState.applyRestorablePosition(resolution)
            logAppleTimingDiagnostic(
                path = "central_seek",
                rawPosition = position,
                adjustedPosition = adjustedPosition,
                resolution = resolution,
                force = true,
            )
            val resolvedPosition = resolution.position ?: run {
                MediaCardDiagnosticLogger.log(
                    stage = "central",
                    event = "seek_dropped",
                    reason = "apple_resolution_null",
                    details = "rawPosition=$position,adjustedPosition=$adjustedPosition",
                    positionSample = true,
                )
                return
            }
            maybeCommitPendingOnlineTranslation(resolvedPosition)
            sink?.onSeekTo(resolvedPosition)
            MediaCardDiagnosticLogger.log(
                stage = "central",
                event = "seek_forwarded",
                details = "rawPosition=$position,forwardedPosition=$resolvedPosition,apple=true",
                positionSample = true,
            )
            return
        }
        maybeCommitPendingOnlineTranslation(adjustedPosition)
        sink?.onSeekTo(adjustedPosition)
        MediaCardDiagnosticLogger.log(
            stage = "central",
            event = "seek_forwarded",
            details = "rawPosition=$position,forwardedPosition=$adjustedPosition,apple=false",
            positionSample = true,
        )
    }

    override fun onReceiveText(text: String?) {
        val controlFrame = OfficialProviderSubscriberControlFrame.inspect(text)
        if (controlFrame.consumed) {
            val providerPackage = activeProviderPackageName
            val playerPackage = activeCentralPlayerPackageName
            val result = if (
                controlFrame.frame != null &&
                !providerPackage.isNullOrBlank() &&
                !playerPackage.isNullOrBlank()
            ) {
                NextTrackMetadataCache.accept(
                    providerPackageName = providerPackage,
                    playerPackageName = playerPackage,
                    frame = controlFrame.frame,
                ).name
            } else {
                "FILTERED_WITHOUT_CACHE"
            }
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "外置 Central 控制帧已消费: result=$result, " +
                        "provider=$providerPackage, player=$playerPackage",
                )
            }
            return
        }
        if (!hasActiveCentralPlayer()) return
        if (centralAppleProviderActive && fallbackSongActive) return
        sink?.onPlainText(
            if (centralAppleProviderActive) simplifyAppleTextForDisplay(text) else text
        )
    }

    // 提供器只负责提供歌词内容；翻译和罗马音是否显示由 HyperLyrics Enhanced 显示端配置决定。
    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
}

}
