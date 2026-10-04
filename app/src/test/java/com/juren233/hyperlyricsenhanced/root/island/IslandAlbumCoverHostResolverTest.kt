/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.*
import org.junit.Test

class IslandAlbumCoverHostResolverTest {
    private class Node(val name: String?, var parent: Node? = null)

    private fun find(source: Node, small: Boolean = false) = IslandAlbumCoverHostResolver.find(
        source, small, parent = { it.parent }, resourceName = { it.name },
    )

    @Test fun RealIconBindFindsItsBigBackgroundWithoutHolderGetters() {
        val big = Node("big_container")
        val area = Node("area_left", big)
        val module = Node("island_container_module_image_text_1", area)
        val iconContainer = Node("island_container_module_icon", module)
        assertSame(big, find(Node("island_fix_icon", iconContainer)))
    }

    @Test fun SmallIslandFindsItsOwnFrameWithoutMeasuredDimensions() {
        val small = Node("small_container")
        assertSame(small, find(Node("island_fix_icon", Node("icon_container", small)), small = true))
    }

    @Test fun ReparentedIconUsesTheNewOwnerOnTheNextBind() {
        val old = Node("big_container")
        val current = Node("big_container")
        val module = Node(null, old)
        val icon = Node("island_fix_icon", module)
        assertSame(old, find(icon))
        module.parent = current
        assertSame(current, find(icon))
    }

    @Test fun AForeignOrDetachedIconDoesNotSelectAnotherIsland() {
        val foreignRoot = Node("notification_root")
        Node("big_container", foreignRoot) // A sibling cannot be selected.
        val icon = Node("island_fix_icon", Node(null, foreignRoot))
        assertNull(find(icon))
        icon.parent = null
        assertNull(find(icon))
    }

    @Test fun NearestDifferentRoleStopsResolution() {
        val outer = Node("big_container")
        val small = Node("small_container", outer)
        val icon = Node("island_fix_icon", small)
        assertNull(find(icon))
        assertSame(small, find(icon, small = true))
    }
}
