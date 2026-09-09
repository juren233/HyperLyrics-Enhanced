/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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

internal fun AppleLyricsBlurHooks.appleLyricsChildTopY(view: View): Float =
    view.top + view.translationY

internal fun AppleLyricsBlurHooks.appleLyricsChildBottomY(view: View): Float =
    view.bottom + view.translationY

internal fun AppleLyricsBlurHooks.appleLyricsRecyclerScrollState(recyclerView: Any): Int =
    runCatching {
        (AppleReflection.call(recyclerView, "getScrollState") as? Number)?.toInt()
    }.getOrNull() ?: AppleLyricsBlurHooks.APPLE_LYRICS_SCROLL_STATE_IDLE

internal fun AppleLyricsBlurHooks.appleLyricsActiveAdapterPositions(adapter: Any): Set<Int> =
    ((runCatching {
        AppleReflection.call(
            adapter,
            lyricsAdapterMember(
                adapter,
                AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD,
            ),
        )
    }.getOrNull() as? Iterable<*>)
        ?.mapNotNull { (it as? Number)?.toInt()?.takeIf { position -> position >= 0 } }
        ?.toSet())
        .orEmpty()

internal fun AppleLyricsBlurHooks.appleLyricsLineBeginMs(
    adapter: Any,
    lyrics: Any,
    lineIndex: Int,
): Long? = runCatching {
    val linePointer = AppleReflection.call(
        lyrics,
        lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_AT_METHOD),
        lineIndex,
    ) ?: return@runCatching null
    val nativeLine = AppleReflection.call(
        linePointer,
        lyricsNativeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD),
    ) ?: return@runCatching null
    (AppleReflection.call(
        nativeLine,
        lyricsNativeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD),
    ) as? Number)?.toLong()
}.getOrNull()

internal fun AppleLyricsBlurHooks.appleLyricsFirstLineBeginMs(adapter: Any): Long? = runCatching {
    val lyrics = AppleReflection.call(
        adapter,
        lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD),
    ) ?: return@runCatching null
    val lineCount = (
        AppleReflection.call(
            lyrics,
            lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_COUNT_METHOD),
        ) as? Number
        )?.toInt()
        ?: return@runCatching null
    if (lineCount <= 0) return@runCatching null
    appleLyricsLineBeginMs(adapter, lyrics, 0)
}.getOrNull()

internal fun AppleLyricsBlurHooks.appleLyricsAdapterPositionForPlayback(
    adapter: Any,
    playbackPositionMs: Long,
): Int? = runCatching {
    val lyrics = AppleReflection.call(
        adapter,
        lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD),
    ) ?: return@runCatching null
    val lineCount = (AppleReflection.call(
        lyrics,
        lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_COUNT_METHOD),
    ) as? Number)?.toInt() ?: return@runCatching null
    if (lineCount <= 0) return@runCatching null
    val itemCount = appleRecyclerAdapterItemCount(adapter)
    selectAppleLyricsPlaybackAdapterPosition(
        lineBeginsMs = (0 until lineCount).map { lineIndex ->
            appleLyricsLineBeginMs(adapter, lyrics, lineIndex)
        },
        playbackPositionMs = playbackPositionMs,
        itemCount = itemCount,
    )
}.getOrNull()

/**
 * Returns a compact, debug-only view of the adapter's lyric time axis and the
 * adapter positions that are relevant to the current layout. Adapter positions
 * are deliberately not assumed to equal lyric indexes: both the direct position
 * probes and the complete logical begin-time list are emitted so that a later
 * diagnosis can prove or falsify that assumption from runtime evidence.
 */
internal fun AppleLyricsBlurHooks.appleLyricsAdapterDebugSnapshot(
    adapter: Any,
    relevantPositions: Iterable<Int> = emptyList(),
    playbackPositionMs: Long? = null,
): String {
    val active = appleLyricsActiveAdapterPositions(adapter).sorted()
    val itemCount = runCatching {
        (AppleReflection.call(adapter, "getItemCount") as? Number)?.toInt()
    }.getOrNull() ?: 0
    val lyrics = runCatching {
        AppleReflection.call(
            adapter,
            lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD),
        )
    }.getOrNull()
    val lineCount = lyrics?.let {
        runCatching {
            (AppleReflection.call(
                it,
                lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_COUNT_METHOD),
            ) as? Number)?.toInt()
        }.getOrNull()
    } ?: 0
    val logicalBegins = if (lyrics == null || lineCount <= 0) {
        emptyList()
    } else {
        (0 until lineCount.coerceAtMost(256)).mapNotNull { index ->
            appleLyricsLineBeginMs(adapter, lyrics, index)?.let { index to it }
        }
    }
    val playbackLine = playbackPositionMs?.let { position ->
        logicalBegins.indexOfLast { (_, begin) -> begin <= position }
            .takeIf { it >= 0 }
            ?.let { logicalBegins[it].first }
    }
    val positions = (relevantPositions.asSequence() + active.asSequence())
        .filter { it >= 0 }
        .distinct()
        .sorted()
        .take(48)
        .toList()
    val viewTypes = positions.joinToString(",") { position ->
        val viewType = appleLyricsAdapterItemViewType(adapter, position)
        "$position:${viewType ?: "?"}"
    }
    val directBegins = if (lyrics == null) {
        emptyList()
    } else {
        positions.mapNotNull { position ->
            appleLyricsLineBeginMs(adapter, lyrics, position)?.let { "$position:$it" }
        }
    }
    val logicalSample = logicalBegins
        .take(16)
        .joinToString(",") { (index, begin) -> "$index:$begin" }
    return "adapter=${adapter.javaClass.name}@${System.identityHashCode(adapter)}," +
        "itemCount=$itemCount,active=$active,viewTypes=[$viewTypes]," +
        "logicalLineCount=$lineCount,logicalBegins=[$logicalSample]," +
        "directPositionBegins=[${directBegins.joinToString(",")}]," +
        "playback=${playbackPositionMs ?: "none"},playbackLine=$playbackLine"
}

internal fun AppleLyricsBlurHooks.appleLyricsCurrentPlaybackPositionMs(): Long? = playbackHooks().currentPositionMs()

internal fun AppleLyricsBlurHooks.appleLyricsVisiblePositionedChildren(
    recyclerView: View,
): List<Pair<Int, View>> {
    val container = recyclerView as? ViewGroup ?: return emptyList()
    return buildList {
        repeat(container.childCount) { index ->
            val child = container.getChildAt(index)
                ?.takeIf {
                    it.visibility == View.VISIBLE && it.width > 0 && it.height > 0
                }
                ?: return@repeat
            appleLyricsChildAdapterPosition(recyclerView, child)
                .takeIf { it >= 0 }
                ?.let { position -> add(position to child) }
        }
    }
}

internal fun AppleLyricsBlurHooks.appleLyricsInstrumentalAdapterPositions(
    adapter: Any,
    positionedChildren: List<Pair<Int, View>>,
): Set<Int> = positionedChildren.mapNotNull { (position, child) ->
    position.takeIf {
        appleLyricsAdapterItemViewType(adapter, position) == 2 ||
            appleLyricsIsInstrumentalIndicator(child)
    }
}.toSet()

internal fun AppleLyricsBlurHooks.appleLyricsWritersCreditsAdapterPositions(
    adapter: Any,
    positionedChildren: List<Pair<Int, View>>,
): Set<Int> = positionedChildren.mapNotNull { (position, _) ->
    position.takeIf { appleLyricsAdapterItemViewType(adapter, position) == 1 }
}.toSet()

internal fun AppleLyricsBlurHooks.appleLyricsAdapterItemViewType(adapter: Any, position: Int): Int? =
    runCatching {
        (AppleReflection.call(adapter, "getItemViewType", position) as? Number)?.toInt()
    }.getOrNull() ?: runCatching {
        (AppleReflection.call(
            adapter,
            lyricsAdapterMember(
                adapter,
                AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD,
            ),
            position,
        ) as? Number)?.toInt()
    }.getOrNull()

internal fun AppleLyricsBlurHooks.appleLyricsIsInstrumentalIndicator(view: View): Boolean {
    val rootId = runCatching {
        view.resources.getIdentifier(
            "lyrics_instrumental_root",
            "id",
            AppleLyricsBlurHooks.APPLE_MUSIC_PACKAGE,
        )
    }.getOrDefault(0)
    return rootId != 0 && view.findViewById<View>(rootId) != null
}

internal fun AppleLyricsBlurHooks.appleLyricsChildAdapterPosition(recyclerView: Any, child: View): Int {
    val recyclerViewAsView = recyclerView as? View ?: return -1
    val namedPosition = runCatching {
        (AppleReflection.call(recyclerViewAsView, "getChildAdapterPosition", child) as? Number)
            ?.toInt()
    }.getOrNull()?.takeIf { it >= 0 }
    if (namedPosition != null) return namedPosition

    val method = appleLyricsChildAdapterPositionMethods[recyclerViewAsView.javaClass]
        ?: findAppleLyricsChildAdapterPositionMethod(recyclerViewAsView.javaClass)?.also {
            appleLyricsChildAdapterPositionMethods[recyclerViewAsView.javaClass] = it
        }
        ?: return -1
    return runCatching {
        (method.invoke(null, child) as? Number)?.toInt()
    }.getOrNull()?.takeIf { it >= 0 } ?: -1
}

internal fun AppleLyricsBlurHooks.findAppleLyricsChildAdapterPositionMethod(clazz: Class<*>): Method? =
    generateSequence(clazz) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(arrayOf(View::class.java))
        }
        ?.apply { isAccessible = true }

internal fun AppleLyricsBlurHooks.logAppleLyricsBlurDiagnostic(
    recyclerView: View,
    state: AppleLyricsBlurRuntimeState,
    stage: String,
    mode: Int,
    adapter: Any? = null,
    activePositions: Set<Int> = emptySet(),
    instrumentalPositions: Set<Int> = emptySet(),
    writersCreditsPositions: Set<Int> = emptySet(),
    focusPositions: Set<Int> = activePositions,
    positionedChildren: List<Pair<Int, View>> = emptyList(),
    radiusByPosition: Map<Int, Int> = emptyMap(),
    deferredBlurPositions: Set<Int> = emptySet(),
    outgoingBottomByPosition: Map<Int, Int> = emptyMap(),
    currentZoneTopByPosition: Map<Int, Int> = emptyMap(),
    currentPositionMs: Long? = null,
    firstLineBeginMs: Long? = null,
) {
    if (!BuildConfig.DEBUG) return
    val visiblePositions = positionedChildren.map(Pair<Int, View>::first)
    val nativeRenderEffectCount = positionedChildren.count { (_, child) ->
        appleLyricsHasNativeRenderEffect(child)
    }
    val signature = listOf(
        stage,
        mode,
        adapter?.javaClass?.name,
        appleLyricsRecyclerScrollState(recyclerView),
        activePositions.sorted(),
        instrumentalPositions.sorted(),
        writersCreditsPositions.sorted(),
        focusPositions.sorted(),
        visiblePositions,
        state.suspendedForScroll,
        state.settledAnchorTopY?.roundToInt(),
        state.pendingProgrammaticRecenterPosition,
        radiusByPosition,
        deferredBlurPositions.sorted(),
        outgoingBottomByPosition,
        currentZoneTopByPosition,
        currentPositionMs,
        firstLineBeginMs,
        nativeRenderEffectCount,
    ).joinToString("|")
    if (state.lastDiagnosticSignature == signature) return
    state.lastDiagnosticSignature = signature
    ProviderLogger.diagnostic(
        "Apple lyrics blur: stage=$stage, mode=$mode, " +
            "adapter=${adapter?.javaClass?.name ?: "none"}, " +
            "scrollState=${appleLyricsRecyclerScrollState(recyclerView)}, " +
            "active=${activePositions.sorted()}, " +
            "instrumental=${instrumentalPositions.sorted()}, " +
            "writers=${writersCreditsPositions.sorted()}, " +
            "focus=${focusPositions.sorted()}, visible=$visiblePositions, " +
            "suspended=${state.suspendedForScroll}, " +
            "anchor=${state.settledAnchorTopY?.roundToInt() ?: "none"}, " +
            "pendingRecenter=${state.pendingProgrammaticRecenterPosition ?: "none"}, " +
            "deferred=${deferredBlurPositions.sorted()}, " +
            "outgoingBottom=$outgoingBottomByPosition, " +
            "currentZoneTop=$currentZoneTopByPosition, radii=$radiusByPosition, " +
            "currentPosition=$currentPositionMs, firstLineBegin=$firstLineBeginMs, " +
            "nativeEffects=$nativeRenderEffectCount"
    )
}

internal fun AppleLyricsBlurHooks.findAppleLyricsScrollToPositionWithOffsetMethod(clazz: Class<*>): Method? {
    runCatching {
        AppleReflection.findMethod(
            clazz,
            "scrollToPositionWithOffset",
            parameterCount = 2,
        )
    }.getOrNull()?.takeIf { method ->
        method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(
                arrayOf(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            )
    }?.let { return it }

    return generateSequence(clazz) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .firstOrNull { method ->
            Modifier.isPublic(method.modifiers) &&
                !Modifier.isStatic(method.modifiers) &&
                !Modifier.isFinal(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                )
        }
        ?.apply { isAccessible = true }
}

