/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppleMusicIdentifierCentralizationTest {
    @Test
    fun `business hooks do not introduce short obfuscated reflection literals`() {
        val relativeSourcePath = "src/main/java/io/github/proify/lyricon/amprovider/xposed"
        val sourceRoot = listOf(File("app/$relativeSourcePath"), File(relativeSourcePath))
            .first(File::isDirectory)
        val excluded = setOf(
            "AppleMusicHookProfiles.kt",
            "AppleMusicDexKitResolver.kt",
        )
        val shortLiteral = Regex("\"[A-Za-z][A-Za-z0-9_$]{0,2}\"")
        val violations = sourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" && file.name !in excluded }
            .flatMap { file ->
                val lines = file.readLines()
                lines.indices.asSequence().mapNotNull { index ->
                    val window = lines.subList(index, minOf(index + 6, lines.size)).joinToString("\n")
                    val callStart = listOf(
                        "AppleReflection.findMethod(",
                        "AppleReflection.field(",
                        "AppleReflection.call(",
                        "getDeclaredMethod(",
                        "getDeclaredField(",
                    ).map(window::indexOf).filter { it >= 0 }.minOrNull()
                    val reflectionArguments = callStart?.let(window::substring).orEmpty()
                    if (callStart != null && shortLiteral.containsMatchIn(reflectionArguments)) {
                        "${file.path}:${index + 1}"
                    } else {
                        null
                    }
                }
            }
            .distinct()
            .toList()

        assertTrue(
            "Apple Music 混淆反射标识必须进入 Hook Profile/Resolver: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `resolver repairs nested runtime members before refreshing baselines`() {
        val relativeSourcePath =
            "src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleMusicHookProfiles.kt"
        val sourceFile = listOf(File("app/$relativeSourcePath"), File(relativeSourcePath))
            .first(File::isFile)
        val source = sourceFile.readText()

        assertTrue(
            "精确类或方法仍可加载时，也必须先修复内部混淆成员",
            source.contains("repairAndRecordClass(") &&
                source.contains("repairAndRecordMethod(") &&
                source.contains("dexKitResolver?.repairRuntimeMembers("),
        )
        assertTrue(
            "修复后的成员名必须覆盖旧基线，而不是继续记录失效标识",
            source.indexOf("dexKitResolver?.repairRuntimeMembers(") <
                source.lastIndexOf("dexKitResolver?.recordMethodBaseline("),
        )
    }
}
