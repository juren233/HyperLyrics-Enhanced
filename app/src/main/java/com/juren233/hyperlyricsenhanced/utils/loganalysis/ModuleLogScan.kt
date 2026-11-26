/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils.loganalysis

/**
 * LSPosed（Vector）modules*.log 里一条本模块日志的首行。
 *
 * 实际格式（取自真机导出的全部日志）：
 * `[ 2026-11-25T09:52:00.336     1000: 24949: 24949 I/LSPosedFramework ] (com.android.systemui)[com.juren233.hyperlyricsenhanced,null,…] [HookEntry] 模块加载完成，当前应用版本8.5.0-canary-250054`
 */
internal data class ModuleLogRecord(
    /** 形如 2026-11-25T09:52:00.336，字典序即时间序。 */
    val time: String,
    val uid: Int,
    val pid: Int,
    val level: Char,
    /** 宿主进程名，如 com.android.systemui、com.tencent.qqmusic:QQPlayerService。 */
    val process: String,
    val tag: String,
    val message: String,
    /** 原始首行，作为依据展示给用户。 */
    val raw: String,
) {
    /** 进程所属包名：去掉 `:子进程` 后缀。 */
    val packageName: String get() = process.substringBefore(':')
}

internal object ModuleLogLineParser {
    private val HEADER = Regex(
        """^\[\s*(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+):\s*(\d+):\s*\d+\s+([VDIWEC])/\S*\s*]\s*""" +
            """\(([^)]*)\)\[com\.juren233\.hyperlyricsenhanced[^\]]*?(?:,(\d+),\d+)?]\s*\[([^\]]+)]\s?(.*)$""",
    )

    /**
     * 一条日志太长时 LSPosed 会拆成几行，方括号末尾两个数字是「第几段,是否最后一段」：
     * `…-2-483,0,0]` 是第一段，`…-2-483,1,1]` 是续行。续行不是新的一条日志（真实导出里续行开头被当成了
     * `[pronunciation]` 这样的假 tag），直接跳过。
     */
    fun parse(line: String): ModuleLogRecord? {
        if (!line.startsWith("[")) return null
        val match = HEADER.find(line) ?: return null
        val chunk = match.groupValues[6].toIntOrNull() ?: 0
        if (chunk > 0) return null
        val time = match.groupValues[1]
        val uid = match.groupValues[2]
        val pid = match.groupValues[3]
        val level = match.groupValues[4]
        val process = match.groupValues[5]
        val tag = match.groupValues[7]
        val message = match.groupValues[8]
        return ModuleLogRecord(
            time = time,
            uid = uid.toIntOrNull() ?: return null,
            pid = pid.toIntOrNull() ?: return null,
            level = level.first(),
            process = process.trim(),
            tag = tag.trim(),
            message = message.trim(),
            raw = line.trim(),
        )
    }
}

/**
 * 逐行扫描模块日志，只留下规则需要的少量记录，16MB 日志也只占很小的内存。
 * 行可以乱序输入（多个日志文件拼接），所有「最新」都按时间比较。
 */
internal class ModuleLogScan {
    /** HookEntry 在 onModuleLoaded 里打的「模块加载完成，当前应用版本<name>-<code>」。 */
    data class ModuleLoad(val record: ModuleLogRecord, val versionName: String, val versionCode: Int)

    var recordCount = 0
        private set

    private val latestLoadByProcess = HashMap<String, ModuleLoad>()
    private val latestLoadByPid = HashMap<Int, ModuleLoad>()
    private val latestHotReloadRejectedByPid = HashMap<Int, ModuleLogRecord>()
    private val latestRestartRequiredByPid = HashMap<Int, ModuleLogRecord>()
    private val latestRecordByProcess = HashMap<String, ModuleLogRecord>()
    private var latestActiveSessionBroadcast: ModuleLogRecord? = null
    private val latestSignalByPid = HashMap<Pair<LogSignal, Int>, ModuleLogRecord>()
    private val latestConfigBroadcastByPid = HashMap<Pair<String, Int>, ModuleLogRecord>()
    private val customFontFailuresByPid = HashMap<Int, MutableList<ModuleLogRecord>>()
    private val unrecognizedByPid = HashMap<Int, MutableList<UnrecognizedProblem>>()
    private val processByPid = HashMap<Int, String>()

    /** 没有任何规则认得的一条报错（E）或警告（W），只记时间和级别。 */
    data class UnrecognizedProblem(val time: String, val isError: Boolean)

    fun acceptLine(line: String) {
        val record = ModuleLogLineParser.parse(line) ?: return
        accept(record)
    }

    fun accept(record: ModuleLogRecord) {
        recordCount++
        latestRecordByProcess.keepLatest(record.process, record)
        val signal = LogSignal.of(record)
        if (signal != null) {
            latestSignalByPid.keepLatest(signal to record.pid, record)
            if (signal == LogSignal.CUSTOM_FONT_FAILED) {
                val list = customFontFailuresByPid.getOrPut(record.pid) { mutableListOf() }
                if (list.size < MAX_FONT_FAILURES_PER_PID) list += record
            }
        } else if (isUnrecognizedProblem(record)) {
            processByPid[record.pid] = record.process
            unrecognizedByPid.getOrPut(record.pid) { mutableListOf() } +=
                UnrecognizedProblem(record.time, isError = record.level == 'E')
        }
        if (record.tag != HOOK_ENTRY_TAG) return
        val message = record.message
        when {
            message.startsWith(MODULE_LOADED_PREFIX) -> parseLoad(record)?.let { load ->
                val previous = latestLoadByProcess[record.process]
                if (previous == null || previous.record.time <= record.time) {
                    latestLoadByProcess[record.process] = load
                }
                val previousInPid = latestLoadByPid[record.pid]
                if (previousInPid == null || previousInPid.record.time <= record.time) {
                    latestLoadByPid[record.pid] = load
                }
            }
            HOT_RELOAD_NOT_APPLIED.any(message::startsWith) ->
                latestHotReloadRejectedByPid.keepLatest(record.pid, record)
            record.level == 'E' && RESTART_SYSTEM_UI_MESSAGES.any(message::startsWith) ->
                latestRestartRequiredByPid.keepLatest(record.pid, record)
            message.startsWith(CONFIG_BROADCAST_PREFIX) -> {
                if (message.startsWith(ACTIVE_SESSION_BROADCAST_PREFIX)) {
                    val previous = latestActiveSessionBroadcast
                    if (previous == null || previous.time <= record.time) latestActiveSessionBroadcast = record
                }
                val key = message.removePrefix(CONFIG_BROADCAST_PREFIX).substringBefore(',').trim()
                if (key.isNotEmpty()) latestConfigBroadcastByPid.keepLatest(key to record.pid, record)
            }
        }
    }

    /** 某个 pid 里某类信号最近的一条。 */
    fun latestSignal(signal: LogSignal, pid: Int): ModuleLogRecord? = latestSignalByPid[signal to pid]

    /** 某个 pid 里某个设置项最近一次「收到配置广播」。 */
    fun latestConfigBroadcast(key: String, pid: Int): ModuleLogRecord? = latestConfigBroadcastByPid[key to pid]

    /** 某个 pid 里键名以 [prefix] 开头的设置项最近一次「收到配置广播」。 */
    fun latestConfigBroadcastWithPrefix(prefix: String, pid: Int): ModuleLogRecord? =
        latestConfigBroadcastByPid.entries
            .filter { (key, _) -> key.second == pid && key.first.startsWith(prefix) }
            .maxByOrNull { it.value.time }
            ?.value

    /** 某个 pid 里读不到自定义字体的记录（FontHelper 每个路径只记一次）。 */
    fun customFontFailures(pid: Int): List<ModuleLogRecord> = customFontFailuresByPid[pid].orEmpty()

    /** 有没认出的报错 / 警告的 pid 及其进程名。 */
    fun pidsWithUnrecognizedProblems(): Map<Int, String> = processByPid

    /** 某个 pid 里时间不早于 [since] 的没认出的报错 / 警告。 */
    fun unrecognizedProblems(pid: Int, since: String): List<UnrecognizedProblem> =
        unrecognizedByPid[pid].orEmpty().filter { it.time >= since }

    /** 每个进程最近一次加载的模块版本。 */
    fun latestLoads(): Map<String, ModuleLoad> = latestLoadByProcess

    fun hotReloadRejected(pid: Int): ModuleLogRecord? = latestHotReloadRejectedByPid[pid]

    /** 每个 pid 最近一次「拒绝热重载」。 */
    fun hotReloadRejections(): Collection<ModuleLogRecord> = latestHotReloadRejectedByPid.values

    /** 某个 pid 最近一次加载的模块版本。 */
    fun latestLoadInPid(pid: Int): ModuleLoad? = latestLoadByPid[pid]

    fun restartRequired(pid: Int): ModuleLogRecord? = latestRestartRequiredByPid[pid]

    fun latestRecord(process: String): ModuleLogRecord? = latestRecordByProcess[process]

    fun hasRecordsFrom(process: String): Boolean = process in latestRecordByProcess

    /** 最近一条「收到配置广播…key_active_media_session_packages」，用来佐证正在播放的 App。 */
    fun activeSessionBroadcast(): ModuleLogRecord? = latestActiveSessionBroadcast

    /**
     * 没有规则认得的 E / W 行。只在 debug 包里写的诊断输出不是问题，不算：`[debug]` 开头的调试信息、
     * `[RUNTIME_PERF_DIAG]` 性能诊断、CoverColorDiagnostic 封面色诊断。
     */
    private fun isUnrecognizedProblem(record: ModuleLogRecord): Boolean =
        (record.level == 'E' || record.level == 'W') &&
            record.tag !in DEBUG_ONLY_TAGS &&
            DEBUG_ONLY_MESSAGE_PREFIXES.none(record.message::startsWith)

    private fun parseLoad(record: ModuleLogRecord): ModuleLoad? {
        val version = record.message.removePrefix(MODULE_LOADED_PREFIX).trim()
        val match = VERSION_SUFFIX.find(version) ?: return null
        return ModuleLoad(
            record = record,
            versionName = match.groupValues[1],
            versionCode = match.groupValues[2].toIntOrNull() ?: return null,
        )
    }

    private fun <K> HashMap<K, ModuleLogRecord>.keepLatest(key: K, record: ModuleLogRecord) {
        val previous = this[key]
        if (previous == null || previous.time <= record.time) this[key] = record
    }

    internal companion object {
        const val HOOK_ENTRY_TAG = "HookEntry"

        /** 与 HookEntry.onModuleLoaded 的文案逐字一致。 */
        const val MODULE_LOADED_PREFIX = "模块加载完成，当前应用版本"

        /** 与 HookEntry.onHotReloading 的文案逐字一致：宿主拒绝热重载，旧版本继续运行。 */
        const val HOT_RELOAD_REJECTED = "当前宿主尚不具备完整热重载状态，需要重启对应进程"

        /** HookEntry.onHotReloading：主线程超时没接手热重载，同样保留旧版本继续运行。 */
        const val HOT_RELOAD_MAIN_THREAD_TIMEOUT = "主线程未及时接受热重载，已取消且保留当前运行状态"

        val HOT_RELOAD_NOT_APPLIED = listOf(HOT_RELOAD_REJECTED, HOT_RELOAD_MAIN_THREAD_TIMEOUT)

        const val CONFIG_BROADCAST_PREFIX = "收到配置广播并更新运行时覆盖: key="

        private const val MAX_FONT_FAILURES_PER_PID = 16

        /** debug 包专用的调试输出前缀（如 LyriconSource 的 `HookLogger.w(TAG, "[debug] $message")`）。 */
        const val DEBUG_ONLY_PREFIX = "[debug]"

        /** RuntimePerfDiagnostics 的输出前缀，start() 里 `if (!BuildConfig.DEBUG) return`，只有 debug 包会写。 */
        const val RUNTIME_PERF_DIAG_PREFIX = "[RUNTIME_PERF_DIAG]"

        val DEBUG_ONLY_MESSAGE_PREFIXES = listOf(DEBUG_ONLY_PREFIX, RUNTIME_PERF_DIAG_PREFIX)

        /** CoverColorDiagnostics：每个入口 `if (!BuildConfig.DEBUG) return`，w 级只是记录封面色切换，不是问题。 */
        val DEBUG_ONLY_TAGS = setOf("CoverColorDiagnostic")

        /** HookEntry 里明确要求「重启系统界面」的三条错误。 */
        val RESTART_SYSTEM_UI_MESSAGES = listOf(
            "热重载清理失败，已尝试释放全部资源，需要重启系统界面",
            "运行时配置更新失败，需要重启系统界面后重试",
            "系统媒体 Provider 刷新失败，需要重启系统界面后重试",
        )

        const val ACTIVE_SESSION_BROADCAST_PREFIX =
            "收到配置广播并更新运行时覆盖: key=key_active_media_session_packages"

        /** 「8.5.0-canary-250054」：versionName 本身可能带连字符，版本号是最后一段数字。 */
        private val VERSION_SUFFIX = Regex("""^(.+)-(\d+)$""")
    }
}
