/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.island.IslandAlbumCoverStyleHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandProbeUtils
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaAmbientFlowPalette
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaAmbientFlowPaletteExtractor
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaArtworkSampler
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowArtwork
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowBackgroundView
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowOverlayLayout
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowTimeline
import com.juren233.hyperlyricsenhanced.root.mediacard.background.MediaFlowTone
import com.juren233.hyperlyricsenhanced.root.mediacard.island.background.IslandExpandedBackgroundTarget
import com.juren233.hyperlyricsenhanced.root.mediacard.island.background.IslandExpandedMediaBackgroundApi
import com.juren233.hyperlyricsenhanced.root.mediacard.island.background.IslandExpandedMediaBackgroundController
import com.juren233.hyperlyricsenhanced.root.mediacard.island.background.IslandExpandedMediaBackgroundHost
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.background.NotificationMediaColorConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal fun IslandExpandedMediaAmbientFlowHooker.refreshCardTheme() {
    val refresh = Runnable {
        val snapshot = synchronized(activeBinders) { activeBinders.toList() }
        snapshot.forEach { binder ->
            runCatching { applyAppearance(binder, allowCoverColor = true) }
                .onFailure { HookLogger.e(TAG, "刷新展开态媒体主题失败", it) }
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) refresh.run()
    else Handler(Looper.getMainLooper()).post(refresh)
}

internal fun IslandExpandedMediaAmbientFlowHooker.refreshBackgroundStyle() {
    val refresh = Runnable {
        val snapshot = synchronized(activeBinders) { activeBinders.toList() }
        snapshot.forEach { binder ->
            runCatching { applyAppearance(binder, allowCoverColor = true) }
                .onFailure {
                    HookLogger.e(TAG, "刷新展开态媒体背景失败", it)
                }
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) refresh.run()
    else Handler(Looper.getMainLooper()).post(refresh)
}

internal fun IslandExpandedMediaAmbientFlowHooker.refreshAmbientFlow() {
    val refresh = Runnable {
        synchronized(activeBinders) { activeBinders.toList() }.forEach { binder ->
            runCatching { applyMode(binder, allowCoverColor = true) }
                .onFailure { HookLogger.e(TAG, "刷新展开态媒体流光失败", it) }
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) refresh.run()
    else Handler(Looper.getMainLooper()).post(refresh)
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyFakeTransitionTheme(fakeContentView: ViewGroup) {
    applyCustomFakeTransitionTheme(fakeContentView)
}

internal fun IslandExpandedMediaAmbientFlowHooker.restoreFakeTransitionTheme(fakeContentView: ViewGroup) {
    val dataOwner = fakeContentView.javaClass.getMethod("getRealView").invoke(fakeContentView)
    if (!IslandProbeUtils.isMediaIsland(IslandProbeUtils.getCurrentIslandData(dataOwner))) return
    val api = nativeApi ?: return
    val target = api.findContentBackgroundTarget(fakeContentView) ?: return
    restoreCustomFakeFlow(fakeContentView)
    IslandExpandedMediaBackgroundController.restoreFakeTransition(target, api)
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyCustomFakeTransitionTheme(fakeContentView: ViewGroup) {
    val dataOwner = fakeContentView.javaClass.getMethod("getRealView").invoke(fakeContentView)
    if (!IslandProbeUtils.isMediaIsland(IslandProbeUtils.getCurrentIslandData(dataOwner))) return
    val api = nativeApi ?: return
    val target = api.findContentBackgroundTarget(fakeContentView) ?: return
    val binder = findBinderForContentOwner(dataOwner as? View, api)

    if (
        !IslandExpandedMediaBackgroundController.isActive() &&
        isCustomMode(currentMode())
    ) {
        binder?.let {
            applyMode(it, allowCoverColor = false)
            applyCardTheme(it)
            IslandExpandedMediaBackgroundController.restoreFakeTransition(target, api)
            applyCustomFakeFlow(fakeContentView, it, target, api)
        }
        applyFakeMediaElements(fakeContentView, binder, api)
        return
    }
    restoreCustomFakeFlow(fakeContentView)
    if (
        IslandExpandedMediaBackgroundController.applyFakeTransition(
            target,
            api.getMiniBar(target),
            api
        )
    ) {
        val binders = binder?.let(::listOf)
            ?: synchronized(activeBinders) { activeBinders.toList() }
        binders.forEach { activeBinder ->
            api.getHolders(activeBinder).forEach { holder ->
                IslandExpandedMediaBackgroundController.applyForeground(
                    activeBinder,
                    holder,
                    api,
                    force = true
                )
            }
        }
    }
    applyFakeMediaElements(fakeContentView, binder, api)
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyCustomFakeFlow(
    fakeContentView: ViewGroup,
    binder: Any,
    target: IslandExpandedBackgroundTarget,
    api: NativeApi
) {
    val binderState = binderStates[binder] ?: return
    val artwork = binderState.customArtwork ?: return
    val contentBounds = target.transitionContentBounds ?: return
    val existing = fakeFlowStates[fakeContentView]
    val state = if (
        existing != null &&
        existing.binder === binder &&
        existing.target.customBackgroundView === target.customBackgroundView
    ) {
        existing
    } else {
        existing?.let { removeCustomFakeFlow(fakeContentView) }
        val flowView = MediaFlowBackgroundView(
            fakeContentView.context,
            binderState.customTimeline,
            appleMusicStyle = true
        ).apply {
            tag = CUSTOM_FAKE_FLOW_VIEW_TAG
            visibility = View.GONE
        }
        fakeContentView.addView(
            flowView,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        FakeFlowState(
            binder = binder,
            target = target,
            api = api,
            flowView = flowView
        ).also { fakeFlowStates[fakeContentView] = it }
    }

    if (state.active) restoreCustomFakeFlowState(state)
    state.target = target
    state.api = api
    state.originalTransitionBackground = fakeContentView.background
    state.originalOccludingBackgrounds = target.transitionOccludingViews.map { view ->
        view to view.background
    }
    state.hiddenHolderFlows = binderState.customViews.values.filter { view ->
        view !== state.flowView && view.isDescendantOf(fakeContentView)
    }

    api.prepareCustomBackground(target)
    fakeContentView.background = null
    state.originalOccludingBackgrounds.forEach { (view, _) -> view.background = null }
    state.hiddenHolderFlows.forEach { it.visibility = View.INVISIBLE }
    state.flowView.apply {
        setTransitionViewport(contentBounds)
        visibility = View.VISIBLE
        update(
            artwork = artwork,
            tone = if (shouldUseLightTheme(binder)) MediaFlowTone.LIGHT else MediaFlowTone.DARK,
            playing = api.isPlaying(binder)
        )
    }
    state.active = true
}

internal fun IslandExpandedMediaAmbientFlowHooker.restoreCustomFakeFlow(fakeContentView: ViewGroup) {
    fakeFlowStates[fakeContentView]?.let { restoreCustomFakeFlowState(it) }
}

internal fun IslandExpandedMediaAmbientFlowHooker.restoreCustomFakeFlowState(state: FakeFlowState) {
    if (!state.active) return
    val root = state.flowView.parent as? ViewGroup
    root?.background = state.originalTransitionBackground
    state.originalOccludingBackgrounds.forEach { (view, background) ->
        view.background = background
    }
    state.hiddenHolderFlows.forEach { it.visibility = View.VISIBLE }
    state.flowView.visibility = View.GONE
    state.api.restoreNativeBackground(state.target)
    state.hiddenHolderFlows = emptyList()
    state.originalOccludingBackgrounds = emptyList()
    state.active = false
}

internal fun IslandExpandedMediaAmbientFlowHooker.removeCustomFakeFlow(fakeContentView: ViewGroup) {
    val state = fakeFlowStates.remove(fakeContentView) ?: return
    restoreCustomFakeFlowState(state)
    (state.flowView.parent as? ViewGroup)?.removeView(state.flowView)
}

internal fun View.isDescendantOf(ancestor: ViewGroup): Boolean {
    var current = parent
    while (current is View) {
        if (current === ancestor) return true
        current = current.parent
    }
    return false
}

internal fun IslandExpandedMediaAmbientFlowHooker.findBinderForContentOwner(owner: View?, api: NativeApi): Any? {
    owner ?: return null
    return synchronized(activeBinders) { activeBinders.toList() }.firstOrNull { binder ->
        api.getHolders(binder).any { holder ->
            api.findExpandedBackgroundTarget(api.getPlayer(holder))?.owner === owner
        }
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyContentViewTheme(contentView: View) {
    val isFakeView = contentView.javaClass.name == IslandExpandedMediaAmbientFlowHooker.FAKE_CONTENT_VIEW_CLASS
    val dataOwner = if (isFakeView) {
        contentView.javaClass.methods.firstOrNull {
            it.name == "getRealView" && it.parameterTypes.isEmpty()
        }?.invoke(contentView)
    } else {
        contentView
    }
    if (!IslandProbeUtils.isMediaIsland(IslandProbeUtils.getCurrentIslandData(dataOwner))) return

    val api = nativeApi ?: return
    val target = api.findContentBackgroundTarget(contentView) ?: return
    if (IslandExpandedMediaBackgroundController.isActive()) {
        return
    }
    if (isFakeView) {
        IslandExpandedMediaBackgroundController.restoreFakeTransition(target, api)
    }
    if (!shouldUseLightTheme(contentView.context)) {
        restoreTrackedTheme(contentView, api)
        return
    }

    val notificationContext = findBinderForContentOwner(dataOwner as? View, api)
        ?.let(api::getContext)
        ?: contentView.context.applicationContext
        ?: contentView.context
    applyLightExpandedBackground(
        api,
        target,
        notificationContext.withNightMode(Configuration.UI_MODE_NIGHT_NO)
    )
}

internal fun IslandExpandedMediaAmbientFlowHooker.refreshMediaElements() {
    val refresh = Runnable {
        val snapshot = synchronized(activeBinders) { activeBinders.toList() }
        snapshot.forEach { binder ->
            runCatching { applyMediaElements(binder) }
                .onFailure { HookLogger.e(TAG, "刷新展开态媒体元素失败", it) }
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) refresh.run()
    else Handler(Looper.getMainLooper()).post(refresh)
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyCardTheme(binder: Any) {
    val api = nativeApi ?: return
    if (!shouldUseLightTheme(binder)) {
        restoreCardTheme(binder)
        return
    }

    val lightContext = api.getContext(binder).withNightMode(Configuration.UI_MODE_NIGHT_NO)
    val colors = CardColors.from(lightContext)
    api.getHolders(binder).forEach { holder ->
        val player = api.getPlayer(holder)
        if (!applyLightExpandedBackground(api, player, lightContext)) {
            player.post {
                if (activeBinders.contains(binder) && shouldUseLightTheme(binder)) {
                    runCatching {
                        applyLightExpandedBackground(api, player, lightContext)
                    }.onFailure {
                HookLogger.e(TAG, "应用延后的实时通知背景失败", it)
                    }
                }
            }
        }
        applyLightForeground(api, holder, colors)
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyAppearance(binder: Any, allowCoverColor: Boolean) {
    applyMode(binder, allowCoverColor)
    val api = nativeApi ?: return
    if (IslandExpandedMediaBackgroundController.isActive()) {
        if (allowCoverColor) IslandExpandedMediaBackgroundController.apply(binder, api)
    } else {
        IslandExpandedMediaBackgroundController.restore(binder)
        applyCardTheme(binder)
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyLightForeground(api: NativeApi, holder: Any, colors: CardColors) {
    val seekBar = api.getSeekBar(holder)
    val state = IslandExpandedMediaAmbientFlowHooker.seekBarThemeStates.getOrPut(seekBar) {
        SeekBarThemeState(
            originalColorFilter = api.getSeekBarShaderColorFilter(seekBar),
            originalHeadGlowAlpha = api.getSeekBarHeadGlowAlpha(seekBar)
        )
    }
    state.suppressHeadGlow = true
    api.applyLightForeground(holder, colors)
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyLightExpandedBackground(
    api: NativeApi,
    player: View,
    notificationLightContext: Context
): Boolean {
    val target = api.findExpandedBackgroundTarget(player) ?: return false
    applyLightExpandedBackground(api, target, notificationLightContext)
    return true
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyLightExpandedBackground(
    api: NativeApi,
    target: IslandExpandedBackgroundTarget,
    notificationLightContext: Context
) {
    val state = themeStates.getOrPut(target.owner) {
        val miniBar = api.getMiniBar(target)
        ViewThemeState(
            target = target,
            miniBar = miniBar,
            originalMiniBarTint = miniBar?.backgroundTintList,
            notificationLightContext = notificationLightContext
        )
    }
    state.notificationLightContext = notificationLightContext
    val islandLightContext = target.expandedView.context.withNightMode(
        Configuration.UI_MODE_NIGHT_NO
    )
    api.applyLiveUpdateBackground(target, islandLightContext, notificationLightContext)
    // 横线标识与前景白色层级统一，恢复路径还原 originalMiniBarTint。
    state.miniBar?.backgroundTintList = ColorStateList.valueOf(
        Color.argb(0x99, 0xFF, 0xFF, 0xFF)
    )
}

internal fun IslandExpandedMediaAmbientFlowHooker.shouldUseLightTheme(binder: Any): Boolean {
    val api = nativeApi ?: return false
    return shouldUseLightTheme(api.getContext(binder))
}

/**
 * 原生 `updateBackgroundBg` 每次执行都会重建深色背景（OS4 上还会重建液态玻璃材质），
 * 浅色主题如果只在 binder 事件后应用一次，会被随后任意内容更新冲掉；
 * 在原生重建背景后立即重放缓存的浅色主题，保证模块总是最后写入。
 */
internal fun IslandExpandedMediaAmbientFlowHooker.reapplyTrackedLightTheme(view: View) {
    if (IslandExpandedMediaBackgroundController.isActive()) return
    val api = nativeApi ?: return
    val state = themeStates.values.firstOrNull { it.target.expandedView === view } ?: return
    applyLightExpandedBackground(api, state.target, state.notificationLightContext)
}

internal fun IslandExpandedMediaAmbientFlowHooker.shouldUseLightTheme(context: Context): Boolean {
    return when (currentCardTheme()) {
        RootConstants.MEDIA_CARD_THEME_ALWAYS_LIGHT -> true
        RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK -> false
        else -> !context.resources.configuration.isNightMode
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.restoreTrackedTheme(view: View, api: NativeApi) {
    themeStates.remove(view)?.let { state ->
        IslandExpandedMediaAmbientFlowHooker.lightBackgroundModes.remove(state.target.expandedView)
        state.miniBar?.backgroundTintList = state.originalMiniBarTint
        api.restoreNativeExpandedBackground(state.target)
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.restoreCardTheme(binder: Any) {
    val api = nativeApi ?: return
    api.getHolders(binder).forEach { holder ->
        val player = api.getPlayer(holder)
        val seekBar = api.getSeekBar(holder)
        api.findExpandedBackgroundTarget(player)?.let { target ->
            restoreTrackedTheme(target.owner, api)
        }
        IslandExpandedMediaAmbientFlowHooker.seekBarThemeStates.remove(seekBar)?.let { state ->
            api.setSeekBarShaderColorFilter(seekBar, state.originalColorFilter)
            api.setSeekBarHeadGlowAlpha(seekBar, state.originalHeadGlowAlpha)
        }
        restoringNativeForeground.set(true)
        try {
            api.applyNativeForeground(binder, holder)
        } finally {
            restoringNativeForeground.remove()
        }
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyMediaElements(binder: Any) {
    val api = nativeApi ?: run {
        MediaCardDiagnosticLogger.log(
            stage = "island_expanded_media",
            event = "media_elements_apply_skipped",
            reason = "native_api_unavailable",
            details = "binder=${MediaCardDiagnosticLogger.identity(binder)}",
        )
        return
    }
    val coverStyle = currentCoverStyle()
    val hideCoverSource = hideCoverSource()
    val hideDeviceSwitch = hideDeviceSwitch()
    val playbackActive = api.isPlaying(binder)
    val holders = api.getHolders(binder)
    MediaCardDiagnosticLogger.log(
        stage = "island_expanded_media",
        event = "media_elements_apply_begin",
        details = "binder=${MediaCardDiagnosticLogger.identity(binder)},holders=${holders.size},coverStyle=$coverStyle,hideCover=$hideCoverSource,hideDevice=$hideDeviceSwitch,playing=$playbackActive",
    )
    holders.forEach { holder ->
        IslandExpandedMediaElementController.apply(
            elements = api.getMediaElements(holder),
            coverStyle = coverStyle,
            hideCoverSource = hideCoverSource,
            hideDeviceSwitch = hideDeviceSwitch,
            playbackActive = playbackActive
        )
    }
    MediaCardDiagnosticLogger.log(
        stage = "island_expanded_media",
        event = "media_elements_apply_complete",
        details = "binder=${MediaCardDiagnosticLogger.identity(binder)},holders=${holders.size},coverStyle=$coverStyle,hideCover=$hideCoverSource,hideDevice=$hideDeviceSwitch,playing=$playbackActive",
    )
}

internal fun IslandExpandedMediaAmbientFlowHooker.restoreMediaElements(binder: Any) {
    val api = nativeApi ?: return
    api.getHolders(binder).forEach { holder ->
        IslandExpandedMediaElementController.restore(api.getMediaElements(holder))
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyFakeMediaElements(
    fakeContentView: ViewGroup,
    binder: Any?,
    api: NativeApi
) {
    val coverStyle = currentCoverStyle()
    val hideCoverSource = hideCoverSource()
    val hideDeviceSwitch = hideDeviceSwitch()
    if (
        coverStyle == RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT &&
        !hideCoverSource &&
        !hideDeviceSwitch
    ) {
        logFakeMediaSkip("no_override_requested", fakeContentView, binder)
        return
    }
    val fakeExpandedView = fakeContentView.javaClass.methods.firstOrNull {
        it.name == "getFakeExpandedView" && it.parameterTypes.isEmpty()
    }?.invoke(fakeContentView) as? View ?: run {
        logFakeMediaSkip("fake_expanded_view_unavailable", fakeContentView, binder)
        return
    }
    val activeBinder = binder
        ?: synchronized(activeBinders) { activeBinders.firstOrNull() }
        ?: run {
            logFakeMediaSkip("binder_unavailable", fakeContentView, binder)
            return
        }
    val referenceElements = api.getHolders(activeBinder).firstNotNullOfOrNull { holder ->
        runCatching { api.getMediaElements(holder) }.getOrNull()
    } ?: run {
        logFakeMediaSkip("reference_holder_unavailable", fakeContentView, activeBinder)
        return
    }
    IslandExpandedMediaElementController.applyToFakeView(
        fakeExpandedView = fakeExpandedView,
        referenceElements = referenceElements,
        coverStyle = coverStyle,
        hideCoverSource = hideCoverSource,
        hideDeviceSwitch = hideDeviceSwitch
    )
}

internal fun IslandExpandedMediaAmbientFlowHooker.logFakeMediaSkip(reason: String, contentView: View, binder: Any?) {
    if (!BuildConfig.DEBUG) return
    MediaCardDiagnosticLogger.log(
        stage = "island_fake_media_resolve",
        event = "apply_skipped",
        reason = reason,
        details = "build=${BuildConfig.VERSION_CODE},fake=${MediaCardDiagnosticLogger.view(contentView)}," +
            "binder=${MediaCardDiagnosticLogger.identity(binder)}",
        positionSample = true,
    )
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyMode(binder: Any, allowCoverColor: Boolean) {
    val api = nativeApi ?: return
    val views = api.getMusicBgViews(binder)
    if (views.isEmpty()) return

    if (IslandExpandedMediaBackgroundController.isActive()) {
        removeCustomFlow(binder)
        binderStates[binder]?.request?.incrementAndGet()
        views.forEach { view -> hideAmbientFlow(view, api) }
        return
    }

    when (currentMode()) {
        RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DISABLED -> {
            removeCustomFlow(binder)
            binderStates[binder]?.request?.incrementAndGet()
            views.forEach { view -> hideAmbientFlow(view, api) }
        }

        RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_COVER_COLOR -> {
            removeCustomFlow(binder)
            views.forEach { restoreViewAlpha(it) }
            if (allowCoverColor) scheduleCoverColors(binder, views.first(), api)
        }

        RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL -> {
            views.forEach { view -> hideAmbientFlow(view, api) }
            val state = binderStates.getOrPut(binder) { BinderState() }
            val customViews = syncCustomFlowViews(state, views)
            if (customViews.isEmpty()) return
            customViews.forEach { configureCustomFlowView(binder, state, it, api) }
            if (allowCoverColor) scheduleCustomFlowColors(binder, state, api)
        }

        else -> {
            removeCustomFlow(binder)
            binderStates[binder]?.request?.incrementAndGet()
            views.forEach { restoreViewAlpha(it) }
        }
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.syncCustomFlowViews(
    state: BinderState,
    anchors: List<View>
): List<MediaFlowBackgroundView> {
    val currentAnchors = anchors.toSet()
    state.customViews.keys.filter { it !in currentAnchors }.forEach { staleAnchor ->
        state.customViews.remove(staleAnchor)?.let { staleView ->
            (staleView.parent as? ViewGroup)?.removeView(staleView)
        }
    }
    return anchors.mapNotNull { anchor -> ensureCustomFlowView(state, anchor) }
}

internal fun IslandExpandedMediaAmbientFlowHooker.ensureCustomFlowView(
    state: BinderState,
    anchor: View
): MediaFlowBackgroundView? {
    state.customViews[anchor]?.takeIf { it.parent === anchor.parent }?.let { return it }
    state.customViews.remove(anchor)?.let { staleView ->
        (staleView.parent as? ViewGroup)?.removeView(staleView)
    }
    val parent = anchor.parent as? ViewGroup ?: return null
    val ownedViews = state.customViews.values.toSet()
    for (index in parent.childCount - 1 downTo 0) {
        val child = parent.getChildAt(index)
        if (child.tag == CUSTOM_FLOW_VIEW_TAG && child !in ownedViews) {
            parent.removeViewAt(index)
        }
    }
    val layoutParams = MediaFlowOverlayLayout.copyForOverlay(anchor.layoutParams) ?: return null
    val view = MediaFlowBackgroundView(anchor.context, state.customTimeline, appleMusicStyle = true).apply {
        tag = CUSTOM_FLOW_VIEW_TAG
        outlineProvider = anchor.outlineProvider
        clipToOutline = anchor.clipToOutline
    }
    val index = (parent.indexOfChild(anchor) + 1).coerceAtMost(parent.childCount)
    parent.addView(view, index, layoutParams)
    state.customViews[anchor] = view
    return view
}

internal fun IslandExpandedMediaAmbientFlowHooker.configureCustomFlowView(
    binder: Any,
    state: BinderState,
    view: MediaFlowBackgroundView,
    api: NativeApi
) {
    view.visibility = if (state.customArtwork != null) View.VISIBLE else View.INVISIBLE
    view.update(
        artwork = state.customArtwork,
        tone = if (shouldUseLightTheme(binder)) MediaFlowTone.LIGHT else MediaFlowTone.DARK,
        playing = api.isPlaying(binder) && state.customArtwork != null
    )
}

internal fun IslandExpandedMediaAmbientFlowHooker.scheduleCustomFlowColors(
    binder: Any,
    state: BinderState,
    api: NativeApi
) {
    val drawable = api.getArtwork(binder) ?: return
    val token = "${System.identityHashCode(drawable)}:${drawable.constantState?.hashCode() ?: 0}"
    if (state.customColorToken == token && state.customArtwork != null) {
        state.customViews.values.toList().forEach {
            configureCustomFlowView(binder, state, it, api)
        }
        return
    }
    val bitmap = MediaArtworkSampler.sample(drawable) ?: return
    state.customColorToken = token
    // Keep the displayed artwork while the next request is prepared.
    val request = state.request.incrementAndGet()
    runCatching {
        colorExecutor.execute {
            if (binderStates[binder] !== state || state.request.get() != request) {
                bitmap.recycle()
                return@execute
            }
            val artwork = runCatching { MediaFlowArtwork.prepare(bitmap, blur = false) }
                .onFailure { HookLogger.e(TAG, "提取展开态媒体柔光颜色失败", it) }
                .getOrNull()
            bitmap.recycle()
            Handler(Looper.getMainLooper()).post {
                if (binderStates[binder] !== state || state.request.get() != request) return@post
                if (!isCustomMode(currentMode()) || artwork == null) {
                    if (artwork == null) state.customColorToken = null
                    return@post
                }
                state.customArtwork = artwork
                state.customViews.values.toList().forEach {
                    configureCustomFlowView(binder, state, it, api)
                }
            }
        }
    }.onFailure { error ->
        bitmap.recycle()
        HookLogger.e(TAG, "调度展开态媒体柔光取色失败", error)
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.removeCustomFlow(binder: Any) {
    synchronized(fakeFlowStates) {
        fakeFlowStates.filterValues { it.binder === binder }.keys.toList()
    }.forEach { removeCustomFakeFlow(it) }
    val state = binderStates[binder] ?: return
    state.customViews.values.toList().forEach { view ->
        view.update(
            tone = MediaFlowTone.DARK,
            playing = false
        )
        (view.parent as? ViewGroup)?.removeView(view)
    }
    state.customViews.clear()
    state.customColorToken = null
    state.customArtwork = null
}

internal fun IslandExpandedMediaAmbientFlowHooker.scheduleCoverColors(binder: Any, primaryView: View, api: NativeApi) {
    val drawable = api.getArtwork(binder) ?: return
    val token = "${System.identityHashCode(drawable)}:${drawable.constantState?.hashCode() ?: 0}"
    val state = binderStates.getOrPut(binder) { BinderState() }
    if (state.colorToken == token) {
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                TAG,
                "流光取色: token未变 重放=${state.palette?.mainColor?.let { "#%06X".format(it) } ?: "null"}"
            )
        }
        state.palette?.let { palette ->
            api.setGradientColor(primaryView, palette.mainColor, palette.colors)
        }
        return
    }
    if (BuildConfig.DEBUG) {
        HookLogger.d(
            TAG,
            "流光取色: 新token old=${state.colorToken} new=$token " +
                "drawable=${drawable.javaClass.simpleName}@${System.identityHashCode(drawable)}"
        )
    }
    val bitmap = MediaArtworkSampler.sample(drawable) ?: return
    state.colorToken = token
    state.palette = null
    val request = state.request.incrementAndGet()

    runCatching {
        colorExecutor.execute {
            if (binderStates[binder] !== state || state.request.get() != request) {
                bitmap.recycle()
                return@execute
            }
            val palette = runCatching {
                MediaAmbientFlowPaletteExtractor.extractCoverMainColor(bitmap)
                    ?.let(api::createPalette)
            }
                .onFailure { HookLogger.e(TAG, "提取展开态媒体颜色失败", it) }
                .getOrNull()
            bitmap.recycle()
            primaryView.post {
                val current = binderStates[binder]
                if (current !== state || current.request.get() != request) return@post
                if (palette == null) {
                    current.colorToken = null
                    return@post
                }
                if (currentMode() !=
                    RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_COVER_COLOR
                ) return@post
                val currentPrimary = api.getMusicBgViews(binder).firstOrNull() ?: return@post
                current.palette = palette
                if (BuildConfig.DEBUG) {
                    HookLogger.d(TAG, "流光取色应用: mainColor=#%06X".format(palette.mainColor))
                }
                api.setGradientColor(currentPrimary, palette.mainColor, palette.colors)
            }
        }
    }.onFailure { error ->
        bitmap.recycle()
        HookLogger.e(TAG, "调度展开态媒体取色任务失败", error)
    }
}

/**
 * 封面图标更新（setFixIcon）触发的流光快速路径：图标到达时 binder 的 artWorkDrawable
 * 往往还是旧封面，按 binder token 取色会重放旧色。此处直接对新图标取色并应用到当前
 * 活跃 binder；之后 binder 路径的慢速刷新会得到相同主色（确定性提取），重复应用无副作用。
 */
internal fun IslandExpandedMediaAmbientFlowHooker.onIslandAlbumIconUpdated(iconDrawable: Drawable?) {
    if (iconDrawable == null) return
    if (!SystemUiEnhancementGate.isEnabled()) return
    if (nativeApi == null) return
    if (currentMode() != RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_COVER_COLOR) return
    val token = "icon:${System.identityHashCode(iconDrawable)}:${iconDrawable.constantState?.hashCode() ?: 0}"
    val cached = iconColorPalette
    if (iconColorToken == token && cached != null) {
        applyCoverPalette(cached)
        return
    }
    // 图标是视图持有的 Drawable，生命周期不受控，同步采样成小图后再异步取色。
    val bitmap = MediaArtworkSampler.sample(iconDrawable) ?: return
    iconColorToken = token
    iconColorPalette = null
    val request = iconColorRequest.incrementAndGet()
    if (BuildConfig.DEBUG) {
        HookLogger.d(TAG, "流光取色(图标): 调度 token=$token bitmap=${bitmap.width}x${bitmap.height}")
    }
    runCatching {
        colorExecutor.execute {
            val palette = runCatching {
                MediaAmbientFlowPaletteExtractor.extractCoverMainColor(bitmap)
                    ?.let { nativeApi?.createPalette(it) }
            }
                .onFailure { HookLogger.e(TAG, "提取展开态媒体图标颜色失败", it) }
                .getOrNull()
            bitmap.recycle()
            if (palette == null || iconColorRequest.get() != request) return@execute
            iconColorPalette = palette
            Handler(Looper.getMainLooper()).post {
                if (iconColorRequest.get() != request) return@post
                if (currentMode() != RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_COVER_COLOR) return@post
                if (BuildConfig.DEBUG) {
                    HookLogger.d(TAG, "流光取色应用(图标): mainColor=#%06X".format(palette.mainColor))
                }
                applyCoverPalette(palette)
            }
        }
    }.onFailure { error ->
        bitmap.recycle()
        HookLogger.e(TAG, "调度展开态媒体图标取色任务失败", error)
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.applyCoverPalette(palette: MediaAmbientFlowPalette) {
    val api = nativeApi ?: return
    val snapshot = synchronized(activeBinders) { activeBinders.toList() }
    snapshot.forEach { binder ->
        runCatching {
            api.getMusicBgViews(binder).firstOrNull()?.let { view ->
                api.setGradientColor(view, palette.mainColor, palette.colors)
            }
        }.onFailure { error ->
            HookLogger.e(TAG, "应用展开态媒体图标流光颜色失败", error)
        }
    }
}

internal fun IslandExpandedMediaAmbientFlowHooker.cleanupBinder(binder: Any) {
    activeBinders.remove(binder)
    IslandExpandedMediaBackgroundController.restore(binder)
    restoreCardTheme(binder)
    restoreMediaElements(binder)
    removeCustomFlow(binder)
    binderStates.remove(binder)?.request?.incrementAndGet()
    nativeApi?.getMusicBgViews(binder)?.forEach { restoreViewAlpha(it) }
}

internal fun IslandExpandedMediaAmbientFlowHooker.restoreViewAlpha(view: View) {
    val original = view.getTag(ORIGINAL_ALPHA_TAG_KEY) as? Float ?: return
    view.setTag(ORIGINAL_ALPHA_TAG_KEY, null)
    if (view.alpha == 0f) view.alpha = original
}

internal fun IslandExpandedMediaAmbientFlowHooker.hideAmbientFlow(view: View, api: NativeApi) {
    val alreadyHidden = view.getTag(ORIGINAL_ALPHA_TAG_KEY) != null && view.alpha == 0f
    if (alreadyHidden) return
    if (view.getTag(ORIGINAL_ALPHA_TAG_KEY) == null) {
        view.setTag(ORIGINAL_ALPHA_TAG_KEY, view.alpha)
    }
    view.alpha = 0f
    api.pause(view)
}

internal fun IslandExpandedMediaAmbientFlowHooker.isExpandedIslandView(view: View): Boolean {
    var current: View? = view
    repeat(8) {
        val parent = current?.parent ?: return false
        if (parent.javaClass.name.contains(".notification.mediaisland.")) return true
        current = parent as? View ?: return false
    }
    return false
}

internal fun IslandExpandedMediaAmbientFlowHooker.currentMode(): Int {
    if (!SystemUiEnhancementGate.isEnabled()) {
        return RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DEFAULT
    }
    return prefs?.getInt(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE,
        RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE
    )?.coerceIn(
        RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DEFAULT,
        RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL
    ) ?: RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE
}

internal fun IslandExpandedMediaAmbientFlowHooker.isCustomMode(mode: Int): Boolean =
    mode == RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL

internal fun IslandExpandedMediaAmbientFlowHooker.currentCardTheme(): Int {
    if (!SystemUiEnhancementGate.isEnabled()) {
        return RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME
    }
    return prefs?.getInt(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME,
        RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME
    )?.coerceIn(
        RootConstants.MEDIA_CARD_THEME_FOLLOW_SYSTEM,
        RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK
    ) ?: RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME
}

internal fun IslandExpandedMediaAmbientFlowHooker.currentCoverStyle(): Int {
    if (!SystemUiEnhancementGate.isEnabled()) {
        return RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT
    }
    return prefs?.getInt(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_COVER_STYLE,
        RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_COVER_STYLE
    )?.coerceIn(
        RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT,
        RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_HIDDEN
    ) ?: RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_COVER_STYLE
}

internal fun IslandExpandedMediaAmbientFlowHooker.hideCoverSource(): Boolean {
    if (!SystemUiEnhancementGate.isEnabled()) return false
    return prefs?.getBoolean(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_COVER_SOURCE,
        RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_COVER_SOURCE
    ) ?: RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_COVER_SOURCE
}

internal fun IslandExpandedMediaAmbientFlowHooker.hideDeviceSwitch(): Boolean {
    if (!SystemUiEnhancementGate.isEnabled()) return false
    return prefs?.getBoolean(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_DEVICE_SWITCH,
        RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_DEVICE_SWITCH
    ) ?: RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_DEVICE_SWITCH
}

internal val Configuration.isNightMode: Boolean
    get() = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

internal fun Context.withNightMode(nightMode: Int): Context {
    val configuration = Configuration(resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    }
    return createConfigurationContext(configuration)
}
