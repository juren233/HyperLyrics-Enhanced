package com.lidesheng.hyperlyric.root.aitrans

import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.lyric.style.AiTranslationConfigs
import java.util.Locale

internal object AITranslationPrompt {
    private val DEFAULT_PROMPT = AiTranslationConfigs.USER_PROMPT

    fun build(configs: AiTranslationConfigs, song: Song?): String {
        val target = configs.targetLanguage?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().displayLanguage
        val title = song?.name ?: "Unknown Track"
        val artist = song?.artist ?: "Unknown Artist"
        val prompt = configs.prompt.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT

        return AiTranslationConfigs.getPrompt(target, title, artist, prompt)
    }
}


