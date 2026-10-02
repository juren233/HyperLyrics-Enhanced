/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

internal data class OriginalCacheValidation(
    val hit: AppleOriginalMetadataCache.CacheHit?,
    val rejected: List<AppleOriginalMetadataCache.CacheHit>,
)

/** Only examines the requested identity/language keys, never unrelated regional entries. */
internal fun validateOriginalCacheEntries(
    keys: Collection<String>,
    aliases: Map<String, Alias>,
    accept: (Alias) -> Boolean,
): OriginalCacheValidation {
    var hit: AppleOriginalMetadataCache.CacheHit? = null
    val rejected = mutableListOf<AppleOriginalMetadataCache.CacheHit>()
    keys.forEach { key ->
        val stored = aliases[key] ?: return@forEach
        val canonical = canonicalCachedOriginalAlias(stored)?.takeIf(accept)
        if (canonical == null) {
            rejected += AppleOriginalMetadataCache.CacheHit(key, stored)
        } else if (hit == null) {
            hit = AppleOriginalMetadataCache.CacheHit(key, canonical)
        }
    }
    return OriginalCacheValidation(hit, rejected)
}

/** A delayed rejection must not erase a replacement published after the read. */
internal fun removeOriginalAliasIfUnchanged(
    aliases: MutableMap<String, Alias>,
    rejected: AppleOriginalMetadataCache.CacheHit,
): Boolean {
    if (aliases[rejected.key] != rejected.alias) return false
    aliases.remove(rejected.key)
    return true
}
