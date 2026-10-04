/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.*
import org.junit.Test

class AppleBrowseMetadataTest {
    private fun alias(name: String) = Alias("", name, "zh-Hans-CN")

    @Test fun `station source selection covers primary fallback both and neither`() {
        assertEquals("薛之谦", stationDisplayTitle("Joker Xue", "薛之谦", null))
        assertEquals("薛之謙", stationDisplayTitle("Joker Xue", null, "薛之謙"))
        assertEquals("薛之谦", stationDisplayTitle("Joker Xue", "薛之谦", "薛之謙"))
        assertEquals("Joker Xue", stationDisplayTitle("Joker Xue", null, null))
        assertEquals("Joker Xue", stationDisplayTitle("Joker Xue", " ", ""))
    }

    @Test fun `late original invalidates a card even when fallback already rendered`() {
        val registry = AppleBrowseScopeRegistry()
        val card = Any()
        registry.record("station:ra.123", card, "Joker Xue")
        assertEquals(listOf(card), registry.changed("station:ra.123", "薛之謙"))
        assertEquals(listOf(card), registry.changed("station:ra.123", "薛之谦"))
        assertTrue(registry.changed("station:ra.123", "薛之谦").isEmpty())
    }

    @Test fun `same artist spelling without reciprocal station identity is rejected`() {
        assertNull(verifiedStationArtist("ra.10", listOf(AppleStationArtistCandidate("10", "Joker Xue", setOf("ra.20")))))
        assertNull(verifiedStationArtist("ra.10", listOf(AppleStationArtistCandidate("10", "Joker Xue", emptySet()))))
    }

    @Test fun `station numeric suffix is not an artist identity`() {
        val actual = AppleStationArtistCandidate("777", "Joker Xue", setOf("ra.10"))
        assertEquals(actual, verifiedStationArtist("ra.10", listOf(AppleStationArtistCandidate("10", "Joker Xue", setOf("ra.11")), actual)))
    }

    @Test fun `multiple reciprocal artist identities are rejected`() {
        assertNull(verifiedStationArtist("ra.10", listOf(
            AppleStationArtistCandidate("11", "One", setOf("ra.10")),
            AppleStationArtistCandidate("12", "Two", setOf("ra.10")),
        )))
    }

    @Test fun `a repeated candidate with the same exact identity is not ambiguous`() {
        val artist = AppleStationArtistCandidate("11", "One", setOf("ra.10"))
        assertEquals(artist, verifiedStationArtist("ra.10", listOf(artist, artist)))
    }

    @Test fun `invalid and library artist IDs do not become catalog artists`() {
        listOf("", "l.111", "ra.10", "11x").forEach { id ->
            assertNull(verifiedStationArtist("ra.10", listOf(AppleStationArtistCandidate(id, "One", setOf("ra.10")))))
        }
    }

    @Test fun `direct artist substitution only covers a proven plain artist title`() {
        assertEquals("薛之谦", stationOriginalTitle("Joker Xue", "Joker Xue", alias("薛之谦")))
        assertNull(stationOriginalTitle("Joker Xue and friends", "Joker Xue", alias("薛之谦")))
        assertNull(stationOriginalTitle("Joker Xue", null, alias("薛之谦")))
        assertNull(stationOriginalTitle("Joker Xue", "Joker Xue", null))
    }

    @Test fun `one lazy scope can display several independent items`() {
        val registry = AppleBrowseScopeRegistry()
        val scope = Any()
        registry.record("10", scope, alias("First"))
        registry.record("20", scope, alias("Second"))
        assertEquals(listOf(scope), registry.changed("10", alias("第一")))
        assertEquals(listOf(scope), registry.changed("20", alias("第二")))
        assertTrue(registry.changed("30", alias("第三")).isEmpty())
    }

    @Test fun `same entity on two pages updates both consumers exactly once`() {
        val registry = AppleBrowseScopeRegistry()
        val home = Any()
        val radio = Any()
        registry.record("10", home, null)
        registry.record("10", home, null)
        registry.record("10", radio, null)
        assertEquals(listOf(home, radio), registry.changed("10", alias("薛之谦")))
        assertTrue(registry.changed("10", alias("薛之谦")).isEmpty())
    }

    @Test fun `switching entities keeps each item's source availability independent`() {
        val registry = AppleBrowseScopeRegistry()
        val first = Any()
        val second = Any()
        registry.record("song:10", first, alias("原名"))
        registry.record("song:20", second, null)
        assertTrue(registry.changed("song:10", alias("原名")).isEmpty())
        assertEquals(listOf(second), registry.changed("song:20", alias("后到原名")))
    }

    @Test fun `configuration reset invalidates consumers and discards old source decisions`() {
        val registry = AppleBrowseScopeRegistry()
        val card = Any()
        registry.record("10", card, alias("原名"))
        registry.record("20", card, alias("地区名"))
        assertEquals(listOf(card), registry.clear())
        assertTrue(registry.changed("10", alias("迟到旧结果")).isEmpty())
        registry.record("10", card, null)
        assertEquals(listOf(card), registry.changed("10", alias("新配置结果")))
    }

    @Test fun `scope registry bounds retained identities`() {
        val registry = AppleBrowseScopeRegistry(2)
        val first = Any(); val second = Any(); val third = Any()
        registry.record("10", first, null)
        registry.record("20", second, null)
        registry.record("30", third, null)
        assertTrue(registry.changed("10", alias("old")).isEmpty())
        assertEquals(listOf(third), registry.changed("30", alias("new")))
    }

    @Test fun `recycled or shifted search model must not be notified using an old position`() {
        data class Model(val id: String)
        val previous = Model("10")
        assertTrue(isCurrentBrowseRow(previous, previous))
        assertFalse(isCurrentBrowseRow(previous, Model("10")))
        assertFalse(isCurrentBrowseRow(previous, Model("20")))
        assertFalse(isCurrentBrowseRow(null, null))
    }

    @Test fun `700 renderer profiles use original DEX names and descriptors only`() {
        val version = AppleMusicVersion("7.0.0-beta", 1606L)
        val renderers = AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.BROWSE_COMPOSE_ITEM)
        assertEquals(setOf("Yb.f", "O9.j", "oa.i", "oa.s", "Yb.n"), renderers.map { it.className }.toSet())
        renderers.forEach {
            assertEquals("a", it.methodName)
            assertEquals(listOf("O0.j", "int", "java.util.List", "W8.d", "boolean", "java.lang.String", "pi.q", "pi.p", "boolean", "z0.m", "int"), it.parameterTypeNames)
            assertEquals(false, it.isStatic)
            assertEquals("void", it.returnTypeName)
        }
        val scope = AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE).single()
        assertEquals("z0.m", scope.className); assertEquals("y", scope.methodName); assertEquals("z0.R0", scope.returnTypeName)
        val notify = AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.RECYCLER_NOTIFY_ITEM_CHANGED).single()
        assertEquals("androidx.recyclerview.widget.RecyclerView\$f", notify.className)
        assertEquals("h", notify.methodName); assertEquals(listOf("int"), notify.parameterTypeNames)
    }

    @Test fun `isolated station search retains exact native callback and constructor member types`() {
        val version = AppleMusicVersion("7.0.0-beta", 1606L)
        val session = AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.RADIO_SEARCH_SESSION).single()
        assertEquals("com.apple.android.music.mediaapi.repository.MediaApiSearchSessionImpl", session.className)
        assertEquals("Oj.F", session.runtimeMemberName(AppleMusicRuntimeMember.RADIO_SEARCH_SCOPE_CLASS))
        assertEquals("w9.a", session.runtimeMemberName(AppleMusicRuntimeMember.RADIO_SEARCH_MEDIA_API_CLASS))
        assertEquals("getCoroutineContext", session.runtimeMemberName(AppleMusicRuntimeMember.RADIO_SEARCH_SCOPE_CONTEXT_METHOD))
        val result = AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.RADIO_SEARCH_RESULT).single()
        assertEquals("postSearchCatalogueResults", result.methodName)
        assertEquals(listOf("com.apple.android.music.mediaapi.repository.MediaApiSearchResultsResponse", "z9.f", "com.apple.android.music.mediaapi.repository.MediaApiRepository\$CATALOGUE_TYPE", "com.apple.android.music.mediaapi.repository.MediaApiRepository\$SearchSessionType"), result.parameterTypeNames)
    }

    @Test fun `700 browse and search profiles do not claim compatibility with older or unverified beta builds`() {
        listOf(AppleMusicVersion("6.5.3", 1599L), AppleMusicVersion("7.0.0-beta", 1608L)).forEach { version ->
            listOf(AppleMusicHookPoint.BROWSE_COMPOSE_ITEM, AppleMusicHookPoint.RADIO_SEARCH_SESSION, AppleMusicHookPoint.SEARCH_RESULTS_MODEL_BOUND).forEach { point ->
                assertTrue(AppleMusicHookProfiles.exactTargets(version, point).isEmpty())
            }
        }
    }
}
