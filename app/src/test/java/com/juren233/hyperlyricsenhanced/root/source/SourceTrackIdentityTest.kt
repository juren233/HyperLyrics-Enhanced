package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test

class SourceTrackIdentityTest {
    private fun song(id: String?, title: String, duration: Long) = Song(id=id, name=title, artist="Artist", duration=duration, lyrics=emptyList())
    @Test fun `legacy matcher keeps id and metadata semantics`() {
        assertTrue(SourceTrackIdentity.of(song("id", "Title", 1000)).matches(SourceTrackIdentity.of(song("id", "Other", 9000))))
        assertTrue(SourceTrackIdentity.of(song(null, " Title ", 1000)).matches(SourceTrackIdentity.of(song(null, "title", 2500))))
        assertFalse(SourceTrackIdentity.of(song(null, "title", 1000)).matches(SourceTrackIdentity.of(song(null, "other", 1000))))
    }

    @Test fun `third party policy preserves priority rules`() {
        assertTrue(ThirdPartySongUpdatePolicy.preserveOnline(true, false, false, false, true, true, true, true))
        assertFalse(ThirdPartySongUpdatePolicy.preserveOnline(true, false, false, false, true, true, false, true))
        assertTrue(ThirdPartySongUpdatePolicy.preserveOnline(true, true, true, false, false, false, false, true))
    }

    @Test
    fun `waiting salt fallback takes the latest report of the same track`() {
        assertTrue(ThirdPartySongUpdatePolicy.refreshPendingFallbackQuery(
            sameTrack = true, sameContent = false, fallbackDelayed = true,
        ))
        // 内容完全一样没有可更新的；查询已开始或换了歌都不替换。
        assertFalse(ThirdPartySongUpdatePolicy.refreshPendingFallbackQuery(
            sameTrack = true, sameContent = true, fallbackDelayed = true,
        ))
        assertFalse(ThirdPartySongUpdatePolicy.refreshPendingFallbackQuery(
            sameTrack = true, sameContent = false, fallbackDelayed = false,
        ))
        assertFalse(ThirdPartySongUpdatePolicy.refreshPendingFallbackQuery(
            sameTrack = false, sameContent = false, fallbackDelayed = true,
        ))
    }
}
