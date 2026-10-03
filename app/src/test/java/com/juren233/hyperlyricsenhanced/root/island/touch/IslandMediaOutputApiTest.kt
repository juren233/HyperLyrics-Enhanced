/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.view.View
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises the actual reflective service lookup without needing Android Views or notification cards. */
class IslandMediaOutputApiTest {
    private lateinit var component: ComponentImpl
    private lateinit var manager: Manager

    @Before fun resetHost() {
        manager = Manager()
        component = ComponentImpl(Provider { manager })
        Factory.systemUIInitializer = Initializer(component)
    }

    @Test fun `gets the host singleton listener without any notification card or bound holder`() {
        val api = api()
        assertSame(manager.mOnClickHandler, api.prepare().listener)
        assertEquals(1, manager.supportChecks)
    }

    @Test fun `resolves live initializer and provider after replacement instead of retaining old service`() {
        val api = api()
        val old = api.prepare().listener
        val replacement = Manager()
        Factory.systemUIInitializer = Initializer(ComponentImpl(Provider { replacement }))
        assertSame(replacement.mOnClickHandler, api.prepare().listener)
        assertNotSame(old, api.prepare().listener)
    }

    @Test fun `initialization may finish after reflection resolution`() {
        Factory.systemUIInitializer = null
        val api = api()
        rejected("initializer_not_ready") { api.prepare().listener }
        Factory.systemUIInitializer = Initializer(component)
        assertSame(manager.mOnClickHandler, api.prepare().listener)
    }

    @Test fun `native support is refreshed for every request`() {
        val api = api()
        manager.supported = false
        rejected("miplay_not_ready_or_unsupported") { api.prepare().listener }
        manager.supported = true
        assertSame(manager.mOnClickHandler, api.prepare().listener)
        assertEquals(2, manager.supportChecks)
    }

    @Test fun `preserves native owner user and local media manager gates`() {
        val api = api()
        manager.mUserTracker = TrackerImpl(10)
        rejected("user_not_supported") { api.prepare().listener }
        manager.mUserTracker = TrackerImpl(0)
        manager.mLocalMediaManager = null
        rejected("local_media_manager_missing") { api.prepare().listener }
    }

    @Test fun `rejects unavailable component provider wrong service and missing callback separately`() {
        val api = api()
        Factory.systemUIInitializer = Initializer(null)
        rejected("component_not_ready") { api.prepare().listener }
        Factory.systemUIInitializer = Initializer(component)
        component.provideMiuiMediaTransferManagerProvider = null
        rejected("provider_not_ready") { api.prepare().listener }
        component.provideMiuiMediaTransferManagerProvider = Provider { Any() }
        rejected("manager_type_mismatch") { api.prepare().listener }
        component.provideMiuiMediaTransferManagerProvider = Provider { manager }
        manager.mOnClickHandler = null
        rejected("native_listener_missing") { api.prepare().listener }
    }

    @Test fun `binary names preserve nested classes and the original provider spelling`() {
        val p = IslandMediaOutputProfile
        assertEquals("com.android.systemui.SystemUIAppComponentFactoryBase", p.FACTORY)
        assertEquals("systemUIInitializer", p.INITIALIZER_FIELD)
        assertEquals("getSysUIComponent", p.COMPONENT_GET)
        assertEquals("com.android.systemui.dagger.DaggerReferenceGlobalRootComponent\$ReferenceSysUIComponentImpl", p.COMPONENT_IMPL)
        assertEquals("provideMiuiMediaTransferManagerProvider", p.MANAGER_PROVIDER)
        assertEquals("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaTransferManagerImpl\$2", p.LISTENER_TYPE)
        assertEquals("mOnClickHandler", p.LISTENER)
        assertEquals("com.android.systemui.controlcenter.phone.controls.MiPlayPluginManager", p.PLUGIN_MANAGER)
        assertEquals("mMiPlayPluginManager", p.PLUGIN_MANAGER_FIELD)
        assertEquals("mMiPlayPlugin", p.PLUGIN_FIELD)
        assertEquals("com.android.systemui.plugins.miui.controls.MiPlayPlugin", p.PLUGIN_INTERFACE)
        assertEquals("isInterconnectionCTAAgree", p.CTA_CHECK)
        assertEquals("settings_key_interconnection_privacy_state", p.CTA_STATE_KEY)
        assertEquals("dynamic_island", p.ISLAND_SOURCE)
        assertFalse(listOf(p.FACTORY, p.COMPONENT_IMPL, p.LISTENER_TYPE).any { it.startsWith("defpackage.") })
    }

    @Test fun `uses the current plugin instance and rejects missing plugin dependencies`() {
        val api = api()
        val first = api.prepare().plugin
        assertSame(manager.mMiPlayPluginManager!!.mMiPlayPlugin, first)
        manager.mMiPlayPluginManager!!.mMiPlayPlugin = Plugin()
        assertNotSame(first, api.prepare().plugin)
        assertSame(manager.mMiPlayPluginManager!!.mMiPlayPlugin, api.prepare().plugin)
        manager.mMiPlayPluginManager!!.mMiPlayPlugin = null
        rejected("plugin_not_ready") { api.prepare() }
        manager.mMiPlayPluginManager = null
        rejected("plugin_manager_not_ready") { api.prepare() }
    }

    private fun api(): IslandMediaOutputApi {
        val p = IslandMediaOutputProfile
        val classes = mapOf(
            p.FACTORY to Factory::class.java, p.INITIALIZER_TYPE to Initializer::class.java,
            p.INITIALIZER_BASE to InitializerBase::class.java, p.COMPONENT_TYPE to Component::class.java,
            p.COMPONENT_IMPL to ComponentImpl::class.java, p.PROVIDER_TYPE to Provider::class.java,
            p.PROVIDER_INTERFACE to Provider::class.java, p.MANAGER to Manager::class.java,
            p.LOCAL_MANAGER_TYPE to LocalManager::class.java, p.USER_TRACKER_TYPE to Tracker::class.java,
            p.USER_TRACKER_IMPL to TrackerImpl::class.java, p.LISTENER_TYPE to Listener::class.java,
            p.PLUGIN_MANAGER to PluginManager::class.java, p.PLUGIN_INTERFACE to Plugin::class.java,
        )
        return IslandMediaOutputApi { classes[it] ?: Class.forName(it) }
    }

    private fun rejected(reason: String, call: () -> Unit) {
        try { call(); fail("Expected $reason") }
        catch (error: MediaOutputUnavailable) { assertEquals(reason, error.reason) }
    }

    class Factory { companion object { @JvmField var systemUIInitializer: Initializer? = null } }
    open class InitializerBase(private val component: Component?) {
        fun getSysUIComponent(): Component? = component
    }
    class Initializer(component: Component?) : InitializerBase(component)
    interface Component
    class ComponentImpl(@JvmField var provideMiuiMediaTransferManagerProvider: Provider?) : Component
    fun interface Provider { fun get(): Any? }
    class Plugin
    class PluginManager { @JvmField var mMiPlayPlugin: Plugin? = Plugin() }
    class LocalManager
    interface Tracker
    class TrackerImpl(private val id: Int) : Tracker { fun getUserId(): Int = id }
    class Listener : View.OnClickListener { override fun onClick(view: View?) = Unit }
    class Manager {
        @JvmField var mMiPlayPluginManager: PluginManager? = PluginManager()
        @JvmField var mSupportMiPlayAudio = true
        @JvmField var mLocalMediaManager: LocalManager? = LocalManager()
        @JvmField var mUserTracker: Tracker? = TrackerImpl(0)
        @JvmField var mOnClickHandler: Listener? = Listener()
        var supportChecks = 0
        var supported = true
        fun checkForTransferComponent() { supportChecks++; mSupportMiPlayAudio = supported }
    }
}
