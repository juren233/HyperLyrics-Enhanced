/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P4 所有权守卫：调度状态只由 AppleInternalCatalogDispatch 声明，
 * 缓存状态只由 AppleInternalCatalogCaches 声明；Resolver 只保留
 * 两个 owner 与 Query/Reflection 适配边界所需的接线字段；家族外的
 * 文件不得触碰 owner 内部状态（外部只能走具名入口函数）。
 */
class AppleInternalCatalogOwnershipTest {

    private val dispatchInternals = listOf(
        "originalSongInFlight",
        "originalCandidateCallbacks",
        "catalogIdentityInFlight",
        "localizedInFlight",
        "originalEntityPending",
        "originalEntityBatchScheduled",
        "originalEntityBatchesRunning",
        "originalEntityBackgroundBatchesRunning",
        "localizedPending",
        "localizedBatchScheduled",
        "localizedBatchesRunning",
        "localizedBackgroundBatchesRunning",
        "requestPriorityByMediaId",
        "requestScopeActive",
        "requestScopeRevision",
    )

    private val cacheInternals = listOf(
        "originalSongCache",
        "localizedCache",
        "localizedArtistAliasCache",
        "catalogIdentityCache",
        "persistentLocalizedCacheEnabled",
        "warmedSelections",
        "warmingSelections",
    )

    private fun mainSourceDir(): File =
        listOf(
            File("app/src/main/java/io/github/proify/lyricon/amprovider/xposed"),
            File("src/main/java/io/github/proify/lyricon/amprovider/xposed"),
        ).first(File::isDirectory)

    private fun source(fileName: String): String = File(mainSourceDir(), fileName).readText()

    @Test
    fun `resolver keeps only the two owners and adapter wiring`() {
        val resolver = source("AppleInternalCatalogResolver.kt")
        assertTrue(
            "Resolver 必须持有调度所有者",
            resolver.contains("internal val dispatch = AppleInternalCatalogDispatch()"),
        )
        assertTrue(
            "Resolver 必须持有缓存所有者",
            resolver.contains("internal val caches = AppleInternalCatalogCaches()"),
        )
        (dispatchInternals + cacheInternals).forEach { field ->
            val declared = Regex("internal\\s+(val|var)\\s+$field\\b")
            assertTrue(
                "Resolver 不应再直接声明 $field",
                !declared.containsMatchIn(resolver),
            )
        }
    }

    @Test
    fun `owner files declare the moved state exactly`() {
        val dispatchSource = source("AppleInternalCatalogDispatch.kt")
        dispatchInternals.forEach { field ->
            assertTrue(
                "调度所有者缺少 $field",
                Regex("(val|var)\\s+$field\\b").containsMatchIn(dispatchSource),
            )
        }
        val cachesSource = source("AppleInternalCatalogCaches.kt")
        cacheInternals.forEach { field ->
            assertTrue(
                "缓存所有者缺少 $field",
                Regex("(val|var)\\s+$field\\b").containsMatchIn(cachesSource),
            )
        }
    }

    @Test
    fun `non catalog files never touch owner internals`() {
        val forbidden = (dispatchInternals + cacheInternals).joinToString("|")
        val regex = Regex("\\.(?:dispatch|caches)\\.(?:$forbidden)\\b")
        val offenders = mainSourceDir().walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filterNot { file -> file.name.startsWith("AppleInternalCatalog") }
            .filter { file -> regex.containsMatchIn(file.readText()) }
            .map(File::getName)
            .toList()
        assertTrue("外部文件不得触碰 owner 内部状态: $offenders", offenders.isEmpty())
    }
}
