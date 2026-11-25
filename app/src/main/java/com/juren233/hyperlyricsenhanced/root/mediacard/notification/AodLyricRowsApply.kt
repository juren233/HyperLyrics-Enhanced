/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.hasLyricLineAdvanced

/** 一次应用里各逐字行要绑定的逐字数据（开关关闭的行为 null），以及间奏指示的起止时间。 */
internal data class AodRowWords(
    val main: List<LyricWord>? = null,
    val backing: List<LyricWord>? = null,
    val overlappingMain: List<LyricWord>? = null,
    val overlappingBacking: List<LyricWord>? = null,
    val interlude: LongRange? = null,
)

/**
 * 「动态效果」三个开关作用到一组息屏歌词行 [AodLyricRows] 的公共步骤，锁屏 AOD 与自定义 AOD
 * 各自读自己的开关（见 [AodTextStyleConfig]），走同一份代码：
 * 1. 应用新内容之前 [prepareTransition]：判定转场、结束进行中的转场、给旧内容拍快照；
 * 2. 写入新内容后 [bindWordSync]：逐字/圆点绑定到四个逐字行；
 * 3. 排版位置确定后 [AodLyricTransition.begin] 开始转场。
 */
internal object AodLyricRowsApply {

    /** 按 [style] 的「逐字效果」「灵动圆点」开关，从当前歌词行取各行逐字数据。 */
    fun currentWords(style: AodTextStyleConfig, mainText: String): AodRowWords {
        val wordSync = style.wordSyncEffect
        return AodRowWords(
            // 逐字开着时，逐行歌词也按该行起止时间逐字推进，只看息屏自己的开关。
            main = if (wordSync) {
                AodWordProgress.mainWords() ?: AodWordProgress.lineSpanWords(mainText)
            } else {
                null
            },
            backing = if (wordSync) AodWordProgress.backingWords() else null,
            overlappingMain = if (wordSync) AodWordProgress.overlappingMainWords() else null,
            overlappingBacking = if (wordSync) AodWordProgress.overlappingBackingWords() else null,
            interlude = if (style.interludeDots) AodWordProgress.interludeRange() else null,
        )
    }

    /**
     * 在把 [content] 写入控件之前调用。[nowInterlude] 表示新内容是前奏/间奏指示行。
     * 返回需要在新内容排版后开始的转场，不做转场时返回 null。
     */
    fun prepareTransition(
        rows: AodLyricRows,
        content: AodLyricContent,
        style: AodTextStyleConfig,
        currentLine: IRichLyricLine?,
        nowInterlude: Boolean,
    ): AodLyricTransition.Pending? {
        val oldRows = AodLyricTransition.shownRows(rows)
        val newRows = AodLyricTransition.contentRows(content)
        val styleChanged = rows.appliedTextStyle != style
        val kind = if (styleChanged) {
            AodTransitionKind.NONE
        } else {
            AodLyricTransitionPolicy.decide(
                canAnimate = AodLyricTransition.canAnimate(rows),
                lyricScroll = style.lyricScroll,
                interludeDots = style.interludeDots,
                wasInterlude = rows.main.isInterludeIndicator,
                nowInterlude = content.main.isNotBlank() && nowInterlude,
                lineAdvanced = hasLyricLineAdvanced(rows.appliedLine, currentLine),
                oldRows = oldRows,
                newRows = newRows,
            )
        }
        // 转场进行中又有新变化：当前转场直接落到终点，再从终点状态开始下一次。
        if (kind != AodTransitionKind.NONE || styleChanged ||
            AodLyricTransitionPolicy.rowsDiffer(oldRows, newRows)
        ) {
            AodLyricTransition.finish(rows)
        }
        return AodLyricTransition.capture(rows, kind, oldRows, newRows)
    }

    /** 新内容写入后，把逐字/圆点绑定到四个逐字行；[words] 中为 null 的行恢复整行显示。 */
    fun bindWordSync(
        rows: AodLyricRows,
        content: AodLyricContent,
        words: AodRowWords,
        playing: Boolean,
    ) {
        val keepAlive = {
            rows.drawWakeLock.acquire(NotificationMediaAodLyricHooker.DRAW_WAKE_LOCK_TIMEOUT_MS)
        }
        rows.main.bindWordSync(
            row = "main",
            text = content.main,
            words = words.main,
            interlude = words.interlude,
            playing = playing,
            keepAlive = keepAlive,
        )
        rows.backing.bindWordSync(
            row = "backing",
            text = content.backing,
            words = words.backing,
            playing = playing,
            keepAlive = keepAlive,
        )
        rows.overlappingMain.bindWordSync(
            row = "overlappingMain",
            text = content.overlappingMain,
            words = words.overlappingMain,
            playing = playing,
            keepAlive = keepAlive,
        )
        rows.overlappingBacking.bindWordSync(
            row = "overlappingBacking",
            text = content.overlappingBacking,
            words = words.overlappingBacking,
            playing = playing,
            keepAlive = keepAlive,
        )
    }
}
