/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.net.Uri
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import kotlin.math.abs

internal fun AppleLyricsSupplementHooks.hookAppleLyricTextGetter(clazz: Class<*>, name: String) {
    val method = runCatching {
        AppleReflection.findMethod(clazz, name, parameterCount = 0)
    }.getOrNull() ?: return
    if (method.returnType != String::class.java || !lyricDisplayTextHookedMethods.add(method)) {
        return
    }
    hookRegistrar.installResultOverrideHook(method) { chain, original ->
        val originalText = original as? String
        if (AppleLyricTextTransform.isRawReadActive()) {
            return@installResultOverrideHook AppleLyricTextTransform.transform(originalText)
                ?: original
        }
        // 补全发音复用 Apple 主句原生 word；只在发音渲染调用栈内替换其显示文本。
        // 这样 word 仍保留原生父 LyricsLine、lineId、wordId 与主句时间轴。
        val scopedPronunciationText = applePronunciationWordRenderContexts.current
            ?.displayText(chain.thisObject)
        if (scopedPronunciationText != null) {
            return@installResultOverrideHook AppleLyricTextTransform.transform(
                scopedPronunciationText
            ) ?: scopedPronunciationText
        }
        when (name) {
            lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD
            ) -> {
                val text = selectAppleLyricsText(
                    official = AppleNativeOnlineTranslationStore.sanitizeContent(originalText),
                    fallback = { onlineTranslationForNativeLine(chain.thisObject) },
                ).text
                val result = AppleLyricTextTransform.transform(text) ?: original
                if (BuildConfig.DEBUG) {
                    ProviderLogger.debug(
                        "[LyricsScrollDiag] getTranslationText: line=${System.identityHashCode(chain.thisObject)}, " +
                            "original=$originalText, online=$text, result=$result"
                    )
                }
                result
            }
            lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD
            ) -> {
                if (shouldHideMandarinPronunciation(lyricObject = chain.thisObject)) {
                    return@installResultOverrideHook ""
                }
                val officialText = RomanizationPolicy.sanitize(
                    originalText = nativeOriginalLineText(chain.thisObject),
                    pronunciation = originalText,
                )
                val selection = selectAppleLyricsText(officialText) {
                    onlinePronunciationForNativeLine(chain.thisObject)
                }
                val onlineText = selection.text.takeIf { selection.fromFallback }
                if (onlineText != null) {
                    reportApplePronunciationRuntimeDiagnostic(
                        stage = "line_text_overlay",
                        details = nativeLineDiagnosticDetails(
                            line = chain.thisObject,
                            pronunciation = onlineText,
                        ),
                    )
                }
                val text = selection.text
                val displayText = ApplePronunciationPolicy.nonNullDisplayText(
                    AppleLyricTextTransform.transform(text)
                )
                logApplePronunciationLineBinding(
                    line = chain.thisObject,
                    originalPronunciation = originalText,
                    officialPronunciation = officialText,
                    onlinePronunciation = onlineText,
                    displayText = displayText,
                )
                displayText
            }
            lyricsRuntimeMember(
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD
            ) -> {
                if (shouldHideMandarinPronunciation(lyricObject = chain.thisObject)) {
                    return@installResultOverrideHook ""
                }
                val text = RomanizationPolicy.sanitize(
                    originalText = nativeOriginalBackgroundLineText(chain.thisObject),
                    pronunciation = originalText,
                )
                ApplePronunciationPolicy.nonNullDisplayText(
                    AppleLyricTextTransform.transform(text)
                )
            }
            else -> AppleLyricTextTransform.transform(originalText) ?: original
        }
    }
    ProviderLogger.debug("Apple Music 歌词文本转换 Hook 已安装: ${clazz.name}#$name")
}

internal fun AppleLyricsSupplementHooks.hookApplePronunciationWordsGetters(clazz: Class<*>) {
    hookApplePronunciationWordsGetter(
        clazz = clazz,
        methodName = lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD
        ),
        parameterCount = 0,
        originalTextGetter = lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD
        ),
        pronunciationTextGetter = lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD
        ),
        mainWordsGetter = lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD
        ),
        onlineFallback = true,
    )
    hookApplePronunciationWordsGetter(
        clazz = clazz,
        methodName = lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD
        ),
        parameterCount = 1,
        originalTextGetter = lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD
        ),
        pronunciationTextGetter = lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD
        ),
        mainWordsGetter = lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD
        ),
        onlineFallback = false,
    )
}

/**
 * Hook Apple 的两个逐词布局构建方法。补全发音传入的是主句原生 word vector，
 * 此处只在该 vector 被消费的调用栈内替换显示文本，退出后立即恢复主句原文。
 */
internal fun AppleLyricsSupplementHooks.hookApplePronunciationWordRendering() {
    val wordVectorClassName = hookResolver.resolveClass(
        AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS
    ).target.className
    hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER)
        .forEach { resolvedClass ->
            val adapterClass = resolvedClass.clazz
            val renderMethods = generateSequence(adapterClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .filter { method ->
                    method.parameterTypes.firstOrNull()?.name == wordVectorClassName &&
                        method.returnType.name == "android.util.ArrayMap"
                }
                .distinctBy { method ->
                    method.name to method.parameterTypes.joinToString { it.name }
                }
                .toList()
            renderMethods.forEach { method ->
                if (!applePronunciationRenderHookedMethods.add(method)) return@forEach
                method.isAccessible = true
                hookRegistrar.installScopedHook(
                    executable = method,
                    enter = enter@{ chain ->
                        val vector = chain.args.firstOrNull() ?: return@enter false
                        val plan = consumeApplePronunciationRenderPlan(vector)
                        if (plan == null) {
                            if (
                                nativeOnlineTranslationStore.hasPronunciation(
                                    currentAppleLyricsSongId
                                ) && nativeVectorSize(vector) > 0
                            ) {
                                reportApplePronunciationRuntimeDiagnostic(
                                    stage = "render_plan_miss",
                                    details = "method=${method.declaringClass.name}#" +
                                        "${method.name}/${method.parameterCount}, " +
                                        "vector=${System.identityHashCode(vector)}, " +
                                        "words=${nativeVectorSize(vector)}, " +
                                        "args=${applePronunciationRenderArgumentSummary(chain.args)}",
                                    dedupeKey = "render_plan_miss:${method.name}:" +
                                        method.parameterCount,
                                )
                            }
                            return@enter false
                        }
                        val context = buildApplePronunciationWordRenderContext(vector, plan)
                            ?: return@enter false
                        reportApplePronunciationRuntimeDiagnostic(
                            stage = "render_plan_consumed",
                            details = "method=${method.declaringClass.name}#" +
                                "${method.name}/${method.parameterCount}, " +
                                "vector=${System.identityHashCode(vector)}, " +
                                "words=${nativeVectorSize(vector)}, " +
                                "pronunciationChars=${plan.pronunciation.length}, " +
                                "args=${applePronunciationRenderArgumentSummary(chain.args)}",
                            dedupeKey = "render_plan_consumed:${method.name}:" +
                                method.parameterCount,
                        )
                        applePronunciationWordRenderContexts.push(context)
                        true
                    },
                    after = { _, _ -> Unit },
                    exit = { applePronunciationWordRenderContexts.pop() },
                )
                ProviderLogger.debug(
                    "Apple Music 发音主句时间轴渲染 Hook 已安装: " +
                        "${method.declaringClass.name}#${method.name}/${method.parameterCount}"
                )
            }
        }
}

private fun AppleLyricsSupplementHooks.hookApplePronunciationWordsGetter(
    clazz: Class<*>,
    methodName: String,
    parameterCount: Int,
    originalTextGetter: String,
    pronunciationTextGetter: String,
    mainWordsGetter: String,
    onlineFallback: Boolean,
) {
    val method = runCatching {
        AppleReflection.findMethod(clazz, methodName, parameterCount = parameterCount)
    }.getOrNull() ?: return
    if (!nativeOnlineTranslationHookedMethods.add(method)) return

    hookRegistrar.installResultOverrideHook(method) { chain, original ->
        if (AppleLyricTextTransform.isRawReadActive()) {
            return@installResultOverrideHook original
        }
        val line = chain.thisObject ?: return@installResultOverrideHook original
        if (shouldHideMandarinPronunciation(lyricObject = line)) {
            return@installResultOverrideHook emptyApplePronunciationWords(
                originalVector = original,
                mainWords = null,
            ) ?: original
        }
        val originalText = nativeRawLineText(line, originalTextGetter)
        val officialPronunciation = RomanizationPolicy.sanitize(
            originalText = originalText,
            pronunciation = nativeRawLineText(line, pronunciationTextGetter),
        )
        val onlinePronunciation = if (onlineFallback) {
            onlinePronunciationForNativeLine(line)
        } else {
            null
        }
        val hasValidOfficialWords = officialPronunciation != null &&
            RomanizationPolicy.sanitize(
                originalText = originalText,
                pronunciation = nativeRawWordVectorText(original),
            ) != null
        val mainWords = runCatching {
            AppleReflection.call(
                line,
                mainWordsGetter,
                *chain.args.toTypedArray(),
            )
        }.getOrNull()
        val hasCompatibleOfficialWords = hasValidOfficialWords &&
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = nativeRenderableWordBegins(mainWords),
                pronunciationWordBegins = nativeRenderableWordBegins(original),
            )
        val mainTimingPronunciation = when {
            officialPronunciation != null && !hasCompatibleOfficialWords -> {
                reportApplePronunciationRuntimeDiagnostic(
                    stage = "official_word_timing_fallback",
                    details = nativeLineDiagnosticDetails(
                        line = line,
                        pronunciation = officialPronunciation,
                    ) + ", method=$methodName/$parameterCount, " +
                        "mainBegins=${nativeRenderableWordBegins(mainWords)}, " +
                        "officialBegins=${nativeRenderableWordBegins(original)}",
                    dedupeKey = "official_word_timing_fallback:$methodName:" +
                        runCatching {
                            lyricsNativeCall(
                                line,
                                AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD,
                            )
                        }
                            .getOrNull(),
                )
                officialPronunciation
            }
            else -> onlinePronunciation
        }
        val wordTrack = ApplePronunciationPolicy.wordTrack(
            hasValidOfficialPronunciation = hasCompatibleOfficialWords,
            hasOnlinePronunciation = mainTimingPronunciation != null,
        )
        val resolvedWords = when (wordTrack) {
            ApplePronunciationWordTrack.OFFICIAL -> original
            ApplePronunciationWordTrack.MAIN_LINE_TIMING -> {
                mainWords?.takeIf { nativeVectorSize(it) > 0 }?.also { vector ->
                    registerApplePronunciationRenderPlan(
                        vector = vector,
                        pronunciation = requireNotNull(mainTimingPronunciation),
                    )
                    reportApplePronunciationRuntimeDiagnostic(
                        stage = "word_track_registered",
                        details = nativeLineDiagnosticDetails(
                            line = line,
                            pronunciation = mainTimingPronunciation,
                        ) + ", method=$methodName/$parameterCount, " +
                            "vector=${System.identityHashCode(vector)}, " +
                            "words=${nativeVectorSize(vector)}, track=$wordTrack",
                        dedupeKey = "word_track_registered:$methodName",
                    )
                } ?: emptyApplePronunciationWords(
                    originalVector = original,
                    mainWords = mainWords,
                ) ?: original
            }
            ApplePronunciationWordTrack.HIDDEN -> emptyApplePronunciationWords(
                originalVector = original,
                mainWords = null,
            ) ?: original
        }
        logApplePronunciationWordBinding(
            line = line,
            methodName = methodName,
            wordTrack = wordTrack,
            originalWords = original,
            resolvedWords = resolvedWords,
            officialPronunciation = officialPronunciation,
            onlinePronunciation = onlinePronunciation,
        )
        resolvedWords
    }
    ProviderLogger.debug(
        "Apple Music 发音逐词轨道 Hook 已安装: ${clazz.name}#$methodName/$parameterCount"
    )
}

internal fun AppleLyricsSupplementHooks.logApplePronunciationDiagnostics(songNative: Any, lines: List<Any>) {
    if (!BuildConfig.DEBUG) return
    val songId = nativeSongId(songNative) ?: return

    val languages = runCatching {
        nativeVectorItems(
            lyricsNativeCall(
                songNative,
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
            ),
            limit = 16,
        ).map(Any::toString).filter(String::isNotBlank)
    }.getOrDefault(emptyList())
    val nativeTextLines = AppleLyricTextTransform.withRawReads {
        lines.count { line ->
            runCatching {
                AppleNativeOnlineTranslationStore.sanitizeContent(
                    lyricsNativeCall(
                        line,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
                    ) as? String
                ) != null
            }.getOrDefault(false)
        }
    }
    val nativeWordLines = AppleLyricTextTransform.withRawReads {
        lines.count { line ->
            runCatching {
                nativeVectorSize(
                    lyricsNativeCall(
                        line,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD,
                    )
                ) > 0
            }.getOrDefault(false)
        }
    }
    val mainWordLines = lines.count { line ->
        runCatching {
            nativeVectorSize(
                lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD)
            ) > 0
        }
            .getOrDefault(false)
    }
    val onlineTextLines = lines.count { onlinePronunciationForNativeLine(it) != null }
    val stateSignature = listOf(
        songId,
        languages.joinToString(","),
        nativeTextLines,
        nativeWordLines,
        onlineTextLines,
        mainWordLines,
    ).joinToString("|")
    if (!applePronunciationDiagnosticsLoggedSongIds.add(stateSignature)) return
    ProviderLogger.diagnostic(
        "Apple pronunciation: id=$songId, languages=$languages, " +
            "nativeTextLines=$nativeTextLines, nativeWordLines=$nativeWordLines, " +
            "onlineTextLines=$onlineTextLines, mainWordLines=$mainWordLines"
    )
    reportApplePronunciationRuntimeDiagnostic(
        stage = "song_snapshot",
        songId = songId,
        details = "languages=$languages, nativeTextLines=$nativeTextLines, " +
            "nativeWordLines=$nativeWordLines, onlineTextLines=$onlineTextLines, " +
            "mainWordLines=$mainWordLines, " +
            "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}, " +
            "hidden=${shouldHideMandarinPronunciation(songId = songId)}",
        dedupeKey = "song_snapshot:$stateSignature",
    )
}

internal fun AppleLyricsSupplementHooks.reportApplePronunciationRuntimeDiagnostic(
    stage: String,
    songId: String? = currentAppleLyricsSongId,
    details: String,
    dedupeKey: String = stage,
) {
    if (!BuildConfig.DEBUG || !runtime.isAttached) return
    val normalizedSongId = songId?.takeIf(String::isNotBlank) ?: "unknown"
    if (!applePronunciationRuntimeDiagnosticKeys.add("$normalizedSongId|$dedupeKey")) {
        return
    }
    val message = "stage=$stage, id=$normalizedSongId, $details"
    Log.i("ApplePronunciationDiag", message)
    runCatching {
        application.contentResolver.call(
            Uri.parse("content://${RootConstants.CLASSIC_AOD_FOCUS_REFRESH_AUTHORITY}"),
            RootConstants.DEBUG_APPLE_PRONUNCIATION_DIAGNOSTIC_METHOD,
            message,
            null,
        )
    }.onFailure {
        ProviderLogger.debug(
            "Apple 发音诊断回传失败: stage=$stage, reason=${it.message}"
        )
    }
}

private fun AppleLyricsSupplementHooks.logApplePronunciationLineBinding(
    line: Any?,
    originalPronunciation: String?,
    officialPronunciation: String?,
    onlinePronunciation: String?,
    displayText: String,
) {
    val context = diagnostics.currentBindingContext() ?: return
    val begin = line?.let {
        runCatching {
            lyricsNativeCall(it, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number
        }
            .getOrNull()
            ?.toLong()
    }
    val source = when {
        officialPronunciation != null -> "official"
        onlinePronunciation != null -> "online"
        else -> "none"
    }
    logApplePronunciationBindingDiagnostic(
        stage = "line_getter",
        context = context,
        details = "begin=$begin, source=$source, " +
            "originalChars=${originalPronunciation.orEmpty().length}, " +
            "officialChars=${officialPronunciation.orEmpty().length}, " +
            "onlineChars=${onlinePronunciation.orEmpty().length}, " +
            "displayChars=${displayText.length}",
        dedupeKey = "line:${context.methodName}:${context.position}:$begin:" +
            "$source:${displayText.length}",
    )
}

private fun AppleLyricsSupplementHooks.logApplePronunciationWordBinding(
    line: Any?,
    methodName: String,
    wordTrack: ApplePronunciationWordTrack,
    originalWords: Any?,
    resolvedWords: Any?,
    officialPronunciation: String?,
    onlinePronunciation: String?,
) {
    val context = diagnostics.currentBindingContext() ?: return
    val begin = line?.let {
        runCatching {
            lyricsNativeCall(it, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number
        }
            .getOrNull()
            ?.toLong()
    }
    val alignment = if (wordTrack == ApplePronunciationWordTrack.OFFICIAL) {
        debugAppleOfficialPronunciationAlignment(
            line = line,
            pronunciationWords = resolvedWords,
        )
    } else {
        "not_official"
    }
    logApplePronunciationBindingDiagnostic(
        stage = "word_getter",
        context = context,
        details = "getter=$methodName, begin=$begin, track=$wordTrack, " +
            "originalWords=${nativeVectorSize(originalWords)}, " +
            "resolvedWords=${nativeVectorSize(resolvedWords)}, " +
            "officialChars=${officialPronunciation.orEmpty().length}, " +
            "onlineChars=${onlinePronunciation.orEmpty().length}, " +
            "alignment=$alignment",
        dedupeKey = "words:${context.methodName}:${context.position}:$begin:" +
            "$methodName:$wordTrack:${nativeVectorSize(resolvedWords)}",
    )
}

private fun AppleLyricsSupplementHooks.debugAppleOfficialPronunciationAlignment(
    line: Any?,
    pronunciationWords: Any?,
): String {
    if (!BuildConfig.DEBUG || line == null) return "unavailable"
    val mainWords = AppleLyricTextTransform.withRawReads {
        runCatching {
            lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD)
        }.getOrNull()
    }
    val main = debugAppleWordTimings(mainWords)
    val pronunciation = debugAppleWordTimings(pronunciationWords)
    val pronunciationBegins = pronunciation.mapTo(hashSetOf()) { it.begin }
    val exactMatches = main.count { it.begin in pronunciationBegins }
    val nearestDeltas = main.map { mainWord ->
        pronunciation.minOfOrNull { pronunciationWord ->
            abs(mainWord.begin - pronunciationWord.begin)
        }
    }
    return "exactBegin=$exactMatches/${main.size}, " +
        "nearestDelta=$nearestDeltas, main=${debugAppleWordTimings(main)}, " +
        "pronunciation=${debugAppleWordTimings(pronunciation)}"
}

private fun AppleLyricsSupplementHooks.debugAppleWordTimings(vector: Any?): List<AppleDebugWordTiming> =
    AppleLyricTextTransform.withRawReads {
        nativeVectorItems(vector, limit = 32).mapNotNull { word ->
            runCatching {
                val begin = (
                    lyricsNativeCall(word, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD)
                        as Number
                    ).toInt()
                val duration = (
                    lyricsNativeCall(word, AppleMusicRuntimeMember.LYRICS_NATIVE_DURATION_METHOD)
                        as Number
                    ).toInt()
                AppleDebugWordTiming(
                    wordId = (
                        lyricsNativeCall(
                            word,
                            AppleMusicRuntimeMember.LYRICS_NATIVE_WORD_ID_METHOD,
                        ) as Number
                        ).toInt(),
                    begin = begin,
                    end = begin + duration,
                    text = (
                        lyricsNativeCall(
                            word,
                            AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD,
                        ) as? String
                        )
                        .orEmpty()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(24),
                )
            }.getOrNull()
        }
    }

private fun AppleLyricsSupplementHooks.debugAppleWordTimings(words: List<AppleDebugWordTiming>): String =
    words.joinToString(prefix = "[", postfix = "]") { word ->
        "${word.wordId}@${word.begin}-${word.end}:${word.text}"
    }

internal fun AppleLyricsSupplementHooks.logApplePronunciationBindingDiagnostic(
    stage: String,
    context: AppleLyricsBindingDiagnosticContext,
    details: String,
    dedupeKey: String,
) {
    if (!BuildConfig.DEBUG) return
    val songId = context.songId?.takeIf(String::isNotBlank) ?: "unknown"
    if (!applePronunciationBindingDiagnosticKeys.add("$songId|$dedupeKey")) return
    Log.i(
        "ApplePronunciationBindDiag",
        "stage=$stage, id=$songId, adapter=${context.adapterClass}@" +
            "${context.adapterIdentity}, method=${context.methodName}, " +
            "position=${context.position}, translation=${context.translationEnabled}, " +
            "pronunciation=${context.pronunciationEnabled}, $details",
    )
}

private fun AppleLyricsSupplementHooks.nativeLineDiagnosticDetails(
    line: Any?,
    pronunciation: String?,
): String {
    val begin = line?.let {
        runCatching {
            lyricsNativeCall(it, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number
        }
            .getOrNull()
            ?.toLong()
    }
    val end = line?.let {
        runCatching {
            lyricsNativeCall(it, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD) as? Number
        }
            .getOrNull()
            ?.toLong()
    }
    return "begin=$begin, end=$end, pronunciationChars=${pronunciation.orEmpty().length}"
}

private fun AppleLyricsSupplementHooks.applePronunciationRenderArgumentSummary(args: List<Any?>): String =
    args.mapIndexedNotNull { index, value ->
        val resourceId = (value as? Number)?.toInt() ?: return@mapIndexedNotNull null
        val resourceName = runCatching {
            application.resources.getResourceEntryName(resourceId)
        }.getOrNull()
        "$index=$resourceId${resourceName?.let { ":$it" }.orEmpty()}"
    }.joinToString(prefix = "[", postfix = "]")

/**
 * 创建空发音向量，用于彻底隐藏发音或在主句没有原生 word 时安全降级。
 * 该路径只创建容器，绝不创建缺少父 LyricsLine 的 LyricsWord。
 */
private fun AppleLyricsSupplementHooks.emptyApplePronunciationWords(
    originalVector: Any?,
    mainWords: Any?,
): Any? {
    val vectorClass = originalVector?.javaClass ?: mainWords?.javaClass ?: return null
    return runCatching {
        AppleReflection.newInstance(vectorClass)
    }.onFailure {
        ProviderLogger.error("Apple Music 空发音向量构建失败", it)
    }.getOrNull()
}

/** 登记一次性的发音渲染计划；对应 vector 被 Apple 消费后立即移除。 */
private fun AppleLyricsSupplementHooks.registerApplePronunciationRenderPlan(
    vector: Any,
    pronunciation: String,
) {
    synchronized(pendingApplePronunciationRenderPlans) {
        if (pendingApplePronunciationRenderPlans.size >= 256) {
            pendingApplePronunciationRenderPlans.clear()
        }
        pendingApplePronunciationRenderPlans[vector] =
            ApplePronunciationRenderPlan(pronunciation)
    }
}

private fun AppleLyricsSupplementHooks.consumeApplePronunciationRenderPlan(vector: Any): ApplePronunciationRenderPlan? =
    synchronized(pendingApplePronunciationRenderPlans) {
        pendingApplePronunciationRenderPlans.remove(vector)
    }

internal fun AppleLyricsSupplementHooks.clearPendingApplePronunciationRenderPlans() {
    synchronized(pendingApplePronunciationRenderPlans) {
        pendingApplePronunciationRenderPlans.clear()
    }
}

/**
 * 为 Apple 主句原生 word 生成仅在当前渲染栈生效的发音文本映射。
 * 发音片段沿用每个主句 word 的原生时间，不创建额外 native 对象。
 */
private fun AppleLyricsSupplementHooks.buildApplePronunciationWordRenderContext(
    vector: Any,
    plan: ApplePronunciationRenderPlan,
): ApplePronunciationWordRenderContext? {
    val words = nativeVectorItems(vector, limit = 256)
    val contentWords = words.filterNot { word ->
        runCatching {
            lyricsNativeCall(
                word,
                AppleMusicRuntimeMember.LYRICS_NATIVE_WHITESPACE_METHOD,
            ) as? Boolean
        }.getOrNull() == true
    }
    val mainWordTexts = AppleLyricTextTransform.withRawReads {
        contentWords.map { word ->
            runCatching {
                lyricsNativeCall(word, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
                    as? String
            }.getOrNull().orEmpty()
        }
    }
    val segments = ApplePronunciationPolicy.displaySegments(
        pronunciation = plan.pronunciation,
        mainWordTexts = mainWordTexts,
    )
    if (segments.isEmpty()) return null
    reportApplePronunciationRuntimeDiagnostic(
        stage = "word_segments_mapped",
        details = "mainWords=${mainWordTexts.joinToString(prefix = "[", postfix = "]")}, " +
            "segments=${segments.joinToString(prefix = "[", postfix = "]")}",
        dedupeKey = "word_segments_mapped:${mainWordTexts.joinToString("|")}:" +
            plan.pronunciation,
    )

    val lastVisibleSegment = segments.indexOfLast(String::isNotEmpty)
    val displayTextByWord = LinkedHashMap<ApplePronunciationWordKey, String>(words.size)
    words.forEach { word ->
        lyricsWordKey(word)?.let { key -> displayTextByWord[key] = "" }
    }
    contentWords.forEachIndexed { index, word ->
        val key = lyricsWordKey(word) ?: return@forEachIndexed
        val segment = segments[index]
        displayTextByWord[key] = when {
            segment.isEmpty() -> ""
            index < lastVisibleSegment -> "$segment "
            else -> segment
        }
    }
    return ApplePronunciationWordRenderContext(
        displayTextByWord = displayTextByWord,
        wordIdMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_WORD_ID_METHOD),
        beginMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD),
        endMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD),
    )
}

private fun AppleLyricsSupplementHooks.lyricsWordKey(word: Any?): ApplePronunciationWordKey? = applePronunciationWordKey(
    word = word,
    wordIdMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_WORD_ID_METHOD),
    beginMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD),
    endMethod = lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD),
)

internal fun AppleLyricsSupplementHooks.hasValidOfficialRomanization(songNative: Any?): Boolean {
    if (songNative == null) return false
    val pronunciationLanguages = nativePronunciationLanguages(songNative)
    rememberApplePronunciationLanguages(songNative, pronunciationLanguages)
    if (
        shouldHideMandarinPronunciation(
            songId = nativeSongId(songNative),
            pronunciationLanguages = pronunciationLanguages,
        )
    ) return false
    val sections = runCatching {
        lyricsNativeCall(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD)
    }
        .getOrNull() ?: return false
    return nativeVectorItems(sections, limit = 8).any { section ->
        val lines = runCatching {
            lyricsNativeCall(section, AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD)
        }
            .getOrNull()
        nativeVectorItems(lines, limit = 64).any { line ->
            val pronunciation = AppleLyricTextTransform.withRawReads {
                runCatching {
                    lyricsNativeCall(
                        line,
                        AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
                    ) as? String
                }.getOrNull()
            }
            RomanizationPolicy.sanitize(
                originalText = nativeOriginalLineText(line),
                pronunciation = pronunciation,
            ) != null
        }
    }
}

internal fun AppleLyricsSupplementHooks.rememberApplePronunciationLanguages(
    songNative: Any?,
    pronunciationLanguages: Collection<String> = nativePronunciationLanguages(songNative),
) {
    val songId = nativeSongId(songNative) ?: return
    val normalized = pronunciationLanguages
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    if (normalized.isNotEmpty()) {
        applePronunciationLanguagesBySongId[songId] = normalized
    }
}
