/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import com.juren233.hyperlyricsenhanced.common.dexkit.DexResolutionSource
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.io.File
import java.util.Base64

/**
 * Slow-path resolver for Apple Music methods whose verified version profile no longer loads.
 *
 * The normal path remains reflection against [AppleMusicHookProfiles]. DexKit is opened only
 * after every exact/compatibility descriptor fails. A resolved descriptor is cached per APK
 * version and update timestamp, then fully revalidated through reflection before reuse.
 */
internal class AppleMusicDexKitResolver(
    private val application: Application,
    private val classLoader: ClassLoader,
    private val nativeLibraryDir: String,
) {
    /**
     * Record the identifiers that were actually resolved by the exact profile.
     * This is deliberately persisted independently of the APK version.  When a
     * later APK changes an obfuscated name, the previous version becomes the
     * structural seed for the DexKit repair pass.
     */
    fun recordBaseline(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        clazz: Class<*>,
        baselineClassName: String = target.className,
    ) {
        val preferences = preferences()
        preferences.edit()
            .putString(classBaselineKey(hookPoint, baselineClassName), encodeClassShape(ClassShape.from(clazz)))
            .apply()
        target.runtimeMemberNames.forEach { (member, name) ->
            when (member.kind()) {
                MemberKind.CLASS_REFERENCE -> runCatching { classLoader.loadClass(name) }
                    .getOrNull()
                    ?.let { referenced ->
                        preferences.edit()
                            .putString(
                                classReferenceBaselineKey(hookPoint, baselineClassName, member),
                                encodeClassShape(ClassShape.from(referenced)),
                            )
                            .apply()
                    }

                MemberKind.FIELD -> findField(clazz, name)?.let { field ->
                    preferences.edit()
                        .putString(
                            memberBaselineKey(hookPoint, baselineClassName, member),
                            encodeMemberDescriptor(MemberDescriptor.from(field, clazz)),
                        )
                        .apply()
                }

                MemberKind.METHOD -> findMethods(clazz, name).firstOrNull()?.let { method ->
                    preferences.edit()
                        .putString(
                            memberBaselineKey(hookPoint, baselineClassName, member),
                            encodeMemberDescriptor(MemberDescriptor.from(method, clazz)),
                        )
                        .apply()
                }
            }
        }
    }

    fun recordMethodBaseline(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        method: Method,
        baselineClassName: String = target.className,
    ) {
        recordBaseline(hookPoint, target, method.declaringClass, baselineClassName)
        preferences().edit()
            .putString(
                hookMethodBaselineKey(hookPoint, baselineClassName),
                encodeMemberDescriptor(MemberDescriptor.from(method, method.declaringClass)),
            )
            .apply()
    }

    /**
     * Repair identifier-sensitive fields, helper methods, and referenced classes even when the
     * outer Hook class or method still resolves through the exact profile.
     *
     * A future obfuscation pass may rename only one nested member.  Waiting for the outer target
     * to fail would skip that case and leave the business Hook with a stale member name.
     */
    fun repairRuntimeMembers(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        clazz: Class<*>,
        baselineClassName: String = target.className,
    ): AppleMusicHookTarget = repairTarget(
        hookPoint = hookPoint,
        target = target,
        clazz = clazz,
        baselineClassName = baselineClassName,
    )

    /** Resolve class-only hooks after the exact and compatibility names fail. */
    fun resolveClasses(
        hookPoint: AppleMusicHookPoint,
        templates: List<AppleMusicHookTarget>,
    ): List<ResolvedAppleMusicHookClass> {
        val preferences = preferences()
        val matches = templates.mapNotNull { template ->
            val shape = decodeClassShape(
                preferences.getString(classBaselineKey(hookPoint, template.className), null),
            ) ?: return@mapNotNull null
            val cacheKey = classCacheKey(hookPoint, template.className)
            val cachedClass = preferences.getString(cacheKey, null)
                ?.let { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }
                ?.takeIf { shape.matches(it) }
            val source = if (cachedClass != null) {
                DexResolutionSource.CACHE
            } else {
                DexResolutionSource.DEXKIT
            }
            val clazz = cachedClass ?: findClasses(template, shape).singleOrNull()?.also {
                preferences.edit().putString(cacheKey, it.name).apply()
            } ?: run {
                preferences.edit().remove(cacheKey).apply()
                return@mapNotNull null
            }
            val repaired = repairTarget(hookPoint, template, clazz, template.className)
            val repairedTarget = repaired.copy(className = clazz.name)
            recordBaseline(hookPoint, repairedTarget, clazz, template.className)
            AppleMusicDexKitWatchdog.resolvedClass(
                hookPoint = hookPoint,
                templateClassName = template.className,
                runtimeCacheKey = cacheKey,
                source = source,
                cacheWritten = cachedClass == null,
                clazz = clazz,
            )
            ResolvedAppleMusicHookClass(
                target = repairedTarget,
                clazz = clazz,
                compatibilityFallback = true,
            )
        }
        return matches.distinctBy { it.clazz.name }
    }

    fun resolveMethod(
        hookPoint: AppleMusicHookPoint,
        templates: List<AppleMusicHookTarget>,
        validator: (AppleMusicHookTarget, Method) -> Boolean,
    ): ResolvedAppleMusicHookMethod? {
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val cacheKey = "${packageInfo.longVersionCode}:${packageInfo.lastUpdateTime}:$hookPoint"
        decode(preferences.getString(cacheKey, null))?.let { cached ->
            val template = bestTemplate(templates, cached)
            val method = runCatching { cached.toMethod(classLoader) }.getOrNull()
            if (template != null && method != null && validator(template, method)) {
                val repairedTarget = repairTarget(
                    hookPoint = hookPoint,
                    target = template.forResolvedMethod(method),
                    clazz = method.declaringClass,
                    baselineClassName = template.className,
                )
                ProviderLogger.info(
                    "Apple Music DexKit 缓存命中: hook=$hookPoint target=${cached.describe()}",
                )
                recordMethodBaseline(hookPoint, repairedTarget, method, template.className)
                AppleMusicDexKitWatchdog.resolvedMethod(
                    hookPoint = hookPoint,
                    runtimeCacheKey = cacheKey,
                    source = DexResolutionSource.CACHE,
                    cacheWritten = false,
                    method = method,
                )
                return ResolvedAppleMusicHookMethod(
                    target = repairedTarget,
                    method = method,
                    compatibilityFallback = true,
                )
            }
            preferences.edit().remove(cacheKey).apply()
            ProviderLogger.diagnostic("Apple Music DexKit 缓存失效: hook=$hookPoint")
        }

        val startedAt = System.nanoTime()
        val descriptors = findCandidates(hookPoint, templates).ifEmpty {
            findCandidatesViaClassSearch(hookPoint, templates)
        }
        val matches = descriptors.mapNotNull { descriptor ->
            val template = bestTemplate(templates, descriptor) ?: return@mapNotNull null
            val method = runCatching { descriptor.toMethod(classLoader) }.getOrNull()
                ?: return@mapNotNull null
            method.takeIf { validator(template, it) }?.let { template to it }
        }.distinctBy { (_, method) -> method.toGenericString() }

        val disambiguated = disambiguateMatches(matches)
        if (disambiguated == null) {
            ProviderLogger.diagnostic(
                "Apple Music DexKit 查询未得到唯一目标或存在歧义: hook=$hookPoint count=${matches.size}",
            )
            return null
        }
        val (template, method) = disambiguated
        val descriptor = MethodDescriptor.from(method)
        preferences.edit().putString(cacheKey, encode(descriptor)).apply()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        ProviderLogger.info(
            "Apple Music DexKit 查询并缓存成功: hook=$hookPoint elapsedMs=$elapsedMs " +
                "target=${descriptor.describe()}",
        )
        val repairedTarget = repairTarget(
            hookPoint = hookPoint,
            target = template.forResolvedMethod(method),
            clazz = method.declaringClass,
            baselineClassName = template.className,
        )
        recordMethodBaseline(hookPoint, repairedTarget, method, template.className)
        AppleMusicDexKitWatchdog.resolvedMethod(
            hookPoint = hookPoint,
            runtimeCacheKey = cacheKey,
            source = DexResolutionSource.DEXKIT,
            cacheWritten = true,
            method = method,
        )
        return ResolvedAppleMusicHookMethod(
            target = repairedTarget,
            method = method.apply { isAccessible = true },
            compatibilityFallback = true,
            contractReason = "dexkit_resolved",
        )
    }

    private fun disambiguateMatches(
        matches: List<Pair<AppleMusicHookTarget, Method>>,
    ): Pair<AppleMusicHookTarget, Method>? {
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches.single()

        val scored = matches.map { (template, method) ->
            var score = 100
            if (template.methodName != null && method.name == template.methodName) score += 50
            if (method.declaringClass.name == template.className) score += 40
            val templatePackage = template.className.substringBeforeLast('.', "")
            val declaringPackage = method.declaringClass.name.substringBeforeLast('.', "")
            if (templatePackage.isNotBlank() && declaringPackage == templatePackage) score += 20
            if (template.parameterCount != null && method.parameterCount == template.parameterCount) score += 20
            if (template.returnTypeName != null && method.returnType.name == template.returnTypeName) score += 20
            if (!method.isBridge && !method.isSynthetic) score += 10
            Triple(template, method, score)
        }.sortedByDescending { it.third }

        val best = scored.first()
        val secondBest = scored.getOrNull(1)
        return if (secondBest == null || best.third - secondBest.third >= 20) {
            best.first to best.second
        } else {
            null
        }
    }

    private fun preferences() = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun findClasses(
        template: AppleMusicHookTarget,
        shape: ClassShape,
    ): List<Class<*>> {
        ensureDexKitLoaded()
        val bridge = dexKitBridge()
        fun query(packagePrefix: String?): List<Class<*>> {
            val finder = FindClass().apply {
                packagePrefix?.takeIf(String::isNotBlank)?.let { searchPackages(it) }
                matcher {
                    fieldCount(shape.fieldCountRange.first, shape.fieldCountRange.last)
                    methodCount(shape.methodCountRange.first, shape.methodCountRange.last)
                    interfaceCount(shape.interfaceCountRange.first, shape.interfaceCountRange.last)
                    shape.stableFieldTypes.keys.take(3).forEach { addFieldForType(it) }
                }
            }
            return bridge.findClass(finder).mapNotNull { data ->
                runCatching { data.getInstance(classLoader) }.getOrNull()
            }.filter(shape::matches)
        }
        val packagePrefix = template.className.substringBeforeLast('.', "")
        return query(packagePrefix).ifEmpty { query(null) }.distinctBy(Class<*>::getName)
    }

    private fun findCandidatesViaClassSearch(
        hookPoint: AppleMusicHookPoint,
        templates: List<AppleMusicHookTarget>,
    ): List<MethodDescriptor> {
        val preferences = preferences()
        return templates.flatMap { template ->
            val classSeed = decodeClassShape(
                preferences.getString(classBaselineKey(hookPoint, template.className), null),
            ) ?: return@flatMap emptyList()
            val candidateClasses = findClasses(template, classSeed)
            candidateClasses.flatMap { clazz ->
                val methods = allMethods(clazz)
                methods.filter { method ->
                    (template.methodName == null || method.name == template.methodName) &&
                        (template.parameterCount == null || method.parameterCount == template.parameterCount) &&
                        (template.returnTypeName == null || !isStableRuntimeType(template.returnTypeName) ||
                            method.returnType.name == template.returnTypeName) &&
                        (template.isStatic == null || Modifier.isStatic(method.modifiers) == template.isStatic)
                }.map(MethodDescriptor::from)
            }
        }.distinct()
    }

    private fun findCandidates(
        hookPoint: AppleMusicHookPoint,
        templates: List<AppleMusicHookTarget>,
    ): List<MethodDescriptor> {
        ensureDexKitLoaded()
        val bridge = dexKitBridge()
        return templates.map { template ->
            val preferences = preferences()
            val methodSeed = decodeMemberDescriptor(
                preferences.getString(hookMethodBaselineKey(hookPoint, template.className), null),
            )
            val classSeed = decodeClassShape(
                preferences.getString(classBaselineKey(hookPoint, template.className), null),
            )
            val packagePrefix = template.className.substringBeforeLast('.', "")

            fun executeQuery(scopedPackage: String?): List<MethodDescriptor> {
                val finder = FindMethod().apply {
                    scopedPackage?.takeIf(String::isNotBlank)?.let { searchPackages(it) }
                    matcher {
                        template.methodName?.let { name(it) }
                        (methodSeed?.parameterCount ?: template.parameterCount)?.let(::paramCount)
                        (methodSeed?.returnTypeName ?: template.returnTypeName)
                            ?.takeIf(::isStableRuntimeType)
                            ?.let(::returnType)
                        if (methodSeed?.static == true || template.isStatic == true) {
                            modifiers(Modifier.STATIC, org.luckypray.dexkit.query.enums.MatchType.Contains)
                        }
                        template.parameterTypeNames
                            ?.takeIf { types -> types.all { it != null && isStableRuntimeType(it) } }
                            ?.map { it!! }
                            ?.let(::paramTypes)
                        template.requiredInvokedMethodNames.forEach { invokeName ->
                            addInvoke(org.luckypray.dexkit.query.matchers.MethodMatcher().name(invokeName))
                        }
                        template.requiredInvokedMethodDescriptors.forEach { invokeDesc ->
                            addInvoke(org.luckypray.dexkit.query.matchers.MethodMatcher().descriptor(invokeDesc))
                        }
                        template.requiredCallerMethodNames.forEach { callerName ->
                            addCaller(org.luckypray.dexkit.query.matchers.MethodMatcher().name(callerName))
                        }
                        classSeed?.let { seed ->
                            declaredClass {
                                fieldCount(seed.fieldCountRange.first, seed.fieldCountRange.last)
                                methodCount(seed.methodCountRange.first, seed.methodCountRange.last)
                                interfaceCount(seed.interfaceCountRange.first, seed.interfaceCountRange.last)
                            }
                        }
                    }
                }
                return bridge.findMethod(finder).map { method ->
                    MethodDescriptor(
                        className = method.className,
                        methodName = method.methodName,
                        parameterTypeNames = method.paramTypeNames,
                        returnTypeName = method.returnTypeName,
                        isStatic = Modifier.isStatic(method.modifiers),
                    )
                }
            }

            val scopedResults = if (packagePrefix.isNotBlank()) executeQuery(packagePrefix) else emptyList()
            if (scopedResults.isNotEmpty()) scopedResults else executeQuery(null)
        }.flatten().distinct()
    }

    private fun dexKitBridge(): DexKitBridge {
        sharedBridge?.let { return it }
        synchronized(dexKitBridgeLock) {
            sharedBridge?.let { return it }
            return DexKitBridge.create(application.applicationInfo.sourceDir).also { bridge ->
                bridge.setThreadNum(selectThreadCount())
                bridge.setMaxConcurrentQueries(1)
                sharedBridge = bridge
            }
        }
    }

    private fun repairTarget(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        clazz: Class<*>,
        baselineClassName: String,
    ): AppleMusicHookTarget {
        val preferences = preferences()
        val names = target.runtimeMemberNames.mapValues { (member, originalName) ->
            when (member.kind()) {
                MemberKind.CLASS_REFERENCE -> {
                    val shape = decodeClassShape(
                        preferences.getString(
                            classReferenceBaselineKey(hookPoint, baselineClassName, member),
                            null,
                        ),
                    )
                    if (shape == null) originalName else run {
                        val cacheKey = classReferenceCacheKey(
                            hookPoint,
                            baselineClassName,
                            member,
                        )
                        val directClass = runCatching { classLoader.loadClass(originalName) }
                            .getOrNull()
                            ?.takeIf(shape::matches)
                        val cachedClass = preferences.getString(cacheKey, null)
                            ?.let { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }
                            ?.takeIf(shape::matches)
                        val repairedClass = directClass ?: cachedClass ?: findClasses(
                            AppleMusicHookTarget(className = originalName),
                            shape,
                        ).singleOrNull()
                        if (repairedClass == null) {
                            preferences.edit().remove(cacheKey).apply()
                            originalName
                        } else {
                            preferences.edit().putString(cacheKey, repairedClass.name).apply()
                            repairedClass.name
                        }
                    }
                }

                MemberKind.FIELD,
                MemberKind.METHOD -> {
                    val descriptor = decodeMemberDescriptor(
                        preferences.getString(memberBaselineKey(hookPoint, baselineClassName, member), null),
                    )
                    descriptor?.resolveName(clazz, member.kind()) ?: originalName
                }
            }
        }
        return target.copy(runtimeMemberNames = names)
    }

    private fun findField(clazz: Class<*>, name: String): Field? =
        generateSequence(clazz) { it.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .firstOrNull { it.name == name }

    private fun findMethods(clazz: Class<*>, name: String): List<Method> =
        generateSequence(clazz) { it.superclass }
            .flatMap { current -> current.declaredMethods.asSequence() }
            .filter { it.name == name }
            .toList()

    private fun bestTemplate(
        templates: List<AppleMusicHookTarget>,
        descriptor: MethodDescriptor,
    ): AppleMusicHookTarget? = templates
        .filter { template ->
            (template.parameterCount == null ||
                template.parameterCount == descriptor.parameterTypeNames.size) &&
                (template.isStatic == null || template.isStatic == descriptor.isStatic) &&
                (template.returnTypeName == null ||
                    !isStableRuntimeType(template.returnTypeName) ||
                    template.returnTypeName == descriptor.returnTypeName) &&
                template.parameterTypeNames.orEmpty().withIndex().all { (index, typeName) ->
                    typeName == null ||
                        !isStableRuntimeType(typeName) ||
                        descriptor.parameterTypeNames.getOrNull(index) == typeName
                }
        }
        .minByOrNull { template ->
            var score = 0
            if (template.methodName != descriptor.methodName) score += 1
            if (template.className != descriptor.className) score += 1
            score
        }

    private fun AppleMusicHookTarget.forResolvedMethod(method: Method): AppleMusicHookTarget = copy(
        className = method.declaringClass.name,
        methodName = method.name,
        parameterCount = method.parameterCount,
        parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
        returnTypeName = method.returnType.name,
        isStatic = Modifier.isStatic(method.modifiers),
    )

    private enum class MemberKind {
        FIELD,
        METHOD,
        CLASS_REFERENCE,
    }

    private fun AppleMusicRuntimeMember.kind(): MemberKind = when {
        name.endsWith("_CLASS") || name.endsWith("_CLASS_NAME") -> MemberKind.CLASS_REFERENCE
        name.endsWith("_FIELD") -> MemberKind.FIELD
        else -> MemberKind.METHOD
    }

    private data class ClassShape(
        val fieldCount: Int,
        val methodCount: Int,
        val interfaceCount: Int,
        val stableFieldTypes: Map<String, Int>,
        val stableMethodShapes: Map<String, Int>,
    ) {
        val fieldCountRange: IntRange = (fieldCount - COUNT_TOLERANCE).coerceAtLeast(0)..(fieldCount + COUNT_TOLERANCE)
        val methodCountRange: IntRange = (methodCount - COUNT_TOLERANCE).coerceAtLeast(0)..(methodCount + COUNT_TOLERANCE)
        val interfaceCountRange: IntRange = (interfaceCount - 1).coerceAtLeast(0)..(interfaceCount + 1)

        fun matches(clazz: Class<*>): Boolean {
            val fields = clazz.declaredFields.toList()
            val methods = clazz.declaredMethods.filterNot { it.isSynthetic && it.name == "\$values" }
            if (fields.size !in fieldCountRange || methods.size !in methodCountRange) return false
            if (clazz.interfaces.size !in interfaceCountRange) return false
            val fieldCounts = fields.mapNotNull { it.type.name.takeIf(::isStableRuntimeType) }
                .groupingBy { it }.eachCount()
            if (stableFieldTypes.any { (type, count) -> (fieldCounts[type] ?: 0) < count }) return false
            val methodCounts = methods.map { methodShape(it) }
                .groupingBy { it }.eachCount()
            return stableMethodShapes.all { (shape, count) -> (methodCounts[shape] ?: 0) >= count }
        }

        companion object {
            fun from(clazz: Class<*>): ClassShape {
                val fields = clazz.declaredFields.toList()
                val methods = clazz.declaredMethods
                    .filterNot { it.isSynthetic && it.name == "\$values" }
                return ClassShape(
                    fieldCount = fields.size,
                    methodCount = methods.size,
                    interfaceCount = clazz.interfaces.size,
                    stableFieldTypes = fields.mapNotNull { it.type.name.takeIf(::isStableRuntimeType) }
                        .groupingBy { it }.eachCount(),
                    stableMethodShapes = methods.map(::methodShape)
                        .filter { it.substringAfter('|').isNotEmpty() }
                        .groupingBy { it }.eachCount(),
                )
            }
        }
    }

    private data class MemberDescriptor(
        val type: MemberKind,
        val declaringClassName: String,
        val name: String,
        val static: Boolean,
        val fieldTypeName: String?,
        val parameterCount: Int?,
        val parameterTypeNames: List<String?>,
        val returnTypeName: String?,
        val ordinal: Int,
    ) {
        fun resolveName(clazz: Class<*>, kind: MemberKind): String? {
            if (type != kind) return null
            if (kind == MemberKind.FIELD) {
                val fields = allFields(clazz)
                fields.firstOrNull { it.name == name && matches(it) }?.let { return it.name }
                val candidates = fields.filter { matches(it) }
                return candidates.getOrNull(ordinal)?.name ?: candidates.singleOrNull()?.name
            }
            val methods = allMethods(clazz)
            methods.firstOrNull { it.name == name && matches(it) }?.let { return it.name }
            val candidates = methods.filter { matches(it) }
            return candidates.getOrNull(ordinal)?.name ?: candidates.singleOrNull()?.name
        }

        private fun matches(field: Field): Boolean =
            !Modifier.isStatic(field.modifiers).xor(static) &&
                (fieldTypeName == null || field.type.name == fieldTypeName)

        private fun matches(method: Method): Boolean =
            !Modifier.isStatic(method.modifiers).xor(static) &&
                (parameterCount == null || method.parameterCount == parameterCount) &&
                parameterTypeNames.withIndex().all { (index, typeName) ->
                    typeName == null || method.parameterTypes.getOrNull(index)?.name == typeName
                } &&
                (returnTypeName == null || method.returnType.name == returnTypeName)

        companion object {
            fun from(field: Field, owner: Class<*>): MemberDescriptor {
                val fields = allFields(owner)
                return MemberDescriptor(
                    type = MemberKind.FIELD,
                    declaringClassName = field.declaringClass.name,
                    name = field.name,
                    static = Modifier.isStatic(field.modifiers),
                    fieldTypeName = field.type.name.takeIf(::isStableRuntimeType),
                    parameterCount = null,
                    parameterTypeNames = emptyList(),
                    returnTypeName = null,
                    ordinal = fields.filter { candidate ->
                        candidate.type.name.takeIf(::isStableRuntimeType) ==
                            field.type.name.takeIf(::isStableRuntimeType) &&
                            Modifier.isStatic(candidate.modifiers) == Modifier.isStatic(field.modifiers)
                    }.indexOf(field).coerceAtLeast(0),
                )
            }

            fun from(method: Method, owner: Class<*>): MemberDescriptor {
                val methods = allMethods(owner)
                return MemberDescriptor(
                    type = MemberKind.METHOD,
                    declaringClassName = method.declaringClass.name,
                    name = method.name,
                    static = Modifier.isStatic(method.modifiers),
                    fieldTypeName = null,
                    parameterCount = method.parameterCount,
                    parameterTypeNames = method.parameterTypes.map {
                        it.name.takeIf(::isStableRuntimeType)
                    },
                    returnTypeName = method.returnType.name.takeIf(::isStableRuntimeType),
                    ordinal = methods.filter { candidate ->
                        candidate.parameterCount == method.parameterCount &&
                            candidate.returnType.name.takeIf(::isStableRuntimeType) ==
                                method.returnType.name.takeIf(::isStableRuntimeType) &&
                            Modifier.isStatic(candidate.modifiers) == Modifier.isStatic(method.modifiers)
                    }.indexOf(method).coerceAtLeast(0),
                )
            }
        }
    }

    private fun classBaselineKey(hookPoint: AppleMusicHookPoint, className: String): String =
        "baseline-class:${hookPoint.name}:${className.encoded()}"

    private fun classReferenceBaselineKey(
        hookPoint: AppleMusicHookPoint,
        className: String,
        member: AppleMusicRuntimeMember,
    ): String = "baseline-class-ref:${hookPoint.name}:${className.encoded()}:${member.name}"

    private fun memberBaselineKey(
        hookPoint: AppleMusicHookPoint,
        className: String,
        member: AppleMusicRuntimeMember,
    ): String = "baseline-member:${hookPoint.name}:${className.encoded()}:${member.name}"

    private fun classCacheKey(hookPoint: AppleMusicHookPoint, className: String): String =
        "cache-class:${hookPoint.name}:${className.encoded()}"

    private fun classReferenceCacheKey(
        hookPoint: AppleMusicHookPoint,
        className: String,
        member: AppleMusicRuntimeMember,
    ): String = "cache-class-ref:${hookPoint.name}:${className.encoded()}:${member.name}"

    private fun hookMethodBaselineKey(hookPoint: AppleMusicHookPoint, className: String): String =
        "baseline-hook-method:${hookPoint.name}:${className.encoded()}"

    private fun encodeClassShape(value: ClassShape): String = listOf(
        value.fieldCount,
        value.methodCount,
        value.interfaceCount,
        encodeCounts(value.stableFieldTypes),
        encodeCounts(value.stableMethodShapes),
    ).joinToString("|")

    private fun decodeClassShape(value: String?): ClassShape? = runCatching {
        val fields = value?.split('|') ?: return null
        if (fields.size != 5) return null
        ClassShape(
            fieldCount = fields[0].toInt(),
            methodCount = fields[1].toInt(),
            interfaceCount = fields[2].toInt(),
            stableFieldTypes = decodeCounts(fields[3]),
            stableMethodShapes = decodeCounts(fields[4]),
        )
    }.getOrNull()

    private fun encodeMemberDescriptor(value: MemberDescriptor): String = listOf(
        value.type.name,
        value.declaringClassName.encoded(),
        value.name.encoded(),
        if (value.static) "1" else "0",
        value.fieldTypeName.orEmpty().encoded(),
        value.parameterCount?.toString().orEmpty(),
        value.parameterTypeNames.joinToString(",") { it.orEmpty().encoded() },
        value.returnTypeName.orEmpty().encoded(),
        value.ordinal.toString(),
    ).joinToString("|")

    private fun decodeMemberDescriptor(value: String?): MemberDescriptor? = runCatching {
        val fields = value?.split('|') ?: return null
        if (fields.size != 9) return null
        MemberDescriptor(
            type = MemberKind.valueOf(fields[0]),
            declaringClassName = fields[1].decoded(),
            name = fields[2].decoded(),
            static = fields[3] == "1",
            fieldTypeName = fields[4].decoded().takeIf(String::isNotEmpty),
            parameterCount = fields[5].toIntOrNull(),
            parameterTypeNames = if (fields[6].isEmpty()) emptyList() else
                fields[6].split(',').map { it.decoded().takeIf(String::isNotEmpty) },
            returnTypeName = fields[7].decoded().takeIf(String::isNotEmpty),
            ordinal = fields[8].toInt(),
        )
    }.getOrNull()

    private fun encodeCounts(values: Map<String, Int>): String = values.entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key.encoded()}:${it.value}" }

    private fun decodeCounts(value: String): Map<String, Int> = if (value.isEmpty()) {
        emptyMap()
    } else {
        value.split(',').mapNotNull { item ->
            val separator = item.lastIndexOf(':')
            if (separator <= 0) return@mapNotNull null
            item.substring(0, separator).decoded() to item.substring(separator + 1).toIntOrNull()
        }.mapNotNull { (key, count) -> count?.let { key to it } }.toMap()
    }

    private fun selectThreadCount(): Int {
        val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val availableMemory = runCatching {
            val manager = application.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.availMem
        }.getOrDefault(0L)
        return if (processors >= 4 && availableMemory >= TWO_GIB_BYTES) 2 else 1
    }

    private fun ensureDexKitLoaded() {
        if (dexKitLoaded) return
        synchronized(dexKitLoadLock) {
            if (dexKitLoaded) return
            val nativeLibrary = File(nativeLibraryDir, "libdexkit.so")
            require(nativeLibrary.isFile) { "DexKit native library missing: ${nativeLibrary.absolutePath}" }
            System.load(nativeLibrary.absolutePath)
            dexKitLoaded = true
        }
    }

    private data class MethodDescriptor(
        val className: String,
        val methodName: String,
        val parameterTypeNames: List<String>,
        val returnTypeName: String,
        val isStatic: Boolean,
    ) {
        fun toMethod(loader: ClassLoader): Method {
            val clazz = loader.loadClass(className)
            val parameters = parameterTypeNames.map { resolveType(loader, it) }.toTypedArray()
            return clazz.getDeclaredMethod(methodName, *parameters).apply {
                isAccessible = true
                require(returnType.name == returnTypeName)
                require(Modifier.isStatic(modifiers) == isStatic)
            }
        }

        fun describe(): String = "$className#$methodName(${parameterTypeNames.joinToString()}):" +
            "$returnTypeName${if (isStatic) "[static]" else "[instance]"}"

        companion object {
            fun from(method: Method) = MethodDescriptor(
                className = method.declaringClass.name,
                methodName = method.name,
                parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
                returnTypeName = method.returnType.name,
                isStatic = Modifier.isStatic(method.modifiers),
            )
        }
    }

    private companion object {
        const val PREFERENCES = "hle_apple_music_dex_methods_v1"
        const val TWO_GIB_BYTES = 2L * 1024L * 1024L * 1024L
        const val COUNT_TOLERANCE = 4
        val dexKitLoadLock = Any()
        val dexKitBridgeLock = Any()

        @Volatile
        var dexKitLoaded = false

        @Volatile
        var sharedBridge: DexKitBridge? = null

        fun allFields(clazz: Class<*>): List<Field> =
            generateSequence(clazz) { it.superclass }
                .flatMap { current -> current.declaredFields.asSequence() }
                .filterNot { Modifier.isStatic(it.modifiers) && it.name == "\$VALUES" }
                .toList()

        fun allMethods(clazz: Class<*>): List<Method> =
            generateSequence(clazz) { it.superclass }
                .flatMap { current -> current.declaredMethods.asSequence() }
                .filterNot { it.isSynthetic && it.name == "\$values" }
                .toList()

        fun methodShape(method: Method): String {
            val returnType = method.returnType.name.takeIf(::isStableRuntimeType).orEmpty()
            val stableParameters = method.parameterTypes.joinToString(",") {
                it.name.takeIf(::isStableRuntimeType).orEmpty()
            }
            return "${method.parameterCount}|$returnType|" +
                "${if (Modifier.isStatic(method.modifiers)) 1 else 0}|$stableParameters"
        }

        fun isStableRuntimeType(typeName: String): Boolean =
            typeName == "void" ||
                typeName in setOf(
                    "boolean",
                    "byte",
                    "char",
                    "short",
                    "int",
                    "long",
                    "float",
                    "double",
                ) ||
                typeName.startsWith("java.") ||
                typeName.startsWith("android.") ||
                typeName.startsWith("kotlin.") ||
                typeName.startsWith("androidx.")

        fun resolveType(loader: ClassLoader, typeName: String): Class<*> = when (typeName) {
            "void" -> Void.TYPE
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "byte" -> Byte::class.javaPrimitiveType!!
            "char" -> Char::class.javaPrimitiveType!!
            "short" -> Short::class.javaPrimitiveType!!
            "int" -> Int::class.javaPrimitiveType!!
            "long" -> Long::class.javaPrimitiveType!!
            "float" -> Float::class.javaPrimitiveType!!
            "double" -> Double::class.javaPrimitiveType!!
            else -> loader.loadClass(typeName)
        }

        fun encode(value: MethodDescriptor): String = buildList {
            add(if (value.isStatic) "1" else "0")
            add(value.className.encoded())
            add(value.methodName.encoded())
            add(value.returnTypeName.encoded())
            add(value.parameterTypeNames.size.toString())
            value.parameterTypeNames.forEach { add(it.encoded()) }
        }.joinToString("|")

        fun decode(value: String?): MethodDescriptor? = runCatching {
            val fields = value?.split('|') ?: return null
            if (fields.size < 5) return null
            val count = fields[4].toInt()
            if (count < 0 || fields.size != count + 5) return null
            MethodDescriptor(
                className = fields[1].decoded(),
                methodName = fields[2].decoded(),
                parameterTypeNames = fields.drop(5).map { it.decoded() },
                returnTypeName = fields[3].decoded(),
                isStatic = fields[0] == "1",
            )
        }.getOrNull()

        fun String.encoded(): String = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(toByteArray(Charsets.UTF_8))

        fun String.decoded(): String = String(
            Base64.getUrlDecoder().decode(this),
            Charsets.UTF_8,
        )
    }
}
