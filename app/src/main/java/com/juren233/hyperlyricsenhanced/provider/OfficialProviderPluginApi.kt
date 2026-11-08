/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.ParcelFileDescriptor
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Executable
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

/**
 * 一次真实方法调用的可改写入口，形状与 libxposed 的 Chain 对齐。
 *
 * 仅在 [OfficialProviderInterceptor.intercept] 回调期间有效，Host 可能复用同一对象；
 * Provider 不得保存该实例或跨线程使用。
 */
interface OfficialProviderCall {
    val executable: Executable
    val thisObject: Any?
    val args: List<Any?>

    fun proceed(): Any?

    fun proceed(args: Array<out Any?>): Any?

    fun proceedWith(thisObject: Any): Any?

    fun proceedWith(thisObject: Any, args: Array<out Any?>): Any?
}

fun interface OfficialProviderInterceptor {
    fun intercept(call: OfficialProviderCall): Any?
}

fun interface OfficialProviderHookHandle {
    fun unhook()
}

interface OfficialProviderHost {
    val packageName: String
    val processName: String

    /**
     * 目标 App 的类加载器；Pack 在 Application 可用之前做反射或早期装配时使用。
     *
     * 旧主程序没有这项能力，返回 null；调用方应等到 `hookApplication` 回调后再装配。
     */
    val targetClassLoader: ClassLoader? get() = null

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

    /**
     * 为精确的 [Executable] 安装可完全接管调用的 Hook。
     *
     * 拦截器可以改写参数、替换返回值或绕开原方法。拦截器抛异常时 Host 统一兜底：
     * 尚未调用 proceed 就执行原方法，已经调用过则返回该次 proceed 的结果。
     * 新 Pack 必须提高 minCoreVersionCode，旧主程序没有这项能力。
     */
    fun intercept(
        executable: Executable,
        interceptor: OfficialProviderInterceptor,
    ): OfficialProviderHookHandle =
        throw UnsupportedOperationException("当前主程序不支持 Provider 拦截 Hook")

    /** 取消目标方法的 AOT/JIT 内联，供需要拦截内联调用方的 Pack 使用。 */
    fun deoptimize(executable: Executable): Boolean = false

    /** 读取主模块远端偏好；旧主程序或读取失败时返回 null。 */
    fun getRemotePreferences(group: String): SharedPreferences? = null

    /**
     * 只读打开主模块发布的 Xposed Remote File（例如用户在主模块里准备的图片）。
     *
     * 文件不存在、旧主程序或读取失败时返回 null；调用方负责关闭返回的描述符。
     */
    fun openRemoteFile(name: String): ParcelFileDescriptor? = null

    /**
     * 打开目标 App DEX 的 DexKit 桥接，Host 负责加载 libdexkit.so。
     *
     * 每次调用返回新的 [DexKitBridge]，由调用方负责 close()。
     */
    fun openDexKit(application: Application): DexKitBridge? = null

    /** 当前主程序 versionCode，Pack 可据此区分 DexKit 缓存等与版本相关的行为。 */
    val coreVersionCode: Int get() = 0

    /** 当前 Provider Pack 的 versionCode，与主程序版本独立；旧主程序返回 0。 */
    val pluginVersionCode: Int get() = 0

    /**
     * 主模块 APK 路径；Pack 读取模块 assets 中的共享资源（如繁简字典）时使用。
     *
     * 旧主程序没有这项能力，返回 null；调用方应自行降级。
     */
    val moduleApkPath: String? get() = null
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
    /**
     * 链式解析模式的放宽约束：完整参数列表未知时按首参类型唯一匹配构造函数。
     *
     * Spotify 歌词包装类的构造器是 (服务接口, 若干混淆协作对象)，服务类型来自
     * 注解锚查询的运行期结果，其余参数名逐版本漂移，无法写进精确档案。
     * 与 [parameterTypeNames] 互斥，命中结果必须唯一。
     */
    val firstParameterTypeName: String? = null,
) {
    /**
     * Binary-compatible constructor for Provider Packs built before the first-parameter
     * constraint was added. InMemoryDexClassLoader delegates this API package to the core
     * class loader, so removing the old JVM constructor would break installed Packs.
     */
    @Suppress("unused")
    @Deprecated("Binary compatibility for Provider Packs", level = DeprecationLevel.HIDDEN)
    constructor(
        className: String,
        parameterTypeNames: List<String> = emptyList(),
    ) : this(
        className = className,
        parameterTypeNames = parameterTypeNames,
        firstParameterTypeName = null,
    )
}

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
 * Declares an annotation-value constraint for a DexKit method query.
 *
 * R8 renames annotation classes on every Spotify-style rebuild (9.1.72 把
 * retrofit2.http.GET 改名为 p.thy，9.1.80 改名为 p.vsz，见 HLE-Providers
 * SPOTIFY-LYRICS-002 取证），因此 [annotationTypeName] 与 [elementName] 都允许
 * 为空：只锚定注解元素携带的字符串常量本身。端点路径常量由 Retrofit 在运行期
 * 反射读取，R8 必须原样保留，是跨版本最稳的语义锚。
 */
data class OfficialProviderMethodAnnotationConstraint(
    val annotationTypeName: String? = null,
    val elementName: String? = null,
    val elementValue: String,
)

/**
 * Stable, DexKit-independent method query passed across the Provider Pack ABI.
 *
 * Null constraints are intentionally left unconstrained. The host requires a
 * unique result after applying every declared constraint.
 *
 * 本主构造器签名已冻结：历史签名的二进制兼容由下方 hidden 构造器承担，后续
 * 一律通过 [OfficialProviderDexMethodQueryBuilder] 扩展字段。Builder 新增
 * 方法是二进制兼容的（旧 Pack 不会调用新方法），而改主构造器签名会立即打断
 * 所有已分发 Pack。
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
    val requiredMethodAnnotation: OfficialProviderMethodAnnotationConstraint? = null,
    val declaringClassFieldTypeNames: List<String> = emptyList(),
    val declaringClassFieldReferences: List<OfficialProviderDexTypeReference> = emptyList(),
) {
    /**
     * Binary-compatible constructor for Provider Packs built against the annotation-anchor
     * API level (the frozen primary without the annotation and field-type constraints).
     *
     * 注解锚与声明类字段约束作为尾部字段加入，已分发 Pack 继续使用它们编译时的
     * 精确 JVM 构造器；此 hidden 构造器保证旧字节码在新核心上照常解析。
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
        forbiddenInvokedMethodDescriptors: List<String> = emptyList(),
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
        forbiddenInvokedMethodDescriptors = forbiddenInvokedMethodDescriptors,
        requiredMethodAnnotation = null,
        declaringClassFieldTypeNames = emptyList(),
        declaringClassFieldReferences = emptyList(),
    )
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

/**
 * Builder for [OfficialProviderDexMethodQuery] — the sanctioned additive extension point.
 *
 * 给 Builder 新增 var 属性是二进制兼容的：旧 Pack 字节码不会引用新属性，新 Pack
 * 在旧核心上因缺 setter 触发 NoSuchMethodError，并由 manifest 的 minCoreVersionCode
 * 门禁阻止加载。主构造器签名从此不再变化。
 */
class OfficialProviderDexMethodQueryBuilder(private val cacheKey: String) {
    var preferredTarget: OfficialProviderMethodTarget? = null
    var declaringClassName: String? = null
    var declaringClassNamePrefix: String? = null
    var declaringClassReference: OfficialProviderDexTypeReference? = null
    var requiredStrings: List<String> = emptyList()
    var requiredInvokedMethodDescriptors: List<String> = emptyList()
    var requiredInvokedMethodNames: List<String> = emptyList()
    var parameterTypeNames: List<String>? = null
    var parameterTypeReferences: Map<Int, OfficialProviderDexTypeReference> = emptyMap()
    var returnTypeName: String? = null
    var returnTypeNamePrefix: String? = null
    var returnTypeReference: OfficialProviderDexTypeReference? = null
    var returnTypeMatchesDeclaringClass: Boolean = false
    var isStatic: Boolean? = null
    var requiredCallerMethodNames: List<String> = emptyList()
    var forbiddenInvokedMethodDescriptors: List<String> = emptyList()
    var requiredMethodAnnotation: OfficialProviderMethodAnnotationConstraint? = null
    var declaringClassFieldTypeNames: List<String> = emptyList()
    var declaringClassFieldReferences: List<OfficialProviderDexTypeReference> = emptyList()

    fun build(): OfficialProviderDexMethodQuery = OfficialProviderDexMethodQuery(
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
        forbiddenInvokedMethodDescriptors = forbiddenInvokedMethodDescriptors,
        requiredMethodAnnotation = requiredMethodAnnotation,
        declaringClassFieldTypeNames = declaringClassFieldTypeNames,
        declaringClassFieldReferences = declaringClassFieldReferences,
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
        query.requiredMethodAnnotation?.let { constraint ->
            require(constraint.elementValue.isNotBlank()) {
                "Provider DexKit 注解元素值不能为空"
            }
            require(constraint.annotationTypeName == null || constraint.annotationTypeName.isNotBlank()) {
                "Provider DexKit 注解类型名不能为空"
            }
            require(constraint.elementName == null || constraint.elementName.isNotBlank()) {
                "Provider DexKit 注解元素名不能为空"
            }
        }
        require(query.declaringClassFieldTypeNames.all(String::isNotBlank)) {
            "Provider DexKit 声明类字段类型不能包含空值"
        }
        require(
            query.declaringClassFieldTypeNames.isEmpty() &&
                query.declaringClassFieldReferences.isEmpty() ||
                (query.declaringClassName == null &&
                    query.declaringClassNamePrefix == null &&
                    query.declaringClassReference == null),
        ) {
            "Provider DexKit 声明类字段约束不能与类名约束同时设置"
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
        query.declaringClassFieldReferences.forEach { reference ->
            require(reference.queryCacheKey.isNotBlank()) {
                "Provider DexKit 声明类字段引用 queryCacheKey 不能为空"
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
                query.forbiddenInvokedMethodDescriptors.isNotEmpty() ||
                query.requiredMethodAnnotation != null ||
                query.declaringClassFieldTypeNames.isNotEmpty() ||
                query.declaringClassFieldReferences.isNotEmpty(),
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
        require(query.preferredTarget == null || query.requiredMethodAnnotation == null) {
            "Provider DexKit 注解约束不能与未经语义校验的首选目标同时使用"
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
