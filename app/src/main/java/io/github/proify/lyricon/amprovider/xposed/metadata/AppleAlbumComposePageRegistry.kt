/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** A library root and its later catalog response are one page, even when Flow retains old rows. */
internal class AppleAlbumComposePageRegistry {
    class Page(
        vm: Any,
        val ownerId: String?,
        root: Any,
        rawId: String?,
        catalogId: String?,
    ) {
        val vm = WeakReference(vm)
        @Volatile var root: Any = root
            internal set
        @Volatile var rawId: String? = rawId
            internal set
        @Volatile var catalogId: String? = catalogId
            internal set
        val id: String get() = ownerId ?: catalogId ?: rawId.orEmpty()
        val ids = ConcurrentHashMap<String, String>()
        val registered = WeakIdentityMap<Any, Boolean>()
        val revision = AppleAlbumRowRevision()
        val requested = mutableSetOf<String>() // main thread only
        var queued = false // main thread only
    }

    data class Row(val page: WeakReference<Page>, val id: String)
    private val pages = WeakHashMap<Any, Page>()
    private val roots = WeakIdentityMap<Any, MutableList<WeakReference<Page>>>()
    private val rows = WeakIdentityMap<Any, Row>()

    @Synchronized
    fun capture(
        vm: Any,
        ownerId: String?,
        root: Any,
        nativeRoot: Any?,
        rawId: String?,
        catalogId: String?,
    ): Page? {
        if (nativeRoot !== root) return null
        val owner = ownerId?.takeIf(String::isNotBlank)
        val page = pages[vm]?.takeIf { it.ownerId == owner }
            ?: Page(vm, owner, root, rawId, catalogId).also { pages[vm] = it }
        // Catalog identity is optional for a root. Each song retains its own lookup identity.
        // Keep the Page itself: StateFlow may keep the first equal TrackDisplayItem instance.
        page.root = root
        page.rawId = rawId
        page.catalogId = catalogId
        val owners = roots[root] ?: mutableListOf<WeakReference<Page>>().also { roots[root] = it }
        owners.removeAll { it.get() == null }
        if (owners.none { it.get() === page }) owners += WeakReference(page)
        return page
    }

    @Synchronized
    fun current(page: Page, nativeRoot: Any?): Boolean {
        val vm = page.vm.get() ?: return false
        return pages[vm] === page && page.root === nativeRoot
    }

    fun invalidates(root: Any, nativeRoot: Any?): Boolean = invalidates(root) { nativeRoot }

    fun invalidates(root: Any, nativeRootFor: (Page) -> Any?): Boolean = owners(root).any { page ->
        page.root === root && page.revision.dirty() && current(page, nativeRootFor(page))
    }

    @Synchronized
    private fun owners(root: Any): List<Page> = roots[root]?.mapNotNull { it.get() }.orEmpty()

    fun bindRow(page: Page, row: Any, rawId: String?) {
        val id = rawId?.let { page.ids[it] } ?: return
        rows[row] = Row(WeakReference(page), id)
    }

    fun row(row: Any): Row? = rows[row]
    @Synchronized fun snapshot(): List<Page> = pages.values.toList()
    @Synchronized fun forMediaId(id: String): List<Page> = pages.values.filter { id in it.ids.values }
}
