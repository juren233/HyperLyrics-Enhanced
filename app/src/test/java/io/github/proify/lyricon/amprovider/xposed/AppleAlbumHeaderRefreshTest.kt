/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.*
import org.junit.Test

class AppleAlbumHeaderRefreshTest {
    private val original = Alias("七里香", "周杰伦", "zh-Hans-CN", "七里香")
    private val fallback = Alias("Common Jasmine Orange", "Jay Chou", "en-US")

    @Test fun `mapping song rows must not acknowledge a late album header result`() {
        val vm = Any()
        val root = Any()
        val page = AppleAlbumComposePageRegistry().capture(vm, "1", root, root, "1", "1")!!
        assertFalse(page.needsRefresh())
        page.updateAlias("1", fallback)
        page.revision.mapped(page.revision.snapshot())
        page.headerRevision.mapped(page.headerRevision.snapshot())
        assertFalse(page.needsRefresh())
        page.updateAlias("1", original)
        page.revision.mapped(page.revision.snapshot())
        assertFalse(page.revision.dirty())
        assertTrue(page.headerRevision.dirty())
        assertTrue(page.needsRefresh())
        page.headerRevision.mapped(page.headerRevision.snapshot())
        assertFalse(page.needsRefresh())
        assertFalse(page.updateAlias("1", original))
    }

    @Test fun `song-only changes do not republish artwork and album headers`() {
        val vm = Any()
        val root = Any()
        val page = AppleAlbumComposePageRegistry().capture(vm, "1", root, root, "1", "1")!!
        assertTrue(page.updateAlias("song", original.copy(title = "我的地盘")))
        assertTrue(page.revision.dirty())
        assertFalse(page.headerRevision.dirty())
    }

    @Test fun `new native root needs its own header even when the alias is already warm`() {
        val registry = AppleAlbumComposePageRegistry()
        val vm = Any()
        val first = Any()
        val page = registry.capture(vm, "1", first, first, "1", "1")!!
        page.updateAlias("1", original)
        page.headerRevision.mapped(page.headerRevision.snapshot())
        val next = Any()
        assertSame(page, registry.capture(vm, "1", next, next, "1", "1"))
        assertFalse(page.updateAlias("1", original))
        assertTrue(page.headerRevision.dirty())
        assertFalse(registry.current(page, first))
        assertTrue(registry.current(page, next))
    }

    @Test fun `late completion for a previous album cannot refresh a reused page owner`() {
        val registry = AppleAlbumComposePageRegistry()
        val vm = Any()
        val first = Any()
        val previous = registry.capture(vm, "1", first, first, "1", "1")!!
        previous.ids["1"] = "1"
        val next = Any()
        val current = registry.capture(vm, "2", next, next, "2", "2")!!
        current.ids["2"] = "2"
        assertTrue(registry.forMediaId("1").isEmpty())
        previous.updateAlias("1", original)
        assertFalse(registry.current(previous, next))
        assertFalse(current.needsRefresh())
        assertEquals(listOf(current), registry.forMediaId("2"))
    }

    @Test fun `a result arriving during header publication remains dirty`() {
        val vm = Any()
        val root = Any()
        val page = AppleAlbumComposePageRegistry().capture(vm, "1", root, root, "1", "1")!!
        page.updateAlias("1", fallback)
        val publishing = page.headerRevision.snapshot()
        page.updateAlias("1", original)
        page.headerRevision.mapped(publishing)
        assertTrue(page.headerRevision.dirty())
    }

    @Test fun `publication for an old root cannot acknowledge a replacement root or lose its warm alias`() {
        val registry = AppleAlbumComposePageRegistry()
        val vm = Any()
        val first = Any()
        val page = registry.capture(vm, "1", first, first, "1", "1")!!
        page.updateAlias("1", original)
        val publishing = page.headerRevision.snapshot()
        val replacement = Any()
        registry.capture(vm, "1", replacement, replacement, "1", "1")
        page.headerRevision.mapped(publishing)
        assertTrue(page.headerRevision.dirty())
        page.headerRevision.mapped(page.headerRevision.snapshot())
        registry.capture(vm, "1", first, first, "1", "1")
        assertTrue(page.headerRevision.dirty())
        assertFalse(page.updateAlias("1", original))
    }
}
