/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.IBinder
import android.os.ParcelFileDescriptor

/**
 * 真实律动拿不到数据（[IslandRealRhythmState.NO_ENERGY] / [IslandRealRhythmState.NO_CALLBACK]）时
 * 附带的输出信息，用来区分是不是走了 session 0 全局效果覆盖不到的路径
 * （直通、MMAP、bit-perfect、FAST 轨道，见知识库 island-audio-rhythm.md）。
 *
 * - [playback]：正在播放的媒体播放器（release 保留，只在状态翻转时读一次）。
 *   播放器类型、采样率、声道、空间化、静音原因是 AudioPlaybackConfiguration 的 SystemApi，
 *   SystemUI 进程可读；读不到时省略该项。输出线程的类型（直通/offload/MMAP）没有公开接口。
 * - [audioFlingerThreads]：debug 专用，读取 `media.audio_flinger` dump，列出未待机的输出线程、
 *   线程类型、输出设备，以及 session 0 上是否挂着 Visualizer。
 */
internal object IslandRealRhythmOutputInfo {
    /** OpenSL ES `SL_IID_VISUALIZATION`，即 Visualizer 效果类型。 */
    private const val VISUALIZER_TYPE_UUID = "e46b26a0-dddd-11db-8afd-0002a5d5c51b"
    private const val PLAYER_STATE_STARTED = 2

    data class Player(
        val playerType: Int?,
        val deviceType: Int?,
        val sampleRate: Int?,
        val channelMask: Int?,
        val spatialized: Boolean?,
        val mutedBy: Int?,
        val usage: Int? = AudioAttributes.USAGE_MEDIA,
    )

    fun playback(context: Context): String {
        val audio = context.getSystemService(AudioManager::class.java) ?: return "players=unavailable"
        val players = runCatching { audio.activePlaybackConfigurations }.getOrElse {
            return "players=error(${it.javaClass.simpleName})"
        }.filter { config ->
            (systemInt(config, "getPlayerState") ?: PLAYER_STATE_STARTED) == PLAYER_STATE_STARTED
        }.map { config ->
            Player(
                usage = runCatching { config.audioAttributes.usage }.getOrNull(),
                playerType = systemInt(config, "getPlayerType"),
                deviceType = runCatching { config.audioDeviceInfo?.type }.getOrNull(),
                sampleRate = systemInt(config, "getSampleRate"),
                channelMask = systemInt(config, "getChannelMask"),
                spatialized = runCatching {
                    config.javaClass.getMethod("isSpatialized").invoke(config) as? Boolean
                }.getOrNull(),
                mutedBy = systemInt(config, "getMutedBy"),
            )
        }
        return describePlayers(players)
    }

    /** 供 [IslandRealRhythmCaptureTarget] 选择采集 session；读不到的 SystemApi 字段按「否」处理。 */
    fun captureCandidates(configs: List<AudioPlaybackConfiguration>): List<IslandRealRhythmCaptureTarget.Player> =
        configs.map { config ->
            IslandRealRhythmCaptureTarget.Player(
                sessionId = systemInt(config, "getSessionId") ?: IslandRealRhythmCaptureTarget.OUTPUT_MIX_SESSION,
                started = systemInt(config, "getPlayerState") == PLAYER_STATE_STARTED,
                spatialized = runCatching {
                    config.javaClass.getMethod("isSpatialized").invoke(config) as? Boolean
                }.getOrNull() == true,
            )
        }

    fun describePlayers(players: List<Player>): String {
        if (players.isEmpty()) return "players=[]"
        return players.joinToString(prefix = "players=[", postfix = "]", separator = "; ") { player ->
            buildList {
                add(playerTypeName(player.playerType))
                player.usage?.takeIf { it != AudioAttributes.USAGE_MEDIA }?.let { add("usage=$it") }
                add("dev=${deviceTypeName(player.deviceType)}")
                player.sampleRate?.takeIf { it > 0 }?.let { add("${it}Hz") }
                player.channelMask?.takeIf { it != 0 }?.let { add("ch=${Integer.bitCount(it)}") }
                player.spatialized?.let { add("spatial=$it") }
                player.mutedBy?.takeIf { it != 0 }?.let { add("mutedBy=0x${Integer.toHexString(it)}") }
            }.joinToString(" ")
        }
    }

    fun playerTypeName(type: Int?): String = when (type) {
        null -> "player?"
        1 -> "AudioTrack"
        2 -> "MediaPlayer"
        3 -> "SoundPool"
        11, 12 -> "OpenSLES"
        13 -> "AAudio"
        14 -> "HwSource"
        15 -> "ExternalProxy"
        else -> "player$type"
    }

    fun deviceTypeName(type: Int?): String = when (type) {
        null -> "?"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "ble_broadcast"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix"
        else -> "type$type"
    }

    private fun systemInt(config: AudioPlaybackConfiguration, method: String): Int? = runCatching {
        config.javaClass.getMethod(method).invoke(config) as? Int
    }.getOrNull()

    /** debug 专用：在调用线程同步 dump，调用方不得在主线程调用。 */
    fun audioFlingerThreads(): String {
        val binder = runCatching {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "media.audio_flinger") as? IBinder
        }.getOrNull() ?: return "audioflinger=unavailable"
        // 必须用管道：audioserver 不能写 SystemUI 私有目录里的文件（设备实测 avc denied
        // `audioserver → system_app_data_file:file write`），而 sepolicy 允许
        // `binderservicedomain → appdomain:fifo_file write` 与 `fd use`。
        // dump 是同步事务，输出可能超过管道缓冲，所以边 dump 边在另一个线程读。
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
        var text: String? = null
        var readError: Throwable? = null
        val reader = Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).bufferedReader().use { text = it.readText() }
            }.onFailure { readError = it }
        }, "HLE-RealRhythmAfDump").apply { start() }
        return try {
            try {
                binder.dump(writeEnd.fileDescriptor, emptyArray())
            } finally {
                writeEnd.close()
            }
            reader.join(AUDIO_FLINGER_DUMP_TIMEOUT_MS)
            if (reader.isAlive) {
                runCatching { readEnd.close() }
                return "audioflinger=timeout"
            }
            readError?.let { return "audioflinger=error(${it.javaClass.simpleName}: ${it.message})" }
            summarizeAudioFlingerDump(text.orEmpty())
        } catch (t: Throwable) {
            runCatching { readEnd.close() }
            "audioflinger=error(${t.javaClass.simpleName}: ${t.message})"
        }
    }

    private const val AUDIO_FLINGER_DUMP_TIMEOUT_MS = 5_000L

    private val threadHeader = Regex("""^(\w+) thread 0x[0-9a-fA-F]+, name (\S+), tid \d+, type \d+ \((\w+)\):""")
    private val standbyLine = Regex("""^\s+Standby: (\w+)""")
    private val devicesLine = Regex("""^\s+Output devices?: \S+ \(([^)]*)\)""")
    private val tracksLine = Regex("""^\s+\d+ Tracks of which (\d+) are active""")
    private val chainLine = Regex("""^\s+\d+ effects for session (\d+)""")

    /** 只列出未待机的输出线程；没有时说明当前没有活动的输出线程。 */
    fun summarizeAudioFlingerDump(dump: String): String {
        data class Thread(
            val name: String,
            val type: String,
            var standby: String = "?",
            var devices: String = "?",
            var activeTracks: String = "?",
            var session0Visualizer: Boolean = false,
        )
        val threads = mutableListOf<Thread>()
        var current: Thread? = null
        var chainSession: String? = null
        dump.lineSequence().forEach { line ->
            threadHeader.find(line)?.let { match ->
                current = if (match.groupValues[1] == "Output") {
                    Thread(match.groupValues[2], match.groupValues[3]).also(threads::add)
                } else {
                    null
                }
                chainSession = null
                return@forEach
            }
            val thread = current ?: return@forEach
            standbyLine.find(line)?.let { thread.standby = it.groupValues[1] }
            devicesLine.find(line)?.let { thread.devices = it.groupValues[1] }
            tracksLine.find(line)?.let { thread.activeTracks = it.groupValues[1] }
            chainLine.find(line)?.let { chainSession = it.groupValues[1] }
            if (chainSession == "0" && line.contains("TYPE: $VISUALIZER_TYPE_UUID")) {
                thread.session0Visualizer = true
            }
        }
        val active = threads.filter { it.standby != "yes" }
        if (active.isEmpty()) return "afThreads=[] (共 ${threads.size} 个输出线程，均待机)"
        val visualizerElsewhere = threads.filter { it.standby == "yes" && it.session0Visualizer }
            .joinToString(",") { it.name }
        return active.joinToString(prefix = "afThreads=[", postfix = "]", separator = "; ") {
            "${it.name} ${it.type} dev=${it.devices} active=${it.activeTracks} visualizer=${it.session0Visualizer}"
        } + if (visualizerElsewhere.isEmpty()) "" else " visualizerOnStandby=$visualizerElsewhere"
    }
}
