/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspendingHookResultPolicyTest {
    private enum class TargetCoroutineState {
        COROUTINE_SUSPENDED,
        RESUMED,
    }

    @Test
    fun recognizesTargetProcessSuspendMarkerByEnumName() {
        assertTrue(
            SuspendingHookResultPolicy.isCoroutineSuspended(
                TargetCoroutineState.COROUTINE_SUSPENDED,
            ),
        )
    }

    @Test
    fun completedResultsAreNotTreatedAsSuspended() {
        assertFalse(SuspendingHookResultPolicy.isCoroutineSuspended(TargetCoroutineState.RESUMED))
        assertFalse(SuspendingHookResultPolicy.isCoroutineSuspended(true))
        assertFalse(SuspendingHookResultPolicy.isCoroutineSuspended(null))
    }
}
