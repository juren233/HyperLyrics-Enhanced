package com.juren233.hyperlyricsenhanced.root.reload

import android.app.Activity
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.relay.GenerationRelayBinder
import com.juren233.hyperlyricsenhanced.root.source.AppleDirectBridgeHandover
import com.juren233.hyperlyricsenhanced.root.utils.LatestViewPost
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class GenerationRelayTest {
    private class RecordingBinder : Binder() {
        val codes = mutableListOf<Int>()
        val values = mutableListOf<Int>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            codes += code
            values += data.readInt()
            return true
        }
    }

    private fun IBinder.send(code: Int, value: Int): Boolean {
        val data = Parcel.obtain()
        try {
            data.writeInt(value)
            return transact(code, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    @Test fun `relay forwards to the attached generation and drops while detached`() {
        val relay = GenerationRelayBinder()
        assertTrue(GenerationRelay.isRelay(relay))
        val first = RecordingBinder()
        val second = RecordingBinder()

        assertFalse(relay.send(7, 1))
        GenerationRelay.attach(relay, first)
        assertTrue(relay.send(7, 2))
        GenerationRelay.attach(relay, null)
        assertFalse(relay.send(7, 3))
        GenerationRelay.attach(relay, second)
        assertTrue(relay.send(8, 4))

        assertEquals(listOf(7), first.codes)
        assertEquals(listOf(2), first.values)
        assertEquals(listOf(8), second.codes)
        assertEquals(listOf(4), second.values)
    }

    @Test fun `a held relay replays the latest call of each held code in arrival order`() {
        val relay = GenerationRelayBinder(intArrayOf(9, 7))
        val retiring = RecordingBinder()
        val next = RecordingBinder()
        GenerationRelay.attach(relay, retiring)
        assertTrue(GenerationRelay.hold(relay))

        assertTrue(relay.send(7, 1))
        assertFalse(relay.send(8, 2))
        assertTrue(relay.send(9, 3))
        assertTrue(relay.send(7, 4))
        assertEquals(2, GenerationRelay.attach(relay, next))
        assertTrue(relay.send(8, 5))

        assertEquals(emptyList<Int>(), retiring.codes)
        assertEquals(listOf(9, 7, 8), next.codes)
        assertEquals(listOf(3, 4, 5), next.values)
    }

    @Test fun `detaching a held relay discards what it held`() {
        val relay = GenerationRelayBinder(intArrayOf(7))
        GenerationRelay.hold(relay)
        assertTrue(relay.send(7, 1))
        GenerationRelay.attach(relay, null)
        assertFalse(relay.send(7, 2))

        val next = RecordingBinder()
        assertEquals(0, GenerationRelay.attach(relay, next))
        assertEquals(emptyList<Int>(), next.codes)
    }

    @Test fun `a replayed call that detaches the relay leaves it detached`() {
        val relay = GenerationRelayBinder(intArrayOf(7, 8))
        val closing = object : Binder() {
            val codes = mutableListOf<Int>()
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                codes += code
                GenerationRelay.attach(relay, null)
                return true
            }
        }
        GenerationRelay.hold(relay)
        relay.send(7, 1)
        relay.send(8, 2)

        assertEquals(1, GenerationRelay.attach(relay, closing))
        assertEquals(listOf(7), closing.codes)
        assertFalse(relay.send(8, 3))
    }

    @Test fun `a relay from a version that cannot hold is only detached`() {
        val older = object : Binder(), java.util.function.Consumer<IBinder?> {
            val targets = mutableListOf<IBinder?>()
            override fun accept(t: IBinder?) { targets += t }
        }
        val target = Binder()
        assertFalse(GenerationRelay.hold(older))
        assertEquals(0, GenerationRelay.attach(older, target))
        assertEquals(listOf<IBinder?>(null, target), older.targets)
    }

    @Test fun `handover passes the relay and last Apple state to the next generation once`() {
        val relay = GenerationRelayBinder()
        val receiver = Binder()
        val song = byteArrayOf(1, 2, 3)
        val envelope = hashMapOf<String, Any?>("relay" to relay)
        envelope["translationReceiver"] = receiver
        envelope["songPayload"] = song
        envelope["playing"] = true
        envelope["position"] = 42L
        envelope["hasText"] = true
        envelope["text"] = null

        AppleDirectBridgeHandover.receive(envelope)
        assertSame(relay, AppleDirectBridgeHandover.relay())
        val state = AppleDirectBridgeHandover.takeIncoming()!!
        assertSame(receiver, state.translationReceiver)
        assertArrayEquals(song, state.songPayload)
        assertEquals(true, state.playing)
        assertEquals(42L, state.position)
        assertTrue(state.hasText)
        assertNull(state.text)
        assertNull(AppleDirectBridgeHandover.takeIncoming())
    }

    @Test fun `a detached view keeps only the latest retry and release withdraws it`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ran = mutableListOf<String>()
        val posts = LatestViewPost()

        val replaced = View(activity)
        repeat(34) { index -> posts.post(replaced) { ran += "retry$index" } }
        val withdrawn = View(activity)
        posts.post(withdrawn) { ran += "withdrawn" }
        posts.cancelAll()
        posts.post(replaced) { ran += "latest" }

        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(replaced)
        root.addView(withdrawn)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("latest"), ran)
    }
}
