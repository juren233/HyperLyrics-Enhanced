package io.github.proify.lyricon.amprovider.xposed

import java.util.IdentityHashMap

internal object AppleMediaApiAttributeSnapshots {
    private const val MAX_SNAPSHOTS = 8_192
    private val snapshots = IdentityHashMap<Any, Snapshot>()

    fun remember(
        attributes: Any,
        name: String?,
        artistName: String?,
        albumName: String?,
    ): Snapshot = synchronized(snapshots) {
        snapshots[attributes] ?: Snapshot(
            name = name,
            artistName = artistName,
            albumName = albumName,
        ).also { snapshot ->
            if (snapshots.size >= MAX_SNAPSHOTS) snapshots.clear()
            snapshots[attributes] = snapshot
        }
    }

    fun get(attributes: Any): Snapshot? = synchronized(snapshots) {
        snapshots[attributes]
    }

    data class Snapshot(
        val name: String?,
        val artistName: String?,
        val albumName: String?,
    )
}
