/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal enum class AodContentLayoutResolutionSource(val diagnosticName: String) {
    KNOWN_THREE_ARGUMENT("known_iiz"),
    KNOWN_TWO_ARGUMENT("known_ii"),
    KNOWN_BOTH("known_iiz_and_ii"),
    UNIQUE_COMPATIBLE("unique_compatible"),
    UNAVAILABLE("unavailable"),
}

internal data class ResolvedAodContentLayoutMethods(
    val methods: List<Method>,
    val source: AodContentLayoutResolutionSource,
)

internal data class ResolvedAodPluginMethods(
    val hookMethods: List<Method>,
    val contentLayout: ResolvedAodContentLayoutMethods,
) {
    val diagnosticSummary: String
        get() = "contentLayout=${contentLayout.source.diagnosticName},targets=${
            hookMethods.joinToString("|") { AodPluginMethodProfile.descriptor(it) }
        }"
}

/**
 * Reflection contract for the Custom AOD plugin view.
 *
 * The lifecycle methods remain strict. The content-layout callback is optional because it is
 * only an extra positioning signal, and Xiaomi has shipped both `(int, int, boolean)` and
 * `(int, int)`. A future shape is accepted only when there is one unambiguous, same-name,
 * instance-void candidate; ambiguous shapes fail closed instead of hooking an arbitrary method.
 */
internal object AodPluginMethodProfile {
    const val CONTENT_LAYOUT_METHOD = "onAodContentLayoutChange"

    private val intType = Int::class.javaPrimitiveType!!
    private val booleanType = Boolean::class.javaPrimitiveType!!
    private val knownThreeArgumentTypes = arrayOf(intType, intType, booleanType)
    private val knownTwoArgumentTypes = arrayOf(intType, intType)
    private val requiredLifecycleMethods = listOf(
        "makeNormalPanel",
        "onAttachedToWindow",
        "onDetachedFromWindow",
        "onUpdatePositionTimer",
    )

    fun resolve(aodViewClass: Class<*>): ResolvedAodPluginMethods {
        val lifecycle = requiredLifecycleMethods.map { name ->
            aodViewClass.getDeclaredMethod(name).also { method ->
                require(isInstanceVoid(method) && method.parameterCount == 0) {
                    "Invalid Custom AOD lifecycle descriptor: ${descriptor(method)}"
                }
                method.isAccessible = true
            }
        }
        val contentLayout = resolveContentLayout(aodViewClass)
        contentLayout.methods.forEach { it.isAccessible = true }
        return ResolvedAodPluginMethods(
            hookMethods = lifecycle + contentLayout.methods,
            contentLayout = contentLayout,
        )
    }

    internal fun resolveContentLayout(aodViewClass: Class<*>): ResolvedAodContentLayoutMethods {
        val candidates = aodViewClass.declaredMethods
            .filter(::isCompatibleContentLayoutCandidate)
            .sortedBy(::descriptor)
        val knownThree = candidates.singleOrNull {
            it.parameterTypes.contentEquals(knownThreeArgumentTypes)
        }
        val knownTwo = candidates.singleOrNull {
            it.parameterTypes.contentEquals(knownTwoArgumentTypes)
        }
        val known = listOfNotNull(knownThree, knownTwo)
        if (known.isNotEmpty()) {
            val source = when {
                knownThree != null && knownTwo != null ->
                    AodContentLayoutResolutionSource.KNOWN_BOTH
                knownThree != null -> AodContentLayoutResolutionSource.KNOWN_THREE_ARGUMENT
                else -> AodContentLayoutResolutionSource.KNOWN_TWO_ARGUMENT
            }
            return ResolvedAodContentLayoutMethods(known, source)
        }
        return when (candidates.size) {
            0 -> ResolvedAodContentLayoutMethods(
                emptyList(),
                AodContentLayoutResolutionSource.UNAVAILABLE,
            )
            1 -> ResolvedAodContentLayoutMethods(
                candidates,
                AodContentLayoutResolutionSource.UNIQUE_COMPATIBLE,
            )
            else -> throw IllegalArgumentException(
                "Ambiguous Custom AOD content-layout callbacks: ${
                    candidates.joinToString("|") { descriptor(it) }
                }",
            )
        }
    }

    fun isSupportedHook(method: Method): Boolean {
        return when (method.name) {
            in requiredLifecycleMethods ->
                isInstanceVoid(method) && method.parameterCount == 0
            CONTENT_LAYOUT_METHOD -> isCompatibleContentLayoutCandidate(method)
            else -> false
        }
    }

    internal fun descriptor(method: Method): String {
        return "${method.declaringClass.name}.${method.name}(${
            method.parameterTypes.joinToString(",") { it.name }
        }):${method.returnType.name}"
    }

    private fun isCompatibleContentLayoutCandidate(method: Method): Boolean {
        return method.name == CONTENT_LAYOUT_METHOD &&
            isInstanceVoid(method) &&
            !method.isBridge &&
            !method.isSynthetic
    }

    private fun isInstanceVoid(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE
    }
}
