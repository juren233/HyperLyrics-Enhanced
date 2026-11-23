/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

import android.content.BroadcastReceiver
import android.content.Context
import android.os.Looper
import android.util.Log
import com.juren233.hyperlyricsenhanced.common.coroutines.ModuleDispatchers
import com.juren233.hyperlyricsenhanced.common.coroutines.ModuleThreads
import com.juren233.hyperlyricsenhanced.common.net.ModuleHttpClients
import com.juren233.hyperlyricsenhanced.online.cache.CachedSearchSource
import com.juren233.hyperlyricsenhanced.root.aitrans.AITranslator
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Modifier

/**
 * Process-wide objects that each module generation creates with its own copy of a library and
 * that no per-feature cleanup owns. Any one of them left behind keeps the retiring generation's
 * ClassLoader (and every static it holds) reachable for the rest of the SystemUI process.
 */
internal object ModuleGenerationRelease {
    private const val LOG_TAG = "HyperLyricsEnhanced"

    /** Lyricon library singletons, `internal` in Kotlin, hence reached by name (kept by R8 rules). */
    internal val LYRICON_RECEIVER_HOLDERS = listOf(
        "io.github.proify.lyricon.subscriber.CentralServiceReceiver",
        "io.github.proify.lyricon.provider.CentralServiceReceiver",
    )

    /**
     * Lyricon's subscriber and provider libraries each register one receiver on the application
     * context the first time they are used and offer no way to unregister it.
     */
    fun unregisterLyriconReceivers(context: Context) {
        val appContext = context.applicationContext ?: context
        LYRICON_RECEIVER_HOLDERS.forEach { name ->
            val owner = Class.forName(name, false, ModuleGenerationRelease::class.java.classLoader)
            if (isInitialized(owner)) appContext.unregisterReceiver(staticReceiverOf(owner))
        }
    }

    internal fun isInitialized(owner: Class<*>): Boolean {
        val instance = owner.getField("INSTANCE").get(null)
        return owner.getMethod("isInitialized").invoke(instance) as Boolean
    }

    /**
     * Must run on the main thread after this generation's teardown.
     *
     * Undispatched work on the main thread (`Dispatchers.Main.immediate`, `Unconfined`) creates a
     * thread-local event loop through this generation's kotlinx-coroutines and never removes it:
     * only a loop that was also used by `runBlocking` resets the thread-local when its use count
     * returns to zero. An empty `runBlocking` performs exactly that reset.
     */
    fun releaseMainThreadEventLoop() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Event loop release requires main thread" }
        runBlocking { }
    }

    /**
     * Releases this generation's HTTP resources without stopping its work. The module never uses
     * the global `Dispatchers.Default`/`IO` (whose core workers never exit and whose shutdown made
     * in-flight dispatches crash SystemUI, 250042); [ModuleDispatchers] threads leave by themselves
     * once idle. A synchronous call may still be running, so idle connections are evicted now and
     * again after each later task of those pools. Off the main thread: closing a TLS socket writes.
     */
    fun releaseHttpClients() {
        ModuleDispatchers.afterEveryTask { ModuleHttpClients.evictIdleConnections() }
        ModuleThreads.newThread("HyperLyrics Enhanced-RetireHttp") {
            try {
                ModuleHttpClients.releaseForReload()
                Log.i(LOG_TAG, "热重载：旧一代 HTTP 连接与日志处理器已释放")
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "热重载：旧一代 HTTP 资源释放失败", error)
            }
        }.start()
    }

    /**
     * Retired generation: each of its SQLite databases (the online lyric cache, the AI translation
     * cache) closes as soon as no operation on it is running, at once if none is
     * ([com.juren233.hyperlyricsenhanced.common.db.DatabaseRetirement]). Without this every
     * generation still alive keeps a connection pool on the shared file until SystemUI restarts.
     */
    fun releaseDatabases() {
        CachedSearchSource.retireForReload()
        AITranslator.retireDatabase()
    }

    /**
     * `PropertyValuesHolder` caches, per target class and forever, the setter/getter it resolved
     * for a property named by string. This generation's views are animated that way by
     * `LayoutTransition` (its own animators, and the framework's static defaults it always uses
     * for parent views) and by YoYo, so those maps keep this generation's classes, and its loader,
     * reachable for the rest of the process. The framework reads and writes each map only while
     * holding the map itself, which is what the removal synchronizes on.
     *
     * Must run on the main thread after this generation's views have left the view tree. A map a
     * ROM lacks or renames is logged and skipped: it cannot block the reload.
     */
    fun releaseAnimatorPropertyCaches() {
        ANIMATOR_PROPERTY_MAPS.forEach { (owner, field) ->
            try {
                val map = Class.forName(owner).getDeclaredField(field)
                    .apply { isAccessible = true }
                    .get(null) as MutableMap<*, *>
                val removed = removeClassesOf(map, ModuleGenerationRelease::class.java.classLoader)
                if (removed > 0) Log.i(LOG_TAG, "热重载：已从 ${owner.substringAfterLast('.')}.$field 移除 $removed 个旧一代类")
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "热重载：无法清理 ${owner.substringAfterLast('.')}.$field，已跳过", error)
            }
        }
    }

    internal val ANIMATOR_PROPERTY_MAPS = listOf(
        "android.animation.PropertyValuesHolder" to "sSetterPropertyMap",
        "android.animation.PropertyValuesHolder" to "sGetterPropertyMap",
        "android.animation.PropertyValuesHolder\$IntPropertyValuesHolder" to "sJNISetterPropertyMap",
        "android.animation.PropertyValuesHolder\$FloatPropertyValuesHolder" to "sJNISetterPropertyMap",
        "android.animation.PropertyValuesHolder\$MultiFloatValuesHolder" to "sJNISetterPropertyMap",
        "android.animation.PropertyValuesHolder\$MultiIntValuesHolder" to "sJNISetterPropertyMap",
    )

    /** Removes every key that is a class defined by [loader]; returns how many were removed. */
    internal fun removeClassesOf(map: MutableMap<*, *>, loader: ClassLoader?): Int = synchronized(map) {
        val keys = map.keys.iterator()
        var removed = 0
        while (keys.hasNext()) {
            if ((keys.next() as? Class<*>)?.classLoader === loader) {
                keys.remove()
                removed++
            }
        }
        removed
    }

    internal fun staticReceiverOf(owner: Class<*>): BroadcastReceiver {
        val fields = owner.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && BroadcastReceiver::class.java.isAssignableFrom(it.type)
        }
        val field = fields.singleOrNull()
            ?: error("${owner.name} has ${fields.size} static BroadcastReceiver fields")
        field.isAccessible = true
        return field.get(null) as BroadcastReceiver
    }
}
