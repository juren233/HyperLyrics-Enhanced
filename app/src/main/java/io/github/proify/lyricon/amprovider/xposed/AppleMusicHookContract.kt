/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 契约校验结果。
 */
internal sealed interface ContractResult {
    data object Passed : ContractResult

    data class Rejected(val reason: String) : ContractResult
}

/**
 * 契约校验上下文，传递当前待校验的 Hook 点、目标描述、已加载的类/方法及环境。
 */
internal data class HookContractContext(
    val hookPoint: AppleMusicHookPoint,
    val target: AppleMusicHookTarget,
    val clazz: Class<*>?,
    val method: Method?,
    val classLoader: ClassLoader? = clazz?.classLoader,
    val classLookup: ((String) -> Class<*>)? = null,
    val dexKitResolver: AppleMusicDexKitResolver? = null,
)

/**
 * Apple Music Hook 语义契约检查器。
 *
 * 即使类名或方法签名匹配，目标也必须通过语义契约校验，
 * 避免因混淆类名碰撞误装到行为不符的旧类（如 6.5.2 误装到 6.5.0 的 e0 或 6.5.1 的 z0.v0）。
 */
internal fun interface AppleMusicHookContract {
    fun validate(context: HookContractContext): ContractResult
}

/**
 * 组合契约：必须全部满足。
 */
internal class AllOfContract(
    private val contracts: List<AppleMusicHookContract>,
) : AppleMusicHookContract {
    constructor(vararg contracts: AppleMusicHookContract) : this(contracts.toList())

    override fun validate(context: HookContractContext): ContractResult {
        contracts.forEach { contract ->
            val result = contract.validate(context)
            if (result is ContractResult.Rejected) return result
        }
        return ContractResult.Passed
    }
}

/**
 * 组合契约：满足任意一个即可。
 */
internal class AnyOfContract(
    private val contracts: List<AppleMusicHookContract>,
) : AppleMusicHookContract {
    constructor(vararg contracts: AppleMusicHookContract) : this(contracts.toList())

    override fun validate(context: HookContractContext): ContractResult {
        if (contracts.isEmpty()) return ContractResult.Passed
        val reasons = mutableListOf<String>()
        contracts.forEach { contract ->
            when (val result = contract.validate(context)) {
                ContractResult.Passed -> return ContractResult.Passed
                is ContractResult.Rejected -> reasons += result.reason
            }
        }
        return ContractResult.Rejected(reasons.joinToString("; "))
    }
}

/**
 * 契约：类必须包含至少一个指定类型的字段（包括父类）。
 */
internal class RequireFieldOfType(
    private val fieldTypeClassName: String,
    private val includeStatic: Boolean = false,
    private val fieldName: String? = null,
) : AppleMusicHookContract {
    override fun validate(context: HookContractContext): ContractResult {
        val clazz = context.clazz ?: context.method?.declaringClass
            ?: return ContractResult.Rejected("Class unavailable for field check")
        val expectedType = runCatching { context.classLookup?.invoke(fieldTypeClassName) }.getOrNull()
            ?: runCatching { context.classLoader?.loadClass(fieldTypeClassName) }.getOrNull()

        val fields = generateSequence(clazz) { it.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .filter { field -> includeStatic || !Modifier.isStatic(field.modifiers) }
            .filter { field -> fieldName == null || field.name == fieldName }
            .filter { field ->
                field.type.name == fieldTypeClassName ||
                    (expectedType != null && expectedType.isAssignableFrom(field.type))
            }
            .toList()

        return if (fields.isNotEmpty()) {
            ContractResult.Passed
        } else {
            ContractResult.Rejected(
                "Class ${clazz.name} does not contain field of type $fieldTypeClassName" +
                    (if (fieldName != null) " (name=$fieldName)" else "")
            )
        }
    }
}

/**
 * 契约：类必须具备静态自类型单例字段（如 static INSTANCE / a: LClass;）。
 */
internal class RequireStaticSelfTypedSingleton : AppleMusicHookContract {
    override fun validate(context: HookContractContext): ContractResult {
        val clazz = context.clazz ?: context.method?.declaringClass
            ?: return ContractResult.Rejected("Class unavailable for singleton check")
        val hasSingleton = clazz.declaredFields.any { field ->
            Modifier.isStatic(field.modifiers) && clazz.isAssignableFrom(field.type)
        }
        return if (hasSingleton) {
            ContractResult.Passed
        } else {
            ContractResult.Rejected(
                "Class ${clazz.name} lacks static self-typed singleton field"
            )
        }
    }
}

/**
 * 契约：方法不得为 synthetic 或 bridge。
 */
internal class RequireNonBridgeMethod : AppleMusicHookContract {
    override fun validate(context: HookContractContext): ContractResult {
        val method = context.method
            ?: return ContractResult.Rejected("Method unavailable for bridge check")
        return if (!method.isBridge && !method.isSynthetic) {
            ContractResult.Passed
        } else {
            ContractResult.Rejected(
                "Method ${method.name} in ${method.declaringClass.name} is bridge/synthetic"
            )
        }
    }
}

/**
 * 契约：方法参数类型校验。
 */
internal class RequireParameterType(
    private val index: Int,
    private val expectedTypeClassName: String,
) : AppleMusicHookContract {
    override fun validate(context: HookContractContext): ContractResult {
        val method = context.method
            ?: return ContractResult.Rejected("Method unavailable for parameter check")
        val paramTypes = method.parameterTypes
        if (index !in paramTypes.indices) {
            return ContractResult.Rejected(
                "Method ${method.name} parameter index $index out of bounds (${paramTypes.size})"
            )
        }
        val actual = paramTypes[index]
        val expected = runCatching { context.classLookup?.invoke(expectedTypeClassName) }.getOrNull()
            ?: runCatching { context.classLoader?.loadClass(expectedTypeClassName) }.getOrNull()
        val matches = actual.name == expectedTypeClassName ||
            (expected != null && expected.isAssignableFrom(actual))
        return if (matches) {
            ContractResult.Passed
        } else {
            ContractResult.Rejected(
                "Method ${method.name} param[$index] is ${actual.name}, expected $expectedTypeClassName"
            )
        }
    }
}

/**
 * 契约：方法返回类型校验。
 */
internal class RequireReturnType(
    private val expectedTypeClassName: String,
) : AppleMusicHookContract {
    override fun validate(context: HookContractContext): ContractResult {
        val method = context.method
            ?: return ContractResult.Rejected("Method unavailable for return type check")
        val returnType = method.returnType
        val expected = runCatching { context.classLookup?.invoke(expectedTypeClassName) }.getOrNull()
            ?: runCatching { context.classLoader?.loadClass(expectedTypeClassName) }.getOrNull()
        val matches = returnType.name == expectedTypeClassName ||
            (expected != null && expected.isAssignableFrom(returnType))
        return if (matches) {
            ContractResult.Passed
        } else {
            ContractResult.Rejected(
                "Method ${method.name} return type is ${returnType.name}, expected $expectedTypeClassName"
            )
        }
    }
}

internal enum class AppleComposeTextLayoutRole {
    ANY,
    PRIMARY,
    INTRINSICS,
}

/**
 * Semantic class contract for Compose's Android text-layout bridge.
 *
 * The obfuscated class name is deliberately ignored. The target must expose a constructor whose
 * text and paint inputs can be rewritten before Android creates StaticLayout/BoringLayout.
 */
internal class RequireComposeTextLayoutClass(
    private val role: AppleComposeTextLayoutRole = AppleComposeTextLayoutRole.ANY,
) : AppleMusicHookContract {
    override fun validate(context: HookContractContext): ContractResult {
        val clazz = context.clazz ?: context.method?.declaringClass
            ?: return ContractResult.Rejected("Class unavailable for Compose text layout check")
        val matchingConstructors = clazz.declaredConstructors.filter { constructor ->
            val parameters = constructor.parameterTypes
            val hasCommonInputs = parameters.firstOrNull() == CharSequence::class.java &&
                parameters.any(TextPaint::class.java::isAssignableFrom)
            if (!hasCommonInputs) return@filter false
            when (role) {
                AppleComposeTextLayoutRole.ANY -> true
                AppleComposeTextLayoutRole.PRIMARY -> parameters.size >= 8
                AppleComposeTextLayoutRole.INTRINSICS ->
                    parameters.size in 2..4 &&
                        parameters.getOrNull(1)?.let(
                            TextPaint::class.java::isAssignableFrom
                        ) == true
            }
        }
        return if (matchingConstructors.isNotEmpty()) {
            ContractResult.Passed
        } else {
            ContractResult.Rejected(
                "Class ${clazz.name} lacks ${role.name.lowercase()} " +
                    "CharSequence/TextPaint constructor"
            )
        }
    }
}

/** Semantic class contract for Apple's Compose typeface factory helper. */
internal class RequireAppleTextStyleUtilsClass : AppleMusicHookContract {
    override fun validate(context: HookContractContext): ContractResult {
        val clazz = context.clazz ?: context.method?.declaringClass
            ?: return ContractResult.Rejected("Class unavailable for Apple text style check")
        val factory = clazz.declaredMethods.singleOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.returnType == Typeface::class.java &&
                method.parameterCount == 4 &&
                method.parameterTypes.getOrNull(0) == Context::class.java &&
                method.parameterTypes.getOrNull(1) == AttributeSet::class.java
        }
        return if (factory != null) {
            ContractResult.Passed
        } else {
            ContractResult.Rejected(
                "Class ${clazz.name} lacks unique static Context/AttributeSet Typeface factory"
            )
        }
    }
}

/**
 * Accepts LiveData by behavior instead of one concrete obfuscated class name.
 *
 * AndroidX public lifecycle methods remain stable even when the bundled class changes from
 * LiveData to a minified name such as G, so future pure renames can be repaired automatically.
 */
internal class RequireLifecycleObservableParameter(
    private val index: Int,
) : AppleMusicHookContract {
    override fun validate(context: HookContractContext): ContractResult {
        val method = context.method
            ?: return ContractResult.Rejected("Method unavailable for lifecycle observable check")
        val actual = method.parameterTypes.getOrNull(index)
            ?: return ContractResult.Rejected(
                "Method ${method.name} parameter index $index out of bounds"
            )
        val methods = generateSequence(actual) { current -> current.superclass }
            .flatMap { current -> current.declaredMethods.asSequence() }
            .toList()
        val hasGetValue = methods.any { candidate ->
            candidate.name == "getValue" && candidate.parameterCount == 0
        }
        val hasObserve = methods.any { candidate ->
            (candidate.name == "observe" && candidate.parameterCount == 2) ||
                (candidate.name == "observeForever" && candidate.parameterCount == 1)
        }
        val hasRemoveObserver = methods.any { candidate ->
            candidate.name == "removeObserver" && candidate.parameterCount == 1
        }
        return if (hasGetValue && hasObserve && hasRemoveObserver) {
            ContractResult.Passed
        } else {
            ContractResult.Rejected(
                "Method ${method.name} param[$index] ${actual.name} lacks LiveData behavior"
            )
        }
    }
}

/**
 * Apple Music 各 Hook 点预置语义契约注册表。
 */
internal object AppleMusicHookContracts {
    private val DEFAULT_CONTRACTS: Map<AppleMusicHookPoint, AppleMusicHookContract> = mapOf(
        AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER to AllOfContract(
            RequireNonBridgeMethod(),
            RequireFieldOfType(
                fieldTypeClassName =
                    "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            ),
        ),
        AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING to AllOfContract(
            RequireNonBridgeMethod(),
            RequireFieldOfType(
                fieldTypeClassName = "com.apple.android.music.model.CollectionItemView",
            ),
        ),
        AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to RequireStaticSelfTypedSingleton(),
        AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE to AllOfContract(
            RequireNonBridgeMethod(),
            RequireLifecycleObservableParameter(0),
        ),
        AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT to RequireComposeTextLayoutClass(),
        AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS to RequireAppleTextStyleUtilsClass(),
        AppleMusicHookPoint.LIBRARY_EPOXY_BUILD to AllOfContract(
            RequireNonBridgeMethod(),
            AppleMusicHookContract { context ->
                val method = context.method
                    ?: return@AppleMusicHookContract ContractResult.Rejected("Method unavailable")
                if (method.name != "buildModels") {
                    return@AppleMusicHookContract ContractResult.Rejected(
                        "Expected methodName buildModels, got ${method.name}"
                    )
                }
                if (method.parameterCount != 5) {
                    return@AppleMusicHookContract ContractResult.Rejected(
                        "Expected parameterCount 5, got ${method.parameterCount}"
                    )
                }
                // 排除 Typed5EpoxyController 的 Object[] 抽象桥接重载
                val firstParam = method.parameterTypes[0]
                if (firstParam == Any::class.java || firstParam.isArray) {
                    return@AppleMusicHookContract ContractResult.Rejected(
                        "First parameter ${firstParam.name} is generic/array bridge"
                    )
                }
                ContractResult.Passed
            },
        ),
        AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER to AllOfContract(
            RequireNonBridgeMethod(),
            AppleMusicHookContract { context ->
                val method = context.method
                    ?: return@AppleMusicHookContract ContractResult.Rejected("Method unavailable")
                if (!Modifier.isStatic(method.modifiers)) {
                    return@AppleMusicHookContract ContractResult.Rejected("Model builder must be static")
                }
                if (method.parameterCount < 4) {
                    return@AppleMusicHookContract ContractResult.Rejected(
                        "Model builder parameter count ${method.parameterCount} < 4"
                    )
                }
                ContractResult.Passed
            },
        ),
        AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER to AllOfContract(
            RequireNonBridgeMethod(),
            RequireParameterType(0, "com.apple.android.music.model.CollectionItemView"),
            RequireReturnType("void"),
        ),
        AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER to AllOfContract(
            RequireNonBridgeMethod(),
            AppleMusicHookContract { context ->
                val method = context.method
                    ?: return@AppleMusicHookContract ContractResult.Rejected("Method unavailable")
                if (method.name != "onMediaMetadataChanged") {
                    return@AppleMusicHookContract ContractResult.Rejected(
                        "Expected onMediaMetadataChanged, got ${method.name}"
                    )
                }
                if (method.parameterCount != 1) {
                    return@AppleMusicHookContract ContractResult.Rejected(
                        "Expected 1 parameter, got ${method.parameterCount}"
                    )
                }
                ContractResult.Passed
            },
        ),
    )

    fun forHookPoint(hookPoint: AppleMusicHookPoint): AppleMusicHookContract? =
        DEFAULT_CONTRACTS[hookPoint]

    fun canRepairClassWithoutBaseline(hookPoint: AppleMusicHookPoint): Boolean =
        hookPoint == AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT ||
            hookPoint == AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS

    fun validate(context: HookContractContext): ContractResult {
        val targetContract = context.target.contract
        if (targetContract != null) {
            val result = targetContract.validate(context)
            if (result is ContractResult.Rejected) return result
        }
        val defaultContract = forHookPoint(context.hookPoint)
        if (defaultContract != null) {
            return defaultContract.validate(context)
        }
        return ContractResult.Passed
    }
}
