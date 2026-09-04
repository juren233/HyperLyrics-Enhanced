/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMediaSessionGateTest {
    private var elapsedMs = 0L
    private var wallClockMs = 1_000_000L
    private var musicActive = false

    private fun gate(graceMs: Long = ActiveMediaSessionGate.BLOCK_GRACE_MS) =
        ActiveMediaSessionGate(
            blockGraceMs = graceMs,
            nowElapsedMs = { elapsedMs },
            nowWallClockMs = { wallClockMs },
            isMusicActive = { musicActive },
        )

    @Test
    fun `snapshot encode and decode round trip`() {
        val raw = ActiveMediaSessionSnapshot.encode(
            publishedAtMs = 123L,
            packages = setOf("com.netease.cloudmusic", "com.apple.android.music"),
        )
        val snapshot = ActiveMediaSessionSnapshot.decode(raw)!!
        assertEquals(123L, snapshot.publishedAtMs)
        assertEquals(
            setOf("com.apple.android.music", "com.netease.cloudmusic"),
            snapshot.packages,
        )
    }

    @Test
    fun `empty package set is a valid snapshot`() {
        val raw = ActiveMediaSessionSnapshot.encode(publishedAtMs = 42L, packages = emptySet())
        val snapshot = ActiveMediaSessionSnapshot.decode(raw)!!
        assertEquals(42L, snapshot.publishedAtMs)
        assertTrue(snapshot.packages.isEmpty())
    }

    @Test
    fun `malformed snapshots decode to null`() {
        assertNull(ActiveMediaSessionSnapshot.decode(null))
        assertNull(ActiveMediaSessionSnapshot.decode(""))
        assertNull(ActiveMediaSessionSnapshot.decode("no-separator"))
        assertNull(ActiveMediaSessionSnapshot.decode("|com.example"))
        assertNull(ActiveMediaSessionSnapshot.decode("not-a-timestamp|com.example"))
    }

    @Test
    fun `package present in snapshot is never blocked`() {
        val gate = gate()
        gate.update(ActiveMediaSessionSnapshot.encode(1L, setOf("com.example.player")))
        elapsedMs += 60_000L
        assertFalse(gate.isBlocked("com.example.player"))
    }

    @Test
    fun `absence within grace stays open and beyond grace blocks`() {
        val gate = gate(graceMs = 12_000L)
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, setOf("other.app")))
        elapsedMs += 11_999L
        assertFalse(gate.isBlocked("com.example.player"))
        elapsedMs += 1L
        assertTrue(gate.isBlocked("com.example.player"))
    }

    @Test
    fun `package reappearing clears the block immediately`() {
        val gate = gate(graceMs = 12_000L)
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        elapsedMs += 30_000L
        assertTrue(gate.isBlocked("com.example.player"))
        wallClockMs += 30_000L
        gate.update(
            ActiveMediaSessionSnapshot.encode(wallClockMs, setOf("com.example.player")),
        )
        assertFalse(gate.isBlocked("com.example.player"))
    }

    @Test
    fun `stale snapshot fails open even when package is absent`() {
        val gate = gate(graceMs = 12_000L)
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        elapsedMs += 60_000L
        wallClockMs += ActiveMediaSessionSnapshot.STALE_AFTER_MS + 1L
        assertFalse(gate.isBlocked("com.example.player"))
    }

    @Test
    fun `periodic republish of the same empty snapshot must not reset grace`() {
        val gate = gate(graceMs = 12_000L)
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        elapsedMs += 8_000L
        wallClockMs += 8_000L
        // 发布方 60 秒周期重发同一空集合，消费方不得重置缺失计时。
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        elapsedMs += 4_001L
        assertTrue(gate.isBlocked("com.example.player"))
    }

    @Test
    fun `missing snapshot fails open`() {
        val gate = gate()
        gate.update(null)
        elapsedMs += 60_000L
        assertFalse(gate.isBlocked("com.example.player"))
    }

    @Test
    fun `blank package name fails open`() {
        val gate = gate()
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        elapsedMs += 60_000L
        assertFalse(gate.isBlocked(null))
        assertFalse(gate.isBlocked(" "))
    }

    @Test
    fun `empty snapshot with music playing fails open even beyond grace`() {
        // 150212 真机失败回归：通知监听器失明时 getActiveSessions 返回空列表，
        // 但系统音频仍在播放，此时绝不能阻断正在播放的歌词。
        val gate = gate()
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        musicActive = true
        elapsedMs += 120_000L
        wallClockMs += 120_000L
        assertFalse(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `empty snapshot with no audio blocks after grace`() {
        val gate = gate()
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        musicActive = false
        elapsedMs += 12_000L
        assertTrue(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `non-empty snapshot missing the player blocks even with unrelated audio`() {
        // 监听器已证明可见（快照里有别的包），缺失的目标包可信；
        // 无关音频不得否决对已关闭播放者的阻断。
        val gate = gate()
        gate.update(
            ActiveMediaSessionSnapshot.encode(wallClockMs, setOf("com.other.video")),
        )
        musicActive = true
        elapsedMs += 12_000L
        assertTrue(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `music stopping re-enables empty snapshot blocking`() {
        val gate = gate()
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        musicActive = true
        elapsedMs += 60_000L
        wallClockMs += 60_000L
        assertFalse(gate.isBlocked("com.netease.cloudmusic"))
        musicActive = false
        elapsedMs += 12_000L
        wallClockMs += 12_000L
        assertTrue(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `local truth missing the player blocks even when stale snapshot keeps the player`() {
        // 150213 真机失败回归：app 侧监听器沉默后快照冻结在 [player]，
        // SystemUI 本地查询必须能推翻过期快照。
        val gate = gate()
        gate.update(
            ActiveMediaSessionSnapshot.encode(wallClockMs, setOf("com.netease.cloudmusic")),
        )
        gate.updateLocal(emptySet())
        elapsedMs += 12_000L
        assertTrue(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `local truth keeps the player open even when snapshot says absent`() {
        val gate = gate()
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        gate.updateLocal(setOf("com.netease.cloudmusic"))
        elapsedMs += 120_000L
        wallClockMs += 120_000L
        assertFalse(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `null local truth falls back to snapshot`() {
        val gate = gate()
        gate.updateLocal(setOf("com.netease.cloudmusic"))
        gate.updateLocal(null)
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        elapsedMs += 12_000L
        assertTrue(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `local empty set with music playing fails open`() {
        val gate = gate()
        gate.updateLocal(emptySet())
        musicActive = true
        elapsedMs += 120_000L
        assertFalse(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `tracked packages prefer local truth`() {
        val gate = gate()
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, setOf("from.snapshot")))
        gate.updateLocal(setOf("from.local"))
        assertEquals(setOf("from.local"), gate.trackedPackages)
    }

    @Test
    fun `witnessed drop with no audio blocks after fast grace`() {
        // 150214 真机结果回归：亲见包消失 + 全局无音频 → 1 秒内阻断，接近实时。
        val gate = gate()
        gate.updateLocal(setOf("com.netease.cloudmusic"))
        elapsedMs += 60_000L
        gate.updateLocal(emptySet())
        elapsedMs += 999L
        assertFalse(gate.isBlocked("com.netease.cloudmusic"))
        elapsedMs += 2L
        assertTrue(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `witnessed drop with audio still playing keeps slow grace`() {
        // 音频仍在放（可能是其他应用）时不走快速档，维持 12 秒慢速宽限。
        val gate = gate()
        gate.updateLocal(setOf("com.netease.cloudmusic", "com.other.player"))
        elapsedMs += 60_000L
        musicActive = true
        gate.updateLocal(setOf("com.other.player"))
        elapsedMs += 2_000L
        assertFalse(gate.isBlocked("com.netease.cloudmusic"))
        elapsedMs += 10_000L
        assertTrue(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `flap within fast grace does not block`() {
        // 会话拆除风暴期的瞬时抖动：1 秒内回来的缺包不得触发快速阻断。
        val gate = gate()
        gate.updateLocal(setOf("com.netease.cloudmusic"))
        elapsedMs += 60_000L
        gate.updateLocal(emptySet())
        elapsedMs += 300L
        gate.updateLocal(setOf("com.netease.cloudmusic"))
        elapsedMs += 300L
        gate.updateLocal(emptySet())
        elapsedMs += 999L
        assertFalse(gate.isBlocked("com.netease.cloudmusic"))
        elapsedMs += 2L
        assertTrue(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `born-blind empty set without audio keeps slow grace`() {
        // 本地集合从未见过该包（监听器失明）时不得走快速档。
        val gate = gate()
        gate.updateLocal(emptySet())
        elapsedMs += 2_000L
        assertFalse(gate.isBlocked("com.netease.cloudmusic"))
        elapsedMs += 10_000L
        assertTrue(gate.isBlocked("com.netease.cloudmusic"))
    }

    @Test
    fun `snapshot fallback path never uses fast grace`() {
        val gate = gate()
        gate.update(
            ActiveMediaSessionSnapshot.encode(wallClockMs, setOf("com.netease.cloudmusic")),
        )
        wallClockMs += 60_000L
        gate.update(ActiveMediaSessionSnapshot.encode(wallClockMs, emptySet()))
        elapsedMs += 2_000L
        assertFalse(gate.isBlocked("com.netease.cloudmusic"))
        elapsedMs += 10_000L
        assertTrue(gate.isBlocked("com.netease.cloudmusic"))
    }
}
