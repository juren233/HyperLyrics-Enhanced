/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.*
import org.junit.Test

class IslandContentUpdateProfileTest {
    private class Fixture {
        fun getAnimatorDelegate(): String = ""
        fun getAnimatorDelegate(value: Int): String = value.toString()
        fun isAnimating(): Boolean = false
        fun getIslandWindowAnimRunning(): Boolean = false
        companion object { @JvmStatic fun getRealView(): String = "" }
    }

    @Test fun OriginalDexNamesRemainExact() {
        assertEquals("miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView", IslandContentUpdateProfile.BASE_CLASS)
        assertEquals("miui.systemui.dynamicisland.window.content.DynamicIslandContentView", IslandContentUpdateProfile.REAL_CLASS)
        assertEquals("miui.systemui.dynamicisland.anim.DynamicIslandAnimationDelegate", IslandContentUpdateProfile.DELEGATE_CLASS)
        assertEquals("getAnimatorDelegate", IslandContentUpdateProfile.DELEGATE_GETTER)
        assertEquals("getRealView", IslandContentUpdateProfile.REAL_GETTER)
        assertEquals("isAnimating", IslandContentUpdateProfile.SELF_RUNNING_GETTER)
        assertEquals("getIslandWindowAnimRunning", IslandContentUpdateProfile.WINDOW_RUNNING_GETTER)
        assertFalse(IslandContentUpdateProfile.BASE_CLASS.startsWith("p000"))
    }

    @Test fun GetterRejectsOverloadsReturnTypeChangesAndStaticAliases() {
        val methods = Fixture::class.java.declaredMethods
        assertEquals(1, methods.count {
            IslandContentUpdateProfile.isGetter(it, IslandContentUpdateProfile.DELEGATE_GETTER, String::class.java.name)
        })
        assertFalse(methods.any {
            IslandContentUpdateProfile.isGetter(it, IslandContentUpdateProfile.DELEGATE_GETTER, IslandContentUpdateProfile.DELEGATE_CLASS)
        })
        assertFalse(methods.any {
            IslandContentUpdateProfile.isGetter(it, IslandContentUpdateProfile.REAL_GETTER, String::class.java.name)
        })
    }

    @Test fun ActualMotionGettersHavePrimitiveBooleanDescriptors() {
        val methods = Fixture::class.java.declaredMethods
        for (name in listOf(IslandContentUpdateProfile.SELF_RUNNING_GETTER, IslandContentUpdateProfile.WINDOW_RUNNING_GETTER)) {
            assertEquals(1, methods.count { IslandContentUpdateProfile.isGetter(it, name, "boolean") })
            assertFalse(methods.any { IslandContentUpdateProfile.isGetter(it, name, "java.lang.Boolean") })
        }
    }
}
