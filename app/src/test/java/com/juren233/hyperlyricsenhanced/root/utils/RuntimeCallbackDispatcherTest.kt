package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.*
import org.junit.Test

class RuntimeCallbackDispatcherTest {
    @Test fun `old Binder callback cannot touch replacement after stop and restart`() {
        val queue = mutableListOf<Runnable>()
        var generation = 1
        var currentSink = "old"
        val dispatcher = RuntimeCallbackDispatcher({ false }, { queue += it })
        val capturedGeneration = generation
        var ownershipChecks = 0
        dispatcher.dispatch(
            isCurrent = { ownershipChecks++; generation == capturedGeneration },
            action = { currentSink = "cleared_by_old_callback" },
        )
        // The Binder caller must not evaluate the guard and then race the owner's stop/start.
        assertEquals(0, ownershipChecks)
        generation++
        currentSink = "replacement"
        queue.single().run()
        assertEquals("replacement", currentSink)
        assertEquals(1, ownershipChecks)
    }

    @Test fun `old connection peer cannot mutate new subscriber even with current generation`() {
        val queue = mutableListOf<Runnable>()
        val oldPeer = Any()
        var currentPeer = oldPeer
        var mutations = 0
        val dispatcher = RuntimeCallbackDispatcher({ false }, { queue += it })
        dispatcher.dispatch({ currentPeer === oldPeer }) { mutations++ }
        currentPeer = Any()
        queue.single().run()
        assertEquals(0, mutations)
    }

    @Test fun `current callback runs wholly inside owner dispatch`() {
        val queue = mutableListOf<Runnable>()
        var onOwner = false
        val order = mutableListOf<String>()
        val dispatcher = RuntimeCallbackDispatcher({ onOwner }, { queue += it })
        dispatcher.dispatch({ assertTrue(onOwner); true }) {
            assertTrue(onOwner)
            order += "source_state"
            order += "sink"
        }
        assertTrue(order.isEmpty())
        onOwner = true
        queue.single().run()
        assertEquals(listOf("source_state", "sink"), order)
    }

    @Test fun `main thread callbacks retain synchronous semantics and reject retired owner`() {
        val queue = mutableListOf<Runnable>()
        var mutations = 0
        val dispatcher = RuntimeCallbackDispatcher({ true }, { queue += it })
        dispatcher.dispatch({ true }) { mutations++ }
        dispatcher.dispatch({ false }) { mutations++ }
        assertEquals(1, mutations)
        assertTrue(queue.isEmpty())
    }
}
