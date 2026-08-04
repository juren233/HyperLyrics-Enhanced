/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Apple Music 安装包版本，用于选择对应的混淆 Hook 档案。 */
internal data class AppleMusicVersion(
    val versionName: String?,
    val versionCode: Long?,
) {
    val displayName: String
        get() = "${versionName ?: "unknown"} (${versionCode ?: "unknown"})"
}

/**
 * 所有已经确认会随 Apple Music 混淆版本变化的 Hook 入口。
 *
 * 新版 Apple Music 适配应优先只修改本文件中的版本档案；业务 Hook 不应再直接写死这些类名。
 */
internal enum class AppleMusicHookPoint {
    MEDIA_API_LOCALIZATION,
    EPOXY_FINAL_BIND,
    LYRICS_SOURCE_MENU_CLICK_LISTENER,
    LYRICS_WORD_RENDER_ADAPTER,
    LYRICS_RECYCLER_ADAPTER,
    COMPOSE_TEXT_LAYOUT,
    APPLE_TEXT_STYLE_UTILS,
    IN_APP_ACTION_SHEET_BINDING,
    COMPOSE_NEVER_EQUAL_POLICY,
    LIBRARY_COMPOSE_VIEW_MODEL_GETTER,
    LISTEN_NOW_MODEL_BUILDER,
    LISTEN_NOW_BOUND_LISTENER,
    LISTEN_NOW_MODEL,
    LISTEN_NOW_ARTWORK_RESOLVER,
    LISTEN_NOW_DELEGATING_ITEM,
    LISTEN_NOW_CUSTOM_IMAGE_VIEW,
    LISTEN_NOW_MEDIA_ENTITY,
    LISTEN_NOW_COLLECTION_ITEM_VIEW,
}

internal data class AppleMusicHookTarget(
    val className: String,
    val methodName: String? = null,
    val parameterCount: Int? = null,
    val parameterTypeNames: List<String?>? = null,
    val returnTypeName: String? = null,
    val isStatic: Boolean? = null,
    val includeSynthetic: Boolean = false,
) {
    init {
        require(
            parameterTypeNames == null ||
                parameterCount == null ||
                parameterTypeNames.size == parameterCount
        ) {
            "parameterTypeNames must match parameterCount"
        }
    }
}

internal data class AppleMusicHookProfile(
    val id: String,
    val versionName: String,
    val versionCodes: Set<Long>,
    private val hookTargets: Map<AppleMusicHookPoint, List<AppleMusicHookTarget>>,
) {
    fun targets(hookPoint: AppleMusicHookPoint): List<AppleMusicHookTarget> =
        hookTargets[hookPoint].orEmpty()

    fun matches(version: AppleMusicVersion): Boolean =
        version.versionCode?.let(versionCodes::contains) == true ||
            version.versionName == versionName
}

/**
 * Apple Music 混淆版本档案的唯一维护入口。
 *
 * 后续版本更新流程：反编译新版 APK，确认每个 [AppleMusicHookPoint] 的目标类和方法，
 * 然后在 [KNOWN_PROFILES] 前部新增一份档案。未知版本会按“较新档案优先”的顺序尝试
 * 已知候选，但只有通过对应方法签名校验的目标才会被采用。
 */
internal object AppleMusicHookProfiles {
    private val APPLE_MUSIC_6_5_0 = AppleMusicHookProfile(
        id = "am-6.5.0-1580",
        versionName = "6.5.0",
        versionCodes = setOf(1580L),
        hookTargets = mapOf(
            AppleMusicHookPoint.MEDIA_API_LOCALIZATION to listOf(
                AppleMusicHookTarget("s8.E", "c0", 1),
            ),
            AppleMusicHookPoint.EPOXY_FINAL_BIND to listOf(
                AppleMusicHookTarget("com.airbnb.epoxy.K", "t", 4),
            ),
            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.fragment.e0",
                    "onClick",
                    1,
                ),
            ),
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER to listOf(
                AppleMusicHookTarget("com.apple.android.music.player.z"),
            ),
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER to listOf(
                AppleMusicHookTarget("com.apple.android.music.player.R0"),
                AppleMusicHookTarget("com.apple.android.music.player.z"),
            ),
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT to listOf(
                AppleMusicHookTarget("z1.l"),
                AppleMusicHookTarget("z1.t"),
            ),
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS to listOf(
                AppleMusicHookTarget("com.apple.android.music.utils.l1\$a"),
            ),
            AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING to listOf(
                AppleMusicHookTarget("l7.e8", "l", 0),
            ),
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to listOf(
                AppleMusicHookTarget("z0.v0"),
            ),
            AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.library3.LibraryComposeContentFragment",
                    "B0",
                    0,
                ),
            ),
        ),
    )

    private val APPLE_MUSIC_6_5_1 = AppleMusicHookProfile(
        id = "am-6.5.1-1583",
        versionName = "6.5.1",
        versionCodes = setOf(1583L),
        hookTargets = mapOf(
            AppleMusicHookPoint.MEDIA_API_LOCALIZATION to listOf(
                AppleMusicHookTarget("s8.F", "c0", 1),
            ),
            AppleMusicHookPoint.EPOXY_FINAL_BIND to listOf(
                AppleMusicHookTarget("com.airbnb.epoxy.J", "t", 4),
            ),
            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.player.fragment.a0",
                    "onClick",
                    1,
                ),
            ),
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER to listOf(
                AppleMusicHookTarget("com.apple.android.music.player.A"),
            ),
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER to listOf(
                AppleMusicHookTarget("com.apple.android.music.player.A"),
                AppleMusicHookTarget("com.apple.android.music.player.U0"),
            ),
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT to listOf(
                AppleMusicHookTarget("z1.k"),
                AppleMusicHookTarget("z1.s"),
            ),
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS to listOf(
                AppleMusicHookTarget("com.apple.android.music.utils.i1\$a"),
            ),
            AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING to listOf(
                AppleMusicHookTarget("l7.f8", "l", 0),
            ),
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to listOf(
                AppleMusicHookTarget("z0.t0"),
            ),
            AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.library3.LibraryComposeContentFragment",
                    "A0",
                    0,
                ),
            ),
            // Verified from Apple Music 6.5.1 (1583) classes*.dex descriptors.
            AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER to listOf(
                AppleMusicHookTarget(
                    className =
                        "com.apple.android.music.listennow.ListenNowEpoxyController",
                    methodName = "buildStandardSwoosh\$lambda\$35",
                    parameterCount = 5,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.listennow.ListenNowEpoxyController",
                        "com.apple.android.music.mediaapi.models.Recommendation",
                        "com.apple.android.music.common.D0",
                        "com.apple.android.music.mediaapi.models.MediaEntity",
                        "java.util.List",
                    ),
                    returnTypeName = "com.airbnb.epoxy.l",
                    isStatic = true,
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER to listOf(
                AppleMusicHookTarget(
                    className =
                        "com.apple.android.music.listennow.ListenNowEpoxyController\$Q",
                    methodName = "onModelBound",
                    parameterCount = 3,
                    returnTypeName = "void",
                    includeSynthetic = true,
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_MODEL to listOf(
                AppleMusicHookTarget("com.apple.android.music.l1"),
            ),
            AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER to listOf(
                AppleMusicHookTarget(
                    className = "com.apple.android.music.common.J",
                    methodName = "t",
                    parameterCount = 1,
                    parameterTypeNames = listOf(
                        "com.apple.android.music.model.CollectionItemView"
                    ),
                    returnTypeName = "void",
                    includeSynthetic = true,
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.model.extensions." +
                        "DelegatingCollectionItemView"
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW to listOf(
                AppleMusicHookTarget("com.apple.android.music.common.CustomImageView"),
            ),
            AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY to listOf(
                AppleMusicHookTarget(
                    "com.apple.android.music.mediaapi.models.MediaEntity"
                ),
            ),
            AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW to listOf(
                AppleMusicHookTarget("com.apple.android.music.model.CollectionItemView"),
            ),
        ),
    )

    /** 新版本档案必须放在前面，未知版本回退时优先尝试较新的目标。 */
    private val KNOWN_PROFILES = listOf(
        APPLE_MUSIC_6_5_1,
        APPLE_MUSIC_6_5_0,
    )

    fun profileFor(version: AppleMusicVersion): AppleMusicHookProfile? =
        KNOWN_PROFILES.firstOrNull { profile -> profile.matches(version) }

    fun exactTargets(
        version: AppleMusicVersion,
        hookPoint: AppleMusicHookPoint,
    ): List<AppleMusicHookTarget> = profileFor(version)?.targets(hookPoint).orEmpty()

    fun candidates(
        version: AppleMusicVersion,
        hookPoint: AppleMusicHookPoint,
    ): List<AppleMusicHookTarget> {
        val exact = exactTargets(version, hookPoint)
        return (exact + KNOWN_PROFILES.flatMap { profile -> profile.targets(hookPoint) })
            .distinct()
    }
}

internal data class ResolvedAppleMusicHookClass(
    val target: AppleMusicHookTarget,
    val clazz: Class<*>,
    val compatibilityFallback: Boolean,
)

internal data class ResolvedAppleMusicHookMethod(
    val target: AppleMusicHookTarget,
    val method: Method,
    val compatibilityFallback: Boolean,
)

/** 统一负责按 Apple Music 版本加载并校验混淆 Hook 目标。 */
internal class AppleMusicHookResolver(
    val version: AppleMusicVersion,
    private val classLookup: (String) -> Class<*>,
) {
    constructor(version: AppleMusicVersion, classLoader: ClassLoader) : this(
        version = version,
        classLookup = classLoader::loadClass,
    )

    val profile: AppleMusicHookProfile? = AppleMusicHookProfiles.profileFor(version)

    fun configuredClassNames(hookPoint: AppleMusicHookPoint): Set<String> {
        val exact = AppleMusicHookProfiles.exactTargets(version, hookPoint)
        val targets = if (exact.isNotEmpty()) {
            exact
        } else {
            AppleMusicHookProfiles.candidates(version, hookPoint)
        }
        return targets.mapTo(LinkedHashSet(), AppleMusicHookTarget::className)
    }

    /**
     * 加载一个 Hook 点在当前精确档案里的全部类。精确目标全部缺失时才进入兼容回退，
     * 避免在已知版本里同时 Hook 旧版本碰巧仍存在、但语义已经变化的类。
     */
    fun resolveClasses(hookPoint: AppleMusicHookPoint): List<ResolvedAppleMusicHookClass> {
        val exactTargets = AppleMusicHookProfiles.exactTargets(version, hookPoint)
        val exactClasses = exactTargets.mapNotNull { target ->
            loadClass(target, compatibilityFallback = false)
        }
        if (exactClasses.isNotEmpty()) return exactClasses

        return AppleMusicHookProfiles.candidates(version, hookPoint)
            .filterNot(exactTargets::contains)
            .mapNotNull { target -> loadClass(target, compatibilityFallback = true) }
    }

    /** 解析单个类；精确档案缺失时才尝试已知版本候选。 */
    fun resolveClass(hookPoint: AppleMusicHookPoint): ResolvedAppleMusicHookClass {
        val exactTargets = AppleMusicHookProfiles.exactTargets(version, hookPoint).toSet()
        val failures = mutableListOf<String>()
        AppleMusicHookProfiles.candidates(version, hookPoint).forEach { target ->
            val clazz = runCatching { classLookup(target.className) }
                .getOrElse { throwable ->
                    failures += "${target.className}:${throwable.javaClass.simpleName}"
                    return@forEach
                }
            return ResolvedAppleMusicHookClass(
                target = target,
                clazz = clazz,
                compatibilityFallback = target !in exactTargets,
            )
        }
        throw ClassNotFoundException(
            "Apple Music ${version.displayName} $hookPoint unresolved: " +
                failures.joinToString(),
        )
    }

    /** 解析单个方法；候选类存在但方法签名不符时继续尝试下一版本候选。 */
    fun resolveMethod(hookPoint: AppleMusicHookPoint): ResolvedAppleMusicHookMethod {
        val exactTargets = AppleMusicHookProfiles.exactTargets(version, hookPoint).toSet()
        val failures = mutableListOf<String>()
        AppleMusicHookProfiles.candidates(version, hookPoint).forEach { target ->
            val clazz = runCatching { classLookup(target.className) }
                .getOrElse { throwable ->
                    failures += "${target.className}:class:${throwable.javaClass.simpleName}"
                    return@forEach
                }
            val matchingMethods = allDeclaredMethods(
                clazz = clazz,
                includeSynthetic = target.includeSynthetic,
            )
                .filter { method -> methodMatches(hookPoint, target, method) }
                .toList()
            if (matchingMethods.size == 1) {
                val method = matchingMethods.single().apply { isAccessible = true }
                return ResolvedAppleMusicHookMethod(
                    target = target,
                    method = method,
                    compatibilityFallback = target !in exactTargets,
                )
            }
            failures += if (matchingMethods.isEmpty()) {
                "${target.className}#${target.methodName}:signature"
            } else {
                "${target.className}#${target.methodName}:ambiguous(${matchingMethods.size})"
            }
        }
        throw NoSuchMethodException(
            "Apple Music ${version.displayName} $hookPoint unresolved: " +
                failures.joinToString(),
        )
    }

    private fun loadClass(
        target: AppleMusicHookTarget,
        compatibilityFallback: Boolean,
    ): ResolvedAppleMusicHookClass? = runCatching {
        ResolvedAppleMusicHookClass(
            target = target,
            clazz = classLookup(target.className),
            compatibilityFallback = compatibilityFallback,
        )
    }.getOrNull()

    private fun methodMatches(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        method: Method,
    ): Boolean {
        if (target.methodName != null && method.name != target.methodName) return false
        if (target.parameterCount != null && method.parameterCount != target.parameterCount) {
            return false
        }
        target.parameterTypeNames?.forEachIndexed { index, expectedName ->
            if (expectedName != null && method.parameterTypes[index].name != expectedName) {
                return false
            }
        }
        if (target.returnTypeName != null && method.returnType.name != target.returnTypeName) {
            return false
        }
        if (target.isStatic != null && Modifier.isStatic(method.modifiers) != target.isStatic) {
            return false
        }
        return when (hookPoint) {
            AppleMusicHookPoint.MEDIA_API_LOCALIZATION ->
                Map::class.java.isAssignableFrom(method.returnType)

            AppleMusicHookPoint.EPOXY_FINAL_BIND -> {
                val parameters = method.parameterTypes
                method.returnType == Void.TYPE &&
                    parameters.size == 4 &&
                    parameters[0] == parameters[1] &&
                    List::class.java.isAssignableFrom(parameters[2]) &&
                    parameters[3] == Int::class.javaPrimitiveType
            }

            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER -> {
                val parameters = method.parameterTypes
                method.returnType == Void.TYPE &&
                    parameters.size == 1 &&
                    parameters[0].name == "android.view.View"
            }

            AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING ->
                method.returnType == Void.TYPE && method.parameterCount == 0

            AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER ->
                method.parameterCount == 0 &&
                    method.returnType.name ==
                    "com.apple.android.music.library2.LibraryViewModel"

            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER,
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER,
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS,
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY,
            AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER,
            AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER,
            AppleMusicHookPoint.LISTEN_NOW_MODEL,
            AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER,
            AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM,
            AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW,
            AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY,
            AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW -> true
        }
    }

    private fun allDeclaredMethods(
        clazz: Class<*>,
        includeSynthetic: Boolean,
    ): Sequence<Method> =
        generateSequence(clazz) { current -> current.superclass }
            .flatMap { current -> current.declaredMethods.asSequence() }
            .filter { method ->
                includeSynthetic || (!method.isBridge && !method.isSynthetic)
            }
            .distinctBy { method ->
                method.name to method.parameterTypes.joinToString(separator = ",") { it.name }
            }
}
