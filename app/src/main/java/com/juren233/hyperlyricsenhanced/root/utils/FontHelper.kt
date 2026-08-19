package com.juren233.hyperlyricsenhanced.root.utils

import android.content.SharedPreferences
import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import java.io.File
import java.util.Collections

object FontHelper {

    private val loggedFontFailures = Collections.synchronizedSet(mutableSetOf<String>())
    private val loggedFontLoads = Collections.synchronizedSet(mutableSetOf<String>())
    private val narrowTypefaceLock = Any()

    @Volatile
    private var narrowTypefaceCache: NarrowTypefaceCacheEntry? = null

    @Volatile
    private var moduleAssetManagerCache: AssetManager? = null

    fun loadTypeface(prefs: SharedPreferences): Typeface {
        val config = readFontConfig(prefs)
        val narrowEnabled = prefs.getBoolean(
            RootConstants.KEY_HOOK_NARROW_LATIN_FONT,
            RootConstants.DEFAULT_HOOK_NARROW_LATIN_FONT
        )
        if (narrowEnabled) {
            loadNarrowTypeface(config)?.let { return it }
        }
        return loadBaseTypeface(config)
    }

    fun loadBaseTypeface(prefs: SharedPreferences): Typeface {
        return loadBaseTypeface(readFontConfig(prefs))
    }

    fun loadNarrowTypeface(prefs: SharedPreferences): Typeface? {
        val config = readFontConfig(prefs)
        val narrowEnabled = prefs.getBoolean(
            RootConstants.KEY_HOOK_NARROW_LATIN_FONT,
            RootConstants.DEFAULT_HOOK_NARROW_LATIN_FONT
        )
        if (!narrowEnabled) return null
        return loadNarrowTypeface(config)
    }

    private fun readFontConfig(prefs: SharedPreferences): FontConfig {
        return FontConfig(
            weight = prefs.getInt(
                RootConstants.KEY_HOOK_FONT_WEIGHT,
                RootConstants.DEFAULT_HOOK_FONT_WEIGHT
            ).coerceIn(FontStyle.FONT_WEIGHT_MIN, FontStyle.FONT_WEIGHT_MAX),
            italic = prefs.getBoolean(
                RootConstants.KEY_HOOK_FONT_ITALIC,
                RootConstants.DEFAULT_HOOK_FONT_ITALIC
            ),
            customFontPath = prefs.getString(RootConstants.KEY_HOOK_CUSTOM_FONT_PATH, null)
                ?.takeIf { it.isNotBlank() }
        )
    }

    private fun loadBaseTypeface(config: FontConfig): Typeface {
        var baseTf: Typeface? = null

        config.customFontPath?.let { customFontPath ->
            try {
                val file = File(customFontPath)
                if (file.exists() && file.canRead()) {
                    baseTf = Typeface.createFromFile(file)
                    HookLogger.d("FontHelper", "自定义字体加载成功：$customFontPath")
                } else {
                    if (loggedFontFailures.add(customFontPath)) {
                        HookLogger.w(
                            "FontHelper",
                            "自定义字体文件不存在或无法读取：$customFontPath " +
                                "(存在: ${file.exists()}, 可读: ${file.canRead()})"
                        )
                    }
                }
            } catch (e: Exception) {
                if (loggedFontFailures.add(customFontPath)) {
                    HookLogger.w(
                        "FontHelper",
                        "无法从文件创建字体：$customFontPath，原因: ${e.message}"
                    )
                }
            }
        }

        val finalBaseTf = baseTf ?: Typeface.DEFAULT

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Typeface.create(finalBaseTf, config.weight, config.italic)
        } else {
            val style = when {
                config.weight >= 600 && config.italic -> Typeface.BOLD_ITALIC
                config.weight >= 600 -> Typeface.BOLD
                config.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(finalBaseTf, style)
        }
    }

    private fun loadNarrowTypeface(config: FontConfig): Typeface? {
        val assetManager = moduleAssetManager()
        val narrowFontSource = if (assetManager != null) {
            NARROW_FONT_ASSET_PATH
        } else {
            NARROW_FONT_PATH
        }
        val key = NarrowTypefaceCacheKey(
            narrowFontSource = narrowFontSource,
            customFont = config.customFontPath?.let(FontFileState::read),
            defaultTypeface = Typeface.DEFAULT,
            weight = config.weight,
            italic = config.italic
        )
        narrowTypefaceCache?.takeIf { it.key == key }?.let { return it.typeface }

        return synchronized(narrowTypefaceLock) {
            narrowTypefaceCache?.takeIf { it.key == key }?.let {
                return@synchronized it.typeface
            }
            buildNarrowTypeface(key, assetManager).also {
                narrowTypefaceCache = NarrowTypefaceCacheEntry(key, it)
            }
        }
    }

    private fun buildNarrowTypeface(
        key: NarrowTypefaceCacheKey,
        assetManager: AssetManager?
    ): Typeface? {
        val variationWeight = key.weight.coerceIn(
            NARROW_FONT_WEIGHT_MIN,
            NARROW_FONT_WEIGHT_MAX
        )
        val slant = if (key.italic) {
            FontStyle.FONT_SLANT_ITALIC
        } else {
            FontStyle.FONT_SLANT_UPRIGHT
        }

        return try {
            val narrowFont = if (key.narrowFontSource == NARROW_FONT_ASSET_PATH &&
                assetManager != null
            ) {
                Font.Builder(assetManager, NARROW_FONT_ASSET_PATH)
                    .setFontVariationSettings(
                        "'wght' $variationWeight, 'wdth' $NARROW_FONT_WIDTH"
                    )
                    .setWeight(variationWeight)
                    .setSlant(slant)
                    .build()
            } else {
                val narrowFontState = FontFileState.read(NARROW_FONT_PATH)
                if (!narrowFontState.exists || !narrowFontState.readable) {
                    if (loggedFontFailures.add(NARROW_FONT_PATH)) {
                        HookLogger.w(
                            "FontHelper",
                            "小米窄字体文件不存在或无法读取：$NARROW_FONT_PATH " +
                                "(存在: ${narrowFontState.exists}, 可读: ${narrowFontState.readable})"
                        )
                    }
                    return null
                }
                Font.Builder(File(NARROW_FONT_PATH))
                    .setFontVariationSettings(
                        "'wght' $variationWeight, 'wdth' $NARROW_FONT_WIDTH"
                    )
                    .setWeight(variationWeight)
                    .setSlant(slant)
                    .build()
            }
            val builder = Typeface.CustomFallbackBuilder(
                FontFamily.Builder(narrowFont).build()
            )

            key.customFont?.let { customFontState ->
                buildCustomFallbackFamily(customFontState)?.let(builder::addCustomFallback)
            }

            // 使用与普通字体一致的字重/斜体作为 Typeface 样式：
            // 英文/数字由窄字体按自身 wght/wdth 轴绘制，
            // CJK 回退到系统字体时也保持用户设置的同一字重，避免 CJK 字重被改变。
            builder.setStyle(FontStyle(key.weight, slant))
                .build()
                .also {
                    if (loggedFontLoads.add(key.narrowFontSource)) {
                        HookLogger.d(
                            "FontHelper",
                            "窄字体回退链创建成功：${key.narrowFontSource}"
                        )
                    }
                }
        } catch (e: Exception) {
            if (loggedFontFailures.add(key.narrowFontSource)) {
                HookLogger.w(
                    "FontHelper",
                    "无法创建窄字体：${key.narrowFontSource}，原因: ${e.message}"
                )
            }
            null
        }
    }

    private fun buildCustomFallbackFamily(state: FontFileState): FontFamily? {
        if (!state.exists || !state.readable) {
            if (loggedFontFailures.add(state.path)) {
                HookLogger.w(
                    "FontHelper",
                    "自定义字体文件不存在或无法读取：${state.path} " +
                        "(存在: ${state.exists}, 可读: ${state.readable})"
                )
            }
            return null
        }

        return try {
            FontFamily.Builder(Font.Builder(File(state.path)).build())
                .build()
                .also {
                    if (loggedFontLoads.add(state.path)) {
                        HookLogger.d("FontHelper", "自定义字体已加入窄字体回退链：${state.path}")
                    }
                }
        } catch (e: Exception) {
            if (loggedFontFailures.add(state.path)) {
                HookLogger.w("FontHelper", "无法从文件创建字体：${state.path}，原因: ${e.message}")
            }
            null
        }
    }

    private fun moduleAssetManager(): AssetManager? {
        moduleAssetManagerCache?.let { return it }
        val context = HookEntry.instance?.moduleContext() ?: return null
        return context.assets.also {
            moduleAssetManagerCache = it
        }
    }

    private data class FontConfig(
        val weight: Int,
        val italic: Boolean,
        val customFontPath: String?
    )

    private data class FontFileState(
        val path: String,
        val exists: Boolean,
        val readable: Boolean,
        val length: Long,
        val lastModified: Long
    ) {
        companion object {
            fun read(path: String): FontFileState {
                val file = File(path)
                return FontFileState(
                    path = path,
                    exists = file.exists(),
                    readable = file.canRead(),
                    length = file.length(),
                    lastModified = file.lastModified()
                )
            }
        }
    }

    private data class NarrowTypefaceCacheKey(
        val narrowFontSource: String,
        val customFont: FontFileState?,
        val defaultTypeface: Typeface,
        val weight: Int,
        val italic: Boolean
    )

    private data class NarrowTypefaceCacheEntry(
        val key: NarrowTypefaceCacheKey,
        val typeface: Typeface?
    )

    private const val NARROW_FONT_ASSET_PATH = "fonts/MiSansCondensed2T_latin_only.ttf"
    private const val NARROW_FONT_PATH = "/product/fonts/MiSansCondensed2T.ttf"
    private const val NARROW_FONT_WIDTH = 30
    private const val NARROW_FONT_WEIGHT_MIN = 100
    private const val NARROW_FONT_WEIGHT_MAX = 900
}
