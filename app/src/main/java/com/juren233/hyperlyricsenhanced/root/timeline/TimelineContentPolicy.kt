/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import com.juren233.hyperlyricsenhanced.common.media.MediaSessionMetadataSanitizer
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences.Settings
import com.juren233.hyperlyricsenhanced.root.source.MediaSessionOnlineFallbackPolicy

/** 统一时间轴接收内容前的纯逻辑门禁。 */
object TimelineContentPolicy {
    /** 已接纳的主源按应用时的锚点键保留优先级，专辑等展示字段刷新不能使其失效。 */
    internal fun hasPrimary(
        activeSourceId: String?,
        track: TrackIdentity?,
        content: TimelineContent?,
        appliedTrackKey: String? = content?.track?.normalizedKey(),
    ): Boolean = content != null && activeSourceId != null && content.sourceId == activeSourceId &&
        hasUsableLyrics(content.song) && track != null && appliedTrackKey == track.normalizedKey()

    private fun hasUsableLyrics(song: Song?): Boolean = song?.lyrics.orEmpty().any {
        it.begin >= 0L && it.end > it.begin && !it.text.isNullOrBlank()
    }

    /** 补充内容必须有独立身份、明确授权和严格曲目匹配，不能占用主源身份。 */
    internal fun admitsOnlineFallback(
        activeSourceId: String?,
        currentTrack: TrackIdentity?,
        content: TimelineContent,
        displayed: TimelineContent?,
        settings: Settings,
        sessionUsable: Boolean,
        appliedTrackKey: String? = displayed?.track?.normalizedKey(),
    ): Boolean = activeSourceId != null &&
        content.sourceId == MediaSessionOnlineFallbackPolicy.SOURCE_ID &&
        content.strictIdentity && content.track != null && currentTrack != null &&
        MediaSessionOnlineFallbackPolicy.sameLookupTrack(content.track, currentTrack) &&
        hasUsableLyrics(content.song) &&
        MediaSessionOnlineFallbackPolicy.eligible(settings, currentTrack, sessionUsable) &&
        !hasPrimary(activeSourceId, currentTrack, displayed, appliedTrackKey)

    enum class Decision {
        APPLY,
        HOLD_FOR_TRACK,
        DROP_INACTIVE_SOURCE,
        DROP_WRONG_TRACK,
    }

    fun decide(
        activeSourceId: String?,
        currentTrack: TrackIdentity?,
        content: TimelineContent,
    ): Decision {
        if (activeSourceId == null || content.sourceId != activeSourceId) {
            return Decision.DROP_INACTIVE_SOURCE
        }
        val contentTrack = content.track ?: return if (currentTrack == null) {
            Decision.HOLD_FOR_TRACK
        } else {
            Decision.APPLY
        }
        val anchorTrack = currentTrack ?: return Decision.HOLD_FOR_TRACK
        val matches = if (content.strictIdentity) {
            contentTrack.matches(anchorTrack)
        } else {
            contentTrack.matchesAvailableFields(anchorTrack)
        }
        return if (matches) Decision.APPLY else Decision.DROP_WRONG_TRACK
    }

    data class DisplayMetadata(val title: String, val artist: String)

    /**
     * 会话文本（标题、歌手）是否不可信：已被净化器判定为污染，或其歌手串恰好能由已匹配歌曲的
     * 歌名与歌手拼出。后一条让污染确认前的头几秒也能用歌曲自己的名称，不必等漂移证据累计。
     */
    fun isSessionTextUntrusted(track: TrackIdentity, song: Song?): Boolean =
        track.polluted ||
            (song != null && MediaSessionMetadataSanitizer.combinedExplains(track.artist, song.name, song.artist))

    /**
     * 渲染面显示的歌曲名与歌手。会话文本可信时沿用会话；不可信时改用已匹配歌曲的名称与歌手，
     * 没有歌曲内容时留空，而不是显示歌词行。
     */
    fun displayMetadata(track: TrackIdentity, song: Song?): DisplayMetadata =
        if (isSessionTextUntrusted(track, song)) {
            DisplayMetadata(song?.name.orEmpty().trim(), song?.artist.orEmpty().trim())
        } else {
            DisplayMetadata(track.title, track.artist)
        }

    /**
     * 已应用内容由歌词源以自身包名声明、且与媒体锚点同包时，返回该播放器包名。
     * 锚点可能选中任意媒体会话（含视频 App），只有来源声明过的包才算歌词来源播放器。
     */
    fun sourceDeclaredPlayer(content: TimelineContent, anchorTrack: TrackIdentity): String? {
        val declared = content.track?.packageName?.trim().orEmpty()
        if (declared.isEmpty() || declared != anchorTrack.packageName.trim()) return null
        return declared
    }

    /**
     * 同曲展示信息刷新只转发给时间轴已接管的曲目（已应用内容、标题回退或同包切歌过渡）。
     * 锚点选中的视频等非作用域会话从未被接管，刷新不得写入歌词包名与标题（issue #39 同类路径）。
     */
    fun shouldForwardMetadataRefresh(appliedTrackKey: String?, track: TrackIdentity): Boolean =
        appliedTrackKey != null && appliedTrackKey == track.normalizedKey()
}
