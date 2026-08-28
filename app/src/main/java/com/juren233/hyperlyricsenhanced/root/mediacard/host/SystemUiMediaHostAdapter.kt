/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.host

import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Resolves one verified SystemUI profile against one concrete class loader.
 *
 * A profile is never shared across loaders: SystemUI can host multiple isolated
 * loaders during reloads, and a Method/Field resolved from one loader is invalid for
 * objects from another loader. Unknown builds, missing classes and signature drift
 * all produce a disabled capability instead of a guessed fallback.
 */
class SystemUiMediaHostAdapter(
    private val profile: SystemUiMediaProfile,
) {
    private val lock = Any()
    private val bindings = java.util.WeakHashMap<ClassLoader, WeakReference<Binding>>()

    fun capability(classLoader: ClassLoader?): SystemUiMediaCapability {
        if (classLoader == null) return SystemUiMediaCapability.disabled("class_loader_missing")
        if (!profile.binaryVerified) {
            return SystemUiMediaCapability.disabled("profile_not_binary_verified")
        }
        return bindingFor(classLoader).capability
    }

    fun binding(classLoader: ClassLoader?): Binding? {
        if (classLoader == null || !profile.binaryVerified) return null
        val binding = bindingFor(classLoader)
        return binding.takeIf { it.capability.enabled }
    }

    private fun bindingFor(classLoader: ClassLoader): Binding = synchronized(lock) {
        bindings[classLoader]?.get()?.let { return@synchronized it }
        return@synchronized resolve(classLoader).also {
            bindings[classLoader] = WeakReference(it)
        }
    }

    private fun resolve(classLoader: ClassLoader): Binding {
        val loadedClasses = profile.classes.mapNotNull { (target, descriptor) ->
            val binaryName = descriptorToBinaryName(descriptor)
            val clazz = runCatching { classLoader.loadClass(binaryName) }.getOrNull()
            if (clazz != null && clazz.name == binaryName && clazz.classLoader === classLoader) {
                target to clazz
            } else {
                null
            }
        }.toMap()

        val methods = profile.methods.mapNotNull { (key, target) ->
            val owner = runCatching {
                classLoader.loadClass(descriptorToBinaryName(target.ownerDescriptor))
            }.getOrNull() ?: return@mapNotNull null
            if (owner.classLoader !== classLoader) return@mapNotNull null
            val method = owner.declaredMethods.firstOrNull { candidate ->
                candidate.name == target.name &&
                    methodDescriptor(candidate) == target.signature
            }?.accessible()
            method?.let { key to it }
        }.toMap()

        val fields = profile.fields.mapNotNull { (key, target) ->
            val owner = runCatching {
                classLoader.loadClass(descriptorToBinaryName(target.ownerDescriptor))
            }.getOrNull() ?: return@mapNotNull null
            if (owner.classLoader !== classLoader) return@mapNotNull null
            val field = runCatching { owner.getDeclaredField(target.name) }.getOrNull()
            if (field != null && fieldDescriptor(field) == target.typeDescriptor) {
                field.isAccessible = true
                key to field
            } else {
                null
            }
        }.toMap()

        val supported = linkedSetOf<SystemUiMediaCapabilityKind>()
        val reasons = mutableMapOf<SystemUiMediaCapabilityKind, String>()
        requireAll(
            kind = SystemUiMediaCapabilityKind.MEDIA_CONTROLLER_LIFECYCLE,
            keys = listOf(
                "controller.attach",
                "controller.bindMediaData",
                "controller.detach",
                "controller.onFullAodStateChanged",
            ),
            methods = methods,
            reasons = reasons,
            supported = supported,
        )
        requireAll(
            kind = SystemUiMediaCapabilityKind.MEDIA_HEADER_GEOMETRY,
            keys = listOf(
                "header.getIntrinsicHeight",
                "header.getMinHeight",
                "header.setActualHeight",
                "header.setAnimateHeight",
            ),
            methods = methods,
            fields = fields,
            fieldKeys = listOf("header.mediaLockScreenHeight", "header.mAnimateHeight"),
            reasons = reasons,
            supported = supported,
        )
        requireAll(
            kind = SystemUiMediaCapabilityKind.FULL_AOD_CALLBACK,
            keys = listOf(
                "transition.onBegin",
                "transition.onUpdate",
                "transition.onComplete",
                "transition.onCancel",
            ),
            methods = methods,
            fields = fields,
            fieldKeys = listOf(
                "transition.owner",
                "transition.fraction",
                "transition.enableFullAod",
            ),
            reasons = reasons,
            supported = supported,
        )
        requireAll(
            kind = SystemUiMediaCapabilityKind.FULL_AOD_HEIGHT_LEASE,
            keys = emptyList(),
            methods = methods,
            fields = fields,
            fieldKeys = listOf("transition.heightList", "transition.owner"),
            reasons = reasons,
            supported = supported,
        )

        val identity = "${profile.os}:${System.identityHashCode(classLoader)}"
        return Binding(
            profile = profile,
            classLoader = classLoader,
            capability = SystemUiMediaCapability(
                profile = profile,
                classLoaderIdentity = identity,
                supported = supported,
                unavailableReasons = reasons,
            ),
            loadedClasses = loadedClasses,
            methods = methods,
            fields = fields,
        )
    }

    private fun requireAll(
        kind: SystemUiMediaCapabilityKind,
        keys: List<String>,
        methods: Map<String, Method>,
        reasons: MutableMap<SystemUiMediaCapabilityKind, String>,
        supported: MutableSet<SystemUiMediaCapabilityKind>,
        fields: Map<String, Field> = emptyMap(),
        fieldKeys: List<String> = emptyList(),
    ) {
        val missingMethods = keys.filterNot(methods::containsKey)
        val missingFields = fieldKeys.filterNot(fields::containsKey)
        if (missingMethods.isEmpty() && missingFields.isEmpty()) {
            supported += kind
        } else {
            val details = buildList {
                if (missingMethods.isNotEmpty()) add("methods=${missingMethods.joinToString()}")
                if (missingFields.isNotEmpty()) add("fields=${missingFields.joinToString()}")
            }.joinToString(";")
            reasons[kind] = "descriptor_unresolved:$details"
        }
    }

    companion object {
        fun forBuild(buildLabel: String?, classLoader: ClassLoader?): SystemUiMediaHostAdapter? =
            SystemUiMediaProfile.forBuild(buildLabel)?.let(::SystemUiMediaHostAdapter)

        fun descriptorToBinaryName(descriptor: String): String = descriptor
            .removePrefix("L")
            .removeSuffix(";")
            .replace('/', '.')

        fun methodDescriptor(method: Method): String = buildString {
            append('(')
            method.parameterTypes.forEach { append(typeDescriptor(it)) }
            append(')')
            append(typeDescriptor(method.returnType))
        }

        fun fieldDescriptor(field: Field): String = typeDescriptor(field.type)

        private fun typeDescriptor(type: Class<*>): String = when {
            type.isPrimitive -> when (type) {
                java.lang.Void.TYPE -> "V"
                java.lang.Boolean.TYPE -> "Z"
                java.lang.Byte.TYPE -> "B"
                java.lang.Character.TYPE -> "C"
                java.lang.Short.TYPE -> "S"
                java.lang.Integer.TYPE -> "I"
                java.lang.Long.TYPE -> "J"
                java.lang.Float.TYPE -> "F"
                java.lang.Double.TYPE -> "D"
                else -> error("unsupported primitive: $type")
            }
            type.isArray -> type.name.replace('.', '/')
            else -> "L${type.name.replace('.', '/')};"
        }

        private fun Method.accessible(): Method = apply { isAccessible = true }
    }

    class Binding internal constructor(
        val profile: SystemUiMediaProfile,
        val classLoader: ClassLoader,
        val capability: SystemUiMediaCapability,
        val loadedClasses: Map<SystemUiMediaTarget, Class<*>>,
        val methods: Map<String, Method>,
        val fields: Map<String, Field>,
    ) {
        private val activeHeightLeases =
            java.util.WeakHashMap<Any, WeakReference<ReflectiveNativeHeightLease>>()

        fun method(key: String): Method? = methods[key]

        fun field(key: String): Field? = fields[key]

        fun readTransitionFrame(listener: Any): SystemUiMediaTransitionFrame? {
            if (listener.javaClass.classLoader !== classLoader) return null
            val owner = fields["transition.owner"]?.let { runCatching { it.get(listener) }.getOrNull() }
                ?: return null
            if (owner.javaClass.classLoader !== classLoader) return null
            val fraction = fields["transition.fraction"]?.let {
                runCatching { it.getFloat(owner) }.getOrNull()
            }?.takeIf { it.isFinite() && it in 0f..1f } ?: return null
            val target = fields["transition.enableFullAod"]?.let {
                runCatching { it.getBoolean(owner) }.getOrNull()
            } ?: return null
            return SystemUiMediaTransitionFrame(targetFullAod = target, fraction = fraction)
        }

        fun acquireHeightLease(listener: Any): NativeHeightLease? {
            if (!capability.supports(SystemUiMediaCapabilityKind.FULL_AOD_HEIGHT_LEASE)) return null
            if (listener.javaClass.classLoader !== classLoader) return null
            val owner = fields["transition.owner"]?.let { runCatching { it.get(listener) }.getOrNull() }
                ?: return null
            val heightField = fields["transition.heightList"] ?: return null
            if (heightField.declaringClass !== owner.javaClass) return null
            synchronized(activeHeightLeases) {
                val existing = activeHeightLeases[owner]?.get()
                if (existing != null && !existing.isClosed) return null
                val lease = ReflectiveNativeHeightLease(
                    classLoader = classLoader,
                    heightListField = heightField,
                    owner = owner,
                )
                if (lease.originalHeights.isEmpty()) return null
                activeHeightLeases[owner] = WeakReference(lease)
                return lease
            }
        }
    }
}
