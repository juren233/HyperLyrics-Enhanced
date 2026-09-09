/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import java.util.WeakHashMap
import java.lang.ref.WeakReference

/**
 * Owns the validity of delayed native artwork captures, without owning their host views.
 *
 * Every begin uses a fresh identity: clearing a per-host integer counter would let an old
 * queued callback match a new request when the same View is reused after cleanup (ABA).
 * Tokens never reference the host. All map access uses this owner's monitor, not the caller's.
 * This is a validity gate, not a lock around bitmap capture or cache publication.
 */
internal class NativeArtworkCaptureRequests<K : Any> {
    class Request internal constructor()

    private val requests = WeakHashMap<K, Request>()

    @Synchronized
    fun begin(host: K): Request = Request().also { requests[host] = it }

    @Synchronized
    fun isCurrent(host: K, request: Request): Boolean = requests[host] === request

    @Synchronized
    fun complete(host: K, request: Request) {
        if (requests[host] === request) requests.remove(host)
    }

    /**
     * Acquire the host only when the queued attempt executes. The action must use its
     * parameter, not capture the original host. No owner lock is held while it runs.
     */
    fun callback(host: WeakReference<K>, request: Request, action: (K) -> Unit): Runnable =
        Runnable {
            val target = host.get() ?: return@Runnable
            if (isCurrent(target, request)) action(target)
        }

    @Synchronized
    fun clear() = requests.clear()
}
