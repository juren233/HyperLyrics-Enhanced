package com.juren233.hyperlyricsenhanced.utils.loganalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 规则 1～7 的单测（11-26 新增）。
 *
 * 正常样本逐字取自 Cavan 11-25 09:52 导出的全部日志：系统界面 24949 在 09:52:00.336 重启后加载 250054，
 * 之后各部分都正常启动。失败样本 Cavan 的日志里没出现过，按源码里的日志语句逐字拼出（同样的行格式、同一个 pid），
 * 文案与源码是否一致由 [failureTexts_existVerbatimInSource] 守住。
 */
class LogAnalyzerNewRulesTest {

    // ---- 真实日志行（6bd2764d-hyperlyricsenhanced_all_logs_20261125_0952.txt） ----
    private val sysUiRestartedLoad =
        "[ 2026-11-25T09:52:00.336     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-1,0,1] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054"
    private val appleMusicRestartedLoad =
        "[ 2026-11-25T09:52:13.493    10361: 25914: 25914 I/LSPosedFramework ] (com.apple.android.music)[com.juren233.hyperlyricsenhanced,null,653a-1a2106746b5-2-1,0,1] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054"
    private val realNotificationCoverReady =
        "[ 2026-11-25T09:52:00.424     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-9,0,1] [NotificationMediaCoverStyleHooker] 通知中心媒体封面 Hook 已初始化: methods=attach, bindMediaData, detach, setMediaData"
    private val realSourceSwitched =
        "[ 2026-11-25T09:52:01.623     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-170,0,1] [SourceManager] 切换歌词源: Lyricon"
    private val realSourceStarted =
        "[ 2026-11-25T09:52:01.634     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-171,0,1] [LyriconSource] 数据源已启动"
    private val realEnvironmentReady =
        "[ 2026-11-25T09:52:01.652     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-180,0,1] [HookEntry] 系统环境初始化完成: hyperIsland=true, lyricRuntime=true, source=Lyricon, mode=1"
    private val realMusicWaveReady =
        "[ 2026-11-25T09:52:04.785     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-300,0,1] [IslandMusicWaveColorHooker] 音频律动 Hook 已初始化: color=true, lottieCallback=true"
    private val realCoverStyleReady =
        "[ 2026-11-25T09:52:04.806     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-301,0,1] [IslandAlbumCoverStyleHooker] 超级岛封面样式 Hook 已初始化: setFixIcon=true, setIcon=true, setSmallIcon=true"
    private val realIslandHookReady =
        "[ 2026-11-25T09:52:04.806     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-302,0,1] [SystemUIHookRegistry] 超级岛 Hook 已初始化"

    /** 重启后到导出之间系统界面 24949 的正常启动顺序。 */
    private val healthyBoot = listOf(
        sysUiRestartedLoad, realNotificationCoverReady, realSourceSwitched, realSourceStarted,
        realEnvironmentReady, realMusicWaveReady, realCoverStyleReady, realIslandHookReady, appleMusicRestartedLoad,
    )

    /** 系统界面 24949 在 [time] 写的一行模块日志，格式与真实日志相同。 */
    private fun sysUi(time: String, level: Char, tag: String, message: String, pid: Int = 24949) =
        "[ 2026-11-25T$time     1000: $pid: $pid $level/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-999,0,1] [$tag] $message"

    private fun broadcast(time: String, key: String, value: String = "\"x\"") =
        sysUi(time, 'I', "HookEntry", "收到配置广播并更新运行时覆盖: key=$key, value=$value")

    private fun scan(lines: List<String>) = ModuleLogScan().apply { lines.forEach(::acceptLine) }

    private fun env() = LogAnalysisEnvironment(
        installedVersionCode = 250054,
        logSource = ModuleLogSourceStatus.Ok,
        moduleServiceBound = true,
        scope = setOf("com.android.systemui", "miui.systemui.plugin", "com.apple.android.music", "com.netease.cloudmusic"),
        pluginScopeTargets = setOf("com.apple.android.music", "com.netease.cloudmusic"),
        runningTargets = null,
        pidsOfProcess = { mapOf("com.android.systemui" to setOf(24949), "com.apple.android.music" to setOf(25914))[it] },
        activePlayerPackage = "com.apple.android.music",
        installedPluginIds = setOf("applemusic", "netease"),
        enabledPluginIds = setOf("applemusic", "netease"),
        dynamicIslandEnabled = false,
        aodFocusNotificationEnabled = true,
        hasPostNotificationPermission = true,
        hasNotificationListenerAccess = true,
    )

    private fun analyze(lines: List<String>, env: LogAnalysisEnvironment = env()) = LogAnalyzer.analyze(scan(lines), env)

    private inline fun <reified T : LogFinding> List<LogFinding>.single(): T =
        filterIsInstance<T>().also { assertEquals("应只有一条 ${T::class.simpleName}: $this", 1, it.size) }.first()

    private inline fun <reified T : LogFinding> List<LogFinding>.noneOf(): Boolean = none { it is T }

    // ---------------- 正常日志不误报 ----------------

    @Test
    fun healthyBoot_allSwitchesOn_noFindings() {
        val env = env().copy(
            aiTranslation = AiTranslationSettings(apiKeyBlank = false, targetLanguageBlank = false),
            customFontPath = "/sdcard/Fonts/MiSans.ttf",
            lyricSource = LogAnalyzer.LYRICON_SOURCE,
            featureSwitches = FeatureSwitches(true, true, true, true),
        )
        assertEquals(emptyList<LogFinding>(), analyze(healthyBoot, env))
    }

    /** Cavan 那份完整导出（开着封面样式和音频律动）按全开的开关再跑一遍，新规则都不能误报。CI 上没有文件会跳过。 */
    @Test
    fun fullRealExports_allSwitchesOn_noNewFindings() {
        val files = listOf(
            "6bd2764d-hyperlyricsenhanced_all_logs_20261125_0952.txt" to 24949,
            "b006760b-hyperlyricsenhanced_all_logs_20261125_1257.txt" to 18519,
        ).map { (name, pid) -> File("/mnt/project-files/aod-anim-test/logs/$name") to pid }
        assumeTrue(files.all { it.first.exists() })
        files.forEach { (file, pid) ->
            val scan = ModuleLogScan()
            file.useLines { lines -> lines.forEach(scan::acceptLine) }
            val env = env().copy(
                pidsOfProcess = { if (it == "com.android.systemui") setOf(pid) else null },
                aiTranslation = AiTranslationSettings(apiKeyBlank = false, targetLanguageBlank = false),
                lyricSource = LogAnalyzer.LYRICON_SOURCE,
                featureSwitches = FeatureSwitches(true, true, true, true),
            )
            val newKinds = LogAnalyzer.analyze(scan, env).filter {
                it is LogFinding.SystemUiStartupFailed || it is LogFinding.AiTranslationFailing ||
                    it is LogFinding.CustomFontUnreadable || it is LogFinding.SuperLyricUnavailable ||
                    it is LogFinding.FeatureUnsupported || it is LogFinding.PluginNeedsRepair
            }
            assertEquals("${file.name} 不应有新规则的报告", emptyList<LogFinding>(), newKinds)
        }
    }

    // ---------------- 规则 1：系统界面启动失败 ----------------

    private val environmentFailed = sysUi("09:52:01.640", 'E', "HookEntry", "系统环境启动: state=FAILED trigger=boot attempt=3 stage=lyric_runtime")
    private val lyriconCentralFailed = sysUi("09:52:00.900", 'E', "EmbeddedLyriconCentral", "内嵌 Lyricon Central 启动失败: reason=standalone_probe_timeout")
    private val islandPluginFailed = sysUi("09:52:04.900", 'E', "SystemUIHookRegistry", "注入超级岛插件失败")

    @Test
    fun startup_environmentFailed_isErrorWithRestart() {
        val boot = listOf(sysUiRestartedLoad, realSourceSwitched, realSourceStarted, environmentFailed)
        val finding = analyze(boot).single<LogFinding.SystemUiStartupFailed>()
        assertEquals(listOf(StartupComponent.SYSTEM_ENVIRONMENT), finding.components)
        assertEquals(LogFindingSeverity.ERROR, finding.severity)
        assertEquals(
            listOf(LogFixAction.RestartProcesses(listOf("com.android.systemui")), LogFixAction.OpenIssuePage),
            finding.actions,
        )
        assertTrue(finding.debugBuild)
        assertEquals(listOf<LogEvidence>(LogEvidence.Line(environmentFailed)), finding.evidence)
    }

    /** release 包：正文改说先装 debug 包复现，反馈按钮换成去 Releases 下载 debug 包。 */
    @Test
    fun startup_releaseBuild_followsBuildType() {
        val boot = listOf(sysUiRestartedLoad, realSourceSwitched, realSourceStarted, environmentFailed)
        val finding = analyze(boot, env().copy(debugBuild = false)).single<LogFinding.SystemUiStartupFailed>()
        assertFalse(finding.debugBuild)
        assertEquals(
            listOf(LogFixAction.RestartProcesses(listOf("com.android.systemui")), LogFixAction.OpenReleasePage),
            finding.actions,
        )
    }

    @Test
    fun startup_failureThenRealSuccessLine_notReported() {
        // 09:52:01.640 失败、09:52:01.652 真实日志里重试成功。
        assertTrue(analyze(healthyBoot + environmentFailed).noneOf<LogFinding.SystemUiStartupFailed>())
    }

    @Test
    fun startup_severalParts_listedTogether() {
        val boot = listOf(sysUiRestartedLoad, lyriconCentralFailed, islandPluginFailed)
        val finding = analyze(boot).single<LogFinding.SystemUiStartupFailed>()
        assertEquals(listOf(StartupComponent.LYRICON_CENTRAL, StartupComponent.ISLAND_PLUGIN), finding.components)
    }

    @Test
    fun startup_failureBeforeHotReloadInSamePid_notReported() {
        val reload = sysUi("09:53:00.000", 'I', "HookEntry", "模块加载完成，当前应用版本8.5.0-canary-250054")
        assertTrue(analyze(listOf(sysUiRestartedLoad, environmentFailed, reload)).noneOf<LogFinding.SystemUiStartupFailed>())
    }

    @Test
    fun startup_failureInOldProcess_notReported() {
        val oldFailure = sysUi("09:21:00.000", 'E', "HookEntry", "系统环境启动: state=FAILED trigger=boot attempt=3 stage=x", pid = 25246)
        assertTrue(analyze(listOf(oldFailure) + healthyBoot).noneOf<LogFinding.SystemUiStartupFailed>())
    }

    // ---------------- 规则 2：AI 翻译 ----------------

    private val aiOn = AiTranslationSettings(apiKeyBlank = false, targetLanguageBlank = false)
    private fun aiFailure(level: Char, tag: String, message: String) = sysUi("09:53:10.000", level, tag, message)

    @Test
    fun ai_switchOff_neverReported() {
        val lines = healthyBoot + aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=401")
        assertTrue(analyze(lines, env().copy(aiTranslation = null)).noneOf<LogFinding.AiTranslationFailing>())
    }

    @Test
    fun ai_missingKeyOrLanguage_reportedFromState() {
        listOf(
            AiTranslationSettings(apiKeyBlank = true, targetLanguageBlank = false),
            AiTranslationSettings(apiKeyBlank = false, targetLanguageBlank = true),
        ).forEach { settings ->
            val finding = analyze(healthyBoot, env().copy(aiTranslation = settings)).single<LogFinding.AiTranslationFailing>()
            assertEquals(AiTranslationProblem.MISSING_CONFIG, finding.problem)
            assertEquals(listOf<LogEvidence>(LogEvidence.AiTranslationConfigIncomplete), finding.evidence)
            assertEquals(listOf<LogFixAction>(LogFixAction.OpenAiTranslationSettings), finding.actions)
        }
    }

    @Test
    fun ai_eachFailureKind_classified() {
        val cases = listOf(
            aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=401") to (AiTranslationProblem.AUTH to 401),
            aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=403") to (AiTranslationProblem.AUTH to 403),
            aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=429") to (AiTranslationProblem.RATE_LIMIT to 429),
            aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=502") to (AiTranslationProblem.HTTP_ERROR to 502),
            aiFailure('W', "AITranslationResponseParser", "翻译响应缺少 JSON: body=<html>Bad Gateway</html>") to (AiTranslationProblem.BAD_RESPONSE to null),
            aiFailure('W', "OpenAiTranslationClient", "翻译响应为空") to (AiTranslationProblem.BAD_RESPONSE to null),
            aiFailure('E', "OpenAiTranslationClient", "翻译网络请求异常: type=SocketTimeoutException") to (AiTranslationProblem.NETWORK to null),
            aiFailure('W', "OpenAiTranslationClient", "翻译连接意外关闭: reason=EOF") to (AiTranslationProblem.NETWORK to null),
        )
        cases.forEach { (line, expected) ->
            val finding = analyze(healthyBoot + line, env().copy(aiTranslation = aiOn)).single<LogFinding.AiTranslationFailing>()
            assertEquals(line, expected.first, finding.problem)
            assertEquals(line, expected.second, finding.httpCode)
            assertEquals(LogFindingSeverity.WARNING, finding.severity)
            assertEquals(listOf<LogEvidence>(LogEvidence.Line(line)), finding.evidence)
        }
    }

    @Test
    fun ai_settingChangedAfterFailure_notReported() {
        val lines = healthyBoot + aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=401") +
            broadcast("09:54:00.000", "key_hook_ai_trans_api_key", "\"sk-***\"")
        assertTrue(analyze(lines, env().copy(aiTranslation = aiOn)).noneOf<LogFinding.AiTranslationFailing>())
    }

    @Test
    fun ai_otherSettingChangedAfterFailure_stillReported() {
        val lines = healthyBoot + aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=401") +
            broadcast("09:54:00.000", "key_active_media_session_packages")
        assertEquals(AiTranslationProblem.AUTH, analyze(lines, env().copy(aiTranslation = aiOn)).single<LogFinding.AiTranslationFailing>().problem)
    }

    /** 回归：先报错、后来翻译成功（i 级「翻译请求成功」），翻译已恢复，不该再报。 */
    @Test
    fun ai_failureThenSuccess_notReported() {
        val lines = healthyBoot + aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=429") +
            sysUi("09:55:00.000", 'I', "OpenAiTranslationClient", "翻译请求成功")
        assertTrue(analyze(lines, env().copy(aiTranslation = aiOn)).noneOf<LogFinding.AiTranslationFailing>())
    }

    @Test
    fun ai_successThenFailure_stillReported() {
        val lines = healthyBoot + sysUi("09:52:30.000", 'I', "OpenAiTranslationClient", "翻译请求成功") +
            aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=429")
        assertEquals(AiTranslationProblem.RATE_LIMIT, analyze(lines, env().copy(aiTranslation = aiOn)).single<LogFinding.AiTranslationFailing>().problem)
    }

    @Test
    fun ai_successInOldSystemUiProcess_doesNotHideCurrentFailure() {
        // 旧进程里的成功不算数，只看当前系统界面进程。
        val lines = healthyBoot + aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=401") +
            sysUi("09:55:00.000", 'I', "OpenAiTranslationClient", "翻译请求成功", pid = 11111)
        assertEquals(AiTranslationProblem.AUTH, analyze(lines, env().copy(aiTranslation = aiOn)).single<LogFinding.AiTranslationFailing>().problem)
    }

    // ---------------- 规则 3：自定义字体 ----------------

    private val fontPath = "/storage/emulated/0/Fonts/HarmonyOS_Sans (1).ttf"
    private val fontMissing = sysUi("09:53:20.000", 'W', "FontHelper", "自定义字体文件不存在或无法读取：$fontPath (存在: false, 可读: false)")
    private val fontBroken = sysUi("09:53:20.000", 'W', "FontHelper", "无法从文件创建字体：$fontPath，原因: native typeface cannot be made")

    @Test
    fun font_unreadableCurrentPath_reportedWithTwoActions() {
        listOf(fontMissing, fontBroken).forEach { line ->
            val finding = analyze(healthyBoot + line, env().copy(customFontPath = fontPath)).single<LogFinding.CustomFontUnreadable>()
            assertEquals(fontPath, finding.path)
            assertEquals(listOf(LogFixAction.OpenFontSettings, LogFixAction.ClearCustomFont), finding.actions)
            assertEquals(listOf<LogEvidence>(LogEvidence.Line(line)), finding.evidence)
        }
    }

    @Test
    fun font_failureForAnotherPath_notReported() {
        val env = env().copy(customFontPath = "/storage/emulated/0/Fonts/MiSans.ttf")
        assertTrue(analyze(healthyBoot + fontMissing, env).noneOf<LogFinding.CustomFontUnreadable>())
    }

    @Test
    fun font_noPathSet_notReported() {
        assertTrue(analyze(healthyBoot + fontMissing, env().copy(customFontPath = "")).noneOf<LogFinding.CustomFontUnreadable>())
    }

    @Test
    fun font_pathChangedAfterFailure_notReported() {
        val lines = healthyBoot + fontMissing + broadcast("09:54:00.000", "key_hook_custom_font_path", "\"$fontPath\"")
        assertTrue(analyze(lines, env().copy(customFontPath = fontPath)).noneOf<LogFinding.CustomFontUnreadable>())
    }

    // ---------------- 规则 4：SuperLyric ----------------

    private val superLyricEnv = env().copy(lyricSource = LogAnalyzer.SUPER_LYRIC_SOURCE)
    private val startedSuperLyric = sysUi("09:52:01.623", 'I', "SourceManager", "启动歌词源: SuperLyric")
    private val fellBackToLyricon = listOf(
        sysUi("09:52:01.620", 'W', "SourceManager", "歌词源不可用，回退到: Lyricon"),
        sysUi("09:52:01.623", 'I', "SourceManager", "切换歌词源: Lyricon"),
    )

    @Test
    fun superLyric_serviceMissing_fromRootCheck() {
        val finding = analyze(healthyBoot, superLyricEnv.copy(superLyricServiceAvailable = false)).single<LogFinding.SuperLyricUnavailable>()
        assertEquals(SuperLyricProblem.SERVICE_MISSING, finding.problem)
        assertEquals(listOf<LogFixAction>(LogFixAction.SwitchLyricSource("lyricon")), finding.actions)
        assertEquals(listOf<LogEvidence>(LogEvidence.SuperLyricServiceCheck(found = false)), finding.evidence)
    }

    @Test
    fun superLyric_fellBackAtBoot_reportedWithActualSource() {
        val lines = listOf(sysUiRestartedLoad) + fellBackToLyricon
        val finding = analyze(lines, superLyricEnv).single<LogFinding.SuperLyricUnavailable>()
        assertEquals(SuperLyricProblem.FELL_BACK, finding.problem)
        assertEquals("Lyricon", finding.fellBackTo)
        assertFalse(finding.serviceNowAvailable)
        assertEquals(listOf<LogFixAction>(LogFixAction.SwitchLyricSource("lyricon")), finding.actions)
    }

    @Test
    fun superLyric_fellBackButServiceNowThere_offersRestartFirst() {
        val lines = listOf(sysUiRestartedLoad) + fellBackToLyricon
        val finding = analyze(lines, superLyricEnv.copy(superLyricServiceAvailable = true)).single<LogFinding.SuperLyricUnavailable>()
        assertTrue(finding.serviceNowAvailable)
        assertEquals(
            listOf(LogFixAction.RestartProcesses(listOf("com.android.systemui")), LogFixAction.SwitchLyricSource("lyricon")),
            finding.actions,
        )
        assertTrue(finding.evidence.contains(LogEvidence.SuperLyricServiceCheck(found = true)))
    }

    @Test
    fun superLyric_noSourceAtAll_reportedWithoutName() {
        val lines = listOf(sysUiRestartedLoad, sysUi("09:52:01.623", 'W', "SourceManager", "没有可用的歌词源"))
        val finding = analyze(lines, superLyricEnv).single<LogFinding.SuperLyricUnavailable>()
        assertEquals(SuperLyricProblem.FELL_BACK, finding.problem)
        assertNull(finding.fellBackTo)
    }

    @Test
    fun superLyric_registerFailedAfterStart_reported() {
        val lines = listOf(sysUiRestartedLoad, startedSuperLyric, sysUi("09:52:01.700", 'E', "SuperLyricSource", "注册接收端失败"))
        assertEquals(SuperLyricProblem.REGISTER_FAILED, analyze(lines, superLyricEnv).single<LogFinding.SuperLyricUnavailable>().problem)
    }

    @Test
    fun superLyric_startedNormally_notReported() {
        val lines = listOf(sysUiRestartedLoad, startedSuperLyric)
        assertTrue(analyze(lines, superLyricEnv.copy(superLyricServiceAvailable = true)).noneOf<LogFinding.SuperLyricUnavailable>())
    }

    @Test
    fun superLyric_registerFailedThenSwitchedAgain_notReported() {
        val lines = listOf(
            sysUiRestartedLoad, startedSuperLyric,
            sysUi("09:52:01.700", 'E', "SuperLyricSource", "注册接收端失败"),
            sysUi("09:55:00.000", 'I', "SourceManager", "切换歌词源: SuperLyric"),
        )
        assertTrue(analyze(lines, superLyricEnv).noneOf<LogFinding.SuperLyricUnavailable>())
    }

    @Test
    fun superLyric_otherSourceChosen_notReported() {
        // 真实日志：选的就是 Lyricon。
        val env = env().copy(lyricSource = LogAnalyzer.LYRICON_SOURCE, superLyricServiceAvailable = false)
        assertTrue(analyze(healthyBoot, env).noneOf<LogFinding.SuperLyricUnavailable>())
    }

    @Test
    fun superLyricServiceCheck_parsesServiceOutput() {
        assertEquals(true, SuperLyricServiceCheck.parse("Service super_lyric: found\n"))
        assertEquals(false, SuperLyricServiceCheck.parse("Service super_lyric: not found\n"))
        assertNull(SuperLyricServiceCheck.parse(""))
        assertNull(SuperLyricServiceCheck.parse("/system/bin/sh: service: not found"))
    }

    // ---------------- 规则 5：系统不支持的功能 ----------------

    private fun featureOn(feature: SystemFeature, debugBuild: Boolean = true) = env().copy(
        debugBuild = debugBuild,
        featureSwitches = FeatureSwitches(
            islandCoverStyle = feature == SystemFeature.ISLAND_COVER_STYLE,
            musicWaveColor = feature == SystemFeature.MUSIC_WAVE_COLOR,
            notificationMediaCover = feature == SystemFeature.NOTIFICATION_MEDIA_COVER,
            narrowLatinFont = feature == SystemFeature.NARROW_LATIN_FONT,
            settingsEntry = feature == SystemFeature.SETTINGS_ENTRY,
            aodLyrics = feature == SystemFeature.AOD_LYRICS,
            islandExpandedFlow = feature == SystemFeature.ISLAND_EXPANDED_FLOW,
            notificationMediaCard = feature == SystemFeature.NOTIFICATION_MEDIA_CARD,
            notificationMediaBackground = feature == SystemFeature.NOTIFICATION_MEDIA_BACKGROUND,
            islandGlow = feature == SystemFeature.ISLAND_GLOW,
        ),
    )

    /** 设置 App 3709 的一行模块日志（宿主进程名与真实日志一致）。 */
    private fun settings(time: String, level: Char, tag: String, message: String, pid: Int = 3709) =
        "[ 2026-11-25T$time     1000: $pid: $pid $level/LSPosedFramework ] (com.android.settings)[com.juren233.hyperlyricsenhanced,null,e7d-1a2101bc320-56-d,0,1] [$tag] $message"

    private val settingsLoad = settings("09:53:00.000", 'I', "HookEntry", "模块加载完成，当前应用版本8.5.0-canary-250054")

    private val featureFailures = mapOf(
        SystemFeature.ISLAND_COVER_STYLE to sysUi("09:52:04.806", 'W', "IslandAlbumCoverStyleHooker", "当前插件不支持超级岛封面样式: reason=setFixIcon"),
        SystemFeature.MUSIC_WAVE_COLOR to sysUi("09:52:04.785", 'I', "IslandMusicWaveColorHooker", "音频律动 Hook 已初始化: color=false, lottieCallback=false"),
        SystemFeature.NOTIFICATION_MEDIA_COVER to sysUi("09:52:00.424", 'W', "NotificationMediaCoverStyleHooker", "跳过通知中心媒体封面 Hook: reason=native_api_unavailable"),
        SystemFeature.NARROW_LATIN_FONT to sysUi("09:52:21.336", 'W', "FontHelper", "小米窄字体文件不存在或无法读取：/system/fonts/MiSansCondensed2T_latin_only.ttf (存在: false, 可读: false)"),
        SystemFeature.SETTINGS_ENTRY to settings("09:53:00.200", 'W', "SettingsEntry", "未找到 updateHeaderList 目标，设置入口不可用"),
        SystemFeature.AOD_LYRICS to sysUi("09:52:00.377", 'W', "NotificationMediaAodLyricHooker", "跳过息屏歌词 Hook: reason=native_api_unavailable"),
        SystemFeature.ISLAND_EXPANDED_FLOW to sysUi("09:52:00.407", 'W', "IslandExpandedMediaAmbientFlowHooker", "跳过展开态媒体流光 Hook: reason=native_api_unavailable"),
        SystemFeature.NOTIFICATION_MEDIA_CARD to sysUi("09:52:00.420", 'W', "NotificationMediaAmbientFlowHooker", "未找到兼容的通知中心媒体控制方法"),
        SystemFeature.NOTIFICATION_MEDIA_BACKGROUND to sysUi("09:52:00.419", 'W', "NotificationMediaAmbientFlowHooker", "通知中心原生背景接口不可用，跳过自定义背景 Hook"),
        SystemFeature.ISLAND_GLOW to sysUi("09:52:04.756", 'W', "HookIslandGlow", "未找到 updateTemplate，跳过媒体岛光效 Hook"),
    )

    // 真实成功行（同一份 09:52 导出）。
    private val realAodLyricsReady =
        "[ 2026-11-25T09:52:00.377     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-b,0,1] [NotificationMediaAodLyricHooker] 息屏歌词 Hook 已初始化: methods=4, dozeConstructors=1, headerVisibility=1"
    private val realAodIconLyricsReady =
        "[ 2026-11-25T09:52:03.292     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-1c5,0,1] [NotificationMediaAodLyricHooker] 通知图标式息屏歌词 Hook 已初始化: methods=5, contentLayout=known_iiz,targets=com.miui.aod.AODView.makeNormalPanel():void"
    private val realExpandedFlowReady =
        "[ 2026-11-25T09:52:00.407     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-d,0,1] [IslandExpandedMediaAmbientFlowHooker] 展开态媒体流光 Hook 已初始化: methods=9"
    private val realMediaCardReady =
        "[ 2026-11-25T09:52:00.420     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-f,0,1] [NotificationMediaAmbientFlowHooker] 通知中心媒体流光 Hook 已初始化: methods=5"
    private val realGlowReady =
        "[ 2026-11-25T09:52:04.756     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-1ea,0,1] [HookIslandGlow] 媒体岛光效 Hook 已初始化: method=updateTemplate"
    private val settingsEntryReady = settings("09:53:00.300", 'I', "SettingsEntry", "设置首页入口 Hook 已安装: updateHeaderList")

    /** 每个 Hook 功能的成功行；通知中心原生背景接口没有成功行。 */
    private val featureReady = mapOf(
        SystemFeature.ISLAND_COVER_STYLE to realCoverStyleReady,
        SystemFeature.MUSIC_WAVE_COLOR to realMusicWaveReady,
        SystemFeature.NOTIFICATION_MEDIA_COVER to realNotificationCoverReady,
        SystemFeature.SETTINGS_ENTRY to settingsEntryReady,
        SystemFeature.AOD_LYRICS to realAodLyricsReady,
        SystemFeature.ISLAND_EXPANDED_FLOW to realExpandedFlowReady,
        SystemFeature.NOTIFICATION_MEDIA_CARD to realMediaCardReady,
        SystemFeature.ISLAND_GLOW to realGlowReady,
    )

    /** 失败行替换掉真实启动顺序里对应的成功行，其余照旧；设置入口另加设置 App 的加载行。 */
    private fun bootWith(feature: SystemFeature): List<String> {
        val ready = featureReady[feature]
        val settingsBoot = if (feature == SystemFeature.SETTINGS_ENTRY) listOf(settingsLoad) else emptyList()
        return healthyBoot.filter { it != ready } + settingsBoot + featureFailures.getValue(feature)
    }

    /** 11-26：Hook 失效的一律只给反馈按钮；英数窄字体不是 Hook，保留关闭并加反馈。 */
    @Test
    fun feature_onButUnsupported_hooksOfferFeedback_narrowFontTurnOffAndFeedback() {
        SystemFeature.entries.forEach { feature ->
            val finding = analyze(bootWith(feature), featureOn(feature)).single<LogFinding.FeatureUnsupported>()
            assertEquals(feature, finding.feature)
            assertEquals(LogFindingSeverity.WARNING, finding.severity)
            val expected = if (feature == SystemFeature.NARROW_LATIN_FONT) {
                listOf(LogFixAction.TurnOffFeature(feature), LogFixAction.OpenIssuePage)
            } else {
                listOf<LogFixAction>(LogFixAction.OpenIssuePage)
            }
            assertEquals(feature.name, expected, finding.actions)
            assertEquals(listOf<LogEvidence>(LogEvidence.Line(featureFailures.getValue(feature))), finding.evidence)
        }
    }

    /** release 包里同样的失败：按钮换成去 Releases 页面下载 debug 包。 */
    @Test
    fun feature_onButUnsupported_releaseBuild_offersReleasePage() {
        SystemFeature.entries.forEach { feature ->
            val finding = analyze(bootWith(feature), featureOn(feature, debugBuild = false)).single<LogFinding.FeatureUnsupported>()
            assertTrue(feature.name, LogFixAction.OpenReleasePage in finding.actions)
            assertFalse(feature.name, LogFixAction.OpenIssuePage in finding.actions)
        }
    }

    @Test
    fun feedbackAction_dependsOnBuildType() {
        assertEquals(LogFixAction.OpenIssuePage, LogFixAction.feedback(debugBuild = true))
        assertEquals(LogFixAction.OpenReleasePage, LogFixAction.feedback(debugBuild = false))
        assertTrue(LogFixPreferences.writesFor(LogFixAction.OpenReleasePage).isEmpty())
        // release 包的「下载 debug 包」打开默认 Releases 列表，不指向当前版本的 tag。
        assertEquals(
            "https://github.com/juren233/HyperLyrics-Enhanced/releases",
            com.juren233.hyperlyricsenhanced.ui.page.log.feedbackLink(LogFixAction.OpenReleasePage),
        )
        assertEquals(
            "https://github.com/juren233/HyperLyrics-Enhanced/issues/new/choose",
            com.juren233.hyperlyricsenhanced.ui.page.log.feedbackLink(LogFixAction.OpenIssuePage),
        )
    }

    /** 通知中心媒体卡片 Hook 点位偏差：按钮去 GitHub 反馈，不再只引导关闭。 */
    @Test
    fun feature_notificationMediaCover_offersIssuePageNotTurnOff() {
        val feature = SystemFeature.NOTIFICATION_MEDIA_COVER
        val finding = analyze(bootWith(feature), featureOn(feature)).single<LogFinding.FeatureUnsupported>()
        assertEquals(listOf<LogFixAction>(LogFixAction.OpenIssuePage), finding.actions)
        assertTrue(LogFixPreferences.writesFor(LogFixAction.OpenIssuePage).isEmpty())
        assertEquals("https://github.com/juren233/HyperLyrics-Enhanced/issues/new/choose", com.juren233.hyperlyricsenhanced.common.ProjectLinks.NEW_ISSUE)
    }

    @Test
    fun feature_switchOff_notReported() {
        SystemFeature.entries.forEach { feature ->
            assertTrue(feature.name, analyze(bootWith(feature), env()).noneOf<LogFinding.FeatureUnsupported>())
        }
    }

    @Test
    fun feature_failedInOneClassLoaderButReadyInAnother_notReported() {
        // 这几个 Hook 对每个类加载器各装一次：同一进程里有一次成功（真实成功行）就算支持。
        featureReady.forEach { (feature, ready) ->
            val lines = healthyBoot + ready + (if (feature == SystemFeature.SETTINGS_ENTRY) listOf(settingsLoad) else emptyList()) +
                featureFailures.getValue(feature)
            assertTrue(feature.name, analyze(lines.distinct(), featureOn(feature)).noneOf<LogFinding.FeatureUnsupported>())
        }
    }

    @Test
    fun feature_narrowFontSwitchedAfterFailure_notReported() {
        val lines = bootWith(SystemFeature.NARROW_LATIN_FONT) + broadcast("09:54:00.000", "key_hook_narrow_latin_font", "true")
        assertTrue(analyze(lines, featureOn(SystemFeature.NARROW_LATIN_FONT)).noneOf<LogFinding.FeatureUnsupported>())
    }

    // ---------------- 规则 6：主线程没接受热重载 ----------------

    @Test
    fun stale_mainThreadDidNotAcceptHotReload_flagsSystemUi() {
        // 加载行已被日志轮转掉，只剩这条：系统界面 25246 仍在跑旧版本。
        val timeout = sysUi("09:51:53.000", 'W', "HookEntry", "主线程未及时接受热重载，已取消且保留当前运行状态", pid = 25246)
        val env = env().copy(pidsOfProcess = { if (it == "com.android.systemui") setOf(25246) else null })
        val finding = analyze(listOf(timeout), env).single<LogFinding.StaleModule>()
        assertEquals(listOf("com.android.systemui"), finding.processes.map { it.process })
        assertTrue(finding.processes.single().confirmedRunning)
        assertTrue(finding.evidence.contains(LogEvidence.Line(timeout)))
        assertTrue(finding.evidence.contains(LogEvidence.NoReloadAfterRejection("com.android.systemui", 25246)))
        assertEquals(listOf<LogFixAction>(LogFixAction.RestartProcesses(listOf("com.android.systemui"))), finding.actions)
    }

    @Test
    fun stale_mainThreadTimeoutThenReloaded_notFlagged() {
        val timeout = sysUi("09:52:00.100", 'W', "HookEntry", "主线程未及时接受热重载，已取消且保留当前运行状态")
        assertTrue(analyze(listOf(timeout) + healthyBoot).noneOf<LogFinding.StaleModule>())
    }

    // ---------------- 规则 7：插件需要修复 ----------------

    @Test
    fun plugin_needsRepair_fromInstallState() {
        val env = env().copy(pluginsNeedingRepair = listOf(RepairablePlugin("applemusic", "Apple Music"), RepairablePlugin("netease", "网易云音乐")))
        val finding = analyze(healthyBoot, env).single<LogFinding.PluginNeedsRepair>()
        assertEquals(listOf("Apple Music", "网易云音乐"), finding.pluginNames)
        assertEquals(listOf<LogFixAction>(LogFixAction.RepairPlugins), finding.actions)
        assertEquals(listOf<LogEvidence>(LogEvidence.PluginFileBroken("Apple Music"), LogEvidence.PluginFileBroken("网易云音乐")), finding.evidence)
    }

    @Test
    fun plugin_allHealthy_notReported() {
        assertTrue(analyze(healthyBoot).noneOf<LogFinding.PluginNeedsRepair>())
    }

    // ---------------- 检查项归类 ----------------

    @Test
    fun report_newFindingsLandInTheirChecks() {
        val env = env().copy(
            aiTranslation = aiOn,
            pluginsNeedingRepair = listOf(RepairablePlugin("applemusic", "Apple Music")),
            featureSwitches = FeatureSwitches(islandCoverStyle = true),
        )
        val lines = healthyBoot.filter { it != realCoverStyleReady } + environmentFailed.replace("09:52:01.640", "09:52:01.700") +
            featureFailures.getValue(SystemFeature.ISLAND_COVER_STYLE) +
            aiFailure('E', "OpenAiTranslationClient", "翻译请求失败: code=429")
        val report = LogAnalyzer.report(scan(lines), env)
        assertEquals(LogCheckStatus.ERROR, report[LogCheck.RUNTIME].status)
        assertTrue(report[LogCheck.RUNTIME].findings.any { it is LogFinding.SystemUiStartupFailed })
        assertEquals(LogCheckStatus.WARNING, report[LogCheck.PLUGIN].status)
        assertTrue(report[LogCheck.PLUGIN].findings.any { it is LogFinding.PluginNeedsRepair })
        assertEquals(LogCheckStatus.WARNING, report[LogCheck.FEATURES].status)
        val features = report[LogCheck.FEATURES].findings
        assertTrue(features.any { it is LogFinding.AiTranslationFailing })
        assertTrue(features.any { it is LogFinding.FeatureUnsupported })
        assertEquals(LogCheckStatus.PASS, report[LogCheck.VERSION].status)
    }

    @Test
    fun report_healthy_runtimeAndFeaturesPass() {
        val report = LogAnalyzer.report(scan(healthyBoot), env())
        assertEquals(LogCheckStatus.PASS, report[LogCheck.RUNTIME].status)
        assertEquals(LogCheckStatus.PASS, report[LogCheck.FEATURES].status)
    }

    // ---------------- 文案与源码一致 ----------------

    /** 规则认的失败文案与源码逐字一致：源码改了文案这里先失败，规则不会悄悄失效。 */
    @Test
    fun failureTexts_existVerbatimInSource() {
        val root = "src/main/java/com/juren233/hyperlyricsenhanced/"
        val expected = mapOf(
            "root/HookEntry.kt" to listOf("系统环境启动: state=", "系统环境初始化完成", "主线程未及时接受热重载，已取消且保留当前运行状态", "注入超级岛插件失败"),
            "root/lyricon/central/EmbeddedLyriconCentralController.kt" to listOf("\"EmbeddedLyriconCentral\"", "内嵌 Lyricon Central 启动失败", "内嵌 Lyricon Central 已启动"),
            "root/source/LyricInfoSource.kt" to listOf("数据源启动失败"),
            "root/island/SystemUIHookRegistry.kt" to listOf("注入超级岛插件失败", "超级岛 Hook 已初始化"),
            "root/aitrans/OpenAiTranslationClient.kt" to listOf("翻译请求失败: code=", "翻译网络请求异常", "翻译连接意外关闭: reason=EOF", "翻译响应为空"),
            "root/aitrans/AITranslationResponseParser.kt" to listOf("翻译响应缺少 JSON: body="),
            "root/aitrans/AiTranslationHealthLog.kt" to listOf("\"OpenAiTranslationClient\"", "\"翻译请求成功\""),
            "root/utils/FontHelper.kt" to listOf("自定义字体文件不存在或无法读取：", "无法从文件创建字体：", "小米窄字体文件不存在或无法读取：", "无法创建窄字体：", "(存在: ", "，原因: "),
            "lyric/source/SourceManager.kt" to listOf("启动歌词源: ", "切换歌词源: ", "没有可用的歌词源", "歌词源不可用: ", "歌词源不可用，回退到: "),
            "root/source/SuperLyricSource.kt" to listOf("注册接收端失败"),
            "root/island/IslandAlbumCoverStyleHooker.kt" to listOf("当前插件不支持超级岛封面样式", "超级岛封面字段不可用", "初始化超级岛封面样式 Hook 失败", "跳过封面样式 Hook", "超级岛封面样式 Hook 已初始化"),
            "root/island/IslandMusicWaveColorHooker.kt" to listOf("当前插件不支持音频律动", "音频律动颜色字段不可用", "初始化音频律动封面色 Hook 失败", "音频律动 Hook 已初始化: color="),
            "root/settings/SettingsEntryHooker.kt" to listOf("\"SettingsEntry\"", "设置入口反射初始化失败", "未找到 updateHeaderList 目标", "updateHeaderList Hook 安装失败", "设置首页入口 Hook 已安装: updateHeaderList"),
            "root/mediacard/notification/NotificationMediaAodLyricHooker.kt" to listOf("跳过息屏歌词 Hook: reason=native_api_unavailable", "息屏歌词 Hook 安装不完整", "通知图标式息屏歌词契约不兼容", "通知图标式息屏歌词 Hook 安装不完整", "息屏歌词 Hook 已初始化", "通知图标式息屏歌词 Hook 已初始化"),
            "root/mediacard/island/IslandExpandedMediaAmbientFlowHooker.kt" to listOf("跳过展开态媒体流光 Hook", "展开态媒体流光 Hook 不完整", "展开态媒体流光 Hook 已初始化"),
            "root/mediacard/notification/NotificationMediaAmbientFlowHooker.kt" to listOf("通知中心媒体控制器不可用", "未找到兼容的通知中心媒体控制方法", "通知中心媒体流光 Hook 已初始化", "通知中心原生背景接口不可用"),
            "root/HookIslandGlow.kt" to listOf("未找到 updateTemplate", "跳过不支持的媒体岛光效 Hook", "初始化媒体岛光效 Hook 失败", "媒体岛光效 Hook 已初始化"),
            "root/island/IslandRealRhythm.kt" to listOf("\"真实律动状态: \${state.name}", "真实律动 Visualizer 启动失败"),
            "root/island/IslandRealRhythmModel.kt" to listOf("START_FAILED(", "NO_CALLBACK(", "NO_ENERGY(", "RUNNING("),
            "root/aitrans/AITranslationCache.kt" to listOf("查询翻译缓存失败", "写入翻译缓存失败", "清空翻译缓存数据库失败", "翻译缓存读取已恢复", "翻译缓存写入已恢复"),
            "utils/loganalysis/ModuleLogScan.kt" to listOf("DEBUG_ONLY_PREFIX = \"[debug]\""),
            // 不计数的诊断输出必须真的只在 debug 包里写。
            "root/source/LyriconSource.kt" to listOf("if (BuildConfig.DEBUG) HookLogger.w(TAG, \"[debug] \$message\")"),
            "root/utils/RuntimePerfDiagnostics.kt" to listOf("private const val TAG = \"RuntimePerfDiag\"", "PREFIX = \"[RUNTIME_PERF_DIAG]\"", "if (!BuildConfig.DEBUG) return"),
            "root/utils/CoverColorDiagnostics.kt" to listOf("private const val TAG = \"CoverColorDiagnostic\"", "if (!BuildConfig.DEBUG) return"),
            "root/mediacard/notification/NotificationMediaCoverStyleHooker.kt" to listOf("跳过通知中心媒体封面 Hook", "安装通知中心媒体封面 Hook 失败", "通知中心媒体封面 Hook 安装不完整", "通知中心媒体封面 Hook 已初始化"),
        )
        expected.forEach { (path, texts) ->
            val source = File(root + path).readText()
            texts.forEach { assertTrue("$path 里找不到「$it」", source.contains(it)) }
        }
    }
}
