/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page.log

import androidx.compose.ui.graphics.vector.ImageVector
import com.juren233.hyperlyricsenhanced.ui.component.HeroTone
import com.juren233.hyperlyricsenhanced.ui.component.StatusGlyphs
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisHealth
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisReport
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Search

/** 分析卡片的四种状态：还没分析完过一次、全部通过、只有警告、有报错。 */
internal enum class LogAnalysisEntryState { CHECKING, CLEAN, WARNING, ERROR }

/** 卡片上那句大字状态语。 */
internal sealed interface LogAnalysisStatusPhrase {
    /** 「正在检查…」 */
    data object Checking : LogAnalysisStatusPhrase

    /** 「一切正常」 */
    data object Clean : LogAnalysisStatusPhrase

    /** 「1 个报错 · 2 个警告」，为 0 的那一半不显示。 */
    data class Counts(val errors: Int, val warnings: Int) : LogAnalysisStatusPhrase
}

/**
 * 分析卡片的状态 → 颜色 / 图标 / 状态语。外显卡片和详情页顶头的头卡共用，
 * 颜色沿用 Apple Music 体验优化页头卡的 [HeroTone]，整张卡片按状态统一换色。
 */
internal object LogAnalysisEntryModel {
    fun stateOf(report: LogAnalysisReport?): LogAnalysisEntryState = when (report?.health) {
        null -> LogAnalysisEntryState.CHECKING
        LogAnalysisHealth.CLEAN -> LogAnalysisEntryState.CLEAN
        LogAnalysisHealth.WARNINGS_ONLY -> LogAnalysisEntryState.WARNING
        LogAnalysisHealth.HAS_ERRORS -> LogAnalysisEntryState.ERROR
    }

    /** 未检查用主题色，正常绿、警告琥珀、报错红，与 Apple Music 头卡同一套。 */
    fun toneOf(state: LogAnalysisEntryState): HeroTone = when (state) {
        LogAnalysisEntryState.CHECKING -> HeroTone.Accent
        LogAnalysisEntryState.CLEAN -> HeroTone.Success
        LogAnalysisEntryState.WARNING -> HeroTone.Warning
        LogAnalysisEntryState.ERROR -> HeroTone.Error
    }

    /** 右下角的状态图标；警告、报错用头卡同一套自绘图标。 */
    fun iconOf(state: LogAnalysisEntryState): ImageVector = when (state) {
        LogAnalysisEntryState.CHECKING -> MiuixIcons.Search
        LogAnalysisEntryState.CLEAN -> MiuixIcons.Ok
        LogAnalysisEntryState.WARNING -> StatusGlyphs.Warning
        LogAnalysisEntryState.ERROR -> StatusGlyphs.Error
    }

    fun phraseOf(report: LogAnalysisReport?): LogAnalysisStatusPhrase = when (stateOf(report)) {
        LogAnalysisEntryState.CHECKING -> LogAnalysisStatusPhrase.Checking
        LogAnalysisEntryState.CLEAN -> LogAnalysisStatusPhrase.Clean
        LogAnalysisEntryState.WARNING, LogAnalysisEntryState.ERROR ->
            LogAnalysisStatusPhrase.Counts(report!!.errorCount, report.warningCount)
    }
}
