/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OfficialProviderRuntimeReentryTest {

    private val packageName = "com.apple.android.music"
    private val loader: ClassLoader = ClassLoader.getSystemClassLoader()

    private var preferenceReads = 0
    private var reentrantResult: Boolean? = null
    private lateinit var module: XposedModule

    private fun install(): Boolean =
        OfficialProviderRuntime.installIfAvailable(module, loader, packageName, packageName)

    /** getRemotePreferences 是安装的第一步；首次读取时模拟框架对同一包名重入 onPackageLoaded。 */
    private fun newModule(reenter: Boolean): XposedModule {
        val framework = Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getRemotePreferences" -> {
                    preferenceReads++
                    if (reenter && preferenceReads == 1) reentrantResult = install()
                    throw IllegalStateException("stop after probing")
                }
                "log" -> null
                else -> error("Unexpected framework call: ${method.name}")
            }
        } as XposedInterface
        return object : XposedModule() {}.apply { attachFramework(framework) {} }
    }

    @Test
    fun sameTargetReentryReturnsImmediatelyInsteadOfRecursing() {
        module = newModule(reenter = true)

        install()

        assertEquals(false, reentrantResult)
        assertEquals(1, preferenceReads)
    }

    @Test
    fun guardIsReleasedAfterInstallReturns() {
        module = newModule(reenter = false)
        install()
        val before = preferenceReads

        assertFalse(install())
        assertEquals(before + 1, preferenceReads)
    }
}
