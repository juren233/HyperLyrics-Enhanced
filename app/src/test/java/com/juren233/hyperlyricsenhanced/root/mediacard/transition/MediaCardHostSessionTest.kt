/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.transition

import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationGroup
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationGroupModel
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationLine
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationModel
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationRole
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationSlot
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaCardFullAodTransitionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardHostSessionTest {
    @Test
    fun `presentation updates are pending while transition content stays frozen`() {
        val session = MediaCardHostSession(MediaCardControllerIdentity(1, 2))
        val first = model(1L, "old")
        val second = model(2L, "new")
        session.attach(first)
        session.acceptPresentation(first)
        val token = session.begin(
            listener = Any(),
            targetFullAod = true,
            mode = MediaCardFullAodTransitionMode.PAUSED_KEEP_LYRICS,
        ).token!!
        assertEquals(first, session.frozenPresentation)
        assertTrue(session.acceptPresentation(second))
        assertEquals(first, session.frozenPresentation)
        assertEquals(second, session.pendingPresentation)
        assertTrue(session.complete(token).accepted)
        assertEquals(second, session.stablePresentation)
        assertEquals(null, session.frozenPresentation)
    }

    private fun model(sequence: Long, text: String): LyricPresentationModel =
        LyricPresentationModel(
            snapshotSequence = sequence,
            songKey = "song",
            packageName = "player",
            positionMs = 0L,
            isPlaying = true,
            isTextMode = false,
            groups = listOf(
                LyricPresentationGroupModel(
                    group = LyricPresentationGroup.CURRENT,
                    lines = listOf(
                        LyricPresentationLine(
                            group = LyricPresentationGroup.CURRENT,
                            slot = LyricPresentationSlot.MAIN,
                            role = LyricPresentationRole.MAIN,
                            text = text,
                            alignment = com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationAlignment.CENTER,
                            blurDistance = 0,
                        ),
                    ),
                ),
            ),
        )
}
