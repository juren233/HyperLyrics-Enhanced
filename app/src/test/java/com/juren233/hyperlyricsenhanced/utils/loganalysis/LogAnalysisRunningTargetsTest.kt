package com.juren233.hyperlyricsenhanced.utils.loganalysis

import android.os.RemoteException
import io.github.libxposed.service.XposedService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * 读取正在运行的作用域进程（getRunningTargets）只有 API 102 的框架才支持；模块声明的最低版本是 101。
 * 这里用假的框架服务验证：101 的框架上不去调用、不崩溃，退回日志 + pidof 判断。
 *
 * 框架接口 IXposedService 和 HookedProcess 只在运行时类路径上（编译期看不到），所以用反射构造。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LogAnalysisRunningTargetsTest {

    private companion object {
        val serviceInterface: Class<*> = Class.forName("io.github.libxposed.service.IXposedService")
        val hookedProcess: Class<*> = Class.forName("io.github.libxposed.service.HookedProcess")

        // HookedProcess.TARGET_STATE_*
        const val STATE_UP_TO_DATE = 0
        const val STATE_STALE = 1
    }

    private class FakeFramework(
        private val apiVersion: () -> Int,
        private val targets: () -> List<Any>,
    ) {
        var runningTargetsCalls = 0

        fun service(): XposedService {
            val binder = Proxy.newProxyInstance(
                serviceInterface.classLoader,
                arrayOf(serviceInterface),
            ) { _, method, _ ->
                when (method.name) {
                    "getApiVersion" -> apiVersion()
                    "getRunningTargets" -> {
                        runningTargetsCalls++
                        targets()
                    }
                    else -> null
                }
            }
            val constructor = XposedService::class.java.getDeclaredConstructor(serviceInterface)
            constructor.isAccessible = true
            return constructor.newInstance(binder)
        }
    }

    private fun process(name: String, pid: Int, state: Int, version: Long): Any =
        hookedProcess.getDeclaredConstructor().newInstance().also { process ->
            hookedProcess.getField("processName").set(process, name)
            hookedProcess.getField("pid").setInt(process, pid)
            hookedProcess.getField("state").setInt(process, state)
            hookedProcess.getField("loadedVersionCode").setLong(process, version)
        }

    @Test
    fun api101Framework_doesNotQueryRunningTargets_andReturnsNull() {
        val framework = FakeFramework(apiVersion = { 101 }, targets = { error("API 101 的框架没有这个接口") })

        assertNull(LogAnalysisRunner.runningTargets(framework.service()))
        assertEquals(0, framework.runningTargetsCalls)
    }

    @Test
    fun apiVersionQueryFails_returnsNullWithoutCrashing() {
        val framework = FakeFramework(apiVersion = { throw RemoteException("binder died") }, targets = { emptyList() })

        assertNull(LogAnalysisRunner.runningTargets(framework.service()))
        assertEquals(0, framework.runningTargetsCalls)
    }

    @Test
    fun api102Framework_mapsRunningTargets() {
        val framework = FakeFramework(
            apiVersion = { 102 },
            targets = {
                listOf(
                    process("com.android.systemui", 1234, STATE_STALE, 250092),
                    process("com.miui.aod", 2345, STATE_UP_TO_DATE, 250093),
                )
            },
        )

        assertEquals(
            listOf(
                RunningHookTarget("com.android.systemui", 1234, 250092, RunningHookTarget.State.STALE),
                RunningHookTarget("com.miui.aod", 2345, 250093, RunningHookTarget.State.UP_TO_DATE),
            ),
            LogAnalysisRunner.runningTargets(framework.service()),
        )
        assertEquals(1, framework.runningTargetsCalls)
    }

    @Test
    fun api102Framework_queryFails_returnsNull() {
        val framework = FakeFramework(apiVersion = { 102 }, targets = { throw RemoteException("binder died") })

        assertNull(LogAnalysisRunner.runningTargets(framework.service()))
    }
}
