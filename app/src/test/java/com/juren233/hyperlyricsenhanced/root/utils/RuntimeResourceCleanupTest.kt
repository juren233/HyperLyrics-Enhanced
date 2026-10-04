package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeResourceCleanupTest {
    @Test
    fun `unsubscribe and unregister failures do not skip destroy or sink teardown`() {
        val calls = mutableListOf<String>()
        val unsubscribeError = IllegalStateException("unsubscribe")
        val unregisterError = LinkageError("unregister")
        val cleanup = RuntimeResourceCleanup()
        cleanup.attempt("subscription") { calls += "unsubscribe"; throw unsubscribeError }
        cleanup.attempt("registration") { calls += "unregister"; throw unregisterError }
        cleanup.attempt("subscriber") { calls += "destroy" }
        cleanup.attempt("sink") { calls += "sink" }

        assertEquals(listOf("unsubscribe", "unregister", "destroy", "sink"), calls)
        val failure = checkNotNull(cleanup.failureOrNull())
        assertSame(unsubscribeError, failure.cause?.cause)
        assertSame(unregisterError, failure.suppressed.single().cause)
        assertTrue(failure.cause?.message.orEmpty().contains("subscription"))
    }

    @Test
    fun `successful cleanup does not prevent another startup attempt`() {
        val cleanup = RuntimeResourceCleanup()
        cleanup.attempt("observer") {}
        cleanup.attempt("configuration callbacks") {}
        assertNull(cleanup.failureOrNull())
        cleanup.throwIfFailed()
    }

    @Test
    fun `previous cleanup uncertainty remains visible on an otherwise empty second stop`() {
        val initial = RuntimeResourceCleanup()
        initial.attempt("observer") { error("unregister failed") }
        val previousFailure = checkNotNull(initial.failureOrNull())
        val repeated = RuntimeResourceCleanup()
        repeated.attempt("previous cleanup") { throw previousFailure }
        var sinkCleared = false
        repeated.attempt("sink") { sinkCleared = true }

        assertTrue(sinkCleared)
        assertSame(previousFailure, checkNotNull(repeated.failureOrNull()).cause?.cause)
    }

    @Test
    fun `failure snapshots do not grow when later cleanup actions fail`() {
        val cleanup = RuntimeResourceCleanup()
        cleanup.attempt("first") { error("first") }
        val snapshot = checkNotNull(cleanup.failureOrNull())
        cleanup.attempt("second") { error("second") }
        assertTrue(snapshot.suppressed.isEmpty())
        assertEquals(1, checkNotNull(cleanup.failureOrNull()).suppressed.size)
    }
}
