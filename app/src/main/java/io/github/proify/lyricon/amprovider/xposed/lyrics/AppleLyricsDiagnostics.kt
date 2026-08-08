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
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy
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


internal class AppleLyricsDiagnostics(
    private val runtime: AppleMusicProviderRuntime,
    private val currentSongId: () -> String?,
    private val epoxyDataBindingFromHolderCallback: (Any?) -> Any?,
    private val bindingDiagnostic: (
        String,
        AppleLyricsBindingDiagnosticContext,
        String,
        String,
    ) -> Unit,
    private val uiStateDiagnostic: (Any, String) -> Unit,
    private val recyclerLifecycleDiagnostic: (Any, String) -> Unit,
    private val appleRecyclerViewPredicate: (Any) -> Boolean,
) {
    private val classLoader: ClassLoader
        get() = runtime.classLoader
    private val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    private val hookRegistrar
        get() = runtime.hookRegistrar

    private val appleLyricsAdapterBindingDiagnosticMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    private val appleLyricsBindingDiagnosticContexts =
        ThreadLocalStack<AppleLyricsBindingDiagnosticContext>()

    fun currentBindingContext(): AppleLyricsBindingDiagnosticContext? =
        appleLyricsBindingDiagnosticContexts.current

    private fun logApplePronunciationBindingDiagnostic(
        stage: String,
        context: AppleLyricsBindingDiagnosticContext,
        details: String,
        dedupeKey: String,
    ) = bindingDiagnostic(stage, context, details, dedupeKey)

    private fun logAppleLyricsUiState(fragment: Any, stage: String) =
        uiStateDiagnostic(fragment, stage)

    private fun logAppleLyricsRecyclerLifecycle(recyclerView: Any, stage: String) =
        recyclerLifecycleDiagnostic(recyclerView, stage)

    private fun isAppleRecyclerViewInstance(value: Any): Boolean =
        appleRecyclerViewPredicate(value)

    private fun debugAppleBooleanField(instance: Any?, name: String): Boolean? =
        instance?.let { value ->
            runCatching { AppleReflection.field(value, name) as? Boolean }.getOrNull()
        }


    fun hookAppleLyricsBindingDiagnostics() {
        val holderClassName = "androidx.recyclerview.widget.RecyclerView\$D"
        hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
            .forEach { resolvedClass ->
            val adapterClass = resolvedClass.clazz
            val translationFieldName = resolvedClass.target.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_ADAPTER_TRANSLATION_SELECTED_FIELD,
            )
            val pronunciationFieldName = resolvedClass.target.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD,
            )
            val bindMethods = adapterClass.declaredMethods.filter { method ->
                val parameterTypes = method.parameterTypes
                !method.isBridge &&
                    !method.isSynthetic &&
                    method.returnType == Void.TYPE &&
                    parameterTypes.size in 2..3 &&
                    parameterTypes[0].name == holderClassName &&
                    parameterTypes[1] == Int::class.javaPrimitiveType &&
                    (
                        parameterTypes.size == 2 ||
                            List::class.java.isAssignableFrom(parameterTypes[2])
                    )
            }
            bindMethods.forEach { method ->
                if (!appleLyricsAdapterBindingDiagnosticMethods.add(method)) return@forEach
                method.isAccessible = true
                hookRegistrar.installScopedHook(
                    executable = method,
                    enter = { chain ->
                        val adapter = chain.thisObject ?: return@installScopedHook false
                        val context = AppleLyricsBindingDiagnosticContext(
                            songId = currentSongId(),
                            adapterClass = adapter.javaClass.name,
                            adapterIdentity = System.identityHashCode(adapter),
                            methodName = "${method.name}/${method.parameterCount}",
                            holder = chain.args.firstOrNull(),
                            position = (chain.args.getOrNull(1) as? Number)?.toInt(),
                            translationEnabled = debugAppleBooleanField(
                                adapter,
                                translationFieldName,
                            ),
                            pronunciationEnabled = debugAppleBooleanField(
                                adapter,
                                pronunciationFieldName,
                            ),
                        )
                        appleLyricsBindingDiagnosticContexts.push(context)
                        true
                    },
                    after = { _, _ ->
                        val context = appleLyricsBindingDiagnosticContexts.current
                            ?: return@installScopedHook
                        val root = appleLyricsHolderRoot(context.holder)
                        val texts = root?.let(::debugTextSnapshot) ?: "none"
                        val layers = debugAppleLyricsHolderLayers(context.holder)
                        logApplePronunciationBindingDiagnostic(
                            stage = "holder_bound",
                            context = context,
                            details = "root=${root?.let(::debugViewDescription)}, " +
                                "texts=$texts, layers=$layers",
                            dedupeKey = "holder:${context.methodName}:${context.position}:" +
                                "${context.pronunciationEnabled}:$texts",
                        )
                        root?.let { capturedRoot ->
                            val capturedContext = context
                            capturedRoot.postOnAnimation {
                                logApplePronunciationBindingDiagnostic(
                                    stage = "holder_next_frame",
                                    context = capturedContext,
                                    details = "root=${debugViewDescription(capturedRoot)}, " +
                                        "texts=${debugTextSnapshot(capturedRoot)}, " +
                                        "layers=${debugAppleLyricsHolderLayers(capturedContext.holder)}",
                                    dedupeKey = "holder_next_frame:" +
                                        "${capturedContext.methodName}:" +
                                        "${capturedContext.position}",
                                )
                            }
                            capturedRoot.postDelayed(
                                {
                                    logApplePronunciationBindingDiagnostic(
                                        stage = "holder_120ms",
                                        context = capturedContext,
                                        details = "root=${debugViewDescription(capturedRoot)}, " +
                                            "texts=${debugTextSnapshot(capturedRoot)}, " +
                                            "layers=${debugAppleLyricsHolderLayers(capturedContext.holder)}",
                                        dedupeKey = "holder_120ms:" +
                                            "${capturedContext.methodName}:" +
                                            "${capturedContext.position}",
                                    )
                                },
                                120L,
                            )
                        }
                    },
                    exit = { appleLyricsBindingDiagnosticContexts.pop() },
                )
            }
        }
        ProviderLogger.diagnostic(
            "Apple pronunciation binding diagnostics installed: " +
                "methods=${appleLyricsAdapterBindingDiagnosticMethods.size}"
        )
    }

    private fun appleLyricsHolderRoot(holder: Any?): View? {
        holder ?: return null
        val binding = epoxyDataBindingFromHolderCallback(holder)
        runCatching {
            binding?.let { AppleReflection.call(it, "getRoot") as? View }
        }.getOrNull()?.let { return it }
        return generateSequence(holder.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { View::class.java.isAssignableFrom(it.type) }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(holder) as? View
                }.getOrNull()
            }
    }

    private fun debugAppleLyricsHolderLayers(holder: Any?): String {
        if (!BuildConfig.DEBUG) return "disabled"
        val binding = epoxyDataBindingFromHolderCallback(holder) ?: return "binding=none"
        val viewFields = generateSequence(binding.javaClass) { it.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .filter { field -> View::class.java.isAssignableFrom(field.type) }
            .toList()
        return viewFields.joinToString(prefix = "[", postfix = "]") { field ->
            val view = runCatching {
                field.isAccessible = true
                field.get(binding) as? View
            }.getOrNull()
            if (view == null) {
                "${field.type.simpleName}=none"
            } else {
                val childCount = (view as? ViewGroup)?.childCount ?: -1
                "${field.type.simpleName}={${debugViewDescription(view)},childCount=$childCount," +
                    "texts=${debugTextSnapshot(view)},children=${debugAppleDirectChildren(view)}}"
            }
        }
    }

    private fun debugAppleDirectChildren(view: View): String {
        val group = view as? ViewGroup ?: return "not_group"
        return (0 until minOf(group.childCount, 24)).joinToString(
            prefix = "[",
            postfix = "]",
        ) { index ->
            val child = group.getChildAt(index)
            val text = (child as? TextView)?.text?.toString()?.trim().orEmpty().take(80)
            "${child.javaClass.simpleName}@${System.identityHashCode(child)}" +
                "(visibility=${child.visibility},alpha=${child.alpha},text=$text," +
                "nested=${(child as? ViewGroup)?.childCount ?: -1})"
        }
    }

    fun hookAppleLyricsUiDiagnostics() {
        runCatching {
            val onCreateView = hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW
            ).method
            hookRegistrar.installHook(
                onCreateView,
                before = { chain ->
                    chain.thisObject?.let { fragment ->
                        logAppleLyricsUiState(fragment, "onCreateView_before")
                    }
                },
                after = { chain, result ->
                    val fragment = chain.thisObject ?: return@installHook
                    logAppleLyricsUiState(fragment, "onCreateView_after")
                    (result as? View)?.let { root ->
                        root.postOnAnimation {
                            logAppleLyricsUiState(fragment, "onCreateView_next_frame")
                        }
                        root.postDelayed(
                            {
                                logAppleLyricsUiState(fragment, "onCreateView_250ms")
                            },
                            250L,
                        )
                    }
                },
            )

            val onResume = hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_UI_ON_RESUME
            ).method
            hookRegistrar.installHook(onResume, after = { chain, _ ->
                chain.thisObject?.let { fragment ->
                    logAppleLyricsUiState(fragment, "onResume_after")
                }
            })

            val onDestroyView = hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW
            ).method
            hookRegistrar.installHook(onDestroyView, before = { chain ->
                chain.thisObject?.let { fragment ->
                    logAppleLyricsUiState(fragment, "onDestroyView_before")
                }
            })

            val recyclerClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val setAdapter = AppleReflection.findMethod(
                recyclerClass,
                "setAdapter",
                parameterCount = 1,
            )
            hookRegistrar.installHook(setAdapter, after = { chain, _ ->
                chain.thisObject
                    ?.takeIf(::isAppleRecyclerViewInstance)
                    ?.let { recyclerView ->
                    logAppleLyricsRecyclerLifecycle(recyclerView, "setAdapter_after")
                }
            })
            val onRecyclerAttached = AppleReflection.findMethod(
                recyclerClass,
                "onAttachedToWindow",
                parameterCount = 0,
            )
            hookRegistrar.installHook(onRecyclerAttached, after = { chain, _ ->
                chain.thisObject
                    ?.takeIf(::isAppleRecyclerViewInstance)
                    ?.let { recyclerView ->
                    logAppleLyricsRecyclerLifecycle(recyclerView, "onAttachedToWindow_after")
                }
            })
            ProviderLogger.diagnostic("Apple lyrics UI lifecycle diagnostics installed")
        }.onFailure {
            ProviderLogger.error("Apple lyrics UI lifecycle diagnostics install failed", it)
        }
    }

    private fun debugTextSnapshot(root: View): String {
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

    private fun debugViewDescription(view: View): String {
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

    private fun debugRecyclerViewSnapshot(recycler: Any): String {
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

}
