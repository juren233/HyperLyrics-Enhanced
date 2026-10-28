package com.juren233.hyperlyricsenhanced.root.timeline

import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences.Settings
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.root.source.MediaSessionOnlineFallbackPolicy
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

    @Test
    fun `source declared player is confirmed only when it is the anchor package`() {
        val content = TimelineContent(sourceId = "lyricon", track = anchor, song = null)

        assertEquals("player.package", TimelineContentPolicy.sourceDeclaredPlayer(content, anchor))
        assertEquals(
            null,
            TimelineContentPolicy.sourceDeclaredPlayer(
                content,
                anchor.copy(packageName = "video.package"),
            ),
        )
    }

    @Test
    fun `content without a source package never confirms the anchor player`() {
        val undeclared = TimelineContent(
            sourceId = "lyricon",
            track = anchor.copy(packageName = ""),
            song = null,
        )
        val trackless = TimelineContent(sourceId = "lyricon", track = null, song = null)

        assertEquals(null, TimelineContentPolicy.sourceDeclaredPlayer(undeclared, anchor))
        assertEquals(null, TimelineContentPolicy.sourceDeclaredPlayer(trackless, anchor))
    }

    @Test
    fun `metadata refresh is forwarded only for the track the timeline owns`() {
        assertTrue(
            TimelineContentPolicy.shouldForwardMetadataRefresh(
                appliedTrackKey = anchor.normalizedKey(),
                track = anchor.copy(album = "Restored Album"),
            )
        )
    }

    @Test
    fun `metadata refresh of an unowned session is dropped`() {
        val video = TrackIdentity(
            packageName = "video.package",
            mediaId = "video-1",
            title = "Video",
            durationMs = 60_000L,
        )

        assertFalse(TimelineContentPolicy.shouldForwardMetadataRefresh(null, video))
        assertFalse(
            TimelineContentPolicy.shouldForwardMetadataRefresh(
                appliedTrackKey = anchor.normalizedKey(),
                track = video,
            )
        )
    }

    private fun fallbackSong() = Song(
        lyrics = listOf(RichLyricLine(
            begin = 0, end = 1_000, duration = 1_000, text = "Line",
        )),
    )

    private val fallbackSettings get() = Settings(packages = setOf(anchor.packageName))

    private fun fallbackContent() = TimelineContent(
        sourceId = MediaSessionOnlineFallbackPolicy.SOURCE_ID,
        track = anchor, song = fallbackSong(), strictIdentity = true,
    )

    @Test
    fun `authorized fallback enters timeline while Lyricon stays selected`() {
        val fallback = fallbackContent()
        assertEquals(TimelineContentPolicy.Decision.DROP_INACTIVE_SOURCE,
            TimelineContentPolicy.decide("lyricon", anchor, fallback))
        assertTrue(TimelineContentPolicy.admitsOnlineFallback(
            "lyricon", anchor, fallback, null, fallbackSettings, true,
        ))
    }

    @Test
    fun `primary lyrics block fallback and later primary replaces displayed fallback`() {
        val primary = TimelineContent("lyricon", anchor, fallbackSong())
        val fallback = fallbackContent()
        assertFalse(TimelineContentPolicy.admitsOnlineFallback(
            "lyricon", anchor, fallback, primary, fallbackSettings, true,
        ))
        assertEquals(TimelineContentPolicy.Decision.APPLY,
            TimelineContentPolicy.decide("lyricon", anchor, primary))
        assertFalse(TimelineContentPolicy.hasPrimary("lyricon", anchor, fallback))
        assertTrue(TimelineContentPolicy.hasPrimary("lyricon", anchor, primary))
        assertFalse(TimelineContentPolicy.hasPrimary("lyricinfo", anchor, primary))
    }

    @Test
    fun `fallback cannot forge source skip identity or enter after stop`() {
        val content = fallbackContent()
        fun admitted(candidate: TimelineContent, track: TrackIdentity? = anchor, active: String? = "lyricon") =
            TimelineContentPolicy.admitsOnlineFallback(active, track, candidate, null, fallbackSettings, true)
        assertFalse(admitted(content.copy(sourceId = "lyricon")))
        assertFalse(admitted(content.copy(track = null)))
        assertFalse(admitted(content.copy(strictIdentity = false)))
        assertFalse(admitted(content.copy(track = anchor.copy(mediaId = "other"))))
        assertFalse(admitted(content.copy(track = anchor.copy(packageName = "other.player"))))
        assertFalse(admitted(content.copy(track = anchor.copy(title = "Another tab"))))
        assertFalse(admitted(content, track = null))
        assertFalse(admitted(content, active = null))
        assertFalse(TimelineContentPolicy.admitsOnlineFallback(
            "lyricon", anchor, content, null, fallbackSettings, false,
        ))
        assertFalse(TimelineContentPolicy.admitsOnlineFallback(
            "lyricon", anchor, content, null, fallbackSettings.copy(packages = emptySet()), true,
        ))
    }

    @Test
    fun `empty lyrics never enter through fallback and empty primary does not claim priority`() {
        val empty = TimelineContent("lyricon", anchor, fallbackSong().copy(lyrics = emptyList()))
        assertFalse(TimelineContentPolicy.hasPrimary("lyricon", anchor, empty))
        assertFalse(TimelineContentPolicy.admitsOnlineFallback(
            "lyricon", anchor, fallbackContent().copy(song = null), null, fallbackSettings, true,
        ))
        assertTrue(TimelineContentPolicy.admitsOnlineFallback(
            "lyricon", anchor, fallbackContent(), empty, fallbackSettings, true,
        ))
    }

    @Test
    fun `accepted primary with partial identity retains priority after album refresh`() {
        val primary = TimelineContent("lyricon", anchor.copy(mediaId = null, durationMs = 0), fallbackSong())
        val refreshed = anchor.copy(album = "Refreshed album")
        assertTrue(TimelineContentPolicy.hasPrimary("lyricon", refreshed, primary, anchor.normalizedKey()))
        assertFalse(TimelineContentPolicy.admitsOnlineFallback(
            "lyricon", refreshed, fallbackContent().copy(track = refreshed), primary,
            fallbackSettings, true, anchor.normalizedKey(),
        ))
        assertFalse(TimelineContentPolicy.hasPrimary("lyricon", refreshed, primary, "another-track"))
    }
}
