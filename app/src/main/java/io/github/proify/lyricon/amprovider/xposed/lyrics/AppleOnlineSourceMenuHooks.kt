/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.lyrics

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.ActiveOnlineSourceMenu
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleNativeOnlineTranslationStore
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.ConfirmedOnlineSourceSelection
import io.github.proify.lyricon.amprovider.xposed.FailedOnlineSourceSwitch
import io.github.proify.lyricon.amprovider.xposed.OnlineSourceMenuPresentation
import io.github.proify.lyricon.amprovider.xposed.OnlineSourceMenuStatus
import io.github.proify.lyricon.amprovider.xposed.PendingOnlineSourceSwitch
import io.github.proify.lyricon.amprovider.xposed.PreferencesMonitor
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.effectiveOnlineSourceSelection
import io.github.proify.lyricon.amprovider.xposed.sourceMenuLabel
import io.github.proify.lyricon.amprovider.xposed.sourceMenuPresentation
import io.github.proify.lyricon.amprovider.xposed.sourceMenuWidth
import java.lang.ref.WeakReference

internal class AppleOnlineSourceMenuHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val nativeTranslationStore: AppleNativeOnlineTranslationStore,
    private val currentSongId: () -> String?,
    private val shouldHideMandarinPronunciation: (String?) -> Boolean,
    private val hasOnlineContentConsumption: (String?, String) -> Boolean,
    private val requestOnlineSource: (Long, String, String, String) -> Boolean,
    private val debugValue: (Any?) -> String,
) {
    private var activeMenu: ActiveOnlineSourceMenu? = null
    private val pendingSwitches = mutableMapOf<String, PendingOnlineSourceSwitch>()
    private val failedSwitches = mutableMapOf<String, FailedOnlineSourceSwitch>()
    private val confirmedSelections = mutableMapOf<String, ConfirmedOnlineSourceSelection>()
    private var requestSequence = 0L
    private lateinit var sourceMenuTarget: AppleMusicHookTarget

    fun installSourceMenu() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER
            )
            sourceMenuTarget = resolved.target
            runtime.hookRegistrar.installHook(resolved.method, after = { chain, _ ->
                addOnlineSourceMenuItems(
                    clickListener = chain.thisObject,
                    anchor = chain.args.firstOrNull() as? View,
                )
            })
            ProviderLogger.debug(
                "Apple Music 三方歌词来源菜单 Hook 已安装: " +
                    "${resolved.target.className}#${resolved.method.name}, " +
                    "fallback=${resolved.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error(
                "Apple Music 三方歌词来源菜单 Hook 安装失败：未找到签名匹配的点击监听器",
                it,
            )
        }
    }

    fun resolvePendingSwitches(songId: String) {
        var sourceChanged = false
        val iterator = pendingSwitches.iterator()
        while (iterator.hasNext()) {
            val (_, pending) = iterator.next()
            if (pending.songId != songId) continue
            val actualSource = storedSource(songId, pending.contentType)
            if (actualSource == pending.targetSource) {
                iterator.remove()
                failedSwitches.remove(pending.contentType)
                confirmedSelections[pending.contentType] = ConfirmedOnlineSourceSelection(
                    songId = songId,
                    contentType = pending.contentType,
                    source = actualSource,
                )
                ProviderLogger.diagnostic(
                    "Apple Music 在线翻译来源菜单切换成功: " +
                        "requestId=${pending.requestId}, songId=$songId, " +
                        "contentType=${pending.contentType}, source=$actualSource"
                )
                sourceChanged = true
            }
        }
        if (sourceChanged) refreshActiveMenu(songId)
    }

    fun receiveSourceSwitchResult(
        requestId: Long,
        songId: String?,
        contentType: String?,
        requestedSource: String?,
        actualSource: String?,
        successful: Boolean,
    ) {
        runtime.mainHandler.post {
            val type = contentType ?: return@post
            val pending = pendingSwitches[type]
            if (
                pending == null ||
                pending.requestId != requestId ||
                pending.songId != songId ||
                pending.targetSource != requestedSource
            ) {
                ProviderLogger.diagnostic(
                    "忽略过期 Apple Music 在线翻译来源结果: " +
                        "requestId=$requestId, songId=$songId, contentType=$contentType, " +
                        "requested=$requestedSource, actual=$actualSource, successful=$successful"
                )
                return@post
            }
            ProviderLogger.diagnostic(
                "Apple Music 在线翻译来源结果已回传: " +
                    "requestId=$requestId, songId=$songId, contentType=$contentType, " +
                    "requested=$requestedSource, actual=$actualSource, successful=$successful"
            )
            if (successful) {
                pendingSwitches.remove(type)
                failedSwitches.remove(type)
                confirmedSelections[type] = ConfirmedOnlineSourceSelection(
                    songId = requireNotNull(songId),
                    contentType = type,
                    source = actualSource ?: requireNotNull(requestedSource),
                )
                refreshActiveMenu(requireNotNull(songId))
            } else {
                markSwitchFailed(
                    pending = pending,
                    actualSource = actualSource ?: currentSource(songId, type),
                    reason = "source_unavailable",
                )
            }
        }
    }

    fun refreshActiveMenu(songId: String) {
        val active = activeMenu ?: return
        if (active.songId != songId) return
        val popup = active.popup.get()
        val menu = active.menu.get()
        val anchor = active.anchor.get()
        if (popup == null || menu == null || anchor == null || !popup.isShowing) {
            activeMenu = null
            return
        }
        renderOnlineSourceMenuItems(
            popup = popup,
            menu = menu,
            anchor = anchor,
            songId = songId,
            nativeMinimumWidth = active.nativeMinimumWidth,
        )
    }

    fun activeMenuSongId(): String? = activeMenu?.songId

    fun isActiveMenuShowing(): Boolean = activeMenu?.popup?.get()?.isShowing == true

    fun isMenuShowingForSong(songId: String): Boolean =
        activeMenu?.takeIf { it.songId == songId }?.popup?.get()?.isShowing == true

    fun clearInactiveMenu() {
        if (!isActiveMenuShowing()) activeMenu = null
    }

    private fun addOnlineSourceMenuItems(clickListener: Any?, anchor: View?) {
        if (clickListener == null) {
            reportDiagnostic(stage = "missing_click_listener")
            return
        }
        if (anchor == null) {
            reportDiagnostic(stage = "missing_anchor", clickListener = clickListener)
            return
        }
        val fragment = resolveFragment(clickListener)
        if (fragment == null) {
            reportDiagnostic(
                stage = "fragment_not_found",
                clickListener = clickListener,
                anchor = anchor,
            )
            return
        }
        val popup = resolvePopup(fragment)
        if (popup == null) {
            reportDiagnostic(
                stage = "popup_not_found",
                clickListener = clickListener,
                anchor = anchor,
                fragment = fragment,
            )
            return
        }
        val menu = popup.contentView as? LinearLayout
        if (menu == null) {
            reportDiagnostic(
                stage = "content_not_linear_layout",
                clickListener = clickListener,
                anchor = anchor,
                fragment = fragment,
                popup = popup,
            )
            return
        }
        if (menu.childCount < 2) {
            reportDiagnostic(
                stage = "native_items_insufficient",
                clickListener = clickListener,
                anchor = anchor,
                fragment = fragment,
                popup = popup,
                menu = menu,
            )
            return
        }
        normalizeTextItems(menu)
        val nativeMinimumWidth = nativeMenuWidth(popup, menu)
        val songId = currentSongId()
        if (songId == null) {
            reportDiagnostic(
                stage = "missing_song_id",
                clickListener = clickListener,
                anchor = anchor,
                fragment = fragment,
                popup = popup,
                menu = menu,
            )
            return
        }
        pendingSwitches.entries.removeAll { it.value.songId != songId }
        failedSwitches.entries.removeAll { it.value.songId != songId }
        confirmedSelections.entries.removeAll { it.value.songId != songId }
        activeMenu = ActiveOnlineSourceMenu(
            popup = WeakReference(popup),
            menu = WeakReference(menu),
            anchor = WeakReference(anchor),
            songId = songId,
            nativeMinimumWidth = nativeMinimumWidth,
        )
        reportDiagnostic(
            stage = "render_requested",
            clickListener = clickListener,
            anchor = anchor,
            fragment = fragment,
            popup = popup,
            menu = menu,
            songId = songId,
        )
        renderOnlineSourceMenuItems(popup, menu, anchor, songId, nativeMinimumWidth)
    }

    private fun renderOnlineSourceMenuItems(
        popup: PopupWindow,
        menu: LinearLayout,
        anchor: View,
        songId: String,
        nativeMinimumWidth: Int,
    ) {
        for (index in menu.childCount - 1 downTo 0) {
            if (menu.getChildAt(index).tag == ONLINE_SOURCE_MENU_ITEM_TAG) {
                menu.removeViewAt(index)
            }
        }
        normalizeTextItems(menu)
        val pronunciationSource = currentSource(songId, "pronunciation")
        val pronunciationPresentation = presentation(songId, "pronunciation", pronunciationSource)
        val translationSource = currentSource(songId, "translation")
        val translationPresentation = presentation(songId, "translation", translationSource)
        val pronunciationSelected = PreferencesMonitor.isPronunciationSelected()
        val pronunciationHidden = shouldHideMandarinPronunciation(songId)
        val translationSelected = PreferencesMonitor.isTranslationSelected()
        reportDiagnostic(
            stage = "render_conditions",
            popup = popup,
            anchor = anchor,
            menu = menu,
            songId = songId,
        )
        if (
            pronunciationPresentation != null &&
            pronunciationSelected &&
            !pronunciationHidden
        ) {
            menu.addView(
                createMenuItem(menu, songId, "pronunciation", pronunciationPresentation),
                1,
            )
        }
        if (translationPresentation != null && translationSelected) {
            menu.addView(createMenuItem(menu, songId, "translation", translationPresentation))
        }
        normalizeTextItems(menu)
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple Music 三方歌词来源菜单诊断: stage=rendered, songId=$songId, " +
                    "translationSource=$translationSource, translationPresentation=$translationPresentation, " +
                    "translationSelected=$translationSelected, pronunciationSource=$pronunciationSource, " +
                    "pronunciationPresentation=$pronunciationPresentation, " +
                    "pronunciationSelected=$pronunciationSelected, pronunciationHidden=$pronunciationHidden, " +
                    "finalChildren=${menu.childCount}, viewTree=${debugViewTree(menu)}"
            )
        }
        updateBounds(popup, menu, anchor, nativeMinimumWidth)
        menu.post {
            if (popup.isShowing) updateBounds(popup, menu, anchor, nativeMinimumWidth)
        }
    }

    private fun createMenuItem(
        menu: LinearLayout,
        songId: String,
        contentType: String,
        presentation: OnlineSourceMenuPresentation,
    ): TextView {
        val layoutId = runtime.application.resources.getIdentifier(
            "menu_item_lyrics_translations",
            "layout",
            APPLE_MUSIC_PACKAGE,
        )
        val item = LayoutInflater.from(menu.context).inflate(layoutId, menu, false) as TextView
        item.tag = ONLINE_SOURCE_MENU_ITEM_TAG
        item.background = menu.getChildAt(0)?.background
            ?.constantState
            ?.newDrawable(menu.resources)
        item.text = sourceMenuLabel(presentation.source, contentType, presentation.status)
        val iconId = sequenceOf(
            "ic_nowplaying_repeat",
            "media3_icon_sync",
            "media_action_repeat_off",
        ).map { iconName ->
            runtime.application.resources.getIdentifier(
                iconName,
                "drawable",
                APPLE_MUSIC_PACKAGE,
            )
        }.firstOrNull { it != 0 } ?: 0
        item.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, iconId, 0)
        item.isEnabled = presentation.status != OnlineSourceMenuStatus.SWITCHING
        item.setOnClickListener {
            val targetSource = if (presentation.source == "QM") "NE" else "QM"
            val requestId = ++requestSequence
            val pending = PendingOnlineSourceSwitch(
                requestId = requestId,
                songId = songId,
                contentType = contentType,
                previousSource = presentation.source,
                targetSource = targetSource,
            )
            failedSwitches.remove(contentType)
            pendingSwitches[contentType] = pending
            ProviderLogger.diagnostic(
                "Apple Music 在线翻译来源菜单点击: requestId=$requestId, " +
                    "songId=$songId, contentType=$contentType, " +
                    "from=${presentation.source}, to=$targetSource, " +
                    "popupShowing=${activeMenu?.popup?.get()?.isShowing}"
            )
            refreshActiveMenu(songId)
            val requestAccepted = requestOnlineSource(
                requestId,
                songId,
                contentType,
                targetSource,
            )
            ProviderLogger.diagnostic(
                "Apple Music 在线翻译来源请求投递: requestId=$requestId, " +
                    "accepted=$requestAccepted"
            )
            if (!requestAccepted) {
                markSwitchFailed(pending, presentation.source, "binder_unavailable")
                return@setOnClickListener
            }
            runtime.mainHandler.postDelayed(
                {
                    val current = pendingSwitches[contentType]
                    if (current?.requestId != requestId) return@postDelayed
                    markSwitchFailed(
                        pending = current,
                        actualSource = currentSource(songId, contentType),
                        reason = "timeout",
                    )
                },
                ONLINE_SOURCE_SWITCH_TIMEOUT_MS,
            )
        }
        return item
    }

    private fun markSwitchFailed(
        pending: PendingOnlineSourceSwitch,
        actualSource: String?,
        reason: String,
    ) {
        val current = pendingSwitches[pending.contentType]
        if (current?.requestId != pending.requestId) return
        pendingSwitches.remove(pending.contentType)
        val displayedSource = actualSource ?: pending.previousSource
        failedSwitches[pending.contentType] = FailedOnlineSourceSwitch(
            requestId = pending.requestId,
            songId = pending.songId,
            contentType = pending.contentType,
            displayedSource = displayedSource,
        )
        ProviderLogger.diagnostic(
            "Apple Music 在线翻译来源菜单切换失败: " +
                "requestId=${pending.requestId}, songId=${pending.songId}, " +
                "contentType=${pending.contentType}, target=${pending.targetSource}, " +
                "actual=$displayedSource, reason=$reason"
        )
        refreshActiveMenu(pending.songId)
        runtime.mainHandler.postDelayed(
            {
                val failure = failedSwitches[pending.contentType]
                if (failure?.requestId != pending.requestId) return@postDelayed
                failedSwitches.remove(pending.contentType)
                refreshActiveMenu(pending.songId)
            },
            ONLINE_SOURCE_SWITCH_FAILURE_FEEDBACK_MS,
        )
    }

    private fun currentSource(songId: String?, contentType: String): String? {
        val confirmedSource = confirmedSelections[contentType]
            ?.takeIf { it.songId == songId }
            ?.source
        return effectiveOnlineSourceSelection(
            storedSource = storedSource(songId, contentType),
            confirmedSource = confirmedSource,
            onlineContentConsumed = hasOnlineContentConsumption(songId, contentType),
        )
    }

    private fun storedSource(songId: String?, contentType: String): String? = when (contentType) {
        "pronunciation" -> nativeTranslationStore.pronunciationSource(songId)
        "translation" -> nativeTranslationStore.translationSource(songId)
        else -> null
    }

    private fun presentation(
        songId: String,
        contentType: String,
        actualSource: String?,
    ): OnlineSourceMenuPresentation? = sourceMenuPresentation(
        actualSource = actualSource,
        pendingTargetSource = pendingSwitches[contentType]
            ?.takeIf { it.songId == songId }
            ?.targetSource,
        failedSource = failedSwitches[contentType]
            ?.takeIf { it.songId == songId }
            ?.displayedSource,
    )

    private fun resolveFragment(clickListener: Any): Any? {
        sourceMenuTarget.runtimeMemberNameOrNull(
            AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_FIELD
        )?.let { fieldName ->
            runCatching { AppleReflection.field(clickListener, fieldName) }
                .getOrNull()
                ?.let { return it }
        }
        val fragmentClassName = sourceMenuTarget.runtimeMemberName(
            AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_CLASS
        )
        return generateSequence(clickListener.javaClass) { it.superclass }
            .flatMap { clazz -> clazz.declaredFields.asSequence() }
            .filter { field -> field.type.name == fragmentClassName }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(clickListener)
                }.getOrNull()
            }
    }

    private fun resolvePopup(fragment: Any): PopupWindow? =
        generateSequence(fragment.javaClass) { it.superclass }
            .flatMap { clazz -> clazz.declaredFields.asSequence() }
            .filter { field -> PopupWindow::class.java.isAssignableFrom(field.type) }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(fragment) as? PopupWindow
                }.getOrNull()
            }

    private fun normalizeTextItems(menu: LinearLayout) {
        for (index in 0 until menu.childCount) {
            val item = menu.getChildAt(index) as? TextView ?: continue
            item.isSingleLine = true
            item.maxLines = 1
            item.ellipsize = null
            item.maxWidth = Int.MAX_VALUE
            item.setHorizontallyScrolling(true)
            item.layoutParams?.let { layoutParams ->
                if (layoutParams.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    item.layoutParams = layoutParams
                }
            }
        }
    }

    private fun nativeMenuWidth(popup: PopupWindow, menu: LinearLayout): Int {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var nativeItemWidth = 0
        for (index in 0 until menu.childCount) {
            val item = menu.getChildAt(index)
            item.measure(unspecified, unspecified)
            nativeItemWidth = maxOf(
                nativeItemWidth,
                item.measuredWidth,
                item.minimumWidth,
                item.layoutParams?.width?.takeIf { it > 0 } ?: 0,
            )
        }
        menu.measure(unspecified, unspecified)
        return sourceMenuWidth(
            popup.width,
            popup.contentView.width,
            menu.width,
            menu.measuredWidth,
            menu.minimumWidth,
            menu.layoutParams?.width?.takeIf { it > 0 } ?: 0,
            nativeItemWidth + menu.paddingLeft + menu.paddingRight,
        )
    }

    private fun updateBounds(
        popup: PopupWindow,
        menu: LinearLayout,
        anchor: View,
        nativeMinimumWidth: Int,
    ) {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        menu.minimumWidth = nativeMinimumWidth
        menu.measure(unspecified, unspecified)
        val desiredWidth = sourceMenuWidth(nativeMinimumWidth, menu.measuredWidth)
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY),
            unspecified,
        )
        val desiredHeight = menu.measuredHeight.coerceAtLeast(1)
        popup.update(anchor, desiredWidth, desiredHeight)
        menu.requestLayout()
        menu.invalidate()
    }

    private fun reportDiagnostic(
        stage: String,
        clickListener: Any? = null,
        anchor: View? = null,
        fragment: Any? = null,
        popup: PopupWindow? = null,
        menu: LinearLayout? = null,
        songId: String? = currentSongId(),
    ) {
        if (!BuildConfig.DEBUG) return
        val resolvedSongId = songId ?: currentSongId()
        val pronunciationSource = currentSource(resolvedSongId, "pronunciation")
        val translationSource = currentSource(resolvedSongId, "translation")
        ProviderLogger.diagnostic(
            "Apple Music 三方歌词来源菜单诊断: stage=$stage, " +
                "listener=${debugValue(clickListener)}, " +
                "listenerFields=${debugSourceMenuFields(clickListener)}, " +
                "anchor=${debugValue(anchor)}, fragment=${debugValue(fragment)}, " +
                "fragmentFields=${debugSourceMenuFields(fragment)}, popup=${debugValue(popup)}, " +
                "popupShowing=${popup?.isShowing}, content=${debugValue(popup?.contentView)}, " +
                "menu=${debugValue(menu)}, nativeChildren=${menu?.childCount}, " +
                "viewTree=${debugViewTree(popup?.contentView)}, songId=$resolvedSongId, " +
                "storeRevision=${nativeTranslationStore.revision()}, " +
                "translation=[has=${nativeTranslationStore.hasTranslation(resolvedSongId)}," +
                "source=$translationSource,presentation=${presentation(
                    resolvedSongId.orEmpty(), "translation", translationSource
                )},selected=${PreferencesMonitor.isTranslationSelected()}], " +
                "pronunciation=[has=${nativeTranslationStore.hasPronunciation(resolvedSongId)}," +
                "source=$pronunciationSource,presentation=${presentation(
                    resolvedSongId.orEmpty(), "pronunciation", pronunciationSource
                )},selected=${PreferencesMonitor.isPronunciationSelected()}," +
                "hidden=${shouldHideMandarinPronunciation(resolvedSongId)}]"
        )
    }

    private fun debugSourceMenuFields(instance: Any?): String {
        if (!BuildConfig.DEBUG || instance == null) return "none"
        val fragmentClassName = sourceMenuTarget.runtimeMemberName(
            AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_CLASS
        )
        return generateSequence(instance.javaClass) { it.superclass }
            .flatMap { clazz -> clazz.declaredFields.asSequence() }
            .filter { field ->
                field.type.name == fragmentClassName ||
                    PopupWindow::class.java.isAssignableFrom(field.type)
            }
            .joinToString(prefix = "[", postfix = "]") { field ->
                "${field.declaringClass.simpleName}.${field.name}:${field.type.simpleName}"
            }
    }

    private fun debugViewTree(root: View?): String {
        if (!BuildConfig.DEBUG || root == null) return "none"
        fun describe(view: View, depth: Int): String {
            val text = (view as? TextView)?.text?.toString()?.replace('\n', ' ')?.take(48)
            val label = view.javaClass.simpleName +
                "(id=${view.id},children=${(view as? ViewGroup)?.childCount ?: 0},text=$text)"
            if (depth >= 2 || view !is ViewGroup || view.childCount == 0) return label
            return (0 until view.childCount).joinToString(
                prefix = "$label[",
                postfix = "]",
            ) { index -> describe(view.getChildAt(index), depth + 1) }
        }
        return describe(root, 0)
    }

    private companion object {
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        const val ONLINE_SOURCE_MENU_ITEM_TAG = "hyperlyrics_enhanced_online_lyrics_source"
        const val ONLINE_SOURCE_SWITCH_TIMEOUT_MS = 15_000L
        const val ONLINE_SOURCE_SWITCH_FAILURE_FEEDBACK_MS = 2_000L
    }
}
