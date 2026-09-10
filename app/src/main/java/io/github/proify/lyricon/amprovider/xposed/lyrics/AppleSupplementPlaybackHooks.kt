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

internal fun AppleLyricsSupplementHooks.hookLyricBuildMethod() {
    val load = hookResolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD)
    lyricsRuntimeTarget = load.target
    appleLyricsLoadMethod = load.method
    hookRegistrar.installHook(load.method, before = { chain ->
        val item = chain.args.firstOrNull() ?: return@installHook
        val requester = lyricRequester()
        val requestSource = if (requester.ownsViewModel(chain.thisObject)) {
            APPLE_LYRICS_REQUEST_SOURCE_MODULE
        } else {
            APPLE_LYRICS_REQUEST_SOURCE_APPLE
        }
        val loadedSongId = runCatching {
            AppleReflection.call(
                item,
                lyricsSongMember(AppleMusicRuntimeMember.LYRICS_SONG_ID_METHOD),
            ).toString()
        }.getOrNull()
        val moduleRequestSongId = requester.requestedMediaId(chain.thisObject)
        val tracksNativeRequest = requestSource == APPLE_LYRICS_REQUEST_SOURCE_APPLE ||
            (requestSource == APPLE_LYRICS_REQUEST_SOURCE_MODULE &&
                !loadedSongId.isNullOrBlank() && loadedSongId == moduleRequestSongId)
        if (tracksNativeRequest) {
            missingLyricsSupplement().onNativeLyricsRequestStarted(loadedSongId)
            chain.thisObject?.let { viewModel ->
                loadedSongId?.let { songId ->
                    observePlayerLyricsViewModelResult(viewModel, songId)
                }
            }
        }
        if (requestSource == APPLE_LYRICS_REQUEST_SOURCE_APPLE) {
            val visibleSongId = currentAppleLyricsSongId
            val queueSongId = currentPlaybackQueueMediaId()
            if (loadedSongId != null && visibleSongId != null && loadedSongId != visibleSongId) {
                stopSupplementActiveLineUpdate()
                clearPendingApplePronunciationRenderPlans()
                clearPendingAppleLyricsScrollRestore()
                presentationBinding.rememberPointer(null)
                appleLyricsScrollSnapshot = null
                appleLyricsScrollSnapshotSongId = null
                presentationBinding.selectSong(loadedSongId)
            }
            playbackBinding.rememberLoad(chain.thisObject, item)
            missingLyricsSupplement().onLyricsItem(item)
        } else {
            // 旧版缓存没有 lyricsSource；若模块请求后 Apple 原生链仍未产出，延迟迁移为补充歌词。
            loadedSongId?.takeIf(String::isNotBlank)?.let { songId ->
                scheduleLegacyModuleCachePromotion(songId)
            }
        }
        loadedSongId?.let { requestId ->
            recordLyricsRequestSource(requestId, requestSource)
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
            "loadLyrics：source=$requestSource, id=$loadedSongId, queueId=$queueId, " +
                "language=$language, nativeTracked=$tracksNativeRequest"
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
            val requester = lyricRequester()
            val requestSource = if (requester.ownsViewModel(chain.thisObject)) {
                APPLE_LYRICS_REQUEST_SOURCE_MODULE
            } else {
                APPLE_LYRICS_REQUEST_SOURCE_APPLE
            }
            val songId = songNative?.let { this.nativeSongId(it) }
            val supplementPointer = missingLyricsSupplement().isSupplementPointer(pointer)
            val hasNativeLines = appleNativeSongHasLines(songNative)
            val moduleNative = shouldTreatModuleLyricsResultAsAppleNative(
                requestSource = requestSource,
                requestedSongId = requester.requestedMediaId(chain.thisObject),
                resultSongId = songId,
                currentPlaybackSongId = currentPlaybackQueueMediaId(),
                supplementPointer = supplementPointer,
                hasNativeLines = hasNativeLines,
            )
            val contentSource = if (moduleNative) {
                APPLE_LYRICS_REQUEST_SOURCE_APPLE
            } else {
                requestSource
            }
            val diagnosticSource = if (moduleNative) {
                APPLE_LYRICS_DIAGNOSTIC_SOURCE_MODULE_NATIVE
            } else {
                requestSource
            }
            if (moduleNative) {
                ProviderLogger.info(
                    "Apple Music 模块请求确认原生歌词: id=$songId, " +
                        "source=$diagnosticSource"
                )
            }
            if (contentSource == APPLE_LYRICS_REQUEST_SOURCE_APPLE) {
                reportNativeLyricsState(
                    songNative = songNative,
                    songId = songId,
                    sourcePointer = pointer,
                )
            }
            if (songNative == null) return@installHook

            val resolvedSongId = nativeSongId(songNative)
            logApplePronunciationModelState(
                stage = "build_after:$diagnosticSource",
                viewModel = chain.thisObject,
                pointer = pointer,
                songNative = songNative,
            )
            val visibleSongId = currentAppleLyricsSongId
                ?.takeIf {
                    requestSource == APPLE_LYRICS_REQUEST_SOURCE_APPLE &&
                        it == resolvedSongId
                }
            mainHandler.post {
                PlaybackManager.onLyricsBuilt(
                    nativeSongObj = songNative,
                    source = contentSource,
                    visibleSongId = visibleSongId,
                    playbackSongId = currentPlaybackQueueMediaId(),
                )
                if (
                    requestSource == APPLE_LYRICS_REQUEST_SOURCE_APPLE &&
                    !resolvedSongId.isNullOrBlank() &&
                    resolvedSongId == currentAppleLyricsSongId
                ) {
                    onlineSourceMenuHooks().refreshActiveMenu(resolvedSongId)
                }
            }
            applyConfiguredContentUiLanguageCallback()
            val onlineTranslation = resolvedSongId?.let(::hasAnyOnlineTranslation) == true
            val onlinePronunciation =
                resolvedSongId?.let(nativeOnlineTranslationStore::hasPronunciation) == true
            val officialPronunciation = contentSource == APPLE_LYRICS_REQUEST_SOURCE_APPLE &&
                hasValidOfficialRomanization(songNative)
            // 补充歌词有自己的 requestMissingLyricsPresentationRefresh 收尾；
            // Apple 的 R2 轨道刷新会反复重绑 adapter 并引起歌词页抽搐。
            if (
                !supplementPointer &&
                ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                    sourceIsApple = contentSource == APPLE_LYRICS_REQUEST_SOURCE_APPLE,
                    hasValidOfficialPronunciation = officialPronunciation,
                    hasOnlineTranslation = onlineTranslation,
                    hasOnlinePronunciation = onlinePronunciation,
                    pronunciationSelected = PreferencesMonitor.isPronunciationSelected(),
                )
            ) {
                ProviderLogger.debug(
                    "Apple Music 歌词模型完成后请求补充轨道刷新: " +
                        "id=$resolvedSongId, officialPronunciation=$officialPronunciation, " +
                        "onlineTranslation=$onlineTranslation, " +
                        "onlinePronunciation=$onlinePronunciation, " +
                        "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}"
                )
                refreshAppleLyricsSupplementPresentation(resolvedSongId)
            }
        }
    )
    ProviderLogger.debug("歌词构建 Hook 已安装")
}

/** 延迟迁移旧缓存，避免抢在 Apple 原生歌词结果之前。 */
internal fun AppleLyricsSupplementHooks.scheduleLegacyModuleCachePromotion(songId: String) {
    mainHandler.postDelayed({
        if (missingLyricsSupplement().hasKnownNativeLyricsFor(songId)) return@postDelayed
        PlaybackManager.promoteLegacyCachedLyricsAsMissingSupplement(songId)
    }, AppleLyricsSupplementHooks.LEGACY_MODULE_PROMOTION_DELAY_MS)
}

internal fun AppleLyricsSupplementHooks.hookAppleNativeLyricsPresentation() {
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
            if (presentationBinding.fragment() !== fragment) {
                presentationBinding.rememberFragment(fragment)
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
            presentationBinding.rememberFragment(fragment)
            val pointer = chain.args.firstOrNull()
            if (pointer != null) {
                presentationBinding.rememberPointer(pointer)
            }
            val songNative = runCatching {
                lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
            }.getOrNull()
            val songId = songNative?.let { this.nativeSongId(it) }
            reportNativeLyricsState(
                songNative = songNative,
                songId = songId,
                sourcePointer = pointer,
            )
            if (songNative == null) {
                presentationBinding.rememberPointer(null)
                stopSupplementActiveLineUpdate()
                val queueSongId = currentPlaybackQueueMediaId()
                onAppleLyricsDisplayTrackChanged(queueSongId)
                return@installHook
            }
            onAppleLyricsDisplayTrackChanged(songId)
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
                presentationBinding.rememberPointer(null)
                stopSupplementActiveLineUpdate()
                val queueSongId = currentPlaybackQueueMediaId()
                onAppleLyricsDisplayTrackChanged(queueSongId)
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
            onAppleLyricsDisplayTrackChanged(songId)
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
            onAppleLyricsPresentationCompleted(fragment, songId)
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

