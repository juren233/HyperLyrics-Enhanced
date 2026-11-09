/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils

import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogExportSettingsTest {
    private val labels = LogExportSettingLabels(
        secretSet = "SET",
        secretEmpty = "EMPTY",
        defaultValue = "DEFAULT",
        hostOnlyFormat = "HOST(%1\$s)",
        customLengthFormat = "CUSTOM(%1\$d)",
        customValue = "CUSTOM",
    )

    @Test
    fun `plain settings are listed sorted by key with typed values`() {
        val lines = LogExportSettings.lines(
            linkedMapOf(
                "key_b" to 3,
                "key_a" to true,
                "key_d" to setOf("z", "a"),
                "key_c" to "line1\nline2",
                "key_e" to 1.5f,
            ),
            labels,
        )
        assertEquals(
            listOf(
                "key_a = true",
                "key_b = 3",
                "key_c = \"line1\\nline2\"",
                "key_d = [a, z]",
                "key_e = 1.5",
            ),
            lines,
        )
    }

    @Test
    fun `api key is never written and only reports whether it is set`() {
        val secret = "sk-very-secret-value"
        val set = LogExportSettings.lines(mapOf(RootConstants.KEY_HOOK_AI_TRANS_API_KEY to secret), labels)
        assertEquals(listOf("${RootConstants.KEY_HOOK_AI_TRANS_API_KEY} = SET"), set)
        assertFalse(set.single().contains(secret))
        assertEquals(
            listOf("${RootConstants.KEY_HOOK_AI_TRANS_API_KEY} = EMPTY"),
            LogExportSettings.lines(mapOf(RootConstants.KEY_HOOK_AI_TRANS_API_KEY to " "), labels),
        )
    }

    @Test
    fun `base url keeps only the host`() {
        val key = RootConstants.KEY_HOOK_AI_TRANS_BASE_URL
        assertEquals(
            listOf("$key = HOST(my.private.host)"),
            LogExportSettings.lines(mapOf(key to "https://user:pw@my.private.host:8443/v1/?token=abc"), labels),
        )
        assertEquals(listOf("$key = DEFAULT"), LogExportSettings.lines(mapOf(key to ""), labels))
        assertEquals(listOf("$key = CUSTOM"), LogExportSettings.lines(mapOf(key to "not a url"), labels))
    }

    @Test
    fun `prompt reports default or custom length only`() {
        val key = RootConstants.KEY_HOOK_AI_TRANS_PROMPT
        assertEquals(
            listOf("$key = DEFAULT"),
            LogExportSettings.lines(mapOf(key to RootConstants.DEFAULT_HOOK_AI_TRANS_PROMPT), labels),
        )
        assertEquals(listOf("$key = CUSTOM(5)"), LogExportSettings.lines(mapOf(key to "hello"), labels))
    }
}
