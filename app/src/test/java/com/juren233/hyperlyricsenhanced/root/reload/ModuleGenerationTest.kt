package com.juren233.hyperlyricsenhanced.root.reload

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ModuleGenerationTest {
    @After fun restore() = ModuleGeneration.resetForTest()

    @Test fun `a retired generation refuses every exit`() {
        assertTrue(ModuleGeneration.mayPublish("test"))
        ModuleGeneration.retire()
        assertTrue(ModuleGeneration.isRetired)
        assertFalse(ModuleGeneration.mayPublish("test"))
        assertFalse(ModuleGeneration.mayPublish("test"))
    }

    /** The exits named in ModuleGeneration's documentation each ask it before publishing. */
    @Test fun `every documented exit asks the generation`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val exits = mapOf(
            "com/juren233/hyperlyricsenhanced/root/source/RootLyricSink.kt" to 11,
            "com/juren233/hyperlyricsenhanced/root/source/AppleMusicDirectBridge.kt" to 1,
            "io/github/proify/lyricon/central/subscriber/ActivePlayerSubscription.kt" to 8,
            "io/github/proify/lyricon/central/registration/RegistrationHandler.kt" to 1,
            "io/github/proify/lyricon/central/BridgeCentral.kt" to 1,
            "com/juren233/hyperlyricsenhanced/common/bridge/AppleDirectBroadcastIdentity.kt" to 1,
            "com/juren233/hyperlyricsenhanced/root/ClassicAodFocusNotificationRecovery.kt" to 1,
            // Disk writes a retired generation must not add (2026-11-23): responses, song links, AMLL files.
            "com/juren233/hyperlyricsenhanced/online/cache/SqliteOnlineLyricCacheStore.kt" to 2,
            "com/juren233/hyperlyricsenhanced/online/source/amll/AmllTtmlRepository.kt" to 1,
        )
        exits.forEach { (path, expected) ->
            val found = Regex("""ModuleGeneration\.mayPublish\(""").findAll(File(root, path).readText()).count()
            assertTrue("$path asks $found times, expected $expected", found == expected)
        }
        val sink = File(root, "com/juren233/hyperlyricsenhanced/root/source/RootLyricSink.kt").readText()
        val overrides = Regex("""override fun (\w+)\(""").findAll(sink).map { it.groupValues[1] }.toSet() - "currentPlaybackState"
        overrides.forEach { assertTrue("RootLyricSink.$it is not gated", "\"RootLyricSink.$it\"" in sink) }
    }
}
