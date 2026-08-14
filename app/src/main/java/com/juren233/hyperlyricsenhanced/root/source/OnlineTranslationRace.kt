/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

internal object OnlineTranslationRace {
    data class Completion<T>(
        val source: Source,
        val value: T?,
        val elapsedMs: Long,
        val error: Throwable? = null,
    )

    suspend fun <T> run(
        sources: List<Source>,
        clockMs: () -> Long,
        fetch: suspend (Source) -> T?,
        onCompletion: suspend (Completion<T>) -> Unit,
    ): LinkedHashMap<Source, T> = supervisorScope {
        val resultChannel = Channel<Completion<T>>(Channel.UNLIMITED)
        sources.forEach { source ->
            launch {
                val startedAt = clockMs()
                val completion = try {
                    Completion(
                        source = source,
                        value = fetch(source),
                        elapsedMs = (clockMs() - startedAt).coerceAtLeast(0L),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Completion<T>(
                        source = source,
                        value = null,
                        elapsedMs = (clockMs() - startedAt).coerceAtLeast(0L),
                        error = error,
                    )
                }
                resultChannel.send(completion)
            }
        }

        val results = linkedMapOf<Source, T>()
        repeat(sources.size) {
            val completion = resultChannel.receive()
            completion.value?.let { results[completion.source] = it }
            onCompletion(completion)
        }
        resultChannel.close()
        results
    }
}
