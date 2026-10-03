/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.source

import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.root.timeline.TimelineContentPolicy
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import org.junit.Assert.*
import org.junit.Test

class SuperLyricContentAdapterTest {
    private val sink = RecordingSink()
    private val adapter = SuperLyricContentAdapter()

    @Test
    fun `full payload without current index publishes every row and its own translation`() {
        adapter.publish(PLAYER, full(), sink)

        val content = sink.contents.single()
        assertEquals("superlyric", content.sourceId)
        assertEquals(PLAYER, content.track?.packageName)
        assertNull(content.track?.mediaId)
        assertEquals("fingerprint", content.song?.id)
        assertEquals(9000L, content.song?.duration)
        val lines = content.song!!.lyrics!!
        assertEquals(listOf("Hello", "World"), lines.map { it.text })
        assertEquals(listOf("你好", "世界"), lines.map { it.translation })
        assertEquals(listOf("ni hao", "shi jie"), lines.map { it.secondary })
        assertEquals(1000L, lines[0].words!![0].begin)
        assertEquals(2500L, lines[0].words!![0].end)
        assertTrue(sink.lines.isEmpty())
        assertTrue(sink.plain.isEmpty())
    }

    @Test
    fun `content matches media metadata without treating lyric fingerprint as media id`() {
        adapter.publish(PLAYER, full(), sink)
        val anchor = TrackIdentity(PLAYER, "different-media-id", "Song", "Artist", "Album")
        val content = sink.contents.single()
        assertEquals(TimelineContentPolicy.Decision.APPLY,
            TimelineContentPolicy.decide("superlyric", anchor, content))
        assertEquals(TimelineContentPolicy.Decision.DROP_WRONG_TRACK,
            TimelineContentPolicy.decide("superlyric", anchor.copy(title = "Next"), content))
        assertEquals(TimelineContentPolicy.Decision.DROP_INACTIVE_SOURCE,
            TimelineContentPolicy.decide("lyricon", anchor, content))
    }

    @Test
    fun `index and position deltas never republish full content or drive a second clock`() {
        adapter.publish(PLAYER, full(), sink)
        repeat(20) {
            adapter.publish(PLAYER, delta().setCurrentLyricIndex(it % 2).setPosition(it * 200L), sink)
        }
        assertEquals(1, sink.contents.size)
        assertTrue(sink.lines.isEmpty())
        assertTrue(sink.positions.isEmpty())
    }

    @Test
    fun `consumer mutations do not invalidate content deduplication`() {
        adapter.publish(PLAYER, full(), sink)
        sink.contents.single().song!!.lyrics!![0].translation = "consumer translation"
        adapter.publish(PLAYER, delta(), sink)
        assertEquals(1, sink.contents.size)
    }

    @Test
    fun `top level current translation never leaks into other full rows`() {
        val translated = SuperLyricLine("译文", arrayOf(SuperLyricWord("译文", 1000L, 2500L)), 1000L, 3000L)
        adapter.publish(PLAYER, full().setCurrentLyricIndex(0).setTranslation(translated), sink)
        val lines = sink.contents.single().song!!.lyrics!!
        assertEquals("译文", lines[0].translation)
        assertEquals("世界", lines[1].translation)
        assertEquals("译文", lines[0].translationWords!!.single().text)
    }

    @Test
    fun `late full lyrics and late row translation each refresh content`() {
        adapter.publish(PLAYER, delta().setTitle("Song").setLyric(SuperLyricLine("Hello", 1000L, 3000L)), sink)
        assertEquals(1, sink.lines.size)
        adapter.publish(PLAYER, full(), sink)
        adapter.publish(PLAYER, delta().setCurrentLyricIndex(1).setTranslation(SuperLyricLine("新翻译")), sink)
        assertEquals(2, sink.contents.size)
        assertEquals("新翻译", sink.contents.last().song!!.lyrics!![1].translation)
        assertEquals(1, sink.lines.size)
    }

    @Test
    fun `full lyrics wait for late song metadata instead of borrowing active media identity`() {
        adapter.publish(PLAYER, full().setTitle(null).setArtist(null).setAlbum(null), sink)
        assertTrue(sink.contents.isEmpty())
        adapter.publish(PLAYER, delta().setTitle("Song").setArtist("Artist"), sink)
        assertEquals("Song", sink.contents.single().song!!.name)
        assertEquals(2, sink.contents.single().song!!.lyrics!!.size)
    }

    @Test
    fun `a separately parcelled current line can enrich its full row without an index`() {
        val packet = full().setLyric(SuperLyricLine("Hello", 1000L, 3000L))
            .setTranslation(SuperLyricLine("独立当前行翻译"))
        adapter.publish(PLAYER, packet, sink)
        assertEquals("独立当前行翻译", sink.contents.single().song!!.lyrics!![0].translation)
        assertEquals("世界", sink.contents.single().song!!.lyrics!![1].translation)
    }

    @Test
    fun `replacement translation clears word timings belonging to old text`() {
        adapter.publish(PLAYER, full().setCurrentLyricIndex(0).setTranslation(
            SuperLyricLine("旧译文", arrayOf(SuperLyricWord("旧译文", 1000L, 3000L)), 1000L, 3000L)
        ), sink)
        adapter.publish(PLAYER, delta().setTranslation(SuperLyricLine("新译文")), sink)
        val line = sink.contents.last().song!!.lyrics!![0]
        assertEquals("新译文", line.translation)
        assertNull(line.translationWords)
    }

    @Test
    fun `changed full snapshot is published even with the same lyric id`() {
        adapter.publish(PLAYER, full(), sink)
        adapter.publish(PLAYER, full().apply { allLyrics!![1].setTranslation("修订") }, sink)
        assertEquals(2, sink.contents.size)
        assertEquals("修订", sink.contents.last().song!!.lyrics!![1].translation)
    }

    @Test
    fun `pause and delta resume republish the retained full song`() {
        adapter.publish(PLAYER, full(), sink)
        assertTrue(adapter.onStop(PLAYER))
        adapter.publish(PLAYER, delta(), sink)
        assertEquals(2, sink.contents.size)
        assertEquals(2, sink.contents.last().song!!.lyrics!!.size)
    }

    @Test
    fun `late stop from another publisher does not clear current content`() {
        adapter.publish(PLAYER, full(), sink)
        assertFalse(adapter.onStop("other.player"))
        adapter.publish(PLAYER, delta(), sink)
        assertEquals(1, sink.contents.size)
    }

    @Test
    fun `new song with only a legacy line does not inherit previous full lyrics or metadata`() {
        adapter.publish(PLAYER, full(), sink)
        adapter.publish(PLAYER, SuperLyricData().setLyricId("next").setTitle("Next")
            .setLyric(SuperLyricLine("New line", 100L, 2000L)), sink)
        assertEquals(1, sink.contents.size)
        assertEquals("New line", sink.lines.single().text)
        assertEquals(listOf("Next", null, null, PLAYER), sink.metadata.last())
    }

    @Test
    fun `publisher changes cannot reuse a coincidentally identical lyric id`() {
        adapter.publish(PLAYER, full(), sink)
        adapter.publish("other.player", delta().setLyric(SuperLyricLine("Other", 100L, 1000L)), sink)
        assertEquals(1, sink.contents.size)
        assertEquals("Other", sink.lines.single().text)
        assertNull(sink.metadata.last()[1])
    }

    @Test
    fun `cold delta recovers matching full lyrics once`() {
        var calls = 0
        val recovering = SuperLyricContentAdapter { calls++; full() }
        recovering.publish(PLAYER, delta(), sink)
        recovering.publish(PLAYER, delta().setPosition(1500L), sink)
        assertEquals(1, calls)
        assertEquals(1, sink.contents.size)
        assertEquals("Song", sink.contents.single().song!!.name)
    }

    @Test
    fun `recovery rejects another song even when titles match`() {
        var calls = 0
        val recovering = SuperLyricContentAdapter { calls++; full().setLyricId("wrong") }
        repeat(5) { recovering.publish(PLAYER, delta().setTitle("Song"), sink) }
        assertTrue(sink.contents.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun `late identifying metadata permits recovery after an anonymous delta`() {
        var calls = 0
        val recovering = SuperLyricContentAdapter { calls++; full() }
        recovering.publish(PLAYER, SuperLyricData().setCurrentLyricIndex(0), sink)
        assertTrue(sink.contents.isEmpty())
        recovering.publish(PLAYER, SuperLyricData().setCurrentLyricIndex(0)
            .setTitle("Song").setArtist("Artist"), sink)
        assertEquals(2, calls)
        assertEquals(2, sink.contents.single().song!!.lyrics!!.size)
    }

    @Test
    fun `clear discards full snapshot and permits a fresh recovery attempt`() {
        var calls = 0
        val recovering = SuperLyricContentAdapter { calls++; null }
        recovering.publish(PLAYER, delta(), sink)
        recovering.publish(PLAYER, full(), sink)
        recovering.clear()
        recovering.publish(PLAYER, delta(), sink)
        assertEquals(2, calls)
        assertEquals(1, sink.contents.size)
    }

    @Test
    fun `legacy timed and plain lines retain translation and word timing`() {
        val data = SuperLyricData().setLyric(SuperLyricLine("Hello",
            arrayOf(SuperLyricWord("Hello", 1000L, 2500L)), 1000L, 3000L))
            .setTranslation(SuperLyricLine("你好"))
            .setSecondary(SuperLyricLine("ni hao"))
        adapter.publish(PLAYER, data, sink)
        assertEquals("你好", sink.lines.single().translation)
        assertEquals("ni hao", sink.lines.single().secondary)
        assertEquals(2500L, sink.lines.single().words!!.single().end)
        adapter.publish(PLAYER, SuperLyricData().setLyric(SuperLyricLine("Text"))
            .setTranslation(SuperLyricLine("文本")), sink)
        assertEquals("Text\n文本", sink.plain.single())
        assertTrue(sink.contents.isEmpty())
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy delay reads current playback position only when needed`() {
        adapter.publish(PLAYER, SuperLyricData().setLyric(SuperLyricLine("Delayed", 1500L)), sink) { 5000L }
        assertEquals(5000L, sink.lines.single().begin)
        assertEquals(6500L, sink.lines.single().end)
    }

    private fun delta() = SuperLyricData().setLyricId("fingerprint").setCurrentLyricIndex(0)

    private fun full() = SuperLyricData()
        .setLyricId("fingerprint").setTitle("Song").setArtist("Artist").setAlbum("Album").setDuration(9000L)
        .setAllLyrics(arrayOf(
            SuperLyricLine("Hello", arrayOf(SuperLyricWord("Hello", 1000L, 2500L)), "你好", "ni hao", 1000L, 3000L),
            SuperLyricLine("World", null, "世界", "shi jie", 4000L, 6000L),
        ))

    private class RecordingSink : LyricSink {
        val contents = mutableListOf<TimelineContent>()
        val lines = mutableListOf<RichLyricLine>()
        val plain = mutableListOf<String?>()
        val metadata = mutableListOf<List<String?>>()
        val positions = mutableListOf<Long>()
        override fun onTimelineContent(content: TimelineContent) { contents += content }
        override fun onLyricLine(line: Any?) { lines += line as RichLyricLine }
        override fun onPlainText(text: String?) { plain += text }
        override fun onMetadata(title: String?, artist: String?, album: String?, publisher: String?) {
            metadata += listOf(title, artist, album, publisher)
        }
        override fun onPositionChanged(position: Long) { positions += position }
        override fun onSongChanged(song: Any?) = error("Full songs must enter the unified timeline")
        override fun onStop() = Unit
        override fun onPlaybackStateChanged(isPlaying: Boolean) = Unit
    }

    private companion object { const val PLAYER = "test.player" }
}
