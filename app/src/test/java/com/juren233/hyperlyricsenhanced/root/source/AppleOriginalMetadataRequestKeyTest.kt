/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOriginalMetadataRequestKeyTest {
    @Test
    fun `only the registered media id counts as an in-flight request`() {
        val key = AppleOriginalMetadataRequestKey()
        assertFalse(key.isCurrent("song"))

        key.register("song")
        assertTrue(key.isCurrent("song"))
        assertFalse(key.isCurrent("other"))
        // The production call site only passes a digit-only, non-null media id; a null
        // candidate therefore never matches a registered request.
        assertFalse(key.isCurrent(null))
    }

    @Test
    fun `unset key follows plain equality so two nulls compare equal`() {
        // 与原实现 `originalMetadataRequestKey == mediaId` 完全一致的相等语义。
        val key = AppleOriginalMetadataRequestKey()
        assertTrue(key.isCurrent(null))
    }

    @Test
    fun `clear releases the registration for a new lookup`() {
        val key = AppleOriginalMetadataRequestKey()
        key.register("song")
        assertTrue(key.isCurrent("song"))

        key.clear()
        assertFalse(key.isCurrent("song"))
        // A later, different song can register normally.
        key.register("next")
        assertTrue(key.isCurrent("next"))
    }
}
