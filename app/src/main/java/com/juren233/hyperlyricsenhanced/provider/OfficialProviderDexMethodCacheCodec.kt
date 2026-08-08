/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import java.security.MessageDigest
import java.util.Base64

internal object OfficialProviderDexMethodCacheCodec {
    private const val FORMAT_VERSION = "1"

    fun cacheKey(
        packageName: String,
        processName: String,
        versionCode: Long,
        lastUpdateTime: Long,
        query: OfficialProviderDexMethodQuery,
    ): String {
        val fingerprint = buildString {
            append(query.cacheKey)
            append('\u0000')
            append(query.preferredTarget?.let(::encode).orEmpty())
            append('\u0000')
            append(query.declaringClassName.orEmpty())
            append('\u0000')
            append(query.declaringClassNamePrefix.orEmpty())
            append('\u0000')
            append(query.declaringClassReference.fingerprint())
            append('\u0000')
            append(query.requiredStrings.joinToString("\u0001"))
            append('\u0000')
            append(query.requiredInvokedMethodDescriptors.joinToString("\u0001"))
            append('\u0000')
            append(query.requiredInvokedMethodNames.joinToString("\u0001"))
            append('\u0000')
            append(query.parameterTypeNames?.joinToString("\u0001").orEmpty())
            append('\u0000')
            append(
                query.parameterTypeReferences.entries
                    .sortedBy(Map.Entry<Int, OfficialProviderDexTypeReference>::key)
                    .joinToString("\u0001") { (index, reference) ->
                        "$index:${reference.fingerprint()}"
                    },
            )
            append('\u0000')
            append(query.returnTypeName.orEmpty())
            append('\u0000')
            append(query.returnTypeNamePrefix.orEmpty())
            append('\u0000')
            append(query.returnTypeReference.fingerprint())
            append('\u0000')
            append(query.returnTypeMatchesDeclaringClass)
            append('\u0000')
            append(query.isStatic?.toString().orEmpty())
        }.sha256()
        return "hle_dex_method_v1:$packageName:$processName:$versionCode:$lastUpdateTime:$fingerprint"
    }

    fun encode(target: OfficialProviderMethodTarget): String = buildList {
        add(FORMAT_VERSION)
        add(if (target.isStatic) "1" else "0")
        add(target.className.encoded())
        add(target.methodName.encoded())
        add(target.returnTypeName.encoded())
        add(target.parameterTypeNames.size.toString())
        target.parameterTypeNames.forEach { add(it.encoded()) }
    }.joinToString("|")

    fun decode(value: String?): OfficialProviderMethodTarget? = runCatching {
        val fields = value?.split('|') ?: return null
        if (fields.size < 6 || fields[0] != FORMAT_VERSION) return null
        val parameterCount = fields[5].toInt()
        if (parameterCount < 0 || fields.size != 6 + parameterCount) return null
        OfficialProviderMethodTarget(
            className = fields[2].decoded(),
            methodName = fields[3].decoded(),
            parameterTypeNames = fields.drop(6).map { it.decoded() },
            returnTypeName = fields[4].decoded(),
            isStatic = when (fields[1]) {
                "1" -> true
                "0" -> false
                else -> return null
            },
        )
    }.getOrNull()

    fun matches(
        target: OfficialProviderMethodTarget,
        query: OfficialProviderDexMethodQuery,
    ): Boolean =
        (query.declaringClassName == null || target.className == query.declaringClassName) &&
            (query.declaringClassNamePrefix == null ||
                target.className.startsWith(query.declaringClassNamePrefix)) &&
            (query.parameterTypeNames == null ||
                target.parameterTypeNames == query.parameterTypeNames) &&
            (query.returnTypeName == null || target.returnTypeName == query.returnTypeName) &&
            (query.returnTypeNamePrefix == null ||
                target.returnTypeName.startsWith(query.returnTypeNamePrefix)) &&
            (!query.returnTypeMatchesDeclaringClass ||
                target.returnTypeName == target.className) &&
            (query.isStatic == null || target.isStatic == query.isStatic)

    private fun OfficialProviderDexTypeReference?.fingerprint(): String = this?.let { reference ->
        "${reference.queryCacheKey}:${reference.source}:${reference.parameterIndex}"
    }.orEmpty()

    private fun String.encoded(): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(toByteArray(Charsets.UTF_8))

    private fun String.decoded(): String = String(
        Base64.getUrlDecoder().decode(this),
        Charsets.UTF_8,
    )

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
