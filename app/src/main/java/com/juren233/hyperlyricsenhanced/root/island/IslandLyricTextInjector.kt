package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.lyric.view.RichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.line.LyricTextPaintOwner
import com.juren233.hyperlyricsenhanced.root.island.view.MaxWidthFrameLayout
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/**
 * Stage-4 / 富歌词大岛视图注入与更新调度。
 *
 * 负责在超级岛卡槽上动态注入 RichLyricLineView (Standard) 或 SpaceGateRichLyricLineView (Split)，
 * 并维护其生命周期恢复及热替换。
 */
internal object IslandLyricTextInjector {
    private const val TAG = "IslandLyricTextInjector"

    fun injectSlots(rootView: ViewGroup, reconfigureExisting: Boolean = true, suppressAnimation: Boolean = false): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)

        var changed = false
        if (config.shouldInjectLeft) {
            changed = injectSlot(rootView, IslandProbeUtils.LEFT_PARENT_NAME, IslandProbeUtils.LEFT_TEST_VIEW_TAG, config.leftMode, reconfigureExisting, config, suppressAnimation) || changed
        } else {
            rootView.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_WRAPPER_TAG)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }

        if (config.shouldInjectRight) {
            changed = injectSlot(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, IslandProbeUtils.RIGHT_TEST_VIEW_TAG, config.rightMode, reconfigureExisting, config, suppressAnimation) || changed
        } else {
            rootView.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }

        if (config.isSplitMode) {
            linkViews(rootView)
        }

        changed = IslandNativeSlotPlacement.apply(rootView, config) || changed
        IslandHostFacade.applyHostSettings(rootView, prefs)
        IslandViewRegistry.refreshInjectedViews(rootView)
        if (changed) {
            IslandAlbumCoverStyleHooker.refreshLeftContentTextShadows()
        }
        if (BuildConfig.DEBUG && (changed || config.adjacentBackgroundTranslation)) {
            logGradientShadowDiagnostic(rootView, config, changed, phase = "inject_return")
            rootView.post {
                logGradientShadowDiagnostic(rootView, config, changed, phase = "posted_after_content")
            }
        }
        return changed
    }

    private fun logGradientShadowDiagnostic(
        rootView: ViewGroup,
        config: IslandSlotRuntimeConfig,
        changed: Boolean,
        phase: String,
    ) {
        if (!BuildConfig.DEBUG) return
        HookLogger.i(
            TAG,
            "[GradientShadowDiag] phase=$phase, changed=$changed, " +
                "activeMode=${config.activeMode}, leftMode=${config.leftMode}, " +
                "rightMode=${config.rightMode}, adjacent=${config.adjacentBackgroundTranslation}, " +
                "supported=${config.supportsAdjacentBackgroundTranslation}, " +
                "targetLeft=${config.adjacentTranslationTargetIsLeft}, " +
                "left=${describeShadowSlot(rootView, IslandProbeUtils.LEFT_TEST_VIEW_TAG)}, " +
                "right=${describeShadowSlot(rootView, IslandProbeUtils.RIGHT_TEST_VIEW_TAG)}",
        )
    }

    private fun describeShadowSlot(rootView: ViewGroup, tag: String): String {
        val target = rootView.findViewWithTag<View>(tag) ?: return "missing"
        val paints = ArrayList<String>()
        fun collect(view: View) {
            if (view is LyricTextPaintOwner) {
                view.forEachDrawingTextPaint { paint ->
                    paints += "${view.javaClass.name}@${System.identityHashCode(view).toString(16)}" +
                        "/paint@${System.identityHashCode(paint).toString(16)}" +
                        "(attached=${view.isAttachedToWindow},visibility=${view.visibility}," +
                        "shadow=${paint.getShadowLayerRadius()}/${paint.getShadowLayerDx()}/" +
                        "${paint.getShadowLayerDy()}/0x${paint.getShadowLayerColor().toUInt().toString(16)})"
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(target)
        return "${target.javaClass.name}@${System.identityHashCode(target).toString(16)}" +
            "(attached=${target.isAttachedToWindow},visibility=${target.visibility}," +
            "tag=${target.tag},paints=[${paints.joinToString(" | ")}])"
    }

    fun restoreExistingSlotsLightweight(rootView: ViewGroup): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)

        var changed = false
        if (config.shouldInjectLeft) {
            changed = restoreExistingSlotLightweight(rootView, IslandProbeUtils.LEFT_PARENT_NAME, IslandProbeUtils.LEFT_TEST_VIEW_TAG) || changed
        }
        if (config.shouldInjectRight) {
            changed = restoreExistingSlotLightweight(rootView, IslandProbeUtils.RIGHT_PARENT_NAME, IslandProbeUtils.RIGHT_TEST_VIEW_TAG) || changed
        }
        changed = IslandNativeSlotPlacement.apply(rootView, config) || changed
        IslandHostFacade.applyHostSettings(rootView, prefs)
        IslandViewRegistry.refreshInjectedViews(rootView)
        return changed
    }

    fun restoreExistingModuleSlotLightweight(rootView: ViewGroup, moduleType: String?): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)

        var changed = false
        if (config.shouldInjectLeft && (moduleType == null || moduleType.endsWith("_1"))) {
            changed = restoreExistingSlotByTagLightweight(rootView, IslandProbeUtils.LEFT_TEST_VIEW_TAG) || changed
        }
        if (config.shouldInjectRight && (moduleType == null || moduleType.endsWith("_2"))) {
            changed = restoreExistingSlotByTagLightweight(rootView, IslandProbeUtils.RIGHT_TEST_VIEW_TAG) || changed
        }
        if (!changed && moduleType != null && !moduleType.endsWith("_1") && !moduleType.endsWith("_2")) {
            if (config.shouldInjectLeft) {
                changed = restoreExistingSlotByTagLightweight(rootView, IslandProbeUtils.LEFT_TEST_VIEW_TAG) || changed
            }
            if (config.shouldInjectRight) {
                changed = restoreExistingSlotByTagLightweight(rootView, IslandProbeUtils.RIGHT_TEST_VIEW_TAG) || changed
            }
        }

        changed = IslandNativeSlotPlacement.apply(rootView, config) || changed
        IslandHostFacade.applyHostSettings(rootView, prefs)
        IslandViewRegistry.refreshInjectedViews(rootView)
        return changed
    }

    fun hasInjectedLyricText(rootView: ViewGroup): Boolean {
        return rootView.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_WRAPPER_TAG) != null ||
            rootView.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG) != null ||
            rootView.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG) != null ||
            rootView.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG) != null
    }

    fun hasVisibleInjectedContent(rootView: ViewGroup): Boolean {
        fun isVisible(tag: String): Boolean {
            return rootView.findViewWithTag<View>(tag)
                ?.visibility
                ?.let { visibility -> visibility != View.GONE }
                ?: false
        }

        return isVisible(IslandProbeUtils.LEFT_TEST_WRAPPER_TAG) ||
            isVisible(IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG) ||
            isVisible(IslandProbeUtils.LEFT_TEST_VIEW_TAG) ||
            isVisible(IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
    }

    fun refreshCurrentContent(rootView: ViewGroup, includeLyricSlots: Boolean = true, force: Boolean = false, suppressAnimation: Boolean = false): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)
        val packageName = LyriconDataBridge.currentLyricPackageName.orEmpty()
        val mediaInfo = MediaMetadataHelper.getMediaInfo(rootView.context, packageName, HookLogger)

        var changed = false
        if (config.shouldInjectLeft && (includeLyricSlots || config.leftMode != 7)) {
            changed = refreshSlotContent(rootView, IslandProbeUtils.LEFT_TEST_VIEW_TAG, config.leftMode, prefs, config, force, suppressAnimation, mediaInfo) || changed
        }
        if (config.shouldInjectRight && (includeLyricSlots || config.rightMode != 7)) {
            changed = refreshSlotContent(rootView, IslandProbeUtils.RIGHT_TEST_VIEW_TAG, config.rightMode, prefs, config, force, suppressAnimation, mediaInfo) || changed
        }

        if (config.isSplitMode) {
            linkViews(rootView)
        }
        return changed
    }

    fun freezeInjectedLyricProgress(rootView: ViewGroup, position: Long) {
        val prefs = HookEntry.instance?.prefs ?: return
        val config = IslandSlotRuntimeConfig.from(prefs)

        if (config.leftMode == 7) {
            freezeLyricView(rootView.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG), position)
        }
        if (config.rightMode == 7) {
            freezeLyricView(rootView.findViewWithTag(IslandProbeUtils.RIGHT_TEST_VIEW_TAG), position)
        }
    }

    fun resumeInjectedContentMotion(rootView: ViewGroup, playbackActive: Boolean) {
        val prefs = HookEntry.instance?.prefs ?: return
        val config = IslandSlotRuntimeConfig.from(prefs)
        var resumed = 0
        if (config.shouldInjectLeft) {
            resumed += resumeSlotMotion(
                rootView.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG),
                config.leftMode,
                config,
                playbackActive,
            )
        }
        if (config.shouldInjectRight) {
            resumed += resumeSlotMotion(
                rootView.findViewWithTag(IslandProbeUtils.RIGHT_TEST_VIEW_TAG),
                config.rightMode,
                config,
                playbackActive,
            )
        }
        if (BuildConfig.DEBUG && resumed > 0) {
            HookLogger.d(
                TAG,
                "真实岛内容动画已恢复: playbackActive=$playbackActive, targets=$resumed",
            )
        }
    }

    private fun resumeSlotMotion(
        view: View?,
        mode: Int,
        config: IslandSlotRuntimeConfig,
        playbackActive: Boolean,
    ): Int {
        when (view) {
            is RichLyricLineView -> {
                view.setPlaybackActive(playbackActive)
                if (IslandContentMotionResumePolicy.shouldRequestMarquee(
                        mode = mode,
                        playbackActive = playbackActive,
                        lyricMarqueeEnabled = config.lyricMarqueeEnabled,
                        metadataMarqueeEnabled = config.metadataMarqueeEnabled,
                    )
                ) {
                    view.requestStartMarquee()
                }
            }
            is SpaceGateRichLyricLineView -> {
                view.setPlaybackActive(playbackActive)
                if (IslandContentMotionResumePolicy.shouldRequestMarquee(
                        mode = mode,
                        playbackActive = playbackActive,
                        lyricMarqueeEnabled = config.lyricMarqueeEnabled,
                        metadataMarqueeEnabled = config.metadataMarqueeEnabled,
                    )
                ) {
                    view.requestStartMarquee()
                }
            }
            else -> return 0
        }
        return 1
    }

    private fun freezeLyricView(view: View?, position: Long) {
        when (view) {
            is RichLyricLineView -> {
                view.setPlaybackActive(false)
                view.setPosition(position)
                view.setPlaybackActive(false)
            }
            is SpaceGateRichLyricLineView -> {
                view.setPlaybackActive(false)
                view.setPosition(position)
                view.setPlaybackActive(false)
            }
        }
    }

    private fun injectSlot(rootView: ViewGroup, parentName: String, viewTag: String, mode: Int, reconfigureExisting: Boolean, config: IslandSlotRuntimeConfig, suppressAnimation: Boolean): Boolean {
        val widthPx = config.widthPx(rootView, parentName) ?: return false

        val parent = IslandViewHelper.findViewByName(rootView, parentName) as? ViewGroup ?: return false
        val container = IslandViewHelper.findViewByName(parent, IslandProbeUtils.TEXT_CONTAINER_NAME) as? ViewGroup ?: return false

        val wrapperTag = "${viewTag}_WRAPPER"

        container.visibility = View.VISIBLE

        val prefs = HookEntry.instance?.prefs ?: return false

        val taggedWrapper = container.findViewWithTag<View>(wrapperTag)
        val existingWrapper = taggedWrapper as? MaxWidthFrameLayout
        if (taggedWrapper != null && existingWrapper == null) {
            (taggedWrapper.parent as? ViewGroup)?.removeView(taggedWrapper)
            HookLogger.i(TAG, "已移除热重载遗留的旧歌词容器: tag=$wrapperTag")
        }
        if (existingWrapper != null) {
            existingWrapper.keepVisible = true
            var changed = updateWrapper(existingWrapper, widthPx, config, parentName)
            val targetView = existingWrapper.findViewWithTag<View>(viewTag)

            if (targetView == null) {
                existingWrapper.addView(createLyricView(rootView, viewTag, config, mode, suppressAnimation), createLyricTextLayoutParams(config))
                changed = true
            } else if (!isViewTypeCorrect(targetView, config.activeMode)) {
                existingWrapper.removeView(targetView)
                IslandSlotContentAssembler.invalidate(targetView)
                existingWrapper.addView(createLyricView(rootView, viewTag, config, mode, suppressAnimation), createLyricTextLayoutParams(config))
                changed = true
            } else {
                changed = restoreTargetView(targetView, config, mode, reconfigureExisting, suppressAnimation) || changed
            }

            if (existingWrapper.visibility != View.VISIBLE) {
                existingWrapper.visibility = View.VISIBLE
                changed = true
            }
            changed = forceWrapperLayout(existingWrapper, container, widthPx) || changed
            hideNativeChildren(container, existingWrapper)
            return changed
        }

        val wrapperView = MaxWidthFrameLayout(rootView.context).apply {
            tag = wrapperTag
            clipChildren = true
            maxWidthPx = widthPx
            keepVisible = true
        }
        updateWrapper(wrapperView, widthPx, config, parentName)
        wrapperView.addView(createLyricView(rootView, viewTag, config, mode, suppressAnimation), createLyricTextLayoutParams(config))

        container.addView(wrapperView, FrameLayout.LayoutParams(wrapperLayoutWidth(config), FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER_VERTICAL or config.wrapperHorizontalGravity(
                isLeft = viewTag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
            )
        })
        hideNativeChildren(container, wrapperView)

        forceWrapperLayout(wrapperView, container, widthPx)

        HookLogger.d(TAG, "已注入歌词视图: tag=$viewTag，激活模式=${config.activeMode}，内容模式=$mode，宽度=${widthPx}px")
        return true
    }

    private fun restoreExistingSlotLightweight(rootView: ViewGroup, parentName: String, viewTag: String): Boolean {
        val parent = IslandViewHelper.findViewByName(rootView, parentName) as? ViewGroup ?: return false
        val container = IslandViewHelper.findViewByName(parent, IslandProbeUtils.TEXT_CONTAINER_NAME) as? ViewGroup ?: return false
        val wrapper = container.findViewWithTag<View>("${viewTag}_WRAPPER") as? MaxWidthFrameLayout
            ?: return false
        val targetView = wrapper.findViewWithTag<View>(viewTag) ?: return false
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)
        val widthPx = config.widthPx(rootView, parentName) ?: return false

        // Apply user geometry before making the fake/real content visible. Otherwise MIUI can
        // start its return animation with the default wrapper padding and the next refresh
        // changes it again, producing a visible layout jump.
        var changed = updateWrapper(wrapper, widthPx, config, parentName)
        wrapper.keepVisible = true
        if (container.visibility != View.VISIBLE) {
            container.visibility = View.VISIBLE
            changed = true
        }
        if (wrapper.visibility != View.VISIBLE) {
            wrapper.visibility = View.VISIBLE
            changed = true
        }
        if (targetView.visibility != View.VISIBLE) {
            targetView.visibility = View.VISIBLE
            changed = true
        }
        changed = forceWrapperLayout(wrapper, container, widthPx) || changed
        hideNativeChildren(container, wrapper)
        return changed
    }

    private fun restoreExistingSlotByTagLightweight(rootView: ViewGroup, viewTag: String): Boolean {
        val wrapper = rootView.findViewWithTag<View>("${viewTag}_WRAPPER") as? MaxWidthFrameLayout
            ?: return false
        val targetView = wrapper.findViewWithTag<View>(viewTag) ?: return false
        val container = wrapper.parent as? ViewGroup ?: return false
        val parentName = findIslandParentName(wrapper) ?: return false
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)
        val widthPx = config.widthPx(rootView, parentName) ?: return false

        // Same pre-visibility synchronization for module-level fake/real restores.
        var changed = updateWrapper(wrapper, widthPx, config, parentName)
        wrapper.keepVisible = true
        if (container.visibility != View.VISIBLE) {
            container.visibility = View.VISIBLE
            changed = true
        }
        if (wrapper.visibility != View.VISIBLE) {
            wrapper.visibility = View.VISIBLE
            changed = true
        }
        if (targetView.visibility != View.VISIBLE) {
            targetView.visibility = View.VISIBLE
            changed = true
        }
        changed = forceWrapperLayout(wrapper, container, widthPx) || changed
        hideNativeChildren(container, wrapper)
        return changed
    }

    private fun findIslandParentName(view: View): String? {
        var current = view.parent as? View
        while (current != null) {
            val name = if (current.id != View.NO_ID) {
                runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull()
            } else {
                null
            }
            if (name == IslandProbeUtils.LEFT_PARENT_NAME ||
                name == IslandProbeUtils.RIGHT_PARENT_NAME
            ) {
                return name
            }
            current = current.parent as? View
        }
        return null
    }

    private fun updateWrapper(wrapper: MaxWidthFrameLayout, widthPx: Int, config: IslandSlotRuntimeConfig, parentName: String): Boolean {
        var changed = false
        val paddingLeft = config.paddingLeftPx(wrapper, parentName)
        val paddingRight = config.paddingRightPx(wrapper, parentName)
        if (wrapper.paddingLeft != paddingLeft || wrapper.paddingRight != paddingRight) {
            wrapper.setPadding(paddingLeft, wrapper.paddingTop, paddingRight, wrapper.paddingBottom)
            changed = true
        }
        if (wrapper.minimumWidth != 0) {
            wrapper.minimumWidth = 0
            changed = true
        }
        if (wrapper.maxWidthPx != widthPx) {
            wrapper.maxWidthPx = widthPx
            changed = true
        }
        val layoutParams = wrapper.layoutParams
        val expectedWidth = wrapperLayoutWidth(config)
        val expectedGravity = Gravity.CENTER_VERTICAL or config.wrapperHorizontalGravity(
            isLeft = config.isLeftParent(parentName)
        )
        if (layoutParams is FrameLayout.LayoutParams && (
                layoutParams.width != expectedWidth ||
                    layoutParams.height != FrameLayout.LayoutParams.MATCH_PARENT ||
                    (config.dynamicWidthEnabled && layoutParams.gravity != expectedGravity)
            )) {
            layoutParams.width = expectedWidth
            layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            if (config.dynamicWidthEnabled) layoutParams.gravity = expectedGravity
            wrapper.layoutParams = layoutParams
            changed = true
        }
        if (changed) wrapper.requestLayout()
        return changed
    }

    private fun isViewTypeCorrect(view: View, activeMode: Int): Boolean {
        return if (activeMode == 1) {
            view is SpaceGateRichLyricLineView
        } else {
            view is RichLyricLineView
        }
    }

    private fun restoreTargetView(targetView: View, config: IslandSlotRuntimeConfig, mode: Int, reconfigure: Boolean, suppressAnimation: Boolean = false): Boolean {
        var changed = false
        val layoutParams = targetView.layoutParams
        val expectedWidth = lyricTextLayoutWidth(config)
        if (layoutParams != null &&
            (layoutParams.width != expectedWidth ||
                layoutParams.height != FrameLayout.LayoutParams.MATCH_PARENT)
        ) {
            layoutParams.width = expectedWidth
            layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            targetView.layoutParams = layoutParams
            changed = true
        }
        if (targetView.visibility != View.VISIBLE) {
            targetView.visibility = View.VISIBLE
            changed = true
        }

        val prefs = HookEntry.instance?.prefs
        if (reconfigure && prefs != null) {
            changed = IslandSlotContentAssembler.applySlotContent(
                targetView,
                prefs,
                config,
                mode,
                force = true,
                suppressAnimation = suppressAnimation
            ) || changed
        }
        return changed
    }

    private fun forceWrapperLayout(wrapper: MaxWidthFrameLayout, container: ViewGroup, widthPx: Int): Boolean {
        val wasZeroWidth = wrapper.width == 0 || wrapper.measuredWidth == 0
        if (!wasZeroWidth) {
            return false
        }

        val heightPx = if (container.height > 0) container.height else container.measuredHeight
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.AT_MOST)
        val heightSpec = if (heightPx > 0) {
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
        wrapper.measure(widthSpec, heightSpec)
        val finalHeight = if (heightPx > 0) heightPx else wrapper.measuredHeight
        wrapper.layout(0, 0, wrapper.measuredWidth, finalHeight)
        return wasZeroWidth
    }

    private fun wrapperLayoutWidth(config: IslandSlotRuntimeConfig): Int {
        return if (config.isSplitMode || config.dynamicWidthEnabled) {
            FrameLayout.LayoutParams.WRAP_CONTENT
        } else {
            FrameLayout.LayoutParams.MATCH_PARENT
        }
    }

    private fun lyricTextLayoutWidth(config: IslandSlotRuntimeConfig): Int {
        return if (config.dynamicWidthEnabled && !config.isSplitMode) {
            FrameLayout.LayoutParams.WRAP_CONTENT
        } else {
            FrameLayout.LayoutParams.MATCH_PARENT
        }
    }

    private fun createLyricTextLayoutParams(config: IslandSlotRuntimeConfig): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            lyricTextLayoutWidth(config),
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    private fun createLyricView(rootView: ViewGroup, tagValue: String, config: IslandSlotRuntimeConfig, mode: Int, suppressAnimation: Boolean = false): View {
        val prefs = HookEntry.instance?.prefs
        val view = if (config.isSplitMode) {
            SpaceGateRichLyricLineView(rootView.context)
        } else {
            RichLyricLineView(rootView.context)
        }
        view.tag = tagValue

        if (prefs != null) {
            IslandSlotContentAssembler.applySlotContent(view, prefs, config, mode, force = true, suppressAnimation = true)
        }
        return view
    }

    private fun refreshSlotContent(
        rootView: ViewGroup,
        viewTag: String,
        mode: Int,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        force: Boolean,
        suppressAnimation: Boolean,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ): Boolean {
        val view = rootView.findViewWithTag<View>(viewTag) ?: return false
        val isLeft = viewTag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        // The adjacent-translation slot is temporarily converted from its
        // normal metadata mode into a lyric slot by BaseIslandRenderer. The
        // lightweight restore/width-refresh path must make the same choice;
        // otherwise it re-applies mode 5/6 and overwrites the translation side
        // with the original content after it was shown.
        val adjacentTranslation = IslandSlotContentAssembler.buildAdjacentTranslationLine(
            prefs = prefs,
            config = config,
            isLeft = isLeft
        )
        val effectiveMode = if (adjacentTranslation != null) 7 else mode
        return IslandSlotContentAssembler.applySlotContent(
            view,
            prefs,
            config,
            effectiveMode,
            lineOverride = adjacentTranslation,
            force = force,
            suppressAnimation = suppressAnimation,
            mediaInfo = mediaInfo
        )
    }

    fun linkViews(rootView: ViewGroup) {
        val leftView = rootView.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG) as? SpaceGateRichLyricLineView
        val rightView = rootView.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG) as? SpaceGateRichLyricLineView

        leftView?.main?.spaceGateEnabled = false
        leftView?.secondary?.spaceGateEnabled = false
        rightView?.main?.spaceGateEnabled = false
        rightView?.secondary?.spaceGateEnabled = false

        if (leftView != null && rightView != null) {
            IslandHostFacade.logCameraCutoutInfo(rootView)
        }
    }

    private fun hideNativeChildren(container: ViewGroup, keepView: View) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.visibility = if (child == keepView) View.VISIBLE else View.GONE
        }
    }
}

internal object IslandContentMotionResumePolicy {
    fun shouldRequestMarquee(
        mode: Int,
        playbackActive: Boolean,
        lyricMarqueeEnabled: Boolean,
        metadataMarqueeEnabled: Boolean,
    ): Boolean {
        if (!playbackActive) return false
        return if (mode == 7) lyricMarqueeEnabled else mode in 1..6 && metadataMarqueeEnabled
    }
}
