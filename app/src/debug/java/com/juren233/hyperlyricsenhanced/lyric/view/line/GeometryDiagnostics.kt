package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.os.Handler
import android.os.Looper
import android.os.Process
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.utils.LogManager

/**
 * Debug automatically collects during normal lyric use. No ADB/property setup.
 * Host sink is installed by HookLogger; the app uses its existing LogManager file.
 * At most 64 immediate samples + one summary per 120-second window per process.
 */
internal object GeometryDiagnostics {
    private const val TAG = "HLEGeomS0"
    private val gate = GeometryWindowGate()
    private var window: GeometrySampleWindow? = null
    private var windowId = 0
    private var expiryHandler: Handler? = null
    private val expire = Runnable { runCatching { finish() } }
    @Volatile private var hostLogger: ((String) -> Unit)? = null

    // No reference to Xposed types here: this same class is also loaded by the normal app.
    fun setHostLogger(logger: ((String) -> Unit)?) { hostLogger = logger }

    private fun write(message: String) {
        // Diagnostic/export errors must never replace a rendering/measurement exception.
        runCatching {
            val host = hostLogger
            if (host != null) host(message) else LogManager.i(TAG, message)
        }
    }

    private fun finish() {
        expiryHandler?.removeCallbacks(expire)
        expiryHandler = null
        val old = window ?: return
        window = null
        val rows = old.close()
        write(GeometryDiagnosticLog.summary(
            BuildConfig.VERSION_CODE, Process.myPid(), windowId,
            old.queries, rows.size, old.reasonCounts
        ))
    }

    val sampling: Boolean get() = window?.active != null && onMainThread()
    private fun onMainThread() = Looper.myLooper() === Looper.getMainLooper()

    fun begin(model: Any, reason: Int, words: Int, preparedWhole: Boolean): Boolean {
        if (!onMainThread()) return false
        return runCatching {
            if (window?.inProgress == true) {
                return@runCatching window!!.begin(model, reason, words, 0L, preparedWhole)
            }
            val now = System.nanoTime()
            if (window?.expired(now) == true) finish()
            if (window == null) {
                val id = gate.start(now) ?: return@runCatching false
                windowId = id
                window = GeometrySampleWindow(now)
                expiryHandler = Handler(Looper.getMainLooper()).also {
                    it.postDelayed(expire, GeometrySampleWindow.WINDOW_NS / 1_000_000)
                }
            }
            val sampled = window?.begin(model, reason, words, now, preparedWhole) ?: false
            if (sampled) window?.startMeasurement(System.nanoTime())
            sampled
        }.getOrDefault(false)
    }

    fun end(failed: Boolean) {
        if (!onMainThread()) return
        runCatching {
            val old = window ?: return@runCatching
            val row = old.end(System.nanoTime(), failed)
            if (row != null) write(GeometryDiagnosticLog.sample(
                BuildConfig.VERSION_CODE, Process.myPid(), windowId, row, old.queries, old.reasonCounts
            ))
            if (!old.inProgress && old.expired(System.nanoTime())) finish()
        }
    }

    fun whole(elapsed: Long) { if (sampling) window?.active?.wholeNs = elapsed }
    fun reuse(status: Int) { if (sampling) window?.active?.reuseStatus = status }
    fun word(utf16: Int, measurementNs: Long, positionNs: Long) {
        if (!sampling) return
        window?.active?.let {
            it.utf16 += utf16
            it.measurementNs += measurementNs
            it.positionNs += positionNs
        }
    }
    fun paintCall(mixed: Boolean) {
        if (!sampling) return
        window?.active?.let { if (mixed) it.mixedCalls++ else it.plainCalls++ }
    }
}
