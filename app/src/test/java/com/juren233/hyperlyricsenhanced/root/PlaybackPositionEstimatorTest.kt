package com.juren233.hyperlyricsenhanced.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackPositionEstimatorTest {
    @Test
    fun `estimates from the latest authoritative position while playing`() {
        val estimator = PlaybackPositionEstimator()
        estimator.update(position = 1_000L, realtime = 10_000L)
        estimator.setPlaying(true, realtime = 10_000L)

        assertEquals(1_250L, estimator.estimate(10_250L))
    }

    @Test
    fun `pause freezes and resume continues from frozen position`() {
        val estimator = PlaybackPositionEstimator()
        estimator.update(position = 2_000L, realtime = 10_000L)
        estimator.setPlaying(true, realtime = 10_000L)
        estimator.setPlaying(false, realtime = 10_500L)

        assertEquals(2_500L, estimator.estimate(20_000L))

        estimator.setPlaying(true, realtime = 20_000L)
        assertEquals(2_750L, estimator.estimate(20_250L))
    }

    @Test
    fun `reset removes stale track position`() {
        val estimator = PlaybackPositionEstimator()
        estimator.update(position = 3_000L, realtime = 10_000L)
        estimator.reset()

        assertNull(estimator.estimate(11_000L))
    }
}
