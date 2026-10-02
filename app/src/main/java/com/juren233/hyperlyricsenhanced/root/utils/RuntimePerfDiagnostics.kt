/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.juren233.hyperlyricsenhanced.root.utils

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only 进程级性能与功耗采样，用于社区 issue 的"动画慢动作"与"耗电增加"取证。
 *
 * 每个被注入进程每 [SAMPLE_INTERVAL_MS] 输出一行结构化日志（tag=HyperLyrics Enhanced，
 * 前缀 [RUNTIME_PERF_DIAG]），内容：
 *  - 进程 CPU 占用与占用最高的线程（按 /proc/self/task 前后差值，可归因模块自有线程）；
 *  - PSS / native / dalvik 内存；
 *  - 电池瞬时/平均电流、电压、电量、温度、充放电状态与按实测电压估算的功率幅值
 *    （电流符号约定随厂商而异，掉电归因只看 status=discharging 的样本）；
 *  - 亮屏状态与歌词运行状态（调用方通过 stateProvider 提供）；
 *  - 帧统计：经系统 FrameMetrics 回调（仅在视图真正绘制时触发，空闲时零开销）聚合
 *    总帧耗时、动画/布局/绘制分段均值与掉帧计数；
 *  - 主线程停顿计数（5 秒心跳的派发延迟，仅亮屏时计入）；
 *  - 冻结取证：进程被整进程挂起期间连采样线程都无法运行，解冻后第一个节拍
 *    补一条 freeze_gap 行，按 uptime/elapsedRealtime 双钟跳变区分「设备醒着但
 *    本进程没跑（冻结类）」与「窗口被深睡吸收」，附 /proc 自证（进程状态、
 *    cgroup 路径与冻结位）。
 *
 * 观察者效应控制：采样线程 15s 一次、心跳 5s 一次、帧回调纯被动，不额外唤醒 vsync，
 * 采样线程自身也会出现在 topThreads 中如实上报开销。release 构建 [start] 直接返回。
 */
object RuntimePerfDiagnostics {
    private const val TAG = "RuntimePerfDiag"
    private const val PREFIX = "[RUNTIME_PERF_DIAG]"

    private const val SAMPLE_INTERVAL_MS = 15_000L
    private const val TOP_THREAD_COUNT = 4
    private const val STALL_PROBE_INTERVAL_MS = 5_000L
    private const val STALL_WARN_THRESHOLD_MS = 300L
    // 冻结取证阈值：采样错过至少一个完整周期（2×15s）；主线程心跳迟到 3×5s。
    private const val SAMPLER_GAP_THRESHOLD_MS = 30_000L
    private const val HEARTBEAT_GAP_THRESHOLD_MS = 15_000L
    private const val NAME_MAX_CHARS = 24
    private const val FRAME_BURST_SAMPLES = 30

    // FrameMetrics 以纳秒返回；阈值对应 60Hz 预算及以上级别的掉帧。
    private const val SLOW_17_NS = 17_000_000L
    private const val SLOW_33_NS = 33_000_000L
    private const val SLOW_50_NS = 50_000_000L
    private const val SLOW_100_NS = 100_000_000L

    private val started = AtomicBoolean(false)

    @Volatile
    private var scope: String = ""

    @Volatile
    private var stateProvider: (() -> String)? = null

    @Volatile
    private var frameViewProvider: (() -> List<View>)? = null

    @Volatile
    private var appRef: Application? = null

    // 懒初始化：本地 JVM 单测只调用纯解析函数，不应触碰 Android 桩。
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var sampler: java.util.concurrent.ScheduledExecutorService? = null

    // 仅采样线程访问。
    private var lastSampleElapsedMs = -1L
    private var lastSampleUptimeMs = -1L
    private var lastScreenInteractive: Boolean? = null
    private var lastProcJiffies = -1L
    private var lastThreadJiffies: Map<Int, Long> = emptyMap()
    private var lastThreadNames: Map<Int, String> = emptyMap()
    private var ticksPerSec = 100.0

    // 仅主线程访问（FrameMetrics 回调、心跳与日志输出都在主线程）。
    private var frameCount = 0L
    private var frameTotalNs = 0L
    private var frameMaxNs = 0L
    private var slow17 = 0L
    private var slow33 = 0L
    private var slow50 = 0L
    private var slow100 = 0L
    private var animNs = 0L
    private var animSamples = 0L
    private var drawNs = 0L
    private var drawSamples = 0L
    private var layoutNs = 0L
    private var layoutSamples = 0L
    private var stallCount = 0L
    private var stallMaxMs = 0L

    /**
     * 在当前进程启动采样。各注入点调用一次即可；release 构建为空操作。
     *
     * @param scope 进程序列标签（systemui / applemusic / provider:<pkg> / module）
     * @param stateProvider 主线程调用的业务状态快照（如播放状态、岛视图数），拼进采样行
     * @param frameViewProvider 需要挂 FrameMetrics 回调的宿主视图（如活动岛根视图）
     * @param attachActivityFrameMetrics 为模块自身进程注册 Activity 窗口帧回调
     */
    fun start(
        app: Application,
        scope: String,
        stateProvider: () -> String = { "" },
        frameViewProvider: () -> List<View> = { emptyList() },
        attachActivityFrameMetrics: Boolean = false,
    ) {
        if (!BuildConfig.DEBUG) return
        if (!started.compareAndSet(false, true)) return
        this.scope = scope
        this.stateProvider = stateProvider
        this.frameViewProvider = frameViewProvider
        appRef = app

        runCatching {
            ticksPerSec = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
                .toDouble().coerceAtLeast(1.0)
        }
        lastProcJiffies = readSelfJiffies() ?: -1L
        lastThreadJiffies = readThreads().associate { it.tid to it.jiffies }
        lastSampleElapsedMs = elapsedRealtimeSafe()

        runCatching {
            HookLogger.i(
                TAG,
                "$PREFIX started scope=$scope proc=${sanitize(Application.getProcessName())} " +
                    "pid=${android.os.Process.myPid()} ver=${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE} " +
                    "sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} " +
                    "cores=${Runtime.getRuntime().availableProcessors()} " +
                    "refresh=${fmt1(refreshRateHz(app))}Hz clkTck=$ticksPerSec",
            )
        }

        if (attachActivityFrameMetrics) {
            runCatching {
                app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(
                        activity: android.app.Activity,
                        savedInstanceState: android.os.Bundle?,
                    ) {
                        runCatching {
                            // SDK 36 起弃用但仍保留；模块自身进程没有更优替代。
                            @Suppress("DEPRECATION")
                            activity.window.addOnFrameMetricsAvailableListener(
                                android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                                    onFrame(metrics)
                                },
                                mainHandler,
                            )
                        }
                    }

                    override fun onActivityStarted(activity: android.app.Activity) = Unit
                    override fun onActivityResumed(activity: android.app.Activity) = Unit
                    override fun onActivityPaused(activity: android.app.Activity) = Unit
                    override fun onActivityStopped(activity: android.app.Activity) = Unit
                    override fun onActivitySaveInstanceState(
                        activity: android.app.Activity,
                        outState: android.os.Bundle,
                    ) = Unit

                    override fun onActivityDestroyed(activity: android.app.Activity) = Unit
                })
            }
        }

        sampler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "HyperLyrics Enhanced-PerfDiag").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(
                ::sample,
                SAMPLE_INTERVAL_MS,
                SAMPLE_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }
        mainHandler.post(stallProbe)
    }

    /** /proc 读取在采样线程；状态读取、帧聚合与日志输出统一在主线程收口。 */
    private fun sample() {
        val app = appRef ?: return
        val core = runCatching { buildCoreSample(app) }.getOrElse {
            HookLogger.w(TAG, "$PREFIX sample_failed error=${it.javaClass.simpleName}:${it.message}")
            return
        }
        mainHandler.post { runCatching { logOnMain(core) } }
    }

    private class CoreSample(
        val windowMs: Long,
        val cpuPct: Double?,
        val cores: Int,
        val threadCount: Int,
        val topThreads: String,
        val mem: String,
        val battery: String,
        val screen: String,
    )

    private fun buildCoreSample(app: Application): CoreSample {
        val now = elapsedRealtimeSafe()
        val nowUptime = uptimeMillisSafe()
        val procJiffies = readSelfJiffies()
        val threads = readThreads()

        val windowMs = if (lastSampleElapsedMs > 0) now - lastSampleElapsedMs else -1L
        val cpuPct: Double?
        val topThreads: String
        if (windowMs > 0 && procJiffies != null && lastProcJiffies >= 0) {
            cpuPct = (procJiffies - lastProcJiffies) * 100.0 /
                (windowMs / 1000.0 * ticksPerSec)
            topThreads = formatTopThreads(threads, lastThreadJiffies, windowMs, ticksPerSec)
        } else {
            cpuPct = null
            topThreads = "na"
        }

        val screenInteractive = isScreenInteractive()
        if (windowMs > SAMPLER_GAP_THRESHOLD_MS) {
            val uptimeJumpMs = if (lastSampleUptimeMs > 0) nowUptime - lastSampleUptimeMs else -1L
            emitFreezeGap(
                src = "sampler",
                gapMs = windowMs,
                uptimeJumpMs = uptimeJumpMs,
                screenBefore = lastScreenInteractive,
                screenNow = screenInteractive,
            )
        }

        lastSampleElapsedMs = now
        lastSampleUptimeMs = nowUptime
        lastScreenInteractive = screenInteractive
        lastProcJiffies = procJiffies ?: -1L
        lastThreadJiffies = threads.associate { it.tid to it.jiffies }
        lastThreadNames = threads.associate { it.tid to it.name }

        return CoreSample(
            windowMs = windowMs,
            cpuPct = cpuPct,
            cores = Runtime.getRuntime().availableProcessors(),
            threadCount = threads.size,
            topThreads = topThreads,
            mem = memorySnapshot(),
            battery = batterySnapshot(app),
            screen = screenSnapshot(app, screenInteractive),
        )
    }

    private fun logOnMain(core: CoreSample) {
        frameViewProvider?.invoke()?.forEach(::attachViewFrameMetricsListener)
        scheduleFrameBurst()
        val state = runCatching { stateProvider?.invoke().orEmpty() }.getOrElse { "<state_error>" }

        val frame = if (frameCount <= 0L) {
            "none"
        } else {
            buildString {
                append("frames=").append(frameCount)
                append(",avgTotalMs=").append(fmt1(frameTotalNs / 1_000_000.0 / frameCount))
                append(",maxMs=").append(fmt1(frameMaxNs / 1_000_000.0))
                append(",slow17=").append(slow17)
                append(",slow33=").append(slow33)
                append(",slow50=").append(slow50)
                append(",slow100=").append(slow100)
                if (animSamples > 0) append(",avgAnimMs=").append(fmt1(animNs / 1_000_000.0 / animSamples))
                if (drawSamples > 0) append(",avgDrawMs=").append(fmt1(drawNs / 1_000_000.0 / drawSamples))
                if (layoutSamples > 0) {
                    append(",avgLayoutMs=").append(fmt1(layoutNs / 1_000_000.0 / layoutSamples))
                }
            }
        }
        val stall = "cnt=$stallCount,maxMs=$stallMaxMs"
        // burst 在上一窗口末尾发起、约 0.5s 后完成，这里取的是上一窗口的结果。
        val burst = burstBlock
        burstBlock = null

        val message = buildString {
            append(PREFIX)
            append(" sample scope=").append(scope)
            append(" winMs=").append(if (core.windowMs > 0) core.windowMs else -1)
            append(" cpu=").append(core.cpuPct?.let { fmt1(it) + "%" } ?: "na")
                .append("/").append(core.cores).append("cores")
            append(" threads=").append(core.threadCount)
            append(" topThreads=").append(core.topThreads)
            append(" mem[").append(core.mem).append("]")
            append(" batt[").append(core.battery).append("]")
            append(" screen=").append(core.screen)
            if (state.isNotBlank()) append(" ").append(state)
            append(" frame[").append(frame).append("]")
            append(" stall[").append(stall).append("]")
            if (burst != null) append(" burst[").append(burst).append("]")
        }

        HookLogger.i(TAG, message)

        // 采样窗口输出后重置；主线程独占访问无需加锁。
        frameCount = 0
        frameTotalNs = 0
        frameMaxNs = 0
        slow17 = 0
        slow33 = 0
        slow50 = 0
        slow100 = 0
        animNs = 0
        animSamples = 0
        drawNs = 0
        drawSamples = 0
        layoutNs = 0
        layoutSamples = 0
        stallCount = 0
        stallMaxMs = 0
    }

    /**
     * 宿主视图帧回调：View.setOnFrameMetricsAvailableListener 在 SDK 36 起被弃用、
     * 新编译桩已移除，但运行平台通常仍保留，故走反射挂载。
     * 解析失败只记录一次；每次调用失败按采样节奏记录原因，绝不静默吞掉
     * （真机 K60 曾出现 frame[none] 而视图在持续渲染，需能区分挂载失败与无帧）。
     */
    private var viewFrameMetricsResolved = false
    private var viewListenerInterface: Class<*>? = null
    private var viewListenerSetMethod: java.lang.reflect.Method? = null
    private var lastAttachError: String? = null

    private fun attachViewFrameMetricsListener(view: View) {
        if (!viewFrameMetricsResolved) {
            viewFrameMetricsResolved = true
            runCatching {
                val listenerClass = Class.forName("android.view.View\$OnFrameMetricsAvailableListener")
                viewListenerSetMethod = View::class.java.getMethod(
                    "setOnFrameMetricsAvailableListener",
                    listenerClass,
                    Handler::class.java,
                )
                viewListenerInterface = listenerClass
            }.onFailure {
                HookLogger.w(
                    TAG,
                    "$PREFIX view_frame_metrics_unavailable reason=${it.javaClass.simpleName}:${it.message}",
                )
            }
        }
        val listenerClass = viewListenerInterface ?: return
        val method = viewListenerSetMethod ?: return
        runCatching {
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, _, args ->
                // 回调经 mainHandler 派发，仍在主线程聚合。
                (args?.getOrNull(1) as? FrameMetrics)?.let(::onFrame)
                null
            }
            // 重复设置只覆盖旧监听，无需簿记；视图分离后回调自然消失。
            method.invoke(view, proxy, mainHandler)
            lastAttachError = null
        }.onFailure { error ->
            val reason = "${error.javaClass.simpleName}:${error.cause?.javaClass?.simpleName}"
            if (lastAttachError != reason) {
                lastAttachError = reason
                HookLogger.w(TAG, "$PREFIX view_frame_attach_failed reason=$reason")
            }
        }
    }

    /**
     * Choreographer 主线程帧间隔兜底测量：不依赖已弃用/被移除的 FrameMetrics 监听器，
     * 只在采样窗口末尾连测 30 个 vsync 回调间隔（约 0.5s），量化主线程帧节奏抖动。
     * 仅亮屏时执行；息屏时 Choreographer 可能不出帧，链路会挂起。
     */
    private fun scheduleFrameBurst() {
        if (!isScreenInteractive()) return
        val choreographer = runCatching {
            android.view.Choreographer.getInstance()
        }.getOrNull() ?: return
        var lastNs = -1L
        var samples = 0
        var totalDeltaNs = 0L
        var maxDeltaNs = 0L
        var over33 = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastNs > 0) {
                    val delta = frameTimeNanos - lastNs
                    if (delta > 0) {
                        samples++
                        totalDeltaNs += delta
                        if (delta > maxDeltaNs) maxDeltaNs = delta
                        if (delta > SLOW_33_NS) over33++
                    }
                }
                lastNs = frameTimeNanos
                if (samples < FRAME_BURST_SAMPLES) {
                    choreographer.postFrameCallback(this)
                } else {
                    burstBlock = "n=$samples,avgMs=" + fmt1(totalDeltaNs / 1_000_000.0 / samples) +
                        ",maxMs=" + fmt1(maxDeltaNs / 1_000_000.0) +
                        ",over33=$over33"
                }
            }
        }
        choreographer.postFrameCallback(callback)
    }

    @Volatile
    private var burstBlock: String? = null

    private fun onFrame(metrics: FrameMetrics) {
        runCatching {
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (total <= 0) return
            frameCount++
            frameTotalNs += total
            if (total > frameMaxNs) frameMaxNs = total
            if (total > SLOW_17_NS) slow17++
            if (total > SLOW_33_NS) slow33++
            if (total > SLOW_50_NS) slow50++
            if (total > SLOW_100_NS) slow100++
            val anim = metrics.getMetric(FrameMetrics.ANIMATION_DURATION)
            if (anim > 0) {
                animNs += anim
                animSamples++
            }
            val draw = metrics.getMetric(FrameMetrics.DRAW_DURATION)
            if (draw > 0) {
                drawNs += draw
                drawSamples++
            }
            val layout = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
            if (layout > 0) {
                layoutNs += layout
                layoutSamples++
            }
        }
    }

    /**
     * 主线程心跳：postDelayed 的实际派发延迟即停顿时长。
     * uptimeMillis 与 Handler 同基准；息屏时主线程消息可长时间不派发，属正常深睡，
     * 仅在亮屏时计入停顿。深睡期间 uptime 同样暂停，迟到≈0；大迟到只发生在
     * 设备醒着而主线程没跑的场景（整进程挂起），据此补冻结取证行。
     */
    private val stallProbe = object : Runnable {
        private var scheduledForMs = 0L

        override fun run() {
            val now = uptimeMillisSafe()
            if (scheduledForMs > 0) {
                val delayedMs = now - scheduledForMs
                if (delayedMs > STALL_WARN_THRESHOLD_MS && isScreenInteractive()) {
                    stallCount++
                    if (delayedMs > stallMaxMs) stallMaxMs = delayedMs
                }
                if (delayedMs > HEARTBEAT_GAP_THRESHOLD_MS) {
                    emitFreezeGap(
                        src = "main",
                        gapMs = delayedMs,
                        uptimeJumpMs = -1L,
                        screenBefore = null,
                        screenNow = isScreenInteractive(),
                    )
                }
            }
            scheduledForMs = now + STALL_PROBE_INTERVAL_MS
            mainHandler.postDelayed(this, STALL_PROBE_INTERVAL_MS)
        }
    }

    internal data class ThreadSample(val tid: Int, val name: String, val jiffies: Long)

    internal data class FreezeGapVerdict(
        val gapMs: Long,
        val uptimeJumpMs: Long,
        val suspendMs: Long,
        val likely: String,
    )

    /**
     * 冻结判别（纯逻辑，供单测）：冻结中的进程连采样线程都不运行，只能在解冻后的
     * 第一个节拍回看补记。elapsedRealtime 含深睡时间、uptimeMillis 不含：
     *  - uptime 几乎走满（≥80% 窗口）→ 设备醒着而本进程没跑，冻结类
     *    （cached-app freezer / SIGSTOP）；
     *  - uptime 几乎没走（≤20%）→ 窗口被 CPU 深睡吸收，不是冻结；
     *  - 两者之间记 mixed。原始数值始终完整落盘，likely 只是阅读提示。
     */
    internal fun classifyFreezeGap(realtimeJumpMs: Long, uptimeJumpMs: Long): FreezeGapVerdict {
        val safeRealtime = realtimeJumpMs.coerceAtLeast(0L)
        val safeUptime = uptimeJumpMs.coerceIn(0L, safeRealtime)
        val likely = when {
            safeRealtime <= 0L -> "unclear"
            safeUptime * 5 >= safeRealtime * 4 -> "frozen"
            safeUptime * 5 <= safeRealtime -> "suspend"
            else -> "mixed"
        }
        return FreezeGapVerdict(
            gapMs = safeRealtime,
            uptimeJumpMs = safeUptime,
            suspendMs = safeRealtime - safeUptime,
            likely = likely,
        )
    }

    /**
     * 冻结空窗补记（debug 取证，Issue #34）。src=sampler 来自 15s 采样节拍
     * （gapMs=真实墙钟窗口，附双钟差与亮屏前后状态）；src=main 来自 5s 主线程心跳的
     * uptime 迟到（深睡时 uptime 暂停、迟到≈0，大迟到即设备醒着而主线程没跑，
     * 无需双钟差已可排除深睡）。procState=T 指 SIGSTOP 型挂起；cg/cgFreeze 提供
     * cgroup freezer 线索（应用域多无权限读冻结位，读不到记 na）。冻结决策方
     * （哪个系统组件下的手）系统不向应用披露，组件级归因需系统侧 logcat/bugreport
     * 的 am_kill / freezer 记录；本行负责回答「是否被冻结、冻了多久、机制类型」。
     */
    private fun emitFreezeGap(
        src: String,
        gapMs: Long,
        uptimeJumpMs: Long,
        screenBefore: Boolean?,
        screenNow: Boolean,
    ) {
        val verdict = if (uptimeJumpMs >= 0L) classifyFreezeGap(gapMs, uptimeJumpMs) else null
        val message = buildString {
            append(PREFIX)
            append(" freeze_gap src=").append(src)
            append(" scope=").append(sanitize(scope))
            append(" pid=").append(android.os.Process.myPid())
            append(" gapMs=").append(gapMs)
            if (verdict != null) {
                append(" uptimeJumpMs=").append(verdict.uptimeJumpMs)
                append(" suspendMs=").append(verdict.suspendMs)
            }
            if (screenBefore != null) {
                append(" screenBefore=").append(if (screenBefore) "on" else "off")
            }
            append(" screen=").append(if (screenNow) "on" else "off")
            append(" procState=").append(readSelfStatState() ?: "na")
            append(" cg=").append(readSelfCgroup() ?: "na")
            append(" cgFreeze=").append(readSelfCgroupFreezeFlag() ?: "na")
            if (verdict != null) append(" likely=").append(verdict.likely)
        }
        HookLogger.i(TAG, message)
    }

    private fun readSelfJiffies(): Long? {
        val line = runCatching { File("/proc/self/stat").readText().trim() }.getOrNull()
            ?: return null
        return parseJiffiesFromStatLine(line)
    }

    private fun readThreads(): List<ThreadSample> {
        val tasks = runCatching { File("/proc/self/task").listFiles() }.getOrNull()
            ?: return emptyList()
        return tasks.mapNotNull { dir ->
            val tid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val statLine = runCatching { File(dir, "stat").readText().trim() }.getOrNull()
                ?: return@mapNotNull null
            val jiffies = parseJiffiesFromStatLine(statLine) ?: return@mapNotNull null
            val name = runCatching {
                File(dir, "comm").readText().trim('\n', ' ', NUL_CHAR)
            }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: lastThreadNames[tid]
                ?: "tid$tid"
            ThreadSample(tid, name, jiffies)
        }
    }

    private fun memorySnapshot(): String {
        val info = Debug.MemoryInfo()
        if (!runCatching { Debug.getMemoryInfo(info) }.isSuccess) return "na"
        val nativeHeapMb = runCatching { Debug.getNativeHeapAllocatedSize() / 1_048_576.0 }
            .getOrDefault(-1.0)
        return buildString {
            append("pssMb=").append(fmt1(info.totalPss / 1024.0))
            append(",nativeHeapMb=")
                .append(if (nativeHeapMb >= 0) fmt1(nativeHeapMb) else "na")
            append(",dalvikPssMb=").append(fmt1(info.dalvikPss / 1024.0))
        }
    }

    private fun batterySnapshot(app: Application): String {
        val intent = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val tempTenthC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val bm = runCatching { app.getSystemService(BatteryManager::class.java) }.getOrNull()
        val currentNowUa = bm?.let {
            runCatching { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()
        }
        val currentAvgUa = bm?.let {
            runCatching {
                it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            }.getOrNull()
        }
        return buildString {
            append("level=")
            append(if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "na")
            append(",status=").append(batteryStatusName(status))
            append(",temp=")
            append(if (tempTenthC != Int.MIN_VALUE) fmt1(tempTenthC / 10.0) + "C" else "na")
            append(",volt=").append(if (voltageMv > 0) "${voltageMv}mV" else "na")
            append(",curNow=").append(formatUa(currentNowUa))
            append(",curAvg=").append(formatUa(currentAvgUa))
            // 电流符号约定随厂商而异：只按实测电压报功率幅值，掉电归因看 status=discharging。
            if (currentNowUa != null && currentNowUa != Int.MIN_VALUE &&
                absOf(currentNowUa) in 1 until 30_000_000 && voltageMv > 0
            ) {
                append(",pwrMag~=")
                append(fmt0(absOf(currentNowUa).toLong() * voltageMv / 1_000_000L))
                append("mW")
            }
        }
    }

    private fun screenSnapshot(app: Application, interactive: Boolean?): String {
        val powerSave = runCatching { app.getSystemService(PowerManager::class.java)?.isPowerSaveMode }.getOrNull()
        return buildString {
            append(if (interactive == true) "on" else "off")
            append(",psm=").append(
                when (powerSave) {
                    true -> "on"
                    false -> "off"
                    null -> "na"
                },
            )
        }
    }

    private fun isScreenInteractive(): Boolean =
        runCatching { appRef?.getSystemService(PowerManager::class.java)?.isInteractive }
            .getOrNull() == true

    private fun formatUa(ua: Int?): String = when {
        ua == null || ua == Int.MIN_VALUE -> "na"
        else -> "${ua}uA"
    }

    private fun absOf(value: Int): Int = if (value < 0) -value else value

    private fun batteryStatusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private fun refreshRateHz(app: Application): Double = runCatching {
        val displayManager =
            app.getSystemService(android.hardware.display.DisplayManager::class.java)
        displayManager
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?.refreshRate
            ?.toDouble()
    }.getOrNull() ?: 0.0

    private fun elapsedRealtimeSafe(): Long = runCatching {
        SystemClock.elapsedRealtime()
    }.getOrElse {
        System.nanoTime() / 1_000_000L
    }

    private fun uptimeMillisSafe(): Long = runCatching {
        SystemClock.uptimeMillis()
    }.getOrElse {
        System.currentTimeMillis()
    }

    internal fun sanitize(value: String): String =
        value.replace(Regex("\\s+"), "_").take(NAME_MAX_CHARS)

    private fun fmt1(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun fmt0(value: Long): String = value.toString()

    internal fun formatTopThreads(
        current: List<ThreadSample>,
        previous: Map<Int, Long>,
        windowMs: Long,
        ticksPerSec: Double,
    ): String {
        if (windowMs <= 0 || previous.isEmpty()) return "na"
        val windowSec = windowMs / 1000.0
        return current.mapNotNull { thread ->
            previous[thread.tid]?.let { prev -> thread to (thread.jiffies - prev) }
        }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(TOP_THREAD_COUNT)
            .joinToString(",") { (thread, delta) ->
                val pct = delta * 100.0 / (windowSec * ticksPerSec)
                "${sanitize(thread.name)}(${thread.tid})=${fmt1(pct)}%"
            }
            .ifEmpty { "idle" }
    }

    /**
     * 解析 /proc/<pid>/stat 或 /proc/<pid>/task/<tid>/stat 的 utime+stime（jiffies）。
     * comm 可能含空格与括号，必须以最后一个 ')' 为界切分。
     */
    internal fun parseJiffiesFromStatLine(line: String): Long? {
        val close = line.lastIndexOf(')')
        if (close < 0 || close + 1 >= line.length) return null
        val rest = line.substring(close + 1).trim().split(' ')
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    /** 解析 /proc/<pid>/stat 的进程状态字符（')' 后第 0 项；T=SIGSTOP 挂起）。 */
    internal fun parseStateFromStatLine(line: String): Char? {
        val close = line.lastIndexOf(')')
        if (close < 0 || close + 1 >= line.length) return null
        val rest = line.substring(close + 1).trim()
        return rest.firstOrNull()?.takeIf { it != ' ' }
    }

    private fun readSelfStatState(): Char? =
        runCatching { File("/proc/self/stat").readText().trim() }
            .getOrNull()
            ?.let(::parseStateFromStatLine)

    private fun readSelfCgroup(): String? =
        runCatching {
            File("/proc/self/cgroup").readText().trim().lineSequence().firstOrNull()
        }.getOrNull()?.takeIf { it.isNotBlank() }

    /** cgroup v2 冻结位（1=冻结中）。应用 SELinux 域多无权限读，失败记 na 不影响其余证据。 */
    private fun readSelfCgroupFreezeFlag(): String? {
        val cgroupPath = readSelfCgroup()
            ?.takeIf { it.startsWith("0::") }
            ?.removePrefix("0::")
            ?.trimEnd('/')
            ?: return null
        return runCatching {
            File("/sys/fs/cgroup$cgroupPath/cgroup.freeze").readText().trim()
        }.getOrNull()?.ifBlank { null }
    }

    private const val NUL_CHAR = '\u0000'
}
