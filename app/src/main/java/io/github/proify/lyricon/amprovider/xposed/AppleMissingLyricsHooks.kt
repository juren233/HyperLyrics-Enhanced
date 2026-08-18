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
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import io.github.libxposed.api.XposedInterface.Chain
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「为无歌词的歌曲补充歌词」功能在 Apple Music 进程内的实现：
 *
 * 1. 接收 SystemUI 回传的三方在线源歌词；
 * 2. 编译成 Apple Music 原生 TTML，通过 Apple 自己的 TTMLParserNative
 *    解析成原生 SongInfo 歌词模型；
 * 3. 在歌词页主结果消费入口 PlayerLyricsViewFragment.I2(SongInfoPtr) 上改写
 *    参数，把补充歌词的 SongInfo 塞进 Apple Music 自己的歌词显示链路，
 *    由原生适配器完成滚动、逐字点亮、模糊与样式渲染。
 *
 * 歌曲原生歌词存在时绝不注入：I2 收到真实原生 SongInfo 时保持原样，
 * 且曾为同一歌曲构建过原生歌词的歌曲会被永久排除。
 */
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

internal class AppleMissingLyricsHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> SharedPreferences?,
    private val currentPlaybackQueueMediaId: () -> String?,
    private val currentVisibleLyricsSongId: () -> String?,
    private val requestPresentationRefresh: (Any?, Any?, Any?) -> Unit,
    private val requestBlankNativeLyricsPageRecovery: (Any?) -> Unit,
    private val refreshVisibleSupplementTranslation: (String) -> Unit,
    private val refreshNowPlaying: (String?) -> Unit,
) {
    private companion object {
        const val MAX_REMEMBERED_NATIVE_LYRICS_SONG_IDS = 512
        const val RESUME_REFRESH_DELAY_MS = 400L
    }

    private val mainHandler: Handler
        get() = runtime.mainHandler

    val store = AppleMissingLyricsStore()

    private val nativeParser by lazy { AppleMissingLyricsNativeParser(runtime) }

    private val lyricsNativeTarget by lazy {
        runtime.hookResolver.resolveClass(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).target
    }

    private val playbackItemTarget by lazy {
        runtime.hookResolver
            .resolveClass(AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE)
            .target
    }

    private val lyricsSongTarget by lazy {
        runtime.hookResolver.resolveClass(AppleMusicHookPoint.APPLE_SONG_MODEL_CLASS).target
    }

    private val nativeLyricsAdamIds = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    private val nativeLyricsContentIds = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    /** 记录本进程中已用三方候选把歌词按钮置为可用的歌曲。 */
    private val supplementAvailabilitySongIds = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    private val loggedTtmlSampleKeys = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    /** 冷启动时避免可用性回调反复重试同一首歌曲的磁盘恢复。 */
    private val attemptedDiskRestoreSongIds = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    /**
     * 记录上次尝试恢复时 Store 正在服务的歌曲。切歌后 Store 内容变化，
     * 回到之前播放过的歌曲时必须允许重新尝试磁盘恢复。
     */
    @Volatile
    private var restoreAttemptContentSongId: String? = null
    private val resultPresentationHitLogged = AtomicBoolean(false)
    private val nativeBuildLock = Any()
    private val nativeBuildScope = AppleMissingLyricsNativeBuildScope()
    private val nativeTakeoverGate = AppleNativeLyricsTakeoverGate()
    private val nativeAvailabilityTracker = AppleNativeLyricsAvailabilityTracker()
    private val acceptedSupplementSongIds = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    private val scheduledTakeoverRechecks = ConcurrentHashMap<String, Long>()

    @Volatile
    private var pendingNativeBuildKey: NativeBuildKey? = null

    @Volatile
    private var lastAvailabilityDiagnostic: String? = null

    private val playerLyricsAvailabilityHitLogged = AtomicBoolean(false)
    private val playerSongBindingHitLogged = AtomicBoolean(false)
    private val playerLyricsAvailabilityDiagnosticKeys = ConcurrentHashMap.newKeySet<String>()
    private val playerSongBindingDiagnosticKeys = ConcurrentHashMap.newKeySet<String>()
    private val playerSongBindingSnapshots = ThreadLocalStack<PlayerSongBindingSnapshot>()

    private data class NativeBuildKey(
        val contentRevision: Long,
        val identity: AppleMissingLyricsPlaybackIdentity,
    )

    private data class PlaybackItemReference(
        val identity: AppleMissingLyricsPlaybackIdentity,
        val item: WeakReference<Any>,
    )

    private data class PlayerSongBindingSnapshot(
        val bindingIdentity: String,
        val itemIdentity: String?,
        val itemMediaId: String?,
        val queueMediaId: String?,
        val sameAsCapturedPlaybackItem: Boolean,
        val supplementContent: Boolean,
        val buttonIdentity: String?,
        val enabled: Boolean?,
        val selected: Boolean?,
        val clickable: Boolean?,
        val visibility: Int?,
        val alpha: Float?,
        val shown: Boolean?,
        val attached: Boolean?,
        val parentEnabled: Boolean?,
    )

    @Volatile
    private var currentPlaybackItemReference: PlaybackItemReference? = null

    fun installHooks() {
        runCatching {
            val presentationMethod = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_RESULT_PRESENTATION
            ).method
            runtime.hookRegistrar.installArgumentRewriteHook(presentationMethod) { chain ->
                if (
                    BuildConfig.DEBUG &&
                    resultPresentationHitLogged.compareAndSet(false, true)
                ) {
                    ProviderLogger.diagnostic(
                        "Apple Music 无歌词补充结果呈现 Hook 首次命中: " +
                            "fragment=${chain.thisObject?.javaClass?.name}"
                        )
                }
                recordAppleNativePresentationResult(chain.args.firstOrNull())
                rewriteNativeModelArgs(chain)
            }
            ProviderLogger.debug("Apple Music 无歌词补充结果呈现改写 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music 无歌词补充结果呈现改写 Hook 安装失败", it)
        }
        runCatching {
            // 时间轴地图构建同样是显示链路的一环：Apple 用空模型构建时改写为补充模型。
            val buildMethod = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD
            ).method
            runtime.hookRegistrar.installArgumentRewriteHook(buildMethod) { chain ->
                rewriteNativeModelArgs(chain)
            }
            ProviderLogger.debug("Apple Music 无歌词补充时间轴地图改写 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music 无歌词补充时间轴地图改写 Hook 安装失败", it)
        }
        runCatching {
            // 歌词页恢复可见时主动注入：覆盖「补充载荷先于页面打开到达」的时序。
            val onResume = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_UI_ON_RESUME
            ).method
            runtime.hookRegistrar.installHook(onResume, after = { chain, _ ->
                val fragment = chain.thisObject
                mainHandler.postDelayed(
                    {
                        maybeRequestInjectedPresentation(fragment)
                        // 已确认 Apple 原生歌词的歌曲走独立自愈：页面恢复可见但
                        // adapter 为空时，重新用当前队列 PlaybackItem 触发 loadLyrics。
                        requestBlankNativeLyricsPageRecovery(fragment)
                    },
                    RESUME_REFRESH_DELAY_MS,
                )
            })
            ProviderLogger.debug("Apple Music 无歌词补充页面恢复 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music 无歌词补充页面恢复 Hook 安装失败", it)
        }
        listOf(
            AppleMusicHookPoint.LYRICS_AVAILABILITY_HAS_LYRICS,
            AppleMusicHookPoint.LYRICS_AVAILABILITY_TIME_SYNCED,
        ).forEach { hookPoint ->
            runCatching {
                val method = runtime.hookResolver.resolveMethod(hookPoint).method
                runtime.hookRegistrar.installResultOverrideHook(method) { chain, original ->
                    recordNativeAvailability(
                        item = chain.thisObject,
                        hookPoint = hookPoint,
                        originalAvailable = original == true,
                    )
                    if (original == true) {
                        original
                    } else if (shouldExposeSupplementLyrics(chain.thisObject)) {
                        true
                    } else {
                        original
                    }
                }
                ProviderLogger.debug(
                    "Apple Music 无歌词补充歌词可用性 Hook 已安装: $hookPoint"
                )
            }.onFailure {
                ProviderLogger.error(
                    "Apple Music 无歌词补充歌词可用性 Hook 安装失败: $hookPoint",
                    it,
                )
            }
        }
        if (BuildConfig.DEBUG) {
            runCatching {
                val parserMethod = runtime.hookResolver.resolveMethod(
                    AppleMusicHookPoint.LYRICS_TTML_PARSER
                ).method
                runtime.hookRegistrar.installHook(parserMethod, before = { chain ->
                    val ttml = chain.args.firstOrNull() as? String ?: return@installHook
                    val key = "sample:${ttml.length}:${ttml.hashCode()}"
                    if (loggedTtmlSampleKeys.add(key)) {
                        ProviderLogger.debug(
                            "Apple TTML sample: bytes=${ttml.length}, " +
                                "head=${ttml.take(2000)}"
                        )
                    }
                })
            }.onFailure {
                ProviderLogger.error("Apple Music TTML 采样 Hook 安装失败", it)
            }
            installLyricsButtonDiagnostics()
        }
    }

    /**
     * 精确记录 Apple Music 播放页歌词按钮的最终消费者链。
     *
     * 原始 DEX 已确认：player.e1.i(PlaybackItem) 计算可用性，l7.N2.l() 再把结果写入
     * M2.a0.setEnabled。这里仅记录真实调用与 View 前后状态，绝不修改返回值或 View。
     */
    private fun installLyricsButtonDiagnostics() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR
            )
            runtime.hookRegistrar.installHook(resolved.method, after = { chain, result ->
                logPlayerLyricsAvailabilityCalculation(
                    chain = chain,
                    result = result,
                    target = resolved.target,
                )
            })
            ProviderLogger.diagnostic(
                "Apple Music 歌词按钮最终可用性计算 Hook 已安装: target=${resolved.method}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 歌词按钮最终可用性计算 Hook 安装失败", it)
        }
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE
            )
            runtime.hookRegistrar.installScopedHook(
                resolved.method,
                enter = { chain ->
                    val snapshot = capturePlayerSongBindingSnapshot(
                        binding = chain.thisObject,
                        target = resolved.target,
                    )
                    playerSongBindingSnapshots.push(snapshot)
                    if (playerSongBindingHitLogged.compareAndSet(false, true)) {
                        ProviderLogger.diagnostic(
                            "Apple Music 歌词按钮播放页 binding Hook 首次命中: " +
                                "binding=${snapshot.bindingIdentity}, " +
                                "item=${snapshot.itemIdentity}, " +
                                "itemMediaId=${snapshot.itemMediaId}, " +
                                "button=${snapshot.buttonIdentity}, " +
                                "enabled=${snapshot.enabled}"
                        )
                    }
                    true
                },
                after = { chain, _ ->
                    val before = playerSongBindingSnapshots.current
                    val after = capturePlayerSongBindingSnapshot(
                        binding = chain.thisObject,
                        target = resolved.target,
                    )
                    logPlayerSongBindingExecution(before, after)
                },
                exit = { playerSongBindingSnapshots.pop() },
            )
            ProviderLogger.diagnostic(
                "Apple Music 歌词按钮播放页 binding Hook 已安装: target=${resolved.method}, " +
                    "itemField=${resolved.target.runtimeMemberName(
                        AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD
                    )}, buttonField=${resolved.target.runtimeMemberName(
                        AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD
                    )}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 歌词按钮播放页 binding Hook 安装失败", it)
        }
    }

    private fun logPlayerLyricsAvailabilityCalculation(
        chain: Chain,
        result: Any?,
        target: AppleMusicHookTarget,
    ) {
        val item = chain.args.firstOrNull()
        val itemSongId = itemMediaId(item)?.takeIf(String::isNotBlank)
        val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
        val diagnosticSongId = queueSongId ?: itemSongId
        val effectiveHasLyrics = readBooleanMethod(
            item,
            target.runtimeMemberName(
                AppleMusicRuntimeMember.PLAYER_LYRICS_ITEM_HAS_LYRICS_METHOD
            ),
        )
        val hasCustomLyrics = readBooleanMethod(
            item,
            target.runtimeMemberName(
                AppleMusicRuntimeMember.PLAYER_LYRICS_ITEM_HAS_CUSTOM_LYRICS_METHOD
            ),
        )
        val currentCapturedItem = currentPlaybackItemReference?.item?.get()
        val storeContent = store.hasContent(diagnosticSongId)
        val nativeKnown = diagnosticSongId?.let(::hasKnownNativeLyrics) == true
        val availabilityExposed =
            diagnosticSongId != null && diagnosticSongId in supplementAvailabilitySongIds
        val presentationAccepted =
            diagnosticSongId != null && diagnosticSongId in acceptedSupplementSongIds
        val signature = listOf(
            debugObjectIdentity(item),
            itemSongId,
            queueSongId,
            effectiveHasLyrics,
            hasCustomLyrics,
            result,
            storeContent,
            nativeKnown,
            availabilityExposed,
            presentationAccepted,
            item != null && item === currentCapturedItem,
        ).joinToString("|")
        val firstHit = playerLyricsAvailabilityHitLogged.compareAndSet(false, true)
        val stateChanged = rememberDiagnostic(playerLyricsAvailabilityDiagnosticKeys, signature)
        if (firstHit || stateChanged) {
            ProviderLogger.diagnostic(
                "Apple Music 歌词按钮最终可用性计算: " +
                    "item=${debugObjectIdentity(item)}, itemMediaId=$itemSongId, " +
                    "queueMediaId=$queueSongId, effectiveHasLyrics=$effectiveHasLyrics, " +
                    "hasCustomLyrics=$hasCustomLyrics, result=${result as? Boolean}, " +
                    "storeContent=$storeContent, nativeLyricsKnown=$nativeKnown, " +
                    "availabilityExposed=$availabilityExposed, " +
                    "presentationAccepted=$presentationAccepted, " +
                    "sameAsCapturedPlaybackItem=${item != null && item === currentCapturedItem}"
            )
        }
    }

    private fun capturePlayerSongBindingSnapshot(
        binding: Any?,
        target: AppleMusicHookTarget,
    ): PlayerSongBindingSnapshot {
        val item = binding?.let { targetBinding ->
            runCatching {
                AppleReflection.field(
                    targetBinding,
                    target.runtimeMemberName(
                        AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD
                    ),
                )
            }.getOrNull()
        }
        val button = binding?.let { targetBinding ->
            runCatching {
                AppleReflection.field(
                    targetBinding,
                    target.runtimeMemberName(
                        AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD
                    ),
                ) as? View
            }.getOrNull()
        }
        val itemSongId = itemMediaId(item)?.takeIf(String::isNotBlank)
        val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
        val currentCapturedItem = currentPlaybackItemReference?.item?.get()
        return PlayerSongBindingSnapshot(
            bindingIdentity = debugObjectIdentity(binding) ?: "null",
            itemIdentity = debugObjectIdentity(item),
            itemMediaId = itemSongId,
            queueMediaId = queueSongId,
            sameAsCapturedPlaybackItem = item != null && item === currentCapturedItem,
            supplementContent = store.hasContent(queueSongId ?: itemSongId),
            buttonIdentity = debugObjectIdentity(button),
            enabled = button?.isEnabled,
            selected = button?.isSelected,
            clickable = button?.isClickable,
            visibility = button?.visibility,
            alpha = button?.alpha,
            shown = button?.isShown,
            attached = button?.isAttachedToWindow,
            parentEnabled = (button?.parent as? View)?.isEnabled,
        )
    }

    private fun logPlayerSongBindingExecution(
        before: PlayerSongBindingSnapshot?,
        after: PlayerSongBindingSnapshot,
    ) {
        val signature = listOf(before, after).joinToString("|")
        if (!rememberDiagnostic(playerSongBindingDiagnosticKeys, signature)) return
        ProviderLogger.diagnostic(
            "Apple Music 歌词按钮播放页 binding 执行: " +
                "binding=${after.bindingIdentity}, item=${after.itemIdentity}, " +
                "itemMediaId=${after.itemMediaId}, queueMediaId=${after.queueMediaId}, " +
                "sameAsCapturedPlaybackItem=${after.sameAsCapturedPlaybackItem}, " +
                "supplementContent=${after.supplementContent}, button=${after.buttonIdentity}, " +
                "beforeEnabled=${before?.enabled}, afterEnabled=${after.enabled}, " +
                "beforeSelected=${before?.selected}, afterSelected=${after.selected}, " +
                "clickable=${after.clickable}, visibility=${after.visibility}, " +
                "alpha=${after.alpha}, shown=${after.shown}, attached=${after.attached}, " +
                "parentEnabled=${after.parentEnabled}"
        )
    }

    private fun readBooleanMethod(instance: Any?, methodName: String): Boolean? =
        instance?.let { target ->
            runCatching { AppleReflection.call(target, methodName) as? Boolean }.getOrNull()
        }

    private fun debugObjectIdentity(instance: Any?): String? = instance?.let { target ->
        "${target.javaClass.name}@${System.identityHashCode(target).toString(16)}"
    }

    private fun rememberDiagnostic(keys: MutableSet<String>, signature: String): Boolean {
        if (keys.size >= 256) keys.clear()
        return keys.add(signature)
    }

    /** 歌词页可见且当前歌曲具备补充歌词时，主动用补充歌词指针触发原生呈现。 */
    private fun maybeRequestInjectedPresentation(fragment: Any? = null) {
        if (!isEnabled()) return
        val songId = currentSupplementSongId() ?: return
        if (hasKnownNativeLyrics(songId)) return
        // 冷启动时补充载荷可能晚于页面 onResume；先用磁盘缓存满足本次呈现请求。
        restoreCachedSupplement(songId)
        nativeTakeoverGate.onSupplementPresentationRequested(songId)
        if (!maybeActivateSupplement(songId, trigger = "page_resume")) return
        if (!store.hasContent(songId)) return
        val pointer = store.nativeSongInfoPointer(songId)
        if (pointer == null) {
            // 缓存刚恢复、原生模型尚未构建：构建完成后的回调会自行请求呈现刷新。
            scheduleNativeLyricsModel(songId)
            return
        }
        ProviderLogger.debug(
            "Apple Music 无歌词补充页面恢复主动呈现: id=$songId"
        )
        requestPresentationRefresh(pointer, fragment, currentPlaybackItem(songId))
    }

    fun isEnabled(): Boolean =
        preferences()?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
        ) == true

    /**
     * 当前补充链应服务的歌曲 ID。队列身份未发布时，歌词页已确认的可见歌曲 ID
     * 比任意 PlaybackItem 的 media ID 更可靠，避免把其他歌曲的补充模型注入当前页。
     */
    private fun currentSupplementSongId(): String? =
        currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
            ?: currentVisibleLyricsSongId()?.takeIf(String::isNotBlank)
            ?: store.playbackIdentity(null)?.contentSongId?.takeIf(String::isNotBlank)

    fun onNativeLyricsRequestStarted(songId: String?) {
        val resolvedSongId = songId?.takeIf(String::isNotBlank) ?: return
        // 补充模型为了同步当前 PlaybackItem 也会再次调用 Apple ViewModel.loadLyrics；
        // 已经完成接管后的这类自触发调用不能重新把补充链锁回 loading。
        if (resolvedSongId in acceptedSupplementSongIds) {
            if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "Apple Music 原生歌词请求忽略: id=$resolvedSongId, " +
                        "reason=supplement_takeover_active"
                )
            }
            return
        }
        nativeTakeoverGate.onNativeRequestStarted(resolvedSongId)
        ProviderLogger.debug(
            "Apple Music 原生歌词请求开始: id=$resolvedSongId"
        )
        scheduleTakeoverRecheck(resolvedSongId)
    }

    private fun recordNativeAvailability(
        item: Any?,
        hookPoint: AppleMusicHookPoint,
        originalAvailable: Boolean,
    ) {
        val itemSongId = itemMediaId(item)?.takeIf(String::isNotBlank)
        val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
        if (queueSongId != null && itemSongId != null && queueSongId != itemSongId) return
        val songId = queueSongId ?: itemSongId ?: return
        val signal = when (hookPoint) {
            AppleMusicHookPoint.LYRICS_AVAILABILITY_HAS_LYRICS ->
                AppleNativeLyricsAvailabilitySignal.HAS_LYRICS
            AppleMusicHookPoint.LYRICS_AVAILABILITY_TIME_SYNCED ->
                AppleNativeLyricsAvailabilitySignal.TIME_SYNCED
            else -> return
        }
        val conclusivelyAbsent = nativeAvailabilityTracker.record(
            songId = songId,
            signal = signal,
            available = originalAvailable,
        ) ?: return
        if (conclusivelyAbsent) {
            ProviderLogger.info(
                "Apple Music availability 暂无官方歌词，仅提前准备三方候选: " +
                    "id=$songId, presentation=wait_native_result"
            )
        } else {
            ProviderLogger.debug(
                "Apple Music availability 暂无歌词结论已撤销: id=$songId"
            )
        }
    }

    private fun takeoverDecision(songId: String): AppleNativeLyricsTakeoverDecision =
        if (hasKnownNativeLyrics(songId)) {
            AppleNativeLyricsTakeoverDecision(false, "native_lyrics_present")
        } else {
            nativeTakeoverGate.decision(songId)
        }

    private fun scheduleTakeoverRecheck(songId: String) {
        val decision = takeoverDecision(songId)
        val delayMs = decision.recheckAfterMs ?: return
        val targetAt = SystemClock.elapsedRealtime() + delayMs
        val existing = scheduledTakeoverRechecks[songId]
        if (existing != null && existing <= targetAt) return
        scheduledTakeoverRechecks[songId] = targetAt
        mainHandler.postDelayed(
            {
                if (scheduledTakeoverRechecks[songId] != targetAt) return@postDelayed
                scheduledTakeoverRechecks.remove(songId, targetAt)
                maybeActivateSupplement(songId, trigger = "resolution_recheck")
            },
            delayMs.coerceAtLeast(1L),
        )
    }

    private fun maybeActivateSupplement(songId: String, trigger: String): Boolean {
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "activation_query_$trigger",
        )
        if (!isEnabled() || currentSupplementSongId() != songId) return false
        val decision = takeoverDecision(songId)
        if (!decision.allowed) {
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 无歌词补充接管等待: id=$songId, trigger=$trigger, " +
                        "reason=${decision.reason}, hasContent=${store.hasContent(songId)}, " +
                        "recheckAfterMs=${decision.recheckAfterMs}"
                )
            }
            scheduleTakeoverRecheck(songId)
            return false
        }
        if (!store.hasContent(songId)) return false
        val newlyAccepted = acceptedSupplementSongIds.add(songId)
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "activation_allowed",
            details = "trigger=$trigger,newlyAccepted=$newlyAccepted,revision=${store.revision()}",
        )
        if (!shouldRunSupplementActivationSideEffects(trigger, newlyAccepted)) {
            AppleSourceSwitchPerformanceDiagnostics.record(
                songId = songId,
                event = "activation_side_effects_skipped",
                details = "trigger=$trigger,revision=${store.revision()}",
            )
            return true
        }
        if (newlyAccepted || BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 无歌词补充允许接管: id=$songId, trigger=$trigger, " +
                    "reason=${decision.reason}"
            )
        }
        scheduleNativeLyricsModel(songId)
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "refresh_now_playing_requested",
            details = "trigger=$trigger",
        )
        refreshNowPlaying(songId)
        return true
    }

    fun hasTranslation(songId: String?): Boolean =
        store.hasTranslation(resolveSupplementContentId(songId))

    fun translationSource(songId: String?): String? =
        store.translationSource(resolveSupplementContentId(songId))

    fun pronunciationSource(songId: String?): String? =
        store.pronunciationSource(resolveSupplementContentId(songId))

    fun translationForLine(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): String? = store.translation(resolveSupplementContentId(songId), begin, end, text)

    fun sourceInfo(songId: String?): AppleMissingLyricsSourceInfo? {
        songId?.takeIf(String::isNotBlank)?.let(::restoreCachedSupplement)
        return store.sourceInfo(resolveSupplementContentId(songId))
    }

    /**
     * Debug-only timing/source description used to correlate source switching with
     * Apple's adapter coordinates. This intentionally exposes no lyric text; it only
     * records the source metadata, line/word counts, and begin-time fingerprints.
     */
    fun timingDebugSnapshot(songId: String?): String {
        if (!BuildConfig.DEBUG) return "disabled"
        val contentSongId = songId?.takeIf(String::isNotBlank)
            ?: return "song=none"
        restoreCachedSupplement(contentSongId)
        val resolvedSongId = resolveSupplementContentId(contentSongId)
        val lines = store.lines(resolvedSongId)
        val info = store.sourceInfo(resolvedSongId)
        val wordLines = lines.count { it.words.size >= 2 }
        val wordCount = lines.sumOf { it.words.size }
        var beginFingerprint = 1
        lines.forEach { line ->
            beginFingerprint = 31 * beginFingerprint + line.begin.hashCode()
            beginFingerprint = 31 * beginFingerprint + line.end.hashCode()
        }
        val beginSample = lines
            .take(8)
            .joinToString(",") { "${it.begin}-${it.end}" }
        val status = info?.statuses.orEmpty().joinToString(";") {
            "${it.source}:${it.lineCount}:${it.wordTimed}:${it.found}"
        }
        return "source=${info?.selectedSource ?: "none"},statuses=$status," +
            "lines=${lines.size},wordLines=$wordLines,words=$wordCount," +
            "beginFingerprint=$beginFingerprint,begins=[$beginSample]," +
            "revision=${store.revision()}"
    }

    fun hasSupplementContent(songId: String?): Boolean {
        val contentSongId = songId?.takeIf(String::isNotBlank) ?: return false
        // 冷启动时来源菜单可能先于可用性回调查询 Store；此处同样允许磁盘恢复。
        restoreCachedSupplement(contentSongId)
        maybeActivateSupplement(contentSongId, trigger = "source_menu_query")
        val resolvedSongId = resolveSupplementContentId(contentSongId) ?: return false
        return shouldShowMissingLyricsSourceMenu(
            hasSupplementContent = store.hasContent(resolvedSongId),
            hasKnownNativeLyrics = hasKnownNativeLyrics(resolvedSongId),
        )
    }

    private fun resolveSupplementContentId(songId: String?): String? =
        songId?.takeIf { it in acceptedSupplementSongIds && store.hasContent(it) }

    /** 判断指针是否为补充歌词生成的原生模型（不应被记为 Apple 原生歌词）。 */
    fun isSupplementPointer(pointer: Any?): Boolean =
        nativeBuildScope.isActive() || isKnownSupplementPointer(
            pointer = pointer,
            supplementPointers = store.knownNativeSongInfoPointers(),
            nativeAddress = ::nativePointerAddress,
        )

    /** 由原生歌词呈现 Hook 回传：hasLines=true 表示 Apple 为该歌曲构建了原生歌词。 */
    fun onNativeLyricsState(songId: String?, hasLines: Boolean) {
        if (songId.isNullOrBlank()) return
        val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
        val identity = store.playbackIdentity(queueSongId)
        val contentSongId = when {
            identity?.adamId?.toString() == songId -> identity.contentSongId
            queueSongId == songId -> queueSongId
            else -> songId
        }
        if (hasLines) {
            if (
                nativeLyricsAdamIds.size >= MAX_REMEMBERED_NATIVE_LYRICS_SONG_IDS ||
                nativeLyricsContentIds.size >= MAX_REMEMBERED_NATIVE_LYRICS_SONG_IDS
            ) {
                nativeLyricsAdamIds.clear()
                nativeLyricsContentIds.clear()
            }
            nativeLyricsAdamIds.add(songId)
            if (identity?.adamId?.toString() == songId) {
                nativeLyricsContentIds.add(identity.contentSongId)
            } else if (contentSongId == songId) {
                nativeLyricsContentIds.add(contentSongId)
            }
            nativeTakeoverGate.onNativeResult(contentSongId, hasLyrics = true)
            acceptedSupplementSongIds.remove(contentSongId)
            supplementAvailabilitySongIds.remove(contentSongId)
            scheduledTakeoverRechecks.remove(contentSongId)
            discardSupplementForConfirmedNativeLyrics(contentSongId)
        } else {
            val acceptedEmptyResult = nativeTakeoverGate.onNativeResult(
                songId = contentSongId,
                hasLyrics = false,
            )
            if (acceptedEmptyResult) {
                ProviderLogger.debug(
                    "Apple Music 原生歌词请求完成: id=$contentSongId, result=empty"
                )
                maybeActivateSupplement(contentSongId, trigger = "native_empty_result")
            } else if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "Apple Music 原生空歌词状态忽略: id=$contentSongId, " +
                        "reason=no_matching_request"
                )
            }
        }
        ProviderLogger.debug(
            "Apple Music 无歌词补充原生歌词状态: adamId=$songId, " +
                "contentId=$contentSongId, hasLines=$hasLines"
        )
    }

    private fun discardSupplementForConfirmedNativeLyrics(songId: String) {
        val deleted = DiskSongManager.deleteMissingLyrics(songId)
        val cleared = store.clear(songId)
        if (!cleared && deleted) return
        ProviderLogger.info(
            "Apple Music 原生歌词优先，撤销三方补充: id=$songId, " +
                "storeCleared=$cleared, diskDeleted=$deleted"
        )
        if (cleared) {
            mainHandler.post { refreshNowPlaying(songId) }
        }
    }

    /** 当前歌曲已确认 Apple 原生歌词时，阻止旧缓存迁移为补充歌词。 */
    fun hasKnownNativeLyricsFor(songId: String): Boolean = hasKnownNativeLyrics(songId)

    /** 捕获 Apple 歌词 ViewModel 实际消费的 PlaybackItem 身份。 */
    fun onLyricsItem(item: Any?) {
        if (!isEnabled()) return
        val identity = capturePlaybackIdentity(item) ?: return
        nativeTakeoverGate.observe(identity.contentSongId)
        val currentSongId = currentSupplementSongId()
        if (currentSongId != null && identity.contentSongId != currentSongId) {
            ProviderLogger.debug(
                "Apple Music 无歌词补充忽略非当前歌词页 loadLyrics: " +
                    "itemId=${identity.contentSongId}, currentId=$currentSongId"
            )
            return
        }
        // 页面打开时当前队列身份可能尚未发布；先恢复磁盘缓存，
        // 保证 buildTimeRangeToLyricsMap 与可用性回调在同一帧内可用。
        restoreCachedSupplement(identity.contentSongId)
        maybeActivateSupplement(identity.contentSongId, trigger = "lyrics_item")
    }

    /**
     * 播放页在歌词按钮可用性计算之前就会经过当前队列项路径；从这里捕获真实
     * PlaybackItem 身份，避免“按钮可进入后才调用 loadLyrics()”的循环依赖。
     */
    fun onCurrentPlaybackItem(contentSongId: String, item: Any?, queueId: Long) {
        if (!isEnabled() || contentSongId.isBlank()) return
        nativeTakeoverGate.observe(contentSongId)
        scheduleTakeoverRecheck(contentSongId)
        restoreCachedSupplement(contentSongId)
        val identity = capturePlaybackIdentity(
            item = item,
            expectedContentSongId = contentSongId,
            queueIdOverride = queueId,
        ) ?: return
        item?.let {
            currentPlaybackItemReference = PlaybackItemReference(identity, WeakReference(it))
        }
        val activated = maybeActivateSupplement(
            identity.contentSongId,
            trigger = "current_playback_item",
        )
        if (activated) {
            // 覆盖“补充载荷先到、队列身份后到”的冷启动时序。身份就绪后再重放一次，
            // 同时刷新 PlaybackItem DataBinding；此时 hasLyrics()/hasTimeSyncedLyrics()
            // 已具备返回 true 的完整前提。
            refreshNowPlaying(identity.contentSongId)
        }
    }

    fun receiveSupplement(song: Song) {
        if (!isEnabled()) {
            ProviderLogger.debug("Apple Music 无歌词补充被忽略: reason=feature_disabled")
            return
        }
        val songId = song.id?.takeIf(String::isNotBlank) ?: return
        val receiveStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val incomingLines = song.lyrics.orEmpty()
        val incomingWordLines = incomingLines.count { it.words.orEmpty().size >= 2 }
        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
            songId = songId,
            stage = "supplement_store_receive_started",
            details = "lines=${incomingLines.size},wordLines=$incomingWordLines," +
                "thread=${Thread.currentThread().name}"
        )
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "supplement_received",
            units = incomingLines.size.toLong(),
            details = "wordLines=$incomingWordLines,thread=${Thread.currentThread().name}",
        )
        nativeTakeoverGate.observe(songId)
        if (hasKnownNativeLyrics(songId)) {
            ProviderLogger.info(
                "Apple Music 无歌词补充被原生歌词拒绝: id=$songId, " +
                    "reason=native_lyrics_present"
            )
            discardSupplementForConfirmedNativeLyrics(songId)
            return
        }
        val hadContent = store.hasContent(songId)
        val revisionBefore = store.revision()
        val storeUpdateStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
            songId = songId,
            stage = "supplement_store_update_started",
            details = "hadContent=$hadContent,revision=$revisionBefore," +
                "thread=${Thread.currentThread().name}"
        )
        val updateResult = store.updateDetailed(song)
        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
            songId = songId,
            stage = "supplement_store_update_finished",
            details = "kind=${updateResult.kind},changed=${updateResult.requiresNativeRebuild}," +
                "revision=$revisionBefore->${store.revision()}," +
                "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - storeUpdateStartedAtNanos) / 1_000_000.0}," +
                "thread=${Thread.currentThread().name}"
        )
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "store_update",
            durationNanos = SystemClock.elapsedRealtimeNanos() - storeUpdateStartedAtNanos,
            units = incomingLines.size.toLong(),
            details = "kind=${updateResult.kind}," +
                "changed=${updateResult.requiresNativeRebuild}," +
                "revision=$revisionBefore->${store.revision()}",
        )
        if (updateResult.requiresNativeRebuild) {
            ProviderLogger.info(
                "Apple Music 无歌词补充已接收: id=$songId, " +
                    "lines=${incomingLines.size}, wordLines=$incomingWordLines"
            )
            val diskWriteStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            val diskSaved = DiskSongManager.saveMissingLyrics(song)
            AppleSourceSwitchPerformanceDiagnostics.record(
                songId = songId,
                event = "disk_cache_write",
                durationNanos = SystemClock.elapsedRealtimeNanos() - diskWriteStartedAtNanos,
                details = "saved=$diskSaved,contentChanged=true",
            )
            if (!diskSaved) {
                ProviderLogger.debug(
                    "Apple Music 无歌词补充磁盘缓存写入失败: id=$songId"
                )
            }
            // 候选第一次到达就刷新播放页可用性，让 hasLyrics() 立即重新判定并
            // 保持歌词按钮可进入；这不代表允许构建或注入三方模型。
            if (!hadContent && currentSupplementSongId() == songId) {
                if (BuildConfig.DEBUG) {
                    ProviderLogger.diagnostic(
                        "Apple Music 无歌词补充候选已就绪，刷新按钮可用性: id=$songId"
                    )
                }
                refreshNowPlaying(songId)
            }
            // Apple 原生请求仍在进行时不得构建或注入补充模型。最终呈现继续由
            // takeover gate 决定，与上面的按钮可用性刷新相互独立。
            maybeActivateSupplement(songId, trigger = "supplement_received")
            AppleSourceSwitchPerformanceDiagnostics.record(
                songId = songId,
                event = "receive_supplement_total",
                durationNanos = SystemClock.elapsedRealtimeNanos() - receiveStartedAtNanos,
                details = "changed=true,hadContent=$hadContent",
            )
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = songId,
                stage = "supplement_store_receive_finished",
                details = "kind=${updateResult.kind},changed=true,hadContent=$hadContent,totalMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - receiveStartedAtNanos) / 1_000_000.0) +
                    ",thread=${Thread.currentThread().name}"
            )
            return
        }
        // 正文/时间轴没有变化时保留现有 native pointer。翻译变化只重绑当前可见行，
        // 元数据变化只持久化，完全相同的竞速载荷不再写盘或刷新播放页。
        if (updateResult.shouldPersist && store.hasContent(songId)) {
            val diskWriteStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = songId,
                stage = "supplement_metadata_disk_write_started",
                details = "thread=${Thread.currentThread().name}"
            )
            val diskSaved = DiskSongManager.saveMissingLyrics(song)
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = songId,
                stage = "supplement_metadata_disk_write_finished",
                details = "saved=$diskSaved,elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - diskWriteStartedAtNanos) / 1_000_000.0) +
                    ",thread=${Thread.currentThread().name}"
            )
            AppleSourceSwitchPerformanceDiagnostics.record(
                songId = songId,
                event = "disk_cache_write",
                durationNanos = SystemClock.elapsedRealtimeNanos() - diskWriteStartedAtNanos,
                details = "saved=$diskSaved,contentChanged=false,kind=${updateResult.kind}",
            )
        }
        if (updateResult.kind == AppleMissingLyricsUpdateKind.TRANSLATION_ONLY) {
            AppleSourceSwitchPerformanceDiagnostics.record(
                songId = songId,
                event = "supplement_translation_visible_refresh_requested",
            )
            refreshVisibleSupplementTranslation(songId)
        }
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "receive_supplement_total",
            durationNanos = SystemClock.elapsedRealtimeNanos() - receiveStartedAtNanos,
            details = "kind=${updateResult.kind},changed=false,hadContent=$hadContent",
        )
        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
            songId = songId,
            stage = "supplement_store_receive_finished",
            details = "kind=${updateResult.kind},changed=false,hadContent=$hadContent,totalMs=" +
                ((SystemClock.elapsedRealtimeNanos() - receiveStartedAtNanos) / 1_000_000.0) +
                ",thread=${Thread.currentThread().name}"
        )
    }

    fun clearSupplement(songId: String?) {
        restoreAttemptContentSongId = null
        songId?.takeIf(String::isNotBlank)?.let { id ->
            acceptedSupplementSongIds.remove(id)
            supplementAvailabilitySongIds.remove(id)
            attemptedDiskRestoreSongIds.remove(id)
            DiskSongManager.deleteMissingLyrics(id)
        }
        if (store.clear(songId)) {
            ProviderLogger.debug("Apple Music 无歌词补充已清除: id=$songId")
            refreshNowPlaying(songId)
        }
    }

    private fun restoreCachedSupplement(songId: String) {
        if (store.hasContent(songId)) return
        val currentContentSongId = store.contentSongId()
        if (
            songId in attemptedDiskRestoreSongIds &&
            restoreAttemptContentSongId == currentContentSongId
        ) {
            // 同一 Store 内容窗口内已经尝试过该歌曲；切歌后 contentSongId 变化，
            // 再切回来时必须允许重新恢复。
            return
        }
        attemptedDiskRestoreSongIds.add(songId)
        restoreAttemptContentSongId = currentContentSongId
        val cached = DiskSongManager.loadMissingLyrics(songId) ?: run {
            ProviderLogger.debug(
                "Apple Music 无歌词补充磁盘恢复未命中: id=$songId"
            )
            return
        }
        if (cached.id != songId) {
            ProviderLogger.debug(
                "Apple Music 无歌词补充磁盘缓存 ID 不匹配: " +
                    "expected=$songId, cached=${cached.id}"
            )
            return
        }
        if (hasKnownNativeLyrics(songId)) {
            DiskSongManager.deleteMissingLyrics(songId)
            ProviderLogger.debug(
                "Apple Music 无歌词补充磁盘缓存被原生歌词拒绝: id=$songId"
            )
            return
        }
        nativeTakeoverGate.observe(songId)
        if (!store.update(cached)) return
        ProviderLogger.info(
            "Apple Music 无歌词补充已从磁盘恢复: id=$songId, " +
                "lines=${cached.lyrics.orEmpty().size}"
        )
        maybeActivateSupplement(songId, trigger = "disk_restore")
    }

    fun onPreferenceChanged() {
        attemptedDiskRestoreSongIds.clear()
        restoreAttemptContentSongId = null
        if (!isEnabled()) {
            val songId = currentPlaybackQueueMediaId()
            songId?.let {
                acceptedSupplementSongIds.remove(it)
                supplementAvailabilitySongIds.remove(it)
                scheduledTakeoverRechecks.remove(it)
                nativeTakeoverGate.clear(it)
            }
            if (store.clear()) {
                refreshNowPlaying(songId)
            }
        }
    }

    private fun scheduleNativeLyricsModel(songId: String) {
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "native_model_schedule_call",
            details = "revision=${store.revision()}",
        )
        if (songId !in acceptedSupplementSongIds) {
            AppleSourceSwitchPerformanceDiagnostics.record(
                songId = songId,
                event = "native_model_deferred",
                details = "reason=native_resolution_pending",
            )
            if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "Apple Music 无歌词补充暂缓原生模型构建: " +
                        "id=$songId, reason=native_resolution_pending"
                )
            }
            scheduleTakeoverRecheck(songId)
            return
        }
        val identity = store.playbackIdentity(songId) ?: run {
            AppleSourceSwitchPerformanceDiagnostics.record(
                songId = songId,
                event = "native_model_deferred",
                details = "reason=playback_identity_missing",
            )
            ProviderLogger.debug(
                "Apple Music 无歌词补充暂缓原生模型构建: " +
                    "id=$songId, reason=playback_identity_missing"
            )
            return
        }
        val key = NativeBuildKey(
            contentRevision = store.revision(),
            identity = identity,
        )
        synchronized(nativeBuildLock) {
            if (pendingNativeBuildKey == key) {
                AppleSourceSwitchPerformanceDiagnostics.record(
                    songId = songId,
                    event = "native_model_schedule_deduplicated",
                    details = "revision=${key.contentRevision}",
                )
                return
            }
            pendingNativeBuildKey = key
        }
        val queuedAtNanos = SystemClock.elapsedRealtimeNanos()
        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
            songId = songId,
            stage = "native_model_main_posted",
            details = "revision=${key.contentRevision},thread=${Thread.currentThread().name}"
        )
        mainHandler.post {
            if (pendingNativeBuildKey != key) {
                AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                    songId = songId,
                    stage = "native_model_main_skipped",
                    details = "reason=key_replaced,revision=${key.contentRevision}," +
                        "thread=${Thread.currentThread().name}"
                )
                return@post
            }
            val mainStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = songId,
                stage = "native_model_main_started",
                details = "revision=${key.contentRevision},queueWaitMs=" +
                    ((mainStartedAtNanos - queuedAtNanos) / 1_000_000.0) +
                    ",thread=${Thread.currentThread().name}"
            )
            AppleSourceSwitchPerformanceDiagnostics.record(
                songId = songId,
                event = "native_model_main_queue_wait",
                durationNanos = SystemClock.elapsedRealtimeNanos() - queuedAtNanos,
                details = "revision=${key.contentRevision}",
            )
            try {
                buildNativeLyricsModel(key)
            } finally {
                synchronized(nativeBuildLock) {
                    if (pendingNativeBuildKey == key) {
                        pendingNativeBuildKey = null
                    }
                }
                AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                    songId = songId,
                    stage = "native_model_main_finished",
                    details = "revision=${key.contentRevision},elapsedMs=" +
                        ((SystemClock.elapsedRealtimeNanos() - mainStartedAtNanos) / 1_000_000.0) +
                        ",thread=${Thread.currentThread().name}"
                )
            }
        }
    }

    private fun buildNativeLyricsModel(key: NativeBuildKey) {
        val identity = key.identity
        val songId = identity.contentSongId
        val totalStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        if (!isEnabled() || key.contentRevision != store.revision()) return
        if (songId !in acceptedSupplementSongIds) return
        if (!store.isCurrentIdentity(identity)) return
        if (hasKnownNativeLyrics(songId, identity.adamId)) {
            ProviderLogger.debug(
                "Apple Music 无歌词补充跳过原生模型构建: reason=native_lyrics_present"
            )
            return
        }
        if (store.nativeSongInfoPointer(songId) != null) return
        val lines = store.lines(songId)
        if (lines.isEmpty()) return
        val ttmlStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val ttml = AppleMissingLyricsTtml.build(lines, store.durationMs(songId))
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "ttml_build",
            durationNanos = SystemClock.elapsedRealtimeNanos() - ttmlStartedAtNanos,
            units = lines.size.toLong(),
            details = "bytes=${ttml.length}",
        )
        val parseStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val pointer = nativeBuildScope.within {
            nativeParser.parse(ttml)
        }
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "native_ttml_parse",
            durationNanos = SystemClock.elapsedRealtimeNanos() - parseStartedAtNanos,
            units = lines.size.toLong(),
            details = "pointer=${pointer != null}",
        )
        if (pointer == null) {
            ProviderLogger.error(
                "Apple Music 无歌词补充原生模型构建失败: id=$songId, " +
                    "ttmlBytes=${ttml.length}"
            )
            return
        }
        if (!applyNativeSongIdentity(pointer, identity)) {
            releaseNativePointer(pointer)
            return
        }
        if (!store.updateNativeSongInfoPointer(pointer, identity)) {
            ProviderLogger.debug(
                "Apple Music 无歌词补充原生模型已丢弃: " +
                    "id=$songId, reason=stale_identity"
            )
            return
        }
        val parsedLines = readNativeLineCount(pointer)
        val parsedWords = readNativeWordCount(pointer)
        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
            songId = songId,
            stage = "native_model_pointer_installed",
            details = "revision=${key.contentRevision}," +
                "pointer=${pointer.javaClass.name}@${System.identityHashCode(pointer)}," +
                "parsedLines=$parsedLines,parsedWords=$parsedWords",
        )
        ProviderLogger.info(
            "Apple Music 无歌词补充原生模型已生成: id=$songId, " +
                "adamId=${identity.adamId}, queueId=${identity.queueId}, " +
                "ttmlBytes=${ttml.length}, parsedLines=$parsedLines, " +
                "parsedWords=$parsedWords"
        )
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "native_model_build_total",
            durationNanos = SystemClock.elapsedRealtimeNanos() - totalStartedAtNanos,
            units = parsedLines.coerceAtLeast(0).toLong(),
            details = "parsedWords=$parsedWords,revision=${key.contentRevision}",
        )
        val presentationStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        requestPresentationRefresh(pointer, null, currentPlaybackItem(songId))
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "presentation_refresh_request",
            durationNanos = SystemClock.elapsedRealtimeNanos() - presentationStartedAtNanos,
            details = "pointer=true",
        )
    }

    private fun currentPlaybackItem(songId: String): Any? {
        val reference = currentPlaybackItemReference ?: return null
        if (reference.identity.contentSongId != songId) return null
        if (store.playbackIdentity(songId) != reference.identity) return null
        return reference.item.get()
    }

    private fun hasKnownNativeLyrics(songId: String, adamId: Long? = null): Boolean =
        songId in nativeLyricsContentIds ||
            adamId?.toString()?.let(nativeLyricsAdamIds::contains) == true ||
            store.playbackIdentity(songId)
                ?.adamId
                ?.toString()
                ?.let(nativeLyricsAdamIds::contains) == true

    private fun capturePlaybackIdentity(
        item: Any?,
        expectedContentSongId: String? = null,
        queueIdOverride: Long? = null,
    ): AppleMissingLyricsPlaybackIdentity? {
        item ?: return null
        val runtimeAdamId = runCatching {
            AppleReflection.call(
                item,
                lyricsSongTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_SONG_ID_METHOD
                ),
            )?.toString()?.toLongOrNull()
        }.getOrNull()
        val itemMediaId = itemMediaId(item)
        val adamId = selectPlaybackAdamId(
            runtimeAdamId = runtimeAdamId,
            itemMediaId = itemMediaId,
            expectedContentSongId = expectedContentSongId,
        ) ?: return null
        val itemQueueId = runCatching {
            (AppleReflection.call(
                item,
                lyricsSongTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_SONG_QUEUE_ID_METHOD
                ),
            ) as? Number)?.toLong()
        }.getOrNull() ?: 0L
        val queueId = queueIdOverride?.takeIf { it > 0L } ?: itemQueueId
        val contentSongId = expectedContentSongId
            ?.takeIf(String::isNotBlank)
            ?: currentPlaybackQueueMediaId()
            ?.takeIf(String::isNotBlank)
            ?: itemMediaId
            ?: adamId.toString()
        if (
            !itemMediaId.isNullOrBlank() &&
            itemMediaId != contentSongId &&
            adamId.toString() != contentSongId
        ) {
            ProviderLogger.debug(
                "Apple Music 无歌词补充忽略过期 PlaybackItem: " +
                    "contentId=$contentSongId, itemMediaId=$itemMediaId, adamId=$adamId"
            )
            return null
        }
        val identity = AppleMissingLyricsPlaybackIdentity(
            contentSongId = contentSongId,
            adamId = adamId,
            queueId = queueId,
        )
        if (store.updatePlaybackIdentity(identity)) {
            ProviderLogger.debug(
                "Apple Music 无歌词补充已捕获播放身份: " +
                    "contentId=$contentSongId, adamId=$adamId, queueId=$queueId"
            )
        }
        return identity
    }

    /**
     * Apple 自身链路在 TTML 解析后会调用 SongInfoNative.setAdamId/setQueueId
     * （6.5.1 原始 DEX：ttml/f#e）。歌词页 I2 会严格比较 adamId 与当前条目 ID。
     */
    private fun applyNativeSongIdentity(
        pointer: Any,
        identity: AppleMissingLyricsPlaybackIdentity,
    ): Boolean {
        val songNative = runCatching {
            AppleReflection.call(
                pointer,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
                ),
            )
        }.getOrNull() ?: return false
        val writeResult = runCatching {
            AppleReflection.call(
                songNative,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SET_ADAM_ID_METHOD
                ),
                identity.adamId,
            )
            AppleReflection.call(
                songNative,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SET_QUEUE_ID_METHOD
                ),
                identity.queueId,
            )
        }
        if (writeResult.isFailure) {
            ProviderLogger.error(
                "Apple Music 无歌词补充歌曲身份写入失败",
                writeResult.exceptionOrNull(),
            )
            return false
        }
        val actualAdamId = runCatching {
            (AppleReflection.call(
                songNative,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD
                ),
            ) as? Number)?.toLong()
        }.getOrNull()
        val actualQueueId = runCatching {
            (AppleReflection.call(
                songNative,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_QUEUE_ID_METHOD
                ),
            ) as? Number)?.toLong()
        }.getOrNull()
        if (actualAdamId != identity.adamId || actualQueueId != identity.queueId) {
            ProviderLogger.error(
                "Apple Music 无歌词补充歌曲身份校验失败: " +
                    "expectedAdamId=${identity.adamId}, actualAdamId=$actualAdamId, " +
                    "expectedQueueId=${identity.queueId}, actualQueueId=$actualQueueId"
            )
            return false
        }
        return true
    }

    private fun nativePointerAddress(pointer: Any): Long? = runCatching {
        (AppleReflection.call(
            pointer,
            lyricsNativeTarget.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_ADDRESS_METHOD
            ),
        ) as? Number)?.toLong()
    }.getOrNull()

    private fun releaseNativePointer(pointer: Any?) {
        pointer ?: return
        runCatching { AppleReflection.call(pointer, "deallocate") }
            .onFailure {
                ProviderLogger.debug(
                    "Apple Music 补充歌词未绑定原生指针释放失败: ${it.message}"
                )
            }
    }

    private fun firstNativeLine(pointer: Any): Any? {
        val songNative = runCatching {
            AppleReflection.call(
                pointer,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
                ),
            )
        }.getOrNull() ?: return null
        val sections = runCatching {
            AppleReflection.call(
                songNative,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD
                ),
            )
        }.getOrNull() ?: return null
        val firstSectionPointer = runCatching {
            AppleReflection.call(
                sections,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
                ),
                0L,
            )
        }.recoverCatching {
            AppleReflection.call(
                sections,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
                ),
                0,
            )
        }.getOrNull() ?: return null
        val firstSection = runCatching {
            AppleReflection.call(
                firstSectionPointer,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
                ),
            )
        }.getOrNull() ?: return null
        val linesVector = runCatching {
            AppleReflection.call(
                firstSection,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD
                ),
            )
        }.getOrNull() ?: return null
        val firstLinePointer = runCatching {
            AppleReflection.call(
                linesVector,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
                ),
                0L,
            )
        }.recoverCatching {
            AppleReflection.call(
                linesVector,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
                ),
                0,
            )
        }.getOrNull() ?: return null
        return runCatching {
            AppleReflection.call(
                firstLinePointer,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
                ),
            )
        }.getOrNull()
    }

    private fun readNativeLineCount(pointer: Any): Int {
        val songNative = runCatching {
            AppleReflection.call(
                pointer,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
                ),
            )
        }.getOrNull() ?: return -1
        val sections = runCatching {
            AppleReflection.call(
                songNative,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD
                ),
            )
        }.getOrNull() ?: return -1
        var totalLines = 0
        val sectionCount = runCatching {
            (AppleReflection.call(
                sections,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD
                ),
            ) as? Number)?.toInt()
        }.getOrNull() ?: return -1
        for (sectionIndex in 0 until sectionCount.coerceAtMost(16)) {
            val sectionPointer = runCatching {
                AppleReflection.call(
                    sections,
                    lyricsNativeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
                    ),
                    sectionIndex.toLong(),
                )
            }.recoverCatching {
                AppleReflection.call(
                    sections,
                    lyricsNativeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD
                    ),
                    sectionIndex,
                )
            }.getOrNull() ?: continue
            val section = runCatching {
                AppleReflection.call(
                    sectionPointer,
                    lyricsNativeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
                    ),
                )
            }.getOrNull() ?: continue
            val linesVector = runCatching {
                AppleReflection.call(
                    section,
                    lyricsNativeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD
                    ),
                )
            }.getOrNull() ?: continue
            val lineCount = runCatching {
                (AppleReflection.call(
                    linesVector,
                    lyricsNativeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD
                    ),
                ) as? Number)?.toInt()
            }.getOrNull() ?: continue
            totalLines += lineCount
        }
        return totalLines
    }

    private fun readNativeWordCount(pointer: Any): Int {
        val firstLine = firstNativeLine(pointer) ?: return -1
        val wordsVector = runCatching {
            AppleReflection.call(
                firstLine,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD
                ),
            )
        }.getOrNull() ?: return -1
        return runCatching {
            (AppleReflection.call(
                wordsVector,
                lyricsNativeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD
                ),
            ) as? Number)?.toInt()
        }.getOrNull() ?: -1
    }

    /** I2 是 Apple 原生歌词主结果消费者；在补充改写之前记录真实原生结果。 */
    private fun recordAppleNativePresentationResult(pointer: Any?) {
        if (isSupplementPointer(pointer)) return
        val pointerSongId = pointer?.let { sourcePointer ->
            val songNative = runCatching {
                AppleReflection.call(
                    sourcePointer,
                    lyricsNativeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD
                    ),
                )
            }.getOrNull()
            songNative?.let { nativeSong -> runCatching {
                (AppleReflection.call(
                    nativeSong,
                    lyricsNativeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD
                    ),
                ) as? Number)?.toLong()?.takeIf { it > 0L }?.toString()
            }.getOrNull() }
        }
        val songId = pointerSongId
            ?: currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
            ?: return
        val lineCount = pointer?.let(::readNativeLineCount) ?: 0
        onNativeLyricsState(songId = songId, hasLines = lineCount > 0)
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple Music 原生歌词主结果: id=$songId, lines=$lineCount, " +
                    "pointer=${pointer != null}"
            )
        }
    }

    /**
     * 原生歌词模型入参改写（I2 结果消费与 buildTimeRangeToLyricsMap 共用）：
     * 当 Apple 传入的 SongInfo 没有原生歌词内容时，改为当前歌曲已完成身份校验的
     * 补充模型；Apple 原生歌词存在时保持原样。
     */
    private fun rewriteNativeModelArgs(chain: Chain): Array<Any?>? {
        if (chain.args.isEmpty()) return null
        val originalPointer = chain.args.firstOrNull()
        if (isSupplementPointer(originalPointer)) return null
        val hasNativeLyrics = originalPointer != null &&
            readNativeLineCount(originalPointer) > 0
        // Apple 已有原生歌词内容时绝不注入。
        if (hasNativeLyrics) return null
        val supplementPointer = supplementPointerForInjection() ?: return null
        ProviderLogger.debug(
            "Apple Music 无歌词补充注入原生模型: " +
                "originalPointer=$originalPointer, pointer=$supplementPointer"
        )
        return arrayOf(supplementPointer)
    }

    private fun supplementPointerForInjection(): Any? {
        if (!isEnabled()) return null
        val songId = currentSupplementSongId() ?: return null
        if (songId !in acceptedSupplementSongIds) return null
        val identity = store.playbackIdentity(songId) ?: return null
        if (hasKnownNativeLyrics(songId, identity.adamId)) return null
        val pointer = store.nativeSongInfoPointer(songId) ?: return null
        if (!store.hasContent(songId)) return null
        return pointer
    }

    /**
     * 歌词可用性：当前播放条目是「原生无歌词且已有三方补充」的歌曲时，
     * 向 Apple Music 暴露歌词可用，使播放页歌词按钮保持可点、歌词页可进入。
     * 原生歌词存在的歌曲绝不干预。
     */
    private fun shouldExposeSupplementLyrics(item: Any?): Boolean {
        val enabled = isEnabled()
        val queueSongId = currentSupplementSongId()
        // 冷启动时队列身份可能尚未发布；播放页的 PlaybackItem 本身已经携带
        // media ID，可作为磁盘缓存键和身份捕获的兜底。
        val itemSongId = itemMediaId(item)?.takeIf(String::isNotBlank)
        var songId = queueSongId ?: itemSongId
        var provisionalIdentity: AppleMissingLyricsPlaybackIdentity? = null
        if (enabled && songId == null) {
            // 连 media ID getter 都不可用时，再从 Apple Song/PlaybackItem 的
            // Adam ID 反推内容 ID，保证磁盘缓存仍有机会在首次判定前恢复。
            provisionalIdentity = capturePlaybackIdentity(item)
            songId = provisionalIdentity?.contentSongId
        }
        // 冷启动时队列/播放页可能先于桥接回放调用 hasLyrics()。按钮可用性第一次被
        // 计算并缓存前，必须已从磁盘恢复补充歌词；否则页面会永久记录 false。
        if (enabled && songId != null && !store.hasContent(songId)) {
            restoreCachedSupplement(songId)
            if (!store.hasContent(songId)) {
                val promoted = PlaybackManager.promoteCurrentCacheAfterNativeUnavailable(
                    songId = songId,
                    nativeLyricsKnown = hasKnownNativeLyrics(songId),
                    storeHasContent = false,
                )
                if (promoted && BuildConfig.DEBUG) {
                    ProviderLogger.diagnostic(
                        "Apple Music 最终原生可用性为 false，已请求降级当前缓存候选: " +
                            "id=$songId"
                    )
                }
            }
        }
        val hasContent = store.hasContent(songId)
        val takeoverDecision = songId?.let(::takeoverDecision)
            ?: AppleNativeLyricsTakeoverDecision(false, "song_id_missing")
        if (songId != null && hasContent && takeoverDecision.allowed) {
            acceptedSupplementSongIds.add(songId)
        } else if (songId != null) {
            scheduleTakeoverRecheck(songId)
        }
        val identity = if (enabled && songId != null && hasContent) {
            when {
                // 队列身份已确认时，按钮可用性由当前队列歌曲决定；Apple 在这里
                // 可能传入队列/历史列表里的其他 PlaybackItem，不能用它拒绝 override。
                queueSongId != null -> store.playbackIdentity(queueSongId)
                    ?: run {
                        if (itemSongId == queueSongId) {
                            capturePlaybackIdentity(item)
                        } else {
                            null
                        }
                    }
                else -> capturePlaybackIdentity(item)
            }
        } else {
            store.playbackIdentity(songId) ?: provisionalIdentity
        }
        val nativeLyricsKnown = songId != null && hasKnownNativeLyrics(
            songId = songId,
            adamId = identity?.adamId,
        )
        // 队列 ID 已确认时不再要求当前 hasLyrics() 的 PlaybackItem 身份匹配；
        // 队列 ID 缺失的兜底路径仍必须由捕获到的条目身份证明这是同一首歌。
        val identityAvailable = queueSongId != null || identity?.contentSongId == songId
        val shouldExpose = songId != null && shouldExposeSupplementAvailability(
            enabled = enabled,
            hasSupplementContent = hasContent,
            identityAvailable = identityAvailable,
            hasKnownNativeLyrics = nativeLyricsKnown,
        )
        if (shouldExpose) {
            supplementAvailabilitySongIds.add(songId)
            if (songId in acceptedSupplementSongIds) {
                scheduleNativeLyricsModel(songId)
            }
        }
        logAvailabilityDecision(
            enabled = enabled,
            queueSongId = queueSongId,
            songId = songId,
            hasContent = hasContent,
            itemMediaId = itemSongId,
            identity = identity,
            nativeLyricsKnown = nativeLyricsKnown,
            shouldExpose = shouldExpose,
            supplementAvailabilityExposed =
                songId != null && songId in supplementAvailabilitySongIds,
            presentationAccepted = songId != null && songId in acceptedSupplementSongIds,
            nativeResolutionReason = takeoverDecision.reason,
        )
        return shouldExpose
    }

    private fun logAvailabilityDecision(
        enabled: Boolean,
        queueSongId: String?,
        songId: String?,
        hasContent: Boolean,
        itemMediaId: String?,
        identity: AppleMissingLyricsPlaybackIdentity?,
        nativeLyricsKnown: Boolean,
        shouldExpose: Boolean,
        supplementAvailabilityExposed: Boolean,
        presentationAccepted: Boolean,
        nativeResolutionReason: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val signature = listOf(
            enabled,
            queueSongId,
            songId,
            hasContent,
            itemMediaId,
            identity?.adamId,
            identity?.queueId,
            nativeLyricsKnown,
            shouldExpose,
            supplementAvailabilityExposed,
            presentationAccepted,
            nativeResolutionReason,
        ).joinToString("|")
        if (lastAvailabilityDiagnostic == signature) return
        lastAvailabilityDiagnostic = signature
        ProviderLogger.diagnostic(
            "Apple Music 无歌词补充可用性判定: enabled=$enabled, " +
                "queueSongId=$queueSongId, contentId=$songId, hasContent=$hasContent, " +
                "itemMediaId=$itemMediaId, adamId=${identity?.adamId}, " +
                "queueId=${identity?.queueId}, nativeLyricsKnown=$nativeLyricsKnown, " +
                "override=$shouldExpose, " +
                "supplementAvailabilityExposed=$supplementAvailabilityExposed, " +
                "presentationAccepted=$presentationAccepted, " +
                "nativeResolution=$nativeResolutionReason"
        )
    }

    private fun itemMediaId(item: Any?): String? {
        item ?: return null
        val subscriptionStoreId = runCatching {
            AppleReflection.call(
                item,
                playbackItemTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_SUBSCRIPTION_STORE_ID_METHOD
                ),
            ) as? String
        }.getOrNull()
        if (!subscriptionStoreId.isNullOrBlank()) return subscriptionStoreId
        val persistentId = runCatching {
            AppleReflection.call(
                item,
                playbackItemTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_PERSISTENT_ID_METHOD
                ),
            ) as? Long
        }.getOrNull() ?: 0L
        return persistentId.takeIf { it > 0L }?.toString()
    }
}
