/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.ChineseLyricsPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import io.github.libxposed.api.XposedInterface.Chain
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 选择 PlaybackItem 的 Adam ID；队列 MediaItem 没有歌词专用 ID 时，使用已验证的媒体 ID。
 */
internal fun selectPlaybackAdamId(
    runtimeAdamId: Long?,
    itemMediaId: String?,
    expectedContentSongId: String?,
): Long? = runtimeAdamId?.takeIf { it > 0L }
    ?: itemMediaId?.toLongOrNull()?.takeIf { it > 0L }
    ?: expectedContentSongId?.toLongOrNull()?.takeIf { it > 0L }

/**
 * 判断回调指针是否属于补充歌词模型。
 *
 * Apple 可能为同一个 native 地址创建不同 Java 包装对象，因此先比较对象身份，再比较
 * 非零 native address。候选集合同时包含当前模型和切歌后仍由 Apple 持有的历史模型。
 */
internal fun isKnownSupplementPointer(
    pointer: Any?,
    supplementPointers: List<Any>,
    nativeAddress: (Any) -> Long?,
): Boolean {
    pointer ?: return false
    if (supplementPointers.any { it === pointer }) return true
    val address = nativeAddress(pointer)?.takeIf { it != 0L } ?: return false
    return supplementPointers.any { supplement ->
        nativeAddress(supplement)?.let { it != 0L && it == address } == true
    }
}

internal fun shouldShowMissingLyricsSourceMenu(
    hasSupplementContent: Boolean,
    hasKnownNativeLyrics: Boolean,
): Boolean = hasSupplementContent && !hasKnownNativeLyrics

/**
 * 来源菜单会以约 300ms 的频率轮询补充歌词可用性。歌曲已经完成接管后，
 * 这类查询只能读取 Store，不能再次调度原生模型或刷新播放页。首次接受仍需
 * 执行完整激活；正文或翻译 revision 的后续变化由对应接收回调继续驱动。
 */
internal fun shouldRunSupplementActivationSideEffects(
    trigger: String,
    newlyAccepted: Boolean,
): Boolean = newlyAccepted || trigger != "source_menu_query"

/**
 * 歌词页恢复可见时，普通补充仍需 Apple 原生歌词不存在；当前明确选择且符合资格的
 * LunaBeat 则必须重新呈现，否则切歌期间在未 resumed Fragment 上完成的首次注入会留下
 * 上一首歌曲的 Adapter。
 */
internal fun shouldPresentSupplementOnLyricsPageResume(
    hasKnownNativeLyrics: Boolean,
    shouldPreferLunaBeat: Boolean,
): Boolean = !hasKnownNativeLyrics || shouldPreferLunaBeat

internal enum class AppleLyricsSourcePresentationAction {
    PRESENT_LUNA_BEAT,
    BUILD_LUNA_BEAT,
    PRESENT_APPLE_NATIVE,
    REFRESH_ONLY,
}

internal fun appleLyricsSourcePresentationAction(
    source: String,
    hasLunaBeatPointer: Boolean,
    hasAppleNativePointer: Boolean,
): AppleLyricsSourcePresentationAction = when (source) {
    "LB" -> if (hasLunaBeatPointer) {
        AppleLyricsSourcePresentationAction.PRESENT_LUNA_BEAT
    } else {
        AppleLyricsSourcePresentationAction.BUILD_LUNA_BEAT
    }
    "APPLE" -> if (hasAppleNativePointer) {
        AppleLyricsSourcePresentationAction.PRESENT_APPLE_NATIVE
    } else {
        AppleLyricsSourcePresentationAction.REFRESH_ONLY
    }
    else -> AppleLyricsSourcePresentationAction.REFRESH_ONLY
}

internal data class AppleNativeLyricsTimingStats(
    val lineCount: Int,
    val wordTimedLineCount: Int,
) {
    val isWordTimed: Boolean
        get() = wordTimedLineCount > 0
}

internal fun shouldUseLunaBeatOverAppleNativeLyrics(
    nativeStats: AppleNativeLyricsTimingStats?,
    lunaBeatLineCount: Int,
): Boolean {
    if (nativeStats == null || !nativeStats.isWordTimed) return true
    if (nativeStats.lineCount <= 0 || lunaBeatLineCount <= 0) return true
    return nativeStats.lineCount != lunaBeatLineCount
}

internal fun shouldRetainLunaBeatAlternativeAfterNativePresentation(
    lunaBeatEnabled: Boolean,
    storedSourceInfo: AppleMissingLyricsSourceInfo?,
): Boolean = lunaBeatEnabled && storedSourceInfo?.statuses.orEmpty().any {
    it.source == "LB" && it.found && it.wordTimed
}

internal fun shouldShowStoredSupplementSourceMenu(
    normalSupplementMenu: Boolean,
    lunaBeatEnabled: Boolean,
    storedSourceInfo: AppleMissingLyricsSourceInfo?,
): Boolean = normalSupplementMenu ||
    shouldRetainLunaBeatAlternativeAfterNativePresentation(
        lunaBeatEnabled = lunaBeatEnabled,
        storedSourceInfo = storedSourceInfo,
    )

/**
 * 三方候选的按钮可用性与最终呈现接管必须相互独立。
 *
 * Apple 原生请求仍在进行时，已经缓存且身份匹配的三方候选也应让歌词按钮可点；
 * 但是否构建、注入并显示三方模型，仍由 [AppleNativeLyricsTakeoverGate] 决定。
 */
internal fun shouldExposeSupplementAvailability(
    enabled: Boolean,
    hasSupplementContent: Boolean,
    identityAvailable: Boolean,
    hasKnownNativeLyrics: Boolean,
): Boolean = enabled &&
    hasSupplementContent &&
    identityAvailable &&
    !hasKnownNativeLyrics

internal data class AppleNativeLyricsTakeoverDecision(
    val allowed: Boolean,
    val reason: String,
    val recheckAfterMs: Long? = null,
)

internal enum class AppleNativeLyricsAvailabilitySignal {
    HAS_LYRICS,
    TIME_SYNCED,
}

/**
 * Apple 会分开查询“是否有歌词”和“是否有时间轴歌词”。单个 false 不足以
 * 否定官方歌词；只有同一当前 PlaybackItem 的两项原始值都为 false，才可视为
 * 已经确认没有官方原生歌词。
 */
internal class AppleNativeLyricsAvailabilityTracker {
    private data class State(
        var hasLyrics: Boolean? = null,
        var timeSynced: Boolean? = null,
    ) {
        fun conclusivelyAbsent(): Boolean = hasLyrics == false && timeSynced == false
    }

    private val states = LinkedHashMap<String, State>()

    /** Returns the changed conclusive-absence state, or null when the verdict did not change. */
    @Synchronized
    fun record(
        songId: String,
        signal: AppleNativeLyricsAvailabilitySignal,
        available: Boolean,
    ): Boolean? {
        if (songId.isBlank()) return null
        val state = state(songId)
        val previous = state.conclusivelyAbsent()
        when (signal) {
            AppleNativeLyricsAvailabilitySignal.HAS_LYRICS -> state.hasLyrics = available
            AppleNativeLyricsAvailabilitySignal.TIME_SYNCED -> state.timeSynced = available
        }
        val current = state.conclusivelyAbsent()
        return if (current == previous) null else current
    }

    @Synchronized
    fun clear(songId: String?) {
        songId?.let(states::remove)
    }

    @Synchronized
    private fun state(songId: String): State {
        states[songId]?.let { return it }
        if (states.size >= 32) {
            states.entries.iterator().run {
                if (hasNext()) {
                    next()
                    remove()
                }
            }
        }
        return State().also { states[songId] = it }
    }
}

/**
 * 把「三方歌词已经到达」与「Apple 原生歌词已经确认缺失」分开。
 *
 * Apple 的歌词请求可能比三方在线源更慢。请求仍在进行时，无论补充内容多早到达，
 * 都不能仅凭先后顺序或 availability getter 的瞬时值接管歌词页；只有原生空结果、
 * 歌词页已请求呈现但 Apple 始终未发起请求的稳定等待窗口，或原生请求的有界超时
 * 完成后才允许接管。
 */
internal class AppleNativeLyricsTakeoverGate(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val noRequestGraceMs: Long = 5_000L,
    private val nativeRequestTimeoutMs: Long = 20_000L,
) {
    private enum class Resolution {
        UNKNOWN,
        LOADING,
        ABSENT_FROM_RESULT,
        PRESENT,
    }

    private class State(
        var requestStartedAtMs: Long? = null,
        var presentationRequestedAtMs: Long? = null,
        var resolution: Resolution = Resolution.UNKNOWN,
    )

    private val states = LinkedHashMap<String, State>()

    @Synchronized
    fun observe(songId: String) {
        if (songId.isBlank()) return
        state(songId)
    }

    @Synchronized
    fun onNativeRequestStarted(songId: String) {
        if (songId.isBlank()) return
        val state = state(songId)
        if (state.resolution == Resolution.PRESENT) return
        state.requestStartedAtMs = clock()
        state.resolution = Resolution.LOADING
    }

    /**
     * 只有歌词页真的需要呈现时，才启动“Apple 未发起原生请求”的兜底窗口。
     * 候选在后台到达不能自行启动这个计时，否则会抢在慢原生歌词开始加载之前。
     */
    @Synchronized
    fun onSupplementPresentationRequested(songId: String) {
        if (songId.isBlank()) return
        val state = state(songId)
        if (state.resolution == Resolution.UNKNOWN && state.presentationRequestedAtMs == null) {
            state.presentationRequestedAtMs = clock()
        }
    }

    /** Returns false when an empty callback had no matching in-flight Apple request. */
    @Synchronized
    fun onNativeResult(songId: String, hasLyrics: Boolean): Boolean {
        if (songId.isBlank()) return false
        val state = state(songId)
        if (hasLyrics) {
            state.resolution = Resolution.PRESENT
            state.requestStartedAtMs = null
            return true
        }
        if (state.resolution != Resolution.LOADING) return false
        if (state.requestStartedAtMs == null) {
            return false
        }
        state.resolution = Resolution.ABSENT_FROM_RESULT
        state.requestStartedAtMs = null
        return true
    }

    @Synchronized
    fun decision(songId: String): AppleNativeLyricsTakeoverDecision {
        if (songId.isBlank()) {
            return AppleNativeLyricsTakeoverDecision(false, "song_id_missing")
        }
        val now = clock()
        val state = state(songId)
        return when (state.resolution) {
            Resolution.PRESENT ->
                AppleNativeLyricsTakeoverDecision(false, "native_lyrics_present")
            Resolution.ABSENT_FROM_RESULT ->
                AppleNativeLyricsTakeoverDecision(true, "native_empty_result")
            Resolution.LOADING -> {
                val startedAt = state.requestStartedAtMs ?: now
                val remaining = nativeRequestTimeoutMs - (now - startedAt)
                if (remaining <= 0L) {
                    AppleNativeLyricsTakeoverDecision(true, "native_request_timeout")
                } else {
                    AppleNativeLyricsTakeoverDecision(
                        allowed = false,
                        reason = "native_request_in_flight",
                        recheckAfterMs = remaining,
                    )
                }
            }
            Resolution.UNKNOWN -> {
                val requestedAt = state.presentationRequestedAtMs
                    ?: return AppleNativeLyricsTakeoverDecision(
                        allowed = false,
                        reason = "native_request_not_started",
                    )
                val remaining = noRequestGraceMs - (now - requestedAt)
                if (remaining <= 0L) {
                    AppleNativeLyricsTakeoverDecision(true, "native_request_not_observed")
                } else {
                    AppleNativeLyricsTakeoverDecision(
                        allowed = false,
                        reason = "native_request_grace",
                        recheckAfterMs = remaining,
                    )
                }
            }
        }
    }

    @Synchronized
    fun clear(songId: String?) {
        songId?.let(states::remove)
    }

    @Synchronized
    private fun state(songId: String): State {
        states[songId]?.let { return it }
        if (states.size >= 32) {
            states.entries.iterator().run {
                if (hasNext()) {
                    next()
                    remove()
                }
            }
        }
        return State().also { states[songId] = it }
    }
}

/**
 * 标记补充 TTML 正在经过 Apple 原生解析器的同步调用栈。
 *
 * 解析器会在返回 SongInfoPtr 之前同步进入歌词构建/呈现 Hook；此时指针还来不及
 * 写入 [AppleMissingLyricsStore]。用线程内嵌套深度覆盖这个短窗口，避免新补充模型
 * 被提前登记成 Apple 原生歌词。
 */
internal class AppleMissingLyricsNativeBuildScope {
    private val depth = ThreadLocal<Int>()

    fun isActive(): Boolean = (depth.get() ?: 0) > 0

    fun <T> within(block: () -> T): T {
        val previousDepth = depth.get() ?: 0
        depth.set(previousDepth + 1)
        return try {
            block()
        } finally {
            if (previousDepth == 0) {
                depth.remove()
            } else {
                depth.set(previousDepth)
            }
        }
    }
}
