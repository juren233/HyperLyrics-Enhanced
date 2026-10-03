/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common

/** Stable preference IDs; never persist enum ordinals. */
enum class IslandTouchSide(val key: String) { LEFT("left"), RIGHT("right") }
enum class IslandTouchGesture(val key: String) {
    TAP("tap"), DOUBLE_TAP("double_tap"), LONG_PRESS("long_press"),
    SWIPE_LEFT("swipe_left"), SWIPE_RIGHT("swipe_right")
}
enum class IslandTouchAction(val id: Int) {
    NONE(0), PLAY_PAUSE(1), PREVIOUS(2), NEXT(3), FORWARD(4), BACKWARD(5),
    RESTART(6), TOGGLE_MUTE(12), VOLUME_UP(7), VOLUME_DOWN(8), OPEN_APP(9),
    OPEN_APP_FREEFORM(11), EXPAND_ISLAND(10), OPEN_MEDIA_OUTPUT(14), COPY_CURRENT_LYRIC(13);

    companion object {
        fun fromId(id: Int): IslandTouchAction = entries.firstOrNull { it.id == id } ?: NONE
    }
}

data class IslandTouchBinding(
    val action: IslandTouchAction = IslandTouchAction.NONE,
    val seconds: Int = IslandTouchConfig.DEFAULT_SECONDS,
)

data class IslandTouchConfig(
    val enabled: Boolean,
    val bindings: Map<Pair<IslandTouchSide, IslandTouchGesture>, IslandTouchBinding>,
    val hapticFeedback: Boolean = true,
    val doubleTapMs: Int = DEFAULT_DOUBLE_TAP_MS,
    val longPressMs: Int = DEFAULT_LONG_PRESS_MS,
    val unifiedSides: Boolean = false,
    val unifiedBindings: Map<IslandTouchGesture, IslandTouchBinding> = emptyMap(),
) {
    fun binding(side: IslandTouchSide, gesture: IslandTouchGesture): IslandTouchBinding =
        (if (unifiedSides) unifiedBindings[gesture] else bindings[side to gesture]) ?: IslandTouchBinding()

    fun withBinding(side: IslandTouchSide, gesture: IslandTouchGesture, binding: IslandTouchBinding): IslandTouchConfig =
        if (unifiedSides) copy(unifiedBindings = unifiedBindings + (gesture to binding))
        else copy(bindings = bindings + ((side to gesture) to binding))

    fun bindingActionKey(side: IslandTouchSide, gesture: IslandTouchGesture): String =
        if (unifiedSides) unifiedActionKey(gesture) else actionKey(side, gesture)

    fun bindingSecondsKey(side: IslandTouchSide, gesture: IslandTouchGesture): String =
        if (unifiedSides) unifiedSecondsKey(gesture) else secondsKey(side, gesture)

    fun handles(side: IslandTouchSide): Boolean = enabled && IslandTouchGesture.entries.any {
        binding(side, it).action != IslandTouchAction.NONE
    }

    companion object {
        const val ENABLED = "hook_island_touch_enabled"
        const val HAPTIC_FEEDBACK = "hook_island_touch_haptic_feedback"
        const val DOUBLE_TAP_MS = "hook_island_touch_double_tap_ms"
        const val LONG_PRESS_MS = "hook_island_touch_long_press_ms"
        const val UNIFIED_SIDES = "hook_island_touch_unified_sides"
        const val DEFAULT_DOUBLE_TAP_MS = 250
        const val MIN_DOUBLE_TAP_MS = 150
        const val MAX_DOUBLE_TAP_MS = 500
        const val DEFAULT_LONG_PRESS_MS = 500
        const val MIN_LONG_PRESS_MS = 200
        const val MAX_LONG_PRESS_MS = 1500
        const val DEFAULT_SECONDS = 10
        const val MIN_SECONDS = 1
        const val MAX_SECONDS = 300
        fun actionKey(side: IslandTouchSide, gesture: IslandTouchGesture) =
            "hook_island_touch_${side.key}_${gesture.key}_action"
        fun secondsKey(side: IslandTouchSide, gesture: IslandTouchGesture) =
            "hook_island_touch_${side.key}_${gesture.key}_seconds"
        fun unifiedActionKey(gesture: IslandTouchGesture) = "hook_island_touch_unified_${gesture.key}_action"
        fun unifiedSecondsKey(gesture: IslandTouchGesture) = "hook_island_touch_unified_${gesture.key}_seconds"
        val preferenceKeys: Set<String> = buildSet {
            add(ENABLED)
            add(HAPTIC_FEEDBACK)
            add(DOUBLE_TAP_MS)
            add(LONG_PRESS_MS)
            add(UNIFIED_SIDES)
            for (gesture in IslandTouchGesture.entries) {
                add(unifiedActionKey(gesture))
                add(unifiedSecondsKey(gesture))
            }
            for (side in IslandTouchSide.entries) for (gesture in IslandTouchGesture.entries) {
                add(actionKey(side, gesture))
                add(secondsKey(side, gesture))
            }
        }
        fun read(boolean: (String, Boolean) -> Boolean, int: (String, Int) -> Int): IslandTouchConfig =
            IslandTouchConfig(boolean(ENABLED, false), buildMap {
                for (side in IslandTouchSide.entries) for (gesture in IslandTouchGesture.entries) {
                    put(side to gesture, IslandTouchBinding(
                        IslandTouchAction.fromId(int(actionKey(side, gesture), 0)),
                        int(secondsKey(side, gesture), DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS),
                    ))
                }
            }, hapticFeedback = boolean(HAPTIC_FEEDBACK, true),
                doubleTapMs = int(DOUBLE_TAP_MS, DEFAULT_DOUBLE_TAP_MS)
                    .coerceIn(MIN_DOUBLE_TAP_MS, MAX_DOUBLE_TAP_MS),
                longPressMs = int(LONG_PRESS_MS, DEFAULT_LONG_PRESS_MS)
                    .coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS),
                unifiedSides = boolean(UNIFIED_SIDES, false),
                unifiedBindings = IslandTouchGesture.entries.associateWith { gesture ->
                    IslandTouchBinding(
                        IslandTouchAction.fromId(int(unifiedActionKey(gesture), 0)),
                        int(unifiedSecondsKey(gesture), DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS),
                    )
                })
    }
}
