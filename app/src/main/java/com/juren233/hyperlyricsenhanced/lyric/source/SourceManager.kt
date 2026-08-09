package com.juren233.hyperlyricsenhanced.lyric.source

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.HyperLogger

class SourceManager(
    private val sources: List<LyricSource>,
    private val prefs: SharedPreferences,
    private val sink: LyricSink,
    private val prefKey: String,
    private val defaultSourceId: String,
    private val stateResetter: StateResetter,
    private val logger: HyperLogger
) {
    private var activeSource: LyricSource? = null

    fun start() {
        val current = activeSource
        if (current != null) {
            diagnostic(
                "stage=start_skipped, reason=already_active, " +
                    "current=${current.id}/${current.displayName}",
            )
            return
        }

        val sourceId = prefs.getString(prefKey, defaultSourceId) ?: defaultSourceId
        val source = sources.find { it.id == sourceId && it.isAvailable() }
            ?: sources.firstOrNull { it.isAvailable() }
        diagnostic(
            "stage=start_resolved, requested=$sourceId, " +
                "resolved=${source?.id}/${source?.displayName}",
        )

        if (source == null) {
            logger.w("SourceManager", "没有可用的歌词源")
            return
        }

        activeSource = source
        logger.i("SourceManager", "启动歌词源: ${source.displayName}")
        source.start(sink)
        diagnostic("stage=start_returned, active=${source.id}/${source.displayName}")
    }

    fun switchSource(sourceId: String) {
        val current = activeSource
        diagnostic(
            "stage=switch_requested, requested=$sourceId, " +
                "current=${current?.id}/${current?.displayName}",
        )
        if (current?.id == sourceId) {
            diagnostic(
                "stage=switch_skipped, reason=same_source, " +
                    "current=${current.id}/${current.displayName}",
            )
            return
        }

        current?.stop()
        stateResetter.clearState()

        val source = sources.find { it.id == sourceId && it.isAvailable() }
        if (source == null) {
            logger.w("SourceManager", "歌词源不可用: $sourceId")
            return
        }

        activeSource = source
        logger.i("SourceManager", "切换歌词源: ${source.displayName}")
        source.start(sink)
        diagnostic("stage=switch_returned, active=${source.id}/${source.displayName}")
    }

    fun getActiveSource(): LyricSource? = activeSource

    fun stop() {
        diagnostic(
            "stage=stop_requested, " +
                "current=${activeSource?.id}/${activeSource?.displayName}",
        )
        activeSource?.stop()
        activeSource = null
        diagnostic("stage=stop_completed")
    }

    private fun diagnostic(message: String) {
        if (BuildConfig.DEBUG) logger.i("SourceManager", "[debug] $message")
    }
}
