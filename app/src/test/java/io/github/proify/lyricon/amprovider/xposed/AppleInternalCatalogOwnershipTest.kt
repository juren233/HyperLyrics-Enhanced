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
 * P4/R4 所有权守卫：调度状态只由 AppleInternalCatalogDispatch 声明，
 * 缓存状态只由 AppleInternalCatalogCaches 声明；Resolver 只保留
 * 两个 owner 与 Query/Reflection 适配边界所需的接线字段。
 *
 * R4 起所有状态字段必须为 `private`，且除两个 owner 本体外的所有文件
 * （**包括 Catalog 家族内的 Batching/Query/Resolve/PersistentCache/Reflection**）
 * 都不得触碰 owner 内部状态，只能走具名入口。豁免只按 owner 本体，不按文件名前缀。
 */
class AppleInternalCatalogOwnershipTest {

    private val dispatchStateFields = listOf(
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

    private val cacheStateFields = listOf(
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
        (dispatchStateFields + cacheStateFields).forEach { field ->
            val declared = Regex("internal\\s+(val|var)\\s+$field\\b")
            assertTrue(
                "Resolver 不应再直接声明 $field",
                !declared.containsMatchIn(resolver),
            )
        }
    }

    @Test
    fun `owner state is declared private`() {
        val dispatchSource = source("AppleInternalCatalogDispatch.kt")
        dispatchStateFields.forEach { field ->
            assertTrue(
                "调度所有者的 $field 必须为 private",
                Regex("private\\s+(val|var)\\s+$field\\b").containsMatchIn(dispatchSource),
            )
        }
        val cachesSource = source("AppleInternalCatalogCaches.kt")
        cacheStateFields.forEach { field ->
            assertTrue(
                "缓存所有者的 $field 必须为 private",
                Regex("private\\s+(val|var)\\s+$field\\b").containsMatchIn(cachesSource),
            )
        }
    }

    @Test
    fun `no file outside the owner bodies touches owner state`() {
        // 豁免只针对 owner 本体，家族内其他文件不再整体放行。
        val exempt = setOf(
            "AppleInternalCatalogDispatch.kt",
            "AppleInternalCatalogCaches.kt",
        )
        val forbidden = (dispatchStateFields + cacheStateFields).joinToString("|")
        val regex = Regex("\\.(?:dispatch|caches)\\.(?:$forbidden)\\b")
        val offenders = mainSourceDir().walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filterNot { file -> file.name in exempt }
            .filter { file -> regex.containsMatchIn(file.readText()) }
            .map(File::getName)
            .toList()
        assertTrue("owner 本体之外不得触碰 owner 内部状态: $offenders", offenders.isEmpty())
    }
}
