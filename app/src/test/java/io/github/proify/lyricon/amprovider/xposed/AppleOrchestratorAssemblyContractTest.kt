/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R6 根编排按领域组装契约。
 *
 * 这些守卫固定 2026-09-10 迁移后的结构：根编排只保留组装结果与根级共享状态；
 * 34 个组件字段各自只有一个组装群所有者；领域组装与生命周期收尾保持原顺序；
 * 跨领域引用保持调用期 supplier；未新增会静默吞掉初始化失败的守卫。
 *
 * 组件本体需要 Android/Xposed 运行时，纯 JVM 单测无法实例化，因此这里校验
 * 源码结构契约；运行时行为（Hook 命中与页面刷新）仍由宿主实测负责。
 */
class AppleOrchestratorAssemblyContractTest {

    private fun source(relative: String): String =
        listOf(
            File("app/src/main/java/io/github/proify/lyricon/amprovider/xposed/$relative"),
            File("src/main/java/io/github/proify/lyricon/amprovider/xposed/$relative"),
        ).first(File::isFile).readText()

    private fun declaration(owner: String, name: String): Boolean =
        Regex("(lateinit )?var\\s+$name\\b").containsMatchIn(source(owner))

    @Test
    fun `root orchestrator keeps only assembly results and shared state`() {
        val root = source(ROOT_FILE)
        listOf("catalogLanguage", "lyricsPlayback", "inAppMetadata").forEach { assembly ->
            assertTrue(
                "根编排必须持有 $assembly 组装结果",
                root.contains("lateinit var $assembly: AppleOrchestrator"),
            )
        }
        assertTrue(
            "根级共享状态必须保留在根编排（覆盖存储/注册表/追踪序号）",
            root.contains("val metadataOverrideStore") &&
                root.contains("val inAppMetadataRegistry") &&
                root.contains("val metadataTraceSequence"),
        )
        COMPONENT_OWNERS.keys.forEach { component ->
            assertTrue(
                "$component 不应再声明在根编排",
                !Regex("(lateinit )?var\\s+$component\\b").containsMatchIn(root),
            )
        }
        assertTrue(
            "根编排应保持可读（当前 ${root.lines().size} 行）",
            root.lines().size <= 250,
        )
    }

    @Test
    fun `every migrated component has exactly one assembly owner`() {
        COMPONENT_OWNERS.forEach { (component, owner) ->
            val owners = ORCHESTRATOR_FILES.filter { candidate ->
                Regex("(lateinit )?var\\s+$component\\b").containsMatchIn(source(candidate))
            }
            assertEquals(
                "$component 的声明显式归属必须唯一",
                listOf(owner),
                owners,
            )
            assertTrue("$owner 未声明 $component", declaration(owner, component))
        }
    }

    @Test
    fun `domain assemblies and lifecycle finalization keep the original order`() {
        val root = source(ROOT_FILE)
        val order = listOf(
            "catalogLanguage = AppleOrchestratorCatalogLanguageAssembly(",
            "lyricsPlayback = AppleOrchestratorLyricsPlaybackAssembly(",
            "inAppMetadata = AppleOrchestratorInAppMetadataAssembly(",
            "initializeContentUiLanguage()",
            "lyricsPlayback.playbackHooks.initializeScreenStateMonitor()",
            "lyricsPlayback.initializeProvider()",
            "startHooks()",
        )
        val indices = order.map { marker ->
            assertTrue("根编排缺少 $marker", root.contains(marker))
            root.indexOf(marker)
        }
        assertEquals(
            "领域组装与生命周期收尾必须保持迁移前顺序",
            indices.sorted(),
            indices,
        )
        val metadata = source(METADATA_FILE)
        assertTrue(
            "元数据表面组装必须保持 表面 → 解析 → 应用/注册/覆盖 → 配置分发 的构造顺序",
            metadata.indexOf("librarySurfaceHooks = AppleLibrarySurfaceHooks(") in 0 until
                metadata.indexOf("metadataResolutionCoordinator = AppleInAppMetadataResolutionCoordinator(") &&
                metadata.indexOf("metadataResolutionCoordinator = AppleInAppMetadataResolutionCoordinator(") in 0 until
                metadata.indexOf("inAppMetadataApplier = AppleInAppMetadataApplier(") &&
                metadata.indexOf("inAppMetadataApplier = AppleInAppMetadataApplier(") in 0 until
                metadata.indexOf("metadataOverrideApplicationCoordinator =") &&
                metadata.indexOf("metadataOverrideApplicationCoordinator =") in 0 until
                metadata.indexOf("metadataConfigurationDispatcher = AppleMetadataConfigurationDispatcher("),
        )
    }

    @Test
    fun `cross domain references stay call time suppliers`() {
        val lyrics = source(LYRICS_FILE)
        val metadata = source(METADATA_FILE)
        assertTrue(
            "歌词/播放组装群对元数据表面只能持有延迟 provider",
            lyrics.contains(
                "inAppMetadataProvider: () -> AppleOrchestratorInAppMetadataAssembly",
            ) && lyrics.contains("private fun inAppMetadata()") &&
                !lyrics.contains("lateinit var inAppMetadata:"),
        )
        listOf(
            "activePlayerFn = {",
            "ensureContentItemMetadataHooksFn = {",
            "effectiveMetadataAliasFn = {",
            "applyPlaybackMetadataOverrideFn = {",
            "onCurrentPlaybackItemFn = {",
        ).forEach { supplier ->
            assertTrue("supplier 必须保留为调用期 lambda：$supplier", lyrics.contains(supplier))
        }
        assertTrue(
            "activePlayer 必须保留原 isInitialized 守卫语义",
            lyrics.contains("this::playbackHooks.isInitialized"),
        )
        listOf(
            "lyricsPlayback.playbackMetadataCoordinator.currentMetadataId()",
            "lyricsPlayback.lyricsHooks.isAppleLyricsRecyclerAdapter(adapter)",
            "lyricsPlayback.playbackHooks.activePlayer()",
        ).forEach { reference ->
            assertTrue(
                "元数据表面对歌词/播放的引用必须保持调用期解析：$reference",
                metadata.contains(reference),
            )
        }
        assertTrue(
            "resolution → override applier 的真实循环依赖必须保持调用期解析",
            metadata.contains("applyPlaybackMetadataOverrideFn = {") &&
                metadata.indexOf(
                    "metadataResolutionCoordinator = AppleInAppMetadataResolutionCoordinator(",
                ) < metadata.indexOf("metadataOverrideApplicationCoordinator ="),
        )
    }

    @Test
    fun `no new initialization guard swallows failures`() {
        val lyrics = source(LYRICS_FILE)
        val metadata = source(METADATA_FILE)
        val catalog = source(CATALOG_FILE)
        assertEquals(
            "歌词/播放组装群只保留迁移前的 9 处窄守卫（1 处 playbackHooks + 8 处 missingLyricsHooks）",
            9,
            Regex("isInitialized").findAll(lyrics).count(),
        )
        assertEquals(
            "目录/语言组装群只保留 1 处 catalogResolver 守卫（原两处合一）",
            1,
            Regex("isInitialized").findAll(catalog).count(),
        )
        assertEquals(
            "元数据表面组装群不得新增初始化守卫",
            0,
            Regex("isInitialized").findAll(metadata).count(),
        )
        assertTrue(
            "初始化失败必须继续向上抛出（保留 onFailure 复位 initialized）",
            source(ROOT_FILE).contains(".onFailure {") &&
                source(ROOT_FILE).contains("initialized.set(false)"),
        )
    }

    @Test
    fun `preference monitor wiring stays before component assignment`() {
        val lyrics = source(LYRICS_FILE)
        val listenerIndex =
            lyrics.indexOf("PreferencesMonitor.listener = object : PreferencesMonitor.Listener")
        val coordinatorIndex =
            lyrics.indexOf("playbackMetadataCoordinator = ApplePlaybackMetadataCoordinator(")
        assertTrue("PreferencesMonitor 监听器必须在歌词/播放组装群接线", listenerIndex >= 0)
        assertTrue("playbackMetadataCoordinator 必须在该组装群构造", coordinatorIndex >= 0)
        assertTrue(
            "监听器注册必须先于组件赋值，否则会漏掉早期偏好事件",
            listenerIndex < coordinatorIndex,
        )
    }

    private companion object {
        const val ROOT_FILE = "AppleMusicProviderOrchestrator.kt"
        const val CATALOG_FILE = "AppleOrchestratorCatalogLanguageAssembly.kt"
        const val LYRICS_FILE = "AppleOrchestratorLyricsPlaybackAssembly.kt"
        const val METADATA_FILE = "AppleOrchestratorInAppMetadataAssembly.kt"

        val ORCHESTRATOR_FILES = listOf(ROOT_FILE, CATALOG_FILE, LYRICS_FILE, METADATA_FILE)

        val COMPONENT_OWNERS = mapOf(
            // 目录/语言组装群
            "internalCatalogResolver" to CATALOG_FILE,
            "contentLocalizationHooks" to CATALOG_FILE,
            "contentUiLanguagePrefs" to CATALOG_FILE,
            "contentUiLanguagePreferenceListener" to CATALOG_FILE,
            // 歌词/播放组装群
            "playbackMetadataCoordinator" to LYRICS_FILE,
            "debugNetworkHooks" to LYRICS_FILE,
            "lyricsHooks" to LYRICS_FILE,
            "missingLyricsHooks" to LYRICS_FILE,
            "onlineSourceMenuHooks" to LYRICS_FILE,
            "playbackHooks" to LYRICS_FILE,
            "atmosphereVolumeDiagnostics" to LYRICS_FILE,
            "playbackMetadataHooks" to LYRICS_FILE,
            "directPlayer" to LYRICS_FILE,
            "lyricRequester" to LYRICS_FILE,
            // 元数据表面组装群
            "queueMetadataHooks" to METADATA_FILE,
            "listenNowHooks" to METADATA_FILE,
            "librarySurfaceHooks" to METADATA_FILE,
            "dataBindingHooks" to METADATA_FILE,
            "collectionSurfaceHooks" to METADATA_FILE,
            "artistSurfaceHooks" to METADATA_FILE,
            "metadataSurfaceRuntime" to METADATA_FILE,
            "inAppArtworkContinuityHooks" to METADATA_FILE,
            "actionSheetMetadataHooks" to METADATA_FILE,
            "playbackItemConversionHooks" to METADATA_FILE,
            "contentItemMetadataHooks" to METADATA_FILE,
            "mediaApiMetadataCoordinator" to METADATA_FILE,
            "metadataResolutionCoordinator" to METADATA_FILE,
            "frameworkMetadataHooks" to METADATA_FILE,
            "visibleMetadataDiagnostics" to METADATA_FILE,
            "media3MetadataCoordinator" to METADATA_FILE,
            "inAppMetadataApplier" to METADATA_FILE,
            "metadataRegistrationCoordinator" to METADATA_FILE,
            "metadataOverrideApplicationCoordinator" to METADATA_FILE,
            "metadataConfigurationDispatcher" to METADATA_FILE,
        )
    }
}
