/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lidesheng.hyperlyric.lyric.model.interfaces

import com.lidesheng.hyperlyric.lyric.model.LyricMetadata

interface ILyricWord : ILyricTiming {
    var text: String?
    var metadata: LyricMetadata?
}
