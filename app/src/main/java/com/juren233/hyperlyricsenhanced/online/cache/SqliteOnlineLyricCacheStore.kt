/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.juren233.hyperlyricsenhanced.common.db.DatabaseRetirement
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.root.reload.ModuleGeneration
import java.io.File

/**
 * SQLite storage of the online lyric response cache, in the data directory of whichever process
 * searches (SystemUI for the hooks, the app for its own lookups).
 */
internal class SqliteOnlineLyricCacheStore(context: Context) : OnlineLyricCacheStore {
    private val helper = Helper(context.applicationContext)
    private val retirement = DatabaseRetirement("在线歌词缓存库") { helper.close() }

    /** SystemUI hot reload: closes this generation's connections once no operation is running. */
    fun retire() = retirement.retire()

    internal val isClosed: Boolean get() = retirement.isClosed

    /**
     * Counts a request that uses the database across several calls ([OnlineLyricCacheIpc]), so a
     * retiring generation finishes it before closing. False once closed; a true result needs [exitRequest].
     */
    fun enterRequest(): Boolean = retirement.enter()

    fun exitRequest() = retirement.exit()

    /** The database file and its journal, for the storage figure of the management page. */
    val databaseFiles: List<File>
        get() = helper.databaseName?.let { name ->
            val file = helper.context.getDatabasePath(name)
            listOf(file, File(file.path + "-journal"), File(file.path + "-wal"))
        }.orEmpty()

    override fun get(key: String): StoredCacheEntry? = retirement.operation(null) {
        helper.readableDatabase.query(
            TABLE_ENTRIES,
            arrayOf(COLUMN_PAYLOAD, COLUMN_PERMANENT, COLUMN_STORED_AT),
            "$COLUMN_KEY = ?",
            arrayOf(key),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                StoredCacheEntry(
                    payload = cursor.getBlob(0),
                    permanent = cursor.getInt(1) != 0,
                    storedAtMs = cursor.getLong(2),
                )
            }
        }
    }

    override fun put(
        key: String,
        payload: ByteArray,
        permanent: Boolean,
        storedAtMs: Long,
        song: OnlineLyricCacheSong,
        source: Source,
        kind: OnlineLyricCacheKind,
        sizeBytes: Long,
    ) = retirement.operation<Unit>(Unit) {
        if (!ModuleGeneration.mayPublish(PUT_EXIT)) return@operation
        val db = helper.writableDatabase
        db.inTransaction {
            db.insertWithOnConflict(
                TABLE_ENTRIES,
                null,
                ContentValues().apply {
                    put(COLUMN_KEY, key)
                    put(COLUMN_PAYLOAD, payload)
                    put(COLUMN_PERMANENT, if (permanent) 1 else 0)
                    put(COLUMN_STORED_AT, storedAtMs)
                    put(COLUMN_SOURCE, source.name)
                    put(COLUMN_KIND, kind.name)
                    put(COLUMN_SIZE, sizeBytes)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            linkLocked(db, song, listOf(key), storedAtMs)
        }
    }

    override fun markPermanent(keys: Collection<String>) = retirement.operation<Unit>(Unit) {
        val db = helper.writableDatabase
        db.inTransaction {
            val values = ContentValues().apply { put(COLUMN_PERMANENT, 1) }
            keys.forEach { key -> db.update(TABLE_ENTRIES, values, "$COLUMN_KEY = ?", arrayOf(key)) }
        }
    }

    override fun remove(key: String) = retirement.operation<Unit>(Unit) {
        val db = helper.writableDatabase
        db.inTransaction {
            db.delete(TABLE_ENTRIES, "$COLUMN_KEY = ?", arrayOf(key))
            db.delete(TABLE_LINKS, "$COLUMN_KEY = ?", arrayOf(key))
        }
    }

    override fun link(song: OnlineLyricCacheSong, keys: Collection<String>, usedAtMs: Long) =
        retirement.operation<Unit>(Unit) {
            if (!ModuleGeneration.mayPublish(LINK_EXIT)) return@operation
            val db = helper.writableDatabase
            db.inTransaction { linkLocked(db, song, keys, usedAtMs) }
        }

    private fun linkLocked(db: SQLiteDatabase, song: OnlineLyricCacheSong, keys: Collection<String>, usedAtMs: Long) {
        // A lookup made without the album (metadata not ready yet) keeps the album already recorded.
        db.execSQL(
            "INSERT INTO $TABLE_SONGS ($COLUMN_TAG, $COLUMN_USED_AT, $COLUMN_TITLE, $COLUMN_ARTIST, " +
                "$COLUMN_PACKAGE, $COLUMN_ALBUM) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT($COLUMN_TAG) DO UPDATE SET $COLUMN_USED_AT = excluded.$COLUMN_USED_AT, " +
                "$COLUMN_TITLE = excluded.$COLUMN_TITLE, $COLUMN_ARTIST = excluded.$COLUMN_ARTIST, " +
                "$COLUMN_PACKAGE = excluded.$COLUMN_PACKAGE, " +
                "$COLUMN_ALBUM = CASE WHEN excluded.$COLUMN_ALBUM = '' THEN $TABLE_SONGS.$COLUMN_ALBUM " +
                "ELSE excluded.$COLUMN_ALBUM END",
            arrayOf<Any>(song.tag, usedAtMs, song.title, song.artist, song.packageName, song.album),
        )
        keys.forEach { key ->
            db.insertWithOnConflict(
                TABLE_LINKS,
                null,
                ContentValues().apply {
                    put(COLUMN_KEY, key)
                    put(COLUMN_TAG, song.tag)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    override fun recordMatch(tag: String, source: Source, score: Int?, wordTimed: Boolean) = retirement.operation<Unit>(Unit) {
        helper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO $TABLE_SONG_SOURCES ($COLUMN_TAG, $COLUMN_SOURCE, $COLUMN_SCORE, $COLUMN_WORD_TIMED) " +
                "SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM $TABLE_SONGS WHERE $COLUMN_TAG = ?)",
            arrayOf<Any?>(tag, source.name, score, if (wordTimed) 1 else 0, tag),
        )
    }

    override fun markUsed(tag: String, used: OnlineLyricCacheLastUsed) = retirement.operation<Unit>(Unit) {
        val values = ContentValues().apply {
            put(COLUMN_LAST_LYRICS_SOURCE, used.lyrics)
            put(COLUMN_LAST_TRANSLATION_SOURCE, used.translation)
            put(COLUMN_LAST_PRONUNCIATION_SOURCE, used.pronunciation)
        }
        helper.writableDatabase.update(TABLE_SONGS, values, "$COLUMN_TAG = ?", arrayOf(tag))
    }

    override fun trimSongs(maxSongs: Int, expiredBeforeMs: Long): List<String> = retirement.operation(emptyList()) {
        val db = helper.writableDatabase
        val catalogKeys = mutableListOf<String>()
        db.inTransaction {
            deleteExpiredMisses(db, expiredBeforeMs)
            // Only songs with lyrics count toward the limit; a song with only misses waits for them to expire.
            val withLyrics = "SELECT l.$COLUMN_TAG FROM $TABLE_LINKS l JOIN $TABLE_ENTRIES e " +
                "ON e.$COLUMN_KEY = l.$COLUMN_KEY WHERE e.$COLUMN_PERMANENT = 1"
            val count = db.compileStatement("SELECT COUNT(DISTINCT $COLUMN_TAG) FROM ($withLyrics)")
                .use { it.simpleQueryForLong() }
            if (count <= maxSongs) return@inTransaction
            db.execSQL(
                "DELETE FROM $TABLE_SONGS WHERE $COLUMN_TAG IN (" +
                    "SELECT $COLUMN_TAG FROM $TABLE_SONGS WHERE $COLUMN_TAG IN ($withLyrics) " +
                    "ORDER BY $COLUMN_USED_AT ASC LIMIT ?)",
                arrayOf<Any>(count - maxSongs),
            )
            // Catalog lookups no remaining song links to are about to go; their files must go with them.
            db.rawQuery(
                "SELECT $COLUMN_KEY FROM $TABLE_ENTRIES WHERE $COLUMN_KIND = ? AND $COLUMN_KEY NOT IN (" +
                    "SELECT $COLUMN_KEY FROM $TABLE_LINKS WHERE $COLUMN_TAG IN (SELECT $COLUMN_TAG FROM $TABLE_SONGS))",
                arrayOf(OnlineLyricCacheKind.CATALOG.name),
            ).use { cursor -> while (cursor.moveToNext()) catalogKeys += cursor.getString(0) }
            deleteUnreferenced(db)
        }
        catalogKeys
    }

    override fun summaries(expiredBeforeMs: Long): List<OnlineLyricCacheSongSummary> = retirement.operation(emptyList()) {
        val db = helper.writableDatabase
        db.inTransaction { deleteExpiredMisses(db, expiredBeforeMs) }
        val songs = LinkedHashMap<String, Triple<OnlineLyricCacheSong, Long, OnlineLyricCacheLastUsed>>()
        db.rawQuery(
            "SELECT $COLUMN_TAG, $COLUMN_TITLE, $COLUMN_ARTIST, $COLUMN_PACKAGE, $COLUMN_USED_AT, $COLUMN_ALBUM, " +
                "${LAST_USED_COLUMNS.joinToString()} FROM $TABLE_SONGS ORDER BY $COLUMN_USED_AT DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val song = OnlineLyricCacheSong(
                    tag = cursor.getString(0),
                    title = cursor.getString(1).orEmpty(),
                    artist = cursor.getString(2).orEmpty(),
                    packageName = cursor.getString(3).orEmpty(),
                    album = cursor.getString(5).orEmpty(),
                )
                fun sourceAt(index: Int) = cursor.getString(index)?.takeIf(String::isNotBlank)
                songs[song.tag] = Triple(
                    song,
                    cursor.getLong(4),
                    OnlineLyricCacheLastUsed(lyrics = sourceAt(6), translation = sourceAt(7), pronunciation = sourceAt(8)),
                )
            }
        }
        val matches = HashMap<Pair<String, String>, Pair<Int?, Boolean>>()
        db.rawQuery(
            "SELECT $COLUMN_TAG, $COLUMN_SOURCE, $COLUMN_SCORE, $COLUMN_WORD_TIMED FROM $TABLE_SONG_SOURCES",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                matches[cursor.getString(0) to cursor.getString(1)] =
                    (if (cursor.isNull(2)) null else cursor.getInt(2)) to (cursor.getInt(3) != 0)
            }
        }
        val stats = HashMap<String, MutableMap<String, OnlineLyricCacheSourceStats>>()
        db.rawQuery(
            "SELECT l.$COLUMN_TAG, e.$COLUMN_SOURCE, " +
                "SUM(CASE WHEN e.$COLUMN_PERMANENT = 1 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN e.$COLUMN_PERMANENT = 0 THEN 1 ELSE 0 END), " +
                "SUM(e.$COLUMN_SIZE) " +
                "FROM $TABLE_LINKS l JOIN $TABLE_ENTRIES e ON e.$COLUMN_KEY = l.$COLUMN_KEY " +
                "GROUP BY l.$COLUMN_TAG, e.$COLUMN_SOURCE",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val tag = cursor.getString(0)
                val source = cursor.getString(1)
                val match = matches[tag to source]
                stats.getOrPut(tag) { linkedMapOf() }[source] = OnlineLyricCacheSourceStats(
                    source = source,
                    hits = cursor.getInt(2),
                    misses = cursor.getInt(3),
                    sizeBytes = cursor.getLong(4),
                    score = match?.first,
                    wordTimed = match?.second,
                )
            }
        }
        songs.values.map { (song, usedAt, lastUsed) ->
            OnlineLyricCacheSongSummary(
                song = song,
                usedAtMs = usedAt,
                sources = stats[song.tag]?.values?.sortedBy { sourceOrder(it.source) }.orEmpty(),
                lastUsed = lastUsed,
            )
        }
    }

    override fun delete(selection: OnlineLyricCacheSelection): List<String> = retirement.operation(emptyList()) {
        val (where, args) = selectionClause(selection)
        val db = helper.writableDatabase
        val catalogKeys = mutableListOf<String>()
        db.inTransaction {
            db.rawQuery(
                "SELECT $COLUMN_KEY FROM $TABLE_ENTRIES WHERE $COLUMN_KIND = ? AND ($where)",
                arrayOf(OnlineLyricCacheKind.CATALOG.name) + args,
            ).use { cursor -> while (cursor.moveToNext()) catalogKeys += cursor.getString(0) }
            db.delete(TABLE_ENTRIES, where, args)
            deleteUnreferenced(db)
        }
        catalogKeys
    }

    private fun selectionClause(selection: OnlineLyricCacheSelection): Pair<String, Array<String>> {
        val clauses = mutableListOf("1 = 1")
        val args = mutableListOf<String>()
        selection.tags?.let { tags ->
            clauses += "$COLUMN_KEY IN (SELECT $COLUMN_KEY FROM $TABLE_LINKS WHERE $COLUMN_TAG IN (" +
                tags.joinToString(",") { "?" } + "))"
            args += tags
        }
        selection.sources?.let { sources ->
            clauses += "$COLUMN_SOURCE IN (" + sources.joinToString(",") { "?" } + ")"
            args += sources
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    /** Misses stored at or before [expiredBeforeMs], and whatever they leave unreferenced. */
    private fun deleteExpiredMisses(db: SQLiteDatabase, expiredBeforeMs: Long) {
        db.delete(
            TABLE_ENTRIES,
            "$COLUMN_PERMANENT = 0 AND $COLUMN_STORED_AT <= ?",
            arrayOf(expiredBeforeMs.toString()),
        )
        deleteUnreferenced(db)
    }

    /** Links of deleted songs or entries, entries no song uses, then songs left without entries. */
    private fun deleteUnreferenced(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM $TABLE_LINKS WHERE $COLUMN_TAG NOT IN (SELECT $COLUMN_TAG FROM $TABLE_SONGS) " +
                "OR $COLUMN_KEY NOT IN (SELECT $COLUMN_KEY FROM $TABLE_ENTRIES)"
        )
        db.execSQL(
            "DELETE FROM $TABLE_ENTRIES WHERE $COLUMN_KEY NOT IN (SELECT $COLUMN_KEY FROM $TABLE_LINKS)"
        )
        db.execSQL(
            "DELETE FROM $TABLE_SONGS WHERE $COLUMN_TAG NOT IN (SELECT $COLUMN_TAG FROM $TABLE_LINKS)"
        )
        // A source's match record and its 「上次…」 marks go with the last entry of that source.
        val sourceStillLinked = "SELECT 1 FROM $TABLE_LINKS l JOIN $TABLE_ENTRIES e ON e.$COLUMN_KEY = l.$COLUMN_KEY " +
            "WHERE l.$COLUMN_TAG = %1\$s.$COLUMN_TAG AND e.$COLUMN_SOURCE = %1\$s.%2\$s"
        db.execSQL(
            "DELETE FROM $TABLE_SONG_SOURCES WHERE NOT EXISTS (" +
                sourceStillLinked.format(TABLE_SONG_SOURCES, COLUMN_SOURCE) + ")"
        )
        for (column in LAST_USED_COLUMNS) {
            db.execSQL(
                "UPDATE $TABLE_SONGS SET $column = NULL WHERE $column IS NOT NULL AND NOT EXISTS (" +
                    sourceStillLinked.format(TABLE_SONGS, column) + ")"
            )
        }
    }

    private fun sourceOrder(name: String): Int =
        Source.entries.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: Int.MAX_VALUE

    private inline fun SQLiteDatabase.inTransaction(block: () -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private class Helper(val context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_ENTRIES ($COLUMN_KEY TEXT PRIMARY KEY, $COLUMN_PAYLOAD BLOB NOT NULL, " +
                    "$COLUMN_PERMANENT INTEGER NOT NULL, $COLUMN_STORED_AT INTEGER NOT NULL, " +
                    "$COLUMN_SOURCE TEXT NOT NULL, $COLUMN_KIND TEXT NOT NULL, $COLUMN_SIZE INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE $TABLE_LINKS ($COLUMN_KEY TEXT NOT NULL, $COLUMN_TAG TEXT NOT NULL, " +
                    "PRIMARY KEY ($COLUMN_KEY, $COLUMN_TAG))"
            )
            db.execSQL("CREATE INDEX idx_links_tag ON $TABLE_LINKS($COLUMN_TAG)")
            db.execSQL(
                "CREATE TABLE $TABLE_SONGS ($COLUMN_TAG TEXT PRIMARY KEY, $COLUMN_USED_AT INTEGER NOT NULL, " +
                    "$COLUMN_TITLE TEXT, $COLUMN_ARTIST TEXT, $COLUMN_PACKAGE TEXT, $COLUMN_ALBUM TEXT NOT NULL DEFAULT '')"
            )
            db.execSQL("CREATE INDEX idx_songs_used ON $TABLE_SONGS($COLUMN_USED_AT)")
            createSongSources(db)
            LAST_USED_COLUMNS.forEach { addTextColumn(db, it) }
        }

        private fun addTextColumn(db: SQLiteDatabase, column: String) =
            db.execSQL("ALTER TABLE $TABLE_SONGS ADD COLUMN $column TEXT")

        /** Per-source match details (version 4). */
        private fun createSongSources(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_SONG_SOURCES ($COLUMN_TAG TEXT NOT NULL, $COLUMN_SOURCE TEXT NOT NULL, " +
                    "$COLUMN_SCORE INTEGER, $COLUMN_WORD_TIMED INTEGER NOT NULL, " +
                    "PRIMARY KEY ($COLUMN_TAG, $COLUMN_SOURCE))"
            )
        }

        /**
         * Version 2 lacks the album: it is added, existing songs get it on their next lookup.
         * Version 3 lacks the match details and the last shown sources: they fill in as songs are matched and shown.
         * Version 4 (240177 test build only) kept one last shown source without saying what it was shown for:
         * its column becomes the lyric body's and is emptied, the shown sources fill in on the next display.
         * Version 1 (240159 test builds only) had no song names or sources: start over.
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                recreate(db)
                return
            }
            if (oldVersion == 2) {
                db.execSQL("ALTER TABLE $TABLE_SONGS ADD COLUMN $COLUMN_ALBUM TEXT NOT NULL DEFAULT ''")
            }
            if (oldVersion < 4) {
                createSongSources(db)
                LAST_USED_COLUMNS.forEach { addTextColumn(db, it) }
                return
            }
            if (oldVersion == 4) {
                db.execSQL("ALTER TABLE $TABLE_SONGS RENAME COLUMN last_source TO $COLUMN_LAST_LYRICS_SOURCE")
                db.execSQL("UPDATE $TABLE_SONGS SET $COLUMN_LAST_LYRICS_SOURCE = NULL")
                addTextColumn(db, COLUMN_LAST_TRANSLATION_SOURCE)
                addTextColumn(db, COLUMN_LAST_PRONUNCIATION_SOURCE)
            }
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

        private fun recreate(db: SQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ENTRIES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LINKS")
            db.execSQL("DROP TABLE IF EXISTS tags")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SONGS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SONG_SOURCES")
            onCreate(db)
        }
    }

    internal companion object {
        /** [ModuleGeneration] exits: a retired generation adds no response or song link (deletions still run). */
        const val PUT_EXIT = "在线歌词缓存写入"
        const val LINK_EXIT = "在线歌词缓存歌曲关联"

        const val DATABASE_NAME = "hyperlyricsenhanced_online_lyrics.db"
        const val DATABASE_VERSION = 5
        const val TABLE_ENTRIES = "entries"
        const val TABLE_LINKS = "links"
        const val TABLE_SONGS = "songs"
        const val TABLE_SONG_SOURCES = "song_sources"
        const val COLUMN_KEY = "cache_key"
        const val COLUMN_PAYLOAD = "payload"
        const val COLUMN_PERMANENT = "permanent"
        const val COLUMN_STORED_AT = "stored_at"
        const val COLUMN_SOURCE = "source"
        const val COLUMN_KIND = "kind"
        const val COLUMN_SIZE = "size"
        const val COLUMN_TAG = "tag"
        const val COLUMN_USED_AT = "used_at"
        const val COLUMN_TITLE = "title"
        const val COLUMN_ARTIST = "artist"
        const val COLUMN_PACKAGE = "package"
        const val COLUMN_ALBUM = "album"
        const val COLUMN_LAST_LYRICS_SOURCE = "last_lyrics_source"
        const val COLUMN_LAST_TRANSLATION_SOURCE = "last_translation_source"
        const val COLUMN_LAST_PRONUNCIATION_SOURCE = "last_pronunciation_source"

        /** In [OnlineLyricCacheLastUsed] order: lyrics, translation, pronunciation. */
        val LAST_USED_COLUMNS = listOf(
            COLUMN_LAST_LYRICS_SOURCE,
            COLUMN_LAST_TRANSLATION_SOURCE,
            COLUMN_LAST_PRONUNCIATION_SOURCE,
        )
        const val COLUMN_SCORE = "score"
        const val COLUMN_WORD_TIMED = "word_timed"
    }
}
