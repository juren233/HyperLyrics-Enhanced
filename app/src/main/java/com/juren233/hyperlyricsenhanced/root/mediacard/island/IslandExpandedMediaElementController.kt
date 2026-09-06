package com.juren233.hyperlyricsenhanced.root.mediacard.island

import android.graphics.Outline
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaCoverRotationController
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal object IslandExpandedMediaElementController {
    private val states = Collections.synchronizedMap(
        WeakHashMap<View, ElementState>()
    )
    private val circleOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    fun apply(
        elements: IslandExpandedMediaElements,
        coverStyle: Int,
        hideCoverSource: Boolean,
        hideDeviceSwitch: Boolean,
        playbackActive: Boolean
    ) {
        MediaCardDiagnosticLogger.log(
            stage = "island_media_elements",
            event = "apply_begin",
            details = "albumView=${MediaCardDiagnosticLogger.view(elements.albumView)},albumImage=${MediaCardDiagnosticLogger.view(elements.albumImage)},coverSource=${MediaCardDiagnosticLogger.view(elements.coverSource)},deviceSwitch=${MediaCardDiagnosticLogger.view(elements.deviceSwitch)},coverStyle=$coverStyle,hideCover=$hideCoverSource,hideDevice=$hideDeviceSwitch,playing=$playbackActive",
        )
        if (
            coverStyle == RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT &&
            !hideCoverSource &&
            !hideDeviceSwitch
        ) {
            restore(elements)
            return
        }

        val state = states.getOrPut(elements.albumView) {
            ElementState.capture(elements)
        }

        when (coverStyle) {
            RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_CIRCLE -> {
                state.restoreHiddenCover()
                state.applyCircle()
                MediaCoverRotationController.detach(elements.albumImage)
            }

            RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_ROTATING_CIRCLE -> {
                state.restoreHiddenCover()
                state.applyCircle()
                MediaCoverRotationController.attach(elements.albumImage, playbackActive)
            }

            RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_HIDDEN -> {
                MediaCoverRotationController.detach(elements.albumImage)
                state.restoreOutlines()
                state.hideCover()
            }

            else -> {
                MediaCoverRotationController.detach(elements.albumImage)
                state.restoreCover()
            }
        }

        state.applyCoverSourceHidden(hideCoverSource)
        state.applyDeviceSwitchHidden(hideDeviceSwitch)
        state.updateVisibilityGuard()
        MediaCardDiagnosticLogger.log(
            stage = "island_media_elements",
            event = "apply_complete",
            details = "albumView=${MediaCardDiagnosticLogger.view(elements.albumView)},albumImage=${MediaCardDiagnosticLogger.view(elements.albumImage)},coverSource=${MediaCardDiagnosticLogger.view(elements.coverSource)},deviceSwitch=${MediaCardDiagnosticLogger.view(elements.deviceSwitch)},coverStyle=$coverStyle,hideCover=$hideCoverSource,hideDevice=$hideDeviceSwitch,playing=$playbackActive",
        )
    }

    fun restore(elements: IslandExpandedMediaElements) {
        MediaCardDiagnosticLogger.log(
            stage = "island_media_elements",
            event = "restore_begin",
            details = "albumView=${MediaCardDiagnosticLogger.view(elements.albumView)},coverSource=${MediaCardDiagnosticLogger.view(elements.coverSource)},deviceSwitch=${MediaCardDiagnosticLogger.view(elements.deviceSwitch)}",
        )
        val state = states.remove(elements.albumView) ?: run {
            MediaCardDiagnosticLogger.log(
                stage = "island_media_elements",
                event = "restore_skipped",
                reason = "no_saved_state",
                details = "albumView=${MediaCardDiagnosticLogger.view(elements.albumView)}",
            )
            MediaCoverRotationController.detach(elements.albumImage)
            return
        }
        MediaCoverRotationController.detach(elements.albumImage)
        state.restoreAll()
        MediaCardDiagnosticLogger.log(
            stage = "island_media_elements",
            event = "restore_complete",
            details = "albumView=${MediaCardDiagnosticLogger.view(elements.albumView)},coverSource=${MediaCardDiagnosticLogger.view(elements.coverSource)},deviceSwitch=${MediaCardDiagnosticLogger.view(elements.deviceSwitch)}",
        )
    }

    fun applyToFakeView(
        fakeExpandedView: View,
        referenceElements: IslandExpandedMediaElements,
        coverStyle: Int,
        hideCoverSource: Boolean,
        hideDeviceSwitch: Boolean
    ) {
        if (
            coverStyle == RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT &&
            !hideCoverSource &&
            !hideDeviceSwitch
        ) {
            return
        }

        val albumViewId = referenceElements.albumView.id
        val albumImageId = referenceElements.albumImage.id
        val coverSourceId = referenceElements.coverSource.id
        val deviceSwitchId = referenceElements.deviceSwitch.id
        val sourceBefore = if (BuildConfig.DEBUG && coverSourceId != 0) {
            fakeExpandedView.findViewById<View>(coverSourceId)?.visibility
        } else null
        val deviceBefore = if (BuildConfig.DEBUG && deviceSwitchId != 0) {
            fakeExpandedView.findViewById<View>(deviceSwitchId)?.visibility
        } else null

        when (coverStyle) {
            RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_CIRCLE,
            RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_ROTATING_CIRCLE -> {
                if (albumViewId != 0) {
                    fakeExpandedView.findViewById<View>(albumViewId)?.let { albumView ->
                        albumView.outlineProvider = circleOutlineProvider
                        albumView.clipToOutline = false
                        albumView.invalidateOutline()
                    }
                }
                if (albumImageId != 0) {
                    fakeExpandedView.findViewById<View>(albumImageId)?.let { albumImage ->
                        albumImage.outlineProvider = circleOutlineProvider
                        albumImage.clipToOutline = true
                        albumImage.invalidateOutline()
                    }
                }
            }

            RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_HIDDEN -> {
                if (albumViewId != 0) {
                    fakeExpandedView.findViewById<View>(albumViewId)?.let { it.visibility = View.GONE }
                }
            }
        }

        if (hideCoverSource && coverSourceId != 0) {
            fakeExpandedView.findViewById<View>(coverSourceId)?.let { it.visibility = View.GONE }
        }
        if (hideDeviceSwitch && deviceSwitchId != 0) {
            fakeExpandedView.findViewById<View>(deviceSwitchId)?.let { it.visibility = View.GONE }
        }
        if (BuildConfig.DEBUG) {
            MediaCardDiagnosticLogger.log(
                stage = "island_fake_media_elements",
                event = "apply_complete",
                details = "build=${BuildConfig.VERSION_CODE},fake=${MediaCardDiagnosticLogger.view(fakeExpandedView)}," +
                    "referencePlayer=${MediaCardDiagnosticLogger.identity(referenceElements.player)}," +
                    "hideCover=$hideCoverSource,hideDevice=$hideDeviceSwitch," +
                    "sourceBefore=$sourceBefore,deviceBefore=$deviceBefore," +
                    "coverSource=${MediaCardDiagnosticLogger.view(fakeExpandedView.findViewById(coverSourceId))}," +
                    "deviceSwitch=${MediaCardDiagnosticLogger.view(fakeExpandedView.findViewById(deviceSwitchId))}",
                positionSample = true,
            )
        }
    }

    fun cleanup() {
        states.values.toList().forEach { state ->
            MediaCoverRotationController.detach(state.elements.albumImage)
            state.restoreAll()
        }
        states.clear()
    }

    private data class ElementState(
        val elements: IslandExpandedMediaElements,
        var albumVisibility: Int,
        val albumOutlineProvider: ViewOutlineProvider?,
        val albumClipToOutline: Boolean,
        val imageOutlineProvider: ViewOutlineProvider?,
        val imageClipToOutline: Boolean,
        val coverSourceVisibility: IslandMediaElementVisibilityOverride,
        val deviceSwitchVisibility: IslandMediaElementVisibilityOverride,
        val titleGoneStartMargin: Int,
        val artistGoneStartMargin: Int,
        val actionsGoneTopMargin: Int,
        val firstActionGoneTopMargin: Int,
        var coverHidden: Boolean = false,
        var coverOutlined: Boolean = false
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private var visibilityObserver: ViewTreeObserver? = null
        private var visibilityGuardRegistered = false
        private val visibilityDiagnostics = if (BuildConfig.DEBUG) {
            IslandMediaVisibilityDiagnosticSampler()
        } else null

        fun updateVisibilityGuard() {
            if (!coverSourceVisibility.hidden && !deviceSwitchVisibility.hidden) {
                removeVisibilityGuard("hidden_preferences_disabled")
                return
            }
            if (!visibilityGuardRegistered) {
                elements.player.addOnAttachStateChangeListener(this)
                visibilityGuardRegistered = true
                logVisibility("visibility_guard_installed")
            }
            if (elements.player.isAttachedToWindow) registerPreDraw()
        }

        private fun registerPreDraw() {
            val observer = elements.player.viewTreeObserver
            if (visibilityObserver === observer) return
            if (!observer.isAlive) {
                logVisibility("visibility_guard_register_skipped", "observer_dead")
                return
            }
            removePreDraw()
            observer.addOnPreDrawListener(this)
            visibilityObserver = observer
            logVisibility("visibility_guard_observer_registered")
        }

        private fun removePreDraw() {
            visibilityObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            visibilityObserver = null
        }

        private fun removeVisibilityGuard(reason: String) {
            removePreDraw()
            if (visibilityGuardRegistered) {
                elements.player.removeOnAttachStateChangeListener(this)
                visibilityGuardRegistered = false
                logVisibility("visibility_guard_removed", reason)
            }
        }

        override fun onViewAttachedToWindow(view: View) {
            registerPreDraw()
            logVisibility("visibility_guard_attached")
        }

        override fun onViewDetachedFromWindow(view: View) {
            removePreDraw()
            logVisibility("visibility_guard_detached")
        }

        override fun onPreDraw(): Boolean {
            // Only guard the two registered controls; do not reapply cover styling, animations,
            // or hook View.setVisibility globally. Native bind/transition writes may run after
            // the binder hook, so enforce the preference at the last point before rendering.
            val sourceOverwritten = coverSourceVisibility.hidden &&
                elements.coverSource.visibility != View.GONE
            val deviceOverwritten = deviceSwitchVisibility.hidden &&
                elements.deviceSwitch.visibility != View.GONE
            val sourceBefore = if (BuildConfig.DEBUG) elements.coverSource.visibility else 0
            val deviceBefore = if (BuildConfig.DEBUG) elements.deviceSwitch.visibility else 0
            if (sourceOverwritten) applyCoverSourceHidden(true)
            if (deviceOverwritten) applyDeviceSwitchHidden(true)
            if (BuildConfig.DEBUG) {
                visibilityDiagnostics?.record(
                    now = SystemClock.elapsedRealtime(),
                    shown = elements.player.isShown,
                    corrected = sourceOverwritten || deviceOverwritten,
                )?.let { event ->
                    logVisibility(
                        event,
                        extra = "sourceBefore=$sourceBefore,deviceBefore=$deviceBefore," +
                            "sourceCorrected=$sourceOverwritten,deviceCorrected=$deviceOverwritten",
                    )
                }
            }
            return true
        }

        private fun logVisibility(event: String, reason: String? = null, extra: String = "") {
            if (!BuildConfig.DEBUG) return
            runCatching {
                MediaCardDiagnosticLogger.log(
                    stage = "island_media_elements",
                    event = event,
                    reason = reason,
                    details = "build=${BuildConfig.VERSION_CODE},guardRegistered=$visibilityGuardRegistered," +
                        "observerAlive=${visibilityObserver?.isAlive}," +
                        "draws=${visibilityDiagnostics?.draws},corrections=${visibilityDiagnostics?.corrections}," +
                        "hideCover=${coverSourceVisibility.hidden},hideDevice=${deviceSwitchVisibility.hidden}," +
                        "player=${MediaCardDiagnosticLogger.view(elements.player)}," +
                        "parent=${MediaCardDiagnosticLogger.identity(elements.player.parent)}," +
                        "coverSource=${MediaCardDiagnosticLogger.view(elements.coverSource)}," +
                        "deviceSwitch=${MediaCardDiagnosticLogger.view(elements.deviceSwitch)},$extra",
                )
            }
        }

        fun applyCircle() {
            if (
                coverOutlined &&
                elements.albumView.outlineProvider === circleOutlineProvider &&
                !elements.albumView.clipToOutline &&
                elements.albumImage.outlineProvider === circleOutlineProvider &&
                elements.albumImage.clipToOutline
            ) {
                return
            }
            elements.albumView.outlineProvider = circleOutlineProvider
            elements.albumView.clipToOutline = false
            elements.albumImage.outlineProvider = circleOutlineProvider
            elements.albumImage.clipToOutline = true
            elements.albumView.invalidateOutline()
            elements.albumImage.invalidateOutline()
            coverOutlined = true
        }

        fun hideCover() {
            if (
                coverHidden &&
                elements.albumView.visibility == View.GONE &&
                elements.albumImage.visibility == View.GONE
            ) {
                return
            }
            if (elements.albumView.visibility != View.GONE) {
                albumVisibility = elements.albumView.visibility
            }
            val albumHeight = elements.albumView.height.takeIf { it > 0 }
                ?: elements.albumView.layoutParams.height
            val textGoneStartMargin = (
                26f * elements.player.resources.displayMetrics.density
            ).roundToInt()
            elements.title.setGoneMargin(
                GONE_START_MARGIN_FIELD,
                textGoneStartMargin
            )
            elements.artist.setGoneMargin(
                GONE_START_MARGIN_FIELD,
                textGoneStartMargin
            )
            elements.actionsAnchor.setGoneMargin(GONE_TOP_MARGIN_FIELD, albumHeight)
            elements.firstAction.setGoneMargin(
                GONE_TOP_MARGIN_FIELD,
                albumHeight + elements.firstAction.topMargin
            )
            elements.albumView.visibility = View.GONE
            coverHidden = true
            elements.player.requestLayout()
        }

        fun restoreHiddenCover() {
            if (!coverHidden) return
            elements.title.setGoneMargin(GONE_START_MARGIN_FIELD, titleGoneStartMargin)
            elements.artist.setGoneMargin(GONE_START_MARGIN_FIELD, artistGoneStartMargin)
            elements.actionsAnchor.setGoneMargin(GONE_TOP_MARGIN_FIELD, actionsGoneTopMargin)
            elements.firstAction.setGoneMargin(
                GONE_TOP_MARGIN_FIELD,
                firstActionGoneTopMargin
            )
            elements.albumView.visibility = albumVisibility
            coverHidden = false
            elements.player.requestLayout()
        }

        fun restoreOutlines() {
            if (!coverOutlined) return
            elements.albumView.outlineProvider = albumOutlineProvider
            elements.albumView.clipToOutline = albumClipToOutline
            elements.albumImage.outlineProvider = imageOutlineProvider
            elements.albumImage.clipToOutline = imageClipToOutline
            elements.albumView.invalidateOutline()
            elements.albumImage.invalidateOutline()
            coverOutlined = false
        }

        fun restoreCover() {
            restoreHiddenCover()
            restoreOutlines()
        }

        fun applyCoverSourceHidden(hidden: Boolean) {
            val view = elements.coverSource
            val visibility = coverSourceVisibility.apply(hidden, view.visibility)
            if (view.visibility != visibility) view.visibility = visibility
        }

        fun applyDeviceSwitchHidden(hidden: Boolean) {
            val view = elements.deviceSwitch
            val visibility = deviceSwitchVisibility.apply(hidden, view.visibility)
            if (view.visibility != visibility) view.visibility = visibility
        }

        fun restoreAll() {
            removeVisibilityGuard("restore_all")
            restoreCover()
            applyCoverSourceHidden(false)
            applyDeviceSwitchHidden(false)
        }

        companion object {
            fun capture(elements: IslandExpandedMediaElements): ElementState {
                return ElementState(
                    elements = elements,
                    albumVisibility = elements.albumView.visibility,
                    albumOutlineProvider = elements.albumView.outlineProvider,
                    albumClipToOutline = elements.albumView.clipToOutline,
                    imageOutlineProvider = elements.albumImage.outlineProvider,
                    imageClipToOutline = elements.albumImage.clipToOutline,
                    coverSourceVisibility = IslandMediaElementVisibilityOverride(elements.coverSource.visibility),
                    deviceSwitchVisibility = IslandMediaElementVisibilityOverride(elements.deviceSwitch.visibility),
                    titleGoneStartMargin = elements.title.getGoneMargin(
                        GONE_START_MARGIN_FIELD
                    ),
                    artistGoneStartMargin = elements.artist.getGoneMargin(
                        GONE_START_MARGIN_FIELD
                    ),
                    actionsGoneTopMargin = elements.actionsAnchor.getGoneMargin(
                        GONE_TOP_MARGIN_FIELD
                    ),
                    firstActionGoneTopMargin = elements.firstAction.getGoneMargin(
                        GONE_TOP_MARGIN_FIELD
                    )
                )
            }
        }
    }

    private const val GONE_START_MARGIN_FIELD = "goneStartMargin"
    private const val GONE_TOP_MARGIN_FIELD = "goneTopMargin"

    private val View.topMargin: Int
        get() = (layoutParams as ViewGroup.MarginLayoutParams).topMargin

    private fun View.getGoneMargin(fieldName: String): Int {
        val params = layoutParams
        return params.javaClass.getField(fieldName).getInt(params)
    }

    private fun View.setGoneMargin(fieldName: String, value: Int) {
        val params = layoutParams
        params.javaClass.getField(fieldName).setInt(params, value)
        layoutParams = params
    }
}

internal data class IslandExpandedMediaElements(
    val albumView: View,
    val albumImage: ImageView,
    val coverSource: ImageView,
    val deviceSwitch: View,
    val title: View,
    val artist: View,
    val actionsAnchor: View,
    val firstAction: View,
    val player: View
)
