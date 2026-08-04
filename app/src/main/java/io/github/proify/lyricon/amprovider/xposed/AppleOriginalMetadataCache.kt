package io.github.proify.lyricon.amprovider.xposed

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Independent persistent cache for original-region song, album, and artist metadata. */
internal class AppleOriginalMetadataCache(
    context: Context,
    private val mainHandler: Handler,
) {
    private val helper = DatabaseHelper(context.applicationContext)
    private val artistRegionPreferences = context.applicationContext.getSharedPreferences(
        ARTIST_REGION_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val artistRegionLock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HLE-AppleOriginalMetadataCache").apply { isDaemon = true }
    }
    private val memoryCache = object :
        LinkedHashMap<String, AppleInternalCatalogResolver.Alias>(256, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<
                String,
                AppleInternalCatalogResolver.Alias,
                >?,
        ): Boolean = size > MAX_ENTRIES
    }
    private val warmLock = Any()
    private var warmed = false
    private var warming = false
    private val warmCallbacks = mutableListOf<(Int?) -> Unit>()
    @Volatile
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun get(
        key: String,
        onResult: (AppleInternalCatalogResolver.Alias?) -> Unit,
    ) {
        val normalizedKey = key.trim()
        if (!enabled || normalizedKey.isEmpty()) {
            onResult(null)
            return
        }
        synchronized(memoryCache) {
            memoryCache[normalizedKey]
        }?.let { cached ->
            onResult(cached)
            return
        }
        executor.execute {
            val alias = if (enabled) {
                synchronized(memoryCache) {
                    memoryCache[normalizedKey]
                } ?: runCatching { read(normalizedKey) }
                    .onFailure { ProviderLogger.error("Apple 原地区元数据缓存读取失败", it) }
                    .getOrNull()
                    ?.also { remember(normalizedKey, it) }
            } else {
                null
            }
            mainHandler.post { onResult(alias.takeIf { enabled }) }
        }
    }

    /**
     * Reads the first acceptable alias from a bounded set of equivalent cache keys in one
     * executor turn. The caller uses this for direct IDs plus compatibility keys, so a miss does
     * not fan out into one SQLite task per legacy key.
     */
    fun getFirst(
        keys: Collection<String>,
        accept: (AppleInternalCatalogResolver.Alias) -> Boolean = { true },
        onResult: (CacheHit?) -> Unit,
    ) {
        val normalizedKeys = keys.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (!enabled || normalizedKeys.isEmpty()) {
            onResult(null)
            return
        }
        synchronized(memoryCache) {
            normalizedKeys.firstNotNullOfOrNull { key ->
                memoryCache[key]
                    ?.takeIf(accept)
                    ?.let { alias -> CacheHit(key, alias) }
            }
        }?.let { hit ->
            onResult(hit)
            return
        }
        executor.execute {
            val hit = if (enabled) {
                runCatching { readFirst(normalizedKeys, accept) }
                    .onFailure { ProviderLogger.error("Apple 原地区元数据兼容缓存读取失败", it) }
                    .getOrNull()
            } else {
                null
            }
            hit?.let { remember(it.key, it.alias) }
            mainHandler.post { onResult(hit.takeIf { enabled }) }
        }
    }

    /**
     * 只读取已预热或已访问的内存项，不在当前线程触发 SQLite I/O。
     */
    fun cached(key: String): AppleInternalCatalogResolver.Alias? {
        val normalizedKey = key.trim()
        if (!enabled || normalizedKey.isEmpty()) return null
        return synchronized(memoryCache) { memoryCache[normalizedKey] }
    }

    fun warmRecentAsync(onResult: (Int?) -> Unit) {
        if (!enabled) {
            onResult(null)
            return
        }
        val startWarm = synchronized(warmLock) {
            when {
                warmed -> {
                    mainHandler.post {
                        onResult(
                            synchronized(memoryCache) { memoryCache.size }
                                .takeIf { enabled }
                        )
                    }
                    false
                }
                warming -> {
                    warmCallbacks += onResult
                    false
                }
                else -> {
                    warming = true
                    warmCallbacks += onResult
                    true
                }
            }
        }
        if (!startWarm) return
        executor.execute {
            val aliases = if (enabled) {
                runCatching { readRecent() }
                    .onFailure { ProviderLogger.error("Apple 原地区元数据缓存预热失败", it) }
                    .getOrNull()
            } else {
                null
            }
            if (aliases != null) {
                synchronized(memoryCache) { memoryCache.putAll(aliases) }
            }
            val callbacks = synchronized(warmLock) {
                warmed = aliases != null
                warming = false
                warmCallbacks.toList().also { warmCallbacks.clear() }
            }
            mainHandler.post {
                val count = aliases?.size?.takeIf { enabled }
                callbacks.forEach { callback -> callback(count) }
            }
        }
    }

    fun put(key: String, alias: AppleInternalCatalogResolver.Alias) {
        val normalizedKey = key.trim()
        if (!enabled || normalizedKey.isEmpty()) return
        val canonicalAlias = AppleInternalCatalogResolver.canonicalCachedOriginalAlias(alias)
            ?: return
        remember(normalizedKey, canonicalAlias)
        executor.execute {
            if (!enabled) return@execute
            runCatching { write(normalizedKey, canonicalAlias) }
                .onFailure { ProviderLogger.error("Apple 原地区元数据缓存写入失败", it) }
        }
    }

    fun remove(key: String) {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return
        synchronized(memoryCache) { memoryCache.remove(normalizedKey) }
        executor.execute {
            runCatching {
                helper.writableDatabase.delete(
                    TABLE_NAME,
                    "$COLUMN_KEY = ?",
                    arrayOf(normalizedKey),
                )
            }.onFailure { ProviderLogger.error("Apple 原地区元数据旧缓存清理失败", it) }
        }
    }

    fun cachedArtistRegion(keys: Collection<String>): String? =
        synchronized(artistRegionLock) {
            val normalizedKeys = keys.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
            var resolvedLanguage: String? = null
            var editor: android.content.SharedPreferences.Editor? = null
            normalizedKeys.forEach { key ->
                val storedLanguage = artistRegionPreferences.getString(key, null)
                    ?.substringBefore(ARTIST_REGION_VALUE_SEPARATOR)
                    ?.takeIf(String::isNotBlank)
                    ?: return@forEach
                val supportedLanguage = AppleInternalCatalogResolver
                    .supportedOriginalLanguageOrNull(storedLanguage)
                if (supportedLanguage == null) {
                    editor = (editor ?: artistRegionPreferences.edit()).remove(key)
                } else if (resolvedLanguage == null) {
                    resolvedLanguage = supportedLanguage
                }
            }
            editor?.apply()
            resolvedLanguage
        }

    fun rememberArtistRegion(keys: Collection<String>, language: String) {
        val normalizedLanguage = AppleInternalCatalogResolver
            .supportedOriginalLanguageOrNull(language)
            ?: return
        val normalizedKeys = keys.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (normalizedKeys.isEmpty()) return
        synchronized(artistRegionLock) {
            val existing = artistRegionPreferences.all
            val incoming = normalizedKeys.toSet()
            val newKeyCount = normalizedKeys.count { it !in existing }
            val overflow = existing.size + newKeyCount - MAX_ARTIST_REGION_ENTRIES
            val editor = artistRegionPreferences.edit()
            if (overflow > 0) {
                existing.asSequence()
                    .filterNot { (key, _) -> key in incoming }
                    .sortedBy { (_, value) ->
                        value?.toString()
                            ?.substringAfter(ARTIST_REGION_VALUE_SEPARATOR, "0")
                            ?.toLongOrNull() ?: 0L
                    }
                    .take(overflow)
                    .forEach { (key, _) -> editor.remove(key) }
            }
            val value = "$normalizedLanguage$ARTIST_REGION_VALUE_SEPARATOR${System.currentTimeMillis()}"
            normalizedKeys.forEach { key -> editor.putString(key, value) }
            editor.apply()
        }
    }

    private fun remember(
        key: String,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        synchronized(memoryCache) { memoryCache[key] = alias }
    }

    private fun readRecent(): Map<String, AppleInternalCatalogResolver.Alias> {
        val aliases = linkedMapOf<String, AppleInternalCatalogResolver.Alias>()
        helper.readableDatabase.query(
            TABLE_NAME,
            WARM_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_UPDATED_AT DESC",
            MAX_ENTRIES.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.stringColumn(COLUMN_KEY)
                val alias = cursor.toAlias()
                AppleInternalCatalogResolver.canonicalCachedOriginalAlias(alias)
                    ?.let { aliases[key] = it }
            }
        }
        return aliases
    }

    private fun read(key: String): AppleInternalCatalogResolver.Alias? {
        val alias = helper.readableDatabase.query(
            TABLE_NAME,
            COLUMNS,
            "$COLUMN_KEY = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.toAlias()
        }
        val canonicalAlias = alias?.let(AppleInternalCatalogResolver::canonicalCachedOriginalAlias)
        if (alias != null && canonicalAlias == null) {
            helper.writableDatabase.delete(
                TABLE_NAME,
                "$COLUMN_KEY = ?",
                arrayOf(key),
            )
        } else if (alias != null && canonicalAlias != alias) {
            write(key, requireNotNull(canonicalAlias))
        }
        return canonicalAlias
    }

    private fun readFirst(
        keys: List<String>,
        accept: (AppleInternalCatalogResolver.Alias) -> Boolean,
    ): CacheHit? {
        val placeholders = keys.joinToString(",") { "?" }
        val aliases = linkedMapOf<String, AppleInternalCatalogResolver.Alias>()
        helper.readableDatabase.query(
            TABLE_NAME,
            WARM_COLUMNS,
            "$COLUMN_KEY IN ($placeholders)",
            keys.toTypedArray(),
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.stringColumn(COLUMN_KEY)
                val alias = cursor.toAlias()
                AppleInternalCatalogResolver.canonicalCachedOriginalAlias(alias)
                    ?.let { aliases[key] = it }
            }
        }
        return keys.firstNotNullOfOrNull { key ->
            aliases[key]
                ?.takeIf(accept)
                ?.let { alias -> CacheHit(key, alias) }
        }
    }

    private fun write(key: String, alias: AppleInternalCatalogResolver.Alias) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
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
            val overflow = countRows(db) - MAX_ENTRIES
            if (overflow > 0) {
                db.execSQL(
                    "DELETE FROM $TABLE_NAME WHERE $COLUMN_KEY IN " +
                        "(SELECT $COLUMN_KEY FROM $TABLE_NAME " +
                        "ORDER BY $COLUMN_UPDATED_AT ASC LIMIT $overflow)"
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.toAlias() = AppleInternalCatalogResolver.Alias(
        title = stringColumn(COLUMN_TITLE),
        artist = stringColumn(COLUMN_ARTIST),
        album = stringColumn(COLUMN_ALBUM),
        language = stringColumn(COLUMN_LANGUAGE),
    )

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
                "CREATE INDEX IF NOT EXISTS idx_hle_apple_original_metadata_updated " +
                    "ON $TABLE_NAME($COLUMN_UPDATED_AT)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    internal companion object {
        const val MAX_ENTRIES = 20_000
        private const val MAX_ARTIST_REGION_ENTRIES = 5_000
        private const val DATABASE_NAME = "hyperlyricsenhanced_apple_original_metadata.db"
        private const val DATABASE_VERSION = 3
        private const val TABLE_NAME = "original_metadata"
        private const val COLUMN_KEY = "cache_key"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_ARTIST = "artist"
        private const val COLUMN_ALBUM = "album"
        private const val COLUMN_LANGUAGE = "language"
        private const val COLUMN_UPDATED_AT = "updated_at"
        private const val ARTIST_REGION_PREFERENCES =
            "hyperlyricsenhanced_apple_original_artist_regions_v3"
        private const val ARTIST_REGION_VALUE_SEPARATOR = "|"
        private val COLUMNS = arrayOf(
            COLUMN_TITLE,
            COLUMN_ARTIST,
            COLUMN_ALBUM,
            COLUMN_LANGUAGE,
        )
        private val WARM_COLUMNS = arrayOf(COLUMN_KEY, *COLUMNS)

        private fun countRows(db: SQLiteDatabase): Int =
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
    }

    data class CacheHit(
        val key: String,
        val alias: AppleInternalCatalogResolver.Alias,
    )
}
