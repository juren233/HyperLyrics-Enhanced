/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

/** Resolve only the container owning this icon, never a sibling or another island. */
internal object IslandAlbumCoverHostResolver {
    fun <T : Any> find(
        source: T,
        smallIsland: Boolean,
        parent: (T) -> T?,
        resourceName: (T) -> String?,
    ): T? {
        val expected = if (smallIsland) "small_container" else "big_container"
        var current = parent(source)
        while (current != null) {
            val name = resourceName(current)
            if (name == "small_container" || name == "big_container") {
                return current.takeIf { name == expected }
            }
            current = parent(current)
        }
        return null
    }
}
