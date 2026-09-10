/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import java.lang.ref.WeakReference

/**
 * The pair used to reload Apple's lyrics ViewModel. This is independent of the visible
 * Fragment/pointer ticket: a load may legitimately bring an old page up to the current queue.
 * Song/Fragment changes therefore do not clear this binding or invalidate presentation work.
 * The pair lasts until the next load binding or collection of its weak referents.
 */
internal class AppleLyricsPlaybackBinding {
    private data class Binding(
        val viewModel: WeakReference<Any>?,
        val item: WeakReference<Any>?,
    )

    @Volatile
    private var binding = Binding(null, null)

    /** Resolve only inside the consuming callback; never retain this snapshot in queued work. */
    data class Snapshot(val viewModel: Any?, val item: Any?)

    fun snapshot(): Snapshot {
        val current = binding
        return Snapshot(current.viewModel?.get(), current.item?.get())
    }

    /** A missing receiver retains the previous ViewModel, as the native load Hook did. */
    @Synchronized
    fun rememberLoad(viewModel: Any?, item: Any) {
        binding = Binding(
            viewModel = viewModel?.let(::WeakReference) ?: binding.viewModel,
            item = WeakReference(item),
        )
    }
}
