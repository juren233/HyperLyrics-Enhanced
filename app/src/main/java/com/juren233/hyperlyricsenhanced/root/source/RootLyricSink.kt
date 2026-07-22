package com.juren233.hyperlyricsenhanced.root.source

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.IslandSlotContentAssembler
import com.juren233.hyperlyricsenhanced.root.island.renderer.IslandRenderer
import com.juren233.hyperlyricsenhanced.root.aitrans.AITranslator
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RootLyricSink(
    private val renderer: IslandRenderer,
    private val prefs: SharedPreferences? = null
) : LyricSink {

    private val aiTransScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeAiTranslationJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPositionDispatchTimeMs = 0L
    private var pendingPosition: Long? = null
    private var positionDispatchScheduled = false
    private var playbackActive = false
    private var lastReceivedPosition = Long.MIN_VALUE
    private var lastDispatchedPosition = Long.MIN_VALUE
    private val positionDispatchRunnable = Runnable {
        positionDispatchScheduled = false
        val latest = pendingPosition ?: return@Runnable
        pendingPosition = null
        dispatchPosition(latest)
    }

    private companion object {
        const val MIN_POSITION_DISPATCH_INTERVAL_MS = 33L
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    }

    override fun onSongChanged(song: Any?) {
        handleSongChanged(song, onlineTranslationMatched = false)
    }

    override fun onOnlineTranslationMatched(song: Any?) {
        handleSongChanged(song, onlineTranslationMatched = true)
    }

    override fun onOnlineTranslationUnavailable(song: Any?) {
        val nativeSong = song as? Song ?: return
        val currentSong = LyriconDataBridge.currentSong ?: return
        if (!isSameSong(currentSong, nativeSong)) return
        val currentPrefs = prefs ?: return
        val aiEnabled = currentPrefs.getBoolean(
            RootConstants.KEY_HOOK_AI_TRANS_ENABLE,
            RootConstants.DEFAULT_HOOK_AI_TRANS_ENABLE
        )
        if (aiEnabled) startAiTranslation(currentSong, currentPrefs)
    }

    private fun handleSongChanged(song: Any?, onlineTranslationMatched: Boolean) {
        cancelPendingPositionDispatch()
        lastReceivedPosition = Long.MIN_VALUE
        lastDispatchedPosition = Long.MIN_VALUE
        activeAiTranslationJob?.cancel()
        activeAiTranslationJob = null

        if (song is Song && prefs != null) {
            val aiEnabled = prefs.getBoolean(
                RootConstants.KEY_HOOK_AI_TRANS_ENABLE,
                RootConstants.DEFAULT_HOOK_AI_TRANS_ENABLE
            )
            if (aiEnabled) {
                if (onlineTranslationMatched) {
                    val hasMissingTranslation = song.lyrics?.any {
                        it.translation.isNullOrBlank()
                    } == true
                    if (hasMissingTranslation) {
                        startAiTranslation(song, prefs, preserveExistingTranslations = true)
                    }
                } else if (shouldDeferAiForOnlineTranslation(song, prefs)) {
                    if (BuildConfig.DEBUG) {
                        HookLogger.d(
                            "RootLyricSink",
                            "Apple Music 在线歌词翻译优先，延后 AI 翻译: song=${song.name}"
                        )
                    }
                } else {
                    startAiTranslation(song, prefs)
                }
            }
        }
    }

    override fun onLyricLine(line: Any?) {
        if (line is IRichLyricLine) {
    
            LyriconDataBridge.updateLyricLine(line)
            renderer.updateLyricLine()
        }
    }

    override fun onPlainText(text: String?) {

        LyriconDataBridge.updateLyric(text)
        renderer.updateLyricLine()
    }

    override fun onStop() {
        activeAiTranslationJob?.cancel()
        activeAiTranslationJob = null
        playbackActive = false
        cancelPendingPositionDispatch()
        lastReceivedPosition = Long.MIN_VALUE
        lastDispatchedPosition = Long.MIN_VALUE
        renderer.clearAllViews()
        LyriconDataBridge.clearState()
    }

    override fun onMetadata(title: String?, artist: String?, album: String?, publisher: String?) {
        if (title != null) LyriconDataBridge.currentSongName = title
        if (!publisher.isNullOrEmpty()) {
            LyriconDataBridge.updateLyricPackage(publisher)
        }
        IslandSlotContentAssembler.invalidate()
        renderer.refreshActiveIsland()
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        playbackActive = isPlaying
        if (!isPlaying) cancelPendingPositionDispatch()
        renderer.onPlaybackStateChanged(isPlaying)
    }

    override fun onPositionChanged(position: Long) {
        if (position == lastReceivedPosition) return
        lastReceivedPosition = position
        val lyricChanged = LyriconDataBridge.updatePosition(position)
        if (lyricChanged) {
            renderer.updateLyricLine()
        }
        if (playbackActive) {
            dispatchPositionThrottled(position)
        } else {
            dispatchPosition(position)
        }
    }

    override fun onSeekTo(position: Long) {
        cancelPendingPositionDispatch()
        lastReceivedPosition = position
        lastDispatchedPosition = position
        lastPositionDispatchTimeMs = SystemClock.uptimeMillis()

        if (LyriconDataBridge.updatePosition(position)) {
            renderer.updateLyricLine()
        }
        renderer.seekTo(position)
    }

    private fun dispatchPositionThrottled(position: Long) {
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastPositionDispatchTimeMs
        if (elapsed >= MIN_POSITION_DISPATCH_INTERVAL_MS) {
            dispatchPosition(position, now)
            return
        }

        pendingPosition = position
        if (positionDispatchScheduled) return

        positionDispatchScheduled = true
        mainHandler.postDelayed(positionDispatchRunnable, MIN_POSITION_DISPATCH_INTERVAL_MS - elapsed)
    }

    private fun dispatchPosition(position: Long, now: Long = SystemClock.uptimeMillis()) {
        if (position == lastDispatchedPosition) return
        lastPositionDispatchTimeMs = now
        lastDispatchedPosition = position
        pendingPosition = null
        renderer.updatePosition(position)
    }

    private fun cancelPendingPositionDispatch() {
        mainHandler.removeCallbacks(positionDispatchRunnable)
        pendingPosition = null
        positionDispatchScheduled = false
    }

    private fun startAiTranslation(
        song: Song,
        prefs: SharedPreferences,
        preserveExistingTranslations: Boolean = false
    ) {
        val configs = buildAiTranslationConfigs(prefs)
        val autoIgnoreChinese = prefs.getBoolean(
            RootConstants.KEY_HOOK_AI_TRANS_AUTO_IGNORE_CHINESE,
            RootConstants.DEFAULT_HOOK_AI_TRANS_AUTO_IGNORE_CHINESE
        )
        val version = LyriconDataBridge.versionCounter.get()

        activeAiTranslationJob = aiTransScope.launch {
            try {
                val ratio = song.calculateChineseRatio()
                val percentage = String.format(java.util.Locale.US, "%.1f%%", ratio * 100)
                if (autoIgnoreChinese) {
                    HookLogger.d("RootLyricSink", "歌曲 ${song.name}（中文占比 $percentage)")
                }
                if (autoIgnoreChinese && ratio > 0.5f) {
                    HookLogger.d("RootLyricSink", "歌曲 ${song.name}（中文占比 $percentage），已自动跳过AI翻译")
                    return@launch
                }
                val skipExisting = prefs.getBoolean(
                    RootConstants.KEY_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION,
                    RootConstants.DEFAULT_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION
                )
                val configuredForceOverride = prefs.getBoolean(
                    RootConstants.KEY_HOOK_AI_TRANS_FORCE_OVERRIDE,
                    RootConstants.DEFAULT_HOOK_AI_TRANS_FORCE_OVERRIDE
                )
                val forceOverride = configuredForceOverride && !preserveExistingTranslations
                if (skipExisting && !forceOverride) {
                    val hasTranslation = song.lyrics?.any { !it.translation.isNullOrBlank() } == true
                    if (hasTranslation) {
                        HookLogger.d("RootLyricSink", "歌曲 ${song.name} 已有翻译，跳过AI翻译")
                        return@launch
                    }
                }
                if (song.lyrics.isNullOrEmpty()) {
                    HookLogger.d("RootLyricSink", "歌曲 ${song.name} 无歌词，跳过AI翻译")
                    return@launch
                }
                val translatedSong = AITranslator.translateSongSync(
                    song = song,
                    configs = configs,
                    forceOverride = forceOverride
                )

                if (version != LyriconDataBridge.versionCounter.get()) {
                    return@launch
                }

                if (translatedSong !== song && translatedSong.lyrics != null) {
                    LyriconDataBridge.applyTranslation(translatedSong)
                    LyriconDataBridge.onAiTranslationComplete?.invoke()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun shouldDeferAiForOnlineTranslation(
        song: Song,
        prefs: SharedPreferences
    ): Boolean {
        if (LyriconDataBridge.currentLyricPackageName != APPLE_MUSIC_PACKAGE) return false
        if (song.lyrics.isNullOrEmpty()) return false
        if (song.lyrics?.any { !it.translation.isNullOrBlank() } == true) return false
        return prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION
        )
    }

    private fun isSameSong(first: Song, second: Song): Boolean {
        val firstId = first.id?.takeIf { it.isNotBlank() }
        val secondId = second.id?.takeIf { it.isNotBlank() }
        if (firstId != null && secondId != null) return firstId == secondId
        return first.name?.trim()?.equals(second.name?.trim(), ignoreCase = true) == true &&
            first.artist?.trim()?.equals(second.artist?.trim(), ignoreCase = true) == true
    }

    private fun buildAiTranslationConfigs(prefs: SharedPreferences): AiTranslationConfigs {
        val providerName = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_PROVIDER, AiTranslationProvider.OPENAI.name)
            ?: AiTranslationProvider.OPENAI.name
        val provider = try { AiTranslationProvider.valueOf(providerName) } catch (_: Exception) { AiTranslationProvider.OPENAI }

        return AiTranslationConfigs(
            provider = providerName,
            targetLanguage = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_TARGET_LANG, RootConstants.DEFAULT_HOOK_AI_TRANS_TARGET_LANG) ?: RootConstants.DEFAULT_HOOK_AI_TRANS_TARGET_LANG,
            apiKey = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_API_KEY, "") ?: "",
            model = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_MODEL, RootConstants.DEFAULT_HOOK_AI_TRANS_MODEL).orEmpty().ifBlank { provider.model },
            baseUrl = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_BASE_URL, RootConstants.DEFAULT_HOOK_AI_TRANS_BASE_URL).orEmpty().ifBlank { provider.url },
            prompt = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_PROMPT, RootConstants.DEFAULT_HOOK_AI_TRANS_PROMPT) ?: RootConstants.DEFAULT_HOOK_AI_TRANS_PROMPT,
            temperature = prefs.getFloat(RootConstants.KEY_HOOK_AI_TRANS_TEMPERATURE, AiTranslationConfigs.DEFAULT_TEMPERATURE),
            topP = prefs.getFloat(RootConstants.KEY_HOOK_AI_TRANS_TOP_P, AiTranslationConfigs.DEFAULT_TOP_P),
            maxTokens = prefs.getInt(RootConstants.KEY_HOOK_AI_TRANS_MAX_TOKENS, AiTranslationConfigs.DEFAULT_MAX_TOKENS)
        )
    }

    private fun Song.calculateChineseRatio(): Float {
        val totalChars = lyrics?.flatMap { it.text.orEmpty().toList() }
            ?.filterNot { it.isWhitespace() || it.isPunctuation() } ?: return 1.0f
        if (totalChars.isEmpty()) return 1.0f

        val chineseHanCount = totalChars.count { it.isChineseHan() }
        return chineseHanCount.toFloat() / totalChars.size
    }

    private fun Char.isChineseHan(): Boolean {
        return try {
            Character.UnicodeScript.of(this.code) == Character.UnicodeScript.HAN
        } catch (_: Exception) {
            val ub = Character.UnicodeBlock.of(this)
            ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                    ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
        }
    }

    private fun Char.isPunctuation(): Boolean {
        return !isLetterOrDigit() && !isWhitespace()
    }
}
