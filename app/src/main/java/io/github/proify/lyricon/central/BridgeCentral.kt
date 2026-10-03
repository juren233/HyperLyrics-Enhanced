/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.proify.lyricon.central.provider.player.SystemActiveAudioPlaybackMonitor
import io.github.proify.lyricon.central.util.ScreenStateMonitor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 中央桥接管理对象。
 *
 * 负责初始化全局 Context，并管理核心广播通信：
 * - 注册 [CentralReceiver] 接收提供者注册请求；
 * - 向系统或其他组件发送启动完成广播。
 */
@SuppressLint("StaticFieldLeak")
object BridgeCentral {

    /** 全局应用 Context，用于广播和注册接收器 */
    private lateinit var context: Context

    /** 用于接收中央控制广播的接收器实例 */
    private val receiver = CentralReceiver
    private var receiverRegistered = false

    private val active = AtomicBoolean(false)
    @Volatile private var retired = false

    /** End this generation before the replacement advertises its new Binder services. */
    fun releaseForReload() {
        retired = true
        active.set(false)
        discardPendingRegistrations()
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
        CentralRuntime.subscribers.closeAll()
        CentralRuntime.providers.closeAll()
        ScreenStateMonitor.release()
    }

    /** Reversible shutdown when HLE no longer has a lyric display consuming Central. */
    fun deactivate() {
        val wasActive = synchronized(this) {
            val previous = active.getAndSet(false)
            discardPendingRegistrations()
            if (receiverRegistered) context.unregisterReceiver(receiver)
            receiverRegistered = false
            previous
        }
        val failures = listOf(
            runCatching { CentralRuntime.subscribers.closeAll(retire = false) },
            runCatching { CentralRuntime.providers.closeAll(retire = false) },
        ).mapNotNull { it.exceptionOrNull() }
        ScreenStateMonitor.release()
        if (wasActive && ::context.isInitialized) {
            // One handoff notification lets clients register with an existing standalone Central.
            // Our registration receiver is already detached, so this cannot reclaim their binding.
            context.sendBroadcast(Intent(Constants.ACTION_CENTRAL_BOOT_COMPLETED))
        }
        check(failures.isEmpty()) { "Central shutdown failed: ${failures.firstOrNull()}" }
    }
    private val pendingRegistrations = LinkedHashMap<String, Intent>()

    /**
     * 初始化中央桥接。
     *
     * 仅在第一次调用时生效，后续调用将被忽略。
     *
     * @param appContext 应用级 Context
     */
    fun initialize(appContext: Context, startActive: Boolean = true) {
        synchronized(this) {
            check(!retired) { "Central has been retired" }
            if (!::context.isInitialized) {
                context = appContext.applicationContext
            }
            if (!receiverRegistered) {
                ScreenStateMonitor.initialize(appContext)
                CentralRuntime.activePlayers.setActiveAudioPlaybackMonitor(
                    SystemActiveAudioPlaybackMonitor(context),
                )
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter().apply {
                        addAction(Constants.ACTION_REGISTER_PROVIDER)
                        addAction(Constants.ACTION_REGISTER_SUBSCRIBER)
                    },
                    ContextCompat.RECEIVER_EXPORTED
                )
                receiverRegistered = true
            }
        }
        if (startActive) activate()
    }

    /** Enables registration responses and replays Binder registrations captured in standby mode. */
    fun activate() {
        val pending = synchronized(this) {
            if (!active.compareAndSet(false, true)) return
            pendingRegistrations.values.toList().also { pendingRegistrations.clear() }
        }
        pending.forEach(CentralRuntime.registration::handle)
    }

    internal fun handleRegistration(intent: Intent) {
        var dispatchImmediately = false
        synchronized(this) {
            if (retired || !receiverRegistered) return
            if (active.get()) {
                dispatchImmediately = true
            } else {
                val key = registrationKey(intent)
                pendingRegistrations[key] = Intent(intent)
                while (pendingRegistrations.size > MAX_PENDING_REGISTRATIONS) {
                    pendingRegistrations.remove(pendingRegistrations.keys.first())
                }
            }
        }
        if (dispatchImmediately) CentralRuntime.registration.handle(intent)
    }

    internal fun discardPendingRegistrations() {
        synchronized(this) { pendingRegistrations.clear() }
    }

    /**
     * 发送中央启动完成广播。
     *
     * 通知系统或其他组件中央模块已完成初始化。
     */
    fun sendBootCompleted() {
        if (!::context.isInitialized || !active.get()) return
        context.sendBroadcast(Intent(Constants.ACTION_CENTRAL_BOOT_COMPLETED))
    }

    private fun registrationKey(intent: Intent): String {
        val binder = intent.getBundleExtra(Constants.EXTRA_BUNDLE)
            ?.getBinder(Constants.EXTRA_BINDER)
        return "${intent.action}:${binder?.hashCode() ?: intent.hashCode()}"
    }

    private const val MAX_PENDING_REGISTRATIONS = 64
}
