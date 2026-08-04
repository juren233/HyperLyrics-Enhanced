/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal object ProviderLogger {
    private const val TAG = "AppleMusicProvider"

    fun debug(message: String) {
        if (BuildConfig.DEBUG) HookLogger.d(TAG, message)
    }

    fun diagnostic(message: String) {
        if (BuildConfig.DEBUG) {
            runCatching { HookLogger.i(TAG, "[debug] $message") }
        }
    }

    fun info(message: String) {
        if (BuildConfig.DEBUG) HookLogger.i(TAG, message)
    }

    fun error(message: String, throwable: Throwable? = null) =
        HookLogger.e(TAG, message, throwable)
}

internal object AppleReflection {
    private val methodsByClass =
        ConcurrentHashMap<Class<*>, Map<String, List<Method>>>()
    private val fieldsByClass =
        ConcurrentHashMap<Class<*>, Map<String, Field>>()

    fun findMethod(
        clazz: Class<*>,
        name: String,
        parameterCount: Int? = null,
        parameterTypes: List<Class<*>>? = null
    ): Method {
        val method = methodsNamed(clazz, name).firstOrNull { candidate ->
            candidate.name == name &&
                (parameterCount == null || candidate.parameterCount == parameterCount) &&
                (parameterTypes == null || candidate.parameterTypes.contentEquals(parameterTypes.toTypedArray()))
        } ?: throw NoSuchMethodException(
            "${clazz.name}#$name/${parameterCount ?: parameterTypes?.size ?: "*"}"
        )
        method.isAccessible = true
        return method
    }

    fun call(instance: Any, name: String, vararg args: Any?): Any? {
        val method = methodsNamed(instance.javaClass, name).firstOrNull { candidate ->
            parametersMatch(candidate.parameterTypes, args)
        } ?: throw NoSuchMethodException("${instance.javaClass.name}#$name/${args.size}")
        method.isAccessible = true
        return method.invoke(instance, *args)
    }

    fun callStatic(clazz: Class<*>, name: String, vararg args: Any?): Any? {
        val method = methodsNamed(clazz, name).firstOrNull { candidate ->
            Modifier.isStatic(candidate.modifiers) &&
                parametersMatch(candidate.parameterTypes, args)
        } ?: throw NoSuchMethodException("${clazz.name}#$name/${args.size}")
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val constructor = clazz.declaredConstructors.firstOrNull { candidate ->
            parametersMatch(candidate.parameterTypes, args)
        } ?: throw NoSuchMethodException("${clazz.name}<init>/${args.size}")
        @Suppress("UNCHECKED_CAST")
        (constructor as Constructor<Any>).isAccessible = true
        return constructor.newInstance(*args)
    }

    fun field(instance: Any, name: String): Any? {
        val field = findField(instance.javaClass, name)
        field.isAccessible = true
        return field.get(instance)
    }

    fun setField(instance: Any, name: String, value: Any?) {
        val field = findField(instance.javaClass, name)
        field.isAccessible = true
        field.set(instance, value)
    }

    fun intField(instance: Any, name: String): Int {
        val field = findField(instance.javaClass, name)
        field.isAccessible = true
        return field.getInt(instance)
    }

    private fun findField(clazz: Class<*>, name: String): Field {
        return fieldsByClass.computeIfAbsent(clazz, ::indexFields)[name]
            ?: throw NoSuchFieldException("${clazz.name}#$name")
    }

    private fun methodsNamed(clazz: Class<*>, name: String): List<Method> =
        methodsByClass.computeIfAbsent(clazz, ::indexMethods)[name].orEmpty()

    private fun indexMethods(clazz: Class<*>): Map<String, List<Method>> {
        val methods = LinkedHashMap<String, MutableList<Method>>()
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.forEach { method ->
                methods.getOrPut(method.name) { ArrayList() }.add(method)
            }
            current = current.superclass
        }
        return methods
    }

    private fun indexFields(clazz: Class<*>): Map<String, Field> {
        val fields = LinkedHashMap<String, Field>()
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredFields.forEach { field -> fields.putIfAbsent(field.name, field) }
            current = current.superclass
        }
        return fields
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val argument = args[index] ?: return@all !types[index].isPrimitive
            boxed(types[index]).isAssignableFrom(argument.javaClass)
        }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        java.lang.Byte.TYPE -> Byte::class.javaObjectType
        java.lang.Character.TYPE -> Char::class.javaObjectType
        java.lang.Short.TYPE -> Short::class.javaObjectType
        java.lang.Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        java.lang.Float.TYPE -> Float::class.javaObjectType
        java.lang.Double.TYPE -> Double::class.javaObjectType
        else -> type
    }
}
