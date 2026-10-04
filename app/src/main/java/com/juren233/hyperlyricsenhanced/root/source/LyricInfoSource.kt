package com.juren233.hyperlyricsenhanced.root.source

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import com.juren233.hyperlyricsenhanced.common.lyric.LyricInfoParser
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSource
import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.root.timeline.SystemMediaPlaybackAnchor
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanup
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanupException
import java.util.concurrent.ConcurrentHashMap

/** 从 MediaMetadata 的 lyricInfo 字段生产完整歌词内容，不参与播放时钟与逐行滚动。 */
class LyricInfoSource(private val context: Context) : LyricSource {

    override val id = "lyricinfo"
    override val displayName = "LyricInfo"

    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val trackedControllers = ConcurrentHashMap<MediaController, MediaController.Callback>()
    @Volatile
    private var sink: LyricSink? = null
    @Volatile
    private var generation = 0L
    private var starting = false
    private var cleanupFailure: RuntimeResourceCleanupException? = null
    private var lastLyricHash: Int = 0
    private var activePackage: String? = null
    private var activeController: MediaController? = null

    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    override fun isAvailable() = true

    override fun start(sink: LyricSink) {
        cleanupFailure?.let { throw it }
        if (this.sink != null || sessionListener != null) stop()
        this.sink = sink
        val currentGeneration = ++generation
        val sessions = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            if (generation == currentGeneration && this.sink != null) onActiveSessionsChanged(controllers)
        }
        sessionListener = sessions
        starting = true
        try {
            manager.addOnActiveSessionsChangedListener(sessions, null)
            onActiveSessionsChanged(manager.getActiveSessions(null))
            HookLogger.i(TAG, "数据源已启动")
        } catch (error: Throwable) {
            HookLogger.e(TAG, "数据源启动失败", error)
            throw error
        } finally {
            starting = false
        }
    }

    override fun stop() {
        val previousSink = sink
        val previousSessions = sessionListener
        val previousControllers = trackedControllers.toMap()
        generation++
        sink = null
        sessionListener = null
        trackedControllers.clear()
        clearSelection()
        val cleanup = RuntimeResourceCleanup()
        cleanup.attempt("previous lyricInfo cleanup") { cleanupFailure?.let { throw it } }
        cleanup.attempt("lyricInfo session listener") {
            previousSessions?.let(manager::removeOnActiveSessionsChangedListener)
        }
        previousControllers.forEach { (controller, callback) ->
            cleanup.attempt("lyricInfo controller callback") { controller.unregisterCallback(callback) }
        }
        cleanup.attempt("lyricInfo sink") { previousSink?.onStop() }
        cleanupFailure = cleanup.failureOrNull()
        cleanupFailure?.let { throw it }
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        if (sink == null) return
        val currentGeneration = generation
        controllers ?: return
        val currentSessions = controllers.toSet()
        trackedControllers.keys.filter { it !in currentSessions }.forEach { dead ->
            trackedControllers[dead]?.let { callback ->
                runCatching { dead.unregisterCallback(callback) }
                    .onSuccess { trackedControllers.remove(dead, callback) }
            }
        }
        val activeToken = activeController?.sessionToken
        if (activeToken != null && controllers.none { it.sessionToken == activeToken }) {
            sink?.onStop()
            clearSelection()
        }
        controllers.forEach { controller ->
            if (trackedControllers.containsKey(controller)) return@forEach
            val callback = object : MediaController.Callback() {
                private fun isCurrent() = generation == currentGeneration &&
                    sink != null && trackedControllers[controller] === this

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (isCurrent()) publish(controller, metadata)
                }
                override fun onSessionDestroyed() {
                    if (isCurrent()) onActiveSessionsChanged(manager.getActiveSessions(null))
                }
            }
            trackedControllers[controller] = callback
            runCatching {
                controller.registerCallback(callback)
                publish(controller, controller.metadata)
            }.onFailure {
                if (starting) throw it
                HookLogger.w(TAG, "媒体会话回调注册失败", it)
            }
        }
    }

    private fun publish(controller: MediaController, metadata: MediaMetadata?) {
        if (sink == null) return
        metadata ?: return
        val packageName = controller.packageName ?: return
        val raw = runCatching { metadata.getString("lyricInfo") }.getOrNull()
        val hash = raw?.hashCode() ?: 0

        if (raw.isNullOrBlank() || hash == 0) {
            if (packageName == activePackage) {
                sink?.onStop()
                clearSelection()
                HookLogger.d(TAG, "歌词已清除: package=$packageName")
            }
            return
        }
        if (hash == lastLyricHash && packageName == activePackage) {
            activeController = controller
            return
        }

        val track = SystemMediaPlaybackAnchor.buildTrackIdentity(packageName, metadata)
        logDiagnosis(raw)
        val song = LyricInfoParser.parse(raw, track.title, track.artist)
        if (song?.lyrics.isNullOrEmpty()) return

        lastLyricHash = hash
        activePackage = packageName
        activeController = controller
        sink?.onTimelineContent(
            TimelineContent(
                sourceId = id,
                track = track,
                song = song,
                strictIdentity = true,
            )
        )
        HookLogger.d(TAG, "完整歌词已提交: song=${track.title}, lines=${song.lyrics!!.size}")
    }

    private fun clearSelection() {
        lastLyricHash = 0
        activePackage = null
        activeController = null
    }

    private fun logDiagnosis(json: String) {
        val diagnosis = LyricInfoParser.diagnose(json) ?: return
        HookLogger.d(
            TAG,
            "songName=${diagnosis.songName} | artist=${diagnosis.artist} | " +
                "songId=${diagnosis.songId} | format=${diagnosis.format} | " +
                "translation=${diagnosis.translationFormat} | lyric=${diagnosis.lyricLength}chars | " +
                diagnosis.lyricPreview.joinToString(" | ")
        )
    }

    private companion object {
        const val TAG = "LyricInfoSource"
    }
}
