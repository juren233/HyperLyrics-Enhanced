/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.lyric

import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** Applies the same native and HyperOS blur mechanisms used by Apple Music lyrics. */
object AppleLyricsBlurRenderer {
    private const val HYPER_OS_SELF_BLUR_TYPE = 0

    private val methodsByClass = Collections.synchronizedMap(
        WeakHashMap<Class<*>, HyperOsMethods>()
    )

    fun apply(view: View, mode: Int, radiusPx: Int) {
        when (AppleLyricsBlurPolicy.normalizeMode(mode)) {
            AppleLyricsBlurPolicy.NATIVE -> {
                clearHyperOsBlur(view)
                view.setRenderEffect(
                    radiusPx.takeIf { it > 0 }?.let { radius ->
                        RenderEffect.createBlurEffect(
                            radius.toFloat(),
                            radius.toFloat(),
                            Shader.TileMode.CLAMP,
                        )
                    }
                )
            }

            AppleLyricsBlurPolicy.ADVANCED_MATERIAL -> {
                view.setRenderEffect(null)
                if (!applyHyperOsBlur(view, radiusPx)) {
                    clear(view)
                }
            }

            else -> clear(view)
        }
    }

    fun clear(view: View) {
        view.setRenderEffect(null)
        clearHyperOsBlur(view)
    }

    private fun applyHyperOsBlur(view: View, radiusPx: Int): Boolean {
        val methods = methodsByClass[view.javaClass] ?: resolveMethods(view.javaClass).also {
            methodsByClass[view.javaClass] = it
        }
        val setSelfBlur = methods.setSelfBlur ?: return false
        return runCatching {
            methods.setSelfBlurType?.invoke(view, HYPER_OS_SELF_BLUR_TYPE)
            setSelfBlur.invoke(view, radiusPx.coerceAtLeast(0), ArrayList<Any>())
            true
        }.getOrElse { false }
    }

    private fun clearHyperOsBlur(view: View) {
        val methods = methodsByClass[view.javaClass] ?: resolveMethods(view.javaClass).also {
            methodsByClass[view.javaClass] = it
        }
        runCatching {
            methods.setSelfBlur?.invoke(view, 0, ArrayList<Any>())
        }
    }

    private fun resolveMethods(clazz: Class<*>): HyperOsMethods {
        fun findPublicMethod(name: String, vararg parameterTypes: Class<*>): Method? =
            runCatching {
                clazz.getMethod(name, *parameterTypes).apply { isAccessible = true }
            }.getOrNull()

        return HyperOsMethods(
            setSelfBlur = findPublicMethod(
                "setMiSelfBlur",
                Int::class.javaPrimitiveType!!,
                ArrayList::class.java,
            ),
            setSelfBlurType = findPublicMethod(
                "setMiSelfBlurType",
                Int::class.javaPrimitiveType!!,
            ),
        )
    }

    private data class HyperOsMethods(
        val setSelfBlur: Method?,
        val setSelfBlurType: Method?,
    )
}
