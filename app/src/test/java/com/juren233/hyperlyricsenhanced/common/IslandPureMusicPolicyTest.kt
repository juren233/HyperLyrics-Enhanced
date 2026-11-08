package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandPureMusicPolicyTest {
    private val keywords = IslandPureMusicPolicy.normalizedKeywordSet(null)

    @Test
    fun `song without lyric lines is pure music`() {
        assertTrue(IslandPureMusicPolicy.isPureMusic(emptyList(), keywords))
        assertTrue(IslandPureMusicPolicy.isPureMusic(listOf(null, "  "), keywords))
    }

    @Test
    fun `single line equal to a keyword is pure music ignoring case and punctuation`() {
        assertTrue(IslandPureMusicPolicy.isPureMusic(listOf("纯音乐，请欣赏"), keywords))
        assertTrue(IslandPureMusicPolicy.isPureMusic(listOf("纯音乐,请欣赏"), keywords))
        assertTrue(IslandPureMusicPolicy.isPureMusic(listOf(" instrumental "), keywords))
        assertTrue(IslandPureMusicPolicy.isPureMusic(listOf("暂无歌词"), keywords))
    }

    @Test
    fun `up to five lines containing a keyword is pure music`() {
        assertTrue(IslandPureMusicPolicy.isPureMusic(listOf("这首纯音乐，请欣赏吧"), keywords))
        assertTrue(IslandPureMusicPolicy.isPureMusic(listOf("Life and loss - Luke", "此歌曲为纯音乐请欣赏"), keywords))
        assertTrue(IslandPureMusicPolicy.isPureMusic(List(4) { "歌词$it" } + "纯音乐，请欣赏", keywords))
    }

    @Test
    fun `more than five lines or no keyword is not pure music`() {
        assertFalse(IslandPureMusicPolicy.isPureMusic(List(5) { "歌词$it" } + "纯音乐，请欣赏", keywords))
        assertFalse(IslandPureMusicPolicy.isPureMusic(listOf("第一行歌词", "第二行歌词"), keywords))
        assertFalse(IslandPureMusicPolicy.isPureMusic(listOf("一句普通歌词"), keywords))
    }

    @Test
    fun `custom keyword list replaces the presets`() {
        val custom = IslandPureMusicPolicy.normalizedKeywordSet("自定义\n")
        assertTrue(IslandPureMusicPolicy.isPureMusic(listOf("自定义"), custom))
        assertFalse(IslandPureMusicPolicy.isPureMusic(listOf("纯音乐，请欣赏"), custom))
    }

    @Test
    fun `empty keyword list only matches songs without lyrics`() {
        val none = IslandPureMusicPolicy.normalizedKeywordSet("")
        assertTrue(IslandPureMusicPolicy.isPureMusic(emptyList(), none))
        assertFalse(IslandPureMusicPolicy.isPureMusic(listOf("纯音乐"), none))
    }

    @Test
    fun `parse and serialize drop blanks and duplicates`() {
        assertEquals(listOf("a", "b"), IslandPureMusicPolicy.parseKeywords(" a \n\n b\r\na"))
        assertEquals("a\nb", IslandPureMusicPolicy.serializeKeywords(listOf("a", " ", "b", "a")))
        assertEquals(IslandPureMusicPolicy.DEFAULT_KEYWORDS, IslandPureMusicPolicy.parseKeywords(null))
    }

    @Test
    fun `saved list from legacy version gains only newly added defaults`() {
        val saved = "纯音乐\n自定义"
        val effective = IslandPureMusicPolicy.effectiveKeywords(saved, null)
        assertTrue("此歌曲为纯音乐，请欣赏" in effective)
        assertTrue("自定义" in effective)
        // 用户删掉的旧预设不会加回来
        assertFalse("暂无歌词" in effective)
    }

    @Test
    fun `defaults deleted after being seen are not restored`() {
        val seen = IslandPureMusicPolicy.DEFAULT_KEYWORDS_RAW
        val effective = IslandPureMusicPolicy.effectiveKeywords("纯音乐", seen)
        assertEquals(listOf("纯音乐"), effective)
    }

    @Test
    fun `never edited list follows latest defaults`() {
        assertEquals(IslandPureMusicPolicy.DEFAULT_KEYWORDS, IslandPureMusicPolicy.effectiveKeywords(null, null))
    }
}
