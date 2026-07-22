/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.amprovider.xposed.parser

object StringVectorParser {

    fun parserStringVectorNative(any: Any): MutableList<String> {
        val size = callMethod(any, "size") as Long
        return (0 until size.toInt()).map { i ->
            callMethod(any, "get", i) as String
        }.toMutableList()
    }
}