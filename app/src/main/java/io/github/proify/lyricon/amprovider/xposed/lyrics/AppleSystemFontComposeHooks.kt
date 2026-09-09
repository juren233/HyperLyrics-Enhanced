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
import android.database.ContentObserver
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
import android.os.Build
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
import java.nio.ByteBuffer
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


internal fun AppleSystemFontHooks.handleTextViewTypefaceAfterSet(
    view: TextView,
    requested: Typeface?,
    requestedStyle: Int?,
    stage: String,
) {
    if (appleSystemFontApplyGuard.isActive) return
    val current = view.typeface ?: return
    val requestedTypeface = requested ?: current
    val previousState = synchronized(appleSystemFontTrackedTextViews) {
        appleSystemFontTrackedTextViews[view]
    }
    val reusedState = previousState?.takeIf { it.appliedTypeface === requestedTypeface }
    val originalTypeface = reusedState?.originalTypeface
        ?: originalAppleTypeface(requestedTypeface)
        ?: requestedTypeface
    val originalStyle = requestedStyle ?: reusedState?.originalStyle
        ?: requestedTypeface.style
    val italic = reusedState?.italic ?: (
        originalTypeface.isItalic || originalStyle and Typeface.ITALIC != 0
    )
    val requestedWeight = reusedState?.requestedWeight ?: originalTypeface.weight
    rememberTextViewState(
        view, originalTypeface, requestedWeight, italic, originalStyle, current,
    )
    applyAppleSystemFontForTextView(view, stage = stage)
}

internal fun AppleSystemFontHooks.hookAppleComposeSystemFontWeight(
    installedHooks: MutableList<String>,
    failedHooks: MutableList<String>,
) {
    var typefaceFactoryInstalled = false
    var typefaceFactoryFailure: Throwable? = null
    val attemptedTypefaceFactories = mutableSetOf<Pair<String, String>>()
    for (attempt in 0..1) {
        var rejectedAny = false
        val candidates = hookResolver.resolveClasses(
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS
        )
        for (resolved in candidates) {
            val attemptKey = resolved.baselineClassName to resolved.clazz.name
            if (!attemptedTypefaceFactories.add(attemptKey)) continue
            val result = runCatching {
                val typefaceFactoryMethod = resolved.clazz.declaredMethods.single { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.returnType == Typeface::class.java &&
                        method.parameterTypes.size == 4 &&
                        method.parameterTypes.firstOrNull() == android.content.Context::class.java
                }.apply { isAccessible = true }
                hookRegistrar.installResultOverrideHook(typefaceFactoryMethod) { _, original ->
                    (original as? Typeface)?.let(appleSystemFontManagedTypefaces::add)
                    original
                }
                installedHooks += "ComposeTypefaceFactory.${typefaceFactoryMethod.name}"
            }
            if (result.isSuccess) {
                typefaceFactoryInstalled = true
                break
            }
            val throwable = result.exceptionOrNull() ?: continue
            typefaceFactoryFailure = throwable
            if (attempt == 0 && hookResolver.rejectClassResolution(
                    hookPoint = AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS,
                    resolved = resolved,
                    reason = "${throwable.javaClass.simpleName}: ${throwable.message}",
                )
            ) {
                rejectedAny = true
            }
        }
        if (typefaceFactoryInstalled || !rejectedAny) break
        ProviderLogger.info("Apple Compose Typeface DexKit 定向失效后重试")
    }
    if (!typefaceFactoryInstalled) {
        failedHooks += "ComposeTypefaceFactory:" +
            (typefaceFactoryFailure?.javaClass?.simpleName ?: "IllegalStateException")
    }

    val attemptedLayouts = mutableSetOf<Pair<String, String>>()
    val installedLayoutBaselines = mutableSetOf<String>()
    val pendingFailures = linkedMapOf<String, Throwable>()
    val rejectedFailures = linkedMapOf<String, Throwable>()
    for (attempt in 0..1) {
        var rejectedAny = false
        val candidates = hookResolver.resolveClasses(AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT)
        candidates.forEach { resolvedClass ->
            val attemptKey = resolvedClass.baselineClassName to resolvedClass.clazz.name
            if (!attemptedLayouts.add(attemptKey)) return@forEach
            val result = runCatching {
                val layoutClass = resolvedClass.clazz
                val constructors = layoutClass.declaredConstructors.filter { constructor ->
                    val parameterTypes = constructor.parameterTypes
                    parameterTypes.firstOrNull() == CharSequence::class.java &&
                        parameterTypes.any(TextPaint::class.java::isAssignableFrom)
                }
                check(constructors.isNotEmpty()) {
                    "Compose text layout constructor unavailable: ${layoutClass.name}"
                }
                constructors.forEachIndexed { index, constructor ->
                    constructor.isAccessible = true
                    val paintIndex = constructor.parameterTypes.indexOfFirst(
                        TextPaint::class.java::isAssignableFrom,
                    )
                    hookRegistrar.installArgumentRewriteHook(constructor) { chain ->
                        if (appleSystemFontApplyGuard.isActive) {
                            return@installArgumentRewriteHook null
                        }
                        val text = chain.args.firstOrNull() as? CharSequence
                            ?: return@installArgumentRewriteHook null
                        val paint = chain.args.getOrNull(paintIndex) as? TextPaint
                            ?: return@installArgumentRewriteHook null
                        val rewritten = rewriteAppleSystemFontLayoutInput(
                            text = text,
                            paint = paint,
                        ) ?: return@installArgumentRewriteHook null
                        chain.args.toTypedArray().also { args ->
                            args[0] = rewritten.text
                            args[paintIndex] = rewritten.paint
                        }
                    }
                    installedHooks += "ComposeTextLayout.${layoutClass.name}#$index"
                }
            }
            if (result.isSuccess) {
                installedLayoutBaselines += resolvedClass.baselineClassName
                pendingFailures.remove(resolvedClass.baselineClassName)
                rejectedFailures.remove(resolvedClass.baselineClassName)
                return@forEach
            }
            val throwable = result.exceptionOrNull() ?: return@forEach
            if (attempt == 0 && hookResolver.rejectClassResolution(
                    hookPoint = AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
                    resolved = resolvedClass,
                    reason = "${throwable.javaClass.simpleName}: ${throwable.message}",
                )
            ) {
                rejectedAny = true
                rejectedFailures[resolvedClass.baselineClassName] = throwable
            } else {
                pendingFailures[resolvedClass.baselineClassName] = throwable
            }
        }
        if (!rejectedAny) break
        ProviderLogger.info("Apple Compose TextLayout DexKit 定向失效后重试")
    }
    rejectedFailures.forEach { (baselineClassName, throwable) ->
        if (baselineClassName !in installedLayoutBaselines) {
            pendingFailures.putIfAbsent(baselineClassName, throwable)
        }
    }
    pendingFailures.forEach { (baselineClassName, throwable) ->
        failedHooks += "$baselineClassName:${throwable.javaClass.simpleName}"
    }
}
