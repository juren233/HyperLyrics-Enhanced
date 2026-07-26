package com.juren233.hyperlyricsenhanced.service.source

import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaSongIdentityTest {

    @Test
    fun `song identity changes when only title changes`() {
        val first = MediaSongIdentity.build(
            packageName = "com.apple.android.music",
            title = "First song",
            artist = "Artist",
            album = "Album",
            duration = 0L,
        )
        val second = MediaSongIdentity.build(
            packageName = "com.apple.android.music",
            title = "Second song",
            artist = "Artist",
            album = "Album",
            duration = 0L,
        )

        assertNotEquals(first, second)
    }
}
