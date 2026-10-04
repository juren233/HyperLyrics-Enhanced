/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.*
import org.junit.Test

class AppleOriginalAlbumEquivalenceTest {
    private val language = "zh-Hans-CN"
    private val original = Alias("七里香", "周杰伦", language, "七里香")
    private fun request(id: String, kind: LocalizedEntityType = LocalizedEntityType.ALBUM,
        lookupIds: List<String> = listOf(id)) = OriginalEntityRequest(
        "$kind:$id", id, lookupIds, kind, language, "cn", "V2:ENTITY_$kind:$id",
        RequestPriority.VISIBLE, emptyList(),
    )
    private fun album(id: String, alias: Alias = original) = CatalogSong(id, alias, null, emptyList(), listOf("300117743"))

    @Test fun `equivalent lookup uses one source id and no exact id filter or title search`() {
        val params = equivalentAlbumQueryParams("1721450027", language)
        assertEquals("1721450027", params["filter[equivalents]"])
        assertFalse(params.containsKey("ids"))
        assertFalse(params.containsKey("term"))
        assertEquals(language, params["l"])
        assertEquals("artists", params["include[albums]"])
        assertThrows(IllegalArgumentException::class.java) { equivalentAlbumQueryParams("1,2", language) }
    }

    @Test fun `official single album mapping can return a different catalog id`() {
        // Issue #45: public catalog response maps 1721450027 -> 536114662.
        assertEquals(original, uniqueOriginalAlbumEquivalent(listOf(album("536114662")), language)?.alias)
        assertNull(uniqueOriginalAlbumEquivalent(emptyList(), language))
        assertNull(uniqueOriginalAlbumEquivalent(listOf(album("1"), album("2")), language))
        assertNull(uniqueOriginalAlbumEquivalent(listOf(album("l.library")), language))
        assertNull(uniqueOriginalAlbumEquivalent(listOf(album("1", original.copy(title = ""))), language))
        assertNull(uniqueOriginalAlbumEquivalent(listOf(album("1", original.copy(language = "ja-JP"))), language))
    }

    @Test fun `exact and known former id results win without an equivalent request`() {
        val results = linkedMapOf<String, Alias?>()
        val direct = original.copy(title = "当前原名")
        val former = original.copy(title = "旧 ID 原名")
        var completed = false
        resolveOriginalEntityBatchResults(
            listOf(request("1", lookupIds = listOf("1", "11")), request("2", lookupIds = listOf("22"))),
            mapOf("1" to direct, "11" to former, "22" to former),
            { _, _, _ -> fail("Exact identity must win") },
            { req, alias -> results[req.mediaId] = alias }, { completed = true },
        )
        assertEquals(mapOf("1" to direct, "2" to former), results)
        assertTrue(completed)
    }

    @Test fun `only missing albums use fallbacks and keep the batch slot until late results arrive`() {
        val results = linkedMapOf<String, Alias?>()
        val calls = mutableListOf<String>()
        val callbacks = mutableListOf<(Alias?) -> Unit>()
        var completed = false
        resolveOriginalEntityBatchResults(
            listOf(request("1"), request("2"), request("3"), request("4", LocalizedEntityType.SONG), request("5", LocalizedEntityType.ARTIST)),
            mapOf("2" to original),
            { _, id, done -> calls += id; callbacks += done },
            { req, alias -> results[req.mediaId] = alias }, { completed = true },
        )
        assertEquals(mapOf("2" to original, "4" to null, "5" to null), results)
        assertEquals(listOf("1"), calls)
        assertFalse(completed)
        callbacks[0](original)
        assertEquals(original, results["1"])
        assertEquals(listOf("1", "3"), calls)
        assertFalse(completed)
        callbacks[1](null)
        assertTrue(results.containsKey("3"))
        assertNull(results["3"])
        assertTrue(completed)
    }

    @Test fun `an empty exact response resolves both issue albums under their original page identities`() {
        val fantasy = Alias("范特西", "周杰伦", language, "范特西")
        val results = linkedMapOf<String, Alias?>()
        resolveOriginalEntityBatchResults(
            listOf(request("1721450027"), request("1721454234")), emptyMap(),
            { _, id, done -> done(when (id) { "1721450027" -> original; "1721454234" -> fantasy; else -> null }) },
            { req, alias -> results[req.mediaId] = alias }, {},
        )
        assertEquals(mapOf("1721450027" to original, "1721454234" to fantasy), results)
        assertFalse(results.containsKey("536114662"))
        assertFalse(results.containsKey("535739206"))
    }

    @Test fun `a former identity is tried only after the source misses and invalid ids are ignored`() {
        val calls = mutableListOf<String>()
        var result: Alias? = null
        resolveOriginalEntityBatchResults(
            listOf(request("1", lookupIds = listOf("1", "", "l.local", "2", "2"))), emptyMap(),
            { _, id, done -> calls += id; done(if (id == "2") original else null) },
            { _, alias -> result = alias }, {},
        )
        assertEquals(listOf("1", "2"), calls)
        assertEquals(original, result)
    }

    @Test fun `wrong language and absent equivalents finish without creating an original`() {
        var result: Alias? = original
        var completed = false
        resolveOriginalEntityBatchResults(
            listOf(request("1")), emptyMap(),
            { _, _, done -> done(original.copy(language = "ja-JP")) },
            { _, alias -> result = alias }, { completed = true },
        )
        assertNull(result)
        assertTrue(completed)
    }
}
