/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * SystemUI dozeTimeTick 脱糖 Runnable 的实例化策略。
 *
 * 三种已在原始 DEX 中核实的形态（以 dexdump 为准，不以反编译器显示为准）：
 * - 旧脱糖：合成 Runnable 声明 `(DozeServiceHost)` 构造器，捕获在构造时完成；
 * - 新脱糖（issue #16 报告者 HyperOS 4.0.0.25 移植包 classes2.dex 的
 *   `Lcom/android/systemui/doze/DozeUi$$ExternalSyntheticLambda0;`：PUBLIC FINAL
 *   SYNTHETIC、公有字段 `f$0 : DozeServiceHost`、`run()V`，构造器为默认无参，
 *   捕获方在调用点为 `f$0` 赋值）：本模块同样先写 `f$0` 再把 runnable 交给
 *   SystemUI 执行，跨线程可见性由后续 Handler 投递的同步边界保证；
 * - 官方零构造器形态（官方 madrid OS4.0.20.0 MiuiSystemUI 同类 dexdump，issue #70：
 *   PUBLIC FINAL SYNTHETIC、公有捕获字段 `f$0`、`run()V`，Direct methods 为空，
 *   捕获字段由调用方字节码直接写入）：无任何构造器可走，经
 *   `sun.misc.Unsafe.allocateInstance` 免构造实例化后反射写 `f$0`。
 *
 * 旧模式优先：命中即保持既有行为不变；三种模式都不满足时按原样抛出，
 * 由上层记录"初始化 AOD 原生刷新接口失败"。
 */
internal interface DozeTickRunnableFactory {
    fun create(host: Any): Runnable

    companion object {
        const val CAPTURED_RECEIVER_FIELD = "f\$0"

        fun resolve(tickRunnableClass: Class<*>, hostClass: Class<*>): DozeTickRunnableFactory {
            val legacy = runCatching {
                tickRunnableClass.getDeclaredConstructor(hostClass)
            }.getOrNull()
            if (legacy != null) {
                legacy.isAccessible = true
                return LegacyConstructor(legacy)
            }
            val captured = runCatching {
                tickRunnableClass.getDeclaredField(CAPTURED_RECEIVER_FIELD)
            }.getOrElse {
                throw IllegalStateException(
                    "脱糖 Runnable ${tickRunnableClass.name} 既无 " +
                        "(${hostClass.name}) 构造器也无 $CAPTURED_RECEIVER_FIELD 捕获字段",
                    it,
                )
            }
            require(captured.type.isAssignableFrom(hostClass)) {
                "脱糖 Runnable 的 $CAPTURED_RECEIVER_FIELD 字段类型 " +
                    "${captured.type.name} 与 ${hostClass.name} 不兼容"
            }
            captured.isAccessible = true
            val noArg = runCatching {
                tickRunnableClass.getDeclaredConstructor()
            }.getOrNull()
            if (noArg != null) {
                noArg.isAccessible = true
                return CapturedField(noArg, captured)
            }
            return UnsafeCapturedField.create(tickRunnableClass, captured)
        }
    }

    private class LegacyConstructor(
        private val constructor: Constructor<*>,
    ) : DozeTickRunnableFactory {
        override fun create(host: Any): Runnable = constructor.newInstance(host) as Runnable
    }

    private class CapturedField(
        private val constructor: Constructor<*>,
        private val field: Field,
    ) : DozeTickRunnableFactory {
        override fun create(host: Any): Runnable =
            (constructor.newInstance() as Runnable).also { field.set(it, host) }
    }

    /**
     * 官方零构造器形态。`sun.misc.Unsafe` 不在公开 android.jar 中，
     * 全程反射取 `theUnsafe`/`allocateInstance`，不引入编译期依赖；
     * 写捕获字段沿用与 [CapturedField] 相同的反射路径。
     */
    private class UnsafeCapturedField private constructor(
        private val tickRunnableClass: Class<*>,
        private val unsafe: Any,
        private val allocateInstance: Method,
        private val captured: Field,
    ) : DozeTickRunnableFactory {
        override fun create(host: Any): Runnable {
            val instance = allocateInstance.invoke(unsafe, tickRunnableClass)
            captured.set(instance, host)
            return instance as Runnable
        }

        companion object {
            fun create(tickRunnableClass: Class<*>, captured: Field): DozeTickRunnableFactory {
                val (unsafe, allocateInstance) = runCatching {
                    val unsafeClass = Class.forName("sun.misc.Unsafe")
                    val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
                        .apply { isAccessible = true }
                    val instance = theUnsafe.get(null)
                    val allocate = unsafeClass
                        .getDeclaredMethod("allocateInstance", Class::class.java)
                        .apply { isAccessible = true }
                    instance to allocate
                }.getOrElse {
                    throw IllegalStateException(
                        "脱糖 Runnable ${tickRunnableClass.name} 无构造器且 " +
                            "sun.misc.Unsafe.allocateInstance 不可用",
                        it,
                    )
                }
                return UnsafeCapturedField(tickRunnableClass, unsafe, allocateInstance, captured)
            }
        }
    }
}
