package com.juren233.hyperlyricsenhanced.root.timeline

import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineContentPolicyTest {
    private val anchor = TrackIdentity(
        packageName = "player.package",
        mediaId = "session-id",
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
    )

    @Test
    fun `inactive source can never enter timeline`() {
        val content = TimelineContent(sourceId = "lyricinfo", track = anchor, song = null)

        assertEquals(
            TimelineContentPolicy.Decision.DROP_INACTIVE_SOURCE,
            TimelineContentPolicy.decide("lyricon", anchor, content),
        )
    }

    @Test
    fun `content waits until system media identity exists`() {
        val content = TimelineContent(sourceId = "remote_payload", track = anchor, song = null)

        assertEquals(
            TimelineContentPolicy.Decision.HOLD_FOR_TRACK,
            TimelineContentPolicy.decide("remote_payload", null, content),
        )
    }

    @Test
    fun `app timeline requires exact cross process identity`() {
        val content = TimelineContent(
            sourceId = "remote_payload",
            track = anchor.copy(mediaId = null),
            song = null,
            strictIdentity = true,
        )

        assertEquals(
            TimelineContentPolicy.Decision.APPLY,
            TimelineContentPolicy.decide("remote_payload", anchor, content),
        )
        assertEquals(
            TimelineContentPolicy.Decision.DROP_WRONG_TRACK,
            TimelineContentPolicy.decide(
                "remote_payload",
                anchor.copy(durationMs = 181_000L),
                content,
            ),
        )
    }

    @Test
    fun `in process source matches only fields it actually provides`() {
        val partial = TrackIdentity(
            packageName = anchor.packageName,
            title = anchor.title,
            artist = anchor.artist,
        )
        val content = TimelineContent(sourceId = "lyricon", track = partial, song = null)

        assertEquals(
            TimelineContentPolicy.Decision.APPLY,
            TimelineContentPolicy.decide("lyricon", anchor, content),
        )
        assertEquals(
            TimelineContentPolicy.Decision.DROP_WRONG_TRACK,
            TimelineContentPolicy.decide(
                "lyricon",
                anchor.copy(packageName = "other.player"),
                content,
            ),
        )
    }

    @Test
    fun `available field matching never falls back to fuzzy title or id`() {
        assertFalse(anchor.matchesAvailableFields(anchor.copy(mediaId = "other-id")))
        assertFalse(
            anchor.copy(mediaId = null).matchesAvailableFields(
                anchor.copy(mediaId = null, title = "Song (Live)"),
            )
        )
        assertTrue(
            anchor.copy(mediaId = null, album = "", durationMs = 0L)
                .matchesAvailableFields(anchor),
        )
    }

    @Test
    fun `QQ HD treats changed title as a new track despite reused media session id`() {
        val previous = TrackIdentity(
            packageName = "com.tencent.qqmusicpad",
            mediaId = "2354",
            title = "iPad",
            artist = "The Chainsmokers",
        )
        val next = previous.copy(title = "Dreamland", artist = "Glass Animals")

        assertFalse(previous.normalizedKey() == next.normalizedKey())
        assertFalse(previous.matches(next))
        assertEquals(
            TimelineContentPolicy.Decision.DROP_WRONG_TRACK,
            TimelineContentPolicy.decide(
                "lyricon",
                next,
                TimelineContent(sourceId = "lyricon", track = previous.copy(mediaId = null), song = null),
            ),
        )
    }

    @Test
    fun `QQ HD ignores session id and album refresh within the same song`() {
        val track = TrackIdentity(
            packageName = "com.tencent.qqmusicpad",
            mediaId = "4137",
            title = "Dreamland",
            artist = "Glass Animals",
            album = "Old Album",
        )
        val refreshed = track.copy(mediaId = "4209", album = "Dreamland")

        assertEquals(track.normalizedKey(), refreshed.normalizedKey())
        assertTrue(track.matches(refreshed))
        assertTrue(track.copy(mediaId = null).matchesAvailableFields(refreshed))
        assertFalse(track.copy(artist = "").matchesAvailableFields(refreshed))
        assertTrue(
            track.copy(title = "Dream Land (Live)", artist = "Glass Animals!")
                .matchesAvailableFields(
                    refreshed.copy(title = "Dream Land（Live）", artist = "Glass Animals"),
                ),
        )
    }
}
