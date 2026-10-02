/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleArtistComposeMetadataTest {
    private val version = AppleMusicVersion("7.0.0-beta", 1606L)

    @Test
    fun `artist Compose entry names match original 1606 descriptors rather than old Epoxy`() {
        val data = AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.ARTIST_COMPOSE_DATA).single()
        assertEquals("com.apple.android.music.profiles2.GenericProfileViewModel", data.className)
        assertEquals("handleProfileData", data.methodName)
        assertEquals(
            listOf("[Lcom.apple.android.music.mediaapi.models.MediaEntity;", "com.apple.android.music.profiles2.GenericProfileViewModel\$a"),
            data.parameterTypeNames,
        )
        assertEquals("void", data.returnTypeName)
        assertEquals(false, data.isStatic)
        assertEquals("GENERIC_PROFILE", data.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_COMPOSE_GENERIC_KIND))
        assertEquals("SIMPLIFIED_RESPONSE", data.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_COMPOSE_SIMPLIFIED_KIND))
        assertFalse(data.runtimeMemberNames.values.contains("PLAYABLE_DEFAULT_CONTENT"))

        val content = AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.ARTIST_COMPOSE_CONTENT).single()
        assertEquals("com.apple.android.music.profiles2.ArtistFragment", content.className)
        assertEquals("r1", content.methodName)
        assertEquals(listOf("z0.m"), content.parameterTypeNames)
        assertFalse(content.className.contains("Epoxy"))
    }

    @Test
    fun `Compose artist support does not alter older version profiles`() {
        val points = AppleMusicHookPoint.entries.filter { it.name.startsWith("ARTIST_COMPOSE_") }
        assertEquals(10, points.size)
        points.forEach { point ->
            assertEquals(1, AppleMusicHookProfiles.exactTargets(version, point).size)
            assertTrue(AppleMusicHookProfiles.exactTargets(AppleMusicVersion("6.5.3", 1599L), point).isEmpty())
        }
    }

    @Test
    fun `page entity kinds keep artists albums and songs isolated`() {
        assertEquals(InAppLibraryEntityKind.ARTIST, artistComposeEntityKind("artists"))
        assertEquals(InAppLibraryEntityKind.SONG, artistComposeEntityKind("library-songs"))
        assertEquals(InAppLibraryEntityKind.ALBUM, artistComposeEntityKind("albums"))
        assertEquals(null, artistComposeEntityKind("music-videos"))
        assertEquals(null, artistComposeEntityKind(null))
    }
}
