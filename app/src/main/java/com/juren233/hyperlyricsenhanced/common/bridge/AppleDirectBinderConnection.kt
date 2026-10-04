/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

import android.os.IBinder
import android.os.IInterface

/** Owns the exact recipient paired with each installed connection and unlinks it on every exit. */
internal class AppleDirectBinderConnection<T : IInterface>(
    private val onDeath: () -> Unit,
    private val onLinkFailure: (Throwable) -> Unit,
) {
    class Entry<T : IInterface>(val target: T) {
        val binder: IBinder = target.asBinder()
        lateinit var recipient: IBinder.DeathRecipient
    }

    private val owner = AppleDirectConnectionOwner<Entry<T>>()
    val current: Entry<T>? get() = owner.current

    fun replace(target: T?): Entry<T>? = synchronized(owner) {
        val entry = target?.let { Entry(it) }
        if (entry != null) {
            entry.recipient = IBinder.DeathRecipient {
                if (clear(entry)) onDeath()
            }
        }
        owner.replace(entry)?.let(::unlink)
        if (entry != null) {
            try {
                entry.binder.linkToDeath(entry.recipient, 0)
            } catch (error: Exception) {
                clear(entry)
                onLinkFailure(error)
                return@synchronized null
            }
            // Also handles an immediately delivered, reentrant death during linkToDeath.
            if (owner.current !== entry) {
                unlink(entry)
                return@synchronized null
            }
        }
        entry
    }

    fun clear(expected: Entry<T>): Boolean = synchronized(owner) {
        if (!owner.clearIfCurrent(expected)) return@synchronized false
        unlink(expected)
        true
    }

    fun clear() = synchronized(owner) { owner.replace(null)?.let(::unlink); Unit }

    /** Shutdown only: revoke ownership before the last notification and always release its recipient. */
    fun clearWithFinalNotification(notify: (T) -> Unit): Result<Unit>? {
        val entry = synchronized(owner) { owner.replace(null) } ?: return null
        return try {
            runCatching { notify(entry.target) }
        } finally {
            unlink(entry)
        }
    }

    private fun unlink(entry: Entry<T>) {
        runCatching { entry.binder.unlinkToDeath(entry.recipient, 0) }
    }
}

/** The direct bridge uses this same monitor for outbound calls and final stop notification. */
internal inline fun <T> withAppleDirectLifecycle(lock: Any, action: () -> T): T =
    synchronized(lock, action)
