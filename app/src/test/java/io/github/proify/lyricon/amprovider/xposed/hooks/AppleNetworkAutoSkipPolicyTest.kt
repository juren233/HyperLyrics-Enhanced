/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 策略按类名后缀与反射字段匹配，这里用同名后缀的本地异常类模拟 DEX 中的目标类型。 */
private class MediaAssetNotFoundException : IOException()

private class HTTPErrorException : IOException()

private class FakeInvalidResponseCodeException(val responseCode: Int) : IOException()

private class FieldlessInvalidResponseCodeException : IOException()

class AppleNetworkAutoSkipPolicyTest {
    private val networkType = AppleNetworkAutoSkipPolicy.NETWORK_FAILURE_TYPE

    @Test
    fun `non network failure types hand back to the native skip decision`() {
        val exception = SocketTimeoutException("timed out")
        assertFalse(AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(exception, 1))
        assertFalse(AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(exception, 8))
        assertFalse(AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(exception, null))
    }

    @Test
    fun `null exception hands back to the native skip decision`() {
        assertFalse(AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(null, networkType))
    }

    @Test
    fun `direct transient io exceptions count as weak network`() {
        val exceptions = listOf(
            EOFException(),
            InterruptedIOException(),
            ConnectException("refused"),
            NoRouteToHostException(),
            SocketException(),
            SocketTimeoutException("timed out"),
            UnknownHostException("dns"),
        )
        exceptions.forEach { exception ->
            assertTrue(
                "expected transient: ${exception.javaClass.name}",
                AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(exception, networkType),
            )
        }
    }

    @Test
    fun `transient exception anywhere in the cause chain counts as weak network`() {
        val nested = chainOf(IOException(), ConnectException("refused"), EOFException())
        assertTrue(AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(nested, networkType))
    }

    @Test
    fun `permanent failure suffix vetoes the whole chain`() {
        val permanentOnTop = chainOf(MediaAssetNotFoundException(), SocketTimeoutException())
        val permanentBelow = chainOf(IOException(), MediaAssetNotFoundException(), EOFException())
        val httpError = chainOf(HTTPErrorException(), SocketTimeoutException())
        listOf(permanentOnTop, permanentBelow, httpError).forEach { exception ->
            assertFalse(
                "expected veto: ${exception.javaClass.name}",
                AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(exception, networkType),
            )
        }
    }

    @Test
    fun `transient http response codes count as weak network`() {
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { code ->
            val exception = FakeInvalidResponseCodeException(code)
            assertTrue(
                "expected transient response code: $code",
                AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(exception, networkType),
            )
        }
    }

    @Test
    fun `other http response codes hand back to the native skip decision`() {
        listOf(301, 403, 404, 410).forEach { code ->
            val exception = FakeInvalidResponseCodeException(code)
            assertFalse(
                "expected native skip for response code: $code",
                AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(exception, networkType),
            )
        }
    }

    @Test
    fun `missing responseCode field hands back to the native skip decision`() {
        val exception = FieldlessInvalidResponseCodeException()
        assertFalse(AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(exception, networkType))
    }

    @Test
    fun `cause chains within the depth limit still resolve the deepest transient`() {
        val atLimit = chainOf(
            *Array(12) { index ->
                if (index == 11) SocketTimeoutException() else IOException()
            },
        )
        assertTrue(AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(atLimit, networkType))
    }

    @Test
    fun `cause chains beyond the depth limit hand back to the native skip decision`() {
        val beyondLimit = chainOf(
            *Array(13) { index ->
                if (index == 12) SocketTimeoutException() else IOException()
            },
        )
        assertFalse(AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(beyondLimit, networkType))
    }

    @Test
    fun `unrelated exception types hand back to the native skip decision`() {
        assertFalse(
            AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(
                IllegalStateException("unexpected"),
                networkType,
            ),
        )
    }

    @Test
    fun `unrelated wrapper exceptions in the chain do not veto deeper transients`() {
        val wrapped = chainOf(IllegalStateException(), SocketTimeoutException())
        assertTrue(AppleNetworkAutoSkipPolicy.isTransientNetworkFailure(wrapped, networkType))
    }

    private fun chainOf(vararg links: Throwable): Throwable =
        if (links.size == 1) {
            links[0]
        } else {
            links.first().apply { initCause(chainOf(*links.drop(1).toTypedArray())) }
        }
}
