/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 调度所有者回归：重复请求合并、回调语义、批处理名额的
 * 完成/失败/同步异常/失效四条释放路径。行为基线取自拆分前的
 * AppleInternalCatalogResolver 内联实现。
 */
class AppleInternalCatalogDispatchTest {

    private fun originalRequest(
        key: String,
        mediaId: String = key,
        priority: RequestPriority = RequestPriority.BACKGROUND,
        language: String = "ja-JP",
        callbacks: List<(Alias?) -> Unit> = emptyList(),
    ) = OriginalEntityRequest(
        requestKey = key,
        mediaId = mediaId,
        lookupIds = listOf(mediaId),
        entityType = LocalizedEntityType.SONG,
        language = language,
        storefront = "jp",
        directCacheKey = "V2:ENTITY_SONG:$mediaId",
        priority = priority,
        callbacks = callbacks,
    )

    private fun localizedRequest(
        key: String,
        priority: RequestPriority = RequestPriority.BACKGROUND,
        language: String = "ja-JP",
    ) = LocalizedRequest(
        cacheKey = "1:SONG:$language:$key",
        requestKey = "rk-$key",
        mediaId = key,
        lookupIds = listOf(key),
        entityType = LocalizedEntityType.SONG,
        selection = 1,
        storefront = "jp",
        language = language,
        priority = priority,
    )

    @Test
    fun `duplicate original entity submissions merge callbacks and never lower priority`() {
        val dispatch = AppleInternalCatalogDispatch()
        val received = mutableListOf<String>()

        val first = dispatch.submitOriginalEntityRequest(
            originalRequest("k1", priority = RequestPriority.BACKGROUND)
                .copy(callbacks = listOf { received += "a" }),
        )
        val second = dispatch.submitOriginalEntityRequest(
            originalRequest("k1", priority = RequestPriority.VISIBLE)
                .copy(callbacks = listOf { received += "b" }),
        )

        assertTrue(first)
        assertFalse(second)
        val queued = dispatch.originalEntityPending.values.single()
        assertEquals(RequestPriority.VISIBLE, queued.priority)
        assertEquals(2, queued.callbacks.size)
    }

    @Test
    fun `batch selection groups same shape requests and acquires one running slot`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitOriginalEntityRequest(originalRequest("a"))
        dispatch.submitOriginalEntityRequest(originalRequest("b"))
        dispatch.submitOriginalEntityRequest(originalRequest("c", language = "ko-KR"))

        val taken = dispatch.takeOriginalEntityBatch()

        assertEquals(listOf("a", "b"), taken.map { it.mediaId })
        assertEquals(1, dispatch.originalEntityPending.size)
        assertEquals(1, dispatch.originalEntityBatchesRunning)
        assertEquals(1, dispatch.originalEntityBackgroundBatchesRunning)
    }

    @Test
    fun `completion releases the slot so the next background batch can start`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitOriginalEntityRequest(originalRequest("a"))
        val first = dispatch.takeOriginalEntityBatch()
        assertEquals(1, first.size)

        assertTrue(dispatch.submitOriginalEntityRequest(originalRequest("b")))
        dispatch.endOriginalEntityBatch(first.first().priority)
        assertEquals(0, dispatch.originalEntityBatchesRunning)
        assertEquals(0, dispatch.originalEntityBackgroundBatchesRunning)

        val second = dispatch.takeOriginalEntityBatch()
        assertEquals(listOf("b"), second.map { it.mediaId })
    }

    @Test
    fun `background cap holds queued work while visible requests keep a slot`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.originalEntityBatchesRunning = MAX_ORIGINAL_ENTITY_BATCHES_RUNNING - 1
        dispatch.originalEntityBackgroundBatchesRunning =
            MAX_BACKGROUND_ORIGINAL_ENTITY_BATCHES_RUNNING

        assertFalse(dispatch.submitOriginalEntityRequest(originalRequest("bg")))
        assertTrue(dispatch.originalEntityPending.containsKey("bg"))
        assertTrue(dispatch.submitOriginalEntityRequest(originalRequest("v", priority = RequestPriority.VISIBLE)))

        val taken = dispatch.takeOriginalEntityBatch()
        assertEquals(listOf("v"), taken.map { it.mediaId })
        assertTrue(dispatch.takeOriginalEntityBatch().isEmpty())
    }

    @Test
    fun `take clears the scheduled flag even when capacity vanished so later release re-arms`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitOriginalEntityRequest(originalRequest("a"))
        dispatch.originalEntityBatchesRunning = MAX_ORIGINAL_ENTITY_BATCHES_RUNNING

        assertTrue(dispatch.takeOriginalEntityBatch().isEmpty())
        assertFalse(dispatch.originalEntityBatchScheduled)

        dispatch.endOriginalEntityBatch(RequestPriority.VISIBLE)
        assertTrue(dispatch.shouldScheduleOriginalEntityBatch())
    }

    @Test
    fun `original song lane merges duplicates and finish drains each callback once`() {
        val dispatch = AppleInternalCatalogDispatch()
        val results = mutableListOf<Int>()

        assertTrue(dispatch.attachOriginalSongCallback("a") { results += 1 })
        assertFalse(dispatch.attachOriginalSongCallback("a") { results += 2 })
        assertTrue(dispatch.attachOriginalSongCallback("b") { results += 3 })

        val drained = dispatch.drainOriginalSongCallbacks("a")
        assertEquals(2, drained.size)
        drained.forEach { callback ->
            callback(OriginalResolution(alias = null, language = null, originKnown = false, artistIds = emptyList()))
        }
        assertEquals(listOf(1, 2), results)
        assertTrue(dispatch.drainOriginalSongCallbacks("a").isEmpty())
        assertTrue(dispatch.attachOriginalSongCallback("a") { })
    }

    @Test
    fun `catalog identity lane merges concurrent lookups and drains exactly once`() {
        val dispatch = AppleInternalCatalogDispatch()
        var calls = 0

        assertTrue(dispatch.attachCatalogIdentityCallback("a") { calls += 1 })
        assertFalse(dispatch.attachCatalogIdentityCallback("a") { calls += 1 })

        val drained = dispatch.drainCatalogIdentityCallbacks("a")
        assertEquals(2, drained.size)
        drained.forEach { callback ->
            callback(CatalogIdentity(isrc = null, fallbackAliases = emptyList(), genres = emptyList(), artistIds = emptyList()))
        }
        assertEquals(2, calls)
        assertTrue(dispatch.drainCatalogIdentityCallbacks("a").isEmpty())
    }

    @Test
    fun `candidate publication skips blank aliases and invalidation drops pending callbacks`() {
        val dispatch = AppleInternalCatalogDispatch()
        val seen = mutableListOf<Alias>()
        val valid = Alias("タイトル", "アーティスト", "ja-JP")

        dispatch.addOriginalCandidateCallback("a") { seen += it }
        dispatch.publishOriginalCandidate("a", Alias("", "", "ja-JP"))
        assertTrue(seen.isEmpty())
        dispatch.publishOriginalCandidate("a", valid)
        assertEquals(listOf(valid), seen)
        dispatch.publishOriginalCandidate("a", valid)
        assertEquals(listOf(valid), seen)

        dispatch.addOriginalCandidateCallback("b") { seen += it }
        dispatch.discardOriginalCandidates("b")
        dispatch.publishOriginalCandidate("b", valid)
        assertEquals(listOf(valid), seen)
    }

    @Test
    fun `request scope installs once per revision and clears stale priorities`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.rememberRequestPriority("1", RequestPriority.VISIBLE)
        assertEquals(RequestPriority.VISIBLE, dispatch.currentRequestPriority("1", RequestPriority.BACKGROUND))

        assertTrue(dispatch.applyRequestScope(1, visible = setOf("2"), activePage = setOf("3")))
        assertEquals(RequestPriority.BACKGROUND, dispatch.currentScopedPriority("1"))
        assertEquals(RequestPriority.VISIBLE, dispatch.currentScopedPriority("2"))
        assertEquals(RequestPriority.ACTIVE_PAGE, dispatch.currentScopedPriority("3"))

        assertFalse(dispatch.applyRequestScope(1, visible = setOf("2"), activePage = setOf("3")))
        assertTrue(dispatch.applyRequestScope(2, visible = emptySet(), activePage = emptySet()))
        assertEquals(RequestPriority.BACKGROUND, dispatch.currentRequestPriority("1", RequestPriority.VISIBLE))
    }

    @Test
    fun `scoped remember fills absent ids as background and keeps scoped winners`() {
        val dispatch = AppleInternalCatalogDispatch()
        assertTrue(dispatch.applyRequestScope(1, visible = setOf("2"), activePage = emptySet()))

        dispatch.rememberRequestPriority("9", RequestPriority.VISIBLE)
        assertEquals(RequestPriority.BACKGROUND, dispatch.currentScopedPriority("9"))
        dispatch.rememberRequestPriority("2", RequestPriority.BACKGROUND)
        assertEquals(RequestPriority.VISIBLE, dispatch.currentScopedPriority("2"))
    }

    @Test
    fun `pending priority sync demotes out of scope lanes and honors the id filter`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitLocalizedRequest(localizedRequest("1", priority = RequestPriority.ACTIVE_PAGE))
        dispatch.submitOriginalEntityRequest(originalRequest("2", priority = RequestPriority.ACTIVE_PAGE))

        val changed = dispatch.updatePendingRequestPriorities(
            scopedPriorities = mapOf("1" to RequestPriority.VISIBLE),
        )
        assertEquals(2, changed)
        assertEquals(RequestPriority.VISIBLE, dispatch.localizedPending.values.single().priority)
        assertEquals(RequestPriority.BACKGROUND, dispatch.originalEntityPending.values.single().priority)

        val unchanged = dispatch.updatePendingRequestPriorities(
            scopedPriorities = mapOf("1" to RequestPriority.VISIBLE),
            onlyMediaIds = setOf("1"),
        )
        assertEquals(0, unchanged)
    }

    @Test
    fun `localized submissions merge by request key without stacking callbacks`() {
        val dispatch = AppleInternalCatalogDispatch()

        assertTrue(dispatch.submitLocalizedRequest(localizedRequest("a", priority = RequestPriority.BACKGROUND)))
        assertFalse(dispatch.submitLocalizedRequest(localizedRequest("a", priority = RequestPriority.VISIBLE)))

        val queued = dispatch.localizedPending.values.single()
        assertEquals(RequestPriority.VISIBLE, queued.priority)
    }

    @Test
    fun `localized completion releases the background slot for the next batch`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitLocalizedRequest(localizedRequest("a", language = "ja-JP"))
        dispatch.submitLocalizedRequest(localizedRequest("b", language = "ja-JP"))
        dispatch.submitLocalizedRequest(localizedRequest("c", language = "ko-KR"))

        val taken = dispatch.takeLocalizedBatch()
        assertEquals(2, taken.size)
        assertEquals(1, dispatch.localizedPending.size)
        assertEquals(1, dispatch.localizedBatchesRunning)

        dispatch.endLocalizedBatch(taken.first().priority)
        assertEquals(0, dispatch.localizedBatchesRunning)
        assertEquals(0, dispatch.localizedBackgroundBatchesRunning)
    }

    @Test
    fun `localized finish drains in flight callbacks so late arrivals re-own the key`() {
        val dispatch = AppleInternalCatalogDispatch()
        val aliases = mutableListOf<Alias?>()

        synchronized(dispatch.localizedInFlight) {
            dispatch.localizedInFlight["rk-a"] = mutableListOf({ aliases += it })
        }
        val drained = dispatch.drainLocalizedCallbacks("rk-a")
        assertEquals(1, drained.size)
        drained.forEach { callback -> callback(Alias("t", "a", "ja-JP")) }
        assertEquals(1, aliases.size)
        assertNull(dispatch.drainLocalizedCallbacks("rk-a").firstOrNull())
    }
}
