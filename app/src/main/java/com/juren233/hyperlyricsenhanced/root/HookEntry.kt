package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import com.juren233.hyperlyricsenhanced.root.reload.MainThreadReload
import com.juren233.hyperlyricsenhanced.root.reload.ReloadSnapshot
import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHotReload
import android.os.Looper
import com.juren233.hyperlyricsenhanced.lyric.source.SourceManager
import com.juren233.hyperlyricsenhanced.root.island.FakeIslandTransitionHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandAlbumCoverStyleHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandViewRegistry
import com.juren233.hyperlyricsenhanced.root.island.IslandMusicWaveColorHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandProgressGlowController
import com.juren233.hyperlyricsenhanced.root.island.IslandRuntimePreferenceOverrides
import com.juren233.hyperlyricsenhanced.root.island.IslandRuntimePreferenceReader
import com.juren233.hyperlyricsenhanced.root.island.IslandModuleRestoreHooker
import com.juren233.hyperlyricsenhanced.root.island.SystemUIHookRegistry
import com.juren233.hyperlyricsenhanced.root.island.IslandWidthHooker
import com.juren233.hyperlyricsenhanced.root.island.RealIslandHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaAmbientFlowHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.AodEnvironmentDiagnostics
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaAodLyricHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaCoverStyleHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.island.IslandExpandedMediaAmbientFlowHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.island.refreshBackgroundStyle
import com.juren233.hyperlyricsenhanced.root.mediacard.island.refreshCardTheme
import com.juren233.hyperlyricsenhanced.root.mediacard.island.refreshMediaElements
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.background.MediaBackgroundRendererPool
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.lyricon.central.EmbeddedLyriconCentralController
import com.juren233.hyperlyricsenhanced.root.lyricon.provider.LyriconProviderControlFrameBridge
import com.juren233.hyperlyricsenhanced.root.salt.SaltPlayerNextTrackHooker
import com.juren233.hyperlyricsenhanced.root.settings.SettingsEntryHooker
import com.juren233.hyperlyricsenhanced.root.source.LyriconBridgeTrafficObserver
import com.juren233.hyperlyricsenhanced.root.source.LyriconSource
import com.juren233.hyperlyricsenhanced.root.source.onActiveMediaSessionSnapshotChanged
import com.juren233.hyperlyricsenhanced.root.source.onPreferenceChanged
import com.juren233.hyperlyricsenhanced.root.source.LyricInfoSource
import com.juren233.hyperlyricsenhanced.root.source.RootLyricSink
import com.juren233.hyperlyricsenhanced.root.source.SuperLyricSource
import com.juren233.hyperlyricsenhanced.root.timeline.LocalTimelineDriver
import com.juren233.hyperlyricsenhanced.root.timeline.SystemMediaPlaybackAnchor
import com.juren233.hyperlyricsenhanced.root.aitrans.AITranslator
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.LyricRuntimeDiagnostics
import com.juren233.hyperlyricsenhanced.root.utils.IslandSystemFontWeight
import com.juren233.hyperlyricsenhanced.root.utils.RuntimePerfDiagnostics
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanup
import com.juren233.hyperlyricsenhanced.common.PreferenceDiagnostics
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.IslandFontWeightMode
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

        private val HYPER_ISLAND_RUNTIME_REFRESH_KEYS = setOf(
            RootConstants.KEY_HOOK_ISLAND_SHORT_LYRIC_SONG_INFO,
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
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_COLOR_MODE,
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_CUSTOM_COLOR,
            RootConstants.KEY_HOOK_TEXT_SIZE,
            RootConstants.KEY_HOOK_TEXT_SIZE_RATIO,
            RootConstants.KEY_HOOK_FONT_WEIGHT,
            RootConstants.KEY_HOOK_FONT_WEIGHT_MODE,
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
            RootConstants.KEY_HOOK_SWITCH_ANIM_RATE,
            RootConstants.KEY_HOOK_SWITCH_ANIM_CUSTOM_RATE,
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
            RootConstants.KEY_HOOK_ISLAND_NEXT_LINE_MODE,
            RootConstants.KEY_HOOK_AUTO_SWITCH_TRANSLATION,
            RootConstants.KEY_HOOK_ADJACENT_BACKGROUND_TRANSLATION,
            RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR,
            RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_GRADIENT,
            RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR_ENABLED,
            RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR,
            RootConstants.KEY_HOOK_MONET_TEXT_COLOR,
            RootConstants.KEY_HOOK_STATUS_BAR_TEXT_COLOR,
            RootConstants.KEY_HOOK_CUSTOM_FONT_PATH,
            RootConstants.KEY_HOOK_NARROW_LATIN_FONT,
            RootConstants.KEY_HOOK_WORD_MOTION_ENABLED,
            RootConstants.KEY_HOOK_WORD_MOTION_CJK_LIFT,
            RootConstants.KEY_HOOK_WORD_MOTION_CJK_WAVE,
            RootConstants.KEY_HOOK_WORD_MOTION_LATIN_LIFT,
            RootConstants.KEY_HOOK_WORD_MOTION_LATIN_WAVE,
            RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND
        )
    }

    private var _prefs: android.content.SharedPreferences? = null
    private var prefListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var preferenceBroadcastReceiver: BroadcastReceiver? = null
    private var runtimeApp: Application? = null
    @Volatile private var runtimeReady = false
    @Volatile private var runtimeEpoch = 0L
    private var startupStage = "waiting"
    private var lyricRuntimeMode: SystemUiLyricRuntimeMode? = null
    internal var systemUiClassLoader: ClassLoader? = null
        private set
    private var playbackAnchor: SystemMediaPlaybackAnchor? = null
    private var localTimelineDriver: LocalTimelineDriver? = null
    private var lyricsOnlyAfterHotReload = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingSystemMediaProviderRefresh: Runnable? = null
    private val runtimeBootstrap by lazy {
        SystemUiRuntimeBootstrap(
            post = { task, delay -> mainHandler.postDelayed(task, delay); Unit },
            remove = mainHandler::removeCallbacks,
            findApplication = ::findCurrentApplication,
            isUsableApplication = { app: Application ->
                val attached = app.baseContext != null
                val packageName = if (attached) app.packageName else null
                val usable = attached && packageName == "com.android.systemui"
                if (!usable) LyricRuntimeDiagnostics.record("runtime_application_rejected") {
                    "reason=${if (!attached) "base_context_unavailable" else "wrong_package"} " +
                        "application=${app.javaClass.name} package=$packageName"
                }
                usable
            },
            initialize = ::initializeSystemEnvironment,
            isRuntimeReady = { runtimeReady },
            rollback = { cleanupRuntime() },
            report = { state, trigger, attempt, failure ->
                LyricRuntimeDiagnostics.record("runtime_bootstrap_$state") {
                    "trigger=$trigger attempt=$attempt startupStage=$startupStage"
                }
                if (failure != null) HookLogger.e(
                    "HookEntry",
                    "系统环境启动: state=$state trigger=$trigger attempt=$attempt stage=$startupStage",
                    failure,
                )
                else if (state == "application_unavailable") HookLogger.w(
                    "HookEntry", "系统环境启动等待超时: reason=application_unavailable",
                )
            },
        )
    }

    private fun armRuntimeBootstrap() {
        // Always post, even from the UI thread: don't initialize inside the host's load callback.
        mainHandler.post {
            if (!SystemUiHookLifetime.retired) runtimeBootstrap.arm()
        }
    }

    private fun signalRuntimeApplication(app: Application) {
        mainHandler.post {
            if (!SystemUiHookLifetime.retired) runtimeBootstrap.signal(app, "application_on_create")
        }
    }

    private inline fun optionalRuntime(capability: String, cleanup: () -> Unit = {}, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            runCatching(cleanup).onFailure(failure::addSuppressed)
            runCatching {
                HookLogger.w("HookEntry", "可选能力初始化失败: capability=$capability", failure)
            }
        }
    }

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

    internal fun moduleApkSourceDir(): String? =
        runCatching { moduleApplicationInfo.sourceDir }.getOrNull()

    /** 宿主（如 SystemUI）进程的 Application 上下文，供需要宿主身份的媒体/视图查询使用。 */
    internal fun runtimeAppContext(): Context? = runtimeApp

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
        LyricRuntimeDiagnostics.record("module_loaded") {
            "version=${com.juren233.hyperlyricsenhanced.BuildConfig.VERSION_NAME} " +
                "versionCode=${com.juren233.hyperlyricsenhanced.BuildConfig.VERSION_CODE}"
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val app = runtimeApp
        if (app == null || !runtimeReady || app.packageName != "com.android.systemui" || lyricsOnlyAfterHotReload) {
            HookLogger.i("HookEntry", "当前宿主尚不具备完整热重载状态，需要重启对应进程")
            return false
        }
        val state = try {
            MainThreadReload.run { SystemUiHotReload.prepare(this, app) }
        } catch (failure: Throwable) {
            LyricRuntimeDiagnostics.record("hot_reload_prepare_failed") { "restartRequired=true" }
            HookLogger.e("HookEntry", "热重载清理失败，已尝试释放全部资源，需要重启系统界面", failure)
            return false
        } ?: run {
            HookLogger.w("HookEntry", "主线程未及时接受热重载，已取消且保留当前运行状态")
            return false
        }
        param.setSavedInstanceState(state)
        HookLogger.i("HookEntry", "系统界面热重载准备完成")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val snapshot = ReloadSnapshot.read(param.savedInstanceState)
        if (snapshot != null) {
            onModuleLoaded(param)
            check(MainThreadReload.run {
                SystemUiHotReload.restore(this, snapshot, param.oldHookHandles)
                true
            } == true) { "SystemUI main thread did not accept hot reload restoration" }
            return
        }
        // One-time migration from older APKs: they saved only a Boolean and discarded host
        // objects. Keep their limited behavior until the user restarts SystemUI once.
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
            check(MainThreadReload.run {
                val app = checkNotNull(findCurrentApplication()) {
                    "SystemUI Application unavailable during legacy hot reload"
                }
                initializeAfterHotReload(app)
                BaseIslandRenderer.refreshActiveIsland()
                true
            } == true) { "SystemUI main thread did not accept legacy hot reload restoration" }
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

        LyricRuntimeDiagnostics.record("package_loaded") {
            "package=$packageName process=$processName"
        }

        // 设置进程只需要首页入口注入，不装 Lyricon 控制帧重连通道。
        if (packageName != "com.android.systemui" && packageName != "miui.systemui.plugin" &&
            packageName != "com.android.settings"
        ) {
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
            installSystemUiHooks(param.defaultClassLoader)

        } else if (packageName == "miui.systemui.plugin") {
            SystemUIHookRegistry.hook(
                this,
                param.defaultClassLoader,
                lyricsOnly = lyricsOnlyAfterHotReload
            )
            // NotificationSettingsManager 在插件 dex 中，主类加载器加载不到，
            // 白名单 Hook 必须借插件类加载器安装。
            UnlockIslandWhitelist.doHookInClassLoader(param.defaultClassLoader)
            UnlockFocusWhitelist.doHookInClassLoader(param.defaultClassLoader)
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
        } else if (packageName == "com.android.settings") {
            SettingsEntryHooker.install(this, param.defaultClassLoader)
        } else {
            OfficialProviderRuntime.installIfAvailable(
                module = this,
                targetClassLoader = param.defaultClassLoader,
                packageName = packageName,
                processName = processName,
            )
        }
    }

    internal fun installSystemUiHooks(classLoader: ClassLoader) {
        systemUiClassLoader = classLoader
        armRuntimeBootstrap()
        // 劫持 Application.onCreate 以初始化 Lyricon Receiver 所需的环境
        LyricRuntimeDiagnostics.record("application_hook_installing")
        try {
            val appClass = classLoader.loadClass("android.app.Application")
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            deoptimize(onCreateMethod)
            hook(onCreateMethod).intercept(AppCreateHooker())
            LyricRuntimeDiagnostics.record("application_hook_installed")
            HookLogger.d("HookEntry", "安装生命周期 Hook: target=Application.onCreate")
        } catch (e: Exception) {
            if (e is ClassNotFoundException || e is NoSuchMethodException) {
                HookLogger.w("HookEntry", "跳过生命周期 Hook: target=Application.onCreate")
            } else {
                HookLogger.e("HookEntry", "安装生命周期 Hook 失败: target=Application.onCreate", e)
            }
        }

        optionalRuntime("media_output") {
            com.juren233.hyperlyricsenhanced.root.island.touch.IslandMediaOutput.initialize(this, classLoader)
        }
        optionalRuntime("aod_hook") {
            NotificationMediaAodLyricHooker.hook(this, classLoader)
        }
        optionalRuntime("expanded_media_hook") {
            IslandExpandedMediaAmbientFlowHooker.hook(this, classLoader)
        }
        optionalRuntime("notification_ambient_hook") {
            NotificationMediaAmbientFlowHooker.hook(this, classLoader)
        }
        optionalRuntime("notification_cover_hook") {
            NotificationMediaCoverStyleHooker.hook(this, classLoader)
        }
        try {
            UnlockIslandWhitelist.hook(this, classLoader)
        } catch (e: Exception) {
             if (e is ClassNotFoundException || e is NoSuchMethodException) {
                 HookLogger.w("HookEntry","此系统版本不支持超级岛下拉小窗白名单")
             } else {
                 HookLogger.e("HookEntry", "超级岛下拉小窗白名单注入失败", e)
             }
        }
        try {
            UnlockFocusWhitelist.hook(this, classLoader)
        } catch (e: Exception) {
             if (e is ClassNotFoundException || e is NoSuchMethodException) {
                 HookLogger.w("HookEntry","此系统版本不支持解锁焦点通知白名单")
             } else {
                 HookLogger.e("HookEntry", "焦点通知白名单注入失败", e)
             }
        }

        optionalRuntime("status_bar_color") {
            com.juren233.hyperlyricsenhanced.root.island.IslandStatusBarColorMonitor.install(this, classLoader)
        }
        optionalRuntime("status_bar_space") {
            com.juren233.hyperlyricsenhanced.root.island.IslandStatusBarSpaceMonitor.install(this, classLoader)
        }

        optionalRuntime("initial_mode_diagnostics") {
            val isHyperIslandEnabled = SystemUiEnhancementGate.isEnabled()

            if (!isHyperIslandEnabled) {
                HookLogger.i("HookEntry", "小米系统界面增强已禁用")
            }

            activeMode = prefs.getInt(RootConstants.KEY_HOOK_LYRIC_MODE, RootConstants.DEFAULT_HOOK_LYRIC_MODE)
            HookLogger.i("HookEntry", "超级岛歌词模式: mode=$activeMode")
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

    }

    internal fun initializeAfterHotReload(app: Application) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Runtime restoration requires main thread" }
        check(!SystemUiHookLifetime.retired && runtimeBootstrap.initializeNow(app) && runtimeReady) {
            "SystemUI lyric runtime could not be restored"
        }
    }

    internal fun cleanupForHotReload() {
        runtimeBootstrap.retire()
        SystemUiHookLifetime.retired = true
        try {
            cleanupRuntime(forHotReload = true)
        } finally {
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun configureEarlyNextLinePreview() {
        LyriconDataBridge.configureEarlyNextLinePreview(
            prefs.getInt(
                RootConstants.KEY_HOOK_EARLY_NEXT_LINE_PREVIEW,
                RootConstants.DEFAULT_HOOK_EARLY_NEXT_LINE_PREVIEW,
            ),
            prefs.getInt(
                RootConstants.KEY_HOOK_EARLY_NEXT_LINE_PREVIEW_CUSTOM_MS,
                RootConstants.DEFAULT_HOOK_EARLY_NEXT_LINE_PREVIEW_CUSTOM_MS,
            ),
        )
    }

    private fun initializeSystemEnvironment(app: Application) {
        LyricRuntimeDiagnostics.record("runtime_init_started") {
            "application=${app.javaClass.name} package=${app.packageName}"
        }
        try {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Runtime startup requires main thread" }
            check(!SystemUiHookLifetime.retired) { "Runtime generation is retired" }
            runtimeEpoch++
            runtimeApp = app
            startupStage = "preferences"
            optionalRuntime("bridge_traffic", { LyriconBridgeTrafficObserver.stop() }) {
                LyriconBridgeTrafficObserver.startOnce(app)
            }
            optionalRuntime("font_weight", { IslandSystemFontWeight.stop() }) {
                IslandSystemFontWeight.start(app) {
                    if (IslandRuntimePreferenceReader.getFontWeightMode(prefs) ==
                        IslandFontWeightMode.SYSTEM
                    ) BaseIslandRenderer.refreshActiveIsland()
                }
            }
            MediaMetadataHelper.setArtworkResolvedListener(BaseIslandRenderer::refreshActiveIsland)
            registerPreferenceBroadcastReceiver(app)

            optionalRuntime("preference_diagnostics") {
                PreferenceDiagnostics.logSnapshot("systemui_remote_init", prefs) { message ->
                    HookLogger.i("PrefsDiagnostics", message)
                }
            }
            LyricRuntimeDiagnostics.record("runtime_preferences_ready")

            configureEarlyNextLinePreview()
            val renderer = BaseIslandRenderer
            val sink = RootLyricSink(renderer, prefs)

            OfficialProviderPreferencePolicy.configure(prefs)
            val officialProviderPlayers = OfficialProviderCatalog.definitions
                .flatMapTo(linkedSetOf()) { definition -> definition.targetPackages }
            NextTrackMetadataCache.clearPlayers(officialProviderPlayers)
            startupStage = "sources"
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
            LyricRuntimeDiagnostics.record("runtime_sources_initialized")

            // SystemUI 唯一时间轴。来源只提交歌词内容；媒体锚点负责播放状态、位置与滚动。
            startupStage = "timeline"
            val anchor = SystemMediaPlaybackAnchor(app)
            playbackAnchor = anchor
            val driver = LocalTimelineDriver(anchor, sink)
            localTimelineDriver = driver
            driver.start()
            LyricRuntimeDiagnostics.record("runtime_timeline_started")

            optionalRuntime("ai_cache") { AITranslator.init(app) }

            startupStage = "connections"
            sourceManager = SourceManager(
                sources = listOf(lyriconSource, superLyricSource, lyricInfoSource!!),
                prefs = prefs,
                sink = driver,
                prefKey = RootConstants.KEY_HOOK_LYRIC_SOURCE,
                defaultSourceId = RootConstants.DEFAULT_HOOK_LYRIC_SOURCE,
                logger = HookLogger
            )
            activeMode = prefs.getInt(
                RootConstants.KEY_HOOK_LYRIC_MODE,
                RootConstants.DEFAULT_HOOK_LYRIC_MODE
            )
            updateLyricRuntimeConnections()
            LyricRuntimeDiagnostics.record("runtime_connections_updated")
            optionalRuntime("screen_monitor", { SystemUiScreenStateMonitor.cleanup() }) {
                SystemUiScreenStateMonitor.initialize(app)
            }
            // debug 包专用：性能/功耗采样（CPU、线程、电池、岛帧耗时），release 为空操作。
            optionalRuntime("performance", { RuntimePerfDiagnostics.stopForReload() }) {
                RuntimePerfDiagnostics.start(
                    app = app,
                    scope = "systemui",
                    stateProvider = {
                        "mode=$activeMode," +
                            "playing=${LyriconDataBridge.currentPlaybackState}," +
                            "pkg=${LyriconDataBridge.currentLyricPackageName ?: LyriconDataBridge.activePackageName}," +
                            "islandViews=${IslandViewRegistry.snapshotAttached().size}"
                    },
                    frameViewProvider = { IslandViewRegistry.snapshotAttached().map { it.first } },
                )
            }
            optionalRuntime("aod_diagnostics") {
                AodEnvironmentDiagnostics.log(
                    context = app,
                    stage = "systemui_init",
                    modulePrefs = prefs,
                )
            }
            optionalRuntime("aod_recovery") {
                ClassicAodFocusNotificationRecovery.ensureListenerCanRecover(app, prefs)
            }

            startupStage = "preference_listener"
            val listenerEpoch = runtimeEpoch
            prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (SystemUiHookLifetime.retired || listenerEpoch != runtimeEpoch) return@OnSharedPreferenceChangeListener
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
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_CONTENT ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN ||
                    key == RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS
                ) {
                    lyriconSource.onPreferenceChanged(key)
                }
                when (key) {
                    RootConstants.KEY_ACTIVE_MEDIA_SESSION_PACKAGES -> {
                        lyriconSource.onActiveMediaSessionSnapshotChanged(
                            prefs.getString(key, null),
                            reason = "remote_prefs_changed",
                        )
                    }
                    RootConstants.KEY_HOOK_LYRIC_SOURCE -> {
                        val newSourceId = prefs.getString(key, RootConstants.DEFAULT_HOOK_LYRIC_SOURCE)
                            ?: RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
                        if (!SystemUiEnhancementGate.isLyricRuntimeEnabled()) {
                            return@OnSharedPreferenceChangeListener
                        }
                        HookLogger.i("HookEntry", "切换歌词源: source=$newSourceId")
                        postRuntimeUpdate {
                            updateLyricRuntimeConnections()
                        }
                    }
                    RootConstants.KEY_HOOK_LYRIC_MODE -> {
                        val newMode = prefs.getInt(key, RootConstants.DEFAULT_HOOK_LYRIC_MODE)
                        if (newMode == activeMode) return@OnSharedPreferenceChangeListener
                        HookLogger.i("HookEntry", "切换歌词模式: mode=$newMode")
                        postRuntimeUpdate {
                            activeMode = newMode
                            BaseIslandRenderer.refreshActiveIsland()
                        }
                    }
                    RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND,
                    RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
                    RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND,
                    RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
                    RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
                    RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS -> {
                        postRuntimeUpdate {
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
                    RootConstants.KEY_HOOK_EARLY_NEXT_LINE_PREVIEW,
                    RootConstants.KEY_HOOK_EARLY_NEXT_LINE_PREVIEW_CUSTOM_MS,
                    RootConstants.KEY_HOOK_REMOVE_CJK_LYRIC_SPACES -> {
                        postRuntimeUpdate {
                            if (key == RootConstants.KEY_HOOK_EARLY_NEXT_LINE_PREVIEW ||
                                key == RootConstants.KEY_HOOK_EARLY_NEXT_LINE_PREVIEW_CUSTOM_MS
                            ) {
                                configureEarlyNextLinePreview()
                                LyriconDataBridge.updateEstimatedPosition(
                                    LyriconDataBridge.estimatedPosition() ?: LyriconDataBridge.currentPosition
                                )
                            }
                            ClassicAodFocusNotificationRecovery.ensureListenerCanRecover(app, prefs)
                            NotificationMediaAodLyricHooker.refresh()
                            BaseIslandRenderer.refreshActiveIsland()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE,
                    RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST,
                    RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM -> {
                        postRuntimeUpdate {
                            IslandAlbumCoverStyleHooker.refresh()
                            BaseIslandRenderer.refreshActiveIsland()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR,
                    RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
                    RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR_MODE -> {
                        postRuntimeUpdate {
                            // 律动颜色与封面样式无关；连带刷新会重走 setFixIcon 重绘封面，造成封面闪烁
                            IslandMusicWaveColorHooker.refresh()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON -> {
                        postRuntimeUpdate {
                            IslandAlbumCoverStyleHooker.refresh()
                            IslandMusicWaveColorHooker.refresh()
                            BaseIslandRenderer.refreshActiveIsland()
                        }
                    }
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_CARD_THEME -> {
                        postRuntimeUpdate {
                            NotificationMediaAmbientFlowHooker.refreshCardTheme()
                        }
                    }
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE -> {
                        postRuntimeUpdate {
                            NotificationMediaAmbientFlowHooker.refreshBackgroundStyle()
                        }
                    }
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_PAUSE_RESTORE_DEFAULT -> {
                        postRuntimeUpdate {
                            NotificationMediaAmbientFlowHooker.refreshAmbientFlow()
                        }
                    }
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_STYLE,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_BLUR,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_COLOR_ANIMATION,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_AUTO_INVERT,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_SOFT_COVER_TONE -> {
                        postRuntimeUpdate {
                            NotificationMediaAmbientFlowHooker.refreshBackgroundStyle()
                        }
                    }
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_COVER_STYLE,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE,
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH -> {
                        postRuntimeUpdate {
                            NotificationMediaCoverStyleHooker.refresh()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE -> {
                        postRuntimeUpdate {
                            IslandExpandedMediaAmbientFlowHooker.refreshCardTheme()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_BLUR,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_COLOR_ANIMATION,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_AUTO_INVERT,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_SOFT_COVER_TONE -> {
                        postRuntimeUpdate {
                            IslandExpandedMediaAmbientFlowHooker.refreshBackgroundStyle()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_COVER_STYLE,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_COVER_SOURCE,
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_DEVICE_SWITCH -> {
                        postRuntimeUpdate {
                            IslandExpandedMediaAmbientFlowHooker.refreshMediaElements()
                        }
                    }
                    RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT,
                    RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH,
                    RootConstants.KEY_HOOK_ISLAND_DUET_FIXED_LENGTH -> {
                        postRuntimeUpdate {
                            BaseIslandRenderer.refreshDynamicWidth()
                        }
                    }
                    in HYPER_ISLAND_RUNTIME_REFRESH_KEYS -> {
                        postRuntimeUpdate {
                            BaseIslandRenderer.refreshActiveIsland()
                        }
                    }
                }
            }
            prefListener?.let {
                prefs.registerOnSharedPreferenceChangeListener(it)
            }

            runtimeReady = true
            startupStage = "ready"
            LyricRuntimeDiagnostics.record("runtime_init_completed")
            optionalRuntime("completion_diagnostics") {
                HookLogger.i(
                    "HookEntry",
                    "系统环境初始化完成: hyperIsland=${SystemUiEnhancementGate.isEnabled()}, " +
                        "lyricRuntime=${SystemUiEnhancementGate.isLyricRuntimeEnabled()}, " +
                        "source=${sourceManager?.getActiveSource()?.displayName ?: "inactive"}, " +
                        "mode=$activeMode"
                )
            }
        } catch (failure: Throwable) {
            runtimeReady = false
            LyricRuntimeDiagnostics.record("runtime_init_failed") {
                "startupStage=$startupStage error=${failure.javaClass.name}"
            }
            // The coordinator owns rollback and the bounded retry budget.
            throw failure
        }
    }

    private fun updateLyricRuntimeConnections() {
        val app = runtimeApp ?: return
        val manager = sourceManager ?: return
        val mode = SystemUiEnhancementGate.lyricRuntimeMode()
        LyricRuntimeDiagnostics.record("runtime_mode_applying") {
            "previous=$lyricRuntimeMode requested=$mode centralRequired=${mode.requiresCentral}"
        }
        if (lyricRuntimeMode != mode) {
            manager.stop()
            lyriconSource.configureCentralSubscription(mode.requiresCentral)
            EmbeddedLyriconCentralController.prepare(app, enabled = mode.requiresCentral)
            if (mode.requiresCentral) {
                OfficialProviderSystemMediaRuntime.installIfAvailable(this, app)
            } else {
                pendingSystemMediaProviderRefresh?.let(mainHandler::removeCallbacks)
                pendingSystemMediaProviderRefresh = null
                OfficialProviderSystemMediaRuntime.releaseAll()
            }
            lyricRuntimeMode = mode
            HookLogger.i("HookEntry", "歌词运行连接模式: $mode")
        }
        val selected = prefs.getString(
            RootConstants.KEY_HOOK_LYRIC_SOURCE,
            RootConstants.DEFAULT_HOOK_LYRIC_SOURCE,
        ) ?: RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
        mode.sourceId(selected)?.let { sourceId ->
            if (manager.getActiveSource()?.id != sourceId) manager.switchSource(sourceId)
        }
    }

    private fun updateFeatureRuntime() {
        val hyperIslandEnabled = SystemUiEnhancementGate.isEnabled()
        val lyricRuntimeEnabled = SystemUiEnhancementGate.isLyricRuntimeEnabled()
        updateLyricRuntimeConnections()
        if (!lyricRuntimeEnabled) {
            localTimelineDriver?.stopDriving()
            AITranslator.cancelActiveRequests()
            IslandProgressGlowController.clearAll()
        }

        if (!hyperIslandEnabled) {
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

        if (hyperIslandEnabled) {
            BaseIslandRenderer.refreshActiveIsland()
        }
        HookLogger.i(
            "HookEntry",
            "更新功能运行状态: hyperIsland=$hyperIslandEnabled, lyricRuntime=$lyricRuntimeEnabled"
        )
    }

    private fun cleanupRuntime(forHotReload: Boolean = false) {
        runtimeReady = false
        runtimeEpoch++
        // Invalidate queued preference work before releasing sources or their timeline owner.
        mainHandler.removeCallbacksAndMessages(null)
        pendingSystemMediaProviderRefresh = null
        val cleanup = RuntimeResourceCleanup()
        cleanup.attempt("font_weight") { IslandSystemFontWeight.stop() }
        cleanup.attempt("bridge_traffic") { LyriconBridgeTrafficObserver.stop() }
        cleanup.attempt("performance") { RuntimePerfDiagnostics.stopForReload() }
        cleanup.attempt("artwork") { MediaMetadataHelper.clearArtworkResolution() }
        cleanup.attempt("system_media_providers") { OfficialProviderSystemMediaRuntime.releaseAll() }
        if (!forHotReload) {
            cleanup.attempt("album_style") { IslandAlbumCoverStyleHooker.cleanup() }
            cleanup.attempt("music_wave") { IslandMusicWaveColorHooker.cleanup() }
        }
        cleanup.attempt("screen_monitor") { SystemUiScreenStateMonitor.cleanup() }
        val listener = prefListener
        prefListener = null
        cleanup.attempt("preference_listener") {
            if (listener != null) _prefs?.unregisterOnSharedPreferenceChangeListener(listener)
        }
        val receiver = preferenceBroadcastReceiver
        preferenceBroadcastReceiver = null
        cleanup.attempt("preference_receiver") {
            if (receiver != null) runtimeApp?.unregisterReceiver(receiver)
        }
        cleanup.attempt("preference_overrides") { IslandRuntimePreferenceOverrides.clear() }
        cleanup.attempt("source_manager") { sourceManager?.stop() }
        // initialize() may acquire a tracker before SourceManager itself exists.
        cleanup.attempt("lyricon_source") { lyriconSource.stop() }
        cleanup.attempt("superlyric_source") { superLyricSource.stop() }
        cleanup.attempt("lyricinfo_source") { lyricInfoSource?.stop() }
        lyricRuntimeMode = null
        if (!forHotReload) {
            cleanup.attempt("embedded_central") {
                runtimeApp?.let { EmbeddedLyriconCentralController.prepare(it, enabled = false) }
            }
        }
        cleanup.attempt("ai_requests") {
            if (forHotReload) AITranslator.releaseForReload() else AITranslator.cancelActiveRequests()
        }
        sourceManager = null
        lyricInfoSource = null
        cleanup.attempt("timeline") { localTimelineDriver?.stop() }
        localTimelineDriver = null
        cleanup.attempt("playback_anchor") { playbackAnchor?.stop() }
        playbackAnchor = null
        runtimeApp = null
        cleanup.throwIfFailed()
    }

    private fun registerPreferenceBroadcastReceiver(app: Application) {
        preferenceBroadcastReceiver?.let { receiver ->
            runCatching { app.unregisterReceiver(receiver) }
        }
        val receiverEpoch = runtimeEpoch
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (SystemUiHookLifetime.retired || receiverEpoch != runtimeEpoch) return
                if (intent.action != RootConstants.ACTION_REMOTE_PREFERENCE_CHANGED) return
                val expectedUid = runCatching {
                    context.packageManager
                        .getApplicationInfo("com.juren233.hyperlyricsenhanced", 0)
                        .uid
                }.getOrDefault(-1)
                val senderUid = if (Build.VERSION.SDK_INT >= 34) sentFromUid else -1
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
                    "string_set" -> intent
                        .getStringArrayListExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_STRING_SET)
                        ?.toSet()
                        ?: emptySet<String>()
                    else -> return
                }
                IslandRuntimePreferenceOverrides.put(key, value)
                if (key in com.juren233.hyperlyricsenhanced.common.IslandTouchConfig.preferenceKeys) return
                if (key == RootConstants.KEY_ACTIVE_MEDIA_SESSION_PACKAGES && value is String) {
                    lyriconSource.onActiveMediaSessionSnapshotChanged(
                        value,
                        reason = "preference_broadcast",
                    )
                }
                if (key == RootConstants.KEY_HOOK_LYRIC_MODE && value is Int) {
                    activeMode = value
                }
                if (key == RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST) {
                    IslandAlbumCoverStyleHooker.refresh()
                }
                if (key == RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR ||
                    key == RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT ||
                    key == RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR_MODE
                ) {
                    IslandMusicWaveColorHooker.refresh()
                }
                if (key == RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH ||
                    key == RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT ||
                    key == RootConstants.KEY_HOOK_ISLAND_DUET_FIXED_LENGTH) {
                    BaseIslandRenderer.refreshDynamicWidth()
                } else {
                    BaseIslandRenderer.refreshActiveIsland()
                }
                HookLogger.i(
                    "HookEntry",
                    "收到配置广播并更新运行时覆盖: key=$key, " +
                        "value=${PreferenceDiagnostics.formatValue(key, value)}"
                )
            }
        }
        // Retain ownership even if the platform throws after partially registering.
        preferenceBroadcastReceiver = receiver
        app.registerReceiver(
            receiver,
            IntentFilter(RootConstants.ACTION_REMOTE_PREFERENCE_CHANGED),
            Context.RECEIVER_EXPORTED
        )
    }

    private fun postRuntimeUpdate(action: () -> Unit) {
        if (SystemUiHookLifetime.retired) return
        val epoch = runtimeEpoch
        mainHandler.post {
            if (SystemUiHookLifetime.retired || !runtimeReady || epoch != runtimeEpoch) return@post
            runCatching(action).onFailure { failure ->
                // Preference changes share these stricter cleanup paths with startup. Never let
                // a failed source/provider release escape onto the host's main Looper.
                HookLogger.e("HookEntry", "运行时配置更新失败，需要重启系统界面后重试", failure)
            }
        }
    }

    private fun scheduleSystemMediaProviderRefresh(app: Application) {
        pendingSystemMediaProviderRefresh?.let(mainHandler::removeCallbacks)
        val epoch = runtimeEpoch
        val refresh = Runnable {
            pendingSystemMediaProviderRefresh = null
            if (SystemUiHookLifetime.retired || epoch != runtimeEpoch ||
                runtimeApp !== app || lyricRuntimeMode?.requiresCentral != true) return@Runnable
            runCatching {
                OfficialProviderSystemMediaRuntime.releaseAll()
                OfficialProviderSystemMediaRuntime.installIfAvailable(this, app)
            }.onFailure { failure ->
                HookLogger.e("HookEntry", "系统媒体 Provider 刷新失败，需要重启系统界面后重试", failure)
            }
        }
        pendingSystemMediaProviderRefresh = refresh
        mainHandler.postDelayed(refresh, SYSTEM_MEDIA_PROVIDER_REFRESH_DELAY_MS)
    }

    private fun findCurrentApplication(): Application? {
        // The bounded coordinator reports lookup exceptions separately from a genuinely null app.
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplication = activityThreadClass.getDeclaredMethod("currentApplication")
        return currentApplication.invoke(null) as? Application
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
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val result = chain.proceed()
            val cl = chain.thisObject as? ClassLoader ?: return result
            try {
                NotificationMediaAodLyricHooker.hookAodPlugin(this@HookEntry, cl)
                SystemUIHookRegistry.hook(
                    this@HookEntry,
                    cl,
                    lyricsOnly = lyricsOnlyAfterHotReload
                )
                // 同步把白名单 Hook 挂到插件类加载器：PluginInstance.loadPlugin
                // 反射转发在新系统上不可靠，两条路径内部各自按 cl 去重，可叠加。
                UnlockIslandWhitelist.doHookInClassLoader(cl)
                UnlockFocusWhitelist.doHookInClassLoader(cl)
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
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val result = chain.proceed()
            val app = chain.thisObject as? Application
            LyricRuntimeDiagnostics.record("application_on_create_hit") {
                "application=${app?.javaClass?.name} modulePresent=${instance != null}"
            }
            app?.let { instance?.signalRuntimeApplication(it) }
            return result
        }
    }
}
