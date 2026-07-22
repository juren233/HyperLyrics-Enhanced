/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("ReplaceManualRangeWithIndicesCalls")

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.model.LyricAgent

object LyricsAgentParser {

    fun parserAgentVector(any: Any): MutableList<LyricAgent> {
        val agents = mutableListOf<LyricAgent>()
        val size = callMethod(any, "size") as? Long ?: 0
        for (i in 0..<size) {
            val agentPtr: Any? = callMethod(any, "get", i)
            val agentNative: Any? = agentPtr?.let { callMethod(it, "get") }
            val agent = agentNative?.let { parserAgentNative(it) }
            agent?.let { agents.add(it) }
        }
        return agents
    }

    private fun parserAgentNative(agentNative: Any): LyricAgent {
        val agent = LyricAgent()
        agent.nameTypes = callMethod(agentNative, "getNameTypes_") as? IntArray ?: intArrayOf()
        agent.type = callMethod(agentNative, "getType_") as? Long ?: 0
        agent.id = callMethod(agentNative, "getId") as? String
        agent.nameTypeNames = LyricAgent.getNameTypesNames(agent.nameTypes)
        agent.typeName = LyricAgent.getType(agent.type)?.name
        return agent
    }
}