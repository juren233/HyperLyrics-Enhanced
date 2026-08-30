package com.juren233.hyperlyricsenhanced.root.mediacard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardNotificationLyricPresentationPolicyTest {
    @Test
    fun `keeps an already presented root visible across a lyric content update`() {
        assertFalse(
            MediaCardNotificationLyricPresentationPolicy.shouldStartPresentationGate(
                rootWasVisible = true,
            )
        )
        assertTrue(
            MediaCardNotificationLyricPresentationPolicy
                .shouldPreservePresentedRootForContentUpdate(
                    rootWasVisible = true,
                    contentChanged = true,
                )
        )
    }

    @Test
    fun `starts the visibility gate only for a root that is not yet presented`() {
        assertTrue(
            MediaCardNotificationLyricPresentationPolicy.shouldStartPresentationGate(
                rootWasVisible = false,
            )
        )
        assertFalse(
            MediaCardNotificationLyricPresentationPolicy
                .shouldPreservePresentedRootForContentUpdate(
                    rootWasVisible = false,
                    contentChanged = true,
                )
        )
    }

    @Test
    fun `hides during unsettled geometry only when a presentation gate owns the root`() {
        assertTrue(
            MediaCardNotificationLyricPresentationPolicy.shouldHideWhileGeometrySettles(
                presentationGateActive = true,
            )
        )
        assertFalse(
            MediaCardNotificationLyricPresentationPolicy.shouldHideWhileGeometrySettles(
                presentationGateActive = false,
            )
        )
    }
}
