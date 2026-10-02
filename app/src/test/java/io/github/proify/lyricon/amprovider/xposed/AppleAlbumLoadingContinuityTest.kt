/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.switchMap
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Replays the original 1606 result/loadTrigger/selection pipeline using real LiveData. */
class AppleAlbumLoadingContinuityTest {
    @Before fun immediateArchitectureExecutor() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
    }
    @After fun restoreArchitectureExecutor() { ArchTaskExecutor.getInstance().setDelegate(null) }

    private class Root(val rawId: String, val catalogId: String?, val phase: String, var title: String)

    private class Pipeline(initialAlias: Alias? = null) : AutoCloseable {
        private val vm = Any()
        private val registry = AppleAlbumComposePageRegistry()
        private val selectedIds = linkedSetOf("keep-selected")
        val selection = MutableLiveData<Set<String>>(selectedIds.toSet())
        val repository = MutableLiveData(Root("l.local", null, "library", "account title"))
        private val loadTrigger = MutableLiveData(false)
        private var nativeRoot = repository.value!!
        private var lastMapped: Root? = null
        private var alias = initialAlias
        private var page: AppleAlbumComposePageRegistry.Page? = null
        var displayedTitle: String? = null
        var displayedPhase: String? = null
        var rowMappings = 0
        private val result = loadTrigger.switchMap { memory ->
            // Original result_delegate$lambda$6$lambda$5:
            // NETWORK -> loadDataFromRepo; MEMORY -> MutableLiveData(getData).
            combine(if (memory) MutableLiveData(nativeRoot) else repository, selection)
        }
        private val observer = Observer<Pair<Root, Set<String>>> { (root, _) ->
            nativeRoot = root
            val current = registry.capture(vm, "album", root, root, root.rawId, root.catalogId)!!
            page = current
            current.ids["song"] = "song"
            alias?.let { root.title = it.title; current.revision.update("song", it) }
            if (lastMapped !== root || registry.invalidates(root, nativeRoot)) {
                displayedTitle = root.title
                displayedPhase = root.phase
                rowMappings++
                current.revision.mapped(current.revision.snapshot())
                lastMapped = root
            }
        }

        init { result.observeForever(observer) }

        fun applyAlias(value: Alias) {
            alias = value
            nativeRoot.title = value.title
            if (!page!!.revision.update("song", value)) return
            val target = AppleMusicHookProfiles.exactTargets(
                AppleMusicVersion("7.0.0-beta", 1606), AppleMusicHookPoint.ALBUM_COMPOSE_REFRESH,
            ).single()
            // Both bodies are verified in album-compose-refresh-dex.txt. Drive the selected
            // runtime target, so reverting the profile to refreshData reproduces detachment.
            when (target.methodName) {
                "refreshData" -> loadTrigger.value = true
                "refreshState" -> selection.value = selectedIds.toSet()
                else -> error("Unverified native row refresh: ${target.methodName}")
            }
        }

        fun receive(phase: String) {
            repository.value = Root("album", "album", phase, "catalog title")
        }

        override fun close() { result.removeObserver(observer) }

        private fun combine(data: LiveData<Root>, selection: LiveData<Set<String>>): LiveData<Pair<Root, Set<String>>> {
            // Native UtilsKt.e combines two LiveData sources without replacing either one
            // when the same selection set is posted again.
            return MediatorLiveData<Pair<Root, Set<String>>>().also { merged ->
                var latest: Root? = null
                var selected = emptySet<String>()
                merged.addSource(data) { root -> latest = root; merged.value = root to selected }
                merged.addSource(selection) { ids -> selected = ids; latest?.let { merged.value = it to ids } }
            }
        }
    }

    @Test fun `name refresh must retain pending catalog subscription and receive the complete page`() {
        Pipeline().use { p ->
            assertTrue(p.repository.hasActiveObservers())
            p.applyAlias(Alias("原名", "歌手", "zh-Hans-CN"))
            assertEquals("原名", p.displayedTitle)
            assertTrue("Name refresh detached the in-flight catalog source", p.repository.hasActiveObservers())
            p.receive("catalog complete")
            assertEquals("catalog complete", p.displayedPhase)
            assertEquals("原名", p.displayedTitle)
            assertEquals(setOf("keep-selected"), p.selection.value)
        }
    }

    @Test fun `late original after catalog also preserves later extended page updates`() {
        Pipeline().use { p ->
            p.receive("catalog")
            p.applyAlias(Alias("original", "artist", "ja-JP"))
            assertTrue(p.repository.hasActiveObservers())
            p.receive("extended shelves complete")
            assertEquals("extended shelves complete", p.displayedPhase)
            assertEquals("original", p.displayedTitle)
        }
    }

    @Test fun `multiple name updates do not replace the loader or change the native selection`() {
        Pipeline().use { p ->
            p.applyAlias(Alias("fallback", "artist", "en-US"))
            p.applyAlias(Alias("原名", "歌手", "ja-JP"))
            assertTrue(p.repository.hasActiveObservers())
            assertEquals(setOf("keep-selected"), p.selection.value)
            p.receive("complete")
            assertEquals("原名", p.displayedTitle)
            assertEquals("complete", p.displayedPhase)
        }
    }

    @Test fun `warm aliases still allow the library snapshot to become a full catalog page`() {
        val alias = Alias("原名", "歌手", "zh-Hans-CN")
        Pipeline(alias).use { p ->
            assertEquals("原名", p.displayedTitle)
            val mappings = p.rowMappings
            p.applyAlias(alias)
            assertEquals(mappings, p.rowMappings)
            assertTrue(p.repository.hasActiveObservers())
            p.receive("complete")
            assertEquals("complete", p.displayedPhase)
            assertEquals("原名", p.displayedTitle)
        }
    }

    @Test fun `no alias leaves the native loading and presentation path intact`() {
        Pipeline().use { p ->
            assertEquals("account title", p.displayedTitle)
            assertTrue(p.repository.hasActiveObservers())
            p.receive("complete")
            assertEquals("catalog title", p.displayedTitle)
            assertEquals("complete", p.displayedPhase)
        }
    }
}
