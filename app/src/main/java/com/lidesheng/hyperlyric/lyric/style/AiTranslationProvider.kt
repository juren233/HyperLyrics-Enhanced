package com.lidesheng.hyperlyric.lyric.style

enum class AiTranslationProvider(val provider: String, val model: String, val url: String) {
    OPENAI(
        "xiaomimimo",
        "mimo-v2-flash",
        "https://api.xiaomimimo.com/v1"
    ),
}

