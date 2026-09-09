/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.lyrics

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import io.github.proify.lyricon.amprovider.xposed.ActiveOnlineSourceMenu
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleNativeOnlineTranslationStore
import io.github.proify.lyricon.amprovider.xposed.AppleSourceSwitchPerformanceDiagnostics
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.ConfirmedOnlineSourceSelection
import io.github.proify.lyricon.amprovider.xposed.FailedOnlineSourceSwitch
import io.github.proify.lyricon.amprovider.xposed.OnlineSourceMenuPresentation
import io.github.proify.lyricon.amprovider.xposed.OnlineSourceMenuStatus
import io.github.proify.lyricon.amprovider.xposed.PendingOnlineSourceSwitch
import io.github.proify.lyricon.amprovider.xposed.PreferencesMonitor
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.effectiveOnlineSourceSelection
import io.github.proify.lyricon.amprovider.xposed.isMissingLyricsSourceSelectable
import io.github.proify.lyricon.amprovider.xposed.missingLyricsSourceMenuLabel
import io.github.proify.lyricon.amprovider.xposed.missingLyricsSourceStatusLabel
import io.github.proify.lyricon.amprovider.xposed.sourceMenuLabel
import io.github.proify.lyricon.amprovider.xposed.sourceMenuPresentation
import io.github.proify.lyricon.amprovider.xposed.sourceMenuWidth
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

internal class AppleOnlineSourceMenuHooks(
    internal val runtime: AppleMusicProviderRuntime,
    internal val nativeTranslationStore: AppleNativeOnlineTranslationStore,
    internal val currentSongId: () -> String?,
    internal val visibleLyricsSongId: () -> String?,
    internal val shouldHideMandarinPronunciation: (String?) -> Boolean,
    internal val hasOnlineContentConsumption: (String?, String) -> Boolean,
    internal val missingLyricsSourceInfo: (String?) -> AppleMissingLyricsSourceInfo?,
    internal val hasMissingLyricsSupplement: (String?) -> Boolean,
    internal val missingLyricsTranslationSource: (String?) -> String?,
    internal val missingLyricsTranslationMatchPercentage: (String?, String) -> Int?,
    internal val missingLyricsPronunciationMatchPercentage: (String?, String) -> Int?,
    internal val missingLyricsPronunciationSource: (String?) -> String?,
    internal val requestOnlineSource: (Long, String, String, String) -> Boolean,
    internal val debugValue: (Any?) -> String,
    private val configuredOrderedSources: () -> List<String> = { DEFAULT_SOURCE_ORDER },
    private val availableLyricsSources: (String?) -> List<String> = { emptyList() },
) {
    private var activeMenu: ActiveOnlineSourceMenu? = null
    internal var activeLyricsSourceDialog: ActiveLyricsSourceDialog? = null
    internal val pendingSwitches = mutableMapOf<String, PendingOnlineSourceSwitch>()
    internal val failedSwitches = mutableMapOf<String, FailedOnlineSourceSwitch>()
    internal val confirmedSelections = mutableMapOf<String, ConfirmedOnlineSourceSelection>()
    internal var requestSequence = 0L
    internal lateinit var sourceMenuTarget: AppleMusicHookTarget

    internal data class LyricsSourceDialogRow(
        val container: LinearLayout,
        val title: TextView,
        val subtitle: TextView,
        val indicator: TextView,
    )

    internal data class ActiveLyricsSourceDialog(
        val dialog: WeakReference<Dialog>,
        val songId: String,
        val contentType: String,
        val rows: Map<String, LyricsSourceDialogRow>,
        val primaryColor: Int,
        val onSurfaceColor: Int,
        val onSurfaceVariantColor: Int,
    )

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
                AppleSourceSwitchPerformanceDiagnostics.stage(
                    requestId = requestId,
                    songId = songId,
                    stage = "stale_result_ignored",
                    details = "contentType=$contentType,requested=$requestedSource",
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
                AppleSourceSwitchPerformanceDiagnostics.complete(
                    mainHandler = runtime.mainHandler,
                    requestId = requestId,
                    songId = songId,
                    successful = true,
                    actualSource = actualSource ?: requestedSource,
                )
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
        refreshActiveLyricsSourceDialog(songId)
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
        val lyricsInfo = missingLyricsSourceInfo(songId)
        val hasLyricsSupplement = hasMissingLyricsSupplement(songId)
        val lyricsSource = currentSource(songId, "lyrics")
        reportDiagnostic(
            stage = "render_conditions",
            popup = popup,
            anchor = anchor,
            menu = menu,
            songId = songId,
        )
        if (hasLyricsSupplement) {
            menu.addView(
                createLyricsSourceMenuItem(
                    menu = menu,
                    songId = songId,
                    source = lyricsSource,
                ),
                0,
            )
        }
        if (
            pronunciationPresentation != null &&
            pronunciationSelected &&
            !pronunciationHidden
        ) {
            menu.addView(
                createMenuItem(menu, songId, "pronunciation", pronunciationPresentation),
                if (hasLyricsSupplement) 2 else 1,
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
                    "lyrics=[hasSupplement=$hasLyricsSupplement,source=$lyricsSource,info=$lyricsInfo], " +
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
            if (contentType == "translation" || contentType == "pronunciation") {
                showSourceDialog(menu.context, songId, contentType)
            } else {
                val targetSource = if (presentation.source == "QM") "NE" else "QM"
                requestOnlineSourceSwitch(songId, contentType, targetSource)
            }
        }
        return item
    }

    private fun createLyricsSourceMenuItem(
        menu: LinearLayout,
        songId: String,
        source: String?,
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
        item.text = missingLyricsSourceMenuLabel(source)
        val iconId = runtime.application.resources.getIdentifier(
            "actionsheet_info",
            "drawable",
            APPLE_MUSIC_PACKAGE,
        ).takeIf { it != 0 } ?: android.R.drawable.ic_menu_info_details
        item.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, iconId, 0)
        item.setOnClickListener {
            showSourceDialog(menu.context, songId, "lyrics")
        }
        return item
    }

    private fun showSourceDialog(
        context: android.content.Context,
        songId: String,
        contentType: String,
    ) {
        val isLyricsSourceDialog = contentType == "lyrics"
        val isTranslationSourceDialog = contentType == "translation"
        activeLyricsSourceDialog?.dialog?.get()?.dismiss()
        val surfaceColor = resolveThemeColor(
            context,
            android.R.attr.colorBackgroundFloating,
            fallback = Color.WHITE,
        )
        val onSurfaceColor = resolveThemeColor(
            context,
            android.R.attr.textColorPrimary,
            fallback = Color.BLACK,
        )
        val onSurfaceVariantColor = resolveThemeColor(
            context,
            android.R.attr.textColorSecondary,
            fallback = Color.GRAY,
        )
        val primaryColor = resolveThemeColor(
            context,
            android.R.attr.colorAccent,
            fallback = Color.rgb(250, 45, 72),
        )
        val groupColor = blendColors(surfaceColor, onSurfaceColor, 0.055f)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 20))
        }
        val title = TextView(context).apply {
            text = when {
                isLyricsSourceDialog -> "选择歌词来源"
                isTranslationSourceDialog -> "选择翻译来源"
                else -> "选择发音来源"
            }
            textSize = 21f
            setTextColor(onSurfaceColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(context, 3))
        }
        list.addView(title)
        list.addView(
            TextView(context).apply {
                text = if (isLyricsSourceDialog) {
                    "选择补充歌词使用的在线来源"
                } else {
                    "缺失行将从其余源自动补全"
                }
                textSize = 13f
                setTextColor(onSurfaceVariantColor)
                setPadding(0, 0, 0, dp(context, 14))
            }
        )
        val selected = currentSource(songId, contentType)
        val dialog = Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        }
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(context, groupColor, 18)
            clipToOutline = true
        }
        val rows = linkedMapOf<String, LyricsSourceDialogRow>()
        val sourceOrder = if (isLyricsSourceDialog) {
            availableLyricsSources(songId)
        } else {
            configuredOrderedSources()
        }
        if (sourceOrder.isEmpty()) {
            group.addView(
                TextView(context).apply {
                    text = "未启用任何在线来源"
                    textSize = 14f
                    setTextColor(onSurfaceVariantColor)
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(context, 16), dp(context, 24), dp(context, 16), dp(context, 24))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        } else {
            sourceOrder.forEachIndexed { index, source ->
                val status = if (isLyricsSourceDialog) lyricsSourceStatus(songId, source) else null
                val contentMatchPercentage = if (status == null) {
                    contentMatchPercentage(songId, contentType, source) ?: 0
                } else {
                    null
                }
                val isSelected = source == selected
                val pending = pendingSwitches[contentType]?.takeIf { it.songId == songId }
                val isPending = pending?.targetSource == source
                val selectable = pending == null && if (status != null) {
                    isMissingLyricsSourceSelectable(status, isSelected)
                } else {
                    !isSelected
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(context, 58)
                    setPadding(dp(context, 16), dp(context, 6), dp(context, 14), dp(context, 6))
                    isClickable = selectable
                    isFocusable = selectable
                    isEnabled = selectable
                    alpha = if (isPending || status == null || status.found) 1f else 0.52f
                    contentDescription = sourceMenuSourceName(source)
                }
                val textColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                }
                val rowTitle = TextView(context).apply {
                    text = sourceMenuSourceName(source)
                    textSize = 16f
                    setTextColor(if (isSelected) primaryColor else onSurfaceColor)
                    typeface = Typeface.create(
                        Typeface.DEFAULT,
                        if (isSelected) Typeface.BOLD else Typeface.NORMAL,
                    )
                }
                val rowCheck = TextView(context).apply {
                    text = if (isSelected) "✓" else ""
                    textSize = 19f
                    setTextColor(primaryColor)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER
                    minWidth = dp(context, 24)
                    contentDescription = if (isSelected) "已选择" else null
                }
                val rowSubtitle = TextView(context).apply {
                    text = when {
                        isPending -> "获取中…"
                        status != null -> missingLyricsSourceStatusLabel(status)
                        isSelected -> currentSourceSubtitle(
                            contentType = contentType,
                            percentage = contentMatchPercentage ?: 0,
                        )
                        else -> sourceMatchSubtitle(
                            contentType = contentType,
                            percentage = contentMatchPercentage ?: 0,
                        )
                    }
                    textSize = 12.5f
                    setTextColor(onSurfaceVariantColor)
                    setPadding(0, dp(context, 1), 0, 0)
                }
                textColumn.addView(rowTitle)
                textColumn.addView(rowSubtitle)
                row.addView(textColumn)
                row.addView(rowCheck)
                row.setOnClickListener {
                    val currentStatus = if (isLyricsSourceDialog) {
                        lyricsSourceStatus(songId, source)
                    } else {
                        null
                    }
                    val currentlySelected = source == currentSource(songId, contentType)
                    val currentlySelectable = if (currentStatus != null) {
                        isMissingLyricsSourceSelectable(currentStatus, currentlySelected)
                    } else {
                        !currentlySelected
                    }
                    if (
                        pendingSwitches[contentType] != null ||
                        !currentlySelectable
                    ) {
                        return@setOnClickListener
                    }
                    requestOnlineSourceSwitch(songId, contentType, source)
                }
                rows[source] = LyricsSourceDialogRow(
                    container = row,
                    title = rowTitle,
                    subtitle = rowSubtitle,
                    indicator = rowCheck,
                )
                group.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                if (index < sourceOrder.lastIndex) {
                    group.addView(
                        View(context).apply {
                            setBackgroundColor(blendColors(groupColor, onSurfaceColor, 0.10f))
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            hairline(context),
                        ).apply {
                            marginStart = dp(context, 16)
                            marginEnd = dp(context, 16)
                        },
                    )
                }
            }
        }
        list.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val dialogRoot = FrameLayout(context).apply {
            background = roundedDrawable(context, surfaceColor, 28)
            addView(
                list,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        dialog.setContentView(dialogRoot)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            val active = activeLyricsSourceDialog
            if (active?.dialog?.get() === dialog) {
                activeLyricsSourceDialog = null
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setDimAmount(0.32f)
            dialog.window?.setLayout(dialogWidth(context), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.32f)
        dialog.window?.setLayout(dialogWidth(context), ViewGroup.LayoutParams.WRAP_CONTENT)
        activeLyricsSourceDialog = ActiveLyricsSourceDialog(
            dialog = WeakReference(dialog),
            songId = songId,
            contentType = contentType,
            rows = rows,
            primaryColor = primaryColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariantColor = onSurfaceVariantColor,
        )
        refreshActiveLyricsSourceDialog(songId)
    }

}
