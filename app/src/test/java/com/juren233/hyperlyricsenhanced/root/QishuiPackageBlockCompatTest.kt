/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QishuiPackageBlockCompatTest {
    @Test
    fun `runtime identifiers match the original Qishui 2040 dex`() {
        assertEquals(
            "Lcom/luna/biz/main/init/blockpackage/PackageBlockDelegate;",
            QishuiPackageBlockRuntimeIdentifiers.DELEGATE_CLASS_DESCRIPTOR,
        )
        assertEquals(
            "com.luna.biz.main.init.blockpackage.PackageBlockDelegate",
            QishuiPackageBlockRuntimeIdentifiers.delegateClassName,
        )
        assertEquals("l", QishuiPackageBlockRuntimeIdentifiers.BLOCK_CALLBACK_METHOD_NAME)
        assertEquals(
            "(Lcom/luna/biz/main/init/blockpackage/PackageBlockDelegate;Z)V",
            QishuiPackageBlockRuntimeIdentifiers.BLOCK_CALLBACK_METHOD_DESCRIPTOR,
        )
    }

    @Test
    fun `suppresses only the positive package block callback`() {
        assertTrue(QishuiPackageBlockCompat.shouldSuppressBlockCallback(isBlocked = true))
        assertFalse(QishuiPackageBlockCompat.shouldSuppressBlockCallback(isBlocked = false))
    }
}
