/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.lidesheng.hyperlyric.lyric.model.interfaces

interface DeepCopyable<T : DeepCopyable<T>> {
    /**
     * 返回当前对象的深拷贝
     */
    fun deepCopy(): T
}
