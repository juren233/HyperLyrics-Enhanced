/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 消费端防线（AOD-LYRICS-004）：Central 当前活动播放者在系统里已经没有任何
 * MediaSession、且持续超过宽限时间时，视为播放已终止，阻断其事件转发。
 *
 * 判据只看“该包的 MediaSession 是否存在”，与播放状态无关——缓冲、暂停时
 * 会话仍然存在，因此不会命中 `ISLAND-BUFFERING-RESTORE-001` 已否定的
 * “以位置冻结时长推断暂停”方向；也禁止用 UI 绑定（playerShown）替代。
 *
 * 两路真值输入，主次分明（`150213` 真机证据：app 侧监听器被 MIUI 解绑后
 * 沉默，快照冻结在过期非空值上，故 app 快照只能作回退）：
 * 1. [updateLocal]：SystemUI 进程内 `getActiveSessions(null)` 的本地集合，
 *    主真值；存在时快照不参与判定。
 * 2. [update]：模块 app 侧发布、经远端 prefs/广播推送的快照，仅当本地
 *    集合不可用时作为回退。
 *
 * 宽限锚点是“当前包集合首次出现”的时刻：周期性重发同一集合不改变集合
 * 本身，因此不会把宽限窗口反复清零；任意包缺失的时长至少为
 * `now - 集合首见时刻`，这是保守下界，作为阻断判据。
 *
 * 两档宽限（`150214` 真机结果：单一 12 秒宽限让残留显示过久）：
 * - 快速档 [FAST_BLOCK_GRACE_MS]：本地真值**亲见**该包消失（曾在集合中
 *   出现后被移除，区别于监听器从未见过的失明状态）且全局无音频
 *   （[isMusicActive] 为假）——关闭播放器的强证据，1 秒内阻断，体感实时。
 * - 慢速档 [BLOCK_GRACE_MS]：其余情况（空集合且从未见过该包、或仍有
 *   音频在放）。空集合与音频交叉验证沿用 `150212` 结论：监听器失明或
 *   存在非本包音频时放行；曲目切换/会话抖动的瞬时缺包必须受慢速档保护，
 *   误清后歌词要等下次切歌才会回填。
 *
 * 快速档只作用于本地主真值；app 快照回退路径恒为慢速档。
 *
 * 状态可能被多个线程并发访问；未知状态（无输入、快照过期、包名为空）
 * 一律 fail-open。时钟与音频状态通过构造注入，保证纯 JVM 可测。
 */
class ActiveMediaSessionGate(
    private val blockGraceMs: Long = BLOCK_GRACE_MS,
    private val fastBlockGraceMs: Long = FAST_BLOCK_GRACE_MS,
    private val nowElapsedMs: () -> Long,
    private val nowWallClockMs: () -> Long,
    private val isMusicActive: () -> Boolean = { false },
) {
    @Volatile
    private var snapshot: ActiveMediaSessionSnapshot.Snapshot? = null

    @Volatile
    private var snapshotFirstSeenElapsedMs = 0L

    @Volatile
    private var localPackages: Set<String>? = null

    @Volatile
    private var localFirstSeenElapsedMs = 0L

    /** 曾在本地集合中出现、随后消失的包；用于区分“亲见消失”与“从未见过”。 */
    private val witnessedDroppedPackages =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 当前生效的包集合（本地优先）；两路输入都缺失时为 null。 */
    val trackedPackages: Set<String>?
        get() = localPackages ?: snapshot?.packages

    /**
     * 更新 SystemUI 本地真值。传入 null 表示本地跟踪不可用，回退 app 快照。
     */
    fun updateLocal(packages: Set<String>?) {
        if (packages == null) {
            localPackages = null
            return
        }
        val previous = localPackages
        if (previous != null && previous != packages) {
            (previous - packages).forEach(witnessedDroppedPackages::add)
        }
        packages.forEach(witnessedDroppedPackages::remove)
        if (previous != packages) {
            localFirstSeenElapsedMs = nowElapsedMs()
        }
        localPackages = packages
    }

    /**
     * 更新 app 侧快照（回退真值）。发布方周期性重发同一集合只为刷新时间戳，
     * 不改变集合本身，因此不会重置宽限。
     */
    fun update(raw: String?) {
        val decoded = ActiveMediaSessionSnapshot.decode(raw)
        if (decoded == null) {
            snapshot = null
            return
        }
        if (snapshot?.packages != decoded.packages) {
            snapshotFirstSeenElapsedMs = nowElapsedMs()
        }
        snapshot = decoded
    }

    fun isBlocked(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val local = localPackages
        if (local != null) {
            return isAbsentFromLocalBeyondGrace(packageName, local)
        }
        val current = snapshot ?: return false
        if (current.isStale(nowWallClockMs())) return false
        return isAbsentBeyondGrace(
            packageName = packageName,
            packages = current.packages,
            firstSeenElapsedMs = snapshotFirstSeenElapsedMs,
        )
    }

    private fun isAbsentFromLocalBeyondGrace(packageName: String, packages: Set<String>): Boolean {
        if (packageName in packages) return false
        if (packages.isEmpty() && isMusicActive()) return false
        val strongCloseEvidence =
            !isMusicActive() &&
                (packages.isNotEmpty() || packageName in witnessedDroppedPackages)
        val grace = if (strongCloseEvidence) fastBlockGraceMs else blockGraceMs
        return nowElapsedMs() - localFirstSeenElapsedMs >= grace
    }

    private fun isAbsentBeyondGrace(
        packageName: String,
        packages: Set<String>,
        firstSeenElapsedMs: Long,
    ): Boolean {
        if (packageName in packages) return false
        if (packages.isEmpty() && isMusicActive()) return false
        return nowElapsedMs() - firstSeenElapsedMs >= blockGraceMs
    }

    companion object {
        /**
         * 慢速宽限：与 app 侧空会话宽限（5s）衔接并覆盖通知重建抖动。
         */
        const val BLOCK_GRACE_MS = 12_000L

        /**
         * 快速宽限：亲见包消失 + 全局无音频的强关闭证据下接近实时阻断。
         */
        const val FAST_BLOCK_GRACE_MS = 1_000L
    }
}
