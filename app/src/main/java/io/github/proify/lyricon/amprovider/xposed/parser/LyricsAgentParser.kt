/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("ReplaceManualRangeWithIndicesCalls")

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.model.LyricAgent

object LyricsAgentParser {

    internal fun parserAgentVector(
        any: Any,
        access: AppleLyricsParserAccess,
    ): MutableList<LyricAgent> {
        val agents = mutableListOf<LyricAgent>()
        val size = access.call(any, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD)
            as? Long ?: 0
        for (i in 0..<size) {
            val agentPtr: Any? = access.call(
                any,
                AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD,
                i,
            )
            val agentNative: Any? = agentPtr?.let {
                access.call(it, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
            }
            val agent = agentNative?.let { parserAgentNative(it, access) }
            agent?.let { agents.add(it) }
        }
        return agents
    }

    private fun parserAgentNative(agentNative: Any, access: AppleLyricsParserAccess): LyricAgent {
        val agent = LyricAgent()
        agent.nameTypes = access.call(
            agentNative,
            AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_NAME_TYPES_METHOD,
        ) as? IntArray ?: intArrayOf()
        agent.type = access.call(
            agentNative,
            AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_TYPE_METHOD,
        ) as? Long ?: 0
        agent.id = access.call(
            agentNative,
            AppleMusicRuntimeMember.LYRICS_NATIVE_AGENT_ID_METHOD,
        ) as? String
        agent.nameTypeNames = LyricAgent.getNameTypesNames(agent.nameTypes)
        agent.typeName = LyricAgent.getType(agent.type)?.name
        return agent
    }
}
