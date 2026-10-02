/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.juren233.hyperlyricsenhanced.root.utils

import com.juren233.hyperlyricsenhanced.root.utils.RuntimePerfDiagnostics.ThreadSample
import com.juren233.hyperlyricsenhanced.root.utils.RuntimePerfDiagnostics.classifyFreezeGap
import com.juren233.hyperlyricsenhanced.root.utils.RuntimePerfDiagnostics.formatTopThreads
import com.juren233.hyperlyricsenhanced.root.utils.RuntimePerfDiagnostics.parseJiffiesFromStatLine
import com.juren233.hyperlyricsenhanced.root.utils.RuntimePerfDiagnostics.parseStateFromStatLine
import com.juren233.hyperlyricsenhanced.root.utils.RuntimePerfDiagnostics.sanitize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerfDiagnosticsTest {

    @Test
    fun `stat line with spaces and parens inside comm parses utime plus stime`() {
        // comm="a (b) c"（字段含空格与括号），utime=100 stime=50（jiffies）。
        val line = "1234 (a (b) c) S 1 2 3 4 5 6 7 8 9 10 100 50 0 0 20 0"
        assertEquals(150L, parseJiffiesFromStatLine(line))
    }

    @Test
    fun `plain stat line parses jiffies`() {
        val line = "42 (kworker/0:1) R 1 1 1 0 -1 4194560 0 0 0 2 5 7 0 0 20 4 0"
        assertEquals(12L, parseJiffiesFromStatLine(line))
    }

    @Test
    fun `truncated or malformed stat line returns null`() {
        assertNull(parseJiffiesFromStatLine("1234 (comm) S 1 2"))
        assertNull(parseJiffiesFromStatLine(""))
        assertNull(parseJiffiesFromStatLine("no-parens 1 2 3"))
    }

    @Test
    fun `top threads sorted by delta with window-scaled percentage`() {
        val current = listOf(
            ThreadSample(1, "RenderThread", 500),
            ThreadSample(2, "HyperLyrics Enhanced-PerfDiag", 400),
            ThreadSample(3, "binder:1_1", 300),
            ThreadSample(4, "gone", 100),
        )
        val previous = mapOf(
            1 to 400L,   // Δ100 jiffies
            2 to 380L,   // Δ20
            3 to 299L,   // Δ1
            4 to 100L,   // Δ0 → 应被过滤
        )
        val windowMs = 1_000L
        val ticks = 100.0
        val result = formatTopThreads(current, previous, windowMs, ticks)
        // RenderThread Δ100/100ticks/1s = 100%；PerfDiag Δ20 → 20%；binder Δ1 → 1%。
        assertTrue(result.startsWith("RenderThread(1)=100.0%"))
        assertTrue(result.contains("HyperLyrics_Enhanced-Per(2)=20.0%"))
        assertTrue(result.contains("binder:1_1(3)=1.0%"))
        assertTrue(!result.contains("gone"))
    }

    @Test
    fun `no cpu delta reports idle and empty previous reports na`() {
        assertEquals("idle", formatTopThreads(emptyList(), mapOf(1 to 1L), 1_000L, 100.0))
        assertEquals("na", formatTopThreads(emptyList(), emptyMap(), 1_000L, 100.0))
        assertEquals("na", formatTopThreads(emptyList(), mapOf(1 to 1L), 0L, 100.0))
    }

    @Test
    fun `sanitize keeps log tokens single-tokened`() {
        assertEquals("HyperLyrics_Enhanced-Per", sanitize("HyperLyrics Enhanced-PerfDiag"))
        assertEquals("012345678901234567890123", sanitize("012345678901234567890123456789"))
    }

    @Test
    fun `gap with uptime nearly full classifies frozen`() {
        // #34 形态：103s 窗口内设备醒着、进程没跑（29-103s 冻结空窗）。
        val verdict = classifyFreezeGap(103_000L, 101_000L)
        assertEquals("frozen", verdict.likely)
        assertEquals(103_000L, verdict.gapMs)
        assertEquals(101_000L, verdict.uptimeJumpMs)
        assertEquals(2_000L, verdict.suspendMs)
    }

    @Test
    fun `gap absorbed by deep sleep classifies suspend`() {
        // 息屏深睡：uptime 暂停，窗口几乎全被 sleep 吸收。
        assertEquals("suspend", classifyFreezeGap(600_000L, 5_000L).likely)
    }

    @Test
    fun `gap with partial uptime classifies mixed`() {
        assertEquals("mixed", classifyFreezeGap(100_000L, 60_000L).likely)
    }

    @Test
    fun `negative or overrange uptime jumps are clamped`() {
        val negative = classifyFreezeGap(50_000L, -1L)
        assertEquals(0L, negative.uptimeJumpMs)
        assertEquals(50_000L, negative.suspendMs)
        assertEquals("suspend", negative.likely)
        val overrange = classifyFreezeGap(50_000L, 80_000L)
        assertEquals(50_000L, overrange.uptimeJumpMs)
        assertEquals("frozen", overrange.likely)
    }

    @Test
    fun `zero window classifies unclear`() {
        assertEquals("unclear", classifyFreezeGap(0L, 0L).likely)
    }

    @Test
    fun `stat line state char parses after comm`() {
        assertEquals('S', parseStateFromStatLine("1234 (a (b) c) S 1 2 3"))
        assertEquals('R', parseStateFromStatLine("42 (kworker/0:1) R 1 1"))
        assertNull(parseStateFromStatLine("1234 (comm)"))
        assertNull(parseStateFromStatLine(""))
    }
}
