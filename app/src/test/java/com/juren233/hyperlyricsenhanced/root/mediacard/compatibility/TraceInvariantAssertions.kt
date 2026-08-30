/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.compatibility

import com.juren233.hyperlyricsenhanced.root.mediacard.integration.PresentedFrame
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceCallbackDisposition
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceEvent
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

object TraceInvariantAssertions {
    fun assertEventSequenceMonotonic(events: List<TraceEvent>) {
        assertStrictlyIncreasing(
            values = events.map(TraceEvent::eventSequence),
            message = "event sequence must be strictly increasing",
        )
    }

    fun assertSnapshotSequenceMonotonic(events: List<TraceEvent>) {
        assertNonDecreasing(
            values = events.mapNotNull(TraceEvent::snapshotSequence),
            message = "snapshot sequence must be non-decreasing",
        )
    }

    fun assertStaleCallbacksDoNotMutateUi(events: List<TraceEvent>) {
        events.filter { it.callbackDisposition == TraceCallbackDisposition.STALE_REJECTED }
            .forEach { event ->
                assertFalse(
                    "stale callback mutated UI at event ${event.eventSequence}",
                    event.mutatesUi,
                )
            }
    }

    fun assertAtMostOneVisibleRoot(frames: List<PresentedFrame>) {
        frames.forEach { frame ->
            assertTrue(
                "visible root count exceeded one at frame ${frame.frameId}",
                frame.visibleRootCount <= 1,
            )
        }
    }

    fun assertFrameGeometry(frames: List<PresentedFrame>) {
        frames.forEach { frame ->
            val safeBottom = frame.playerBounds.bottom - frame.safeBottomInset
            frame.lyricBounds?.let { lyricBounds ->
                assertTrue(
                    "lyric bounds escaped player at frame ${frame.frameId}",
                    frame.playerBounds.contains(lyricBounds),
                )
                assertTrue(
                    "lyric bounds crossed safe bottom at frame ${frame.frameId}",
                    lyricBounds.bottom <= safeBottom,
                )
            }
            frame.progressBounds?.let { progressBounds ->
                assertTrue(
                    "progress bounds escaped player at frame ${frame.frameId}",
                    frame.playerBounds.contains(progressBounds),
                )
            }
            frame.actionBounds.forEach { actionBounds ->
                assertTrue(
                    "action bounds escaped player at frame ${frame.frameId}",
                    frame.playerBounds.contains(actionBounds),
                )
            }
            frame.lyricBounds?.let { lyricBounds ->
                frame.progressBounds?.let { progressBounds ->
                    assertFalse(
                        "lyric/progress overlap at frame ${frame.frameId}",
                        lyricBounds.intersects(progressBounds),
                    )
                }
                frame.actionBounds.forEach { actionBounds ->
                    assertFalse(
                        "lyric/action overlap at frame ${frame.frameId}",
                        lyricBounds.intersects(actionBounds),
                    )
                }
            }
            frame.progressBounds?.let { progressBounds ->
                frame.actionBounds.forEach { actionBounds ->
                    assertTrue(
                        "action must not be above progress at frame ${frame.frameId}",
                        actionBounds.top >= progressBounds.bottom,
                    )
                }
            }
        }
    }

    fun assertTerminalResourcesReleased(events: List<TraceEvent>) {
        events.filter {
            it.type == TraceEventType.NATIVE_COMPLETE || it.type == TraceEventType.NATIVE_CANCEL
        }.forEach { event ->
            assertEquals(
                "terminal event retained resources at event ${event.eventSequence}",
                0,
                event.activeResourceCount,
            )
        }
    }

    private fun assertStrictlyIncreasing(values: List<Long>, message: String) {
        values.zipWithNext().forEach { (previous, current) ->
            if (current <= previous) {
                fail("$message: $previous then $current")
            }
        }
    }

    private fun assertNonDecreasing(values: List<Long>, message: String) {
        values.zipWithNext().forEach { (previous, current) ->
            if (current < previous) {
                fail("$message: $previous then $current")
            }
        }
    }
}
