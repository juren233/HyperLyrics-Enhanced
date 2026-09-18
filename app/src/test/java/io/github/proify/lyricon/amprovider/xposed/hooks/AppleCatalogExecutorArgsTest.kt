/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package io.github.proify.lyricon.amprovider.xposed.hooks

import io.github.proify.lyricon.amprovider.xposed.AMP_HTTP_MODULE_MARKER_PARAM
import io.github.proify.lyricon.amprovider.xposed.AMP_HTTP_MODULE_MARKER_VALUE
import io.github.proify.lyricon.amprovider.xposed.CATALOG_REQUEST_TOKEN_PARAM
import io.github.proify.lyricon.amprovider.xposed.CatalogRequestLocalization
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppleCatalogExecutorArgsTest {
    private val us = CatalogRequestLocalization("us", "zh-Hans")

    private fun rewrite(
        argList: List<Any?>,
        queryArgIndex: Int,
        configuredStorefront: String? = null,
        localization: (String) -> CatalogRequestLocalization? = { if (it == "q") us else null },
    ) = AppleCatalogExecutorArgs.rewrite(argList, queryArgIndex, configuredStorefront, localization)

    @Test
    fun `module request gets storefront rewritten and token stripped`() {
        val query = linkedMapOf<Any?, Any?>(
            "ids" to "661608565",
            "l" to "zh-Hans",
            CATALOG_REQUEST_TOKEN_PARAM to "q",
        )
        val result = rewrite(args(query), queryArgIndex = 5)
        assertNotNull(result)
        assertEquals("q", result!!.token)
        assertEquals("us", result.storefront)
        assertArrayEquals(
            listOf(123L, "UA", "Bearer token", "us", "songs", query, null).toTypedArray(),
            result.args,
        )
        assertNull("token must never leave the device", query[CATALOG_REQUEST_TOKEN_PARAM])
        assertEquals(
            "amp-api network interceptor must let marked requests through",
            AMP_HTTP_MODULE_MARKER_VALUE,
            query[AMP_HTTP_MODULE_MARKER_PARAM],
        )
    }

    @Test
    fun `batch shape reads the query map at index four and leaves headers untouched`() {
        val query = linkedMapOf<Any?, Any?>(
            "ids[0]" to "661608565",
            CATALOG_REQUEST_TOKEN_PARAM to "q",
        )
        val headers = linkedMapOf<Any?, Any?>("Accept" to "application/json")
        val batchArgs = listOf<Any?>(
            123L, "UA", "Bearer token", "cn", query, headers, null,
        )
        val result = rewrite(batchArgs, queryArgIndex = 4)
        assertNotNull(result)
        assertEquals("us", result!!.storefront)
        assertEquals("us", result.args[3])
        assertEquals("headers map must not be treated as the query", headers, result.args[5])
        assertNull(query[CATALOG_REQUEST_TOKEN_PARAM])
        assertEquals(AMP_HTTP_MODULE_MARKER_VALUE, query[AMP_HTTP_MODULE_MARKER_PARAM])
    }

    @Test
    fun `unknown token only strips the token and keeps the storefront`() {
        val query = linkedMapOf<Any?, Any?>(
            "ids" to "661608565",
            CATALOG_REQUEST_TOKEN_PARAM to "stale",
        )
        val result = rewrite(args(query), queryArgIndex = 5)
        assertNotNull(result)
        assertEquals("stale", result!!.token)
        assertNull("localization missing, storefront must stay untouched", result.storefront)
        assertEquals("cn", result.args[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX])
        assertNull(query[CATALOG_REQUEST_TOKEN_PARAM])
        assertEquals(AMP_HTTP_MODULE_MARKER_VALUE, query[AMP_HTTP_MODULE_MARKER_PARAM])
    }

    @Test
    fun `native request is rewritten to the configured storefront`() {
        val query = linkedMapOf<Any?, Any?>("ids" to "661608565")
        val result = rewrite(args(query), queryArgIndex = 5, configuredStorefront = "us")
        assertNotNull(result)
        assertNull("native requests carry no token", result!!.token)
        assertEquals("us", result.storefront)
        assertEquals("us", result.args[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX])
        assertEquals("query map stays untouched", "661608565", query["ids"])
        assertEquals(
            "rewritten native requests are marked for the network interceptor",
            AMP_HTTP_MODULE_MARKER_VALUE,
            query[AMP_HTTP_MODULE_MARKER_PARAM],
        )
    }

    @Test
    fun `native request without configuration is left untouched`() {
        val query = linkedMapOf<Any?, Any?>("ids" to "661608565")
        assertNull(rewrite(args(query), queryArgIndex = 5, configuredStorefront = null))
        assertNull(rewrite(args(query), queryArgIndex = 5, configuredStorefront = ""))
        assertEquals("cn", args(query)[AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX])
        assertNull(
            "untouched native requests must stay unmarked",
            query[AMP_HTTP_MODULE_MARKER_PARAM],
        )
    }

    @Test
    fun `non map query or short argument lists are ignored`() {
        assertNull(rewrite(listOf(1L, "UA"), queryArgIndex = 5))
        assertNull(
            rewrite(
                listOf(1L, "UA", "Bearer", "cn", "songs", "not-a-map", null),
                queryArgIndex = 5,
                configuredStorefront = "us",
            ),
        )
        assertNull(rewrite(listOf<Any?>(null), queryArgIndex = 5))
        assertNull("query index must sit after the storefront", rewrite(args(null), queryArgIndex = 3))
    }

    private fun args(query: MutableMap<Any?, Any?>?): List<Any?> = listOf(
        123L,
        "UA",
        "Bearer token",
        "cn",
        "songs",
        query,
        null,
    )
}
