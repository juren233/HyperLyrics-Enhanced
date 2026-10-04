/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

import android.os.IBinder
import android.os.IInterface
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Fake interfaces exercise recipient ownership without an Android runtime or Binder driver. */
class AppleDirectBinderConnectionTest {
    private class Endpoint : IInterface {
        val recipients = mutableListOf<IBinder.DeathRecipient>()
        val unlinked = mutableListOf<IBinder.DeathRecipient>()
        var failLink = false
        private val binder = Proxy.newProxyInstance(
            IBinder::class.java.classLoader, arrayOf(IBinder::class.java),
        ) { _, method, args ->
            when (method.name) {
                "linkToDeath" -> {
                    if (failLink) error("dead binder")
                    recipients += args!![0] as IBinder.DeathRecipient
                    null
                }
                "unlinkToDeath" -> { unlinked += args!![0] as IBinder.DeathRecipient; true }
                "isBinderAlive", "pingBinder" -> true
                else -> error("Unexpected Binder call: ${method.name}")
            }
        } as IBinder
        override fun asBinder(): IBinder = binder
    }

    @Test fun `replacement unlinks old recipient and its queued death cannot clear replacement`() {
        var deaths = 0
        val owner = AppleDirectBinderConnection<Endpoint>({ deaths++ }, { throw it })
        val first = Endpoint()
        val second = Endpoint()
        val old = owner.replace(first)!!
        val current = owner.replace(second)!!
        assertEquals(listOf(first.recipients.single()), first.unlinked)
        first.recipients.single().binderDied()
        assertSame(current, owner.current)
        assertFalse(owner.clear(old)) // A concurrent old send failure is equally harmless.
        assertEquals(0, deaths)
        second.recipients.single().binderDied()
        assertNull(owner.current)
        assertEquals(1, deaths)
        assertEquals(second.recipients, second.unlinked)
    }

    @Test fun `same Binder re-registration has a new ownership token`() {
        val owner = AppleDirectBinderConnection<Endpoint>({}, { throw it })
        val endpoint = Endpoint()
        val old = owner.replace(endpoint)!!
        val current = owner.replace(endpoint)!!
        assertFalse(owner.clear(old))
        endpoint.recipients.first().binderDied()
        assertSame(current, owner.current)
        assertTrue(owner.clear(current))
        assertFalse(owner.clear(current))
        assertEquals(endpoint.recipients, endpoint.unlinked)
    }

    @Test fun `stop and explicit null registration unlink the owned recipient exactly once`() {
        val owner = AppleDirectBinderConnection<Endpoint>({}, { throw it })
        val endpoint = Endpoint()
        owner.replace(endpoint)
        owner.replace(null)
        owner.clear()
        assertNull(owner.current)
        assertEquals(endpoint.recipients, endpoint.unlinked)
        owner.replace(endpoint)
        owner.clear()
        owner.clear()
        assertEquals(endpoint.recipients, endpoint.unlinked)
    }

    @Test fun `failed death registration does not leave a half connected endpoint`() {
        var failures = 0
        val owner = AppleDirectBinderConnection<Endpoint>({}, { failures++ })
        val endpoint = Endpoint().apply { failLink = true }
        assertNull(owner.replace(endpoint))
        assertNull(owner.current)
        assertEquals(1, failures)
        endpoint.failLink = false
        assertNotNull(owner.replace(endpoint))
        assertSame(endpoint, owner.current?.target)
    }

    @Test fun `shutdown notification is once only with ownership revoked and late death inert`() {
        var deaths = 0
        var notifications = 0
        val owner = AppleDirectBinderConnection<Endpoint>({ deaths++ }, { throw it })
        val endpoint = Endpoint()
        val old = owner.replace(endpoint)!!
        val result = owner.clearWithFinalNotification { target ->
            assertSame(endpoint, target)
            assertNull(owner.current)
            assertFalse(owner.clear(old))
            endpoint.recipients.single().binderDied()
            notifications++
        }
        assertTrue(result!!.isSuccess)
        assertNull(owner.clearWithFinalNotification { notifications++ })
        owner.clear()
        endpoint.recipients.single().binderDied()
        assertEquals(1, notifications)
        assertEquals(0, deaths)
        assertEquals(endpoint.recipients, endpoint.unlinked)
    }

    @Test fun `failed shutdown notification still unlinks and cannot disturb a replacement`() {
        var deaths = 0
        val owner = AppleDirectBinderConnection<Endpoint>({ deaths++ }, { throw it })
        val endpoint = Endpoint()
        val replacement = Endpoint()
        owner.replace(endpoint)
        val result = owner.clearWithFinalNotification {
            owner.replace(replacement)
            error("remote already dead")
        }
        assertTrue(result!!.isFailure)
        assertEquals(endpoint.recipients, endpoint.unlinked)
        assertTrue(replacement.unlinked.isEmpty())
        assertSame(replacement, owner.current?.target)
        endpoint.recipients.single().binderDied()
        assertSame(replacement, owner.current?.target)
        assertEquals(0, deaths)
        owner.clear()
        assertEquals(replacement.recipients, replacement.unlinked)
    }

    @Test fun `equal but distinct ownership entries cannot release each other`() {
        data class Entry(val value: Int)
        val owner = AppleDirectConnectionOwner<Entry>()
        val old = Entry(1)
        val current = Entry(1)
        owner.replace(old)
        assertSame(old, owner.replace(current))
        assertFalse(owner.clearIfCurrent(old))
        assertSame(current, owner.current)
        assertTrue(owner.clearIfCurrent(current))
        assertFalse(owner.clearIfCurrent(current))
    }
}
