/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
@file:Suppress("unused")

package com.juren233.hyperlyricsenhanced.lyric.model.extensions

import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.DeepCopyable
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.ILyricTiming
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.Normalize

/**
 * 规范化排序
 */
fun <T : ILyricTiming> List<T>.normalizeSortByTime(): List<T> = sortedBy { it.begin }

/**
 * 深拷贝对象
 */
fun <T : DeepCopyable<T>> List<T>.deepCopy(): List<T> = map { it.deepCopy() }

/**
 * 规范化对象
 */
fun <T : Normalize<T>> List<T>.normalize(): List<T> = map { it.normalize() }
