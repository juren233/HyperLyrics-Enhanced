/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("PrivateApi")

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaAmbientFlowPalette
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaAmbientFlowPaletteExtractor
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaArtworkSampler
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowArtwork
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowBackgroundView
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowOverlayLayout
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowTone
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.background.NotificationMediaBackgroundController
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal data class AmbientFlowControllerState(
    var view: View? = null,
    var nativeApi: NativeMusicBgApi? = null,
    var customView: Boolean = false,
    var lastMediaData: Any? = null,
    var colorToken: String? = null,
    var pendingColorToken: String? = null,
    var isPlaying: Boolean = false,
    var hasColors: Boolean = false,
    var flowActive: Boolean = false,
    val colorRequest: AtomicInteger = AtomicInteger()
)

internal sealed interface MediaFlowColorPayload {
    data class Native(val palette: MediaAmbientFlowPalette) : MediaFlowColorPayload
    data class Custom(val artwork: MediaFlowArtwork) : MediaFlowColorPayload
}

internal data class ControllerThemeState(val originalContext: Context)

internal fun NotificationMediaAmbientFlowHooker.newColorExecutor() = Executors.newSingleThreadExecutor { task ->
    Thread(task, "HyperLyrics Enhanced-MediaColor").apply { isDaemon = true }
}

internal class CardThemeApi private constructor(
    private val contextField: Field,
    private val updateForegroundColorsMethod: Method,
    private val updateMediaBackgroundMethod: Method?
) {
    fun apply(controller: Any, theme: Int, refreshViews: Boolean) {
        val existingState = NotificationMediaAmbientFlowHooker.themeStates[controller]
        val originalContext = existingState?.originalContext
            ?: contextField.get(controller) as Context
        val themedContext = when (theme) {
            RootConstants.MEDIA_CARD_THEME_ALWAYS_LIGHT ->
                originalContext.withNightMode(Configuration.UI_MODE_NIGHT_NO)
            RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK ->
                originalContext.withNightMode(Configuration.UI_MODE_NIGHT_YES)
            else -> originalContext
        }

        if (theme == RootConstants.MEDIA_CARD_THEME_FOLLOW_SYSTEM) {
            if (existingState != null) {
                contextField.set(controller, originalContext)
                NotificationMediaAmbientFlowHooker.themeStates.remove(controller)
            }
        } else {
            NotificationMediaAmbientFlowHooker.themeStates[controller] = ControllerThemeState(originalContext)
            contextField.set(controller, themedContext)
        }

        if (refreshViews) {
            updateForegroundColorsMethod.invoke(controller)
            updateMediaBackgroundMethod?.invoke(controller)
        }
    }

    fun restore(controller: Any) {
        val state = NotificationMediaAmbientFlowHooker.themeStates.remove(controller) ?: return
        contextField.set(controller, state.originalContext)
    }

    private fun Context.withNightMode(nightMode: Int): Context {
        val configuration = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        return createConfigurationContext(configuration)
    }

    companion object {
        fun create(classLoader: ClassLoader): CardThemeApi {
            val controllerClass = NotificationMediaAmbientFlowHooker.controllerClassNames.firstNotNullOfOrNull { className ->
                runCatching { classLoader.loadClass(className) }.getOrNull()
            } ?: error("Media controller class is unavailable")
            return CardThemeApi(
                contextField = findRequiredField(controllerClass, "context"),
                updateForegroundColorsMethod = NotificationMediaAmbientFlowHooker.findNearestMethods(
                    controllerClass,
                    NotificationMediaHookMethodProfile.UPDATE_FOREGROUND_COLORS
                ).single { it.parameterCount == 0 && it.returnType == Void.TYPE }
                    .apply { isAccessible = true },
                updateMediaBackgroundMethod = NotificationMediaAmbientFlowHooker.findNearestMethods(
                    controllerClass,
                    NotificationMediaHookMethodProfile.UPDATE_MEDIA_BACKGROUND
                ).singleOrNull { it.parameterCount == 0 && it.returnType == Void.TYPE }
                    ?.apply { isAccessible = true }
            )
        }

        private fun findRequiredField(type: Class<*>, name: String): Field {
            var current: Class<*>? = type
            while (current != null) {
                runCatching { current.getDeclaredField(name) }.getOrNull()?.let { field ->
                    field.isAccessible = true
                    return field
                }
                current = current.superclass
            }
            error("No field $name in ${type.name}")
        }
    }
}

internal class NativeMusicBgApi private constructor(
    private val viewClass: Class<*>,
    private val constructor: java.lang.reflect.Constructor<*>,
    private val setGradientColorMethod: Method,
    private val startMethod: Method,
    private val resumeMethod: Method,
    private val pauseMethod: Method,
    private val getMainColorMethod: Method,
    private val getPaletteColorMethod: Method,
    private val drawableToBitmapMethod: Method,
    private val frameLoopShaderField: Field?,
    private val frameLoopEnableMethod: Method?,
    private val frameLoopEnableValue: Any?
) {
    fun createView(context: Context): View = constructor.newInstance(context) as View

    fun accepts(view: View): Boolean = viewClass.isInstance(view)

    fun setGradientColor(view: View, mainColor: Int, colors: IntArray) {
        setGradientColorMethod.invoke(view, mainColor, colors)
    }

    fun start(view: View) {
        startMethod.invoke(view)
    }

    fun resume(view: View) {
        resumeMethod.invoke(view)
    }

    fun pause(view: View) {
        pauseMethod.invoke(view)
    }

    /**
     * 恢复原生 MusicBgView 的着色器帧循环。
     *
     * 原生帧循环存在无自愈死态：`MusicBgView.pause()` 无条件清除 `mNeedResumeShader`
     * 恢复标记，`resume()` 对未暂停状态 early-return，`setFrameLoopStrategy` 在着色器
     * 未运行时是 no-op；一旦帧循环被禁用且 pause 标志为 false，start/resume 都无法
     * 复活它。播放中显式下发 `FrameLoopStrategy.ENABLE`（幂等）兜底恢复。
     */
    fun ensureFrameLoopEnabled(view: View): Boolean {
        val shaderField = frameLoopShaderField ?: return false
        val enableMethod = frameLoopEnableMethod ?: return false
        val enableValue = frameLoopEnableValue ?: return false
        if (!accepts(view)) return false
        return runCatching {
            val shader = shaderField.get(view) ?: return false
            enableMethod.invoke(shader, enableValue)
            true
        }.getOrDefault(false)
    }

    fun extractSystemPalette(drawable: Drawable): MediaAmbientFlowPalette {
        val bitmap = if (drawableToBitmapMethod.parameterCount == 1) {
            drawableToBitmapMethod.invoke(null, drawable) as Bitmap
        } else {
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            drawableToBitmapMethod.invoke(null, drawable, width, height) as Bitmap
        }
        val mainColor = getMainColorMethod.invoke(null, bitmap) as Int
        return createPalette(mainColor)
    }

    fun createPalette(mainColor: Int): MediaAmbientFlowPalette {
        val colors = intArrayOf(
            getPaletteColor(mainColor, "primary", 12),
            getPaletteColor(mainColor, "primary", 10),
            getPaletteColor(mainColor, "tertiary", 12)
        )
        return MediaAmbientFlowPalette(mainColor, colors)
    }

    private fun getPaletteColor(mainColor: Int, role: String, tone: Int): Int {
        return getPaletteColorMethod.invoke(null, mainColor, role, tone) as Int
    }

    companion object {
        fun create(classLoader: ClassLoader): NativeMusicBgApi {
            val viewClass = classLoader.loadClass("com.mi.widget.view.MusicBgView")
            val constructor = viewClass.getDeclaredConstructor(Context::class.java).apply {
                isAccessible = true
            }
            val setGradientColor = viewClass.getDeclaredMethod(
                "setGradientColor",
                Int::class.javaPrimitiveType,
                IntArray::class.java
            ).apply { isAccessible = true }
            val start = viewClass.getDeclaredMethod("start").apply { isAccessible = true }
            val resume = viewClass.getDeclaredMethod("resume").apply { isAccessible = true }
            val pause = viewClass.getDeclaredMethod("pause").apply { isAccessible = true }

            val drawableUtils = classLoader.loadClass("com.miui.utils.DrawableUtils")
            val drawableToBitmap = drawableUtils.declaredMethods
                .firstOrNull { method ->
                    method.name == "drawable2Bitmap" &&
                        method.returnType == Bitmap::class.java &&
                        method.parameterTypes.contentEquals(
                            arrayOf(
                                Drawable::class.java,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType
                            )
                        )
                }
                ?: drawableUtils.declaredMethods.firstOrNull { method ->
                    method.name == "drawable2Bitmap" &&
                        method.returnType == Bitmap::class.java &&
                        method.parameterTypes.contentEquals(arrayOf(Drawable::class.java))
                }
                ?.apply { isAccessible = true }
                ?: error("No compatible DrawableUtils.drawable2Bitmap method")

            val miPalette = classLoader.loadClass("miuix.mipalette.MiPalette")
            miPalette.declaredMethods.firstOrNull { method ->
                method.name == "init" && method.parameterCount == 0
            }?.apply { isAccessible = true }?.invoke(null)
            val getMainColor = miPalette.getDeclaredMethod(
                "getMainColorHCT",
                Bitmap::class.java
            ).apply { isAccessible = true }
            val getPaletteColor = miPalette.getDeclaredMethod(
                "getPaletteColor",
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val frameLoopKick = resolveFrameLoopKick(viewClass)
            if (frameLoopKick == null) {
                HookLogger.w(NotificationMediaAmbientFlowHooker.TAG, "流光帧循环恢复接口不可用，保留原生 pause/resume 语义")
            }

            return NativeMusicBgApi(
                viewClass = viewClass,
                constructor = constructor,
                setGradientColorMethod = setGradientColor,
                startMethod = start,
                resumeMethod = resume,
                pauseMethod = pause,
                getMainColorMethod = getMainColor,
                getPaletteColorMethod = getPaletteColor,
                drawableToBitmapMethod = drawableToBitmap,
                frameLoopShaderField = frameLoopKick?.first,
                frameLoopEnableMethod = frameLoopKick?.second,
                frameLoopEnableValue = frameLoopKick?.third
            )
        }

        /**
         * 容错解析 MusicBgView → mShader → setFrameLoopStrategy*(FrameLoopStrategy) →
         * ENABLE 常量的调用链。方法名带库版本混淆后缀，按前缀匹配；任一环节缺失时
         * 返回 null，降级为不强制恢复帧循环。
         */
        private fun resolveFrameLoopKick(
            viewClass: Class<*>
        ): Triple<Field, Method, Any>? {
            return runCatching {
                val shaderField = viewClass.declaredFields
                    .firstOrNull { it.name == "mShader" }
                    ?: return null
                shaderField.isAccessible = true
                val enableMethod = shaderField.type.declaredMethods.firstOrNull { method ->
                    method.name.startsWith("setFrameLoopStrategy") &&
                        method.parameterCount == 1
                } ?: return null
                enableMethod.isAccessible = true
                val strategyType = enableMethod.parameterTypes[0]
                val enableValue = runCatching {
                    strategyType.getField("ENABLE").get(null)
                }.getOrNull() ?: return null
                Triple(shaderField, enableMethod, enableValue)
            }.getOrNull()
        }
    }
}

internal const val CONTROLLER_PACKAGE =
    "com.android.systemui.statusbar.notification.mediacontrol."
// Hidden PowerManager level that permits frame submission while the display is dozing.
internal const val DRAW_WAKE_LOCK_LEVEL = 0x80
internal const val FLOW_WAKE_LOCK_TIMEOUT_MS = 1_000L
internal const val FLOW_KEEP_ALIVE_INTERVAL_MS = 700L
internal const val FLOW_FADE_OUT_DURATION_MS = 200L
internal val TARGET_METHOD_NAMES = listOf(
    "attach",
    "detach",
    "bindMediaData",
    NotificationMediaHookMethodProfile.UPDATE_FOREGROUND_COLORS,
    NotificationMediaHookMethodProfile.UPDATE_MEDIA_BACKGROUND
)
internal val NATIVE_BACKGROUND_UPDATE_METHODS = setOf(
    NotificationMediaHookMethodProfile.UPDATE_FOREGROUND_COLORS,
    NotificationMediaHookMethodProfile.UPDATE_MEDIA_BACKGROUND
)
internal const val HYPER_PROGRESS_SEEK_BAR_CLASS =
    "miuix.miuixbasewidget.widget.HyperProgressSeekBar"
