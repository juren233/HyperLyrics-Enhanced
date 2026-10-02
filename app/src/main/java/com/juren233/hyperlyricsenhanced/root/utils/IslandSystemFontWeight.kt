/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.utils

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.io.File
import java.lang.reflect.Modifier

/** Binary names verified against classes.dex in the device's miui-framework.jar on 2026-10-02.
 * JAR SHA-256: c4ca44c09784b4f8f576418b19f31a91ce7a4ef80ee02475ea774385ecef2639.
 * These are DEX descriptors, not JADX aliases. Keep the regression test in sync with binary evidence.
 */
internal object IslandSystemFontWeightTargets {
    const val SETTINGS = "miui.util.font.FontSettings"
    const val TYPE = "miui.util.font.FontType"
    const val WEIGHT = "miui.util.font.FontWght"
    const val HELPER = "miui.util.TypefaceHelper"
    const val MIUI = "MIUI"
    const val LOAD = "loadFontSetting" // ()Z, static
    const val INDEX = "getWeightIdx" // (IZLmiui/util/font/FontType;)I, static
    const val SCALE = "getScaleWght" // (IFLmiui/util/font/FontType;)I, static
    const val PATH = "getFontPath" // (Lmiui/util/font/FontType;)Ljava/lang/String;, static
    const val SCALE_FIELD = "sFontScale" // I, public static
    // framework.jar: Configuration.extraConfig:Landroid/content/res/IMiuiConfiguration;
    // miui-framework.jar: MiuiConfiguration.extraData:Landroid/os/Bundle;
    const val MIUI_CONFIGURATION = "android.content.res.MiuiConfiguration"
    const val EXTRA_CONFIG = "extraConfig"
    const val EXTRA_DATA = "extraData"
    const val CONFIG_SCALE = "key_var_font_scale"
}

/** Keeps system settings out of the lyric frame loop; both rendering and measuring share its snapshot. */
internal object IslandSystemFontWeight {
    private const val SCALE_KEY = "key_miui_font_weight_scale"
    // Visual compensation requested for system-following text. Apply before OEM axis mapping,
    // since MiSans's native coordinates are not interchangeable with a standard font's wght.
    private const val WEIGHT_OFFSET = 50

    data class State(val scale: Int = 50, val adjustment: Int = 0, val revision: Int = 0) {
        val semanticWeight: Int get() = (400 + WEIGHT_OFFSET + adjustment).coerceIn(1, 1000)
        // Custom/condensed fonts use their own standard wght axis, never MiSans's OEM coordinates.
        val standardWeight: Int get() = (400 + WEIGHT_OFFSET + (scale - 50) * 2 + adjustment).coerceIn(1, 1000)
    }

    @Volatile
    var state = State()
        private set

    private var context: Context? = null
    private var observer: ContentObserver? = null
    private var callbacks: ComponentCallbacks? = null
    private var cached: Pair<String, Typeface>? = null
    private var failureLogged = false

    private val methods by lazy {
        runCatching {
            val targets = IslandSystemFontWeightTargets
            val type = Class.forName(targets.TYPE)
            val weight = Class.forName(targets.WEIGHT)
            val settings = Class.forName(targets.SETTINGS)
            fun method(owner: Class<*>, name: String, result: Class<*>, vararg args: Class<*>) =
                owner.getDeclaredMethod(name, *args).apply {
                    check(Modifier.isStatic(modifiers) && returnType == result)
                    isAccessible = true
                }
            Methods(
                settings,
                settings.getDeclaredField(targets.SCALE_FIELD).apply {
                    check(Modifier.isStatic(modifiers) && this.type == Int::class.javaPrimitiveType)
                    isAccessible = true
                },
                requireNotNull(type.enumConstants).first { (it as Enum<*>).name == targets.MIUI },
                method(settings, targets.LOAD, Boolean::class.javaPrimitiveType!!),
                method(weight, targets.INDEX, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, type),
                method(weight, targets.SCALE, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, type),
                method(Class.forName(targets.HELPER), targets.PATH, String::class.java, type),
            ).also {
                if (BuildConfig.DEBUG) HookLogger.i("IslandFontWeight", "resolved OEM weight targets")
            }
        }.onFailure {
            if (BuildConfig.DEBUG) HookLogger.w("IslandFontWeight", "resolve_failed ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private val configurationFields by lazy {
        runCatching {
            val targets = IslandSystemFontWeightTargets
            val extraConfig = Configuration::class.java.getDeclaredField(targets.EXTRA_CONFIG)
                .apply { isAccessible = true }
            val extraData = Class.forName(targets.MIUI_CONFIGURATION)
                .getDeclaredField(targets.EXTRA_DATA).apply { isAccessible = true }
            extraConfig to extraData
        }.getOrNull()
    }

    private fun configurationScale(config: Configuration): Int? = runCatching {
        val fields = configurationFields ?: return null
        val extra = fields.first.get(config) ?: return null
        val data = fields.second.get(extra) as? Bundle ?: return null
        if (data.containsKey(IslandSystemFontWeightTargets.CONFIG_SCALE)) {
            data.getInt(IslandSystemFontWeightTargets.CONFIG_SCALE).takeIf { it in 0..100 }
        } else null
    }.getOrNull()

    internal fun resolveScale(
        configurationScale: Int?,
        systemScale: Int?,
        globalScale: Int?,
        preferGlobal: Boolean = false,
    ): Int {
        val settingScale = if (preferGlobal) globalScale ?: systemScale else systemScale ?: globalScale
        return (configurationScale ?: settingScale ?: 50).coerceIn(0, 100)
    }

    internal fun <T> withScaleSnapshot(
        scale: Int,
        readScale: () -> Int,
        writeScale: (Int) -> Unit,
        prepare: () -> Unit = {},
        mapAxis: () -> T,
    ): T {
        val previous = readScale()
        return try {
            prepare()
            writeScale(scale)
            mapAxis()
        } finally {
            writeScale(previous)
        }
    }

    fun start(app: Context, onChanged: () -> Unit) {
        stop()
        context = app
        val systemUri = Settings.System.getUriFor(SCALE_KEY)
        val globalUri = Settings.Global.getUriFor(SCALE_KEY)
        fun refresh(config: Configuration? = null, preferGlobal: Boolean = false) {
            val resolver = app.contentResolver
            val deliveredScale = config?.let(::configurationScale)
            val scale = resolveScale(
                deliveredScale,
                runCatching { Settings.System.getInt(resolver, SCALE_KEY) }.getOrNull(),
                runCatching { Settings.Global.getInt(resolver, SCALE_KEY) }.getOrNull(),
                preferGlobal,
            )
            val adjustment = (config ?: app.resources.configuration).fontWeightAdjustment
                .takeUnless { it == Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED } ?: 0
            val next = State(scale, adjustment, state.revision + if (config != null) 1 else 0)
            if (next != state) {
                if (BuildConfig.DEBUG) HookLogger.i("IslandFontWeight",
                    "changed source=${if (config != null) "configuration" else if (preferGlobal) "global" else "system"} " +
                        "configScale=$deliveredScale old=$state new=$next")
                state = next
                cached = null
                onChanged()
            }
        }
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
            override fun onChange(selfChange: Boolean, uri: Uri?) = refresh(preferGlobal = uri == globalUri)
        }.also {
            app.contentResolver.registerContentObserver(systemUri, false, it)
            app.contentResolver.registerContentObserver(globalUri, false, it)
        }
        callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = refresh(newConfig)
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = Unit
        }.also(app::registerComponentCallbacks)
        refresh(app.resources.configuration)
    }

    fun stop() {
        context?.let { app ->
            observer?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
            callbacks?.let { app.unregisterComponentCallbacks(it) }
        }
        context = null
        observer = null
        callbacks = null
        cached = null
    }

    fun typeface(italic: Boolean, textSizeSp: Int): Typeface {
        val current = state
        val key = "$current:$italic:$textSizeSp"
        cached?.takeIf { it.first == key }?.let { return it.second }
        val weight = current.semanticWeight
        val result = methods?.let { api ->
            runCatching {
                val (axis, path) = synchronized(api.settings) {
                    // Native load may still reflect the previous font-service Bundle. Map this
                    // update's exact scale, without leaving our temporary value in the host.
                    withScaleSnapshot(
                        current.scale,
                        { api.scaleField.getInt(null) },
                        { api.scaleField.setInt(null, it) },
                        prepare = { api.load.invoke(null) },
                    ) {
                        val index = api.index.invoke(null, weight, false, api.type) as Int
                        val axis = api.scale.invoke(null, index, textSizeSp.toFloat(), api.type) as Int
                        axis to (api.path.invoke(null, api.type) as String)
                    }
                }
                val slant = if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT
                val font = Font.Builder(File(path)).setWeight(weight).setSlant(slant)
                    .setFontVariationSettings("'wght' $axis").build()
                Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                    .setSystemFallback("sans-serif").setStyle(FontStyle(weight, slant)).build()
                    .also {
                        if (BuildConfig.DEBUG) HookLogger.i("IslandFontWeight",
                            "generated scale=${current.scale} revision=${current.revision} axis=$axis size=$textSizeSp")
                    }
            }.onFailure {
                if (!failureLogged) {
                    failureLogged = true
                    HookLogger.w("IslandSystemFontWeight", "系统可变字重读取失败：${it.javaClass.simpleName}")
                }
            }.getOrNull()
        } ?: Typeface.create(Typeface.DEFAULT, weight, italic)
        cached = key to result
        return result
    }

    private data class Methods(
        val settings: Class<*>,
        val scaleField: java.lang.reflect.Field,
        val type: Any,
        val load: java.lang.reflect.Method,
        val index: java.lang.reflect.Method,
        val scale: java.lang.reflect.Method,
        val path: java.lang.reflect.Method,
    )
}
