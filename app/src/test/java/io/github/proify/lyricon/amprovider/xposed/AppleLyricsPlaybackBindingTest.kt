/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.*
import org.junit.Test
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppleLyricsPlaybackBindingTest {
    @Test
    fun `empty binding has no reload targets`() {
        assertEquals(AppleLyricsPlaybackBinding.Snapshot(null, null), AppleLyricsPlaybackBinding().snapshot())
    }

    @Test
    fun `repeat and a b a loads replace the complete pair without rejecting queue catchup`() {
        val binding = AppleLyricsPlaybackBinding()
        val aViewModel = Any()
        val aItem = Any()
        val bViewModel = Any()
        val bItem = Any()
        binding.rememberLoad(aViewModel, aItem)
        val first = binding.snapshot()
        binding.rememberLoad(aViewModel, aItem)
        assertEquals(first, binding.snapshot())
        binding.rememberLoad(bViewModel, bItem)
        assertEquals(AppleLyricsPlaybackBinding.Snapshot(bViewModel, bItem), binding.snapshot())
        binding.rememberLoad(aViewModel, aItem)
        assertEquals(first, binding.snapshot())
    }

    @Test
    fun `same item accepts replacement view model and same view model accepts replacement item`() {
        val binding = AppleLyricsPlaybackBinding()
        val item = Any()
        val firstViewModel = Any()
        val nextViewModel = Any()
        binding.rememberLoad(firstViewModel, item)
        val old = binding.snapshot()
        binding.rememberLoad(nextViewModel, item)
        assertEquals(AppleLyricsPlaybackBinding.Snapshot(nextViewModel, item), binding.snapshot())
        val nextItem = Any()
        binding.rememberLoad(nextViewModel, nextItem)
        assertEquals(AppleLyricsPlaybackBinding.Snapshot(nextViewModel, nextItem), binding.snapshot())
        assertEquals(AppleLyricsPlaybackBinding.Snapshot(firstViewModel, item), old)
    }

    @Test
    fun `missing receiver changes only the item and preserves the previously known view model`() {
        val binding = AppleLyricsPlaybackBinding()
        val firstItem = Any()
        binding.rememberLoad(null, firstItem)
        assertEquals(AppleLyricsPlaybackBinding.Snapshot(null, firstItem), binding.snapshot())
        val viewModel = Any()
        binding.rememberLoad(viewModel, firstItem)
        val nextItem = Any()
        binding.rememberLoad(null, nextItem)
        assertEquals(AppleLyricsPlaybackBinding.Snapshot(viewModel, nextItem), binding.snapshot())
    }

    @Test
    fun `queued reload resolves targets at execution and previous snapshot stays coherent`() {
        val binding = AppleLyricsPlaybackBinding()
        val viewModel = Any()
        val item = Any()
        binding.rememberLoad(viewModel, item)
        val previous = binding.snapshot()
        val reload = { binding.snapshot() }
        val nextViewModel = Any()
        val nextItem = Any()
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit { binding.rememberLoad(nextViewModel, nextItem) }.get(5, TimeUnit.SECONDS)
            assertEquals(AppleLyricsPlaybackBinding.Snapshot(nextViewModel, nextItem), reload())
            assertEquals(AppleLyricsPlaybackBinding.Snapshot(viewModel, item), previous)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `owner publishes an immutable weak pair with cross thread visibility`() {
        val binding = AppleLyricsPlaybackBinding()
        binding.rememberLoad(Any(), Any())
        val stateField = binding.javaClass.declaredFields.single { !Modifier.isStatic(it.modifiers) }
        assertTrue(Modifier.isPrivate(stateField.modifiers))
        assertTrue(Modifier.isVolatile(stateField.modifiers))
        stateField.isAccessible = true
        val state = stateField.get(binding)
        val referents = state.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertEquals(2, referents.size)
        referents.forEach { field ->
            assertTrue(Modifier.isPrivate(field.modifiers))
            assertTrue(Modifier.isFinal(field.modifiers))
            assertEquals(WeakReference::class.java, field.type)
            field.isAccessible = true
            // Model collected referents deterministically; do not rely on System.gc timing.
            (field.get(state) as WeakReference<*>).clear()
        }
        assertEquals(AppleLyricsPlaybackBinding.Snapshot(null, null), binding.snapshot())
    }
}
