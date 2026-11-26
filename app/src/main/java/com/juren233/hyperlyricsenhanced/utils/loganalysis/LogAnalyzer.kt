/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils.loganalysis

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog

/** 读取 LSPosed 模块日志的结果，与日志页的读取逻辑共用同一套判断。 */
internal sealed interface ModuleLogSourceStatus {
    data object Ok : ModuleLogSourceStatus
    data object NoRoot : ModuleLogSourceStatus
    data object LsposedLogDirNotFound : ModuleLogSourceStatus
    data class NoLogFiles(val directory: String) : ModuleLogSourceStatus
    data class ReadFailed(val message: String) : ModuleLogSourceStatus
}

/** libxposed service 102 的 HookedTarget 快照；框架不支持时整体为 null。 */
internal data class RunningHookTarget(
    val processName: String,
    val pid: Int,
    val loadedVersionCode: Long,
    val state: State,
) {
    enum class State { UP_TO_DATE, STALE, RELOADING, FAILED }
}

/**
 * 分析时的现场状态。日志只能说明「过去发生了什么」，这些状态用来确认问题此刻仍然存在，
 * 避免把已经解决的旧问题报出来。
 */
internal data class LogAnalysisEnvironment(
    val installedVersionCode: Int,
    val logSource: ModuleLogSourceStatus,
    /** RootApplication.xposedService 是否已连接（模块已在 LSPosed 中启用并生效）。 */
    val moduleServiceBound: Boolean,
    /** LSPosed 里当前勾选的作用域；读不到时为 null。 */
    val scope: Set<String>?,
    /** 已启用的官方插件需要、且对应 App 已安装的作用域（与 OfficialProviderScopeManager 的判定一致）。 */
    val pluginScopeTargets: Set<String>,
    /** 正在运行、已加载本模块的进程；框架低于 API 102 或读取失败时为 null。 */
    val runningTargets: List<RunningHookTarget>?,
    /** 查询进程名当前的 pid；查不到时返回 null（未知），进程不存在时返回空集合。 */
    val pidsOfProcess: (String) -> Set<Int>?,
    val activePlayerPackage: String?,
    val installedPluginIds: Set<String>,
    val enabledPluginIds: Set<String>,
    val dynamicIslandEnabled: Boolean,
    val aodFocusNotificationEnabled: Boolean,
    val hasPostNotificationPermission: Boolean,
    val hasNotificationListenerAccess: Boolean,
    /** 「歌词设置 → 翻译 → OpenAI 翻译」的开关和必填项；没开时为 null。 */
    val aiTranslation: AiTranslationSettings? = null,
    /** 「文字样式 → 字体」里填的路径；没填时为 null。 */
    val customFontPath: String? = null,
    /** 「歌词设置 → 获取来源 → 歌词源」选的源 id（lyricon / superlyric / lyricinfo）。 */
    val lyricSource: String? = null,
    /** root 执行 `service check super_lyric` 的结果；没有 root 或查询失败时为 null。 */
    val superLyricServiceAvailable: Boolean? = null,
    val featureSwitches: FeatureSwitches = FeatureSwitches(),
    /** 已安装但文件缺失或损坏、插件页标着「需修复」的插件。 */
    val pluginsNeedingRepair: List<RepairablePlugin> = emptyList(),
    /** 当前装的是 debug 包（BuildConfig.DEBUG）。release 包的日志缺少适配需要的调试信息，反馈前要先换 debug 包。 */
    val debugBuild: Boolean = true,
)

internal data class AiTranslationSettings(val apiKeyBlank: Boolean, val targetLanguageBlank: Boolean)

/** 依赖系统内部接口的功能开关当前是否打开（按功能真正生效的条件算，见 LogAnalysisRunner.featureSwitches）。 */
internal data class FeatureSwitches(
    val islandCoverStyle: Boolean = false,
    val musicWaveColor: Boolean = false,
    val notificationMediaCover: Boolean = false,
    val narrowLatinFont: Boolean = false,
    val settingsEntry: Boolean = false,
    val aodLyrics: Boolean = false,
    val islandExpandedFlow: Boolean = false,
    val notificationMediaCard: Boolean = false,
    val notificationMediaBackground: Boolean = false,
    val islandGlow: Boolean = false,
    /** 「跟随音乐真实律动」不是「不开启」，且超级岛开着。 */
    val realRhythm: Boolean = false,
) {
    fun isOn(feature: SystemFeature): Boolean = when (feature) {
        SystemFeature.ISLAND_COVER_STYLE -> islandCoverStyle
        SystemFeature.MUSIC_WAVE_COLOR -> musicWaveColor
        SystemFeature.NOTIFICATION_MEDIA_COVER -> notificationMediaCover
        SystemFeature.NARROW_LATIN_FONT -> narrowLatinFont
        SystemFeature.SETTINGS_ENTRY -> settingsEntry
        SystemFeature.AOD_LYRICS -> aodLyrics
        SystemFeature.ISLAND_EXPANDED_FLOW -> islandExpandedFlow
        SystemFeature.NOTIFICATION_MEDIA_CARD -> notificationMediaCard
        SystemFeature.NOTIFICATION_MEDIA_BACKGROUND -> notificationMediaBackground
        SystemFeature.ISLAND_GLOW -> islandGlow
    }

    companion object {
        /** 全部打开，单测与真实日志回归用。 */
        val ALL_ON = FeatureSwitches(true, true, true, true, true, true, true, true, true, true, true)
    }
}

internal data class RepairablePlugin(val id: String, val name: String)

/** 被 Hook 的宿主：决定文案里说哪个系统组件的 hook 点位偏差、反馈时要附哪些 APK。 */
internal enum class HookHost { SYSTEM_UI, SYSTEM_UI_OR_AOD, SETTINGS }

/**
 * 可能因为系统版本不提供对应接口而用不了的功能。[hookHost] 不为空的是 Hook 点位失效，需要适配，引导反馈；
 * 英数窄字体不是 Hook，是系统里没有那个字体文件，仍然一键关闭。
 */
internal enum class SystemFeature(val hookHost: HookHost?) {
    ISLAND_COVER_STYLE(HookHost.SYSTEM_UI),
    MUSIC_WAVE_COLOR(HookHost.SYSTEM_UI),
    NOTIFICATION_MEDIA_COVER(HookHost.SYSTEM_UI),
    NARROW_LATIN_FONT(null),
    SETTINGS_ENTRY(HookHost.SETTINGS),
    AOD_LYRICS(HookHost.SYSTEM_UI_OR_AOD),
    ISLAND_EXPANDED_FLOW(HookHost.SYSTEM_UI),
    NOTIFICATION_MEDIA_CARD(HookHost.SYSTEM_UI),
    NOTIFICATION_MEDIA_BACKGROUND(HookHost.SYSTEM_UI),
    ISLAND_GLOW(HookHost.SYSTEM_UI),
}

/** 真实律动拿不到频谱的两种情况。 */
internal enum class RealRhythmProblem {
    /** Visualizer 创建或启用时抛了异常。 */
    START_FAILED,
    /** 已接管，但持续没有采集回调或各频段持续没有能量：音频走了 Visualizer 采不到的输出路径。 */
    NO_SPECTRUM,
}

/** 系统界面里模块启动时的几个部分。 */
internal enum class StartupComponent { SYSTEM_ENVIRONMENT, LYRICON_CENTRAL, LYRIC_SOURCE, ISLAND_PLUGIN }

internal enum class AiTranslationProblem { MISSING_CONFIG, AUTH, RATE_LIMIT, HTTP_ERROR, BAD_RESPONSE, NETWORK }

internal enum class SuperLyricProblem {
    /** `service check super_lyric` 找不到服务：SuperLyric 模块没装、没启用或没生效。 */
    SERVICE_MISSING,
    /** 系统界面启动时 SuperLyric 不可用，已经回退到别的歌词源。 */
    FELL_BACK,
    /** 选了 SuperLyric，但注册接收端失败。 */
    REGISTER_FAILED,
}

internal enum class LogFindingSeverity { ERROR, WARNING }

/** 一条依据：日志原文，或现场检查到的事实（由界面按语言翻译）。 */
internal sealed interface LogEvidence {
    data class Line(val text: String) : LogEvidence
    data class InstalledVersion(val versionCode: Int) : LogEvidence
    data class ScopeLacks(val packages: List<String>) : LogEvidence
    data class NoRecordsFrom(val process: String, val scannedRecords: Int) : LogEvidence
    data class ProcessRunning(val process: String, val pid: Int, val loadedVersionCode: Long?) : LogEvidence
    /** 拒绝热重载之后，日志里这个进程再没有加载过模块。 */
    data class NoReloadAfterRejection(val process: String, val pid: Int) : LogEvidence
    data object ServiceNotBound : LogEvidence
    data class PlayingWithoutPlugin(val playerPackage: String, val pluginId: String, val installed: Boolean) : LogEvidence
    data class PermissionMissing(val postNotification: Boolean, val notificationListener: Boolean) : LogEvidence
    data class SourceError(val status: ModuleLogSourceStatus) : LogEvidence
    data object AiTranslationConfigIncomplete : LogEvidence
    data class SuperLyricServiceCheck(val found: Boolean) : LogEvidence
    data class PluginFileBroken(val pluginName: String) : LogEvidence
}

/** 卡片上的一键处理。 */
internal sealed interface LogFixAction {
    /** 通过 XposedService.requestScope 申请作用域，LSPosed 会弹通知让用户确认。 */
    data class RequestScope(val packages: List<String>) : LogFixAction
    /** 重启系统界面或结束音乐 App（ShellUtils.killAppProcesses，需要 root）。 */
    data class RestartProcesses(val packages: List<String>) : LogFixAction
    data object OpenPluginDownloads : LogFixAction
    data object OpenPluginManager : LogFixAction
    data object OpenAppNotificationSettings : LogFixAction
    data object OpenNotificationListenerSettings : LogFixAction
    data object RepairPlugins : LogFixAction
    /** 「歌词设置」页，AI 翻译在「翻译」组里。 */
    data object OpenAiTranslationSettings : LogFixAction
    /** 「文字样式」页，自定义字体在「字体」项。 */
    data object OpenFontSettings : LogFixAction
    /** 清空自定义字体路径，恢复系统默认字体。 */
    data object ClearCustomFont : LogFixAction
    data class SwitchLyricSource(val sourceId: String) : LogFixAction
    data class TurnOffFeature(val feature: SystemFeature) : LogFixAction
    /** 打开本仓库 GitHub 新建 issue 页面，用于需要适配才能解决的问题（debug 包）。 */
    data object OpenIssuePage : LogFixAction
    /** 打开当前版本的 Releases 页面下载 debug 包（release 包的日志不够排查，先换 debug 包复现）。 */
    data object OpenReleasePage : LogFixAction
    /** 「跟随音乐真实律动」设为「不开启」。 */
    data object TurnOffRealRhythm : LogFixAction

    companion object {
        /** 需要开发者排查的问题：debug 包直接去反馈，release 包先去下载 debug 包。 */
        fun feedback(debugBuild: Boolean): LogFixAction = if (debugBuild) OpenIssuePage else OpenReleasePage
    }
}

internal sealed interface LogFinding {
    val severity: LogFindingSeverity
    val evidence: List<LogEvidence>
    val actions: List<LogFixAction>

    /** 日志读不到：分析只能基于现场状态。 */
    data class LogsUnreadable(
        val status: ModuleLogSourceStatus,
        override val evidence: List<LogEvidence>,
    ) : LogFinding {
        override val severity = LogFindingSeverity.WARNING
        override val actions = emptyList<LogFixAction>()
    }

    /** LSPosed 没有把服务交给本应用：模块未启用或 LSPosed 未运行。 */
    data class ModuleInactive(override val evidence: List<LogEvidence>) : LogFinding {
        override val severity = LogFindingSeverity.ERROR
        override val actions = emptyList<LogFixAction>()
    }

    data class ScopeMissing(
        val packages: List<String>,
        val includesSystemUi: Boolean,
        override val evidence: List<LogEvidence>,
    ) : LogFinding {
        override val severity = if (includesSystemUi) LogFindingSeverity.ERROR else LogFindingSeverity.WARNING
        override val actions = listOf<LogFixAction>(LogFixAction.RequestScope(packages))
    }

    /** 更新后宿主进程仍在运行旧版模块（热重载被拒绝或还没重启）。 */
    data class StaleModule(
        val processes: List<StaleProcess>,
        val installedVersionCode: Int,
        override val evidence: List<LogEvidence>,
    ) : LogFinding {
        override val severity = LogFindingSeverity.ERROR
        override val actions = listOf<LogFixAction>(
            LogFixAction.RestartProcesses(processes.map { it.packageName }.distinct()),
        )
    }

    data class StaleProcess(
        val process: String,
        val packageName: String,
        /** 日志被轮转、只剩「拒绝热重载」时不知道旧版本号，为 null。 */
        val loadedVersionCode: Long?,
        /** 已用 pid 确认进程仍在运行；为 false 时只能说「可能」。 */
        val confirmedRunning: Boolean,
    )

    /** 系统界面里出现了模块明确要求重启系统界面的错误，且该进程还没重启过。 */
    data class SystemUiRestartRequired(override val evidence: List<LogEvidence>) : LogFinding {
        override val severity = LogFindingSeverity.ERROR
        override val actions = listOf<LogFixAction>(
            LogFixAction.RestartProcesses(listOf(LogAnalyzer.SYSTEM_UI)),
        )
    }

    /** 系统界面里模块的某个部分启动失败，之后在同一个进程里没再成功过。 */
    data class SystemUiStartupFailed(
        val components: List<StartupComponent>,
        override val evidence: List<LogEvidence>,
        val debugBuild: Boolean = true,
    ) : LogFinding {
        override val severity = LogFindingSeverity.ERROR
        override val actions = listOf(
            LogFixAction.RestartProcesses(listOf(LogAnalyzer.SYSTEM_UI)),
            LogFixAction.feedback(debugBuild),
        )
    }

    /** 开了 AI 翻译，但配置不完整或请求失败。歌词照常显示，只是没有翻译，所以是警告。 */
    data class AiTranslationFailing(
        val problem: AiTranslationProblem,
        val httpCode: Int?,
        override val evidence: List<LogEvidence>,
    ) : LogFinding {
        override val severity = LogFindingSeverity.WARNING
        override val actions = listOf<LogFixAction>(LogFixAction.OpenAiTranslationSettings)
    }

    data class CustomFontUnreadable(
        val path: String,
        override val evidence: List<LogEvidence>,
    ) : LogFinding {
        override val severity = LogFindingSeverity.WARNING
        override val actions = listOf(LogFixAction.OpenFontSettings, LogFixAction.ClearCustomFont)
    }

    data class SuperLyricUnavailable(
        val problem: SuperLyricProblem,
        /** 回退到的歌词源显示名（如 Lyricon）；没选到任何源时为 null。 */
        val fellBackTo: String?,
        /** 服务现在已经在了：重启系统界面就能重新接上。 */
        val serviceNowAvailable: Boolean,
        override val evidence: List<LogEvidence>,
    ) : LogFinding {
        override val severity = LogFindingSeverity.WARNING
        override val actions = buildList {
            val switch = LogFixAction.SwitchLyricSource(LogAnalyzer.LYRICON_SOURCE)
            if (serviceNowAvailable) {
                add(LogFixAction.RestartProcesses(listOf(LogAnalyzer.SYSTEM_UI)))
                add(switch)
            } else {
                add(switch)
            }
        }
    }

    /**
     * 开了某个功能，但它依赖的系统接口不在：Hook 点位失效的引导反馈（debug 包去 GitHub，release 包先下载 debug 包），
     * 英数窄字体缺字体文件则一键关闭。
     */
    data class FeatureUnsupported(
        val feature: SystemFeature,
        override val evidence: List<LogEvidence>,
        val debugBuild: Boolean = true,
    ) : LogFinding {
        override val severity = LogFindingSeverity.WARNING
        override val actions = if (feature.hookHost != null) {
            listOf(LogFixAction.feedback(debugBuild))
        } else {
            listOf(LogFixAction.TurnOffFeature(feature), LogFixAction.feedback(debugBuild))
        }
    }

    /** 开了「跟随音乐真实律动」，但系统界面当前进程最近一次状态是拿不到频谱。 */
    data class RealRhythmNoSpectrum(
        val problem: RealRhythmProblem,
        override val evidence: List<LogEvidence>,
    ) : LogFinding {
        override val severity = LogFindingSeverity.WARNING
        override val actions = listOf<LogFixAction>(LogFixAction.TurnOffRealRhythm)
    }

    /**
     * AI 翻译缓存读写失败。缓存库在系统界面自己的数据目录里，不涉及用户能授予的权限；
     * 失败多半是存储空间满或数据库读写出错，翻译照常请求，只是每次都要重新翻译。
     */
    data class TranslationCacheFailing(
        override val evidence: List<LogEvidence>,
        val debugBuild: Boolean = true,
    ) : LogFinding {
        override val severity = LogFindingSeverity.WARNING
        // 不给重启：缓存每次读写都会重新取数据库，失败不会卡到进程重启，清出空间后下一次读写就恢复。
        override val actions = listOf(LogFixAction.feedback(debugBuild))
    }

    /**
     * 当前进程里还有没规则认得的报错 / 警告：只给数量，引导用 debug 包复现后导出完整调试日志反馈。
     * 只要数出来有报错就按报错标红，只剩警告才是黄色。
     */
    data class UnrecognizedProblems(
        val errorCount: Int,
        val warningCount: Int,
        val debugBuild: Boolean,
    ) : LogFinding {
        override val severity = if (errorCount > 0) LogFindingSeverity.ERROR else LogFindingSeverity.WARNING
        override val evidence = emptyList<LogEvidence>()
        override val actions = listOf(LogFixAction.feedback(debugBuild))
    }

    data class PluginNeedsRepair(
        val pluginNames: List<String>,
        override val evidence: List<LogEvidence>,
    ) : LogFinding {
        override val severity = LogFindingSeverity.WARNING
        override val actions = listOf<LogFixAction>(LogFixAction.RepairPlugins)
    }

    data class PlayerWithoutPlugin(
        val playerPackage: String,
        val pluginId: String,
        val pluginName: String,
        val pluginInstalled: Boolean,
        override val evidence: List<LogEvidence>,
    ) : LogFinding {
        override val severity = LogFindingSeverity.WARNING
        override val actions = listOf(
            if (pluginInstalled) LogFixAction.OpenPluginManager else LogFixAction.OpenPluginDownloads,
        )
    }

    data class NotificationAccessMissing(
        val dynamicIsland: Boolean,
        val aodFocusNotification: Boolean,
        val missingPostNotification: Boolean,
        val missingNotificationListener: Boolean,
        override val evidence: List<LogEvidence>,
    ) : LogFinding {
        override val severity = LogFindingSeverity.WARNING
        override val actions = buildList {
            if (missingPostNotification) add(LogFixAction.OpenAppNotificationSettings)
            if (missingNotificationListener) add(LogFixAction.OpenNotificationListenerSettings)
        }
    }
}

/**
 * 日志分析的规则表。每条规则只在「日志里能稳定认出、而且办法确实有效」时给出结论，
 * 并且用现场状态确认问题此刻仍然存在。
 */
internal object LogAnalyzer {
    const val SYSTEM_UI = "com.android.systemui"
    const val LYRICON_SOURCE = "lyricon"
    const val SUPER_LYRIC_SOURCE = "superlyric"
    const val AI_TRANSLATION_KEY_PREFIX = "key_hook_ai_trans_"
    const val CUSTOM_FONT_PATH_KEY = "key_hook_custom_font_path"
    const val NARROW_LATIN_FONT_KEY = "key_hook_narrow_latin_font"
    const val SETTINGS = "com.android.settings"
    const val REAL_RHYTHM_MODE_KEY = "key_hook_island_music_wave_real_rhythm_mode"

    fun analyze(scan: ModuleLogScan, env: LogAnalysisEnvironment): List<LogFinding> = buildList {
        logsUnreadable(env)?.let(::add)
        val inactive = moduleInactive(scan, env)
        if (inactive != null) {
            add(inactive)
        } else {
            scopeMissing(scan, env)?.let(::add)
        }
        val stale = staleModule(scan, env)
        stale?.let(::add)
        val systemUiStale = stale?.processes?.any { it.process == SYSTEM_UI } == true
        if (!systemUiStale) {
            systemUiRestartRequired(scan, env)?.let(::add)
            systemUiStartupFailed(scan, env)?.let(::add)
        }
        pluginNeedsRepair(env)?.let(::add)
        playerWithoutPlugin(scan, env)?.let(::add)
        aiTranslationFailing(scan, env)?.let(::add)
        customFontUnreadable(scan, env)?.let(::add)
        superLyricUnavailable(scan, env)?.let(::add)
        SystemFeature.entries.forEach { feature -> featureUnsupported(scan, env, feature)?.let(::add) }
        realRhythmNoSpectrum(scan, env)?.let(::add)
        translationCacheFailing(scan, env)?.let(::add)
        notificationAccessMissing(env)?.let(::add)
        unrecognizedProblems(scan, env)?.let(::add)
    }.sortedBy { it.severity.ordinal }

    /**
     * 状态板用：把结论归到各检查项，没问题的项记「通过」，没法检查或不需要检查的项记「未检查」并说明原因。
     */
    fun report(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogAnalysisReport =
        report(analyze(scan, env), env)

    fun report(findings: List<LogFinding>, env: LogAnalysisEnvironment): LogAnalysisReport {
        fun result(check: LogCheck, matched: List<LogFinding>, skip: LogCheckSkipReason?): LogCheckResult = when {
            matched.isNotEmpty() -> LogCheckResult(
                check = check,
                status = if (matched.any { it.severity == LogFindingSeverity.ERROR }) LogCheckStatus.ERROR else LogCheckStatus.WARNING,
                findings = matched,
            )
            skip != null -> LogCheckResult(check, LogCheckStatus.SKIPPED, skipReason = skip)
            else -> LogCheckResult(check, LogCheckStatus.PASS)
        }
        val inactive = findings.any { it is LogFinding.ModuleInactive }
        val logsOk = env.logSource == ModuleLogSourceStatus.Ok
        val player = env.activePlayerPackage?.trim()?.takeIf(String::isNotEmpty)
        val pluginSkip = when {
            // 模块没生效时插件根本不会被加载，「插件运行正常」无从谈起；这个原因排在「没在播放」前面。
            inactive -> LogCheckSkipReason.MODULE_INACTIVE
            player == null -> LogCheckSkipReason.NOT_PLAYING
            OfficialProviderCatalog.definitionForPackage(player)
                ?.takeIf { OfficialProviderCatalog.shouldShowInDownloadList(it.id) } == null ->
                LogCheckSkipReason.PLAYER_HAS_NO_PLUGIN
            else -> null
        }
        return LogAnalysisReport(
            LogCheck.entries.map { check ->
                when (check) {
                    LogCheck.MODULE -> result(check, findings.filterIsInstance<LogFinding.ModuleInactive>(), null)
                    LogCheck.SCOPE -> result(
                        check,
                        findings.filterIsInstance<LogFinding.ScopeMissing>(),
                        when {
                            inactive -> LogCheckSkipReason.MODULE_INACTIVE
                            env.scope == null -> LogCheckSkipReason.SCOPE_UNKNOWN
                            else -> null
                        },
                    )
                    LogCheck.VERSION -> result(
                        check,
                        findings.filter { it is LogFinding.StaleModule || it is LogFinding.SystemUiRestartRequired },
                        when {
                            inactive -> LogCheckSkipReason.MODULE_INACTIVE
                            !logsOk && env.runningTargets == null -> LogCheckSkipReason.LOGS_UNREADABLE
                            else -> null
                        },
                    )
                    LogCheck.RUNTIME -> result(
                        check,
                        findings.filterIsInstance<LogFinding.SystemUiStartupFailed>(),
                        when {
                            inactive -> LogCheckSkipReason.MODULE_INACTIVE
                            !logsOk -> LogCheckSkipReason.LOGS_UNREADABLE
                            else -> null
                        },
                    )
                    LogCheck.PLUGIN -> result(
                        check,
                        findings.filter { it is LogFinding.PluginNeedsRepair || it is LogFinding.PlayerWithoutPlugin },
                        pluginSkip,
                    )
                    LogCheck.FEATURES -> result(
                        check,
                        findings.filter {
                            it is LogFinding.AiTranslationFailing || it is LogFinding.CustomFontUnreadable ||
                                it is LogFinding.SuperLyricUnavailable || it is LogFinding.FeatureUnsupported ||
                                it is LogFinding.RealRhythmNoSpectrum || it is LogFinding.TranslationCacheFailing
                        },
                        when {
                            inactive -> LogCheckSkipReason.MODULE_INACTIVE
                            !logsOk -> LogCheckSkipReason.LOGS_UNREADABLE
                            else -> null
                        },
                    )
                    LogCheck.NOTIFICATION -> result(
                        check,
                        findings.filterIsInstance<LogFinding.NotificationAccessMissing>(),
                        LogCheckSkipReason.FEATURES_OFF.takeIf { !env.dynamicIslandEnabled && !env.aodFocusNotificationEnabled },
                    )
                    LogCheck.LOGS -> result(
                        check,
                        findings.filter { it is LogFinding.LogsUnreadable || it is LogFinding.UnrecognizedProblems },
                        null,
                    )
                }
            },
        )
    }

    private fun logsUnreadable(env: LogAnalysisEnvironment): LogFinding? {
        val status = env.logSource
        if (status == ModuleLogSourceStatus.Ok) return null
        return LogFinding.LogsUnreadable(status, listOf(LogEvidence.SourceError(status)))
    }

    private fun moduleInactive(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        if (env.moduleServiceBound) return null
        val evidence = buildList {
            add(LogEvidence.ServiceNotBound)
            // 有日志时附上最近一次加载记录，说明模块以前生效过（多半是被停用或 LSPosed 没运行）。
            scan.latestLoads().values.maxByOrNull { it.record.time }?.let { add(LogEvidence.Line(it.record.raw)) }
        }
        return LogFinding.ModuleInactive(evidence)
    }

    private fun scopeMissing(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        val scope = env.scope ?: return null
        val wanted = linkedSetOf(SYSTEM_UI) + env.pluginScopeTargets.sorted()
        val missing = wanted.filterNot(scope::contains)
        if (missing.isEmpty()) return null
        val evidence = buildList<LogEvidence> {
            add(LogEvidence.ScopeLacks(missing))
            if (env.logSource == ModuleLogSourceStatus.Ok) {
                missing.filterNot(scan::hasRecordsFrom).forEach {
                    add(LogEvidence.NoRecordsFrom(it, scan.recordCount))
                }
            }
        }
        return LogFinding.ScopeMissing(missing, includesSystemUi = SYSTEM_UI in missing, evidence = evidence)
    }

    private fun staleModule(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding.StaleModule? {
        val installed = env.installedVersionCode.toLong()
        val stale = mutableListOf<LogFinding.StaleProcess>()
        val evidence = mutableListOf<LogEvidence>()
        val running = env.runningTargets
        if (running != null) {
            // 框架直接告诉我们每个进程加载的版本：最准，不依赖日志是否被轮转。
            running.filter { target ->
                isAppProcess(target.processName) && when (target.state) {
                    RunningHookTarget.State.RELOADING -> false
                    RunningHookTarget.State.STALE, RunningHookTarget.State.FAILED -> true
                    RunningHookTarget.State.UP_TO_DATE -> target.loadedVersionCode != installed
                }
            }.sortedBy { it.processName }.forEach { target ->
                stale += LogFinding.StaleProcess(
                    process = target.processName,
                    packageName = target.processName.substringBefore(':'),
                    loadedVersionCode = target.loadedVersionCode,
                    confirmedRunning = true,
                )
                evidence += LogEvidence.ProcessRunning(target.processName, target.pid, target.loadedVersionCode)
                scan.latestLoadInPid(target.pid)?.let { evidence += LogEvidence.Line(it.record.raw) }
                scan.hotReloadRejected(target.pid)?.let { evidence += LogEvidence.Line(it.raw) }
            }
        } else {
            staleFromLogs(scan, env, installed, stale, evidence)
        }
        if (stale.isEmpty()) return null
        return LogFinding.StaleModule(
            processes = stale,
            installedVersionCode = env.installedVersionCode,
            evidence = listOf(LogEvidence.InstalledVersion(env.installedVersionCode)) + evidence,
        )
    }

    /**
     * 框架拿不到运行中进程时，从日志推断。两种线索：
     * 1. 进程最近一次「模块加载完成」的版本号不是当前安装的版本；
     * 2. 进程「拒绝热重载」之后再没加载过模块（版本行可能已经被日志轮转掉）。
     * 同一进程之后又换了新 pid 并加载了模块，说明已经重启过，不算。
     */
    private fun staleFromLogs(
        scan: ModuleLogScan,
        env: LogAnalysisEnvironment,
        installed: Long,
        stale: MutableList<LogFinding.StaleProcess>,
        evidence: MutableList<LogEvidence>,
    ) {
        data class Candidate(val process: String, val pid: Int, val time: String, val versionCode: Long?, val lines: List<LogEvidence>)

        val candidates = HashMap<String, Candidate>()
        fun offer(candidate: Candidate) {
            val previous = candidates[candidate.process]
            if (previous == null || previous.time < candidate.time) candidates[candidate.process] = candidate
        }
        scan.latestLoads().values
            .filter { it.versionCode.toLong() != installed && isAppProcess(it.record.process) }
            .forEach { load ->
                val lines = buildList<LogEvidence> {
                    add(LogEvidence.Line(load.record.raw))
                    scan.hotReloadRejected(load.record.pid)
                        ?.takeIf { it.time >= load.record.time }
                        ?.let { add(LogEvidence.Line(it.raw)) }
                }
                offer(Candidate(load.record.process, load.record.pid, load.record.time, load.versionCode.toLong(), lines))
            }
        scan.hotReloadRejections()
            .filter { isAppProcess(it.process) }
            .forEach { rejection ->
                val loadInPid = scan.latestLoadInPid(rejection.pid)
                // 拒绝之后同一进程又加载了模块：已经换上新版本（或由第 1 条线索判断）。
                if (loadInPid != null && loadInPid.record.time > rejection.time) return@forEach
                val version = loadInPid?.versionCode?.toLong()
                if (version == installed) return@forEach
                val lines = buildList<LogEvidence> {
                    loadInPid?.let { add(LogEvidence.Line(it.record.raw)) }
                    add(LogEvidence.Line(rejection.raw))
                    add(LogEvidence.NoReloadAfterRejection(rejection.process, rejection.pid))
                }
                offer(Candidate(rejection.process, rejection.pid, rejection.time, version, lines))
            }
        candidates.values.sortedBy { it.process }.forEach { candidate ->
            // 之后这个进程名以新的 pid 加载过模块：进程已经重启。
            val restarted = scan.latestLoads()[candidate.process]
                ?.let { it.record.pid != candidate.pid && it.record.time > candidate.time } == true
            if (restarted) return@forEach
            val pids = env.pidsOfProcess(candidate.process)
            // 进程已经不在（或已换了新进程）也不算：新进程启动时会加载新版本。
            if (pids != null && candidate.pid !in pids) return@forEach
            stale += LogFinding.StaleProcess(
                process = candidate.process,
                packageName = candidate.process.substringBefore(':'),
                loadedVersionCode = candidate.versionCode,
                confirmedRunning = pids != null,
            )
            evidence += candidate.lines
        }
    }

    /** 系统界面当前的 pid：优先框架，其次 root pidof，都查不到时退回「日志里系统界面最新的进程」。 */
    private fun currentSystemUiPids(scan: ModuleLogScan, env: LogAnalysisEnvironment): Set<Int> =
        env.runningTargets
            ?.filter { it.processName == SYSTEM_UI }
            ?.mapTo(hashSetOf()) { it.pid }
            ?: env.pidsOfProcess(SYSTEM_UI)
            ?: setOfNotNull(scan.latestRecord(SYSTEM_UI)?.pid)

    /**
     * 系统界面当前进程里、最近一次加载模块之后的某类信号。
     * 热重载会在同一个 pid 里重新加载模块，之前的失败不再算数；日志被轮转、看不到加载行时不做这层过滤。
     */
    private fun currentSignal(
        scan: ModuleLogScan,
        pids: Set<Int>,
        signal: LogSignal,
    ): ModuleLogRecord? = pids.mapNotNull { pid ->
        scan.latestSignal(signal, pid)?.takeIf { it.time >= generationStart(scan, pid) }
    }.maxByOrNull { it.time }

    private fun generationStart(scan: ModuleLogScan, pid: Int): String = scan.latestLoadInPid(pid)?.record?.time ?: ""

    private fun newerThan(record: ModuleLogRecord?, than: ModuleLogRecord): Boolean = record != null && record.time > than.time

    private fun systemUiRestartRequired(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        val currentPids = currentSystemUiPids(scan, env)
        val record = currentPids.mapNotNull(scan::restartRequired).maxByOrNull { it.time } ?: return null
        // 同一进程之后又成功热重载（重新加载了模块），说明已经恢复。
        val reloadedAfter = scan.latestLoadInPid(record.pid)?.let { it.record.time > record.time } == true
        if (reloadedAfter) return null
        return LogFinding.SystemUiRestartRequired(listOf(LogEvidence.Line(record.raw)))
    }

    /** 规则 1：系统界面里模块的某个部分启动失败，同一进程之后没再成功。 */
    private fun systemUiStartupFailed(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        if (env.logSource != ModuleLogSourceStatus.Ok) return null
        val pids = currentSystemUiPids(scan, env)
        val pairs = listOf(
            Triple(StartupComponent.SYSTEM_ENVIRONMENT, LogSignal.SYSTEM_ENVIRONMENT_FAILED, LogSignal.SYSTEM_ENVIRONMENT_READY),
            Triple(StartupComponent.LYRICON_CENTRAL, LogSignal.LYRICON_CENTRAL_FAILED, LogSignal.LYRICON_CENTRAL_STARTED),
            Triple(StartupComponent.LYRIC_SOURCE, LogSignal.LYRIC_SOURCE_FAILED, LogSignal.LYRIC_SOURCE_STARTED),
            Triple(StartupComponent.ISLAND_PLUGIN, LogSignal.ISLAND_PLUGIN_FAILED, LogSignal.ISLAND_PLUGIN_READY),
        )
        val components = mutableListOf<StartupComponent>()
        val evidence = mutableListOf<LogEvidence>()
        pairs.forEach { (component, failed, ready) ->
            val failure = currentSignal(scan, pids, failed) ?: return@forEach
            // 同一进程之后又成功了（例如系统环境重试成功、超级岛插件重新加载），不算。
            if (newerThan(currentSignal(scan, pids, ready), failure)) return@forEach
            components += component
            evidence += LogEvidence.Line(failure.raw)
        }
        if (components.isEmpty()) return null
        return LogFinding.SystemUiStartupFailed(components, evidence, env.debugBuild)
    }

    /** 规则 7：插件文件缺失或损坏。直接读插件安装状态，不靠日志（Release 包不写插件相关的应用日志）。 */
    private fun pluginNeedsRepair(env: LogAnalysisEnvironment): LogFinding? {
        val plugins = env.pluginsNeedingRepair
        if (plugins.isEmpty()) return null
        return LogFinding.PluginNeedsRepair(
            pluginNames = plugins.map { it.name },
            evidence = plugins.map { LogEvidence.PluginFileBroken(it.name) },
        )
    }

    /** 规则 2：AI 翻译。只在开关打开时检查；配置缺项看现场状态，请求失败看系统界面当前进程的日志。 */
    private fun aiTranslationFailing(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        val settings = env.aiTranslation ?: return null
        if (settings.apiKeyBlank || settings.targetLanguageBlank) {
            return LogFinding.AiTranslationFailing(
                problem = AiTranslationProblem.MISSING_CONFIG,
                httpCode = null,
                evidence = listOf(LogEvidence.AiTranslationConfigIncomplete),
            )
        }
        val pids = currentSystemUiPids(scan, env)
        val failure = currentSignal(scan, pids, LogSignal.AI_TRANSLATION_FAILED) ?: return null
        // 失败之后又成功过：翻译已经恢复。
        if (newerThan(currentSignal(scan, pids, LogSignal.AI_TRANSLATION_SUCCEEDED), failure)) return null
        // 失败之后改过 AI 翻译的任何设置：等下一次请求再看，先不报。
        val changedAfter = pids.any { pid ->
            newerThan(scan.latestConfigBroadcastWithPrefix(AI_TRANSLATION_KEY_PREFIX, pid), failure)
        }
        if (changedAfter) return null
        val (problem, code) = classifyAiFailure(failure.message)
        return LogFinding.AiTranslationFailing(problem, code, listOf(LogEvidence.Line(failure.raw)))
    }

    internal fun classifyAiFailure(message: String): Pair<AiTranslationProblem, Int?> {
        val code = Regex("""code=(\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            code == 401 || code == 403 -> AiTranslationProblem.AUTH to code
            code == 429 -> AiTranslationProblem.RATE_LIMIT to code
            code != null -> AiTranslationProblem.HTTP_ERROR to code
            message.startsWith("翻译响应缺少 JSON") || message.startsWith("翻译响应为空") ->
                AiTranslationProblem.BAD_RESPONSE to null
            else -> AiTranslationProblem.NETWORK to null
        }
    }

    /** 规则 3：填了自定义字体，系统界面读不到这个文件。 */
    private fun customFontUnreadable(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        val path = env.customFontPath?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val pids = currentSystemUiPids(scan, env)
        val failure = pids.flatMap { pid ->
            scan.customFontFailures(pid).filter { it.time >= generationStart(scan, pid) }
        }.filter { record ->
            // 文案是「…：<路径> (存在…」或「…：<路径>，原因…」，只认与当前设置完全相同的路径。
            val logged = record.message.substringAfter('：')
                .substringBefore(" (存在")
                .substringBefore("，原因")
                .trim()
            logged == path
        }.maxByOrNull { it.time } ?: return null
        val changedAfter = pids.any { pid -> newerThan(scan.latestConfigBroadcast(CUSTOM_FONT_PATH_KEY, pid), failure) }
        if (changedAfter) return null
        return LogFinding.CustomFontUnreadable(path, listOf(LogEvidence.Line(failure.raw)))
    }

    /** 规则 4：选了 SuperLyric，但服务不在、启动时已回退，或注册接收端失败。 */
    private fun superLyricUnavailable(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        if (env.lyricSource != SUPER_LYRIC_SOURCE) return null
        val service = env.superLyricServiceAvailable
        if (service == false) {
            return LogFinding.SuperLyricUnavailable(
                problem = SuperLyricProblem.SERVICE_MISSING,
                fellBackTo = null,
                serviceNowAvailable = false,
                evidence = listOf(LogEvidence.SuperLyricServiceCheck(found = false)),
            )
        }
        if (env.logSource != ModuleLogSourceStatus.Ok) return null
        val pids = currentSystemUiPids(scan, env)
        val selected = currentSignal(scan, pids, LogSignal.LYRIC_SOURCE_SELECTED)
        val registerFailed = currentSignal(scan, pids, LogSignal.SUPER_LYRIC_REGISTER_FAILED)
        val selectedName = selected?.message?.let(::selectedSourceName)
        val problem = when {
            registerFailed != null && (selected == null || registerFailed.time >= selected.time) ->
                SuperLyricProblem.REGISTER_FAILED
            selected != null && selectedName != SUPER_LYRIC_DISPLAY_NAME -> SuperLyricProblem.FELL_BACK
            else -> return null
        }
        val evidence = buildList {
            selected?.let { add(LogEvidence.Line(it.raw)) }
            registerFailed?.takeIf { problem == SuperLyricProblem.REGISTER_FAILED }?.let { add(LogEvidence.Line(it.raw)) }
            service?.let { add(LogEvidence.SuperLyricServiceCheck(found = it)) }
        }
        return LogFinding.SuperLyricUnavailable(
            problem = problem,
            fellBackTo = selectedName?.takeIf { problem == SuperLyricProblem.FELL_BACK },
            serviceNowAvailable = service == true,
            evidence = evidence,
        )
    }

    /** 「启动歌词源: SuperLyric」→ SuperLyric；没选到源时为 null。 */
    private fun selectedSourceName(message: String): String? = when {
        message.startsWith(LogSignal.SOURCE_STARTED_PREFIX) -> message.removePrefix(LogSignal.SOURCE_STARTED_PREFIX).trim()
        message.startsWith(LogSignal.SOURCE_SWITCHED_PREFIX) -> message.removePrefix(LogSignal.SOURCE_SWITCHED_PREFIX).trim()
        else -> null
    }

    private const val SUPER_LYRIC_DISPLAY_NAME = "SuperLyric"

    /** 规则 5：开了某个功能，但宿主里对应的 Hook 装不上（系统没有这个接口），或系统里没有英数窄字体文件。 */
    private fun featureUnsupported(
        scan: ModuleLogScan,
        env: LogAnalysisEnvironment,
        feature: SystemFeature,
    ): LogFinding? {
        if (!env.featureSwitches.isOn(feature)) return null
        if (env.logSource != ModuleLogSourceStatus.Ok) return null
        val pids = if (feature.hookHost == HookHost.SETTINGS) currentSettingsPids(scan, env) else currentSystemUiPids(scan, env)
        val failure: ModuleLogRecord = when (feature) {
            SystemFeature.NARROW_LATIN_FONT -> {
                val record = currentSignal(scan, pids, LogSignal.NARROW_FONT_FAILED) ?: return null
                // 失败后又改过这个开关：等系统界面重新读字体再看。
                if (pids.any { newerThan(scan.latestConfigBroadcast(NARROW_LATIN_FONT_KEY, it), record) }) return null
                record
            }
            else -> {
                val (failed, ready) = hookSignals(feature)
                val record = currentSignal(scan, pids, failed) ?: return null
                // 这些 Hook 会对多个类加载器各装一次，只要当前进程里有一次装成功就说明功能可用。
                // 通知中心原生背景接口没有单独的成功行，只认失败行。
                if (ready != null && currentSignal(scan, pids, ready) != null) return null
                record
            }
        }
        return LogFinding.FeatureUnsupported(feature, listOf(LogEvidence.Line(failure.raw)), env.debugBuild)
    }

    private fun hookSignals(feature: SystemFeature): Pair<LogSignal, LogSignal?> = when (feature) {
        SystemFeature.ISLAND_COVER_STYLE -> LogSignal.ISLAND_COVER_STYLE_FAILED to LogSignal.ISLAND_COVER_STYLE_READY
        SystemFeature.MUSIC_WAVE_COLOR -> LogSignal.MUSIC_WAVE_COLOR_FAILED to LogSignal.MUSIC_WAVE_COLOR_READY
        SystemFeature.NOTIFICATION_MEDIA_COVER -> LogSignal.NOTIFICATION_MEDIA_COVER_FAILED to LogSignal.NOTIFICATION_MEDIA_COVER_READY
        SystemFeature.SETTINGS_ENTRY -> LogSignal.SETTINGS_ENTRY_FAILED to LogSignal.SETTINGS_ENTRY_READY
        // 主进程和息屏显示两条路径，任一路径装成功就不报，避免把另一条用不上的路径当成失效。
        SystemFeature.AOD_LYRICS -> LogSignal.AOD_LYRICS_FAILED to LogSignal.AOD_LYRICS_READY
        SystemFeature.ISLAND_EXPANDED_FLOW -> LogSignal.ISLAND_EXPANDED_FLOW_FAILED to LogSignal.ISLAND_EXPANDED_FLOW_READY
        SystemFeature.NOTIFICATION_MEDIA_CARD -> LogSignal.NOTIFICATION_MEDIA_CARD_FAILED to LogSignal.NOTIFICATION_MEDIA_CARD_READY
        SystemFeature.NOTIFICATION_MEDIA_BACKGROUND -> LogSignal.NOTIFICATION_MEDIA_BACKGROUND_FAILED to null
        SystemFeature.ISLAND_GLOW -> LogSignal.ISLAND_GLOW_FAILED to LogSignal.ISLAND_GLOW_READY
        SystemFeature.NARROW_LATIN_FONT -> error("英数窄字体不是 Hook")
    }

    /**
     * 设置 App 当前的 pid。设置不常驻，没在运行时用它最近一次加载当前版本模块的进程：
     * Hook 点位是否失效只取决于系统版本和模块版本，进程退出后结论不变。
     */
    private fun currentSettingsPids(scan: ModuleLogScan, env: LogAnalysisEnvironment): Set<Int> {
        val running = env.runningTargets
            ?.filter { it.processName == SETTINGS }
            ?.mapTo(hashSetOf()) { it.pid }
            ?: env.pidsOfProcess(SETTINGS)
        if (!running.isNullOrEmpty()) return running
        val load = scan.latestLoads()[SETTINGS] ?: return emptySet()
        return if (load.versionCode == env.installedVersionCode) setOf(load.record.pid) else emptySet()
    }

    /** 规则 8：开了「跟随音乐真实律动」，系统界面当前进程最近一次状态是启动失败或拿不到频谱。 */
    private fun realRhythmNoSpectrum(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        if (!env.featureSwitches.realRhythm) return null
        if (env.logSource != ModuleLogSourceStatus.Ok) return null
        val pids = currentSystemUiPids(scan, env)
        val state = currentSignal(scan, pids, LogSignal.REAL_RHYTHM_STATE) ?: return null
        val name = state.message.removePrefix(LogSignal.REAL_RHYTHM_STATE_PREFIX).substringBefore(' ')
        val problem = when (name) {
            "START_FAILED" -> RealRhythmProblem.START_FAILED
            "NO_CALLBACK", "NO_ENERGY" -> RealRhythmProblem.NO_SPECTRUM
            else -> return null
        }
        // 状态之后改过律动模式：等下一次状态再看。
        if (pids.any { newerThan(scan.latestConfigBroadcast(REAL_RHYTHM_MODE_KEY, it), state) }) return null
        val evidence = buildList<LogEvidence> {
            if (problem == RealRhythmProblem.START_FAILED) {
                currentSignal(scan, pids, LogSignal.REAL_RHYTHM_START_FAILED)?.let { add(LogEvidence.Line(it.raw)) }
            }
            add(LogEvidence.Line(state.raw))
        }
        return LogFinding.RealRhythmNoSpectrum(problem, evidence)
    }

    /**
     * 规则 9：开了 AI 翻译，系统界面当前进程里翻译缓存读或写失败，且之后没有同一类的恢复记录。
     * 缓存每次读写都会重新取数据库、失败不会卡住，清出空间后下一次读写就恢复，不用重启。
     */
    private fun translationCacheFailing(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        if (env.aiTranslation == null) return null
        if (env.logSource != ModuleLogSourceStatus.Ok) return null
        val pids = currentSystemUiPids(scan, env)
        val failures = listOf(
            LogSignal.TRANSLATION_CACHE_READ_FAILED to LogSignal.TRANSLATION_CACHE_READ_RECOVERED,
            LogSignal.TRANSLATION_CACHE_WRITE_FAILED to LogSignal.TRANSLATION_CACHE_WRITE_RECOVERED,
        ).mapNotNull { (failed, recovered) ->
            currentSignal(scan, pids, failed)?.takeUnless { newerThan(currentSignal(scan, pids, recovered), it) }
        }
        if (failures.isEmpty()) return null
        return LogFinding.TranslationCacheFailing(
            failures.sortedBy { it.time }.map { LogEvidence.Line(it.raw) },
            env.debugBuild,
        )
    }

    /**
     * 规则 10：各进程当前这一次运行里（最近一次加载模块之后）没有规则认得的报错和警告，分开计数。
     * 旧进程、热重载之前的行不算；还在跑旧版模块的进程不算（由「还在跑旧版模块」引导重启）；
     * 只在 debug 包写的诊断输出不算（见 [ModuleLogScan]）。
     */
    private fun unrecognizedProblems(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        if (env.logSource != ModuleLogSourceStatus.Ok) return null
        val systemUiPids = currentSystemUiPids(scan, env)
        var errors = 0
        var warnings = 0
        scan.pidsWithUnrecognizedProblems().forEach { (pid, process) ->
            val current = if (process == SYSTEM_UI) pid in systemUiPids else scan.latestRecord(process)?.pid == pid
            if (!current) return@forEach
            // 这个进程还在跑旧版模块（版本不对或拒绝了热重载）：先按「还在跑旧版模块」重启，旧版的报错不用反馈。
            if (runsStaleModule(scan, env, pid)) return@forEach
            scan.unrecognizedProblems(pid, generationStart(scan, pid)).forEach {
                if (it.isError) errors++ else warnings++
            }
        }
        if (errors == 0 && warnings == 0) return null
        return LogFinding.UnrecognizedProblems(errors, warnings, env.debugBuild)
    }

    private fun runsStaleModule(scan: ModuleLogScan, env: LogAnalysisEnvironment, pid: Int): Boolean {
        val load = scan.latestLoadInPid(pid)
        if (load != null && load.versionCode != env.installedVersionCode) return true
        val rejected = scan.hotReloadRejected(pid) ?: return false
        return load == null || load.record.time < rejected.time
    }

    private fun playerWithoutPlugin(scan: ModuleLogScan, env: LogAnalysisEnvironment): LogFinding? {
        val player = env.activePlayerPackage?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val definition = OfficialProviderCatalog.definitionForPackage(player) ?: return null
        if (definition.id in env.enabledPluginIds) return null
        if (!OfficialProviderCatalog.shouldShowInDownloadList(definition.id)) return null
        val installed = definition.id in env.installedPluginIds
        val evidence = buildList {
            add(LogEvidence.PlayingWithoutPlugin(player, definition.id, installed))
            scan.activeSessionBroadcast()?.takeIf { player in it.message }?.let { add(LogEvidence.Line(it.raw)) }
        }
        return LogFinding.PlayerWithoutPlugin(
            playerPackage = player,
            pluginId = definition.id,
            pluginName = definition.displayName,
            pluginInstalled = installed,
            evidence = evidence,
        )
    }

    private fun notificationAccessMissing(env: LogAnalysisEnvironment): LogFinding? {
        if (!env.dynamicIslandEnabled && !env.aodFocusNotificationEnabled) return null
        val missingPost = !env.hasPostNotificationPermission
        val missingListener = !env.hasNotificationListenerAccess
        if (!missingPost && !missingListener) return null
        return LogFinding.NotificationAccessMissing(
            dynamicIsland = env.dynamicIslandEnabled,
            aodFocusNotification = env.aodFocusNotificationEnabled,
            missingPostNotification = missingPost,
            missingNotificationListener = missingListener,
            evidence = listOf(LogEvidence.PermissionMissing(missingPost, missingListener)),
        )
    }

    /** 只处理能被「重启」修好的 App 进程（系统界面与音乐 App），不碰 system_server 等。 */
    private fun isAppProcess(process: String): Boolean {
        val packageName = process.substringBefore(':')
        return packageName == SYSTEM_UI ||
            packageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME ||
            OfficialProviderCatalog.definitionForPackage(packageName) != null
    }
}
