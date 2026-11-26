/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.juren233.hyperlyricsenhanced.common.ProjectLinks
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisReport
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheck
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheckResult
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheckSkipReason
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheckStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.root.RootApplication
import com.juren233.hyperlyricsenhanced.root.utils.ShellUtils
import com.juren233.hyperlyricsenhanced.ui.component.SectionEnter
import com.juren233.hyperlyricsenhanced.ui.component.SectionExit
import com.juren233.hyperlyricsenhanced.ui.component.HeroTone
import com.juren233.hyperlyricsenhanced.ui.component.SimpleDialog
import com.juren233.hyperlyricsenhanced.ui.component.heroPalette
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisHealth
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogEvidence
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogFinding
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogFixAction
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogFixPreferences
import com.juren233.hyperlyricsenhanced.utils.loganalysis.AiTranslationProblem
import com.juren233.hyperlyricsenhanced.utils.loganalysis.StartupComponent
import com.juren233.hyperlyricsenhanced.utils.loganalysis.SuperLyricProblem
import com.juren233.hyperlyricsenhanced.utils.loganalysis.SystemFeature
import com.juren233.hyperlyricsenhanced.utils.loganalysis.HookHost
import com.juren233.hyperlyricsenhanced.utils.loganalysis.RealRhythmProblem
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.utils.loganalysis.ModuleLogLineParser
import com.juren233.hyperlyricsenhanced.utils.loganalysis.ModuleLogSourceStatus
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ---------------- 分析入口与详情页共用 ----------------

/**
 * 反馈类按钮打开的网页。release 包的「下载 debug 包」打开仓库默认的 Releases 列表，
 * 不指向当前版本的 tag：用户顺带能装上最新版本。
 */
internal fun feedbackLink(action: LogFixAction): String? = when (action) {
    LogFixAction.OpenIssuePage -> ProjectLinks.NEW_ISSUE
    LogFixAction.OpenReleasePage -> ProjectLinks.RELEASES
    else -> null
}

/** 检查项的状态色：取 Apple Music 体验优化页头卡同一套色调的文字色，深浅色下都看得清，与外显卡片一致。 */
@Composable
internal fun toneOf(status: LogCheckStatus): Color = when (status) {
    LogCheckStatus.ERROR -> heroPalette(HeroTone.Error).content
    LogCheckStatus.WARNING -> heroPalette(HeroTone.Warning).content
    LogCheckStatus.PASS -> heroPalette(HeroTone.Success).content
    LogCheckStatus.SKIPPED -> MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.6f)
}

/** 状态小图标里符号（感叹号、勾、横线）的颜色：浅色下比 [toneOf] 浅一档（见 [TonalPalette.glyphInk]），深色下相同。 */
@Composable
internal fun glyphInkOf(status: LogCheckStatus): Color = when (status) {
    LogCheckStatus.ERROR -> heroPalette(HeroTone.Error).glyphInk
    LogCheckStatus.WARNING -> heroPalette(HeroTone.Warning).glyphInk
    LogCheckStatus.PASS -> heroPalette(HeroTone.Success).glyphInk
    LogCheckStatus.SKIPPED -> toneOf(status)
}

internal fun overallStatus(report: LogAnalysisReport?): LogCheckStatus = when (report?.health) {
    null -> LogCheckStatus.SKIPPED
    LogAnalysisHealth.HAS_ERRORS -> LogCheckStatus.ERROR
    LogAnalysisHealth.WARNINGS_ONLY -> LogCheckStatus.WARNING
    LogAnalysisHealth.CLEAN -> LogCheckStatus.PASS
}

@Composable
internal fun heroDetail(report: LogAnalysisReport?): String? = when (report?.health) {
    null -> null
    LogAnalysisHealth.CLEAN -> stringResource(R.string.log_analysis_hero_clean_detail, report.checkedCount)
    LogAnalysisHealth.HAS_ERRORS -> stringResource(R.string.log_analysis_hero_errors_detail)
    LogAnalysisHealth.WARNINGS_ONLY -> stringResource(R.string.log_analysis_hero_warnings_detail)
}

@Composable
internal fun checkTitle(check: LogCheck): String = stringResource(
    when (check) {
        LogCheck.MODULE -> R.string.log_analysis_check_module
        LogCheck.SCOPE -> R.string.log_analysis_check_scope
        LogCheck.VERSION -> R.string.log_analysis_check_version
        LogCheck.RUNTIME -> R.string.log_analysis_check_runtime
        LogCheck.PLUGIN -> R.string.log_analysis_check_plugin
        LogCheck.FEATURES -> R.string.log_analysis_check_features
        LogCheck.NOTIFICATION -> R.string.log_analysis_check_notification
        LogCheck.LOGS -> R.string.log_analysis_check_logs
    },
)

/** 一行检查项下面那句说明：通过时说检查了什么，未检查时说原因，有问题时说问题。 */
@Composable
internal fun checkSummary(result: LogCheckResult): String = when (result.status) {
    LogCheckStatus.PASS -> stringResource(
        when (result.check) {
            LogCheck.MODULE -> R.string.log_analysis_pass_module
            LogCheck.SCOPE -> R.string.log_analysis_pass_scope
            LogCheck.VERSION -> R.string.log_analysis_pass_version
            LogCheck.RUNTIME -> R.string.log_analysis_pass_runtime
            LogCheck.PLUGIN -> R.string.log_analysis_pass_plugin
            LogCheck.FEATURES -> R.string.log_analysis_pass_features
            LogCheck.NOTIFICATION -> R.string.log_analysis_pass_notification
            LogCheck.LOGS -> R.string.log_analysis_pass_logs
        },
    )
    LogCheckStatus.SKIPPED -> stringResource(
        when (result.skipReason) {
            LogCheckSkipReason.MODULE_INACTIVE -> R.string.log_analysis_skip_module_inactive
            LogCheckSkipReason.SCOPE_UNKNOWN -> R.string.log_analysis_skip_scope_unknown
            LogCheckSkipReason.LOGS_UNREADABLE -> R.string.log_analysis_skip_logs_unreadable
            LogCheckSkipReason.NOT_PLAYING -> R.string.log_analysis_skip_not_playing
            LogCheckSkipReason.PLAYER_HAS_NO_PLUGIN -> R.string.log_analysis_skip_player_no_plugin
            LogCheckSkipReason.FEATURES_OFF -> R.string.log_analysis_skip_features_off
            LogCheckSkipReason.ANALYSIS_FAILED, null -> R.string.log_analysis_skip_failed
        },
    )
    else -> result.findings.map { describe(it).title }.joinToString("\n")
}

@Composable
internal fun statusShort(status: LogCheckStatus): String = stringResource(
    when (status) {
        LogCheckStatus.ERROR -> R.string.log_analysis_status_error_short
        LogCheckStatus.WARNING -> R.string.log_analysis_status_warning_short
        LogCheckStatus.PASS -> R.string.log_analysis_status_pass_short
        LogCheckStatus.SKIPPED -> R.string.log_analysis_status_skip_short
    },
)

/** 状态图标的底形：警告是圆润三角形，其余（报错、通过、未检查）是圆形。 */
internal enum class StatusGlyphShape { CIRCLE, ROUNDED_TRIANGLE }

internal fun statusGlyphShape(status: LogCheckStatus): StatusGlyphShape =
    if (status == LogCheckStatus.WARNING) StatusGlyphShape.ROUNDED_TRIANGLE else StatusGlyphShape.CIRCLE

/**
 * 圆润三角形底形，坐标按 24×24 的格子给出再缩放到 [side]：顶点 (12,-2.6)、(26.4,22.6)、(-2.4,22.6)，
 * 三个角都用二次曲线圆过去（每个角从顶点沿两条边各退 [WARNING_CORNER] 个单位）。
 * 三角形比同格子的圆显小，所以三个尖角伸出格子一点（圆角后实际范围约 x -0.6..24.6、y -0.2..22.6），
 * 墨迹面积约为圆的 78%，重心比圆心略低，与旁边的圆形图标看起来一样大、一样高；画布不裁剪，伸出部分照常画出。
 */
internal fun roundedTrianglePath(side: Float): Path {
    val u = side / 24f
    val pts = listOf(Offset(12f, -2.6f), Offset(26.4f, 22.6f), Offset(-2.4f, 22.6f))
    fun toward(from: Offset, to: Offset, d: Float): Offset {
        val v = to - from
        return from + v * (d / v.getDistance())
    }
    return Path().apply {
        pts.indices.forEach { i ->
            val prev = pts[(i + 2) % 3]
            val cur = pts[i]
            val next = pts[(i + 1) % 3]
            val a = toward(cur, prev, WARNING_CORNER)
            val b = toward(cur, next, WARNING_CORNER)
            if (i == 0) moveTo(a.x * u, a.y * u) else lineTo(a.x * u, a.y * u)
            quadraticTo(cur.x * u, cur.y * u, b.x * u, b.y * u)
        }
        close()
    }
}

private const val WARNING_CORNER = 5.5f

/** 状态图标：通过画勾，问题画感叹号，未检查画横线；警告的底形是圆润三角形，其余是圆形。[filled] 为实心底色 + 白色符号。 */
@Composable
internal fun StatusGlyph(status: LogCheckStatus, size: Dp, filled: Boolean = false) {
    val tone = toneOf(status)
    val symbol = glyphInkOf(status)
    Canvas(modifier = Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val fill = if (filled) tone else tone.copy(alpha = 0.16f)
        val ink = if (filled) Color.White else symbol
        val stroke = r * if (filled) 0.17f else 0.2f
        when (statusGlyphShape(status)) {
            StatusGlyphShape.CIRCLE -> drawCircle(color = fill, radius = r)
            StatusGlyphShape.ROUNDED_TRIANGLE -> drawPath(roundedTrianglePath(r * 2f), fill)
        }
        when (status) {
            LogCheckStatus.PASS -> {
                val path = Path().apply {
                    moveTo(r * 0.55f, r * 1.02f)
                    lineTo(r * 0.88f, r * 1.35f)
                    lineTo(r * 1.47f, r * 0.7f)
                }
                drawPath(path, ink, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            LogCheckStatus.ERROR -> {
                drawLine(ink, Offset(r, r * 0.55f), Offset(r, r * 1.12f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(ink, radius = stroke * 0.62f, center = Offset(r, r * 1.45f))
            }
            LogCheckStatus.WARNING -> {
                // 感叹号在三角形里视觉居中：圆点底部到底边的距离，与竖线顶端到两条斜边的距离相等（都约 4.3 个单位）。
                drawLine(ink, Offset(r, r * 0.62f), Offset(r, r * 1.09f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(ink, radius = stroke * 0.62f, center = Offset(r, r * 1.4f))
            }
            LogCheckStatus.SKIPPED -> drawLine(
                ink,
                Offset(r * 0.62f, r),
                Offset(r * 1.38f, r),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
internal fun cardInset(): Dp = BasicComponentDefaults.InsideMargin.calculateStartPadding(LocalLayoutDirection.current)

/** 一条结论的说明、操作步骤、依据和修复按钮（标题由外层显示）。 */
@Composable
internal fun FindingDetails(
    finding: LogFinding,
    startPadding: Dp,
    onReanalyze: () -> Unit,
    initiallyShowEvidence: Boolean,
) {
    val context = LocalContext.current
    val text = describe(finding)
    var showEvidence by remember(finding) { mutableStateOf(initiallyShowEvidence) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = startPadding, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text.body,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        text.steps?.let { steps ->
            Text(text = steps, fontSize = 14.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurface)
        }
        // 「没认出的报错和警告」只给数量，没有依据可看。
        if (finding.evidence.isNotEmpty()) {
            Text(
                text = stringResource(if (showEvidence) R.string.log_analysis_hide_evidence else R.string.log_analysis_show_evidence),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clickable { showEvidence = !showEvidence }.padding(vertical = 2.dp),
            )
        }
        AnimatedVisibility(visible = showEvidence && finding.evidence.isNotEmpty(), enter = SectionEnter, exit = SectionExit) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                finding.evidence.forEach { evidence -> EvidenceItem(evidence) }
            }
        }
        if (finding.actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                finding.actions.forEachIndexed { index, action ->
                    FixActionButton(action = action, primary = index == 0, onDone = onReanalyze, context = context)
                }
            }
        }
    }
}

@Composable
private fun FixActionButton(action: LogFixAction, primary: Boolean, onDone: () -> Unit, context: Context) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var confirmRestart by remember { mutableStateOf(false) }
    val label = when (action) {
        is LogFixAction.RequestScope -> stringResource(R.string.log_analysis_action_request_scope)
        is LogFixAction.RestartProcesses -> stringResource(R.string.log_analysis_action_restart)
        LogFixAction.OpenPluginDownloads -> stringResource(R.string.log_analysis_action_download_plugin)
        LogFixAction.OpenPluginManager -> stringResource(R.string.log_analysis_action_enable_plugin)
        LogFixAction.OpenAppNotificationSettings -> stringResource(R.string.log_analysis_action_post_notification)
        LogFixAction.OpenNotificationListenerSettings -> stringResource(R.string.log_analysis_action_listener)
        LogFixAction.RepairPlugins -> stringResource(R.string.log_analysis_action_repair_plugin)
        LogFixAction.OpenAiTranslationSettings -> stringResource(R.string.log_analysis_action_ai_settings)
        LogFixAction.OpenFontSettings -> stringResource(R.string.log_analysis_action_font_settings)
        LogFixAction.ClearCustomFont -> stringResource(R.string.log_analysis_action_clear_font)
        is LogFixAction.SwitchLyricSource -> stringResource(R.string.log_analysis_action_use_lyricon)
        is LogFixAction.TurnOffFeature -> stringResource(R.string.log_analysis_action_turn_off)
        LogFixAction.OpenIssuePage -> stringResource(R.string.log_analysis_action_report_issue)
        LogFixAction.OpenReleasePage -> stringResource(R.string.log_analysis_action_download_debug)
        LogFixAction.TurnOffRealRhythm -> stringResource(R.string.log_analysis_action_turn_off)
    }
    val accent = MiuixTheme.colorScheme.primary
    TextButton(
        text = label,
        colors = if (primary) {
            ButtonDefaults.textButtonColors(color = accent.copy(alpha = 0.12f), textColor = accent)
        } else {
            ButtonDefaults.textButtonColors()
        },
        minHeight = 36.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        textStyle = MiuixTheme.textStyles.button.copy(fontSize = 15.sp),
        onClick = {
            when (action) {
                is LogFixAction.RequestScope -> requestScope(context, action.packages, onDone)
                is LogFixAction.RestartProcesses -> confirmRestart = true
                LogFixAction.OpenPluginDownloads -> navigator.navigate(Route.LyricProviderDownloads)
                LogFixAction.OpenPluginManager -> navigator.navigate(Route.LyricProvider)
                LogFixAction.OpenAppNotificationSettings -> context.startSettings(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
                LogFixAction.OpenNotificationListenerSettings -> context.startSettings(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                )
                LogFixAction.RepairPlugins -> navigator.navigate(Route.LyricProvider)
                LogFixAction.OpenAiTranslationSettings -> navigator.navigate(Route.LyricSettings)
                LogFixAction.OpenFontSettings -> navigator.navigate(Route.LyricDisplay)
                LogFixAction.OpenIssuePage,
                LogFixAction.OpenReleasePage -> feedbackLink(action)?.let(context::openLink)
                LogFixAction.TurnOffRealRhythm,
                LogFixAction.ClearCustomFont,
                is LogFixAction.SwitchLyricSource,
                is LogFixAction.TurnOffFeature -> {
                    applyPreferenceFix(context, action)
                    onDone()
                }
            }
        },
    )
    if (action is LogFixAction.RestartProcesses) {
        SimpleDialog(
            show = confirmRestart,
            title = stringResource(R.string.log_analysis_restart_confirm_title, appLabels(context, action.packages)),
            summary = stringResource(R.string.log_analysis_restart_confirm_summary),
            onDismiss = { confirmRestart = false },
            onConfirm = {
                confirmRestart = false
                scope.launch {
                    val ok = ShellUtils.killAppProcesses(action.packages)
                    if (!ok) {
                        Toast.makeText(context, R.string.log_analysis_restart_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.log_analysis_restart_done, Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                }
            },
        )
    }
}

/** 改设置的一键处理：写法与设置页相同，改完提示一句并重新分析。 */
private fun applyPreferenceFix(context: Context, action: LogFixAction) {
    val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
    LogFixPreferences.apply(prefs, LogFixPreferences.writesFor(action))
    val message = when (action) {
        LogFixAction.ClearCustomFont -> R.string.log_analysis_font_cleared
        is LogFixAction.SwitchLyricSource -> R.string.log_analysis_switched_to_lyricon
        else -> R.string.log_analysis_turned_off
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun Context.startSettings(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(this, R.string.log_analysis_open_settings_failed, Toast.LENGTH_SHORT).show() }
}

private fun Context.openLink(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(this, R.string.log_analysis_open_link_failed, Toast.LENGTH_SHORT).show() }
}

private fun requestScope(context: Context, packages: List<String>, onDone: () -> Unit) {
    val service = RootApplication.xposedService
    if (service == null) {
        Toast.makeText(context, R.string.log_analysis_scope_no_service, Toast.LENGTH_SHORT).show()
        return
    }
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    runCatching {
        service.requestScope(
            packages,
            object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    main.post {
                        Toast.makeText(context, R.string.log_analysis_scope_approved, Toast.LENGTH_LONG).show()
                        onDone()
                    }
                }

                override fun onScopeRequestFailed(message: String) {
                    main.post {
                        Toast.makeText(
                            context,
                            context.getString(R.string.log_analysis_scope_failed, message),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
        Toast.makeText(context, R.string.log_analysis_scope_requested, Toast.LENGTH_LONG).show()
    }.onFailure { error ->
        Toast.makeText(
            context,
            context.getString(R.string.log_analysis_scope_failed, error.message.orEmpty()),
            Toast.LENGTH_LONG,
        ).show()
    }
}

internal data class FindingText(val title: String, val body: String, val steps: String? = null)

@Composable
internal fun describe(finding: LogFinding): FindingText {
    val context = LocalContext.current
    return when (finding) {
        is LogFinding.LogsUnreadable -> FindingText(
            title = stringResource(R.string.log_analysis_logs_unreadable_title),
            body = sourceStatusText(finding.status),
            steps = if (finding.status == ModuleLogSourceStatus.NoRoot) {
                stringResource(R.string.log_analysis_logs_unreadable_no_root_steps)
            } else {
                null
            },
        )
        is LogFinding.ModuleInactive -> FindingText(
            title = stringResource(R.string.log_analysis_inactive_title),
            body = stringResource(R.string.log_analysis_inactive_body),
            steps = stringResource(R.string.log_analysis_inactive_steps),
        )
        is LogFinding.ScopeMissing -> FindingText(
            title = stringResource(R.string.log_analysis_scope_title, appLabels(context, finding.packages)),
            body = stringResource(
                if (finding.includesSystemUi) R.string.log_analysis_scope_body_system_ui else R.string.log_analysis_scope_body_plugin,
            ),
            steps = stringResource(R.string.log_analysis_scope_steps),
        )
        is LogFinding.StaleModule -> {
            val labels = appLabels(context, finding.processes.map { it.packageName }.distinct())
            val confirmed = finding.processes.all { it.confirmedRunning }
            FindingText(
                title = stringResource(R.string.log_analysis_stale_title, labels),
                body = stringResource(
                    if (confirmed) R.string.log_analysis_stale_body else R.string.log_analysis_stale_body_possible,
                    finding.installedVersionCode,
                ),
            )
        }
        is LogFinding.SystemUiRestartRequired -> FindingText(
            title = stringResource(R.string.log_analysis_systemui_restart_title),
            body = stringResource(R.string.log_analysis_systemui_restart_body),
        )
        is LogFinding.SystemUiStartupFailed -> FindingText(
            title = stringResource(R.string.log_analysis_startup_title),
            body = stringResource(
                if (finding.debugBuild) R.string.log_analysis_startup_body_debug else R.string.log_analysis_startup_body_release,
                finding.components.map { startupComponentName(it) }
                    .joinToString(stringResource(R.string.log_analysis_list_separator)),
            ),
        )
        is LogFinding.AiTranslationFailing -> FindingText(
            title = stringResource(R.string.log_analysis_ai_title),
            body = when (finding.problem) {
                AiTranslationProblem.MISSING_CONFIG -> stringResource(R.string.log_analysis_ai_body_missing)
                AiTranslationProblem.AUTH -> stringResource(R.string.log_analysis_ai_body_auth, finding.httpCode ?: 0)
                AiTranslationProblem.RATE_LIMIT -> stringResource(R.string.log_analysis_ai_body_rate_limit)
                AiTranslationProblem.HTTP_ERROR -> stringResource(R.string.log_analysis_ai_body_http, finding.httpCode ?: 0)
                AiTranslationProblem.BAD_RESPONSE -> stringResource(R.string.log_analysis_ai_body_bad_response)
                AiTranslationProblem.NETWORK -> stringResource(R.string.log_analysis_ai_body_network)
            },
            steps = stringResource(R.string.log_analysis_ai_steps),
        )
        is LogFinding.CustomFontUnreadable -> FindingText(
            title = stringResource(R.string.log_analysis_font_title),
            body = stringResource(R.string.log_analysis_font_body, finding.path),
            steps = stringResource(R.string.log_analysis_font_steps),
        )
        is LogFinding.SuperLyricUnavailable -> {
            val reason = when (finding.problem) {
                SuperLyricProblem.SERVICE_MISSING -> stringResource(R.string.log_analysis_superlyric_body_missing)
                SuperLyricProblem.FELL_BACK -> finding.fellBackTo
                    ?.let { stringResource(R.string.log_analysis_superlyric_body_fell_back, it) }
                    ?: stringResource(R.string.log_analysis_superlyric_body_fell_back_none)
                SuperLyricProblem.REGISTER_FAILED -> stringResource(R.string.log_analysis_superlyric_body_register)
            }
            FindingText(
                title = stringResource(R.string.log_analysis_superlyric_title),
                body = if (finding.serviceNowAvailable) {
                    reason + stringResource(R.string.log_analysis_superlyric_body_now_available)
                } else {
                    reason
                },
                steps = stringResource(R.string.log_analysis_superlyric_steps),
            )
        }
        is LogFinding.FeatureUnsupported -> {
            val host = finding.feature.hookHost
            FindingText(
                title = stringResource(R.string.log_analysis_feature_title, featureName(finding.feature)),
                body = if (host == null) {
                    stringResource(
                        if (finding.debugBuild) {
                            R.string.log_analysis_feature_body_narrow_font_debug
                        } else {
                            R.string.log_analysis_feature_body_narrow_font_release
                        },
                    )
                } else {
                    hookFailureBody(host, finding.feature, finding.debugBuild)
                },
                steps = listOfNotNull(
                    featureLocation(finding.feature),
                    stringResource(R.string.log_analysis_export_steps),
                ).joinToString("\n"),
            )
        }
        is LogFinding.RealRhythmNoSpectrum -> FindingText(
            title = stringResource(R.string.log_analysis_real_rhythm_title),
            body = stringResource(
                when (finding.problem) {
                    RealRhythmProblem.START_FAILED -> R.string.log_analysis_real_rhythm_body_start_failed
                    RealRhythmProblem.NO_SPECTRUM -> R.string.log_analysis_real_rhythm_body_no_spectrum
                },
            ),
            steps = stringResource(R.string.log_analysis_real_rhythm_steps),
        )
        is LogFinding.TranslationCacheFailing -> FindingText(
            title = stringResource(R.string.log_analysis_translation_cache_title),
            body = stringResource(
                if (finding.debugBuild) {
                    R.string.log_analysis_translation_cache_body_debug
                } else {
                    R.string.log_analysis_translation_cache_body_release
                },
            ),
            steps = stringResource(R.string.log_analysis_export_steps),
        )
        is LogFinding.UnrecognizedProblems -> FindingText(
            title = when {
                finding.errorCount == 0 -> stringResource(R.string.log_analysis_unrecognized_title_warnings, finding.warningCount)
                finding.warningCount == 0 -> stringResource(R.string.log_analysis_unrecognized_title_errors, finding.errorCount)
                else -> stringResource(R.string.log_analysis_unrecognized_title, finding.errorCount, finding.warningCount)
            },
            body = stringResource(
                if (finding.debugBuild) R.string.log_analysis_unrecognized_body_debug else R.string.log_analysis_unrecognized_body_release,
            ),
            steps = stringResource(R.string.log_analysis_export_steps),
        )
        is LogFinding.PluginNeedsRepair -> FindingText(
            title = stringResource(
                R.string.log_analysis_repair_title,
                finding.pluginNames.joinToString(stringResource(R.string.log_analysis_list_separator)),
            ),
            body = stringResource(R.string.log_analysis_repair_body),
        )
        is LogFinding.PlayerWithoutPlugin -> {
            val player = appLabel(context, finding.playerPackage)
            FindingText(
                title = stringResource(R.string.log_analysis_plugin_title, player),
                body = stringResource(
                    if (finding.pluginInstalled) R.string.log_analysis_plugin_body_disabled else R.string.log_analysis_plugin_body_missing,
                    player,
                    finding.pluginName,
                ),
            )
        }
        is LogFinding.NotificationAccessMissing -> {
            val features = listOfNotNull(
                stringResource(R.string.log_analysis_feature_dynamic_island).takeIf { finding.dynamicIsland },
                stringResource(R.string.log_analysis_feature_aod_focus).takeIf { finding.aodFocusNotification },
            ).joinToString(stringResource(R.string.log_analysis_list_separator))
            FindingText(
                title = stringResource(R.string.log_analysis_notification_title),
                body = stringResource(R.string.log_analysis_notification_body, features),
            )
        }
    }
}

@Composable
private fun startupComponentName(component: StartupComponent): String = stringResource(
    when (component) {
        StartupComponent.SYSTEM_ENVIRONMENT -> R.string.log_analysis_startup_system_environment
        StartupComponent.LYRICON_CENTRAL -> R.string.log_analysis_startup_lyricon_central
        StartupComponent.LYRIC_SOURCE -> R.string.log_analysis_startup_lyric_source
        StartupComponent.ISLAND_PLUGIN -> R.string.log_analysis_startup_island_plugin
    },
)

@Composable
private fun featureName(feature: SystemFeature): String = stringResource(
    when (feature) {
        SystemFeature.ISLAND_COVER_STYLE -> R.string.log_analysis_feature_island_cover_style
        SystemFeature.MUSIC_WAVE_COLOR -> R.string.log_analysis_feature_music_wave_color
        SystemFeature.NOTIFICATION_MEDIA_COVER -> R.string.log_analysis_feature_notification_media_cover
        SystemFeature.NARROW_LATIN_FONT -> R.string.log_analysis_feature_narrow_font
        SystemFeature.SETTINGS_ENTRY -> R.string.log_analysis_feature_settings_entry
        SystemFeature.AOD_LYRICS -> R.string.log_analysis_feature_aod_lyrics
        SystemFeature.ISLAND_EXPANDED_FLOW -> R.string.log_analysis_feature_island_expanded_flow
        SystemFeature.NOTIFICATION_MEDIA_CARD -> R.string.log_analysis_feature_notification_media_card
        SystemFeature.NOTIFICATION_MEDIA_BACKGROUND -> R.string.log_analysis_feature_notification_media_background
        SystemFeature.ISLAND_GLOW -> R.string.log_analysis_feature_island_glow
    },
)

@Composable
private fun featureLocation(feature: SystemFeature): String = stringResource(
    when (feature) {
        SystemFeature.ISLAND_COVER_STYLE -> R.string.log_analysis_feature_steps_island_cover_style
        SystemFeature.MUSIC_WAVE_COLOR -> R.string.log_analysis_feature_steps_music_wave_color
        SystemFeature.NOTIFICATION_MEDIA_COVER -> R.string.log_analysis_feature_steps_notification_media_cover
        SystemFeature.NARROW_LATIN_FONT -> R.string.log_analysis_feature_steps_narrow_font
        SystemFeature.SETTINGS_ENTRY -> R.string.log_analysis_feature_steps_settings_entry
        SystemFeature.AOD_LYRICS -> R.string.log_analysis_feature_steps_aod_lyrics
        SystemFeature.ISLAND_EXPANDED_FLOW -> R.string.log_analysis_feature_steps_island_expanded_flow
        SystemFeature.NOTIFICATION_MEDIA_CARD -> R.string.log_analysis_feature_steps_notification_media_card
        SystemFeature.NOTIFICATION_MEDIA_BACKGROUND -> R.string.log_analysis_feature_steps_notification_media_background
        SystemFeature.ISLAND_GLOW -> R.string.log_analysis_feature_steps_island_glow
    },
)

/** Hook 点位失效：哪个组件偏差 + （封面样式的同理说明）+ debug / release 两种反馈引导，附哪些 APK 按宿主决定。 */
@Composable
private fun hookFailureBody(host: HookHost, feature: SystemFeature, debugBuild: Boolean): String {
    val intro = stringResource(
        when (host) {
            HookHost.SYSTEM_UI -> R.string.log_analysis_hook_intro_system_ui
            HookHost.SYSTEM_UI_OR_AOD -> R.string.log_analysis_hook_intro_aod
            HookHost.SETTINGS -> R.string.log_analysis_hook_intro_settings
        },
    )
    val note = if (feature == SystemFeature.NOTIFICATION_MEDIA_COVER) {
        stringResource(R.string.log_analysis_hook_note_media_cover)
    } else {
        null
    }
    val apks = stringResource(
        when (host) {
            HookHost.SYSTEM_UI -> R.string.log_analysis_hook_apks_system_ui
            HookHost.SYSTEM_UI_OR_AOD -> R.string.log_analysis_hook_apks_aod
            HookHost.SETTINGS -> R.string.log_analysis_hook_apks_settings
        },
    )
    val feedback = stringResource(
        if (debugBuild) R.string.log_analysis_hook_feedback_debug else R.string.log_analysis_hook_feedback_release,
        apks,
    )
    return listOfNotNull(intro, note, feedback).joinToString(stringResource(R.string.log_analysis_sentence_join))
}

@Composable
private fun sourceStatusText(status: ModuleLogSourceStatus): String = when (status) {
    ModuleLogSourceStatus.Ok -> ""
    ModuleLogSourceStatus.NoRoot -> stringResource(R.string.log_analysis_source_no_root)
    ModuleLogSourceStatus.LsposedLogDirNotFound -> stringResource(R.string.log_analysis_source_no_lsposed)
    is ModuleLogSourceStatus.NoLogFiles -> stringResource(R.string.log_analysis_source_no_files, status.directory)
    is ModuleLogSourceStatus.ReadFailed -> stringResource(R.string.log_analysis_source_failed, status.message)
}

@Composable
private fun EvidenceItem(evidence: LogEvidence) {
    val textColor = MiuixTheme.colorScheme.onSecondaryContainer
    val record = (evidence as? LogEvidence.Line)?.let { ModuleLogLineParser.parse(it.text) }
    if (record == null) {
        Text(text = evidenceText(evidence), fontSize = 12.sp, lineHeight = 17.sp, color = textColor)
        return
    }
    // 日志原文太长：第一行放时间、进程、pid，第二行放标签和内容，去掉 LSPosed 的模块标识段。
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${record.time.substringAfter('T')}  ${record.level}  pid\u00A0${record.pid}  ${record.process}",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor.copy(alpha = 0.7f),
        )
        Text(text = "[${record.tag}] ${record.message}", fontSize = 12.sp, lineHeight = 17.sp, color = textColor)
    }
}

@Composable
private fun evidenceText(evidence: LogEvidence): String {
    val context = LocalContext.current
    return when (evidence) {
        is LogEvidence.Line -> evidence.text
        is LogEvidence.InstalledVersion -> stringResource(R.string.log_analysis_evidence_installed, evidence.versionCode)
        is LogEvidence.ScopeLacks -> stringResource(R.string.log_analysis_evidence_scope_lacks, evidence.packages.joinToString())
        is LogEvidence.NoRecordsFrom -> stringResource(
            R.string.log_analysis_evidence_no_records,
            evidence.scannedRecords,
            evidence.process,
        )
        is LogEvidence.ProcessRunning -> stringResource(
            R.string.log_analysis_evidence_running,
            evidence.process,
            evidence.pid,
            evidence.loadedVersionCode ?: 0L,
        )
        is LogEvidence.NoReloadAfterRejection -> stringResource(
            R.string.log_analysis_evidence_no_reload,
            evidence.process,
            evidence.pid,
        )
        LogEvidence.ServiceNotBound -> stringResource(R.string.log_analysis_evidence_service_not_bound)
        is LogEvidence.PlayingWithoutPlugin -> stringResource(
            if (evidence.installed) R.string.log_analysis_evidence_plugin_disabled else R.string.log_analysis_evidence_plugin_missing,
            appLabel(context, evidence.playerPackage),
            evidence.pluginId,
        )
        is LogEvidence.PermissionMissing -> listOfNotNull(
            stringResource(R.string.log_analysis_evidence_no_post_notification).takeIf { evidence.postNotification },
            stringResource(R.string.log_analysis_evidence_no_listener).takeIf { evidence.notificationListener },
        ).joinToString("\n")
        is LogEvidence.SourceError -> sourceStatusText(evidence.status)
        LogEvidence.AiTranslationConfigIncomplete -> stringResource(R.string.log_analysis_evidence_ai_config_incomplete)
        is LogEvidence.SuperLyricServiceCheck -> stringResource(
            if (evidence.found) R.string.log_analysis_evidence_superlyric_found else R.string.log_analysis_evidence_superlyric_not_found,
        )
        is LogEvidence.PluginFileBroken -> stringResource(R.string.log_analysis_evidence_plugin_broken, evidence.pluginName)
    }
}

private fun appLabels(context: Context, packages: List<String>): String =
    packages.joinToString(context.getString(R.string.log_analysis_list_separator)) { appLabel(context, it) }

private fun appLabel(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
}.getOrNull()?.takeIf(String::isNotBlank) ?: packageName
