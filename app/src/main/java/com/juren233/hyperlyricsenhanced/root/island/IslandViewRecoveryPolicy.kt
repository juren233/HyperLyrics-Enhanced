/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

internal object IslandViewRecoveryPolicy {
    enum class Action {
        REATTACH_HOST,
        REINJECT_REGISTERED_HOST,
        UPDATE_EXISTING_VIEW,
    }

    fun decide(
        hasRegisteredHost: Boolean,
        hasInjectedView: Boolean,
    ): Action = when {
        !hasRegisteredHost -> Action.REATTACH_HOST
        !hasInjectedView -> Action.REINJECT_REGISTERED_HOST
        else -> Action.UPDATE_EXISTING_VIEW
    }
}
