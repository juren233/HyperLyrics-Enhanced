/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

/**
 * Main-thread lifetime for one lock-screen overlay; never reused for replacement views.
 * Dispose before restoring/removing views so even reentrant layout callbacks are rejected.
 * This gate does not synchronize background threads or remove callbacks from Android queues.
 */
internal class AodOverlayLifetime {
    var allowsUpdates: Boolean = true
        private set

    fun dispose() {
        allowsUpdates = false
    }
}
