package com.juren233.hyperlyricsenhanced.ui.page.log

import com.juren233.hyperlyricsenhanced.ui.component.HeroTone
import com.juren233.hyperlyricsenhanced.ui.component.StatusGlyphs
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisReport
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheck
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheckResult
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheckSkipReason
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogCheckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Search

/** 外显卡片的状态 → 颜色 / 图标 / 状态语，四种状态各验一遍。 */
class LogAnalysisEntryModelTest {
    private fun report(vararg statuses: Pair<LogCheck, LogCheckStatus>): LogAnalysisReport {
        val map = statuses.toMap()
        return LogAnalysisReport(
            LogCheck.entries.map { check ->
                val status = map[check] ?: LogCheckStatus.PASS
                LogCheckResult(check, status, skipReason = LogCheckSkipReason.SCOPE_UNKNOWN.takeIf { status == LogCheckStatus.SKIPPED })
            },
        )
    }

    @Test
    fun noReportYet_checkingInThemeColor() {
        val state = LogAnalysisEntryModel.stateOf(null)
        assertEquals(LogAnalysisEntryState.CHECKING, state)
        assertEquals(HeroTone.Accent, LogAnalysisEntryModel.toneOf(state))
        assertSame(MiuixIcons.Search, LogAnalysisEntryModel.iconOf(state))
        assertEquals(LogAnalysisStatusPhrase.Checking, LogAnalysisEntryModel.phraseOf(null))
    }

    @Test
    fun allPassed_greenTick_allNormal() {
        val clean = report()
        assertEquals(LogAnalysisEntryState.CLEAN, LogAnalysisEntryModel.stateOf(clean))
        assertEquals(HeroTone.Success, LogAnalysisEntryModel.toneOf(LogAnalysisEntryState.CLEAN))
        assertSame(MiuixIcons.Ok, LogAnalysisEntryModel.iconOf(LogAnalysisEntryState.CLEAN))
        assertEquals(LogAnalysisStatusPhrase.Clean, LogAnalysisEntryModel.phraseOf(clean))
    }

    @Test
    fun skippedChecksAlone_stillAllNormal() {
        val skipped = report(LogCheck.SCOPE to LogCheckStatus.SKIPPED)
        assertEquals(LogAnalysisEntryState.CLEAN, LogAnalysisEntryModel.stateOf(skipped))
    }

    @Test
    fun warningsOnly_amberTriangle_countsWarnings() {
        val warn = report(LogCheck.SCOPE to LogCheckStatus.WARNING, LogCheck.NOTIFICATION to LogCheckStatus.WARNING)
        assertEquals(LogAnalysisEntryState.WARNING, LogAnalysisEntryModel.stateOf(warn))
        assertEquals(HeroTone.Warning, LogAnalysisEntryModel.toneOf(LogAnalysisEntryState.WARNING))
        assertSame(StatusGlyphs.Warning, LogAnalysisEntryModel.iconOf(LogAnalysisEntryState.WARNING))
        assertEquals(LogAnalysisStatusPhrase.Counts(errors = 0, warnings = 2), LogAnalysisEntryModel.phraseOf(warn))
    }

    @Test
    fun anyError_redCircle_countsBoth() {
        val err = report(
            LogCheck.VERSION to LogCheckStatus.ERROR,
            LogCheck.SCOPE to LogCheckStatus.WARNING,
            LogCheck.NOTIFICATION to LogCheckStatus.WARNING,
        )
        assertEquals(LogAnalysisEntryState.ERROR, LogAnalysisEntryModel.stateOf(err))
        assertEquals(HeroTone.Error, LogAnalysisEntryModel.toneOf(LogAnalysisEntryState.ERROR))
        assertSame(StatusGlyphs.Error, LogAnalysisEntryModel.iconOf(LogAnalysisEntryState.ERROR))
        assertEquals(LogAnalysisStatusPhrase.Counts(errors = 1, warnings = 2), LogAnalysisEntryModel.phraseOf(err))
    }

    @Test
    fun everyStateHasItsOwnColor() {
        val tones = LogAnalysisEntryState.entries.map(LogAnalysisEntryModel::toneOf)
        assertEquals(tones.size, tones.toSet().size)
    }
}
