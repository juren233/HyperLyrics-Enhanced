/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleContentLocalizationHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleDebugNetworkHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.ApplePlaybackHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.FunctionalAppleMusicHookModule
import io.github.proify.lyricon.amprovider.xposed.lyrics.AppleOnlineSourceMenuHooks
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalReentryGuard
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import android.content.SharedPreferences

internal fun AppleLyricsSupplementHooks.ensureAppleLyricTextHooks(songNative: Any) {
    val songId = nativeSongId(songNative)
    val pronunciationLanguages = nativePronunciationLanguages(songNative)
    rememberApplePronunciationLanguages(songNative, pronunciationLanguages)
    ensureAppleNativeOnlineTranslationHooks(songNative)
    val sections = runCatching {
        lyricsNativeCall(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD)
    }.getOrNull() ?: return
    val lines = nativeVectorItems(sections, limit = 8).flatMap { section ->
        val lineVector = runCatching {
            lyricsNativeCall(section, AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD)
        }.getOrNull()
        nativeVectorItems(lineVector, limit = 16)
    }
    if (lines.isEmpty()) return
    val pronunciationContext = ApplePronunciationContext(
        songId = songId,
        pronunciationLanguages = pronunciationLanguages,
    )
    lines.forEach { line ->
        applePronunciationContextByLyricObject[line] = pronunciationContext
    }
    logApplePronunciationDiagnostics(songNative, lines)

    val textGetterNames = listOf(
        lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD),
        lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD),
        lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD),
        lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD),
        lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATED_BACKGROUND_TEXT_METHOD
        ),
        lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD
        ),
    )
    lines.map(Any::javaClass).distinct().forEach { lineClass ->
        textGetterNames.forEach { name -> hookAppleLyricTextGetter(lineClass, name) }
        hookApplePronunciationWordsGetters(lineClass)
    }

    val words = lines.flatMap { line ->
        buildList {
            runCatching {
                lyricsNativeCall(line, AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD)
            }
                .getOrNull()
                ?.let { addAll(nativeVectorItems(it, limit = 8)) }
            val backgroundWords = runCatching {
                lyricsNativeCall(
                    line,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD,
                    false,
                )
            }.recoverCatching {
                lyricsNativeCall(
                    line,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD,
                )
            }.getOrNull()
            backgroundWords?.let { addAll(nativeVectorItems(it, limit = 8)) }
        }
    }
    words.map(Any::javaClass).distinct().forEach { wordClass ->
        hookAppleLyricTextGetter(
            wordClass,
            lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD),
        )
    }
}

internal val loggedTranslationAvailabilityKeys =
    java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

internal fun AppleLyricsSupplementHooks.ensureAppleNativeOnlineTranslationHooks(songNative: Any) {
    val translationAvailabilityChecks = mapOf<String, (String?) -> Boolean>(
        lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD
        ) to { songId: String? -> hasAnyOnlineTranslation(songId) },
        lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_TRANSLATION_METHOD
        ) to { songId: String? -> hasAnyOnlineTranslation(songId) },
    )
    translationAvailabilityChecks.forEach { (name, hasOnlineContent) ->
        val method = runCatching {
            AppleReflection.findMethod(songNative.javaClass, name, parameterCount = 1)
        }.getOrNull() ?: return@forEach
        if (
            method.returnType != Boolean::class.javaPrimitiveType ||
            !nativeOnlineTranslationHookedMethods.add(method)
        ) return@forEach

        // Apple Music 6.5.0 的歌词页会先用系统语言调用 setTranslation/hasTranslation，
        // 但官方对象可能只提供带地区的标签（例如 zh-Hans-CN）。把这两个调用统一
        // 映射到对象真实存在的官方标签，避免译文已加载却被 G2 可见性检查隐藏。
        hookRegistrar.installArgumentRewriteHook(method) { chain ->
            if (appleOfficialTranslationProbeGuard.isActive) {
                return@installArgumentRewriteHook null
            }
            val requestedLanguage = chain.args.firstOrNull() as? String
                ?: return@installArgumentRewriteHook null
            val selectedLanguage = selectAppleOfficialTranslationArgument(
                songNative = chain.thisObject,
                requestedLanguage = requestedLanguage,
            ) ?: return@installArgumentRewriteHook null
            if (selectedLanguage == requestedLanguage) {
                null
            } else {
                ProviderLogger.debug(
                    "Apple 官方翻译语言参数兼容映射：method=$name, " +
                        "requested=$requestedLanguage, selected=$selectedLanguage"
                )
                arrayOf(selectedLanguage)
            }
        }

        hookRegistrar.installResultOverrideHook(method) { chain, original ->
            if (appleOfficialTranslationProbeGuard.isActive) {
                return@installResultOverrideHook original
            }
            val songId = nativeSongId(chain.thisObject)
            val resolved = original == true || hasOnlineContent(songId)
            if (BuildConfig.DEBUG) {
                logTranslationAvailabilityOverride(
                    method = name,
                    songId = songId,
                    original = original == true,
                    nativeStore = isNativeOnlineTranslationEnabled() &&
                        nativeOnlineTranslationStore.hasTranslation(songId),
                    supplement = missingLyricsSupplement().hasTranslation(songId),
                    lunaBeat = missingLyricsSupplement().hasLunaBeatSource(songId),
                    resolved = resolved,
                    songNative = chain.thisObject,
                )
            }
            resolved
        }
        ProviderLogger.debug(
            "Apple Music 原生在线翻译可用性 Hook 已安装: " +
                "${method.declaringClass.name}#$name"
        )
    }
    listOf(
        lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD
        ),
        lyricsRuntimeMember(
            AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_PRONUNCIATION_METHOD
        ),
    ).forEach { name ->
        val method = runCatching {
            AppleReflection.findMethod(songNative.javaClass, name, parameterCount = 1)
        }.getOrNull() ?: return@forEach
        if (
            method.returnType != Boolean::class.javaPrimitiveType ||
            !nativeOnlineTranslationHookedMethods.add(method)
        ) return@forEach

        hookRegistrar.installResultOverrideHook(method) { chain, original ->
            val songId = nativeSongId(chain.thisObject)
            val requestedLanguage = chain.args.firstOrNull() as? String
            if (
                shouldHideMandarinPronunciation(
                    songId = songId,
                    pronunciationLanguages = listOfNotNull(requestedLanguage),
                )
            ) {
                return@installResultOverrideHook false
            }
            val hasOnlineRomanization = isNativeOnlineTranslationEnabled() &&
                nativeOnlineTranslationStore.hasPronunciation(songId)
            val resolved = hasOnlineRomanization ||
                (original == true && hasValidOfficialRomanization(chain.thisObject))
            reportApplePronunciationRuntimeDiagnostic(
                stage = "availability_$name",
                songId = songId,
                details = "requested=$requestedLanguage, original=$original, " +
                    "online=$hasOnlineRomanization, resolved=$resolved, " +
                    "selected=${PreferencesMonitor.isPronunciationSelected()}",
                dedupeKey = "availability_$name:$requestedLanguage:$original:$resolved",
            )
            resolved
        }
        ProviderLogger.debug(
            "Apple Music 罗马音可用性 Hook 已安装: " +
                "${method.declaringClass.name}#$name"
        )
    }
}

/**
 * 根据 SongInfo 当前真实提供的官方语言标签，修正 Apple 歌词页传入的系统语言参数。
 * 这里只返回官方轨道标签，不把三方缓存伪装成 Apple 官方可用性。
 */
internal fun AppleLyricsSupplementHooks.selectAppleOfficialTranslationArgument(
    songNative: Any?,
    requestedLanguage: String,
): String? {
    songNative ?: return null
    val availableLanguages = nativeVectorStrings(
        runCatching {
            lyricsNativeCall(
                songNative,
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD,
            )
        }.getOrNull()
    )
    return selectAppleLyricsTranslationLanguage(
        systemLanguage = requestedLanguage,
        availableLanguages = availableLanguages,
    )
}

internal fun AppleLyricsSupplementHooks.hookAppleOfficialPronunciationLanguageMatching() {
    val method = hookResolver.resolveMethod(
        AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH
    ).method
    hookRegistrar.installResultOverrideHook(method) { chain, original ->
        val appleLanguages = nativeVectorStrings(chain.args.firstOrNull())
        if (
            shouldHideMandarinPronunciation(
                pronunciationLanguages = appleLanguages,
            )
        ) {
            return@installResultOverrideHook null
        }
        ApplePronunciationPolicy.selectLanguage(
            systemMatch = original as? String,
            appleLanguages = appleLanguages,
            onlineFallbackLanguage = thirdPartyPronunciationFallbackLanguage(),
        )
    }
    ProviderLogger.debug(
        "Apple Music 官方发音语言优先选择 Hook 已安装: " +
            "${method.declaringClass.name}#matchToSystemLyricsScript"
    )
}

internal fun AppleLyricsSupplementHooks.hookAppleLyricsPreferredLanguages() {
    runCatching {
        val requestClass = hookResolver.resolveClass(
            AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST
        ).clazz
        val constructorAndIndexes = requestClass.declaredConstructors.firstNotNullOfOrNull {
            candidate ->
            val indexes = appleLyricsStringArrayParameterIndexes(candidate.parameterTypes)
            if (indexes.size == 2) candidate to indexes else null
        } ?: throw NoSuchMethodException(
            "${requestClass.name}<init>(...,String[],...,String[],...)"
        )
        val (constructor, stringArrayIndexes) = constructorAndIndexes
        val translationIndex = stringArrayIndexes.first()
        val pronunciationIndex = stringArrayIndexes.last()
        constructor.isAccessible = true
        hookRegistrar.installArgumentRewriteHook(constructor) { chain ->
            val originalTranslationLanguages =
                chain.args.getOrNull(translationIndex) as? Array<*>
            val expandedTranslationLanguages = expandAppleLyricsTranslationLanguages(
                originalTranslationLanguages?.filterIsInstance<String>().orEmpty()
            ).toTypedArray()
            val originalPronunciationLanguages =
                chain.args.getOrNull(pronunciationIndex) as? Array<*>
            val expandedPronunciationLanguages = expandAppleLyricsPronunciationLanguages(
                originalPronunciationLanguages?.filterIsInstance<String>().orEmpty()
            ).toTypedArray()
            ProviderLogger.debug(
                "Apple Music 官方歌词翻译候选请求: " +
                    "original=${originalTranslationLanguages?.toList()}, " +
                    "expanded=${expandedTranslationLanguages.toList()}"
            )
            ProviderLogger.debug(
                "Apple Music 官方歌词发音候选请求: " +
                    "original=${originalPronunciationLanguages?.toList()}, " +
                    "expanded=${expandedPronunciationLanguages.toList()}"
            )
            chain.args.toTypedArray().also { rewritten ->
                rewritten[translationIndex] = expandedTranslationLanguages
                rewritten[pronunciationIndex] = expandedPronunciationLanguages
            }
        }
        ProviderLogger.debug(
            "Apple Music 官方歌词语言候选 Hook 已安装: " +
                "${requestClass.name}<init>, translationIndex=$translationIndex, " +
                "pronunciationIndex=$pronunciationIndex"
        )
    }.onFailure {
        ProviderLogger.error(
            "Apple Music 官方歌词语言候选 Hook 安装失败，已保留原生歌词链路",
            it,
        )
    }
}

internal fun AppleLyricsSupplementHooks.currentSystemLyricsLanguage(): String? = runCatching {
    playbackBinding.snapshot().viewModel?.let {
            lyricsNativeCall(
                it,
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD,
            ) as? String
    }
}.getOrNull()?.takeIf(String::isNotBlank)

internal fun AppleLyricsSupplementHooks.thirdPartyPronunciationFallbackLanguage(): String? {
    val systemLanguage = currentSystemLyricsLanguage()
    if (
        shouldHideMandarinPronunciation(
            pronunciationLanguages = listOfNotNull(systemLanguage),
        )
    ) return null
    if (!isNativeOnlineTranslationEnabled()) return null
    val songId = currentAppleLyricsSongId
    if (!nativeOnlineTranslationStore.hasPronunciation(songId)) return null
    return systemLanguage
        ?.takeIf(RomanizationPolicy::isLatinLanguageTag)
        ?: "und-Latn"
}

internal fun AppleLyricsSupplementHooks.applyAppleNativeSupplementSelection(songNative: Any) {
    val songId = nativeSongId(songNative)
    reportApplePronunciationRuntimeDiagnostic(
        stage = "supplement_selection",
        songId = songId,
        details = "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}, " +
            "translationSelected=${PreferencesMonitor.isTranslationSelected()}, " +
            "hasOnlinePronunciation=${nativeOnlineTranslationStore.hasPronunciation(songId)}",
    )
    val tracks = appleNativeSupplementTracks(
        pronunciationSelected = PreferencesMonitor.isPronunciationSelected(),
        translationSelected = PreferencesMonitor.isTranslationSelected(),
    )
    if (AppleNativeSupplementTrack.TRANSLATION in tracks) {
        applyAppleNativeTranslationSelection(songNative)
    }
    if (AppleNativeSupplementTrack.PRONUNCIATION in tracks) {
        applyAppleNativePronunciationSelection(songNative)
    }
}

internal fun AppleLyricsSupplementHooks.applyAppleNativePronunciationSelection(songNative: Any) {
    val officialLanguages = nativeVectorStrings(
        runCatching {
            lyricsNativeCall(
                songNative,
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
            )
        }.getOrNull()
    ).filter(RomanizationPolicy::isLatinLanguageTag)
    val songId = nativeSongId(songNative)
    rememberApplePronunciationLanguages(songNative, officialLanguages)
    if (
        shouldHideMandarinPronunciation(
            songId = songId,
            pronunciationLanguages = officialLanguages,
        )
    ) {
        ProviderLogger.debug(
            "Apple 歌词发音轨道已隐藏：id=$songId, languages=$officialLanguages"
        )
        return
    }
    val language = officialLanguages
        .firstOrNull()
        ?.takeIf { hasValidOfficialRomanization(songNative) }
        ?: thirdPartyPronunciationFallbackLanguage()
        ?: return
    val selected = runCatching {
        lyricsNativeCall(
            songNative,
            AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD,
            language,
        ) as? Boolean
    }.onFailure {
        ProviderLogger.error("Apple 歌词发音轨道选择失败：language=$language", it)
    }.getOrNull()
    reportApplePronunciationRuntimeDiagnostic(
        stage = "pronunciation_selection_applied",
        songId = songId,
        details = "language=$language, officialLanguages=$officialLanguages, " +
            "selected=$selected, preference=${PreferencesMonitor.isPronunciationSelected()}",
    )
    ProviderLogger.debug(
        "Apple 歌词发音轨道选择：language=$language, " +
            "official=${officialLanguages.isNotEmpty()}, selected=$selected"
    )
}

internal fun AppleLyricsSupplementHooks.applyAppleNativeTranslationSelection(songNative: Any) {
    val systemLanguage = currentSystemLyricsLanguage() ?: return
    val officialLanguages = nativeVectorStrings(
        runCatching {
            lyricsNativeCall(
                songNative,
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD,
            )
        }.getOrNull()
    )
    val officialLanguage = selectAppleLyricsTranslationLanguage(
        systemLanguage = systemLanguage,
        availableLanguages = officialLanguages,
    )
    val language = officialLanguage ?: systemLanguage
    val selected = runCatching {
        appleOfficialTranslationProbeGuard.run {
        lyricsNativeCall(
            songNative,
            AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD,
            language,
        ) as? Boolean
        }
    }.onFailure {
        ProviderLogger.error("Apple 歌词翻译轨道选择失败：language=$language", it)
    }.getOrNull()
    ProviderLogger.debug(
        "Apple 歌词翻译轨道选择：systemLanguage=$systemLanguage, " +
            "officialLanguages=$officialLanguages, language=$language, " +
            "official=${officialLanguage != null}, selected=$selected"
    )
}

