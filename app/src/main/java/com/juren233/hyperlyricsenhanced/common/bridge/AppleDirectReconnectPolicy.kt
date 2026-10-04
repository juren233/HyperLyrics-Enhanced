/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

/** A finite retry burst. Success cancels pending retries but retains the cooldown budget. */
internal class AppleDirectReconnectPolicy(
    private val retryDelaysMs: List<Long> = listOf(0L, 500L, 1_000L, 2_000L, 4_000L),
    private val cooldownMs: Long = 30_000L,
) {
    init {
        require(retryDelaysMs.isNotEmpty() && retryDelaysMs.all { it >= 0 })
        require(cooldownMs > retryDelaysMs.sum())
    }

    var nextAttemptAtMs: Long? = null
        private set
    private var attempts = 0
    private var cooldownUntilMs = 0L

    /** Read-only diagnostics: observing an expired cooldown must never renew the retry budget. */
    data class Snapshot(
        val attemptsConsumed: Int,
        val maxAttempts: Int,
        val nextAttemptAtMs: Long?,
        val nextDelayMs: Long?,
        val cooldownRemainingMs: Long,
        val budgetConsumed: Boolean,
        val waitingForCooldown: Boolean,
    ) {
        fun diagnosticFields(): String =
            "attempt=$attemptsConsumed maxAttempts=$maxAttempts " +
                "nextDelayMs=${nextDelayMs ?: "none"} cooldownRemainingMs=$cooldownRemainingMs " +
                "budgetConsumed=$budgetConsumed waitingForCooldown=$waitingForCooldown"
    }

    fun snapshot(nowMs: Long): Snapshot = Snapshot(
        attemptsConsumed = attempts,
        maxAttempts = retryDelaysMs.size,
        nextAttemptAtMs = nextAttemptAtMs,
        nextDelayMs = nextAttemptAtMs?.let { (it - nowMs).coerceAtLeast(0L) },
        cooldownRemainingMs = (cooldownUntilMs - nowMs).coerceAtLeast(0L),
        budgetConsumed = attempts == retryDelaysMs.size,
        waitingForCooldown = attempts == retryDelaysMs.size &&
            nextAttemptAtMs?.let { it > nowMs } == true,
    )

    /** Coalesces duplicate triggers; exhausting a burst alone does not start another one. */
    fun request(nowMs: Long, allowReconnect: Boolean = true): Boolean {
        if (!allowReconnect || nextAttemptAtMs != null) return false
        resetBudgetIfExpired(nowMs)
        nextAttemptAtMs = if (attempts == retryDelaysMs.size) cooldownUntilMs
        else nowMs + retryDelaysMs[attempts]
        return true
    }

    /** Consumes one scheduled attempt and schedules at most the remainder of this burst. */
    fun takeAttempt(nowMs: Long): Boolean {
        val scheduled = nextAttemptAtMs ?: return false
        if (nowMs < scheduled) return false
        // Only a separately triggered, cooldown-delayed burst renews the budget. A delayed
        // Handler must still finish its current burst rather than renewing it indefinitely.
        if (attempts == retryDelaysMs.size) attempts = 0
        if (attempts == 0) cooldownUntilMs = nowMs + cooldownMs
        attempts++
        nextAttemptAtMs = if (attempts < retryDelaysMs.size) nowMs + retryDelaysMs[attempts]
        else null
        return true
    }

    fun connected() { nextAttemptAtMs = null }

    fun stop() {
        nextAttemptAtMs = null
        attempts = 0
        cooldownUntilMs = 0L
    }

    private fun resetBudgetIfExpired(nowMs: Long) {
        if (nowMs >= cooldownUntilMs) attempts = 0
    }
}
