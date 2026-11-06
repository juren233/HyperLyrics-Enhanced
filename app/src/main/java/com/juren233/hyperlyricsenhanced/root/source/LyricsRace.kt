/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope

/**
 * 「自动」来源下 ttml 与搜索源的竞速执行：全部同时开始，谁先出结果谁先通过 onEarly 交出去，
 * ttml 的结果无论多晚到都优先（按传入顺序，靠前的优先），最终结论由 [AppleLyricsRacePolicy] 裁决。
 *
 * Apple Music 与其他播放器的在线兜底共用这一份，避免两处各写一套裁决。
 * 调用方负责创建各个 Deferred（并把失败包进 Result），这里只等待、裁决和取消。
 */
internal object LyricsRace {
    sealed interface Final<out A, out B> {
        data class Ttml<A>(val index: Int, val value: A) : Final<A, Nothing>
        data class Search<B>(val value: B) : Final<Nothing, B>
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <A, B> run(
        ttml: List<Deferred<Result<A>>>,
        search: Deferred<Result<B>>,
        ttmlHit: (A) -> Boolean,
        searchHit: (B) -> Boolean,
        onEarlyTtml: suspend (index: Int, value: A) -> Unit,
        onEarlySearch: suspend (value: B) -> Unit,
    ): Final<A, B> = coroutineScope {
        val wake = Channel<Unit>(Channel.UNLIMITED)
        ttml.forEach { job -> job.invokeOnCompletion { wake.trySend(Unit) } }
        search.invokeOnCompletion { wake.trySend(Unit) }

        fun ttmlStates() = ttml.map { job ->
            when {
                !job.isCompleted -> AppleLyricsRacePolicy.State.PENDING
                job.getCompleted().getOrNull()?.let(ttmlHit) == true -> AppleLyricsRacePolicy.State.HIT
                else -> AppleLyricsRacePolicy.State.MISS
            }
        }

        fun searchState() = when {
            !search.isCompleted -> AppleLyricsRacePolicy.State.PENDING
            search.getCompleted().getOrNull()?.let(searchHit) == true -> AppleLyricsRacePolicy.State.HIT
            else -> AppleLyricsRacePolicy.State.MISS
        }

        var earlyPublished = false
        while (true) {
            when (val decision = AppleLyricsRacePolicy.decide(ttmlStates(), searchState(), earlyPublished)) {
                AppleLyricsRacePolicy.Decision.Wait -> wake.receive()
                is AppleLyricsRacePolicy.Decision.Early -> {
                    earlyPublished = true
                    val index = decision.ttmlIndex
                    if (index == null) {
                        onEarlySearch(search.getCompleted().getOrThrow())
                    } else {
                        onEarlyTtml(index, ttml[index].getCompleted().getOrThrow())
                    }
                }
                is AppleLyricsRacePolicy.Decision.FinalTtml -> {
                    val value = ttml[decision.index].getCompleted().getOrThrow()
                    search.cancel()
                    ttml.forEach { it.cancel() }
                    return@coroutineScope Final.Ttml(decision.index, value)
                }
                AppleLyricsRacePolicy.Decision.FinalSearch ->
                    return@coroutineScope Final.Search(search.getCompleted().getOrThrow())
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}
