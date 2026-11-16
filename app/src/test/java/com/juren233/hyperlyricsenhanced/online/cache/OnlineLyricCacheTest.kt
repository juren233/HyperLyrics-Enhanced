/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.cache


import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import com.juren233.hyperlyricsenhanced.online.model.OnlineSourceRequestException
import com.juren233.hyperlyricsenhanced.online.model.SearchSource
import com.juren233.hyperlyricsenhanced.online.model.SongSearchResult
import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OnlineLyricCacheTest {
    private var now = 1_000_000L
    private val sixHours = OnlineLyricCachePolicy.MISS_TTL_MS

    private fun newCache(store: OnlineLyricCacheStore = MemoryStore(), maxSongs: Int = 1_000) =
        OnlineLyricResponseCache(store, clockMs = { now }, maxSongs = { maxSongs })

    private val song = SongSearchResult("42", "Linked", "Jim Yosef", "NCS", 223_000, Source.QM)
    private val lyrics = LyricsResult(
        tags = emptyMap(),
        original = listOf(LyricsLine(0, 1_000, listOf(LyricsWord(0, 1_000, "line")))),
        translated = null,
        romanization = null,
    )

    private class FakeSource(
        override val sourceType: Source = Source.QM,
        var searchResult: () -> List<SongSearchResult> = { emptyList() },
        var lyricsResult: () -> LyricsResult? = { null },
        var metadataResult: () -> List<SongSearchResult> = { emptyList() },
    ) : SearchSource {
        var searches = 0
        var lyricsCalls = 0
        var metadataCalls = 0

        override suspend fun search(
            keyword: String,
            page: Int,
            separator: String,
            pageSize: Int,
            durationMs: Long,
        ): List<SongSearchResult> {
            searches++
            return searchResult()
        }

        override suspend fun searchByMetadata(
            title: String,
            artist: String,
            durationMs: Long,
            separator: String,
        ): List<SongSearchResult> {
            metadataCalls++
            return metadataResult()
        }

        override suspend fun getLyrics(song: SongSearchResult): LyricsResult? {
            lyricsCalls++
            return lyricsResult()
        }
    }

    private fun cached(
        delegate: SearchSource,
        cache: OnlineLyricResponseCache,
        retry: Set<Source> = emptySet(),
    ) = CachedSearchSource(delegate, cache, OnlineLyricCacheRequest(appleSong("1"), retry))

    private fun appleSong(id: String) = OnlineLyricCacheSong.appleMusic(id, "Linked $id", "Jim Yosef", "Album $id")

    @Test
    fun `empty search is a miss for six hours, then asked again`() = runBlocking {
        val cache = newCache()
        val delegate = FakeSource()
        cached(delegate, cache).search("Linked")
        cached(delegate, cache).search("Linked")
        assertEquals(1, delegate.searches)
        now += sixHours
        cached(delegate, cache).search("Linked")
        assertEquals(2, delegate.searches)
    }

    @Test
    fun `a source picked by hand skips the remembered miss`() = runBlocking {
        val cache = newCache()
        val delegate = FakeSource()
        cached(delegate, cache).search("Linked")
        cached(delegate, cache, retry = setOf(Source.QM)).search("Linked")
        assertEquals(2, delegate.searches)
        cached(delegate, cache, retry = setOf(Source.NE)).search("Linked")
        assertEquals(2, delegate.searches)
    }

    @Test
    fun `failed requests are never cached`() = runBlocking {
        val cache = newCache()
        val delegate = FakeSource(searchResult = { throw OnlineSourceRequestException("rate limited") })
        repeat(2) { runCatching { cached(delegate, cache).search("Linked") } }
        assertEquals(2, delegate.searches)
    }

    @Test
    fun `lyrics with content are kept past the miss lifetime, also for manual picks`() = runBlocking {
        val cache = newCache()
        val delegate = FakeSource(lyricsResult = { lyrics })
        cached(delegate, cache).getLyrics(song)
        now += sixHours * 10
        val again = cached(delegate, cache, retry = setOf(Source.QM)).getLyrics(song)
        assertEquals(1, delegate.lyricsCalls)
        assertEquals("line", again?.original?.single()?.words?.single()?.text)
    }

    @Test
    fun `a song without lyrics is a miss`() = runBlocking {
        val cache = newCache()
        val delegate = FakeSource(lyricsResult = { null })
        assertNull(cached(delegate, cache).getLyrics(song))
        assertNull(cached(delegate, cache).getLyrics(song))
        assertEquals(1, delegate.lyricsCalls)
        now += sixHours
        cached(delegate, cache).getLyrics(song)
        assertEquals(2, delegate.lyricsCalls)
    }

    @Test
    fun `candidate list becomes a hit once one of its songs gave lyrics`() = runBlocking {
        val cache = newCache()
        val delegate = FakeSource(searchResult = { listOf(song) })
        val first = cached(delegate, cache)
        first.search("Linked")
        first.confirm(song, 90, lyrics)
        now += sixHours * 10
        assertEquals(listOf(song), cached(delegate, cache, retry = setOf(Source.QM)).search("Linked"))
        assertEquals(1, delegate.searches)
    }

    @Test
    fun `unconfirmed candidate list expires like a miss`() = runBlocking {
        val cache = newCache()
        val delegate = FakeSource(searchResult = { listOf(song) })
        cached(delegate, cache).search("Linked")
        now += sixHours
        cached(delegate, cache).search("Linked")
        assertEquals(2, delegate.searches)
    }

    @Test
    fun `empty metadata matches are not cached because that endpoint hides failures`() = runBlocking {
        val cache = newCache()
        val delegate = FakeSource()
        repeat(2) { cached(delegate, cache).searchByMetadata("Linked", "Jim Yosef", 223_000) }
        assertEquals(2, delegate.metadataCalls)
        delegate.metadataResult = { listOf(song) }
        repeat(2) { cached(delegate, cache).searchByMetadata("Linked", "Jim Yosef", 223_000) }
        assertEquals(3, delegate.metadataCalls)
    }

    @Test
    fun `clearing a song removes only its entries`() = runBlocking {
        val cache = newCache()
        val delegate = FakeSource(lyricsResult = { lyrics })
        CachedSearchSource(delegate, cache, OnlineLyricCacheRequest(appleSong("1"))).getLyrics(song)
        val other = song.copy(id = "43")
        CachedSearchSource(delegate, cache, OnlineLyricCacheRequest(appleSong("2"))).getLyrics(other)
        cache.delete(OnlineLyricCacheSelection(tags = setOf("apple:1")))
        CachedSearchSource(delegate, cache, OnlineLyricCacheRequest(appleSong("1"))).getLyrics(song)
        CachedSearchSource(delegate, cache, OnlineLyricCacheRequest(appleSong("2"))).getLyrics(other)
        assertEquals(3, delegate.lyricsCalls)
    }

    @Test
    fun `a version 2 database keeps its songs and gains the album`() {
        val app = RuntimeEnvironment.getApplication()
        val path = app.getDatabasePath("hyperlyricsenhanced_online_lyrics.db").apply { parentFile?.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "CREATE TABLE entries (cache_key TEXT PRIMARY KEY, payload BLOB NOT NULL, permanent INTEGER NOT NULL, " +
                    "stored_at INTEGER NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL, size INTEGER NOT NULL)"
            )
            db.execSQL("CREATE TABLE links (cache_key TEXT NOT NULL, tag TEXT NOT NULL, PRIMARY KEY (cache_key, tag))")
            db.execSQL("CREATE TABLE songs (tag TEXT PRIMARY KEY, used_at INTEGER NOT NULL, title TEXT, artist TEXT, package TEXT)")
            db.execSQL("INSERT INTO entries VALUES ('k1', x'00', 1, 0, 'QM', 'LYRICS', 1)")
            db.execSQL("INSERT INTO links VALUES ('k1', 'apple:1')")
            db.execSQL("INSERT INTO songs VALUES ('apple:1', 0, 'Linked 1', 'Jim Yosef', 'pkg')")
            db.version = 2
        }
        val cache = newCache(SqliteOnlineLyricCacheStore(app))
        val song = cache.summaries().single().song
        assertEquals("Linked 1", song.title)
        assertEquals("", song.album)
        cache.link(appleSong("1"), listOf("k1"))
        assertEquals("Album 1", cache.summaries().single().song.album)
    }

    @Test
    fun `sqlite store records the album and keeps it when a later lookup has none`() {
        val store = SqliteOnlineLyricCacheStore(RuntimeEnvironment.getApplication())
        val cache = newCache(store)
        cache.store(appleSong("1"), "k1", ByteArray(1), true, Source.QM, OnlineLyricCacheKind.LYRICS)
        assertEquals("Album 1", cache.summaries().single().song.album)
        now += 1
        cache.link(OnlineLyricCacheSong.appleMusic("1", "Linked 1", "Jim Yosef", null), listOf("k1"))
        assertEquals("Album 1", cache.summaries().single().song.album)
        cache.link(OnlineLyricCacheSong.appleMusic("1", "Linked 1", "Jim Yosef", "Deluxe"), listOf("k1"))
        assertEquals("Deluxe", cache.summaries().single().song.album)
    }

    @Test
    fun `sqlite store keeps hits, trims the least recently used songs and clears selectively`() {
        val store = SqliteOnlineLyricCacheStore(RuntimeEnvironment.getApplication())
        val cache = newCache(store, maxSongs = 2)
        fun put(id: String, key: String, permanent: Boolean, source: Source = Source.QM, size: Int = 1) =
            cache.store(appleSong(id), key, ByteArray(size), permanent, source, OnlineLyricCacheKind.LYRICS)
        put("1", "k1", permanent = true)
        now += 1
        put("2", "k2", permanent = false)
        now += 1
        cache.link(appleSong("1"), listOf("k1"))
        now += 1
        put("3", "k3", permanent = false, source = Source.NE, size = 5)
        put("3", "k3b", permanent = true, source = Source.QM, size = 7)
        assertTrue(cache.lookup("k1", retryMiss = true) != null)
        assertTrue("a song with only misses does not count toward the limit", cache.lookup("k2", retryMiss = false) != null)
        now += 1
        put("4", "k4", permanent = true)
        assertNull("the least recently used song with lyrics is trimmed", cache.lookup("k1", retryMiss = true))
        assertTrue(cache.lookup("k2", retryMiss = false) != null)

        val summaries = cache.summaries()
        assertEquals(listOf("apple:4", "apple:3", "apple:2"), summaries.map { it.song.tag })
        val third = summaries[1]
        assertEquals("Linked 3", third.song.title)
        assertEquals(12L, third.sizeBytes)
        assertEquals(1, third.sources.single { it.source == "NE" }.misses)
        assertEquals(1, third.sources.single { it.source == "QM" }.hits)

        cache.delete(OnlineLyricCacheSelection(tags = setOf("apple:3"), sources = setOf("QM")))
        assertNull(cache.lookup("k3b", retryMiss = false))
        assertTrue(cache.lookup("k3", retryMiss = false) != null)
        cache.delete(OnlineLyricCacheSelection(tags = setOf("apple:3"), sources = setOf("NE")))
        assertNull(cache.lookup("k3", retryMiss = false))
        assertEquals(listOf("apple:4", "apple:2"), cache.summaries().map { it.song.tag })

        now += sixHours * 2
        put("5", "k5", permanent = false)
        now += sixHours
        assertEquals("expired misses are dropped from the listing", listOf("apple:4"), cache.summaries().map { it.song.tag })
        cache.delete(OnlineLyricCacheSelection())
        assertTrue(cache.summaries().isEmpty())
    }

    @Test
    fun `catalog lookups are returned by a clear so their files can go`() {
        val store = SqliteOnlineLyricCacheStore(RuntimeEnvironment.getApplication())
        val cache = newCache(store)
        val ref = CatalogCacheRef(Source.AMLL, "APPLE_MUSIC", "1207120543")
        cache.store(appleSong("1207120543"), ref.key, ByteArray(0), true, Source.AMLL, OnlineLyricCacheKind.CATALOG, 900)
        assertEquals(900L, cache.summaries().single().sizeBytes)
        assertEquals(listOf(ref), cache.delete(OnlineLyricCacheSelection(sources = setOf("AMLL"))))
    }

    @Test
    fun `a trim hands over the catalog lookups of the songs it drops, after a store and on demand`() {
        val store = SqliteOnlineLyricCacheStore(RuntimeEnvironment.getApplication())
        val trimmed = mutableListOf<CatalogCacheRef>()
        var limit = 2
        val cache = OnlineLyricResponseCache(store, clockMs = { now }, maxSongs = { limit }, onTrimmed = { trimmed += it })
        val refs = (1..3).map { CatalogCacheRef(Source.AMLL, "APPLE_MUSIC", "$it") }
        refs.forEach { ref ->
            now += 1
            cache.store(appleSong(ref.songId), ref.key, ByteArray(0), true, Source.AMLL, OnlineLyricCacheKind.CATALOG, 10)
        }
        assertEquals(listOf(refs[0]), trimmed)
        assertEquals(listOf("apple:3", "apple:2"), cache.summaries().map { it.song.tag })

        limit = 5
        assertEquals(listOf(refs[1]), cache.trim(1))
        assertEquals(listOf("apple:3"), cache.summaries().map { it.song.tag })
        assertEquals(emptyList<CatalogCacheRef>(), cache.trim(1))
    }

    @Test
    fun `the cache limit is a preset or a positive custom count, 1000 by default`() {
        assertEquals(1_000, OnlineLyricCacheLimit.resolve(OnlineLyricCacheLimit.DEFAULT_MODE, 42))
        assertEquals(listOf(100, 200, 500, 1_000), (0..3).map { OnlineLyricCacheLimit.resolve(it, 42) })
        assertEquals(42, OnlineLyricCacheLimit.resolve(OnlineLyricCacheLimit.MODE_CUSTOM, 42))
        assertEquals(1, OnlineLyricCacheLimit.resolve(OnlineLyricCacheLimit.MODE_CUSTOM, 0))

        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("cache_limit_test", 0)
        assertEquals(1_000, OnlineLyricCacheLimit.read(prefs))
        prefs.edit().putInt(OnlineLyricCacheLimit.KEY_MODE, OnlineLyricCacheLimit.MODE_CUSTOM)
            .putInt(OnlineLyricCacheLimit.KEY_CUSTOM, 300).commit()
        assertEquals(300, OnlineLyricCacheLimit.read(prefs))
        prefs.edit().putInt(OnlineLyricCacheLimit.KEY_MODE, 99).commit()
        assertEquals(1_000, OnlineLyricCacheLimit.read(prefs))
    }

    @Test
    fun `sqlite store keeps each source's match and the last shown sources until that source is cleared`() {
        val store = SqliteOnlineLyricCacheStore(RuntimeEnvironment.getApplication())
        val cache = newCache(store)
        val target = appleSong("1")
        cache.recordMatch(target, Source.QM, 90, wordTimed = false)
        assertTrue("no song row yet: nothing recorded", cache.summaries().isEmpty())
        cache.store(target, "qm", ByteArray(3), true, Source.QM, OnlineLyricCacheKind.LYRICS)
        cache.store(target, "amll", ByteArray(5), true, Source.AMLL, OnlineLyricCacheKind.CATALOG)
        cache.recordMatch(target, Source.QM, 90, wordTimed = false)
        cache.recordMatch(target, Source.QM, 96, wordTimed = true)
        cache.recordMatch(target, Source.AMLL, null, wordTimed = true)
        cache.markUsed(target.tag, OnlineLyricCacheLastUsed(lyrics = Source.AMLL.name, translation = Source.QM.name))
        assertEquals(
            OnlineLyricCacheLastUsed(lyrics = Source.AMLL.name, translation = Source.QM.name),
            cache.summaries().single().lastUsed,
        )
        cache.markUsed(target.tag, OnlineLyricCacheLastUsed(translation = Source.QM.name, pronunciation = Source.QM.name))

        val summary = cache.summaries().single()
        assertEquals(
            "a new display replaces the whole record",
            OnlineLyricCacheLastUsed(translation = Source.QM.name, pronunciation = Source.QM.name),
            summary.lastUsed,
        )
        val qm = summary.sources.single { it.source == Source.QM.name }
        assertEquals(96, qm.score)
        assertEquals(true, qm.wordTimed)
        val amll = summary.sources.single { it.source == Source.AMLL.name }
        assertNull(amll.score)
        assertEquals(true, amll.wordTimed)

        cache.delete(OnlineLyricCacheSelection(tags = setOf(target.tag), sources = setOf(Source.QM.name)))
        val left = cache.summaries().single()
        assertEquals("the cleared source is no longer marked", OnlineLyricCacheLastUsed(), left.lastUsed)
        assertEquals(listOf(Source.AMLL.name), left.sources.map { it.source })
        cache.store(target, "qm", ByteArray(3), true, Source.QM, OnlineLyricCacheKind.LYRICS)
        assertNull(
            "a cleared source's old match is not shown again",
            cache.summaries().single().sources.single { it.source == Source.QM.name }.score,
        )
    }

    @Test
    fun `a shown song records the sources of its lyric body, translation and pronunciation`() {
        val keys = com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
        fun line(translation: String? = null, roma: String? = null) =
            com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine(
                begin = 0, end = 1, text = "a", translation = translation, roma = roma,
            )
        val base = com.juren233.hyperlyricsenhanced.lyric.model.Song(name = "Linked", lyrics = listOf(line()))
        assertNull(OnlineLyricCacheUsage.usedSources(base))
        assertNull(
            "without a cache song nothing is recorded",
            OnlineLyricCacheUsage.usedSources(OnlineLyricCacheUsage.filedUnder(base, tag = null, source = Source.QM)),
        )
        assertNull(
            "the player's own lyrics are no online content",
            OnlineLyricCacheUsage.usedSources(OnlineLyricCacheUsage.filedUnder(base, tag = "song:x")),
        )
        val searched = OnlineLyricCacheUsage.filedUnder(base, tag = "song:x", source = Source.NE)
        assertEquals(
            "song:x" to OnlineLyricCacheLastUsed(lyrics = Source.NE.name),
            OnlineLyricCacheUsage.usedSources(searched),
        )
        assertEquals(
            "an online body's own translation and pronunciation are its source's",
            OnlineLyricCacheLastUsed(Source.NE.name, Source.NE.name, Source.NE.name),
            OnlineLyricCacheUsage.usedSourcesOf(searched.copy(lyrics = listOf(line(translation = "t", roma = "r")))),
        )
        val translated = OnlineLyricCacheUsage.filedUnder(
            base.copy(
                metadata = com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf(
                    keys.ONLINE_TRANSLATION_SOURCE to "KUGOU",
                    keys.APPLE_MISSING_LYRICS_SOURCE to "AMLL",
                ),
                lyrics = listOf(line(translation = "t", roma = "r")),
            ),
            tag = "apple:1",
        )
        assertEquals(
            "the online completion names the translation; the rest comes with the body",
            "apple:1" to OnlineLyricCacheLastUsed(lyrics = "AMLL", translation = "KUGOU", pronunciation = "AMLL"),
            OnlineLyricCacheUsage.usedSources(translated),
        )
        val completedOnly = OnlineLyricCacheUsage.filedUnder(
            base.copy(
                metadata = com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf(
                    keys.ONLINE_TRANSLATION_SOURCE to "KUWO",
                ),
                lyrics = listOf(line(translation = "t", roma = "native")),
            ),
            tag = "song:spotify",
        )
        assertEquals(
            "the player's own pronunciation is not an online source's",
            OnlineLyricCacheLastUsed(translation = "KUWO"),
            OnlineLyricCacheUsage.usedSources(completedOnly)?.second,
        )
        assertEquals(
            "re-filing keeps one tag",
            "apple:2",
            OnlineLyricCacheUsage.usedSources(OnlineLyricCacheUsage.filedUnder(translated, tag = "apple:2"))?.first,
        )
    }

    @Test
    fun `a version 4 cache keeps its songs and drops the unlabelled last source`() {
        val context = RuntimeEnvironment.getApplication()
        val name = "hyperlyricsenhanced_online_lyrics.db"
        context.deleteDatabase(name)
        context.getDatabasePath(name).parentFile?.mkdirs()
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        old.execSQL(
            "CREATE TABLE entries (cache_key TEXT PRIMARY KEY, payload BLOB NOT NULL, permanent INTEGER NOT NULL, " +
                "stored_at INTEGER NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL, size INTEGER NOT NULL)"
        )
        old.execSQL("CREATE TABLE links (cache_key TEXT NOT NULL, tag TEXT NOT NULL, PRIMARY KEY (cache_key, tag))")
        old.execSQL(
            "CREATE TABLE songs (tag TEXT PRIMARY KEY, used_at INTEGER NOT NULL, title TEXT, artist TEXT, " +
                "package TEXT, album TEXT NOT NULL DEFAULT '', last_source TEXT)"
        )
        old.execSQL(
            "CREATE TABLE song_sources (tag TEXT NOT NULL, source TEXT NOT NULL, score INTEGER, " +
                "word_timed INTEGER NOT NULL, PRIMARY KEY (tag, source))"
        )
        old.execSQL("INSERT INTO entries VALUES ('k', x'00', 1, 1, 'KUWO', 'LYRICS', 1)")
        old.execSQL("INSERT INTO links VALUES ('k', 't')")
        old.execSQL("INSERT INTO songs VALUES ('t', 1, 'petal', 'Ariana Grande', 'pkg', '', 'KUWO')")
        old.execSQL("INSERT INTO song_sources VALUES ('t', 'KUWO', 105, 0)")
        old.version = 4
        old.close()

        val cache = newCache(SqliteOnlineLyricCacheStore(context))
        val summary = cache.summaries().single()
        assertEquals("petal", summary.song.title)
        assertEquals(105, summary.sources.single().score)
        assertEquals(OnlineLyricCacheLastUsed(), summary.lastUsed)
        cache.markUsed("t", OnlineLyricCacheLastUsed(translation = "KUWO"))
        assertEquals(OnlineLyricCacheLastUsed(translation = "KUWO"), cache.summaries().single().lastUsed)
    }

    @Test
    fun `codec round trips songs and lyrics`() {
        val withExtras = song.copy(extras = mapOf("accessKey" to "a"))
        assertEquals(listOf(withExtras), OnlineLyricCacheCodec.decodeSongs(OnlineLyricCacheCodec.encodeSongs(listOf(withExtras))))
        assertEquals(lyrics, OnlineLyricCacheCodec.decodeLyrics(OnlineLyricCacheCodec.encodeLyrics(lyrics)))
        assertNull(OnlineLyricCacheCodec.decodeLyrics(OnlineLyricCacheCodec.encodeLyrics(null)))
    }

    private class MemoryStore : OnlineLyricCacheStore {
        private val entries = HashMap<String, StoredCacheEntry>()
        private val links = HashMap<String, MutableSet<String>>()

        override fun get(key: String) = entries[key]
        override fun put(
            key: String,
            payload: ByteArray,
            permanent: Boolean,
            storedAtMs: Long,
            song: OnlineLyricCacheSong,
            source: Source,
            kind: OnlineLyricCacheKind,
            sizeBytes: Long,
        ) {
            entries[key] = StoredCacheEntry(payload, permanent, storedAtMs)
            link(song, listOf(key), storedAtMs)
        }
        override fun markPermanent(keys: Collection<String>) {
            keys.forEach { key -> entries[key]?.let { entries[key] = StoredCacheEntry(it.payload, true, it.storedAtMs) } }
        }
        override fun remove(key: String) {
            entries.remove(key)
        }
        override fun link(song: OnlineLyricCacheSong, keys: Collection<String>, usedAtMs: Long) {
            links.getOrPut(song.tag) { linkedSetOf() } += keys
        }
        override fun recordMatch(tag: String, source: Source, score: Int?, wordTimed: Boolean) = Unit
        override fun markUsed(tag: String, used: OnlineLyricCacheLastUsed) = Unit
        override fun trimSongs(maxSongs: Int, expiredBeforeMs: Long) = emptyList<String>()
        override fun summaries(expiredBeforeMs: Long) = emptyList<OnlineLyricCacheSongSummary>()
        override fun delete(selection: OnlineLyricCacheSelection): List<String> {
            val tags = selection.tags ?: links.keys.toSet()
            tags.forEach { tag -> links.remove(tag).orEmpty().forEach(entries::remove) }
            return emptyList()
        }
    }
}
