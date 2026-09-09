/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import com.juren233.hyperlyricsenhanced.common.dexkit.DexResolutionSource
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.io.File
import java.util.Base64

internal fun AppleMusicDexKitResolver.encodeClassShape(value: AppleMusicDexKitResolver.ClassShape): String = listOf(
    value.fieldCount,
    value.methodCount,
    value.interfaceCount,
    encodeCounts(value.stableFieldTypes),
    encodeCounts(value.stableMethodShapes),
).joinToString("|")

internal fun AppleMusicDexKitResolver.decodeClassShape(value: String?): AppleMusicDexKitResolver.ClassShape? = runCatching {
    val fields = value?.split('|') ?: return null
    if (fields.size != 5) return null
    AppleMusicDexKitResolver.ClassShape(
        fieldCount = fields[0].toInt(),
        methodCount = fields[1].toInt(),
        interfaceCount = fields[2].toInt(),
        stableFieldTypes = decodeCounts(fields[3]),
        stableMethodShapes = decodeCounts(fields[4]),
    )
}.getOrNull()

internal fun AppleMusicDexKitResolver.encodeMemberDescriptor(value: AppleMusicDexKitResolver.MemberDescriptor): String = listOf(
    value.type.name,
    value.declaringClassName.encoded(),
    value.name.encoded(),
    if (value.static) "1" else "0",
    value.fieldTypeName.orEmpty().encoded(),
    value.parameterCount?.toString().orEmpty(),
    value.parameterTypeNames.joinToString(",") { it.orEmpty().encoded() },
    value.returnTypeName.orEmpty().encoded(),
    value.ordinal.toString(),
).joinToString("|")

internal fun AppleMusicDexKitResolver.decodeMemberDescriptor(value: String?): AppleMusicDexKitResolver.MemberDescriptor? = runCatching {
    val fields = value?.split('|') ?: return null
    if (fields.size != 9) return null
    AppleMusicDexKitResolver.MemberDescriptor(
        type = AppleMusicDexKitResolver.MemberKind.valueOf(fields[0]),
        declaringClassName = fields[1].decoded(),
        name = fields[2].decoded(),
        static = fields[3] == "1",
        fieldTypeName = fields[4].decoded().takeIf(String::isNotEmpty),
        parameterCount = fields[5].toIntOrNull(),
        parameterTypeNames = if (fields[6].isEmpty()) emptyList() else
            fields[6].split(',').map { it.decoded().takeIf(String::isNotEmpty) },
        returnTypeName = fields[7].decoded().takeIf(String::isNotEmpty),
        ordinal = fields[8].toInt(),
    )
}.getOrNull()

internal fun AppleMusicDexKitResolver.encodeCounts(values: Map<String, Int>): String = values.entries
    .sortedBy { it.key }
    .joinToString(",") { "${it.key.encoded()}:${it.value}" }

internal fun AppleMusicDexKitResolver.decodeCounts(value: String): Map<String, Int> = if (value.isEmpty()) {
    emptyMap()
} else {
    value.split(',').mapNotNull { item ->
        val separator = item.lastIndexOf(':')
        if (separator <= 0) return@mapNotNull null
        item.substring(0, separator).decoded() to item.substring(separator + 1).toIntOrNull()
    }.mapNotNull { (key, count) -> count?.let { key to it } }.toMap()
}

internal fun AppleMusicDexKitResolver.selectThreadCount(): Int {
    val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val availableMemory = runCatching {
        val manager = application.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        info.availMem
    }.getOrDefault(0L)
    return if (processors >= 4 && availableMemory >= TWO_GIB_BYTES) 2 else 1
}

internal fun AppleMusicDexKitResolver.ensureDexKitLoaded() {
    if (dexKitLoaded) return
    synchronized(dexKitLoadLock) {
        if (dexKitLoaded) return
        val nativeLibrary = File(nativeLibraryDir, "libdexkit.so")
        require(nativeLibrary.isFile) { "DexKit native library missing: ${nativeLibrary.absolutePath}" }
        System.load(nativeLibrary.absolutePath)
        dexKitLoaded = true
    }
}

internal data class MethodDescriptor(
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

    const val PREFERENCES = "hle_apple_music_dex_methods_v2"
    const val TWO_GIB_BYTES = 2L * 1024L * 1024L * 1024L
    const val COUNT_TOLERANCE = 4
    val dexKitLoadLock = Any()
    val dexKitBridgeLock = Any()

    @Volatile
    var dexKitLoaded = false

    @Volatile
    var sharedBridge: DexKitBridge? = null

    fun allFields(clazz: Class<*>): List<Field> =
        generateSequence(clazz) { it.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) && it.name == "\$VALUES" }
            .toList()

    fun allMethods(clazz: Class<*>): List<Method> =
        generateSequence(clazz) { it.superclass }
            .flatMap { current -> current.declaredMethods.asSequence() }
            .filterNot { it.isSynthetic && it.name == "\$values" }
            .toList()

    fun methodShape(method: Method): String {
        val returnType = method.returnType.name.takeIf(::isStableRuntimeType).orEmpty()
        val stableParameters = method.parameterTypes.joinToString(",") {
            it.name.takeIf(::isStableRuntimeType).orEmpty()
        }
        return "${method.parameterCount}|$returnType|" +
            "${if (Modifier.isStatic(method.modifiers)) 1 else 0}|$stableParameters"
    }

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

    internal fun encode(value: MethodDescriptor): String = buildList {
        add(if (value.isStatic) "1" else "0")
        add(value.className.encoded())
        add(value.methodName.encoded())
        add(value.returnTypeName.encoded())
        add(value.parameterTypeNames.size.toString())
        value.parameterTypeNames.forEach { add(it.encoded()) }
    }.joinToString("|")

    internal fun decode(value: String?): MethodDescriptor? = runCatching {
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
