/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import android.annotation.SuppressLint
import android.content.res.Resources
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport.TAG
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Compatibility path for the original, stateful pre-helper island width implementation. */
internal object IslandLegacyWidthHooker {
    private val calculationWindow = ThreadLocal<CalculationWindow?>()
    private val firstCalculationLogged = AtomicBoolean(false)
    private val firstMinWidthLogged = AtomicBoolean(false)
    private val firstUnlockLogged = AtomicBoolean(false)
    private val missingMinResourceLogged = AtomicBoolean(false)

    @Volatile
    private var resourceHookInstalled = false

    fun install(module: XposedModule, cl: ClassLoader): Boolean {
        val baseClass = runCatching {
            cl.loadClass(IslandLegacyWidthProfile.BASE_CONTENT_VIEW_CLASS)
        }.getOrNull() ?: return false
        val contentClass = runCatching {
            cl.loadClass(IslandLegacyWidthProfile.CONTENT_VIEW_CLASS)
        }.getOrNull() ?: return false
        if (!baseClass.isAssignableFrom(contentClass)) {
            HookLogger.w(TAG, "旧版岛宽 Hook 跳过: 内容类不继承已验证基类")
            return false
        }

        val calculateMethod = baseClass.declaredMethods
            .filter(IslandLegacyWidthProfile::isCalculateMethod)
            .singleOrNull()
        val updateLayoutMethod = contentClass.declaredMethods
            .filter(IslandLegacyWidthProfile::isUpdateLayoutMethod)
            .singleOrNull()
        val islandHeightGetter = baseClass.methods
            .filter(IslandLegacyWidthProfile::isIslandHeightGetter)
            .singleOrNull()
        val fields = LegacyFields.resolve(baseClass)
        if (calculateMethod == null || updateLayoutMethod == null || islandHeightGetter == null || fields == null) {
            HookLogger.w(
                TAG,
                "旧版岛宽 Hook 跳过: 二进制契约不完整 " +
                    "calculate=${calculateMethod != null}, layout=${updateLayoutMethod != null}, " +
                    "height=${islandHeightGetter != null}, fields=${fields != null}",
            )
            return false
        }

        val minWidthHookAvailable = installResourceHookOnce(module)
        calculateMethod.isAccessible = true
        updateLayoutMethod.isAccessible = true
        islandHeightGetter.isAccessible = true
        module.deoptimize(calculateMethod)
        module.deoptimize(updateLayoutMethod)
        module.hook(calculateMethod).intercept(
            LegacyCalculationHook(fields, islandHeightGetter, minWidthHookAvailable),
        )
        module.hook(updateLayoutMethod).intercept(LegacyUpdateLayoutHook(fields))
        HookLogger.i(
            TAG,
            "已安装旧版岛宽兼容 Hook: calculate=$calculateMethod, " +
                "layout=$updateLayoutMethod, minWidthResourceHook=$minWidthHookAvailable",
        )
        return true
    }

    @Synchronized
    private fun installResourceHookOnce(module: XposedModule): Boolean {
        if (resourceHookInstalled) return true
        return runCatching {
            val method = Resources::class.java.getDeclaredMethod(
                "getDimensionPixelSize",
                Int::class.javaPrimitiveType,
            )
            module.deoptimize(method)
            module.hook(method).intercept(MinWidthResourceHook())
            resourceHookInstalled = true
            true
        }.onFailure { error ->
            HookLogger.e(TAG, "安装旧版动态最小岛宽资源 Hook 失败", error)
        }.getOrDefault(false)
    }

    private class LegacyCalculationHook(
        private val fields: LegacyFields,
        private val islandHeightGetter: Method,
        private val minWidthHookAvailable: Boolean,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            if (!IslandWidthHooker.lyricWidthCalculationActive) return chain.proceed()
            val host = chain.thisObject as? View ?: return chain.proceed()
            val left = chain.args.getOrNull(0) as? View
            val right = chain.args.getOrNull(1) as? View
            val dynamicWidth = IslandViewHelper.isDynamicWidthEnabled()
            val resetSnapshot = if (dynamicWidth) {
                runCatching {
                    WidthInputSnapshot(
                        leftWidth = fields.leftWidth.getInt(host),
                        rightWidth = fields.rightWidth.getInt(host),
                    ).also {
                        fields.leftWidth.setInt(host, 0)
                        fields.rightWidth.setInt(host, 0)
                    }
                }.getOrNull()
            } else {
                null
            }
            val resources = host.resources
            val minWidth = if (dynamicWidth && minWidthHookAvailable) {
                resolveMinWidthOverride(host, resources, islandHeightGetter)
            } else {
                null
            }
            val current = CalculationWindow(
                host = host,
                measuredLeftWidth = left?.measuredWidth ?: 0,
                measuredRightWidth = right?.measuredWidth ?: 0,
                resources = resources,
                minWidthResourceId = minWidth?.resourceId ?: 0,
                minWidthFloor = minWidth?.floor ?: 0,
            )
            val previous = calculationWindow.get()
            calculationWindow.set(current)
            if (BuildConfig.DEBUG && firstCalculationLogged.compareAndSet(false, true)) {
                HookLogger.i(
                    TAG,
                    "旧版岛宽计算首次命中: dynamic=$dynamicWidth, " +
                        "reset=${resetSnapshot != null}, left=${current.measuredLeftWidth}, " +
                        "right=${current.measuredRightWidth}, minRes=0x${current.minWidthResourceId.toString(16)}",
                )
            }
            return try {
                chain.proceed()
            } catch (error: Throwable) {
                resetSnapshot?.let { snapshot ->
                    runCatching {
                        fields.leftWidth.setInt(host, snapshot.leftWidth)
                        fields.rightWidth.setInt(host, snapshot.rightWidth)
                    }
                }
                throw error
            } finally {
                if (previous == null) calculationWindow.remove() else calculationWindow.set(previous)
            }
        }
    }

    private class MinWidthResourceHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val result = chain.proceed()
            val window = calculationWindow.get() ?: return result
            if (window.minWidthResourceId == 0 || window.minWidthFloor <= 0) return result
            if (chain.thisObject !== window.resources) return result
            val requestedId = (chain.args.getOrNull(0) as? Number)?.toInt() ?: return result
            if (requestedId != window.minWidthResourceId) return result
            val original = (result as? Number)?.toInt() ?: return result
            val adjusted = min(original, window.minWidthFloor)
            if (BuildConfig.DEBUG && firstMinWidthLogged.compareAndSet(false, true)) {
                HookLogger.i(
                    TAG,
                    "旧版动态最小岛宽首次命中: id=0x${requestedId.toString(16)}, " +
                        "original=$original, floor=${window.minWidthFloor}, adjusted=$adjusted",
                )
            }
            return adjusted
        }
    }

    private class LegacyUpdateLayoutHook(
        private val fields: LegacyFields,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val window = calculationWindow.get()
            val host = chain.thisObject
            if (window != null && host === window.host) {
                rewriteUnlockedWidthIfNeeded(host, window, fields)
            }
            return chain.proceed()
        }
    }

    private fun rewriteUnlockedWidthIfNeeded(
        host: Any?,
        window: CalculationWindow,
        fields: LegacyFields,
    ) {
        if (host == null || IslandDynamicWidthLimiter.isEnabled()) return
        if (!IslandViewHelper.isUnlockIslandLengthEnabled() || isFlipTiny(host)) return
        runCatching {
            val nativeWidth = fields.viewWidth.getInt(host)
            val nativeX = fields.viewX.getInt(host)
            val geometry = IslandLegacyWidthProfile.unlockedGeometry(
                nativeWidth = nativeWidth,
                nativeX = nativeX,
                cutoutWidth = fields.cutoutWidth.getInt(host),
                measuredLeftWidth = window.measuredLeftWidth,
                measuredRightWidth = window.measuredRightWidth,
            ) ?: return@runCatching
            fields.viewWidth.setInt(host, geometry.viewWidth)
            fields.leftWidth.setInt(host, geometry.leftWidth)
            fields.rightWidth.setInt(host, geometry.rightWidth)
            fields.viewX.setInt(host, geometry.viewX)
            if (firstUnlockLogged.compareAndSet(false, true)) {
                HookLogger.i(
                    TAG,
                    "旧版手机岛宽上限已解除: $nativeWidth -> ${geometry.viewWidth} " +
                        "(x=$nativeX -> ${geometry.viewX}, left=${geometry.leftWidth}, " +
                        "right=${geometry.rightWidth})",
                )
            }
        }.onFailure { error ->
            HookLogger.e(TAG, "改写旧版手机岛宽结果失败", error)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun resolveMinWidthOverride(
        host: View,
        resources: Resources,
        islandHeightGetter: Method,
    ): MinWidthOverride? {
        val resourceId = resources.getIdentifier(
            IslandLegacyWidthProfile.MIN_WIDTH_RESOURCE_NAME,
            IslandLegacyWidthProfile.MIN_WIDTH_RESOURCE_TYPE,
            IslandLegacyWidthProfile.MIN_WIDTH_RESOURCE_PACKAGE,
        )
        val exactResource = resourceId != 0 && runCatching {
            resources.getResourcePackageName(resourceId) ==
                IslandLegacyWidthProfile.MIN_WIDTH_RESOURCE_PACKAGE &&
                resources.getResourceTypeName(resourceId) ==
                IslandLegacyWidthProfile.MIN_WIDTH_RESOURCE_TYPE &&
                resources.getResourceEntryName(resourceId) ==
                IslandLegacyWidthProfile.MIN_WIDTH_RESOURCE_NAME
        }.getOrDefault(false)
        if (!exactResource) {
            if (BuildConfig.DEBUG && missingMinResourceLogged.compareAndSet(false, true)) {
                HookLogger.w(TAG, "旧版动态最小岛宽跳过: 未解析到精确 big_island_min_width 资源")
            }
            return null
        }
        val height = runCatching { islandHeightGetter.invoke(host) as? Int }.getOrNull() ?: return null
        if (height <= 0) return null
        val margin = (2f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return MinWidthOverride(resourceId, height + margin)
    }

    private fun isFlipTiny(host: Any): Boolean = runCatching {
        val method = host.javaClass.classLoader
            ?.loadClass(IslandLegacyWidthProfile.FLIP_UTILS_CLASS)
            ?.getDeclaredMethod(IslandLegacyWidthProfile.FLIP_TINY_METHOD)
            ?: return@runCatching false
        if (!Modifier.isStatic(method.modifiers) ||
            method.returnType != Boolean::class.javaPrimitiveType ||
            method.parameterTypes.isNotEmpty()
        ) {
            return@runCatching false
        }
        method.invoke(null) as? Boolean
    }.getOrNull() == true

    private data class CalculationWindow(
        val host: View,
        val measuredLeftWidth: Int,
        val measuredRightWidth: Int,
        val resources: Resources,
        val minWidthResourceId: Int,
        val minWidthFloor: Int,
    )

    private data class WidthInputSnapshot(
        val leftWidth: Int,
        val rightWidth: Int,
    )

    private data class MinWidthOverride(
        val resourceId: Int,
        val floor: Int,
    )

    private data class LegacyFields(
        val maxWidth: Field,
        val viewWidth: Field,
        val leftWidth: Field,
        val rightWidth: Field,
        val viewX: Field,
        val cutoutWidth: Field,
    ) {
        companion object {
            fun resolve(baseClass: Class<*>): LegacyFields? = runCatching {
                fun field(name: String): Field = baseClass.getDeclaredField(name).also { candidate ->
                    check(IslandLegacyWidthProfile.isRequiredField(candidate))
                    candidate.isAccessible = true
                }
                LegacyFields(
                    maxWidth = field(IslandLegacyWidthProfile.MAX_WIDTH_FIELD),
                    viewWidth = field(IslandLegacyWidthProfile.VIEW_WIDTH_FIELD),
                    leftWidth = field(IslandLegacyWidthProfile.LEFT_WIDTH_FIELD),
                    rightWidth = field(IslandLegacyWidthProfile.RIGHT_WIDTH_FIELD),
                    viewX = field(IslandLegacyWidthProfile.VIEW_X_FIELD),
                    cutoutWidth = field(IslandLegacyWidthProfile.CUTOUT_WIDTH_FIELD),
                )
            }.getOrNull()
        }
    }
}
