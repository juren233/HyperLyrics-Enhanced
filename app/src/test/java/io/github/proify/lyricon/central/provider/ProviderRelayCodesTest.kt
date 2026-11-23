package io.github.proify.lyricon.central.provider

import io.github.proify.lyricon.provider.IRemotePlayer
import io.github.proify.lyricon.provider.IRemoteService
import org.junit.Assert.assertEquals
import org.junit.Test

/** The held codes are hard-coded; a provider library update that moves or adds a method fails here. */
class ProviderRelayCodesTest {
    private fun codes(stub: Class<*>): Map<String, Int> =
        stub.declaredFields.filter { it.name.startsWith("TRANSACTION_") }.associate {
            it.isAccessible = true
            it.name.removePrefix("TRANSACTION_") to it.getInt(null)
        }

    @Test fun `player relay holds every setter and nothing that needs an answer`() {
        val all = codes(IRemotePlayer.Stub::class.java)
        val setters = all - "getPositionMemory"
        assertEquals(
            setOf("setSong", "setPlaybackState", "seekTo", "sendText", "setPositionUpdateInterval",
                "setDisplayTranslation", "setDisplayRoma", "setPlaybackState2", "getPositionMemory"),
            all.keys,
        )
        assertEquals(setters.values.sorted(), ProviderConnection.PLAYER_HELD_CODES.sorted())
    }

    @Test fun `service relay holds disconnect only`() {
        val all = codes(IRemoteService.Stub::class.java)
        assertEquals(setOf("getPlayer", "disconnect"), all.keys)
        assertEquals(listOf(all.getValue("disconnect")), ProviderConnection.SERVICE_HELD_CODES.toList())
    }
}
