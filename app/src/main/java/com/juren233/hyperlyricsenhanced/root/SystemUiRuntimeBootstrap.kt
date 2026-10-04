package com.juren233.hyperlyricsenhanced.root

/** Main-thread owner of one module generation's runtime startup. No Android dependency. */
internal class SystemUiRuntimeBootstrap<A : Any>(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val findApplication: () -> A?,
    private val isUsableApplication: (A) -> Boolean,
    private val initialize: (A) -> Unit,
    private val isRuntimeReady: () -> Boolean,
    private val rollback: () -> Unit,
    private val report: (String, String, Int, Throwable?) -> Unit = { _, _, _, _ -> },
) {
    enum class State { WAITING_FOR_APP, INITIALIZING, ROLLING_BACK, RETRY_WAIT, READY, FAILED, RETIRED }

    var state = State.WAITING_FOR_APP
        private set
    var attempts = 0
        private set
    private var armed = false
    private var generation = 0L
    private val pending = linkedSetOf<Runnable>()
    private val acquisitionOffsets = longArrayOf(0, 250, 1_000, 3_000, 5_000)
    private val retryDelays = longArrayOf(250, 1_000)

    /** Call on the main thread, before installing optional hooks. */
    fun arm() {
        if (armed || state != State.WAITING_FOR_APP) return
        armed = true
        emit("armed", "current_application", attempts, null)
        acquire(0)
    }

    /** A lifecycle callback supplies another signal, never a second runtime. */
    fun signal(application: A, trigger: String) {
        if (state != State.WAITING_FOR_APP) return
        if (!isUsable(application)) {
            emit("application_rejected", trigger, attempts, null)
            return
        }
        cancelPending()
        attempt(application, trigger, allowRetry = true)
    }

    /** Restoration must finish in this call; a queued retry cannot mean successful hot reload. */
    fun initializeNow(application: A): Boolean {
        if (state == State.READY) return isRuntimeReady()
        if (state != State.WAITING_FOR_APP && state != State.RETRY_WAIT) return false
        if (!isUsable(application)) return false
        cancelPending()
        return attempt(application, "hot_reload", allowRetry = false)
    }

    fun retire() {
        state = State.RETIRED
        generation++
        cancelPending()
    }

    private fun isUsable(application: A): Boolean {
        val validation = runCatching { isUsableApplication(application) }
        validation.exceptionOrNull()?.let {
            emit("application_validation_failed", "application_validation", attempts, it)
        }
        return validation.getOrDefault(false)
    }

    private fun emit(state: String, trigger: String, attempt: Int, failure: Throwable?) {
        // A diagnostics backend is optional and cannot become a startup dependency.
        runCatching { report(state, trigger, attempt, failure) }
    }

    private fun acquire(index: Int) {
        val delay = if (index == 0) 0 else acquisitionOffsets[index] - acquisitionOffsets[index - 1]
        schedule(delay) {
            if (state != State.WAITING_FOR_APP) return@schedule
            val lookup = runCatching(findApplication)
            val app = lookup.getOrNull()
            val poll = "current_application_poll_${index + 1}"
            val usable = app != null && isUsable(app)
            when {
                lookup.isFailure -> emit("application_lookup_failed", poll, attempts, lookup.exceptionOrNull())
                app == null -> emit("application_pending", poll, attempts, null)
                !usable -> emit("application_rejected", poll, attempts, null)
            }
            if (app != null && usable) {
                signal(app, "current_application")
            } else if (index + 1 < acquisitionOffsets.size) {
                acquire(index + 1)
            } else {
                // No more polling; an actual later lifecycle signal may still start the runtime.
                emit("application_unavailable", "current_application", attempts, null)
            }
        }
    }

    private fun attempt(application: A, trigger: String, allowRetry: Boolean): Boolean {
        state = State.INITIALIZING
        attempts++
        emit("initializing", trigger, attempts, null)
        try {
            initialize(application)
            if (state == State.RETIRED) return false
            check(isRuntimeReady()) { "Runtime initializer returned before readiness" }
            state = State.READY
            cancelPending()
            emit("ready", trigger, attempts, null)
            return true
        } catch (failure: Throwable) {
            if (state == State.RETIRED) return false
            state = State.ROLLING_BACK
            val cleanupFailure = runCatching(rollback).exceptionOrNull()
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure)
            if (state == State.RETIRED) return false
            if (cleanupFailure == null && allowRetry && attempts <= retryDelays.size) {
                state = State.RETRY_WAIT
                emit("retry_wait", trigger, attempts, failure)
                schedule(retryDelays[attempts - 1]) {
                    if (state == State.RETRY_WAIT) attempt(application, "retry", allowRetry = true)
                }
            } else {
                state = State.FAILED
                cancelPending()
                emit(if (cleanupFailure == null) "failed" else "rollback_failed", trigger, attempts, failure)
            }
            return false
        }
    }

    private fun schedule(delay: Long, action: () -> Unit) {
        val token = generation
        lateinit var task: Runnable
        task = Runnable {
            pending.remove(task)
            if (token == generation && state != State.RETIRED) action()
        }
        pending += task
        post(task, delay)
    }

    private fun cancelPending() {
        pending.forEach(remove)
        pending.clear()
    }
}
