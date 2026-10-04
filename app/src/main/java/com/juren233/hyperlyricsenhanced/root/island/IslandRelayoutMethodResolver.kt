/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal enum class IslandRelayoutEntry {
    UPDATE_BIG_ISLAND_VIEW_WIDTH,
    CALCULATE_BIG_ISLAND_WIDTH,
}

internal data class ResolvedIslandRelayoutMethod(
    val method: Method,
    val entry: IslandRelayoutEntry,
) {
    val diagnosticSummary: String
        get() = "${method.declaringClass.name}.${method.name}():${method.returnType.name}"
}

/** Resolves only public instance entry points that can actually be invoked with no arguments. */
internal object IslandRelayoutMethodResolver {
    private const val UPDATE_METHOD = "updateBigIslandViewWidth"
    private const val CALCULATE_METHOD = "calculateBigIslandWidth"
    private val cache = IslandRelayoutMethodCache(::resolveUncached)

    fun resolve(hostClass: Class<*>): ResolvedIslandRelayoutMethod? = cache.resolve(hostClass)

    private fun resolveUncached(hostClass: Class<*>): ResolvedIslandRelayoutMethod? {
        val method = hostClass.methods
            .asSequence()
            .filter { candidate ->
                (candidate.name == UPDATE_METHOD || candidate.name == CALCULATE_METHOD) &&
                    candidate.parameterCount == 0 &&
                    !Modifier.isStatic(candidate.modifiers) &&
                    !candidate.isBridge &&
                    !candidate.isSynthetic
            }
            .sortedWith(
                compareBy<Method>(
                    { if (it.name == UPDATE_METHOD) 0 else 1 },
                    { inheritanceDistance(hostClass, it.declaringClass) },
                    { it.declaringClass.name },
                    { it.returnType.name },
                    { it.toGenericString() },
                ),
            )
            .firstOrNull()
            ?: return null
        return ResolvedIslandRelayoutMethod(
            method = method,
            entry = if (method.name == UPDATE_METHOD) {
                IslandRelayoutEntry.UPDATE_BIG_ISLAND_VIEW_WIDTH
            } else {
                IslandRelayoutEntry.CALCULATE_BIG_ISLAND_WIDTH
            },
        )
    }

    private fun inheritanceDistance(hostClass: Class<*>, declaringClass: Class<*>): Int {
        if (hostClass == declaringClass) return 0
        val queue = ArrayDeque<Pair<Class<*>, Int>>()
        val visited = HashSet<Class<*>>()
        queue.add(hostClass to 0)
        while (queue.isNotEmpty()) {
            val (current, distance) = queue.removeFirst()
            if (!visited.add(current)) continue
            if (current == declaringClass) return distance
            current.superclass?.let { queue.add(it to distance + 1) }
            current.interfaces.forEach { queue.add(it to distance + 1) }
        }
        return Int.MAX_VALUE
    }
}

/**
 * A loaded Class has an immutable method set. Cache misses as well as matches: most ancestors
 * visited after a lyric animation are ordinary ViewGroups without an island relayout entry.
 * Class identity also keeps identically named classes from different plugin loaders separate.
 * A failed lookup throws without inserting a result, so a later call can retry.
 */
internal class IslandRelayoutMethodCache(
    private val resolveUncached: (Class<*>) -> ResolvedIslandRelayoutMethod?,
) {
    private class Resolution(val method: ResolvedIslandRelayoutMethod?)

    private val resolutions = ConcurrentHashMap<Class<*>, Resolution>()

    fun resolve(hostClass: Class<*>): ResolvedIslandRelayoutMethod? =
        resolutions.computeIfAbsent(hostClass) { Resolution(resolveUncached(it)) }.method
}
