package com.juren233.hyperlyricsenhanced.root.salt

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery

internal data class SaltPlayerNextTrackProfile(
    val versionName: String,
    val controllerClassName: String = "com.salt.music.service.MusicController",
    val songClassName: String = "com.salt.music.data.entry.Song",
)

internal object SaltPlayerNextTrackHookProfiles {
    const val CACHE_KEY = "salt-player-native-next-track-v1"

    fun resolve(versionName: String): SaltPlayerNextTrackProfile =
        SaltPlayerNextTrackProfile(versionName = versionName)

    fun usesNativeLyricon(versionName: String): Boolean {
        val parts = versionName.substringBefore('-').split('.').mapNotNull(String::toIntOrNull)
        if (parts.size < 2) return false
        val major = parts[0]
        val minor = parts[1]
        val patch = parts.getOrElse(2) { 0 }
        return major > 12 || (major == 12 && (minor > 2 || (minor == 2 && patch >= 0)))
    }

    fun controllerQuery(profile: SaltPlayerNextTrackProfile): OfficialProviderDexMethodQuery =
        OfficialProviderDexMethodQuery(
            cacheKey = CACHE_KEY,
            declaringClassNamePrefix = profile.controllerClassName.substringBeforeLast('.') + ".",
            parameterTypeNames = listOf(profile.songClassName, "long", "long", "java.lang.Long"),
            returnTypeName = "void",
            isStatic = true,
        )
}
