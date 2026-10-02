/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap

/** A scope can display several cards; invalidating it never writes a previous card's text. */
internal class AppleBrowseScopeRegistry(private val limit: Int = 512) {
    private class Entry(var value: Any?, val scopes: MutableList<WeakReference<Any>> = mutableListOf())
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun record(id: String, scope: Any, value: Any?) {
        val entry = entries.getOrPut(id) { Entry(value) }
        entry.scopes.removeAll { it.get() == null }
        if (entry.scopes.none { it.get() === scope }) entry.scopes += WeakReference(scope)
        while (entries.size > limit) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun changed(id: String, value: Any?): List<Any> {
        val entry = entries[id] ?: return emptyList()
        if (entry.value == value) return emptyList()
        entry.value = value
        entry.scopes.removeAll { it.get() == null }
        return entry.scopes.mapNotNull { it.get() }
    }

    @Synchronized
    fun clear(): List<Any> {
        val scopes = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        entries.values.forEach { entry -> entry.scopes.mapNotNullTo(scopes) { it.get() } }
        entries.clear()
        return scopes.toList()
    }
}

internal data class AppleStationArtistCandidate(
    val artistId: String,
    val artistName: String,
    val stationIds: Set<String>,
)

/** Names only find candidates. Only the catalog's reciprocal station relationship proves identity. */
internal fun verifiedStationArtist(
    stationId: String,
    candidates: List<AppleStationArtistCandidate>,
): AppleStationArtistCandidate? = candidates.filter {
    it.artistId.isNotEmpty() && it.artistId.all(Char::isDigit) && stationId in it.stationIds
}.distinctBy { it.artistId }.singleOrNull()

internal fun stationOriginalTitle(
    nativeTitle: String,
    verifiedArtistName: String?,
    alias: Alias?,
): String? {
    if (verifiedArtistName.isNullOrBlank() || nativeTitle.trim() != verifiedArtistName.trim()) return null
    return alias?.artist?.takeIf(String::isNotBlank) ?: alias?.title?.takeIf(String::isNotBlank)
}
