/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.utils

import android.view.View
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * At most one pending [View.post] per view for one purpose; a newer post replaces the older one.
 *
 * `View.post` on a view that is not attached queues into the view's own run queue until it attaches.
 * A view that never attaches (SystemUI keeps detached island fake views) would otherwise collect
 * every retry for the rest of the process. The task holds the view weakly so the map's value never
 * keeps its key alive; [action] must not capture the view itself.
 */
internal class LatestViewPost {
    private val pending = WeakHashMap<View, Runnable>()

    fun post(view: View, action: (View) -> Unit) {
        val ref = WeakReference(view)
        lateinit var task: Runnable
        task = Runnable {
            val target = ref.get() ?: return@Runnable
            synchronized(pending) { if (pending[target] === task) pending.remove(target) }
            action(target)
        }
        val previous = synchronized(pending) { pending.put(view, task) }
        previous?.let(view::removeCallbacks)
        view.post(task)
    }

    /** Withdraws every pending task, including those parked in a detached view's run queue. */
    fun cancelAll() {
        val snapshot = synchronized(pending) { pending.toMap().also { pending.clear() } }
        snapshot.forEach { (view, task) -> view.removeCallbacks(task) }
    }
}
