package io.github.proify.lyricon.central.provider.player

import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PlayerBinderHandoverTest {
    /** Position ticks arrive asynchronously from the ticker and are not part of the replay. */
    private class Recording : PlayerListener {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun onSongChanged(recorder: PlayerRecorder, song: Song?) { events += "song=${song?.id}" }
        override fun onPlaybackStateChanged(recorder: PlayerRecorder, isPlaying: Boolean) { events += "playing=$isPlaying" }
        override fun onPositionChanged(recorder: PlayerRecorder, position: Long) = Unit
        override fun onSeekTo(recorder: PlayerRecorder, position: Long) { events += "seek=$position" }
        override fun onSendText(recorder: PlayerRecorder, text: String?) { events += "text=$text" }
        override fun onDisplayTranslationChanged(recorder: PlayerRecorder, isDisplayTranslation: Boolean) {
            events += "translation=$isDisplayTranslation"
        }
        override fun onDisplayRomaChanged(recorder: PlayerRecorder, displayRoma: Boolean) { events += "roma=$displayRoma" }
    }

    private val info = ProviderInfo("provider.pkg", "player.pkg", null, null, "player.process")

    @Test fun `replay feeds the last inputs in the provider library's reconnect order on the inherited memory`() {
        val retiring = PlayerBinder(info, Recording())
        retiring.setDisplayRoma(true)
        retiring.sendText("first")
        retiring.setDisplayTranslation(true)
        retiring.sendText("last")
        retiring.setPlaybackState(true)
        retiring.setPositionUpdateInterval(200)
        val state = retiring.handoverState()
        val memory = retiring.getPositionMemory()
        assertNotNull(memory)
        retiring.close(keepMemory = true)

        val events = Recording()
        val next = PlayerBinder(info, events, memory)
        next.replay(state)

        assertEquals(listOf("translation=true", "roma=true", "text=last", "playing=true", "seek=0"), events.events.toList())
        assertSame(memory, next.getPositionMemory())
        next.close()
    }

    @Test fun `a player that received nothing replays nothing`() {
        val retiring = PlayerBinder(info, Recording())
        val state = retiring.handoverState()
        retiring.close()

        val events = Recording()
        val next = PlayerBinder(info, events)
        next.replay(state)

        assertEquals(emptyList<String>(), events.events.toList())
        next.close()
    }
}
