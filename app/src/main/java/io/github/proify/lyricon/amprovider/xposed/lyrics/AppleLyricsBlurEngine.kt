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

internal fun AppleLyricsBlurHooks.appleLyricsHasNativeRenderEffect(view: View): Boolean =
    runCatching {
        AppleReflection.call(view, "getRenderEffect") != null
    }.recoverCatching {
        AppleReflection.field(view, "mRenderEffect") != null
    }.getOrDefault(false)

internal fun AppleLyricsBlurHooks.applyAppleLyricsBlur(view: View, mode: Int, radiusPx: Int) {
    if (BuildConfig.DEBUG) {
        ProviderLogger.debug(
            "[LyricsScrollDiag] applyBlur: " +
                "view=${view.javaClass.simpleName}@${System.identityHashCode(view)}, " +
                "mode=$mode, radius=$radiusPx"
        )
    }
    if (mode == AppleLyricsBlurPolicy.OFF) {
        clearAppleLyricsBlur(view)
        return
    }
    val state = synchronized(appleLyricsBlurRuntimeStates) {
        appleLyricsBlurRuntimeStates.getOrPut(view) {
            AppleLyricsBlurRuntimeState()
        }
    }
    appleLyricsBlurredViews.add(view)
    if (state.blurMode != null && state.blurMode != mode) {
        cancelAppleLyricsBlurAnimation(view, state, clearEffect = true)
        state.blurMode = null
    }
    state.blurMode = mode
    val targetRadius = radiusPx.coerceAtLeast(0).toFloat()
    if (!appleLyricsBlurAnimationEnabled()) {
        cancelAppleLyricsBlurAnimation(view, state, clearEffect = true)
        state.currentBlurRadius = targetRadius
        state.targetBlurRadius = targetRadius
        applyAppleLyricsBlurRadius(view, mode, targetRadius)
        return
    }
    animateAppleLyricsBlur(view, mode, targetRadius, state)
}

internal fun AppleLyricsBlurHooks.animateAppleLyricsBlur(
    view: View,
    mode: Int,
    targetRadius: Float,
    state: AppleLyricsBlurRuntimeState,
) {
    if (
        state.blurAnimator != null &&
        state.blurMode == mode &&
        state.targetBlurRadius == targetRadius
    ) {
        return
    }
    cancelAppleLyricsBlurAnimator(state)
    val currentRadius = state.currentBlurRadius.coerceAtLeast(0f)
    state.targetBlurRadius = targetRadius
    if (currentRadius == targetRadius) {
        applyAppleLyricsBlurRadius(view, mode, targetRadius)
        return
    }
    val viewRef = WeakReference(view)
    val animator = ValueAnimator.ofFloat(currentRadius, targetRadius).apply {
        duration = AppleLyricsBlurHooks.APPLE_LYRICS_BLUR_ANIMATION_DURATION_MS
        addUpdateListener { animation ->
            if (state.blurAnimator !== animation) return@addUpdateListener
            val targetView = viewRef.get() ?: return@addUpdateListener
            val animatedRadius = animation.animatedValue as? Float ?: return@addUpdateListener
            state.currentBlurRadius = animatedRadius
            applyAppleLyricsBlurRadius(targetView, mode, animatedRadius)
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (state.blurAnimator !== animation) return
                state.blurAnimator = null
                state.currentBlurRadius = targetRadius
                state.targetBlurRadius = targetRadius
                viewRef.get()?.let { targetView ->
                    applyAppleLyricsBlurRadius(targetView, mode, targetRadius)
                }
            }
        })
    }
    state.blurAnimator = animator
    animator.start()
}

internal fun AppleLyricsBlurHooks.applyAppleLyricsBlurRadius(view: View, mode: Int, radiusPx: Float) {
    val boundedRadius = radiusPx.coerceAtLeast(0f)
    if (boundedRadius <= 0f) {
        view.setRenderEffect(null)
        clearAppleLyricsHyperOsBlur(view)
        return
    }
    when (mode) {
        AppleLyricsBlurPolicy.NATIVE -> {
            clearAppleLyricsHyperOsBlur(view)
            applyAppleLyricsNativeBlur(view, boundedRadius)
        }
        AppleLyricsBlurPolicy.ADVANCED_MATERIAL -> {
            view.setRenderEffect(null)
            if (!applyAppleLyricsHyperOsBlur(view, boundedRadius.roundToInt())) {
                clearAppleLyricsBlur(view)
            }
        }
        else -> clearAppleLyricsBlur(view)
    }
}

internal fun AppleLyricsBlurHooks.cancelAppleLyricsBlurAnimator(state: AppleLyricsBlurRuntimeState) {
    val animator = state.blurAnimator ?: return
    state.blurAnimator = null
    animator.cancel()
}

internal fun AppleLyricsBlurHooks.cancelAppleLyricsBlurAnimation(
    view: View,
    state: AppleLyricsBlurRuntimeState,
    clearEffect: Boolean,
) {
    cancelAppleLyricsBlurAnimator(state)
    if (clearEffect) {
        view.setRenderEffect(null)
        clearAppleLyricsHyperOsBlur(view)
        state.currentBlurRadius = 0f
        state.targetBlurRadius = 0f
    }
}


internal fun AppleLyricsBlurHooks.applyAppleLyricsNativeBlur(view: View, radiusPx: Float) {
    view.setRenderEffect(
        radiusPx.takeIf { it > 0f }?.let { radius ->
            RenderEffect.createBlurEffect(
                radius,
                radius,
                Shader.TileMode.CLAMP,
            )
        }
    )
}

internal fun AppleLyricsBlurHooks.applyAppleLyricsHyperOsBlur(view: View, radiusPx: Int): Boolean {
    val methods = appleLyricsHyperOsMethods(view)
    val setSelfBlur = methods.setSelfBlur ?: return false
    return runCatching {
        methods.setSelfBlurType?.invoke(view, AppleLyricsBlurHooks.APPLE_LYRICS_HYPER_OS_SELF_BLUR_TYPE)
        setSelfBlur.invoke(view, radiusPx.coerceAtLeast(0), ArrayList<Any>())
        true
    }.getOrElse {
        false
    }
}

internal fun AppleLyricsBlurHooks.clearAppleLyricsBlur(view: View) {
    if (BuildConfig.DEBUG) {
        ProviderLogger.debug(
            "[LyricsScrollDiag] clearBlur: " +
                "view=${view.javaClass.simpleName}@${System.identityHashCode(view)}"
        )
    }
    val state = synchronized(appleLyricsBlurRuntimeStates) {
        appleLyricsBlurRuntimeStates[view]
    }
    state?.let {
        cancelAppleLyricsBlurAnimator(it)
        it.blurMode = null
        it.currentBlurRadius = 0f
        it.targetBlurRadius = 0f
    }
    view.setRenderEffect(null)
    clearAppleLyricsHyperOsBlur(view)
    appleLyricsBlurredViews.remove(view)
    synchronized(appleLyricsBlurRuntimeStates) {
        if (state != null && appleLyricsBlurRuntimeStates[view] === state) {
            appleLyricsBlurRuntimeStates.remove(view)
        }
    }
}

internal fun AppleLyricsBlurHooks.clearAppleLyricsBlurForRecycler(recyclerView: View) {
    (recyclerView as? ViewGroup)?.let { container ->
        repeat(container.childCount) { index ->
            container.getChildAt(index)?.let(::clearAppleLyricsBlur)
        }
    }
    val affectedViews = synchronized(appleLyricsBlurredViews) {
        appleLyricsBlurredViews.toList().also { appleLyricsBlurredViews.clear() }
    }
    affectedViews.forEach(::clearAppleLyricsBlur)
}

internal fun AppleLyricsBlurHooks.clearAppleLyricsHyperOsBlur(view: View) {
    val methods = appleLyricsHyperOsMethods(view)
    runCatching {
        methods.setSelfBlur?.invoke(view, 0, ArrayList<Any>())
    }
}

internal fun AppleLyricsBlurHooks.appleLyricsHyperOsMethods(view: View): AppleLyricsHyperOsMethods =
    synchronized(appleLyricsHyperOsMethods) {
        appleLyricsHyperOsMethods.getOrPut(view.javaClass) {
            fun findPublicMethod(name: String, vararg parameterTypes: Class<*>): Method? =
                runCatching {
                    view.javaClass.getMethod(name, *parameterTypes).apply {
                        isAccessible = true
                    }
                }.getOrNull()

            AppleLyricsHyperOsMethods(
                setSelfBlur = findPublicMethod(
                    "setMiSelfBlur",
                    Int::class.javaPrimitiveType!!,
                    ArrayList::class.java,
                ),
                setSelfBlurType = findPublicMethod(
                    "setMiSelfBlurType",
                    Int::class.javaPrimitiveType!!,
                ),
            )
        }
    }


internal fun AppleLyricsBlurHooks.isAppleLyricsRecyclerView(recyclerView: Any): Boolean {
    val recyclerViewAsView = recyclerView as? View ?: return false
    appleLyricsRecyclerViewClassifications[recyclerViewAsView]?.let { return it }
    val resourceEntryName = runCatching {
        recyclerViewAsView.resources.getResourceEntryName(recyclerViewAsView.id)
    }.getOrNull()
    val isLyrics = resourceEntryName == "lyrics_main_content" ||
        isAppleLyricsRecyclerAdapter(appleRecyclerAdapter(recyclerView))
    appleLyricsRecyclerViewClassifications[recyclerViewAsView] = isLyrics
    return isLyrics
}

internal fun AppleLyricsBlurHooks.resolveAppleLyricsRecyclerView(fragment: Any): Any? {
    runCatching {
        AppleReflection.call(
            fragment,
            lyricsUiMember(AppleMusicRuntimeMember.LYRICS_UI_RECYCLER_VIEW_METHOD),
        )
    }.getOrNull()?.takeIf(::isAppleRecyclerViewInstance)?.let { return it }

    runCatching {
        val binding = AppleReflection.field(
            fragment,
            lyricsUiMember(AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD),
        ) ?: return@runCatching null
        AppleReflection.field(
            binding,
            lyricsUiMember(AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD),
        )
    }.getOrNull()?.takeIf(::isAppleRecyclerViewInstance)?.let { recyclerView ->
        ProviderLogger.debug(
            "Apple Music 歌词 RecyclerView 已解析: source=PlayerLyricsViewFragment.i0.a0"
        )
        return recyclerView
    }

    instanceFieldValues(fragment)
        .filter(::isDataBindingInstance)
        .firstNotNullOfOrNull(::directRecyclerViewField)
        ?.let { recyclerView ->
            ProviderLogger.debug(
                "Apple Music 歌词 RecyclerView 已解析: source=databinding_scan"
            )
            return recyclerView
        }
    return directRecyclerViewField(fragment)
}

internal fun AppleLyricsBlurHooks.directRecyclerViewField(instance: Any): Any? =
    generateSequence<Class<*>>(instance.javaClass) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .filter { isAppleRecyclerViewClass(it.type) }
        .firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(instance)
            }.getOrNull()?.takeIf(::isAppleRecyclerViewInstance)
        }

internal fun AppleLyricsBlurHooks.isDataBindingInstance(instance: Any): Boolean =
    generateSequence<Class<*>>(instance.javaClass) { it.superclass }
        .any { it.name == "androidx.databinding.ViewDataBinding" }

internal fun AppleLyricsBlurHooks.instanceFieldValues(instance: Any): Sequence<Any> =
    generateSequence<Class<*>>(instance.javaClass) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .filterNot { Modifier.isStatic(it.modifiers) }
        .mapNotNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(instance)
            }.getOrNull()
        }

internal fun AppleLyricsBlurHooks.isAppleRecyclerViewInstance(value: Any): Boolean =
    isAppleRecyclerViewClass(value.javaClass)

internal fun AppleLyricsBlurHooks.isAppleRecyclerViewClass(clazz: Class<*>): Boolean =
    generateSequence(clazz) { it.superclass }
        .any { it.name == "androidx.recyclerview.widget.RecyclerView" }

internal fun AppleLyricsBlurHooks.appleRecyclerAdapter(recyclerView: Any): Any? =
    runCatching { AppleReflection.call(recyclerView, "getAdapter") }.getOrNull()

internal fun AppleLyricsBlurHooks.appleRecyclerAdapterItemCount(adapter: Any): Int =
    runCatching {
        AppleReflection.call(adapter, "getItemCount") as? Number
    }.recoverCatching {
        AppleReflection.call(
            adapter,
            lyricsAdapterMember(
                adapter,
                AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD,
            ),
        ) as? Number
    }.getOrNull()?.toInt()?.coerceAtLeast(0) ?: 0

/**
 * Invoke the host's verified two-int scroll method without relying on a
 * decompiler/display name. Apple Music's lyrics layout manager is loaded by
 * the host ClassLoader and may expose an obfuscated method name.
 */
internal fun AppleLyricsBlurHooks.appleLyricsScrollToPositionWithOffset(
    layoutManager: Any,
    position: Int,
    offset: Int,
): String? {
    val method = findAppleLyricsScrollToPositionWithOffsetMethod(layoutManager.javaClass)
        ?: return null
    return runCatching {
        method.invoke(layoutManager, position, offset)
        "${method.declaringClass.name}#${method.name}"
    }.getOrNull()
}

internal fun AppleLyricsBlurHooks.appleRecyclerNotifyDataSetChanged(adapter: Any) {
    runCatching {
        AppleReflection.call(adapter, "notifyDataSetChanged")
    }.recoverCatching {
        AppleReflection.call(
            adapter,
            lyricsAdapterMember(
                adapter,
                AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD,
            ),
        )
    }.getOrThrow()
}

