/* Copyright 2026 juren233 */
package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/** Exercises the real resolver with an older, still-resolvable member baseline. */
class AppleExactProfileMemberPrecedenceTest {
    class Queue {
        fun w(items: List<Any>) = Unit
        fun v(position: Int): Any = position
        fun oldEntry(position: Int): Any = position
    }

    class TestApplication : Application() {
        lateinit var testPreferences: SharedPreferences
        override fun getSharedPreferences(name: String, mode: Int) = testPreferences
    }

    private class Fixture(version: AppleMusicVersion) {
        val values = mutableMapOf<String, String>()
        val point = AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT
        val member = AppleMusicRuntimeMember.QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD
        val key = AppleMusicDexKitCachePolicy.trustedMemberBaselineKey(point, "ea.a", member)
        val dex: AppleMusicDexKitResolver
        val resolver: AppleMusicHookResolver

        init {
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> { values[args[0] as String] = args[1] as String; editor }
                    "apply" -> null
                    "commit" -> true
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            val preferences = Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getString" -> values[args[0] as String] ?: args[1]
                    "edit" -> editor
                    else -> error("Unexpected preference call: ${method.name}")
                }
            } as SharedPreferences
            // Android's mockable Application constructor throws. Allocate only this inert
            // test fixture; its sole used Android method is overridden above.
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
                isAccessible = true
                get(null) as Unsafe
            }
            val app = (unsafe.allocateInstance(TestApplication::class.java) as TestApplication)
                .apply { testPreferences = preferences }
            dex = AppleMusicDexKitResolver(app, javaClass.classLoader!!, "")
            values[key] = dex.encodeMemberDescriptor(AppleMusicDexKitResolver.MemberDescriptor.from(
                Queue::class.java.getDeclaredMethod("oldEntry", Int::class.javaPrimitiveType),
                Queue::class.java,
            ))
            resolver = AppleMusicHookResolver(version, { name ->
                if (name == "ea.a") Queue::class.java else throw ClassNotFoundException(name)
            }, dex)
        }

        fun assertVerified(target: AppleMusicHookTarget) {
            assertEquals("v", target.runtimeMemberName(member))
            assertEquals("v", dex.decodeMemberDescriptor(values[key])!!.name)
        }
    }

    @Test fun `exact method members outrank an older valid baseline`() {
        val f = Fixture(AppleMusicVersion("7.0.0-beta", 1606))
        val resolved = f.resolver.resolveMethod(f.point)
        assertFalse(resolved.compatibilityFallback)
        f.assertVerified(resolved.target)
    }

    @Test fun `exact class members outrank an older valid baseline`() {
        val f = Fixture(AppleMusicVersion("7.0.0-beta", 1606))
        val resolved = f.resolver.resolveClass(f.point)
        assertFalse(resolved.compatibilityFallback)
        f.assertVerified(resolved.target)
    }

    @Test fun `exact class collection members outrank an older valid baseline`() {
        val f = Fixture(AppleMusicVersion("7.0.0-beta", 1606))
        val resolved = f.resolver.resolveClasses(f.point).single()
        assertFalse(resolved.compatibilityFallback)
        f.assertVerified(resolved.target)
    }

    @Test fun `unknown version retains fallback repair without rewriting its baseline`() {
        val f = Fixture(AppleMusicVersion("7.0.0-beta", 1608))
        val before = f.values.toMap()
        val resolved = f.resolver.resolveMethod(f.point)
        assertTrue(resolved.compatibilityFallback)
        assertEquals("oldEntry", resolved.target.runtimeMemberName(f.member))
        assertEquals(before, f.values)
    }
}
