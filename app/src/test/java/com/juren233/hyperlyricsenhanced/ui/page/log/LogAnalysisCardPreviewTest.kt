package com.juren233.hyperlyricsenhanced.ui.page.log

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.ui.component.HeroCard
import com.juren233.hyperlyricsenhanced.ui.component.HeroTone
import com.juren233.hyperlyricsenhanced.ui.component.settingsCard
import com.juren233.hyperlyricsenhanced.ui.component.settingsHeroCard
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.preference.ArrowPreference
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Navigator
import com.juren233.hyperlyricsenhanced.ui.utils.ThemeUtils
import com.juren233.hyperlyricsenhanced.utils.loganalysis.AiTranslationSettings
import com.juren233.hyperlyricsenhanced.utils.loganalysis.FeatureSwitches
import com.juren233.hyperlyricsenhanced.utils.loganalysis.RepairablePlugin
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisEnvironment
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalyzer
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisHealth
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisReport
import com.juren233.hyperlyricsenhanced.utils.loganalysis.ModuleLogScan
import com.juren233.hyperlyricsenhanced.utils.loganalysis.ModuleLogSourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import com.juren233.hyperlyricsenhanced.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.time.Duration

/**
 * 按真机尺寸（宽 393dp、xxhdpi）把分析卡片和详情页画出来存成 PNG，浅色和深色各一张，用来在打包前自己看效果。
 * 结论由分析器跑真实日志片段得出（09:51:53 Apple Music 拒绝热重载那一刻 / 09:52 重启之后）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "zh-rCN-w393dp-h1400dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogAnalysisCardPreviewTest {
    init {
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
    }

    private val beforeRestart = listOf(
        "[ 2026-11-25T09:51:52.382     1000: 25246: 26038 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,629e-1a21048ff16-e4-556e,0,1] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054",
        "[ 2026-11-25T09:51:52.810    10361: 26164: 28331 I/LSPosedFramework ] (com.apple.android.music)[com.juren233.hyperlyricsenhanced,null,6634-1a2104940d2-111-16ed,0,1] [HookEntry] 当前宿主尚不具备完整热重载状态，需要重启对应进程",
    )
    private val afterRestart = beforeRestart + listOf(
        "[ 2026-11-25T09:52:00.336     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-1,0,1] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054",
        "[ 2026-11-25T09:52:13.493    10361: 25914: 25914 I/LSPosedFramework ] (com.apple.android.music)[com.juren233.hyperlyricsenhanced,null,653a-1a2106746b5-2-1,0,1] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054",
    )

    private fun env(listener: Boolean, scope: Set<String>, pids: Map<String, Set<Int>>) = LogAnalysisEnvironment(
        installedVersionCode = 250054,
        logSource = ModuleLogSourceStatus.Ok,
        moduleServiceBound = true,
        scope = scope,
        pluginScopeTargets = setOf("com.apple.android.music", "com.netease.cloudmusic"),
        runningTargets = null,
        pidsOfProcess = { pids[it] },
        activePlayerPackage = "com.apple.android.music",
        installedPluginIds = setOf("applemusic", "netease"),
        enabledPluginIds = setOf("applemusic", "netease"),
        dynamicIslandEnabled = false,
        aodFocusNotificationEnabled = true,
        hasPostNotificationPermission = true,
        hasNotificationListenerAccess = listener,
    )

    /** 09:51:53 那一刻：Apple Music 拒绝热重载（报错），作用域缺网易云、缺通知使用权（两个警告）。 */
    private fun problemReport(): LogAnalysisReport = LogAnalyzer.report(
        ModuleLogScan().apply { beforeRestart.forEach(::acceptLine) },
        env(
            listener = false,
            scope = setOf("com.android.systemui", "com.apple.android.music"),
            pids = mapOf("com.android.systemui" to setOf(25246), "com.apple.android.music" to setOf(26164)),
        ),
    )

    /** 两个进程都重启之后，作用域缺网易云、缺通知使用权：只剩两个警告。 */
    private fun warningReport(): LogAnalysisReport = LogAnalyzer.report(
        ModuleLogScan().apply { afterRestart.forEach(::acceptLine) },
        env(
            listener = false,
            scope = setOf("com.android.systemui", "com.apple.android.music"),
            pids = mapOf("com.android.systemui" to setOf(24949), "com.apple.android.music" to setOf(25914)),
        ),
    )

    /** 09:52 导出时：两个进程都已重启，作用域和权限都齐。 */
    private fun cleanReport(): LogAnalysisReport = LogAnalyzer.report(
        ModuleLogScan().apply { afterRestart.forEach(::acceptLine) },
        env(
            listener = true,
            scope = setOf("com.android.systemui", "com.apple.android.music", "com.netease.cloudmusic"),
            pids = mapOf("com.android.systemui" to setOf(24949), "com.apple.android.music" to setOf(25914)),
        ),
    )

    private fun sysUi(time: String, level: Char, tag: String, message: String) =
        "[ 2026-11-25T$time     1000: 24949: 24949 $level/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,6175-1a210671350-2-999,0,1] [$tag] $message"

    /**
     * 新增规则全都出现的一刻（重启后的 24949，失败行按源码文案拼出）：
     * 运行状态 1 个报错（Lyricon Central 没启动），插件 1 个警告（Apple Music 插件要修复），
     * 功能检查里 AI 翻译 401、自定义字体读不到、选了 SuperLyric 却回退到 Lyricon、超级岛封面样式不支持。
     */
    private fun newRulesReport(): LogAnalysisReport = LogAnalyzer.report(
        ModuleLogScan().apply {
            (afterRestart + listOf(
                sysUi("09:52:00.900", 'E', "EmbeddedLyriconCentral", "内嵌 Lyricon Central 启动失败: reason=standalone_probe_timeout"),
                sysUi("09:52:01.620", 'W', "SourceManager", "歌词源不可用，回退到: Lyricon"),
                sysUi("09:52:01.623", 'I', "SourceManager", "切换歌词源: Lyricon"),
                sysUi("09:52:04.806", 'W', "IslandAlbumCoverStyleHooker", "当前插件不支持超级岛封面样式: reason=setFixIcon"),
                sysUi("09:53:10.000", 'E', "OpenAiTranslationClient", "翻译请求失败: code=401"),
                sysUi("09:53:20.000", 'W', "FontHelper", "自定义字体文件不存在或无法读取：/storage/emulated/0/Fonts/HarmonyOS_Sans.ttf (存在: false, 可读: false)"),
            )).forEach(::acceptLine)
        },
        env(
            listener = true,
            scope = setOf("com.android.systemui", "com.apple.android.music", "com.netease.cloudmusic"),
            pids = mapOf("com.android.systemui" to setOf(24949), "com.apple.android.music" to setOf(25914)),
        ).copy(
            aiTranslation = AiTranslationSettings(apiKeyBlank = false, targetLanguageBlank = false),
            customFontPath = "/storage/emulated/0/Fonts/HarmonyOS_Sans.ttf",
            lyricSource = LogAnalyzer.SUPER_LYRIC_SOURCE,
            featureSwitches = FeatureSwitches(islandCoverStyle = true),
            pluginsNeedingRepair = listOf(RepairablePlugin("applemusic", "Apple Music")),
        ),
    )

    /** 设置页「日志」组的真实排布：上面是 Apple Music 体验优化页的头卡作对照，下面是日志组。 */
    @Composable
    private fun EntryInContext(state: LogAnalysisUiState) {
        HeroCard(
            overline = "Apple Music",
            title = stringResource(R.string.apple_music_hero_ready),
            notes = listOf(stringResource(R.string.apple_music_hero_plugin_version, "1.0.39")),
            tone = HeroTone.Success,
            decorationIcon = MiuixIcons.Music,
            onClick = {},
            modifier = Modifier.settingsHeroCard(),
        )
        SmallTitle(text = stringResource(R.string.title_logs))
        LogAnalysisEntryContent(state = state, onOpen = {}, modifier = Modifier.settingsCard())
        Card(modifier = Modifier.settingsCard()) {
            Column {
                ArrowPreference(title = stringResource(R.string.title_log_level), summary = stringResource(R.string.log_level_normal), onClick = {})
                ArrowPreference(title = stringResource(R.string.title_view_logs), onClick = {})
                ArrowPreference(title = stringResource(R.string.export_all_logs), onClick = {})
            }
        }
    }

    private fun renderContent(themeMode: Int, name: String, fullWindow: Boolean = false, body: @Composable () -> Unit): Bitmap {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // 预览要用 App 的真实文案，只有带 -PhleTestResources 运行时才有资源；平时跳过。
        assumeTrue(runCatching { activity.getString(R.string.log_analysis_title) }.isSuccess)
        activity.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putInt(UIConstants.KEY_THEME_MODE, themeMode)
        }
        val pm = shadowOf(activity.packageManager)
        listOf("com.apple.android.music" to "Apple Music", "com.netease.cloudmusic" to "网易云音乐", "com.android.systemui" to "系统界面")
            .forEach { (pkg, label) ->
                pm.installPackage(
                    PackageInfo().apply {
                        packageName = pkg
                        applicationInfo = ApplicationInfo().apply {
                            packageName = pkg
                            nonLocalizedLabel = label
                        }
                    },
                )
            }
        var contentHeight = 0
        activity.setContent {
            ThemeUtils.MiuixThemeWrapper {
                CompositionLocalProvider(LocalNavigator provides Navigator(mutableListOf())) {
                    if (fullWindow) {
                        body()
                        return@CompositionLocalProvider
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // 真实页面是可滚动列表，高度不受限；头卡里「贴底」的空白只有在这种情况下才和真机一致。
                            .verticalScroll(rememberScrollState())
                            .background(MiuixTheme.colorScheme.surface)
                            .padding(vertical = 12.dp)
                            .onGloballyPositioned { contentHeight = it.size.height },
                    ) {
                        body()
                    }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        // miuix 卡片的圆角用 RuntimeShader，软件画布画不了，走 Robolectric 的硬件渲染截图。
        val window = activity.window
        val full = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        var copyResult = -1
        PixelCopy.request(window, full, { copyResult = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("截图失败", PixelCopy.SUCCESS, copyResult)
        val bitmap = if (fullWindow) full else Bitmap.createBitmap(full, 0, 0, full.width, contentHeight.coerceIn(1, full.height))
        val out = File("build/log-analysis-preview").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test
    fun reports_matchPreviewScenarios() {
        val problems = problemReport()
        assertEquals(1, problems.errorCount)
        assertEquals(2, problems.warningCount)
        assertEquals(5, problems.passCount)
        val warnings = warningReport()
        assertEquals(LogAnalysisHealth.WARNINGS_ONLY, warnings.health)
        assertEquals(2, warnings.warningCount)
        val clean = cleanReport()
        assertEquals(LogAnalysisHealth.CLEAN, clean.health)
        assertEquals(8, clean.passCount)
        val newRules = newRulesReport()
        assertEquals(1, newRules.errorCount)
        assertEquals(2, newRules.warningCount)
    }

    /** 和 Apple Music 体验优化页磁贴同一页对照：四种状态 × 浅色深色。 */
    @Test
    fun previewEntryStates() {
        val states = listOf(
            "checking" to LogAnalysisUiState(report = null, running = true),
            "clean" to LogAnalysisUiState(cleanReport()),
            "warning" to LogAnalysisUiState(warningReport()),
            "error" to LogAnalysisUiState(problemReport()),
            "newrules" to LogAnalysisUiState(newRulesReport()),
        )
        states.forEach { (tag, state) ->
            renderContent(1, "entry-$tag-light") { EntryInContext(state) }
            renderContent(5, "entry-$tag-dark") { EntryInContext(state) }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h1750dp-xxhdpi")
    fun previewDetailPage() {
        val problems = LogAnalysisUiState(problemReport())
        renderContent(1, "detail-problems-light", fullWindow = true) { LogAnalysisPageContent(problems, onAnalyze = {}) }
        renderContent(5, "detail-problems-dark", fullWindow = true) { LogAnalysisPageContent(problems, onAnalyze = {}, expandedEvidenceIndex = 0) }
        renderContent(1, "detail-clean-light", fullWindow = true) { LogAnalysisPageContent(LogAnalysisUiState(cleanReport()), onAnalyze = {}) }
    }

    /** 新增规则的详情页：8 个检查项 + 各条新卡片，浅色深色各一张（深色展开第一条证据）。 */
    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h3600dp-xxhdpi")
    fun previewNewRulesDetailPage() {
        val state = LogAnalysisUiState(newRulesReport())
        renderContent(1, "detail-newrules-light", fullWindow = true) { LogAnalysisPageContent(state, onAnalyze = {}) }
        renderContent(5, "detail-newrules-dark", fullWindow = true) { LogAnalysisPageContent(state, onAnalyze = {}, expandedEvidenceIndex = 0) }
    }
}
