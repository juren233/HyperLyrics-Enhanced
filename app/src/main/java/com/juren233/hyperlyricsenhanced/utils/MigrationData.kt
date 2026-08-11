package com.juren233.hyperlyricsenhanced.utils

data class MigrationItem(
    val text: String,
    val summary: String? = null,
    val url: String? = null
)

data class MigrationNote(
    val versionCode: Int,
    val scope: MigrationScope,
    val items: List<MigrationItem>
)

sealed interface MigrationScope {
    /** Applies from the note's build threshold through every patch in this major/minor line. */
    data class MinorVersion(
        val major: Int,
        val minor: Int,
    ) : MigrationScope

    /** Preserves the original exact-build behavior for historical notices only. */
    data object LegacyExactBuild : MigrationScope
}

object MigrationData {
    val notes = listOf(
        MigrationNote(
            versionCode = 140000,
            scope = MigrationScope.MinorVersion(major = 7, minor = 4),
            items = listOf(
                MigrationItem(
                    text = "Lyricon歌词源已升级至插件体系",
                    summary = "\n现在 HyperLyrics Enhanced 已将 Lyricon 的词幕服务模块与主流音乐平台的 Provider 模块整合在一起，作为插件功能，实现无需额外模块，还能满足各取所需的需求。\n\n更多详情请在“歌词设置”-“Lyricon配置”查看。",
                ),
            ),
        ),
        MigrationNote(
            versionCode = 100001,
            scope = MigrationScope.LegacyExactBuild,
            items = listOf(
                MigrationItem(
                    text = "更新后请重启系统界面和音乐 App"
                )
            )
        ),
        MigrationNote(
            versionCode = 1934,
            scope = MigrationScope.LegacyExactBuild,
            items = listOf(
                MigrationItem(
                    text = "本次更新请重启系统界面",
                    summary = "重启手机也行~"
                )
            )
        ),
        MigrationNote(
            versionCode = 1933,
            scope = MigrationScope.LegacyExactBuild,
            items = listOf(
                MigrationItem(
                    text = "再一次温馨提示，HyperLyrics Enhanced v6.0 往后需要额外安装 lyricon central 才可继续使用 lyricon 歌词源",
                    summary = "点我跳转下载 lyricon central 模块",
                    url = "https://github.com/tomakino/lyricon/releases/tag/core"
                )
            )
        ),
        MigrationNote(
            versionCode = 1932,
            scope = MigrationScope.LegacyExactBuild,
            items = listOf(
                MigrationItem(
                    text = "本次更新和xposed模块功能无关，但是使用无 root 模式的请注意",
                    summary = "新版本大幅更改了“通知型灵动岛歌词”的歌词数据来源，默认选择 metadata（自动）即可。当然，你也可以选择指定的歌词源"
                )
            )
        ),
        MigrationNote(
            versionCode = 1931,
            scope = MigrationScope.LegacyExactBuild,
            items = listOf(
                MigrationItem(
                    text = "HyperLyrics Enhanced v6.0 往后，需要Lyricon central才可继续使用Lyricon 歌词源",
                    summary = "点击跳转下载 Lyricon central 模块",
                    url = "https://github.com/tomakino/lyricon/releases/tag/core"
                )
            )
        )
    )

    /**
     * Returns the newest migration-note group crossed since the user last confirmed a notice.
     *
     * A minor-version notice uses [MigrationNote.versionCode] as its minimum build and stays
     * applicable throughout that major/minor release line. Moving to the next minor version is
     * the exclusive upper bound. Only the newest applicable group is returned so superseded
     * historical notices are not shown together with current guidance.
     */
    internal fun notesForUpgrade(
        lastSeenVersionCode: Long,
        currentVersionCode: Long,
        currentVersionName: String,
    ): List<MigrationNote> {
        if (currentVersionCode <= lastSeenVersionCode) return emptyList()

        val currentMinorVersion = parseMinorVersion(currentVersionName)
        val applicableNotes = notes.filter { note ->
            val noteVersionCode = note.versionCode.toLong()
            noteVersionCode > lastSeenVersionCode && when (val scope = note.scope) {
                is MigrationScope.MinorVersion ->
                    noteVersionCode <= currentVersionCode && scope == currentMinorVersion
                MigrationScope.LegacyExactBuild -> noteVersionCode == currentVersionCode
            }
        }
        val newestApplicableVersion = applicableNotes
            .maxOfOrNull { it.versionCode }
            ?: return emptyList()

        return applicableNotes.filter { it.versionCode == newestApplicableVersion }
    }

    private fun parseMinorVersion(versionName: String): MigrationScope.MinorVersion? {
        val parts = versionName.trim()
            .removePrefix("v")
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
        if (parts.size < 2) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        return MigrationScope.MinorVersion(major = major, minor = minor)
    }
}
