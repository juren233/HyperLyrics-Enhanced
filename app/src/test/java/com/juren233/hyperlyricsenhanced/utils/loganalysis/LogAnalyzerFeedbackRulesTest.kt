package com.juren233.hyperlyricsenhanced.utils.loganalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-26 Cavan 第二轮意见对应的规则：
 * 「跟随音乐真实律动」拿不到频谱、AI 翻译缓存读写失败、没有规则认得的报错 / 警告只计数并引导反馈。
 *
 * 正常行逐字取自 Cavan 11-25 09:52 导出（系统界面 24949 在 09:52:00.336 加载 250054）；
 * 真实律动 NO_ENERGY 行取自 11-24 21:42 的导出（b66ad3bd），只换了 pid 和时间。
 */
class LogAnalyzerFeedbackRulesTest {

    private val sysUiLoad =
        "[ 2026-11-25T09:52:00.336     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-1,0,1] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054"
    private val appleMusicLoad =
        "[ 2026-11-25T09:52:13.493    10361: 25914: 25914 I/LSPosedFramework ] (com.apple.android.music)[com.juren233.hyperlyricsenhanced,null,653a-1a2106746b5-2-1,0,1] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054"

    private fun sysUi(time: String, level: Char, tag: String, message: String, pid: Int = 24949) =
        "[ 2026-11-25T$time     1000: $pid: $pid $level/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-999,0,1] [$tag] $message"

    private fun appleMusic(time: String, level: Char, tag: String, message: String, pid: Int = 25914) =
        "[ 2026-11-25T$time    10361: $pid: $pid $level/LSPosedFramework ] (com.apple.android.music)[com.juren233.hyperlyricsenhanced,null,653a-1a2106746b5-2-999,0,1] [$tag] $message"

    private fun env(debugBuild: Boolean = true, switches: FeatureSwitches = FeatureSwitches()) = LogAnalysisEnvironment(
        installedVersionCode = 250054,
        logSource = ModuleLogSourceStatus.Ok,
        moduleServiceBound = true,
        scope = setOf("com.android.systemui", "miui.systemui.plugin", "com.apple.android.music"),
        pluginScopeTargets = setOf("com.apple.android.music"),
        runningTargets = null,
        pidsOfProcess = { mapOf("com.android.systemui" to setOf(24949), "com.apple.android.music" to setOf(25914))[it] },
        activePlayerPackage = "com.apple.android.music",
        installedPluginIds = setOf("applemusic"),
        enabledPluginIds = setOf("applemusic"),
        dynamicIslandEnabled = false,
        aodFocusNotificationEnabled = false,
        hasPostNotificationPermission = true,
        hasNotificationListenerAccess = true,
        featureSwitches = switches,
        debugBuild = debugBuild,
    )

    private fun scan(lines: List<String>) = ModuleLogScan().apply { lines.forEach(::acceptLine) }
    private fun analyze(lines: List<String>, env: LogAnalysisEnvironment = env()) = LogAnalyzer.analyze(scan(lines), env)

    private inline fun <reified T : LogFinding> List<LogFinding>.single(): T =
        filterIsInstance<T>().also { assertEquals("应只有一条 ${T::class.simpleName}: $this", 1, it.size) }.first()

    private inline fun <reified T : LogFinding> List<LogFinding>.noneOf(): Boolean = none { it is T }

    // ---------------- 跟随音乐真实律动 ----------------

    private val rhythmOn = FeatureSwitches(realRhythm = true)

    private fun rhythmState(time: String, name: String, description: String, detail: String = "views=2, session=0") =
        sysUi(time, 'I', "IslandRealRhythm", "真实律动状态: $name $description, $detail")

    private val noEnergy = rhythmState("09:53:00.000", "NO_ENERGY", "已接管，Visualizer 有采集回调但各频段持续没有能量")
    private val noCallback = rhythmState("09:53:00.000", "NO_CALLBACK", "已接管，Visualizer 持续没有采集回调")
    private val startFailedLine = sysUi("09:53:00.000", 'E', "IslandRealRhythm", "真实律动 Visualizer 启动失败")
    private val startFailed = rhythmState("09:53:00.001", "START_FAILED", "Visualizer 启动失败，等待原生重绑或设置变化后重试", "session=0")
    private val running = rhythmState("09:54:00.000", "RUNNING", "已接管，Visualizer 正在收到音频数据")

    @Test
    fun realRhythm_noEnergyOrNoCallback_reportedAsNoSpectrumWithTurnOff() {
        listOf(noEnergy, noCallback).forEach { state ->
            val finding = analyze(listOf(sysUiLoad, state), env(switches = rhythmOn)).single<LogFinding.RealRhythmNoSpectrum>()
            assertEquals(RealRhythmProblem.NO_SPECTRUM, finding.problem)
            assertEquals(listOf<LogFixAction>(LogFixAction.TurnOffRealRhythm), finding.actions)
            assertEquals(listOf<LogEvidence>(LogEvidence.Line(state)), finding.evidence)
        }
    }

    @Test
    fun realRhythm_startFailed_reportedWithBothLines() {
        val finding = analyze(listOf(sysUiLoad, startFailedLine, startFailed), env(switches = rhythmOn))
            .single<LogFinding.RealRhythmNoSpectrum>()
        assertEquals(RealRhythmProblem.START_FAILED, finding.problem)
        assertEquals(listOf<LogEvidence>(LogEvidence.Line(startFailedLine), LogEvidence.Line(startFailed)), finding.evidence)
    }

    @Test
    fun realRhythm_runningAfterProblem_notReported() {
        assertTrue(analyze(listOf(sysUiLoad, noEnergy, running), env(switches = rhythmOn)).noneOf<LogFinding.RealRhythmNoSpectrum>())
    }

    @Test
    fun realRhythm_modeChangedAfterProblem_notReported() {
        val changed = sysUi("09:53:30.000", 'I', "HookEntry", "收到配置广播并更新运行时覆盖: key=key_hook_island_music_wave_real_rhythm_mode, value=2")
        assertTrue(analyze(listOf(sysUiLoad, noEnergy, changed), env(switches = rhythmOn)).noneOf<LogFinding.RealRhythmNoSpectrum>())
    }

    @Test
    fun realRhythm_switchOff_notReported() {
        assertTrue(analyze(listOf(sysUiLoad, noEnergy)).noneOf<LogFinding.RealRhythmNoSpectrum>())
    }

    @Test
    fun realRhythm_problemBeforeReload_notReported() {
        val oldState = noEnergy.replace("09:53:00.000", "09:51:00.000")
        assertTrue(analyze(listOf(oldState, sysUiLoad), env(switches = rhythmOn)).noneOf<LogFinding.RealRhythmNoSpectrum>())
    }

    // ---------------- AI 翻译缓存 ----------------

    private val aiOn = AiTranslationSettings(apiKeyBlank = false, targetLanguageBlank = false)
    private val cacheWriteFailed = sysUi("09:53:10.000", 'E', "AITranslationCache", "写入翻译缓存失败")

    private val cacheReadFailed = sysUi("09:53:05.000", 'E', "AITranslationCache", "查询翻译缓存失败")
    private fun cacheRecovered(time: String, message: String) = sysUi(time, 'I', "AITranslationCache", message)
    private val readRecovered = com.juren233.hyperlyricsenhanced.root.aitrans.AITranslationCache.READ_RECOVERED_MESSAGE
    private val writeRecovered = com.juren233.hyperlyricsenhanced.root.aitrans.AITranslationCache.WRITE_RECOVERED_MESSAGE
    private fun cacheFinding(lines: List<String>) =
        analyze(listOf(sysUiLoad) + lines, env().copy(aiTranslation = aiOn)).filterIsInstance<LogFinding.TranslationCacheFailing>()

    // Cavan 08:45 问「翻译缓存重启系统界面有用？」：没用。缓存每次读写都重新取数据库，失败不会卡到重启，
    // 所以按钮只留反馈，不给「一键重启」。
    @Test
    fun translationCache_failureWithAiOn_reportedWithFeedbackOnly_noRestart() {
        val finding = analyze(listOf(sysUiLoad, cacheWriteFailed), env().copy(aiTranslation = aiOn))
            .single<LogFinding.TranslationCacheFailing>()
        assertEquals(listOf<LogFixAction>(LogFixAction.OpenIssuePage), finding.actions)
        assertEquals(listOf<LogEvidence>(LogEvidence.Line(cacheWriteFailed)), finding.evidence)
        val release = analyze(listOf(sysUiLoad, cacheWriteFailed), env(debugBuild = false).copy(aiTranslation = aiOn))
            .single<LogFinding.TranslationCacheFailing>()
        assertEquals(listOf<LogFixAction>(LogFixAction.OpenReleasePage), release.actions)
    }

    @Test
    fun translationCache_writeRecoveredAfterFailure_notReported() {
        assertTrue(cacheFinding(listOf(cacheWriteFailed, cacheRecovered("09:53:20.000", writeRecovered))).isEmpty())
    }

    @Test
    fun translationCache_clearFailure_clearedByWriteRecovery() {
        val clearFailed = sysUi("09:53:10.000", 'E', "AITranslationCache", "清空翻译缓存数据库失败")
        assertTrue(cacheFinding(listOf(clearFailed, cacheRecovered("09:53:20.000", writeRecovered))).isEmpty())
    }

    @Test
    fun translationCache_readRecoveredAfterFailure_notReported() {
        assertTrue(cacheFinding(listOf(cacheReadFailed, cacheRecovered("09:53:20.000", readRecovered))).isEmpty())
    }

    // 存储满时读照常、写一直失败：读恢复不能把写失败也清掉。
    @Test
    fun translationCache_readRecoveryDoesNotClearWriteFailure() {
        val finding = cacheFinding(listOf(cacheWriteFailed, cacheRecovered("09:53:20.000", readRecovered))).also { assertEquals(1, it.size) }.first()
        assertEquals(listOf<LogEvidence>(LogEvidence.Line(cacheWriteFailed)), finding.evidence)
    }

    @Test
    fun translationCache_recoveryBeforeNewFailure_stillReported() {
        val finding = cacheFinding(listOf(cacheRecovered("09:53:01.000", writeRecovered), cacheWriteFailed)).also { assertEquals(1, it.size) }.first()
        assertEquals(listOf<LogEvidence>(LogEvidence.Line(cacheWriteFailed)), finding.evidence)
    }

    @Test
    fun translationCache_readAndWriteBothFailing_bothLinesAsEvidence() {
        val finding = cacheFinding(listOf(cacheReadFailed, cacheWriteFailed)).also { assertEquals(1, it.size) }.first()
        assertEquals(listOf<LogEvidence>(LogEvidence.Line(cacheReadFailed), LogEvidence.Line(cacheWriteFailed)), finding.evidence)
    }

    @Test
    fun translationCache_aiOff_notReported() {
        assertTrue(analyze(listOf(sysUiLoad, cacheWriteFailed)).noneOf<LogFinding.TranslationCacheFailing>())
    }

    @Test
    fun translationCache_eachFailureText_recognized() {
        listOf("查询翻译缓存失败", "写入翻译缓存失败", "清空翻译缓存数据库失败").forEach { text ->
            val line = sysUi("09:53:10.000", 'E', "AITranslationCache", text)
            assertTrue(text, analyze(listOf(sysUiLoad, line), env().copy(aiTranslation = aiOn)).any { it is LogFinding.TranslationCacheFailing })
        }
    }

    // ---------------- 没认出的报错 / 警告：只计数 ----------------

    private val unknownError = sysUi("09:53:20.000", 'E', "NotificationMediaAmbientFlowHooker", "刷新通知中心媒体卡片主题失败")
    private val unknownWarning = sysUi("09:53:21.000", 'W', "IslandRealRhythm", "暂停原生律动失败: IllegalStateException")
    private val appleWarning = appleMusic("09:53:22.000", 'W', "AppleMusicLyricsBridge", "歌词请求超时")

    @Test
    fun unrecognized_errorsAndWarningsCountedSeparately_acrossCurrentProcesses() {
        val finding = analyze(listOf(sysUiLoad, appleMusicLoad, unknownError, unknownWarning, appleWarning))
            .single<LogFinding.UnrecognizedProblems>()
        assertEquals(1, finding.errorCount)
        assertEquals(2, finding.warningCount)
        // 里面有报错就按报错标红。
        assertEquals(LogFindingSeverity.ERROR, finding.severity)
        assertEquals(emptyList<LogEvidence>(), finding.evidence)
        assertEquals(listOf<LogFixAction>(LogFixAction.OpenIssuePage), finding.actions)
    }

    /** 只剩警告时才是黄色。 */
    @Test
    fun unrecognized_warningsOnly_isWarning() {
        val finding = analyze(listOf(sysUiLoad, unknownWarning, appleMusicLoad, appleWarning))
            .single<LogFinding.UnrecognizedProblems>()
        assertEquals(0, finding.errorCount)
        assertEquals(2, finding.warningCount)
        assertEquals(LogFindingSeverity.WARNING, finding.severity)
    }

    /**
     * 复现：日志里只有一条没认出的报错时，「日志」检查项和外层卡片之前都是黄色的警告。
     * 现在检查项、头卡统计和外层卡片都按报错标红。
     */
    @Test
    fun unrecognized_withErrors_marksLogsCheckAndReportRed() {
        val report = LogAnalyzer.report(scan(listOf(sysUiLoad, unknownError)), env())
        assertEquals(LogCheckStatus.ERROR, report[LogCheck.LOGS].status)
        assertEquals(LogAnalysisHealth.HAS_ERRORS, report.health)
        assertEquals(1, report.errorCount)
    }

    @Test
    fun unrecognized_warningsOnly_keepsLogsCheckYellow() {
        val report = LogAnalyzer.report(scan(listOf(sysUiLoad, unknownWarning)), env())
        assertEquals(LogCheckStatus.WARNING, report[LogCheck.LOGS].status)
        assertEquals(LogAnalysisHealth.WARNINGS_ONLY, report.health)
    }

    @Test
    fun unrecognized_releaseBuild_offersReleasePage() {
        val finding = analyze(listOf(sysUiLoad, unknownError), env(debugBuild = false)).single<LogFinding.UnrecognizedProblems>()
        assertEquals(listOf<LogFixAction>(LogFixAction.OpenReleasePage), finding.actions)
    }

    /** debug 包的 `[debug]` 调试输出（如 LyriconSource 的 w 级）不是问题，不算。 */
    @Test
    fun unrecognized_debugOnlyOutput_notCounted() {
        val debugLine = sysUi("09:53:23.000", 'W', "LyriconSource", "[debug] Timing publish: songId=1, restorePosition=0")
        assertTrue(analyze(listOf(sysUiLoad, debugLine)).noneOf<LogFinding.UnrecognizedProblems>())
    }

    /**
     * 只在 debug 包写的诊断输出不算（逐字取自真实导出，只换了 pid 和时间）：
     * CoverColorDiagnostic 的封面色切换记录、RuntimePerfDiag 的性能诊断。
     */
    @Test
    fun unrecognized_debugOnlyDiagnostics_notCounted() {
        val coverColor = sysUi("09:53:26.000", 'W', "CoverColorDiagnostic", "seq=101 event=cover_to_default_after_key_change surface=island_text target=HyperIslandTextView")
        val perf = sysUi("09:53:27.000", 'W', "RuntimePerfDiag", "[RUNTIME_PERF_DIAG] view_frame_attach_failed reason=NoSuchMethodException:null")
        assertTrue(analyze(listOf(sysUiLoad, coverColor, perf)).noneOf<LogFinding.UnrecognizedProblems>())
    }

    /**
     * 长日志被 LSPosed 拆成几行，续行（方括号末尾「1,1」）不是新的一条：
     * 真实导出里续行开头的「[pronunciation]:…」被当成了一条 tag 为 pronunciation 的警告。
     */
    @Test
    fun parser_continuationChunk_isNotANewRecord() {
        val first = "[ 2026-11-25T09:53:28.000     1000: 24949: 24949 W/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,1652-1a21037fb39-2-483,0,0] [LyriconSource] [debug] 在线翻译未匹配行: player=com.apple.android.music, 0@0[pronunciation]:baby"
        val continuation = "[ 2026-11-25T09:53:28.000     1000: 24949: 24949 W/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,1652-1a21037fb39-2-483,1,1] [pronunciation]:just know there's nobody who's | 53@141808[pronunciation]:anymore my type than you"
        assertEquals(null, ModuleLogLineParser.parse(continuation))
        assertEquals("LyriconSource", ModuleLogLineParser.parse(first)?.tag)
        assertEquals("HookEntry", ModuleLogLineParser.parse(sysUiLoad)?.tag)
        assertTrue(analyze(listOf(sysUiLoad, first, continuation)).noneOf<LogFinding.UnrecognizedProblems>())
    }

    /**
     * Cavan 09:51:53 那一刻：Apple Music 26164 拒绝了热重载、还在跑旧版，它 09:24 的 Provider 报错是旧版写的，
     * 已经由「还在跑旧版模块」引导重启，不再算进「还有 N 条」。两行逐字取自 09:52 导出。
     */
    @Test
    fun unrecognized_processRunningStaleModule_notCounted() {
        val providerError =
            "[ 2026-11-25T09:24:37.496    10361: 26164: 32719 E/LSPosedFramework ] (com.apple.android.music)[com.juren233.hyperlyricsenhanced,null,6634-1a2104940d2-121-16eb,0,1] [OfficialProviderHookHost] 官方 Provider 拦截回调异常，已回退原调用结果: target=public final jk.D sb.a.a(ok.f) error=null"
        val rejected =
            "[ 2026-11-25T09:51:52.810    10361: 26164: 28331 I/LSPosedFramework ] (com.apple.android.music)[com.juren233.hyperlyricsenhanced,null,6634-1a2104940d2-111-16ed,0,1] [HookEntry] 当前宿主尚不具备完整热重载状态，需要重启对应进程"
        assertTrue(analyze(listOf(sysUiLoad, providerError, rejected)).noneOf<LogFinding.UnrecognizedProblems>())
        // 没拒绝热重载时同一行要算。
        assertEquals(1, analyze(listOf(sysUiLoad, providerError)).single<LogFinding.UnrecognizedProblems>().errorCount)
        // 加载的版本不是已安装的版本，也不算。
        val oldLoad = appleMusic("09:24:00.000", 'I', "HookEntry", "模块加载完成，当前应用版本8.5.0-canary-250053", pid = 26164)
        assertTrue(analyze(listOf(sysUiLoad, oldLoad, providerError)).noneOf<LogFinding.UnrecognizedProblems>())
    }

    /** SuperLyric 回退时的「歌词源不可用，回退到: Lyricon」由 SuperLyric 规则说明，不再算进「还有 N 条」。 */
    @Test
    fun unrecognized_lyricSourceFallback_notCounted() {
        val fellBack = sysUi("09:52:01.620", 'W', "SourceManager", "歌词源不可用，回退到: Lyricon")
        assertTrue(analyze(listOf(sysUiLoad, fellBack)).noneOf<LogFinding.UnrecognizedProblems>())
    }

    @Test
    fun unrecognized_infoAndDebugLevels_notCounted() {
        val info = sysUi("09:53:24.000", 'I', "IslandRealRhythm", "真实律动首次接管: view=1")
        val debug = sysUi("09:53:25.000", 'D', "IslandRealRhythm", "Visualizer 启动: session=0")
        assertTrue(analyze(listOf(sysUiLoad, info, debug)).noneOf<LogFinding.UnrecognizedProblems>())
    }

    @Test
    fun unrecognized_beforeReloadOrInOldProcess_notCounted() {
        val beforeReload = unknownError.replace("09:53:20.000", "09:51:00.000")
        val oldPid = sysUi("09:53:20.000", 'E', "HookIslandGlow", "刷新媒体岛光效模板失败", pid = 12345)
        assertTrue(analyze(listOf(beforeReload, sysUiLoad, oldPid)).noneOf<LogFinding.UnrecognizedProblems>())
    }

    /** 规则认得的行（这里是翻译缓存失败）由对应规则处理，不再算进「还有 N 条」。 */
    @Test
    fun unrecognized_linesMatchedByRules_notCounted() {
        val findings = analyze(listOf(sysUiLoad, cacheWriteFailed, unknownWarning), env().copy(aiTranslation = aiOn))
        val finding = findings.single<LogFinding.UnrecognizedProblems>()
        assertEquals(0, finding.errorCount)
        assertEquals(1, finding.warningCount)
    }

    @Test
    fun unrecognized_logsUnreadable_notReported() {
        val env = env().copy(logSource = ModuleLogSourceStatus.NoRoot)
        assertTrue(analyze(listOf(sysUiLoad, unknownError), env).noneOf<LogFinding.UnrecognizedProblems>())
    }

    // ---------------- 检查项归类 ----------------

    @Test
    fun report_newFindingsLandInTheirChecks() {
        val env = env(switches = rhythmOn).copy(aiTranslation = aiOn)
        val report = LogAnalyzer.report(scan(listOf(sysUiLoad, noEnergy, cacheWriteFailed, unknownError)), env)
        val features = report[LogCheck.FEATURES].findings
        assertTrue(features.any { it is LogFinding.RealRhythmNoSpectrum })
        assertTrue(features.any { it is LogFinding.TranslationCacheFailing })
        assertEquals(LogCheckStatus.WARNING, report[LogCheck.FEATURES].status)
        assertTrue(report[LogCheck.LOGS].findings.any { it is LogFinding.UnrecognizedProblems })
        // 没认出的那条是报错：日志检查项按报错标红。
        assertEquals(LogCheckStatus.ERROR, report[LogCheck.LOGS].status)
    }
}
