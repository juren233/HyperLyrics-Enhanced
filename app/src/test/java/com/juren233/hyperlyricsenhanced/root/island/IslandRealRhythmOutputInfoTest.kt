/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandRealRhythmOutputInfoTest {
    private val dump = """
Output thread 0xb400007122e76590, name AudioOut_D, tid 4372, type 0 (MIXER):
  I/O handle: 13
  Standby: yes
  Output devices: 0x2 (AUDIO_DEVICE_OUT_SPEAKER)
  4 Tracks of which 0 are active
  0 Effect Chains
Output thread 0xb400007122cbc590, name AudioOut_15, tid 4388, type 0 (MIXER):
  I/O handle: 21
  Standby: no
  Output devices: 0x80 (AUDIO_DEVICE_OUT_BLUETOOTH_A2DP)
  13 Tracks of which 1 are active
  2 Effect Chains
    1 effects for session 16729
		- TYPE: e46b26a0-dddd-11db-8afd-0002a5d5c51b
    3 effects for session 0
		- TYPE: f46b26a0-dddd-11db-8afd-0002a5d5c51b
		- TYPE: e46b26a0-dddd-11db-8afd-0002a5d5c51b
Output thread 0xb400007122bd8590, name AudioOut_2D, tid 4409, type 1 (DIRECT):
  Standby: no
  Output devices: 0x4000 (AUDIO_DEVICE_OUT_USB_HEADSET)
  1 Tracks of which 1 are active
  0 Effect Chains
Input thread 0xb400007122bd0000, name AudioIn_3E, tid 4500, type 3 (RECORD):
  Standby: no
""".trimIndent()

    @Test fun `audio flinger summary lists active output threads and session 0 visualizer`() {
        assertEquals(
            "afThreads=[AudioOut_15 MIXER dev=AUDIO_DEVICE_OUT_BLUETOOTH_A2DP active=1 visualizer=true; " +
                "AudioOut_2D DIRECT dev=AUDIO_DEVICE_OUT_USB_HEADSET active=1 visualizer=false]",
            IslandRealRhythmOutputInfo.summarizeAudioFlingerDump(dump),
        )
    }

    @Test fun `visualizer on a player session is not counted as session 0`() {
        val onlyPlayerSession = dump.replace(
            "    3 effects for session 0\n\t\t- TYPE: f46b26a0-dddd-11db-8afd-0002a5d5c51b\n\t\t- TYPE: e46b26a0-dddd-11db-8afd-0002a5d5c51b",
            "",
        )
        assertEquals(
            "afThreads=[AudioOut_15 MIXER dev=AUDIO_DEVICE_OUT_BLUETOOTH_A2DP active=1 visualizer=false; " +
                "AudioOut_2D DIRECT dev=AUDIO_DEVICE_OUT_USB_HEADSET active=1 visualizer=false]",
            IslandRealRhythmOutputInfo.summarizeAudioFlingerDump(onlyPlayerSession),
        )
    }

    @Test fun `audio flinger summary reports when every output thread is in standby`() {
        assertEquals(
            "afThreads=[] (共 1 个输出线程，均待机)",
            IslandRealRhythmOutputInfo.summarizeAudioFlingerDump(dump.lines().take(6).joinToString("\n")),
        )
    }

    @Test fun `player description keeps readable fields and omits unknown ones`() {
        val players = listOf(
            IslandRealRhythmOutputInfo.Player(13, null, 96_000, 0b11, true, 0),
            IslandRealRhythmOutputInfo.Player(null, null, null, null, null, 0x10),
        )
        assertEquals(
            "players=[AAudio dev=? 96000Hz ch=2 spatial=true; player? dev=? mutedBy=0x10]",
            IslandRealRhythmOutputInfo.describePlayers(players),
        )
        assertEquals("players=[]", IslandRealRhythmOutputInfo.describePlayers(emptyList()))
    }

    @Test fun `non media usage is shown so unknown usage players are not hidden`() {
        assertEquals(
            "players=[MediaPlayer usage=0 dev=? 48000Hz spatial=true]",
            IslandRealRhythmOutputInfo.describePlayers(
                listOf(IslandRealRhythmOutputInfo.Player(2, null, 48_000, null, true, null, usage = 0)),
            ),
        )
    }
}
