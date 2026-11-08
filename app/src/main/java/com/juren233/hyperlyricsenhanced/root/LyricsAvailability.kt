/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「所有来源都没有歌词」的汇总判定。
 *
 * 第一方（Provider）取词彻底结束并上报缺失，且第三方在线兜底没有在途请求时，才算最终无歌词。
 * 最终状态一旦成立就锁存：兜底的后续重试不会让岛来回切换；之后任何来源送来歌词、切歌、
 * 清空状态都会 [reset]，立即回到歌词显示。
 */
internal object LyricsAvailability {
    private val thirdPartyProbes = CopyOnWriteArrayList<() -> Boolean>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var firstPartyReported = false

    @Volatile
    var finalUnavailable = false
        private set

    /** [probe] 返回 true 表示该第三方兜底此刻还有在途请求。 */
    fun addThirdPartyProbe(probe: () -> Boolean) {
        thirdPartyProbes.add(probe)
    }

    fun removeThirdPartyProbe(probe: () -> Boolean) {
        thirdPartyProbes.remove(probe)
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 时间轴收到带「歌词确认缺失」标记的占位歌曲。 */
    fun onFirstPartyReported() {
        firstPartyReported = true
        evaluate()
    }

    /** 某个第三方兜底请求结束（无论有无结果）。 */
    fun onThirdPartySettled() = evaluate()

    fun reset() {
        val wasFinal: Boolean
        synchronized(this) {
            firstPartyReported = false
            wasFinal = finalUnavailable
            finalUnavailable = false
        }
        if (wasFinal) notifyListeners()
    }

    private fun evaluate() {
        val changed = synchronized(this) {
            if (finalUnavailable || !firstPartyReported) return@synchronized false
            if (thirdPartyProbes.any { it() }) return@synchronized false
            finalUnavailable = true
            true
        }
        if (changed) notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { it() }
}
