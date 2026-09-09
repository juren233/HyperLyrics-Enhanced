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

internal fun AppleSystemFontHooks.logAppleSystemFontDrawState(view: TextView) {
    if (!BuildConfig.DEBUG || (!isFollowSystemFontEnabled() && !isFollowSystemFontWeightEnabled())) return
    val viewTypeface = view.typeface
    val paint = view.paint
    val paintTypeface = paint.typeface
    // onDraw 诊断不能调用 CustomTextView.getText()，否则会重新进入 Future 解析 Hook。
    val text = view.layout?.text
        ?.toString()
        ?.replace('\n', ' ')
        ?.take(32)
        .orEmpty()
    val traceKey = listOf(
        "font_draw",
        System.identityHashCode(view),
        viewTypeface?.weight,
        paintTypeface?.weight,
        text,
    ).joinToString(":")
    if (!appleSystemFontDebugTraceKeys.add(traceKey)) return
    ProviderLogger.debug(
        "Apple 系统字体粗细最终绘制：view=${view.javaClass.name}@" +
            System.identityHashCode(view) +
            ", text=$text, viewWeight=${viewTypeface?.weight}, " +
            "paintWeight=${paintTypeface?.weight}, " +
            "sameTypeface=${viewTypeface === paintTypeface}, " +
            "viewVariationInstance=${isAppleTypefaceVariationInstance(viewTypeface)}, " +
            "paintVariationInstance=${isAppleTypefaceVariationInstance(paintTypeface)}, " +
            "variation=${runCatching { paint.fontVariationSettings }.getOrNull()}, " +
            "fakeBold=${paint.isFakeBoldText}, textSizePx=${paint.textSize}"
    )
}

internal fun AppleSystemFontHooks.applyAppleSystemFontForTextView(
    view: TextView,
    textOverride: CharSequence? = null,
    stage: String,
    requestLayout: Boolean = true,
) {
    if (appleSystemFontApplyGuard.isActive) return

    appleSystemFontApplyGuard.run {
        val state = synchronized(appleSystemFontTrackedTextViews) {
            appleSystemFontTrackedTextViews[view]
        }
        val current = view.typeface ?: return@run
        val originalFromReplacement = synchronized(appleSystemFontOriginalTypefacesByReplacement) {
            appleSystemFontOriginalTypefacesByReplacement[current]
        }
        val content = textOverride ?: view.text
        val activeState = state?.takeIf {
            current === it.appliedTypeface || current === it.originalTypeface
        }
        val original = originalFromReplacement ?: activeState?.originalTypeface ?: current
        val originalStyle = activeState?.originalStyle ?: original.style
        val systemFontEnabled = isFollowSystemFontEnabled()
        val systemFontWeightEnabled = isFollowSystemFontWeightEnabled()
        val appliedSignature = synchronized(appleSystemFontSignaturesByReplacement) {
            appleSystemFontSignaturesByReplacement[current]
        }
        val requestedWeight = activeState?.requestedWeight
            ?: appliedSignature?.semanticWeight
            ?: original.weight
        val italic = activeState?.italic
            ?: appliedSignature?.italic
            ?: original.isItalic
        val mode = AppleSystemFontWeightPolicy.fontMode(
            systemFontEnabled, systemFontWeightEnabled, content,
        )
        val useSystemFont = mode == AppleSystemFontWeightPolicy.FontMode.SYSTEM
        val useAppleWeight = mode == AppleSystemFontWeightPolicy.FontMode.APPLE_WEIGHT
        if (useAppleWeight && originalFromReplacement != null) {
            val semanticWeight = AppleSystemFontWeightPolicy.semanticWeight(
                reportedWeight = requestedWeight,
                isBold = original.isBold,
            )
            val expectedSignature = AppleSystemFontReplacementSignature(
                effectiveSfProWeight = mappedAppleSystemFontWeight(semanticWeight),
                semanticWeight = semanticWeight,
                usesCjkFallback = AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(content),
                italic = italic,
            )
            if (appliedSignature == expectedSignature) {
                rememberTextViewState(
                    view = view,
                    originalTypeface = original,
                    requestedWeight = requestedWeight,
                    italic = italic,
                    originalStyle = originalStyle,
                    appliedTypeface = current,
                )
                return@run
            }
        }
        val target = when {
            useSystemFont -> createSystemDefaultTypeface(requestedWeight, italic, view.textSize)
            useAppleWeight -> createAppleWeightAdjustedTypeface(
                original = original,
                requestedWeight = requestedWeight,
                italic = italic,
                text = content,
                textSizePx = view.textSize,
            )
            else -> original
        }
        if (AppleSystemFontWeightPolicy.shouldReplaceTextContent(content) || state != null || originalFromReplacement != null) {
            rememberTextViewState(
                view = view,
                originalTypeface = original,
                requestedWeight = requestedWeight,
                italic = italic,
                originalStyle = originalStyle,
                appliedTypeface = target,
            )
        }
        if (current === target) return@run
        if (useSystemFont || useAppleWeight) {
            view.setTypeface(target)
        } else {
            view.setTypeface(target, originalStyle)
        }
        if (requestLayout) view.requestLayout()
        view.invalidate()
        if (useAppleWeight || useSystemFont) {
            logAppleSystemFontReplacement(
                stage = stage,
                resourceName = null,
                original = original,
                replacement = target,
                requestedWeight = requestedWeight,
            )
        }
    }
}

internal fun AppleSystemFontHooks.rememberAppleSystemFontReplacement(
    replacement: Typeface,
    original: Typeface,
    effectiveWeight: Int,
    semanticWeight: Int,
    usesCjkFallback: Boolean,
    italic: Boolean,
) {
    if (replacement === original) return
    synchronized(appleSystemFontOriginalTypefacesByReplacement) {
        appleSystemFontOriginalTypefacesByReplacement[replacement] = original
    }
    synchronized(appleSystemFontSignaturesByReplacement) {
        appleSystemFontSignaturesByReplacement[replacement] =
            AppleSystemFontReplacementSignature(
                effectiveSfProWeight = effectiveWeight,
                semanticWeight = semanticWeight,
                usesCjkFallback = usesCjkFallback,
                italic = italic,
            )
    }
}

internal fun AppleSystemFontHooks.logAppleSystemFontReplacement(
    stage: String,
    resourceName: String?,
    original: Typeface,
    replacement: Typeface,
    requestedWeight: Int,
) {
    if (!BuildConfig.DEBUG) return
    val traceKey = listOf(
        stage,
        resourceName.orEmpty(),
        requestedWeight,
        original.weight,
        replacement.weight,
        original.isItalic,
    ).joinToString(":")
    if (!appleSystemFontDebugTraceKeys.add(traceKey)) return
    ProviderLogger.debug(
        "Apple 系统字体粗细替换：stage=$stage, resource=$resourceName, " +
            "requestedWeight=$requestedWeight, originalWeight=${original.weight}, " +
            "resultWeight=${replacement.weight}, italic=${original.isItalic}, " +
            "scale=${currentMiuiFontWeightScale()}"
    )
}

internal fun AppleSystemFontHooks.currentMiuiFontWeightScale(forceRefresh: Boolean = false): Int {
    val now = SystemClock.uptimeMillis()
    val lastRead = appleSystemFontScaleLastReadUptimeMillis
    if (!forceRefresh && lastRead >= 0L && now - lastRead < 500L) {
        return appleSystemFontScaleCache
    }
    return synchronized(appleSystemFontScaleLock) {
        val synchronizedLastRead = appleSystemFontScaleLastReadUptimeMillis
        if (!forceRefresh && synchronizedLastRead >= 0L && now - synchronizedLastRead < 500L) {
            return@synchronized appleSystemFontScaleCache
        }
        val resolver = application.contentResolver
        val scale = runCatching {
            Settings.System.getInt(resolver, AppleSystemFontHooks.SYSTEM_FONT_WEIGHT_SCALE_KEY)
        }.getOrNull() ?: runCatching {
            Settings.Global.getInt(resolver, AppleSystemFontHooks.SYSTEM_FONT_WEIGHT_SCALE_KEY)
        }.getOrNull() ?: 50
        appleSystemFontScaleCache = scale.coerceIn(0, 100)
        appleSystemFontScaleLastReadUptimeMillis = now
        appleSystemFontScaleCache
    }
}
