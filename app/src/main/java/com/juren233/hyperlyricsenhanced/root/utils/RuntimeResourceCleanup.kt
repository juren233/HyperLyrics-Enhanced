package com.juren233.hyperlyricsenhanced.root.utils

/** Runs every release even when an earlier resource cannot be released. */
internal class RuntimeResourceCleanup {
    private val failures = mutableListOf<Throwable>()

    fun attempt(resource: String, release: () -> Unit) {
        try {
            release()
        } catch (error: Throwable) {
            failures += IllegalStateException("Failed to release $resource", error)
        }
    }

    fun failureOrNull(): RuntimeResourceCleanupException? = failures.takeIf { it.isNotEmpty() }
        ?.let { RuntimeResourceCleanupException(it.toList()) }

    fun throwIfFailed() {
        failureOrNull()?.let { throw it }
    }
}

/** A failed rollback is not evidence that a new runtime attempt is safe. */
internal class RuntimeResourceCleanupException(failures: List<Throwable>) :
    IllegalStateException("Runtime resource cleanup failed (${failures.size})", failures.first()) {
    init {
        failures.drop(1).forEach(::addSuppressed)
    }
}
