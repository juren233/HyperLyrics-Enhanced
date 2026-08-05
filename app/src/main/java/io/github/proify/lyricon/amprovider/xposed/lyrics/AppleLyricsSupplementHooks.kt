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
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy
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
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> SharedPreferences?,
    private val playbackHooks: () -> ApplePlaybackHooks,
    private val onlineSourceMenuHooks: () -> AppleOnlineSourceMenuHooks,
    private val lyricRequester: () -> LyricRequester,
    private val catalogResolver: () -> AppleInternalCatalogResolver?,
    private val applyConfiguredContentUiLanguageCallback: () -> Unit,
    private val recordLyricsRequestSource: (String, String) -> Unit,
    private val currentPlaybackQueueMediaId: () -> String?,
    private val epoxyDataBindingFromHolderCallback: (Any?) -> Any?,
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
    private var appleLyricsItemRef: WeakReference<Any>? = null
    @Volatile
    private var appleLyricsPresentationMethod: Method? = null
    @Volatile
    private var appleLyricsFragmentRef: WeakReference<Any>? = null
    @Volatile
    private var appleLyricsSongPointerRef: WeakReference<Any>? = null
    @Volatile
    private var currentAppleLyricsSongId: String? = null
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
        if (
            songId.isNullOrBlank() ||
            songId != currentAppleLyricsSongId ||
            !isNativeOnlineTranslationEnabled()
        ) return false
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

    fun receiveNativeOnlineTranslation(compressedSong: ByteArray) {
        coroutineScope.launch {
            val song = runCatching {
                json.decodeFromString<LocalSong>(
                    compressedSong.inflate().toString(Charsets.UTF_8)
                )
            }.onFailure {
                ProviderLogger.error("Apple Music 原生在线翻译解析失败", it)
            }.getOrNull() ?: return@launch

            mainHandler.post {
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
                if (nativeOnlineTranslationStore.update(song)) {
                    val revision = nativeOnlineTranslationStore.revision()
                    clearPendingApplePronunciationRenderPlans()
                    ProviderLogger.info(
                        "Apple Music 原生在线翻译已接收: id=${song.id}, " +
                            "translatedLines=${song.lyrics.orEmpty().count {
                                !it.translation.isNullOrBlank()
                            }}, romanizedLines=${song.lyrics.orEmpty().count {
                                !it.roma.isNullOrBlank()
                            }}"
                    )
                    song.id?.let(onlineSourceMenuHooks()::resolvePendingSwitches)
                    song.id?.let(onlineSourceMenuHooks()::refreshActiveMenu)
                    refreshAppleLyricsSupplementPresentation(
                        expectedSongId = song.id,
                        expectedRevision = revision,
                    )
                }
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

    fun refreshAppleLyricsSupplementPresentation(
        expectedSongId: String? = null,
        expectedRevision: Long? = null,
        deferWhileSourceMenuShowing: Boolean = true,
    ) {
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
                currentAppleLyricsSongId = currentSongId
            }
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
                    AppleLyricTextTransform.transform(text) ?: original
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
            ) to nativeOnlineTranslationStore::hasTranslation,
            lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_TRANSLATION_METHOD
            ) to nativeOnlineTranslationStore::hasTranslation,
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
                    isNativeOnlineTranslationEnabled() &&
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
        if (line == null || !isNativeOnlineTranslationEnabled()) return null
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
        )
    }

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
            if (source == "apple") {
                chain.thisObject?.let { appleLyricsViewModelRef = WeakReference(it) }
                appleLyricsItemRef = WeakReference(item)
            }
            val id = runCatching {
                AppleReflection.call(item, lyricsSongMember(AppleMusicRuntimeMember.LYRICS_SONG_ID_METHOD))
            }.getOrNull()
            id?.toString()?.let { requestId ->
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
                "loadLyrics：source=$source, id=$id, queueId=$queueId, language=$language"
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
                ) ?: return@installHook

                val source = if (lyricRequester().ownsViewModel(chain.thisObject)) "module" else "apple"
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
                val onlineTranslation =
                    songId?.let(nativeOnlineTranslationStore::hasTranslation) == true
                val onlinePronunciation =
                    songId?.let(nativeOnlineTranslationStore::hasPronunciation) == true
                val officialPronunciation = source == "apple" &&
                    hasValidOfficialRomanization(songNative)
                if (
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

    fun hookAppleNativeLyricsPresentation() {
        val method = hookResolver.resolveMethod(
            AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION
        ).method
        appleLyricsPresentationMethod = method
        hookRegistrar.installHook(
            method,
            before = { chain ->
                val fragment = chain.thisObject ?: return@installHook
                val pointer = chain.args.firstOrNull() ?: return@installHook
                appleLyricsFragmentRef = WeakReference(fragment)
                appleLyricsSongPointerRef = WeakReference(pointer)
                val songNative = runCatching {
                    lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
                }.getOrNull() ?: return@installHook
                val songId = nativeSongId(songNative)
                if (currentAppleLyricsSongId != songId) {
                    clearPendingApplePronunciationRenderPlans()
                    currentAppleLyricsSongId = songId
                }
                ensureAppleLyricTextHooks(songNative)
                logAppleLyricsUiState(
                    fragment = fragment,
                    stage = "presentation_before",
                    expectedSongId = songId,
                )
            },
            after = { chain, _ ->
                val fragment = chain.thisObject ?: return@installHook
                val pointer = chain.args.firstOrNull() ?: return@installHook
                val songNative = runCatching {
                    lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
                }.getOrNull() ?: return@installHook
                val songId = nativeSongId(songNative) ?: return@installHook
                if (currentAppleLyricsSongId != songId) {
                    clearPendingApplePronunciationRenderPlans()
                    currentAppleLyricsSongId = songId
                }
                logAppleLyricsUiState(
                    fragment = fragment,
                    stage = "presentation_after",
                    expectedSongId = songId,
                )
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
