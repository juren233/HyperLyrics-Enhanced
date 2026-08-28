/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.host

import java.lang.reflect.Field
import java.lang.reflect.Method

enum class SystemUiMediaCapabilityKind {
    MEDIA_CONTROLLER_LIFECYCLE,
    MEDIA_HEADER_GEOMETRY,
    FULL_AOD_CALLBACK,
    FULL_AOD_HEIGHT_LEASE,
}

data class SystemUiMediaCapability(
    val profile: SystemUiMediaProfile?,
    val classLoaderIdentity: String?,
    val supported: Set<SystemUiMediaCapabilityKind>,
    val unavailableReasons: Map<SystemUiMediaCapabilityKind, String>,
) {
    val enabled: Boolean
        get() = supported.isNotEmpty()

    fun supports(kind: SystemUiMediaCapabilityKind): Boolean = kind in supported

    fun reason(kind: SystemUiMediaCapabilityKind): String? = unavailableReasons[kind]

    companion object {
        fun disabled(reason: String): SystemUiMediaCapability = SystemUiMediaCapability(
            profile = null,
            classLoaderIdentity = null,
            supported = emptySet(),
            unavailableReasons = SystemUiMediaCapabilityKind.values()
                .associateWith { reason },
        )
    }
}

data class SystemUiMediaTransitionFrame(
    val targetFullAod: Boolean?,
    val fraction: Float?,
)

/**
 * The bottom-level contract used by the transition/session layer. The adapter owns
 * only reflection and native height-list restoration; it does not decide when a
 * session starts, how a callback is sequenced, or which View should be animated.
 */
interface NativeHeightLease : AutoCloseable {
    val classLoader: ClassLoader
    val originalHeights: IntArray

    fun setTargetHeight(index: Int, height: Int): Boolean

    fun restore(): Boolean

    override fun close() {
        restore()
    }
}

internal class ReflectiveNativeHeightLease(
    override val classLoader: ClassLoader,
    private val heightListField: Field,
    private val owner: Any,
) : NativeHeightLease {
    private val lock = Any()
    private var closed = false

    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    private val initialHeights: IntArray = synchronized(lock) {
        readHeights()
    }

    override val originalHeights: IntArray
        get() = synchronized(lock) { initialHeights.copyOf() }

    override fun setTargetHeight(index: Int, height: Int): Boolean = synchronized(lock) {
        if (closed || index !in initialHeights.indices) return false
        val values = readHeights()
        if (index !in values.indices) return false
        values[index] = height.coerceAtLeast(0)
        runCatching { heightListField.set(owner, values) }.isSuccess
    }

    override fun restore(): Boolean = synchronized(lock) {
        if (closed) return true
        val restored = runCatching {
            heightListField.set(owner, initialHeights.copyOf())
        }.isSuccess
        if (restored) closed = true
        restored
    }

    private fun readHeights(): IntArray = runCatching {
        (heightListField.get(owner) as? IntArray)?.copyOf()
    }.getOrNull() ?: IntArray(0)
}
