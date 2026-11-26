/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.aitrans

import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cavan 08:45 问「翻译缓存重启系统界面有用？」。这里用同一个缓存对象（不重启、不重建）复现读写失败，
 * 再把原因去掉，证明下一次读写自己就恢复，并且恢复时写一条给日志分析看的 i 级记录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AITranslationCacheRecoveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val logged = mutableListOf<String>()
    private val items = listOf(TranslationItem(0, "你好"))
    private val app get() = RuntimeEnvironment.getApplication()
    private val dbFile get() = app.getDatabasePath("hyperlyricsenhanced_translation.db")

    @After fun cancel() = scope.cancel()

    private fun newCache() = AITranslationCache(8, AtomicInteger(), scope) { logged += it }.apply { init(app) }

    private fun helperOf(cache: AITranslationCache): SQLiteOpenHelper =
        AITranslationCache::class.java.getDeclaredField("dbHelper").apply { isAccessible = true }.get(cache) as SQLiteOpenHelper

    // 改前：打开数据库在 runCatching 外面，打不开时异常一路抛到 translateSong，这首歌整首不翻译。
    @Test fun `a database that can't open only loses caching, and works again on the next call once it can`() = runBlocking {
        dbFile.parentFile!!.mkdirs()
        dbFile.delete()
        File(dbFile.path).mkdirs() // a directory where the database file should be: open fails
        val cache = newCache()
        assertNull(cache.getFromDb("k1"))
        cache.saveToDb("k1", items)
        assertEquals(emptyList<String>(), logged)

        File(dbFile.path).delete()
        cache.saveToDb("k1", items)
        assertEquals(items, cache.getFromDb("k1"))
        assertEquals(listOf("翻译缓存写入已恢复", "翻译缓存读取已恢复"), logged)
    }

    // 存储满：同一个连接上写一直失败、读照常；空间放开后同一个缓存下一次写就成功，不用重启。
    @Test fun `writes fail while storage is full and work again on the same cache once space is freed`() = runBlocking {
        val cache = newCache()
        cache.saveToDb("k1", items)
        val db = helperOf(cache).writableDatabase
        db.setMaximumSize(db.pageSize * 2) // the file is already at least this big: no new page fits
        val big = List(200) { TranslationItem(it, "很长的翻译".repeat(50)) }
        cache.saveToDb("k2", big)
        assertNull(cache.getFromDb("k2"))
        assertEquals(items, cache.getFromDb("k1"))
        // Reads kept working, so no read recovery is logged, and the write failure isn't over yet.
        assertEquals(emptyList<String>(), logged)

        db.setMaximumSize(64L * 1024 * 1024)
        cache.saveToDb("k2", big)
        assertEquals(big, cache.getFromDb("k2"))
        cache.saveToDb("k3", items)
        assertEquals(listOf("翻译缓存写入已恢复"), logged)
    }

    @Test fun `nothing is logged while every read and write works`() = runBlocking {
        val cache = newCache()
        cache.saveToDb("k1", items)
        assertEquals(items, cache.getFromDb("k1"))
        assertNull(cache.getFromDb("missing"))
        assertEquals(emptyList<String>(), logged)
    }
}
