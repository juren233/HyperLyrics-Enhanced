/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.compatibility

import com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaCapabilityKind
import com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaHostAdapter
import com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiMediaHostCompatibilityTest {
    @Test
    fun `os4 profile uses exact raw dex descriptors for transition callbacks`() {
        val profile = SystemUiMediaProfile.OS4
        assertTrue(profile.binaryVerified)
        assertEquals(
            "Lcom/android/systemui/statusbar/notification/fullaod/" +
                "NotifiFullAodController\$FullAodTransitionListener;",
            profile.classDescriptor(
                com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaTarget
                    .FULL_AOD_TRANSITION_LISTENER,
            ),
        )
        assertEquals(
            "(Ljava/lang/Object;Ljava/util/Collection;)V",
            profile.method("transition.onUpdate")?.signature,
        )
        assertEquals("(Ljava/lang/Object;)V", profile.method("transition.onCancel")?.signature)
        assertEquals("F", profile.field("transition.fraction")?.typeDescriptor)
        assertEquals("[I", profile.field("transition.heightList")?.typeDescriptor)
    }

    @Test
    fun `os3 remains fail closed and does not resolve a runtime binding`() {
        val profile = SystemUiMediaProfile.forBuild("3.0.301.0.WOCCNXM")
        assertNotNull(profile)
        assertFalse(profile!!.binaryVerified)
        val adapter = SystemUiMediaHostAdapter.forBuild("3.0.301.0.WOCCNXM", javaClass.classLoader)
        assertNotNull(adapter)
        val capability = adapter!!.capability(javaClass.classLoader)
        assertFalse(capability.enabled)
        assertFalse(capability.supports(SystemUiMediaCapabilityKind.FULL_AOD_CALLBACK))
        assertNull(adapter.binding(javaClass.classLoader))
    }

    @Test
    fun `unknown build and loader mismatch fail closed without guessed aliases`() {
        assertNull(SystemUiMediaProfile.forBuild("unknown-system-build"))
        assertNull(SystemUiMediaHostAdapter.forBuild("unknown-system-build", javaClass.classLoader))
        assertEquals(
            "com.android.systemui.statusbar.notification.fullaod." +
                "NotifiFullAodController\$FullAodTransitionListener",
            SystemUiMediaHostAdapter.descriptorToBinaryName(
                SystemUiMediaProfile.FULL_AOD_LISTENER_CLASS,
            ),
        )
    }
}
