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

internal fun AppleSystemFontHooks.hookAppleLyricsWordFontMeasurement(
    customTextViewClass: Class<*>?,
    installedHooks: MutableList<String>,
    failedHooks: MutableList<String>,
) {
    customTextViewClass ?: run {
        failedHooks += "LyricsWordFontMeasurement:CustomTextViewUnavailable"
        return
    }
    hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER)
        .forEach { resolvedClass ->
        val className = resolvedClass.target.className
        val adapterClass = resolvedClass.clazz
        runCatching {
            val methods = generateSequence(adapterClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .filter { method ->
                    method.returnType.name == "android.util.ArrayMap" &&
                        method.parameterTypes.firstOrNull()?.name == lyricsWordVectorClassName
                }
                .distinctBy { method ->
                    method.name to method.parameterTypes.joinToString { it.name }
                }
                .toList()
            methods.forEach { method ->
                if (!appleSystemFontLyricsRenderHookedMethods.add(method)) {
                    return@forEach
                }
                method.isAccessible = true
                hookRegistrar.installScopedHook(
                    executable = method,
                    enter = enter@{ chain ->
                        val measurementText = nativeRawWordVectorText(
                            chain.args.firstOrNull()
                        )?.takeIf(
                            AppleSystemFontWeightPolicy::shouldReplaceTextContent
                        ) ?: return@enter false
                        appleSystemFontLyricsMeasurementTexts.push(measurementText)
                        applyAppleLyricsTemplateFontsForMeasurement(
                            adapter = chain.thisObject,
                            sampleText = measurementText,
                        )
                        true
                    },
                    after = { _, _ -> Unit },
                    exit = { appleSystemFontLyricsMeasurementTexts.pop() },
                )
                installedHooks +=
                    "LyricsWordFontMeasurement.${method.declaringClass.name}#${method.name}"
            }
        }.onFailure { throwable ->
            failedHooks += "LyricsWordFontMeasurement.$className:${throwable.javaClass.simpleName}"
        }
    }
}

internal fun AppleSystemFontHooks.hookAppleLyricsMeasureTextDiagnostics(
    installedHooks: MutableList<String>,
    failedHooks: MutableList<String>,
) {
    if (!BuildConfig.DEBUG) return
    runCatching {
        val measureText = Paint::class.java.getDeclaredMethod(
            "measureText",
            String::class.java,
        ).apply { isAccessible = true }
        hookRegistrar.installHook(measureText, after = { chain, result ->
            if (appleSystemFontLyricsMeasureDiagnosticGuard.isActive) {
                return@installHook
            }
            val lineText = appleSystemFontLyricsMeasurementTexts.current
                ?: return@installHook
            val measuredText = chain.args.firstOrNull() as? String
                ?: return@installHook
            val paint = chain.thisObject as? Paint ?: return@installHook
            val currentTypeface = paint.typeface ?: return@installHook
            val actualWidth = (result as? Number)?.toFloat() ?: return@installHook
            val expectedTypeface = appleSystemTypefaceForText(
                current = currentTypeface,
                text = lineText,
                textSizePx = paint.textSize,
            ) ?: return@installHook
            val expectedWidth = appleSystemFontLyricsMeasureDiagnosticGuard.run {
                Paint(paint).apply { typeface = expectedTypeface }
                    .measureText(measuredText)
            }
            val originalTypeface = synchronized(appleSystemFontOriginalTypefacesByReplacement) {
                appleSystemFontOriginalTypefacesByReplacement[currentTypeface]
            }
            if (originalTypeface != null && originalTypeface !== currentTypeface) {
                val originalWidth = appleSystemFontLyricsMeasureDiagnosticGuard.run {
                    Paint(paint).apply { typeface = originalTypeface }
                        .measureText(measuredText)
                }
                val baselineDelta = actualWidth - originalWidth
                val baselineKey = listOf(
                    measuredText.take(32),
                    paint.textSize.roundToInt(),
                    currentTypeface.weight,
                    originalTypeface.weight,
                    (baselineDelta * 10f).roundToInt(),
                ).joinToString(":")
                if (
                    appleSystemFontLyricsMeasureBaselineKeys.size < 256 &&
                    appleSystemFontLyricsMeasureBaselineKeys.add(baselineKey)
                ) {
                    ProviderLogger.diagnostic(
                        "Apple 歌词逐字字体宽度基线: text=${measuredText.take(48)}, " +
                            "line=${lineText.take(48)}, textSizePx=${paint.textSize}, " +
                            "originalWeight=${originalTypeface.weight}, " +
                            "replacementWeight=${currentTypeface.weight}, " +
                            "originalWidth=$originalWidth, replacementWidth=$actualWidth, " +
                            "delta=$baselineDelta"
                    )
                }
            }
            val currentSignature = synchronized(appleSystemFontSignaturesByReplacement) {
                appleSystemFontSignaturesByReplacement[currentTypeface]
            }
            val expectedSignature = synchronized(appleSystemFontSignaturesByReplacement) {
                appleSystemFontSignaturesByReplacement[expectedTypeface]
            }
            val sameTypeface = currentTypeface === expectedTypeface ||
                currentSignature != null && currentSignature == expectedSignature
            val widthDelta = expectedWidth - actualWidth
            if (sameTypeface && abs(widthDelta) < 0.25f) return@installHook
            val diagnosticKey = listOf(
                measuredText.take(32),
                currentTypeface.weight,
                expectedTypeface.weight,
                (widthDelta * 10f).roundToInt(),
            ).joinToString(":")
            if (appleSystemFontLyricsMeasureDiagnosticKeys.size >= 256 ||
                !appleSystemFontLyricsMeasureDiagnosticKeys.add(diagnosticKey)
            ) {
                return@installHook
            }
            ProviderLogger.diagnostic(
                "Apple 歌词逐字测量诊断: text=${measuredText.take(48)}, " +
                    "line=${lineText.take(48)}, textSizePx=${paint.textSize}, " +
                    "currentWeight=${currentTypeface.weight}, " +
                    "expectedWeight=${expectedTypeface.weight}, " +
                    "sameTypeface=$sameTypeface, actualWidth=$actualWidth, " +
                    "expectedWidth=$expectedWidth, delta=$widthDelta"
            )
        })
        installedHooks += "Paint.measureText(String)[debug]"
    }.onFailure { throwable ->
        failedHooks += "Paint.measureText(String):${throwable.javaClass.simpleName}"
    }
}

internal fun AppleSystemFontHooks.hookAppleLyricsGradientDiagnostics(
    installedHooks: MutableList<String>,
    failedHooks: MutableList<String>,
) {
    if (!BuildConfig.DEBUG) return

    runCatching {
        val getAnimatedFraction = ValueAnimator::class.java.getDeclaredMethod(
            "getAnimatedFraction",
        ).apply { isAccessible = true }
        hookRegistrar.installHook(getAnimatedFraction, after = { chain, result ->
            if (!isFollowSystemFontWeightEnabled()) return@installHook
            val animator = chain.thisObject as? ValueAnimator ?: return@installHook
            val fraction = (result as? Number)?.toFloat() ?: return@installHook
            appleLyricsGradientAnimatorSample.set(
                AppleLyricsGradientAnimatorSample(
                    animatorIdentity = System.identityHashCode(animator),
                    animatedFraction = fraction,
                    currentPlayTimeMs = animator.currentPlayTime,
                    durationMs = animator.duration,
                    capturedAtUptimeMs = SystemClock.uptimeMillis(),
                )
            )
        })
        installedHooks += "ValueAnimator.getAnimatedFraction[lyrics-gradient-debug]"
    }.onFailure { throwable ->
        failedHooks +=
            "ValueAnimator.getAnimatedFraction:${throwable.javaClass.simpleName}"
    }

    runCatching {
        val resolved = hookResolver.resolveMethod(
            AppleMusicHookPoint.LYRICS_GRADIENT_MASK_UPDATE
        )
        val target = resolved.target
        val maskClass = resolved.method.declaringClass
        val layoutClass = classLoader.loadClass(
            target.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_GRADIENT_LAYOUT_CLASS_NAME
            )
        )
        val updateMask = resolved.method
        val startChildField = maskClass.getDeclaredField(
            target.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_GRADIENT_MASK_START_CHILD_FIELD
            )
        ).apply { isAccessible = true }
        val endChildField = maskClass.getDeclaredField(
            target.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_GRADIENT_MASK_END_CHILD_FIELD
            )
        ).apply { isAccessible = true }
        val positionsField = maskClass.getDeclaredField(
            target.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_GRADIENT_MASK_POSITIONS_FIELD
            )
        ).apply { isAccessible = true }
        val fractionField = maskClass.getDeclaredField(
            target.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_GRADIENT_MASK_FRACTION_FIELD
            )
        ).apply { isAccessible = true }
        val layoutField = maskClass.declaredFields.first { field ->
            field.type == layoutClass
        }.apply { isAccessible = true }

        hookRegistrar.installHook(updateMask, after = { chain, _ ->
            if (!isFollowSystemFontWeightEnabled()) return@installHook
            val mask = chain.thisObject ?: return@installHook
            val inputFraction = (chain.args.getOrNull(2) as? Number)?.toFloat()
                ?: return@installHook
            val positions = (positionsField.get(mask) as? FloatArray)
                ?.copyOf()
                ?: (chain.args.getOrNull(1) as? FloatArray)?.copyOf()
                ?: return@installHook
            val now = SystemClock.uptimeMillis()
            val maskIdentity = System.identityHashCode(mask)
            val lastLoggedAt = appleLyricsGradientLastLogAt[maskIdentity] ?: Long.MIN_VALUE
            if (now - lastLoggedAt < 100L) return@installHook
            appleLyricsGradientLastLogAt[maskIdentity] = now

            val animatorSample = appleLyricsGradientAnimatorSample.get()
                ?.takeIf { now - it.capturedAtUptimeMs <= 16L }
            val layout = layoutField.get(mask) as? View
            val storedFraction = (fractionField.get(mask) as? Number)?.toFloat()
            val startChild = (startChildField.get(mask) as? Number)?.toInt()
            val endChild = (endChildField.get(mask) as? Number)?.toInt()
            ProviderLogger.diagnostic(
                "Apple 歌词逐字渐变诊断: mask=$maskIdentity, " +
                    "layout=${layout?.let(System::identityHashCode)}, " +
                    "childRange=$startChild..$endChild, inputFraction=$inputFraction, " +
                    "storedFraction=$storedFraction, positions=${positions.contentToString()}, " +
                    "animator=${animatorSample?.animatorIdentity}, " +
                    "animatorFraction=${animatorSample?.animatedFraction}, " +
                    "playTime=${animatorSample?.currentPlayTimeMs}, " +
                    "duration=${animatorSample?.durationMs}, " +
                    "playbackPosition=${currentPlaybackPositionMs()}, " +
                    "lyricsSongId=${currentSongId()}, " +
                    "fontScale=${currentMiuiFontWeightScale()}"
            )
        })
        installedHooks +=
            "FullWidthAlphaGradientFlexboxLayout.Mask#b[lyrics-gradient-debug]"
    }.onFailure { throwable ->
        failedHooks +=
            "FullWidthAlphaGradientFlexboxLayout.Mask#b:${throwable.javaClass.simpleName}"
    }
}

internal fun AppleSystemFontHooks.applyAppleLyricsTemplateFontsForMeasurement(
    adapter: Any?,
    sampleText: String,
) {
    adapter ?: return
    val paths = appleSystemFontLyricsTemplateFieldPaths.getOrPut(adapter.javaClass) {
        resolveAppleLyricsTemplateFieldPaths(adapter.javaClass)
    }
    if (paths.isEmpty()) return
    var applied = 0
    paths.forEach { path ->
        val view = path.get(adapter) ?: return@forEach
        applyAppleSystemFontForTextView(
            view = view,
            textOverride = sampleText,
            stage = "lyrics_word_template_measure",
            requestLayout = false,
        )
        applied += 1
    }
    if (BuildConfig.DEBUG && applied > 0) {
        val traceKey = "lyrics_template_font:${adapter.javaClass.name}:$applied"
        if (appleSystemFontDebugTraceKeys.add(traceKey)) {
            ProviderLogger.debug(
                "Apple 歌词逐字模板测量字体已同步：adapter=${adapter.javaClass.name}, " +
                    "views=$applied, text=${sampleText.take(24)}"
            )
        }
    }
}

internal fun AppleSystemFontHooks.resolveAppleLyricsTemplateFieldPaths(
    adapterClass: Class<*>,
): List<AppleSystemFontTemplateFieldPath> {
    val bindingBaseClass = runCatching {
        classLoader.loadClass("androidx.databinding.ViewDataBinding")
    }.getOrNull()
    return generateSequence(adapterClass) { it.superclass }
        .flatMap { owner -> owner.declaredFields.asSequence() }
        .mapNotNull { bindingField ->
            if (bindingBaseClass == null ||
                !bindingBaseClass.isAssignableFrom(bindingField.type)
            ) {
                return@mapNotNull null
            }
            val textField = bindingField.type.declaredFields.firstOrNull { field ->
                TextView::class.java.isAssignableFrom(field.type) &&
                    field.type.name == customTextViewClassName
            } ?: return@mapNotNull null
            runCatching {
                bindingField.isAccessible = true
                textField.isAccessible = true
                AppleSystemFontTemplateFieldPath(bindingField, textField)
            }.getOrNull()
        }
        .toList()
}

internal fun AppleSystemFontHooks.rewriteAppleSystemFontLayoutInput(
    text: CharSequence,
    paint: TextPaint,
): AppleSystemFontLayoutInput? {
    if (!AppleSystemFontWeightPolicy.shouldReplaceTextContent(text)) return null

    val rewrittenPaint = TextPaint(paint)
    var paintChanged = false
    val originalPaintTypeface = rewrittenPaint.typeface
    val replacementPaintTypeface = appleSystemTypefaceForText(
        current = originalPaintTypeface,
        text = text,
        textSizePx = rewrittenPaint.textSize,
    )
    if (
        replacementPaintTypeface != null &&
        replacementPaintTypeface !== originalPaintTypeface
    ) {
        rewrittenPaint.typeface = replacementPaintTypeface
        paintChanged = true
    }

    var rewrittenText: CharSequence = text
    var rewrittenSpanCount = 0
    if (text is Spanned) {
        var spannable: SpannableString? = null
        text.getSpans(0, text.length, MetricAffectingSpan::class.java)
            .forEach { span ->
                val currentSpanTypeface = metricSpanTypeface(span)
                    ?: return@forEach
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                if (start < 0 || end <= start || end > text.length) return@forEach
                val replacementSpanTypeface = appleSystemTypefaceForText(
                    current = currentSpanTypeface,
                    text = text.subSequence(start, end),
                    textSizePx = rewrittenPaint.textSize,
                ) ?: return@forEach
                if (replacementSpanTypeface === currentSpanTypeface) return@forEach
                val replacementSpan = createTypefaceMetricSpan(
                    original = span,
                    replacement = replacementSpanTypeface,
                ) ?: return@forEach
                val mutableText = spannable ?: SpannableString(text).also {
                    spannable = it
                }
                val flags = text.getSpanFlags(span)
                mutableText.removeSpan(span)
                mutableText.setSpan(replacementSpan, start, end, flags)
                rewrittenSpanCount += 1
            }
        spannable?.let { rewrittenText = it }
    }

    if (!paintChanged && rewrittenSpanCount == 0) return null
    if (BuildConfig.DEBUG) {
        val traceKey = listOf(
            "compose_font_layout",
            originalPaintTypeface?.weight,
            rewrittenPaint.typeface?.weight,
            AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(text),
            rewrittenSpanCount,
            isFollowSystemFontWeightEnabled(),
        ).joinToString(":")
        if (appleSystemFontDebugTraceKeys.add(traceKey)) {
            ProviderLogger.debug(
                "Apple Compose 字体粗细布局：paintChanged=$paintChanged, " +
                    "spans=$rewrittenSpanCount, " +
                    "cjkFallback=" +
                    AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(text) +
                    ", textSizePx=${rewrittenPaint.textSize}, " +
                    "enabled=${isFollowSystemFontWeightEnabled()}"
            )
        }
    }
    return AppleSystemFontLayoutInput(
        text = rewrittenText,
        paint = rewrittenPaint,
    )
}

