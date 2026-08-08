/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.io.File
import java.util.Base64

/**
 * Slow-path resolver for Apple Music methods whose verified version profile no longer loads.
 *
 * The normal path remains reflection against [AppleMusicHookProfiles]. DexKit is opened only
 * after every exact/compatibility descriptor fails. A resolved descriptor is cached per APK
 * version and update timestamp, then fully revalidated through reflection before reuse.
 */
internal class AppleMusicDexKitResolver(
    private val application: Application,
    private val classLoader: ClassLoader,
    private val nativeLibraryDir: String,
) {
    fun resolveMethod(
        hookPoint: AppleMusicHookPoint,
        templates: List<AppleMusicHookTarget>,
        validator: (AppleMusicHookTarget, Method) -> Boolean,
    ): ResolvedAppleMusicHookMethod? {
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val cacheKey = "${packageInfo.longVersionCode}:${packageInfo.lastUpdateTime}:$hookPoint"
        decode(preferences.getString(cacheKey, null))?.let { cached ->
            val template = bestTemplate(templates, cached)
            val method = runCatching { cached.toMethod(classLoader) }.getOrNull()
            if (template != null && method != null && validator(template, method)) {
                ProviderLogger.info(
                    "Apple Music DexKit 缓存命中: hook=$hookPoint target=${cached.describe()}",
                )
                return ResolvedAppleMusicHookMethod(
                    target = template.forResolvedMethod(method),
                    method = method,
                    compatibilityFallback = true,
                )
            }
            preferences.edit().remove(cacheKey).apply()
            ProviderLogger.diagnostic("Apple Music DexKit 缓存失效: hook=$hookPoint")
        }

        val startedAt = System.nanoTime()
        val descriptors = findCandidates(templates)
        val matches = descriptors.mapNotNull { descriptor ->
            val template = bestTemplate(templates, descriptor) ?: return@mapNotNull null
            val method = runCatching { descriptor.toMethod(classLoader) }.getOrNull()
                ?: return@mapNotNull null
            method.takeIf { validator(template, it) }?.let { template to it }
        }.distinctBy { (_, method) -> method.toGenericString() }
        if (matches.size != 1) {
            ProviderLogger.diagnostic(
                "Apple Music DexKit 查询未得到唯一目标: hook=$hookPoint count=${matches.size}",
            )
            return null
        }
        val (template, method) = matches.single()
        val descriptor = MethodDescriptor.from(method)
        preferences.edit().putString(cacheKey, encode(descriptor)).apply()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        ProviderLogger.info(
            "Apple Music DexKit 查询并缓存成功: hook=$hookPoint elapsedMs=$elapsedMs " +
                "target=${descriptor.describe()}",
        )
        return ResolvedAppleMusicHookMethod(
            target = template.forResolvedMethod(method),
            method = method.apply { isAccessible = true },
            compatibilityFallback = true,
        )
    }

    private fun findCandidates(
        templates: List<AppleMusicHookTarget>,
    ): List<MethodDescriptor> {
        ensureDexKitLoaded()
        val bridge = dexKitBridge()
        return templates.map { template ->
            val finder = FindMethod().apply {
                matcher {
                    template.parameterCount?.let(::paramCount)
                    template.returnTypeName
                        ?.takeIf(::isStableRuntimeType)
                        ?.let(::returnType)
                }
            }
            bridge.findMethod(finder).map { method ->
                MethodDescriptor(
                    className = method.className,
                    methodName = method.methodName,
                    parameterTypeNames = method.paramTypeNames,
                    returnTypeName = method.returnTypeName,
                    isStatic = Modifier.isStatic(method.modifiers),
                )
            }
        }.flatten().distinct()
    }

    private fun dexKitBridge(): DexKitBridge {
        sharedBridge?.let { return it }
        synchronized(dexKitBridgeLock) {
            sharedBridge?.let { return it }
            return DexKitBridge.create(application.applicationInfo.sourceDir).also { bridge ->
                bridge.setThreadNum(selectThreadCount())
                bridge.setMaxConcurrentQueries(1)
                sharedBridge = bridge
            }
        }
    }

    private fun bestTemplate(
        templates: List<AppleMusicHookTarget>,
        descriptor: MethodDescriptor,
    ): AppleMusicHookTarget? = templates
        .filter { template ->
            (template.parameterCount == null ||
                template.parameterCount == descriptor.parameterTypeNames.size) &&
                (template.isStatic == null || template.isStatic == descriptor.isStatic) &&
                (template.returnTypeName == null ||
                    !isStableRuntimeType(template.returnTypeName) ||
                    template.returnTypeName == descriptor.returnTypeName) &&
                template.parameterTypeNames.orEmpty().withIndex().all { (index, typeName) ->
                    typeName == null ||
                        !isStableRuntimeType(typeName) ||
                        descriptor.parameterTypeNames.getOrNull(index) == typeName
                }
        }
        .minByOrNull { template ->
            var score = 0
            if (template.methodName != descriptor.methodName) score += 1
            if (template.className != descriptor.className) score += 1
            score
        }

    private fun AppleMusicHookTarget.forResolvedMethod(method: Method): AppleMusicHookTarget = copy(
        className = method.declaringClass.name,
        methodName = method.name,
        parameterCount = method.parameterCount,
        parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
        returnTypeName = method.returnType.name,
        isStatic = Modifier.isStatic(method.modifiers),
    )

    private fun selectThreadCount(): Int {
        val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val availableMemory = runCatching {
            val manager = application.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.availMem
        }.getOrDefault(0L)
        return if (processors >= 4 && availableMemory >= TWO_GIB_BYTES) 2 else 1
    }

    private fun ensureDexKitLoaded() {
        if (dexKitLoaded) return
        synchronized(dexKitLoadLock) {
            if (dexKitLoaded) return
            val nativeLibrary = File(nativeLibraryDir, "libdexkit.so")
            require(nativeLibrary.isFile) { "DexKit native library missing: ${nativeLibrary.absolutePath}" }
            System.load(nativeLibrary.absolutePath)
            dexKitLoaded = true
        }
    }

    private data class MethodDescriptor(
        val className: String,
        val methodName: String,
        val parameterTypeNames: List<String>,
        val returnTypeName: String,
        val isStatic: Boolean,
    ) {
        fun toMethod(loader: ClassLoader): Method {
            val clazz = loader.loadClass(className)
            val parameters = parameterTypeNames.map { resolveType(loader, it) }.toTypedArray()
            return clazz.getDeclaredMethod(methodName, *parameters).apply {
                isAccessible = true
                require(returnType.name == returnTypeName)
                require(Modifier.isStatic(modifiers) == isStatic)
            }
        }

        fun describe(): String = "$className#$methodName(${parameterTypeNames.joinToString()}):" +
            "$returnTypeName${if (isStatic) "[static]" else "[instance]"}"

        companion object {
            fun from(method: Method) = MethodDescriptor(
                className = method.declaringClass.name,
                methodName = method.name,
                parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
                returnTypeName = method.returnType.name,
                isStatic = Modifier.isStatic(method.modifiers),
            )
        }
    }

    private companion object {
        const val PREFERENCES = "hle_apple_music_dex_methods_v1"
        const val TWO_GIB_BYTES = 2L * 1024L * 1024L * 1024L
        val dexKitLoadLock = Any()
        val dexKitBridgeLock = Any()

        @Volatile
        var dexKitLoaded = false

        @Volatile
        var sharedBridge: DexKitBridge? = null

        fun isStableRuntimeType(typeName: String): Boolean =
            typeName == "void" ||
                typeName in setOf(
                    "boolean",
                    "byte",
                    "char",
                    "short",
                    "int",
                    "long",
                    "float",
                    "double",
                ) ||
                typeName.startsWith("java.") ||
                typeName.startsWith("android.") ||
                typeName.startsWith("kotlin.") ||
                typeName.startsWith("androidx.")

        fun resolveType(loader: ClassLoader, typeName: String): Class<*> = when (typeName) {
            "void" -> Void.TYPE
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "byte" -> Byte::class.javaPrimitiveType!!
            "char" -> Char::class.javaPrimitiveType!!
            "short" -> Short::class.javaPrimitiveType!!
            "int" -> Int::class.javaPrimitiveType!!
            "long" -> Long::class.javaPrimitiveType!!
            "float" -> Float::class.javaPrimitiveType!!
            "double" -> Double::class.javaPrimitiveType!!
            else -> loader.loadClass(typeName)
        }

        fun encode(value: MethodDescriptor): String = buildList {
            add(if (value.isStatic) "1" else "0")
            add(value.className.encoded())
            add(value.methodName.encoded())
            add(value.returnTypeName.encoded())
            add(value.parameterTypeNames.size.toString())
            value.parameterTypeNames.forEach { add(it.encoded()) }
        }.joinToString("|")

        fun decode(value: String?): MethodDescriptor? = runCatching {
            val fields = value?.split('|') ?: return null
            if (fields.size < 5) return null
            val count = fields[4].toInt()
            if (count < 0 || fields.size != count + 5) return null
            MethodDescriptor(
                className = fields[1].decoded(),
                methodName = fields[2].decoded(),
                parameterTypeNames = fields.drop(5).map { it.decoded() },
                returnTypeName = fields[3].decoded(),
                isStatic = fields[0] == "1",
            )
        }.getOrNull()

        fun String.encoded(): String = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(toByteArray(Charsets.UTF_8))

        fun String.decoded(): String = String(
            Base64.getUrlDecoder().decode(this),
            Charsets.UTF_8,
        )
    }
}
