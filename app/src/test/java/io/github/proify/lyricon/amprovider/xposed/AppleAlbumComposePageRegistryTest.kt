/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AppleAlbumComposePageRegistryTest {
    private data class NativeRow(val id: String, val title: String, val artist: String)
    private data class Example(val album: String, val song: String, val title: String, val artist: String)
    private val examples = listOf(
        Example("1722205323", "1722205331", "忽然之間", "Karen Mok"),
        Example("1882935769", "1882935962", "Michi Teyu Ku (Overflowing)", "Fujii Kaze"),
        Example("914664926", "914664935", "Angel", "David Tao"),
        Example("152197399", "152197590", "太聪明", "陈绮贞"),
    )

    @Test fun `first library roots without catalog IDs still own each actual song before catalog arrives`() {
        examples.forEach { e ->
            val registry = AppleAlbumComposePageRegistry()
            val vm = Any()
            val library = Any()
            val page = registry.capture(vm, e.album, library, library, "l.local", null)
            assertNotNull("The library root must not block its songs: ${e.album}", page)
            page!!
            assertNull(page.catalogId)
            assertEquals(e.album, page.id)
            page.ids[e.song] = e.song
            val row = NativeRow(e.song, e.title, e.artist)
            registry.bindRow(page, row, row.id)
            assertEquals(e.song, registry.row(row)?.id)
            assertSame(page, registry.row(row)?.page?.get())
            assertTrue(registry.current(page, library))
            assertTrue(registry.forMediaId(e.album).isEmpty())
            assertEquals(listOf(page), registry.forMediaId(e.song))
        }
    }

    @Test fun `equal catalog rows retained by StateFlow remain queryable through first row ownership`() {
        examples.forEach { e ->
            val registry = AppleAlbumComposePageRegistry()
            val vm = Any()
            val library = Any()
            val first = registry.capture(vm, e.album, library, library, "l.local", null)!!
            first.ids[e.song] = e.song
            val libraryRow = NativeRow(e.song, e.title, e.artist)
            registry.bindRow(first, libraryRow, libraryRow.id)
            val display = MutableStateFlow(listOf(libraryRow))

            val catalog = Any()
            val next = registry.capture(vm, e.album, catalog, catalog, e.album, e.album)!!
            val catalogRow = libraryRow.copy()
            registry.bindRow(next, catalogRow, catalogRow.id)
            display.value = listOf(catalogRow)
            assertSame(libraryRow, display.value.single()) // Actual StateFlow equality contract.
            assertSame(first, next)
            val bound = registry.row(display.value.single())!!
            assertSame(next, bound.page.get())
            assertEquals(e.song, bound.id)
            assertTrue(registry.current(bound.page.get()!!, catalog))
            assertFalse(registry.current(bound.page.get()!!, library))
        }
    }

    @Test fun `late original updates the retained row through current native root and subsequent mapping`() {
        val e = examples.first()
        val registry = AppleAlbumComposePageRegistry()
        val vm = Any()
        val library = Any()
        val page = registry.capture(vm, e.album, library, library, "l.local", null)!!
        page.ids[e.song] = e.song
        val firstRow = NativeRow(e.song, e.title, e.artist)
        registry.bindRow(page, firstRow, e.song)
        val display = MutableStateFlow(listOf(firstRow))
        val catalog = Any()
        registry.capture(vm, e.album, catalog, catalog, e.album, e.album)
        display.value = listOf(firstRow.copy())

        val requestId = registry.row(display.value.single())!!.id
        val primary = Alias("忽然之间", "莫文蔚", "zh-Hans-CN")
        val targets = registry.forMediaId(requestId)
        assertEquals(listOf(page), targets)
        targets.forEach { it.revision.update(requestId, primary) }
        assertTrue(registry.invalidates(catalog, catalog))
        assertFalse(registry.invalidates(library, catalog))
        val token = page.revision.snapshot()
        val rebuilt = NativeRow(requestId, primary.title, primary.artist)
        registry.bindRow(page, rebuilt, requestId)
        page.revision.mapped(token)
        display.value = listOf(rebuilt)
        assertEquals("莫文蔚", display.value.single().artist)
        assertEquals("忽然之间", display.value.single().title)
        assertSame(page, registry.row(display.value.single())!!.page.get())
        assertFalse(registry.invalidates(catalog, catalog))
    }

    @Test fun `warm aliases and absent aliases do not require an artificial same-root refresh`() {
        val registry = AppleAlbumComposePageRegistry()
        val vm = Any()
        val library = Any()
        val page = registry.capture(vm, "album", library, library, "l.local", null)!!
        assertFalse(registry.invalidates(library, library))
        page.revision.update("song", Alias("原名", "歌手", "zh-Hans-CN"))
        page.revision.mapped(page.revision.snapshot())
        assertFalse(registry.invalidates(library, library))
        val catalog = Any()
        assertSame(page, registry.capture(vm, "album", catalog, catalog, "album", "album"))
        assertFalse(registry.invalidates(catalog, catalog))
    }

    @Test fun `stale native inputs cannot move a page back or erase a pending original result`() {
        val registry = AppleAlbumComposePageRegistry()
        val vm = Any()
        val stale = Any()
        val current = Any()
        val page = registry.capture(vm, "album", current, current, "album", "album")!!
        page.revision.update("song", Alias("原名", "歌手", "zh-Hans-CN"))
        assertNull(registry.capture(vm, "album", stale, current, "l.old", null))
        assertSame(current, page.root)
        assertTrue(registry.invalidates(current, current))
    }

    @Test fun `view model reuse for another album invalidates old row callbacks even for shared songs`() {
        val registry = AppleAlbumComposePageRegistry()
        val vm = Any()
        val a = Any()
        val pageA = registry.capture(vm, "A", a, a, "l.A", null)!!
        pageA.ids["song"] = "song"
        val rowA = NativeRow("song", "title", "artist")
        registry.bindRow(pageA, rowA, "song")
        val b = Any()
        val pageB = registry.capture(vm, "B", b, b, "l.B", null)!!
        assertNotSame(pageA, pageB)
        assertFalse(registry.current(registry.row(rowA)!!.page.get()!!, b))
        assertTrue(registry.current(pageB, b))
        pageA.revision.update("song", Alias("旧页", "歌手", "zh-Hans-CN"))
        assertFalse(registry.invalidates(a, b))
        assertTrue(registry.forMediaId("song").isEmpty())
    }

    @Test fun `different fragments with equal native rows retain independent identity ownership`() {
        val registry = AppleAlbumComposePageRegistry()
        val vmA = Any()
        val vmB = Any()
        val a = Any()
        val b = Any()
        val pageA = registry.capture(vmA, "album", a, a, "l.A", null)!!
        val pageB = registry.capture(vmB, "album", b, b, "album", "album")!!
        pageA.ids["song"] = "song"
        pageB.ids["song"] = "song"
        val rowA = NativeRow("song", "title", "artist")
        val rowB = rowA.copy()
        registry.bindRow(pageA, rowA, "song")
        registry.bindRow(pageB, rowB, "song")
        assertSame(pageA, registry.row(rowA)!!.page.get())
        assertSame(pageB, registry.row(rowB)!!.page.get())
        assertEquals(setOf(pageA, pageB), registry.forMediaId("song").toSet())
    }

    @Test fun `unknown track identity never falls back to album or now playing IDs`() {
        val registry = AppleAlbumComposePageRegistry()
        val vm = Any()
        val root = Any()
        val page = registry.capture(vm, "album", root, root, "album", "album")!!
        page.ids["album"] = "album"
        page.ids["playing"] = "playing"
        val unknown = NativeRow("unknown", "title", "artist")
        registry.bindRow(page, unknown, "unknown")
        assertNull(registry.row(unknown))
        registry.bindRow(page, unknown, null)
        assertNull(registry.row(unknown))
    }

    @Test fun `missing navigation ID still scopes ownership to the same view model only`() {
        val registry = AppleAlbumComposePageRegistry()
        val vm = Any()
        val library = Any()
        val page = registry.capture(vm, null, library, library, "l.local", null)!!
        val catalog = Any()
        assertSame(page, registry.capture(vm, null, catalog, catalog, "catalog", "catalog"))
        assertEquals("catalog", page.id)
        val otherVm = Any()
        assertNotSame(page, registry.capture(otherVm, null, catalog, catalog, "catalog", "catalog"))
    }

    @Test fun `shared native root cannot let a clean second page mask the dirty first page`() {
        val registry = AppleAlbumComposePageRegistry()
        val vmA = Any()
        val vmB = Any()
        val root = Any()
        val first = registry.capture(vmA, "album", root, root, "album", "album")!!
        val second = registry.capture(vmB, "album", root, root, "album", "album")!!
        first.revision.update("song", Alias("原名", "歌手", "zh-Hans-CN"))
        assertTrue(registry.invalidates(root, root))
        assertFalse(second.revision.dirty())
        first.revision.mapped(first.revision.snapshot())
        assertFalse(registry.invalidates(root, root))
        second.revision.update("song", Alias("原名", "歌手", "zh-Hans-CN"))
        assertTrue(registry.invalidates(root, root))
        // The second VM has moved to an unobserved native root; its old alias must not replay it.
        val moved = Any()
        assertFalse(registry.invalidates(root) { if (it === second) moved else root })
    }

}
