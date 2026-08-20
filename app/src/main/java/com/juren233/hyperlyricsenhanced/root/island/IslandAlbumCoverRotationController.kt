package com.juren233.hyperlyricsenhanced.root.island

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

internal data class IslandCoverPivot(
    val x: Float,
    val y: Float,
)

internal object IslandAlbumCoverRotationGeometry {
    fun centeredPivot(width: Int, height: Int): IslandCoverPivot? {
        if (width <= 0 || height <= 0) return null
        return IslandCoverPivot(width / 2f, height / 2f)
    }
}

internal object IslandAlbumCoverRotationController {
    private const val TAG = "IslandAlbumCoverRotation"
    private const val ROTATION_DURATION_MS = 20_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = WeakHashMap<ImageView, RotationState>()

    @Volatile
    private var playbackActive = true

    private val attachStateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            val imageView = view as? ImageView ?: return
            states[imageView]?.let {
                ensureCenteredPivot(imageView)
                startIfNeeded(imageView, it)
            }
        }

        override fun onViewDetachedFromWindow(view: View) {
            val imageView = view as? ImageView ?: return
            states[imageView]?.let { stopAnimator(imageView, it, resetRotation = true) }
        }
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val imageView = view as? ImageView ?: return@OnLayoutChangeListener
        if (states.containsKey(imageView)) {
            ensureCenteredPivot(imageView)
        }
    }

    fun attach(view: ImageView) {
        runOnMain {
            val state = states.getOrPut(view) {
                view.addOnAttachStateChangeListener(attachStateListener)
                view.addOnLayoutChangeListener(layoutChangeListener)
                RotationState()
            }
            ensureCenteredPivot(view)
            startIfNeeded(view, state)
        }
    }

    fun detach(view: ImageView) {
        runOnMain {
            val state = states.remove(view) ?: return@runOnMain
            view.removeOnAttachStateChangeListener(attachStateListener)
            view.removeOnLayoutChangeListener(layoutChangeListener)
            stopAnimator(view, state, resetRotation = true)
        }
    }

    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) return
        playbackActive = active
        runOnMain {
            states.toList().forEach { (view, state) ->
                if (active) {
                    startIfNeeded(view, state)
                } else {
                    state.animator?.takeIf { it.isStarted && !it.isPaused }?.pause()
                }
            }
        }
    }

    fun cleanup() {
        runOnMain {
            states.toList().forEach { (view, state) ->
                view.removeOnAttachStateChangeListener(attachStateListener)
                view.removeOnLayoutChangeListener(layoutChangeListener)
                stopAnimator(view, state, resetRotation = true)
            }
            states.clear()
        }
    }

    private fun startIfNeeded(view: ImageView, state: RotationState) {
        if (!playbackActive || !view.isAttachedToWindow) return

        ensureCenteredPivot(view)

        val existing = state.animator
        if (existing != null) {
            if (existing.isPaused) existing.resume()
            return
        }

        state.animator = ObjectAnimator.ofFloat(
            view,
            View.ROTATION,
            view.rotation,
            view.rotation + 360f
        ).apply {
            duration = ROTATION_DURATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    private fun stopAnimator(
        view: ImageView,
        state: RotationState,
        resetRotation: Boolean
    ) {
        state.animator?.cancel()
        state.animator = null
        if (resetRotation) view.rotation = 0f
    }

    private fun ensureCenteredPivot(view: ImageView) {
        val width = view.width
        val height = view.height
        val pivot = IslandAlbumCoverRotationGeometry.centeredPivot(width, height) ?: return
        if (view.pivotX == pivot.x && view.pivotY == pivot.y) return
        view.pivotX = pivot.x
        view.pivotY = pivot.y
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                TAG,
                "旋转封面锚点已校正: view=${System.identityHashCode(view)}, " +
                    "pivot=${pivot.x}x${pivot.y}, size=${width}x${height}",
            )
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private data class RotationState(
        var animator: ObjectAnimator? = null
    )
}
