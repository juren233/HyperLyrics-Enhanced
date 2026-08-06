/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.app.Instrumentation
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Static host for official Provider Packs.
 *
 * Only this class touches libxposed. Pack callbacks receive ordinary Android
 * values and never receive [XposedModule] or [XposedInterface.Chain].
 */
internal class OfficialProviderHookHost(
    private val module: XposedModule,
    private val targetClassLoader: ClassLoader,
    override val packageName: String,
) : OfficialProviderHost {
    private val tag = "OfficialProviderHookHost"

    override fun hookApplication(callback: OfficialProviderApplicationCallback) {
        val method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate",
            Application::class.java,
        )
        module.hook(method).intercept(ApplicationCreatedHooker(packageName, callback))
    }

    override fun hookMediaSession(
        playbackStateCallback: OfficialProviderPlaybackStateCallback,
        metadataCallback: OfficialProviderMetadataCallback,
    ) {
        val mediaSessionClass = Class.forName(
            MediaSession::class.java.name,
            false,
            targetClassLoader,
        )
        val setPlaybackState = mediaSessionClass.getDeclaredMethod(
            "setPlaybackState",
            PlaybackState::class.java,
        )
        module.hook(setPlaybackState).intercept(
            PlaybackStateHooker(playbackStateCallback),
        )

        val setMetadata = mediaSessionClass.getDeclaredMethod(
            "setMetadata",
            MediaMetadata::class.java,
        )
        module.hook(setMetadata).intercept(MetadataHooker(metadataCallback))
    }

    override fun hookAfterMethod(
        target: OfficialProviderMethodTarget,
        callback: OfficialProviderMethodCallback,
    ) {
        require(target.className.isNotBlank()) { "Provider Hook className 不能为空" }
        require(target.methodName.isNotBlank()) { "Provider Hook methodName 不能为空" }
        val targetClass = Class.forName(target.className, false, targetClassLoader)
        val parameterTypes = target.parameterTypeNames.map(::resolveParameterType).toTypedArray()
        val method = targetClass.getDeclaredMethod(target.methodName, *parameterTypes).apply {
            isAccessible = true
        }
        require(method.returnType == resolveReturnType(target.returnTypeName)) {
            "Provider Hook 返回类型不匹配: ${method.returnType.name}"
        }
        require(Modifier.isStatic(method.modifiers) == target.isStatic) {
            "Provider Hook static 约束不匹配"
        }
        val descriptor = buildString {
            append(target.className)
            append('#')
            append(target.methodName)
            append('(')
            append(target.parameterTypeNames.joinToString())
            append(')')
            append(':')
            append(target.returnTypeName)
            append(if (target.isStatic) "[static]" else "[instance]")
        }
        module.hook(method).intercept(AfterMethodHooker(module, descriptor, callback))
        module.log(Log.INFO, tag, "官方 Provider 方法 Hook 已安装: target=$descriptor")
    }

    private fun resolveParameterType(typeName: String): Class<*> = when (typeName) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        else -> Class.forName(typeName, false, targetClassLoader)
    }

    private fun resolveReturnType(typeName: String): Class<*> = when (typeName) {
        "void" -> Void.TYPE
        else -> resolveParameterType(typeName)
    }

    private class ApplicationCreatedHooker(
        private val expectedPackageName: String,
        private val callback: OfficialProviderApplicationCallback,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val application = chain.args.firstOrNull() as? Application
            if (application?.packageName == expectedPackageName) {
                runCatching { callback.onApplicationCreated(application) }
            }
            return result
        }
    }

    private class PlaybackStateHooker(
        private val callback: OfficialProviderPlaybackStateCallback,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                callback.onPlaybackStateChanged(chain.args.firstOrNull() as? PlaybackState)
            }
            return result
        }
    }

    private class MetadataHooker(
        private val callback: OfficialProviderMetadataCallback,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                callback.onMetadataChanged(chain.args.firstOrNull() as? MediaMetadata)
            }
            return result
        }
    }

    private class AfterMethodHooker(
        private val module: XposedModule,
        private val descriptor: String,
        private val callback: OfficialProviderMethodCallback,
    ) : XposedInterface.Hooker {
        private val firstCallbackRecorded = AtomicBoolean(false)

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                callback.onMethodCalled(chain.thisObject, chain.args.toTypedArray())
            }.onSuccess {
                if (firstCallbackRecorded.compareAndSet(false, true)) {
                    module.log(
                        Log.INFO,
                        "OfficialProviderHookHost",
                        "官方 Provider 方法 Hook 首次命中: target=$descriptor",
                    )
                }
            }.onFailure { error ->
                module.log(
                    Log.ERROR,
                    "OfficialProviderHookHost",
                    "官方 Provider 方法回调失败: target=$descriptor error=${error.message}",
                )
            }
            return result
        }
    }

    fun logInstalled(pluginId: String) {
        module.log(
            Log.INFO,
            tag,
            "官方 Provider Hook 已安装: id=$pluginId package=$packageName",
        )
    }
}
