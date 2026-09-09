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


internal class AppleSystemFontHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> android.content.SharedPreferences?,
    internal val currentSongId: () -> String?,
    internal val nativeRawWordVectorText: (Any?) -> String?,
    internal val currentPlaybackPositionMs: () -> Long?,
) {
    internal companion object {
        const val MAX_APPLE_SYSTEM_FONT_VARIATION_CACHE_ENTRIES = 64
        const val SYSTEM_FONT_WEIGHT_SCALE_KEY = "key_miui_font_weight_scale"

        /** Apple 主字体家族 XML 背后的可变 TTF 资源名（6.5.0–6.5.2 稳定）。 */
        const val APPLE_SF_PRO_FONT_RESOURCE = "sf_pro_android_ui"

        /** Android 二进制 XML（RES_XML_TYPE=0x0003）小端首字节。 */
        const val RES_XML_FIRST_BYTE: Byte = 0x03
    }

    internal val application: Application
        get() = runtime.application
    internal val classLoader: ClassLoader
        get() = runtime.classLoader
    internal val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    internal val hookRegistrar
        get() = runtime.hookRegistrar
    private val mainHandler: Handler
        get() = runtime.mainHandler
    private val contentUiLanguagePrefs: android.content.SharedPreferences?
        get() = preferences()
    internal val lyricsWordVectorClassName by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS).target.className
    }
    internal val customTextViewClassName by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW).target.className
    }

    internal val appleSystemFontManagedTypefaces = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Typeface, Boolean>())
    )
    internal val appleSystemFontOriginalTypefacesByReplacement =
        Collections.synchronizedMap(WeakHashMap<Typeface, Typeface>())
    internal val appleSystemFontSignaturesByReplacement =
        Collections.synchronizedMap(WeakHashMap<Typeface, AppleSystemFontReplacementSignature>())
    internal val appleSystemFontCompositeCache = ConcurrentHashMap<String, Typeface>()
    internal val appleSystemFontVariationCache =
        AppleSystemFontVariationCache<Typeface, Typeface>(
            MAX_APPLE_SYSTEM_FONT_VARIATION_CACHE_ENTRIES,
        )
    internal val appleSystemFontTrackedTextViews =
        Collections.synchronizedMap(WeakHashMap<TextView, AppleSystemFontTextViewState>())
    internal val appleSystemFontLyricsRenderHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    internal val appleSystemFontLyricsTemplateFieldPaths =
        ConcurrentHashMap<Class<*>, List<AppleSystemFontTemplateFieldPath>>()
    internal val appleSystemFontLyricsMeasurementTexts = ThreadLocalStack<String>()
    internal val appleSystemFontApplyGuard = ThreadLocalReentryGuard()
    internal val appleSystemFontLyricsMeasureDiagnosticGuard = ThreadLocalReentryGuard()
    internal val appleSystemFontLyricsMeasureDiagnosticKeys =
        ConcurrentHashMap.newKeySet<String>()
    internal val appleSystemFontLyricsMeasureBaselineKeys =
        ConcurrentHashMap.newKeySet<String>()
    internal val appleLyricsGradientAnimatorSample =
        ThreadLocal<AppleLyricsGradientAnimatorSample?>()
    internal val appleLyricsGradientLastLogAt = ConcurrentHashMap<Int, Long>()
    internal val appleSystemFontDebugTraceKeys = ConcurrentHashMap.newKeySet<String>()
    internal val appleSystemFontScaleLock = Any()
    @Volatile
    internal var appleSystemFontScaleCache = 50
    @Volatile
    internal var appleSystemFontScaleLastReadUptimeMillis = -1L
    @Volatile
    internal var hyperOsFontSettingsLastSyncedScale = -1
    @Volatile
    private var systemFontWeightObserverLastScale = -1
    internal val appleSystemFontResourceNameByTypeface =
        Collections.synchronizedMap(WeakHashMap<Typeface, String>())
    internal val appleSystemFontResourceFileCache = ConcurrentHashMap<String, Typeface>()

    internal val appleSystemFontResourceBuffersByName = ConcurrentHashMap<String, ByteBuffer>()

    internal val appleSystemFontXmlResourceNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val appleFontDerivationWidthKeys = ConcurrentHashMap.newKeySet<String>()
    internal val appleFontDerivationFailureKeys = ConcurrentHashMap.newKeySet<String>()
    internal val appleSystemFontVariationMethods: AppleSystemFontVariationMethods? by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        resolveAppleSystemFontVariationMethods()
    }
    internal val hyperOsFontWeightMethods: HyperOsFontWeightMethods? by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        resolveHyperOsFontWeightMethods()
    }

    internal fun isFollowSystemFontEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT,
        ) == true

    internal fun isFollowSystemFontWeightEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT,
        ) == true

    private val systemDefaultTypefaceCache = ConcurrentHashMap<String, Typeface>()

    internal fun createSystemDefaultTypeface(
        requestedWeight: Int,
        italic: Boolean,
        textSizePx: Float?,
    ): Typeface {
        val weight = requestedWeight.coerceIn(1, 1000)
        val methods = hyperOsFontWeightMethods
        if (methods != null) {
            @Suppress("DEPRECATION")
            val density = application.resources.displayMetrics.scaledDensity
            val textSizeSp = (textSizePx ?: 16f * density) / density.coerceAtLeast(0.01f)
            // Reuse the binary-verified HyperOS mapping and font path already used by
            // the CJK fallback. OEM axis values belong to this font, never to SF Pro.
            val axis = hyperOsCjkWeightAxis(methods, weight, textSizeSp)
            if (axis != null) {
                val key = "${methods.miuiFontPath}:$weight:$axis:$italic"
                systemDefaultTypefaceCache[key]?.let { return it }
                val result = runCatching {
                    val slant = if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT
                    val font = Font.Builder(File(methods.miuiFontPath))
                        .setWeight(weight)
                        .setSlant(slant)
                        .setFontVariationSettings("'wght' $axis")
                        .build()
                    Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                        .setSystemFallback("sans-serif")
                        .setStyle(FontStyle(weight, slant))
                        .build()
                }.onFailure {
                    logAppleFontDerivationFailure("system_default", it.toString())
                }.getOrNull()
                if (result != null) {
                    if (systemDefaultTypefaceCache.size >= 128) systemDefaultTypefaceCache.clear()
                    systemDefaultTypefaceCache[key] = result
                    return result
                }
            }
        }
        val adjustment = application.resources.configuration.fontWeightAdjustment
            .takeUnless { it == android.content.res.Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED }
            ?: 0
        return Typeface.create(Typeface.DEFAULT, (weight + adjustment).coerceIn(1, 1000), italic)
    }

    private fun resolveTextViewOriginalTypeface(
        view: TextView,
        requested: Typeface,
    ): Typeface {
        val state = synchronized(appleSystemFontTrackedTextViews) {
            appleSystemFontTrackedTextViews[view]
        }
        if (state?.appliedTypeface === requested) {
            return state.originalTypeface
        }
        return originalAppleTypeface(requested) ?: requested
    }

    internal fun rememberTextViewState(
        view: TextView,
        originalTypeface: Typeface,
        requestedWeight: Int,
        italic: Boolean,
        originalStyle: Int,
        appliedTypeface: Typeface,
    ) {
        synchronized(appleSystemFontTrackedTextViews) {
            appleSystemFontTrackedTextViews[view] = AppleSystemFontTextViewState(
                originalTypeface = originalTypeface,
                requestedWeight = requestedWeight,
                italic = italic,
                originalStyle = originalStyle,
                appliedTypeface = appliedTypeface,
            )
        }
    }

    fun refreshAppleSystemFont() {
        mainHandler.post {
            val systemFontEnabled = isFollowSystemFontEnabled()
            val systemFontWeightEnabled = isFollowSystemFontWeightEnabled()
            appleSystemFontVariationCache.clear()
            systemDefaultTypefaceCache.clear()
            if (systemFontWeightEnabled || systemFontEnabled) {
                currentMiuiFontWeightScale(forceRefresh = true)
            }
            val trackedViews = synchronized(appleSystemFontTrackedTextViews) {
                appleSystemFontTrackedTextViews.entries.map { it.key to it.value }
            }
            trackedViews.forEach { (view, _) ->
                applyAppleSystemFontForTextView(view, stage = "settings_refresh")
            }
            if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "Apple 系统字体已刷新：font=$systemFontEnabled, " +
                        "weight=$systemFontWeightEnabled, views=${trackedViews.size}, " +
                        "scale=${currentMiuiFontWeightScale()}"
                )
            }
        }
    }

    fun refreshAppleSystemFontWeight() = refreshAppleSystemFont()


    /**
     * OS4 的字体粗细变更不再触发 Apple Music 视图重建（`AM-FONT-WEIGHT-003`），
     * 因此必须自行监听系统滑块键并主动重应用；OS3 上该监听只是既有
     * 配置变更路径之外的一道幂等保险，不改变原行为。
     */
    private fun registerSystemFontWeightSettingsObserver() {
        runCatching {
            val resolver = application.contentResolver
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (!isFollowSystemFontEnabled() && !isFollowSystemFontWeightEnabled()) return
                    val scale = currentMiuiFontWeightScale(forceRefresh = true)
                    // 滑块写入 System/Global 两个命名空间会触发两次回调，按值去重。
                    if (scale == systemFontWeightObserverLastScale) return
                    systemFontWeightObserverLastScale = scale
                    ProviderLogger.info(
                        "Apple 系统字体粗细滑块已变化：scale=$scale, 重应用已跟踪视图"
                    )
                    refreshAppleSystemFont()
                }
            }
            resolver.registerContentObserver(
                Settings.System.getUriFor(SYSTEM_FONT_WEIGHT_SCALE_KEY),
                false,
                observer,
            )
            resolver.registerContentObserver(
                Settings.Global.getUriFor(SYSTEM_FONT_WEIGHT_SCALE_KEY),
                false,
                observer,
            )
            systemFontWeightObserverLastScale = currentMiuiFontWeightScale(forceRefresh = true)
            ProviderLogger.info("Apple 系统字体粗细滑块监听已注册")
        }.onFailure {
            ProviderLogger.error("Apple 系统字体粗细滑块监听注册失败", it)
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
                    isFollowSystemFontEnabled() ||
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
            hookRegistrar.installHook(setTypeface, after = { chain, _ ->
                val view = chain.thisObject as? TextView ?: return@installHook
                handleTextViewTypefaceAfterSet(
                    view = view,
                    requested = chain.args.firstOrNull() as? Typeface,
                    requestedStyle = null,
                    stage = "text_view",
                )
            })
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
                val previousState = synchronized(appleSystemFontTrackedTextViews) {
                    appleSystemFontTrackedTextViews[view]
                }
                val originalTypeface = resolveTextViewOriginalTypeface(view, requested)
                val systemFontEnabled = isFollowSystemFontEnabled()
                val systemFontWeightEnabled = isFollowSystemFontWeightEnabled()
                if (!AppleSystemFontWeightPolicy.shouldReplaceTextContent(view.text)) {
                    return@installArgumentRewriteHook null
                }
                val reusedState = previousState?.takeIf {
                    it.appliedTypeface === requested && requestedStyle == Typeface.NORMAL
                }
                val bold = requestedStyle and Typeface.BOLD != 0
                val italic = reusedState?.italic ?: (
                    originalTypeface.isItalic ||
                        requestedStyle and Typeface.ITALIC != 0
                    )
                val requestedWeight = reusedState?.requestedWeight ?: if (bold) {
                    maxOf(originalTypeface.weight, 700)
                } else {
                    originalTypeface.weight
                }
                val replacement = when {
                    systemFontEnabled -> createSystemDefaultTypeface(requestedWeight, italic, view.textSize)
                    systemFontWeightEnabled ->
                        createAppleWeightAdjustedTypeface(
                            original = originalTypeface,
                            requestedWeight = requestedWeight,
                            italic = italic,
                            textView = view,
                        )
                    else -> originalTypeface
                }
                rememberTextViewState(
                    view = view,
                    originalTypeface = originalTypeface,
                    requestedWeight = requestedWeight,
                    italic = italic,
                    originalStyle = reusedState?.originalStyle ?: requestedStyle,
                    appliedTypeface = replacement,
                )
                if (!systemFontEnabled && !systemFontWeightEnabled) {
                    return@installArgumentRewriteHook if (requested === replacement) {
                        null
                    } else {
                        arrayOf(replacement, requestedStyle)
                    }
                }
                if (systemFontEnabled) {
                    return@installArgumentRewriteHook if (
                        requested === replacement && requestedStyle == Typeface.NORMAL
                    ) {
                        null
                    } else {
                        arrayOf(replacement, Typeface.NORMAL)
                    }
                }
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

        registerSystemFontWeightSettingsObserver()

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

}
