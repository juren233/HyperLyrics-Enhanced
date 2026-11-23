/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.online.cache

import android.database.sqlite.SQLiteOpenHelper
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.root.reload.ModuleGeneration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class OnlineLyricCacheRetirementTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun restore() {
        ModuleGeneration.resetForTest()
        CachedSearchSource.resetForTest()
    }

    private fun song(id: String) = OnlineLyricCacheSong.appleMusic(id, "Song $id", "Artist", "Album")

    private fun SqliteOnlineLyricCacheStore.putEntry(key: String, id: String = "1") =
        put(key, byteArrayOf(1), true, 1L, song(id), Source.QM, OnlineLyricCacheKind.LYRICS, 1L)

    /** The helper's open database; non-null after any access opened it. */
    private fun SqliteOnlineLyricCacheStore.openDatabase(): Any? {
        val helper = SqliteOnlineLyricCacheStore::class.java.getDeclaredField("helper")
            .apply { isAccessible = true }.get(this)
        return SQLiteOpenHelper::class.java.getDeclaredField("mDatabase")
            .apply { isAccessible = true }.get(helper)
    }

    @Test fun `a retired generation adds no response or link, but still deletes`() {
        val store = SqliteOnlineLyricCacheStore(app)
        store.putEntry("kept")
        ModuleGeneration.retire()
        store.putEntry("late", id = "2")
        store.link(song("3"), listOf("kept"), 2L)
        assertNull(store.get("late"))
        assertEquals(listOf("tag:apple:1"), store.summaries(0L).map { "tag:" + it.song.tag })
        store.remove("kept")
        assertNull(store.get("kept"))
    }

    @Test fun `an idle store closes at retirement and a late call neither throws nor reopens`() {
        val store = SqliteOnlineLyricCacheStore(app)
        store.putEntry("k1")
        assertNotNull(store.openDatabase())
        store.retire()
        assertTrue(store.isClosed)
        assertNull(store.openDatabase())
        assertNull(store.get("k1"))
        store.putEntry("k2")
        store.markPermanent(listOf("k1"))
        store.remove("k1")
        assertEquals(emptyList<String>(), store.trimSongs(0, 0L))
        assertEquals(emptyList<OnlineLyricCacheSongSummary>(), store.summaries(0L))
        assertEquals(emptyList<String>(), store.delete(OnlineLyricCacheSelection()))
        assertNull(store.openDatabase())
        // Nothing the late calls did reached the file.
        assertNotNull(SqliteOnlineLyricCacheStore(app).get("k1"))
        assertNull(SqliteOnlineLyricCacheStore(app).get("k2"))
    }

    @Test fun `a request under way keeps the store open until it is answered`() {
        val store = SqliteOnlineLyricCacheStore(app)
        assertTrue(store.enterRequest())
        store.retire()
        assertFalse(store.isClosed)
        store.putEntry("k1")
        assertNotNull(store.get("k1"))
        store.exitRequest()
        assertTrue(store.isClosed)
        assertFalse(store.enterRequest())
    }

    @Test fun `the shared store retires with the generation, even one first asked for later`() {
        CachedSearchSource.retireForReload()
        val store = CachedSearchSource.storeFor(app)
        assertTrue(store.isClosed)
        assertSame(store, CachedSearchSource.storeFor(app))
        assertNull(store.openDatabase())
    }
}
