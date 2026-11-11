/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Exact binary contract for pre-helper Dynamic Island width calculation. */
internal object IslandLegacyWidthProfile {
    const val BASE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    const val CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
    const val VIEW_CLASS = "android.view.View"
    const val CALCULATE_METHOD = "calculateBigIslandWidth"
    const val UPDATE_LAYOUT_METHOD = "updateBigIslandLayout"
    const val ISLAND_HEIGHT_GETTER = "getIslandViewHeight"

    const val MAX_WIDTH_FIELD = "maxWidth"
    const val VIEW_WIDTH_FIELD = "bigIslandViewWidth"
    const val LEFT_WIDTH_FIELD = "bigIslandLeftWidth"
    const val RIGHT_WIDTH_FIELD = "bigIslandRightWidth"
    const val VIEW_X_FIELD = "bigIslandX"
    const val CUTOUT_WIDTH_FIELD = "cutoutWidth"

    const val MIN_WIDTH_RESOURCE_PACKAGE = "miui.systemui.plugin"
    const val MIN_WIDTH_RESOURCE_TYPE = "dimen"
    const val MIN_WIDTH_RESOURCE_NAME = "big_island_min_width"

    const val FLIP_UTILS_CLASS = "miui.systemui.util.FlipUtils"
    const val FLIP_TINY_METHOD = "isFlipTiny"

    // 旧版岛背景提交（Issue #65）：原生「大岛变化」提交所需的类与方法，来自 plugin3.dex 原件。
    const val ANIMATION_DELEGATE_CLASS =
        "miui.systemui.dynamicisland.anim.DynamicIslandAnimationDelegate"
    const val EVENT_COORDINATOR_CLASS =
        "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator"
    const val STATE_CLASS = "miui.systemui.dynamicisland.event.DynamicIslandState"
    const val BIG_ISLAND_STATE_CLASS = "miui.systemui.dynamicisland.event.DynamicIslandState\$BigIsland"

    val GET_STATE_METHOD = LegacyMethodSpec(BASE_CONTENT_VIEW_CLASS, "getState", STATE_CLASS)
    val GET_ANIMATOR_DELEGATE_METHOD =
        LegacyMethodSpec(BASE_CONTENT_VIEW_CLASS, "getAnimatorDelegate", ANIMATION_DELEGATE_CLASS)
    val GET_EVENT_COORDINATOR_METHOD = LegacyMethodSpec(
        BASE_CONTENT_VIEW_CLASS,
        "getDynamicIslandEventCoordinator",
        EVENT_COORDINATOR_CLASS,
    )
    val IS_TEMP_HIDDEN_METHOD = LegacyMethodSpec(
        EVENT_COORDINATOR_CLASS,
        "isTempHidden",
        java.lang.Boolean.TYPE.name,
        listOf(CONTENT_VIEW_CLASS),
    )
    val BIG_ISLAND_CHANGED_ANIMATION_METHOD = LegacyMethodSpec(
        ANIMATION_DELEGATE_CLASS,
        "bigIslandChangedAnimation",
        Void.TYPE.name,
        listOf(CONTENT_VIEW_CLASS),
    )

    fun matches(spec: LegacyMethodSpec, method: Method): Boolean = matches(
        spec = spec,
        declaringClassName = method.declaringClass.name,
        name = method.name,
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map { it.name },
        isStatic = Modifier.isStatic(method.modifiers),
    )

    internal fun matches(
        spec: LegacyMethodSpec,
        declaringClassName: String,
        name: String,
        returnTypeName: String,
        parameterTypeNames: List<String>,
        isStatic: Boolean = false,
    ): Boolean = declaringClassName == spec.declaringClass &&
        name == spec.name &&
        returnTypeName == spec.returnType &&
        parameterTypeNames == spec.parameterTypes &&
        !isStatic

    private val requiredIntFields = setOf(
        VIEW_WIDTH_FIELD,
        LEFT_WIDTH_FIELD,
        RIGHT_WIDTH_FIELD,
        VIEW_X_FIELD,
        CUTOUT_WIDTH_FIELD,
    )

    fun isCalculateMethod(method: Method): Boolean = isCalculateMethod(
        declaringClassName = method.declaringClass.name,
        name = method.name,
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map { it.name },
        isStatic = Modifier.isStatic(method.modifiers),
    )

    internal fun isCalculateMethod(
        declaringClassName: String,
        name: String,
        returnTypeName: String,
        parameterTypeNames: List<String>,
        isStatic: Boolean = false,
    ): Boolean = declaringClassName == BASE_CONTENT_VIEW_CLASS &&
        name == CALCULATE_METHOD &&
        returnTypeName == Void.TYPE.name &&
        parameterTypeNames == listOf(VIEW_CLASS, VIEW_CLASS) &&
        !isStatic

    fun isUpdateLayoutMethod(method: Method): Boolean = isUpdateLayoutMethod(
        declaringClassName = method.declaringClass.name,
        name = method.name,
        returnTypeName = method.returnType.name,
        parameterTypeNames = method.parameterTypes.map { it.name },
        isStatic = Modifier.isStatic(method.modifiers),
    )

    internal fun isUpdateLayoutMethod(
        declaringClassName: String,
        name: String,
        returnTypeName: String,
        parameterTypeNames: List<String>,
        isStatic: Boolean = false,
    ): Boolean = declaringClassName == CONTENT_VIEW_CLASS &&
        name == UPDATE_LAYOUT_METHOD &&
        returnTypeName == Void.TYPE.name &&
        parameterTypeNames.isEmpty() &&
        !isStatic

    fun isIslandHeightGetter(method: Method): Boolean =
        method.declaringClass.name == BASE_CONTENT_VIEW_CLASS &&
            method.name == ISLAND_HEIGHT_GETTER &&
            method.returnType == Int::class.javaPrimitiveType &&
            method.parameterTypes.isEmpty() &&
            !Modifier.isStatic(method.modifiers)

    fun isRequiredField(field: Field): Boolean = isRequiredField(
        declaringClassName = field.declaringClass.name,
        name = field.name,
        typeName = field.type.name,
        isStatic = Modifier.isStatic(field.modifiers),
    )

    internal fun isRequiredField(
        declaringClassName: String,
        name: String,
        typeName: String,
        isStatic: Boolean = false,
    ): Boolean = declaringClassName == BASE_CONTENT_VIEW_CLASS &&
        !isStatic &&
        when (name) {
            MAX_WIDTH_FIELD -> typeName == java.lang.Float.TYPE.name
            in requiredIntFields -> typeName == java.lang.Integer.TYPE.name
            else -> false
        }

    fun unlockedGeometry(
        nativeWidth: Int,
        nativeX: Int,
        cutoutWidth: Int,
        measuredLeftWidth: Int,
        measuredRightWidth: Int,
    ): IslandLegacyUnlockedGeometry? {
        if (nativeWidth <= 0 || cutoutWidth < 0) return null
        val maxSide = maxOf(measuredLeftWidth, measuredRightWidth)
        if (maxSide <= 0) return null
        val unlockedWidth = cutoutWidth.toLong() + 2L * maxSide
        if (unlockedWidth <= nativeWidth || unlockedWidth > Int.MAX_VALUE) return null
        val screenWidth = 2L * nativeX + nativeWidth
        val centeredX = (screenWidth - unlockedWidth) / 2L
        if (centeredX !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        return IslandLegacyUnlockedGeometry(
            viewWidth = unlockedWidth.toInt(),
            leftWidth = maxSide,
            rightWidth = maxSide,
            viewX = centeredX.toInt(),
        )
    }
}

/** 旧版二进制中一个实例方法的精确描述（声明类、方法名、返回类型、参数类型）。 */
internal data class LegacyMethodSpec(
    val declaringClass: String,
    val name: String,
    val returnType: String,
    val parameterTypes: List<String> = emptyList(),
)

internal data class IslandLegacyUnlockedGeometry(
    val viewWidth: Int,
    val leftWidth: Int,
    val rightWidth: Int,
    val viewX: Int,
)
