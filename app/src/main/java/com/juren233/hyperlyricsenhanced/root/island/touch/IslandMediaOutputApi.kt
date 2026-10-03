/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class MediaOutputUnavailable(val reason: String) : IllegalStateException(reason)

internal class NativeMediaOutputEntry(val listener: View.OnClickListener, val plugin: Any)

/** Resolves the host-owned service afresh, including after a module hot reload. */
internal class IslandMediaOutputApi(private val load: (String) -> Class<*>) {
    constructor(loader: ClassLoader) : this({ loader.loadClass(it) })
    private val p = IslandMediaOutputProfile
    private val initializer = field(p.FACTORY, p.INITIALIZER_FIELD, p.INITIALIZER_TYPE, true)
    private val component = method(p.INITIALIZER_BASE, p.COMPONENT_GET, p.COMPONENT_TYPE)
    private val provider = field(p.COMPONENT_IMPL, p.MANAGER_PROVIDER, p.PROVIDER_TYPE)
    private val providerGet = method(p.PROVIDER_INTERFACE, p.PROVIDER_GET, "java.lang.Object")
    private val managerType = load(p.MANAGER)
    private val checkSupport = method(p.MANAGER, p.CHECK_SUPPORT, "void")
    private val support = field(p.MANAGER, p.SUPPORT, "boolean")
    private val localManager = field(p.MANAGER, p.LOCAL_MANAGER, p.LOCAL_MANAGER_TYPE)
    private val userTracker = field(p.MANAGER, p.USER_TRACKER, p.USER_TRACKER_TYPE)
    private val userId = method(p.USER_TRACKER_IMPL, p.GET_USER_ID, "int")
    private val listener = field(p.MANAGER, p.LISTENER, p.LISTENER_TYPE)
    private val pluginManager = field(p.MANAGER, p.PLUGIN_MANAGER_FIELD, p.PLUGIN_MANAGER)
    private val plugin = field(p.PLUGIN_MANAGER, p.PLUGIN_FIELD, p.PLUGIN_INTERFACE)

    init {
        check(View.OnClickListener::class.java.isAssignableFrom(listener.type))
    }

    fun prepare(): NativeMediaOutputEntry {
        val hostInitializer = initializer.get(null) ?: unavailable("initializer_not_ready")
        val hostComponent = component.invoke(hostInitializer) ?: unavailable("component_not_ready")
        val hostProvider = provider.get(hostComponent) ?: unavailable("provider_not_ready")
        val manager = providerGet.invoke(hostProvider) ?: unavailable("manager_not_ready")
        if (!managerType.isInstance(manager)) unavailable("manager_type_mismatch")
        // Same gates as applyMediaTransferView's MiPlay branch. Do not force support,
        // bypass the owner-user restriction, or bypass the listener's CTA handling.
        if (localManager.get(manager) == null) unavailable("local_media_manager_missing")
        checkSupport.invoke(manager)
        if (!support.getBoolean(manager)) unavailable("miplay_not_ready_or_unsupported")
        val tracker = userTracker.get(manager) ?: unavailable("user_tracker_not_ready")
        if (userId.invoke(tracker) != 0) unavailable("user_not_supported")
        val callback = listener.get(manager) as? View.OnClickListener ?: unavailable("native_listener_missing")
        val currentPluginManager = pluginManager.get(manager) ?: unavailable("plugin_manager_not_ready")
        val currentPlugin = plugin.get(currentPluginManager) ?: unavailable("plugin_not_ready")
        return NativeMediaOutputEntry(callback, currentPlugin)
    }

    internal fun method(owner: String, name: String, result: String, vararg params: String): Method =
        load(owner).getDeclaredMethod(name, *params.map(::type).toTypedArray()).apply {
            check(returnType == type(result) && !Modifier.isStatic(modifiers))
            isAccessible = true
        }

    private fun field(owner: String, name: String, expected: String, static: Boolean = false): Field =
        load(owner).getDeclaredField(name).apply {
            check(type == this@IslandMediaOutputApi.type(expected) && Modifier.isStatic(modifiers) == static)
            isAccessible = true
        }

    private fun type(name: String): Class<*> = when (name) {
        "void" -> Void.TYPE
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        else -> load(name)
    }

    private fun unavailable(reason: String): Nothing = throw MediaOutputUnavailable(reason)
}
