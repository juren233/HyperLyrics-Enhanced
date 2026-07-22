/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.util

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import io.github.proify.lyricon.amprovider.xposed.model.LyricAgent
import io.github.proify.lyricon.amprovider.xposed.model.LyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf

fun AppleSong.toSong(): Song = AppleSongMapper.map(this)

object AppleSongMapper {

    fun map(song: AppleSong): Song {
        return Song(
            id = song.adamId,
            name = song.name,
            artist = song.artist,
            duration = song.duration.toLong(),
            lyrics = convertLyrics(song.lyrics, song.agents)
        )
    }

    private fun convertLyrics(
        appleLyrics: List<LyricLine>,
        agents: List<LyricAgent>
    ): MutableList<RichLyricLine> {
        val lineDirections = computeLineDirections(appleLyrics, agents)
        val groupAgentIds = agents
            .filter { LyricAgent.getType(it.type) == LyricAgent.Type.GROUP }
            .mapNotNull(LyricAgent::id)
            .toSet()
        logLineDirections(agents, appleLyrics, lineDirections)

        return appleLyrics.mapIndexed { index, appleLine ->
            RichLyricLine().apply {

                begin = appleLine.begin.toLong()
                end = appleLine.end.toLong()
                duration = appleLine.duration.toLong()

                text = appleLine.htmlLineText
                words = appleLine.words.map { it.toLyricWord() }.toMutableList()

                secondary = appleLine.backgroundVocalsText()
                secondaryWords = appleLine.backgroundWords
                    .map { it.toLyricWord() }
                    .toMutableList()

                translation = appleLine.htmlTranslationLineText
                val metadataEntries = buildList {
                    if (appleLine.agent in groupAgentIds) {
                        add(LyricMetadataKeys.GROUP_VOCALS to "true")
                    }
                    appleLine.htmlTranslatedBackgroundVocalsLineText
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            add(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to it)
                        }
                }
                if (metadataEntries.isNotEmpty()) {
                    metadata = lyricMetadataOf(*metadataEntries.toTypedArray())
                }
//                translationWords = listOf(
//                    LyricWord(
//                        begin = begin,
//                        end = end,
//                        duration = duration,
//                        text = appleLine.htmlTranslationLineText
//                    )
//                )

                isAlignedRight = lineDirections[index] == LyricDirection.RIGHT
            }
        }.toMutableList()
    }

    private fun io.github.proify.lyricon.amprovider.xposed.model.LyricWord.toLyricWord(): LyricWord =
        LyricWord(
            text = this.text,
            begin = this.begin.toLong(),
            duration = this.duration.toLong(),
            end = this.end.toLong()
        )

    private fun LyricLine.backgroundVocalsText(): String? =
        htmlBackgroundVocalsLineText
            ?.takeIf { it.isNotBlank() }
            ?: backgroundWords
                .joinToString("") { it.text.orEmpty() }
                .takeIf { it.isNotBlank() }

    private fun computeLineDirections(
        appleLyrics: List<LyricLine>,
        agents: List<LyricAgent>
    ): List<LyricDirection> {
        val agentTypes = agents
            .mapNotNull { agent -> agent.id?.let { it to LyricAgent.getType(agent.type) } }
            .toMap()
        val duetAgentCount = agentTypes.values.count { type ->
            type == LyricAgent.Type.PERSON || type == LyricAgent.Type.OTHER
        }
        if (duetAgentCount <= 1) return List(appleLyrics.size) { LyricDirection.DEFAULT }

        val resolvedLines = mutableListOf<DirectedLine>()
        appleLyrics.forEachIndexed { index, line ->
            val agentId = line.agent.orEmpty()
            val type = agentTypes[agentId]
            val direction = resolveLineDirection(
                appleLyrics = appleLyrics,
                resolvedLines = resolvedLines,
                agentId = agentId,
                agentType = type,
                lineIndex = index
            )
            resolvedLines += DirectedLine(agentId, direction)
        }
        return resolvedLines.map(DirectedLine::direction)
    }

    private fun resolveLineDirection(
        appleLyrics: List<LyricLine>,
        resolvedLines: List<DirectedLine>,
        agentId: String,
        agentType: LyricAgent.Type?,
        lineIndex: Int
    ): LyricDirection {
        if (agentId.isEmpty()) return LyricDirection.DEFAULT
        if (lineIndex <= 0) {
            return when (agentType) {
                LyricAgent.Type.PERSON -> LyricDirection.LEFT
                LyricAgent.Type.OTHER -> LyricDirection.RIGHT
                else -> LyricDirection.DEFAULT
            }
        }
        if (agentType == LyricAgent.Type.GROUP) return LyricDirection.DEFAULT

        val previous = resolvedLines.getOrNull(lineIndex - 1)
        if (previous == null || previous.direction == LyricDirection.DEFAULT) {
            return resolveLineDirection(
                appleLyrics = appleLyrics,
                resolvedLines = resolvedLines,
                agentId = agentId,
                agentType = agentType,
                lineIndex = lineIndex - 1
            )
        }
        if (agentId == previous.agentId) return previous.direction

        return directionAfterAgentChange(
            previousDirection = previous.direction,
            previousTextIsRtl = isAppleRtlText(appleLyrics[lineIndex - 1].htmlLineText),
            currentTextIsRtl = isAppleRtlText(appleLyrics[lineIndex].htmlLineText)
        )
    }

    private fun directionAfterAgentChange(
        previousDirection: LyricDirection,
        previousTextIsRtl: Boolean,
        currentTextIsRtl: Boolean
    ): LyricDirection {
        val useLeft = if (previousDirection == LyricDirection.LEFT) {
            previousTextIsRtl != currentTextIsRtl
        } else {
            previousTextIsRtl == currentTextIsRtl
        }
        return if (useLeft) LyricDirection.LEFT else LyricDirection.RIGHT
    }

    private fun isAppleRtlText(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        for (index in text.indices) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetter(codePoint)) {
                return text[index].code in APPLE_RTL_FIRST_LETTER_RANGE
            }
        }
        return false
    }

    private fun logLineDirections(
        agents: List<LyricAgent>,
        appleLyrics: List<LyricLine>,
        directions: List<LyricDirection>
    ) {
        val agentTypes = agents.associate { it.id to LyricAgent.getType(it.type) }
        val declaredAgents = agents.joinToString(prefix = "[", postfix = "]") { agent ->
            "${agent.id.orEmpty()}:${agent.typeName ?: agent.type}"
        }
        val duetAgentCount = agents.count { agent ->
            val type = LyricAgent.getType(agent.type)
            type == LyricAgent.Type.PERSON || type == LyricAgent.Type.OTHER
        }
        ProviderLogger.diagnostic(
            "AppleSongMapper: declaredAgents=$declaredAgents, " +
                "duetLayout=${duetAgentCount > 1}, lineCount=${appleLyrics.size}"
        )
        appleLyrics.forEachIndexed { index, line ->
            val agentId = line.agent.orEmpty()
            ProviderLogger.diagnostic(
                "AppleSongMapper: line[$index] agent=$agentId, " +
                    "type=${agentTypes[agentId]}, direction=${directions[index].logName}, " +
                    "text=${line.htmlLineText.orEmpty().take(DIAGNOSTIC_TEXT_LIMIT)}"
            )
        }
    }

    private data class DirectedLine(
        val agentId: String,
        val direction: LyricDirection
    )

    private enum class LyricDirection(val logName: String) {
        DEFAULT("default"),
        LEFT("left"),
        RIGHT("right")
    }

    private val APPLE_RTL_FIRST_LETTER_RANGE = 1424..1791
    private const val DIAGNOSTIC_TEXT_LIMIT = 80
}
