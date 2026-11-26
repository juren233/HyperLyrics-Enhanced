package com.juren233.hyperlyricsenhanced.utils.loganalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 日志分析每条规则的单测。日志样本逐字取自 Cavan 11-25 09:52 导出的全部日志（8.5.0-canary-250054）：
 * 那次更新后系统界面热重载成功，Apple Music 拒绝热重载（旧版本继续跑），随后两个进程都重启了。
 * 每条规则都同时验证「问题样本能认出来」和「正常日志不误报」。
 */
class LogAnalyzerTest {

    // ---- 真实日志行（6bd2764d-hyperlyricsenhanced_all_logs_20261125_0952.txt） ----
    private val sysUiReloadReady =
        "[ 2026-11-25T09:51:52.111     1000: 25246: 26038 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,629e-1a21048ff16-e4-556d,0,1] [HookEntry] 系统界面热重载准备完成"
    private val sysUiHotReloadedLoad =
        "[ 2026-11-25T09:51:52.382     1000: 25246: 26038 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,629e-1a21048ff16-e4-556e,0,1] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054"
    private val appleMusicRejected =
        "[ 2026-11-25T09:51:52.810    10361: 26164: 28331 I/LSPosedFramework ] (com.apple.android.music)[com.juren233.hyperlyricsenhanced,null,6634-1a2104940d2-111-16ed,0,1] [HookEntry] 当前宿主尚不具备完整热重载状态，需要重启对应进程"
    private val sysUiBroadcast =
        "[ 2026-11-25T09:51:52.908     1000: 25246: 25246 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,629e-1a21048ff16-2-58f9,0,1] [HookEntry] 收到配置广播并更新运行时覆盖: key=key_active_media_session_packages, value=\"1795571419301|com.apple.android.music\""
    private val sysUiRestartedLoad =
        "[ 2026-11-25T09:52:00.336     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-1,0,1] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054"
    private val appleMusicRestartedLoad =
        "[ 2026-11-25T09:52:13.493    10361: 25914: 25914 I/LSPosedFramework ] (com.apple.android.music)[com.juren233.hyperlyricsenhanced,null,653a-1a2106746b5-2-1,0,1] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054"
    private val sysUiLaterBroadcast =
        "[ 2026-11-25T09:52:54.569     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-1253,0,1] [HookEntry] 收到配置广播并更新运行时覆盖: key=key_active_media_session_packages, value=\"1795571574433|com.apple.android.music\""
    private val providerError =
        "[ 2026-11-25T09:24:37.496    10361: 26164: 32719 E/LSPosedFramework ] (com.apple.android.music)[com.juren233.hyperlyricsenhanced,null,6634-1a2104940d2-121-16eb,0,1] [OfficialProviderHookHost] 官方 Provider 拦截回调异常，已回退原调用结果: target=public final jk.D sb.a.a(ok.f) error=null"
    private val lyriconDebugWarning =
        "[ 2026-11-25T09:20:43.679     1000: 25246: 25246 W/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,629e-1a21048ff16-2-1966,0,1] [LyriconSource] [debug] 来源侧播放态已转发为提示: playing=true"
    private val appDebugLine =
        "11-25 08:32:07.536 I/PrefsBridge: local_write key=key_active_media_session_packages type=string value=\"1795566727535|com.apple.android.music\""

    /** 09:51:53 那一刻手机上能读到的日志：Apple Music 刚拒绝热重载、还没被重启。 */
    private val beforeRestart = listOf(
        lyriconDebugWarning, providerError, sysUiReloadReady, sysUiHotReloadedLoad, appleMusicRejected, sysUiBroadcast,
    )

    /** 导出时（09:52）的完整顺序：两个进程都已重启并加载了 250054。 */
    private val afterRestart = beforeRestart + listOf(sysUiRestartedLoad, appleMusicRestartedLoad, sysUiLaterBroadcast)

    private fun scan(lines: List<String>) = ModuleLogScan().apply { lines.forEach(::acceptLine) }

    /** Cavan 那台手机当时的正常状态（插件、作用域取自 12:57 导出的环境信息）。 */
    private fun healthyEnv(
        installed: Int = 250054,
        pids: Map<String, Set<Int>?> = mapOf("com.android.systemui" to setOf(24949), "com.apple.android.music" to setOf(25914)),
    ) = LogAnalysisEnvironment(
        installedVersionCode = installed,
        logSource = ModuleLogSourceStatus.Ok,
        moduleServiceBound = true,
        scope = setOf("com.android.systemui", "miui.systemui.plugin", "com.apple.android.music", "com.netease.cloudmusic"),
        pluginScopeTargets = setOf("com.apple.android.music", "com.netease.cloudmusic"),
        runningTargets = null,
        pidsOfProcess = { pids[it] },
        activePlayerPackage = "com.apple.android.music",
        installedPluginIds = setOf("applemusic", "netease", "qqmusic", "kugou", "kuwo", "spotify", "qishui"),
        enabledPluginIds = setOf("applemusic", "netease", "qqmusic", "kugou", "kuwo", "spotify", "qishui"),
        dynamicIslandEnabled = false,
        aodFocusNotificationEnabled = true,
        hasPostNotificationPermission = true,
        hasNotificationListenerAccess = true,
    )

    private inline fun <reified T : LogFinding> List<LogFinding>.single(): T =
        filterIsInstance<T>().also { assertEquals("应只有一条 ${T::class.simpleName}: $this", 1, it.size) }.first()

    // ---------------- 解析 ----------------

    @Test
    fun parser_readsRealModuleLine() {
        val record = ModuleLogLineParser.parse(appleMusicRejected)!!
        assertEquals("2026-11-25T09:51:52.810", record.time)
        assertEquals(10361, record.uid)
        assertEquals(26164, record.pid)
        assertEquals('I', record.level)
        assertEquals("com.apple.android.music", record.process)
        assertEquals("HookEntry", record.tag)
        assertEquals(ModuleLogScan.HOT_RELOAD_REJECTED, record.message)
        assertEquals(appleMusicRejected, record.raw)
    }

    @Test
    fun parser_ignoresAppDebugLogAndContinuationLines() {
        assertNull(ModuleLogLineParser.parse(appDebugLine))
        assertNull(ModuleLogLineParser.parse("    at com.juren233.hyperlyricsenhanced.root.HookEntry.onModuleLoaded(HookEntry.kt:315)"))
        assertNull(ModuleLogLineParser.parse(""))
    }

    @Test
    fun scan_readsVersionFromLoadLine() {
        val load = scan(afterRestart).latestLoads()["com.android.systemui"]!!
        assertEquals("8.5.0-canary", load.versionName)
        assertEquals(250054, load.versionCode)
        assertEquals(24949, load.record.pid)
    }

    /** 文案和 HookEntry 源码逐字一致，源码改了文案这里会先失败，规则不会悄悄失效。 */
    @Test
    fun matchedMessages_existVerbatimInHookEntrySource() {
        val source = File("src/main/java/com/juren233/hyperlyricsenhanced/root/HookEntry.kt").readText()
        val messages = listOf(ModuleLogScan.MODULE_LOADED_PREFIX, ModuleLogScan.HOT_RELOAD_REJECTED) +
            ModuleLogScan.RESTART_SYSTEM_UI_MESSAGES +
            "收到配置广播并更新运行时覆盖: key="
        messages.forEach { assertTrue("HookEntry.kt 里找不到「$it」", source.contains(it)) }
    }

    // ---------------- 正常日志不误报 ----------------

    @Test
    fun healthyRealLog_noFindings() {
        assertEquals(emptyList<LogFinding>(), LogAnalyzer.analyze(scan(afterRestart), healthyEnv()))
    }

    @Test
    fun healthyRealLog_pidsUnknown_noFindings() {
        // 查不到 pid 时也只靠日志里「之后换了新 pid 重新加载」判断已重启。
        val env = healthyEnv(pids = emptyMap())
        assertEquals(emptyList<LogFinding>(), LogAnalyzer.analyze(scan(afterRestart), env))
    }

    /** 本机有完整导出日志时，用整份真实日志再跑一遍（CI 上没有这份文件，会跳过）。 */
    @Test
    fun fullRealExport_noFindings() {
        val file = File("/mnt/project-files/aod-anim-test/logs/6bd2764d-hyperlyricsenhanced_all_logs_20261125_0952.txt")
        assumeTrue(file.exists())
        val scan = ModuleLogScan()
        file.useLines { lines -> lines.forEach(scan::acceptLine) }
        assertTrue("应扫到大量模块日志: ${scan.recordCount}", scan.recordCount > 20_000)
        assertEquals(emptyList<LogFinding>(), LogAnalyzer.analyze(scan, healthyEnv()))
    }

    // ---------------- 更新后进程仍跑旧版本 ----------------

    @Test
    fun stale_realHotReloadRejection_flagsAppleMusicOnly() {
        val env = healthyEnv(pids = mapOf("com.android.systemui" to setOf(25246), "com.apple.android.music" to setOf(26164)))
        val finding = LogAnalyzer.analyze(scan(beforeRestart), env).single<LogFinding.StaleModule>()
        assertEquals(listOf("com.apple.android.music"), finding.processes.map { it.process })
        val process = finding.processes.single()
        assertTrue(process.confirmedRunning)
        // 旧版本的加载记录已被日志轮转掉，不知道具体版本号
        assertNull(process.loadedVersionCode)
        assertTrue(finding.evidence.contains(LogEvidence.Line(appleMusicRejected)))
        assertEquals(listOf<LogFixAction>(LogFixAction.RestartProcesses(listOf("com.apple.android.music"))), finding.actions)
    }

    @Test
    fun stale_realHotReloadRejection_pidUnknown_stillFlaggedAsPossible() {
        val env = healthyEnv(pids = emptyMap())
        val process = LogAnalyzer.analyze(scan(beforeRestart), env).single<LogFinding.StaleModule>().processes.single()
        assertEquals("com.apple.android.music", process.process)
        assertFalse(process.confirmedRunning)
    }

    @Test
    fun stale_rejectedProcessAlreadyGone_notFlagged() {
        // 日志还停在拒绝热重载，但 26164 已经不在了（被系统或用户结束）。
        val env = healthyEnv(pids = mapOf("com.android.systemui" to setOf(25246), "com.apple.android.music" to emptySet()))
        assertTrue(LogAnalyzer.analyze(scan(beforeRestart), env).none { it is LogFinding.StaleModule })
    }

    @Test
    fun stale_installedNewerThanLoaded_flagsBoth() {
        // 模拟又装了 250055、热重载记录还没写进日志：两个进程都还在跑 250054。
        val findings = LogAnalyzer.analyze(scan(afterRestart), healthyEnv(installed = 250055))
        val finding = findings.single<LogFinding.StaleModule>()
        assertEquals(listOf("com.android.systemui", "com.apple.android.music"), finding.processes.map { it.process })
        assertTrue(finding.processes.all { it.loadedVersionCode == 250054L && it.confirmedRunning })
        assertTrue(finding.evidence.contains(LogEvidence.Line(sysUiRestartedLoad)))
        assertTrue(finding.evidence.contains(LogEvidence.Line(appleMusicRestartedLoad)))
    }

    @Test
    fun stale_installedNewer_butProcessesRestartedSinceLog_notFlagged() {
        val env = healthyEnv(
            installed = 250055,
            pids = mapOf("com.android.systemui" to setOf(30001), "com.apple.android.music" to setOf(30002)),
        )
        assertTrue(LogAnalyzer.analyze(scan(afterRestart), env).none { it is LogFinding.StaleModule })
    }

    @Test
    fun stale_runningTargetsFromFramework_takePriority() {
        val env = healthyEnv().copy(
            runningTargets = listOf(
                RunningHookTarget("com.android.systemui", 24949, 250054, RunningHookTarget.State.UP_TO_DATE),
                RunningHookTarget("com.apple.android.music", 26164, 250053, RunningHookTarget.State.STALE),
                RunningHookTarget("com.netease.cloudmusic", 1234, 250053, RunningHookTarget.State.RELOADING),
                RunningHookTarget("system_server", 1000, 250053, RunningHookTarget.State.STALE),
            ),
        )
        val finding = LogAnalyzer.analyze(scan(beforeRestart), env).single<LogFinding.StaleModule>()
        assertEquals(listOf("com.apple.android.music"), finding.processes.map { it.process })
        assertEquals(250053L, finding.processes.single().loadedVersionCode)
        assertTrue(finding.evidence.contains(LogEvidence.Line(appleMusicRejected)))
    }

    @Test
    fun stale_runningTargetsAllUpToDate_noFinding() {
        val env = healthyEnv().copy(
            runningTargets = listOf(
                RunningHookTarget("com.android.systemui", 24949, 250054, RunningHookTarget.State.UP_TO_DATE),
                RunningHookTarget("com.apple.android.music", 25914, 250054, RunningHookTarget.State.UP_TO_DATE),
            ),
        )
        // 即使日志里还停在「拒绝热重载」，框架说已是新版本就不报。
        assertTrue(LogAnalyzer.analyze(scan(beforeRestart), env).none { it is LogFinding.StaleModule })
    }

    // ---------------- 模块未生效 ----------------

    @Test
    fun moduleInactive_serviceNotBound_reportedWithLastLoad() {
        val env = healthyEnv().copy(moduleServiceBound = false, scope = null)
        val findings = LogAnalyzer.analyze(scan(afterRestart), env)
        val finding = findings.single<LogFinding.ModuleInactive>()
        assertEquals(LogFindingSeverity.ERROR, finding.severity)
        assertTrue(finding.evidence.contains(LogEvidence.Line(appleMusicRestartedLoad)))
        assertTrue(findings.none { it is LogFinding.ScopeMissing })
    }

    @Test
    fun moduleInactive_serviceBound_notReported() {
        assertTrue(LogAnalyzer.analyze(scan(afterRestart), healthyEnv()).none { it is LogFinding.ModuleInactive })
    }

    // ---------------- 作用域缺失 ----------------

    @Test
    fun scopeMissing_systemUi_isErrorWithOneTapRequest() {
        val env = healthyEnv().copy(scope = setOf("com.apple.android.music", "com.netease.cloudmusic"))
        // 只剩 Apple Music 的日志：系统界面没勾作用域时就是这样
        val finding = LogAnalyzer.analyze(scan(listOf(appleMusicRestartedLoad)), env).single<LogFinding.ScopeMissing>()
        assertEquals(listOf("com.android.systemui"), finding.packages)
        assertEquals(LogFindingSeverity.ERROR, finding.severity)
        assertEquals(listOf<LogFixAction>(LogFixAction.RequestScope(listOf("com.android.systemui"))), finding.actions)
        assertTrue(finding.evidence.contains(LogEvidence.NoRecordsFrom("com.android.systemui", 1)))
    }

    @Test
    fun scopeMissing_pluginTarget_isWarning() {
        val env = healthyEnv().copy(scope = setOf("com.android.systemui", "com.apple.android.music"))
        val finding = LogAnalyzer.analyze(scan(afterRestart), env).single<LogFinding.ScopeMissing>()
        assertEquals(listOf("com.netease.cloudmusic"), finding.packages)
        assertEquals(LogFindingSeverity.WARNING, finding.severity)
    }

    @Test
    fun scopeMissing_scopeUnreadable_notReported() {
        val env = healthyEnv().copy(scope = null)
        assertTrue(LogAnalyzer.analyze(scan(afterRestart), env).none { it is LogFinding.ScopeMissing })
    }

    // ---------------- 系统界面要求重启 ----------------

    /**
     * 两份真实日志里都没出现过这三条错误，样本按真实行的格式、用 HookEntry 源码里的原文拼出来，
     * 上面的 matchedMessages_existVerbatimInHookEntrySource 保证原文和源码一致。
     */
    private fun sysUiError(time: String, pid: Int, message: String) =
        "[ 2026-11-25T$time     1000: $pid: $pid E/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-2,0,1] [HookEntry] $message"

    @Test
    fun systemUiRestartRequired_errorInCurrentProcess_reported() {
        ModuleLogScan.RESTART_SYSTEM_UI_MESSAGES.forEach { message ->
            val error = sysUiError("09:53:10.000", 24949, message)
            val finding = LogAnalyzer.analyze(scan(afterRestart + error), healthyEnv())
                .single<LogFinding.SystemUiRestartRequired>()
            assertEquals(listOf(LogEvidence.Line(error)), finding.evidence)
            assertEquals(listOf<LogFixAction>(LogFixAction.RestartProcesses(listOf("com.android.systemui"))), finding.actions)
        }
    }

    @Test
    fun systemUiRestartRequired_errorInOldProcess_notReported() {
        // 错误出在已重启掉的 25246，现在的系统界面是 24949。
        val error = sysUiError("09:51:52.500", 25246, ModuleLogScan.RESTART_SYSTEM_UI_MESSAGES[1])
        assertTrue(LogAnalyzer.analyze(scan(afterRestart + error), healthyEnv()).none { it is LogFinding.SystemUiRestartRequired })
    }

    @Test
    fun systemUiRestartRequired_reloadedAfterError_notReported() {
        // 25246 先报错，随后同一进程热重载成功（09:51:52.382 的加载记录）。
        val error = sysUiError("09:51:52.200", 25246, ModuleLogScan.RESTART_SYSTEM_UI_MESSAGES[0])
        val env = healthyEnv(pids = mapOf("com.android.systemui" to setOf(25246), "com.apple.android.music" to setOf(26164)))
        assertTrue(LogAnalyzer.analyze(scan(beforeRestart + error), env).none { it is LogFinding.SystemUiRestartRequired })
    }

    @Test
    fun systemUiRestartRequired_sameMessageAsWarning_notReported() {
        // 只认 E 级：同样的字出现在别的级别不算。
        val line = sysUiError("09:53:10.000", 24949, ModuleLogScan.RESTART_SYSTEM_UI_MESSAGES[1]).replace(" E/", " W/")
        assertTrue(LogAnalyzer.analyze(scan(afterRestart + line), healthyEnv()).none { it is LogFinding.SystemUiRestartRequired })
    }

    // ---------------- 正在播放的 App 没有启用插件 ----------------

    @Test
    fun playerWithoutPlugin_notInstalled_pointsToDownloads() {
        val env = healthyEnv().copy(
            installedPluginIds = setOf("netease"),
            enabledPluginIds = setOf("netease"),
        )
        val finding = LogAnalyzer.analyze(scan(afterRestart), env).single<LogFinding.PlayerWithoutPlugin>()
        assertEquals("applemusic", finding.pluginId)
        assertFalse(finding.pluginInstalled)
        assertEquals(listOf<LogFixAction>(LogFixAction.OpenPluginDownloads), finding.actions)
        // 真实日志里系统界面收到的「正在播放 Apple Music」广播作为依据
        assertTrue(finding.evidence.contains(LogEvidence.Line(sysUiLaterBroadcast)))
    }

    @Test
    fun playerWithoutPlugin_installedButDisabled_pointsToManager() {
        val env = healthyEnv().copy(enabledPluginIds = setOf("netease"))
        val finding = LogAnalyzer.analyze(scan(afterRestart), env).single<LogFinding.PlayerWithoutPlugin>()
        assertTrue(finding.pluginInstalled)
        assertEquals(listOf<LogFixAction>(LogFixAction.OpenPluginManager), finding.actions)
    }

    @Test
    fun playerWithoutPlugin_unknownOrNoPlayer_notReported() {
        listOf("com.ss.android.ugc.aweme", null, "").forEach { player ->
            val env = healthyEnv().copy(activePlayerPackage = player, installedPluginIds = emptySet(), enabledPluginIds = emptySet())
            assertTrue(LogAnalyzer.analyze(scan(afterRestart), env).none { it is LogFinding.PlayerWithoutPlugin })
        }
    }

    // ---------------- 通知权限 ----------------

    @Test
    fun notificationAccess_listenerMissing_forAod() {
        val env = healthyEnv().copy(hasNotificationListenerAccess = false)
        val finding = LogAnalyzer.analyze(scan(afterRestart), env).single<LogFinding.NotificationAccessMissing>()
        assertTrue(finding.aodFocusNotification)
        assertFalse(finding.dynamicIsland)
        assertEquals(listOf<LogFixAction>(LogFixAction.OpenNotificationListenerSettings), finding.actions)
    }

    @Test
    fun notificationAccess_bothMissing_forDynamicIsland() {
        val env = healthyEnv().copy(
            dynamicIslandEnabled = true,
            aodFocusNotificationEnabled = false,
            hasPostNotificationPermission = false,
            hasNotificationListenerAccess = false,
        )
        val finding = LogAnalyzer.analyze(scan(afterRestart), env).single<LogFinding.NotificationAccessMissing>()
        assertEquals(
            listOf(LogFixAction.OpenAppNotificationSettings, LogFixAction.OpenNotificationListenerSettings),
            finding.actions,
        )
    }

    @Test
    fun notificationAccess_featuresOff_notReported() {
        val env = healthyEnv().copy(
            dynamicIslandEnabled = false,
            aodFocusNotificationEnabled = false,
            hasPostNotificationPermission = false,
            hasNotificationListenerAccess = false,
        )
        assertTrue(LogAnalyzer.analyze(scan(afterRestart), env).none { it is LogFinding.NotificationAccessMissing })
    }

    // ---------------- 日志读不到 ----------------

    @Test
    fun logsUnreadable_noRoot_reportedAndOtherRulesStillRun() {
        val env = healthyEnv(pids = emptyMap()).copy(
            logSource = ModuleLogSourceStatus.NoRoot,
            hasNotificationListenerAccess = false,
        )
        val findings = LogAnalyzer.analyze(ModuleLogScan(), env)
        assertEquals(ModuleLogSourceStatus.NoRoot, findings.single<LogFinding.LogsUnreadable>().status)
        assertNotNull(findings.filterIsInstance<LogFinding.NotificationAccessMissing>().singleOrNull())
    }

    @Test
    fun findings_errorsSortedBeforeWarnings() {
        val env = healthyEnv(installed = 250055).copy(hasNotificationListenerAccess = false)
        val severities = LogAnalyzer.analyze(scan(afterRestart), env).map { it.severity }
        assertEquals(severities.sortedBy { it.ordinal }, severities)
        assertEquals(LogFindingSeverity.ERROR, severities.first())
        assertEquals(LogFindingSeverity.WARNING, severities.last())
    }

    // ---------------- 逐项检查清单（外显卡片的计数和状态都从这里来） ----------------

    private fun report(lines: List<String>, env: LogAnalysisEnvironment) = LogAnalyzer.report(scan(lines), env)

    private fun LogAnalysisReport.statusOf(check: LogCheck) = this[check].status

    @Test
    fun report_healthyRealLog_allEightPass() {
        val report = report(afterRestart, healthyEnv())
        assertEquals(LogCheck.entries, report.checks.map { it.check })
        assertTrue(report.checks.all { it.status == LogCheckStatus.PASS })
        assertEquals(LogAnalysisHealth.CLEAN, report.health)
        assertEquals(0, report.errorCount)
        assertEquals(0, report.warningCount)
        assertEquals(8, report.passCount)
        assertEquals(8, report.checkedCount)
    }

    @Test
    fun report_realHotReloadRejection_versionIsError() {
        val env = healthyEnv(pids = mapOf("com.android.systemui" to setOf(25246), "com.apple.android.music" to setOf(26164)))
        val report = report(beforeRestart, env)
        assertEquals(LogCheckStatus.ERROR, report.statusOf(LogCheck.VERSION))
        report[LogCheck.VERSION].findings.single<LogFinding.StaleModule>()
        assertEquals(LogAnalysisHealth.HAS_ERRORS, report.health)
        assertEquals(1, report.errorCount)
        assertEquals(0, report.warningCount)
        assertEquals(7, report.passCount)
    }

    @Test
    fun report_cavansMomentBeforeRestart_oneErrorTwoWarnings() {
        // 预览图里那一刻：Apple Music 拒绝热重载、作用域缺网易云、没给通知使用权。
        val env = healthyEnv(pids = mapOf("com.android.systemui" to setOf(25246), "com.apple.android.music" to setOf(26164))).copy(
            scope = setOf("com.android.systemui", "com.apple.android.music"),
            hasNotificationListenerAccess = false,
        )
        val report = report(beforeRestart, env)
        assertEquals(LogCheckStatus.ERROR, report.statusOf(LogCheck.VERSION))
        assertEquals(LogCheckStatus.WARNING, report.statusOf(LogCheck.SCOPE))
        assertEquals(LogCheckStatus.WARNING, report.statusOf(LogCheck.NOTIFICATION))
        assertEquals(1, report.errorCount)
        assertEquals(2, report.warningCount)
        assertEquals(5, report.passCount)
        assertEquals(LogAnalysisHealth.HAS_ERRORS, report.health)
    }

    @Test
    fun report_warningsOnly_healthIsWarningsOnly() {
        val env = healthyEnv().copy(
            scope = setOf("com.android.systemui", "com.apple.android.music"),
            hasNotificationListenerAccess = false,
        )
        val report = report(afterRestart, env)
        assertEquals(0, report.errorCount)
        assertEquals(2, report.warningCount)
        assertEquals(LogAnalysisHealth.WARNINGS_ONLY, report.health)
    }

    @Test
    fun report_scopeMissingSystemUi_scopeIsError() {
        val env = healthyEnv().copy(scope = setOf("com.apple.android.music", "com.netease.cloudmusic"))
        assertEquals(LogCheckStatus.ERROR, report(afterRestart, env).statusOf(LogCheck.SCOPE))
    }

    @Test
    fun report_errorAndWarningInSameCheck_checkCountsOnceAsError() {
        // 系统界面（报错）和网易云（警告）都缺作用域：同一项只算 1 个报错，不重复计数。
        val env = healthyEnv().copy(scope = setOf("com.apple.android.music"))
        val report = report(afterRestart, env)
        assertEquals(LogCheckStatus.ERROR, report.statusOf(LogCheck.SCOPE))
        assertEquals(1, report.errorCount)
        assertEquals(0, report.warningCount)
    }

    @Test
    fun report_moduleInactive_moduleErrorAndScopeSkipped() {
        val report = report(afterRestart, healthyEnv().copy(moduleServiceBound = false))
        assertEquals(LogCheckStatus.ERROR, report.statusOf(LogCheck.MODULE))
        assertEquals(LogCheckStatus.SKIPPED, report.statusOf(LogCheck.SCOPE))
        assertEquals(LogCheckSkipReason.MODULE_INACTIVE, report[LogCheck.SCOPE].skipReason)
        // 模块没生效时「运行状态」「运行版本」「歌词插件」「功能设置」都没法看，一起跳过：8 项里查了 3 项。
        assertEquals(LogCheckStatus.SKIPPED, report.statusOf(LogCheck.RUNTIME))
        assertEquals(3, report.checkedCount)
    }

    @Test
    fun report_moduleInactive_versionAndFeaturesSkippedNotPassed() {
        // 复现：模块没生效、日志能读时，「运行版本」「功能设置」原来显示「通过」（相关进程运行正常 / 已开的功能没发现问题）。
        val report = report(afterRestart, healthyEnv().copy(moduleServiceBound = false))
        listOf(LogCheck.VERSION, LogCheck.FEATURES).forEach { check ->
            assertEquals("$check", LogCheckStatus.SKIPPED, report.statusOf(check))
            assertEquals("$check", LogCheckSkipReason.MODULE_INACTIVE, report[check].skipReason)
        }
        // 还显示「通过」的只剩与模块是否生效无关的两项：通知权限、日志读取。
        assertEquals(
            setOf(LogCheck.NOTIFICATION, LogCheck.LOGS),
            report.checks.filter { it.status == LogCheckStatus.PASS }.map { it.check }.toSet(),
        )
    }

    @Test
    fun report_moduleInactive_pluginSkippedNotPassed() {
        // 复现：模块没生效、正在用 Apple Music 播放且插件已启用时，「歌词插件」原来显示「通过 · 插件运行正常」。
        val report = report(afterRestart, healthyEnv().copy(moduleServiceBound = false))
        assertEquals(LogCheckStatus.SKIPPED, report.statusOf(LogCheck.PLUGIN))
        assertEquals(LogCheckSkipReason.MODULE_INACTIVE, report[LogCheck.PLUGIN].skipReason)
    }

    @Test
    fun report_moduleInactiveAndNotPlaying_pluginReasonIsModuleInactive() {
        // 模块没生效又没在播放：先说模块未激活，不说「现在没有在播放」。
        val report = report(afterRestart, healthyEnv().copy(moduleServiceBound = false, activePlayerPackage = null))
        assertEquals(LogCheckSkipReason.MODULE_INACTIVE, report[LogCheck.PLUGIN].skipReason)
    }

    @Test
    fun report_moduleInactive_pluginProblemStillShown() {
        // 跳过只替换「通过」：在播的 App 有插件却没启用，这是现场能确认的问题，照样显示。
        val env = healthyEnv().copy(moduleServiceBound = false, enabledPluginIds = emptySet())
        assertEquals(LogCheckStatus.WARNING, report(afterRestart, env).statusOf(LogCheck.PLUGIN))
    }

    @Test
    fun report_moduleInactiveAndLogsUnreadable_moduleInactiveReasonWins() {
        // 两个原因都在时，和「运行」项一样先说模块没生效（那是要先处理的）。
        val env = healthyEnv().copy(moduleServiceBound = false, logSource = ModuleLogSourceStatus.NoRoot)
        val report = report(afterRestart, env)
        listOf(LogCheck.VERSION, LogCheck.RUNTIME, LogCheck.FEATURES).forEach { check ->
            assertEquals("$check", LogCheckSkipReason.MODULE_INACTIVE, report[check].skipReason)
        }
    }

    @Test
    fun report_moduleInactive_featureProblemStillShown() {
        // 跳过只替换「通过」：现场就能确认的问题（AI 翻译没填 API Key）照样显示。
        val env = healthyEnv().copy(
            moduleServiceBound = false,
            aiTranslation = AiTranslationSettings(apiKeyBlank = true, targetLanguageBlank = false),
        )
        assertEquals(LogCheckStatus.WARNING, report(afterRestart, env).statusOf(LogCheck.FEATURES))
    }

    @Test
    fun report_scopeUnreadable_scopeSkippedNotPassed() {
        val report = report(afterRestart, healthyEnv().copy(scope = null))
        assertEquals(LogCheckStatus.SKIPPED, report.statusOf(LogCheck.SCOPE))
        assertEquals(LogCheckSkipReason.SCOPE_UNKNOWN, report[LogCheck.SCOPE].skipReason)
        assertEquals(LogAnalysisHealth.CLEAN, report.health)
        assertEquals(7, report.passCount)
    }

    @Test
    fun report_logsUnreadable_versionSkippedAndLogsWarning() {
        val env = healthyEnv(pids = emptyMap()).copy(logSource = ModuleLogSourceStatus.NoRoot)
        val report = LogAnalyzer.report(ModuleLogScan(), env)
        assertEquals(LogCheckStatus.WARNING, report.statusOf(LogCheck.LOGS))
        assertEquals(LogCheckStatus.SKIPPED, report.statusOf(LogCheck.VERSION))
        assertEquals(LogCheckSkipReason.LOGS_UNREADABLE, report[LogCheck.VERSION].skipReason)
        // 读不到日志不等于「一切正常」。
        assertEquals(LogAnalysisHealth.WARNINGS_ONLY, report.health)
    }

    @Test
    fun report_logsUnreadable_butFrameworkReportsTargets_versionStillChecked() {
        val env = healthyEnv(pids = emptyMap()).copy(
            logSource = ModuleLogSourceStatus.NoRoot,
            runningTargets = listOf(
                RunningHookTarget("com.android.systemui", 24949, 250054, RunningHookTarget.State.UP_TO_DATE),
                RunningHookTarget("com.apple.android.music", 26164, 250053, RunningHookTarget.State.STALE),
            ),
        )
        assertEquals(LogCheckStatus.ERROR, LogAnalyzer.report(ModuleLogScan(), env).statusOf(LogCheck.VERSION))
    }

    @Test
    fun report_pluginCheck_skipReasons() {
        val notPlaying = report(afterRestart, healthyEnv().copy(activePlayerPackage = null))
        assertEquals(LogCheckSkipReason.NOT_PLAYING, notPlaying[LogCheck.PLUGIN].skipReason)
        val noPlugin = report(afterRestart, healthyEnv().copy(activePlayerPackage = "com.ss.android.ugc.aweme"))
        assertEquals(LogCheckSkipReason.PLAYER_HAS_NO_PLUGIN, noPlugin[LogCheck.PLUGIN].skipReason)
        val missing = report(afterRestart, healthyEnv().copy(installedPluginIds = setOf("netease"), enabledPluginIds = setOf("netease")))
        assertEquals(LogCheckStatus.WARNING, missing.statusOf(LogCheck.PLUGIN))
    }

    @Test
    fun report_featuresOff_notificationSkipped() {
        val env = healthyEnv().copy(
            dynamicIslandEnabled = false,
            aodFocusNotificationEnabled = false,
            hasNotificationListenerAccess = false,
        )
        val report = report(afterRestart, env)
        assertEquals(LogCheckSkipReason.FEATURES_OFF, report[LogCheck.NOTIFICATION].skipReason)
        assertEquals(LogAnalysisHealth.CLEAN, report.health)
    }

    @Test
    fun report_failed_isWarningNotClean() {
        val report = LogAnalysisReport.failed("boom")
        assertEquals(LogCheckStatus.WARNING, report.statusOf(LogCheck.LOGS))
        assertTrue(report.checks.filter { it.check != LogCheck.LOGS }.all { it.skipReason == LogCheckSkipReason.ANALYSIS_FAILED })
        assertEquals(LogAnalysisHealth.WARNINGS_ONLY, report.health)
        assertEquals(1, report.warningCount)
    }
}
