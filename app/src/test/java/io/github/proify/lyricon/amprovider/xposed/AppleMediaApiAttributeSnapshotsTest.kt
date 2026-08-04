package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleMediaApiAttributeSnapshotsTest {

    @Test
    fun `snapshot keeps the first raw values after the model is overridden`() {
        val attributes = AttributeToken("same")

        AppleMediaApiAttributeSnapshots.remember(
            attributes = attributes,
            name = "花",
            artistName = "藤井 風",
            albumName = "Pre: Prema",
        )
        AppleMediaApiAttributeSnapshots.remember(
            attributes = attributes,
            name = "Hana",
            artistName = "藤井风",
            albumName = "Pre: Prema",
        )

        assertEquals(
            AppleMediaApiAttributeSnapshots.Snapshot(
                name = "花",
                artistName = "藤井 風",
                albumName = "Pre: Prema",
            ),
            AppleMediaApiAttributeSnapshots.get(attributes),
        )
    }

    @Test
    fun `equal attribute values remain isolated by object identity`() {
        val first = AttributeToken("same")
        val second = AttributeToken("same")

        AppleMediaApiAttributeSnapshots.remember(first, "花", "藤井 風", null)
        AppleMediaApiAttributeSnapshots.remember(second, "Hana", "Fujii Kaze", null)

        assertEquals("花", AppleMediaApiAttributeSnapshots.get(first)?.name)
        assertEquals("Hana", AppleMediaApiAttributeSnapshots.get(second)?.name)
    }

    private data class AttributeToken(val value: String)
}
