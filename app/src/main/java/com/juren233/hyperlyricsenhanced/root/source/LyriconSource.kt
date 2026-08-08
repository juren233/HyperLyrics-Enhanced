package com.juren233.hyperlyricsenhanced.root.source

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.TraditionalLyricsSimplifier
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSource
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.utils.ChineseUtils
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.proify.lyricon.amprovider.xposed.AppleDirectBridgeContract
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
import kotlin.math.abs

class LyriconSource : LyricSource {

    companion object {
        private const val TAG = "LyriconSource"
        private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        private const val BUILT_IN_PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced"
        private const val APPLE_LYRICS_GRACE_MS = 5_000L
        private const val APPLE_MEDIA_MONITOR_INTERVAL_MS = 1_000L
        private const val SAME_TRACK_DURATION_TOLERANCE_MS = 2_000L
        private const val TIMING_DIAGNOSTIC_INTERVAL_MS = 5_000L
        private const val PRONUNCIATION_DIAGNOSTIC_TAG = "ApplePronunciationDiag"
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }

    private fun LyriconSong.toLocalSong(): LocalSong {
        val jsonString = json.encodeToString(this)
        return json.decodeFromString(jsonString)
    }

    override val id = "lyricon"
    override val displayName = "Lyricon"

    @Volatile
    private var sink: LyricSink? = null
    private var app: Application? = null
    @Volatile
    private var subscriber: LyriconSubscriber? = null

    private var activeProviderPackageName: String? = null
    @Volatile
    private var activeCentralPlayerPackageName: String? = null
    private var activeProviderDelayMs: Int = RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
    private var prefs: android.content.SharedPreferences? = null
    private var onCentralConnected: (() -> Unit)? = null
    private var onCentralConnectTimeout: (() -> Unit)? = null
    private var directBridge: AppleMusicDirectBridge? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fallbackRequestMutex = Mutex()
    private val mediaPositionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fallbackJob: Job? = null
    private var onlineTranslationJob: Job? = null
    private var mediaPositionJob: Job? = null
    private var fallbackDelayRunnable: Runnable? = null
    private var fallbackGeneration = 0
    private var onlineTranslationGeneration = 0
    private var onlineTranslationAttemptKey: String? = null
    private var originalMetadataRequestKey: String? = null
    private var onlineMatchedTranslationActive = false
    private var temporaryTranslationSource: Source? = null
    private var temporaryPronunciationSource: Source? = null
    private var pendingTranslationSourceRequest: OnlineSourceSwitchRequest? = null
    private var pendingPronunciationSourceRequest: OnlineSourceSwitchRequest? = null
    private var currentAppleSong: LocalSong? = null
    private var currentAppleHasNativeLyrics = false
    private var currentPublishedAppleSong: LocalSong? = null
    private var currentPublishedAppleOnlineTranslationMatched = false
    @Volatile
    private var fallbackSongActive = false
    private var lastAdjustedPosition = 0L
    private var appleSongGeneration = 0
    @Volatile
    private var appleMediaPositionReference: AppleCentralPositionPolicy.MediaReference? = null
    @Volatile
    private var appleDirectPositionReference: AppleCentralPositionPolicy.DirectReference? = null
    @Volatile
    private var currentDirectAppleSongId: String? = null
    private var lastObservedMediaKey: String? = null
    private var lastMediaPlaybackState: Boolean? = null
    private var lastTimingDiagnosticAtMs = 0L
    private var lastTimingDiagnosticPosition = -1L
    private var lastTimingDiagnosticState: String? = null
    private var lastCentralPositionDiagnosticAtMs = 0L

    private val appleMediaMonitor = object : Runnable {
        override fun run() {
            if (sink == null) return
            observeAppleMediaSession()
            mainHandler.postDelayed(this, APPLE_MEDIA_MONITOR_INTERVAL_MS)
        }
    }

    @Volatile
    private var centralAppleProviderActive = false


    override fun isAvailable(): Boolean = true

    override fun start(sink: LyricSink) {
        if (this.subscriber != null) {
            HookLogger.d(TAG, "跳过重复启动: reason=already_running")
            return
        }
        this.sink = sink
        val application = app ?: run {
            HookLogger.w(TAG, "数据源启动延后: reason=application_unavailable")
            return
        }
        directBridge = AppleMusicDirectBridge(application, this).also { it.start() }
        initializeSubscriber(application)
        startAppleMediaMonitor()
        HookLogger.i(TAG, "数据源已启动")
    }

    override fun stop() {
        stopAppleMediaMonitor()
        cancelFallback(clearAppleSong = true, reason = "source_stopped")
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
            activeCentralPlayerPackageName = null
            activeProviderPackageName = null
            currentPublishedAppleSong = null
            currentPublishedAppleOnlineTranslationMatched = false
            appleMediaPositionReference = null
            appleDirectPositionReference = null
            currentDirectAppleSongId = null
            subscriber = null
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
        this.onCentralConnected = onCentralConnected
        this.onCentralConnectTimeout = onCentralConnectTimeout

        LyriconDataBridge.onAiTranslationComplete = {
            BaseIslandRenderer.refreshActiveIsland()
        }
    }

    fun onPreferenceChanged(key: String?) {
        val packageName = activeProviderPackageName
        if (packageName != null && key == providerDelayKey(packageName)) {
            activeProviderDelayMs = readProviderDelay(packageName)
            return
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_ONLINE_FALLBACK ||
            key == RootConstants.KEY_HOOK_APPLE_MUSIC_FALLBACK_QQ_FIRST
        ) {
            mainHandler.post { applyFallbackPreferenceChange(key) }
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION ||
            key == RootConstants.KEY_HOOK_APPLE_MUSIC_TRANSLATION_QQ_FIRST
        ) {
            mainHandler.post { applyOnlineTranslationPreferenceChange(key) }
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA) {
            mainHandler.post(::applyOriginalMetadataPreferenceChange)
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS) {
            mainHandler.post(::applySimplifiedLyricsPreferenceChange)
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION) {
            mainHandler.post(::applyNativeOnlineTranslationPreferenceChange)
        }
        if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN) {
            mainHandler.post(::applyMandarinPinyinPreferenceChange)
        }
    }

    private fun applyNativeOnlineTranslationPreferenceChange() {
        val bridge = directBridge ?: return
        val song = currentPublishedAppleSong
        if (
            isNativeOnlineTranslationEnabled() &&
            currentPublishedAppleOnlineTranslationMatched &&
            song != null
        ) {
            bridge.publishOnlineTranslation(
                ApplePronunciationVisibilityPolicy.filterSong(
                    song = song,
                    hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                )
            )
        } else {
            bridge.clearOnlineTranslation(song?.id ?: currentAppleSong?.id)
        }
    }

    private fun applyMandarinPinyinPreferenceChange() {
        val nativeSong = currentAppleSong ?: return
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "mandarin_pinyin_preference_changed",
        )
        publishAppleSong(nativeSong, restorePosition = true)
        if (
            isOnlineTranslationMatchEnabled() &&
            !nativeSong.lyrics.isNullOrEmpty() &&
            needsOnlineEnrichment(nativeSong)
        ) {
            scheduleOnlineTranslation(nativeSong)
        }
    }

    private fun applyOriginalMetadataPreferenceChange() {
        val nativeSong = currentAppleSong ?: return
        originalMetadataRequestKey = null
        cancelFallback(clearAppleSong = false, reason = "original_metadata_preference_changed")
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = true,
            reason = "original_metadata_preference_changed",
        )
        val originalMetadataPlan = AppleOnlineTranslationRequestPolicy.originalMetadataLookupPlan(
            shouldRequestOriginalMetadataForOnlineLookup(nativeSong)
        )
        if (originalMetadataPlan.requestOriginalMetadata) {
            requestOriginalMetadata(nativeSong, "preference_changed")
        }
        if (
            !originalMetadataPlan.waitForResult &&
            nativeSong.lyrics.isNullOrEmpty() &&
            isFallbackEnabled()
        ) {
            scheduleFallback(nativeSong, 0L)
        } else if (
            !originalMetadataPlan.waitForResult &&
            !nativeSong.lyrics.isNullOrEmpty() &&
            needsOnlineEnrichment(nativeSong) &&
            isOnlineTranslationMatchEnabled()
        ) {
            scheduleOnlineTranslation(nativeSong)
        }
    }

    private fun applyFallbackPreferenceChange(key: String) {
        if (!isFallbackEnabled()) {
            val nativeSong = currentAppleSong
            val shouldRestoreNative = fallbackSongActive && nativeSong != null
            cancelFallback(clearAppleSong = false, reason = "fallback_disabled")
            if (shouldRestoreNative) {
                publishAppleSong(nativeSong, restorePosition = true)
            }
            return
        }

        val nativeSong = currentAppleSong ?: return
        if (currentAppleHasNativeLyrics) return
        val delayMs = if (
            key == RootConstants.KEY_HOOK_APPLE_MUSIC_FALLBACK_QQ_FIRST && fallbackSongActive
        ) 0L else APPLE_LYRICS_GRACE_MS
        scheduleFallback(nativeSong, delayMs)
        observeAppleMediaSession(force = true)
    }

    private fun applyOnlineTranslationPreferenceChange(key: String) {
        val nativeSong = currentAppleSong ?: return
        if (!isOnlineTranslationMatchEnabled()) {
            val wasPending = onlineTranslationJob?.isActive == true
            val shouldRestoreNative = onlineMatchedTranslationActive
            cancelOnlineTranslation(
                clearAttempt = true,
                clearMatched = true,
                reason = "online_translation_disabled"
            )
            if (shouldRestoreNative) {
                publishAppleSong(nativeSong, restorePosition = true)
            } else if (wasPending) {
                sink?.onOnlineTranslationUnavailable(nativeSong)
            }
            return
        }
        if (nativeSong.lyrics.isNullOrEmpty() || !needsOnlineEnrichment(nativeSong)) return

        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = false,
            reason = if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_TRANSLATION_QQ_FIRST) {
                "preferred_source_changed"
            } else {
                "online_translation_enabled"
            }
        )
        scheduleOnlineTranslation(nativeSong)
    }

    private fun providerDelayKey(packageName: String): String {
        return RootConstants.KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX + packageName
    }

    private fun readProviderDelay(packageName: String): Int {
        return prefs?.getInt(
            providerDelayKey(packageName),
            RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
        )?.coerceIn(
            RootConstants.MIN_HOOK_LYRICON_PROVIDER_DELAY,
            RootConstants.MAX_HOOK_LYRICON_PROVIDER_DELAY
        ) ?: RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
    }

    private fun handleAppleSong(song: LocalSong?) {
        diagnostic(
            "Apple Music 歌曲入口: id=${song?.id}, title=${song?.name}, " +
                "artist=${song?.artist}, duration=${song?.duration}, " +
                "lyrics=${song?.lyrics.orEmpty().size}, fallbackEnabled=${isFallbackEnabled()}"
        )
        val previousSong = currentAppleSong
        val sameTrack = previousSong != null && song != null && isSameTrack(previousSong, song)
        val preservesCurrentLyrics = previousSong != null && song != null &&
            AppleSongUpdatePolicy.shouldPreserveCurrentLyrics(previousSong, song, sameTrack)
        if (preservesCurrentLyrics) {
            debug("忽略同一首歌的空歌词降级: title=${song.name}")
            return
        }
        val originalMetadataChanged = sameTrack &&
            AppleOnlineTranslationRequestPolicy.originalMetadataChanged(previousSong, song)
        val repeatedEmptySong = sameTrack && !originalMetadataChanged && song.lyrics.isNullOrEmpty() &&
            (fallbackSongActive || fallbackDelayRunnable != null || fallbackJob?.isActive == true)
        if (repeatedEmptySong) {
            currentAppleSong = song
            currentAppleHasNativeLyrics = false
            debug("忽略同一首歌的重复空歌词占位: title=${song.name}")
            return
        }
        val repeatedNativeNeedingEnrichment = sameTrack &&
            !song.lyrics.isNullOrEmpty() &&
            needsOnlineEnrichment(song) &&
            !originalMetadataChanged &&
            (onlineTranslationJob?.isActive == true || onlineMatchedTranslationActive)
        if (repeatedNativeNeedingEnrichment) {
            currentAppleSong = song
            currentAppleHasNativeLyrics = true
            debug("忽略同一首歌的重复待补全原生歌词: title=${song.name}")
            return
        }
        cancelFallback(clearAppleSong = false, reason = "apple_song_updated")
        val incomingHasTranslation = hasTranslation(song)
        val incomingNeedsEnrichment = needsOnlineEnrichment(song)
        cancelOnlineTranslation(
            clearAttempt = !sameTrack || !incomingNeedsEnrichment || originalMetadataChanged,
            clearMatched = true,
            reason = "apple_song_updated"
        )
        currentAppleSong = song
        currentAppleHasNativeLyrics = !song?.lyrics.isNullOrEmpty()
        if (!sameTrack) {
            appleSongGeneration += 1
            appleMediaPositionReference = null
            appleDirectPositionReference = null
            lastAdjustedPosition = 0L
            originalMetadataRequestKey = null
            temporaryTranslationSource = null
            temporaryPronunciationSource = null
            pendingTranslationSourceRequest = null
            pendingPronunciationSourceRequest = null
            refreshAppleMediaPositionReference()
        }
        val originalMetadataPlan = AppleOnlineTranslationRequestPolicy.originalMetadataLookupPlan(
            song != null && shouldRequestOriginalMetadataForOnlineLookup(song)
        )
        pronunciationDiagnostic(
            "stage=request_entry_gate, id=${song?.id}, generation=$appleSongGeneration, " +
                "prefsPresent=${prefs != null}, matchingEnabled=${isOnlineTranslationMatchEnabled()}, " +
                "lyrics=${song?.lyrics.orEmpty().size}, needsEnrichment=${needsOnlineEnrichment(song)}, " +
                "titlePresent=${!song?.name.isNullOrBlank()}, " +
                "requestOriginal=${originalMetadataPlan.requestOriginalMetadata}, " +
                "waitOriginal=${originalMetadataPlan.waitForResult}, sameTrack=$sameTrack, " +
                "originalMetadataChanged=$originalMetadataChanged"
        )
        if (song != null && originalMetadataPlan.requestOriginalMetadata) {
            requestOriginalMetadata(song, "setting_enabled")
        }

        runCatching {
            publishAppleSong(song, restorePosition = sameTrack)
        }.onFailure {
            debugError("Apple Music 歌曲发布失败: title=${song?.name}", it)
        }

        if (
            song != null &&
            !originalMetadataPlan.waitForResult &&
            song.lyrics.isNullOrEmpty() &&
            isFallbackEnabled()
        ) {
            HookLogger.i(
                TAG,
                "Apple Music 原生歌词未返回，允许在线兜底: title=${song.name}"
            )
            scheduleFallback(song, APPLE_LYRICS_GRACE_MS)
        }
        if (song != null && !originalMetadataPlan.waitForResult &&
            !song.lyrics.isNullOrEmpty() && incomingNeedsEnrichment &&
            isOnlineTranslationMatchEnabled()
        ) {
            HookLogger.i(
                TAG,
                "Apple Music 原生歌词待补全: title=${song.name}, " +
                    "lines=${song.lyrics.orEmpty().size}, " +
                    "hasTranslation=$incomingHasTranslation"
            )
            if (originalMetadataChanged) {
                HookLogger.i(
                    TAG,
                    "Apple Music 原名已更新，重新匹配在线翻译: " +
                        "title=${song.name}, originalTitle=${song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE)}"
                )
            }
            scheduleOnlineTranslation(song)
        }
    }

    private fun publishAppleSong(
        song: LocalSong?,
        restorePosition: Boolean,
        onlineTranslationMatched: Boolean = false
    ) {
        currentPublishedAppleSong = song
        currentPublishedAppleOnlineTranslationMatched = onlineTranslationMatched
        if (BuildConfig.DEBUG) {
            HookLogger.i(
                TAG,
                "[debug] Timing publish: songId=${song?.id}, restorePosition=$restorePosition, " +
                    "lastAdjustedPosition=$lastAdjustedPosition, " +
                    "onlineTranslationMatched=$onlineTranslationMatched, " +
                    "centralPlayer=$activeCentralPlayerPackageName, fallback=$fallbackSongActive"
            )
        }
        publishSong(
            song = AppleSongDisplayPolicy.copyForDisplay(song)
                ?.let(::filterApplePronunciationForDisplay)
                ?.let(::simplifyAppleSongForDisplay),
            restorePosition = restorePosition,
            onlineTranslationMatched = onlineTranslationMatched
        )
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

    private fun applySimplifiedLyricsPreferenceChange() {
        val song = currentPublishedAppleSong ?: return
        publishAppleSong(
            song = song,
            restorePosition = true,
            onlineTranslationMatched = currentPublishedAppleOnlineTranslationMatched
        )
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

    private fun simplifyAppleTextForDisplay(text: String?): String? {
        text ?: return null
        if (!isSimplifyTraditionalLyricsEnabled()) return text
        val application = app ?: return text
        return ChineseUtils.toSimplified(application, text)
    }

    private fun scheduleFallback(baseSong: LocalSong, delayMs: Long) {
        if (baseSong.name.isNullOrBlank() || !isFallbackEnabled()) return
        fallbackGeneration += 1
        val generation = fallbackGeneration
        fallbackDelayRunnable?.let(mainHandler::removeCallbacks)
        fallbackDelayRunnable = null
        fallbackJob?.cancel()
        fallbackJob = null

        val delayedSearch = Runnable {
            if (generation != fallbackGeneration) return@Runnable
            fallbackDelayRunnable = null
            val application = app
            if (application == null) {
                diagnostic(
                    "Apple Music 在线兜底无法启动: reason=application_unavailable, " +
                        "title=${baseSong.name}"
                )
                return@Runnable
            }
            fallbackJob = fallbackScope.launch {
                try {
                    fallbackRequestMutex.withLock {
                        if (generation != fallbackGeneration) return@withLock
                        val preferredSource = if (isQqFirst()) Source.QM else Source.NE
                        diagnostic(
                            "Apple Music 在线兜底开始: title=${baseSong.name}, " +
                                "artist=${baseSong.artist}, source=$preferredSource"
                        )
                        val lines = OnlineLyricTargeter.fetchBestLyric(
                            context = application,
                            pkgName = APPLE_MUSIC_PACKAGE,
                            title = baseSong.name.orEmpty(),
                            artist = baseSong.artist.orEmpty(),
                            durationMs = baseSong.duration,
                            originalTitle = baseSong.metadata
                                ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
                            originalArtist = baseSong.metadata
                                ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST),
                            preferOriginalMetadata = shouldPreferAppleOriginalMetadata(),
                            preferredSource = preferredSource
                        )
                        val fallbackSong = lines?.let {
                            OnlineFallbackSongMapper.map(baseSong, it)
                        }
                        mainHandler.post {
                            applyFallbackResult(generation, baseSong, fallbackSong, application)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    debugError("Apple Music 在线兜底失败: title=${baseSong.name}", e)
                }
            }
        }
        fallbackDelayRunnable = delayedSearch
        diagnostic(
            "Apple Music 在线兜底已调度: title=${baseSong.name}, " +
                "delayMs=$delayMs, generation=$generation"
        )
        mainHandler.postDelayed(delayedSearch, delayMs)
    }

    private fun applyFallbackResult(
        generation: Int,
        baseSong: LocalSong,
        fallbackSong: LocalSong?,
        application: Application
    ) {
        val nativeSong = currentAppleSong
        val requestStillCurrent = generation == fallbackGeneration &&
            nativeSong != null &&
            isSameTrack(nativeSong, baseSong) &&
            !currentAppleHasNativeLyrics &&
            isFallbackEnabled()
        if (!requestStillCurrent) {
            diagnostic(
                "Apple Music 在线兜底结果已过期: title=${baseSong.name}, " +
                    "generation=$generation, currentGeneration=$fallbackGeneration"
            )
            return
        }

        fallbackJob = null
        if (fallbackSong == null) {
            diagnostic("Apple Music 在线兜底未命中: title=${baseSong.name}")
            requestOriginalMetadata(baseSong, "lyrics_fallback_miss")
            return
        }
        fallbackSongActive = true
        MediaMetadataHelper.getPlaybackProgress(application, APPLE_MUSIC_PACKAGE)
            .position
            .takeIf { it >= 0L }
            ?.let { lastAdjustedPosition = it }
        HookLogger.i(
            TAG,
            "Apple Music 在线兜底命中: title=${baseSong.name}, " +
                "lines=${fallbackSong.lyrics.orEmpty().size}, " +
                "translations=${fallbackSong.lyrics.orEmpty().count {
                    OnlineTranslationContentPolicy.isMeaningful(it.translation)
                }}"
        )
        val fallbackHasTranslation = hasTranslation(fallbackSong)
        publishAppleSong(
            fallbackSong,
            restorePosition = true,
            onlineTranslationMatched = fallbackHasTranslation
        )
        if (!fallbackHasTranslation) sink?.onOnlineTranslationUnavailable(fallbackSong)
        startMediaPositionPolling()
    }

    private fun cancelFallback(clearAppleSong: Boolean, reason: String) {
        if (fallbackDelayRunnable != null || fallbackJob?.isActive == true || fallbackSongActive) {
            diagnostic(
                "Apple Music 在线兜底取消: reason=$reason, " +
                    "clearAppleSong=$clearAppleSong, title=${currentAppleSong?.name}"
            )
        }
        fallbackGeneration += 1
        fallbackDelayRunnable?.let(mainHandler::removeCallbacks)
        fallbackDelayRunnable = null
        fallbackJob?.cancel()
        fallbackJob = null
        fallbackSongActive = false
        stopMediaPositionPolling()
        if (clearAppleSong) {
            currentAppleSong = null
            currentAppleHasNativeLyrics = false
        }
    }

    private fun scheduleOnlineTranslation(baseSong: LocalSong): Boolean {
        val matchingEnabled = isOnlineTranslationMatchEnabled()
        val nativeLineCount = baseSong.lyrics.orEmpty().size
        val enrichmentNeeded = needsOnlineEnrichment(baseSong)
        val titlePresent = !baseSong.name.isNullOrBlank()
        if (!matchingEnabled || nativeLineCount == 0 || !enrichmentNeeded || !titlePresent) {
            pronunciationDiagnostic(
                "stage=request_schedule_skipped, reason=precondition_failed, " +
                    "id=${baseSong.id}, prefsPresent=${prefs != null}, " +
                    "matchingEnabled=$matchingEnabled, nativeLines=$nativeLineCount, " +
                    "needsEnrichment=$enrichmentNeeded, titlePresent=$titlePresent"
            )
            return false
        }
        val attemptKey = translationIdentity(baseSong)
        if (onlineTranslationAttemptKey == attemptKey) {
            pronunciationDiagnostic(
                "stage=request_schedule_skipped, reason=attempt_already_recorded, " +
                    "id=${baseSong.id}, attempt=$attemptKey"
            )
            return false
        }

        onlineTranslationAttemptKey = attemptKey
        onlineTranslationGeneration += 1
        val generation = onlineTranslationGeneration
        pronunciationDiagnostic(
            "stage=request_scheduled, generation=$generation, id=${baseSong.id}, " +
                "attempt=$attemptKey, nativeLines=${baseSong.lyrics.orEmpty().size}"
        )
        onlineTranslationJob?.cancel()
        onlineTranslationJob = fallbackScope.launch {
            try {
                fallbackRequestMutex.withLock {
                    if (generation != onlineTranslationGeneration) {
                        pronunciationDiagnostic(
                            "stage=request_abandoned, generation=$generation, id=${baseSong.id}, " +
                                "reason=generation_changed_before_start, " +
                                "currentGeneration=$onlineTranslationGeneration"
                        )
                        return@withLock
                    }
                    val application = app ?: run {
                        pronunciationDiagnostic(
                            "stage=request_abandoned, generation=$generation, id=${baseSong.id}, " +
                                "reason=application_unavailable"
                        )
                        return@withLock
                    }
                    val preferredSource = if (isTranslationQqFirst()) Source.QM else Source.NE
                    val alternativeSource = if (preferredSource == Source.QM) Source.NE else Source.QM
                    val requestedFirstSource =
                        pendingTranslationSourceRequest?.requestedSource
                            ?: pendingPronunciationSourceRequest?.requestedSource
                    val firstSource = requestedFirstSource ?: preferredSource
                    val secondSource = if (firstSource == Source.QM) Source.NE else Source.QM
                    val completeOnlinePronunciation =
                        ApplePronunciationVisibilityPolicy.allowsOnlineSupplementation(
                            song = baseSong,
                            hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                        )
                    val totalLineCount = baseSong.lyrics.orEmpty().sumOf { line ->
                        if (line.text.isNullOrBlank()) {
                            0
                        } else {
                            (if (!OnlineTranslationContentPolicy.isMeaningful(line.translation)) 1 else 0) +
                            (if (
                                completeOnlinePronunciation && line.roma.isNullOrBlank()
                            ) 1 else 0)
                        }
                    }
                    val mediaInfo = MediaMetadataHelper.getMediaInfo(
                        application,
                        APPLE_MUSIC_PACKAGE,
                    )
                    val searchDuration = AppleOnlineTranslationSearchDurationPolicy.resolve(
                        song = baseSong,
                        media = AppleOnlineTranslationSearchDurationPolicy.MediaSnapshot(
                            title = mediaInfo.title,
                            artist = mediaInfo.artist,
                            durationMs = mediaInfo.duration,
                        ),
                    )
                    diagnostic(
                        "Apple Music 在线歌词补全开始: title=${baseSong.name}, " +
                            "artist=${baseSong.artist}, preferred=$preferredSource, " +
                            "first=$firstSource, requested=${requestedFirstSource ?: "none"}"
                    )
                    pronunciationDiagnostic(
                        "stage=search_duration_resolved, generation=$generation, id=${baseSong.id}, " +
                            "lyricDuration=${baseSong.duration}, mediaDuration=${mediaInfo.duration}, " +
                            "mediaTitle=${mediaInfo.title}, mediaArtist=${mediaInfo.artist}, " +
                            "identityMatched=${searchDuration.mediaIdentityMatched}, " +
                            "selectedDuration=${searchDuration.durationMs}"
                    )
                    pronunciationDiagnostic(
                        "stage=request_started, generation=$generation, id=${baseSong.id}, " +
                            "preferred=$preferredSource, first=$firstSource, second=$secondSource, " +
                            "pronunciationAllowed=$completeOnlinePronunciation, " +
                            "targetContent=$totalLineCount"
                    )
                    val firstCandidate = fetchOnlineTranslationCandidate(
                        application = application,
                        baseSong = baseSong,
                        source = firstSource,
                        totalLineCount = totalLineCount,
                        generation = generation,
                        searchDurationMs = searchDuration.durationMs,
                    )
                    val secondCandidate = if (
                        temporaryTranslationSource == secondSource ||
                        temporaryPronunciationSource == secondSource ||
                        OnlineTranslationSelector.shouldTryAlternative(
                            firstCandidate,
                            totalLineCount
                        )
                    ) {
                        diagnostic(
                            "Apple Music 在线翻译继续比较另一来源: " +
                                "title=${baseSong.name}, source=$secondSource"
                        )
                        fetchOnlineTranslationCandidate(
                            application = application,
                            baseSong = baseSong,
                            source = secondSource,
                            totalLineCount = totalLineCount,
                            generation = generation,
                            searchDurationMs = searchDuration.durationMs,
                        )
                    } else {
                        null
                    }
                    val candidates = listOfNotNull(firstCandidate, secondCandidate)
                        .associateBy(OnlineTranslationSelector.Candidate::source)
                    val preferredCandidate = candidates[preferredSource]
                    val alternativeCandidate = candidates[alternativeSource]
                    val selected = OnlineTranslationSelector.select(
                        preferred = preferredCandidate,
                        alternative = alternativeCandidate,
                        totalLineCount = totalLineCount
                    )
                    val supplemental = when {
                        selected === preferredCandidate -> alternativeCandidate
                        selected === alternativeCandidate -> preferredCandidate
                        else -> null
                    }
                    val defaultTranslationCandidate = listOfNotNull(selected, supplemental)
                        .firstOrNull {
                            OnlineTranslationMatcher.contributesTranslation(baseSong, it.result)
                        }
                    val defaultPronunciationCandidate = listOfNotNull(selected, supplemental)
                        .firstOrNull {
                            OnlineTranslationMatcher.contributesPronunciation(baseSong, it.result)
                        }
                    val selection = OnlineTranslationSelection(
                        onlineLinesBySource = candidates.mapValues { it.value.onlineLines },
                        defaultTranslationSource = defaultTranslationCandidate?.source,
                        defaultPronunciationSource = defaultPronunciationCandidate?.source,
                        forcedTranslationSource = temporaryTranslationSource,
                        forcedPronunciationSource = temporaryPronunciationSource,
                    )
                    val currentPublishedSong = currentPublishedAppleSong
                        ?.takeIf { isSameTrack(it, baseSong) }
                        ?.let { publishedSong ->
                            ApplePronunciationVisibilityPolicy.filterSong(
                                song = publishedSong,
                                hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                            )
                        }
                    val mergedResult = OnlineTranslationMatcher.composeSelectedSources(
                        baseSong = baseSong,
                        candidates = candidates.mapValues { it.value.result },
                        defaultTranslationSource = defaultTranslationCandidate?.source,
                        defaultPronunciationSource = defaultPronunciationCandidate?.source,
                        forcedTranslationSource = temporaryTranslationSource,
                        forcedPronunciationSource = temporaryPronunciationSource,
                        currentPublishedSong = currentPublishedSong,
                    )
                    val selectedTranslationSource = mergedResult
                        ?.song
                        ?.metadata
                        ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
                    val selectedPronunciationSource = mergedResult
                        ?.song
                        ?.metadata
                        ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
                    diagnostic(
                        "Apple Music 在线翻译来源选择: title=${baseSong.name}, " +
                        "preferred=$preferredSource, selected=${selected?.source}, " +
                        "translation=$selectedTranslationSource, " +
                        "pronunciation=$selectedPronunciationSource, " +
                        "compared=${alternativeCandidate != null}"
                    )
                    HookLogger.i(
                        TAG,
                        "Apple Music 在线翻译来源选择: title=${baseSong.name}, " +
                            "selected=${selected?.source}, " +
                            "translation=$selectedTranslationSource, " +
                            "pronunciation=$selectedPronunciationSource, " +
                            "compared=${alternativeCandidate != null}"
                    )
                    val selectedMatchedCount = selected?.result?.matchedCount ?: 0
                    if (mergedResult != null && mergedResult.matchedCount > selectedMatchedCount) {
                        diagnostic(
                            "Apple Music 在线翻译已由备用源补齐: title=${baseSong.name}, " +
                                "source=${supplemental?.source}, " +
                                "matched=$selectedMatchedCount->${mergedResult.matchedCount}"
                        )
                    }
                    pronunciationDiagnostic(
                        "stage=selection_composed, generation=$generation, id=${baseSong.id}, " +
                            "candidateSources=${candidates.keys.joinToString("+")}, " +
                            "selected=${selected?.source}, translationSource=$selectedTranslationSource, " +
                            "pronunciationSource=$selectedPronunciationSource, " +
                            "resultPresent=${mergedResult != null}, " +
                            "resultRomanized=${mergedResult?.song?.lyrics.orEmpty().count { !it.roma.isNullOrBlank() }}"
                    )
                    mainHandler.post {
                        applyOnlineTranslationResult(generation, baseSong, selection)
                    }
                }
            } catch (e: CancellationException) {
                pronunciationDiagnostic(
                    "stage=request_cancelled, generation=$generation, id=${baseSong.id}, " +
                        "currentGeneration=$onlineTranslationGeneration"
                )
                throw e
            } catch (e: Exception) {
                pronunciationDiagnostic(
                    "stage=request_failed, generation=$generation, id=${baseSong.id}, " +
                        "error=${e.javaClass.name}, message=${e.message}"
                )
                mainHandler.post {
                    applyOnlineTranslationResult(generation, baseSong, null)
                }
                debugError("Apple Music 在线翻译匹配失败: title=${baseSong.name}", e)
            }
        }
        return true
    }

    private suspend fun fetchOnlineTranslationCandidate(
        application: Application,
        baseSong: LocalSong,
        source: Source,
        totalLineCount: Int,
        generation: Int,
        searchDurationMs: Long,
    ): OnlineTranslationSelector.Candidate? {
        pronunciationDiagnostic(
            "stage=candidate_fetch_started, generation=$generation, id=${baseSong.id}, source=$source"
        )
        val onlineLines = OnlineLyricTargeter.fetchBestLyric(
            context = application,
            pkgName = APPLE_MUSIC_PACKAGE,
            title = baseSong.name.orEmpty(),
            artist = baseSong.artist.orEmpty(),
            durationMs = searchDurationMs,
            originalTitle = baseSong.metadata
                ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
            originalArtist = baseSong.metadata
                ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST),
            preferOriginalMetadata = shouldPreferAppleOriginalMetadata(),
            preferredSource = source,
            requireTranslation = false,
            fallbackToOtherSources = false
        ) ?: run {
            pronunciationDiagnostic(
                "stage=candidate_fetch_finished, generation=$generation, id=${baseSong.id}, " +
                    "source=$source, found=false"
            )
            diagnostic(
                "Apple Music 在线翻译候选未命中: title=${baseSong.name}, source=$source"
            )
            return null
        }
        val filteredOnlineLines = ApplePronunciationVisibilityPolicy.filterOnlineLines(
            song = baseSong,
            onlineLines = onlineLines,
            hideMandarinPinyin = isHideMandarinPinyinEnabled(),
        )
        val result = OnlineTranslationMatcher.apply(baseSong, filteredOnlineLines)
        val baseLines = baseSong.lyrics.orEmpty()
        val enrichedLines = result.song.lyrics.orEmpty()
        val matchedTranslationCount = baseLines.indices.count { index ->
            !OnlineTranslationContentPolicy.isMeaningful(baseLines[index].translation) &&
                OnlineTranslationContentPolicy.isMeaningful(
                    enrichedLines.getOrNull(index)?.translation
                )
        }
        val matchedPronunciationCount = baseLines.indices.count { index ->
            baseLines[index].roma.isNullOrBlank() &&
                !enrichedLines.getOrNull(index)?.roma.isNullOrBlank()
        }
        val candidate = OnlineTranslationSelector.Candidate(
            source = source,
            onlineLineCount = onlineLines.size,
            translatedLineCount = onlineLines.count {
                OnlineTranslationContentPolicy.isMeaningful(it.translation)
            },
            result = result,
            romanizedLineCount = filteredOnlineLines.count {
                !it.romanization.isNullOrBlank()
            },
            matchedContentCount = matchedTranslationCount + matchedPronunciationCount,
            onlineLines = filteredOnlineLines,
        )
        pronunciationDiagnostic(
            "stage=candidate_fetch_finished, generation=$generation, id=${baseSong.id}, " +
                "source=$source, found=true, rawLines=${onlineLines.size}, " +
                "rawRomanized=${onlineLines.count { !it.romanization.isNullOrBlank() }}, " +
                "filteredLines=${filteredOnlineLines.size}, " +
                "filteredRomanized=${candidate.romanizedLineCount}"
        )
        pronunciationDiagnostic(
            "stage=matcher_finished, generation=$generation, id=${baseSong.id}, source=$source, " +
                "matchedLines=${result.matchedCount}, matchedTranslation=$matchedTranslationCount, " +
                "matchedPronunciation=$matchedPronunciationCount, " +
                "resultRomanized=${enrichedLines.count { !it.roma.isNullOrBlank() }}"
        )
        diagnostic(
            "Apple Music 在线翻译候选: title=${baseSong.name}, source=$source, " +
                "lines=${candidate.onlineLineCount}, " +
                "translated=${candidate.translatedLineCount}, " +
                "romanized=${candidate.romanizedLineCount}, " +
                "matchedLines=${result.matchedCount}, " +
                "matchedContent=${candidate.matchedContentCount}/$totalLineCount, " +
                "coverage=${formatMetric(OnlineTranslationSelector.coverage(candidate, totalLineCount))}, " +
                "confidence=${formatMetric(result.averageMatchScore)}, " +
                "quality=${formatMetric(OnlineTranslationSelector.quality(candidate, totalLineCount))}"
        )
        return candidate
    }

    private fun formatMetric(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    private fun applyOnlineTranslationResult(
        generation: Int,
        baseSong: LocalSong,
        selection: OnlineTranslationSelection?
    ) {
        val nativeSong = currentAppleSong
        val generationMatches = generation == onlineTranslationGeneration
        val sameTrack = nativeSong != null && isSameTrack(nativeSong, baseSong)
        val nativeLyricsAvailable = currentAppleHasNativeLyrics
        val enrichmentNeeded = needsOnlineEnrichment(nativeSong)
        val matchingEnabled = isOnlineTranslationMatchEnabled()
        val requestStillCurrent = generationMatches && nativeSong != null && sameTrack &&
            nativeLyricsAvailable && enrichmentNeeded && matchingEnabled
        pronunciationDiagnostic(
            "stage=apply_guard, generation=$generation, id=${baseSong.id}, " +
                "accepted=$requestStillCurrent, currentGeneration=$onlineTranslationGeneration, " +
                "generationMatches=$generationMatches, sameTrack=$sameTrack, " +
                "nativeLyrics=$nativeLyricsAvailable, enrichmentNeeded=$enrichmentNeeded, " +
                "matchingEnabled=$matchingEnabled, resultPresent=${selection != null}, " +
                "candidateSources=${selection?.onlineLinesBySource?.keys?.joinToString("+").orEmpty()}"
        )
        if (!requestStillCurrent) {
            diagnostic(
                "Apple Music 在线翻译匹配结果已过期: title=${baseSong.name}, " +
                    "generation=$generation, currentGeneration=$onlineTranslationGeneration"
            )
            return
        }

        onlineTranslationJob = null
        val latestNativeSong = nativeSong
        val currentPublishedSong = currentPublishedAppleSong
            ?.takeIf { isSameTrack(it, latestNativeSong) }
            ?.let { publishedSong ->
                ApplePronunciationVisibilityPolicy.filterSong(
                    song = publishedSong,
                    hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                )
            }
        val nativeLyricsChangedDuringRequest = baseSong.lyrics != latestNativeSong.lyrics
        val mergedResult = (selection ?: OnlineTranslationSelection()).compose(
            latestNativeSong = latestNativeSong,
            currentPublishedSong = currentPublishedSong,
        )
        pronunciationDiagnostic(
            "stage=result_rebased, generation=$generation, id=${baseSong.id}, " +
                "nativeLyricsChanged=$nativeLyricsChangedDuringRequest, " +
                "requestLines=${baseSong.lyrics.orEmpty().size}, " +
                "latestLines=${latestNativeSong.lyrics.orEmpty().size}, " +
                "candidateSources=${selection?.onlineLinesBySource?.keys?.joinToString("+").orEmpty()}"
        )
        val hasPendingSourceSwitch = pendingTranslationSourceRequest
            ?.takeIf { it.songId == baseSong.id } != null ||
            pendingPronunciationSourceRequest
                ?.takeIf { it.songId == baseSong.id } != null
        val hasOnlineEnrichment = mergedResult != null &&
            (
                OnlineTranslationMatcher.contributesTranslation(latestNativeSong, mergedResult) ||
                    OnlineTranslationMatcher.contributesPronunciation(
                        latestNativeSong,
                        mergedResult,
                    )
                )
        val mergedRomanizedLines = mergedResult?.song?.lyrics.orEmpty().count {
            !it.roma.isNullOrBlank()
        }
        pronunciationDiagnostic(
            "stage=apply_decision, generation=$generation, id=${baseSong.id}, " +
                "mergedPresent=${mergedResult != null}, mergedRomanized=$mergedRomanizedLines, " +
                "hasOnlineEnrichment=$hasOnlineEnrichment, sourceSwitch=$hasPendingSourceSwitch"
        )
        if (mergedResult == null || (!hasOnlineEnrichment && !hasPendingSourceSwitch)) {
            diagnostic("Apple Music 在线翻译匹配未命中: title=${baseSong.name}")
            HookLogger.i(TAG, "Apple Music 在线翻译匹配未命中: title=${baseSong.name}")
            if (requestOriginalMetadata(baseSong, "translation_match_miss")) return
            completePendingOnlineSourceSwitchRequests(null)
            if (!onlineMatchedTranslationActive) {
                if (
                    !currentPublishedAppleOnlineTranslationMatched &&
                    currentPublishedAppleSong != latestNativeSong
                ) {
                    publishAppleSong(latestNativeSong, restorePosition = true)
                }
                sink?.onOnlineTranslationUnavailable(nativeSong)
            }
            return
        }

        onlineMatchedTranslationActive = hasOnlineEnrichment
        diagnostic(
            "Apple Music 在线翻译结果接受: title=${baseSong.name}, " +
                "matched=${mergedResult.matchedCount}, enriched=$hasOnlineEnrichment, " +
                "sourceSwitch=$hasPendingSourceSwitch, total=${baseSong.lyrics.orEmpty().size}"
        )
        HookLogger.i(
            TAG,
            "Apple Music 在线翻译结果接受: title=${baseSong.name}, " +
                "matched=${mergedResult.matchedCount}, enriched=$hasOnlineEnrichment, " +
                "sourceSwitch=$hasPendingSourceSwitch, total=${baseSong.lyrics.orEmpty().size}"
        )
        val unmatched = mergedResult.song.lyrics.orEmpty().mapIndexedNotNull { index, line ->
            val missing = buildList {
                if (!OnlineTranslationContentPolicy.isMeaningful(line.translation)) {
                    add("translation")
                }
                if (line.roma.isNullOrBlank()) add("pronunciation")
            }
            missing.takeIf { it.isNotEmpty() }?.let {
                "$index@${line.begin}[${it.joinToString("+")}]:${line.text.orEmpty().take(48)}"
            }
        }
        if (unmatched.isNotEmpty()) {
            diagnostic(
                "Apple Music 在线翻译未匹配行: title=${baseSong.name}, " +
                    unmatched.joinToString(separator = " | ")
                )
        }
        val nativePublicationEnabled = isNativeOnlineTranslationEnabled()
        pronunciationDiagnostic(
            "stage=publish_attempt, generation=$generation, id=${baseSong.id}, " +
                "enabled=$nativePublicationEnabled, enriched=$hasOnlineEnrichment, " +
                "romanizedLines=$mergedRomanizedLines, bridgePresent=${directBridge != null}"
        )
        if (nativePublicationEnabled && hasOnlineEnrichment) {
            val published = directBridge?.publishOnlineTranslation(
                song = mergedResult.song,
                generation = generation,
            ) == true
            pronunciationDiagnostic(
                "stage=publish_call_result, generation=$generation, id=${baseSong.id}, " +
                    "success=$published"
            )
        }
        completePendingOnlineSourceSwitchRequests(mergedResult.song)
        publishAppleSong(
            mergedResult.song,
            restorePosition = true,
            onlineTranslationMatched = hasOnlineEnrichment
        )
    }

    private fun requestOriginalMetadata(baseSong: LocalSong, reason: String): Boolean {
        val mediaId = baseSong.id?.takeIf { it.all(Char::isDigit) } ?: return false
        val hasOriginalMetadata = !baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE)
            .isNullOrBlank() || !baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST)
            .isNullOrBlank()
        val originalMetadataResolved = baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_METADATA_RESOLVED)
            .toBoolean()
        if (
            hasOriginalMetadata ||
            originalMetadataResolved ||
            originalMetadataRequestKey == mediaId
        ) return false
        val application = app ?: return false
        originalMetadataRequestKey = mediaId
        application.sendBroadcast(
            Intent(AppleDirectBridgeContract.ACTION_RESOLVE_ORIGINAL_METADATA)
                .setPackage(APPLE_MUSIC_PACKAGE)
                .putExtra(AppleDirectBridgeContract.EXTRA_MEDIA_ID, mediaId)
        )
        HookLogger.i(
            TAG,
            "Apple Music 三方检索未命中，请求多地区原名: " +
                "id=$mediaId, title=${baseSong.name}, reason=$reason"
        )
        return true
    }

    private fun shouldRequestOriginalMetadataForOnlineLookup(song: LocalSong): Boolean {
        if (!shouldPreferAppleOriginalMetadata()) return false
        if (
            song.metadata
                ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_METADATA_RESOLVED)
                .toBoolean()
        ) return false
        return AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
            mediaId = song.id,
            title = song.name,
            artist = song.artist,
            genre = song.metadata?.getString(LyricMetadataKeys.APPLE_CATALOG_GENRE),
        )
    }

    private fun cancelOnlineTranslation(
        clearAttempt: Boolean,
        clearMatched: Boolean,
        reason: String
    ) {
        if (onlineTranslationJob?.isActive == true || onlineMatchedTranslationActive) {
            diagnostic(
                "Apple Music 在线翻译匹配取消: reason=$reason, " +
                    "title=${currentAppleSong?.name}"
            )
        }
        onlineTranslationGeneration += 1
        onlineTranslationJob?.cancel()
        onlineTranslationJob = null
        if (clearAttempt) onlineTranslationAttemptKey = null
        if (clearMatched) {
            directBridge?.clearOnlineTranslation(currentAppleSong?.id)
            onlineMatchedTranslationActive = false
        }
    }

    private fun startAppleMediaMonitor() {
        lastObservedMediaKey = null
        mainHandler.removeCallbacks(appleMediaMonitor)
        mainHandler.post(appleMediaMonitor)
    }

    private fun stopAppleMediaMonitor() {
        mainHandler.removeCallbacks(appleMediaMonitor)
        lastObservedMediaKey = null
    }

    private fun observeAppleMediaSession(force: Boolean = false) {
        val application = app ?: return
        if (hasNonAppleCentralPlayer()) return
        val media = MediaMetadataHelper.getMediaInfo(application, APPLE_MUSIC_PACKAGE, HookLogger)
        if (media.title.isBlank()) return
        updateAppleMediaPositionReference(media)
        if (!isFallbackEnabled()) return

        val mediaSong = LocalSong(
            name = media.title,
            artist = media.artist,
            duration = media.duration.coerceAtLeast(0L),
            lyrics = emptyList()
        )
        val mediaKey = songIdentity(mediaSong)
        if (!force && mediaKey == lastObservedMediaKey) return
        lastObservedMediaKey = mediaKey

        val nativeSong = currentAppleSong
        if (
            AppleSongUpdatePolicy.canStartFallbackFromMediaSession(
                currentSong = nativeSong,
                mediaSessionSong = mediaSong,
                currentHasNativeLyrics = currentAppleHasNativeLyrics
            )
        ) {
            val fallbackPending = fallbackDelayRunnable != null || fallbackJob?.isActive == true
            if (!fallbackPending && !fallbackSongActive) {
                diagnostic(
                    "Apple Music Provider 已确认无歌词，媒体会话补充触发在线兜底: title=${media.title}, " +
                        "artist=${media.artist}, duration=${media.duration}"
                )
                scheduleFallback(nativeSong ?: return, APPLE_LYRICS_GRACE_MS)
            }
            return
        }

        diagnostic(
            "Apple Music 媒体会话只用于观察，等待原生歌词通道确认: " +
                "title=${media.title}, artist=${media.artist}, duration=${media.duration}"
        )
    }

    private fun refreshAppleMediaPositionReference() {
        val application = app ?: return
        val media = MediaMetadataHelper.getMediaInfo(application, APPLE_MUSIC_PACKAGE, HookLogger)
        if (media.title.isBlank()) return
        updateAppleMediaPositionReference(media)
    }

    private fun updateAppleMediaPositionReference(media: MediaMetadataHelper.MediaInfo) {
        val application = app ?: return
        val currentSong = currentAppleSong ?: return
        val previous = appleMediaPositionReference
        val matchesCurrentSong = AppleCentralPositionPolicy.matchesTrack(
            firstTitle = currentSong.name,
            firstArtist = currentSong.artist,
            firstDuration = currentSong.duration,
            secondTitle = media.title,
            secondArtist = media.artist,
            secondDuration = media.duration,
        )
        val continuesBoundMediaIdentity = previous?.songGeneration == appleSongGeneration &&
            AppleCentralPositionPolicy.matchesTrack(
                firstTitle = previous.title,
                firstArtist = previous.artist,
                firstDuration = previous.duration,
                secondTitle = media.title,
                secondArtist = media.artist,
                secondDuration = media.duration,
            )
        if (!matchesCurrentSong && !continuesBoundMediaIdentity) return

        val progress = MediaMetadataHelper.getPlaybackProgress(application, APPLE_MUSIC_PACKAGE)
        if (progress.position < 0L) return
        appleMediaPositionReference = AppleCentralPositionPolicy.MediaReference(
            songGeneration = appleSongGeneration,
            title = media.title,
            artist = media.artist,
            duration = media.duration.takeIf { it > 0L } ?: progress.duration,
            position = progress.position,
            isPlaying = progress.isPlaying,
            playbackSpeed = progress.playbackSpeed,
            observedAtMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun resolveApplePosition(
        adjustedPosition: Long,
        explicitSeek: Boolean,
    ): AppleCentralPositionPolicy.Resolution = AppleCentralPositionPolicy.resolve(
        centralPosition = adjustedPosition,
        currentSongDuration = currentPublishedAppleSong?.duration
            ?.takeIf { it > 0L }
            ?: currentAppleSong?.duration
            ?: 0L,
        currentSongGeneration = appleSongGeneration,
        mediaReference = appleMediaPositionReference,
        directReference = appleDirectPositionReference.takeIf {
            !hasActiveCentralPlayer() || isBuiltInAppleCentralProviderActive()
        },
        providerDelayMs = activeProviderDelayMs,
        nowMs = SystemClock.elapsedRealtime(),
        explicitSeek = explicitSeek,
    )

    private fun startMediaPositionPolling() {
        if (mediaPositionJob?.isActive == true) return
        val application = app ?: return
        lastMediaPlaybackState = null
        mediaPositionJob = mediaPositionScope.launch {
            while (isActive && fallbackSongActive) {
                val progress = MediaMetadataHelper.getPlaybackProgress(
                    application,
                    APPLE_MUSIC_PACKAGE
                )
                if (progress.position >= 0L) {
                    lastAdjustedPosition = progress.position
                    sink?.onPositionChanged(progress.position)
                }
                if (lastMediaPlaybackState != progress.isPlaying) {
                    lastMediaPlaybackState = progress.isPlaying
                    sink?.onPlaybackStateChanged(progress.isPlaying)
                }
                delay(33L)
            }
        }
    }

    private fun stopMediaPositionPolling() {
        mediaPositionJob?.cancel()
        mediaPositionJob = null
        lastMediaPlaybackState = null
    }

    private fun isFallbackEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_ONLINE_FALLBACK,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ONLINE_FALLBACK
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ONLINE_FALLBACK

    private fun isQqFirst(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_FALLBACK_QQ_FIRST,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FALLBACK_QQ_FIRST
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FALLBACK_QQ_FIRST

    private fun isTranslationQqFirst(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_TRANSLATION_QQ_FIRST,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_TRANSLATION_QQ_FIRST
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_TRANSLATION_QQ_FIRST

    private fun isOnlineTranslationMatchEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION

    private fun isNativeOnlineTranslationEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION

    private fun isHideMandarinPinyinEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN

    private fun shouldPreferAppleOriginalMetadata(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA

    private fun isSimplifyTraditionalLyricsEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS

    private fun hasTranslation(song: LocalSong?): Boolean = song?.lyrics?.any {
        OnlineTranslationContentPolicy.isMeaningful(it.translation)
    } == true

    private fun needsOnlineEnrichment(song: LocalSong?): Boolean {
        song ?: return false
        val completeOnlinePronunciation =
            ApplePronunciationVisibilityPolicy.allowsOnlineSupplementation(
                song = song,
                hideMandarinPinyin = isHideMandarinPinyinEnabled(),
            )
        return song.lyrics?.any {
            !it.text.isNullOrBlank() &&
                (
                    !OnlineTranslationContentPolicy.isMeaningful(it.translation) ||
                        (completeOnlinePronunciation && it.roma.isNullOrBlank())
                    )
        } == true
    }

    private fun translationIdentity(song: LocalSong): String =
        AppleOnlineTranslationRequestPolicy.attemptKey(song)

    private fun hasActiveCentralPlayer(): Boolean = activeCentralPlayerPackageName != null

    private fun hasNonAppleCentralPlayer(): Boolean {
        val packageName = activeCentralPlayerPackageName
        return packageName != null && packageName != APPLE_MUSIC_PACKAGE
    }

    private fun isSameTrack(first: LocalSong, second: LocalSong): Boolean {
        val firstId = first.id?.trim().orEmpty()
        val secondId = second.id?.trim().orEmpty()
        if (firstId.isNotEmpty() && secondId.isNotEmpty() && firstId == secondId) return true

        if (normalizeIdentity(first.name) != normalizeIdentity(second.name)) return false
        if (normalizeIdentity(first.artist) != normalizeIdentity(second.artist)) return false
        return first.duration <= 0L || second.duration <= 0L ||
            abs(first.duration - second.duration) <= SAME_TRACK_DURATION_TOLERANCE_MS
    }

    private fun songIdentity(song: LocalSong): String = listOf(
        normalizeIdentity(song.name),
        normalizeIdentity(song.artist),
        song.duration.toString()
    ).joinToString("|")

    private fun normalizeIdentity(value: String?): String = value.orEmpty().trim().lowercase()

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) HookLogger.d(TAG, message)
    }

    private fun diagnostic(message: String) {
        if (BuildConfig.DEBUG) HookLogger.w(TAG, "[debug] $message")
    }

    private fun pronunciationDiagnostic(message: String) {
        if (BuildConfig.DEBUG) Log.i(PRONUNCIATION_DIAGNOSTIC_TAG, message)
    }

    private fun debugError(message: String, error: Throwable) {
        if (BuildConfig.DEBUG) HookLogger.e(TAG, message, error)
    }


    private fun initializeSubscriber(app: Application) {
        val sub = LyriconFactory.createSubscriber(app)
        subscriber = sub

        sub.addConnectionListener(connectionListener)
        sub.subscribeActivePlayer(activePlayerListener)
        sub.register()
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnected(subscriber: LyriconSubscriber) {
                HookLogger.i(TAG, "订阅连接已建立")
                mainHandler.post { onCentralConnected?.invoke() }
        }

        override fun onReconnected(subscriber: LyriconSubscriber) {
                HookLogger.i(TAG, "订阅连接已恢复")
                mainHandler.post { onCentralConnected?.invoke() }
        }

        override fun onDisconnected(subscriber: LyriconSubscriber) {
                centralAppleProviderActive = false
                activeCentralPlayerPackageName = null
                activeProviderPackageName = null
                HookLogger.w(TAG, "订阅连接已断开")
        }

        override fun onConnectTimeout(subscriber: LyriconSubscriber) {
                centralAppleProviderActive = false
                activeCentralPlayerPackageName = null
                activeProviderPackageName = null
                HookLogger.w(TAG, "订阅连接超时")
                mainHandler.post {
                    onCentralConnectTimeout?.invoke()
                    subscriber.register()
                }
        }
    }

    private val activePlayerListener = object : ActivePlayerListener {
        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            val playerPackageName = providerInfo?.playerPackageName
            activeCentralPlayerPackageName = playerPackageName
            if (playerPackageName == null && currentAppleSong != null) {
                centralAppleProviderActive = false
                diagnostic(
                    "忽略 Central 空提供者状态: directTitle=${currentAppleSong?.name}"
                )
                return
            }

            cancelFallback(clearAppleSong = true, reason = "central_provider_changed")
            cancelOnlineTranslation(
                clearAttempt = true,
                clearMatched = true,
                reason = "central_provider_changed"
            )
            lastAdjustedPosition = 0L
            sink?.onStop()
            centralAppleProviderActive =
                playerPackageName == APPLE_MUSIC_PACKAGE
            if (!centralAppleProviderActive) {
                currentPublishedAppleSong = null
                currentPublishedAppleOnlineTranslationMatched = false
            }
            activeProviderPackageName = providerInfo?.providerPackageName
            activeProviderDelayMs = providerInfo?.providerPackageName
                ?.let(::readProviderDelay)
                ?: RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY
            LyriconDataBridge.updateLyricPackage(playerPackageName)
        }


        override fun onSongChanged(song: LyriconSong?) {
            val localSong = song?.toLocalSong()
            if (centralAppleProviderActive) {
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
                publishSong(localSong, restorePosition = false)
            }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            if (!hasActiveCentralPlayer()) return
            if (centralAppleProviderActive && fallbackSongActive) return
            sink?.onPlaybackStateChanged(isPlaying)
        }

        override fun onPositionChanged(position: Long) {
            if (!hasActiveCentralPlayer()) {
                logCentralPositionDiagnostic(position, null, "dropped_no_active_player")
                return
            }
            if (centralAppleProviderActive && fallbackSongActive) {
                logCentralPositionDiagnostic(position, null, "dropped_apple_fallback_active")
                return
            }
            val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
            if (centralAppleProviderActive) {
                val resolution = resolveApplePosition(adjustedPosition, explicitSeek = false)
                lastAdjustedPosition = AppleCentralPositionPolicy.restorablePosition(
                    previousPosition = lastAdjustedPosition,
                    resolution = resolution,
                )
                logAppleTimingDiagnostic(
                    path = "central",
                    rawPosition = position,
                    adjustedPosition = adjustedPosition,
                    resolution = resolution,
                )
                val resolvedPosition = resolution.position
                if (resolvedPosition == null) {
                    logCentralPositionDiagnostic(position, null, "dropped_apple_resolution")
                    return
                }
                sink?.onPositionChanged(resolvedPosition)
                logCentralPositionDiagnostic(position, resolvedPosition, "forwarded_apple")
                return
            }
            sink?.onPositionChanged(adjustedPosition)
            logCentralPositionDiagnostic(position, adjustedPosition, "forwarded_non_apple")
        }


        override fun onSeekTo(position: Long) {
            if (!hasActiveCentralPlayer()) return
            if (centralAppleProviderActive && fallbackSongActive) return
            val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
            if (centralAppleProviderActive) {
                val resolution = resolveApplePosition(adjustedPosition, explicitSeek = true)
                lastAdjustedPosition = AppleCentralPositionPolicy.restorablePosition(
                    previousPosition = lastAdjustedPosition,
                    resolution = resolution,
                )
                logAppleTimingDiagnostic(
                    path = "central_seek",
                    rawPosition = position,
                    adjustedPosition = adjustedPosition,
                    resolution = resolution,
                    force = true,
                )
                val resolvedPosition = resolution.position ?: return
                sink?.onSeekTo(resolvedPosition)
                return
            }
            sink?.onSeekTo(adjustedPosition)
        }

        override fun onReceiveText(text: String?) {
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

    internal fun onDirectSongChanged(song: LyriconSong?) {
        val localSong = song?.toLocalSong()
        currentDirectAppleSongId = localSong?.id
        appleDirectPositionReference = null
        if (hasActiveCentralPlayer()) return
        val providerPackage = BUILT_IN_PROVIDER_PACKAGE
        activeProviderPackageName = providerPackage
        activeProviderDelayMs = readProviderDelay(providerPackage)
        LyriconDataBridge.updateLyricPackage(APPLE_MUSIC_PACKAGE)
        handleAppleSong(localSong)
    }

    internal fun onDirectPlaybackStateChanged(isPlaying: Boolean) {
        if (!hasActiveCentralPlayer() && !fallbackSongActive) {
            sink?.onPlaybackStateChanged(isPlaying)
        }
    }

    internal fun onDirectPositionChanged(position: Long) {
        if (fallbackSongActive) return
        if (hasActiveCentralPlayer() && !centralAppleProviderActive) return
        if (centralAppleProviderActive && !isBuiltInAppleCentralProviderActive()) return
        if (centralAppleProviderActive && !directSongMatchesCurrentAppleSong()) return
        val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
        appleDirectPositionReference = AppleCentralPositionPolicy.DirectReference(
            songGeneration = appleSongGeneration,
            position = adjustedPosition,
            observedAtMs = SystemClock.elapsedRealtime(),
        )
        val resolution = resolveApplePosition(adjustedPosition, explicitSeek = false)
        lastAdjustedPosition = AppleCentralPositionPolicy.restorablePosition(
            previousPosition = lastAdjustedPosition,
            resolution = resolution,
        )
        logAppleTimingDiagnostic(
            if (centralAppleProviderActive) "direct_primary" else "direct",
            position,
            adjustedPosition,
            resolution = resolution,
        )
        resolution.position?.let { sink?.onPositionChanged(it) }
    }

    internal fun onDirectSeekTo(position: Long) {
        if (fallbackSongActive) return
        if (hasActiveCentralPlayer() && !centralAppleProviderActive) return
        if (centralAppleProviderActive && !isBuiltInAppleCentralProviderActive()) return
        if (centralAppleProviderActive && !directSongMatchesCurrentAppleSong()) return
        val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
        appleDirectPositionReference = AppleCentralPositionPolicy.DirectReference(
            songGeneration = appleSongGeneration,
            position = adjustedPosition,
            observedAtMs = SystemClock.elapsedRealtime(),
        )
        val resolution = resolveApplePosition(adjustedPosition, explicitSeek = true)
        lastAdjustedPosition = AppleCentralPositionPolicy.restorablePosition(
            previousPosition = lastAdjustedPosition,
            resolution = resolution,
        )
        logAppleTimingDiagnostic(
            "direct_seek",
            position,
            adjustedPosition,
            resolution = resolution,
            force = true,
        )
        resolution.position?.let { sink?.onSeekTo(it) }
    }

    private fun directSongMatchesCurrentAppleSong(): Boolean {
        val directSongId = currentDirectAppleSongId ?: return false
        val currentSongId = currentAppleSong?.id ?: return false
        return directSongId == currentSongId
    }

    private fun isBuiltInAppleCentralProviderActive(): Boolean =
        centralAppleProviderActive && activeProviderPackageName == BUILT_IN_PROVIDER_PACKAGE

    private fun logAppleTimingDiagnostic(
        path: String,
        rawPosition: Long,
        adjustedPosition: Long,
        resolution: AppleCentralPositionPolicy.Resolution? = null,
        force: Boolean = false,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val state = listOf(
            path,
            currentAppleSong?.id,
            currentPublishedAppleSong?.id,
            activeCentralPlayerPackageName,
            activeProviderPackageName,
            activeProviderDelayMs,
            fallbackSongActive,
            resolution?.reason,
        ).joinToString("|")
        if (
            !force &&
            state == lastTimingDiagnosticState &&
            now - lastTimingDiagnosticAtMs < TIMING_DIAGNOSTIC_INTERVAL_MS
        ) return
        HookLogger.i(
            TAG,
            "[debug] Timing receive: path=$path, rawPosition=$rawPosition, " +
                "adjustedPosition=$adjustedPosition, " +
                "resolvedPosition=${resolution?.position ?: adjustedPosition}, " +
                "mediaPosition=${resolution?.mediaPosition}, " +
                "directPosition=${resolution?.directPosition}, decision=${resolution?.reason}, " +
                "positionDelta=${(adjustedPosition - lastTimingDiagnosticPosition).takeIf {
                    lastTimingDiagnosticPosition >= 0L
                }}, delayMs=$activeProviderDelayMs, currentSongId=${currentAppleSong?.id}, " +
                "publishedSongId=${currentPublishedAppleSong?.id}, " +
                "centralPlayer=$activeCentralPlayerPackageName, " +
                "provider=$activeProviderPackageName, fallback=$fallbackSongActive"
        )
        lastTimingDiagnosticAtMs = now
        lastTimingDiagnosticPosition = adjustedPosition
        lastTimingDiagnosticState = state
    }

    private fun logCentralPositionDiagnostic(
        rawPosition: Long,
        forwardedPosition: Long?,
        decision: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCentralPositionDiagnosticAtMs < TIMING_DIAGNOSTIC_INTERVAL_MS) return
        lastCentralPositionDiagnosticAtMs = now
        HookLogger.i(
            TAG,
            "[debug] [LyricPositionDiag] stage=subscriber_callback, decision=$decision, " +
                "rawPosition=$rawPosition, forwardedPosition=$forwardedPosition, " +
                "centralPlayer=$activeCentralPlayerPackageName, " +
                "provider=$activeProviderPackageName, delayMs=$activeProviderDelayMs, " +
                "appleCentral=$centralAppleProviderActive, fallback=$fallbackSongActive, " +
                "sinkAvailable=${sink != null}"
        )
    }

    internal fun onDirectText(text: String?) {
        if (!hasActiveCentralPlayer() && !fallbackSongActive) {
            sink?.onPlainText(simplifyAppleTextForDisplay(text))
        }
    }

    internal fun onDirectOnlineLyricContentSourceRequested(
        requestId: Long,
        songId: String?,
        contentType: String?,
        sourceName: String?,
    ) {
        val nativeSong = currentAppleSong
        val requestedSource = runCatching { Source.valueOf(sourceName.orEmpty()) }
            .getOrNull()
        if (
            nativeSong == null ||
            songId.isNullOrBlank() ||
            songId != nativeSong.id ||
            requestedSource == null ||
            contentType !in setOf("translation", "pronunciation")
        ) {
            diagnostic(
                "Apple Music 在线翻译来源切换拒绝: requestId=$requestId, " +
                    "songId=$songId, currentSongId=${nativeSong?.id}, " +
                    "contentType=$contentType, source=$sourceName"
            )
            directBridge?.publishOnlineTranslationSourceSwitchResult(
                requestId = requestId,
                songId = songId,
                contentType = contentType,
                requestedSource = sourceName,
                actualSource = null,
                successful = false,
            )
            return
        }
        val request = OnlineSourceSwitchRequest(
            requestId = requestId,
            songId = requireNotNull(songId),
            contentType = requireNotNull(contentType),
            requestedSource = requestedSource,
        )
        when (contentType) {
            "translation" -> {
                temporaryTranslationSource = requestedSource
                pendingTranslationSourceRequest = request
            }
            "pronunciation" -> {
                temporaryPronunciationSource = requestedSource
                pendingPronunciationSourceRequest = request
            }
        }
        diagnostic(
            "Apple Music 在线翻译来源切换接受: requestId=$requestId, " +
                "songId=$songId, contentType=$contentType, source=$requestedSource"
        )
        cancelOnlineTranslation(
            clearAttempt = true,
            clearMatched = false,
            reason = "temporary_" + contentType + "_source_switched",
        )
        if (!scheduleOnlineTranslation(nativeSong)) {
            failPendingOnlineSourceSwitchRequest(request)
        }
    }

    private fun completePendingOnlineSourceSwitchRequests(song: LocalSong?) {
        val translationSource = song
            ?.metadata
            ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
        val pronunciationSource = song
            ?.metadata
            ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
        listOfNotNull(
            pendingTranslationSourceRequest?.let { it to translationSource },
            pendingPronunciationSourceRequest?.let { it to pronunciationSource },
        ).forEach { (request, actualSource) ->
            publishOnlineSourceSwitchResult(request, actualSource)
        }
        pendingTranslationSourceRequest = null
        pendingPronunciationSourceRequest = null
    }

    private fun failPendingOnlineSourceSwitchRequest(request: OnlineSourceSwitchRequest) {
        when (request.contentType) {
            "translation" -> {
                if (pendingTranslationSourceRequest?.requestId == request.requestId) {
                    pendingTranslationSourceRequest = null
                }
            }
            "pronunciation" -> {
                if (pendingPronunciationSourceRequest?.requestId == request.requestId) {
                    pendingPronunciationSourceRequest = null
                }
            }
        }
        publishOnlineSourceSwitchResult(request, actualSource = null)
    }

    private fun publishOnlineSourceSwitchResult(
        request: OnlineSourceSwitchRequest,
        actualSource: Source?,
    ) {
        val successful = actualSource == request.requestedSource
        diagnostic(
            "Apple Music 在线翻译来源切换完成: requestId=${request.requestId}, " +
                "songId=${request.songId}, contentType=${request.contentType}, " +
                "requested=${request.requestedSource}, actual=${actualSource ?: "none"}, " +
                "successful=$successful"
        )
        directBridge?.publishOnlineTranslationSourceSwitchResult(
            requestId = request.requestId,
            songId = request.songId,
            contentType = request.contentType,
            requestedSource = request.requestedSource.name,
            actualSource = actualSource?.name,
            successful = successful,
        )
    }
}

private data class OnlineSourceSwitchRequest(
    val requestId: Long,
    val songId: String,
    val contentType: String,
    val requestedSource: Source,
)
