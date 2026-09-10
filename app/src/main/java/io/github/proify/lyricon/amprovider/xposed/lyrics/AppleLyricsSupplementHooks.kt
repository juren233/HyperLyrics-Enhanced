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

internal class AppleLyricsSupplementHooks(
    internal val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> SharedPreferences?,
    internal val playbackHooks: () -> ApplePlaybackHooks,
    internal val onlineSourceMenuHooks: () -> AppleOnlineSourceMenuHooks,
    internal val lyricRequester: () -> LyricRequester,
    internal val catalogResolver: () -> AppleInternalCatalogResolver?,
    internal val applyConfiguredContentUiLanguageCallback: () -> Unit,
    internal val recordLyricsRequestSource: (String, String) -> Unit,
    internal val currentPlaybackQueueMediaId: () -> String?,
    internal val registeredPlaybackItems: (String) -> List<Any>,
    internal val registeredPlaybackItemId: (Any) -> String?,
    private val epoxyDataBindingFromHolderCallback: (Any?) -> Any?,
    internal val missingLyricsSupplement: () -> AppleMissingLyricsHooks,
) {
    internal companion object {
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

    internal val application: Application
        get() = runtime.application
    internal val classLoader: ClassLoader
        get() = runtime.classLoader
    internal val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    internal val hookRegistrar
        get() = runtime.hookRegistrar
    internal val mainHandler: Handler
        get() = runtime.mainHandler
    internal val contentUiLanguagePrefs: SharedPreferences?
        get() = preferences()
    internal val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }

    internal val lyricDisplayTextHookedMethods = ConcurrentHashMap.newKeySet<Executable>()
    internal val nativeOnlineTranslationHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    internal val applePronunciationRenderHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    internal val appleOfficialTranslationProbeGuard = ThreadLocalReentryGuard()
    internal val applePronunciationDiagnosticsLoggedSongIds =
        ConcurrentHashMap.newKeySet<String>()
    internal val applePronunciationRuntimeDiagnosticKeys =
        ConcurrentHashMap.newKeySet<String>()
    internal val applePronunciationBindingDiagnosticKeys =
        ConcurrentHashMap.newKeySet<String>()
    internal val applePronunciationLanguagesBySongId =
        ConcurrentHashMap<String, List<String>>()
    internal val applePronunciationContextByLyricObject =
        WeakIdentityMap<Any, ApplePronunciationContext>()
    val nativeOnlineTranslationStore = AppleNativeOnlineTranslationStore()
    internal val deferredTranslationPresentation = AppleLyricsDeferredPresentation()
    internal val pendingApplePronunciationRenderPlans = Collections.synchronizedMap(
        IdentityHashMap<Any, ApplePronunciationRenderPlan>()
    )
    internal val applePronunciationWordRenderContexts =
        ThreadLocalStack<ApplePronunciationWordRenderContext>()
    @Volatile
    internal var appleLyricsLoadMethod: Method? = null
    @Volatile
    private var activeLyricsResultObserver: Pair<WeakReference<Any>, Any>? = null
    @Volatile
    internal var appleLyricsPresentationMethod: Method? = null
    @Volatile
    internal var appleLyricsResultPresentationMethod: Method? = null
    internal val presentationBinding = AppleLyricsPresentationBinding()
    internal val playbackBinding = AppleLyricsPlaybackBinding()
    internal val currentAppleLyricsSongId: String?
        get() = presentationBinding.songId()
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
    internal val suppressedLyricsLoadingViews =
        Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    internal val forcedLyricsTranslationButtons =
        Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    internal val trackedLyricsRecyclerViews =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    internal data class AppleLyricsScrollSnapshot(
        val firstPosition: Int,
        val firstOffset: Int,
        val activeAdapterPosition: Int?,
        val activeAdapterOffset: Int?,
        val playbackPositionMs: Long?,
        val sourceTimingDebug: String? = null,
        val adapterTimingDebug: String? = null,
    )
    internal data class ResolvedAppleLyricsScrollTarget(
        val layoutManager: Any,
        val itemCount: Int,
        val anchor: AppleLyricsRestoreAnchor,
        val activePositions: Set<Int>,
        val playbackMappedPosition: Int?,
        val sourceTimingDebug: String? = null,
        val adapterTimingDebug: String? = null,
    )
    internal var appleLyricsScrollSnapshot: AppleLyricsScrollSnapshot? = null
    internal var appleLyricsScrollSnapshotSongId: String? = null
    /** A failed restore must not let Apple's temporary top layout erase the last good anchor. */
    internal var preserveAppleLyricsTopSnapshotSongId: String? = null
    internal var pendingAppleLyricsScrollRestoreRecycler: WeakReference<ViewGroup>? = null
    internal var pendingAppleLyricsScrollRestoreListener: ViewTreeObserver.OnPreDrawListener? = null
    internal var appleLyricsPresentationInFlight = false
    internal var supplementActiveLineUpdateScheduled = false
    internal var lastSupplementActiveLineIndex = -1
    internal val supplementActiveLineUpdateRunnable = object : Runnable {
        override fun run() {
            supplementActiveLineUpdateScheduled = false
            updateSupplementActiveLine()
        }
    }
    internal lateinit var lyricsRuntimeTarget: AppleMusicHookTarget
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
            nativeRawWordVectorText = { nativeRawWordVectorText(it) },
            currentPlaybackPositionMs = { appleLyricsCurrentPlaybackPositionMs() },
        )
    }
    internal val blurHooks by lazy {
        AppleLyricsBlurHooks(
            runtime = runtime,
            preferences = preferences,
            playbackHooks = playbackHooks,
            currentFragment = { presentationBinding.fragment() },
        )
    }
    internal val diagnostics by lazy {
        AppleLyricsDiagnostics(
            runtime = runtime,
            currentSongId = ::currentSongId,
            epoxyDataBindingFromHolderCallback = epoxyDataBindingFromHolderCallback,
            bindingDiagnostic = { stage, context, details, dedupeKey -> logApplePronunciationBindingDiagnostic(stage, context, details, dedupeKey) },
            uiStateDiagnostic = { fragment, stage ->
                logAppleLyricsUiState(fragment, stage)
            },
            recyclerLifecycleDiagnostic = { recyclerView, stage -> logAppleLyricsRecyclerLifecycle(recyclerView, stage) },
            appleRecyclerViewPredicate = { isAppleRecyclerViewInstance(it) },
        )
    }

    fun currentSongId(): String? = currentAppleLyricsSongId

    internal fun installSourceSwitchPresentationPerformanceHook(
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
                    }.getOrNull()?.let { this.nativeSongId(it) }
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

    internal fun lyricsRuntimeMember(member: AppleMusicRuntimeMember): String =
        lyricsRuntimeTarget.runtimeMemberName(member)

    internal fun lyricsUiMember(member: AppleMusicRuntimeMember): String =
        lyricsUiTarget.runtimeMemberName(member)

    internal fun lyricsSongMember(member: AppleMusicRuntimeMember): String =
        lyricsSongTarget.runtimeMemberName(member)

    internal fun lyricsNativeCall(
        instance: Any?,
        member: AppleMusicRuntimeMember,
        vararg args: Any?,
    ): Any? = instance?.let {
        AppleReflection.call(it, lyricsRuntimeMember(member), *args)
    }

    internal fun lyricsUiField(instance: Any?, member: AppleMusicRuntimeMember): Any? =
        instance?.let { AppleReflection.field(it, lyricsUiMember(member)) }

    fun isSimplifyTraditionalLyricsEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
        ) == true

    private val translationPreferenceHitLogged = AtomicBoolean(false)
    private val pronunciationPreferenceHitLogged = AtomicBoolean(false)

    fun hookTranslationPreference() {
        val translationMethod = hookResolver.resolveMethod(
            AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE
        ).method
        hookRegistrar.installHook(translationMethod, after = { chain, _ ->
            (chain.args.firstOrNull() as? Boolean)?.let {
                if (
                    BuildConfig.DEBUG &&
                    translationPreferenceHitLogged.compareAndSet(false, true)
                ) {
                    ProviderLogger.diagnostic(
                        "Apple Music 歌词翻译偏好 Hook 首次命中: selected=$it"
                    )
                }
                PreferencesMonitor.notifyTranslationSelectedChanged(it)
            }
        })
        ProviderLogger.debug("Apple Music 歌词翻译偏好 Hook 已安装")
        val pronunciationMethod = hookResolver.resolveMethod(
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE
        ).method
        hookRegistrar.installHook(pronunciationMethod, after = { chain, _ ->
            (chain.args.firstOrNull() as? Boolean)?.let {
                if (
                    BuildConfig.DEBUG &&
                    pronunciationPreferenceHitLogged.compareAndSet(false, true)
                ) {
                    ProviderLogger.diagnostic(
                        "Apple Music 歌词发音偏好 Hook 首次命中: selected=$it"
                    )
                }
                PreferencesMonitor.notifyPronunciationSelectedChanged(it)
            }
        })
        ProviderLogger.debug("Apple Music 歌词发音偏好 Hook 已安装")
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

    internal fun observePlayerLyricsViewModelResult(viewModel: Any, songId: String) {
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
            val nativeSongId = songNative?.let { this.nativeSongId(it) }
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

    internal fun cleanupLyricsResultObserver() {
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
            val binding = playbackBinding.snapshot()
            val viewModel = binding.viewModel ?: return@post
            val item = binding.item ?: return@post
            runCatching {
                method.invoke(viewModel, item)
            }.onFailure {
                ProviderLogger.error("Apple Music 当前歌词页刷新失败", it)
            }
        }
    }

    fun refreshAppleLyricsBlurEffect() = blurHooks.refreshAppleLyricsBlurEffect()

    fun refreshAppleSystemFont() = systemFontHooks.refreshAppleSystemFont()

    fun refreshAppleSystemFontWeight() = refreshAppleSystemFont()

    internal fun scheduleAppleLyricsBlur(
        recyclerView: Any?,
        delayMs: Long = 0L,
    ) = blurHooks.scheduleAppleLyricsBlur(recyclerView, delayMs)

    internal fun refreshAppleLyricsRecyclerView(
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

        val bindingTicket = presentationBinding.ticket()
        fun isRefreshCurrent(): Boolean =
            presentationBinding.isCurrent(bindingTicket) &&
                (expectedSongId == null || bindingTicket.songId == expectedSongId) &&
                (expectedRevision == null ||
                    nativeOnlineTranslationStore.isCurrentRevision(
                        songId = expectedSongId,
                        revision = expectedRevision,
                    ))

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

    internal fun refreshVisibleAppleLyricsRows(
        recyclerView: Any,
        songId: String,
        retryAfterLayout: Boolean = true,
        isCurrent: () -> Boolean = { true },
    ) {
        if (!isCurrent()) return
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
                        isCurrent = isCurrent,
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

    fun hookAppleSystemFontWeight() = systemFontHooks.hookAppleSystemFontWeight()

    fun hookAppleLyricsBlurEffect() = blurHooks.hookAppleLyricsBlurEffect()

    fun hookAppleLyricsBindingDiagnostics() = diagnostics.hookAppleLyricsBindingDiagnostics()

    fun hookAppleLyricsUiDiagnostics() = diagnostics.hookAppleLyricsUiDiagnostics()

    fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean =
        blurHooks.isAppleLyricsRecyclerAdapter(adapter)
}
