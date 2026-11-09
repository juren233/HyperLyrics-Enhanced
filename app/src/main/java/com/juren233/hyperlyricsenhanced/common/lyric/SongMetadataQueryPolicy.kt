/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.lyric

/**
 * 按歌名/歌手查在线曲库时共用的文本规则：拆分多歌手署名、剥掉歌名里的括号注记、生成
 * 按歌名匹配的查询组合。核心在线源（`OnlineLyricTargeter`、`QmSource`、`QqMusicCatalogSongId`）
 * 与 QQ 音乐插件共用这一份；插件仓库里的副本由 `scripts/sync_shared_to_providers.py` 同步，
 * 不要手改。运行时插件优先解析核心里的这个类（核心 R8 保留，见 `proguard-rules.pro`）。
 * 只用 Kotlin 标准库，插件包也能编译。
 */
object SongMetadataQueryPolicy {
    private val artistSeparators = arrayOf("&", ",", "，", "、", "/", "／")

    private val closeToOpen = mapOf(
        ')' to '(',
        ']' to '[',
        '}' to '{',
        '）' to '（',
        '］' to '［',
        '｝' to '｛',
    )
    private val openers = closeToOpen.values.toSet()

    @JvmStatic
    fun splitArtists(value: String): List<String> = value.split(*artistSeparators)

    /**
     * 剥掉半角/全角圆、方、花括号及其包裹内容。不使用正则：剥括号正则
     * `\\(.*?\\)|（.*?）|\\[.*?]|\\{.*?}` 在 Android 的 ICU regex 上编译失败
     * （PatternSyntaxException，真机 210126 实证）而 JVM 单测通过，属于两个
     * regex 实现的方言差异；`\\s+` 等已真机验证过的简单模式不受影响。
     */
    @JvmStatic
    fun stripBracketedAnnotations(value: String): String {
        val builder = StringBuilder(value.length)
        val openStack = ArrayDeque<Char>()
        for (ch in value) {
            when {
                ch in openers -> {
                    // 括号段以空格占位，避免「MILGRAM（注记）コトコ」粘连成单 token
                    if (builder.isEmpty() || builder.last() != ' ') builder.append(' ')
                    openStack.addLast(ch)
                }
                !openStack.isEmpty() && closeToOpen[ch] == openStack.last() -> openStack.removeLast()
                openStack.isEmpty() -> builder.append(ch)
                // 括号内的其余字符（含嵌套开括号后的正文）一律丢弃
            }
        }
        return builder.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * QQ 音乐按歌名匹配（GetPlayLyricInfo）的查询组合，按顺序逐个尝试。接口只认单个歌手、歌名带
     * 版本括号也不命中（2026-11-09 实测：「张紫宁/赵紫骅/游戏科学/8082Audio」无论 `/`、空格、`;`
     * 连接都返回 24001，只传「张紫宁」命中《勿听》509884965；「Question...? (Explicit)」24001，
     * 去括号后命中 378883302）。
     *
     * 顺序：原歌名 + 整串歌手 → 原歌名 + 拆开的单个歌手 → 去括号歌名 + 整串歌手 → 去括号歌名 +
     * 单个歌手，重复组合只查一次。这里只决定「拿什么去查」，候选是否采纳由调用方按会话原始
     * 歌名/歌手核对。
     */
    @JvmStatic
    fun metadataQueries(title: String, artist: String): List<Pair<String, String>> {
        val titles = listOf(title.trim(), stripBracketedAnnotations(title))
            .filter(String::isNotBlank)
            .distinct()
        val artists = (listOf(artist.trim()) + splitArtists(artist).map(String::trim))
            .filter(String::isNotBlank)
            .distinct()
        return titles.flatMap { candidateTitle -> artists.map { candidateTitle to it } }
    }
}
