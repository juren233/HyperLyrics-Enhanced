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
 * P6 根编排收口守卫：15 个 Host 匿名实现已全部移交 owner 文件的命名类，
 * Orchestrator 不再内联页面绑定逻辑；偏好监听器强引用字段与注册接线
 * 保留在 Orchestrator（受 AppleMusicProviderHookOrderTest 同时约束）。
 */
class AppleOrchestratorHostOwnershipTest {

    private fun source(relative: String): String =
        listOf(
            File("app/src/main/java/io/github/proify/lyricon/amprovider/xposed/$relative"),
            File("src/main/java/io/github/proify/lyricon/amprovider/xposed/$relative"),
        ).first(File::isFile).readText()

    private val hostOwners = listOf(
        "ApplePlaybackMetadataCoordinatorHost" to "metadata/ApplePlaybackMetadataCoordinator.kt",
        "AppleQueueMetadataHost" to "metadata/AppleQueueMetadataHooks.kt",
        "AppleListenNowHost" to "metadata/AppleListenNowContracts.kt",
        "AppleLibrarySurfaceHost" to "metadata/AppleLibrarySurfaceContracts.kt",
        "AppleDataBindingMetadataHost" to "metadata/AppleDataBindingMetadataContracts.kt",
        "AppleCollectionSurfaceHost" to "metadata/AppleCollectionSurfaceHooks.kt",
        "AppleArtistSurfaceHost" to "metadata/AppleArtistSurfaceHooks.kt",
        "AppleMetadataSurfaceHost" to "metadata/AppleMetadataSurfaceRuntime.kt",
        "AppleInAppArtworkContinuityHost" to "metadata/AppleInAppArtworkContinuityHooks.kt",
        "AppleActionSheetMetadataHost" to "metadata/AppleActionSheetMetadataHooks.kt",
        "ApplePlaybackItemConversionHost" to "metadata/ApplePlaybackItemConversionHooks.kt",
        "AppleContentItemMetadataHost" to "metadata/AppleContentItemMetadataHooks.kt",
        "AppleMediaApiMetadataHost" to "metadata/AppleMediaApiMetadataCoordinator.kt",
        "AppleInAppMetadataResolutionHost" to "metadata/AppleInAppMetadataResolutionContracts.kt",
        "AppleVisibleMetadataDiagnosticsHost" to "diagnostics/AppleVisibleMetadataDiagnostics.kt",
    )

    @Test
    fun `orchestrator no longer inlines anonymous host implementations`() {
        val orchestrator = source("AppleMusicProviderOrchestrator.kt")
        assertTrue(
            "Orchestrator 不应再内联 Apple Host 匿名实现",
            !orchestrator.contains("object : Apple"),
        )
    }

    @Test
    fun `every host default implementation lives in its owner file`() {
        val wiring = listOf(
            "AppleMusicProviderOrchestrator.kt",
            "AppleOrchestratorLyricsPlaybackAssembly.kt",
            "AppleOrchestratorInAppMetadataAssembly.kt",
            "AppleOrchestratorCatalogLanguageAssembly.kt",
        ).joinToString("\n") { source(it) }
        hostOwners.forEach { (interfaceName, ownerFile) ->
            val className = "Default$interfaceName"
            val owner = source(ownerFile)
            assertTrue(
                "$ownerFile 缺少 $className",
                owner.contains("internal class $className"),
            )
            assertTrue(
                "R6 组装群应以 $className 接线",
                wiring.contains(className),
            )
        }
    }

    @Test
    fun `preference listener strong reference wiring stays in orchestrator`() {
        val catalogLanguage = source("AppleOrchestratorCatalogLanguageAssembly.kt")
        val lyricsPlayback = source("AppleOrchestratorLyricsPlaybackAssembly.kt")
        assertTrue(
            "偏好监听器强引用字段必须保留在目录/语言组装群",
            catalogLanguage.contains("contentUiLanguagePreferenceListener"),
        )
        assertTrue(
            "PreferencesMonitor 监听器接线必须保留在歌词/播放组装群",
            lyricsPlayback.contains(
                "PreferencesMonitor.listener = object : PreferencesMonitor.Listener",
            ),
        )
    }

    @Test
    fun `original song alias validation must call the top-level policy fully qualified`() {
        val owner = source("metadata/ApplePlaybackMetadataCoordinator.kt")
        assertTrue(
            "validatedOriginalSongAlias 成员必须全限定调用同名顶层策略函数",
            owner.contains("io.github.proify.lyricon.amprovider.xposed.validatedOriginalSongAlias("),
        )
        // 成员与 AppleMetadataPolicies.kt 的顶层策略函数同名同签名；成员体内出现
        // 无限定调用会按 Kotlin 作用域规则解析到自身，形成无限递归（StackOverflow）。
        val bareCall = Regex("(?<!\\.)(?<!fun )validatedOriginalSongAlias\\(")
        val offenders = bareCall.findAll(owner).count()
        assertTrue(
            "validatedOriginalSongAlias 在成员体内只能以全限定名调用，发现 $offenders 处无限定调用",
            offenders == 0,
        )
    }

    @Test
    fun `visible diagnostics framework metadata access keeps baseline call-time semantics`() {
        val metadataAssembly = source("AppleOrchestratorInAppMetadataAssembly.kt")
        assertTrue(
            "frameworkMetadataFn 必须与基线一致直接调用 frameworkMetadataHooks，" +
                "不得引入 isInitialized 守卫静默吞掉未初始化失败",
            !metadataAssembly.contains("::frameworkMetadataHooks.isInitialized"),
        )
    }
}
