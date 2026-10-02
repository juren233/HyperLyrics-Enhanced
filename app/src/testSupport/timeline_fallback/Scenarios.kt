/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
import com.juren233.hyperlyricsenhanced.lyric.model.*
import com.juren233.hyperlyricsenhanced.lyric.source.*
import com.juren233.hyperlyricsenhanced.root.*
import com.juren233.hyperlyricsenhanced.root.timeline.*
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity

private class Sink : LyricSink {
    var playing = false
    var title: String? = null
    var publisher: String? = null
    val events = mutableListOf<String>()
    override fun onSongChanged(song: Any?) { events += "song" }
    override fun onStop() {
        playing = false; title = null; publisher = null
        LyriconDataBridge.currentSong = null
        events += "stop"
    }
    override fun onMetadata(title: String?, artist: String?, album: String?, publisher: String?) {
        this.title = title; this.publisher = publisher; events += "metadata"
    }
    override fun onTrackTransition(title: String?, artist: String?, album: String?, publisher: String?) {
        LyriconDataBridge.currentSong = null
        onMetadata(title, artist, album, publisher)
    }
    override fun onPlaybackStateChanged(isPlaying: Boolean) { playing = isPlaying; events += "playing=$isPlaying" }
    override fun currentPlaybackState() = playing
    override fun onPositionChanged(position: Long) = Unit
    override fun onLyricLine(line: Any?) = Unit
    override fun onPlainText(text: String?) = Unit
}

private class Fixture {
    val anchor = SystemMediaPlaybackAnchor()
    val sink = Sink()
    val driver = LocalTimelineDriver(anchor, sink)
    init { SystemUiEnhancementGate.enabled = true; LyriconDataBridge.currentSong = null; driver.onSourceSelected("lyricon") }
    fun track(pkg: String = "cn.kuwo.player", id: String = "a") = TrackIdentity(pkg, id, "title-$id")
    fun select(track: TrackIdentity?, playing: Boolean) {
        anchor.currentTrack = track
        driver.onTrackChanged(track)
        play(playing)
    }
    fun play(playing: Boolean) {
        anchor.state = SystemMediaPlaybackAnchor.Anchor(playing)
        driver.onPlaybackStateChanged(playing)
    }
    fun content(track: TrackIdentity, lyrics: Boolean, source: String = "lyricon") {
        driver.onTimelineContent(TimelineContent(source, track, Song(track.mediaId!!, track.title,
            lyrics = if (lyrics) listOf(RichLyricLine(0, 3000, 3000, "lyric")) else emptyList())))
    }
    fun bound(track: TrackIdentity, playing: Boolean) {
        check(sink.title == track.title) { "title expected ${track.title}, got ${sink.title}" }
        check(sink.publisher == track.packageName)
        check(sink.playing == playing) { "playback expected $playing, got ${sink.playing}; ${sink.events}" }
        check(LocalTimelineDriver.appliedTrackKeyForDiag == track.normalizedKey()) { "fallback track was not bound" }
    }
}

fun main() {
    var count = 0
    fun scenario(name: String, block: Fixture.() -> Unit) {
        val f = Fixture()
        try { f.block(); println("PASS $name"); count++ } finally { f.driver.stop() }
    }
    scenario("cold empty payload with playback arriving first") {
        val t = track(); anchor.currentTrack = t; play(true); content(t, false); bound(t, true)
        check(sink.events.indexOfLast { it == "playing=true" } > sink.events.indexOfLast { it == "stop" })
    }
    scenario("track-only fallback without any Provider song") {
        val t = track(); select(t, true); bound(t, true); check(LyriconDataBridge.currentSong == null)
    }
    scenario("paused empty song resumes and pauses without lyrics") {
        val t = track(); select(t, false); content(t, false); bound(t, false)
        play(true); bound(t, true); play(false); bound(t, false)
    }
    scenario("empty revision preserves existing lyrics") {
        val t = track(); select(t, true); content(t, true)
        val song = LyriconDataBridge.currentSong
        content(t, false); bound(t, true); check(LyriconDataBridge.currentSong === song)
    }
    scenario("same player next empty song then late lyrics") {
        val first = track(); select(first, true); content(first, true)
        val next = track(id = "b"); select(next, true); content(next, false); bound(next, true)
        check(LyriconDataBridge.currentSong == null)
        content(next, true); check(LyriconDataBridge.currentSong?.id == "b"); bound(next, true)
    }
    scenario("Kuwo to MT to NetEase then Kuwo cached replay") {
        val kuwo = track(); select(kuwo, true); content(kuwo, true)
        select(track("bin.mt.plus", "file"), true)
        check(sink.title == null && !sink.playing)
        val netease = track("com.netease.cloudmusic", "n"); select(netease, true); bound(netease, true)
        check(LyriconDataBridge.currentSong == null)
        content(kuwo, true); bound(netease, true); check(LyriconDataBridge.currentSong == null)
        content(netease, true); check(LyriconDataBridge.currentSong?.id == "n")
        select(kuwo, true); bound(kuwo, true); check(LyriconDataBridge.currentSong?.id == "a")
    }
    scenario("stopped source cannot be resumed by later playback or content") {
        val t = track(); select(t, true); driver.onSourceStopped("lyricon"); play(true); content(t, true)
        check(!sink.playing && sink.title == null && LocalTimelineDriver.appliedTrackKeyForDiag == null)
    }
    scenario("disabled runtime does not publish a fallback") {
        SystemUiEnhancementGate.enabled = false
        val t = track(); select(t, true); content(t, false)
        check(sink.title == null && !sink.playing)
    }
    scenario("cleared session cannot use an earlier playing hint") {
        val t = track(); select(t, true); driver.onSourcePlaybackHint(true, t.packageName)
        select(null, false); check(!sink.playing && sink.title == null)
    }
    println("$count timeline binding scenarios passed (JVM boundaries; no device/visual acceptance)")
}
