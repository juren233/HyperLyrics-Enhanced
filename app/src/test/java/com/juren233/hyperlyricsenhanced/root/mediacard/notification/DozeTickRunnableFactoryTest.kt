/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class DozeTickRunnableFactoryTest {
    class Host

    class LegacyRunnable(host: Host) : Runnable {
        private val captured: Host = host

        override fun run() {
            lastCaptured = captured
        }

        companion object {
            var lastCaptured: Host? = null
        }
    }

    // 新 D8 脱糖形态（报告者移植包 dexdump 证据）：默认构造器 + 公有 f$0 捕获字段。
    class NewDesugarRunnable : Runnable {
        @JvmField
        var `f$0`: Host? = null

        override fun run() {
            lastObserved = `f$0`
        }

        companion object {
            var lastObserved: Host? = null
        }
    }

    class PlainRunnable : Runnable {
        override fun run() {}
    }

    @Test
    fun `locks the binary-verified double-dollar desugar class name`() {
        assertEquals(
            "com.android.systemui.doze.DozeUi\$\$ExternalSyntheticLambda0",
            NotificationMediaAodLyricHooker.DOZE_TICK_RUNNABLE_CLASS,
        )
        // jadx 风格的单 $ 别名是反编译显示名，不得混入运行时标识。
        assertFalse(
            NotificationMediaAodLyricHooker.DOZE_TICK_RUNNABLE_CLASS
                .contains("doze.DozeUi\$External"),
        )
    }

    @Test
    fun `resolves the legacy constructor pattern`() {
        val host = Host()

        val factory = DozeTickRunnableFactory.resolve(LegacyRunnable::class.java, Host::class.java)
        val runnable = factory.create(host)

        assertTrue(runnable is LegacyRunnable)
        runnable.run()
        assertSame(host, LegacyRunnable.lastCaptured)
    }

    @Test
    fun `resolves the new desugar pattern via the captured field`() {
        val host = Host()

        val factory = DozeTickRunnableFactory.resolve(
            NewDesugarRunnable::class.java,
            Host::class.java,
        )
        val runnable = factory.create(host)

        assertTrue(runnable is NewDesugarRunnable)
        assertSame(host, (runnable as NewDesugarRunnable).`f$0`)
        runnable.run()
        assertSame(host, NewDesugarRunnable.lastObserved)
    }

    @Test
    fun `resolves the official zero-constructor desugar pattern via Unsafe allocation`() {
        val host = Host()
        val tickClass = zeroConstructorClass(withCapturedField = true)
        assertTrue(tickClass.declaredConstructors.isEmpty())

        val factory = DozeTickRunnableFactory.resolve(tickClass, Host::class.java)
        val runnable = factory.create(host)

        val captured = tickClass.getDeclaredField("f\$0").apply { isAccessible = true }
        assertSame(host, captured.get(runnable))
    }

    @Test
    fun `reports a clear error for a constructor-less class without the captured field`() {
        val tickClass = zeroConstructorClass(withCapturedField = false)

        val exception = assertThrows(IllegalStateException::class.java) {
            DozeTickRunnableFactory.resolve(tickClass, Host::class.java)
        }
        assertTrue(exception.message!!.contains("f\$0"))
    }

    @Test
    fun `reports a clear error when neither pattern is available`() {
        val exception = assertThrows(IllegalStateException::class.java) {
            DozeTickRunnableFactory.resolve(PlainRunnable::class.java, Host::class.java)
        }
        assertTrue(exception.message!!.contains("f\$0"))
    }

    /**
     * 官方 4.0.20.0 零构造器形态（issue #70 dexdump：PUBLIC FINAL SYNTHETIC、公有
     * f$0、run()V、Direct methods 为空）无法用 Kotlin 声明，手写最小类文件字节码
     * 定义：实现 Runnable、不含任何方法（即无构造器），可选声明公有 f$0 字段。
     */
    private fun zeroConstructorClass(withCapturedField: Boolean): Class<*> {
        val hostDescriptor = "L" + Host::class.java.name.replace('.', '/') + ";"
        val internalName = "test/ZeroCtor" + if (withCapturedField) "Captured" else "Bare"
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { s ->
            s.writeInt(0xCAFEBABE.toInt())
            s.writeShort(0)
            s.writeShort(52) // major 52 = Java 8
            s.writeShort(9) // constant_pool_count
            s.writeByte(1); s.writeUTF(internalName) // #1
            s.writeByte(7); s.writeShort(1) // #2 Class
            s.writeByte(1); s.writeUTF("java/lang/Object") // #3
            s.writeByte(7); s.writeShort(3) // #4 Class
            s.writeByte(1); s.writeUTF("java/lang/Runnable") // #5
            s.writeByte(7); s.writeShort(5) // #6 Class
            s.writeByte(1); s.writeUTF("f\$0") // #7
            s.writeByte(1); s.writeUTF(hostDescriptor) // #8
            s.writeShort(0x0021) // ACC_PUBLIC | ACC_SUPER
            s.writeShort(2) // this_class
            s.writeShort(4) // super_class
            s.writeShort(1) // interfaces_count
            s.writeShort(6) // java/lang/Runnable
            s.writeShort(if (withCapturedField) 1 else 0) // fields_count
            if (withCapturedField) {
                s.writeShort(0x0001) // ACC_PUBLIC
                s.writeShort(7) // name: f$0
                s.writeShort(8) // descriptor: LHost;
                s.writeShort(0) // attributes_count
            }
            s.writeShort(0) // methods_count：零方法 = 零构造器
            s.writeShort(0) // attributes_count
        }
        return SingleClassClassLoader().declare(internalName, out.toByteArray())
    }

    private class SingleClassClassLoader :
        ClassLoader(DozeTickRunnableFactoryTest::class.java.classLoader) {
        fun declare(internalName: String, bytes: ByteArray): Class<*> =
            defineClass(internalName.replace('/', '.'), bytes, 0, bytes.size)
    }
}
