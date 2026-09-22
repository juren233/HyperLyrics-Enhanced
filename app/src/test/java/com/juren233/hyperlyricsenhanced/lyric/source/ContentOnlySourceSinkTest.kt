package com.juren233.hyperlyricsenhanced.lyric.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentOnlySourceSinkTest {
    @Test
    fun `source without clock capability cannot drive timeline`() {
        val delegate = RecordingSink()
        val boundary = ContentOnlySourceSink("superlyric", { true }, delegate)

        boundary.onMetadata("title", "artist", "album", "publisher")
        boundary.onPlaybackStateChanged(true)
        boundary.onPositionChanged(123L)
        boundary.onSeekTo(456L)

        assertTrue(delegate.events.isEmpty())
    }

    @Test
    fun `active clock source forwards position and seek through timeline`() {
        val delegate = RecordingSink()
        val boundary = ContentOnlySourceSink("lyricon", { true }, delegate, allowSourceClock = true)

        boundary.onPlaybackStateChanged(true)
        boundary.onPositionChanged(123L)
        boundary.onSeekTo(456L)

        assertEquals(listOf("position:123", "seek:456"), delegate.events)
    }

    @Test
    fun `inactive clock source cannot change timeline`() {
        var active = true
        val delegate = RecordingSink()
        val boundary = ContentOnlySourceSink("lyricon", { active }, delegate, allowSourceClock = true)
        boundary.onPositionChanged(123L)
        active = false

        boundary.onPositionChanged(456L)
        boundary.onSeekTo(789L)

        assertEquals(listOf("position:123"), delegate.events)
    }

    @Test
    fun `line only source content reaches the same timeline sink`() {
        val delegate = RecordingSink()
        val boundary = ContentOnlySourceSink("superlyric", { true }, delegate)

        boundary.onLyricLine("line")
        boundary.onPlainText("plain")

        assertEquals(listOf("line", "text"), delegate.events)
    }

    @Test
    fun `complete song is converted to tagged timeline content`() {
        val delegate = RecordingSink()
        val boundary = ContentOnlySourceSink("lyricon", { true }, delegate)
        val song = Song(name = "Song")

        boundary.onSongChanged(song)

        assertEquals(1, delegate.contents.size)
        assertEquals("lyricon", delegate.contents.single().sourceId)
        assertEquals(song, delegate.contents.single().song)
    }

    @Test
    fun `late callbacks from stopped source are rejected`() {
        var active = true
        val delegate = RecordingSink()
        val boundary = ContentOnlySourceSink("lyricinfo", { active }, delegate)
        active = false

        boundary.onTimelineContent(TimelineContent("lyricinfo", null, Song(name = "Old")))
        boundary.onStop()

        assertTrue(delegate.contents.isEmpty())
        assertTrue(delegate.events.isEmpty())
        assertNull(boundary.currentPlaybackState())
    }

    private class RecordingSink : LyricSink {
        val contents = mutableListOf<TimelineContent>()
        val events = mutableListOf<String>()

        override fun onTimelineContent(content: TimelineContent) {
            contents += content
        }

        override fun onSongChanged(song: Any?) { events += "song" }
        override fun onLyricLine(line: Any?) { events += "line" }
        override fun onPlainText(text: String?) { events += "text" }
        override fun onStop() { events += "stop" }
        override fun onMetadata(title: String?, artist: String?, album: String?, publisher: String?) {
            events += "metadata"
        }
        override fun onPlaybackStateChanged(isPlaying: Boolean) { events += "playback" }
        override fun currentPlaybackState(): Boolean = true
        override fun onPositionChanged(position: Long) { events += "position:$position" }
        override fun onSeekTo(position: Long) { events += "seek:$position" }
    }
}
