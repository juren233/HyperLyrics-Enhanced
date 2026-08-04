package io.github.proify.lyricon.amprovider.xposed

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Read-through localized metadata cache stored beside Apple Music for zero-hop startup reads. */
internal class AppleLocalizedMetadataCache(
    context: Context,
    private val mainHandler: Handler,
) {
    private val helper = DatabaseHelper(context.applicationContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HLE-AppleMetadataCache").apply { isDaemon = true }
    }
    @Volatile
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun warmRecentBlocking(
        prefix: String,
    ): Map<String, AppleInternalCatalogResolver.Alias>? {
        if (!enabled) return null
        return runCatching { readRecent(prefix) }
            .onFailure { ProviderLogger.error("Apple 地区元数据缓存预热失败", it) }
            .getOrNull()
    }

    fun warmRecentAsync(
        prefix: String,
        onResult: (Map<String, AppleInternalCatalogResolver.Alias>?) -> Unit,
    ) {
        if (!enabled) {
            onResult(null)
            return
        }
        executor.execute {
            val aliases = warmRecentBlocking(prefix)
            mainHandler.post { onResult(aliases.takeIf { enabled }) }
        }
    }

    fun getMany(
        keys: Collection<String>,
        onResult: (Map<String, AppleInternalCatalogResolver.Alias>) -> Unit,
    ) {
        val normalizedKeys = keys.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (!enabled || normalizedKeys.isEmpty()) {
            onResult(emptyMap())
            return
        }
        executor.execute {
            val aliases = if (enabled) {
                runCatching { readAll(normalizedKeys) }
                    .onFailure { ProviderLogger.error("Apple 地区元数据缓存读取失败", it) }
                    .getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            mainHandler.post { onResult(aliases.takeIf { enabled }.orEmpty()) }
        }
    }

    fun put(key: String, alias: AppleInternalCatalogResolver.Alias) {
        putMany(mapOf(key to alias))
    }

    fun putMany(aliases: Map<String, AppleInternalCatalogResolver.Alias>) {
        if (!enabled || aliases.isEmpty()) return
        val normalized = aliases.filterKeys(String::isNotBlank)
        executor.execute {
            if (!enabled) return@execute
            runCatching { writeAll(normalized) }
                .onFailure { ProviderLogger.error("Apple 地区元数据缓存写入失败", it) }
        }
    }

    private fun readRecent(prefix: String): Map<String, AppleInternalCatalogResolver.Alias> {
        val aliases = linkedMapOf<String, AppleInternalCatalogResolver.Alias>()
        val db = helper.readableDatabase
        CacheBucket.entries.forEach { bucket ->
            db.query(
                TABLE_NAME,
                COLUMNS,
                "$COLUMN_KEY LIKE ? AND ${bucket.selectionSql}",
                arrayOf("$prefix%"),
                null,
                null,
                "$COLUMN_UPDATED_AT DESC",
                bucket.warmLimit.toString(),
            ).use { cursor ->
                while (cursor.moveToNext()) aliases.putFromCursor(cursor)
            }
        }
        ProviderLogger.info(
            "Apple 地区元数据缓存统计: " +
                "song=${countRows(db, CacheBucket.SONG)}, " +
                "album=${countRows(db, CacheBucket.ALBUM)}, " +
                "artist=${countRows(db, CacheBucket.ARTIST)}, " +
                "artistAlias=${countRows(db, CacheBucket.ARTIST_ALIAS)}, " +
                "warm=${aliases.size}"
        )
        return aliases
    }

    private fun readAll(
        keys: List<String>,
    ): Map<String, AppleInternalCatalogResolver.Alias> = buildMap {
        keys.chunked(MAX_BATCH_SIZE).forEach { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            helper.readableDatabase.query(
                TABLE_NAME,
                COLUMNS,
                "$COLUMN_KEY IN ($placeholders)",
                batch.toTypedArray(),
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) putFromCursor(cursor)
            }
        }
    }

    private fun writeAll(aliases: Map<String, AppleInternalCatalogResolver.Alias>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            aliases.forEach { (key, alias) ->
                db.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    ContentValues().apply {
                        put(COLUMN_KEY, key)
                        put(COLUMN_TITLE, alias.title)
                        put(COLUMN_ARTIST, alias.artist)
                        put(COLUMN_ALBUM, alias.album)
                        put(COLUMN_LANGUAGE, alias.language)
                        put(COLUMN_UPDATED_AT, System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            trimToBucketSizes(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun MutableMap<String, AppleInternalCatalogResolver.Alias>.putFromCursor(cursor: Cursor) {
        val key = cursor.stringColumn(COLUMN_KEY)
        this[key] = AppleInternalCatalogResolver.Alias(
            title = cursor.stringColumn(COLUMN_TITLE),
            artist = cursor.stringColumn(COLUMN_ARTIST),
            album = cursor.stringColumn(COLUMN_ALBUM),
            language = cursor.stringColumn(COLUMN_LANGUAGE),
        )
    }

    private fun Cursor.stringColumn(name: String): String =
        getString(getColumnIndexOrThrow(name)).orEmpty()

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_NAME (
                    $COLUMN_KEY TEXT PRIMARY KEY,
                    $COLUMN_TITLE TEXT NOT NULL,
                    $COLUMN_ARTIST TEXT NOT NULL,
                    $COLUMN_ALBUM TEXT NOT NULL,
                    $COLUMN_LANGUAGE TEXT NOT NULL,
                    $COLUMN_UPDATED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_hle_apple_metadata_updated " +
                    "ON $TABLE_NAME($COLUMN_UPDATED_AT)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    private companion object {
        const val DATABASE_NAME = "hyperlyricsenhanced_apple_metadata.db"
        const val DATABASE_VERSION = 1
        const val TABLE_NAME = "localized_metadata"
        const val COLUMN_KEY = "cache_key"
        const val COLUMN_TITLE = "title"
        const val COLUMN_ARTIST = "artist"
        const val COLUMN_ALBUM = "album"
        const val COLUMN_LANGUAGE = "language"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val MAX_ENTRIES_PER_BUCKET = 20_000
        const val MAX_WARM_METADATA_ENTRIES_PER_BUCKET = 1_024
        const val MAX_WARM_ARTIST_ALIAS_ENTRIES = 2_048
        const val MAX_BATCH_SIZE = 100
        val COLUMNS = arrayOf(
            COLUMN_KEY,
            COLUMN_TITLE,
            COLUMN_ARTIST,
            COLUMN_ALBUM,
            COLUMN_LANGUAGE,
        )

        enum class CacheBucket(
            val selectionSql: String,
            val warmLimit: Int = MAX_WARM_METADATA_ENTRIES_PER_BUCKET,
        ) {
            SONG("$COLUMN_KEY LIKE '%:SONG:%'"),
            ALBUM("$COLUMN_KEY LIKE '%:ALBUM:%'"),
            ARTIST("$COLUMN_KEY LIKE '%:ARTIST:%'"),
            ARTIST_ALIAS(
                "$COLUMN_KEY LIKE '%:ARTIST_ALIAS:%'",
                MAX_WARM_ARTIST_ALIAS_ENTRIES,
            ),
        }

        fun countRows(db: SQLiteDatabase, bucket: CacheBucket): Int =
            db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_NAME WHERE ${bucket.selectionSql}",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }

        fun trimToBucketSizes(db: SQLiteDatabase) {
            CacheBucket.entries.forEach { bucket ->
                val overflow = countRows(db, bucket) - MAX_ENTRIES_PER_BUCKET
                if (overflow <= 0) return@forEach
                db.execSQL(
                    "DELETE FROM $TABLE_NAME WHERE $COLUMN_KEY IN " +
                        "(SELECT $COLUMN_KEY FROM $TABLE_NAME " +
                        "WHERE ${bucket.selectionSql} " +
                        "ORDER BY $COLUMN_UPDATED_AT ASC LIMIT $overflow)"
                )
            }
        }
    }
}
