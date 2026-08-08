/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import java.util.Base64

internal data class OfficialProviderDexMethodBaseline(
    val fieldCount: Int,
    val methodCount: Int,
    val interfaceCount: Int,
    val stableFieldTypeCounts: Map<String, Int>,
    val parameterCount: Int,
    val stableParameterTypeNames: List<String?>,
    val stableReturnTypeName: String?,
    val isStatic: Boolean,
    val ordinal: Int,
)

internal object OfficialProviderDexMethodBaselineCodec {
    private const val FORMAT_VERSION = "1"

    fun encode(value: OfficialProviderDexMethodBaseline): String = listOf(
        FORMAT_VERSION,
        value.fieldCount.toString(),
        value.methodCount.toString(),
        value.interfaceCount.toString(),
        encodeCounts(value.stableFieldTypeCounts),
        value.parameterCount.toString(),
        value.stableParameterTypeNames.joinToString(",") { it.orEmpty().encoded() },
        value.stableReturnTypeName.orEmpty().encoded(),
        if (value.isStatic) "1" else "0",
        value.ordinal.toString(),
    ).joinToString("|")

    fun decode(value: String?): OfficialProviderDexMethodBaseline? = runCatching {
        val fields = value?.split('|') ?: return null
        if (fields.size != 10 || fields[0] != FORMAT_VERSION) return null
        OfficialProviderDexMethodBaseline(
            fieldCount = fields[1].toInt(),
            methodCount = fields[2].toInt(),
            interfaceCount = fields[3].toInt(),
            stableFieldTypeCounts = decodeCounts(fields[4]),
            parameterCount = fields[5].toInt(),
            stableParameterTypeNames = if (fields[6].isEmpty()) emptyList() else
                fields[6].split(',').map { it.decoded().takeIf(String::isNotEmpty) },
            stableReturnTypeName = fields[7].decoded().takeIf(String::isNotEmpty),
            isStatic = fields[8] == "1",
            ordinal = fields[9].toInt(),
        )
    }.getOrNull()

    private fun encodeCounts(values: Map<String, Int>): String = values.entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key.encoded()}:${it.value}" }

    private fun decodeCounts(value: String): Map<String, Int> = if (value.isEmpty()) {
        emptyMap()
    } else {
        value.split(',').mapNotNull { item ->
            val separator = item.lastIndexOf(':')
            if (separator <= 0) return@mapNotNull null
            val count = item.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
            item.substring(0, separator).decoded() to count
        }.toMap()
    }

    private fun String.encoded(): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(toByteArray(Charsets.UTF_8))

    private fun String.decoded(): String = String(
        Base64.getUrlDecoder().decode(this),
        Charsets.UTF_8,
    )
}
