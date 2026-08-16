/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import io.github.proify.extensions.deflate
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.util.Locale

object DiskSongManager {
    private var baseDir: File? = null
    private var missingLyricsBaseDir: File? = null

    fun initialize(context: Context) {
        val lyriconDir = File(context.filesDir, "lyricon")

        val locale = Locale.getDefault()
        baseDir = File(File(lyriconDir, "songs"), locale.toLanguageTag())
        baseDir?.mkdirs()
        missingLyricsBaseDir = File(
            File(lyriconDir, "missing_lyrics"),
            locale.toLanguageTag(),
        )
        missingLyricsBaseDir?.mkdirs()
    }

    fun save(appleSong: AppleSong): Boolean {
        val id = appleSong.adamId
        if (id.isNullOrBlank()) return false
        val string = json.encodeToString(appleSong)
        return runCatching {
            val file = getFile(id)

            file.also { it.parentFile?.mkdirs() }
                .writeBytes(
                    string
                        .toByteArray(Charsets.UTF_8)
                        .deflate()
                )
        }.isSuccess
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun load(id: String): AppleSong? {
        return runCatching {
            getFile(id)
                .takeIf { it.exists() }
                ?.readBytes()
                ?.inflate()
                ?.let {
                    json.decodeFromString<AppleSong>(
                        it.toString(Charsets.UTF_8)
                    )
                }
        }.getOrNull()
    }

    fun delete(id: String): Boolean = runCatching {
        val file = getFile(id)
        !file.exists() || file.delete()
    }.getOrDefault(false)

    fun saveMissingLyrics(song: Song): Boolean {
        val id = song.id?.takeIf(String::isNotBlank) ?: return false
        val string = json.encodeToString(song)
        return runCatching {
            getMissingLyricsFile(id)
                .also { it.parentFile?.mkdirs() }
                .writeBytes(string.toByteArray(Charsets.UTF_8).deflate())
        }.isSuccess
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun loadMissingLyrics(id: String): Song? = runCatching {
        getMissingLyricsFile(id)
            .takeIf(File::exists)
            ?.readBytes()
            ?.inflate()
            ?.let { json.decodeFromString<Song>(it.toString(Charsets.UTF_8)) }
    }.getOrNull()

    fun deleteMissingLyrics(id: String): Boolean = runCatching {
        val file = getMissingLyricsFile(id)
        !file.exists() || file.delete()
    }.getOrDefault(false)

    //fun hasCache(id: String): Boolean = getFile(id).exists()
    private fun getFile(id: String): File = File(baseDir, "$id.json.gz")

    private fun getMissingLyricsFile(id: String): File =
        File(missingLyricsBaseDir, "$id.json.gz")
}
