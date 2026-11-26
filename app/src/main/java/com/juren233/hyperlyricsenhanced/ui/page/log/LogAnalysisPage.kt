/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page.log

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.HeroCard
import com.juren233.hyperlyricsenhanced.ui.component.NoticeCard
import com.juren233.hyperlyricsenhanced.ui.component.settingsCard
import com.juren233.hyperlyricsenhanced.ui.component.settingsHeroCard
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisReport
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheckResult
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheckStatus
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogFindingSeverity
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 外显卡片的最小高度：比 Apple Music 体验优化页的头卡（124dp）矮，只放两行字。 */
private val EntryMinHeight = 88.dp

/** 状态图标缩到卡片高度的 0.8 倍，圆形报错图标的上沿才落在右上角箭头下面。 */
private const val EntryDecorationScale = 0.8f

/**
 * 设置页「日志」组里的分析入口，与 Apple Music 体验优化页顶头的头卡同一种卡片：
 * 左上小字「日志分析」、下面大字状态语，右下角状态图标，整张卡片按状态换色，点进去看详情。
 */
@Composable
fun LogAnalysisEntry() {
    val navigator = LocalNavigator.current
    LogAnalysisEntryContent(
        state = LogAnalysisStore.state,
        onOpen = { navigator.navigate(Route.LogAnalysis) },
        modifier = Modifier.settingsCard(),
    )
}

@Composable
internal fun LogAnalysisEntryContent(
    state: LogAnalysisUiState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entryState = LogAnalysisEntryModel.stateOf(state.report)
    HeroCard(
        overline = stringResource(R.string.log_analysis_title),
        title = statusPhrase(state.report),
        decorationIcon = LogAnalysisEntryModel.iconOf(entryState),
        tone = LogAnalysisEntryModel.toneOf(entryState),
        onClick = onOpen,
        minHeight = EntryMinHeight,
        decorationScale = EntryDecorationScale,
        modifier = modifier,
    )
}

/** 大字状态语：「正在检查…」「一切正常」「1 个报错 · 2 个警告」。 */
@Composable
internal fun statusPhrase(report: LogAnalysisReport?): String =
    when (val phrase = LogAnalysisEntryModel.phraseOf(report)) {
        LogAnalysisStatusPhrase.Checking -> stringResource(R.string.log_analysis_hero_checking)
        LogAnalysisStatusPhrase.Clean -> stringResource(R.string.log_analysis_hero_clean)
        is LogAnalysisStatusPhrase.Counts -> listOfNotNull(
            phrase.errors.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.log_analysis_errors_phrase, it, it) },
            phrase.warnings.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.log_analysis_warnings_phrase, it, it) },
        ).joinToString(" · ")
    }

// ---------------- 详情子页面 ----------------

@Composable
fun LogAnalysisPage() {
    val context = LocalContext.current
    LogAnalysisPageContent(LogAnalysisStore.state, onAnalyze = { LogAnalysisStore.refresh(context, force = true) })
}

@Composable
internal fun LogAnalysisPageContent(
    state: LogAnalysisUiState,
    onAnalyze: () -> Unit,
    expandedEvidenceIndex: Int = -1,
    debugBuild: Boolean = BuildConfig.DEBUG,
) {
    XposedLyricSettingPage(
        title = stringResource(R.string.log_analysis_title),
        // 重新分析放在顶栏右侧，与 Apple Music 体验优化页的一键刷新同一位置；分析中换成转圈。
        trailingContent = {
            if (state.running) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(size = 22.dp, strokeWidth = 2.5.dp)
                }
            } else {
                IconButton(onClick = onAnalyze) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = stringResource(R.string.log_analysis_rerun),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        },
    ) {
        logAnalysisDetailItems(state, expandedEvidenceIndex, onAnalyze, debugBuild)
    }
}

internal fun LazyListScope.logAnalysisDetailItems(
    state: LogAnalysisUiState,
    expandedEvidenceIndex: Int = -1,
    onAnalyze: () -> Unit,
    debugBuild: Boolean = BuildConfig.DEBUG,
) {
    val report = state.report
    item(key = "summary") { DetailHeroCard(state.report) }
    if (report == null) return
    val problems = report.findings.sortedBy { it.severity.ordinal }
    if (problems.isNotEmpty()) {
        item(key = "problems_title") { SmallTitle(text = stringResource(R.string.log_analysis_section_problems)) }
        problems.forEachIndexed { index, finding ->
            item(key = "problem_$index") {
                Card(modifier = Modifier.settingsCard()) {
                    Column(modifier = Modifier.padding(horizontal = cardInset(), vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusGlyph(
                                if (finding.severity == LogFindingSeverity.ERROR) LogCheckStatus.ERROR else LogCheckStatus.WARNING,
                                24.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = describe(finding).title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        FindingDetails(finding, 0.dp, onAnalyze, index == expandedEvidenceIndex)
                    }
                }
            }
        }
    }
    item(key = "checks_title") { SmallTitle(text = stringResource(R.string.log_analysis_section_checks)) }
    item(key = "checks") {
        Card(modifier = Modifier.settingsCard()) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                report.checks.forEach { CheckLine(it, cardInset()) }
            }
        }
    }
    // 没检测出来时怎么反馈：用信息横幅（与首页「增强版」提示同一种），比页脚灰字显眼。
    item(key = "feedback_banner") {
        NoticeCard(
            text = stringResource(feedbackBannerText(debugBuild)),
            icon = MiuixIcons.Info,
            emphasized = true,
            modifier = Modifier.padding(top = 12.dp).settingsCard(),
        )
    }
}

/** 页底反馈横幅的文案：debug 包直接导出日志反馈；release 包先装 debug 包复现再导出。 */
internal fun feedbackBannerText(debugBuild: Boolean): Int =
    if (debugBuild) R.string.log_analysis_footer_debug else R.string.log_analysis_footer_release

/** 详情页顶头的头卡：与外显卡片同一套颜色和状态语，高度与 Apple Music 体验优化页的头卡一致。 */
@Composable
private fun DetailHeroCard(report: LogAnalysisReport?) {
    val entryState = LogAnalysisEntryModel.stateOf(report)
    HeroCard(
        overline = stringResource(R.string.log_analysis_title),
        title = statusPhrase(report),
        subtitle = heroDetail(report),
        // 全部通过时副标题已经说了「6 项检查全部通过」，不再重复。
        notes = listOfNotNull(
            report?.takeIf { entryState != LogAnalysisEntryState.CLEAN }
                ?.let { stringResource(R.string.log_analysis_checked_note, it.checkedCount, it.passCount) },
        ),
        decorationIcon = LogAnalysisEntryModel.iconOf(entryState),
        tone = LogAnalysisEntryModel.toneOf(entryState),
        modifier = Modifier.settingsHeroCard(),
    )
}

/** 检查项一览：一行一项，右侧写状态，不展开。 */
@Composable
private fun CheckLine(result: LogCheckResult, inset: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(result.status, 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = checkTitle(result.check),
                fontSize = 15.sp,
                color = if (result.status == LogCheckStatus.SKIPPED) {
                    MiuixTheme.colorScheme.onSurfaceVariantActions
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
            Text(text = checkSummary(result), fontSize = 12.sp, lineHeight = 16.sp, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusShort(result.status),
            fontSize = 13.sp,
            color = if (result.status == LogCheckStatus.SKIPPED) MiuixTheme.colorScheme.onSurfaceVariantActions else toneOf(result.status),
        )
    }
}

