/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.common.*
import com.juren233.hyperlyricsenhanced.root.LivePreferenceRefreshPolicy
import com.juren233.hyperlyricsenhanced.root.island.IslandRuntimePreferenceOverrides
import org.junit.Assert.*
import org.junit.Test

class IslandTouchConfigTest {
    @Test fun `new actions retain saved bindings and occupy the requested menu positions`() {
        val legacy = listOf(IslandTouchAction.NONE, IslandTouchAction.PLAY_PAUSE,
            IslandTouchAction.PREVIOUS, IslandTouchAction.NEXT, IslandTouchAction.FORWARD,
            IslandTouchAction.BACKWARD, IslandTouchAction.RESTART, IslandTouchAction.VOLUME_UP,
            IslandTouchAction.VOLUME_DOWN, IslandTouchAction.OPEN_APP, IslandTouchAction.EXPAND_ISLAND,
            IslandTouchAction.OPEN_APP_FREEFORM)
        legacy.forEachIndexed { id, action -> assertEquals(action, IslandTouchAction.fromId(id)) }
        assertEquals(IslandTouchAction.TOGGLE_MUTE, IslandTouchAction.fromId(12))
        assertEquals(IslandTouchAction.COPY_CURRENT_LYRIC, IslandTouchAction.fromId(13))
        assertEquals(IslandTouchAction.OPEN_MEDIA_OUTPUT, IslandTouchAction.fromId(14))
        assertEquals(IslandTouchAction.VOLUME_CONTINUOUS, IslandTouchAction.fromId(15))
        val actions = IslandTouchAction.entries
        assertEquals(IslandTouchAction.TOGGLE_MUTE, actions[actions.indexOf(IslandTouchAction.RESTART) + 1])
        assertEquals(IslandTouchAction.VOLUME_CONTINUOUS, actions[actions.indexOf(IslandTouchAction.VOLUME_DOWN) + 1])
        assertEquals(IslandTouchAction.OPEN_MEDIA_OUTPUT, actions[actions.indexOf(IslandTouchAction.EXPAND_ISLAND) + 1])
        assertEquals(IslandTouchAction.COPY_CURRENT_LYRIC, actions[actions.indexOf(IslandTouchAction.OPEN_MEDIA_OUTPUT) + 1])
        assertEquals(actions.size, actions.map { it.id }.toSet().size)
    }
    @Test fun `defaults preserve native controls on both sides`() {
        val config = IslandTouchConfig.read({ _, fallback -> fallback }, { _, fallback -> fallback })
        assertFalse(config.enabled)
        assertTrue(config.hapticFeedback)
        assertEquals(250, config.doubleTapMs)
        assertEquals(500, config.longPressMs)
        assertFalse(config.unifiedSides)
        assertEquals(10, config.bindings.size)
        for (side in IslandTouchSide.entries) assertFalse(config.handles(side))
    }
    @Test fun `binding one side never takes over the other side`() {
        val key = IslandTouchConfig.actionKey(IslandTouchSide.LEFT, IslandTouchGesture.TAP)
        val config = IslandTouchConfig.read({ k, fallback -> k == IslandTouchConfig.ENABLED || fallback }, { k, fallback ->
            if (k == key) IslandTouchAction.PLAY_PAUSE.id else fallback
        })
        assertTrue(config.handles(IslandTouchSide.LEFT))
        assertFalse(config.handles(IslandTouchSide.RIGHT))
        assertFalse(config.copy(enabled = false).handles(IslandTouchSide.LEFT))
    }
    @Test fun `unknown actions are disabled and seek seconds are bounded`() {
        val config = IslandTouchConfig.read({ _, _ -> true }, { key, _ ->
            if (key.endsWith("_action")) 999 else -50
        })
        assertTrue(config.bindings.values.all { it.action == IslandTouchAction.NONE && it.seconds == 1 })
        val maximum = IslandTouchConfig.read({ _, _ -> true }, { _, _ -> Int.MAX_VALUE })
        assertTrue(maximum.bindings.values.all { it.seconds == 300 })
    }
    @Test fun `each gesture has independent persistent keys and broadcasts`() {
        assertEquals(35, IslandTouchConfig.preferenceKeys.size)
        IslandTouchConfig.preferenceKeys.forEach { assertTrue(LivePreferenceRefreshPolicy.contains(it)) }
        assertFalse(LivePreferenceRefreshPolicy.contains("hook_island_touch_unrecognized"))
    }
    @Test fun `broadcast overrides immediately replace remote values and clear falls back`() {
        val key = IslandTouchConfig.actionKey(IslandTouchSide.RIGHT, IslandTouchGesture.SWIPE_LEFT)
        try {
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.ENABLED, true)
            IslandRuntimePreferenceOverrides.put(key, IslandTouchAction.NEXT.id)
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.HAPTIC_FEEDBACK, false)
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.DOUBLE_TAP_MS, 200)
            fun read() = IslandTouchConfig.read(IslandRuntimePreferenceOverrides::getBoolean,
                IslandRuntimePreferenceOverrides::getInt)
            assertEquals(IslandTouchAction.NEXT, read().binding(IslandTouchSide.RIGHT, IslandTouchGesture.SWIPE_LEFT).action)
            assertTrue(read().handles(IslandTouchSide.RIGHT))
            assertFalse(read().hapticFeedback)
            assertEquals(200, read().doubleTapMs)
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.HAPTIC_FEEDBACK, null)
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.DOUBLE_TAP_MS, null)
            assertTrue(read().hapticFeedback)
            assertEquals(250, read().doubleTapMs)
            IslandRuntimePreferenceOverrides.put(key, null)
            assertFalse(read().handles(IslandTouchSide.RIGHT))
        } finally { IslandRuntimePreferenceOverrides.clear() }
    }
    @Test fun `double tap window is bounded without changing gesture bindings`() {
        for ((stored, expected) in listOf(-1 to 150, 0 to 150, 200 to 200, 900 to 500)) {
            val config = IslandTouchConfig.read({ _, default -> default }, { key, default ->
                if (key == IslandTouchConfig.DOUBLE_TAP_MS) stored else default
            })
            assertEquals(expected, config.doubleTapMs)
            assertTrue(config.bindings.values.all { it.action == IslandTouchAction.NONE && it.seconds == 10 })
        }
    }
    @Test fun `unified and separate bindings survive editing and switching in either direction`() {
        val gesture = IslandTouchGesture.SWIPE_LEFT
        val left = IslandTouchSide.LEFT
        val right = IslandTouchSide.RIGHT
        val stored = mutableMapOf(
            IslandTouchConfig.actionKey(left, gesture) to IslandTouchAction.PREVIOUS.id,
            IslandTouchConfig.actionKey(right, gesture) to IslandTouchAction.NEXT.id,
            IslandTouchConfig.secondsKey(left, gesture) to 15,
            IslandTouchConfig.secondsKey(right, gesture) to 25,
        )
        fun read(unified: Boolean) = IslandTouchConfig.read({ key, fallback ->
            when (key) {
                IslandTouchConfig.ENABLED -> true
                IslandTouchConfig.UNIFIED_SIDES -> unified
                else -> fallback
            }
        }, { key, fallback -> stored[key] ?: fallback })

        val unified = read(true).withBinding(right, gesture, IslandTouchBinding(IslandTouchAction.FORWARD, 40))
        stored[unified.bindingActionKey(right, gesture)] = unified.binding(right, gesture).action.id
        stored[unified.bindingSecondsKey(right, gesture)] = unified.binding(right, gesture).seconds
        for (side in IslandTouchSide.entries) {
            assertEquals(IslandTouchBinding(IslandTouchAction.FORWARD, 40), read(true).binding(side, gesture))
            assertTrue(read(true).handles(side))
        }
        assertEquals(IslandTouchBinding(IslandTouchAction.PREVIOUS, 15), read(false).binding(left, gesture))
        assertEquals(IslandTouchBinding(IslandTouchAction.NEXT, 25), read(false).binding(right, gesture))

        val separate = read(false).withBinding(left, gesture, IslandTouchBinding(IslandTouchAction.BACKWARD, 60))
        stored[separate.bindingActionKey(left, gesture)] = separate.binding(left, gesture).action.id
        stored[separate.bindingSecondsKey(left, gesture)] = separate.binding(left, gesture).seconds
        assertEquals(IslandTouchBinding(IslandTouchAction.FORWARD, 40), read(true).binding(left, gesture))
        assertEquals(IslandTouchBinding(IslandTouchAction.BACKWARD, 60), read(false).binding(left, gesture))
        assertEquals(IslandTouchBinding(IslandTouchAction.NEXT, 25), read(false).binding(right, gesture))
        assertFalse(read(true).copy(enabled = false).handles(left))
    }

    @Test fun `empty unified bindings keep native controls without borrowing a side`() {
        val config = IslandTouchConfig(true, mapOf(
            (IslandTouchSide.LEFT to IslandTouchGesture.TAP) to IslandTouchBinding(IslandTouchAction.PLAY_PAUSE)
        ), unifiedSides = true)
        assertFalse(config.handles(IslandTouchSide.LEFT))
        assertFalse(config.handles(IslandTouchSide.RIGHT))
        assertTrue(config.copy(unifiedSides = false).handles(IslandTouchSide.LEFT))
    }

    @Test fun `long press time and unified seek durations are bounded`() {
        for ((stored, expected) in listOf(-1 to 200, 200 to 200, 900 to 900, 3000 to 1500)) {
            val config = IslandTouchConfig.read({ _, fallback -> fallback }, { key, fallback ->
                if (key == IslandTouchConfig.LONG_PRESS_MS) stored else fallback
            })
            assertEquals(expected, config.longPressMs)
        }
        for ((stored, expected) in listOf(-1 to 1, 90 to 90, Int.MAX_VALUE to 300)) {
            val config = IslandTouchConfig.read({ _, _ -> true }, { key, fallback ->
                when {
                    key.startsWith("hook_island_touch_unified_") && key.endsWith("_seconds") -> stored
                    key.endsWith("_action") -> 999
                    else -> fallback
                }
            })
            assertTrue(config.unifiedBindings.values.all { it.seconds == expected && it.action == IslandTouchAction.NONE })
        }
    }

    @Test fun `unified mode bindings and long press time refresh without restarting host`() {
        try {
            val gesture = IslandTouchGesture.LONG_PRESS
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.ENABLED, true)
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.UNIFIED_SIDES, true)
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.LONG_PRESS_MS, 900)
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.unifiedActionKey(gesture), IslandTouchAction.FORWARD.id)
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.unifiedSecondsKey(gesture), 35)
            fun read() = IslandTouchConfig.read(IslandRuntimePreferenceOverrides::getBoolean,
                IslandRuntimePreferenceOverrides::getInt)
            val config = read()
            assertEquals(900, config.longPressMs)
            IslandTouchSide.entries.forEach {
                assertEquals(IslandTouchBinding(IslandTouchAction.FORWARD, 35), config.binding(it, gesture))
            }
            IslandRuntimePreferenceOverrides.put(IslandTouchConfig.UNIFIED_SIDES, false)
            assertNotEquals(config, read())
            assertFalse(read().handles(IslandTouchSide.LEFT))
            IslandRuntimePreferenceOverrides.clear()
            assertEquals(500, read().longPressMs)
            assertFalse(read().unifiedSides)
        } finally { IslandRuntimePreferenceOverrides.clear() }
    }

    @Test fun `runtime identifiers retain original DEX names without decompiler aliases`() {
        assertEquals("miui.systemui.dynamicisland.window.DynamicIslandWindowView", IslandTouchHookProfile.WINDOW)
        assertEquals("miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView", IslandTouchHookProfile.BASE_CONTENT)
        assertEquals("dispatchTouchEvent", IslandTouchHookProfile.DISPATCH)
        assertEquals("isExpandedShowing", IslandTouchHookProfile.EXPANDED)
        assertEquals("miui.systemui.dynamicisland.window.content.DynamicIslandContentView", IslandTouchHookProfile.CONTENT)
        assertEquals("onIslandClick", IslandTouchHookProfile.CLICK)
        assertEquals("miui.systemui.dynamicisland.touch.domain.interactor.DynamicIslandTouchInteractor", IslandTouchHookProfile.TOUCH_INTERACTOR)
        assertEquals("performLongClick", IslandTouchHookProfile.LONG_CLICK)
        assertEquals("windowView", IslandTouchHookProfile.WINDOW_FIELD)
        assertFalse(IslandTouchHookProfile.WINDOW.startsWith("defpackage."))
        assertFalse(IslandTouchHookProfile.TOUCH_INTERACTOR.contains("AnonymousClass"))
    }

    @Test fun `continuous volume is limited to horizontal swipes even when stored elsewhere`() {
        val continuous = IslandTouchAction.VOLUME_CONTINUOUS
        assertEquals(setOf(IslandTouchGesture.SWIPE_LEFT, IslandTouchGesture.SWIPE_RIGHT),
            IslandTouchGesture.entries.filter { continuous.availableFor(it) }.toSet())
        IslandTouchAction.entries.filter { it != continuous }.forEach { action ->
            IslandTouchGesture.entries.forEach { assertTrue(action.availableFor(it)) }
        }
        for (unified in listOf(false, true)) {
            val config = IslandTouchConfig.read({ key, fallback -> key == IslandTouchConfig.UNIFIED_SIDES && unified ||
                key == IslandTouchConfig.ENABLED || fallback }, { key, fallback ->
                if (key.endsWith("_action")) continuous.id else fallback
            })
            for (side in IslandTouchSide.entries) for (gesture in IslandTouchGesture.entries) {
                assertEquals(if (continuous.availableFor(gesture)) continuous else IslandTouchAction.NONE,
                    config.binding(side, gesture).action)
            }
        }
    }

}
