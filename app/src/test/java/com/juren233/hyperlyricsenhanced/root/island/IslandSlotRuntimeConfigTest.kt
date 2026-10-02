package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.After
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.IslandFontWeightMode
import com.juren233.hyperlyricsenhanced.lyric.view.line.resolvePlainTextOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandSlotRuntimeConfigTest {
    @Test
    fun `font weight live selection invalidates style and restores the stored custom weight`() {
        val prefs = preferences(mapOf(RootConstants.KEY_HOOK_FONT_WEIGHT to 750))
        val custom = IslandSlotRuntimeConfig.from(prefs)
        assertEquals(IslandFontWeightMode.CUSTOM, custom.fontWeightMode)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_FONT_WEIGHT_MODE, IslandFontWeightMode.SYSTEM)
        val system = IslandSlotRuntimeConfig.from(prefs)
        assertEquals(IslandFontWeightMode.SYSTEM, system.fontWeightMode)
        assertTrue(custom.styleSignature != system.styleSignature)
        assertEquals(750, system.fontWeight)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_FONT_WEIGHT_MODE, IslandFontWeightMode.CUSTOM)
        assertEquals(custom.styleSignature, IslandSlotRuntimeConfig.from(prefs).styleSignature)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_FONT_WEIGHT, 820)
        val changed = IslandSlotRuntimeConfig.from(prefs)
        assertEquals(820, changed.fontWeight)
        assertTrue(custom.styleSignature != changed.styleSignature)
    }

    @Test
    fun `next lyric line follows live overrides and invalidates presentation`() {
        val prefs = preferences(mapOf(
            RootConstants.KEY_HOOK_LYRIC_MODE to RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND,
        ))
        val before = IslandSlotRuntimeConfig.from(prefs)
        assertFalse(before.nextLyricLine)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_NEXT_LYRIC_LINE, true)
        val enabled = IslandSlotRuntimeConfig.from(prefs)
        assertTrue(enabled.nextLyricLine)
        assertTrue(before.styleSignature != enabled.styleSignature)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_NEXT_LYRIC_LINE, false)
        assertEquals(before, IslandSlotRuntimeConfig.from(prefs))
    }

    @Test
    fun `status bar color follows live overrides and invalidates the style`() {
        val prefs = preferences(emptyMap())
        val before = IslandSlotRuntimeConfig.from(prefs)
        assertFalse(before.statusBarTextColorEnabled)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_STATUS_BAR_TEXT_COLOR, true)
        val enabled = IslandSlotRuntimeConfig.from(prefs)
        assertTrue(enabled.statusBarTextColorEnabled)
        assertTrue(before.styleSignature != enabled.styleSignature)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_STATUS_BAR_TEXT_COLOR, false)
        assertEquals(before.styleSignature, IslandSlotRuntimeConfig.from(prefs).styleSignature)
    }

    @Test
    fun `status bar color restores persisted selection`() {
        assertTrue(IslandSlotRuntimeConfig.from(preferences(mapOf(
            RootConstants.KEY_HOOK_STATUS_BAR_TEXT_COLOR to true,
        ))).statusBarTextColorEnabled)
    }

    @After
    fun resetRuntimeOverrides() = IslandRuntimePreferenceOverrides.clear()

    @Test
    fun `short lyric song info defaults on and live toggle invalidates presentation`() {
        val prefs = preferences(mapOf(
            RootConstants.KEY_HOOK_LYRIC_MODE to RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND,
        ))
        val before = IslandSlotRuntimeConfig.from(prefs)
        assertTrue(before.shortLyricSongInfo)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_SHORT_LYRIC_SONG_INFO, false)
        val disabled = IslandSlotRuntimeConfig.from(prefs)
        assertFalse(disabled.shortLyricSongInfo)
        assertTrue(before.styleSignature != disabled.styleSignature)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_SHORT_LYRIC_SONG_INFO, true)
        assertEquals(before, IslandSlotRuntimeConfig.from(prefs))
    }

    @Test
    fun `short lyric song info restores persisted choice`() {
        assertFalse(IslandSlotRuntimeConfig.from(preferences(mapOf(
            RootConstants.KEY_HOOK_ISLAND_SHORT_LYRIC_SONG_INFO to false,
        ))).shortLyricSongInfo)
    }

    @Test
    fun `automatic limit never reads manual widths including zero and tiny values`() {
        val prefs = widthPreferences(left = 0, right = 1, rejectManualReads = true)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT, true)
        val config = IslandSlotRuntimeConfig.from(prefs)
        assertTrue(config.dynamicLimitEnabled)
        assertEquals(540, config.contentWidthPx(1080, 3f, isLeft = true))
        assertEquals(540, config.contentWidthPx(1080, 3f, isLeft = false))
    }

    @Test
    fun `turning automatic limit off restores saved asymmetric manual widths`() {
        val prefs = widthPreferences(left = 45, right = 120)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT, true)
        val automatic = IslandSlotRuntimeConfig.from(prefs)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT, false)
        val manual = IslandSlotRuntimeConfig.from(prefs)
        assertFalse(manual.dynamicLimitEnabled)
        assertEquals(135, manual.contentWidthPx(1080, 3f, isLeft = true))
        assertEquals(360, manual.contentWidthPx(1080, 3f, isLeft = false))
        assertTrue(automatic.styleSignature != manual.styleSignature)
    }

    @Test
    fun `automatic mode leaves saved zero width untouched for later manual mode`() {
        val prefs = widthPreferences(left = 0, right = 100)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT, true)
        val automatic = IslandSlotRuntimeConfig.from(prefs)
        assertEquals(540, automatic.contentWidthPx(1080, 3f, true))
        assertTrue(automatic.shouldInjectLeft)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT, false)
        val manual = IslandSlotRuntimeConfig.from(prefs)
        assertEquals(null, manual.contentWidthPx(1080, 3f, true))
        assertFalse(manual.shouldInjectLeft)
    }

    @Test
    fun `duet right line anchors lyric slot to end under default position`() {
        val config = IslandSlotRuntimeConfig.from(preferences(
            mapOf(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH to true)
        ))
        assertTrue(config.dynamicWidthEnabled)
        assertEquals(5, config.leftMode)
        assertEquals(7, config.rightMode)
        val base = config.wrapperHorizontalGravity(false)
        assertEquals(android.view.Gravity.START, base)
        assertEquals(base, config.wrapperHorizontalGravity(false, null))
        assertEquals(base, config.wrapperHorizontalGravity(false, false))
        assertEquals(android.view.Gravity.END, config.wrapperHorizontalGravity(false, true))
        assertEquals(base, config.wrapperHorizontalGravity(true, true))
    }

    @Test
    fun `explicit center and right preferences keep precedence over duet direction`() {
        val center = IslandSlotRuntimeConfig.from(preferences(mapOf(
            RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH to true,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_LYRIC_POSITION to
                RootConstants.ISLAND_LYRIC_POSITION_CENTER,
        )))
        assertEquals(
            android.view.Gravity.CENTER_HORIZONTAL,
            center.wrapperHorizontalGravity(false, true)
        )
        val right = IslandSlotRuntimeConfig.from(preferences(mapOf(
            RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH to true,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_LYRIC_POSITION to
                RootConstants.ISLAND_LYRIC_POSITION_RIGHT,
        )))
        assertEquals(android.view.Gravity.END, right.wrapperHorizontalGravity(false, true))
    }

    @Test
    fun `duet anchor requires dynamic width`() {
        val config = IslandSlotRuntimeConfig.from(preferences(
            mapOf(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH to false)
        ))
        assertFalse(config.dynamicWidthEnabled)
        assertEquals(
            config.wrapperHorizontalGravity(false),
            config.wrapperHorizontalGravity(false, true)
        )
    }

    @Test
    fun `both slot modes force lyrics but only full island ignores side positions`() {
        listOf(
            RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND,
            RootConstants.HOOK_LYRIC_MODE_SEPARATED,
        ).forEach { mode ->
            val config = IslandSlotRuntimeConfig.from(preferences(mapOf(
                RootConstants.KEY_HOOK_LYRIC_MODE to mode,
                RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT to 5,
                RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT to 6,
                RootConstants.KEY_HOOK_ISLAND_LEFT_LYRIC_POSITION to
                    RootConstants.ISLAND_LYRIC_POSITION_CENTER,
                RootConstants.KEY_HOOK_ISLAND_RIGHT_LYRIC_POSITION to
                    RootConstants.ISLAND_LYRIC_POSITION_RIGHT,
            )))
            assertEquals(7, config.leftMode)
            assertEquals(7, config.rightMode)
            assertTrue(config.usesBothLyricSlots)
            assertEquals(mode == RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND, config.isFullIslandMode)
            assertEquals(mode == RootConstants.HOOK_LYRIC_MODE_SEPARATED, config.isSeparatedMode)
            assertEquals(config.isFullIslandMode, config.usesSpaceGateView)
            assertEquals(config.isSeparatedMode, config.centerLyric(true))
            assertFalse(config.centerLyric(false))
            assertFalse(config.rightAlignLyric(true))
            assertEquals(config.isSeparatedMode, config.rightAlignLyric(false))
            assertEquals(
                if (config.isSeparatedMode) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START,
                config.wrapperHorizontalGravity(true)
            )
            assertEquals(
                if (config.isSeparatedMode) android.view.Gravity.END else android.view.Gravity.START,
                config.wrapperHorizontalGravity(false)
            )
            assertEquals(
                config.wrapperHorizontalGravity(false),
                config.wrapperHorizontalGravity(false, true)
            )
        }
    }

    @Test
    fun `separated live positions align each fitting row independently of scrolling sibling`() {
        val prefs = preferences(mapOf(
            RootConstants.KEY_HOOK_LYRIC_MODE to RootConstants.HOOK_LYRIC_MODE_SEPARATED,
        ))
        for (isLeft in listOf(true, false)) {
            val key = if (isLeft) RootConstants.KEY_HOOK_ISLAND_LEFT_LYRIC_POSITION
                else RootConstants.KEY_HOOK_ISLAND_RIGHT_LYRIC_POSITION
            for ((position, shortOffset) in listOf(
                RootConstants.ISLAND_LYRIC_POSITION_CENTER to 30f,
                RootConstants.ISLAND_LYRIC_POSITION_RIGHT to 60f,
                RootConstants.ISLAND_LYRIC_POSITION_DEFAULT to 0f,
            )) {
                val before = IslandSlotRuntimeConfig.from(prefs)
                IslandRuntimePreferenceOverrides.put(key, position)
                val config = IslandSlotRuntimeConfig.from(prefs)
                assertTrue(before.styleSignature != config.styleSignature)
                assertFalse(config.centerLyric(!isLeft))
                assertFalse(config.rightAlignLyric(!isLeft))
                // Main and secondary measure independently: either can overflow while
                // the other still has room to use the selected side position.
                for (widths in listOf(140f to 40f, 40f to 140f, 40f to 40f)) {
                    val offsets = listOf(widths.first, widths.second).map { width ->
                        resolvePlainTextOffset(
                            textWidth = width,
                            viewWidth = 100f,
                            scrollOffset = if (width > 100f) -12f else 0f,
                            isAlignedRight = false,
                            centerIfPossible = config.centerLyric(isLeft),
                            alignRight = config.rightAlignLyric(isLeft),
                        )
                    }
                    assertEquals(if (widths.first > 100f) -12f else shortOffset, offsets[0])
                    assertEquals(if (widths.second > 100f) -12f else shortOffset, offsets[1])
                }
            }
        }
    }

    @Test
    fun `single side mode preserves configured slot content`() {
        val config = IslandSlotRuntimeConfig.from(preferences(mapOf(
            RootConstants.KEY_HOOK_LYRIC_MODE to RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE,
            RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT to 5,
            RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT to 6,
        )))

        assertTrue(config.isSingleSideMode)
        assertFalse(config.usesBothLyricSlots)
        assertFalse(config.usesSpaceGateView)
        assertEquals(5, config.leftMode)
        assertEquals(6, config.rightMode)
    }

    private fun preferences(values: Map<String, Any>): SharedPreferences {
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values
                "contains" -> values.containsKey(args!![0])
                "getInt" -> (values[args!![0] as String] as? Int) ?: args[1]
                "getBoolean" -> (values[args!![0] as String] as? Boolean) ?: args[1]
                "getString", "getStringSet", "getFloat", "getLong" -> args!![1]
                "edit" -> error("Gravity mode must not modify stored preferences")
                else -> null
            }
        } as SharedPreferences
    }

    private fun widthPreferences(left: Int, right: Int, rejectManualReads: Boolean = false): SharedPreferences {
        val values = mapOf(
            RootConstants.KEY_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH to left,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH to right,
        )
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values
                "contains" -> values.containsKey(args!![0])
                "getInt" -> {
                    val key = args!![0] as String
                    check(!rejectManualReads || key !in values) { "Automatic mode read manual width" }
                    values[key] ?: args[1]
                }
                "getString", "getStringSet", "getBoolean", "getFloat", "getLong" -> args!![1]
                "edit" -> error("Width mode must not modify stored preferences")
                else -> null
            }
        } as SharedPreferences
    }


    @Test
    fun `legacy enabled duration migrates to full island preview`() {
        assertEquals(
            RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL,
            IslandSlotRuntimeConfig.resolveNextSongPreviewStyle(
                hasStoredStyle = false,
                storedStyle = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_NONE,
                legacyDurationSeconds = 4
            )
        )
    }

    @Test
    fun `legacy disabled duration migrates to hidden preview`() {
        assertEquals(
            RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_NONE,
            IslandSlotRuntimeConfig.resolveNextSongPreviewStyle(
                hasStoredStyle = false,
                storedStyle = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL,
                legacyDurationSeconds = 0
            )
        )
    }

    @Test
    fun `stored preview style takes precedence over legacy duration`() {
        assertEquals(
            RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF,
            IslandSlotRuntimeConfig.resolveNextSongPreviewStyle(
                hasStoredStyle = true,
                storedStyle = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF,
                legacyDurationSeconds = 0
            )
        )
    }

    @Test
    fun `other side position chooses the side opposite a single lyric slot`() {
        assertFalse(
            IslandSlotRuntimeConfig.resolveHalfPreviewTargetIsLeft(
                position = RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_OTHER_SIDE,
                leftMode = 7,
                rightMode = 5
            )
        )
        assertTrue(
            IslandSlotRuntimeConfig.resolveHalfPreviewTargetIsLeft(
                position = RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_OTHER_SIDE,
                leftMode = 5,
                rightMode = 7
            )
        )
    }

    @Test
    fun `explicit half preview position overrides lyric slots`() {
        assertTrue(
            IslandSlotRuntimeConfig.resolveHalfPreviewTargetIsLeft(
                position = RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_LEFT,
                leftMode = 7,
                rightMode = 5
            )
        )
        assertFalse(
            IslandSlotRuntimeConfig.resolveHalfPreviewTargetIsLeft(
                position = RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_RIGHT,
                leftMode = 5,
                rightMode = 7
            )
        )
    }

    @Test
    fun `half preview always uses a fixed five second window`() {
        assertEquals(
            5_000L,
            IslandSlotRuntimeConfig.resolveNextSongPreviewDurationMs(
                style = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF,
                fullDurationSeconds = 1
            )
        )
        assertTrue(
            IslandSlotRuntimeConfig.resolveShouldForceNextSongPreview(
                style = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF,
                fullForceEnabled = false
            )
        )
    }

    @Test
    fun `full preview keeps its configured duration and force switch`() {
        assertEquals(
            3_000L,
            IslandSlotRuntimeConfig.resolveNextSongPreviewDurationMs(
                style = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL,
                fullDurationSeconds = 3
            )
        )
        assertFalse(
            IslandSlotRuntimeConfig.resolveShouldForceNextSongPreview(
                style = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL,
                fullForceEnabled = false
            )
        )
    }

    @Test
    fun `duet fixed length defaults off and toggling changes signature`() {
        val disabled = IslandSlotRuntimeConfig.from(duetPreferences(duetFixedLength = false))
        assertFalse(disabled.duetFixedLengthEnabled)
        val enabled = IslandSlotRuntimeConfig.from(duetPreferences(duetFixedLength = true))
        assertTrue(enabled.duetFixedLengthEnabled)
        assertTrue(enabled.styleSignature != disabled.styleSignature)
    }

    @Test
    fun `audio rhythm trailing gap applies only to the right slot`() {
        val enabled = IslandSlotRuntimeConfig.from(
            rhythmPreferences(showRhythm = true, rightPaddingRight = 3)
        )
        assertEquals(0, enabled.paddingRightDp(IslandProbeUtils.LEFT_PARENT_NAME))
        assertEquals(
            3 + IslandSlotRuntimeConfig.RHYTHM_TRAILING_GAP_DP,
            enabled.paddingRightDp(IslandProbeUtils.RIGHT_PARENT_NAME)
        )

        val disabled = IslandSlotRuntimeConfig.from(
            rhythmPreferences(showRhythm = false, rightPaddingRight = 3)
        )
        assertEquals(0, disabled.paddingRightDp(IslandProbeUtils.LEFT_PARENT_NAME))
        assertEquals(3, disabled.paddingRightDp(IslandProbeUtils.RIGHT_PARENT_NAME))
    }

    private fun rhythmPreferences(showRhythm: Boolean, rightPaddingRight: Int): SharedPreferences {
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when {
                method.name == "getBoolean" && args!![0] == RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON -> showRhythm
                method.name == "getInt" && args!![0] == RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_RIGHT -> rightPaddingRight
                else -> when (method.name) {
                    "getAll" -> emptyMap<String, Any>()
                    "contains" -> false
                    "getInt", "getFloat", "getString", "getStringSet", "getLong", "getBoolean" -> args!![1]
                    "edit" -> error("Config read must not modify stored preferences")
                    else -> null
                }
            }
        } as SharedPreferences
    }

    private fun duetPreferences(duetFixedLength: Boolean): SharedPreferences {
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" ->
                    if (args!![0] == RootConstants.KEY_HOOK_ISLAND_DUET_FIXED_LENGTH) {
                        duetFixedLength
                    } else {
                        args[1]
                    }
                "getAll" -> emptyMap<String, Any>()
                "contains" -> false
                "getInt", "getFloat", "getString", "getStringSet", "getLong" -> args!![1]
                "edit" -> error("Config read must not modify stored preferences")
                else -> null
            }
        } as SharedPreferences
    }
}
