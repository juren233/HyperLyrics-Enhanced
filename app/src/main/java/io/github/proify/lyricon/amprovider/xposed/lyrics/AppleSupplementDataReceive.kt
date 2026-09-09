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
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import java.lang.ref.WeakReference
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import kotlinx.coroutines.launch
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy

internal fun AppleLyricsSupplementHooks.currentAppleLyricsNativeSong(songId: String): Any? {
    val pointer = appleLyricsSongPointerRef?.get() ?: return null
    val songNative = runCatching {
        lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
    }.getOrNull()
        ?: return null
    return songNative.takeIf { nativeSongId(it) == songId }
}

internal fun AppleLyricsSupplementHooks.currentAppleLyricsNativeLines(songNative: Any): List<Any> {
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

internal fun AppleLyricsSupplementHooks.appleNativeSongHasLines(songNative: Any?): Boolean =
    songNative != null && currentAppleLyricsNativeLines(songNative).isNotEmpty()

internal fun AppleLyricsSupplementHooks.reportNativeLyricsState(
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

/**
 * 原生歌词页恢复可见后的空白自愈。
 *
 * 复现路径：歌词页隐藏期间 Apple 已为队列当前歌曲调用过 loadLyrics，但重新可见时
 * Fragment 仍持有上一首歌曲的空 adapter，且 Apple 不会再重放 loadLyrics。此时页面
 * 空白而 AOD/超级岛正常。这里只对已确认 Apple 原生歌词的歌曲、且 adapter 仍为空的
 * 可见页面补一次当前队列 PlaybackItem 的 loadLyrics；已有内容时不做任何操作。
 */
internal fun AppleLyricsSupplementHooks.scheduleBlankNativeLyricsPageRecovery(fragment: Any? = null) {
    if (!missingLyricsSupplement().isEnabled()) return
    val queueSongId = currentPlaybackQueueMediaId()
        ?.takeIf(String::isNotBlank)
        ?: return
    if (!missingLyricsSupplement().hasKnownNativeLyricsFor(queueSongId)) return
    mainHandler.postDelayed(
        {
            recoverBlankNativeLyricsPage(fragment, queueSongId)
        },
        AppleLyricsSupplementHooks.BLANK_NATIVE_LYRICS_PAGE_RECOVERY_DELAY_MS,
    )
}

internal fun AppleLyricsSupplementHooks.recoverBlankNativeLyricsPage(
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
        runtimeSongId = { playbackItemSongId(it) },
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

internal fun AppleLyricsSupplementHooks.receiveNativeOnlineTranslation(compressedSong: ByteArray) {
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
internal fun AppleLyricsSupplementHooks.receiveMissingLyricsSupplement(compressedSong: ByteArray) {
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
internal fun AppleLyricsSupplementHooks.receiveModuleMissingLyrics(song: LyriconSong) {
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

internal fun AppleLyricsSupplementHooks.clearNativeOnlineTranslation(songId: String?) {
    mainHandler.post {
        if (nativeOnlineTranslationStore.clear(songId)) {
            clearPendingApplePronunciationRenderPlans()
            ProviderLogger.debug("Apple Music 原生在线翻译已清除: id=$songId")
            songId?.let(onlineSourceMenuHooks()::refreshActiveMenu)
            refreshAppleLyricsSupplementPresentation()
        }
    }
}

