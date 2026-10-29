package com.juren233.hyperlyricsenhanced.common

import android.content.Context
import com.juren233.hyperlyricsenhanced.utils.LogManager

/**
 * 一次性存储键迁移：超级岛相关落盘键统一为 HyperIsland 官方命名，Apple Music 繁转简开关的落盘键
 * 随功能范围（歌词 → 内容）改名。
 *
 * 仅迁移本地 SharedPreferences；LSPosed 远程偏好副本由 [com.juren233.hyperlyricsenhanced.root.RootApplication]
 * 在 XposedService 绑定后的全量 syncAllPreferences 推送覆盖，无需在此处理。
 * 迁移幂等：旧键不存在时不产生任何写入；新键已存在时仅清理旧键，不回写覆盖。
 */
object StorageKeyMigrator {
    private const val TAG = "StorageKeyMigrator"

    // Legacy persisted keys must keep their original spelling for upgrades.
    private val renamedBooleanKeys = listOf(
        "key_hook_enable_super_island" to RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND,
        "key_feature_entry_super_island" to UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND,
        // 该开关已由「繁体歌词」扩展为「繁体内容」（含歌名、歌手名与专辑名），落盘键同步改名。
        "key_hook_apple_music_simplify_traditional_lyrics" to
            RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_CONTENT,
    )

    /**
     * 旧「使用开源仓库歌词源对部分逐行歌词转换为逐字歌词」开关同时承担两层含义：LunaBeat 源启用，
     * 以及逐行转逐字。两者现已拆成独立键，旧值同时写入两个新键，保证升级后行为不变。
     */
    private const val LEGACY_LUNABEAT_WORD_LYRICS = "key_hook_apple_music_lunabeat_word_lyrics"

    /** 旧值要写入哪些新键：已存在的新键保持不动。 */
    internal fun lunaBeatWordLyricsWrites(
        legacyValue: Boolean?,
        existingKeys: Set<String>,
    ): Map<String, Boolean> {
        legacyValue ?: return emptyMap()
        return listOf(
            RootConstants.KEY_HOOK_ONLINE_SOURCE_LUNABEAT,
            RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_LYRICS,
        ).filterNot(existingKeys::contains).associateWith { legacyValue }
    }

    private fun migrateLunaBeatWordLyrics(
        prefs: android.content.SharedPreferences,
        edits: android.content.SharedPreferences.Editor,
    ): Boolean {
        val legacyValue = prefs.all[LEGACY_LUNABEAT_WORD_LYRICS] as? Boolean ?: return false
        lunaBeatWordLyricsWrites(legacyValue, prefs.all.keys).forEach { (currentKey, value) ->
            edits.putBoolean(currentKey, value)
            LogManager.i(TAG, "migrated key=$LEGACY_LUNABEAT_WORD_LYRICS -> $currentKey value=$value")
        }
        edits.remove(LEGACY_LUNABEAT_WORD_LYRICS)
        return true
    }

    fun migrateLegacyKeys(context: Context) {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREF_NAME, Context.MODE_PRIVATE)
        val edits = prefs.edit()
        var dirty = migrateLunaBeatWordLyrics(prefs, edits)
        renamedBooleanKeys.forEach { (legacyKey, currentKey) ->
            val legacyValue = prefs.all[legacyKey] as? Boolean ?: return@forEach
            if (!prefs.contains(currentKey)) {
                edits.putBoolean(currentKey, legacyValue)
                LogManager.i(TAG, "migrated key=$legacyKey -> $currentKey value=$legacyValue")
            }
            edits.remove(legacyKey)
            dirty = true
        }
        if (dirty) edits.apply()
    }
}
