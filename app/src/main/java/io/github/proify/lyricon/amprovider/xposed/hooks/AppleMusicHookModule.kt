/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

internal interface AppleMusicHookModule {
    val id: String
    val debugOnly: Boolean
        get() = false

    fun installHooks()
}

internal class FunctionalAppleMusicHookModule(
    override val id: String,
    override val debugOnly: Boolean = false,
    private val installer: () -> Unit,
) : AppleMusicHookModule {
    override fun installHooks() = installer()
}
