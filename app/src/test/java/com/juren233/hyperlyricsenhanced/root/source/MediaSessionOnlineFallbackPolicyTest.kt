package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences.Settings
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import org.junit.Assert.*
import org.junit.Test

class MediaSessionOnlineFallbackPolicyTest {
    private val track = TrackIdentity("org.chromium.webapk.example", title = "Song", artist = "Artist", durationMs = 180_000)
    private val settings = Settings(packages = setOf(track.packageName, "com.android.chrome", "org.chromium.webapk.other", "native.player"))

    @Test fun `only explicitly selected browser PWA and native packages are eligible`() {
        listOf(track.packageName, "com.android.chrome", "org.chromium.webapk.other", "native.player").forEach { pkg ->
            assertTrue(pkg, MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(packageName = pkg), true))
        }
    }

    @Test fun `unselected packages and package prefixes are rejected`() {
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(packageName = "another.player"), true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings.copy(packages = setOf("org.chromium.webapk.*")), track, true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings.copy(packages = emptySet()), track, true))
    }

    @Test fun `empty settings and invalid sessions are ineligible`() {
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(Settings(), track, true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(packageName = " "), true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track, false))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, null, true))
    }

    @Test fun `placeholder and unrecovered polluted metadata are ineligible`() {
        for (title in listOf("", " ", "Playing~", "Unknown", "Unknown Song", "未知歌曲", "Loading...", "Playing…", "---")) {
            assertFalse(title, MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(title = title), true))
        }
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(artist = "Unknown artist"), true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(durationMs = 0), true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(polluted = true), true))
    }

    @Test fun `reused browser media id cannot hide a changed song`() {
        val identified = track.copy(mediaId = "fixed-id")
        assertFalse(MediaSessionOnlineFallbackPolicy.sameLookupTrack(identified, identified.copy(title = "Other")))
        assertTrue(MediaSessionOnlineFallbackPolicy.sameLookupTrack(identified, identified.copy(album = "New album", durationMs = 180_001)))
        assertFalse(MediaSessionOnlineFallbackPolicy.sameLookupTrack(identified, identified.copy(durationMs = 200_000)))
    }

    @Test fun `line to word sources follow the configured order and leave Apple Music to its own setting`() {
        val sources = listOf(Source.NE, Source.AMLL, Source.QM, Source.LB)
        val spotify = TrackIdentity(
            "com.spotify.music", mediaId = "spotify:track:70pVCVMGjmIWPbWXDwf11e",
            title = "petal", artist = "Ariana Grande", durationMs = 180_000,
        )
        val off = Settings(sources, setOf(spotify.packageName))
        val on = off.copy(lineToWord = true)
        assertEquals(listOf(Source.AMLL, Source.LB), MediaSessionOnlineFallbackPolicy.lineToWordSources(off, spotify))
        assertEquals(sources, MediaSessionOnlineFallbackPolicy.lineToWordSources(on, spotify))
        val unaddressable = spotify.copy(packageName = "web.player", mediaId = "a")
        assertEquals(listOf(Source.NE, Source.QM), MediaSessionOnlineFallbackPolicy.lineToWordSources(on, unaddressable))
        val apple = spotify.copy(packageName = "com.apple.android.music", mediaId = "x")
        assertTrue(
            MediaSessionOnlineFallbackPolicy.lineToWordSources(on, apple).all(Source::isTtmlCatalog),
        )
    }
}
