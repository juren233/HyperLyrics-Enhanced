package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences.Settings
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 调度限定在主线程；取词及解析在 IO。只消费锚点选中的会话，不参与选会话。 */
internal class MediaSessionOnlineFallbackCoordinator(
    private val tracks: Tracks,
    private val timeline: Timeline,
    private val settings: () -> Settings,
    private val fetchSong: suspend (TrackIdentity, List<Source>) -> Song?,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val graceMs: Long = 1_500L,
) {
    interface Tracks {
        val current: TrackIdentity?
        /** PLAYING / BUFFERING / PAUSED；STOPPED、ERROR、NONE 或消失时为 false。 */
        val usable: Boolean
        fun listen(onChanged: () -> Unit)
        fun unlisten()
    }

    interface Timeline {
        fun hasPrimary(track: TrackIdentity): Boolean
        /** 当前曲目已显示逐字歌词（主源或补充）。 */
        fun hasWordTimedContent(track: TrackIdentity): Boolean
        fun hasFallback(track: TrackIdentity): Boolean
        fun authorize(settings: Settings, sessionUsable: Boolean)
        fun submit(content: TimelineContent)
        fun listen(onPrimaryChanged: () -> Unit)
        fun close()
    }

    private data class RequestKey(val track: String, val title: String, val artist: String, val sources: List<Source>)
    private data class Cached(val track: TrackIdentity, val song: Song?, val expiresAt: Long)
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val cache = linkedMapOf<RequestKey, Cached>()
    private var request: Job? = null
    private var currentKey: RequestKey? = null
    private var requestTrack: TrackIdentity? = null
    private var generation = 0L
    private var started = false
    private var stopped = false

    fun start() {
        check(!stopped) { "A stopped fallback coordinator cannot be restarted" }
        if (started) return
        started = true
        tracks.listen(::refresh)
        timeline.listen(::refresh)
        refresh()
    }

    private var lastSkip: String? = null

    /** 仅调试：记录「为什么没取词」，同一曲目同一原因只记一次。 */
    private fun logSkip(track: TrackIdentity?, reason: String) {
        if (!com.juren233.hyperlyricsenhanced.BuildConfig.DEBUG) return
        val line = "跳过补充: pkg=${track?.packageName}, title=${track?.title}, $reason"
        if (line == lastSkip) return
        lastSkip = line
        com.juren233.hyperlyricsenhanced.root.utils.HookLogger.d("MediaSessionOnlineFallback", line)
    }

    fun refresh() {
        if (!started || stopped) return
        val config = settings()
        val track = tracks.current
        timeline.authorize(config, tracks.usable)
        if (!MediaSessionOnlineFallbackPolicy.eligible(config, track, tracks.usable) || track == null) {
            logSkip(track, "不满足补充条件: usable=${tracks.usable}, 在启用名单=${track?.packageName in config.packages}, 污染=${track?.polluted}")
            cancelRequest()
            return
        }
        if (timeline.hasWordTimedContent(track)) {
            logSkip(track, "已有逐字歌词，不再补充")
            cancelRequest()
            return
        }
        // 主源只有整行歌词时只做逐字升级：仅 TTML 目录源，且播放器必须有可寻址的平台 ID。
        val upgradeOnly = timeline.hasPrimary(track)
        val sources = if (upgradeOnly) {
            config.sources.filter(Source::isTtmlCatalog).takeIf {
                CatalogPlatform.ofPlayerPackage(track.packageName)?.let { CatalogPlatform.songIdOf(it, track.mediaId) } != null
            }.orEmpty()
        } else {
            config.sources
        }
        if (sources.isEmpty()) {
            logSkip(track, "无可用来源: 仅升级=$upgradeOnly, 平台=${CatalogPlatform.ofPlayerPackage(track.packageName)}, mediaId=${track.mediaId}")
            cancelRequest()
            return
        }
        val key = RequestKey(track.normalizedKey(), track.title.trim(), track.artist.trim(), sources.toList())
        // 元数据补全、位置、暂停恢复均不改变调度键；请求完成后仍保留键以避免重复提交。
        if (currentKey == key && requestTrack?.let {
                MediaSessionOnlineFallbackPolicy.sameLookupTrack(it, track)
            } == true
        ) {
            val failed = cache[key]?.takeIf { it.song == null }
            if (request?.isActive == true || timeline.hasFallback(track) ||
                (failed != null && failed.expiresAt > nowMs())
            ) return
        }
        cancelRequest()
        currentKey = key
        requestTrack = track
        val token = generation
        request = scope.launch {
            delay(graceMs)
            if (!canPublish(token, track, config, upgradeOnly)) return@launch
            val cached = cache[key]?.takeIf {
                it.expiresAt > nowMs() && MediaSessionOnlineFallbackPolicy.sameLookupTrack(it.track, track)
            }
            val song = if (cached != null) cached.song else {
                val fetched = try {
                    withContext(ioDispatcher) {
                        withTimeoutOrNull(30_000L) { fetchSong(track, sources) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (!canPublish(token, track, config, upgradeOnly)) return@launch
                val usableSong = fetched?.takeIf { !it.lyrics.isNullOrEmpty() }
                cache[key] = Cached(track, usableSong, nowMs() + if (usableSong == null) 60_000L else 600_000L)
                while (cache.size > 16) cache.remove(cache.keys.first())
                usableSong
            }
            // 无结果不提交空内容，保留时间轴原有的空态/标题回退。
            if (song != null && canPublish(token, track, config, upgradeOnly)) {
                timeline.submit(TimelineContent(
                    sourceId = MediaSessionOnlineFallbackPolicy.SOURCE_ID,
                    track = track,
                    song = song,
                    onlineTranslationMatched = true,
                    strictIdentity = true,
                    revision = token,
                ))
            }
        }
    }

    private fun canPublish(
        token: Long,
        track: TrackIdentity,
        captured: Settings,
        upgradeOnly: Boolean,
    ): Boolean {
        val current = tracks.current ?: return false
        val config = settings()
        return !stopped && generation == token && config.sources == captured.sources &&
            MediaSessionOnlineFallbackPolicy.sameLookupTrack(track, current) &&
            MediaSessionOnlineFallbackPolicy.eligible(config, current, tracks.usable) &&
            !timeline.hasWordTimedContent(current) &&
            (upgradeOnly || !timeline.hasPrimary(current))
    }

    private fun cancelRequest() {
        generation++
        request?.cancel()
        request = null
        currentKey = null
        requestTrack = null
    }

    fun stop() {
        if (stopped) return
        stopped = true
        cancelRequest()
        try {
            tracks.unlisten()
        } finally {
            try { timeline.close() } finally {
                scope.cancel()
                cache.clear()
            }
        }
    }
}
