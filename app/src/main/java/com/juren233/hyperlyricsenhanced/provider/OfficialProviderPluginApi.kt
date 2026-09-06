/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Stable boundary implemented by code loaded from an official Provider Pack.
 *
 * This API deliberately contains no libxposed types. The static host owns every
 * Xposed API call, which keeps Pack code compatible with runtime API protection.
 */
interface OfficialProviderPlugin {
    fun install(host: OfficialProviderHost)
}

interface OfficialProviderHost {
    val packageName: String
    val processName: String

    fun hookApplication(callback: OfficialProviderApplicationCallback)

    fun hookMediaSession(
        playbackStateCallback: OfficialProviderPlaybackStateCallback,
        metadataCallback: OfficialProviderMetadataCallback,
    )

    /**
     * Installs an after-call Hook for an exact method descriptor in the target app.
     *
     * Provider Packs must source every identifier from the original DEX rather than
     * a decompiler display alias. Keeping libxposed objects inside the host preserves
     * the stable Pack boundary while still allowing app-specific lyric entry points.
     */
    fun hookAfterMethod(
        target: OfficialProviderMethodTarget,
        callback: OfficialProviderMethodCallback,
    )

    /**
     * 在精确方法返回后读取并转换返回值。
     *
     * 回调失败时 Host 保留原始返回值，避免 Provider 的观察逻辑破坏目标 App 调用链。
     */
    fun hookMethodResult(
        target: OfficialProviderMethodTarget,
        callback: OfficialProviderMethodResultCallback,
    )

    /**
     * 为精确的构造函数描述符安装执行后 Hook。
     *
     * 构造函数使用独立目标类型，避免 Provider Pack 把反编译器显示的 `<init>`
     * 当作普通反射方法名进行查找。
     */
    fun hookAfterConstructor(
        target: OfficialProviderConstructorTarget,
        callback: OfficialProviderConstructorCallback,
    )

    /**
     * Resolves an obfuscation-sensitive method from the original target DEX.
     *
     * The host first tries a previously verified exact descriptor. DexKit is
     * opened only when that cached descriptor cannot be hooked. A fresh result
     * is persisted only after the exact method Hook has been installed.
     */
    fun hookAfterDexMethod(
        application: Application,
        query: OfficialProviderDexMethodQuery,
        callback: OfficialProviderMethodCallback,
    )

    fun resolveDexMethods(
        application: Application,
        queries: List<OfficialProviderDexMethodQuery>,
        callback: OfficialProviderDexMethodsCallback,
    )

    /**
     * Reports whether a DexKit-resolved target produced valid business data.
     *
     * [cacheKey] is the stable [OfficialProviderDexMethodQuery.cacheKey], not the
     * versioned host cache key. Providers should report every real parse attempt;
     * the first invalid result invalidates the registered query or batch and starts
     * at most one cache-bypassing repair pass. Repaired single-method Hooks deactivate
     * the previous callback generation. Debug builds additionally record bounded
     * first-hit diagnostics.
     *
     * The default implementation keeps Provider Packs that do not need validation
     * source-compatible. Packs that call this method must raise minCoreVersionCode.
     */
    fun reportDexMethodValidation(
        cacheKey: String,
        valid: Boolean,
        detail: String? = null,
    ) = Unit

    /** Emits bounded debug-only diagnostics through the host's exported log channel. */
    /** Optional capability: false unless the core explicitly enables debug diagnostics.
     * New packs must fail closed when an older core has no such method.
     */
    fun isDiagnosticEnabled(): Boolean = false

    fun reportDiagnostic(
        tag: String,
        message: String,
    ) = Unit

    /**
     * 读取主模块远端 Hook 配置中的布尔键，Provider Pack 用它决定本地歌词策略。
     *
     * 例如椒盐音乐在“优先使用在线源”开启时跳过内置歌词与同目录歌词读取。
     * 默认实现返回 [default]，保持旧 Provider Pack 的二进制兼容；调用此方法的
     * Pack 应相应提高 manifest 的 minCoreVersionCode，避免在旧主模块上静默降级。
     */
    fun getBooleanPreference(key: String, default: Boolean): Boolean = default
}

/**
 * Optional Provider Pack entry point for packs that run in SystemUI and observe
 * the target player's public MediaSession instead of entering the player process.
 */
interface OfficialProviderSystemMediaPlugin {
    fun installSystemMedia(host: OfficialProviderSystemMediaHost)

    fun releaseSystemMedia()
}

interface OfficialProviderSystemMediaHost {
    val application: Application
    val playerPackageName: String

    fun subscribe(callback: OfficialProviderSystemMediaCallback): OfficialProviderSystemMediaSubscription
}

fun interface OfficialProviderSystemMediaCallback {
    fun onMediaChanged(metadata: MediaMetadata?, playbackState: PlaybackState?)
}

fun interface OfficialProviderSystemMediaSubscription {
    fun release()
}

fun interface OfficialProviderApplicationCallback {
    fun onApplicationCreated(application: Application)
}

fun interface OfficialProviderPlaybackStateCallback {
    fun onPlaybackStateChanged(state: PlaybackState?)
}

fun interface OfficialProviderMetadataCallback {
    fun onMetadataChanged(metadata: MediaMetadata?)
}

data class OfficialProviderMethodTarget(
    val className: String,
    val methodName: String,
    val parameterTypeNames: List<String> = emptyList(),
    val returnTypeName: String,
    val isStatic: Boolean,
)

data class OfficialProviderConstructorTarget(
    val className: String,
    val parameterTypeNames: List<String> = emptyList(),
)

enum class OfficialProviderDexTypeSource {
    DECLARING_CLASS,
    RETURN_TYPE,
    PARAMETER_TYPE,
}

/**
 * Refers to a type produced by an earlier query in the same ordered batch.
 *
 * This keeps downstream queries attached to the resolved call graph instead of
 * copying an obfuscated class name into every node of the Provider Pack.
 */
data class OfficialProviderDexTypeReference(
    val queryCacheKey: String,
    val source: OfficialProviderDexTypeSource,
    val parameterIndex: Int = -1,
)

/**
 * Stable, DexKit-independent method query passed across the Provider Pack ABI.
 *
 * Null constraints are intentionally left unconstrained. The host requires a
 * unique result after applying every declared constraint.
 */
data class OfficialProviderDexMethodQuery(
    val cacheKey: String,
    val preferredTarget: OfficialProviderMethodTarget? = null,
    val declaringClassName: String? = null,
    val declaringClassNamePrefix: String? = null,
    val declaringClassReference: OfficialProviderDexTypeReference? = null,
    val requiredStrings: List<String> = emptyList(),
    val requiredInvokedMethodDescriptors: List<String> = emptyList(),
    val requiredInvokedMethodNames: List<String> = emptyList(),
    val parameterTypeNames: List<String>? = null,
    val parameterTypeReferences: Map<Int, OfficialProviderDexTypeReference> = emptyMap(),
    val returnTypeName: String? = null,
    val returnTypeNamePrefix: String? = null,
    val returnTypeReference: OfficialProviderDexTypeReference? = null,
    val returnTypeMatchesDeclaringClass: Boolean = false,
    val isStatic: Boolean? = null,
    val requiredCallerMethodNames: List<String> = emptyList(),
    val forbiddenInvokedMethodDescriptors: List<String> = emptyList(),
) {
    /**
     * Binary-compatible constructor for Provider Packs built against the caller-constraint API.
     *
     * Forbidden invoke constraints were added as a trailing field so existing Packs keep the
     * exact JVM constructor they were compiled against.
     */
    @Suppress("unused")
    @Deprecated("Binary compatibility for Provider Packs", level = DeprecationLevel.HIDDEN)
    constructor(
        cacheKey: String,
        preferredTarget: OfficialProviderMethodTarget? = null,
        declaringClassName: String? = null,
        declaringClassNamePrefix: String? = null,
        declaringClassReference: OfficialProviderDexTypeReference? = null,
        requiredStrings: List<String> = emptyList(),
        requiredInvokedMethodDescriptors: List<String> = emptyList(),
        requiredInvokedMethodNames: List<String> = emptyList(),
        parameterTypeNames: List<String>? = null,
        parameterTypeReferences: Map<Int, OfficialProviderDexTypeReference> = emptyMap(),
        returnTypeName: String? = null,
        returnTypeNamePrefix: String? = null,
        returnTypeReference: OfficialProviderDexTypeReference? = null,
        returnTypeMatchesDeclaringClass: Boolean = false,
        isStatic: Boolean? = null,
        requiredCallerMethodNames: List<String> = emptyList(),
    ) : this(
        cacheKey = cacheKey,
        preferredTarget = preferredTarget,
        declaringClassName = declaringClassName,
        declaringClassNamePrefix = declaringClassNamePrefix,
        declaringClassReference = declaringClassReference,
        requiredStrings = requiredStrings,
        requiredInvokedMethodDescriptors = requiredInvokedMethodDescriptors,
        requiredInvokedMethodNames = requiredInvokedMethodNames,
        parameterTypeNames = parameterTypeNames,
        parameterTypeReferences = parameterTypeReferences,
        returnTypeName = returnTypeName,
        returnTypeNamePrefix = returnTypeNamePrefix,
        returnTypeReference = returnTypeReference,
        returnTypeMatchesDeclaringClass = returnTypeMatchesDeclaringClass,
        isStatic = isStatic,
        requiredCallerMethodNames = requiredCallerMethodNames,
        forbiddenInvokedMethodDescriptors = emptyList(),
    )

    /**
     * Binary-compatible constructor for Provider Packs built against plugin API v3.
     *
     * Caller constraints were added without changing the plugin API version because the
     * extension is additive. New Packs that use it must still raise minCoreVersionCode.
     */
    @Suppress("unused")
    @Deprecated("Binary compatibility for Provider Packs", level = DeprecationLevel.HIDDEN)
    constructor(
        cacheKey: String,
        preferredTarget: OfficialProviderMethodTarget? = null,
        declaringClassName: String? = null,
        declaringClassNamePrefix: String? = null,
        declaringClassReference: OfficialProviderDexTypeReference? = null,
        requiredStrings: List<String> = emptyList(),
        requiredInvokedMethodDescriptors: List<String> = emptyList(),
        requiredInvokedMethodNames: List<String> = emptyList(),
        parameterTypeNames: List<String>? = null,
        parameterTypeReferences: Map<Int, OfficialProviderDexTypeReference> = emptyMap(),
        returnTypeName: String? = null,
        returnTypeNamePrefix: String? = null,
        returnTypeReference: OfficialProviderDexTypeReference? = null,
        returnTypeMatchesDeclaringClass: Boolean = false,
        isStatic: Boolean? = null,
    ) : this(
        cacheKey = cacheKey,
        preferredTarget = preferredTarget,
        declaringClassName = declaringClassName,
        declaringClassNamePrefix = declaringClassNamePrefix,
        declaringClassReference = declaringClassReference,
        requiredStrings = requiredStrings,
        requiredInvokedMethodDescriptors = requiredInvokedMethodDescriptors,
        requiredInvokedMethodNames = requiredInvokedMethodNames,
        parameterTypeNames = parameterTypeNames,
        parameterTypeReferences = parameterTypeReferences,
        returnTypeName = returnTypeName,
        returnTypeNamePrefix = returnTypeNamePrefix,
        returnTypeReference = returnTypeReference,
        returnTypeMatchesDeclaringClass = returnTypeMatchesDeclaringClass,
        isStatic = isStatic,
        requiredCallerMethodNames = emptyList(),
        forbiddenInvokedMethodDescriptors = emptyList(),
    )

    /**
     * Binary-compatible constructor used by Provider Packs built before ordered query references
     * were added. InMemoryDexClassLoader delegates this API package to the core class loader, so
     * removing the old JVM constructor would break already installed Packs with NoSuchMethodError.
    */
    @Suppress("unused")
    @Deprecated("Binary compatibility for Provider Packs", level = DeprecationLevel.HIDDEN)
    constructor(
        cacheKey: String,
        preferredTarget: OfficialProviderMethodTarget? = null,
        declaringClassName: String? = null,
        declaringClassNamePrefix: String? = null,
        requiredStrings: List<String> = emptyList(),
        requiredInvokedMethodDescriptors: List<String> = emptyList(),
        parameterTypeNames: List<String>? = null,
        returnTypeName: String? = null,
        returnTypeMatchesDeclaringClass: Boolean = false,
        isStatic: Boolean? = null,
    ) : this(
        cacheKey = cacheKey,
        preferredTarget = preferredTarget,
        declaringClassName = declaringClassName,
        declaringClassNamePrefix = declaringClassNamePrefix,
        declaringClassReference = null,
        requiredStrings = requiredStrings,
        requiredInvokedMethodDescriptors = requiredInvokedMethodDescriptors,
        requiredInvokedMethodNames = emptyList(),
        parameterTypeNames = parameterTypeNames,
        parameterTypeReferences = emptyMap(),
        returnTypeName = returnTypeName,
        returnTypeNamePrefix = null,
        returnTypeReference = null,
        returnTypeMatchesDeclaringClass = returnTypeMatchesDeclaringClass,
        isStatic = isStatic,
        requiredCallerMethodNames = emptyList(),
        forbiddenInvokedMethodDescriptors = emptyList(),
    )
}

internal object OfficialProviderDexMethodQueryValidator {
    fun validate(query: OfficialProviderDexMethodQuery) {
        require(query.cacheKey.isNotBlank()) { "Provider DexKit cacheKey 不能为空" }
        require(query.declaringClassName == null || query.declaringClassName.isNotBlank()) {
            "Provider DexKit declaringClassName 不能为空"
        }
        require(query.declaringClassNamePrefix == null || query.declaringClassNamePrefix.isNotBlank()) {
            "Provider DexKit declaringClassNamePrefix 不能为空"
        }
        require(query.declaringClassName == null || query.declaringClassReference == null) {
            "Provider DexKit declaringClassName 与引用不能同时设置"
        }
        require(query.requiredStrings.all(String::isNotBlank)) {
            "Provider DexKit 特征字符串不能包含空值"
        }
        require(query.requiredInvokedMethodDescriptors.all(String::isNotBlank)) {
            "Provider DexKit 调用方法描述符不能包含空值"
        }
        require(query.requiredInvokedMethodNames.all(String::isNotBlank)) {
            "Provider DexKit 调用方法名不能包含空值"
        }
        require(query.requiredCallerMethodNames.all(String::isNotBlank)) {
            "Provider DexKit 调用方方法名不能包含空值"
        }
        require(query.forbiddenInvokedMethodDescriptors.all(String::isNotBlank)) {
            "Provider DexKit 禁止调用方法描述符不能包含空值"
        }
        require(query.parameterTypeReferences.keys.all { it >= 0 }) {
            "Provider DexKit 参数类型引用下标不能为负数"
        }
        require(
            query.parameterTypeReferences.isEmpty() || query.parameterTypeNames != null,
        ) {
            "Provider DexKit 使用参数类型引用时必须提供参数列表"
        }
        require(
            query.parameterTypeNames == null ||
                query.parameterTypeReferences.keys.all { it in query.parameterTypeNames.indices },
        ) {
            "Provider DexKit 参数类型引用超出参数列表"
        }
        require(query.returnTypeName == null || query.returnTypeReference == null) {
            "Provider DexKit returnTypeName 与引用不能同时设置"
        }
        require(query.returnTypeNamePrefix == null || query.returnTypeNamePrefix.isNotBlank()) {
            "Provider DexKit returnTypeNamePrefix 不能为空"
        }
        require(query.returnTypeName == null || query.returnTypeNamePrefix == null) {
            "Provider DexKit returnTypeName 与前缀不能同时设置"
        }
        require(query.returnTypeReference == null || query.returnTypeNamePrefix == null) {
            "Provider DexKit 返回类型引用与前缀不能同时设置"
        }
        listOfNotNull(
            query.declaringClassReference,
            query.returnTypeReference,
            *query.parameterTypeReferences.values.toTypedArray(),
        ).forEach { reference ->
            require(reference.queryCacheKey.isNotBlank()) {
                "Provider DexKit 类型引用 queryCacheKey 不能为空"
            }
            require(
                reference.source == OfficialProviderDexTypeSource.PARAMETER_TYPE ||
                    reference.parameterIndex == -1,
            ) {
                "Provider DexKit 非参数类型引用不应设置 parameterIndex"
            }
            require(
                reference.source != OfficialProviderDexTypeSource.PARAMETER_TYPE ||
                    reference.parameterIndex >= 0,
            ) {
                "Provider DexKit 参数类型引用必须设置 parameterIndex"
            }
        }
        require(
            query.declaringClassName != null ||
                query.declaringClassNamePrefix != null ||
                query.declaringClassReference != null ||
                query.requiredStrings.isNotEmpty() ||
                query.requiredInvokedMethodDescriptors.isNotEmpty() ||
                query.requiredInvokedMethodNames.isNotEmpty() ||
                query.requiredCallerMethodNames.isNotEmpty() ||
                query.forbiddenInvokedMethodDescriptors.isNotEmpty(),
        ) {
            "Provider DexKit 后备查询必须包含类名或特征字符串"
        }
        require(query.preferredTarget == null || query.requiredCallerMethodNames.isEmpty()) {
            "Provider DexKit 调用方约束不能与未经语义校验的首选目标同时使用"
        }
        require(
            query.preferredTarget == null || query.forbiddenInvokedMethodDescriptors.isEmpty(),
        ) {
            "Provider DexKit 禁止调用约束不能与未经语义校验的首选目标同时使用"
        }
        query.preferredTarget?.let { target ->
            require(target.className.isNotBlank()) { "Provider 首选 className 不能为空" }
            require(target.methodName.isNotBlank()) { "Provider 首选 methodName 不能为空" }
            require(target.returnTypeName.isNotBlank()) { "Provider 首选 returnTypeName 不能为空" }
        }
    }
}

fun interface OfficialProviderMethodCallback {
    fun onMethodCalled(receiver: Any?, arguments: Array<Any?>)
}

fun interface OfficialProviderMethodResultCallback {
    fun onMethodReturned(receiver: Any?, arguments: Array<Any?>, result: Any?): Any?
}

fun interface OfficialProviderConstructorCallback {
    fun onConstructed(instance: Any?, arguments: Array<Any?>)
}

fun interface OfficialProviderDexMethodsCallback {
    fun onMethodsResolved(targets: List<OfficialProviderMethodTarget>)
}

data class OfficialProviderNextTrackFrame(
    val clear: Boolean,
    val currentId: String,
    val currentTitle: String,
    val currentArtist: String,
    val nextId: String,
    val nextTitle: String,
    val nextArtist: String,
    val nextAlbum: String,
    val nextDurationMs: Long,
)

/**
 * Additive control channel transported through Lyricon's existing sendText call.
 * Reserved frames are consumed by Central and must never reach plain-text lyrics.
 */
object OfficialProviderControlProtocol {
    const val CONTROL_ONLY_METADATA_KEY = "hle.control_only"
    const val NEXT_TRACK_PREFIX = "\u001eHLE_OFFICIAL_NEXT_TRACK_V1|"

    private const val UPDATE_OPERATION = "U"
    private const val CLEAR_OPERATION = "C"
    private const val FIELD_COUNT = 9
    private const val MAX_FRAME_LENGTH = 16 * 1024
    private const val MAX_FIELD_LENGTH = 1024

    fun encodeNextTrack(
        currentId: String,
        currentTitle: String,
        currentArtist: String,
        nextId: String,
        nextTitle: String,
        nextArtist: String,
        nextAlbum: String = "",
        nextDurationMs: Long = -1L,
    ): String = encode(
        operation = UPDATE_OPERATION,
        currentId = currentId,
        currentTitle = currentTitle,
        currentArtist = currentArtist,
        nextId = nextId,
        nextTitle = nextTitle,
        nextArtist = nextArtist,
        nextAlbum = nextAlbum,
        nextDurationMs = nextDurationMs,
    )

    fun encodeNextTrackClear(
        currentId: String = "",
        currentTitle: String = "",
        currentArtist: String = "",
    ): String = encode(
        operation = CLEAR_OPERATION,
        currentId = currentId,
        currentTitle = currentTitle,
        currentArtist = currentArtist,
        nextId = "",
        nextTitle = "",
        nextArtist = "",
        nextAlbum = "",
        nextDurationMs = -1L,
    )

    fun isReservedFrame(text: String?): Boolean = text?.startsWith(NEXT_TRACK_PREFIX) == true

    fun decodeNextTrack(text: String?): OfficialProviderNextTrackFrame? {
        if (!isReservedFrame(text) || text == null || text.length > MAX_FRAME_LENGTH) return null
        val fields = text.removePrefix(NEXT_TRACK_PREFIX).split('|')
        if (fields.size != FIELD_COUNT) return null
        val operation = fields[0]
        if (operation != UPDATE_OPERATION && operation != CLEAR_OPERATION) return null
        val decoded = fields.drop(1).dropLast(1).map { decodeField(it) ?: return null }
        val duration = fields.last().toLongOrNull()?.takeIf { it >= -1L } ?: return null
        val frame = OfficialProviderNextTrackFrame(
            clear = operation == CLEAR_OPERATION,
            currentId = decoded[0],
            currentTitle = decoded[1],
            currentArtist = decoded[2],
            nextId = decoded[3],
            nextTitle = decoded[4],
            nextArtist = decoded[5],
            nextAlbum = decoded[6],
            nextDurationMs = duration,
        )
        return frame.takeIf { it.clear || it.nextTitle.isNotBlank() }
    }

    private fun encode(
        operation: String,
        currentId: String,
        currentTitle: String,
        currentArtist: String,
        nextId: String,
        nextTitle: String,
        nextArtist: String,
        nextAlbum: String,
        nextDurationMs: Long,
    ): String = buildString {
        append(NEXT_TRACK_PREFIX)
        append(operation)
        listOf(
            currentId,
            currentTitle,
            currentArtist,
            nextId,
            nextTitle,
            nextArtist,
            nextAlbum,
        ).forEach { value ->
            append('|')
            append(encodeField(value.take(MAX_FIELD_LENGTH)))
        }
        append('|')
        append(nextDurationMs.coerceAtLeast(-1L))
    }

    private fun encodeField(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.length <= MAX_FIELD_LENGTH }
}
