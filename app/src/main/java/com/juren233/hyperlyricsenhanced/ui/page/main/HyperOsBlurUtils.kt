/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import android.util.Log
import android.provider.Settings
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.lang.reflect.Method

/**
 * HyperCeiler's fan.miuix 1.0.10.5 binary resolves these exact vendor View APIs.
 * Keep the names centralized because aliases shown by decompilers are not valid runtime names.
 */
internal object HyperOsBlurRuntimeIdentifiers {
    const val ADD_BACKGROUND_BLEND_COLOR = "addMiBackgroundBlendColor"
    const val CLEAR_BACKGROUND_BLEND_COLOR = "clearMiBackgroundBlendColor"
    const val SET_BACKGROUND_BLUR_MODE = "setMiBackgroundBlurMode"
    const val SET_BACKGROUND_BLUR_RADIUS = "setMiBackgroundBlurRadius"
    const val SET_VIEW_BLUR_MODE = "setMiViewBlurMode"

    val exactNames = setOf(
        ADD_BACKGROUND_BLEND_COLOR,
        CLEAR_BACKGROUND_BLEND_COLOR,
        SET_BACKGROUND_BLUR_MODE,
        SET_BACKGROUND_BLUR_RADIUS,
        SET_VIEW_BLUR_MODE,
    )
}

/** Debug-only evidence for the About material path; release builds emit nothing. */
internal object AboutDebugLog {
    const val TAG = "HyperLyricsAbout"

    private var lastPagerMarker: String? = null

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun w(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }

    fun viewLabel(view: View): String =
        "${view.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(view))}"

    fun pager(
        active: Boolean,
        offsetFraction: Float,
        currentPage: Int,
        settledPage: Int,
        targetPage: Int,
        involved: Boolean,
        entryAlpha: Float,
    ) {
        if (!BuildConfig.DEBUG) return
        val offsetBucket = (offsetFraction * 20f).toInt()
        val marker = "$active:$offsetBucket:$currentPage:$settledPage:$targetPage:$involved"
        if (marker == lastPagerMarker) return
        lastPagerMarker = marker
        d(
            "pager active=$active involved=$involved offset=$offsetFraction " +
                "bucket=$offsetBucket current=$currentPage settled=$settledPage " +
                "target=$targetPage entryAlpha=$entryAlpha",
        )
    }
}

/** Minimal reflection bridge matching the View calls made by HyperCeiler's MiuiBlurUtils. */
internal object HyperOsBlurUtils {
    private val methodCache = mutableMapOf<MethodKey, Method>()

    fun isEffectEnabled(view: View): Boolean {
        return try {
            val setting = Settings.Secure.getInt(
                view.context.contentResolver,
                BACKGROUND_BLUR_ENABLE_SETTING,
                0,
            )
            val enabled = setting == 1
            AboutDebugLog.d(
                "effect_enabled setting=$BACKGROUND_BLUR_ENABLE_SETTING value=$setting result=$enabled",
            )
            enabled
        } catch (error: Throwable) {
            AboutDebugLog.w(
                "effect_enabled exception=${error.javaClass.simpleName}:${error.message}",
            )
            false
        }
    }

    fun setBackgroundBlur(view: View, radiusPx: Int): Boolean {
        val modeApplied = invokeInt(
            view,
            HyperOsBlurRuntimeIdentifiers.SET_BACKGROUND_BLUR_MODE,
            BACKGROUND_BLUR_MODE_ENABLED,
        )
        val radiusApplied = invokeInt(
            view,
            HyperOsBlurRuntimeIdentifiers.SET_BACKGROUND_BLUR_RADIUS,
            radiusPx.coerceAtMost(MAX_BACKGROUND_BLUR_RADIUS_PX),
        )
        val applied = modeApplied && radiusApplied
        AboutDebugLog.d(
            "background_blur view=${AboutDebugLog.viewLabel(view)} radius=$radiusPx " +
                "mode=$modeApplied radiusApplied=$radiusApplied result=$applied",
        )
        return applied
    }

    fun setViewBlurMode(view: View, mode: Int): Boolean = invokeInt(
        view,
        HyperOsBlurRuntimeIdentifiers.SET_VIEW_BLUR_MODE,
        mode,
    )

    fun addBackgroundBlendColor(view: View, color: Int, mode: Int): Boolean = invoke(
        view = view,
        name = HyperOsBlurRuntimeIdentifiers.ADD_BACKGROUND_BLEND_COLOR,
        parameterTypes = arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
        arguments = arrayOf(color, mode),
    )

    fun clearBackgroundBlendColors(view: View): Boolean = invoke(
        view = view,
        name = HyperOsBlurRuntimeIdentifiers.CLEAR_BACKGROUND_BLEND_COLOR,
        parameterTypes = emptyArray(),
        arguments = emptyArray(),
    )

    fun clearBackgroundBlur(view: View) {
        val modeApplied = invokeInt(view, HyperOsBlurRuntimeIdentifiers.SET_BACKGROUND_BLUR_MODE, 0)
        val viewModeApplied = setViewBlurMode(view, 0)
        AboutDebugLog.d(
            "background_blur_clear view=${AboutDebugLog.viewLabel(view)} " +
                "mode=$modeApplied viewMode=$viewModeApplied",
        )
    }

    private fun invokeInt(view: View, name: String, argument: Int): Boolean = invoke(
        view = view,
        name = name,
        parameterTypes = arrayOf(Int::class.javaPrimitiveType!!),
        arguments = arrayOf(argument),
    )

    private fun invoke(
        view: View,
        name: String,
        parameterTypes: Array<Class<*>>,
        arguments: Array<Any>,
    ): Boolean {
        return try {
            val key = MethodKey(name, parameterTypes.toList())
            val method = synchronized(methodCache) {
                methodCache.getOrPut(key) {
                    View::class.java.getMethod(name, *parameterTypes)
                }
            }
            val returnValue = method.invoke(view, *arguments)
            val applied = (returnValue as? Boolean) ?: true
            AboutDebugLog.d(
                "api name=$name view=${AboutDebugLog.viewLabel(view)} " +
                    "args=${arguments.joinToString(prefix = "[", postfix = "]")} " +
                    "return=${returnValue ?: "void"} result=$applied",
            )
            applied
        } catch (error: Throwable) {
            AboutDebugLog.w(
                "api name=$name view=${AboutDebugLog.viewLabel(view)} " +
                    "args=${arguments.joinToString(prefix = "[", postfix = "]")} " +
                    "exception=${error.javaClass.simpleName}:${error.message}",
            )
            false
        }
    }

    private data class MethodKey(
        val name: String,
        val parameterTypes: List<Class<*>>,
    )

    private const val BACKGROUND_BLUR_ENABLE_SETTING = "background_blur_enable"
    private const val BACKGROUND_BLUR_MODE_ENABLED = 1
    private const val MAX_BACKGROUND_BLUR_RADIUS_PX = 400
}
