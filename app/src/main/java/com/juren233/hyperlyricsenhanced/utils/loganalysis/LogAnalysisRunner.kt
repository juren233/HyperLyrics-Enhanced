/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils.loganalysis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.ClassicAodSongInfoConfig
import com.juren233.hyperlyricsenhanced.common.IslandMusicWaveColorMode
import com.juren233.hyperlyricsenhanced.common.IslandProgressColorMode
import com.juren233.hyperlyricsenhanced.common.IslandRealRhythmConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.coroutines.ModuleDispatchers
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRepository
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderScopeManager
import com.juren233.hyperlyricsenhanced.root.RootApplication
import com.juren233.hyperlyricsenhanced.root.settings.SettingsEntryProfile
import com.juren233.hyperlyricsenhanced.utils.LogExportEnvironmentCollector
import com.juren233.hyperlyricsenhanced.utils.LogManager
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 读日志 + 收集现场状态 + 跑规则，全部在本机完成，不联网。 */
internal object LogAnalysisRunner {
    private val PROCESS_NAME = Regex("""^[A-Za-z0-9._]+(?::[A-Za-z0-9._]+)?$""")

    suspend fun run(context: Context): LogAnalysisReport = withContext(ModuleDispatchers.IO) {
        val appContext = context.applicationContext
        val scan = ModuleLogScan()
        val logSource = LogManager.streamModuleLogLines(scan::acceptLine)
        val environment = collectEnvironment(appContext, logSource)
        LogAnalyzer.report(scan, environment)
    }

    private suspend fun collectEnvironment(
        context: Context,
        logSource: ModuleLogSourceStatus,
    ): LogAnalysisEnvironment {
        val service = RootApplication.xposedService
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val plugins = runCatching { OfficialProviderRepository.loadInstalledItems(context) }
            .getOrDefault(emptyList())
        val hasRoot = logSource != ModuleLogSourceStatus.NoRoot
        val pidCache = HashMap<String, Set<Int>?>()
        return LogAnalysisEnvironment(
            installedVersionCode = BuildConfig.VERSION_CODE,
            logSource = logSource,
            moduleServiceBound = service != null,
            scope = service?.let { runCatching { it.scope.toSet() }.getOrNull() },
            pluginScopeTargets = if (service == null) {
                emptySet()
            } else {
                runCatching { OfficialProviderScopeManager.installedConfiguredScopes() }.getOrDefault(emptySet())
            },
            runningTargets = service?.let(::runningTargets),
            pidsOfProcess = { process ->
                if (!hasRoot) null else pidCache.getOrPut(process) { pidsOf(process) }
            },
            activePlayerPackage = runCatching {
                LogExportEnvironmentCollector.resolveCurrentMusicPackage(context)
            }.getOrNull(),
            installedPluginIds = plugins.filter { it.installed }.mapTo(hashSetOf()) { it.catalog.id },
            enabledPluginIds = plugins.filter { it.installed && it.enabled }.mapTo(hashSetOf()) { it.catalog.id },
            dynamicIslandEnabled = prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND,
                RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND,
            ),
            aodFocusNotificationEnabled = prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
                RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
            ) && ClassicAodSongInfoConfig.displayStyle(prefs) ==
                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION,
            hasPostNotificationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
            hasNotificationListenerAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName),
            aiTranslation = aiTranslationSettings(prefs),
            customFontPath = prefs.getString(RootConstants.KEY_HOOK_CUSTOM_FONT_PATH, null)
                ?.trim()?.takeIf(String::isNotEmpty),
            lyricSource = prefs.getString(RootConstants.KEY_HOOK_LYRIC_SOURCE, RootConstants.DEFAULT_HOOK_LYRIC_SOURCE)
                ?: RootConstants.DEFAULT_HOOK_LYRIC_SOURCE,
            superLyricServiceAvailable = if (
                hasRoot && prefs.getString(RootConstants.KEY_HOOK_LYRIC_SOURCE, null) == LogAnalyzer.SUPER_LYRIC_SOURCE
            ) {
                superLyricServiceFound()
            } else {
                null
            },
            featureSwitches = featureSwitches(prefs),
            pluginsNeedingRepair = plugins.filter { it.installed && it.needsRepair }
                .map { RepairablePlugin(it.catalog.id, it.catalog.displayName) },
            debugBuild = BuildConfig.DEBUG,
        )
    }

    private fun aiTranslationSettings(prefs: android.content.SharedPreferences): AiTranslationSettings? {
        val enabled = prefs.getBoolean(RootConstants.KEY_HOOK_AI_TRANS_ENABLE, RootConstants.DEFAULT_HOOK_AI_TRANS_ENABLE)
        if (!enabled) return null
        // 与 RootLyricSink.buildAiTranslationConfigs 一致：模型和接口地址留空时会用服务商默认值，只有这两项真会缺。
        return AiTranslationSettings(
            apiKeyBlank = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_API_KEY, "").isNullOrBlank(),
            targetLanguageBlank = prefs.getString(
                RootConstants.KEY_HOOK_AI_TRANS_TARGET_LANG,
                RootConstants.DEFAULT_HOOK_AI_TRANS_TARGET_LANG,
            ).isNullOrBlank(),
        )
    }

    internal fun featureSwitches(prefs: android.content.SharedPreferences): FeatureSwitches {
        val musicWaveMode = IslandMusicWaveColorMode.resolve(
            storedMode = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR_MODE, IslandMusicWaveColorMode.UNSPECIFIED),
            hasLegacyCoverPreference = prefs.contains(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR),
            legacyCoverEnabled = prefs.getBoolean(
                RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR,
                RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_COLOR,
            ),
            legacyCoverGradient = prefs.getBoolean(
                RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
                RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
            ),
        )
        val hyperIsland = prefs.getBoolean(
            RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND,
            RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND,
        )
        // 媒体卡片相关功能都受超级岛总开关控制（SystemUiEnhancementGate）。
        val notificationFlow = prefs.getInt(
            RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE,
            RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE,
        ) != RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DISABLED
        val notificationBackground = prefs.getInt(
            RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_STYLE,
            RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_STYLE,
        ) != RootConstants.NOTIFICATION_MEDIA_BACKGROUND_STYLE_DEFAULT
        // 与 IslandRuntimePreferenceReader.getProgressColorMode 一致，HookIslandGlow 只在封面取色时生效。
        val progressColorMode = IslandProgressColorMode.resolve(
            storedMode = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_PROGRESS_COLOR_MODE, IslandProgressColorMode.UNSPECIFIED),
            legacyProgressEnabled = prefs.getBoolean(
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_GLOW,
                RootConstants.DEFAULT_HOOK_ISLAND_PROGRESS_GLOW,
            ),
            legacyCoverEnabled = prefs.getBoolean(
                RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
                RootConstants.DEFAULT_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
            ),
            legacyCoverGradient = prefs.getBoolean(
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_GRADIENT,
                RootConstants.DEFAULT_HOOK_ISLAND_PROGRESS_GRADIENT,
            ),
        )
        // 与 SettingsEntryHooker 一致：隐藏了应用图标且位置不是「不显示」才会注入入口。
        val settingsEntry = prefs.getBoolean(UIConstants.KEY_HIDE_APP_ICON, UIConstants.DEFAULT_HIDE_APP_ICON) &&
            SettingsEntryProfile.resolveEntryPosition(
                positionExists = prefs.contains(UIConstants.KEY_SETTINGS_ENTRY_POSITION),
                positionValue = prefs.getInt(UIConstants.KEY_SETTINGS_ENTRY_POSITION, 0),
                legacyShowEntryExists = prefs.contains(UIConstants.KEY_SHOW_SETTINGS_ENTRY),
                legacyShowEntry = prefs.getBoolean(UIConstants.KEY_SHOW_SETTINGS_ENTRY, UIConstants.DEFAULT_SHOW_SETTINGS_ENTRY),
            ) != SettingsEntryProfile.POSITION_HIDDEN
        return FeatureSwitches(
            islandCoverStyle = prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE,
                RootConstants.DEFAULT_HOOK_ISLAND_ALBUM_COVER_STYLE,
            ) != RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT,
            musicWaveColor = IslandMusicWaveColorMode.isEnabled(musicWaveMode),
            notificationMediaCover = hyperIsland && (
                prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_COVER_STYLE,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_COVER_STYLE,
                ) != RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT ||
                    prefs.getBoolean(
                        RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE,
                        RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE,
                    ) ||
                    prefs.getBoolean(
                        RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH,
                        RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH,
                    )
                ),
            narrowLatinFont = prefs.getBoolean(
                RootConstants.KEY_HOOK_NARROW_LATIN_FONT,
                RootConstants.DEFAULT_HOOK_NARROW_LATIN_FONT,
            ),
            settingsEntry = settingsEntry,
            aodLyrics = prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
                RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
            ),
            islandExpandedFlow = hyperIsland && prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE,
                RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE,
            ) != RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DISABLED,
            notificationMediaCard = hyperIsland && (notificationFlow || notificationBackground),
            notificationMediaBackground = hyperIsland && notificationBackground,
            islandGlow = hyperIsland && IslandProgressColorMode.usesCover(progressColorMode),
            realRhythm = hyperIsland && IslandRealRhythmConfig.resolveMode(
                prefs.getInt(IslandRealRhythmConfig.KEY_MODE, IslandRealRhythmConfig.DEFAULT_MODE),
            ) != IslandRealRhythmConfig.MODE_OFF,
        )
    }

    /**
     * 与 SuperLyricHelper.isAvailable 判定一致（ServiceManager.getService("super_lyric") 不为空），
     * 应用进程没权限直接查，用 root 跑 `service check super_lyric`。查询失败返回 null。
     */
    private fun superLyricServiceFound(): Boolean? = runCatching {
        val shell = ProcessBuilder("su", "-c", "service check super_lyric")
            .redirectErrorStream(true)
            .start()
        try {
            if (!shell.waitFor(10, TimeUnit.SECONDS)) return null
            SuperLyricServiceCheck.parse(shell.inputStream.bufferedReader().readText())
        } finally {
            shell.destroy()
        }
    }.getOrNull()

    /**
     * API 102 才有 getRunningTargets；框架低于 102 或查询失败时返回 null，退回日志 + pidof 判断。
     *
     * 版本号先取到名为 apiVersion 的局部变量再比较：Lint 的 XposedNewApi 检查只认
     * `apiVersion < API_102` 这种写法作为提前返回的保护，包在 runCatching 里比较它认不出来。
     */
    @VisibleForTesting
    internal fun runningTargets(service: XposedService): List<RunningHookTarget>? {
        val apiVersion = runCatching { service.apiVersion }.getOrDefault(0)
        if (apiVersion < XposedService.API_102) return null
        return runCatching {
            service.runningTargets.map { target ->
                RunningHookTarget(
                    processName = target.processName,
                    pid = target.pid,
                    loadedVersionCode = target.loadedVersionCode,
                    state = when (target.state) {
                        HookedTarget.State.STALE -> RunningHookTarget.State.STALE
                        HookedTarget.State.RELOADING -> RunningHookTarget.State.RELOADING
                        HookedTarget.State.FAILED -> RunningHookTarget.State.FAILED
                        else -> RunningHookTarget.State.UP_TO_DATE
                    },
                )
            }
        }.getOrNull()
    }

    /** 用 root 的 pidof 查进程当前的 pid；查询失败返回 null（未知）。 */
    private fun pidsOf(process: String): Set<Int>? {
        if (!PROCESS_NAME.matches(process)) return null
        return runCatching {
            val shell = ProcessBuilder("su", "-c", "pidof '$process'; echo \"__EXIT__\$?\"")
                .redirectErrorStream(true)
                .start()
            try {
                if (!shell.waitFor(10, TimeUnit.SECONDS)) return null
                val output = shell.inputStream.bufferedReader().readText()
                // pidof 找不到进程时退出码为 1、没有输出：这是「进程不在」，不是「查询失败」。
                val exit = output.substringAfterLast("__EXIT__").trim().toIntOrNull() ?: return null
                if (exit != 0 && exit != 1) return null
                output.substringBefore("__EXIT__")
                    .split(Regex("""\s+"""))
                    .mapNotNullTo(hashSetOf()) { it.toIntOrNull() }
            } finally {
                shell.destroy()
            }
        }.getOrNull()
    }
}

/** `service check super_lyric` 的输出：「Service super_lyric: found」或「Service super_lyric: not found」。 */
internal object SuperLyricServiceCheck {
    fun parse(output: String): Boolean? {
        val line = output.lineSequence().map(String::trim).firstOrNull { it.startsWith("Service super_lyric:") }
            ?: return null
        return when (line.substringAfter(':').trim()) {
            "found" -> true
            "not found" -> false
            else -> null
        }
    }
}
