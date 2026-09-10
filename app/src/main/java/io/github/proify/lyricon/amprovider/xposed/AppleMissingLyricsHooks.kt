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
 * 「为无歌词的歌曲补充歌词」功能在 Apple Music 进程内的实现：
 *
 * 1. 接收 SystemUI 回传的三方在线源歌词；
 * 2. 编译成 Apple Music 原生 TTML，通过 Apple 自己的 TTMLParserNative
 *    解析成原生 SongInfo 歌词模型；
 * 3. 在歌词页主结果消费入口 PlayerLyricsViewFragment.I2(SongInfoPtr) 上改写
 *    参数，把补充歌词的 SongInfo 塞进 Apple Music 自己的歌词显示链路，
 *    由原生适配器完成滚动、逐字点亮、模糊与样式渲染。
 *
 * 普通三方补充只服务无原生歌词歌曲；启用 LunaBeat 实验来源并精确命中逐字
 * TTML 时，可在保留 Apple 原生模型的同时临时切换两者。
 */

internal class AppleMissingLyricsHooks(
    internal val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> SharedPreferences?,
    internal val currentPlaybackQueueMediaId: () -> String?,
    private val currentVisibleLyricsSongId: () -> String?,
    internal val requestPresentationRefresh: (Any?, Any?, Any?) -> Unit,
    private val requestBlankNativeLyricsPageRecovery: (Any?) -> Unit,
    internal val refreshVisibleSupplementTranslation: (AppleMissingLyricsPresentationUpdate) -> Unit,
    internal val refreshNowPlaying: (String?) -> Unit,
) {
    internal companion object {
        const val MAX_REMEMBERED_NATIVE_LYRICS_SONG_IDS = 512
        const val RESUME_REFRESH_DELAY_MS = 400L

        object SourceName {
            const val APPLE_NATIVE = "APPLE"
            const val LUNA_BEAT = "LB"
        }
    }

    internal val mainHandler: Handler
        get() = runtime.mainHandler

    val store = AppleMissingLyricsStore()

    internal val nativeParser by lazy { AppleMissingLyricsNativeParser(runtime) }

    internal val lyricsNativeTarget by lazy {
        runtime.hookResolver.resolveClass(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).target
    }

    internal val playbackItemTarget by lazy {
        runtime.hookResolver
            .resolveClass(AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE)
            .target
    }

    internal val lyricsSongTarget by lazy {
        runtime.hookResolver.resolveClass(AppleMusicHookPoint.APPLE_SONG_MODEL_CLASS).target
    }

    internal val nativeLyricsKnowledge = AppleNativeLyricsKnowledge(MAX_REMEMBERED_NATIVE_LYRICS_SONG_IDS)
    internal val candidates = AppleMissingLyricsCandidateState()
    private val loggedTtmlSampleKeys = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    private val resultPresentationHitLogged = AtomicBoolean(false)
    private val nativeBuildRewriteHitLogged = AtomicBoolean(false)
    private val lyricsPageResumeHitLogged = AtomicBoolean(false)
    private val availabilityHookHitLogged = AtomicBoolean(false)
    internal val nativeBuildLock = Any()
    internal val nativeBuildScope = AppleMissingLyricsNativeBuildScope()
    internal val nativeTakeoverGate = AppleNativeLyricsTakeoverGate()
    private val nativeAvailabilityTracker = AppleNativeLyricsAvailabilityTracker()
    internal val scheduledTakeoverRechecks = ConcurrentHashMap<String, Long>()
    internal val lyricsSourceSelection = AppleLyricsSourceSelection()
    internal val nativeAlternatives = AppleLyricsNativeAlternatives(MAX_REMEMBERED_NATIVE_LYRICS_SONG_IDS)

    @Volatile
    internal var pendingNativeBuildKey: NativeBuildKey? = null

    @Volatile
    internal var lastAvailabilityDiagnostic: String? = null

    internal val playerLyricsAvailabilityHitLogged = AtomicBoolean(false)
    internal val playerSongBindingHitLogged = AtomicBoolean(false)
    internal val playerLyricsAvailabilityDiagnosticKeys = ConcurrentHashMap.newKeySet<String>()
    internal val playerSongBindingDiagnosticKeys = ConcurrentHashMap.newKeySet<String>()
    internal val playerSongBindingSnapshots = ThreadLocalStack<PlayerSongBindingSnapshot>()

    internal data class NativeBuildKey(
        val contentRevision: Long,
        val identity: AppleMissingLyricsPlaybackIdentity,
    )

    internal data class PlaybackItemReference(
        val identity: AppleMissingLyricsPlaybackIdentity,
        val item: WeakReference<Any>,
    )

    internal data class PlayerSongBindingSnapshot(
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
    internal var currentPlaybackItemReference: PlaybackItemReference? = null

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
                if (
                    BuildConfig.DEBUG &&
                    nativeBuildRewriteHitLogged.compareAndSet(false, true)
                ) {
                    ProviderLogger.diagnostic(
                        "Apple Music 无歌词补充时间轴地图改写 Hook 首次命中: " +
                            "viewModel=${chain.thisObject?.javaClass?.name}"
                    )
                }
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
                if (
                    BuildConfig.DEBUG &&
                    lyricsPageResumeHitLogged.compareAndSet(false, true)
                ) {
                    ProviderLogger.diagnostic(
                        "Apple Music 无歌词补充页面恢复 Hook 首次命中: " +
                            "fragment=${chain.thisObject?.javaClass?.name}"
                    )
                }
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
                    if (
                        BuildConfig.DEBUG &&
                        availabilityHookHitLogged.compareAndSet(false, true)
                    ) {
                        ProviderLogger.diagnostic(
                            "Apple Music 无歌词补充歌词可用性 Hook 首次命中: " +
                                "item=${chain.thisObject?.javaClass?.name}"
                        )
                    }
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

    /** 歌词页可见且当前歌曲具备补充歌词时，主动用补充歌词指针触发原生呈现。 */
    private fun maybeRequestInjectedPresentation(fragment: Any? = null) {
        if (!isEnabled()) return
        val songId = currentSupplementSongId() ?: return
        if (!shouldPresentSupplementOnLyricsPageResume(
                hasKnownNativeLyrics = hasKnownNativeLyrics(songId),
                shouldPreferLunaBeat = shouldPreferLunaBeat(songId),
            )
        ) {
            return
        }
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

    fun isEnabled(): Boolean = isFillMissingLyricsEnabled() || isLunaBeatWordLyricsEnabled()

    private fun isFillMissingLyricsEnabled(): Boolean =
        preferences()?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
        ) == true

    internal fun isLunaBeatWordLyricsEnabled(): Boolean =
        preferences()?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
        ) == true

    /**
     * 当前补充链应服务的歌曲 ID。队列身份未发布时，歌词页已确认的可见歌曲 ID
     * 比任意 PlaybackItem 的 media ID 更可靠，避免把其他歌曲的补充模型注入当前页。
     */
    internal fun currentSupplementSongId(): String? =
        currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
            ?: currentVisibleLyricsSongId()?.takeIf(String::isNotBlank)
            ?: store.playbackIdentity(null)?.contentSongId?.takeIf(String::isNotBlank)

    fun onNativeLyricsRequestStarted(songId: String?) {
        val resolvedSongId = songId?.takeIf(String::isNotBlank) ?: return
        val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
        val identity = store.playbackIdentity(queueSongId)
        val contentSongId = when {
            identity?.adamId?.toString() == resolvedSongId -> identity.contentSongId
            queueSongId == resolvedSongId -> queueSongId
            else -> resolvedSongId
        }
        // 补充模型为了同步当前 PlaybackItem 也会再次调用 Apple ViewModel.loadLyrics；
        // 已经完成接管后的这类自触发调用不能重新把补充链锁回 loading。
        if (candidates.isAccepted(contentSongId) || candidates.isAccepted(resolvedSongId)) {
            if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "Apple Music 原生歌词请求忽略: id=$contentSongId, " +
                        "reason=supplement_takeover_active"
                )
            }
            return
        }
        nativeTakeoverGate.onNativeRequestStarted(contentSongId)
        if (resolvedSongId != contentSongId) {
            nativeTakeoverGate.onNativeRequestStarted(resolvedSongId)
        }
        ProviderLogger.debug(
            "Apple Music 原生歌词请求开始: adamId=$resolvedSongId, contentId=$contentSongId"
        )
        scheduleTakeoverRecheck(contentSongId)
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

    internal fun takeoverDecision(songId: String): AppleNativeLyricsTakeoverDecision =
        if (shouldPreferLunaBeat(songId)) {
            AppleNativeLyricsTakeoverDecision(true, "lunabeat_selected")
        } else if (hasKnownNativeLyrics(songId)) {
            AppleNativeLyricsTakeoverDecision(false, "native_lyrics_present")
        } else {
            nativeTakeoverGate.decision(songId)
        }

    internal fun scheduleTakeoverRecheck(songId: String) {
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

    internal fun maybeActivateSupplement(songId: String, trigger: String): Boolean {
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
        val newlyAccepted = candidates.accept(songId)
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

}
