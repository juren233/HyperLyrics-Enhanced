package com.juren233.hyperlyricsenhanced.root.source

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSource
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
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
        private const val APPLE_LYRICS_GRACE_MS = 5_000L
        private const val APPLE_MEDIA_MONITOR_INTERVAL_MS = 1_000L
        private const val SAME_TRACK_DURATION_TOLERANCE_MS = 2_000L
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
    private var onlineMatchedTranslationActive = false
    private var currentAppleSong: LocalSong? = null
    private var currentAppleHasNativeLyrics = false
    @Volatile
    private var fallbackSongActive = false
    private var lastAdjustedPosition = 0L
    private var lastObservedMediaKey: String? = null
    private var lastMediaPlaybackState: Boolean? = null

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
            subscriber = null
            sink?.onStop()
            sink = null
        }
        HookLogger.i(TAG, "数据源已停止")
    }

    fun initialize(app: Application, prefs: android.content.SharedPreferences?) {
        this.app = app
        this.prefs = prefs

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
    }

    private fun applyFallbackPreferenceChange(key: String) {
        if (!isFallbackEnabled()) {
            val nativeSong = currentAppleSong
            val shouldRestoreNative = fallbackSongActive && nativeSong != null
            cancelFallback(clearAppleSong = false, reason = "fallback_disabled")
            if (shouldRestoreNative) {
                publishSong(nativeSong, restorePosition = true)
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
                publishSong(nativeSong, restorePosition = true)
            } else if (wasPending) {
                sink?.onOnlineTranslationUnavailable(nativeSong)
            }
            return
        }
        if (nativeSong.lyrics.isNullOrEmpty() || hasTranslation(nativeSong)) return

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
        val repeatedEmptySong = sameTrack && song.lyrics.isNullOrEmpty() &&
            (fallbackSongActive || fallbackDelayRunnable != null || fallbackJob?.isActive == true)
        if (repeatedEmptySong) {
            currentAppleSong = song
            currentAppleHasNativeLyrics = false
            debug("忽略同一首歌的重复空歌词占位: title=${song.name}")
            return
        }
        val repeatedNativeWithoutTranslation = sameTrack &&
            !song.lyrics.isNullOrEmpty() &&
            !hasTranslation(song) &&
            (onlineTranslationJob?.isActive == true || onlineMatchedTranslationActive)
        if (repeatedNativeWithoutTranslation) {
            currentAppleSong = song
            currentAppleHasNativeLyrics = true
            debug("忽略同一首歌的重复无翻译原生歌词: title=${song.name}")
            return
        }
        cancelFallback(clearAppleSong = false, reason = "apple_song_updated")
        val incomingHasTranslation = hasTranslation(song)
        cancelOnlineTranslation(
            clearAttempt = !sameTrack || incomingHasTranslation,
            clearMatched = true,
            reason = "apple_song_updated"
        )
        currentAppleSong = song
        currentAppleHasNativeLyrics = !song?.lyrics.isNullOrEmpty()
        if (!sameTrack) lastAdjustedPosition = 0L

        if (song != null && song.lyrics.isNullOrEmpty() && isFallbackEnabled()) {
            scheduleFallback(song, APPLE_LYRICS_GRACE_MS)
        }
        if (song != null && !song.lyrics.isNullOrEmpty() && !incomingHasTranslation &&
            isOnlineTranslationMatchEnabled()
        ) {
            scheduleOnlineTranslation(song)
        }
        runCatching {
            publishSong(song, restorePosition = sameTrack)
        }.onFailure {
            debugError("Apple Music 歌曲发布失败: title=${song?.name}", it)
        }
    }

    private fun publishSong(
        song: LocalSong?,
        restorePosition: Boolean,
        onlineTranslationMatched: Boolean = false
    ) {
        LyriconDataBridge.updateSong(song)
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
            return
        }
        fallbackSongActive = true
        MediaMetadataHelper.getPlaybackProgress(application, APPLE_MUSIC_PACKAGE)
            .position
            .takeIf { it >= 0L }
            ?.let { lastAdjustedPosition = it }
        diagnostic(
            "Apple Music 在线兜底命中: title=${baseSong.name}, " +
                "lines=${fallbackSong.lyrics.orEmpty().size}, " +
                "translations=${fallbackSong.lyrics.orEmpty().count { !it.translation.isNullOrBlank() }}"
        )
        val fallbackHasTranslation = hasTranslation(fallbackSong)
        publishSong(
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

    private fun scheduleOnlineTranslation(baseSong: LocalSong) {
        if (!isOnlineTranslationMatchEnabled() || baseSong.lyrics.isNullOrEmpty() ||
            hasTranslation(baseSong) || baseSong.name.isNullOrBlank()
        ) return
        val attemptKey = translationIdentity(baseSong)
        if (onlineTranslationAttemptKey == attemptKey) return

        onlineTranslationAttemptKey = attemptKey
        onlineTranslationGeneration += 1
        val generation = onlineTranslationGeneration
        onlineTranslationJob?.cancel()
        onlineTranslationJob = fallbackScope.launch {
            try {
                fallbackRequestMutex.withLock {
                    if (generation != onlineTranslationGeneration) return@withLock
                    val application = app ?: return@withLock
                    val preferredSource = if (isTranslationQqFirst()) Source.QM else Source.NE
                    val alternativeSource = if (preferredSource == Source.QM) Source.NE else Source.QM
                    val totalLineCount = baseSong.lyrics.orEmpty().count {
                        it.translation.isNullOrBlank() && !it.text.isNullOrBlank()
                    }
                    diagnostic(
                        "Apple Music 在线翻译匹配开始: title=${baseSong.name}, " +
                            "artist=${baseSong.artist}, source=$preferredSource"
                    )
                    val preferredCandidate = fetchOnlineTranslationCandidate(
                        application = application,
                        baseSong = baseSong,
                        source = preferredSource,
                        totalLineCount = totalLineCount
                    )
                    val alternativeCandidate = if (
                        OnlineTranslationSelector.shouldTryAlternative(
                            preferredCandidate,
                            totalLineCount
                        )
                    ) {
                        diagnostic(
                            "Apple Music 首选在线翻译质量不足，比较备用源: " +
                                "title=${baseSong.name}, source=$alternativeSource"
                        )
                        fetchOnlineTranslationCandidate(
                            application = application,
                            baseSong = baseSong,
                            source = alternativeSource,
                            totalLineCount = totalLineCount
                        )
                    } else {
                        null
                    }
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
                    val mergedResult = selected?.result?.let { primary ->
                        OnlineTranslationMatcher.fillMissing(primary, supplemental?.result)
                    }
                    diagnostic(
                        "Apple Music 在线翻译来源选择: title=${baseSong.name}, " +
                        "preferred=$preferredSource, selected=${selected?.source}, " +
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
                    mainHandler.post {
                        applyOnlineTranslationResult(generation, baseSong, mergedResult)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mainHandler.post {
                    applyOnlineTranslationResult(generation, baseSong, null)
                }
                debugError("Apple Music 在线翻译匹配失败: title=${baseSong.name}", e)
            }
        }
    }

    private suspend fun fetchOnlineTranslationCandidate(
        application: Application,
        baseSong: LocalSong,
        source: Source,
        totalLineCount: Int
    ): OnlineTranslationSelector.Candidate? {
        val onlineLines = OnlineLyricTargeter.fetchBestLyric(
            context = application,
            pkgName = APPLE_MUSIC_PACKAGE,
            title = baseSong.name.orEmpty(),
            artist = baseSong.artist.orEmpty(),
            durationMs = baseSong.duration,
            preferredSource = source,
            requireTranslation = true,
            fallbackToOtherSources = false
        ) ?: run {
            diagnostic(
                "Apple Music 在线翻译候选未命中: title=${baseSong.name}, source=$source"
            )
            return null
        }
        val result = OnlineTranslationMatcher.apply(baseSong, onlineLines)
        val candidate = OnlineTranslationSelector.Candidate(
            source = source,
            onlineLineCount = onlineLines.size,
            translatedLineCount = onlineLines.count { !it.translation.isNullOrBlank() },
            result = result
        )
        diagnostic(
            "Apple Music 在线翻译候选: title=${baseSong.name}, source=$source, " +
                "lines=${candidate.onlineLineCount}, " +
                "translated=${candidate.translatedLineCount}, " +
                "matched=${result.matchedCount}/$totalLineCount, " +
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
        result: OnlineTranslationMatcher.Result?
    ) {
        val nativeSong = currentAppleSong
        val requestStillCurrent = generation == onlineTranslationGeneration &&
            nativeSong != null &&
            isSameTrack(nativeSong, baseSong) &&
            currentAppleHasNativeLyrics &&
            !hasTranslation(nativeSong) &&
            isOnlineTranslationMatchEnabled()
        if (!requestStillCurrent) {
            diagnostic(
                "Apple Music 在线翻译匹配结果已过期: title=${baseSong.name}, " +
                    "generation=$generation, currentGeneration=$onlineTranslationGeneration"
            )
            return
        }

        onlineTranslationJob = null
        if (result == null || result.matchedCount == 0) {
            diagnostic("Apple Music 在线翻译匹配未命中: title=${baseSong.name}")
            if (!onlineMatchedTranslationActive) {
                sink?.onOnlineTranslationUnavailable(nativeSong)
            }
            return
        }

        onlineMatchedTranslationActive = true
        diagnostic(
            "Apple Music 在线翻译匹配命中: title=${baseSong.name}, " +
                "matched=${result.matchedCount}, total=${baseSong.lyrics.orEmpty().size}"
        )
        val unmatched = result.song.lyrics.orEmpty().mapIndexedNotNull { index, line ->
            if (line.translation.isNullOrBlank()) {
                "$index@${line.begin}:${line.text.orEmpty().take(48)}"
            } else {
                null
            }
        }
        if (unmatched.isNotEmpty()) {
            diagnostic(
                "Apple Music 在线翻译未匹配行: title=${baseSong.name}, " +
                    unmatched.joinToString(separator = " | ")
            )
        }
        publishSong(
            result.song,
            restorePosition = true,
            onlineTranslationMatched = true
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
        if (clearMatched) onlineMatchedTranslationActive = false
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
        if (!isFallbackEnabled()) return
        if (hasNonAppleCentralPlayer()) return
        val media = MediaMetadataHelper.getMediaInfo(application, APPLE_MUSIC_PACKAGE, HookLogger)
        if (media.title.isBlank()) return

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
        if (nativeSong != null && isSameTrack(nativeSong, mediaSong)) {
            val fallbackPending = fallbackDelayRunnable != null || fallbackJob?.isActive == true
            if (!currentAppleHasNativeLyrics && !fallbackPending && !fallbackSongActive) {
                diagnostic(
                    "Apple Music 媒体会话补充触发在线兜底: title=${media.title}, " +
                        "artist=${media.artist}, duration=${media.duration}"
                )
                scheduleFallback(nativeSong, APPLE_LYRICS_GRACE_MS)
            }
            return
        }

        diagnostic(
            "Apple Music 媒体会话检测到歌曲: title=${media.title}, " +
                "artist=${media.artist}, duration=${media.duration}"
        )
        handleAppleSong(mediaSong)
    }

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

    private fun hasTranslation(song: LocalSong?): Boolean = song?.lyrics?.any {
        !it.translation.isNullOrBlank()
    } == true

    private fun translationIdentity(song: LocalSong): String {
        val id = song.id?.trim().orEmpty()
        if (id.isNotEmpty()) return "id:$id"
        return "${normalizeIdentity(song.name)}|${normalizeIdentity(song.artist)}"
    }

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
        }

        override fun onReconnected(subscriber: LyriconSubscriber) {
                HookLogger.i(TAG, "订阅连接已恢复")
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
            if (!hasActiveCentralPlayer()) return
            if (centralAppleProviderActive && fallbackSongActive) return
            val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
            if (centralAppleProviderActive) lastAdjustedPosition = adjustedPosition
            sink?.onPositionChanged(adjustedPosition)
        }


        override fun onSeekTo(position: Long) {
            if (!hasActiveCentralPlayer()) return
            if (centralAppleProviderActive && fallbackSongActive) return
            val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
            if (centralAppleProviderActive) lastAdjustedPosition = adjustedPosition
            sink?.onSeekTo(adjustedPosition)
        }

        override fun onReceiveText(text: String?) {
            if (!hasActiveCentralPlayer()) return
            if (centralAppleProviderActive && fallbackSongActive) return
            sink?.onPlainText(text)
        }

        // 提供器只负责提供歌词内容；翻译和罗马音是否显示由 HyperLyrics Enhanced 显示端配置决定。
        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
    }

    internal fun onDirectSongChanged(song: LyriconSong?) {
        if (hasActiveCentralPlayer()) return
        val providerPackage = "com.juren233.hyperlyricsenhanced"
        activeProviderPackageName = providerPackage
        activeProviderDelayMs = readProviderDelay(providerPackage)
        LyriconDataBridge.updateLyricPackage(APPLE_MUSIC_PACKAGE)
        val localSong = song?.toLocalSong()
        handleAppleSong(localSong)
    }

    internal fun onDirectPlaybackStateChanged(isPlaying: Boolean) {
        if (!hasActiveCentralPlayer() && !fallbackSongActive) {
            sink?.onPlaybackStateChanged(isPlaying)
        }
    }

    internal fun onDirectPositionChanged(position: Long) {
        if (hasActiveCentralPlayer() || fallbackSongActive) return
        val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
        lastAdjustedPosition = adjustedPosition
        sink?.onPositionChanged(adjustedPosition)
    }

    internal fun onDirectSeekTo(position: Long) {
        if (hasActiveCentralPlayer() || fallbackSongActive) return
        val adjustedPosition = (position - activeProviderDelayMs).coerceAtLeast(0L)
        lastAdjustedPosition = adjustedPosition
        sink?.onSeekTo(adjustedPosition)
    }

    internal fun onDirectText(text: String?) {
        if (!hasActiveCentralPlayer() && !fallbackSongActive) sink?.onPlainText(text)
    }
}
