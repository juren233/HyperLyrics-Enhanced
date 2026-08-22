package com.juren233.hyperlyricsenhanced.root

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.juren233.hyperlyricsenhanced.lyric.source.SourceManager
import com.juren233.hyperlyricsenhanced.root.island.FakeIslandTransitionHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandAlbumCoverStyleHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandMusicWaveColorHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandProgressGlowController
import com.juren233.hyperlyricsenhanced.root.island.IslandRuntimePreferenceOverrides
import com.juren233.hyperlyricsenhanced.root.island.IslandModuleRestoreHooker
import com.juren233.hyperlyricsenhanced.root.island.SystemUIHookRegistry
import com.juren233.hyperlyricsenhanced.root.island.IslandWidthHooker
import com.juren233.hyperlyricsenhanced.root.island.RealIslandHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaAmbientFlowHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaAodLyricHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaCoverStyleHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.island.IslandExpandedMediaAmbientFlowHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.background.MediaBackgroundRendererPool
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.lyricon.central.EmbeddedLyriconCentralController
import com.juren233.hyperlyricsenhanced.root.lyricon.provider.LyriconProviderControlFrameBridge
import com.juren233.hyperlyricsenhanced.root.salt.SaltPlayerNextTrackHooker
import com.juren233.hyperlyricsenhanced.root.source.LyriconSource
import com.juren233.hyperlyricsenhanced.root.source.LyricInfoSource
import com.juren233.hyperlyricsenhanced.root.source.RootLyricSink
import com.juren233.hyperlyricsenhanced.root.source.SuperLyricSource
import com.juren233.hyperlyricsenhanced.root.aitrans.AITranslator
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.common.PreferenceDiagnostics
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.media.NextTrackMetadataCache
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.online.utils.ChineseUtils
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPreferencePolicy
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRuntime
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderSystemMediaRuntime
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProvider
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

class HookEntry : XposedModule() {

    companion object {
        private const val STATE_RUNTIME_READY = "runtimeReady"
        private const val SYSTEM_MEDIA_PROVIDER_REFRESH_DELAY_MS = 250L

        @Volatile
        var activeMode = 0
        val lyriconSource = LyriconSource()
        val superLyricSource = SuperLyricSource()
        var lyricInfoSource: LyricInfoSource? = null
        var sourceManager: SourceManager? = null
            private set

        @JvmStatic
        var instance: HookEntry? = null
            private set

        private val SUPER_ISLAND_RUNTIME_REFRESH_KEYS = setOf(
            RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT,
            RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT,
            RootConstants.KEY_HOOK_ISLAND_LEFT_PADDING_LEFT,
            RootConstants.KEY_HOOK_ISLAND_LEFT_PADDING_RIGHT,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_LEFT,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_RIGHT,
            RootConstants.KEY_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH,
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.KEY_HOOK_ISLAND_FORCE_NEXT_SONG_AT_END,
            RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_DURATION,
            RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_STYLE,
            RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_POSITION,
            RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_WEIGHT,
            RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_GLOW,
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_GRADIENT,
            RootConstants.KEY_HOOK_TEXT_SIZE,
            RootConstants.KEY_HOOK_TEXT_SIZE_RATIO,
            RootConstants.KEY_HOOK_FONT_WEIGHT,
            RootConstants.KEY_HOOK_FONT_ITALIC,
            RootConstants.KEY_HOOK_FADING_EDGE_LENGTH,
            RootConstants.KEY_HOOK_GRADIENT_PROGRESS,
            RootConstants.KEY_HOOK_LYRIC_POSITION,
            RootConstants.KEY_HOOK_ISLAND_LEFT_LYRIC_POSITION,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_LYRIC_POSITION,
            RootConstants.KEY_HOOK_CENTER_LYRIC,
            RootConstants.KEY_HOOK_CENTER_GROUP_VOCALS,
            RootConstants.KEY_HOOK_ANIM_ENABLE,
            RootConstants.KEY_HOOK_ANIM_ID,
            RootConstants.KEY_HOOK_MARQUEE_MODE,
            RootConstants.KEY_HOOK_MARQUEE_SPEED,
            RootConstants.KEY_HOOK_MARQUEE_DELAY,
            RootConstants.KEY_HOOK_MARQUEE_LOOP_DELAY,
            RootConstants.KEY_HOOK_MARQUEE_INFINITE,
            RootConstants.KEY_HOOK_MARQUEE_STOP_END,
            RootConstants.KEY_HOOK_MARQUEE_METADATA_MODE,
            RootConstants.KEY_HOOK_MARQUEE_METADATA_SPEED,
            RootConstants.KEY_HOOK_MARQUEE_METADATA_DELAY,
            RootConstants.KEY_HOOK_MARQUEE_METADATA_LOOP_DELAY,
            RootConstants.KEY_HOOK_MARQUEE_METADATA_INFINITE,
            RootConstants.KEY_HOOK_SYLLABLE_RELATIVE,
            RootConstants.KEY_HOOK_SYLLABLE_HIGHLIGHT,
            RootConstants.KEY_HOOK_TRANSLATION_DISPLAY,
            RootConstants.KEY_HOOK_TRANSLATION_FALLBACK,
            RootConstants.KEY_HOOK_DISABLE_TRANSLATION,
            RootConstants.KEY_HOOK_TRANSLATION_ONLY,
            RootConstants.KEY_HOOK_SWAP_TRANSLATION,
            RootConstants.KEY_HOOK_NEXT_LYRIC_LINE,
            RootConstants.KEY_HOOK_AUTO_SWITCH_TRANSLATION,
            RootConstants.KEY_HOOK_ADJACENT_BACKGROUND_TRANSLATION,
            RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR,
            RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_GRADIENT,
            RootConstants.KEY_HOOK_CUSTOM_FONT_PATH,
            RootConstants.KEY_HOOK_NARROW_LATIN_FONT,
            RootConstants.KEY_HOOK_WORD_MOTION_ENABLED,
            RootConstants.KEY_HOOK_WORD_MOTION_CJK_LIFT,
            RootConstants.KEY_HOOK_WORD_MOTION_CJK_WAVE,
            RootConstants.KEY_HOOK_WORD_MOTION_LATIN_LIFT,
            RootConstants.KEY_HOOK_WORD_MOTION_LATIN_WAVE,
            RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND
        )
    }

    private var _prefs: android.content.SharedPreferences? = null
    private var prefListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var preferenceBroadcastReceiver: BroadcastReceiver? = null
    private var runtimeApp: Application? = null
    private var lyricsOnlyAfterHotReload = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingSystemMediaProviderRefresh: Runnable? = null

    val prefs: android.content.SharedPreferences
        get() {
            if (_prefs == null) {
                _prefs = getRemotePreferences(UIConstants.PREF_NAME)
            }
            return _prefs!!
        }

    internal fun moduleContext(): Context? {
        val app = runtimeApp ?: return null
        val info = runCatching { moduleApplicationInfo }.getOrNull() ?: return null
        return runCatching {
            app.createPackageContext(info.packageName, 0)
        }.getOrNull()
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        instance = this
        HookLogger.module = this
        runCatching {
            ChineseUtils.setModuleApkPath(moduleApplicationInfo.sourceDir)
        }.onFailure {
            HookLogger.e("HookEntry", "繁简转换字典路径初始化失败", it)
        }
        HookLogger.i("HookEntry", "模块加载完成，当前应用版本${com.juren233.hyperlyricsenhanced.BuildConfig.VERSION_NAME}-${com.juren233.hyperlyricsenhanced.BuildConfig.VERSION_CODE}")
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val state = Bundle().apply {
            putBoolean(STATE_RUNTIME_READY, runtimeApp != null)
        }
        param.setSavedInstanceState(state)
        IslandAlbumCoverStyleHooker.releaseAll()
        IslandExpandedMediaAmbientFlowHooker.releaseAll()
        NotificationMediaCoverStyleHooker.releaseAll()
        NotificationMediaAmbientFlowHooker.releaseAll()
        NotificationMediaAodLyricHooker.releaseAll()
        IslandProgressGlowController.clearAll()
        MediaBackgroundRendererPool.releaseAll()
        BaseIslandRenderer.clearAllViews()
        cleanupRuntime()
        HookLogger.i("HookEntry", "热重载准备完成")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        instance = this
        HookLogger.module = this
        NotificationMediaAodLyricHooker.initialize(this)
        lyricsOnlyAfterHotReload = true

        var replacedCount = 0
        var removedCount = 0
        param.oldHookHandles.forEach { handle ->
            val replacement = createLyricReplacementHooker(handle.executable)
            if (replacement != null) {
                runCatching {
                    handle.replaceHook(replacement)
                    replacedCount++
                }.onFailure {
                    handle.unhook()
                    removedCount++
                }
            } else {
                handle.unhook()
                removedCount++
            }
        }

        val state = param.savedInstanceState as? Bundle
        if (state?.getBoolean(STATE_RUNTIME_READY) == true) {
            findCurrentApplication()?.let { app ->
                Handler(Looper.getMainLooper()).post {
                    initializeSystemEnvironment(app)
                    BaseIslandRenderer.refreshActiveIsland()
                }
            }
                ?: HookLogger.w("HookEntry", "热重载运行时恢复延后: reason=application_unavailable")
        }
        HookLogger.i(
            "HookEntry",
            "热重载完成: replaced=$replacedCount removed=$removedCount media=restart_required"
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val processName = runCatching { android.app.Application.getProcessName() }.getOrNull() ?: ""
        val packageName = param.packageName

        // 普通目标仍只在主进程注入；官方 Provider 可精确声明必要的播放子进程。
        if (!OfficialProviderCatalog.shouldLoadIntoProcess(packageName, processName)) return

        if (packageName != "com.android.systemui" && packageName != "miui.systemui.plugin") {
            runCatching {
                LyriconProviderControlFrameBridge.install(
                    module = this,
                    classLoader = HookEntry::class.java.classLoader
                        ?: param.defaultClassLoader,
                )
            }.onFailure {
                HookLogger.e("HookEntry", "Lyricon 控制帧重连通道安装失败", it)
            }
        }
        
        if (packageName == "com.android.systemui") {
            NotificationMediaAodLyricHooker.hook(this, param.defaultClassLoader)
            if (!lyricsOnlyAfterHotReload) {
                IslandExpandedMediaAmbientFlowHooker.hook(this, param.defaultClassLoader)
                NotificationMediaAmbientFlowHooker.hook(this, param.defaultClassLoader)
                NotificationMediaCoverStyleHooker.hook(this, param.defaultClassLoader)
            }
            try {
                UnlockIslandWhitelist.hook(this, param.defaultClassLoader)
            } catch (e: Exception) {
                 if (e is ClassNotFoundException || e is NoSuchMethodException) {
                     HookLogger.w("HookEntry","此系统版本不支持超级岛下拉小窗白名单")
                 } else {
                     HookLogger.e("HookEntry", "超级岛下拉小窗白名单注入失败", e)
                 }
            }
            try {
                UnlockFocusWhitelist.hook(this, param.defaultClassLoader)
            } catch (e: Exception) {
                 if (e is ClassNotFoundException || e is NoSuchMethodException) {
                     HookLogger.w("HookEntry","此系统版本不支持解锁焦点通知白名单")
                 } else {
                     HookLogger.e("HookEntry", "焦点通知白名单注入失败", e)
                 }
            }

            val isSuperIslandEnabled = SystemUiEnhancementGate.isEnabled()
            
            if (!isSuperIslandEnabled) {
                HookLogger.i("HookEntry", "小米系统界面增强已禁用")
            }

            activeMode = prefs.getInt(RootConstants.KEY_HOOK_LYRIC_MODE, RootConstants.DEFAULT_HOOK_LYRIC_MODE)
            HookLogger.i("HookEntry", "超级岛歌词模式: mode=$activeMode")

            // 劫持 Application.onCreate 以初始化 Lyricon Receiver 所需的环境
            try {
                val appClass = param.defaultClassLoader.loadClass("android.app.Application")
                val onCreateMethod = appClass.getDeclaredMethod("onCreate")
                deoptimize(onCreateMethod)
                hook(onCreateMethod).intercept(AppCreateHooker())
                HookLogger.d("HookEntry", "安装生命周期 Hook: target=Application.onCreate")
            } catch (e: Exception) {
                if (e is ClassNotFoundException || e is NoSuchMethodException) {
                    HookLogger.w("HookEntry", "跳过生命周期 Hook: target=Application.onCreate")
                } else {
                    HookLogger.e("HookEntry", "安装生命周期 Hook 失败: target=Application.onCreate", e)
                }
            }

            // 核心：拦截 ClassLoader 构造，以捕捉 miui.systemui.plugin 等动态加载的插件
            try {
                val clClass = Class.forName("dalvik.system.BaseDexClassLoader")
                for (constructor in clClass.declaredConstructors) {
                    deoptimize(constructor)
                    hook(constructor).intercept(ClassLoaderHooker())
                }
                HookLogger.d("HookEntry", "安装插件加载 Hook: target=BaseDexClassLoader")
            } catch (e: Exception) {
                if (e is ClassNotFoundException || e is NoSuchMethodException) {
                    HookLogger.w("HookEntry", "跳过插件加载 Hook: target=BaseDexClassLoader")
                } else {
                    HookLogger.e("HookEntry", "安装插件加载 Hook 失败: target=BaseDexClassLoader", e)
                }
            }

        } else if (packageName == "miui.systemui.plugin") {
            SystemUIHookRegistry.hook(
                this,
                param.defaultClassLoader,
                lyricsOnly = lyricsOnlyAfterHotReload
            )
        } else if (packageName == "com.apple.android.music") {
            runCatching {
                AppleMusicProvider.install(this, param.defaultClassLoader)
            }.onFailure {
                HookLogger.e("HookEntry", "Apple Music 内置歌词提供器注入失败", it)
            }
        } else if (packageName == OfficialProviderCatalog.SALT_PLAYER_PACKAGE_NAME) {
            SaltPlayerNextTrackHooker.install(
                module = this,
                classLoader = param.defaultClassLoader,
                packageName = packageName,
                processName = processName,
            )
            OfficialProviderRuntime.installIfAvailable(
                module = this,
                targetClassLoader = param.defaultClassLoader,
                packageName = packageName,
                processName = processName,
            )
        } else {
            OfficialProviderRuntime.installIfAvailable(
                module = this,
                targetClassLoader = param.defaultClassLoader,
                packageName = packageName,
                processName = processName,
            )
        }
    }

    private fun initializeSystemEnvironment(app: Application) {
        try {
            cleanupRuntime()
            runtimeApp = app
            MediaMetadataHelper.setArtworkResolvedListener(BaseIslandRenderer::refreshActiveIsland)
            registerPreferenceBroadcastReceiver(app)

            PreferenceDiagnostics.logSnapshot("systemui_remote_init", prefs) { message ->
                HookLogger.i("PrefsDiagnostics", message)
            }

            val renderer = BaseIslandRenderer
            val sink = RootLyricSink(renderer, prefs)

            OfficialProviderPreferencePolicy.configure(prefs)
            val officialProviderPlayers = OfficialProviderCatalog.definitions
                .flatMapTo(linkedSetOf()) { definition -> definition.targetPackages }
            NextTrackMetadataCache.clearPlayers(officialProviderPlayers)
            EmbeddedLyriconCentralController.prepare(app)
            OfficialProviderSystemMediaRuntime.installIfAvailable(this, app)
            EmbeddedLyriconCentralController.onOfficialProviderPreferencesChanged(
                officialProviderPlayers,
            )
            lyriconSource.initialize(
                app = app,
                prefs = prefs,
                onCentralConnected = EmbeddedLyriconCentralController::onCentralConnected,
                onCentralConnectTimeout = {
                    EmbeddedLyriconCentralController.onSubscriberConnectTimeout(app)
                },
            )
            superLyricSource.initialize(app)
            lyricInfoSource = LyricInfoSource(app)

            AITranslator.init(app)

            sourceManager = SourceManager(
                sources = listOf(lyriconSource, superLyricSource, lyricInfoSource!!),
                prefs = prefs,
                sink = sink,
                prefKey = RootConstants.KEY_HOOK_LYRIC_SOURCE,
                defaultSourceId = RootConstants.DEFAULT_HOOK_LYRIC_SOURCE,
                stateResetter = LyriconDataBridge,
                logger = HookLogger
            )
            activeMode = prefs.getInt(
                RootConstants.KEY_HOOK_LYRIC_MODE,
                RootConstants.DEFAULT_HOOK_LYRIC_MODE
            )
            if (SystemUiEnhancementGate.isLyricRuntimeEnabled()) {
                sourceManager?.start()
            }
            SystemUiScreenStateMonitor.initialize(app)
            ClassicAodFocusNotificationRecovery.ensureListenerCanRecover(app, prefs)

            prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (com.juren233.hyperlyricsenhanced.BuildConfig.DEBUG && key != null) {
                    val value = runCatching { prefs.all[key] }.getOrNull()
                    HookLogger.i(
                        "PrefsDiagnostics",
                        "remote_change key=$key type=${PreferenceDiagnostics.typeName(value)} " +
                            "value=${PreferenceDiagnostics.formatValue(key, value)}",
                    )
                }
                val affectedOfficialProviderPlayers =
                    OfficialProviderPreferencePolicy.affectedPlayerPackages(key)
                if (affectedOfficialProviderPlayers.isNotEmpty()) {
                    NextTrackMetadataCache.clearPlayers(affectedOfficialProviderPlayers)
                    val affectsSystemMediaProvider = affectedOfficialProviderPlayers.any { packageName ->
                        OfficialProviderCatalog.definitionForPackage(packageName)
                            ?.systemMediaRuntime == true
                    }
                    if (affectsSystemMediaProvider) {
                        scheduleSystemMediaProviderRefresh(app)
                    }
                    EmbeddedLyriconCentralController.onOfficialProviderPreferencesChanged(
                        affectedOfficialProviderPlayers,
                    )
                    HookLogger.i(
                        "HookEntry",
                        "官方 Provider 配置已重评估: key=$key, " +
                            "players=${affectedOfficialProviderPlayers.sorted()}",
                    )
                }
                if (key?.startsWith(RootConstants.KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX) == true ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION ||
                    key == RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE ||
                    com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
                        .isSourcePreference(key) ||
                    com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
                        .isAppPreference(key) ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS
                ) {
                    lyriconSource.onPreferenceChanged(key)
                }
                when (key) {
                    RootConstants.KEY_HOOK_LYRIC_SOURCE -> {
                        val newSourceId = prefs.getString(key, RootConstants.DEFAULT_HOOK_LYRIC_SOURCE)
                            ?: RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
                        if (!SystemUiEnhancementGate.isLyricRuntimeEnabled()) {
                            return@OnSharedPreferenceChangeListener
                        }
                        HookLogger.i("HookEntry", "切换歌词源: source=$newSourceId")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            sourceManager?.switchSource(newSourceId)
                        }
                    }
                    RootConstants.KEY_HOOK_LYRIC_MODE -> {
                        val newMode = prefs.getInt(key, RootConstants.DEFAULT_HOOK_LYRIC_MODE)
                        if (newMode == activeMode) return@OnSharedPreferenceChangeListener
                        HookLogger.i("HookEntry", "切换歌词模式: mode=$newMode")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            activeMode = newMode
                            BaseIslandRenderer.refreshActiveIsland()
                        }
                    }
                    RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                    RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
                    RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            ClassicAodFocusNotificationRecovery.ensureListenerCanRecover(app, prefs)
                            updateFeatureRuntime()
                        }
                    }
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_SHOW_NEXT_LYRIC,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_LYRIC_STYLE,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_DUET_LYRICS,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_CENTER_NON_DUET_SONG,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_CENTER_GROUP_VOCALS,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_PAUSE_STYLE,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_DISPLAY,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_FALLBACK,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_SWAP_TRANSLATION,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_SONG_PREVIEW,
                    RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_SONG_PREVIEW_POSITION,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_SHOW_NEXT_LYRIC,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_LYRIC_STYLE,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_DUET_LYRICS,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_CENTER_NON_DUET_SONG,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_CENTER_GROUP_VOCALS,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_PAUSE_STYLE,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_DISPLAY,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_FALLBACK,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_SWAP_TRANSLATION,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_FORMAT,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_DISPLAY_STYLE,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_POSITION,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_SHOW_ICON,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_SONG_PREVIEW,
                    RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_SONG_PREVIEW_POSITION,
                    RootConstants.KEY_HOOK_REMOVE_CJK_LYRIC_SPACES -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            ClassicAodFocusNotificationRecovery.ensureListenerCanRecover(app, prefs)
                            NotificationMediaAodLyricHooker.refresh()
                            BaseIslandRenderer.refreshActiveIsland()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE,
                    RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            IslandAlbumCoverStyleHooker.refresh()
                            BaseIslandRenderer.refreshActiveIsland()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR,
                    RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            IslandAlbumCoverStyleHooker.refresh()
                            IslandMusicWaveColorHooker.refresh()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            IslandAlbumCoverStyleHooker.refresh()
                            IslandMusicWaveColorHooker.refresh()
                            BaseIslandRenderer.refreshActiveIsland()
                        }
                    }
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_CARD_THEME -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            NotificationMediaAmbientFlowHooker.refreshCardTheme()
                        }
                    }
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            NotificationMediaAmbientFlowHooker.refreshBackgroundStyle()
                        }
                    }
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_STYLE,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_BLUR,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_COLOR_ANIMATION,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_AUTO_INVERT,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_SOFT_COVER_TONE -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            NotificationMediaAmbientFlowHooker.refreshBackgroundStyle()
                        }
                    }
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_COVER_STYLE,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            NotificationMediaCoverStyleHooker.refresh()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            IslandExpandedMediaAmbientFlowHooker.refreshCardTheme()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_BLUR,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_COLOR_ANIMATION,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_AUTO_INVERT,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_SOFT_COVER_TONE -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            IslandExpandedMediaAmbientFlowHooker.refreshBackgroundStyle()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_COVER_STYLE,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_COVER_SOURCE,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_DEVICE_SWITCH -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            IslandExpandedMediaAmbientFlowHooker.refreshMediaElements()
                        }
                    }
                    in SUPER_ISLAND_RUNTIME_REFRESH_KEYS -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            BaseIslandRenderer.refreshActiveIsland()
                        }
                    }
                }
            }
            prefListener?.let {
                prefs.registerOnSharedPreferenceChangeListener(it)
            }

            HookLogger.i(
                "HookEntry",
                "系统环境初始化完成: superIsland=${SystemUiEnhancementGate.isEnabled()}, " +
                    "lyricRuntime=${SystemUiEnhancementGate.isLyricRuntimeEnabled()}, " +
                    "source=${sourceManager?.getActiveSource()?.displayName ?: "inactive"}, " +
                    "mode=$activeMode"
            )
        } catch (e: Exception) {
            HookLogger.e("HookEntry", "系统环境初始化失败", e)
        }
    }

    private fun updateFeatureRuntime() {
        val superIslandEnabled = SystemUiEnhancementGate.isEnabled()
        val lyricRuntimeEnabled = SystemUiEnhancementGate.isLyricRuntimeEnabled()
        if (lyricRuntimeEnabled) {
            sourceManager?.start()
        } else {
            sourceManager?.stop()
            AITranslator.cancelActiveRequests()
            LyriconDataBridge.clearState()
            BaseIslandRenderer.clearAllViews()
            IslandProgressGlowController.clearAll()
        }

        if (!superIslandEnabled) {
            BaseIslandRenderer.clearAllViews()
            IslandProgressGlowController.clearAll()
        }

        IslandAlbumCoverStyleHooker.refresh()
        IslandMusicWaveColorHooker.refresh()
        NotificationMediaAmbientFlowHooker.refreshBackgroundStyle()
        NotificationMediaAmbientFlowHooker.refreshCardTheme()
        NotificationMediaCoverStyleHooker.refresh()
        NotificationMediaAodLyricHooker.refresh()
        IslandExpandedMediaAmbientFlowHooker.refreshBackgroundStyle()
        IslandExpandedMediaAmbientFlowHooker.refreshCardTheme()
        IslandExpandedMediaAmbientFlowHooker.refreshMediaElements()

        if (superIslandEnabled) {
            BaseIslandRenderer.refreshActiveIsland()
        }
        HookLogger.i(
            "HookEntry",
            "更新功能运行状态: superIsland=$superIslandEnabled, lyricRuntime=$lyricRuntimeEnabled"
        )
    }

    private fun cleanupRuntime() {
        pendingSystemMediaProviderRefresh?.let(mainHandler::removeCallbacks)
        pendingSystemMediaProviderRefresh = null
        MediaMetadataHelper.clearArtworkResolution()
        OfficialProviderSystemMediaRuntime.releaseAll()
        IslandAlbumCoverStyleHooker.cleanup()
        IslandMusicWaveColorHooker.cleanup()
        SystemUiScreenStateMonitor.cleanup()
        prefListener?.let {
            runCatching { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        }
        prefListener = null
        preferenceBroadcastReceiver?.let { receiver ->
            runCatching { runtimeApp?.unregisterReceiver(receiver) }
        }
        preferenceBroadcastReceiver = null
        IslandRuntimePreferenceOverrides.clear()
        runCatching { sourceManager?.stop() }
        AITranslator.cancelActiveRequests()
        sourceManager = null
        lyricInfoSource = null
        runtimeApp = null
    }

    private fun registerPreferenceBroadcastReceiver(app: Application) {
        preferenceBroadcastReceiver?.let { receiver ->
            runCatching { app.unregisterReceiver(receiver) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != RootConstants.ACTION_REMOTE_PREFERENCE_CHANGED) return
                val expectedUid = runCatching {
                    context.packageManager
                        .getApplicationInfo("com.juren233.hyperlyricsenhanced", 0)
                        .uid
                }.getOrDefault(-1)
                val senderUid = runCatching { sentFromUid }.getOrDefault(-1)
                if (expectedUid >= 0 && senderUid >= 0 && senderUid != expectedUid) {
                    HookLogger.w(
                        "HookEntry",
                        "拒绝非 HyperLyrics 配置广播: senderUid=$senderUid expectedUid=$expectedUid"
                    )
                    return
                }
                if (intent.getStringExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_GROUP) != UIConstants.PREF_NAME) {
                    return
                }
                val key = intent.getStringExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_KEY) ?: return
                val type = intent.getStringExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE) ?: return
                val value: Any? = when (type) {
                    "clear" -> null
                    "boolean" -> intent.getBooleanExtra(
                        RootConstants.EXTRA_REMOTE_PREFERENCE_BOOLEAN,
                        false
                    )
                    "int" -> intent.getIntExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_INT, 0)
                    "long" -> intent.getLongExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_LONG, 0L)
                    "float" -> intent.getFloatExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_FLOAT, 0f)
                    "string" -> intent.getStringExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_STRING)
                    else -> return
                }
                IslandRuntimePreferenceOverrides.put(key, value)
                if (key == RootConstants.KEY_HOOK_LYRIC_MODE && value is Int) {
                    activeMode = value
                }
                BaseIslandRenderer.refreshActiveIsland()
                HookLogger.i(
                    "HookEntry",
                    "收到配置广播并更新运行时覆盖: key=$key, " +
                        "value=${PreferenceDiagnostics.formatValue(key, value)}"
                )
            }
        }
        app.registerReceiver(
            receiver,
            IntentFilter(RootConstants.ACTION_REMOTE_PREFERENCE_CHANGED),
            Context.RECEIVER_EXPORTED
        )
        preferenceBroadcastReceiver = receiver
    }

    private fun scheduleSystemMediaProviderRefresh(app: Application) {
        pendingSystemMediaProviderRefresh?.let(mainHandler::removeCallbacks)
        val refresh = Runnable {
            pendingSystemMediaProviderRefresh = null
            if (runtimeApp !== app) return@Runnable
            OfficialProviderSystemMediaRuntime.releaseAll()
            OfficialProviderSystemMediaRuntime.installIfAvailable(this, app)
        }
        pendingSystemMediaProviderRefresh = refresh
        mainHandler.postDelayed(refresh, SYSTEM_MEDIA_PROVIDER_REFRESH_DELAY_MS)
    }

    private fun findCurrentApplication(): Application? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThreadClass.getDeclaredMethod("currentApplication")
            currentApplication.invoke(null) as? Application
        }.getOrNull()
    }

    private fun createLyricReplacementHooker(executable: Executable): Hooker? {
        val owner = executable.declaringClass.name
        if (executable is Constructor<*> && owner == "dalvik.system.BaseDexClassLoader") {
            return ClassLoaderHooker()
        }
        if (executable !is Method) return null

        val name = executable.name
        UnlockFocusWhitelist.replacementHooker(executable)?.let { return it }
        return when {
            NotificationMediaAodLyricHooker.isTargetMethod(executable) ->
                NotificationMediaAodLyricHooker.hookerFor(executable)
            owner == "android.app.Application" && name == "onCreate" ->
                AppCreateHooker()
            name == "updateBigIslandView" ->
                RealIslandHooker.UpdateBigIslandViewHook()
            name == "calculateBigIslandWidth" ->
                IslandWidthHooker.CalculateWidthHook()
            name == "hideIslandLayout" || name == "showIslandLayout" ->
                RealIslandHooker.LayoutVisibilityHook(name)
            name == "onTrackingFakeViewStart" ->
                FakeIslandTransitionHooker.TrackingStartHook()
            name == "updateViewStateWhenOpenAnimStart" ->
                FakeIslandTransitionHooker.PrepareVisibleHook()
            owner.endsWith("DynamicIslandContentFakeView") && name == "setVisibility" ->
                FakeIslandTransitionHooker.VisibilityHook()
            owner.endsWith("IslandTemplateBuilder") && name == "updateModuleView" ->
                IslandModuleRestoreHooker.UpdateModuleViewHook()
            owner.endsWith("IslandModuleViewHolderAdapter") && name == "updateView" ->
                IslandModuleRestoreHooker.AdapterUpdateViewHook()
            else -> null
        }
    }

    /**
     * 动态类加载器劫持
     */
    inner class ClassLoaderHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val cl = chain.thisObject as? ClassLoader ?: return result
            try {
                NotificationMediaAodLyricHooker.hookAodPlugin(this@HookEntry, cl)
                SystemUIHookRegistry.hook(
                    this@HookEntry,
                    cl,
                    lyricsOnly = lyricsOnlyAfterHotReload
                )
            } catch (e: Exception) {
                if (e is ClassNotFoundException || e is NoSuchMethodException) {
                    // HookLogger.w("HookEntry","插件中未找到超级岛相关类")
                } else {
                    HookLogger.e("HookEntry", "注入超级岛插件失败", e)
                }
            }
            return result
        }
    }

    /**
     * Application 生命周期劫持
     */
    class AppCreateHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val app = chain.thisObject as? Application
            app?.let { instance?.initializeSystemEnvironment(it) }
            return chain.proceed()
        }
    }
}
