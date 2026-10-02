/*
 * Copyright 2026 juren233
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.BufferedReader
import java.io.InputStreamReader

object AboutDeviceInfoHelper {

    private const val PREFS_NAME = "hle_device_info_prefs"
    private const val KEY_CACHED_CUSTOM_DEVICE_NAME = "cached_custom_device_name"

    @Volatile
    private var memoryCachedDeviceName: String? = null

    /**
     * 读取系统属性，优先反射 android.os.SystemProperties，失败时回退到 getprop 进程。
     */
    fun getSystemProperty(key: String): String? {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            val value = getMethod.invoke(null, key, "") as? String
            value?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: runCatching {
            val process = Runtime.getRuntime().exec("getprop $key")
            BufferedReader(InputStreamReader(process.inputStream)).use {
                val line = it.readLine()?.trim()
                line?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /**
     * 以 Root 权限安全执行命令并读取单行输出（带超时保护，避免阻塞）。
     */
    fun executeRootCommand(cmd: String, timeoutMs: Long = 1000L): String? {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            var output: String? = null
            val readerThread = Thread {
                output = runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        reader.readLine()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                    }
                }.getOrNull()
            }
            readerThread.start()
            readerThread.join(timeoutMs)
            if (readerThread.isAlive) {
                readerThread.interrupt()
                process.destroyForcibly()
                null
            } else {
                process.waitFor()
                output
            }
        }.getOrNull()
    }

    /**
     * 通过 Root 尝试读取带 SELinux 保护的系统属性（如 HyperOS 2.x 的 persist.private.device_name）。
     */
    fun getRootSystemProperty(key: String, timeoutMs: Long = 1000L): String? {
        val commands = listOf(
            "nsenter --mount=/proc/1/ns/mnt -- getprop $key",
            "getprop $key",
        )
        for (cmd in commands) {
            val value = executeRootCommand(cmd, timeoutMs)
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    /**
     * 通过 Root 尝试读取 Settings（如 settings get secure bluetooth_name）。
     */
    fun getRootSetting(namespace: String, key: String, timeoutMs: Long = 1000L): String? {
        return executeRootCommand("settings get $namespace $key", timeoutMs)
    }

    /**
     * 通过小米/MIUI/HyperOS Framework 的 MiuiSettings$System.getDeviceName(context) 反射读取。
     */
    fun getMiuiDeviceName(context: Context): String? {
        val classNames = listOf(
            "android.provider.MiuiSettings\$System",
            "android.provider.SystemSettings\$System",
        )
        for (className in classNames) {
            val name = runCatching {
                val clazz = Class.forName(className)
                val method = clazz.getMethod("getDeviceName", Context::class.java)
                val result = method.invoke(null, context) as? String
                result?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (name != null) return name
        }
        return null
    }

    /**
     * 获取关于页大标题展示的设备名称（同步快速路径，不阻塞主线程）：
     * 1. 优先使用已缓存的自定义设备名；
     * 2. 尝试从 MiuiSettings / Settings / persist 等无须 root 的接口读取；
     * 3. 回退为官方销售市场名（ro.product.marketname）；
     * 4. 最终兜底为 Build.MODEL。
     */
    fun resolveDeviceName(
        context: Context,
        systemPropertyGetter: (String) -> String? = ::getSystemProperty,
        // The app does not request BLUETOOTH_CONNECT. Keep this optional input injectable,
        // and use the existing Settings/MIUI/property paths instead of a forbidden API call.
        bluetoothNameGetter: () -> String? = { null },
    ): String {
        val cached = memoryCachedDeviceName
            ?: runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_CACHED_CUSTOM_DEVICE_NAME, null)
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()

        if (!cached.isNullOrBlank()) {
            memoryCachedDeviceName = cached
            return cached
        }

        return resolveDeviceNameInternal(
            cachedName = cached,
            miuiDeviceNameGetter = { getMiuiDeviceName(context) },
            settingsGetter = { namespace, key ->
                runCatching {
                    when (namespace) {
                        "secure" -> Settings.Secure.getString(context.contentResolver, key)
                        "system" -> Settings.System.getString(context.contentResolver, key)
                        else -> null
                    }
                }.getOrNull()
            },
            systemPropertyGetter = systemPropertyGetter,
            bluetoothNameGetter = bluetoothNameGetter,
            fallbackMarketName = systemPropertyGetter("ro.product.marketname"),
            fallbackModel = Build.MODEL,
        )
    }

    /**
     * 纯函数：用于单元测试与快速解析逻辑。
     */
    internal fun resolveDeviceNameInternal(
        cachedName: String?,
        miuiDeviceNameGetter: () -> String?,
        settingsGetter: (namespace: String, key: String) -> String?,
        systemPropertyGetter: (String) -> String?,
        bluetoothNameGetter: () -> String?,
        fallbackMarketName: String?,
        fallbackModel: String,
    ): String {
        val candidates = listOfNotNull(
            cachedName,
            miuiDeviceNameGetter(),
            settingsGetter("secure", "bluetooth_name"),
            settingsGetter("system", "device_name"),
            settingsGetter("secure", "device_name"),
            settingsGetter("system", "bluetooth_name"),
            systemPropertyGetter("persist.private.device_name"),
            systemPropertyGetter("persist.bluetooth.device_name"),
            systemPropertyGetter("persist.sys.device_name"),
            bluetoothNameGetter(),
            fallbackMarketName,
            fallbackModel,
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim() ?: fallbackModel
    }

    /**
     * 异步后台路径：尝试通过 Root 提取被 SELinux 保护的自定义设备名称并写入持久缓存。
     */
    fun resolveDeviceNameWithRoot(
        context: Context,
        rootPropertyGetter: (String) -> String? = ::getRootSystemProperty,
        rootSettingGetter: (String, String) -> String? = ::getRootSetting,
    ): String? {
        val rootCandidates = listOfNotNull(
            rootPropertyGetter("persist.private.device_name"),
            rootPropertyGetter("persist.bluetooth.device_name"),
            rootSettingGetter("secure", "bluetooth_name"),
            rootPropertyGetter("persist.sys.device_name"),
        )
        val result = rootCandidates.firstOrNull { it.isNotBlank() }?.trim()
        if (!result.isNullOrBlank()) {
            memoryCachedDeviceName = result
            runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CACHED_CUSTOM_DEVICE_NAME, result)
                    .apply()
            }
        }
        return result
    }

    /**
     * 获取关于页展示的设备型号：
     * 遵循 HyperCeiler 与用户预期，优先展示市场销售型号（如 Xiaomi 15），回退为 Build.MODEL。
     */
    fun resolveDeviceModel(
        systemPropertyGetter: (String) -> String? = ::getSystemProperty,
        fallbackModel: String = Build.MODEL,
    ): String {
        return systemPropertyGetter("ro.product.marketname")?.takeIf { it.isNotBlank() }?.trim() ?: fallbackModel
    }
}
