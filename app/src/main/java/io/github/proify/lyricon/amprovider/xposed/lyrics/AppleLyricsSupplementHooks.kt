/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleContentLocalizationHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleDebugNetworkHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.ApplePlaybackHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.FunctionalAppleMusicHookModule
import io.github.proify.lyricon.amprovider.xposed.lyrics.AppleOnlineSourceMenuHooks
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalReentryGuard
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

import android.content.SharedPreferences

internal fun selectLyricsViewModelPlaybackItem(
    expectedSongId: String,
    expectedType: Class<*>,
    candidates: List<Any?>,
    registeredSongId: (Any) -> String?,
    runtimeSongId: (Any) -> String?,
): Any? = candidates.asSequence()
    .filterNotNull()
    .firstOrNull { candidate ->
        expectedType.isInstance(candidate) &&
            (registeredSongId(candidate)?.takeIf(String::isNotBlank)
                ?: runtimeSongId(candidate)?.takeIf(String::isNotBlank)) == expectedSongId
    }

/**
 * 判断 Apple 的一次 loadLyrics 调用是否属于当前歌词页。
 *
 * 页面可见歌曲 ID 可能在切歌后的若干秒内仍停在上一首；此时 Apple 为队列当前歌曲
 * 发起的 loadLyrics 恰恰是让页面追上新歌的合法调用，必须接受。只有既不匹配可见歌曲
 * 也不匹配队列当前歌曲的历史调用才需要拒绝。
 */
internal fun belongsToCurrentLyricsPage(
    loadedSongId: String?,
    visibleSongId: String?,
    queueSongId: String?,
): Boolean {
    if (loadedSongId.isNullOrBlank()) return true
    val visibleSong = visibleSongId?.takeIf(String::isNotBlank)
    val queueSong = queueSongId?.takeIf(String::isNotBlank)
    if (visibleSong == null && queueSong == null) return true
    return loadedSongId == visibleSong || loadedSongId == queueSong
}

/**
 * 判断带「无歌词补充」标记的在线翻译回传是否应该继续走补充歌词链路。
 *
 * 同一首歌可能在模块歌词先到、Apple 原生歌词后到的窗口里把标记合并到原生载荷上。
 * 如果当前歌词页已经是 Apple 原生指针、或本进程已登记该曲 Apple 原生歌词，翻译必须
 * 走原生在线翻译 Store，否则补充链会因 `native_lyrics_present` 丢弃翻译。
 */
internal fun shouldRouteAppleTranslationAsMissingSupplement(
    markedAsSupplement: Boolean,
    knownNativeLyrics: Boolean,
    visiblePageIsSupplement: Boolean?,
): Boolean {
    if (!markedAsSupplement || knownNativeLyrics) return false
    return visiblePageIsSupplement != false
}

internal fun shouldKeepAppleLyricsScrollSnapshot(
    existingPosition: Int?,
    capturedPosition: Int,
    presentationInFlight: Boolean,
): Boolean = presentationInFlight &&
    capturedPosition == 0 &&
    existingPosition != null &&
    existingPosition > 0

internal data class AppleLyricsRestoreAnchor(
    val position: Int,
    val offset: Int,
    val activePosition: Int?,
)

internal fun selectAppleLyricsPlaybackAdapterPosition(
    lineBeginsMs: List<Long?>,
    playbackPositionMs: Long,
    itemCount: Int,
): Int? {
    if (itemCount <= 0) return null
    val logicalLinePosition = lineBeginsMs.indexOfLast { beginMs ->
        beginMs != null && beginMs <= playbackPositionMs
    }
    return logicalLinePosition
        .takeIf { it >= 0 }
        ?.coerceIn(0, itemCount - 1)
}

internal fun selectAppleLyricsRestoreAnchor(
    savedPosition: Int,
    savedOffset: Int,
    savedActivePosition: Int?,
    savedActiveOffset: Int?,
    currentActivePositions: Iterable<Int>,
    itemCount: Int,
    playbackMappedPosition: Int? = null,
): AppleLyricsRestoreAnchor? {
    if (itemCount <= 0) return null
    val currentActivePosition = currentActivePositions
        .filter { it in 0 until itemCount }
        .maxOrNull()
    if (currentActivePosition != null && savedActiveOffset != null) {
        return AppleLyricsRestoreAnchor(
            position = currentActivePosition,
            offset = savedActiveOffset,
            activePosition = currentActivePosition,
        )
    }
    if (currentActivePosition != null && savedActivePosition != null) {
        val activeDistanceFromFirst = savedActivePosition - savedPosition
        return AppleLyricsRestoreAnchor(
            position = (currentActivePosition - activeDistanceFromFirst)
                .coerceIn(0, itemCount - 1),
            offset = savedOffset,
            activePosition = currentActivePosition,
        )
    }
    val mappedPosition = playbackMappedPosition
        ?.takeIf { it >= 0 }
        ?.coerceIn(0, itemCount - 1)
    if (mappedPosition != null) {
        return AppleLyricsRestoreAnchor(
            position = mappedPosition,
            offset = savedActiveOffset ?: savedOffset,
            activePosition = mappedPosition,
        )
    }
    return AppleLyricsRestoreAnchor(
        position = savedPosition.coerceIn(0, itemCount - 1),
        offset = savedOffset,
        activePosition = null,
    )
}

internal fun visibleAdapterRange(positions: Iterable<Int>): IntRange? {
    val valid = positions.filter { it >= 0 }.toList()
    if (valid.isEmpty()) return null
    val first = valid.minOrNull() ?: return null
    val last = valid.maxOrNull() ?: return null
    return first..last
}

internal class AppleLyricsSupplementHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> SharedPreferences?,
    private val playbackHooks: () -> ApplePlaybackHooks,
    private val onlineSourceMenuHooks: () -> AppleOnlineSourceMenuHooks,
    private val lyricRequester: () -> LyricRequester,
    private val catalogResolver: () -> AppleInternalCatalogResolver?,
    private val applyConfiguredContentUiLanguageCallback: () -> Unit,
    private val recordLyricsRequestSource: (String, String) -> Unit,
    private val currentPlaybackQueueMediaId: () -> String?,
    private val registeredPlaybackItems: (String) -> List<Any>,
    private val registeredPlaybackItemId: (Any) -> String?,
    private val epoxyDataBindingFromHolderCallback: (Any?) -> Any?,
    private val missingLyricsSupplement: () -> AppleMissingLyricsHooks,
) {
    private companion object {
        const val APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION = 0.22f
        const val APPLE_LYRICS_SCROLL_STATE_IDLE = 0
        const val APPLE_LYRICS_IDLE_RECHECK_DELAY_MS = 96L
        const val APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS = 16L
        const val APPLE_LYRICS_BEFORE_FIRST_LINE_RECHECK_MAX_MS = 250L
        const val APPLE_LYRICS_HYPER_OS_SELF_BLUR_TYPE = 0
        const val MAX_APPLE_SYSTEM_FONT_VARIATION_CACHE_ENTRIES = 64
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        const val LEGACY_MODULE_PROMOTION_DELAY_MS = 1_000L
        const val SUPPLEMENT_ACTIVE_LINE_INTERVAL_MS = 300L
        const val BLANK_NATIVE_LYRICS_PAGE_RECOVERY_DELAY_MS = 1_500L
    }

    private val application: Application
        get() = runtime.application
    private val classLoader: ClassLoader
        get() = runtime.classLoader
    private val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    private val hookRegistrar
        get() = runtime.hookRegistrar
    private val mainHandler: Handler
        get() = runtime.mainHandler
    private val contentUiLanguagePrefs: SharedPreferences?
        get() = preferences()
    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }

    private val lyricDisplayTextHookedMethods = ConcurrentHashMap.newKeySet<Executable>()
    private val nativeOnlineTranslationHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    private val applePronunciationRenderHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    private val appleOfficialTranslationProbeGuard = ThreadLocalReentryGuard()
    private val applePronunciationDiagnosticsLoggedSongIds =
        ConcurrentHashMap.newKeySet<String>()
    private val applePronunciationRuntimeDiagnosticKeys =
        ConcurrentHashMap.newKeySet<String>()
    private val applePronunciationBindingDiagnosticKeys =
        ConcurrentHashMap.newKeySet<String>()
    private val applePronunciationLanguagesBySongId =
        ConcurrentHashMap<String, List<String>>()
    private val applePronunciationContextByLyricObject =
        WeakIdentityMap<Any, ApplePronunciationContext>()
    val nativeOnlineTranslationStore = AppleNativeOnlineTranslationStore()
    private var deferredNativeTranslationRefreshSongId: String? = null
    private var deferredNativeTranslationRefreshRevision: Long? = null
    private var deferredNativeTranslationRefreshScheduled = false
    private val pendingApplePronunciationRenderPlans = Collections.synchronizedMap(
        IdentityHashMap<Any, ApplePronunciationRenderPlan>()
    )
    private val applePronunciationWordRenderContexts =
        ThreadLocalStack<ApplePronunciationWordRenderContext>()
    @Volatile
    private var appleLyricsLoadMethod: Method? = null
    @Volatile
    private var appleLyricsViewModelRef: WeakReference<Any>? = null
    @Volatile
    private var activeLyricsResultObserver: Pair<WeakReference<Any>, Any>? = null
    @Volatile
    private var appleLyricsItemRef: WeakReference<Any>? = null
    @Volatile
    private var appleLyricsPresentationMethod: Method? = null
    @Volatile
    private var appleLyricsResultPresentationMethod: Method? = null
    @Volatile
    private var appleLyricsFragmentRef: WeakReference<Any>? = null
    @Volatile
    private var appleLyricsSongPointerRef: WeakReference<Any>? = null
    @Volatile
    private var currentAppleLyricsSongId: String? = null
    private data class AppleLyricsPresentationPerformanceContext(
        val stagePrefix: String,
        val songId: String?,
        val methodName: String,
        val startedAtNanos: Long,
    )
    private val appleLyricsPresentationPerformanceContexts =
        ThreadLocalStack<AppleLyricsPresentationPerformanceContext>()
    private val appleLyricsPresentationPerformanceMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    /** 已绑定「可见即隐藏」监听的 loading_progress View，避免同一实例重复注册。 */
    private val suppressedLyricsLoadingViews =
        Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    private val forcedLyricsTranslationButtons =
        Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    private val trackedLyricsRecyclerViews =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private data class AppleLyricsScrollSnapshot(
        val firstPosition: Int,
        val firstOffset: Int,
        val activeAdapterPosition: Int?,
        val activeAdapterOffset: Int?,
        val playbackPositionMs: Long?,
        val sourceTimingDebug: String? = null,
        val adapterTimingDebug: String? = null,
    )
    private data class ResolvedAppleLyricsScrollTarget(
        val layoutManager: Any,
        val itemCount: Int,
        val anchor: AppleLyricsRestoreAnchor,
        val activePositions: Set<Int>,
        val playbackMappedPosition: Int?,
        val sourceTimingDebug: String? = null,
        val adapterTimingDebug: String? = null,
    )
    private var appleLyricsScrollSnapshot: AppleLyricsScrollSnapshot? = null
    private var appleLyricsScrollSnapshotSongId: String? = null
    /** A failed restore must not let Apple's temporary top layout erase the last good anchor. */
    private var preserveAppleLyricsTopSnapshotSongId: String? = null
    private var pendingAppleLyricsScrollRestoreRecycler: WeakReference<ViewGroup>? = null
    private var pendingAppleLyricsScrollRestoreListener: ViewTreeObserver.OnPreDrawListener? = null
    private var appleLyricsPresentationInFlight = false
    private var supplementActiveLineUpdateScheduled = false
    private var lastSupplementActiveLineIndex = -1
    private val supplementActiveLineUpdateRunnable = object : Runnable {
        override fun run() {
            supplementActiveLineUpdateScheduled = false
            updateSupplementActiveLine()
        }
    }
    private lateinit var lyricsRuntimeTarget: AppleMusicHookTarget
    private val lyricsUiTarget by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW).target
    }
    private val lyricsSongTarget by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.APPLE_SONG_MODEL_CLASS).target
    }
    private val systemFontHooks by lazy {
        AppleSystemFontHooks(
            runtime = runtime,
            preferences = preferences,
            currentSongId = ::currentSongId,
            nativeRawWordVectorText = ::nativeRawWordVectorText,
            currentPlaybackPositionMs = ::appleLyricsCurrentPlaybackPositionMs,
        )
    }
    private val blurHooks by lazy {
        AppleLyricsBlurHooks(
            runtime = runtime,
            preferences = preferences,
            playbackHooks = playbackHooks,
            currentFragment = { appleLyricsFragmentRef?.get() },
        )
    }
    private val diagnostics by lazy {
        AppleLyricsDiagnostics(
            runtime = runtime,
            currentSongId = ::currentSongId,
            epoxyDataBindingFromHolderCallback = epoxyDataBindingFromHolderCallback,
            bindingDiagnostic = ::logApplePronunciationBindingDiagnostic,
            uiStateDiagnostic = { fragment, stage ->
                logAppleLyricsUiState(fragment, stage)
            },
            recyclerLifecycleDiagnostic = ::logAppleLyricsRecyclerLifecycle,
            appleRecyclerViewPredicate = ::isAppleRecyclerViewInstance,
        )
    }

    fun currentSongId(): String? = currentAppleLyricsSongId

    private fun installSourceSwitchPresentationPerformanceHook(
        method: Method,
        stagePrefix: String,
    ) {
        if (!BuildConfig.DEBUG || !appleLyricsPresentationPerformanceMethods.add(method)) return
        hookRegistrar.installScopedHook(
            executable = method,
            enter = { chain ->
                val pointer = chain.args.firstOrNull()
                val songId = pointer?.let { candidate ->
                    runCatching {
                        lyricsNativeCall(
                            candidate,
                            AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
                        )
                    }.getOrNull()?.let(::nativeSongId)
                } ?: currentAppleLyricsSongId
                if (!AppleSourceSwitchPerformanceDiagnostics.isTracing(songId)) {
                    return@installScopedHook false
                }
                val context = AppleLyricsPresentationPerformanceContext(
                    stagePrefix = stagePrefix,
                    songId = songId,
                    methodName = "${method.declaringClass.name}.${method.name}/${method.parameterCount}",
                    startedAtNanos = SystemClock.elapsedRealtimeNanos(),
                )
                appleLyricsPresentationPerformanceContexts.push(context)
                AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                    songId = songId,
                    stage = "${stagePrefix}_started",
                    details = "method=${context.methodName},pointer=${pointer?.javaClass?.name ?: "none"}",
                )
                true
            },
            after = { _, _ ->
                val context = appleLyricsPresentationPerformanceContexts.current
                    ?: return@installScopedHook
                val elapsedNanos =
                    SystemClock.elapsedRealtimeNanos() - context.startedAtNanos
                AppleSourceSwitchPerformanceDiagnostics.record(
                    songId = context.songId,
                    event = context.stagePrefix,
                    durationNanos = elapsedNanos,
                    details = "method=${context.methodName}",
                )
                AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                    songId = context.songId,
                    stage = "${context.stagePrefix}_finished",
                    details = "method=${context.methodName},elapsedMs=${elapsedNanos / 1_000_000.0}",
                )
            },
            exit = { appleLyricsPresentationPerformanceContexts.pop() },
        )
    }

    private fun lyricsRuntimeMember(member: AppleMusicRuntimeMember): String =
        lyricsRuntimeTarget.runtimeMemberName(member)

    private fun lyricsUiMember(member: AppleMusicRuntimeMember): String =
        lyricsUiTarget.runtimeMemberName(member)

    private fun lyricsSongMember(member: AppleMusicRuntimeMember): String =
        lyricsSongTarget.runtimeMemberName(member)

    private fun lyricsNativeCall(
        instance: Any?,
        member: AppleMusicRuntimeMember,
        vararg args: Any?,
    ): Any? = instance?.let {
        AppleReflection.call(it, lyricsRuntimeMember(member), *args)
    }

    private fun lyricsUiField(instance: Any?, member: AppleMusicRuntimeMember): Any? =
        instance?.let { AppleReflection.field(it, lyricsUiMember(member)) }

    fun isSimplifyTraditionalLyricsEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
        ) == true

    fun hookTranslationPreference() {
        val translationMethod = hookResolver.resolveMethod(
            AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE
        ).method
        hookRegistrar.installHook(translationMethod, after = { chain, _ ->
            (chain.args.firstOrNull() as? Boolean)?.let {
                PreferencesMonitor.notifyTranslationSelectedChanged(it)
            }
        })
        val pronunciationMethod = hookResolver.resolveMethod(
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE
        ).method
        hookRegistrar.installHook(pronunciationMethod, after = { chain, _ ->
            (chain.args.firstOrNull() as? Boolean)?.let {
                PreferencesMonitor.notifyPronunciationSelectedChanged(it)
            }
        })
    }


    fun hasCurrentOnlineContentConsumption(
        songId: String?,
        contentType: String,
    ): Boolean {
        if (songId.isNullOrBlank() || songId != currentAppleLyricsSongId) return false
        if (contentType == "lyrics") {
            return missingLyricsSupplement().sourceInfo(songId)?.selectedSource != null
        }
        if (contentType == "translation" && missingLyricsSupplement().hasTranslation(songId)) {
            return true
        }
        if (!isNativeOnlineTranslationEnabled()) return false
        val songNative = currentAppleLyricsNativeSong(songId) ?: return false
        val lines = currentAppleLyricsNativeLines(songNative)
        if (lines.isEmpty()) return false

        return when (contentType) {
            "translation" -> lines.any { line ->
                AppleNativeOnlineTranslationStore.sanitizeContent(
                    nativeRawLineText(
                        line,
                        lyricsRuntimeMember(
                            AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD
                        ),
                    )
                ) == null && onlineTranslationForNativeLine(line) != null
            }
            "pronunciation" -> {
                if (shouldHideMandarinPronunciation(songId = songId)) return false
                lines.any { line ->
                    val originalText = nativeOriginalLineText(line)
                    val officialPronunciation = RomanizationPolicy.sanitize(
                        originalText = originalText,
                        pronunciation = nativeRawLineText(
                            line,
                            lyricsRuntimeMember(
                                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD
                            ),
                        ),
                    )
                    officialPronunciation == null &&
                        onlinePronunciationForNativeLine(line) != null
                }
            }
            else -> false
        }
    }

    private fun currentAppleLyricsNativeSong(songId: String): Any? {
        val pointer = appleLyricsSongPointerRef?.get() ?: return null
        val songNative = runCatching {
            lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
        }.getOrNull()
            ?: return null
        return songNative.takeIf { nativeSongId(it) == songId }
    }

    private fun currentAppleLyricsNativeLines(songNative: Any): List<Any> {
        val sections = runCatching {
            lyricsNativeCall(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD)
        }
            .getOrNull() ?: return emptyList()
        return nativeVectorItems(sections, limit = 8).flatMap { section ->
            val lines = runCatching {
                lyricsNativeCall(section, AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD)
            }.getOrNull()
            nativeVectorItems(lines, limit = 128)
        }
    }

    private fun appleNativeSongHasLines(songNative: Any?): Boolean =
        songNative != null && currentAppleLyricsNativeLines(songNative).isNotEmpty()

    private fun reportNativeLyricsState(
        songNative: Any?,
        songId: String?,
        sourcePointer: Any? = null,
    ) {
        // 补充歌词的原生模型不是 Apple 原生歌词，绝不能记为「原生已存在」。
        if (missingLyricsSupplement().isSupplementPointer(sourcePointer)) return
        // 无歌词结果只接受 I2 主结果入口的明确完成信号；R2/时间轴构建中的空模型
        // 可能只是加载中间态，不能据此提前允许三方接管。
        if (!appleNativeSongHasLines(songNative)) return
        missingLyricsSupplement().onNativeLyricsState(
            songId = songId,
            hasLines = true,
        )
    }

    private fun observePlayerLyricsViewModelResult(viewModel: Any, songId: String) {
        val resultLiveData = runCatching {
            AppleReflection.call(
                viewModel,
                lyricsRuntimeMember(
                    AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_RESULT_GETTER
                ),
            )
        }.getOrNull() ?: return

        val initialValue = runCatching {
            AppleReflection.call(resultLiveData, "getValue")
        }.getOrNull()

        mainHandler.post {
            // 清理上一个 Observer，避免泄露或重复监听
            activeLyricsResultObserver?.let { (liveDataRef, oldObserver) ->
                val oldLiveData = liveDataRef.get()
                if (oldLiveData != null) {
                    runCatching {
                        AppleReflection.call(oldLiveData, "removeObserver", oldObserver)
                    }
                }
            }

            val observerClass = runCatching {
                classLoader.loadClass("androidx.lifecycle.Observer")
            }.getOrNull() ?: return@post

            val observer = java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(observerClass),
            ) { _, method, args ->
                if (method.name == "onChanged") {
                    val result = args?.firstOrNull()
                    if (result !== initialValue) {
                        handleNativeLyricsResultEmitted(songId, result)
                    }
                }
                null
            }

            activeLyricsResultObserver = Pair(WeakReference(resultLiveData), observer)
            runCatching {
                AppleReflection.call(resultLiveData, "observeForever", observer)
            }.onFailure {
                ProviderLogger.debug("Apple Music 原生歌词 LiveData 观察者安装失败: ${it.message}")
            }
        }
    }

    private fun handleNativeLyricsResultEmitted(songId: String, result: Any?) {
        if (result == null) return
        val pointer = runCatching {
            AppleReflection.call(result, "getFirst")
        }.getOrNull()

        // 如果是本模块注入的三方补充模型，绝不能当作 Apple 原生歌词结果处理
        if (pointer != null && missingLyricsSupplement().isSupplementPointer(pointer)) {
            return
        }

        if (pointer != null) {
            val songNative = runCatching {
                lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
            }.getOrNull()
            val nativeSongId = songNative?.let(::nativeSongId)
            val hasLines = appleNativeSongHasLines(songNative)
            ProviderLogger.info(
                "Apple Music 原生歌词请求结果已发射: id=${nativeSongId ?: songId}, " +
                    "hasLines=$hasLines"
            )
            missingLyricsSupplement().onNativeLyricsState(
                songId = nativeSongId ?: songId,
                hasLines = hasLines,
            )
        } else {
            // pointer 为 null 表示 Apple 原生歌词请求已结束且无歌词（404 / 异常 / 空结果）
            val exception = runCatching {
                AppleReflection.call(result, "getSecond")
            }.getOrNull()
            ProviderLogger.info(
                "Apple Music 原生歌词请求结果已发射: id=$songId, " +
                    "result=empty, error=${exception?.javaClass?.name}"
            )
            missingLyricsSupplement().onNativeLyricsState(
                songId = songId,
                hasLines = false,
            )
        }
    }

    private fun cleanupLyricsResultObserver() {
        mainHandler.post {
            activeLyricsResultObserver?.let { (liveDataRef, oldObserver) ->
                val oldLiveData = liveDataRef.get()
                if (oldLiveData != null) {
                    runCatching {
                        AppleReflection.call(oldLiveData, "removeObserver", oldObserver)
                    }
                }
            }
            activeLyricsResultObserver = null
        }
    }



    fun refreshAppleLyricsDisplay() {
        mainHandler.post {
            val method = appleLyricsLoadMethod ?: return@post
            val viewModel = appleLyricsViewModelRef?.get() ?: return@post
            val item = appleLyricsItemRef?.get() ?: return@post
            runCatching {
                method.invoke(viewModel, item)
            }.onFailure {
                ProviderLogger.error("Apple Music 当前歌词页刷新失败", it)
            }
        }
    }

    /**
     * 原生歌词页恢复可见后的空白自愈。
     *
     * 复现路径：歌词页隐藏期间 Apple 已为队列当前歌曲调用过 loadLyrics，但重新可见时
     * Fragment 仍持有上一首歌曲的空 adapter，且 Apple 不会再重放 loadLyrics。此时页面
     * 空白而 AOD/超级岛正常。这里只对已确认 Apple 原生歌词的歌曲、且 adapter 仍为空的
     * 可见页面补一次当前队列 PlaybackItem 的 loadLyrics；已有内容时不做任何操作。
     */
    fun scheduleBlankNativeLyricsPageRecovery(fragment: Any? = null) {
        if (!missingLyricsSupplement().isEnabled()) return
        val queueSongId = currentPlaybackQueueMediaId()
            ?.takeIf(String::isNotBlank)
            ?: return
        if (!missingLyricsSupplement().hasKnownNativeLyricsFor(queueSongId)) return
        mainHandler.postDelayed(
            {
                recoverBlankNativeLyricsPage(fragment, queueSongId)
            },
            BLANK_NATIVE_LYRICS_PAGE_RECOVERY_DELAY_MS,
        )
    }

    private fun recoverBlankNativeLyricsPage(
        fragmentOverride: Any?,
        expectedSongId: String,
    ) {
        if (!missingLyricsSupplement().isEnabled()) return
        if (currentPlaybackQueueMediaId() != expectedSongId) return
        val fragment = fragmentOverride ?: appleLyricsFragmentRef?.get() ?: run {
            ProviderLogger.debug(
                "Apple Music 原生歌词空白页自愈跳过: reason=fragment_missing, " +
                    "id=$expectedSongId"
            )
            return
        }
        val rootView = runCatching {
            AppleReflection.call(
                fragment,
                lyricsUiMember(AppleMusicRuntimeMember.LYRICS_UI_ROOT_VIEW_GETTER),
            ) as? View
        }.getOrNull() ?: run {
            ProviderLogger.debug(
                "Apple Music 原生歌词空白页自愈跳过: reason=root_missing, " +
                    "id=$expectedSongId"
            )
            return
        }
        if (!rootView.isShown) return
        val recyclerView = resolveAppleLyricsRecyclerView(fragment) ?: run {
            ProviderLogger.debug(
                "Apple Music 原生歌词空白页自愈跳过: reason=recycler_missing, " +
                    "id=$expectedSongId"
            )
            return
        }
        val adapter = appleRecyclerAdapter(recyclerView) ?: run {
            ProviderLogger.debug(
                "Apple Music 原生歌词空白页自愈跳过: reason=adapter_missing, " +
                    "id=$expectedSongId"
            )
            return
        }
        if (appleRecyclerAdapterItemCount(adapter) > 0) return
        val viewModel = runCatching {
            lyricsUiField(fragment, AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD)
        }.getOrNull() ?: run {
            ProviderLogger.debug(
                "Apple Music 原生歌词空白页自愈跳过: reason=view_model_missing, " +
                    "id=$expectedSongId"
            )
            return
        }
        val loadMethod = appleLyricsLoadMethod ?: runCatching {
            hookResolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).method
        }.getOrNull() ?: return
        val expectedType = loadMethod.parameterTypes.singleOrNull() ?: return
        val playbackItem = selectLyricsViewModelPlaybackItem(
            expectedSongId = expectedSongId,
            expectedType = expectedType,
            candidates = registeredPlaybackItems(expectedSongId),
            registeredSongId = registeredPlaybackItemId,
            runtimeSongId = ::playbackItemSongId,
        ) ?: run {
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 原生歌词空白页自愈跳过: " +
                        "reason=playback_item_missing, id=$expectedSongId, " +
                        "expectedType=${expectedType.name}"
                )
            }
            return
        }
        appleLyricsViewModelRef = WeakReference(viewModel)
        appleLyricsItemRef = WeakReference(playbackItem)
        runCatching {
            loadMethod.invoke(viewModel, playbackItem)
        }.onSuccess {
            ProviderLogger.info(
                "Apple Music 原生歌词空白页自愈已触发: id=$expectedSongId"
            )
        }.onFailure {
            ProviderLogger.error(
                "Apple Music 原生歌词空白页自愈失败: id=$expectedSongId",
                it,
            )
        }
    }

    fun receiveNativeOnlineTranslation(compressedSong: ByteArray) {
        val callbackStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        ProviderLogger.diagnostic(
            "[SourceSwitchPerf] stage=translation_payload_received, " +
                "bytes=${compressedSong.size}, thread=${Thread.currentThread().name}"
        )
        coroutineScope.launch {
            val decodeStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            ProviderLogger.diagnostic(
                "[SourceSwitchPerf] stage=translation_payload_decode_started, " +
                    "bytes=${compressedSong.size}, thread=${Thread.currentThread().name}"
            )
            val song = runCatching {
                json.decodeFromString<LocalSong>(
                    compressedSong.inflate().toString(Charsets.UTF_8)
                )
            }.onFailure {
                ProviderLogger.error("Apple Music 原生在线翻译解析失败", it)
            }.getOrNull() ?: return@launch
            ProviderLogger.diagnostic(
                "[SourceSwitchPerf] stage=translation_payload_decode_finished, " +
                    "id=${song.id}, lines=${song.lyrics.orEmpty().size}, elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - decodeStartedAtNanos) / 1_000_000.0) +
                    ", callbackElapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - callbackStartedAtNanos) / 1_000_000.0) +
                    ", thread=${Thread.currentThread().name}"
            )

            // 三方在线源为原生无歌词歌曲补充的完整歌词走独立显示链路。
            // 但同一首歌可能先以模块补充身份发布、随后 Apple 原生歌词才确认；
            // 此时回传仍带补充标记，必须按当前页/已确认原生身份改走原生在线翻译，
            // 否则翻译会被补充链的 native_lyrics_present 保护直接丢弃。
            val markedAsSupplement = song.metadata
                ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
                .toBoolean()
            val visiblePointer = appleLyricsSongPointerRef?.get()
            val visiblePointerSongId = visiblePointer?.let { pointer ->
                runCatching {
                    nativeSongId(
                        lyricsNativeCall(
                            pointer,
                            AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
                        )
                    )
                }.getOrNull()
            }
            val visiblePageIsSupplement = visiblePointerSongId
                ?.takeIf { it == song.id }
                ?.let {
                    missingLyricsSupplement().isSupplementPointer(visiblePointer)
                }
            if (
                shouldRouteAppleTranslationAsMissingSupplement(
                    markedAsSupplement = markedAsSupplement,
                    knownNativeLyrics = song.id?.let {
                        missingLyricsSupplement().hasKnownNativeLyricsFor(it)
                    } == true,
                    visiblePageIsSupplement = visiblePageIsSupplement,
                )
            ) {
                AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                    songId = song.id,
                    stage = "translation_routed_to_supplement",
                    details = "thread=${Thread.currentThread().name},markedAsSupplement=$markedAsSupplement," +
                        "knownNativeLyrics=${song.id?.let {
                            missingLyricsSupplement().hasKnownNativeLyricsFor(it)
                        } == true},visiblePageIsSupplement=$visiblePageIsSupplement",
                )
                missingLyricsSupplement().receiveSupplement(song)
                return@launch
            }

            mainHandler.post {
                AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                    songId = song.id,
                    stage = "translation_apply_main_started",
                    details = "thread=${Thread.currentThread().name}"
                )
                try {
                    val romanizedLineCount = song.lyrics.orEmpty().count {
                        !it.roma.isNullOrBlank()
                    }
                    reportApplePronunciationRuntimeDiagnostic(
                        stage = "payload_callback_decoded",
                        songId = song.id,
                        details = "lyrics=${song.lyrics.orEmpty().size}, " +
                            "romanizedLines=$romanizedLineCount, " +
                            "featureEnabled=${isNativeOnlineTranslationEnabled()}",
                    )
                    if (!isNativeOnlineTranslationEnabled()) return@post
                    val storeStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                        songId = song.id,
                        stage = "translation_store_update_started",
                        details = "thread=${Thread.currentThread().name}"
                    )
                    val displayContentChanged =
                        nativeOnlineTranslationStore.wouldChangeDisplayContent(song)
                    val updated = nativeOnlineTranslationStore.update(song)
                    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                        songId = song.id,
                        stage = "translation_store_update_finished",
                        details = "updated=$updated,displayContentChanged=$displayContentChanged," +
                            "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - storeStartedAtNanos) / 1_000_000.0}"
                    )
                    if (updated) {
                        val revision = nativeOnlineTranslationStore.revision()
                        clearPendingApplePronunciationRenderPlans()
                        ProviderLogger.info(
                            "Apple Music 原生在线翻译已接收: id=${song.id}, " +
                                "translatedLines=${song.lyrics.orEmpty().count {
                                    !it.translation.isNullOrBlank()
                                }}, romanizedLines=${song.lyrics.orEmpty().count {
                                    !it.roma.isNullOrBlank()
                                }}, displayContentChanged=$displayContentChanged"
                        )
                        song.id?.let(onlineSourceMenuHooks()::resolvePendingSwitches)
                        song.id?.let(onlineSourceMenuHooks()::refreshActiveMenu)
                        if (displayContentChanged) {
                            refreshAppleLyricsSupplementPresentation(
                                expectedSongId = song.id,
                                expectedRevision = revision,
                            )
                        } else {
                            ProviderLogger.debug(
                                "Apple Music 原生在线翻译仅来源信息变化，跳过整页刷新: " +
                                    "id=${song.id}"
                            )
                        }
                    }
                } finally {
                    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                        songId = song.id,
                        stage = "translation_apply_main_finished",
                        details = "thread=${Thread.currentThread().name}"
                    )
                }
            }
        }
    }

    /** 独立接收 SystemUI 回传的“无原生歌词补充”载荷，不再借用在线翻译事务。 */
    fun receiveMissingLyricsSupplement(compressedSong: ByteArray) {
        val callbackStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        ProviderLogger.diagnostic(
            "[SourceSwitchPerf] stage=supplement_payload_received, " +
                "bytes=${compressedSong.size}, thread=${Thread.currentThread().name}"
        )
        coroutineScope.launch {
            val decodeStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            ProviderLogger.diagnostic(
                "[SourceSwitchPerf] stage=supplement_payload_decode_started, " +
                    "bytes=${compressedSong.size}, thread=${Thread.currentThread().name}"
            )
            val song = runCatching {
                json.decodeFromString<LocalSong>(
                    compressedSong.inflate().toString(Charsets.UTF_8)
                )
            }.onFailure {
                ProviderLogger.error("Apple Music 无歌词补充解析失败", it)
            }.getOrNull() ?: return@launch
            ProviderLogger.diagnostic(
                "[SourceSwitchPerf] stage=supplement_payload_decode_finished, " +
                    "id=${song.id}, lines=${song.lyrics.orEmpty().size}, elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - decodeStartedAtNanos) / 1_000_000.0) +
                    ", callbackElapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - callbackStartedAtNanos) / 1_000_000.0) +
                    ", thread=${Thread.currentThread().name}"
            )
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = song.id,
                stage = "supplement_receive_started",
                details = "lines=${song.lyrics.orEmpty().size},thread=${Thread.currentThread().name}"
            )
            missingLyricsSupplement().receiveSupplement(song)
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = song.id,
                stage = "supplement_receive_finished",
                details = "elapsedMs=${(SystemClock.elapsedRealtimeNanos() - decodeStartedAtNanos) / 1_000_000.0}," +
                    "thread=${Thread.currentThread().name}"
            )
            song.id?.let { songId ->
                val postedAtNanos = SystemClock.elapsedRealtimeNanos()
                AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                    songId = songId,
                    stage = "supplement_menu_refresh_posted",
                    details = "thread=${Thread.currentThread().name}"
                )
                mainHandler.post {
                    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                        songId = songId,
                        stage = "supplement_menu_refresh_main_started",
                        details = "queueWaitMs=${(SystemClock.elapsedRealtimeNanos() - postedAtNanos) / 1_000_000.0}," +
                            "thread=${Thread.currentThread().name}"
                    )
                    onlineSourceMenuHooks().refreshActiveMenu(songId)
                    AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                        songId = songId,
                        stage = "supplement_menu_refresh_main_finished",
                        details = "thread=${Thread.currentThread().name}"
                    )
                }
            }
        }
    }

    /**
     * Apple Music 自己的模块歌词 ViewModel 已经产出完整歌词时，直接填充本进程 Store。
     * 这条路径不依赖 SystemUI 的进程内缓存，覆盖 SystemUI 重启后的冷启动场景。
     */
    fun receiveModuleMissingLyrics(song: LyriconSong) {
        coroutineScope.launch {
            val localSong = runCatching {
                json.decodeFromString<LocalSong>(json.encodeToString(song))
            }.onFailure {
                ProviderLogger.error("Apple Music 模块补充歌词转换失败", it)
            }.getOrNull() ?: return@launch
            missingLyricsSupplement().receiveSupplement(localSong)
            localSong.id?.let { songId ->
                mainHandler.post { onlineSourceMenuHooks().refreshActiveMenu(songId) }
            }
        }
    }

    fun clearNativeOnlineTranslation(songId: String?) {
        mainHandler.post {
            if (nativeOnlineTranslationStore.clear(songId)) {
                clearPendingApplePronunciationRenderPlans()
                ProviderLogger.debug("Apple Music 原生在线翻译已清除: id=$songId")
                songId?.let(onlineSourceMenuHooks()::refreshActiveMenu)
                refreshAppleLyricsSupplementPresentation()
            }
        }
    }

    /**
     * 无歌词补充：写入 ViewModel 结果并显式调用 I2 主结果入口。I2 会验证
     * SongInfo.adamId、安装歌词适配器，并通过 Apple 自身的 L2/N2/R2 链路收尾。
     */
    fun requestMissingLyricsPresentationRefresh(
        supplementPointer: Any? = null,
        fragmentOverride: Any? = null,
        currentPlaybackItem: Any? = null,
    ) {
        mainHandler.post {
            ProviderLogger.debug(
                "Apple Music 无歌词补充呈现刷新进入: method=" +
                    "${appleLyricsResultPresentationMethod != null}, fragmentRef=" +
                    "${appleLyricsFragmentRef?.get() != null}, override=" +
                    "${fragmentOverride != null}, supplement=${supplementPointer != null}, " +
                    "currentPlaybackItem=${currentPlaybackItem != null}"
            )
            val method = appleLyricsResultPresentationMethod ?: return@post
            val fragment = fragmentOverride ?: appleLyricsFragmentRef?.get() ?: return@post
            val pointer = supplementPointer
                ?: appleLyricsSongPointerRef?.get()
                ?: return@post
            if (supplementPointer != null) {
                if (!injectSupplementIntoViewModel(
                        fragment = fragment,
                        pointer = supplementPointer,
                        currentPlaybackItem = currentPlaybackItem,
                    )
                ) {
                    return@post
                }
            }
            val presentedSongId = runCatching {
                lyricsNativeCall(
                    pointer,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
                )
            }.getOrNull()?.let(::nativeSongId)
            if (presentedSongId != null) {
                appleLyricsPresentationInFlight = true
                ensureAppleLyricsScrollTracking(fragment, presentedSongId)
            }
            runCatching { method.invoke(fragment, pointer) }
                .onSuccess {
                    if (supplementPointer != null && presentedSongId != null) {
                        // I2/结果呈现可能只更新 ViewModel，不经过原生歌词呈现 Hook。
                        // 仍走一次已有的完整 bind 清理路径，避免旧 holder 的翻译子行残留。
                        refreshAppleLyricsRecyclerView(
                            fragment = fragment,
                            expectedSongId = presentedSongId,
                            expectedRevision = null,
                        )
                    }
                    if (presentedSongId != null) {
                        // 排在完整 bind 请求之后重新登记恢复，确保最终位置校验发生在
                        // 新歌词 Adapter 的本轮布局，而不是旧 holder 尚未清理的中间态。
                        restoreAppleLyricsScrollSnapshot(fragment, presentedSongId)
                    } else {
                        appleLyricsPresentationInFlight = false
                    }
                    ProviderLogger.debug("Apple Music 无歌词补充原生呈现已刷新")
                }
                .onFailure {
                    appleLyricsPresentationInFlight = false
                    ProviderLogger.error("Apple Music 无歌词补充原生呈现刷新失败", it)
                }
            ensureMissingLyricsTranslationButtonVisible(fragment)
            dismissAppleLyricsLoadingOverlay(fragment)
            mainHandler.postDelayed(
                { dismissAppleLyricsLoadingOverlay(fragment) },
                800L,
            )
            scheduleSupplementActiveLineUpdate()
        }
    }

    /**
     * 补充歌词已经通过结果 LiveData / I2 呈现后，Apple 仍可能因后续原生加载状态
     * 重新显示 `loading_progress` 遮罩。该遮罩覆盖在 RecyclerView 上方并拦截点击，
     * 对无歌词补充歌曲必须在每次呈现后强制隐藏。
     */
    private fun dismissAppleLyricsLoadingOverlay(fragment: Any?) {
        fragment ?: return
        val root = runCatching {
            AppleReflection.call(
                fragment,
                lyricsUiMember(AppleMusicRuntimeMember.LYRICS_UI_ROOT_VIEW_GETTER),
            ) as? View
        }.getOrNull() ?: return
        val targetResourceName = lyricsUiMember(
            AppleMusicRuntimeMember.LYRICS_UI_LOADING_PROGRESS_RESOURCE_NAME
        )
        var hidden = 0
        fun hideLoadingView(view: View?, depth: Int) {
            if (view == null || depth > 4) return
            val entryName = runCatching {
                if (view.id == View.NO_ID) null else {
                    view.resources.getResourceEntryName(view.id)
                }
            }.getOrNull()
            if (entryName == targetResourceName) {
                if (view.visibility != View.GONE) {
                    view.visibility = View.GONE
                    view.isClickable = false
                    hidden += 1
                }
                // Apple 可能在任意后续回调里把遮罩重新置为 VISIBLE。把抑制动作
                // 绑定到该 View 自己的 layout 变化上，不再依赖固定延迟窗口。
                if (suppressedLyricsLoadingViews.add(view)) {
                    view.addOnLayoutChangeListener(
                        object : View.OnLayoutChangeListener {
                            override fun onLayoutChange(
                                changedView: View,
                                left: Int,
                                top: Int,
                                right: Int,
                                bottom: Int,
                                oldLeft: Int,
                                oldTop: Int,
                                oldRight: Int,
                                oldBottom: Int,
                            ) {
                                val currentSong = currentAppleLyricsSongId ?: currentPlaybackQueueMediaId()
                                val hasSupplement = currentSong != null && missingLyricsSupplement().hasSupplementContent(currentSong)
                                if (hasSupplement && changedView.visibility != View.GONE) {
                                    changedView.visibility = View.GONE
                                    changedView.isClickable = false
                                    ProviderLogger.debug(
                                        "Apple Music 无歌词补充加载遮罩再次显示并被抑制: " +
                                            "id=$targetResourceName"
                                    )
                                }
                            }
                        }
                    )
                }
            }
            (view as? ViewGroup)?.let { group ->
                for (index in 0 until group.childCount) {
                    hideLoadingView(group.getChildAt(index), depth + 1)
                }
            }
        }
        hideLoadingView(root, 0)
        if (hidden > 0) {
            ProviderLogger.debug(
                "Apple Music 无歌词补充加载遮罩已隐藏: id=$targetResourceName, count=$hidden"
            )
        }
    }

    /**
     * 无歌词补充歌曲的播放页必须始终保留 translations_button，否则用户无法进入
     * 来源菜单选择歌词来源。Apple 会在翻译尚未回传时把该按钮置 GONE，这里在补充页
     * 呈现成功后强制恢复 VISIBLE，并持续监听布局变化。
     */
    private fun ensureMissingLyricsTranslationButtonVisible(fragment: Any?) {
        fragment ?: return
        val songId = currentAppleLyricsSongId
            ?: currentPlaybackQueueMediaId()
            ?: return
        if (!missingLyricsSupplement().hasSupplementContent(songId)) return
        val root = runCatching {
            AppleReflection.call(
                fragment,
                lyricsUiMember(AppleMusicRuntimeMember.LYRICS_UI_ROOT_VIEW_GETTER),
            ) as? View
        }.getOrNull() ?: return
        var forced = 0
        fun forceButton(view: View?, depth: Int) {
            if (view == null || depth > 6) return
            val entryName = runCatching {
                if (view.id == View.NO_ID) null else {
                    view.resources.getResourceEntryName(view.id)
                }
            }.getOrNull()
            if (entryName == "translations_button") {
                if (view.visibility != View.VISIBLE || !view.isEnabled) {
                    view.visibility = View.VISIBLE
                    view.isEnabled = true
                    view.isClickable = true
                    forced += 1
                }
                if (forcedLyricsTranslationButtons.add(view)) {
                    view.addOnLayoutChangeListener(
                        object : View.OnLayoutChangeListener {
                            override fun onLayoutChange(
                                changedView: View,
                                left: Int,
                                top: Int,
                                right: Int,
                                bottom: Int,
                                oldLeft: Int,
                                oldTop: Int,
                                oldRight: Int,
                                oldBottom: Int,
                            ) {
                                if (
                                    missingLyricsSupplement()
                                        .hasSupplementContent(songId) &&
                                    changedView.visibility != View.VISIBLE
                                ) {
                                    changedView.visibility = View.VISIBLE
                                    changedView.isEnabled = true
                                    changedView.isClickable = true
                                }
                            }
                        }
                    )
                }
            }
            (view as? ViewGroup)?.let { group ->
                for (index in 0 until group.childCount) {
                    forceButton(group.getChildAt(index), depth + 1)
                }
            }
        }
        forceButton(root, 0)
        if (forced > 0) {
            ProviderLogger.debug(
                "Apple Music 补充歌词翻译按钮已强制可见: id=$songId, count=$forced"
            )
        }
    }

    private fun ensureAppleLyricsScrollTracking(fragment: Any, songId: String) {
        // Apple Music 的 RecyclerView 由宿主 ClassLoader 加载，不能强转为模块侧
        // androidx.recyclerview.widget.RecyclerView。这里只依赖 framework View/ViewTreeObserver，
        // 位置读取和滚动调用统一交给已有的安全反射/ChildAdapterPosition 解析。
        val recycler = resolveAppleLyricsRecyclerView(fragment) as? ViewGroup ?: return
        if (trackedLyricsRecyclerViews.add(recycler)) {
            val recyclerRef = WeakReference(recycler)
            recycler.viewTreeObserver.addOnScrollChangedListener {
                if (appleLyricsPresentationInFlight || isAppleLyricsScrollRestorePending()) return@addOnScrollChangedListener
                val currentRecycler = recyclerRef.get() ?: return@addOnScrollChangedListener
                currentAppleLyricsSongId?.let { currentSongId ->
                    captureAppleLyricsScrollSnapshot(currentRecycler, currentSongId)
                }
            }
            recycler.addOnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
                if (appleLyricsPresentationInFlight || isAppleLyricsScrollRestorePending()) return@addOnLayoutChangeListener
                val currentRecycler = changedView as? ViewGroup ?: return@addOnLayoutChangeListener
                currentAppleLyricsSongId?.let { currentSongId ->
                    captureAppleLyricsScrollSnapshot(currentRecycler, currentSongId)
                }
            }
            ProviderLogger.debug(
                "Apple Music 歌词滚动位置监听已绑定: id=$songId, " +
                    "recycler=${System.identityHashCode(recycler)}"
            )
        }
        captureAppleLyricsScrollSnapshot(recycler, songId)
    }

    private fun isAppleLyricsScrollRestorePending(): Boolean =
        pendingAppleLyricsScrollRestoreRecycler?.get() != null &&
            pendingAppleLyricsScrollRestoreListener != null

    private fun clearPendingAppleLyricsScrollRestore() {
        val recycler = pendingAppleLyricsScrollRestoreRecycler?.get()
        val listener = pendingAppleLyricsScrollRestoreListener
        if (recycler != null && listener != null) {
            runCatching {
                recycler.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
        pendingAppleLyricsScrollRestoreRecycler = null
        pendingAppleLyricsScrollRestoreListener = null
    }

    private fun captureAppleLyricsScrollSnapshot(recycler: ViewGroup, songId: String) {
        if (currentAppleLyricsSongId != songId) return
        val firstChild = recycler.getChildAt(0) ?: return
        val position = blurHooks.appleLyricsChildAdapterPosition(recycler, firstChild)
        if (position < 0) return
        val adapter = blurHooks.appleRecyclerAdapter(recycler)
        val activePositions = adapter
            ?.let(blurHooks::appleLyricsActiveAdapterPositions)
            .orEmpty()
        val positionedChildren = buildMap<Int, View> {
            for (index in 0 until recycler.childCount) {
                val child = recycler.getChildAt(index) ?: continue
                val childPosition = blurHooks.appleLyricsChildAdapterPosition(recycler, child)
                if (childPosition >= 0) put(childPosition, child)
            }
        }
        val activeAdapterPosition = activePositions
            .filter(positionedChildren::containsKey)
            .maxOrNull()
            ?: activePositions.maxOrNull()
        val detailedDiagnostics = BuildConfig.DEBUG &&
            (appleLyricsPresentationInFlight || isAppleLyricsScrollRestorePending())
        val sourceTimingDebug = detailedDiagnostics.takeIf { it }
            ?.let { missingLyricsSupplement().timingDebugSnapshot(songId) }
        val adapterTimingDebug = detailedDiagnostics.takeIf { it }
            ?.let {
                blurHooks.appleLyricsAdapterDebugSnapshot(
                    adapter = adapter ?: return@let "adapter=none",
                    relevantPositions = positionedChildren.keys,
                    playbackPositionMs = appleLyricsCurrentPlaybackPositionMs(),
                )
            }
        val snapshot = AppleLyricsScrollSnapshot(
            firstPosition = position,
            firstOffset = firstChild.top,
            activeAdapterPosition = activeAdapterPosition,
            activeAdapterOffset = activeAdapterPosition
                ?.let(positionedChildren::get)
                ?.top,
            playbackPositionMs = appleLyricsCurrentPlaybackPositionMs(),
            sourceTimingDebug = sourceTimingDebug,
            adapterTimingDebug = adapterTimingDebug,
        )
        val existing = appleLyricsScrollSnapshot
            ?.takeIf { appleLyricsScrollSnapshotSongId == songId }
        if (
            preserveAppleLyricsTopSnapshotSongId == songId &&
                position == 0 &&
                existing?.firstPosition != null &&
                existing.firstPosition > 0
        ) {
            return
        }
        if (preserveAppleLyricsTopSnapshotSongId == songId && position > 0) {
            preserveAppleLyricsTopSnapshotSongId = null
        }
        if (shouldKeepAppleLyricsScrollSnapshot(
                existingPosition = existing?.firstPosition,
                capturedPosition = snapshot.firstPosition,
                presentationInFlight =
                    appleLyricsPresentationInFlight || isAppleLyricsScrollRestorePending(),
            )
        ) {
            return
        }
        appleLyricsScrollSnapshot = snapshot
        appleLyricsScrollSnapshotSongId = songId
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple Music 歌词滚动快照已更新: id=$songId, " +
                    "position=${snapshot.firstPosition}, offset=${snapshot.firstOffset}, " +
                    "activePositions=${activePositions.sorted()}, " +
                    "activeAnchor=${snapshot.activeAdapterPosition ?: "none"}, " +
                    "activeAnchorOffset=${snapshot.activeAdapterOffset ?: "none"}, " +
                    "playback=${snapshot.playbackPositionMs ?: "none"}, " +
                    "presentationInFlight=$appleLyricsPresentationInFlight"
            )
            if (sourceTimingDebug != null || adapterTimingDebug != null) {
                ProviderLogger.diagnostic(
                    "Apple Music 歌词映射诊断: stage=snapshot, id=$songId, " +
                        "source=[$sourceTimingDebug], adapter=[$adapterTimingDebug]"
                )
            }
        }
    }

    private fun debugAppleLyricsVisibleChildren(recycler: ViewGroup): String {
        if (!BuildConfig.DEBUG) return "disabled"
        val ranges = buildList {
            repeat(recycler.childCount) { index ->
                val child = recycler.getChildAt(index) ?: return@repeat
                val position = blurHooks.appleLyricsChildAdapterPosition(recycler, child)
                if (position >= 0) {
                    add(Triple(position, child.top, child.bottom))
                }
            }
        }.take(32)
        val overlaps = buildList {
            for (leftIndex in ranges.indices) {
                for (rightIndex in leftIndex + 1 until ranges.size) {
                    val left = ranges[leftIndex]
                    val right = ranges[rightIndex]
                    if (minOf(left.third, right.third) > maxOf(left.second, right.second)) {
                        add("${left.first}/${right.first}")
                    }
                }
            }
        }
        val segments = ranges.map { it.first }
            .fold(mutableListOf<MutableList<Int>>()) { result, position ->
                val previous = result.lastOrNull()?.lastOrNull()
                if (previous == null || position > previous + 1) {
                    result += mutableListOf(position)
                } else {
                    result.last() += position
                }
                result
            }
            .joinToString("/") { segment ->
                if (segment.size == 1) segment.first().toString()
                else "${segment.first()}-${segment.last()}"
            }
        val children = ranges.joinToString(",") { (position, top, bottom) ->
            "$position:$top..$bottom"
        }
        return "children=[$children],segments=$segments," +
            "overlaps=[${overlaps.joinToString(",")}],childCount=${recycler.childCount}"
    }

    private fun restoreAppleLyricsScrollSnapshot(fragment: Any, songId: String) {
        val snapshot = appleLyricsScrollSnapshot
            ?.takeIf { appleLyricsScrollSnapshotSongId == songId }
            ?: run {
                appleLyricsPresentationInFlight = false
                return
            }
        val recycler = resolveAppleLyricsRecyclerView(fragment) as? ViewGroup
            ?: run {
                appleLyricsPresentationInFlight = false
                return
            }

        clearPendingAppleLyricsScrollRestore()
        var attempts = 0
        var completed = false
        var playbackMappedAdapter: Any? = null
        var playbackMappedPositionResolved = false
        var cachedPlaybackMappedPosition: Int? = null
        lateinit var listener: ViewTreeObserver.OnPreDrawListener

        fun resolveRestoreTarget(): ResolvedAppleLyricsScrollTarget? {
            val layoutManager = runCatching {
                AppleReflection.call(recycler, "getLayoutManager")
            }.getOrNull() ?: return null
            val adapter = blurHooks.appleRecyclerAdapter(recycler) ?: return null
            val itemCount = blurHooks.appleRecyclerAdapterItemCount(adapter)
            if (itemCount <= 0) return null
            val activePositions = blurHooks.appleLyricsActiveAdapterPositions(adapter)
            val playbackPositionMs = appleLyricsCurrentPlaybackPositionMs()
                ?: snapshot.playbackPositionMs
            if (playbackMappedAdapter !== adapter) {
                playbackMappedAdapter = adapter
                playbackMappedPositionResolved = false
                cachedPlaybackMappedPosition = null
            }
            val playbackMappedPosition = if (playbackMappedPositionResolved) {
                cachedPlaybackMappedPosition
            } else if (playbackPositionMs == null) {
                null
            } else {
                blurHooks.appleLyricsAdapterPositionForPlayback(
                    adapter = adapter,
                    playbackPositionMs = playbackPositionMs,
                ).also { resolvedPosition ->
                    cachedPlaybackMappedPosition = resolvedPosition
                    playbackMappedPositionResolved = true
                }
            }
            val anchor = selectAppleLyricsRestoreAnchor(
                savedPosition = snapshot.firstPosition,
                savedOffset = snapshot.firstOffset,
                savedActivePosition = snapshot.activeAdapterPosition,
                savedActiveOffset = snapshot.activeAdapterOffset,
                currentActivePositions = activePositions,
                itemCount = itemCount,
                playbackMappedPosition = playbackMappedPosition,
            ) ?: return null
            val relevantPositions = buildSet {
                add(snapshot.firstPosition)
                snapshot.activeAdapterPosition?.let(::add)
                activePositions.forEach(::add)
                playbackMappedPosition?.let(::add)
            }
            return ResolvedAppleLyricsScrollTarget(
                layoutManager = layoutManager,
                itemCount = itemCount,
                anchor = anchor,
                activePositions = activePositions,
                playbackMappedPosition = playbackMappedPosition,
                sourceTimingDebug = BuildConfig.DEBUG
                    .let { if (it) missingLyricsSupplement().timingDebugSnapshot(songId) else null },
                adapterTimingDebug = BuildConfig.DEBUG
                    .let {
                        if (!it) {
                            null
                        } else {
                            blurHooks.appleLyricsAdapterDebugSnapshot(
                                adapter = adapter,
                                relevantPositions = relevantPositions,
                                playbackPositionMs = playbackPositionMs,
                            )
                        }
                    },
            )
        }

        fun finishRestore(success: Boolean = false) {
            if (completed) return
            completed = true
            runCatching {
                recycler.viewTreeObserver.removeOnPreDrawListener(listener)
            }
            if (pendingAppleLyricsScrollRestoreListener === listener) {
                pendingAppleLyricsScrollRestoreRecycler = null
                pendingAppleLyricsScrollRestoreListener = null
            }
            if (!success) {
                preserveAppleLyricsTopSnapshotSongId = songId
            }
            appleLyricsPresentationInFlight = false
        }

        // R2/I2 clears all RecyclerView children before the first layout of the new source.
        // Seed the host LayoutManager's pending target immediately so that first layout is
        // built at the preserved lyric position instead of drawing a transient top frame.
        resolveRestoreTarget()?.let { target ->
            val resolvedMethod = blurHooks.appleLyricsScrollToPositionWithOffset(
                layoutManager = target.layoutManager,
                position = target.anchor.position,
                offset = target.anchor.offset,
            )
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 歌词滚动预定位: id=$songId, " +
                        "target=${target.anchor.position}, offset=${target.anchor.offset}, " +
                        "activePositions=${target.activePositions.sorted()}, " +
                        "activeTarget=${target.anchor.activePosition ?: "none"}, " +
                        "playbackTarget=${target.playbackMappedPosition ?: "none"}, " +
                        "itemCount=${target.itemCount}, " +
                        "method=${resolvedMethod ?: "unresolved"}, " +
                        "savedSource=[${snapshot.sourceTimingDebug ?: "none"}], " +
                        "currentSource=[${target.sourceTimingDebug ?: "none"}], " +
                        "savedAdapter=[${snapshot.adapterTimingDebug ?: "none"}], " +
                        "currentAdapter=[${target.adapterTimingDebug ?: "none"}]"
                )
            }
        }

        listener = ViewTreeObserver.OnPreDrawListener {
            attempts += 1
            if (currentAppleLyricsSongId != songId) {
                finishRestore()
                return@OnPreDrawListener true
            }
            val restoreTarget = resolveRestoreTarget()
            if (restoreTarget == null) {
                if (attempts >= 8) finishRestore()
                return@OnPreDrawListener true
            }
            val layoutManager = restoreTarget.layoutManager
            val itemCount = restoreTarget.itemCount
            val targetPosition = restoreTarget.anchor.position
            val targetOffset = restoreTarget.anchor.offset
            val firstChild = recycler.getChildAt(0)
            val currentPosition = firstChild?.let {
                blurHooks.appleLyricsChildAdapterPosition(recycler, it)
            }?.takeIf { it >= 0 }
            val currentOffset = firstChild?.top
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 歌词滚动恢复尝试: id=$songId, attempt=$attempts, " +
                        "currentPosition=${currentPosition ?: "none"}, " +
                        "currentOffset=${currentOffset ?: "none"}, target=$targetPosition, " +
                        "targetOffset=$targetOffset, " +
                        "activePositions=${restoreTarget.activePositions.sorted()}, " +
                        "activeTarget=${restoreTarget.anchor.activePosition ?: "none"}, " +
                        "playbackTarget=${restoreTarget.playbackMappedPosition ?: "none"}, " +
                        "saved=${snapshot.firstPosition}, itemCount=$itemCount, " +
                        "adapter=${blurHooks.appleRecyclerAdapter(recycler)?.javaClass?.name ?: "none"}, " +
                        "adapterIdentity=${blurHooks.appleRecyclerAdapter(recycler)
                            ?.let(System::identityHashCode) ?: 0}, " +
                        "savedSource=[${snapshot.sourceTimingDebug ?: "none"}], " +
                        "currentSource=[${restoreTarget.sourceTimingDebug ?: "none"}], " +
                        "savedAdapter=[${snapshot.adapterTimingDebug ?: "none"}], " +
                        "currentAdapter=[${restoreTarget.adapterTimingDebug ?: "none"}], " +
                        "visible=${debugAppleLyricsVisibleChildren(recycler)}"
                )
            }
            if (
                currentPosition == targetPosition &&
                currentOffset != null &&
                abs(currentOffset - targetOffset) <= 2
            ) {
                finishRestore(success = true)
                return@OnPreDrawListener true
            }
            val resolvedMethod = blurHooks.appleLyricsScrollToPositionWithOffset(
                layoutManager = layoutManager,
                position = targetPosition,
                offset = targetOffset,
            )
            if (resolvedMethod != null) {
                ProviderLogger.debug(
                    "Apple Music 歌词滚动位置已恢复: id=$songId, " +
                        "position=$targetPosition, offset=$targetOffset, " +
                        "attempt=$attempts, method=$resolvedMethod"
                )
            } else {
                ProviderLogger.error(
                    "Apple Music 歌词滚动位置恢复调用失败: id=$songId, " +
                        "position=$targetPosition, layout=${layoutManager.javaClass.name}",
                )
            }
            if (attempts >= 8) finishRestore()
            true
        }
        pendingAppleLyricsScrollRestoreRecycler = WeakReference(recycler)
        pendingAppleLyricsScrollRestoreListener = listener
        recycler.viewTreeObserver.addOnPreDrawListener(listener)
        recycler.postOnAnimation {
            if (!recycler.isAttachedToWindow && !completed) {
                finishRestore()
            }
        }
    }

    private fun scheduleSupplementActiveLineUpdate() {
        if (supplementActiveLineUpdateScheduled) return
        supplementActiveLineUpdateScheduled = true
        mainHandler.postDelayed(
            supplementActiveLineUpdateRunnable,
            SUPPLEMENT_ACTIVE_LINE_INTERVAL_MS,
        )
    }

    /**
     * Apple 没有为补充歌词持续下发 active line（adapter.B() 始终为空），但点击歌词
     * seek 仍正常，说明 adapter 只缺 T 的激活集合。这里按补充 Store 自己的行时间
     * 计算当前句，并在 index 变化时补发与 Apple 跳转歌词相同的行级 T 调用。
     */
    private fun updateSupplementActiveLine() {
        val fragment = appleLyricsFragmentRef?.get() ?: run {
                stopSupplementActiveLineUpdate()
                return
            }
        val recyclerView = resolveAppleLyricsRecyclerView(fragment)
            ?: run {
                stopSupplementActiveLineUpdate()
                return
            }
        val adapter = appleRecyclerAdapter(recyclerView)
            ?: run {
                stopSupplementActiveLineUpdate()
                return
            }
        val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
        val visibleSongId = currentAppleLyricsSongId?.takeIf(String::isNotBlank)
        val songId = queueSongId ?: visibleSongId ?: run {
            stopSupplementActiveLineUpdate()
            return
        }
        if (queueSongId != null && visibleSongId != null && queueSongId != visibleSongId) {
            stopSupplementActiveLineUpdate()
            return
        }
        if (
            !missingLyricsSupplement().hasSupplementContent(songId) ||
            missingLyricsSupplement().hasKnownNativeLyricsFor(songId)
        ) {
            stopSupplementActiveLineUpdate()
            return
        }
        val position = appleLyricsCurrentPlaybackPositionMs()
            ?: return scheduleSupplementActiveLineUpdate()
        val lines = missingLyricsSupplement().store.lines(songId)
        if (lines.isEmpty()) {
            stopSupplementActiveLineUpdate()
            return
        }
        val lineIndex = lines.indexOfLast { line -> line.begin <= position }
            .coerceAtLeast(0)
        if (lineIndex == lastSupplementActiveLineIndex) {
            return scheduleSupplementActiveLineUpdate()
        }
        val methodName = blurHooks.lyricsAdapterMember(
            adapter,
            AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD,
        )
        val emptyPairs = java.lang.reflect.Array.newInstance(
            classLoader.loadClass("android.util.Pair"),
            0,
        )
        val applied = runCatching {
            AppleReflection.call(
                adapter,
                methodName,
                listOf(lineIndex),
                -1,
                emptyPairs,
            )
            true
        }.onFailure {
            ProviderLogger.error("Apple Music 无歌词补充激活行补发失败", it)
        }.getOrDefault(false)
        if (applied) {
            lastSupplementActiveLineIndex = lineIndex
            ProviderLogger.debug(
                "Apple Music 无歌词补充激活行补发: " +
                    "id=$songId, index=$lineIndex, position=$position"
            )
        }
        scheduleSupplementActiveLineUpdate()
    }

    private fun stopSupplementActiveLineUpdate() {
        lastSupplementActiveLineIndex = -1
    }

    /** 与 Apple 自身链路一致：时间轴地图 + 结果 LiveData，让原生观察者接管显示。 */
    private fun injectSupplementIntoViewModel(
        fragment: Any,
        pointer: Any,
        currentPlaybackItem: Any?,
    ): Boolean {
        val viewModel = runCatching {
            AppleReflection.field(
                fragment,
                lyricsUiMember(AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD),
            )
        }.getOrNull() ?: run {
            ProviderLogger.debug(
                "Apple Music 无歌词补充注入跳过: reason=view_model_missing, " +
                "fragment=${fragment.javaClass.name}"
            )
            return false
        }
        if (!synchronizeSupplementPlaybackItem(viewModel, pointer, currentPlaybackItem)) {
            // 冷启动时兼容的 PlaybackItem 可能晚于补充模型就绪；此时仍先构建时间轴
            // 并写入结果 LiveData，让歌词页立即显示当前补充指针。待 onCurrentPlaybackItem
            // 捕获到真实条目后，现有刷新链会再次同步并覆盖。
            ProviderLogger.debug(
                "Apple Music 无歌词补充注入降级: reason=playback_item_sync_deferred, " +
                    "pointerSongId=${nativeSongId(lyricsNativeCall(
                        pointer,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
                    ))}"
            )
        }
        runCatching {
            hookResolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD).method
        }.getOrNull()?.let { buildMethod ->
            runCatching { buildMethod.invoke(viewModel, pointer) }
                .onSuccess {
                    ProviderLogger.debug("Apple Music 无歌词补充时间轴地图已构建")
                }
                .onFailure {
                    ProviderLogger.error("Apple Music 无歌词补充时间轴地图构建失败", it)
                }
        }
        val resultLiveData = runCatching {
            AppleReflection.call(
                viewModel,
                lyricsRuntimeMember(
                    AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_RESULT_GETTER
                ),
            )
        }.getOrNull() ?: return false
        // 结果 LiveData 的值类型为 kotlin.Pair<SongInfoPtr, Exception>（6.5.1 DEX：
        // PlayerLyricsViewFragment$14.onChanged 先取 first 再取 second）。
        val resultValue = runCatching {
            val pairClass = classLoader.loadClass("kotlin.Pair")
            pairClass
                .getConstructor(Any::class.java, Any::class.java)
                .newInstance(pointer, null)
        }.getOrNull() ?: return false
        runCatching {
            AppleReflection.call(resultLiveData, "setValue", resultValue)
        }.recoverCatching {
            AppleReflection.call(resultLiveData, "postValue", resultValue)
        }.onSuccess {
            ProviderLogger.debug("Apple Music 无歌词补充结果 LiveData 已写入")
        }.onFailure {
            ProviderLogger.error("Apple Music 无歌词补充结果 LiveData 写入失败", it)
        }
        return true
    }

    /**
     * 冷启动时歌词 Fragment 可能先绑定恢复队列中的上一首歌。补充指针注入前必须
     * 先让同一个 ViewModel 消费当前 PlaybackItem，否则适配器内容与页面标题会属于
     * 不同歌曲。
     */
    private fun synchronizeSupplementPlaybackItem(
        viewModel: Any,
        pointer: Any,
        currentPlaybackItem: Any?,
    ): Boolean {
        val expectedSongId = runCatching {
            lyricsNativeCall(
                pointer,
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
            )
        }.getOrNull()?.let(::nativeSongId)
        if (expectedSongId.isNullOrBlank()) {
            ProviderLogger.debug(
                "Apple Music 无歌词补充注入跳过: reason=supplement_song_id_missing"
            )
            return false
        }
        val loadMethod = appleLyricsLoadMethod ?: runCatching {
            hookResolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).method
        }.getOrNull() ?: return false
        val expectedType = loadMethod.parameterTypes.singleOrNull() ?: run {
            ProviderLogger.debug(
                "Apple Music 无歌词补充注入跳过: reason=load_signature_mismatch, " +
                    "parameterCount=${loadMethod.parameterTypes.size}"
            )
            return false
        }
        val candidates = buildList<Any?> {
            add(currentPlaybackItem)
            addAll(registeredPlaybackItems(expectedSongId))
        }
        val playbackItem = selectLyricsViewModelPlaybackItem(
            expectedSongId = expectedSongId,
            expectedType = expectedType,
            candidates = candidates,
            registeredSongId = registeredPlaybackItemId,
            runtimeSongId = ::playbackItemSongId,
        ) ?: run {
            if (BuildConfig.DEBUG) {
                val candidateSummary = candidates.filterNotNull().joinToString(limit = 16) { item ->
                    val itemId = registeredPlaybackItemId(item) ?: playbackItemSongId(item)
                    "${item.javaClass.name}:$itemId"
                }
                ProviderLogger.diagnostic(
                    "Apple Music 无歌词补充注入跳过: " +
                        "reason=compatible_playback_item_missing, expectedId=$expectedSongId, " +
                        "expectedType=${expectedType.name}, candidates=[$candidateSummary]"
                )
            }
            return false
        }
        val itemSongId = registeredPlaybackItemId(playbackItem)
            ?: playbackItemSongId(playbackItem)
        val boundItemId = appleLyricsItemRef?.get()?.let { item ->
            registeredPlaybackItemId(item) ?: playbackItemSongId(item)
        }
        if (appleLyricsViewModelRef?.get() === viewModel && boundItemId == expectedSongId) {
            return true
        }
        return runCatching {
            loadMethod.invoke(viewModel, playbackItem)
        }.onSuccess {
            appleLyricsViewModelRef = WeakReference(viewModel)
            appleLyricsItemRef = WeakReference(playbackItem)
            ProviderLogger.debug(
                "Apple Music 无歌词补充已同步当前 PlaybackItem: " +
                    "previousId=$boundItemId, currentId=$itemSongId"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 无歌词补充同步当前 PlaybackItem 失败", it)
        }.isSuccess
    }

    private fun playbackItemSongId(item: Any): String? = runCatching {
        AppleReflection.call(
            item,
            lyricsSongMember(AppleMusicRuntimeMember.LYRICS_SONG_ID_METHOD),
        )?.toString()
    }.getOrNull()?.takeIf(String::isNotBlank)

    fun refreshAppleLyricsSupplementPresentation(
        expectedSongId: String? = null,
        expectedRevision: Long? = null,
        deferWhileSourceMenuShowing: Boolean = true,
    ) {
        if (BuildConfig.DEBUG) {
            ProviderLogger.debug(
                "[LyricsScrollDiag] refreshAppleLyricsSupplementPresentation: expectedSongId=$expectedSongId, revision=$expectedRevision"
            )
        }
        mainHandler.post {
            val activeMenuSongId = onlineSourceMenuHooks().activeMenuSongId()
            val activeMenuShowing = onlineSourceMenuHooks().isActiveMenuShowing()
            if (
                deferWhileSourceMenuShowing &&
                shouldDeferNativeTranslationPresentationRefresh(
                    activeMenuSongId = activeMenuSongId,
                    popupShowing = activeMenuShowing,
                    expectedSongId = expectedSongId,
                )
            ) {
                deferNativeTranslationPresentationRefresh(
                    expectedSongId = expectedSongId ?: requireNotNull(activeMenuSongId),
                    expectedRevision = expectedRevision,
                )
                return@post
            }
            onlineSourceMenuHooks().clearInactiveMenu()
            val method = appleLyricsPresentationMethod ?: return@post
            val fragment = appleLyricsFragmentRef?.get() ?: return@post
            val pointer = appleLyricsSongPointerRef?.get() ?: return@post
            val songNative = runCatching {
                lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
            }.getOrNull() ?: return@post
            val currentSongId = nativeSongId(songNative)
            if (!expectedSongId.isNullOrBlank() && expectedSongId != currentSongId) {
                ProviderLogger.debug(
                    "跳过非当前 Apple Music 在线翻译页面刷新: " +
                        "expected=$expectedSongId, current=$currentSongId"
                )
                return@post
            }
            if (
                expectedRevision != null &&
                !nativeOnlineTranslationStore.isCurrentRevision(
                    songId = currentSongId,
                    revision = expectedRevision,
                )
            ) {
                ProviderLogger.debug(
                    "跳过过期 Apple Music 在线翻译页面刷新: " +
                        "id=$currentSongId, revision=$expectedRevision"
                )
                return@post
            }
            if (currentAppleLyricsSongId != currentSongId) {
                clearPendingApplePronunciationRenderPlans()
                clearPendingAppleLyricsScrollRestore()
                currentAppleLyricsSongId = currentSongId
                appleLyricsScrollSnapshot = null
                appleLyricsScrollSnapshotSongId = null
            }
            currentSongId?.let { ensureAppleLyricsScrollTracking(fragment, it) }
            ensureAppleLyricTextHooks(songNative)
            applyAppleNativeSupplementSelection(songNative)
            runCatching {
                method.invoke(fragment, pointer)
            }.onSuccess {
                refreshAppleLyricsRecyclerView(
                    fragment = fragment,
                    expectedSongId = currentSongId,
                    expectedRevision = expectedRevision,
                )
                ProviderLogger.debug(
                    "Apple Music 在线翻译页面已轻量刷新: " +
                        "id=$currentSongId, revision=${expectedRevision ?: "none"}"
                )
            }.onFailure {
                ProviderLogger.error("Apple Music 在线翻译页面轻量刷新失败", it)
            }
        }
    }

    /**
     * 翻译覆盖层变化时保留现有 SongInfo 指针，只重绑歌词列表当前可见的行。
     * Apple 的文本 getter 会从 Store 动态读取翻译；这里不再重新调用完整呈现方法，
     * 也不发送带 payload 的 notify（Apple karaoke holder 会把翻译子行重复追加）。
     */
    fun refreshVisibleMissingLyricsTranslation(expectedSongId: String) {
        if (expectedSongId.isBlank()) return
        mainHandler.post {
            if (currentAppleLyricsSongId != expectedSongId) return@post
            val fragment = appleLyricsFragmentRef?.get() ?: return@post
            val pointer = appleLyricsSongPointerRef?.get() ?: return@post
            if (!missingLyricsSupplement().isSupplementPointer(pointer)) return@post
            val songNative = runCatching {
                lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
            }.getOrNull() ?: return@post
            if (nativeSongId(songNative) != expectedSongId) return@post

            ensureAppleLyricTextHooks(songNative)
            applyAppleNativeSupplementSelection(songNative)
            ensureMissingLyricsTranslationButtonVisible(fragment)
            val recyclerView = resolveAppleLyricsRecyclerView(fragment) ?: return@post
            refreshVisibleAppleLyricsRows(recyclerView, expectedSongId)
        }
    }

    private fun deferNativeTranslationPresentationRefresh(
        expectedSongId: String,
        expectedRevision: Long?,
    ) {
        deferredNativeTranslationRefreshSongId = expectedSongId
        deferredNativeTranslationRefreshRevision = expectedRevision
        if (deferredNativeTranslationRefreshScheduled) return
        deferredNativeTranslationRefreshScheduled = true
        mainHandler.postDelayed(
            {
                deferredNativeTranslationRefreshScheduled = false
                val deferredSongId = deferredNativeTranslationRefreshSongId
                    ?: return@postDelayed
                val deferredRevision = deferredNativeTranslationRefreshRevision
                val popupStillShowing =
                    onlineSourceMenuHooks().isMenuShowingForSong(deferredSongId)
                if (popupStillShowing) {
                    deferNativeTranslationPresentationRefresh(
                        expectedSongId = deferredSongId,
                        expectedRevision = deferredRevision,
                    )
                    return@postDelayed
                }
                deferredNativeTranslationRefreshSongId = null
                deferredNativeTranslationRefreshRevision = null
                refreshAppleLyricsSupplementPresentation(
                    expectedSongId = deferredSongId,
                    expectedRevision = deferredRevision,
                    deferWhileSourceMenuShowing = false,
                )
            },
            100L,
        )
    }

    internal fun shouldDeferNativeTranslationPresentationRefresh(
        activeMenuSongId: String?,
        popupShowing: Boolean,
        expectedSongId: String?,
    ): Boolean =
        popupShowing &&
            !activeMenuSongId.isNullOrBlank() &&
            (expectedSongId.isNullOrBlank() || activeMenuSongId == expectedSongId)

    fun isNativeOnlineTranslationEnabled(): Boolean {
        val prefs = contentUiLanguagePrefs ?: return false
        return prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
        ) && prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
        )
    }

    private fun isHideMandarinPinyinEnabled(): Boolean {
        val prefs = contentUiLanguagePrefs ?: return false
        return prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
        )
    }


    fun shouldHideMandarinPronunciation(
        songId: String? = null,
        pronunciationLanguages: Collection<String> = emptyList(),
        lyricObject: Any? = null,
    ): Boolean {
        val lyricContext = lyricObject?.let(applePronunciationContextByLyricObject::get)
        val resolvedSongId = songId ?: lyricContext?.songId ?: currentAppleLyricsSongId
        val genre = resolvedSongId?.let { id ->
            sequenceOf(MediaMetadataCache.getMetadataById(id)?.genre)
                .plus(
                    catalogResolver()?.cachedCatalogGenres(id)?.asSequence()
                        ?: emptySequence()
                )
                .filterNotNull()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .joinToString(", ")
                .takeIf(String::isNotEmpty)
        }
        val resolvedPronunciationLanguages = buildList {
            addAll(pronunciationLanguages)
            addAll(lyricContext?.pronunciationLanguages.orEmpty())
            resolvedSongId?.let { id ->
                addAll(applePronunciationLanguagesBySongId[id].orEmpty())
            }
        }.map(String::trim).filter(String::isNotEmpty).distinct()
        return ApplePronunciationVisibilityPolicy.shouldHide(
            genre = genre,
            pronunciationLanguages = resolvedPronunciationLanguages,
            hideMandarinPinyin = isHideMandarinPinyinEnabled(),
        )
    }

    private fun ensureAppleLyricTextHooks(songNative: Any) {
        val songId = nativeSongId(songNative)
        val pronunciationLanguages = nativePronunciationLanguages(songNative)
        rememberApplePronunciationLanguages(songNative, pronunciationLanguages)
        ensureAppleNativeOnlineTranslationHooks(songNative)
        val sections = runCatching {
            lyricsNativeCall(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD)
        }.getOrNull() ?: return
        val lines = nativeVectorItems(sections, limit = 8).flatMap { section ->
            val lineVector = runCatching {
                lyricsNativeCall(section, AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD)
            }.getOrNull()
            nativeVectorItems(lineVector, limit = 16)
        }
        if (lines.isEmpty()) return
        val pronunciationContext = ApplePronunciationContext(
            songId = songId,
            pronunciationLanguages = pronunciationLanguages,
        )
        lines.forEach { line ->
            applePronunciationContextByLyricObject[line] = pronunciationContext
        }
        logApplePronunciationDiagnostics(songNative, lines)

        val textGetterNames = listOf(
            lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD),
            lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD),
            lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD),
            lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD),
            lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATED_BACKGROUND_TEXT_METHOD
            ),
            lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD
            ),
        )
        lines.map(Any::javaClass).distinct().forEach { lineClass ->
            textGetterNames.forEach { name -> hookAppleLyricTextGetter(lineClass, name) }
            hookApplePronunciationWordsGetters(lineClass)
        }

        val words = lines.flatMap { line ->
            buildList {
                runCatching {
                    lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD)
                }
                    .getOrNull()
                    ?.let { addAll(nativeVectorItems(it, limit = 8)) }
                val backgroundWords = runCatching {
                    lyricsNativeCall(
                        line,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD,
                        false,
                    )
                }.recoverCatching {
                    lyricsNativeCall(
                        line,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD,
                    )
                }.getOrNull()
                backgroundWords?.let { addAll(nativeVectorItems(it, limit = 8)) }
            }
        }
        words.map(Any::javaClass).distinct().forEach { wordClass ->
            hookAppleLyricTextGetter(
                wordClass,
                lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD),
            )
        }
    }

    private fun hookAppleLyricTextGetter(clazz: Class<*>, name: String) {
        val method = runCatching {
            AppleReflection.findMethod(clazz, name, parameterCount = 0)
        }.getOrNull() ?: return
        if (method.returnType != String::class.java || !lyricDisplayTextHookedMethods.add(method)) {
            return
        }
        hookRegistrar.installResultOverrideHook(method) { chain, original ->
            val originalText = original as? String
            if (AppleLyricTextTransform.isRawReadActive()) {
                return@installResultOverrideHook AppleLyricTextTransform.transform(originalText)
                    ?: original
            }
            // 补全发音复用 Apple 主句原生 word；只在发音渲染调用栈内替换其显示文本。
            // 这样 word 仍保留原生父 LyricsLine、lineId、wordId 与主句时间轴。
            val scopedPronunciationText = applePronunciationWordRenderContexts.current
                ?.displayText(chain.thisObject)
            if (scopedPronunciationText != null) {
                return@installResultOverrideHook AppleLyricTextTransform.transform(
                    scopedPronunciationText
                ) ?: scopedPronunciationText
            }
            when (name) {
                lyricsRuntimeMember(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD
                ) -> {
                    val text = AppleNativeOnlineTranslationStore.sanitizeContent(originalText)
                        ?: onlineTranslationForNativeLine(chain.thisObject)
                    val result = AppleLyricTextTransform.transform(text) ?: original
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.debug(
                            "[LyricsScrollDiag] getTranslationText: line=${System.identityHashCode(chain.thisObject)}, " +
                                "original=$originalText, online=$text, result=$result"
                        )
                    }
                    result
                }
                lyricsRuntimeMember(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD
                ) -> {
                    if (shouldHideMandarinPronunciation(lyricObject = chain.thisObject)) {
                        return@installResultOverrideHook ""
                    }
                    val officialText = RomanizationPolicy.sanitize(
                        originalText = nativeOriginalLineText(chain.thisObject),
                        pronunciation = originalText,
                    )
                    val onlineText = if (officialText == null) {
                        onlinePronunciationForNativeLine(chain.thisObject)
                    } else {
                        null
                    }
                    if (onlineText != null) {
                        reportApplePronunciationRuntimeDiagnostic(
                            stage = "line_text_overlay",
                            details = nativeLineDiagnosticDetails(
                                line = chain.thisObject,
                                pronunciation = onlineText,
                            ),
                        )
                    }
                    val text = officialText ?: onlineText
                    val displayText = ApplePronunciationPolicy.nonNullDisplayText(
                        AppleLyricTextTransform.transform(text)
                    )
                    logApplePronunciationLineBinding(
                        line = chain.thisObject,
                        originalPronunciation = originalText,
                        officialPronunciation = officialText,
                        onlinePronunciation = onlineText,
                        displayText = displayText,
                    )
                    displayText
                }
                lyricsRuntimeMember(
                    AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD
                ) -> {
                    if (shouldHideMandarinPronunciation(lyricObject = chain.thisObject)) {
                        return@installResultOverrideHook ""
                    }
                    val text = RomanizationPolicy.sanitize(
                        originalText = nativeOriginalBackgroundLineText(chain.thisObject),
                        pronunciation = originalText,
                    )
                    ApplePronunciationPolicy.nonNullDisplayText(
                        AppleLyricTextTransform.transform(text)
                    )
                }
                else -> AppleLyricTextTransform.transform(originalText) ?: original
            }
        }
        ProviderLogger.debug("Apple Music 歌词文本转换 Hook 已安装: ${clazz.name}#$name")
    }

    private fun hookApplePronunciationWordsGetters(clazz: Class<*>) {
        hookApplePronunciationWordsGetter(
            clazz = clazz,
            methodName = lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD
            ),
            parameterCount = 0,
            originalTextGetter = lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD
            ),
            pronunciationTextGetter = lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD
            ),
            mainWordsGetter = lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD
            ),
            onlineFallback = true,
        )
        hookApplePronunciationWordsGetter(
            clazz = clazz,
            methodName = lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD
            ),
            parameterCount = 1,
            originalTextGetter = lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD
            ),
            pronunciationTextGetter = lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD
            ),
            mainWordsGetter = lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD
            ),
            onlineFallback = false,
        )
    }

    /**
     * Hook Apple 的两个逐词布局构建方法。补全发音传入的是主句原生 word vector，
     * 此处只在该 vector 被消费的调用栈内替换显示文本，退出后立即恢复主句原文。
     */
    fun hookApplePronunciationWordRendering() {
        val wordVectorClassName = hookResolver.resolveClass(
            AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS
        ).target.className
        hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER)
            .forEach { resolvedClass ->
                val adapterClass = resolvedClass.clazz
                val renderMethods = generateSequence(adapterClass) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .filter { method ->
                        method.parameterTypes.firstOrNull()?.name == wordVectorClassName &&
                            method.returnType.name == "android.util.ArrayMap"
                    }
                    .distinctBy { method ->
                        method.name to method.parameterTypes.joinToString { it.name }
                    }
                    .toList()
                renderMethods.forEach { method ->
                    if (!applePronunciationRenderHookedMethods.add(method)) return@forEach
                    method.isAccessible = true
                    hookRegistrar.installScopedHook(
                        executable = method,
                        enter = enter@{ chain ->
                            val vector = chain.args.firstOrNull() ?: return@enter false
                            val plan = consumeApplePronunciationRenderPlan(vector)
                            if (plan == null) {
                                if (
                                    nativeOnlineTranslationStore.hasPronunciation(
                                        currentAppleLyricsSongId
                                    ) && nativeVectorSize(vector) > 0
                                ) {
                                    reportApplePronunciationRuntimeDiagnostic(
                                        stage = "render_plan_miss",
                                        details = "method=${method.declaringClass.name}#" +
                                            "${method.name}/${method.parameterCount}, " +
                                            "vector=${System.identityHashCode(vector)}, " +
                                            "words=${nativeVectorSize(vector)}, " +
                                            "args=${applePronunciationRenderArgumentSummary(chain.args)}",
                                        dedupeKey = "render_plan_miss:${method.name}:" +
                                            method.parameterCount,
                                    )
                                }
                                return@enter false
                            }
                            val context = buildApplePronunciationWordRenderContext(vector, plan)
                                ?: return@enter false
                            reportApplePronunciationRuntimeDiagnostic(
                                stage = "render_plan_consumed",
                                details = "method=${method.declaringClass.name}#" +
                                    "${method.name}/${method.parameterCount}, " +
                                    "vector=${System.identityHashCode(vector)}, " +
                                    "words=${nativeVectorSize(vector)}, " +
                                    "pronunciationChars=${plan.pronunciation.length}, " +
                                    "args=${applePronunciationRenderArgumentSummary(chain.args)}",
                                dedupeKey = "render_plan_consumed:${method.name}:" +
                                    method.parameterCount,
                            )
                            applePronunciationWordRenderContexts.push(context)
                            true
                        },
                        after = { _, _ -> Unit },
                        exit = { applePronunciationWordRenderContexts.pop() },
                    )
                    ProviderLogger.debug(
                        "Apple Music 发音主句时间轴渲染 Hook 已安装: " +
                            "${method.declaringClass.name}#${method.name}/${method.parameterCount}"
                    )
                }
            }
    }

    private fun hookApplePronunciationWordsGetter(
        clazz: Class<*>,
        methodName: String,
        parameterCount: Int,
        originalTextGetter: String,
        pronunciationTextGetter: String,
        mainWordsGetter: String,
        onlineFallback: Boolean,
    ) {
        val method = runCatching {
            AppleReflection.findMethod(clazz, methodName, parameterCount = parameterCount)
        }.getOrNull() ?: return
        if (!nativeOnlineTranslationHookedMethods.add(method)) return

        hookRegistrar.installResultOverrideHook(method) { chain, original ->
            if (AppleLyricTextTransform.isRawReadActive()) {
                return@installResultOverrideHook original
            }
            val line = chain.thisObject ?: return@installResultOverrideHook original
            if (shouldHideMandarinPronunciation(lyricObject = line)) {
                return@installResultOverrideHook emptyApplePronunciationWords(
                    originalVector = original,
                    mainWords = null,
                ) ?: original
            }
            val originalText = nativeRawLineText(line, originalTextGetter)
            val officialPronunciation = RomanizationPolicy.sanitize(
                originalText = originalText,
                pronunciation = nativeRawLineText(line, pronunciationTextGetter),
            )
            val onlinePronunciation = if (onlineFallback) {
                onlinePronunciationForNativeLine(line)
            } else {
                null
            }
            val hasValidOfficialWords = officialPronunciation != null &&
                RomanizationPolicy.sanitize(
                    originalText = originalText,
                    pronunciation = nativeRawWordVectorText(original),
                ) != null
            val mainWords = runCatching {
                AppleReflection.call(
                    line,
                    mainWordsGetter,
                    *chain.args.toTypedArray(),
                )
            }.getOrNull()
            val hasCompatibleOfficialWords = hasValidOfficialWords &&
                ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                    mainWordBegins = nativeRenderableWordBegins(mainWords),
                    pronunciationWordBegins = nativeRenderableWordBegins(original),
                )
            val mainTimingPronunciation = when {
                officialPronunciation != null && !hasCompatibleOfficialWords -> {
                    reportApplePronunciationRuntimeDiagnostic(
                        stage = "official_word_timing_fallback",
                        details = nativeLineDiagnosticDetails(
                            line = line,
                            pronunciation = officialPronunciation,
                        ) + ", method=$methodName/$parameterCount, " +
                            "mainBegins=${nativeRenderableWordBegins(mainWords)}, " +
                            "officialBegins=${nativeRenderableWordBegins(original)}",
                        dedupeKey = "official_word_timing_fallback:$methodName:" +
                            runCatching {
                                lyricsNativeCall(
                                    line,
                                    AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD,
                                )
                            }
                                .getOrNull(),
                    )
                    officialPronunciation
                }
                else -> onlinePronunciation
            }
            val wordTrack = ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = hasCompatibleOfficialWords,
                hasOnlinePronunciation = mainTimingPronunciation != null,
            )
            val resolvedWords = when (wordTrack) {
                ApplePronunciationWordTrack.OFFICIAL -> original
                ApplePronunciationWordTrack.MAIN_LINE_TIMING -> {
                    mainWords?.takeIf { nativeVectorSize(it) > 0 }?.also { vector ->
                        registerApplePronunciationRenderPlan(
                            vector = vector,
                            pronunciation = requireNotNull(mainTimingPronunciation),
                        )
                        reportApplePronunciationRuntimeDiagnostic(
                            stage = "word_track_registered",
                            details = nativeLineDiagnosticDetails(
                                line = line,
                                pronunciation = mainTimingPronunciation,
                            ) + ", method=$methodName/$parameterCount, " +
                                "vector=${System.identityHashCode(vector)}, " +
                                "words=${nativeVectorSize(vector)}, track=$wordTrack",
                            dedupeKey = "word_track_registered:$methodName",
                        )
                    } ?: emptyApplePronunciationWords(
                        originalVector = original,
                        mainWords = mainWords,
                    ) ?: original
                }
                ApplePronunciationWordTrack.HIDDEN -> emptyApplePronunciationWords(
                    originalVector = original,
                    mainWords = null,
                ) ?: original
            }
            logApplePronunciationWordBinding(
                line = line,
                methodName = methodName,
                wordTrack = wordTrack,
                originalWords = original,
                resolvedWords = resolvedWords,
                officialPronunciation = officialPronunciation,
                onlinePronunciation = onlinePronunciation,
            )
            resolvedWords
        }
        ProviderLogger.debug(
            "Apple Music 发音逐词轨道 Hook 已安装: ${clazz.name}#$methodName/$parameterCount"
        )
    }

    private fun logApplePronunciationDiagnostics(songNative: Any, lines: List<Any>) {
        if (!BuildConfig.DEBUG) return
        val songId = nativeSongId(songNative) ?: return

        val languages = runCatching {
            nativeVectorItems(
                lyricsNativeCall(
                    songNative,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
                ),
                limit = 16,
            ).map(Any::toString).filter(String::isNotBlank)
        }.getOrDefault(emptyList())
        val nativeTextLines = AppleLyricTextTransform.withRawReads {
            lines.count { line ->
                runCatching {
                    AppleNativeOnlineTranslationStore.sanitizeContent(
                        lyricsNativeCall(
                            line,
                            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
                        ) as? String
                    ) != null
                }.getOrDefault(false)
            }
        }
        val nativeWordLines = AppleLyricTextTransform.withRawReads {
            lines.count { line ->
                runCatching {
                    nativeVectorSize(
                        lyricsNativeCall(
                            line,
                            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD,
                        )
                    ) > 0
                }.getOrDefault(false)
            }
        }
        val mainWordLines = lines.count { line ->
            runCatching {
                nativeVectorSize(
                    lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD)
                ) > 0
            }
                .getOrDefault(false)
        }
        val onlineTextLines = lines.count { onlinePronunciationForNativeLine(it) != null }
        val stateSignature = listOf(
            songId,
            languages.joinToString(","),
            nativeTextLines,
            nativeWordLines,
            onlineTextLines,
            mainWordLines,
        ).joinToString("|")
        if (!applePronunciationDiagnosticsLoggedSongIds.add(stateSignature)) return
        ProviderLogger.diagnostic(
            "Apple pronunciation: id=$songId, languages=$languages, " +
                "nativeTextLines=$nativeTextLines, nativeWordLines=$nativeWordLines, " +
                "onlineTextLines=$onlineTextLines, mainWordLines=$mainWordLines"
        )
        reportApplePronunciationRuntimeDiagnostic(
            stage = "song_snapshot",
            songId = songId,
            details = "languages=$languages, nativeTextLines=$nativeTextLines, " +
                "nativeWordLines=$nativeWordLines, onlineTextLines=$onlineTextLines, " +
                "mainWordLines=$mainWordLines, " +
                "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}, " +
                "hidden=${shouldHideMandarinPronunciation(songId = songId)}",
            dedupeKey = "song_snapshot:$stateSignature",
        )
    }

    private fun reportApplePronunciationRuntimeDiagnostic(
        stage: String,
        songId: String? = currentAppleLyricsSongId,
        details: String,
        dedupeKey: String = stage,
    ) {
        if (!BuildConfig.DEBUG || !runtime.isAttached) return
        val normalizedSongId = songId?.takeIf(String::isNotBlank) ?: "unknown"
        if (!applePronunciationRuntimeDiagnosticKeys.add("$normalizedSongId|$dedupeKey")) {
            return
        }
        val message = "stage=$stage, id=$normalizedSongId, $details"
        Log.i("ApplePronunciationDiag", message)
        runCatching {
            application.contentResolver.call(
                Uri.parse("content://${RootConstants.CLASSIC_AOD_FOCUS_REFRESH_AUTHORITY}"),
                RootConstants.DEBUG_APPLE_PRONUNCIATION_DIAGNOSTIC_METHOD,
                message,
                null,
            )
        }.onFailure {
            ProviderLogger.debug(
                "Apple 发音诊断回传失败: stage=$stage, reason=${it.message}"
            )
        }
    }

    private fun logApplePronunciationLineBinding(
        line: Any?,
        originalPronunciation: String?,
        officialPronunciation: String?,
        onlinePronunciation: String?,
        displayText: String,
    ) {
        val context = diagnostics.currentBindingContext() ?: return
        val begin = line?.let {
            runCatching {
                lyricsNativeCall(it, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number
            }
                .getOrNull()
                ?.toLong()
        }
        val source = when {
            officialPronunciation != null -> "official"
            onlinePronunciation != null -> "online"
            else -> "none"
        }
        logApplePronunciationBindingDiagnostic(
            stage = "line_getter",
            context = context,
            details = "begin=$begin, source=$source, " +
                "originalChars=${originalPronunciation.orEmpty().length}, " +
                "officialChars=${officialPronunciation.orEmpty().length}, " +
                "onlineChars=${onlinePronunciation.orEmpty().length}, " +
                "displayChars=${displayText.length}",
            dedupeKey = "line:${context.methodName}:${context.position}:$begin:" +
                "$source:${displayText.length}",
        )
    }

    private fun logApplePronunciationWordBinding(
        line: Any?,
        methodName: String,
        wordTrack: ApplePronunciationWordTrack,
        originalWords: Any?,
        resolvedWords: Any?,
        officialPronunciation: String?,
        onlinePronunciation: String?,
    ) {
        val context = diagnostics.currentBindingContext() ?: return
        val begin = line?.let {
            runCatching {
                lyricsNativeCall(it, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number
            }
                .getOrNull()
                ?.toLong()
        }
        val alignment = if (wordTrack == ApplePronunciationWordTrack.OFFICIAL) {
            debugAppleOfficialPronunciationAlignment(
                line = line,
                pronunciationWords = resolvedWords,
            )
        } else {
            "not_official"
        }
        logApplePronunciationBindingDiagnostic(
            stage = "word_getter",
            context = context,
            details = "getter=$methodName, begin=$begin, track=$wordTrack, " +
                "originalWords=${nativeVectorSize(originalWords)}, " +
                "resolvedWords=${nativeVectorSize(resolvedWords)}, " +
                "officialChars=${officialPronunciation.orEmpty().length}, " +
                "onlineChars=${onlinePronunciation.orEmpty().length}, " +
                "alignment=$alignment",
            dedupeKey = "words:${context.methodName}:${context.position}:$begin:" +
                "$methodName:$wordTrack:${nativeVectorSize(resolvedWords)}",
        )
    }

    private fun debugAppleOfficialPronunciationAlignment(
        line: Any?,
        pronunciationWords: Any?,
    ): String {
        if (!BuildConfig.DEBUG || line == null) return "unavailable"
        val mainWords = AppleLyricTextTransform.withRawReads {
            runCatching {
                lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD)
            }.getOrNull()
        }
        val main = debugAppleWordTimings(mainWords)
        val pronunciation = debugAppleWordTimings(pronunciationWords)
        val pronunciationBegins = pronunciation.mapTo(hashSetOf()) { it.begin }
        val exactMatches = main.count { it.begin in pronunciationBegins }
        val nearestDeltas = main.map { mainWord ->
            pronunciation.minOfOrNull { pronunciationWord ->
                abs(mainWord.begin - pronunciationWord.begin)
            }
        }
        return "exactBegin=$exactMatches/${main.size}, " +
            "nearestDelta=$nearestDeltas, main=${debugAppleWordTimings(main)}, " +
            "pronunciation=${debugAppleWordTimings(pronunciation)}"
    }

    private fun debugAppleWordTimings(vector: Any?): List<AppleDebugWordTiming> =
        AppleLyricTextTransform.withRawReads {
            nativeVectorItems(vector, limit = 32).mapNotNull { word ->
                runCatching {
                    val begin = (
                        lyricsNativeCall(word, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD)
                            as Number
                        ).toInt()
                    val duration = (
                        lyricsNativeCall(word, AppleMusicRuntimeMember.LYRICS_NATIVE_DURATION_METHOD)
                            as Number
                        ).toInt()
                    AppleDebugWordTiming(
                        wordId = (
                            lyricsNativeCall(
                                word,
                                AppleMusicRuntimeMember.LYRICS_NATIVE_WORD_ID_METHOD,
                            ) as Number
                            ).toInt(),
                        begin = begin,
                        end = begin + duration,
                        text = (
                            lyricsNativeCall(
                                word,
                                AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD,
                            ) as? String
                            )
                            .orEmpty()
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .take(24),
                    )
                }.getOrNull()
            }
        }

    private fun debugAppleWordTimings(words: List<AppleDebugWordTiming>): String =
        words.joinToString(prefix = "[", postfix = "]") { word ->
            "${word.wordId}@${word.begin}-${word.end}:${word.text}"
        }

    private fun logApplePronunciationBindingDiagnostic(
        stage: String,
        context: AppleLyricsBindingDiagnosticContext,
        details: String,
        dedupeKey: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val songId = context.songId?.takeIf(String::isNotBlank) ?: "unknown"
        if (!applePronunciationBindingDiagnosticKeys.add("$songId|$dedupeKey")) return
        Log.i(
            "ApplePronunciationBindDiag",
            "stage=$stage, id=$songId, adapter=${context.adapterClass}@" +
                "${context.adapterIdentity}, method=${context.methodName}, " +
                "position=${context.position}, translation=${context.translationEnabled}, " +
                "pronunciation=${context.pronunciationEnabled}, $details",
        )
    }

    private fun nativeLineDiagnosticDetails(
        line: Any?,
        pronunciation: String?,
    ): String {
        val begin = line?.let {
            runCatching {
                lyricsNativeCall(it, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number
            }
                .getOrNull()
                ?.toLong()
        }
        val end = line?.let {
            runCatching {
                lyricsNativeCall(it, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD) as? Number
            }
                .getOrNull()
                ?.toLong()
        }
        return "begin=$begin, end=$end, pronunciationChars=${pronunciation.orEmpty().length}"
    }

    private fun applePronunciationRenderArgumentSummary(args: List<Any?>): String =
        args.mapIndexedNotNull { index, value ->
            val resourceId = (value as? Number)?.toInt() ?: return@mapIndexedNotNull null
            val resourceName = runCatching {
                application.resources.getResourceEntryName(resourceId)
            }.getOrNull()
            "$index=$resourceId${resourceName?.let { ":$it" }.orEmpty()}"
        }.joinToString(prefix = "[", postfix = "]")

    private fun ensureAppleNativeOnlineTranslationHooks(songNative: Any) {
        val translationAvailabilityChecks = mapOf<String, (String?) -> Boolean>(
            lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD
            ) to ::hasAnyOnlineTranslation,
            lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_TRANSLATION_METHOD
            ) to ::hasAnyOnlineTranslation,
        )
        translationAvailabilityChecks.forEach { (name, hasOnlineContent) ->
            val method = runCatching {
                AppleReflection.findMethod(songNative.javaClass, name, parameterCount = 1)
            }.getOrNull() ?: return@forEach
            if (
                method.returnType != Boolean::class.javaPrimitiveType ||
                !nativeOnlineTranslationHookedMethods.add(method)
            ) return@forEach

            // Apple Music 6.5.0 的歌词页会先用系统语言调用 setTranslation/hasTranslation，
            // 但官方对象可能只提供带地区的标签（例如 zh-Hans-CN）。把这两个调用统一
            // 映射到对象真实存在的官方标签，避免译文已加载却被 G2 可见性检查隐藏。
            hookRegistrar.installArgumentRewriteHook(method) { chain ->
                if (appleOfficialTranslationProbeGuard.isActive) {
                    return@installArgumentRewriteHook null
                }
                val requestedLanguage = chain.args.firstOrNull() as? String
                    ?: return@installArgumentRewriteHook null
                val selectedLanguage = selectAppleOfficialTranslationArgument(
                    songNative = chain.thisObject,
                    requestedLanguage = requestedLanguage,
                ) ?: return@installArgumentRewriteHook null
                if (selectedLanguage == requestedLanguage) {
                    null
                } else {
                    ProviderLogger.debug(
                        "Apple 官方翻译语言参数兼容映射：method=$name, " +
                            "requested=$requestedLanguage, selected=$selectedLanguage"
                    )
                    arrayOf(selectedLanguage)
                }
            }

            hookRegistrar.installResultOverrideHook(method) { chain, original ->
                if (appleOfficialTranslationProbeGuard.isActive) {
                    return@installResultOverrideHook original
                }
                if (original == true) {
                    true
                } else {
                    hasOnlineContent(nativeSongId(chain.thisObject))
                }
            }
            ProviderLogger.debug(
                "Apple Music 原生在线翻译可用性 Hook 已安装: " +
                    "${method.declaringClass.name}#$name"
            )
        }
        listOf(
            lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD
            ),
            lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_PRONUNCIATION_METHOD
            ),
        ).forEach { name ->
            val method = runCatching {
                AppleReflection.findMethod(songNative.javaClass, name, parameterCount = 1)
            }.getOrNull() ?: return@forEach
            if (
                method.returnType != Boolean::class.javaPrimitiveType ||
                !nativeOnlineTranslationHookedMethods.add(method)
            ) return@forEach

            hookRegistrar.installResultOverrideHook(method) { chain, original ->
                val songId = nativeSongId(chain.thisObject)
                val requestedLanguage = chain.args.firstOrNull() as? String
                if (
                    shouldHideMandarinPronunciation(
                        songId = songId,
                        pronunciationLanguages = listOfNotNull(requestedLanguage),
                    )
                ) {
                    return@installResultOverrideHook false
                }
                val hasOnlineRomanization = isNativeOnlineTranslationEnabled() &&
                    nativeOnlineTranslationStore.hasPronunciation(songId)
                val resolved = hasOnlineRomanization ||
                    (original == true && hasValidOfficialRomanization(chain.thisObject))
                reportApplePronunciationRuntimeDiagnostic(
                    stage = "availability_$name",
                    songId = songId,
                    details = "requested=$requestedLanguage, original=$original, " +
                        "online=$hasOnlineRomanization, resolved=$resolved, " +
                        "selected=${PreferencesMonitor.isPronunciationSelected()}",
                    dedupeKey = "availability_$name:$requestedLanguage:$original:$resolved",
                )
                resolved
            }
            ProviderLogger.debug(
                "Apple Music 罗马音可用性 Hook 已安装: " +
                    "${method.declaringClass.name}#$name"
            )
        }
    }

    /**
     * 根据 SongInfo 当前真实提供的官方语言标签，修正 Apple 歌词页传入的系统语言参数。
     * 这里只返回官方轨道标签，不把三方缓存伪装成 Apple 官方可用性。
     */
    private fun selectAppleOfficialTranslationArgument(
        songNative: Any?,
        requestedLanguage: String,
    ): String? {
        songNative ?: return null
        val availableLanguages = nativeVectorStrings(
            runCatching {
                lyricsNativeCall(
                    songNative,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD,
                )
            }.getOrNull()
        )
        return selectAppleLyricsTranslationLanguage(
            systemLanguage = requestedLanguage,
            availableLanguages = availableLanguages,
        )
    }

    fun hookAppleOfficialPronunciationLanguageMatching() {
        val method = hookResolver.resolveMethod(
            AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH
        ).method
        hookRegistrar.installResultOverrideHook(method) { chain, original ->
            val appleLanguages = nativeVectorStrings(chain.args.firstOrNull())
            if (
                shouldHideMandarinPronunciation(
                    pronunciationLanguages = appleLanguages,
                )
            ) {
                return@installResultOverrideHook null
            }
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = original as? String,
                appleLanguages = appleLanguages,
                onlineFallbackLanguage = thirdPartyPronunciationFallbackLanguage(),
            )
        }
        ProviderLogger.debug(
            "Apple Music 官方发音语言优先选择 Hook 已安装: " +
                "${method.declaringClass.name}#matchToSystemLyricsScript"
        )
    }

    fun hookAppleLyricsPreferredLanguages() {
        runCatching {
            val requestClass = hookResolver.resolveClass(
                AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST
            ).clazz
            val constructorAndIndexes = requestClass.declaredConstructors.firstNotNullOfOrNull {
                candidate ->
                val indexes = appleLyricsStringArrayParameterIndexes(candidate.parameterTypes)
                if (indexes.size == 2) candidate to indexes else null
            } ?: throw NoSuchMethodException(
                "${requestClass.name}<init>(...,String[],...,String[],...)"
            )
            val (constructor, stringArrayIndexes) = constructorAndIndexes
            val translationIndex = stringArrayIndexes.first()
            val pronunciationIndex = stringArrayIndexes.last()
            constructor.isAccessible = true
            hookRegistrar.installArgumentRewriteHook(constructor) { chain ->
                val originalTranslationLanguages =
                    chain.args.getOrNull(translationIndex) as? Array<*>
                val expandedTranslationLanguages = expandAppleLyricsTranslationLanguages(
                    originalTranslationLanguages?.filterIsInstance<String>().orEmpty()
                ).toTypedArray()
                val originalPronunciationLanguages =
                    chain.args.getOrNull(pronunciationIndex) as? Array<*>
                val expandedPronunciationLanguages = expandAppleLyricsPronunciationLanguages(
                    originalPronunciationLanguages?.filterIsInstance<String>().orEmpty()
                ).toTypedArray()
                ProviderLogger.debug(
                    "Apple Music 官方歌词翻译候选请求: " +
                        "original=${originalTranslationLanguages?.toList()}, " +
                        "expanded=${expandedTranslationLanguages.toList()}"
                )
                ProviderLogger.debug(
                    "Apple Music 官方歌词发音候选请求: " +
                        "original=${originalPronunciationLanguages?.toList()}, " +
                        "expanded=${expandedPronunciationLanguages.toList()}"
                )
                chain.args.toTypedArray().also { rewritten ->
                    rewritten[translationIndex] = expandedTranslationLanguages
                    rewritten[pronunciationIndex] = expandedPronunciationLanguages
                }
            }
            ProviderLogger.debug(
                "Apple Music 官方歌词语言候选 Hook 已安装: " +
                    "${requestClass.name}<init>, translationIndex=$translationIndex, " +
                    "pronunciationIndex=$pronunciationIndex"
            )
        }.onFailure {
            ProviderLogger.error(
                "Apple Music 官方歌词语言候选 Hook 安装失败，已保留原生歌词链路",
                it,
            )
        }
    }

    private fun currentSystemLyricsLanguage(): String? = runCatching {
        appleLyricsViewModelRef?.get()?.let {
                lyricsNativeCall(
                    it,
                    AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD,
                ) as? String
        }
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun thirdPartyPronunciationFallbackLanguage(): String? {
        val systemLanguage = currentSystemLyricsLanguage()
        if (
            shouldHideMandarinPronunciation(
                pronunciationLanguages = listOfNotNull(systemLanguage),
            )
        ) return null
        if (!isNativeOnlineTranslationEnabled()) return null
        val songId = currentAppleLyricsSongId
        if (!nativeOnlineTranslationStore.hasPronunciation(songId)) return null
        return systemLanguage
            ?.takeIf(RomanizationPolicy::isLatinLanguageTag)
            ?: "und-Latn"
    }

    private fun applyAppleNativeSupplementSelection(songNative: Any) {
        val songId = nativeSongId(songNative)
        reportApplePronunciationRuntimeDiagnostic(
            stage = "supplement_selection",
            songId = songId,
            details = "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}, " +
                "translationSelected=${PreferencesMonitor.isTranslationSelected()}, " +
                "hasOnlinePronunciation=${nativeOnlineTranslationStore.hasPronunciation(songId)}",
        )
        val tracks = appleNativeSupplementTracks(
            pronunciationSelected = PreferencesMonitor.isPronunciationSelected(),
            translationSelected = PreferencesMonitor.isTranslationSelected(),
        )
        if (AppleNativeSupplementTrack.TRANSLATION in tracks) {
            applyAppleNativeTranslationSelection(songNative)
        }
        if (AppleNativeSupplementTrack.PRONUNCIATION in tracks) {
            applyAppleNativePronunciationSelection(songNative)
        }
    }

    private fun applyAppleNativePronunciationSelection(songNative: Any) {
        val officialLanguages = nativeVectorStrings(
            runCatching {
                lyricsNativeCall(
                    songNative,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
                )
            }.getOrNull()
        ).filter(RomanizationPolicy::isLatinLanguageTag)
        val songId = nativeSongId(songNative)
        rememberApplePronunciationLanguages(songNative, officialLanguages)
        if (
            shouldHideMandarinPronunciation(
                songId = songId,
                pronunciationLanguages = officialLanguages,
            )
        ) {
            ProviderLogger.debug(
                "Apple 歌词发音轨道已隐藏：id=$songId, languages=$officialLanguages"
            )
            return
        }
        val language = officialLanguages
            .firstOrNull()
            ?.takeIf { hasValidOfficialRomanization(songNative) }
            ?: thirdPartyPronunciationFallbackLanguage()
            ?: return
        val selected = runCatching {
            lyricsNativeCall(
                songNative,
                AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD,
                language,
            ) as? Boolean
        }.onFailure {
            ProviderLogger.error("Apple 歌词发音轨道选择失败：language=$language", it)
        }.getOrNull()
        reportApplePronunciationRuntimeDiagnostic(
            stage = "pronunciation_selection_applied",
            songId = songId,
            details = "language=$language, officialLanguages=$officialLanguages, " +
                "selected=$selected, preference=${PreferencesMonitor.isPronunciationSelected()}",
        )
        ProviderLogger.debug(
            "Apple 歌词发音轨道选择：language=$language, " +
                "official=${officialLanguages.isNotEmpty()}, selected=$selected"
        )
    }

    private fun applyAppleNativeTranslationSelection(songNative: Any) {
        val systemLanguage = currentSystemLyricsLanguage() ?: return
        val officialLanguages = nativeVectorStrings(
            runCatching {
                lyricsNativeCall(
                    songNative,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD,
                )
            }.getOrNull()
        )
        val officialLanguage = selectAppleLyricsTranslationLanguage(
            systemLanguage = systemLanguage,
            availableLanguages = officialLanguages,
        )
        val language = officialLanguage ?: systemLanguage
        val selected = runCatching {
            appleOfficialTranslationProbeGuard.run {
            lyricsNativeCall(
                songNative,
                AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD,
                language,
            ) as? Boolean
            }
        }.onFailure {
            ProviderLogger.error("Apple 歌词翻译轨道选择失败：language=$language", it)
        }.getOrNull()
        ProviderLogger.debug(
            "Apple 歌词翻译轨道选择：systemLanguage=$systemLanguage, " +
                "officialLanguages=$officialLanguages, language=$language, " +
                "official=${officialLanguage != null}, selected=$selected"
        )
    }

    fun refreshAppleLyricsBlurEffect() = blurHooks.refreshAppleLyricsBlurEffect()

    fun refreshAppleSystemFontWeight() = systemFontHooks.refreshAppleSystemFontWeight()

    private fun scheduleAppleLyricsBlur(
        recyclerView: Any?,
        delayMs: Long = 0L,
    ) = blurHooks.scheduleAppleLyricsBlur(recyclerView, delayMs)

    private fun appleLyricsCurrentPlaybackPositionMs(): Long? =
        playbackHooks().currentPositionMs()

    private fun refreshAppleLyricsRecyclerView(
        fragment: Any,
        expectedSongId: String?,
        expectedRevision: Long?,
    ) {
        if (BuildConfig.DEBUG) {
            ProviderLogger.debug(
                "[LyricsScrollDiag] refreshAppleLyricsRecyclerView: expectedSongId=$expectedSongId, expectedRevision=$expectedRevision"
            )
        }
        val recyclerView = resolveAppleLyricsRecyclerView(fragment) ?: run {
            logAppleLyricsUiState(
                fragment = fragment,
                stage = "refresh_resolve_failed",
                expectedSongId = expectedSongId,
                expectedRevision = expectedRevision,
            )
            ProviderLogger.debug(
                "Apple Music 歌词 RecyclerView 解析失败: fragment=${fragment.javaClass.name}"
            )
            return
        }
        logAppleLyricsUiState(
            fragment = fragment,
            stage = "refresh_resolved",
            expectedSongId = expectedSongId,
            expectedRevision = expectedRevision,
        )

        fun isRefreshCurrent(): Boolean =
            expectedRevision == null ||
                nativeOnlineTranslationStore.isCurrentRevision(
                    songId = expectedSongId,
                    revision = expectedRevision,
                )

        val recyclerViewAsView = recyclerView as? View ?: return

        fun isComputingLayout(): Boolean =
            runCatching {
                AppleReflection.call(recyclerView, "isComputingLayout") as? Boolean
            }.getOrNull() == true

        fun rebindAllRows(stage: String) {
            if (!isRefreshCurrent()) return
            if (isComputingLayout()) {
                recyclerViewAsView.postOnAnimation { rebindAllRows(stage) }
                return
            }
            val adapter = appleRecyclerAdapter(recyclerView) ?: return
            val itemCount = appleRecyclerAdapterItemCount(adapter)
            if (itemCount <= 0) return
            if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "[LyricsScrollDiag] rebindAllRows: stage=$stage, expectedSongId=$expectedSongId, itemCount=$itemCount"
                )
            }
            runCatching {
                appleRecyclerNotifyDataSetChanged(adapter)
            }.onFailure {
                ProviderLogger.error(
                    "Apple Music 歌词列表完整重绑失败: stage=$stage",
                    it,
                )
            }
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 歌词列表已请求完整重绑: " +
                        "id=$expectedSongId, revision=${expectedRevision ?: "none"}, " +
                        "stage=$stage, adapter=${adapter.javaClass.name}, " +
                        "itemCount=$itemCount"
                )
            }
        }

        // Apple Music's karaoke adapter appends translation child views when it handles
        // PAYLOAD_TOGGLE_TRANSLATION. A scroll fixes duplicate rows because the normal full bind
        // clears and rebuilds the holder. Request the same full-bind path for all rows instead of
        // sending the partial translation payload or directly binding foreign-ClassLoader holders.
        recyclerViewAsView.postOnAnimation {
            rebindAllRows("next_frame")
        }
    }

    private fun refreshVisibleAppleLyricsRows(
        recyclerView: Any,
        songId: String,
        retryAfterLayout: Boolean = true,
    ) {
        if (BuildConfig.DEBUG) {
            ProviderLogger.debug(
                "[LyricsScrollDiag] refreshVisibleAppleLyricsRows: songId=$songId, retryAfterLayout=$retryAfterLayout"
            )
        }
        val recyclerViewAsView = recyclerView as? View ?: return
        val computingLayout = runCatching {
            AppleReflection.call(recyclerView, "isComputingLayout") as? Boolean
        }.getOrNull() == true
        if (computingLayout) {
            if (retryAfterLayout) {
                recyclerViewAsView.postOnAnimation {
                    refreshVisibleAppleLyricsRows(
                        recyclerView = recyclerView,
                        songId = songId,
                        retryAfterLayout = false,
                    )
                }
            }
            return
        }
        val adapter = appleRecyclerAdapter(recyclerView) ?: return
        val positions = (recyclerView as? ViewGroup)
            ?.let { group ->
                (0 until group.childCount).mapNotNull { index ->
                    val child = group.getChildAt(index)
                    blurHooks.appleLyricsChildAdapterPosition(recyclerView, child)
                        .takeIf { it >= 0 }
                }
            }
            .orEmpty()
        val range = visibleAdapterRange(positions)
            ?: runCatching {
                val layoutManager = AppleReflection.call(recyclerView, "getLayoutManager")
                val first = (layoutManager?.let {
                    AppleReflection.call(it, "findFirstVisibleItemPosition")
                } as? Number)?.toInt() ?: return@runCatching null
                val childCount = (recyclerView as? ViewGroup)?.childCount ?: return@runCatching null
                visibleAdapterRange(first until (first + childCount))
            }.getOrNull()
            ?: return
        val count = range.last - range.first + 1
        runCatching {
            AppleReflection.call(adapter, "notifyItemRangeChanged", range.first, count)
        }.recoverCatching {
            range.forEach { position ->
                AppleReflection.call(adapter, "notifyItemChanged", position)
            }
        }.onFailure {
            ProviderLogger.error(
                "Apple Music 补充歌词翻译可见行重绑失败: id=$songId, range=$range",
                it,
            )
        }
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "supplement_translation_visible_rebind",
            units = count.toLong(),
            details = "range=$range,adapter=${adapter.javaClass.name}",
        )
    }

    private fun logAppleLyricsUiState(
        fragment: Any,
        stage: String,
        expectedSongId: String? = null,
        expectedRevision: Long? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val bindingRead = runCatching {
            lyricsUiField(fragment, AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD)
        }
        val binding = bindingRead.getOrNull()
        val bindingRecyclerRead = binding?.let { currentBinding ->
            runCatching {
                lyricsUiField(
                    currentBinding,
                    AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD,
                )
            }
        }
        val bindingRecycler = bindingRecyclerRead
            ?.getOrNull()
            ?.takeIf(::isAppleRecyclerViewInstance)
        val fragmentAdapterRead = runCatching {
            lyricsUiField(fragment, AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD)
        }
        val fragmentAdapter = fragmentAdapterRead.getOrNull()
        val lyricsPointer = appleLyricsSongPointerRef?.get()
        val lyricsNative = lyricsPointer?.let { pointer ->
            runCatching {
                lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
            }.getOrNull()
        }
        val fragmentRoot = runCatching {
            AppleReflection.call(fragment, "getView") as? View
        }.getOrNull()
        val lifecycle = listOf("isAdded", "isVisible", "isResumed").joinToString(",") { name ->
            "$name=${runCatching { AppleReflection.call(fragment, name) }.getOrNull()}"
        }
        val currentSongId = currentAppleLyricsSongId
        ProviderLogger.diagnostic(
            "Apple lyrics UI state: stage=$stage, " +
                "fragment=${debugAppleLyricsValue(fragment)}, lifecycle=[$lifecycle], " +
                "root=${debugAppleLyricsValue(fragmentRoot)}, " +
                "i0=${debugAppleLyricsRead(bindingRead)}, " +
                "i0.a0=${debugAppleLyricsRead(bindingRecyclerRead)}, " +
                "k0=${debugAppleLyricsRead(fragmentAdapterRead)}, " +
                "k0State=${debugAppleLyricsAdapterState(fragmentAdapter)}, " +
                "viewModelState=${debugAppleLyricsViewModelState(fragment)}, " +
                "songPointer=${debugAppleNativePointer(lyricsPointer)}, " +
                "songNative=${debugAppleNativePointer(lyricsNative)}, " +
                "songState=${debugApplePronunciationSongState(lyricsNative)}, " +
                "fragmentRecyclerFields=${debugAppleLyricsRecyclerFields(fragment)}, " +
                "bindingRecyclerFields=${debugAppleLyricsRecyclerFields(binding)}, " +
                "resolvedRecycler=${bindingRecycler?.let(::debugRecyclerViewSnapshot)}, " +
                "expectedSongId=$expectedSongId, currentSongId=$currentSongId, " +
                "expectedRevision=${expectedRevision ?: "none"}, " +
                "storeRevision=${nativeOnlineTranslationStore.revision()}, " +
                "hasTranslation=${nativeOnlineTranslationStore.hasTranslation(currentSongId)}, " +
                "hasPronunciation=${nativeOnlineTranslationStore.hasPronunciation(currentSongId)}, " +
                "translationSelected=${PreferencesMonitor.isTranslationSelected()}, " +
                "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}"
        )
    }

    private fun debugAppleLyricsRead(result: Result<Any?>?): String = when {
        result == null -> "not_read"
        result.isFailure -> {
            val throwable = result.exceptionOrNull()
            "error:${throwable?.javaClass?.simpleName}:${throwable?.message}"
        }
        else -> debugAppleLyricsValue(result.getOrNull())
    }

    fun debugAppleLyricsValue(value: Any?): String = when (value) {
        null -> "null"
        else -> when {
            isAppleRecyclerViewInstance(value) -> debugRecyclerViewSnapshot(value)
            value is View -> {
                val idName = runCatching {
                    value.resources.getResourceName(value.id)
                }.getOrNull()
                "${value.javaClass.name}@${System.identityHashCode(value)}" +
                    "[id=$idName,attached=${value.isAttachedToWindow}," +
                    "shown=${value.isShown},visibility=${value.visibility}]"
            }
            else -> "${value.javaClass.name}@${System.identityHashCode(value)}"
        }
    }

    private fun debugAppleBooleanField(
        instance: Any?,
        member: AppleMusicRuntimeMember,
    ): Boolean? =
        instance?.let { value ->
            runCatching {
                AppleReflection.field(value, blurHooks.lyricsAdapterMember(value, member)) as? Boolean
            }.getOrNull()
        }

    private fun debugAppleLyricsAdapterState(adapter: Any?): String {
        if (adapter == null) return "null"
        val itemCount = runCatching {
            (AppleReflection.call(
                adapter,
                blurHooks.lyricsAdapterMember(
                    adapter,
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD,
                ),
            ) as? Number)?.toInt()
        }.recoverCatching {
            (AppleReflection.call(adapter, "getItemCount") as? Number)?.toInt()
        }.getOrNull()
        return "${adapter.javaClass.name}@${System.identityHashCode(adapter)}" +
            "[translation=${debugAppleBooleanField(
                adapter,
                AppleMusicRuntimeMember.LYRICS_ADAPTER_TRANSLATION_SELECTED_FIELD,
            )}," +
            "pronunciation=${debugAppleBooleanField(
                adapter,
                AppleMusicRuntimeMember.LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD,
            )}," +
            "itemCount=$itemCount]"
    }

    private fun debugAppleLyricsViewModelState(fragment: Any): String {
        val viewModel = runCatching {
            lyricsUiField(fragment, AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD)
        }.getOrNull()
            ?: return "null"
        fun liveValue(member: AppleMusicRuntimeMember): Any? = runCatching {
            val liveData = AppleReflection.call(viewModel, lyricsUiMember(member))
                ?: return@runCatching null
            AppleReflection.call(liveData, "getValue")
        }.getOrNull()
        return "${viewModel.javaClass.name}@${System.identityHashCode(viewModel)}" +
            "[pronunciationSelected=${liveValue(
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_PRONUNCIATION_SELECTED_GETTER
            )}," +
            "pronunciationAvailable=${liveValue(
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_PRONUNCIATION_AVAILABLE_GETTER
            )}," +
            "translationSelected=${liveValue(
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_TRANSLATION_SELECTED_GETTER
            )}," +
            "translationAvailable=${liveValue(
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_TRANSLATION_AVAILABLE_GETTER
            )}]"
    }

    private fun debugAppleNativePointer(value: Any?): String {
        if (value == null) return "null"
        val address = runCatching {
            (lyricsNativeCall(
                value,
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_ADDRESS_METHOD,
            ) as? Number)?.toLong()
        }.getOrNull()
        return "${value.javaClass.name}@${System.identityHashCode(value)}[address=$address]"
    }

    private fun debugApplePronunciationSongState(songNative: Any?): String {
        songNative ?: return "null"
        val languages = nativePronunciationLanguages(songNative)
        val sections = runCatching {
            lyricsNativeCall(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD)
        }
            .getOrNull()
        val lines = nativeVectorItems(sections, limit = 8).flatMap { section ->
            val lineVector = runCatching {
                lyricsNativeCall(section, AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD)
            }
                .getOrNull()
            nativeVectorItems(lineVector, limit = 64)
        }
        val textLines = AppleLyricTextTransform.withRawReads {
            lines.count { line ->
                runCatching {
                    AppleNativeOnlineTranslationStore.sanitizeContent(
                        lyricsNativeCall(
                            line,
                            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
                        ) as? String
                    ) != null
                }.getOrDefault(false)
            }
        }
        val wordLines = AppleLyricTextTransform.withRawReads {
            lines.count { line ->
                runCatching {
                    nativeVectorSize(
                        lyricsNativeCall(
                            line,
                            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD,
                        )
                    ) > 0
                }.getOrDefault(false)
            }
        }
        return "[id=${nativeSongId(songNative)},languages=$languages," +
            "lines=${lines.size},textLines=$textLines,wordLines=$wordLines]"
    }

    private fun logApplePronunciationModelState(
        stage: String,
        viewModel: Any?,
        pointer: Any?,
        songNative: Any?,
    ) {
        if (!BuildConfig.DEBUG) return
        ProviderLogger.diagnostic(
            "Apple pronunciation model: stage=$stage, " +
                "viewModel=${viewModel?.let(::debugAppleLyricsValue)}, " +
                "pointer=${debugAppleNativePointer(pointer)}, " +
                "native=${debugAppleNativePointer(songNative)}, " +
                "state=${debugApplePronunciationSongState(songNative)}, " +
                "currentSongId=$currentAppleLyricsSongId"
        )
    }

    private fun debugAppleLyricsRecyclerFields(instance: Any?): String {
        if (instance == null) return "none"
        return generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filter { isAppleRecyclerViewClass(it.type) }
            .take(8)
            .map { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }
                "${field.declaringClass.simpleName}.${field.name}=${debugAppleLyricsRead(value)}"
            }
            .joinToString(prefix = "[", postfix = "]")
            .ifEmpty { "none" }
    }

    private fun logAppleLyricsRecyclerLifecycle(
        recyclerView: Any,
        stage: String,
    ) {
        if (!BuildConfig.DEBUG || !isAppleLyricsRecyclerView(recyclerView)) return
        val songId = currentAppleLyricsSongId
        ProviderLogger.diagnostic(
            "Apple lyrics Recycler lifecycle: stage=$stage, " +
                "snapshot=${debugRecyclerViewSnapshot(recyclerView)}, " +
                "currentSongId=$songId, " +
                "storeRevision=${nativeOnlineTranslationStore.revision()}, " +
                "hasTranslation=${nativeOnlineTranslationStore.hasTranslation(songId)}, " +
                "hasPronunciation=${nativeOnlineTranslationStore.hasPronunciation(songId)}"
        )
    }

    private fun isAppleLyricsRecyclerView(recyclerView: Any): Boolean =
        blurHooks.isAppleLyricsRecyclerView(recyclerView)

    private fun resolveAppleLyricsRecyclerView(fragment: Any): Any? =
        blurHooks.resolveAppleLyricsRecyclerView(fragment)

    private fun isAppleRecyclerViewInstance(value: Any): Boolean =
        blurHooks.isAppleRecyclerViewInstance(value)

    private fun isAppleRecyclerViewClass(clazz: Class<*>): Boolean =
        blurHooks.isAppleRecyclerViewClass(clazz)

    private fun appleRecyclerAdapter(recyclerView: Any): Any? =
        blurHooks.appleRecyclerAdapter(recyclerView)

    private fun appleRecyclerAdapterItemCount(adapter: Any): Int =
        blurHooks.appleRecyclerAdapterItemCount(adapter)

    private fun appleRecyclerNotifyDataSetChanged(adapter: Any) =
        blurHooks.appleRecyclerNotifyDataSetChanged(adapter)

    private fun onlineTranslationForNativeLine(line: Any?): String? {
        if (line == null) return null
        val begin = (
            lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number
            )?.toLong()
            ?: return null
        val end = (
            lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD) as? Number
            )?.toLong()
            ?: return null
        val text = AppleLyricTextTransform.withRawReads {
            lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
                as? String
        }
        return nativeOnlineTranslationStore.translation(
            songId = currentAppleLyricsSongId,
            begin = begin,
            end = end,
            text = text,
        ) ?: missingLyricsSupplement().translationForLine(
            songId = currentAppleLyricsSongId,
            begin = begin,
            end = end,
            text = text,
        )
    }

    private fun hasAnyOnlineTranslation(songId: String?): Boolean =
        (isNativeOnlineTranslationEnabled() && nativeOnlineTranslationStore.hasTranslation(songId)) ||
            missingLyricsSupplement().hasTranslation(songId)

    private fun onlinePronunciationForNativeLine(line: Any?): String? {
        if (line == null || !isNativeOnlineTranslationEnabled()) return null
        if (shouldHideMandarinPronunciation()) return null
        val begin = (
            lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number
            )?.toLong()
            ?: return null
        val end = (
            lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD) as? Number
            )?.toLong()
            ?: return null
        val text = AppleLyricTextTransform.withRawReads {
            lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
                as? String
        }
        return RomanizationPolicy.sanitize(
            originalText = text,
            pronunciation = nativeOnlineTranslationStore.pronunciation(
                songId = currentAppleLyricsSongId,
                begin = begin,
                end = end,
                text = text,
            ),
        )
    }

    private fun nativeOriginalLineText(line: Any?): String? {
        if (line == null) return null
        return nativeRawLineText(
            line,
            lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD),
        )
    }

    private fun nativeOriginalBackgroundLineText(line: Any?): String? {
        if (line == null) return null
        return nativeRawLineText(
            line,
            lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD),
        )
    }

    private fun nativeRawLineText(line: Any, getter: String): String? =
        AppleLyricTextTransform.withRawReads {
            runCatching { AppleReflection.call(line, getter) as? String }.getOrNull()
        }

    private fun nativeRawWordVectorText(vector: Any?): String? =
        AppleLyricTextTransform.withRawReads {
            nativeVectorItems(vector, limit = 256)
                .joinToString(separator = "") { word ->
                    runCatching {
                        lyricsNativeCall(word, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
                            as? String
                    }.getOrNull().orEmpty()
                }
                .trim()
                .takeIf(String::isNotEmpty)
        }

    private fun nativeRenderableWordBegins(vector: Any?): List<Int> =
        AppleLyricTextTransform.withRawReads {
            nativeVectorItems(vector, limit = 256).mapNotNull { word ->
                val isWhitespace = runCatching {
                    lyricsNativeCall(
                        word,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_WHITESPACE_METHOD,
                    ) as? Boolean
                }.getOrNull() == true
                val text = runCatching {
                    lyricsNativeCall(word, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
                        as? String
                }.getOrNull()?.trim().orEmpty()
                val begin = runCatching {
                    (lyricsNativeCall(
                        word,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD,
                    ) as? Number)?.toInt()
                }.getOrNull()
                begin?.takeIf { !isWhitespace && text.isNotEmpty() && it >= 0 }
            }
        }

    /**
     * 创建空发音向量，用于彻底隐藏发音或在主句没有原生 word 时安全降级。
     * 该路径只创建容器，绝不创建缺少父 LyricsLine 的 LyricsWord。
     */
    private fun emptyApplePronunciationWords(
        originalVector: Any?,
        mainWords: Any?,
    ): Any? {
        val vectorClass = originalVector?.javaClass ?: mainWords?.javaClass ?: return null
        return runCatching {
            AppleReflection.newInstance(vectorClass)
        }.onFailure {
            ProviderLogger.error("Apple Music 空发音向量构建失败", it)
        }.getOrNull()
    }

    /** 登记一次性的发音渲染计划；对应 vector 被 Apple 消费后立即移除。 */
    private fun registerApplePronunciationRenderPlan(
        vector: Any,
        pronunciation: String,
    ) {
        synchronized(pendingApplePronunciationRenderPlans) {
            if (pendingApplePronunciationRenderPlans.size >= 256) {
                pendingApplePronunciationRenderPlans.clear()
            }
            pendingApplePronunciationRenderPlans[vector] =
                ApplePronunciationRenderPlan(pronunciation)
        }
    }

    private fun consumeApplePronunciationRenderPlan(vector: Any): ApplePronunciationRenderPlan? =
        synchronized(pendingApplePronunciationRenderPlans) {
            pendingApplePronunciationRenderPlans.remove(vector)
        }

    fun clearPendingApplePronunciationRenderPlans() {
        synchronized(pendingApplePronunciationRenderPlans) {
            pendingApplePronunciationRenderPlans.clear()
        }
    }

    /**
     * 为 Apple 主句原生 word 生成仅在当前渲染栈生效的发音文本映射。
     * 发音片段沿用每个主句 word 的原生时间，不创建额外 native 对象。
     */
    private fun buildApplePronunciationWordRenderContext(
        vector: Any,
        plan: ApplePronunciationRenderPlan,
    ): ApplePronunciationWordRenderContext? {
        val words = nativeVectorItems(vector, limit = 256)
        val contentWords = words.filterNot { word ->
            runCatching {
                lyricsNativeCall(
                    word,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_WHITESPACE_METHOD,
                ) as? Boolean
            }.getOrNull() == true
        }
        val mainWordTexts = AppleLyricTextTransform.withRawReads {
            contentWords.map { word ->
                runCatching {
                    lyricsNativeCall(word, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
                        as? String
                }.getOrNull().orEmpty()
            }
        }
        val segments = ApplePronunciationPolicy.displaySegments(
            pronunciation = plan.pronunciation,
            mainWordTexts = mainWordTexts,
        )
        if (segments.isEmpty()) return null
        reportApplePronunciationRuntimeDiagnostic(
            stage = "word_segments_mapped",
            details = "mainWords=${mainWordTexts.joinToString(prefix = "[", postfix = "]")}, " +
                "segments=${segments.joinToString(prefix = "[", postfix = "]")}",
            dedupeKey = "word_segments_mapped:${mainWordTexts.joinToString("|")}:" +
                plan.pronunciation,
        )

        val lastVisibleSegment = segments.indexOfLast(String::isNotEmpty)
        val displayTextByWord = LinkedHashMap<ApplePronunciationWordKey, String>(words.size)
        words.forEach { word ->
            lyricsWordKey(word)?.let { key -> displayTextByWord[key] = "" }
        }
        contentWords.forEachIndexed { index, word ->
            val key = lyricsWordKey(word) ?: return@forEachIndexed
            val segment = segments[index]
            displayTextByWord[key] = when {
                segment.isEmpty() -> ""
                index < lastVisibleSegment -> "$segment "
                else -> segment
            }
        }
        return ApplePronunciationWordRenderContext(
            displayTextByWord = displayTextByWord,
            wordIdMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_WORD_ID_METHOD),
            beginMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD),
            endMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD),
        )
    }

    private fun lyricsWordKey(word: Any?): ApplePronunciationWordKey? = applePronunciationWordKey(
        word = word,
        wordIdMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_WORD_ID_METHOD),
        beginMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD),
        endMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD),
    )

    private fun hasValidOfficialRomanization(songNative: Any?): Boolean {
        if (songNative == null) return false
        val pronunciationLanguages = nativePronunciationLanguages(songNative)
        rememberApplePronunciationLanguages(songNative, pronunciationLanguages)
        if (
            shouldHideMandarinPronunciation(
                songId = nativeSongId(songNative),
                pronunciationLanguages = pronunciationLanguages,
            )
        ) return false
        val sections = runCatching {
            lyricsNativeCall(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD)
        }
            .getOrNull() ?: return false
        return nativeVectorItems(sections, limit = 8).any { section ->
            val lines = runCatching {
                lyricsNativeCall(section, AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD)
            }
                .getOrNull()
            nativeVectorItems(lines, limit = 64).any { line ->
                val pronunciation = AppleLyricTextTransform.withRawReads {
                    runCatching {
                        lyricsNativeCall(
                            line,
                            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
                        ) as? String
                    }.getOrNull()
                }
                RomanizationPolicy.sanitize(
                    originalText = nativeOriginalLineText(line),
                    pronunciation = pronunciation,
                ) != null
            }
        }
    }

    private fun nativeSongId(songNative: Any?): String? = songNative?.let { song ->
        runCatching {
            lyricsNativeCall(song, AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD)?.toString()
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun nativePronunciationLanguages(songNative: Any?): List<String> =
        nativeVectorStrings(
            songNative?.let { song ->
                runCatching {
                    lyricsNativeCall(
                        song,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
                    )
                }.getOrNull()
            }
        )

    private fun rememberApplePronunciationLanguages(
        songNative: Any?,
        pronunciationLanguages: Collection<String> = nativePronunciationLanguages(songNative),
    ) {
        val songId = nativeSongId(songNative) ?: return
        val normalized = pronunciationLanguages
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (normalized.isNotEmpty()) {
            applePronunciationLanguagesBySongId[songId] = normalized
        }
    }

    private fun nativeVectorItems(vector: Any?, limit: Int): List<Any> {
        vector ?: return emptyList()
        val size = nativeVectorSize(vector)
        return buildList {
            repeat(minOf(size, limit)) { index ->
                val pointer = runCatching {
                    lyricsNativeCall(
                        vector,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                        index.toLong(),
                    )
                }.recoverCatching {
                    lyricsNativeCall(
                        vector,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                        index,
                    )
                }.getOrNull() ?: return@repeat
                val value = runCatching {
                    lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
                }.getOrNull() ?: pointer
                add(value)
            }
        }
    }

    private fun nativeVectorStrings(vector: Any?, limit: Int = 16): List<String> {
        vector ?: return emptyList()
        val size = minOf(nativeVectorSize(vector), limit)
        return buildList {
            repeat(size) { index ->
                val value = runCatching {
                    lyricsNativeCall(
                        vector,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                        index.toLong(),
                    ) as? String
                }.recoverCatching {
                    lyricsNativeCall(
                        vector,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                        index,
                    ) as? String
                }.getOrNull()?.takeIf(String::isNotBlank)
                if (value != null) add(value)
            }
        }
    }

    private fun nativeVectorSize(vector: Any?): Int = vector?.let {
        runCatching {
            (lyricsNativeCall(
                it,
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD,
            ) as? Number)?.toInt()
        }.getOrNull()?.coerceAtLeast(0)
    } ?: 0

    fun hookLyricBuildMethod() {
        val load = hookResolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD)
        lyricsRuntimeTarget = load.target
        appleLyricsLoadMethod = load.method
        hookRegistrar.installHook(load.method, before = { chain ->
            val item = chain.args.firstOrNull() ?: return@installHook
            val source = if (lyricRequester().ownsViewModel(chain.thisObject)) "module" else "apple"
            val loadedSongId = runCatching {
                AppleReflection.call(
                    item,
                    lyricsSongMember(AppleMusicRuntimeMember.LYRICS_SONG_ID_METHOD),
                ).toString()
            }.getOrNull()
            if (source == "apple") {
                val visibleSongId = currentAppleLyricsSongId
                val queueSongId = currentPlaybackQueueMediaId()
                if (loadedSongId != null && visibleSongId != null && loadedSongId != visibleSongId) {
                    stopSupplementActiveLineUpdate()
                    clearPendingApplePronunciationRenderPlans()
                    clearPendingAppleLyricsScrollRestore()
                    appleLyricsSongPointerRef = null
                    appleLyricsScrollSnapshot = null
                    appleLyricsScrollSnapshotSongId = null
                    currentAppleLyricsSongId = loadedSongId
                }
                missingLyricsSupplement().onNativeLyricsRequestStarted(loadedSongId)
                chain.thisObject?.let { viewModel ->
                    appleLyricsViewModelRef = WeakReference(viewModel)
                    loadedSongId?.let { songId ->
                        observePlayerLyricsViewModelResult(viewModel, songId)
                    }
                }
                appleLyricsItemRef = WeakReference(item)
                missingLyricsSupplement().onLyricsItem(item)
            } else {
                // 旧版缓存没有 lyricsSource；若模块请求后 Apple 原生链仍未产出，延迟迁移为补充歌词。
                loadedSongId?.takeIf(String::isNotBlank)?.let { songId ->
                    scheduleLegacyModuleCachePromotion(songId)
                }
            }
            loadedSongId?.let { requestId ->
                recordLyricsRequestSource(requestId, source)
            }
            val queueId = runCatching {
                AppleReflection.call(
                    item,
                    lyricsSongMember(AppleMusicRuntimeMember.LYRICS_SONG_QUEUE_ID_METHOD),
                )
            }.getOrNull()
            val language = runCatching {
                chain.thisObject?.let {
                    lyricsNativeCall(
                        it,
                        AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD,
                    )
                }
            }.getOrNull()
            ProviderLogger.debug(
                "loadLyrics：source=$source, id=$loadedSongId, queueId=$queueId, language=$language"
            )
        })

        val buildMethod = hookResolver.resolveMethod(
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD
        ).method
        hookRegistrar.installHook(
            buildMethod,
            before = { chain ->
                val pointer = chain.args.firstOrNull() ?: return@installHook
                val songNative = lyricsNativeCall(
                    pointer,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
                ) ?: return@installHook
                ensureAppleLyricTextHooks(songNative)
                applyAppleNativeSupplementSelection(songNative)
                logApplePronunciationModelState(
                    stage = "build_before",
                    viewModel = chain.thisObject,
                    pointer = pointer,
                    songNative = songNative,
                )
            },
            after = { chain, _ ->
                val pointer = chain.args.firstOrNull() ?: return@installHook
                val songNative = lyricsNativeCall(
                    pointer,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
                )
                val source =
                    if (lyricRequester().ownsViewModel(chain.thisObject)) "module" else "apple"
                if (source == "apple") {
                    reportNativeLyricsState(
                        songNative = songNative,
                        songId = songNative?.let(::nativeSongId),
                        sourcePointer = pointer,
                    )
                }
                if (songNative == null) return@installHook

                val songId = nativeSongId(songNative)
                logApplePronunciationModelState(
                    stage = "build_after:$source",
                    viewModel = chain.thisObject,
                    pointer = pointer,
                    songNative = songNative,
                )
                val visibleSongId = currentAppleLyricsSongId
                    ?.takeIf { source == "apple" && it == songId }
                mainHandler.post {
                    PlaybackManager.onLyricsBuilt(
                        nativeSongObj = songNative,
                        source = source,
                        visibleSongId = visibleSongId,
                        playbackSongId = currentPlaybackQueueMediaId(),
                    )
                    if (
                        source == "apple" &&
                        !songId.isNullOrBlank() &&
                        songId == currentAppleLyricsSongId
                    ) {
                        onlineSourceMenuHooks().refreshActiveMenu(songId)
                    }
                }
                applyConfiguredContentUiLanguageCallback()
                val onlineTranslation = songId?.let(::hasAnyOnlineTranslation) == true
                val onlinePronunciation =
                    songId?.let(nativeOnlineTranslationStore::hasPronunciation) == true
                val officialPronunciation = source == "apple" &&
                    hasValidOfficialRomanization(songNative)
                // 补充歌词有自己的 requestMissingLyricsPresentationRefresh 收尾；
                // Apple 的 R2 轨道刷新会反复重绑 adapter 并引起歌词页抽搐。
                val supplementPointer =
                    missingLyricsSupplement().isSupplementPointer(pointer)
                if (
                    !supplementPointer &&
                    ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                        sourceIsApple = source == "apple",
                        hasValidOfficialPronunciation = officialPronunciation,
                        hasOnlineTranslation = onlineTranslation,
                        hasOnlinePronunciation = onlinePronunciation,
                        pronunciationSelected = PreferencesMonitor.isPronunciationSelected(),
                    )
                ) {
                    ProviderLogger.debug(
                        "Apple Music 歌词模型完成后请求补充轨道刷新: " +
                            "id=$songId, officialPronunciation=$officialPronunciation, " +
                            "onlineTranslation=$onlineTranslation, " +
                            "onlinePronunciation=$onlinePronunciation, " +
                            "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}"
                    )
                    refreshAppleLyricsSupplementPresentation(songId)
                }
            }
        )
        ProviderLogger.debug("歌词构建 Hook 已安装")
    }

    /** 延迟迁移旧缓存，避免抢在 Apple 原生歌词结果之前。 */
    private fun scheduleLegacyModuleCachePromotion(songId: String) {
        mainHandler.postDelayed({
            if (missingLyricsSupplement().hasKnownNativeLyricsFor(songId)) return@postDelayed
            PlaybackManager.promoteLegacyCachedLyricsAsMissingSupplement(songId)
        }, LEGACY_MODULE_PROMOTION_DELAY_MS)
    }

    fun hookAppleNativeLyricsPresentation() {
        appleLyricsResultPresentationMethod = hookResolver.resolveMethod(
            AppleMusicHookPoint.LYRICS_RESULT_PRESENTATION
        ).method
        appleLyricsResultPresentationMethod?.let { method ->
            installSourceSwitchPresentationPerformanceHook(
                method = method,
                stagePrefix = "lyrics_result_presentation",
            )
        }
        // 无歌词补充的原生模型可能先于 Apple 自身的原生呈现回调完成。若只等
        // PlayerLyricsViewFragment 的 N2/I2 呈现 Hook 来登记 Fragment，冷启动时
        // requestMissingLyricsPresentationRefresh 会因 fragmentRef=false 直接放弃，
        // 表现为歌词页一直加载、直到后台回前台触发下一次呈现。
        runCatching {
            val onCreateView = hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW
            ).method
            hookRegistrar.installHook(onCreateView, before = { chain ->
                val fragment = chain.thisObject ?: return@installHook
                if (appleLyricsFragmentRef?.get() !== fragment) {
                    appleLyricsFragmentRef = WeakReference(fragment)
                    ProviderLogger.debug(
                        "Apple Music 歌词页 Fragment 已提前登记: " +
                            "class=${fragment.javaClass.name}"
                    )
                }
                currentAppleLyricsSongId?.let { songId ->
                    ensureAppleLyricsScrollTracking(fragment, songId)
                }
            })
        }.onFailure {
            ProviderLogger.error("Apple Music 歌词页 Fragment 提前登记 Hook 安装失败", it)
        }
        runCatching {
            val onDestroyView = hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW
            ).method
            hookRegistrar.installHook(onDestroyView, after = { _, _ ->
                cleanupLyricsResultObserver()
            })
        }.onFailure {
            ProviderLogger.error("Apple Music 歌词页 Fragment 销毁 Hook 安装失败", it)
        }
        val method = hookResolver.resolveMethod(
            AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION
        ).method
        appleLyricsPresentationMethod = method
        installSourceSwitchPresentationPerformanceHook(
            method = method,
            stagePrefix = "lyrics_native_presentation",
        )
        hookRegistrar.installHook(
            method,
            before = { chain ->
                val fragment = chain.thisObject ?: return@installHook
                appleLyricsFragmentRef = WeakReference(fragment)
                val pointer = chain.args.firstOrNull()
                if (pointer != null) {
                    appleLyricsSongPointerRef = WeakReference(pointer)
                }
                val songNative = runCatching {
                    lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
                }.getOrNull()
                val songId = songNative?.let(::nativeSongId)
                reportNativeLyricsState(
                    songNative = songNative,
                    songId = songId,
                    sourcePointer = pointer,
                )
                if (songNative == null) {
                    appleLyricsSongPointerRef = null
                    stopSupplementActiveLineUpdate()
                    val queueSongId = currentPlaybackQueueMediaId()
                    if (currentAppleLyricsSongId != queueSongId) {
                        clearPendingApplePronunciationRenderPlans()
                        clearPendingAppleLyricsScrollRestore()
                        currentAppleLyricsSongId = queueSongId
                        appleLyricsScrollSnapshot = null
                        appleLyricsScrollSnapshotSongId = null
                    }
                    return@installHook
                }
                if (currentAppleLyricsSongId != songId) {
                    clearPendingApplePronunciationRenderPlans()
                    clearPendingAppleLyricsScrollRestore()
                    currentAppleLyricsSongId = songId
                    appleLyricsScrollSnapshot = null
                    appleLyricsScrollSnapshotSongId = null
                }
                ensureAppleLyricTextHooks(songNative)
                appleLyricsPresentationInFlight = true
                songId?.let { ensureAppleLyricsScrollTracking(fragment, it) }
                logAppleLyricsUiState(
                    fragment = fragment,
                    stage = "presentation_before",
                    expectedSongId = songId,
                )
            },
            after = { chain, _ ->
                val fragment = chain.thisObject ?: run {
                    appleLyricsPresentationInFlight = false
                    return@installHook
                }
                // 保持 presentationInFlight 直到布局后滚动恢复完成。Apple 在原生
                // 呈现期间会先把第一行临时置为 0；过早清除该标志会让这个临时顶部
                // 覆盖掉切源前保存的当前句位置。
                val pointer = chain.args.firstOrNull() ?: run {
                    appleLyricsPresentationInFlight = false
                    appleLyricsSongPointerRef = null
                    stopSupplementActiveLineUpdate()
                    val queueSongId = currentPlaybackQueueMediaId()
                    if (currentAppleLyricsSongId != queueSongId) {
                        clearPendingApplePronunciationRenderPlans()
                        clearPendingAppleLyricsScrollRestore()
                        currentAppleLyricsSongId = queueSongId
                        appleLyricsScrollSnapshot = null
                        appleLyricsScrollSnapshotSongId = null
                    }
                    return@installHook
                }
                val songNative = runCatching {
                    lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
                }.getOrNull() ?: run {
                    appleLyricsPresentationInFlight = false
                    return@installHook
                }
                val songId = nativeSongId(songNative) ?: run {
                    appleLyricsPresentationInFlight = false
                    return@installHook
                }
                if (currentAppleLyricsSongId != songId) {
                    clearPendingApplePronunciationRenderPlans()
                    clearPendingAppleLyricsScrollRestore()
                    currentAppleLyricsSongId = songId
                    appleLyricsScrollSnapshot = null
                    appleLyricsScrollSnapshotSongId = null
                }
                ensureAppleLyricsScrollTracking(fragment, songId)
                logAppleLyricsUiState(
                    fragment = fragment,
                    stage = "presentation_after",
                    expectedSongId = songId,
                )
                if (missingLyricsSupplement().isSupplementPointer(pointer)) {
                    dismissAppleLyricsLoadingOverlay(fragment)
                    ensureMissingLyricsTranslationButtonVisible(fragment)
                    scheduleSupplementActiveLineUpdate()
                }
                restoreAppleLyricsScrollSnapshot(fragment, songId)
                scheduleAppleLyricsBlur(resolveAppleLyricsRecyclerView(fragment))
                mainHandler.post {
                    PlaybackManager.onLyricsBuilt(
                        nativeSongObj = songNative,
                        source = "apple",
                        visibleSongId = songId,
                        playbackSongId = currentPlaybackQueueMediaId(),
                    )
                }
            },
        )
        ProviderLogger.debug("Apple Music 原生歌词呈现 Hook 已安装")
    }

    fun hookAppleSystemFontWeight() = systemFontHooks.hookAppleSystemFontWeight()

    fun hookAppleLyricsBlurEffect() = blurHooks.hookAppleLyricsBlurEffect()

    fun hookAppleLyricsBindingDiagnostics() = diagnostics.hookAppleLyricsBindingDiagnostics()

    fun hookAppleLyricsUiDiagnostics() = diagnostics.hookAppleLyricsUiDiagnostics()

    fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean =
        blurHooks.isAppleLyricsRecyclerAdapter(adapter)

    private fun debugTextSnapshot(root: View): String {
        if (!BuildConfig.DEBUG) return "disabled"
        val texts = mutableListOf<String>()
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 96 && texts.size < 12) {
            val view = pending.removeFirst()
            visited += 1
            if (view is TextView) {
                val text = view.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    texts += "${view.javaClass.simpleName}=${text.take(160)}"
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let(pending::addLast)
                }
            }
        }
        return texts.joinToString(prefix = "[", postfix = "]")
    }

    private fun debugViewDescription(view: View): String {
        val id = view.id
        val resourceName = if (id == View.NO_ID) {
            "no-id"
        } else {
            runCatching { view.resources.getResourceName(id) }
                .getOrElse { "0x${id.toString(16)}" }
        }
        return "${view.javaClass.name}@${System.identityHashCode(view)}" +
            "[id=$resourceName,shown=${view.isShown},attached=${view.isAttachedToWindow}," +
            "visibility=${view.visibility},alpha=${view.alpha}]"
    }

    private fun debugRecyclerViewSnapshot(recycler: Any): String {
        if (!BuildConfig.DEBUG) return "disabled"
        val view = recycler as? View ?: return "not_view"
        val scrollState = runCatching {
            AppleReflection.call(recycler, "getScrollState")
        }.getOrNull()
        val adapter = runCatching {
            AppleReflection.call(recycler, "getAdapter")
        }.getOrNull()
        val layoutManager = runCatching {
            AppleReflection.call(recycler, "getLayoutManager")
        }.getOrNull()
        val firstVisible = layoutManager?.let { manager ->
            runCatching {
                AppleReflection.call(manager, "findFirstVisibleItemPosition")
            }.getOrNull()
        }
        val child = (recycler as? ViewGroup)?.getChildAt(0)
        return "view=${debugViewDescription(view)}, state=$scrollState, " +
            "adapter=${adapter?.javaClass?.name}, layout=${layoutManager?.javaClass?.name}, " +
            "first=$firstVisible, childTop=${child?.top}, childCount=" +
            "${(recycler as? ViewGroup)?.childCount}"
    }
}
