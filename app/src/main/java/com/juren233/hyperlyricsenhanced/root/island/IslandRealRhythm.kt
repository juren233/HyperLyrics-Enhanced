/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.widget.ImageView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.IslandRealRhythmConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

/**
 * 「跟随音乐真实律动」：用输出混音（session 0）的 Visualizer 频谱驱动超级岛右侧音频律动。
 *
 * 原生 `music_wave` Lottie 保留在原位但暂停并隐藏图像，模块在同一个 View 的 overlay 上
 * 按原生几何（[IslandRealRhythmGeometry]）和原生渐变色（`gradientTopColor/gradientBottomColor`）绘制。
 * 只接管 pic 为 musicWave 的律动；暂停态（musicPause）、关闭开关时完整还原原生动画。
 * 媒体流静音或音量为 0 时 Visualizer 拿不到任何数据（ISLAND-REAL-RHYTHM-001 实测），
 * 按用户确认改显示原生循环动画，恢复有声后再接管。
 * 所有 View 操作在主线程；Visualizer 在独立线程创建并回调。
 */
internal object IslandRealRhythm {
    private const val TAG = "IslandRealRhythm"
    private const val SUPERVISE_INTERVAL_MS = 1_000L
    private const val CAPTURE_SIZE = 512
    /** AudioManager 的隐藏广播（设备 dumpsys activity broadcasts 已核对存在）。 */
    private const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"
    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

    private val mainHandler = Handler(Looper.getMainLooper())
    /** 每个见过的原生律动 View 及其最近一次绑定的 pic。 */
    private val candidates = WeakHashMap<ImageView, String>()
    private val bindings = WeakHashMap<ImageView, Binding>()
    private var prefs: SharedPreferences? = null
    private var engine: Engine? = null
    /** 本次绑定周期内启动失败过就不再重试，直到下一次原生重绑或设置变化。 */
    private var startFailed = false
    private var supervising = false
    private var framing = false
    private var lastFrameNanos = 0L
    private var firstTakeoverLogged = false
    private val targets = FloatArray(IslandRealRhythmGeometry.BAR_COUNT)
    private val smoother = IslandRealRhythmSmoother()
    /** 当前律动参数；主线程按设置更新，采集线程与帧回调读取。 */
    @Volatile
    private var tuning = IslandRealRhythmTuning.DEFAULT
    private var muted = false
    private var muteReceiverContext: Context? = null
    private val muteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateMuted(context)
    }

    /** 原生 registerLottieCallback 完成后调用（composition 已设置并开始播放）。 */
    fun onLottieBound(view: View, pic: String?, sharedPrefs: SharedPreferences?) {
        val image = view as? ImageView ?: return
        runOnMain {
            if (sharedPrefs != null) prefs = sharedPrefs
            updateTuning()
            candidates[image] = pic.orEmpty()
            startFailed = false
            ensureMuteReceiver(image.context)
            evaluate(image)
            ensureSupervising()
        }
    }

    /** 设置变化：按当前开关对所有已知律动 View 重新接管或还原。 */
    fun refresh(sharedPrefs: SharedPreferences?) {
        runOnMain {
            if (sharedPrefs != null) prefs = sharedPrefs
            updateTuning()
            startFailed = false
            candidates.keys.toList().forEach(::evaluate)
            ensureSupervising()
        }
    }

    fun releaseForReload() {
        val release = {
            bindings.keys.toList().forEach(::unbind)
            bindings.clear()
            candidates.clear()
            muteReceiverContext?.let { context -> runCatching { context.unregisterReceiver(muteReceiver) } }
            muteReceiverContext = null
            muted = false
            stopEngine()
            mainHandler.removeCallbacks(supervisor)
            supervising = false
            stopFrames()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release() else mainHandler.post(release)
    }

    private fun mode(): Int {
        val sharedPrefs = prefs ?: return IslandRealRhythmConfig.MODE_OFF
        return IslandRealRhythmConfig.resolveMode(
            IslandRuntimePreferenceReader.getInt(
                sharedPrefs,
                IslandRealRhythmConfig.KEY_MODE,
                IslandRealRhythmConfig.DEFAULT_MODE,
            )
        )
    }

    private fun isEnabled(): Boolean = mode() != IslandRealRhythmConfig.MODE_OFF

    /** 「开启」用默认参数，「自定义」读用户设置；未变化时沿用原对象。 */
    private fun updateTuning() {
        val sharedPrefs = prefs ?: return
        val values = IslandRealRhythmConfig.resolveValues(
            mode(),
            readInt = { IslandRuntimePreferenceReader.getInt(sharedPrefs, it.key, it.default) },
            readWeakBand = {
                IslandRuntimePreferenceReader.getBoolean(
                    sharedPrefs,
                    IslandRealRhythmConfig.KEY_WEAK_BAND,
                    IslandRealRhythmConfig.DEFAULT_WEAK_BAND,
                )
            },
        )
        if (values == tuning.values) return
        tuning = IslandRealRhythmTuning(values)
        if (BuildConfig.DEBUG) HookLogger.i(TAG, "律动参数: $values")
    }

    private fun evaluate(view: ImageView) {
        val wanted = isEnabled() && !muted &&
            candidates[view] == IslandMusicWaveMethodProfile.MUSIC_WAVE_PIC
        if (wanted) bind(view) else if (bindings.containsKey(view)) unbind(view)
    }

    private fun bind(view: ImageView) {
        val existing = bindings[view]
        if (existing != null) {
            // 原生重绑会重新 playAnimation；保持暂停并记录原生是否在播放，以便还原。
            existing.wasAnimating = existing.wasAnimating || isAnimating(view)
            pauseNative(view)
            view.imageAlpha = 0
            return
        }
        val binding = Binding(view, RhythmDrawable(view), isAnimating(view))
        bindings[view] = binding
        pauseNative(view)
        view.imageAlpha = 0
        binding.drawable.setBounds(0, 0, view.width, view.height)
        view.overlay.add(binding.drawable)
        view.addOnLayoutChangeListener(binding.layoutListener)
        if (BuildConfig.DEBUG && !firstTakeoverLogged) {
            firstTakeoverLogged = true
            HookLogger.i(TAG, "真实律动首次接管: view=${System.identityHashCode(view)}")
        }
        startFrames()
    }

    private fun unbind(view: ImageView) {
        val binding = bindings.remove(view) ?: return
        view.removeOnLayoutChangeListener(binding.layoutListener)
        view.overlay.remove(binding.drawable)
        view.imageAlpha = 255
        if (binding.wasAnimating) resumeNative(view)
        view.invalidate()
        if (bindings.isEmpty()) stopEngine()
    }

    private fun ensureSupervising() {
        if (supervising || candidates.isEmpty()) return
        supervising = true
        mainHandler.postDelayed(supervisor, SUPERVISE_INTERVAL_MS)
        supervise()
    }

    /** 有可见的接管律动且亮屏时才运行 Visualizer，其余时间释放。 */
    private val supervisor = object : Runnable {
        override fun run() {
            supervise()
            if (candidates.isEmpty() && bindings.isEmpty()) {
                supervising = false
                return
            }
            mainHandler.postDelayed(this, SUPERVISE_INTERVAL_MS)
        }
    }

    private fun ensureMuteReceiver(viewContext: Context) {
        if (muteReceiverContext != null) return
        val context = viewContext.applicationContext ?: viewContext
        runCatching {
            val filter = IntentFilter().apply {
                addAction(STREAM_MUTE_CHANGED_ACTION)
                addAction(VOLUME_CHANGED_ACTION)
            }
            context.registerReceiver(muteReceiver, filter, Context.RECEIVER_EXPORTED)
            muteReceiverContext = context
        }.onFailure { HookLogger.w(TAG, "静音监听注册失败: ${it.javaClass.simpleName}") }
        updateMuted(context)
    }

    /** 静音状态翻转时，对所有已知律动重新判定：静音交还原生，有声再接管。 */
    private fun updateMuted(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val now = runCatching {
            audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        }.getOrDefault(false)
        if (now == muted) return
        muted = now
        if (BuildConfig.DEBUG) HookLogger.i(TAG, "媒体静音状态: muted=$now")
        candidates.keys.toList().forEach(::evaluate)
        ensureSupervising()
    }

    private fun supervise() {
        (muteReceiverContext ?: candidates.keys.firstOrNull()?.context)?.let(::updateMuted)
        val anyVisible = bindings.keys.any { it.isAttachedToWindow && it.isShown }
        val interactive = bindings.keys.firstOrNull()?.context
            ?.getSystemService(PowerManager::class.java)?.isInteractive != false
        val wanted = anyVisible && interactive && isEnabled()
        when {
            wanted && engine == null && !startFailed -> startEngine()
            !wanted && engine != null -> stopEngine()
        }
        if (anyVisible) startFrames()
    }

    private fun startEngine() {
        val created = Engine()
        engine = created
        created.start { failure ->
            mainHandler.post {
                if (engine !== created) return@post
                engine = null
                startFailed = true
                HookLogger.e(TAG, "真实律动 Visualizer 启动失败", failure)
            }
        }
        if (BuildConfig.DEBUG) HookLogger.i(TAG, "Visualizer 启动: bindings=${bindings.size}")
    }

    private fun stopEngine() {
        val current = engine ?: return
        engine = null
        current.release()
        synchronized(targets) { targets.fill(0f) }
        if (BuildConfig.DEBUG) HookLogger.i(TAG, "Visualizer 已释放")
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framing = false
            val dtMs = if (lastFrameNanos == 0L) 16.67f else (frameTimeNanos - lastFrameNanos) / 1_000_000f
            lastFrameNanos = frameTimeNanos
            val snapshot = synchronized(targets) { targets.copyOf() }
            val moving = smoother.step(snapshot, dtMs, tuning)
            val colors = IslandMusicWaveColorHooker.currentWaveColors()
            var anyVisible = false
            for ((view, binding) in bindings) {
                binding.drawable.update(smoother.levels, colors)
                if (view.isAttachedToWindow && view.isShown) {
                    anyVisible = true
                    binding.drawable.invalidateSelf()
                }
            }
            if (anyVisible && (engine != null || moving)) startFrames() else lastFrameNanos = 0L
        }
    }

    private fun startFrames() {
        if (framing || bindings.isEmpty()) return
        framing = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrames() {
        if (!framing) return
        framing = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameNanos = 0L
        smoother.reset()
    }

    private fun publishTargets(levels: FloatArray) {
        synchronized(targets) { levels.copyInto(targets) }
    }

    private fun isAnimating(view: ImageView): Boolean = runCatching {
        view.javaClass.getMethod(IslandMusicWaveMethodProfile.LOTTIE_IS_ANIMATING_METHOD).invoke(view) == true
    }.getOrDefault(false)

    private fun pauseNative(view: ImageView) {
        runCatching { view.javaClass.getMethod(IslandMusicWaveMethodProfile.LOTTIE_PAUSE_METHOD).invoke(view) }
            .onFailure { HookLogger.w(TAG, "暂停原生律动失败: ${it.javaClass.simpleName}") }
    }

    private fun resumeNative(view: ImageView) {
        runCatching { view.javaClass.getMethod(IslandMusicWaveMethodProfile.LOTTIE_RESUME_METHOD).invoke(view) }
            .onFailure { HookLogger.w(TAG, "恢复原生律动失败: ${it.javaClass.simpleName}") }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private class Binding(val view: ImageView, val drawable: RhythmDrawable, var wasAnimating: Boolean) {
        val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            drawable.setBounds(0, 0, v.width, v.height)
        }
    }

    /**
     * Visualizer 采集线程：创建、回调与释放都在该线程，主线程不等待。
     * FFT 回调里换算目标高度后交给主线程帧回调。
     */
    private class Engine {
        private val thread = HandlerThread("HLE-RealRhythm").apply { start() }
        private val handler = Handler(thread.looper)
        private var visualizer: Visualizer? = null
        private val stats = DebugStats()

        fun start(onFailure: (Throwable) -> Unit) {
            handler.post {
                var created: Visualizer? = null
                try {
                    created = Visualizer(0)
                    val range = Visualizer.getCaptureSizeRange()
                    created.captureSize = CAPTURE_SIZE.coerceIn(range[0], range[1])
                    val analyzer = IslandRealRhythmAnalyzer()
                    val levels = FloatArray(IslandRealRhythmGeometry.BAR_COUNT)
                    created.setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit
                            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                                if (fft == null) return
                                analyzer.analyze(fft, samplingRate, levels, tuning)
                                publishTargets(levels)
                                if (BuildConfig.DEBUG) stats.add(analyzer.lastDb, levels)
                            }
                        },
                        Visualizer.getMaxCaptureRate(),
                        false,
                        true,
                    )
                    val status = created.setEnabled(true)
                    check(status == Visualizer.SUCCESS) { "setEnabled 返回 $status" }
                    visualizer = created
                } catch (t: Throwable) {
                    runCatching { created?.release() }
                    thread.quitSafely()
                    onFailure(t)
                }
            }
        }

        fun release() {
            handler.post {
                visualizer?.let { current ->
                    runCatching { current.enabled = false }
                    runCatching { current.release() }
                }
                visualizer = null
                thread.quitSafely()
            }
        }
    }

    /** debug 专用：每 2 s 汇总各频段 dB 与电平分布，供调参与判断静音数据。 */
    private class DebugStats {
        private val count = IslandRealRhythmGeometry.BAR_COUNT
        private val minDb = FloatArray(count)
        private val maxDb = FloatArray(count)
        private val levelSum = FloatArray(count)
        private val levelMin = FloatArray(count)
        private val levelMax = FloatArray(count)
        private val silent = IntArray(count)
        private var frames = 0
        private var windowStart = 0L

        init { clear() }

        private val raw = StringBuilder()
        private var rawFrames = 0

        fun add(db: FloatArray, levels: FloatArray) {
            // 原始频段 dB 逐帧记录（每 20 帧一行），供离线按真实数据调参。
            raw.append(db.joinToString(",") { if (it.isNaN()) "-" else "%.1f".format(it) }).append(';')
            if (++rawFrames >= 20) {
                HookLogger.i(TAG, "[raw] $raw")
                raw.setLength(0)
                rawFrames = 0
            }
            val now = SystemClock.elapsedRealtime()
            if (windowStart == 0L) windowStart = now
            frames++
            for (i in 0 until count) {
                if (db[i].isNaN()) silent[i]++ else {
                    minDb[i] = minOf(minDb[i], db[i]); maxDb[i] = maxOf(maxDb[i], db[i])
                }
                levelSum[i] += levels[i]
                levelMin[i] = minOf(levelMin[i], levels[i]); levelMax[i] = maxOf(levelMax[i], levels[i])
            }
            if (now - windowStart < 2_000L) return
            val bands = (0 until count).joinToString(" ") { i ->
                "b$i[db=${"%.1f".format(minDb[i])}..${"%.1f".format(maxDb[i])} " +
                    "lv=${"%.2f".format(levelMin[i])}/${"%.2f".format(levelSum[i] / frames)}/${"%.2f".format(levelMax[i])} " +
                    "silent=${silent[i]}]"
            }
            HookLogger.i(TAG, "[stats] frames=$frames $bands")
            clear()
        }

        private fun clear() {
            minDb.fill(Float.POSITIVE_INFINITY); maxDb.fill(Float.NEGATIVE_INFINITY)
            levelSum.fill(0f); levelMin.fill(1f); levelMax.fill(0f); silent.fill(0)
            frames = 0; windowStart = 0L
        }
    }

    /** 按原生 Lottie 在 View 内的实际映射位置绘制 5 根胶囊条。 */
    private class RhythmDrawable(private val view: ImageView) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val levels = FloatArray(IslandRealRhythmGeometry.BAR_COUNT)
        private val content = RectF()
        private val bar = RectF()
        private val shaderMatrix = Matrix()
        private var shader: LinearGradient? = null
        private var topColor = 0
        private var bottomColor = 0

        fun update(source: FloatArray, colors: Pair<Int, Int>?) {
            source.copyInto(levels)
            val top = colors?.first ?: -1
            val bottom = colors?.second ?: -1
            if (shader == null || top != topColor || bottom != bottomColor) {
                topColor = top
                bottomColor = bottom
                shader = LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP)
            }
        }

        override fun draw(canvas: Canvas) {
            val gradient = shader ?: return
            if (!resolveContent()) return
            val scale = content.width() / IslandRealRhythmGeometry.CANVAS_SIZE
            val halfWidth = IslandRealRhythmGeometry.BAR_WIDTH / 2f * scale
            paint.shader = gradient
            for (i in 0 until IslandRealRhythmGeometry.BAR_COUNT) {
                val cx = content.left + IslandRealRhythmGeometry.CENTER_X[i] * scale
                val cy = content.top + IslandRealRhythmGeometry.CENTER_Y * scale
                val half = IslandRealRhythmGeometry.height(levels[i]) / 2f * scale
                bar.set(cx - halfWidth, cy - half, cx + halfWidth, cy + half)
                // 原生渐变只覆盖胶囊直线段，两端圆角取端点色。
                val gradientTop = bar.top + halfWidth
                val span = (bar.bottom - halfWidth - gradientTop).coerceAtLeast(0.01f)
                shaderMatrix.setScale(1f, span)
                shaderMatrix.postTranslate(0f, gradientTop)
                gradient.setLocalMatrix(shaderMatrix)
                canvas.drawRoundRect(bar, halfWidth, halfWidth, paint)
            }
        }

        /** 与 ImageView 绘制原生 Lottie 时相同的映射：drawable bounds → imageMatrix → padding。 */
        private fun resolveContent(): Boolean {
            val native = view.drawable
            if (native != null && !native.bounds.isEmpty) {
                content.set(native.bounds)
                view.imageMatrix.mapRect(content)
                content.offset(view.paddingLeft.toFloat(), view.paddingTop.toFloat())
            } else {
                content.set(
                    view.paddingLeft.toFloat(),
                    view.paddingTop.toFloat(),
                    (view.width - view.paddingRight).toFloat(),
                    (view.height - view.paddingBottom).toFloat(),
                )
            }
            return content.width() > 0f && content.height() > 0f
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
