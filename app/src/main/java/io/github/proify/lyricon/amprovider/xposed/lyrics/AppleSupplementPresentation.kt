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

/**
 * 无歌词补充：写入 ViewModel 结果并显式调用 I2 主结果入口。I2 会验证
 * SongInfo.adamId、安装歌词适配器，并通过 Apple 自身的 L2/N2/R2 链路收尾。
 */
internal fun AppleLyricsSupplementHooks.requestMissingLyricsPresentationRefresh(
    supplementPointer: Any? = null,
    fragmentOverride: Any? = null,
    currentPlaybackItem: Any? = null,
) {
    mainHandler.post {
        ProviderLogger.debug(
            "Apple Music 无歌词补充呈现刷新进入: method=" +
                "${appleLyricsResultPresentationMethod != null}, fragmentRef=" +
                "${presentationBinding.fragment() != null}, override=" +
                "${fragmentOverride != null}, supplement=${supplementPointer != null}, " +
                "currentPlaybackItem=${currentPlaybackItem != null}"
        )
        val method = appleLyricsResultPresentationMethod ?: return@post
        val fragment = fragmentOverride ?: presentationBinding.fragment() ?: return@post
        val pointer = supplementPointer
            ?: presentationBinding.pointer()
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
        }.getOrNull()?.let { this.nativeSongId(it) }
        if (presentedSongId != null) {
            scrollPresentationState.beginPresentation()
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
                    scrollPresentationState.finishPresentation()
                }
                ProviderLogger.debug("Apple Music 无歌词补充原生呈现已刷新")
            }
            .onFailure {
                scrollPresentationState.finishPresentation()
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
internal fun AppleLyricsSupplementHooks.dismissAppleLyricsLoadingOverlay(fragment: Any?) {
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
            if (viewTracking.markLoadingViewSuppressedIfNew(view)) {
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
internal fun AppleLyricsSupplementHooks.ensureMissingLyricsTranslationButtonVisible(fragment: Any?) {
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
            if (viewTracking.markTranslationButtonForcedIfNew(view)) {
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

/** 与 Apple 自身链路一致：时间轴地图 + 结果 LiveData，让原生观察者接管显示。 */
internal fun AppleLyricsSupplementHooks.injectSupplementIntoViewModel(
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
internal fun AppleLyricsSupplementHooks.synchronizeSupplementPlaybackItem(
    viewModel: Any,
    pointer: Any,
    currentPlaybackItem: Any?,
): Boolean {
    val expectedSongId = runCatching {
        lyricsNativeCall(
            pointer,
            AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
        )
    }.getOrNull()?.let { this.nativeSongId(it) }
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
    val binding = playbackBinding.snapshot()
    val boundItemId = binding.item?.let { item ->
        registeredPlaybackItemId(item) ?: playbackItemSongId(item)
    }
    if (binding.viewModel === viewModel && boundItemId == expectedSongId) {
        return true
    }
    return runCatching {
        loadMethod.invoke(viewModel, playbackItem)
    }.onSuccess {
        playbackBinding.rememberLoad(viewModel, playbackItem)
        ProviderLogger.debug(
            "Apple Music 无歌词补充已同步当前 PlaybackItem: " +
                "previousId=$boundItemId, currentId=$itemSongId"
        )
    }.onFailure {
        ProviderLogger.error("Apple Music 无歌词补充同步当前 PlaybackItem 失败", it)
    }.isSuccess
}

internal fun AppleLyricsSupplementHooks.playbackItemSongId(item: Any): String? = runCatching {
    AppleReflection.call(
        item,
        lyricsSongMember(AppleMusicRuntimeMember.LYRICS_SONG_ID_METHOD),
    )?.toString()
}.getOrNull()?.takeIf(String::isNotBlank)

internal fun AppleLyricsSupplementHooks.refreshAppleLyricsSupplementPresentation(
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
        val binding = presentationBinding.snapshot()
        val fragment = binding.fragment ?: return@post
        val pointer = binding.pointer ?: return@post
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
        onAppleLyricsDisplayTrackChanged(currentSongId)
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
internal fun AppleLyricsSupplementHooks.refreshVisibleMissingLyricsTranslation(update: AppleMissingLyricsPresentationUpdate) {
    val expectedSongId = update.songId?.takeIf(String::isNotBlank) ?: return
    mainHandler.post {
        if (!missingLyricsSupplement().store.isCurrentPresentation(update)) return@post
        val binding = presentationBinding.snapshot()
        if (binding.songId != expectedSongId) return@post
        val fragment = binding.fragment ?: return@post
        val pointer = binding.pointer ?: return@post
        if (!missingLyricsSupplement().isSupplementPointer(pointer)) return@post
        val songNative = runCatching {
            lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
        }.getOrNull() ?: return@post
        if (nativeSongId(songNative) != expectedSongId) return@post

        ensureAppleLyricTextHooks(songNative)
        applyAppleNativeSupplementSelection(songNative)
        ensureMissingLyricsTranslationButtonVisible(fragment)
        val recyclerView = resolveAppleLyricsRecyclerView(fragment) ?: return@post
        val ticket = presentationBinding.ticket()
        refreshVisibleAppleLyricsRows(recyclerView, expectedSongId, isCurrent = {
            presentationBinding.isCurrent(ticket) &&
                missingLyricsSupplement().store.isCurrentPresentation(update)
        })
    }
}

internal fun AppleLyricsSupplementHooks.deferNativeTranslationPresentationRefresh(
    expectedSongId: String,
    expectedRevision: Long?,
) {
    val update = AppleLyricsPresentationUpdate(expectedSongId, expectedRevision)
    if (!deferredTranslationPresentation.offer(update)) return
    mainHandler.postDelayed(
        {
            val deferred = deferredTranslationPresentation.take() ?: return@postDelayed
            val deferredSongId = deferred.songId ?: return@postDelayed
            val popupStillShowing =
                onlineSourceMenuHooks().isMenuShowingForSong(deferredSongId)
            if (popupStillShowing) {
                deferNativeTranslationPresentationRefresh(
                    expectedSongId = deferredSongId,
                    expectedRevision = deferred.revision,
                )
                return@postDelayed
            }
            refreshAppleLyricsSupplementPresentation(
                expectedSongId = deferredSongId,
                expectedRevision = deferred.revision,
                deferWhileSourceMenuShowing = false,
            )
        },
        100L,
    )
}

internal fun AppleLyricsSupplementHooks.shouldDeferNativeTranslationPresentationRefresh(
    activeMenuSongId: String?,
    popupShowing: Boolean,
    expectedSongId: String?,
): Boolean =
    popupShowing &&
        !activeMenuSongId.isNullOrBlank() &&
        (expectedSongId.isNullOrBlank() || activeMenuSongId == expectedSongId)

internal fun AppleLyricsSupplementHooks.isNativeOnlineTranslationEnabled(): Boolean {
    val prefs = contentUiLanguagePrefs ?: return false
    return prefs.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
    ) && prefs.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
    )
}

internal fun AppleLyricsSupplementHooks.isHideMandarinPinyinEnabled(): Boolean {
    val prefs = contentUiLanguagePrefs ?: return false
    return prefs.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
    )
}


internal fun AppleLyricsSupplementHooks.shouldHideMandarinPronunciation(
    songId: String? = null,
    pronunciationLanguages: Collection<String> = emptyList(),
    lyricObject: Any? = null,
): Boolean {
    val lyricContext = lyricObject?.let(pronunciationState::contextFor)
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
            addAll(pronunciationState.languages(id).orEmpty())
        }
    }.map(String::trim).filter(String::isNotEmpty).distinct()
    return ApplePronunciationVisibilityPolicy.shouldHide(
        genre = genre,
        pronunciationLanguages = resolvedPronunciationLanguages,
        hideMandarinPinyin = isHideMandarinPinyinEnabled(),
    )
}
