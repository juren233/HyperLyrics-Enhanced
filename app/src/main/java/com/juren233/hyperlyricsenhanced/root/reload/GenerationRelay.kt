/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

import android.os.Binder
import android.os.IBinder
import dalvik.system.InMemoryDexClassLoader
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.Base64
import java.util.function.Consumer

/**
 * Loads the process-lifetime relay Binder (`src/relay/java`, embedded by the build as
 * [GenerationRelayDex]). Its ClassLoader's parent is the boot class loader, so a remote process
 * holding the relay keeps only that tiny loader alive, never a module generation's loader.
 *
 * Relays are the cross-generation objects in the hot reload snapshot that are neither JDK types nor
 * host objects: made of framework types only, each one is reused by every generation. Users: the
 * Apple direct bridge, and the Lyricon central's service and player Binders held by providers.
 */
internal object GenerationRelay {
    /** One relay loader per module generation; each relay instance only references that loader. */
    private val relayClass: Class<*> by lazy {
        val dex = Base64.getDecoder().decode(GenerationRelayDex.DEX_BASE64)
        InMemoryDexClassLoader(ByteBuffer.wrap(dex), Binder::class.java.classLoader)
            .loadClass(GenerationRelayDex.CLASS_NAME)
    }

    /**
     * [heldCodes]: transaction codes whose latest call is kept while the relay is held during a hot
     * reload and replayed into the next generation (see `GenerationRelayBinder`).
     */
    fun create(heldCodes: IntArray = IntArray(0)): IBinder =
        relayClass.getDeclaredConstructor(IntArray::class.java).newInstance(heldCodes) as IBinder

    fun isRelay(value: Any?): Boolean = value is IBinder && value is Consumer<*>

    /**
     * Points the relay at [target] after replaying what it held, or detaches it with `null`, dropping
     * later calls. Returns how many held calls were replayed. A relay an earlier module version
     * created holds nothing; it is driven through the interface every version has.
     */
    @Suppress("UNCHECKED_CAST")
    fun attach(relay: IBinder, target: IBinder?): Int {
        val attach = method(relay, "attach", IBinder::class.java)
            ?: return 0.also { (relay as Consumer<IBinder?>).accept(target) }
        return attach.invoke(relay, target) as Int
    }

    /**
     * Retiring generation during a hot reload: detaches the relay, keeping the latest call of each
     * held code for the next generation. False for a relay that cannot hold; it was only detached.
     */
    fun hold(relay: IBinder): Boolean {
        val hold = method(relay, "hold") ?: return false.also { attach(relay, null) }
        hold.invoke(relay)
        return true
    }

    /** The relay's class may come from an earlier module version, which lacks newer methods. */
    private fun method(relay: IBinder, name: String, vararg parameters: Class<*>): Method? =
        try {
            relay.javaClass.getMethod(name, *parameters)
        } catch (_: NoSuchMethodException) {
            null
        }
}
