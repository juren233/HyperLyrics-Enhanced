package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IslandFontWeightModeTest {
    @Test
    fun `fresh install defaults to system even after setup writes preferences`() {
        val values = mutableMapOf<String, Any>()
        val prefs = preferences(values)
        IslandFontWeightMode.initialize(prefs)
        values[UIConstants.KEY_SETUP_COMPLETED] = true
        IslandFontWeightMode.initialize(prefs)
        assertEquals(IslandFontWeightMode.SYSTEM, IslandFontWeightMode.read(prefs))
        assertFalse(values.containsKey(RootConstants.KEY_HOOK_FONT_WEIGHT))
    }

    @Test
    fun `upgrade preserves customized value and untouched old default`() {
        for (weight in listOf(null, 100, 600, 900)) {
            val values = mutableMapOf<String, Any>(UIConstants.KEY_SETUP_COMPLETED to true)
            weight?.let { values[RootConstants.KEY_HOOK_FONT_WEIGHT] = it }
            val prefs = preferences(values)
            assertEquals(IslandFontWeightMode.CUSTOM, IslandFontWeightMode.read(prefs))
            IslandFontWeightMode.initialize(prefs)
            assertEquals(IslandFontWeightMode.CUSTOM, IslandFontWeightMode.read(prefs))
            assertEquals(weight ?: 600, prefs.getInt(RootConstants.KEY_HOOK_FONT_WEIGHT, 600))
        }
    }

    @Test
    fun `explicit selection survives restart and never rewrites custom weight`() {
        val values = mutableMapOf<String, Any>(RootConstants.KEY_HOOK_FONT_WEIGHT to 750)
        val prefs = preferences(values)
        for (mode in listOf(IslandFontWeightMode.SYSTEM, IslandFontWeightMode.CUSTOM)) {
            values[RootConstants.KEY_HOOK_FONT_WEIGHT_MODE] = mode
            IslandFontWeightMode.initialize(prefs)
            assertEquals(mode, IslandFontWeightMode.read(prefs))
            assertEquals(750, values[RootConstants.KEY_HOOK_FONT_WEIGHT])
        }
    }

    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        val editor = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putInt" -> { values[args!![0] as String] = args[1]; proxy }
                "apply" -> null
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args!![0])
                "getInt" -> values[args!![0]] ?: args[1]
                "getAll" -> values.toMap()
                "edit" -> editor
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
    }
}
