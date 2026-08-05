/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

/** Broadcasts a metadata configuration change to each state owner in a fixed order. */
internal class AppleMetadataConfigurationDispatcher(
    private val clearStateOwners: List<() -> Unit>,
    private val restoreCapturedModels: () -> Unit,
    private val scheduleConsumerRefresh: () -> Unit,
) {
    fun dispatch() {
        clearStateOwners.forEach { clearState -> clearState() }
        restoreCapturedModels()
        scheduleConsumerRefresh()
    }
}
