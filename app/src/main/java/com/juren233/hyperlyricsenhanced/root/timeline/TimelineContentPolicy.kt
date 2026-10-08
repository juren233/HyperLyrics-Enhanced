/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity

/** 统一时间轴接收内容前的纯逻辑门禁。 */
object TimelineContentPolicy {
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
