/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 按 Apple 播放页原生回调顺序刷新无歌词补充状态。
 *
 * 先重放媒体元数据回调：Apple 的 PlayerSongViewFragment 会在这里读取
 * ReturnToLyrics 标志、歌词按钮 selected 状态和 hasLyrics()，满足条件时自动调用
 * 原生歌词页切换；随后再刷新 DataBinding，覆盖按钮尚未重新绑定的冷启动时序。
 */
internal fun refreshMissingLyricsNowPlaying(
    mediaId: String?,
    refreshMetadataCallbacks: (String?) -> Unit,
    refreshPlaybackItemBindings: (String?) -> Unit,
) {
    refreshMetadataCallbacks(mediaId)
    refreshPlaybackItemBindings(mediaId)
}

/**
 * Apple Music 提供器根编排。
 *
 * R6 起只保留根安装、初始化幂等、应用生命周期引用、根级共享状态
 * （覆盖存储/注册表/追踪序号）与三个领域组装群：
 * [catalogLanguage]（目录与内容语言）、[lyricsPlayback]（歌词与播放）、
 * [inAppMetadata]（应用内元数据表面）。组件的构造与相互接线分别在组装群内部完成，
 * 需要调用期解析的 supplier 仍以 lambda 形式保留原解析时机。
 */
internal object AppleMusicProviderOrchestrator {
    internal const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    internal val initialized = AtomicBoolean(false)
    internal lateinit var runtime: AppleMusicProviderRuntime
    internal val application: Application
        get() = runtime.application
    internal val classLoader: ClassLoader
        get() = runtime.classLoader
    internal val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    internal val module: XposedModule
        get() = runtime.module
    internal val hookRegistrar
        get() = runtime.hookRegistrar
    internal val mainHandler
        get() = runtime.mainHandler

    /** 目录/语言组装群：目录解析器、内容语言偏好与本地化 Hook。 */
    internal lateinit var catalogLanguage: AppleOrchestratorCatalogLanguageAssembly

    /** 歌词/播放组装群：歌词补充、播放事件、在线来源菜单与提供器初始化。 */
    internal lateinit var lyricsPlayback: AppleOrchestratorLyricsPlaybackAssembly

    /** 元数据表面组装群：应用内元数据 Hook、表面运行时与覆盖协调器。 */
    internal lateinit var inAppMetadata: AppleOrchestratorInAppMetadataAssembly

    /** 根级共享状态：覆盖存储、应用内注册表与调试追踪序号。 */
    internal val metadataOverrideStore = AppleMetadataOverrideStore()
    internal val inAppMetadataRegistry = AppleInAppMetadataRegistry()
    internal val metadataTraceSequence = AtomicLong(0L)

    @Synchronized
    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (::runtime.isInitialized) {
            ProviderLogger.info("Apple Music 内置歌词提供器生命周期 Hook 已存在")
            return
        }
        runtime = AppleMusicProviderRuntime(module, classLoader)
        val onCreate = Application::class.java.getDeclaredMethod("onCreate")
        hookRegistrar.withModule("provider-lifecycle") {
            hookRegistrar.installHook(onCreate, after = { chain, _ ->
                (chain.thisObject as? Application)?.let(::onAppCreate)
            })
        }
        ProviderLogger.info("Apple Music 内置歌词提供器生命周期 Hook 已安装")
    }

    private fun onAppCreate(app: Application) {
        if (!initialized.compareAndSet(false, true)) return
        val appleMusicVersion = runCatching {
            val packageInfo = app.packageManager.getPackageInfo(APPLE_MUSIC_PACKAGE, 0)
            AppleMusicVersion(
                versionName = packageInfo.versionName,
                versionCode = packageInfo.longVersionCode,
            )
        }.getOrElse {
            AppleMusicVersion(versionName = null, versionCode = null)
        }
        val hookResolver = AppleMusicHookResolver(
            version = appleMusicVersion,
            application = app,
            nativeLibraryDir = module.getModuleApplicationInfo().nativeLibraryDir,
        )
        runtime.attach(app, hookResolver)
        ProviderLogger.info(
            "Apple Music Hook 版本档案已加载: app=${appleMusicVersion.displayName}, " +
                "profile=${hookResolver.profile?.id ?: "compatibility-fallback"}"
        )

        runCatching {
            DiskSongManager.initialize(application)
            // 1) 目录/语言：prefs 尚未注入（由 initializeContentUiLanguage 完成），
            //    但对组件只暴露调用期 supplier，不提前快照。
            catalogLanguage = AppleOrchestratorCatalogLanguageAssembly(
                application = application,
                classLoader = classLoader,
                hookResolver = hookResolver,
                runtime = runtime,
            ).also { it.assemble() }
            // 2) 歌词/播放：对元数据表面组装群只持有延迟 provider，保持调用期解析。
            lyricsPlayback = AppleOrchestratorLyricsPlaybackAssembly(
                runtime = runtime,
                catalogLanguage = catalogLanguage,
                metadataOverrideStore = metadataOverrideStore,
                inAppMetadataRegistry = inAppMetadataRegistry,
                inAppMetadataProvider = { inAppMetadata },
            ).also { it.assemble() }
            // 3) 元数据表面：构造顺序与迁移前一致（表面 → 解析 → 应用/注册/覆盖 → 配置分发）。
            inAppMetadata = AppleOrchestratorInAppMetadataAssembly(
                runtime = runtime,
                catalogLanguage = catalogLanguage,
                lyricsPlayback = lyricsPlayback,
                metadataOverrideStore = metadataOverrideStore,
                inAppMetadataRegistry = inAppMetadataRegistry,
                metadataTraceSequence = metadataTraceSequence,
            ).also { it.assemble() }
            // 4) 跨领域生命周期收尾：偏好监听、屏幕状态、远程播放器与 Hook 安装顺序。
            initializeContentUiLanguage()
            lyricsPlayback.playbackHooks.initializeScreenStateMonitor()
            lyricsPlayback.initializeProvider()
            startHooks()
            ProviderLogger.info("Apple Music 内置歌词提供器初始化完成")
        }.onFailure {
            initialized.set(false)
            ProviderLogger.error("Apple Music 内置歌词提供器初始化失败", it)
        }
    }
}
