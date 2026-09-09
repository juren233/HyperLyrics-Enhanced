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

internal fun AppleSystemFontHooks.appleSystemTypefaceForText(
    current: Typeface?,
    text: CharSequence,
    textSizePx: Float,
): Typeface? {
    current ?: return null
    if (!AppleSystemFontWeightPolicy.shouldReplaceTextContent(text)) return current
    if (isFollowSystemFontEnabled()) {
        val request = appleSystemFontRequest(current)
        return createSystemDefaultTypeface(
            request?.semanticWeight ?: current.weight,
            request?.italic ?: current.isItalic,
            textSizePx,
        )
    }
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

internal fun AppleSystemFontHooks.appleSystemFontRequest(typeface: Typeface): AppleSystemFontRequest? {
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

internal fun AppleSystemFontHooks.metricSpanTypeface(span: MetricAffectingSpan): Typeface? {
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

internal fun AppleSystemFontHooks.createTypefaceMetricSpan(
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

internal fun AppleSystemFontHooks.replaceAppleFontResource(
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
    appleSystemFontResourceNameByTypeface[original] = resourceIdentity.third
    if (isFollowSystemFontEnabled() || !isFollowSystemFontWeightEnabled()) return original

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

internal fun AppleSystemFontHooks.originalAppleTypeface(typeface: Typeface): Typeface? {
    synchronized(appleSystemFontOriginalTypefacesByReplacement) {
        appleSystemFontOriginalTypefacesByReplacement[typeface]?.let { return it }
    }
    return synchronized(appleSystemFontManagedTypefaces) {
        typeface.takeIf(appleSystemFontManagedTypefaces::contains)
    }
}

internal fun AppleSystemFontHooks.createAppleWeightAdjustedTypeface(
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
    // 资源文件派生仅用于 OS4（Android 17）回归环境；OS3 必须保持已验收的
    // cjkComposite → createFromTypefaceWithVariation 原顺序执行（用户约束：修 OS4 不得动 OS3 实现）。
    val resourceFile = if (Build.VERSION.SDK_INT >= 37) {
        createAppleTypefaceFromResourceFile(
            original = original,
            effectiveWeight = effectiveWeight,
            italic = italic,
            text = text,
            textSizePx = textSizePx,
            semanticWeight = semanticWeight,
        )
    } else {
        null
    }
    val result = resourceFile ?: cjkComposite ?: createAppleTypefaceWithVariation(
        original = original,
        effectiveWeight = effectiveWeight,
        italic = italic,
    ) ?: original
    val strategy = when {
        resourceFile != null -> "resource_file"
        cjkComposite != null -> "cjk_composite"
        result !== original -> "variation"
        else -> "unavailable"
    }
    rememberAppleSystemFontReplacement(
        replacement = result,
        original = original,
        effectiveWeight = effectiveWeight,
        semanticWeight = semanticWeight,
        usesCjkFallback = cjkComposite != null ||
            (resourceFile != null && usesCjkFallback),
        italic = italic,
    )
    if (BuildConfig.DEBUG) {
        val path = "apple_typeface_$strategy"
        val traceKey =
            "system_typeface:$path:$semanticWeight:$effectiveWeight:$italic:${text != null}"
        if (appleSystemFontDebugTraceKeys.add(traceKey)) {
            ProviderLogger.debug(
                "Apple 系统字体粗细生成：path=$path, semanticWeight=$semanticWeight, " +
                    "effectiveWeight=$effectiveWeight, resultWeight=${result.weight}, " +
                    "sameAsOriginal=${result === original}, italic=$italic, " +
                    "cjkFallback=${cjkComposite != null || (resourceFile != null && usesCjkFallback)}, " +
                    "variationInstance=${isAppleTypefaceVariationInstance(result)}, " +
                    "textSizePx=$textSizePx, scale=${currentMiuiFontWeightScale()}"
            )
        }
    }
    return result
}

/**
 * A17（OS4）上旧的 fontFamilies 反射链与 createFromTypefaceWithVariation 都不再可靠
 * （前者元素类型变化/hiddenapi BLOCKED 导致静默失败，后者对象不同但渲染无差异，
 * 见 `AM-FONT-WEIGHT-003`）。本策略只用公开 API：直接取 Apple APK 里的
 * 原始可变 TTF 字节，用 [Font.Builder] 写入 wght 轴并按文本追加 MiSans fallback。
 */
internal fun AppleSystemFontHooks.createAppleTypefaceFromResourceFile(
    original: Typeface,
    effectiveWeight: Int,
    italic: Boolean,
    text: CharSequence?,
    textSizePx: Float?,
    semanticWeight: Int,
): Typeface? {
    val resources = application.resources
    val packageName = application.packageName
    val recorded = appleSystemFontResourceNameByTypeface[original]
    val candidates = buildList {
        if (recorded != null) add(recorded)
        add(AppleSystemFontHooks.APPLE_SF_PRO_FONT_RESOURCE)
    }
    val usesCjkFallback = AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(text)
    val cjkAxis = if (usesCjkFallback) {
        hyperOsFontWeightMethods?.let { methods ->
            @Suppress("DEPRECATION")
            val textSizeSp = (textSizePx ?: 16f).let { sizePx ->
                val scaledDensity = application.resources.displayMetrics.scaledDensity
                if (scaledDensity > 0f) sizePx / scaledDensity else sizePx
            }
            hyperOsCjkWeightAxis(
                methods = methods,
                semanticWeight = semanticWeight,
                textSizeSp = textSizeSp,
            )
        }
    } else {
        null
    }
    candidates.forEach { name ->
        if (name in appleSystemFontXmlResourceNames) return@forEach
        val cacheKey = "$name:$effectiveWeight:$italic:${cjkAxis ?: "none"}"
        val typeface = appleSystemFontResourceFileCache[cacheKey] ?: runCatching {
            val fontData = appleSystemFontResourceBuffer(name, resources, packageName)
                ?: return@runCatching null
            buildTypefaceFromResourceBytes(
                fontData = fontData,
                effectiveWeight = effectiveWeight,
                italic = italic,
                cjkAxis = cjkAxis,
                semanticWeight = semanticWeight,
            )?.also {
                if (appleSystemFontResourceFileCache.size >= 256) {
                    appleSystemFontResourceFileCache.clear()
                }
                appleSystemFontResourceFileCache[cacheKey] = it
            }
        }.onFailure { throwable ->
            logAppleFontDerivationFailure(
                "resource_file:$name",
                "${throwable.javaClass.simpleName}: ${throwable.message}",
            )
        }.getOrNull()
        if (typeface != null) {
            logAppleFontDerivationWidths(
                strategy = "resource_file($name)",
                original = original,
                derived = typeface,
                effectiveWeight = effectiveWeight,
                usesCjkFallback = usesCjkFallback,
            )
            return typeface
        }
    }
    return null
}

/**
 * 每个资源名只做一次 `getIdentifier` + `openRawResource` + 直接缓冲分配（SF Pro 约 1MB）。
 * 派生挂在 `TextView.setTypeface`/`setText` 等高频路径上，任何按调用重复的 I/O 都会直接
 * 变成 AM 主线程掉帧（150233 轮真机证据：5 分钟 69 次 Choreographer 掉帧、峰值 330 帧）。
 */
internal fun AppleSystemFontHooks.appleSystemFontResourceBuffer(
    name: String,
    resources: Resources,
    packageName: String,
): ByteBuffer? {
    appleSystemFontResourceBuffersByName[name]?.let { return it.duplicate() }
    val resourceId = resources.getIdentifier(name, "font", packageName)
    if (resourceId == 0) {
        logAppleFontDerivationFailure("resource_id:$name", "identifier=0")
        return null
    }
    val bytes = resources.openRawResource(resourceId).use { it.readBytes() }
    if (bytes.size >= 4 && bytes[0] == AppleSystemFontHooks.RES_XML_FIRST_BYTE) {
        // font-family XML（regular.xml 等）而非 TTF；负缓存后不再重复读字节探测。
        appleSystemFontXmlResourceNames.add(name)
        logAppleFontDerivationFailure("resource_xml:$name", "family-xml-not-ttf")
        return null
    }
    // Font.Builder 只接受 direct 缓冲（堆缓冲直接抛
    // "Only direct buffer can be used as the source of font data"，见 AM-FONT-WEIGHT-003 150232 轮）。
    val direct = ByteBuffer.allocateDirect(bytes.size).put(bytes)
    direct.flip()
    appleSystemFontResourceBuffersByName[name] = direct
    return direct.duplicate()
}

internal fun AppleSystemFontHooks.buildTypefaceFromResourceBytes(
    fontData: ByteBuffer,
    effectiveWeight: Int,
    italic: Boolean,
    cjkAxis: Int?,
    semanticWeight: Int,
): Typeface? = runCatching {
    val font = Font.Builder(fontData)
        .setWeight(effectiveWeight.coerceIn(1, 1000))
        .setSlant(if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
        .setFontVariationSettings("'wght' $effectiveWeight")
        .build()
    val builder = Typeface.CustomFallbackBuilder(
        FontFamily.Builder(font).build(),
    ).setStyle(
        FontStyle(
            AppleSystemFontWeightPolicy.compositeStyleWeight(semanticWeight),
            if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT,
        ),
    )
    if (cjkAxis != null) {
        hyperOsFontWeightMethods?.let { methods ->
            val cjkFont = Font.Builder(File(methods.miuiFontPath))
                .setWeight(semanticWeight.coerceIn(1, 1000))
                .setFontVariationSettings("'wght' $cjkAxis")
                .build()
            builder.addCustomFallback(FontFamily.Builder(cjkFont).build())
        }
    }
    builder.build()
}.onFailure { throwable ->
    logAppleFontDerivationFailure(
        "resource_build",
        "${throwable.javaClass.simpleName}: ${throwable.message}",
    )
}.getOrNull()

internal fun AppleSystemFontHooks.logAppleFontDerivationWidths(
    strategy: String,
    original: Typeface,
    derived: Typeface,
    effectiveWeight: Int,
    usesCjkFallback: Boolean,
) {
    if (!BuildConfig.DEBUG) return
    if (!appleFontDerivationWidthKeys.add("$strategy:$effectiveWeight:$usesCjkFallback")) return
    runCatching {
        fun width(tf: Typeface, sample: String): Float = Paint().apply {
            textSize = 100f
            typeface = tf
        }.measureText(sample)
        val latinSample = "Wave Illegible 400"
        val cjkSample = "演员测试歌曲歌词"
        ProviderLogger.info(
            "Apple 字体派生宽度对照：strategy=$strategy, effectiveWeight=$effectiveWeight, " +
                "latinOriginal=${width(original, latinSample)}, " +
                "latinDerived=${width(derived, latinSample)}, " +
                "cjkOriginal=${width(original, cjkSample)}, " +
                "cjkDerived=${width(derived, cjkSample)}, cjkFallback=$usesCjkFallback"
        )
    }
}

internal fun AppleSystemFontHooks.logAppleFontDerivationFailure(strategy: String, detail: String) {
    if (!BuildConfig.DEBUG) return
    if (appleFontDerivationFailureKeys.add(strategy)) {
        ProviderLogger.error("Apple 字体派生策略失败：strategy=$strategy, detail=$detail")
    }
}

internal fun AppleSystemFontHooks.createAppleTypefaceWithSystemCjkFallback(
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
            ?: throw IllegalStateException(
                "fontFamilies-font-family-missing: " +
                    "families=${originalFamilies?.map { it?.javaClass?.name }}",
            )
        val originalFont = originalFamily.getFont(0)
        val primaryFont = buildFontWithWeight(
            source = originalFont,
            weight = effectiveSfProWeight,
            italic = italic,
            variation = "'wght' $effectiveSfProWeight",
        ) ?: throw IllegalStateException("primary-font-unavailable")
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

internal fun AppleSystemFontHooks.buildFontWithWeight(
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

internal fun AppleSystemFontHooks.hyperOsCjkWeightAxis(
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
internal fun AppleSystemFontHooks.resolveHyperOsFontWeightMethods(): HyperOsFontWeightMethods? = runCatching {
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

internal fun AppleSystemFontHooks.createAppleTypefaceWithVariation(
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
internal fun AppleSystemFontHooks.resolveAppleSystemFontVariationMethods(): AppleSystemFontVariationMethods? {
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
        // Android 17 (HyperOS 4) removed Typeface.isVariationInstance; the method only
        // feeds draw diagnostics and must not gate the functional variation APIs.
        val isVariationInstance = runCatching {
            Typeface::class.java.getDeclaredMethod(
                "isVariationInstance",
            ).apply { isAccessible = true }
        }.getOrNull()
        ProviderLogger.info(
            "Apple SF Pro 可变字体 wght 轴接口已接入" +
                if (isVariationInstance == null) {
                    "（isVariationInstance 缺失，仅诊断能力降级）"
                } else {
                    ""
                },
        )
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

internal fun AppleSystemFontHooks.isAppleTypefaceVariationInstance(typeface: Typeface?): Boolean? {
    typeface ?: return null
    val methods = appleSystemFontVariationMethods ?: return null
    val isVariationInstance = methods.isVariationInstance ?: return null
    return runCatching {
        isVariationInstance.invoke(typeface) as? Boolean
    }.getOrNull()
}

internal fun AppleSystemFontHooks.mappedAppleSystemFontWeight(semanticWeight: Int): Int =
    AppleSystemFontWeightPolicy.sfProWeightForSystemScale(
        semanticWeight = semanticWeight,
        systemScale = currentMiuiFontWeightScale(),
    )

