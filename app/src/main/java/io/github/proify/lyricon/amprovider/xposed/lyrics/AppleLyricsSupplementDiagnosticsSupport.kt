/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.lang.reflect.Modifier

internal fun AppleLyricsSupplementHooks.debugAppleLyricsVisibleChildren(recycler: ViewGroup): String {
    if (!BuildConfig.DEBUG) return "disabled"
    val ranges = buildList {
        repeat(recycler.childCount) { index ->
            val child = recycler.getChildAt(index) ?: return@repeat
            val position = blurHooks.appleLyricsChildAdapterPosition(recycler, child)
            if (position >= 0) {
                add(Triple(position, child.top, child.bottom))
            }
        }
    }.take(32)
    val overlaps = buildList {
        for (leftIndex in ranges.indices) {
            for (rightIndex in leftIndex + 1 until ranges.size) {
                val left = ranges[leftIndex]
                val right = ranges[rightIndex]
                if (minOf(left.third, right.third) > maxOf(left.second, right.second)) {
                    add("${left.first}/${right.first}")
                }
            }
        }
    }
    val segments = ranges.map { it.first }
        .fold(mutableListOf<MutableList<Int>>()) { result, position ->
            val previous = result.lastOrNull()?.lastOrNull()
            if (previous == null || position > previous + 1) {
                result += mutableListOf(position)
            } else {
                result.last() += position
            }
            result
        }
        .joinToString("/") { segment ->
            if (segment.size == 1) segment.first().toString()
            else "${segment.first()}-${segment.last()}"
        }
    val children = ranges.joinToString(",") { (position, top, bottom) ->
        "$position:$top..$bottom"
    }
    return "children=[$children],segments=$segments," +
        "overlaps=[${overlaps.joinToString(",")}],childCount=${recycler.childCount}"
}

/** Debug-only：定位按钮“有翻译”信念的具体来源（Apple 原生或模块哪个 Store）。 */
internal fun AppleLyricsSupplementHooks.logTranslationAvailabilityOverride(
    method: String,
    songId: String?,
    original: Boolean,
    nativeStore: Boolean,
    supplement: Boolean,
    lunaBeat: Boolean,
    resolved: Boolean,
    songNative: Any?,
) {
    val key = "$method:$songId:$original:$nativeStore:$supplement:$lunaBeat:$resolved"
    if (!loggedTranslationAvailabilityKeys.add(key)) return
    if (loggedTranslationAvailabilityKeys.size > 64) loggedTranslationAvailabilityKeys.clear()
    val officialLanguages = songNative?.let { native ->
        runCatching {
            nativeVectorStrings(
                lyricsNativeCall(
                    native,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD,
                )
            )
        }.getOrNull()
    }
    ProviderLogger.diagnostic(
        "Apple Music 原生翻译可用性覆盖: method=$method, songId=$songId, " +
            "original=$original, nativeStore=$nativeStore, supplement=$supplement, " +
            "lunaBeat=$lunaBeat, officialLanguages=$officialLanguages, resolved=$resolved"
    )
}

internal fun AppleLyricsSupplementHooks.logAppleLyricsUiState(
    fragment: Any,
    stage: String,
    expectedSongId: String? = null,
    expectedRevision: Long? = null,
) {
    if (!BuildConfig.DEBUG) return
    val bindingRead = runCatching {
        lyricsUiField(fragment, AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD)
    }
    val binding = bindingRead.getOrNull()
    val bindingRecyclerRead = binding?.let { currentBinding ->
        runCatching {
            lyricsUiField(
                currentBinding,
                AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD,
            )
        }
    }
    val bindingRecycler = bindingRecyclerRead
        ?.getOrNull()
        ?.takeIf { isAppleRecyclerViewInstance(it) }
    val fragmentAdapterRead = runCatching {
        lyricsUiField(fragment, AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD)
    }
    val fragmentAdapter = fragmentAdapterRead.getOrNull()
    val lyricsPointer = presentationBinding.pointer()
    val lyricsNative = lyricsPointer?.let { pointer ->
        runCatching {
            lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
        }.getOrNull()
    }
    val fragmentRoot = runCatching {
        AppleReflection.call(fragment, "getView") as? View
    }.getOrNull()
    val lifecycle = listOf("isAdded", "isVisible", "isResumed").joinToString(",") { name ->
        "$name=${runCatching { AppleReflection.call(fragment, name) }.getOrNull()}"
    }
    val currentSongId = currentAppleLyricsSongId
    ProviderLogger.diagnostic(
        "Apple lyrics UI state: stage=$stage, " +
            "fragment=${debugAppleLyricsValue(fragment)}, lifecycle=[$lifecycle], " +
            "root=${debugAppleLyricsValue(fragmentRoot)}, " +
            "i0=${debugAppleLyricsRead(bindingRead)}, " +
            "i0.a0=${debugAppleLyricsRead(bindingRecyclerRead)}, " +
            "k0=${debugAppleLyricsRead(fragmentAdapterRead)}, " +
            "k0State=${debugAppleLyricsAdapterState(fragmentAdapter)}, " +
            "viewModelState=${debugAppleLyricsViewModelState(fragment)}, " +
            "songPointer=${debugAppleNativePointer(lyricsPointer)}, " +
            "songNative=${debugAppleNativePointer(lyricsNative)}, " +
            "songState=${debugApplePronunciationSongState(lyricsNative)}, " +
            "fragmentRecyclerFields=${debugAppleLyricsRecyclerFields(fragment)}, " +
            "bindingRecyclerFields=${debugAppleLyricsRecyclerFields(binding)}, " +
            "resolvedRecycler=${bindingRecycler?.let { debugRecyclerViewSnapshot(it) }}, " +
            "expectedSongId=$expectedSongId, currentSongId=$currentSongId, " +
            "expectedRevision=${expectedRevision ?: "none"}, " +
            "storeRevision=${nativeOnlineTranslationStore.revision()}, " +
            "hasTranslation=${nativeOnlineTranslationStore.hasTranslation(currentSongId)}, " +
            "hasPronunciation=${nativeOnlineTranslationStore.hasPronunciation(currentSongId)}, " +
            "translationSelected=${PreferencesMonitor.isTranslationSelected()}, " +
            "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}"
    )
}

private fun AppleLyricsSupplementHooks.debugAppleLyricsRead(result: Result<Any?>?): String = when {
    result == null -> "not_read"
    result.isFailure -> {
        val throwable = result.exceptionOrNull()
        "error:${throwable?.javaClass?.simpleName}:${throwable?.message}"
    }
    else -> debugAppleLyricsValue(result.getOrNull())
}

internal fun AppleLyricsSupplementHooks.debugAppleLyricsValue(value: Any?): String = when (value) {
    null -> "null"
    else -> when {
        isAppleRecyclerViewInstance(value) -> debugRecyclerViewSnapshot(value)
        value is View -> {
            val idName = runCatching {
                value.resources.getResourceName(value.id)
            }.getOrNull()
            "${value.javaClass.name}@${System.identityHashCode(value)}" +
                "[id=$idName,attached=${value.isAttachedToWindow}," +
                "shown=${value.isShown},visibility=${value.visibility}]"
        }
        else -> "${value.javaClass.name}@${System.identityHashCode(value)}"
    }
}

private fun AppleLyricsSupplementHooks.debugAppleBooleanField(
    instance: Any?,
    member: AppleMusicRuntimeMember,
): Boolean? =
    instance?.let { value ->
        runCatching {
            AppleReflection.field(value, blurHooks.lyricsAdapterMember(value, member)) as? Boolean
        }.getOrNull()
    }

private fun AppleLyricsSupplementHooks.debugAppleLyricsAdapterState(adapter: Any?): String {
    if (adapter == null) return "null"
    val itemCount = runCatching {
        (AppleReflection.call(
            adapter,
            blurHooks.lyricsAdapterMember(
                adapter,
                AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD,
            ),
        ) as? Number)?.toInt()
    }.recoverCatching {
        (AppleReflection.call(adapter, "getItemCount") as? Number)?.toInt()
    }.getOrNull()
    return "${adapter.javaClass.name}@${System.identityHashCode(adapter)}" +
        "[translation=${debugAppleBooleanField(
            adapter,
            AppleMusicRuntimeMember.LYRICS_ADAPTER_TRANSLATION_SELECTED_FIELD,
        )}," +
        "pronunciation=${debugAppleBooleanField(
            adapter,
            AppleMusicRuntimeMember.LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD,
        )}," +
        "itemCount=$itemCount]"
}

private fun AppleLyricsSupplementHooks.debugAppleLyricsViewModelState(fragment: Any): String {
    val viewModel = runCatching {
        lyricsUiField(fragment, AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD)
    }.getOrNull()
        ?: return "null"
    fun liveValue(member: AppleMusicRuntimeMember): Any? = runCatching {
        val liveData = AppleReflection.call(viewModel, lyricsUiMember(member))
            ?: return@runCatching null
        AppleReflection.call(liveData, "getValue")
    }.getOrNull()
    return "${viewModel.javaClass.name}@${System.identityHashCode(viewModel)}" +
        "[pronunciationSelected=${liveValue(
            AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_PRONUNCIATION_SELECTED_GETTER
        )}," +
        "pronunciationAvailable=${liveValue(
            AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_PRONUNCIATION_AVAILABLE_GETTER
        )}," +
        "translationSelected=${liveValue(
            AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_TRANSLATION_SELECTED_GETTER
        )}," +
        "translationAvailable=${liveValue(
            AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_TRANSLATION_AVAILABLE_GETTER
        )}]"
}

private fun AppleLyricsSupplementHooks.debugAppleNativePointer(value: Any?): String {
    if (value == null) return "null"
    val address = runCatching {
        (lyricsNativeCall(
            value,
            AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_ADDRESS_METHOD,
        ) as? Number)?.toLong()
    }.getOrNull()
    return "${value.javaClass.name}@${System.identityHashCode(value)}[address=$address]"
}

private fun AppleLyricsSupplementHooks.debugApplePronunciationSongState(songNative: Any?): String {
    songNative ?: return "null"
    val languages = nativePronunciationLanguages(songNative)
    val sections = runCatching {
        lyricsNativeCall(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD)
    }
        .getOrNull()
    val lines = nativeVectorItems(sections, limit = 8).flatMap { section ->
        val lineVector = runCatching {
            lyricsNativeCall(section, AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD)
        }
            .getOrNull()
        nativeVectorItems(lineVector, limit = 64)
    }
    val textLines = AppleLyricTextTransform.withRawReads {
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
    val wordLines = AppleLyricTextTransform.withRawReads {
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
    return "[id=${nativeSongId(songNative)},languages=$languages," +
        "lines=${lines.size},textLines=$textLines,wordLines=$wordLines]"
}

internal fun AppleLyricsSupplementHooks.logApplePronunciationModelState(
    stage: String,
    viewModel: Any?,
    pointer: Any?,
    songNative: Any?,
) {
    if (!BuildConfig.DEBUG) return
    ProviderLogger.diagnostic(
        "Apple pronunciation model: stage=$stage, " +
            "viewModel=${viewModel?.let { debugAppleLyricsValue(it) }}, " +
            "pointer=${debugAppleNativePointer(pointer)}, " +
            "native=${debugAppleNativePointer(songNative)}, " +
            "state=${debugApplePronunciationSongState(songNative)}, " +
            "currentSongId=$currentAppleLyricsSongId"
    )
}

private fun AppleLyricsSupplementHooks.debugAppleLyricsRecyclerFields(instance: Any?): String {
    if (instance == null) return "none"
    return generateSequence<Class<*>>(instance.javaClass) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .filterNot { Modifier.isStatic(it.modifiers) }
        .filter { isAppleRecyclerViewClass(it.type) }
        .take(8)
        .map { field ->
            val value = runCatching {
                field.isAccessible = true
                field.get(instance)
            }
            "${field.declaringClass.simpleName}.${field.name}=${debugAppleLyricsRead(value)}"
        }
        .joinToString(prefix = "[", postfix = "]")
        .ifEmpty { "none" }
}

internal fun AppleLyricsSupplementHooks.logAppleLyricsRecyclerLifecycle(
    recyclerView: Any,
    stage: String,
) {
    if (!BuildConfig.DEBUG || !isAppleLyricsRecyclerView(recyclerView)) return
    val songId = currentAppleLyricsSongId
    ProviderLogger.diagnostic(
        "Apple lyrics Recycler lifecycle: stage=$stage, " +
            "snapshot=${debugRecyclerViewSnapshot(recyclerView)}, " +
            "currentSongId=$songId, " +
            "storeRevision=${nativeOnlineTranslationStore.revision()}, " +
            "hasTranslation=${nativeOnlineTranslationStore.hasTranslation(songId)}, " +
            "hasPronunciation=${nativeOnlineTranslationStore.hasPronunciation(songId)}"
    )
}

private fun AppleLyricsSupplementHooks.debugTextSnapshot(root: View): String {
    if (!BuildConfig.DEBUG) return "disabled"
    val texts = mutableListOf<String>()
    val pending = ArrayDeque<View>()
    pending.add(root)
    var visited = 0
    while (pending.isNotEmpty() && visited < 96 && texts.size < 12) {
        val view = pending.removeFirst()
        visited += 1
        if (view is TextView) {
            val text = view.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                texts += "${view.javaClass.simpleName}=${text.take(160)}"
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                view.getChildAt(index)?.let(pending::addLast)
            }
        }
    }
    return texts.joinToString(prefix = "[", postfix = "]")
}

private fun AppleLyricsSupplementHooks.debugViewDescription(view: View): String {
    val id = view.id
    val resourceName = if (id == View.NO_ID) {
        "no-id"
    } else {
        runCatching { view.resources.getResourceName(id) }
            .getOrElse { "0x${id.toString(16)}" }
    }
    return "${view.javaClass.name}@${System.identityHashCode(view)}" +
        "[id=$resourceName,shown=${view.isShown},attached=${view.isAttachedToWindow}," +
        "visibility=${view.visibility},alpha=${view.alpha}]"
}

private fun AppleLyricsSupplementHooks.debugRecyclerViewSnapshot(recycler: Any): String {
    if (!BuildConfig.DEBUG) return "disabled"
    val view = recycler as? View ?: return "not_view"
    val scrollState = runCatching {
        AppleReflection.call(recycler, "getScrollState")
    }.getOrNull()
    val adapter = runCatching {
        AppleReflection.call(recycler, "getAdapter")
    }.getOrNull()
    val layoutManager = runCatching {
        AppleReflection.call(recycler, "getLayoutManager")
    }.getOrNull()
    val firstVisible = layoutManager?.let { manager ->
        runCatching {
            AppleReflection.call(manager, "findFirstVisibleItemPosition")
        }.getOrNull()
    }
    val child = (recycler as? ViewGroup)?.getChildAt(0)
    return "view=${debugViewDescription(view)}, state=$scrollState, " +
        "adapter=${adapter?.javaClass?.name}, layout=${layoutManager?.javaClass?.name}, " +
        "first=$firstVisible, childTop=${child?.top}, childCount=" +
        "${(recycler as? ViewGroup)?.childCount}"
}
