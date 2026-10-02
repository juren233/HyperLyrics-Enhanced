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
}
