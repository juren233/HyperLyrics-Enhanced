package com.juren233.hyperlyricsenhanced.root.island

import android.view.ViewGroup
import java.util.WeakHashMap

internal object FakeIslandTransitionState {
    private data class State(var generation: Int = 0, var active: Boolean = false)

    private val states = WeakHashMap<ViewGroup, State>()

    fun ensureActive(view: ViewGroup): Int = synchronized(states) {
        val state = states.getOrPut(view) { State() }
        if (!state.active) {
            state.generation++
            state.active = true
        }
        state.generation
    }

    fun finish(view: ViewGroup) = synchronized(states) {
        val state = states.getOrPut(view) { State() }
        state.active = false
        state.generation++
    }

    fun isActive(view: ViewGroup, generation: Int): Boolean = synchronized(states) {
        states[view]?.let { it.active && it.generation == generation } == true
    }
}
