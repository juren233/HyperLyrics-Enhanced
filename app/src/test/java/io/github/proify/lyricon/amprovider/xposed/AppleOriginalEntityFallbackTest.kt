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
 * Issue #45 第二次复现：国区目录整首省略条目（例如 `1821210440`、`1466039271`），
 * 同一 ID 在港澳台仍有正式中文名，因此主地区空条目必须按 cn → hk → tw → mo 回退。
 */
class AppleOriginalEntityFallbackTest {
    private val chineseLanguage = "zh-Hans-CN"
    private val traditional = Alias("六月飛霜", "陳奕迅", chineseLanguage, "Stranger Under My Skin")

    private fun request(
        id: String,
        kind: LocalizedEntityType = LocalizedEntityType.SONG,
        language: String = chineseLanguage,
        lookupIds: List<String> = listOf(id),
    ) = OriginalEntityRequest(
        "$kind:$language:$id", id, lookupIds, kind, language, "cn",
        originalDirectEntityCacheKey(kind, id), RequestPriority.VISIBLE, emptyList(),
    )

    @Test
    fun `Chinese original names fall back from the mainland to Hong Kong, Taiwan and Macau`() {
        assertEquals(
            listOf("cn", "hk", "tw", "mo"),
            originalEntityStorefrontCandidates(chineseLanguage).map { it.storefront },
        )
        assertEquals(
            listOf("cn", "hk", "tw", "mo"),
            originalEntityStorefrontCandidates("zh-Hant-HK").map { it.storefront },
        )
        assertEquals(
            listOf("zh-Hant-MO"),
            originalEntityStorefrontCandidates("zh-Hant-MO")
                .filter { it.storefront == "mo" }
                .map { it.language },
        )
        assertEquals(
            listOf("zh-Hant-TW"),
            originalEntityStorefrontCandidates("zh-Hant-TW")
                .filter { it.storefront == "tw" }
                .map { it.language },
        )
        assertEquals(
            listOf("jp"),
            originalEntityStorefrontCandidates("ja-JP").map { it.storefront },
        )
        assertEquals(
            listOf("kr"),
            originalEntityStorefrontCandidates("ko-KR").map { it.storefront },
        )
        assertTrue(originalEntityStorefrontCandidates("current").isEmpty())
    }

    @Test
    fun `Macau results normalize to the mainland language key like Hong Kong and Taiwan`() {
        val fromMacau = Alias("六月飛霜", "陳奕迅", "zh-Hant-MO", "Stranger Under My Skin")
        val normalized = normalizeOriginalEntityAlias(fromMacau)

        assertEquals(chineseLanguage, normalized.language)
        assertTrue(isAcceptableOriginalAlias(normalized, chineseLanguage))
        // 澳门商店的数字 storefront id（143515）必须能随账号后缀一起改写，否则回退请求仍会被发到账号地区。
        assertEquals(
            "143515-19,29",
            localizedStorefrontHeaderValue(storefront = "mo", currentValue = "143465-19,29"),
        )
    }

    @Test
    fun `an absent mainland entry resolves through Hong Kong under the original page id`() {
        val queried = mutableListOf<String>()
        val resolved = mutableListOf<Triple<String, Alias, List<String>>>()
        var completion: Triple<List<OriginalEntityRequest>, Boolean, List<String>>? = null
        resolveOriginalEntityAcrossStorefronts(
            requests = listOf(request("1821210440")),
            candidates = originalEntityStorefrontCandidates(chineseLanguage),
            query = { candidate, _, done ->
                queried += candidate.storefront
                done(
                    if (candidate.storefront == "hk") {
                        mapOf("1821210440" to normalizeOriginalEntityAlias(traditional))
                    } else {
                        emptyMap()
                    },
                )
            },
            onResolved = { request, alias, storefronts ->
                resolved += Triple(request.mediaId, alias, storefronts)
            },
            onComplete = { unresolved, confirmed, storefronts ->
                completion = Triple(unresolved, confirmed, storefronts)
            },
        )

        assertEquals(listOf("cn", "hk"), queried)
        assertEquals(listOf(Triple("1821210440", traditional, listOf("cn", "hk"))), resolved)
        assertTrue(isAcceptableOriginalAlias(traditional, chineseLanguage))
        assertTrue(requireNotNull(completion).first.isEmpty())
    }

    @Test
    fun `mainland hits publish before fallback and later storefronts query only missing ids`() {
        val events = mutableListOf<String>()
        val latin = Alias("Stranger Under My Skin", "Eason Chan", chineseLanguage)
        resolveOriginalEntityAcrossStorefronts(
            requests = listOf(request("1821210441"), request("1821210440")),
            candidates = originalEntityStorefrontCandidates(chineseLanguage),
            query = { candidate, lookupIds, done ->
                events += "query:${candidate.storefront}:${lookupIds.joinToString(",")}"
                done(
                    when (candidate.storefront) {
                        "cn" -> mapOf("1821210441" to latin)
                        "hk" -> mapOf("1821210440" to normalizeOriginalEntityAlias(traditional))
                        else -> emptyMap()
                    },
                )
            },
            onResolved = { request, _, storefronts ->
                events += "resolved:${request.mediaId}:${storefronts.joinToString(",")}"
            },
            onComplete = { unresolved, _, _ -> events += "complete:${unresolved.size}" },
        )

        assertEquals(
            listOf(
                "query:cn:1821210441,1821210440",
                "resolved:1821210441:cn",
                "query:hk:1821210440",
                "resolved:1821210440:cn,hk",
                "complete:0",
            ),
            events,
        )
    }

    @Test
    fun `a mainland entry never triggers a second storefront query`() {
        val queried = mutableListOf<String>()
        val resolved = mutableListOf<Alias>()
        val latin = Alias("Stranger Under My Skin", "Eason Chan", chineseLanguage)
        resolveOriginalEntityAcrossStorefronts(
            requests = listOf(request("1821210441")),
            candidates = originalEntityStorefrontCandidates(chineseLanguage),
            query = { candidate, _, done ->
                queried += candidate.storefront
                done(mapOf("1821210441" to latin))
            },
            onResolved = { _, alias, _ -> resolved += alias },
            onComplete = { _, _, _ -> },
        )

        assertEquals(listOf("cn"), queried)
        assertEquals(listOf(latin), resolved)
    }

    @Test
    fun `every candidate may miss and the batch still completes exactly once`() {
        val queried = mutableListOf<String>()
        var completions = 0
        var unresolvedIds: List<String>? = null
        var confirmedAbsent: Boolean? = null
        resolveOriginalEntityAcrossStorefronts(
            requests = listOf(request("1821210448"), request("5", LocalizedEntityType.ALBUM)),
            candidates = originalEntityStorefrontCandidates(chineseLanguage),
            query = { candidate, _, done ->
                queried += candidate.storefront
                done(emptyMap())
            },
            onResolved = { _, _, _ -> error("nothing should resolve") },
            onComplete = { unresolved, confirmed, storefronts ->
                completions += 1
                unresolvedIds = unresolved.map(OriginalEntityRequest::mediaId)
                confirmedAbsent = confirmed
                assertEquals(listOf("cn", "hk", "tw", "mo"), storefronts)
            },
        )

        assertEquals(listOf("cn", "hk", "tw", "mo"), queried)
        assertEquals(1, completions)
        assertEquals(listOf("1821210448", "5"), unresolvedIds)
        assertEquals(true, confirmedAbsent)
    }

    @Test
    fun `a failed storefront request is never reported as a confirmed absence`() {
        var confirmedAbsent: Boolean? = null
        resolveOriginalEntityAcrossStorefronts(
            requests = listOf(request("1466039271")),
            candidates = originalEntityStorefrontCandidates(chineseLanguage),
            query = { candidate, _, done ->
                done(if (candidate.storefront == "tw") null else emptyMap())
            },
            onResolved = { _, _, _ -> },
            onComplete = { _, confirmed, _ -> confirmedAbsent = confirmed },
        )

        assertEquals(false, confirmedAbsent)
    }

    @Test
    fun `confirmed misses expire after the ttl and can be forgotten per entity`() {
        var now = 0L
        val cache = OriginalEntityMissCache(ttlMs = 1_000L, maxSize = 2, now = { now })
        val songKey = originalDirectEntityCacheKey(LocalizedEntityType.SONG, "1466039271")
        val missKey = originalEntityMissKey(songKey, chineseLanguage)

        assertFalse(cache.isFresh(missKey))
        cache.remember(missKey)
        now = 999L
        assertTrue(cache.isFresh(missKey))
        assertFalse(cache.isFresh(originalEntityMissKey(songKey, "ja-JP")))
        now = 1_000L
        assertFalse(cache.isFresh(missKey))

        cache.remember(missKey)
        cache.forgetEndingWith("|$songKey")
        assertFalse(cache.isFresh(missKey))

        cache.remember("a")
        cache.remember("b")
        cache.remember("c")
        assertFalse(cache.isFresh("a"))
        assertTrue(cache.isFresh("c"))
    }

    @Test
    fun `Hong Kong results are normalized so they are not dropped as legacy regional cache`() {
        val fromHongKong = Alias("六月飛霜", "陳奕迅", "zh-Hant-HK", "Stranger Under My Skin")
        val normalized = normalizeOriginalEntityAlias(fromHongKong)

        assertEquals(chineseLanguage, normalized.language)
        assertNull(canonicalCachedOriginalAlias(fromHongKong))
        assertEquals(normalized, canonicalCachedOriginalAlias(normalized))
        assertTrue(isAcceptableOriginalAlias(normalized, chineseLanguage))
        // 港台标签在语义上可用，但会被遗留缓存校验丢弃，所以必须先归一。
        assertTrue(isAcceptableOriginalAlias(fromHongKong, chineseLanguage))
    }
}
