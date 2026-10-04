/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps race publications ordered while expensive matching runs away from the UI thread. */
internal class OnlineTranslationPreparationQueue(
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()

    /**
     * [capture] returns a detached snapshot, or null after request invalidation.
     * [commit] returns false only when that snapshot changed and needs preparing again.
     * It must validate the generation and snapshot together with the actual publication.
     */
    suspend fun <S : Any, R> prepareAndCommit(
        capture: () -> S?,
        prepare: (S) -> R,
        commit: (S, R) -> Boolean,
    ) = mutex.withLock {
        while (true) {
            currentCoroutineContext().ensureActive()
            val snapshot = withContext(mainDispatcher) { capture() } ?: return@withLock
            val result = withContext(workerDispatcher) { prepare(snapshot) }
            if (withContext(mainDispatcher) { commit(snapshot, result) }) return@withLock
        }
    }
}
