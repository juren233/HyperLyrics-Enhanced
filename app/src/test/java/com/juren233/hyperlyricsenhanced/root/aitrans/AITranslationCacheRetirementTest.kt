/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.aitrans

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AITranslationCacheRetirementTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun cancel() = scope.cancel()

    private fun newCache() = AITranslationCache(8, AtomicInteger(), scope).apply {
        init(RuntimeEnvironment.getApplication())
    }

    @Test fun `a retired cache closes and later calls fail safely without reopening`() = runBlocking {
        val items = listOf(TranslationItem(0, "你好"))
        val cache = newCache()
        cache.saveToDb("k1", items)
        assertEquals(items, cache.getFromDb("k1"))
        cache.retire()
        assertTrue(cache.isClosed)
        assertNull(cache.getFromDb("k1"))
        cache.saveToDb("k2", items)
        var cleared = false
        cache.clear { cleared = true }
        assertEquals(false, cleared)
        // The file kept what was written before the close, and nothing after.
        val reopened = newCache()
        assertEquals(items, reopened.getFromDb("k1"))
        assertNull(reopened.getFromDb("k2"))
    }

    @Test fun `a cache that never opened its database retires without opening it`() {
        val cache = AITranslationCache(8, AtomicInteger(), scope)
        cache.retire()
        assertTrue(cache.isClosed)
        cache.init(RuntimeEnvironment.getApplication())
        assertNull(runBlocking { cache.getFromDb("k1") })
    }
}
