package com.juren233.hyperlyricsenhanced.root.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class OnlineTranslationRequestTest {
    @Test fun `same attempt is not scheduled twice and cancellation advances generation`() {
        val owner = OnlineTranslationRequest<String>()
        val first = owner.begin("song")!!
        assertNull(owner.begin("song"))
        owner.cancel(clearAttempt = true)
        assertTrue(owner.snapshot().generation > first)
        assertTrue(owner.begin("song")!! > first)
    }

    @Test fun `stale delivery is rejected and current delivery is accepted`() {
        val owner = OnlineTranslationRequest<String>()
        val first = owner.begin("a")!!
        owner.cancel(clearAttempt = true)
        val second = owner.begin("b")!!
        var applied = 0
        assertFalse(owner.deliver(first) { applied++ })
        assertTrue(owner.deliver(second) { applied++ })
        assertEquals(1, applied)
    }

    @Test fun `deferred result is only consumed at boundary`() {
        val owner = OnlineTranslationRequest<String>()
        val generation = owner.begin("a")!!
        owner.defer(generation, "result")
        assertNull(owner.takePending { false })
        assertEquals("result", owner.takePending { true })
        assertNull(owner.takePending { true })
    }
    @Test fun `launch registers and runs current worker only`() = runBlocking {
        val owner = OnlineTranslationRequest<String>()
        val token = owner.begin("song")!!
        var ran = false
        owner.launch(this, token) { ran = true }
        delay(20)
        assertTrue(ran)
        assertFalse(owner.snapshot().running)
    }

    @Test fun `cancel rejects a queued delivery after worker completion`() = runBlocking {
        val owner = OnlineTranslationRequest<String>()
        val token = owner.begin("song")!!
        var applied = false
        owner.launch(this, token) { delay(1) }
        delay(20)
        owner.cancel(clearAttempt = false)
        assertFalse(owner.deliver(token) { applied = true })
        assertFalse(applied)
    }

}
