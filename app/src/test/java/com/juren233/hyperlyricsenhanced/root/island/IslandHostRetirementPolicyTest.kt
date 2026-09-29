/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandHostRetirementPolicyTest {
    private val allPresent = IslandAnchorState(
        leftParent = true,
        leftContainer = true,
        rightParent = true,
        rightContainer = true,
    )

    @Test
    fun `transient failures below threshold are not retired`() {
        // fake/real 过渡与原生重排窗口最多撞 1-2 句换句，不得触发注销。
        assertFalse(IslandHostRetirementPolicy.shouldRetire(0))
        assertFalse(IslandHostRetirementPolicy.shouldRetire(1))
        assertFalse(IslandHostRetirementPolicy.shouldRetire(4))
    }

    @Test
    fun `structural failures at and beyond threshold are retired`() {
        assertTrue(IslandHostRetirementPolicy.shouldRetire(5))
        assertTrue(IslandHostRetirementPolicy.shouldRetire(12))
    }

    @Test
    fun `all anchors present is injectable`() {
        assertTrue(
            IslandHostRetirementPolicy.isAnchorStateInjectable(
                allPresent,
                shouldInjectLeft = true,
                shouldInjectRight = true,
            ),
        )
    }

    @Test
    fun `missing text container on injected side blocks injection`() {
        val leftTextMissing = allPresent.copy(leftContainer = false)
        assertFalse(
            IslandHostRetirementPolicy.isAnchorStateInjectable(
                leftTextMissing,
                shouldInjectLeft = true,
                shouldInjectRight = true,
            ),
        )
        // 该侧不注入时其锚点不参与判定。
        assertTrue(
            IslandHostRetirementPolicy.isAnchorStateInjectable(
                leftTextMissing,
                shouldInjectLeft = false,
                shouldInjectRight = true,
            ),
        )
    }

    @Test
    fun `missing parent on injected side blocks injection`() {
        val rightParentMissing = allPresent.copy(rightParent = false, rightContainer = false)
        assertFalse(
            IslandHostRetirementPolicy.isAnchorStateInjectable(
                rightParentMissing,
                shouldInjectLeft = true,
                shouldInjectRight = true,
            ),
        )
        assertTrue(
            IslandHostRetirementPolicy.isAnchorStateInjectable(
                rightParentMissing,
                shouldInjectLeft = true,
                shouldInjectRight = false,
            ),
        )
    }

    @Test
    fun `anchors on non injected sides never block injection`() {
        val bothSidesGone = IslandAnchorState(
            leftParent = false,
            leftContainer = false,
            rightParent = false,
            rightContainer = false,
        )
        assertFalse(
            IslandHostRetirementPolicy.isAnchorStateInjectable(
                bothSidesGone,
                shouldInjectLeft = true,
                shouldInjectRight = false,
            ),
        )
        assertFalse(
            IslandHostRetirementPolicy.isAnchorStateInjectable(
                bothSidesGone,
                shouldInjectLeft = false,
                shouldInjectRight = true,
            ),
        )
    }
}
