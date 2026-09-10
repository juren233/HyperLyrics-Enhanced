/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import java.lang.ref.WeakReference

/** Current display binding only. No strong native pointer survives in this owner. */
internal class AppleLyricsPresentationBinding {
    private var songId: String? = null
    private var fragment: WeakReference<Any>? = null
    private var pointer: WeakReference<Any>? = null
    private var generation = 0L

    data class Snapshot(val songId: String?, val fragment: Any?, val pointer: Any?, val generation: Long)
    /** Safe to retain in queued work: contains no View or native pointer. */
    data class Ticket(val songId: String?, val generation: Long)

    @Synchronized fun songId(): String? = songId
    @Synchronized fun fragment(): Any? = fragment?.get()
    @Synchronized fun pointer(): Any? = pointer?.get()
    @Synchronized fun snapshot(): Snapshot = Snapshot(songId, fragment?.get(), pointer?.get(), generation)
    @Synchronized fun ticket(): Ticket = Ticket(songId, generation)
    @Synchronized fun isCurrent(ticket: Ticket): Boolean =
        ticket.generation == generation && ticket.songId == songId

    @Synchronized fun rememberFragment(value: Any) {
        if (fragment?.get() !== value) generation++
        fragment = WeakReference(value)
    }
    @Synchronized fun rememberPointer(value: Any?) {
        if (pointer?.get() !== value) generation++
        pointer = value?.let(::WeakReference)
    }
    /** Pointer cleanup stays at the original call sites, preserving native lifetime semantics. */
    @Synchronized fun selectSong(value: String?): Boolean {
        if (songId == value) return false
        songId = value
        generation++
        return true
    }
}
