/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.media

import com.juren233.hyperlyricsenhanced.common.media.SanitizedTrack.Resolution
import com.juren233.hyperlyricsenhanced.common.media.SanitizedTrack.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 夹具来自 2026-10-26 Salt Player 真机取证：会话标题逐句漂移为歌词行，歌手字段是
 * 「歌手 - 歌名」，专辑不变，通知标题是真实歌名。时长未取到，使用固定夹具值。
 */
class MediaSessionMetadataSanitizerTest {
    private val sanitizer = MediaSessionMetadataSanitizer()

    // ---- Salt Player 真机形态 ----

    @Test
    fun `notification anchor recovers Salt Player title and artist on the first sample`() {
        val result = sanitizer.sanitize(saltLine(0), anchors = listOf(notificationAnchor))

        assertEquals(Status.RECOVERED, result.status)
        assertEquals(Resolution.ANCHOR, result.resolvedBy)
        assertEquals("跟佢做个 FRIEND", result.title)
        assertEquals("许冠杰", result.artist)
    }

    @Test
    fun `display text replaces the raw session text only when recovered`() {
        val recovered = sanitizer.sanitize(saltLine(0), anchors = listOf(notificationAnchor))
        assertEquals("跟佢做个 FRIEND", recovered.titleOr(SALT_LINES[0]))
        assertEquals("许冠杰", recovered.artistOr(SALT_ARTIST))

        // 未恢复（原始曲目、证据不足、顺序未定）一律沿用原始文本，绝不回填歌词行或猜测顺序。
        val clean = MediaSessionMetadataSanitizer().sanitize(saltLine(0))
        assertEquals(Status.CLEAN, clean.status)
        assertEquals(SALT_LINES[0], clean.titleOr(SALT_LINES[0]))
        assertEquals(SALT_ARTIST, clean.artistOr(SALT_ARTIST))

        val undecided = MediaSessionMetadataSanitizer().also { s -> repeat(2) { s.sanitize(saltLine(it)) } }
            .sanitize(saltLine(2))
        assertEquals(Status.UNRESOLVED, undecided.status)
        assertEquals(SALT_LINES[2], undecided.titleOr(SALT_LINES[2]))
        assertEquals(SALT_ARTIST, undecided.artistOr(SALT_ARTIST))
    }

    @Test
    fun `identity text is constant across every lyric line`() {
        val results = SALT_LINES.indices.map {
            sanitizer.sanitize(saltLine(it), anchors = listOf(notificationAnchor))
        }

        assertEquals(1, results.map { it.identityTitle to it.identityArtist }.toSet().size)
        assertEquals("", results.first().identityTitle)
        assertEquals(SALT_ARTIST, results.first().identityArtist)
        assertEquals(setOf("跟佢做个 FRIEND"), results.map { it.title }.toSet())
    }

    @Test
    fun `without anchors the first samples stay raw and two title changes prove drift`() {
        val first = sanitizer.sanitize(saltLine(0))
        val second = sanitizer.sanitize(saltLine(1))
        val third = sanitizer.sanitize(saltLine(2))

        assertEquals(Status.CLEAN, first.status)
        assertEquals(Status.CLEAN, second.status)
        assertEquals(Status.UNRESOLVED, third.status)
        // 无法确定顺序时不回填歌词行，也不猜顺序。
        assertNull(third.title)
        assertNull(third.artist)
        assertEquals(
            listOf(
                TitleArtist(title = "许冠杰", artist = "跟佢做个 FRIEND"),
                TitleArtist(title = "跟佢做个 FRIEND", artist = "许冠杰"),
            ),
            third.candidates,
        )
    }

    @Test
    fun `album artist decides the order without anchors`() {
        repeat(2) { sanitizer.sanitize(saltLine(it, albumArtist = "许冠杰")) }
        val result = sanitizer.sanitize(saltLine(2, albumArtist = "许冠杰"))

        assertEquals(Status.RECOVERED, result.status)
        assertEquals(Resolution.ARTIST_SPLIT, result.resolvedBy)
        assertEquals("跟佢做个 FRIEND", result.title)
        assertEquals("许冠杰", result.artist)
    }

    @Test
    fun `order learned from an anchored track is reused on the next track without anchors`() {
        sanitizer.sanitize(saltLine(0), anchors = listOf(notificationAnchor))

        val nextTrack = { index: Int ->
            sample(
                title = "lyric line $index",
                artist = "Taylor Swift - Glitch",
                album = "Midnights",
                durationMs = 128_000L,
                positionMs = 20_000L + index * 1_500L,
            )
        }
        sanitizer.sanitize(nextTrack(0))
        sanitizer.sanitize(nextTrack(1))
        val result = sanitizer.sanitize(nextTrack(2))

        assertEquals(Status.RECOVERED, result.status)
        assertEquals("Glitch", result.title)
        assertEquals("Taylor Swift", result.artist)
    }

    // ---- 小米音乐形态与连字符歌手 ----

    @Test
    fun `provider anchor recovers the title-dash-artist form`() {
        val result = sanitizer.sanitize(
            sample(
                packageName = XIAOMI,
                title = "You cut me open",
                artist = "Love Somebody-LAUV",
            ),
            anchors = listOf(TrackAnchor(AnchorSource.PROVIDER, title = "Love Somebody", artist = "LAUV")),
        )

        assertEquals(Status.RECOVERED, result.status)
        assertEquals("Love Somebody", result.title)
        assertEquals("LAUV", result.artist)
    }

    @Test
    fun `anchor picks the right dash when the artist name itself contains a hyphen`() {
        val result = sanitizer.sanitize(
            sample(packageName = XIAOMI, title = "lyric", artist = "Love Somebody-Jay-Z"),
            anchors = listOf(TrackAnchor(AnchorSource.NOTIFICATION, title = "Love Somebody")),
        )

        assertEquals("Love Somebody", result.title)
        assertEquals("Jay-Z", result.artist)
    }

    @Test
    fun `an ambiguous hyphenated split is reported as candidates instead of guessed`() {
        val lines = listOf("line one", "line two", "line three")
        val results = lines.mapIndexed { index, line ->
            sanitizer.sanitize(
                sample(
                    packageName = XIAOMI,
                    title = line,
                    artist = "Love Somebody-Jay-Z",
                    positionMs = 50_000L + index * 4_000L,
                ),
            )
        }

        val last = results.last()
        assertEquals(Status.UNRESOLVED, last.status)
        assertEquals(
            listOf(
                TitleArtist("Love Somebody", "Jay-Z"),
                TitleArtist("Jay-Z", "Love Somebody"),
                TitleArtist("Love Somebody-Jay", "Z"),
                TitleArtist("Z", "Love Somebody-Jay"),
            ),
            last.candidates,
        )
    }

    @Test
    fun `provider anchor outranks notification anchor regardless of list order`() {
        val result = sanitizer.sanitize(
            sample(title = "lyric", artist = "Real-Artist"),
            anchors = listOf(
                TrackAnchor(AnchorSource.NOTIFICATION, title = "Artist"),
                TrackAnchor(AnchorSource.PROVIDER, title = "Real"),
            ),
        )

        assertEquals("Real", result.title)
        assertEquals("Artist", result.artist)
    }

    // ---- 不得误伤 ----

    @Test
    fun `clean metadata passes through untouched even with hyphenated artists`() {
        listOf("A-Lin" to "Sunshine", "Jay-Z" to "Run This Town").forEach { (artist, title) ->
            val result = sanitizer.sanitize(
                sample(title = title, artist = artist),
                anchors = listOf(TrackAnchor(AnchorSource.NOTIFICATION, title = "Unrelated")),
            )

            assertEquals(Status.CLEAN, result.status)
            assertEquals(Resolution.RAW, result.resolvedBy)
            assertEquals(title, result.title)
            assertEquals(artist, result.artist)
            assertEquals(title, result.identityTitle)
            assertEquals(artist, result.identityArtist)
        }
    }

    @Test
    fun `an anchor alone never rewrites clean metadata`() {
        // Apple Music 原名恢复：会话标题是原名，通知/Provider 是本地化名，二者合法不一致。
        val result = sanitizer.sanitize(
            sample(title = "原名", artist = "Artist"),
            anchors = listOf(TrackAnchor(AnchorSource.PROVIDER, title = "Localized", artist = "Localized Artist")),
        )

        assertEquals(Status.CLEAN, result.status)
        assertEquals("原名", result.title)
        assertEquals("Artist", result.artist)
    }

    @Test
    fun `a stable title equal to a lyric line is not pollution`() {
        // 副歌恰好等于歌名：「跟佢做个Friend」规范化后与真实歌名相同。
        repeat(5) {
            val result = sanitizer.sanitize(
                sample(title = "跟佢做个 FRIEND", artist = "许冠杰", positionMs = 100_000L + it * 1_000L),
                activeLyricLines = listOf("跟佢做个Friend"),
            )
            assertEquals(Status.CLEAN, result.status)
        }
    }

    @Test
    fun `a single placeholder to real title update is not drift`() {
        sanitizer.sanitize(sample(title = "Loading", artist = "Artist", positionMs = 500L))
        val result = sanitizer.sanitize(sample(title = "Real Song", artist = "Artist", positionMs = 900L))

        assertEquals(Status.CLEAN, result.status)
        assertEquals("Real Song", result.title)
    }

    @Test
    fun `unknown duration never produces drift evidence`() {
        val results = (0..4).map {
            sanitizer.sanitize(sample(title = "line $it", artist = "Artist", durationMs = 0L, positionMs = 50_000L + it))
        }

        assertTrue(results.all { it.status == Status.CLEAN })
    }

    // ---- 漂移与歌词行证据 ----

    @Test
    fun `a title that changes into the active lyric line is drift immediately and the track start title is kept`() {
        sanitizer.sanitize(sample(title = "Real Song", artist = "Artist", positionMs = 1_000L))
        val result = sanitizer.sanitize(
            sample(title = "Some lyric line", artist = "Artist", positionMs = 6_000L),
            activeLyricLines = listOf("Some lyric line"),
        )

        assertEquals(Status.RECOVERED, result.status)
        assertEquals(Resolution.BASELINE_TITLE, result.resolvedBy)
        assertEquals("Real Song", result.title)
        assertEquals("Artist", result.artist)
    }

    @Test
    fun `a cold start in the middle of a song has no baseline title`() {
        val results = (0..2).map {
            sanitizer.sanitize(sample(title = "line $it", artist = "Artist", positionMs = 120_000L + it * 3_000L))
        }

        val last = results.last()
        assertEquals(Status.UNRESOLVED, last.status)
        assertEquals(Resolution.NONE, last.resolvedBy)
        assertNull(last.title)
        assertEquals("Artist", last.artist)
    }

    @Test
    fun `seeking backwards inside a polluted track keeps it polluted`() {
        sanitizer.sanitize(sample(title = "line 0", artist = "Artist", positionMs = 100_000L, sampledAtMs = 0L))
        sanitizer.sanitize(sample(title = "line 1", artist = "Artist", positionMs = 103_000L, sampledAtMs = 3_000L))
        val result = sanitizer.sanitize(
            sample(title = "line 2", artist = "Artist", positionMs = 60_000L, sampledAtMs = 6_000L),
        )

        assertTrue(result.isPolluted)
    }

    // ---- 换曲判断 ----

    @Test
    fun `media id change starts a clean new track`() {
        repeat(3) {
            sanitizer.sanitize(sample(title = "line $it", artist = "Artist", mediaId = "m1", positionMs = 50_000L + it))
        }
        val result = sanitizer.sanitize(
            sample(
                title = "Fresh Song",
                artist = "Other",
                mediaId = "m2",
                album = "Other Album",
                durationMs = 210_000L,
                positionMs = 400L,
            ),
        )

        assertEquals(Status.CLEAN, result.status)
        assertEquals("Fresh Song", result.title)
    }

    @Test
    fun `position reset is a track boundary even when duration and album repeat`() {
        val titles = listOf("A" to 200_000L, "B" to 500L, "C" to 1_500L)
        val results = titles.mapIndexed { index, (title, position) ->
            sanitizer.sanitize(
                sample(title = title, artist = "Artist", positionMs = position, sampledAtMs = index * 1_000L),
            )
        }

        assertTrue(results.all { it.status == Status.CLEAN })
    }

    @Test
    fun `the same title changes without a position reset are drift`() {
        val results = listOf("A", "B", "C").mapIndexed { index, title ->
            sanitizer.sanitize(
                sample(title = title, artist = "Artist", positionMs = 200_000L + index * 1_000L, sampledAtMs = index * 1_000L),
            )
        }

        assertEquals(Status.UNRESOLVED, results.last().status)
    }

    // ---- 曲目编号：键不能被曲首的字段补全与改写带着走（2026-10-26 酷狗真机） ----

    private fun kugou(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        positionMs: Long,
        sampledAtMs: Long = positionMs,
    ) = sample(
        title = title,
        artist = artist,
        packageName = KUGOU,
        album = album,
        durationMs = durationMs,
        positionMs = positionMs,
        sampledAtMs = sampledAtMs,
    )

    @Test
    fun `track token survives the kugou start-of-track mutations`() {
        // 01:59:12.086 起播 → .099 时长取整 → .330 专辑补全 → 13.485 歌手被拼成「歌手-歌名」→ 随后歌词行。
        val tokens = listOf(
            kugou("Song", "Walters", "", 147_382L, 0L),
            kugou("Song", "Walters", "", 147_000L, 20L),
            kugou("Song", "Walters", "Song", 147_000L, 270L),
            kugou("Lyric line", "Walters-Song", "Song", 147_000L, 1_340L),
            kugou("Another line", "Walters-Song", "Song", 147_000L, 2_800L),
            kugou("Third line", "Walters-Song", "Song", 147_000L, 9_000L),
        ).map { sanitizer.sanitize(it).trackToken }

        assertEquals(1, tokens.toSet().size)
    }

    @Test
    fun `track token changes when the song changes`() {
        val first = sanitizer.sanitize(kugou("Song", "Walters", "Song", 147_000L, 60_000L)).trackToken

        val otherDuration = sanitizer.sanitize(
            kugou("Next", "BIGBANG", "MADE SERIES", 267_000L, 20_000L, sampledAtMs = 90_000L),
        ).trackToken
        // 时长相同、两侧专辑都非空且不同：换曲。
        val otherAlbum = sanitizer.sanitize(
            kugou("Third", "BIGBANG", "Other Album", 267_000L, 21_000L, sampledAtMs = 91_000L),
        ).trackToken
        // 专辑只是从空补全：同一首。
        val filled = sanitizer.sanitize(
            kugou("Fourth", "BIGBANG", "", 267_000L, 22_000L, sampledAtMs = 92_000L),
        ).trackToken

        assertEquals(3, setOf(first, otherDuration, otherAlbum).size)
        assertEquals(otherAlbum, filled)
    }

    @Test
    fun `a media id change starts a new token and keeps it stable afterwards`() {
        val first = sanitizer.sanitize(sample(title = "A", artist = "X", mediaId = "m1")).trackToken
        val same = sanitizer.sanitize(sample(title = "B", artist = "X", mediaId = "m1")).trackToken
        val next = sanitizer.sanitize(sample(title = "C", artist = "X", mediaId = "m2")).trackToken

        assertEquals(first, same)
        assertFalse(first == next)
    }

    @Test
    fun `restarting the same song keeps the token but a different artist at the start is a new track`() {
        val playing = sanitizer.sanitize(kugou("Song", "Walters", "Album", 147_000L, 100_000L, sampledAtMs = 0L))
        // 单曲循环或拖回开头：歌手与专辑都没变，已加载的歌词仍然有效。
        val restarted = sanitizer.sanitize(kugou("Song", "Walters", "Album", 147_000L, 400L, sampledAtMs = 50_000L))
        sanitizer.sanitize(kugou("Song", "Walters", "Album", 147_000L, 60_000L, sampledAtMs = 110_000L))
        // 同时长同专辑、歌手不同的下一首：进度归零，换曲。
        val another = sanitizer.sanitize(kugou("Other", "Someone", "Album", 147_000L, 300L, sampledAtMs = 111_000L))

        assertEquals(playing.trackToken, restarted.trackToken)
        assertFalse(playing.trackToken == another.trackToken)
    }

    // ---- md3music 快速切歌（2026-11-17 真机）：切歌时先发上一首的专辑与时长，再发空元数据 ----

    private fun md3(
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        positionMs: Long,
        sampledAtMs: Long,
    ) = sample(
        title = title,
        artist = artist,
        packageName = MD3MUSIC,
        album = album,
        durationMs = durationMs,
        positionMs = positionMs,
        sampledAtMs = sampledAtMs,
    )

    @Test
    fun `md3music quick skips get a new token for every song and are never treated as drift`() {
        val previous = sanitizer.sanitize(md3("多远都要在一起", "G.E.M.邓紫棋", "新的心跳", 217_756L, 120_000L, 0L))
        // 22:53:40.573 起：上一首播了约 120 秒，下一首《不该》。
        val buGai = listOf(
            md3("不该 (with aMEI)", "周杰伦、张惠妹", "新的心跳", 217_756L, 120_595L, 600L),
            md3(null, null, null, 0L, 0L, 720L),
            md3("不该 (with aMEI)", "周杰伦、张惠妹", null, 0L, 0L, 730L),
            md3("不该 (with aMEI)", "周杰伦、张惠妹", null, 291_448L, 0L, 880L),
            md3("不该 (with aMEI)", "周杰伦、张惠妹", "周杰伦的床边故事", 291_448L, 0L, 890L),
        ).map(sanitizer::sanitize).last()
        // 42.128 起：《不该》只播了 735 ms。
        val callOfSilence = listOf(
            md3("Call of Silence", "澤野弘之", "周杰伦的床边故事", 291_448L, 735L, 2_150L),
            md3(null, null, null, 0L, 0L, 2_290L),
            md3("Call of Silence", "澤野弘之", null, 0L, 0L, 2_350L),
            md3("Call of Silence", "澤野弘之", null, 178_259L, 0L, 2_560L),
            md3("Call of Silence", "澤野弘之", "TVアニメ「進撃の巨人」Season 2 オリジナルサウンドトラック", 178_259L, 0L, 2_580L),
        ).map(sanitizer::sanitize).last()
        // 44.690 起：《Call of Silence》只播了 1601 ms。
        val babyDontCry = listOf(
            md3("Baby, Don't Cry", "EXO", "TVアニメ「進撃の巨人」Season 2 オリジナルサウンドトラック", 178_259L, 1_601L, 4_710L),
            md3(null, null, null, 0L, 0L, 4_760L),
            md3("Baby, Don't Cry", "EXO", null, 0L, 0L, 4_830L),
            md3("Baby, Don't Cry", "EXO", null, 235_964L, 0L, 4_950L),
            md3("Baby, Don't Cry", "EXO", "The 1st Album XOXO (KISS&HUG)", 235_964L, 0L, 4_960L),
        ).map(sanitizer::sanitize).last()

        val tokens = listOf(previous, buGai, callOfSilence, babyDontCry).map { it.trackToken }
        assertEquals(4, tokens.toSet().size)
        assertEquals(Status.CLEAN, callOfSilence.status)
        assertEquals(Status.CLEAN, babyDontCry.status)
        assertEquals("Baby, Don't Cry", babyDontCry.title)
        assertEquals("Baby, Don't Cry", babyDontCry.identityTitle)
    }

    @Test
    fun `samples without duration or album neither split a song nor hide the next change`() {
        val start = sanitizer.sanitize(kugou("Song", "Walters", "Album", 147_000L, 10_000L)).trackToken
        // 同一首歌中途一份缺字段的元数据，之后字段原样回来：仍是同一首。
        val gap = sanitizer.sanitize(kugou("Song", "Walters", "", 0L, 11_000L)).trackToken
        val back = sanitizer.sanitize(kugou("Song", "Walters", "Album", 147_000L, 12_000L)).trackToken
        // 再经过一份缺字段的元数据后专辑变了：换曲。
        sanitizer.sanitize(kugou("Next", "Other", "", 0L, 13_000L))
        val next = sanitizer.sanitize(kugou("Next", "Other", "Other Album", 147_000L, 14_000L)).trackToken

        assertEquals(start, gap)
        assertEquals(start, back)
        assertFalse(start == next)
    }

    @Test
    fun `tokens are never reused after the sanitizer is reset`() {
        val before = sanitizer.sanitize(kugou("Song", "Walters", "Album", 147_000L, 10_000L)).trackToken
        sanitizer.reset()
        val after = sanitizer.sanitize(kugou("Song", "Walters", "Album", 147_000L, 11_000L)).trackToken

        assertFalse(before == after)
    }

    // ---- 标题带歌手后缀 ----

    @Test
    fun `title suffix naming the artist is stripped without drift evidence`() {
        val titleFirst = sanitizer.sanitize(sample(title = "天空没有极限-G.E.M.邓紫棋", artist = "G.E.M.邓紫棋"))
        val artistFirst = sanitizer.sanitize(
            sample(packageName = "com.other.player", title = "G.E.M.邓紫棋 - 天空没有极限", artist = "G.E.M.邓紫棋"),
        )

        listOf(titleFirst, artistFirst).forEach {
            assertEquals(Status.RECOVERED, it.status)
            assertEquals(Resolution.TITLE_SUFFIX, it.resolvedBy)
            assertEquals("天空没有极限", it.title)
            assertEquals("G.E.M.邓紫棋", it.artist)
            assertEquals("天空没有极限", it.identityTitle)
        }
    }

    // ---- 状态边界 ----

    @Test
    fun `sessions are isolated per package and evicted beyond the limit`() {
        val small = MediaSessionMetadataSanitizer(maxSessions = 1)
        repeat(3) { small.sanitize(sample(title = "line $it", artist = "Artist", positionMs = 50_000L + it)) }
        assertTrue(small.sanitize(sample(title = "line 3", artist = "Artist", positionMs = 50_003L)).isPolluted)

        small.sanitize(sample(packageName = "com.other.player", title = "x", artist = "y"))
        val afterEviction = small.sanitize(sample(title = "line 4", artist = "Artist", positionMs = 50_004L))

        assertFalse(afterEviction.isPolluted)
    }

    @Test
    fun `clear forgets one package`() {
        repeat(3) { sanitizer.sanitize(sample(title = "line $it", artist = "Artist", positionMs = 50_000L + it)) }

        sanitizer.clear(SALT)

        assertFalse(sanitizer.sanitize(sample(title = "line 3", artist = "Artist", positionMs = 50_003L)).isPolluted)
    }

    // ---- 锚点独立性与按需读取 ----

    @Test
    fun `an anchor that merely echoes the session title is not independent`() {
        // 部分 App 的通知由同一份被污染的元数据生成：锚点等于当前（或上一条）歌词行。
        var lastRawTitle: String? = null
        val results = (0..3).map { index ->
            val line = SALT_LINES[index]
            val echoed = lastRawTitle
            lastRawTitle = line
            sanitizer.sanitize(saltLine(index)) {
                listOfNotNull(
                    TrackAnchor(AnchorSource.NOTIFICATION, title = line),
                    echoed?.let { TrackAnchor(AnchorSource.PROVIDER, title = it) },
                )
            }
        }

        val last = results.last()
        assertEquals(Status.UNRESOLVED, last.status)
        assertNull(last.title)
        assertTrue(SALT_LINES.none { it == last.title })
    }

    @Test
    fun `anchors are only read when the sample could need them`() {
        var reads = 0
        val provider = { _: String -> reads++; emptyList<TrackAnchor>() }

        sanitizer.sanitize(sample(title = "Sunshine", artist = "Artist"), anchorProvider = provider)
        sanitizer.sanitize(sample(title = "Sunshine", artist = "Artist"), anchorProvider = provider)
        assertEquals(0, reads)

        sanitizer.sanitize(sample(title = "Sunshine", artist = "A - B"), anchorProvider = provider)
        assertEquals(1, reads)
    }

    @Test
    fun `combined artist explanation ignores order separators case and spacing`() {
        val explains = MediaSessionMetadataSanitizer::combinedExplains
        assertTrue(explains("许冠杰 - 跟佢做个 FRIEND", "跟佢做个Friend", "许冠杰"))
        assertTrue(explains("Love Somebody-LAUV", "Love Somebody", "LAUV"))
        assertFalse(explains("许冠杰 - 跟佢做个 FRIEND", "另一首歌", "许冠杰"))
        assertFalse(explains("许冠杰", "跟佢做个 FRIEND", "许冠杰"))
        assertFalse(explains(null, "Song", "Artist"))
        assertFalse(explains("Song-Artist", "", "Artist"))
    }

    // ---- 辅助函数 ----

    @Test
    fun `spaced separators win so hyphens inside names are not boundaries`() {
        assertEquals(
            listOf("A-Lin" to "Song"),
            MediaSessionMetadataSanitizer.splitCandidates("A-Lin - Song"),
        )
        assertEquals(
            listOf("Song" to "Artist"),
            MediaSessionMetadataSanitizer.splitCandidates("Song－Artist"),
        )
        assertTrue(MediaSessionMetadataSanitizer.splitCandidates("-Artist").isEmpty())
        assertTrue(MediaSessionMetadataSanitizer.splitCandidates("Plain").isEmpty())
    }

    @Test
    fun `match normalization ignores case spacing punctuation and width`() {
        assertEquals(
            MediaSessionMetadataSanitizer.normalizeForMatch("跟佢做个 FRIEND"),
            MediaSessionMetadataSanitizer.normalizeForMatch("跟佢做个Friend"),
        )
        assertEquals(
            MediaSessionMetadataSanitizer.normalizeForMatch("ＡＢＣ　１２３"),
            MediaSessionMetadataSanitizer.normalizeForMatch("abc 123"),
        )
        assertEquals("", MediaSessionMetadataSanitizer.normalizeForMatch(" -- "))
    }

    // ---- 歌词源自带的歌名歌手（SuperLyric / LyricInfo / Lyricon）作为锚点：必须与会话歌手串交叉验证 ----

    private fun lyricsSong(title: String, artist: String) =
        TrackAnchor(AnchorSource.LYRICS_SONG, title = title, artist = artist)

    @Test
    fun `lyrics song anchor recovers the artist-dash-title form`() {
        val result = sanitizer.sanitize(
            saltLine(0),
            anchors = listOf(lyricsSong("跟佢做个 FRIEND", "许冠杰")),
        )

        assertEquals(Status.RECOVERED, result.status)
        assertEquals("跟佢做个 FRIEND", result.title)
        assertEquals("许冠杰", result.artist)
        assertEquals(SanitizedTrack.Resolution.ANCHOR, result.resolvedBy)
    }

    @Test
    fun `lyrics song anchor handles both orders with a hyphenated artist`() {
        val titleFirst = sanitizer.sanitize(
            sample(packageName = XIAOMI, title = "lyric", artist = "Run This Town-Jay-Z"),
            anchors = listOf(lyricsSong("Run This Town", "Jay-Z")),
        )
        val artistFirst = sanitizer.sanitize(
            sample(packageName = "com.other.player", title = "lyric", artist = "Jay-Z - Run This Town"),
            anchors = listOf(lyricsSong("Run This Town", "Jay-Z")),
        )

        listOf(titleFirst, artistFirst).forEach {
            assertEquals(Status.RECOVERED, it.status)
            assertEquals("Run This Town", it.title)
            assertEquals("Jay-Z", it.artist)
        }
    }

    @Test
    fun `lyrics song anchor settles a split that is ambiguous without it`() {
        // 标题逐句漂移确认污染后，「Love Somebody-Jay-Z」有多种拆法且顺序未知，净化器不猜。
        fun drift(target: MediaSessionMetadataSanitizer, anchors: List<TrackAnchor>): SanitizedTrack {
            var last: SanitizedTrack? = null
            listOf("line one", "line two", "line three").forEachIndexed { index, line ->
                last = target.sanitize(
                    sample(
                        packageName = XIAOMI,
                        title = line,
                        artist = "Love Somebody-Jay-Z",
                        positionMs = 107_895L + index * 1_500L,
                    ),
                    anchors = if (index == 2) anchors else emptyList(),
                )
            }
            return last!!
        }

        val withoutAnchor = drift(MediaSessionMetadataSanitizer(), emptyList())
        assertEquals(Status.UNRESOLVED, withoutAnchor.status)
        assertTrue(withoutAnchor.candidates.size > 1)

        val withAnchor = drift(MediaSessionMetadataSanitizer(), listOf(lyricsSong("Love Somebody", "Jay-Z")))
        assertEquals(Status.RECOVERED, withAnchor.status)
        assertEquals("Love Somebody", withAnchor.title)
        assertEquals("Jay-Z", withAnchor.artist)
    }

    @Test
    fun `a polluted lyrics song anchor is not adopted`() {
        // 发布端把歌词行当成歌名：它拼不出会话的歌手串，必须被丢弃，而不是被当成真歌名。
        val pollutedPublisher = lyricsSong(SALT_LINES[2], "许冠杰")
        listOf(0, 1).forEach { sanitizer.sanitize(saltLine(it)) }

        val result = sanitizer.sanitize(saltLine(2), anchors = listOf(pollutedPublisher))

        assertEquals(Status.UNRESOLVED, result.status)
        assertEquals(null, result.title)
    }

    @Test
    fun `a lyrics song anchor with the wrong artist or no artist is not adopted`() {
        listOf(0, 1).forEach { sanitizer.sanitize(saltLine(it)) }

        listOf(lyricsSong("跟佢做个 FRIEND", "别的歌手"), TrackAnchor(AnchorSource.LYRICS_SONG, title = "跟佢做个 FRIEND"))
            .forEach { anchor ->
                val result = sanitizer.sanitize(saltLine(2), anchors = listOf(anchor))
                assertEquals(Status.UNRESOLVED, result.status)
                assertEquals(null, result.title)
            }
    }

    @Test
    fun `a stale lyrics song anchor from the previous song never applies`() {
        val result = sanitizer.sanitize(
            sample(
                title = "另一首歌的歌词",
                artist = "Taylor Swift - Sweet Nothing",
                album = "Midnights",
            ),
            anchors = listOf(lyricsSong("跟佢做个 FRIEND", "许冠杰")),
        )

        assertEquals(Status.CLEAN, result.status)
    }

    private val notificationAnchor = TrackAnchor(AnchorSource.NOTIFICATION, title = "跟佢做个 FRIEND")

    private fun saltLine(index: Int, albumArtist: String? = null) = sample(
        title = SALT_LINES[index],
        positionMs = 107_895L + index * 1_500L,
        albumArtist = albumArtist,
    )

    private fun sample(
        title: String?,
        artist: String? = SALT_ARTIST,
        packageName: String = SALT,
        mediaId: String? = null,
        album: String? = SALT_ALBUM,
        albumArtist: String? = null,
        durationMs: Long = SALT_DURATION_MS,
        positionMs: Long = 107_895L,
        sampledAtMs: Long = positionMs,
    ) = SessionMetadataSample(
        packageName = packageName,
        mediaId = mediaId,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        durationMs = durationMs,
        positionMs = positionMs,
        playing = true,
        sampledAtMs = sampledAtMs,
    )

    private companion object {
        const val SALT = "com.salt.music"
        const val XIAOMI = "com.miui.player"
        const val KUGOU = "com.kugou.android"
        const val MD3MUSIC = "com.md3music.md3music"
        const val SALT_ARTIST = "许冠杰 - 跟佢做个 FRIEND"
        const val SALT_ALBUM = "最佳拍档大显神通"
        const val SALT_DURATION_MS = 200_000L
        val SALT_LINES = listOf(
            "教佢揸车揾戏嚟做",
            "去咖啡湾撑艇",
            "再转车出粉岭",
            "两个吃哂一打西饼",
            "跟佢做个Friend",
            "惊亲两份惊",
            "有雨揸紧遮柄",
            "揾个沙煲乱掟",
        )
    }
}
