/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.host

import java.lang.reflect.Field
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHeightLeaseContractTest {
    @Test
    fun `binding rejects duplicate active lease and permits reacquire after release`() {
        val owner = FakeNativeOwner(intArrayOf(100, 200))
        val listener = owner.Listener()
        val ownerField = listener.javaClass.getDeclaredField("this$0").accessible()
        val heightField = FakeNativeOwner::class.java.getDeclaredField("heightList").accessible()
        val profile = SystemUiMediaProfile(
            os = SystemUiMediaOs.HYPEROS_4,
            buildLabels = setOf("test"),
            classes = emptyMap(),
            methods = emptyMap(),
            fields = emptyMap(),
            evidence = "test",
            binaryVerified = true,
        )
        val capability = SystemUiMediaCapability(
            profile = profile,
            classLoaderIdentity = "test",
            supported = setOf(SystemUiMediaCapabilityKind.FULL_AOD_HEIGHT_LEASE),
            unavailableReasons = emptyMap(),
        )
        val binding = SystemUiMediaHostAdapter.Binding(
            profile = profile,
            classLoader = requireNotNull(FakeNativeOwner::class.java.classLoader),
            capability = capability,
            loadedClasses = emptyMap(),
            methods = emptyMap(),
            fields = mapOf(
                "transition.owner" to ownerField,
                "transition.heightList" to heightField,
            ),
        )

        val first = binding.acquireHeightLease(listener)
        assertNotNull(first)
        assertNull(binding.acquireHeightLease(listener))
        assertTrue(first!!.restore())
        assertNotNull(binding.acquireHeightLease(listener))
    }

    @Test
    fun `height lease snapshots and restores defensively and idempotently`() {
        val owner = FakeNativeOwner(intArrayOf(100, 200, 300))
        val field = FakeNativeOwner::class.java.getDeclaredField("heightList").accessible()
        val lease = ReflectiveNativeHeightLease(
            classLoader = requireNotNull(FakeNativeOwner::class.java.classLoader),
            heightListField = field,
            owner = owner,
        )

        val exposedOriginal = lease.originalHeights
        exposedOriginal[1] = 999
        assertArrayEquals(intArrayOf(100, 200, 300), lease.originalHeights)

        assertTrue(lease.setTargetHeight(index = 1, height = 480))
        assertArrayEquals(intArrayOf(100, 480, 300), owner.heightList)

        owner.heightList = intArrayOf(100)
        assertFalse(lease.setTargetHeight(index = 1, height = 520))
        assertTrue(lease.restore())
        assertArrayEquals(intArrayOf(100, 200, 300), owner.heightList)
        assertTrue(lease.restore())
        assertFalse(lease.setTargetHeight(index = 0, height = 600))
    }

    private class FakeNativeOwner(
        @JvmField var heightList: IntArray,
    ) {
        inner class Listener
    }

    private fun Field.accessible(): Field = apply { isAccessible = true }
}
