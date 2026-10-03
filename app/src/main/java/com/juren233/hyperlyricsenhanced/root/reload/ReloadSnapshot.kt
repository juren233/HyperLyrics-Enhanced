/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

/**
 * Cross-generation envelope. Only JDK containers, scalar values and existing host objects
 * belong here: never transfer a module state, callback, accessor, drawable or custom View.
 * This is an in-process libxposed saved state, not a Bundle/Parcelable serialization format.
 */
internal object ReloadSnapshot {
    private const val VERSION = "hleSystemUiReloadVersion"
    private const val CURRENT_VERSION = 1

    fun create(): HashMap<String, Any?> = hashMapOf(VERSION to CURRENT_VERSION)

    fun read(value: Any?): Map<*, *>? = (value as? Map<*, *>)
        ?.takeIf { it[VERSION] == CURRENT_VERSION }

    fun items(state: Map<*, *>, key: String): List<Any> =
        (state[key] as? Array<*>)?.filterNotNull().orEmpty()

    fun rows(state: Map<*, *>, key: String): List<Array<*>> =
        items(state, key).filterIsInstance<Array<*>>()
}
