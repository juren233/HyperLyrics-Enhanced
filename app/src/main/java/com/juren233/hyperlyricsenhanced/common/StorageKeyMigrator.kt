package com.juren233.hyperlyricsenhanced.common

import android.content.Context
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
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

    /**
     * 旧「逐行转逐字」开关是布尔键，现已改为三选一的整数键（不开启 / 仅 TTML 源 / 所有来源）。
     * 旧值为开启时对应「仅 TTML 源」，为关闭时沿用默认的「不开启」，因此不写入。
     */
    private const val LEGACY_LINE_TO_WORD_LYRICS = "key_hook_apple_music_upgrade_line_to_word_lyrics"

    /**
     * 2026-11-19 之前，网易云、QQ 音乐在「启用App」里的开关存在「更多App」名单里（在名单内即开启），
     * 现改为各自的开关键，与 Spotify、汽水一致。名单里的这两个包迁成开关键为开，并从名单移除。
     */
    private val listBackedSwitchPackages = setOf(
        OnlineTranslationSourcePreferences.NETEASE_PACKAGE,
        OnlineTranslationSourcePreferences.QQ_MUSIC_PACKAGE,
    )

    private fun listBackedSwitchKey(packageName: String): String =
        requireNotNull(OnlineTranslationSourcePreferences.appPreferenceKey(packageName)) {
            "缺少开关键: $packageName"
        }

    internal data class ListBackedSwitchMigration(
        val packages: Set<String>,
        val enabledKeys: Set<String>,
    )

    /** 名单里有要迁出的包时，返回迁移后的名单与要置为开启的开关键；没有则为 null。 */
    internal fun listBackedSwitchMigration(packages: Set<String>): ListBackedSwitchMigration? {
        val moved = packages.filter(listBackedSwitchPackages::contains)
        if (moved.isEmpty()) return null
        return ListBackedSwitchMigration(
            packages = packages - moved.toSet(),
            enabledKeys = moved.map(::listBackedSwitchKey).toSet(),
        )
    }

    /**
     * 旧键 → 由它迁移出的键；逐行转逐字旧布尔键既是 LunaBeat 旧键的目标，也是模式键的来源。
     * 「更多App」名单本身不是旧键，但旧备份里的它代表网易云、QQ 音乐开关当时的状态（不在名单即关闭）。
     */
    private val legacyKeyTargets: Map<String, List<String>> =
        renamedBooleanKeys.associate { (legacyKey, currentKey) -> legacyKey to listOf(currentKey) } +
            mapOf(
                LEGACY_LUNABEAT_WORD_LYRICS to listOf(
                    RootConstants.KEY_HOOK_ONLINE_SOURCE_LUNABEAT,
                    LEGACY_LINE_TO_WORD_LYRICS,
                ),
                LEGACY_LINE_TO_WORD_LYRICS to listOf(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE,
                ),
                MediaSessionOnlineFallbackPreferences.PACKAGES to
                    listBackedSwitchPackages.map(::listBackedSwitchKey),
            )

    /**
     * 恢复备份时要先清掉的设备现有键：备份里的旧键代表备份当时的设置，它迁移出的键若不在备份里，
     * 设备上现有的值会让「已存在的键保持不动」丢弃备份的值。沿迁移链传递
     * （LunaBeat 旧键 → 逐行转逐字旧布尔键 → 模式键）；备份里自带的键不清。
     */
    internal fun keysSupersededByRestoredLegacy(restoredKeys: Set<String>): Set<String> {
        val superseded = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(restoredKeys.filter(legacyKeyTargets::containsKey))
        while (pending.isNotEmpty()) {
            val legacyKey = pending.removeFirst()
            if (!visited.add(legacyKey)) continue
            legacyKeyTargets[legacyKey].orEmpty().forEach { target ->
                if (target in restoredKeys) return@forEach
                superseded += target
                if (target in legacyKeyTargets) pending += target
            }
        }
        return superseded
    }

    /** 旧值要写入哪些键（逐行转逐字以旧布尔键为中间形态）：已存在的键保持不动。 */
    internal fun lunaBeatWordLyricsWrites(
        legacyValue: Boolean?,
        existingKeys: Set<String>,
    ): Map<String, Boolean> {
        legacyValue ?: return emptyMap()
        return listOf(
            RootConstants.KEY_HOOK_ONLINE_SOURCE_LUNABEAT,
            LEGACY_LINE_TO_WORD_LYRICS,
        ).filterNot(existingKeys::contains).associateWith { legacyValue }
    }

    /** 旧布尔值要写入的新模式；新键已存在则保持不动，旧值为关闭或缺失时无需写入。 */
    internal fun lineToWordModeWrite(legacyValue: Boolean?, existingKeys: Set<String>): Int? {
        if (legacyValue != true) return null
        if (RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE in existingKeys) return null
        return RootConstants.APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE_TTML_ONLY
    }

    /**
     * 先处理 LunaBeat 旧键（其拆分结果里的逐行转逐字值仍以旧布尔键为中间形态），再把旧布尔键换算成模式键。
     */
    private fun migrateLunaBeatAndLineToWord(
        prefs: android.content.SharedPreferences,
        edits: android.content.SharedPreferences.Editor,
    ): Boolean {
        var dirty = false
        var pendingLegacyLineToWord: Boolean? = prefs.all[LEGACY_LINE_TO_WORD_LYRICS] as? Boolean
        val legacyLunaBeat = prefs.all[LEGACY_LUNABEAT_WORD_LYRICS] as? Boolean
        if (legacyLunaBeat != null) {
            lunaBeatWordLyricsWrites(legacyLunaBeat, prefs.all.keys).forEach { (currentKey, value) ->
                if (currentKey == LEGACY_LINE_TO_WORD_LYRICS) {
                    pendingLegacyLineToWord = value
                } else {
                    edits.putBoolean(currentKey, value)
                }
                LogManager.i(TAG, "migrated key=$LEGACY_LUNABEAT_WORD_LYRICS -> $currentKey value=$value")
            }
            edits.remove(LEGACY_LUNABEAT_WORD_LYRICS)
            dirty = true
        }
        if (prefs.all.containsKey(LEGACY_LINE_TO_WORD_LYRICS) || pendingLegacyLineToWord != null) {
            lineToWordModeWrite(pendingLegacyLineToWord, prefs.all.keys)?.let { mode ->
                edits.putInt(RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE, mode)
                LogManager.i(
                    TAG,
                    "migrated key=$LEGACY_LINE_TO_WORD_LYRICS -> " +
                        "${RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_SOURCE_MODE} value=$mode"
                )
            }
            edits.remove(LEGACY_LINE_TO_WORD_LYRICS)
            dirty = true
        }
        return dirty
    }

    fun migrateLegacyKeys(context: Context) {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREF_NAME, Context.MODE_PRIVATE)
        val edits = prefs.edit()
        var dirty = migrateLunaBeatAndLineToWord(prefs, edits)
        renamedBooleanKeys.forEach { (legacyKey, currentKey) ->
            val legacyValue = prefs.all[legacyKey] as? Boolean ?: return@forEach
            if (!prefs.contains(currentKey)) {
                edits.putBoolean(currentKey, legacyValue)
                LogManager.i(TAG, "migrated key=$legacyKey -> $currentKey value=$legacyValue")
            }
            edits.remove(legacyKey)
            dirty = true
        }
        // 旧版恢复备份把集合型偏好写成了字符串，SystemUI 读取时会抛类型转换异常。
        StringSetPreferences.KEYS.forEach { key ->
            val raw = prefs.all[key] as? String ?: return@forEach
            edits.putStringSet(key, StringSetPreferences.parse(raw))
            LogManager.i(TAG, "migrated string-typed set key=$key")
            dirty = true
        }
        // 在上面的字符串修正之后写入，同一个 Editor 里后写的值生效。
        StringSetPreferences.read(prefs, MediaSessionOnlineFallbackPreferences.PACKAGES, null)
            ?.let(::listBackedSwitchMigration)
            ?.let { migration ->
                migration.enabledKeys.forEach { key ->
                    edits.putBoolean(key, true)
                    LogManager.i(TAG, "migrated ${MediaSessionOnlineFallbackPreferences.PACKAGES} -> $key value=true")
                }
                edits.putStringSet(MediaSessionOnlineFallbackPreferences.PACKAGES, migration.packages)
                dirty = true
            }
        // 旧版恢复备份把整数值的浮点偏好（如 1.0）写成了 Int，SystemUI 的 getFloat 会抛类型转换异常。
        FloatPreferences.KEYS.forEach { key ->
            val raw = prefs.all[key]
            if (raw == null || raw is Float) return@forEach
            val fixed = (raw as? Number)?.toFloat() ?: (raw as? String)?.toFloatOrNull()
            if (fixed != null) edits.putFloat(key, fixed) else edits.remove(key)
            LogManager.i(TAG, "migrated non-float key=$key type=${raw.javaClass.simpleName}")
            dirty = true
        }
        if (dirty) edits.apply()
    }
}
