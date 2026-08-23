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


internal class AppleSystemFontHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> android.content.SharedPreferences?,
    private val currentSongId: () -> String?,
    private val nativeRawWordVectorText: (Any?) -> String?,
    private val currentPlaybackPositionMs: () -> Long?,
) {
    private companion object {
        const val MAX_APPLE_SYSTEM_FONT_VARIATION_CACHE_ENTRIES = 64
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
    private val contentUiLanguagePrefs: android.content.SharedPreferences?
        get() = preferences()
    private val lyricsWordVectorClassName by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS).target.className
    }
    private val customTextViewClassName by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW).target.className
    }

    private val appleSystemFontManagedTypefaces = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Typeface, Boolean>())
    )
    private val appleSystemFontOriginalTypefacesByReplacement =
        Collections.synchronizedMap(WeakHashMap<Typeface, Typeface>())
    private val appleSystemFontSignaturesByReplacement =
        Collections.synchronizedMap(WeakHashMap<Typeface, AppleSystemFontReplacementSignature>())
    private val appleSystemFontCompositeCache = ConcurrentHashMap<String, Typeface>()
    private val appleSystemFontVariationCache =
        AppleSystemFontVariationCache<Typeface, Typeface>(
            MAX_APPLE_SYSTEM_FONT_VARIATION_CACHE_ENTRIES,
        )
    private val appleSystemFontTrackedTextViews =
        Collections.synchronizedMap(WeakHashMap<TextView, AppleSystemFontTextViewState>())
    private val appleSystemFontLyricsRenderHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    private val appleSystemFontLyricsTemplateFieldPaths =
        ConcurrentHashMap<Class<*>, List<AppleSystemFontTemplateFieldPath>>()
    private val appleSystemFontLyricsMeasurementTexts = ThreadLocalStack<String>()
    private val appleSystemFontApplyGuard = ThreadLocalReentryGuard()
    private val appleSystemFontLyricsMeasureDiagnosticGuard = ThreadLocalReentryGuard()
    private val appleSystemFontLyricsMeasureDiagnosticKeys =
        ConcurrentHashMap.newKeySet<String>()
    private val appleSystemFontLyricsMeasureBaselineKeys =
        ConcurrentHashMap.newKeySet<String>()
    private val appleLyricsGradientAnimatorSample =
        ThreadLocal<AppleLyricsGradientAnimatorSample?>()
    private val appleLyricsGradientLastLogAt = ConcurrentHashMap<Int, Long>()
    private val appleSystemFontDebugTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val appleSystemFontScaleLock = Any()
    @Volatile
    private var appleSystemFontScaleCache = 50
    @Volatile
    private var appleSystemFontScaleLastReadUptimeMillis = -1L
    @Volatile
    private var hyperOsFontSettingsLastSyncedScale = -1
    private val appleSystemFontVariationMethods: AppleSystemFontVariationMethods? by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        resolveAppleSystemFontVariationMethods()
    }
    private val hyperOsFontWeightMethods: HyperOsFontWeightMethods? by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        resolveHyperOsFontWeightMethods()
    }

    private fun isFollowSystemFontWeightEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT,
        ) == true

    fun refreshAppleSystemFontWeight() {
        mainHandler.post {
            val enabled = isFollowSystemFontWeightEnabled()
            appleSystemFontVariationCache.clear()
            if (enabled) currentMiuiFontWeightScale(forceRefresh = true)
            val trackedViews = synchronized(appleSystemFontTrackedTextViews) {
                appleSystemFontTrackedTextViews.entries.map { it.key to it.value }
            }
            trackedViews.forEach { (view, state) ->
                val target = if (enabled) {
                    createAppleWeightAdjustedTypeface(
                        original = state.originalTypeface,
                        requestedWeight = state.requestedWeight,
                        italic = state.italic,
                        textView = view,
                    )
                } else {
                    state.originalTypeface
                }
                appleSystemFontApplyGuard.run {
                    if (enabled) {
                        view.setTypeface(target)
                    } else {
                        view.setTypeface(target, state.originalStyle)
                    }
                }
                view.requestLayout()
                view.invalidate()
            }
            if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "Apple 系统字体粗细已刷新：enabled=$enabled, " +
                        "views=${trackedViews.size}, scale=${currentMiuiFontWeightScale()}"
                )
            }
        }
    }


    fun hookAppleSystemFontWeight() {
        val installedHooks = mutableListOf<String>()
        val failedHooks = mutableListOf<String>()
        val resolvedCustomTextView = runCatching {
            hookResolver.resolveClass(AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW)
        }.getOrNull()
        val customTextViewClass = resolvedCustomTextView?.clazz
        fun customTextViewMember(member: AppleMusicRuntimeMember): String =
            resolvedCustomTextView?.target?.runtimeMemberName(member)
                ?: error("CustomTextView runtime member unavailable: $member")

        runCatching {
            val getFont = Resources::class.java.getDeclaredMethod(
                "getFont",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            hookRegistrar.installResultOverrideHook(getFont) { chain, original ->
                val resources = chain.thisObject as? Resources
                    ?: return@installResultOverrideHook original
                val resourceId = (chain.args.firstOrNull() as? Number)?.toInt()
                    ?: return@installResultOverrideHook original
                val typeface = original as? Typeface
                    ?: return@installResultOverrideHook original
                replaceAppleFontResource(resources, resourceId, typeface)
            }
            installedHooks += "Resources.getFont"
        }.onFailure { throwable ->
            failedHooks += "Resources.getFont:${throwable.javaClass.simpleName}"
        }

        runCatching {
            val createWithWeight = Typeface::class.java.getDeclaredMethod(
                "create",
                Typeface::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            hookRegistrar.installResultOverrideHook(createWithWeight) { chain, originalResult ->
                if (
                    appleSystemFontApplyGuard.isActive ||
                    !isFollowSystemFontWeightEnabled()
                ) {
                    return@installResultOverrideHook originalResult
                }
                val base = chain.args.getOrNull(0) as? Typeface
                    ?: return@installResultOverrideHook originalResult
                val originalTypeface = originalAppleTypeface(base)
                    ?: return@installResultOverrideHook originalResult
                val requestedWeight = (chain.args.getOrNull(1) as? Number)?.toInt()
                    ?: originalTypeface.weight
                val italic = chain.args.getOrNull(2) as? Boolean
                    ?: originalTypeface.isItalic
                createAppleWeightAdjustedTypeface(
                    original = originalTypeface,
                    requestedWeight = requestedWeight,
                    italic = italic,
                ).also { replacement ->
                    logAppleSystemFontReplacement(
                        stage = "typeface_create",
                        resourceName = null,
                        original = originalTypeface,
                        replacement = replacement,
                        requestedWeight = requestedWeight,
                    )
                }
            }
            installedHooks += "Typeface.create(weight)"
        }.onFailure { throwable ->
            failedHooks += "Typeface.create(weight):${throwable.javaClass.simpleName}"
        }

        runCatching {
            val setTypeface = TextView::class.java.getDeclaredMethod(
                "setTypeface",
                Typeface::class.java,
            ).apply { isAccessible = true }
            hookRegistrar.installArgumentRewriteHook(setTypeface) { chain ->
                if (appleSystemFontApplyGuard.isActive) {
                    return@installArgumentRewriteHook null
                }
                val view = chain.thisObject as? TextView
                    ?: return@installArgumentRewriteHook null
                val requested = chain.args.firstOrNull() as? Typeface
                    ?: return@installArgumentRewriteHook null
                val originalTypeface = originalAppleTypeface(requested)
                    ?: return@installArgumentRewriteHook null
                synchronized(appleSystemFontTrackedTextViews) {
                    appleSystemFontTrackedTextViews[view] =
                        AppleSystemFontTextViewState(
                            originalTypeface = originalTypeface,
                            requestedWeight = originalTypeface.weight,
                            italic = originalTypeface.isItalic,
                            originalStyle = originalTypeface.style,
                        )
                }
                if (!isFollowSystemFontWeightEnabled()) {
                    return@installArgumentRewriteHook if (requested === originalTypeface) {
                        null
                    } else {
                        arrayOf(originalTypeface)
                    }
                }
                val replacement = createAppleWeightAdjustedTypeface(
                    original = originalTypeface,
                    textView = view,
                )
                logAppleSystemFontReplacement(
                    stage = "text_view",
                    resourceName = null,
                    original = originalTypeface,
                    replacement = replacement,
                    requestedWeight = originalTypeface.weight,
                )
                if (replacement === requested) null else arrayOf(replacement)
            }
            installedHooks += "TextView.setTypeface"
        }.onFailure { throwable ->
            failedHooks += "TextView.setTypeface:${throwable.javaClass.simpleName}"
        }

        runCatching {
            val styledTypefaceOwner = customTextViewClass?.superclass
                ?: error("CustomTextView superclass unavailable")
            val styledTypeface = styledTypefaceOwner.getDeclaredMethod(
                customTextViewMember(AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_SET_TYPEFACE_METHOD),
                Typeface::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            hookRegistrar.installArgumentRewriteHook(styledTypeface) { chain ->
                if (appleSystemFontApplyGuard.isActive) {
                    return@installArgumentRewriteHook null
                }
                val view = chain.thisObject as? TextView
                    ?: return@installArgumentRewriteHook null
                val requested = chain.args.getOrNull(0) as? Typeface
                    ?: return@installArgumentRewriteHook null
                val requestedStyle = (chain.args.getOrNull(1) as? Number)?.toInt()
                    ?: Typeface.NORMAL
                val originalTypeface = originalAppleTypeface(requested)
                    ?: return@installArgumentRewriteHook null
                val bold = requestedStyle and Typeface.BOLD != 0
                val italic = originalTypeface.isItalic ||
                    requestedStyle and Typeface.ITALIC != 0
                val requestedWeight = if (bold) {
                    maxOf(originalTypeface.weight, 700)
                } else {
                    originalTypeface.weight
                }
                synchronized(appleSystemFontTrackedTextViews) {
                    appleSystemFontTrackedTextViews[view] =
                        AppleSystemFontTextViewState(
                            originalTypeface = originalTypeface,
                            requestedWeight = requestedWeight,
                            italic = italic,
                            originalStyle = requestedStyle,
                        )
                }
                if (!isFollowSystemFontWeightEnabled()) {
                    return@installArgumentRewriteHook if (requested === originalTypeface) {
                        null
                    } else {
                        arrayOf(originalTypeface, requestedStyle)
                    }
                }
                val replacement = createAppleWeightAdjustedTypeface(
                    original = originalTypeface,
                    requestedWeight = requestedWeight,
                    italic = italic,
                    textView = view,
                )
                logAppleSystemFontReplacement(
                    stage = "custom_text_view_style",
                    resourceName = null,
                    original = originalTypeface,
                    replacement = replacement,
                    requestedWeight = requestedWeight,
                )
                arrayOf(replacement, Typeface.NORMAL)
            }
            installedHooks += "CustomTextView.setTypeface(style)"
        }.onFailure { throwable ->
            failedHooks += "CustomTextView.setTypeface(style):${throwable.javaClass.simpleName}"
        }

        runCatching {
            val setText = TextView::class.java.getDeclaredMethod(
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java,
            ).apply { isAccessible = true }
            hookRegistrar.installHook(setText, after = { chain, _ ->
                val view = chain.thisObject as? TextView ?: return@installHook
                val text = chain.args.firstOrNull() as? CharSequence
                applyAppleSystemFontForTextView(
                    view = view,
                    textOverride = text,
                    stage = "text_view_set_text",
                )
            })
            installedHooks += "TextView.setText(CharSequence,BufferType)"
        }.onFailure { throwable ->
            failedHooks += "TextView.setText(CharSequence,BufferType):${throwable.javaClass.simpleName}"
        }

        runCatching {
            val customSetText = customTextViewClass?.getDeclaredMethod(
                customTextViewMember(AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_SET_TEXT_METHOD),
                CharSequence::class.java,
                TextView.BufferType::class.java,
            )?.apply { isAccessible = true }
                ?: error("CustomTextView.setText(CharSequence,BufferType) unavailable")
            hookRegistrar.installHook(customSetText, after = { chain, _ ->
                val view = chain.thisObject as? TextView ?: return@installHook
                val text = chain.args.firstOrNull() as? CharSequence
                applyAppleSystemFontForTextView(
                    view = view,
                    textOverride = text,
                    stage = "custom_text_view_set_text",
                )
            })
            installedHooks += "CustomTextView.setText(CharSequence,BufferType)"
        }.onFailure { throwable ->
            failedHooks += "CustomTextView.setText(CharSequence,BufferType):${throwable.javaClass.simpleName}"
        }

        runCatching {
            val futureOwner = customTextViewClass?.superclass
                ?: error("CustomTextView Future owner unavailable")
            val futureField = futureOwner.declaredFields.firstOrNull { field ->
                java.util.concurrent.Future::class.java.isAssignableFrom(field.type)
            }?.apply { isAccessible = true }
                ?: error("CustomTextView Future field unavailable")
            // 不依赖 JADX 的 p301q.A 展示包名，只从真实 CustomTextView superclass 取 Future 解析方法。
            val resolveFuture = futureOwner.declaredMethods.firstOrNull { method ->
                method.name == customTextViewMember(
                    AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_FUTURE_RESOLVE_METHOD
                ) &&
                    method.parameterCount == 0 &&
                    method.returnType == Void.TYPE
            }?.apply { isAccessible = true }
                ?: error("CustomTextView Future resolver f() unavailable")
            hookRegistrar.installScopedHook(
                executable = resolveFuture,
                enter = { chain ->
                    val view = chain.thisObject as? TextView ?: return@installScopedHook false
                    runCatching { futureField.get(view) != null }.getOrDefault(false)
                },
                after = { chain, _ ->
                    val view = chain.thisObject as? TextView ?: return@installScopedHook
                    applyAppleSystemFontForTextView(
                        view = view,
                        stage = "text_future_resolved",
                    )
                },
                exit = { Unit },
            )
            installedHooks += "CustomTextView.FutureResolver"
        }.onFailure { throwable ->
            failedHooks += "CustomTextView.FutureResolver:${throwable.javaClass.simpleName}"
        }

        runCatching {
            val getTextMetricsParams = TextView::class.java.getDeclaredMethod(
                "getTextMetricsParams"
            ).apply { isAccessible = true }
            hookRegistrar.installHook(getTextMetricsParams, before = { chain ->
                val measurementText = appleSystemFontLyricsMeasurementTexts.current
                    ?: return@installHook
                val view = chain.thisObject as? TextView ?: return@installHook
                // Apple 创建 PrecomputedText Future 前必须先固定最终字体，否则 Future 会缓存旧字宽。
                applyAppleSystemFontForTextView(
                    view = view,
                    textOverride = measurementText,
                    stage = "lyrics_text_metrics",
                    requestLayout = false,
                )
            })
            installedHooks += "TextView.getTextMetricsParams"
        }.onFailure { throwable ->
            failedHooks += "TextView.getTextMetricsParams:${throwable.javaClass.simpleName}"
        }

        hookAppleLyricsWordFontMeasurement(
            customTextViewClass = customTextViewClass,
            installedHooks = installedHooks,
            failedHooks = failedHooks,
        )
        hookAppleLyricsMeasureTextDiagnostics(
            installedHooks = installedHooks,
            failedHooks = failedHooks,
        )
        hookAppleLyricsGradientDiagnostics(
            installedHooks = installedHooks,
            failedHooks = failedHooks,
        )

        if (BuildConfig.DEBUG) {
            runCatching {
                val onDraw = TextView::class.java.getDeclaredMethod(
                    "onDraw",
                    Canvas::class.java,
                ).apply { isAccessible = true }
                hookRegistrar.installHook(onDraw, before = { chain ->
                    val view = chain.thisObject as? TextView ?: return@installHook
                    logAppleSystemFontDrawState(view)
                })
                installedHooks += "TextView.onDraw[debug]"
            }.onFailure { throwable ->
                failedHooks += "TextView.onDraw:${throwable.javaClass.simpleName}"
            }

            runCatching {
                val onDraw = customTextViewClass?.getDeclaredMethod(
                    customTextViewMember(AppleMusicRuntimeMember.CUSTOM_TEXT_VIEW_ON_DRAW_METHOD),
                    Canvas::class.java,
                )?.apply { isAccessible = true }
                    ?: error("CustomTextView.onDraw unavailable")
                hookRegistrar.installHook(onDraw, before = { chain ->
                    val view = chain.thisObject as? TextView ?: return@installHook
                    logAppleSystemFontDrawState(view)
                })
                installedHooks += "CustomTextView.onDraw[debug]"
            }.onFailure { throwable ->
                failedHooks += "CustomTextView.onDraw:${throwable.javaClass.simpleName}"
            }
        }

        hookAppleComposeSystemFontWeight(
            installedHooks = installedHooks,
            failedHooks = failedHooks,
        )

        if (installedHooks.isNotEmpty()) {
            ProviderLogger.info(
                "Apple 系统字体粗细 Hook 已安装：hooks=${installedHooks.joinToString()}, " +
                    "enabled=${isFollowSystemFontWeightEnabled()}"
            )
        }
        if (failedHooks.isNotEmpty()) {
            ProviderLogger.error(
                "Apple 系统字体粗细 Hook 安装不完整：${failedHooks.joinToString()}"
            )
        }
    }

    private fun hookAppleComposeSystemFontWeight(
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

    private fun hookAppleLyricsWordFontMeasurement(
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

    private fun hookAppleLyricsMeasureTextDiagnostics(
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

    private fun hookAppleLyricsGradientDiagnostics(
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

    private fun applyAppleLyricsTemplateFontsForMeasurement(
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

    private fun resolveAppleLyricsTemplateFieldPaths(
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

    private fun rewriteAppleSystemFontLayoutInput(
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

    private fun appleSystemTypefaceForText(
        current: Typeface?,
        text: CharSequence,
        textSizePx: Float,
    ): Typeface? {
        current ?: return null
        val request = appleSystemFontRequest(current) ?: return current
        if (
            !isFollowSystemFontWeightEnabled() ||
            !AppleSystemFontWeightPolicy.shouldReplaceTextContent(text)
        ) {
            return request.original
        }

        val effectiveWeight = mappedAppleSystemFontWeight(request.semanticWeight)
        val expectedSignature = AppleSystemFontReplacementSignature(
            effectiveSfProWeight = effectiveWeight,
            semanticWeight = request.semanticWeight,
            usesCjkFallback = AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(text),
            italic = request.italic,
        )
        val currentSignature = synchronized(appleSystemFontSignaturesByReplacement) {
            appleSystemFontSignaturesByReplacement[current]
        }
        if (currentSignature == expectedSignature) return current

        return createAppleWeightAdjustedTypeface(
            original = request.original,
            requestedWeight = request.semanticWeight,
            italic = request.italic,
            text = text,
            textSizePx = textSizePx,
        )
    }

    private fun appleSystemFontRequest(typeface: Typeface): AppleSystemFontRequest? {
        val replacementOriginal = synchronized(appleSystemFontOriginalTypefacesByReplacement) {
            appleSystemFontOriginalTypefacesByReplacement[typeface]
        }
        if (replacementOriginal != null) {
            val signature = synchronized(appleSystemFontSignaturesByReplacement) {
                appleSystemFontSignaturesByReplacement[typeface]
            }
            return AppleSystemFontRequest(
                original = replacementOriginal,
                semanticWeight = signature?.semanticWeight
                    ?: AppleSystemFontWeightPolicy.semanticWeight(
                        reportedWeight = replacementOriginal.weight,
                        isBold = replacementOriginal.isBold,
                    ),
                italic = signature?.italic ?: replacementOriginal.isItalic,
            )
        }
        val managed = synchronized(appleSystemFontManagedTypefaces) {
            appleSystemFontManagedTypefaces.contains(typeface)
        }
        if (!managed) return null
        return AppleSystemFontRequest(
            original = typeface,
            semanticWeight = AppleSystemFontWeightPolicy.semanticWeight(
                reportedWeight = typeface.weight,
                isBold = typeface.isBold,
            ),
            italic = typeface.isItalic,
        )
    }

    private fun metricSpanTypeface(span: MetricAffectingSpan): Typeface? {
        if (span is TypefaceSpan) {
            span.typeface?.let { return it }
        }
        var owner: Class<*>? = span.javaClass
        while (owner != null && owner != MetricAffectingSpan::class.java) {
            owner.declaredFields.firstOrNull { field ->
                Typeface::class.java.isAssignableFrom(field.type)
            }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(span) as? Typeface
                }.getOrNull()
            }
            owner = owner.superclass
        }
        return null
    }

    private fun createTypefaceMetricSpan(
        original: MetricAffectingSpan,
        replacement: Typeface,
    ): MetricAffectingSpan? {
        if (original is TypefaceSpan) return TypefaceSpan(replacement)
        val constructor = original.javaClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.contentEquals(arrayOf(Typeface::class.java))
        } ?: return null
        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(replacement) as? MetricAffectingSpan
        }.getOrNull()
    }

    private fun replaceAppleFontResource(
        resources: Resources,
        resourceId: Int,
        original: Typeface,
    ): Typeface {
        val resourceIdentity = runCatching {
            Triple(
                resources.getResourcePackageName(resourceId),
                resources.getResourceTypeName(resourceId),
                resources.getResourceEntryName(resourceId),
            )
        }.getOrNull() ?: return original
        if (!AppleSystemFontWeightPolicy.shouldReplaceFontResource(
                packageName = resourceIdentity.first,
                resourceType = resourceIdentity.second,
                resourceName = resourceIdentity.third,
            )
        ) {
            return original
        }
        appleSystemFontManagedTypefaces.add(original)
        if (!isFollowSystemFontWeightEnabled()) return original

        val replacement = createAppleWeightAdjustedTypeface(original)
        logAppleSystemFontReplacement(
            stage = "font_resource",
            resourceName = resourceIdentity.third,
            original = original,
            replacement = replacement,
            requestedWeight = original.weight,
        )
        return replacement
    }

    private fun originalAppleTypeface(typeface: Typeface): Typeface? {
        synchronized(appleSystemFontOriginalTypefacesByReplacement) {
            appleSystemFontOriginalTypefacesByReplacement[typeface]?.let { return it }
        }
        return synchronized(appleSystemFontManagedTypefaces) {
            typeface.takeIf(appleSystemFontManagedTypefaces::contains)
        }
    }

    private fun createAppleWeightAdjustedTypeface(
        original: Typeface,
        requestedWeight: Int = original.weight,
        italic: Boolean = original.isItalic,
        textView: TextView? = null,
        text: CharSequence? = textView?.text,
        textSizePx: Float? = textView?.textSize,
    ): Typeface {
        val semanticWeight = AppleSystemFontWeightPolicy.semanticWeight(
            reportedWeight = requestedWeight,
            isBold = original.isBold,
        )
        val effectiveWeight = mappedAppleSystemFontWeight(semanticWeight)
        val usesCjkFallback = AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(
            text,
        )
        val cjkComposite = if (usesCjkFallback) {
            createAppleTypefaceWithSystemCjkFallback(
                original = original,
                semanticWeight = semanticWeight,
                effectiveSfProWeight = effectiveWeight,
                italic = italic,
                textSizePx = textSizePx,
            )
        } else {
            null
        }
        val result = cjkComposite ?: createAppleTypefaceWithVariation(
            original = original,
            effectiveWeight = effectiveWeight,
            italic = italic,
        ) ?: original
        rememberAppleSystemFontReplacement(
            replacement = result,
            original = original,
            effectiveWeight = effectiveWeight,
            semanticWeight = semanticWeight,
            usesCjkFallback = cjkComposite != null,
            italic = italic,
        )
        if (BuildConfig.DEBUG) {
            val path = if (result !== original) {
                "apple_typeface_variation_axis"
            } else {
                "apple_typeface_variation_unavailable"
            }
            val traceKey =
                "system_typeface:$path:$semanticWeight:$effectiveWeight:$italic:${text != null}"
            if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                ProviderLogger.debug(
                    "Apple 系统字体粗细生成：path=$path, semanticWeight=$semanticWeight, " +
                        "effectiveWeight=$effectiveWeight, resultWeight=${result.weight}, " +
                        "sameAsOriginal=${result === original}, italic=$italic, " +
                        "cjkFallback=${cjkComposite != null}, " +
                        "variationInstance=${isAppleTypefaceVariationInstance(result)}, " +
                        "textSizePx=$textSizePx, scale=${currentMiuiFontWeightScale()}"
                )
            }
        }
        return result
    }

    private fun createAppleTypefaceWithSystemCjkFallback(
        original: Typeface,
        semanticWeight: Int,
        effectiveSfProWeight: Int,
        italic: Boolean,
        textSizePx: Float?,
    ): Typeface? {
        val methods = hyperOsFontWeightMethods ?: return null
        @Suppress("DEPRECATION")
        val textSizeSp = (textSizePx ?: 16f).let { sizePx ->
            val scaledDensity = application.resources.displayMetrics.scaledDensity
            if (scaledDensity > 0f) sizePx / scaledDensity else sizePx
        }
        val cjkAxis = hyperOsCjkWeightAxis(
            methods = methods,
            semanticWeight = semanticWeight,
            textSizeSp = textSizeSp,
        ) ?: return null
        val cacheKey = listOf(
            System.identityHashCode(original),
            effectiveSfProWeight,
            semanticWeight,
            cjkAxis,
            italic,
            methods.miuiFontPath,
        ).joinToString(":")
        appleSystemFontCompositeCache[cacheKey]?.let { return it }

        return runCatching {
            val originalFamilies = methods.typefaceFontFamiliesField.get(original) as? List<*>
            val originalFamily = originalFamilies
                ?.filterIsInstance<FontFamily>()
                ?.firstOrNull()
                ?: return@runCatching null
            val originalFont = originalFamily.getFont(0)
            val primaryFont = buildFontWithWeight(
                source = originalFont,
                weight = effectiveSfProWeight,
                italic = italic,
                variation = "'wght' $effectiveSfProWeight",
            ) ?: return@runCatching null
            val cjkFont = Font.Builder(File(methods.miuiFontPath))
                .setWeight(semanticWeight.coerceIn(1, 1000))
                .setFontVariationSettings("'wght' $cjkAxis")
                .build()
            val builder = Typeface.CustomFallbackBuilder(
                FontFamily.Builder(primaryFont).build(),
            )
                .addCustomFallback(FontFamily.Builder(cjkFont).build())
                .setStyle(
                    FontStyle(
                        AppleSystemFontWeightPolicy.compositeStyleWeight(semanticWeight),
                        if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT,
                    )
                )
            val composite = builder.build()
            if (appleSystemFontCompositeCache.size >= 512) {
                appleSystemFontCompositeCache.clear()
            }
            appleSystemFontCompositeCache[cacheKey] = composite
            if (BuildConfig.DEBUG) {
                val traceKey = "cjk_fallback:$semanticWeight:$effectiveSfProWeight:$cjkAxis:$italic"
                if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                    ProviderLogger.debug(
                        "Apple 中文系统字体 fallback：font=${methods.miuiFontPath}, " +
                            "semanticWeight=$semanticWeight, sfProAxis=$effectiveSfProWeight, " +
                            "miuiAxis=$cjkAxis, textSizeSp=$textSizeSp, scale=" +
                            currentMiuiFontWeightScale()
                    )
                }
            }
            composite
        }.onFailure { throwable ->
            if (BuildConfig.DEBUG) {
                val traceKey = "cjk_fallback_create:${throwable.javaClass.name}"
                if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                    ProviderLogger.error("Apple 中文系统字体 fallback 创建失败", throwable)
                }
            }
        }.getOrNull()
    }

    private fun buildFontWithWeight(
        source: Font,
        weight: Int,
        italic: Boolean,
        variation: String,
    ): Font? = runCatching {
        val builder = source.file?.let { Font.Builder(it) }
            ?: source.buffer.duplicate().let { Font.Builder(it) }
        builder
            .setTtcIndex(source.ttcIndex)
            .setWeight(weight.coerceIn(1, 1000))
            .setSlant(if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
            .setFontVariationSettings(variation)
            .build()
    }.getOrNull()

    private fun hyperOsCjkWeightAxis(
        methods: HyperOsFontWeightMethods,
        semanticWeight: Int,
        textSizeSp: Float,
    ): Int? {
        val scale = currentMiuiFontWeightScale()
        if (hyperOsFontSettingsLastSyncedScale != scale) {
            synchronized(methods) {
                if (hyperOsFontSettingsLastSyncedScale != scale) {
                    runCatching { methods.loadFontSettingMethod.invoke(null) }
                    runCatching { methods.fontScaleField.setInt(null, scale) }
                    hyperOsFontSettingsLastSyncedScale = scale
                }
            }
        }
        return runCatching {
            val weightIndex = methods.getWeightIdxMethod.invoke(
                null,
                semanticWeight,
                false,
                methods.miuiFontType,
            ) as Int
            methods.getScaleWghtMethod.invoke(
                null,
                weightIndex,
                textSizeSp,
                methods.miuiFontType,
            ) as Int
        }.onFailure { throwable ->
            if (BuildConfig.DEBUG) {
                val traceKey = "cjk_fallback_axis:${throwable.javaClass.name}"
                if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                    ProviderLogger.error("HyperOS 中文字体字重轴读取失败", throwable)
                }
            }
        }.getOrNull()
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private fun resolveHyperOsFontWeightMethods(): HyperOsFontWeightMethods? = runCatching {
        val fontSettingsClass = Class.forName(
            "miui.util.font.FontSettings",
            false,
            classLoader,
        )
        val loadFontSetting = fontSettingsClass.getDeclaredMethod("loadFontSetting")
            .apply { isAccessible = true }
        val fontScaleField = fontSettingsClass.getDeclaredField("sFontScale")
            .apply { isAccessible = true }
        val fontTypeClass = Class.forName(
            "miui.util.font.FontType",
            false,
            classLoader,
        )
        val miuiFontType = requireNotNull(
            (fontTypeClass.enumConstants as Array<*>)
                .first { (it as Enum<*>).name == "MIUI" },
        )
        val fontWghtClass = Class.forName(
            "miui.util.font.FontWght",
            false,
            classLoader,
        )
        val getWeightIdx = fontWghtClass.getDeclaredMethod(
            "getWeightIdx",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            fontTypeClass,
        ).apply { isAccessible = true }
        val getScaleWght = fontWghtClass.getDeclaredMethod(
            "getScaleWght",
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            fontTypeClass,
        ).apply { isAccessible = true }
        val helperClass = Class.forName(
            "miui.util.TypefaceHelper",
            false,
            classLoader,
        )
        val getFontPath = helperClass.getDeclaredMethod("getFontPath", fontTypeClass)
            .apply { isAccessible = true }
        val fontPath = (getFontPath.invoke(null, miuiFontType) as? String)
            ?.takeIf { File(it).isFile }
            ?: error("HyperOS MiSans variable font path unavailable")
        val typefaceFontFamiliesField = Typeface::class.java
            .getDeclaredField("fontFamilies")
            .apply { isAccessible = true }
        runCatching { loadFontSetting.invoke(null) }
        ProviderLogger.info("HyperOS 中文 MiSans 可变字体接口已接入：path=$fontPath")
        HyperOsFontWeightMethods(
            loadFontSettingMethod = loadFontSetting,
            fontScaleField = fontScaleField,
            getWeightIdxMethod = getWeightIdx,
            getScaleWghtMethod = getScaleWght,
            miuiFontType = miuiFontType,
            miuiFontPath = fontPath,
            typefaceFontFamiliesField = typefaceFontFamiliesField,
        )
    }.onFailure { throwable ->
        ProviderLogger.error("HyperOS 中文 MiSans 可变字体接口不可用", throwable)
    }.getOrNull()

    private fun createAppleTypefaceWithVariation(
        original: Typeface,
        effectiveWeight: Int,
        italic: Boolean,
    ): Typeface? {
        val methods = appleSystemFontVariationMethods ?: return null
        return appleSystemFontVariationCache.getOrCreate(
            original = original,
            effectiveWeight = effectiveWeight,
            italic = italic,
        ) {
            runCatching {
                val styledBase = if (original.isItalic == italic) {
                    original
                } else {
                    appleSystemFontApplyGuard.run {
                        Typeface.create(
                            original,
                            original.weight.coerceIn(1, 1000),
                            italic,
                        )
                    }
                }
                val weightAxis = methods.axisConstructor.newInstance(
                    "wght",
                    effectiveWeight.toFloat(),
                )
                appleSystemFontApplyGuard.run {
                    methods.createFromTypefaceWithVariation.invoke(
                        null,
                        styledBase,
                        listOf(weightAxis),
                    ) as? Typeface
                }
            }.onFailure { throwable ->
                if (BuildConfig.DEBUG) {
                    val cause = throwable.cause ?: throwable
                    val traceKey = "apple_font_variation_create:${cause.javaClass.name}"
                    if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                        ProviderLogger.error("Apple SF Pro 可变字体 wght 轴创建失败", cause)
                    }
                }
            }.getOrNull()
        }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private fun resolveAppleSystemFontVariationMethods(): AppleSystemFontVariationMethods? {
        return runCatching {
            val axisClass = Class.forName(
                "android.graphics.fonts.FontVariationAxis",
                false,
                classLoader,
            )
            val axisConstructor = axisClass.getDeclaredConstructor(
                String::class.java,
                Float::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val createFromTypefaceWithVariation = Typeface::class.java.getDeclaredMethod(
                "createFromTypefaceWithVariation",
                Typeface::class.java,
                List::class.java,
            ).apply { isAccessible = true }
            val isVariationInstance = Typeface::class.java.getDeclaredMethod(
                "isVariationInstance",
            ).apply { isAccessible = true }
            ProviderLogger.info("Apple SF Pro 可变字体 wght 轴接口已接入")
            AppleSystemFontVariationMethods(
                axisConstructor = axisConstructor,
                createFromTypefaceWithVariation = createFromTypefaceWithVariation,
                isVariationInstance = isVariationInstance,
            )
        }.getOrElse { throwable ->
            ProviderLogger.error("Apple SF Pro 可变字体 wght 轴接口不可用", throwable)
            null
        }
    }

    private fun isAppleTypefaceVariationInstance(typeface: Typeface?): Boolean? {
        typeface ?: return null
        val methods = appleSystemFontVariationMethods ?: return null
        return runCatching {
            methods.isVariationInstance.invoke(typeface) as? Boolean
        }.getOrNull()
    }

    private fun mappedAppleSystemFontWeight(semanticWeight: Int): Int =
        AppleSystemFontWeightPolicy.sfProWeightForSystemScale(
            semanticWeight = semanticWeight,
            systemScale = currentMiuiFontWeightScale(),
        )

    private fun logAppleSystemFontDrawState(view: TextView) {
        if (!BuildConfig.DEBUG || !isFollowSystemFontWeightEnabled()) return
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

    private fun applyAppleSystemFontForTextView(
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

            fun restoreOriginalTypeface() {
                val original = state?.originalTypeface ?: originalFromReplacement ?: return
                view.setTypeface(original, state?.originalStyle ?: original.style)
                if (requestLayout) view.requestLayout()
            }

            if (!isFollowSystemFontWeightEnabled()) {
                if (originalFromReplacement != null) restoreOriginalTypeface()
                if (state != null) {
                    synchronized(appleSystemFontTrackedTextViews) {
                        appleSystemFontTrackedTextViews.remove(view)
                    }
                }
                return@run
            }

            if (!AppleSystemFontWeightPolicy.shouldReplaceTextContent(content)) {
                if (originalFromReplacement != null) restoreOriginalTypeface()
                synchronized(appleSystemFontTrackedTextViews) {
                    appleSystemFontTrackedTextViews.remove(view)
                }
                return@run
            }

            val appliedSignature = synchronized(appleSystemFontSignaturesByReplacement) {
                appleSystemFontSignaturesByReplacement[current]
            }
            val original = originalFromReplacement
                ?: if (state != null && current === state.originalTypeface) {
                    state.originalTypeface
                } else {
                    current
                }
            val requestedWeight = if (state != null && state.originalTypeface === original) {
                state.requestedWeight
            } else {
                appliedSignature?.semanticWeight ?: original.weight
            }
            val italic = if (state != null && state.originalTypeface === original) {
                state.italic
            } else {
                appliedSignature?.italic ?: original.isItalic
            }
            val originalStyle = state?.originalStyle ?: original.style
            val semanticWeight = AppleSystemFontWeightPolicy.semanticWeight(
                reportedWeight = requestedWeight,
                isBold = original.isBold,
            )
            val expectedWeight = mappedAppleSystemFontWeight(semanticWeight)
            val expectedSignature = AppleSystemFontReplacementSignature(
                effectiveSfProWeight = expectedWeight,
                semanticWeight = semanticWeight,
                usesCjkFallback = AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(content),
                italic = italic,
            )
            val alreadyApplied = originalFromReplacement != null &&
                appliedSignature == expectedSignature
            if (alreadyApplied) return@run

            val nextState = AppleSystemFontTextViewState(
                originalTypeface = original,
                requestedWeight = requestedWeight,
                italic = italic,
                originalStyle = originalStyle,
            )
            if (state != nextState) {
                synchronized(appleSystemFontTrackedTextViews) {
                    appleSystemFontTrackedTextViews[view] = nextState
                }
            }

            val replacement = createAppleWeightAdjustedTypeface(
                original = original,
                requestedWeight = requestedWeight,
                italic = italic,
                text = content,
                textSizePx = view.textSize,
            )
            view.setTypeface(replacement)
            if (requestLayout) view.requestLayout()
            logAppleSystemFontReplacement(
                stage = stage,
                resourceName = null,
                original = original,
                replacement = replacement,
                requestedWeight = requestedWeight,
            )
        }
    }

    private fun rememberAppleSystemFontReplacement(
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

    private fun logAppleSystemFontReplacement(
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

    private fun currentMiuiFontWeightScale(forceRefresh: Boolean = false): Int {
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
                Settings.System.getInt(resolver, "key_miui_font_weight_scale")
            }.getOrNull() ?: runCatching {
                Settings.Global.getInt(resolver, "key_miui_font_weight_scale")
            }.getOrNull() ?: 50
            appleSystemFontScaleCache = scale.coerceIn(0, 100)
            appleSystemFontScaleLastReadUptimeMillis = now
            appleSystemFontScaleCache
        }
    }


}
