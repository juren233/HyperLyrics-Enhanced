/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.lang.ref.WeakReference

/** A native -100 -> -1 initialization is equivalent only while the effective theme is unchanged. */
internal class AppleInitialThemeSnapshot {
    private data class Pending(val activity: WeakReference<Any>, val nightMode: Int)
    private val pending = mutableListOf<Pending>()

    @Synchronized
    fun onCreated(activity: Any, capturedMode: Int, nightMode: Int) {
        forget(activity)
        if (capturedMode == MODE_UNSPECIFIED && nightMode in VALID_NIGHT_MODES) {
            pending += Pending(WeakReference(activity), nightMode)
        }
    }

    @Synchronized
    fun awaitingActivities(): List<Any> {
        pending.removeAll { it.activity.get() == null }
        return pending.mapNotNull { it.activity.get() }
    }

    @Synchronized
    fun forget(activity: Any) {
        pending.removeAll { it.activity.get().let { owner -> owner == null || owner === activity } }
    }

    @Synchronized
    fun onThemeResolved(activity: Any, capturedMode: Int, globalMode: Int, nightMode: Int): Int? {
        val snapshot = pending.firstOrNull { it.activity.get() === activity } ?: return null
        val initialNightMode = snapshot.nightMode
        // Creation can precede the preference Flow. Retain its unresolved snapshot until
        // the first resolved theme is applied, without waiting for a foreground return.
        if (capturedMode == MODE_UNSPECIFIED && globalMode == MODE_UNSPECIFIED && nightMode == initialNightMode) {
            return null
        }
        // Once initialization is evaluated, later changes belong to the native theme lifecycle.
        pending.remove(snapshot)
        return MODE_FOLLOW_SYSTEM.takeIf {
            capturedMode == MODE_UNSPECIFIED && globalMode == MODE_FOLLOW_SYSTEM &&
                nightMode == initialNightMode
        }
    }

    private companion object {
        // Original 1606 k.f.<clinit> and D.invokeSuspend default values, respectively.
        const val MODE_UNSPECIFIED = -100
        const val MODE_FOLLOW_SYSTEM = -1
        val VALID_NIGHT_MODES = setOf(0x10, 0x20)
    }
}
