package com.juren233.hyperlyricsenhanced.root.aitrans

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.juren233.hyperlyricsenhanced.common.extensions.json
import com.juren233.hyperlyricsenhanced.common.extensions.toJson
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Memory + SQLite cache for completed song-level AI translations. */
internal class AITranslationCache(
    private val maxCacheSize: Int,
    private val generation: AtomicInteger,
    private val scope: CoroutineScope
) {
    private companion object {
        const val TAG = "AITranslationCache"
    }

    private val dbMutex = Mutex()
    private var dbHelper: DatabaseHelper? = null

    private val memory: MutableMap<String, List<TranslationItem>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, List<TranslationItem>>(maxCacheSize, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TranslationItem>>?): Boolean {
                    return size > maxCacheSize
                }
            }
        )

    fun init(context: Context) {
        if (dbHelper == null) {
            synchronized(this) {
                if (dbHelper == null) {
                    HookLogger.d(TAG, "初始化翻译缓存数据库")
                    dbHelper = DatabaseHelper(context.applicationContext)
                }
            }
        }
    }

    fun getFromMemory(key: String): List<TranslationItem>? = memory[key]

    fun putMemory(key: String, items: List<TranslationItem>) {
        memory[key] = items
    }

    suspend fun getFromDb(key: String): List<TranslationItem>? = dbMutex.withLock {
        val db = dbHelper?.readableDatabase ?: return null
        return runCatching {
            db.query(
                DatabaseHelper.TABLE_NAME,
                arrayOf(DatabaseHelper.COLUMN_DATA),
                "${DatabaseHelper.COLUMN_ID} = ?",
                arrayOf(key),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val jsonData =
                        cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_DATA))
                    json.decodeFromString<List<TranslationItem>>(jsonData)
                } else null
            }
        }.getOrElse {
            HookLogger.e(TAG, "查询翻译缓存失败", it)
            null
        }
    }

    suspend fun saveToDb(key: String, items: List<TranslationItem>) {
        val jsonData = items.toJson()
        dbMutex.withLock {
            val db = dbHelper?.writableDatabase ?: return@withLock
            runCatching {
                val values = ContentValues().apply {
                    put(DatabaseHelper.COLUMN_ID, key)
                    put(DatabaseHelper.COLUMN_DATA, jsonData)
                    put(DatabaseHelper.COLUMN_TIMESTAMP, System.currentTimeMillis())
                }
                db.insertWithOnConflict(
                    DatabaseHelper.TABLE_NAME,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }.onFailure {
                HookLogger.e(TAG, "写入翻译缓存失败", it)
            }
        }
    }

    fun clear(callback: () -> Unit) {
        generation.incrementAndGet()
        memory.clear()
        scope.launch(Dispatchers.IO) {
            dbMutex.withLock {
                runCatching {
                    dbHelper?.writableDatabase?.delete(DatabaseHelper.TABLE_NAME, null, null)
                    HookLogger.d(TAG, "翻译缓存数据库已清空")
                    withContext(Dispatchers.Main) { callback() }
                }.onFailure {
                    HookLogger.e(TAG, "清空翻译缓存数据库失败", it)
                }
            }
        }
    }

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        companion object {
            const val DATABASE_NAME = "hyperlyricsenhanced_translation.db"
            const val DATABASE_VERSION = 1
            const val TABLE_NAME = "ai_cache"
            const val COLUMN_ID = "song_id"
            const val COLUMN_DATA = "translation_json"
            const val COLUMN_TIMESTAMP = "created_at"
        }

        override fun onCreate(db: SQLiteDatabase) {
            HookLogger.i(TAG, "创建翻译缓存数据表")
            db.execSQL(
                """
                CREATE TABLE $TABLE_NAME (
                    $COLUMN_ID TEXT PRIMARY KEY,
                    $COLUMN_DATA TEXT,
                    $COLUMN_TIMESTAMP INTEGER
                )
            """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON $TABLE_NAME($COLUMN_TIMESTAMP)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            HookLogger.w(TAG, "升级翻译缓存数据库: old=$oldVersion, new=$newVersion, action=recreate")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }
}
