/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional original-APK test: no proprietary APK is checked into the repository. */
class AppleMusicProfileBinaryTest {
    @Test
    fun `all current 1599 targets and member chains match the original APK`() {
        verify(AppleMusicVersion("6.5.3", 1599L), "HLE_APPLE_MUSIC_APK", "HLE_APPLE_PROFILE_EXPORT")
    }

    @Test
    fun `all current 1606 targets and member chains match the original APK`() {
        verify(AppleMusicVersion("7.0.0-beta", 1606L), "HLE_APPLE_MUSIC_700_APK", "HLE_APPLE_PROFILE_700_EXPORT")
    }

    private fun verify(version: AppleMusicVersion, apkVariable: String, exportVariable: String) {
        val apk = System.getenv(apkVariable)
        assumeTrue("Set $apkVariable to the original ${version.displayName} base.apk", !apk.isNullOrBlank())
        assertTrue("APK does not exist", File(requireNotNull(apk)).isFile)
        val json = buildJsonObject {
            put("id", AppleMusicHookProfiles.profileFor(version)!!.id)
            put("hookPoints", buildJsonObject {
                AppleMusicHookPoint.entries.forEach { point ->
                    if (AppleMusicHookProfiles.exactTargets(version, point).isEmpty()) return@forEach
                    put(point.name, buildJsonArray {
                        AppleMusicHookProfiles.exactTargets(version, point).forEach { target ->
                            add(buildJsonObject {
                                put("className", target.className)
                                target.methodName?.let { put("methodName", it) }
                                target.parameterCount?.let { put("parameterCount", it) }
                                target.parameterTypeNames?.let { names ->
                                    put("parameterTypeNames", buildJsonArray {
                                        names.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) }
                                    })
                                }
                                target.returnTypeName?.let { put("returnTypeName", it) }
                                target.isStatic?.let { put("isStatic", it) }
                                put("includeSynthetic", target.includeSynthetic)
                                put("allowFirstMatch", target.allowFirstMatch)
                                put("runtimeMemberNames", buildJsonObject {
                                    target.runtimeMemberNames.forEach { (key, value) -> put(key.name, value) }
                                })
                            })
                        }
                    })
                }
            })
        }
        val export = System.getenv(exportVariable)?.let(::File)
            ?: File.createTempFile("apple-${version.versionCode}-profile-", ".json").apply { deleteOnExit() }
        export.writeText(json.toString())
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "scripts/verify_apple_music_profile_export.py").isFile }
        val process = ProcessBuilder(
            "python3", File(root, "scripts/verify_apple_music_profile_export.py").absolutePath,
            "--apk", requireNotNull(apk), "--profiles-json", export.absolutePath,
            "--profile-id", AppleMusicHookProfiles.profileFor(version)!!.id,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
        println(output)
        if (version.versionCode == 1606L) {
            // The old names still exist, but their DEX signatures/types have different roles.
            val mutations = listOf(
                Triple("CUSTOM_TEXT_VIEW_FUTURE_RESOLVE_METHOD", "f", "q.B#f"),
                Triple("CONTENT_HTTP_HEADERS_GET_METHOD", "e", "kk.t#e"),
                Triple("PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD", "g0", "q8.v2.g0"),
                Triple("PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD", "Y", "q8.v2.Y"),
            )
            val corrupted = File.createTempFile("apple-stale-settings-", ".json")
            try {
                // Both classes exist in DEX: reject the unregistered activity specifically.
                val launchPoint = "APPLE_MAIN_CONTENT_ACTIVITY"
                val legacyActivity = buildJsonObject {
                    json.jsonObject.forEach { (key, value) ->
                        if (key != "hookPoints") put(key, value) else put(key, buildJsonObject {
                            value.jsonObject.forEach { (point, targets) ->
                                if (point != launchPoint) put(point, targets) else put(point, buildJsonArray {
                                    targets.jsonArray.forEach { target -> add(buildJsonObject {
                                        target.jsonObject.forEach { (member, field) ->
                                            put(member, if (member == "className")
                                                JsonPrimitive("com.apple.android.music.common.MainContentActivity") else field)
                                        }
                                    }) }
                                })
                            }
                        })
                    }
                }
                corrupted.writeText(legacyActivity.toString())
                val legacyCheck = ProcessBuilder(
                    "python3", File(root, "scripts/verify_apple_music_profile_export.py").absolutePath,
                    "--apk", requireNotNull(apk), "--profiles-json", corrupted.absolutePath,
                    "--profile-id", AppleMusicHookProfiles.profileFor(version)!!.id,
                ).redirectErrorStream(true).start()
                val legacyOutput = legacyCheck.inputStream.bufferedReader().use { it.readText() }
                assertEquals(legacyOutput, 1, legacyCheck.waitFor())
                assertTrue(legacyOutput, legacyOutput.contains("not a registered Activity in AndroidManifest.xml"))
                for ((member, oldName, expectedError) in mutations) {
                    val currentName = AppleMusicHookPoint.entries.asSequence()
                        .flatMap { AppleMusicHookProfiles.exactTargets(version, it).asSequence() }
                        .flatMap { it.runtimeMemberNames.entries.asSequence() }
                        .first { it.key.name == member }.value
                    val changed = json.toString().replace(
                        "\"$member\":\"$currentName\"", "\"$member\":\"$oldName\"",
                    )
                    assertTrue("Mutation did not change $member", changed != json.toString())
                    corrupted.writeText(changed)
                    val rejected = ProcessBuilder(
                        "python3", File(root, "scripts/verify_apple_music_profile_export.py").absolutePath,
                        "--apk", requireNotNull(apk), "--profiles-json", corrupted.absolutePath,
                        "--profile-id", AppleMusicHookProfiles.profileFor(version)!!.id,
                    ).redirectErrorStream(true).start()
                    val rejection = rejected.inputStream.bufferedReader().use { it.readText() }
                    assertEquals("Old member was accepted: $member\n$rejection", 1, rejected.waitFor())
                    assertTrue(rejection, rejection.contains(expectedError))
                }
            } finally {
                corrupted.delete()
            }
        }
    }
}
