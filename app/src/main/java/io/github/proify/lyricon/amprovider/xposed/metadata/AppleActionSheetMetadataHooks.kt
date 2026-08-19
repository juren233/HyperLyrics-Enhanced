/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.Looper
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal interface AppleActionSheetMetadataHost {
    fun activePlaybackIdentity(): ActivePlaybackMediaIdentity

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun rawContentItemValue(contentItem: Any, runtimeMember: AppleMusicRuntimeMember): Any?

    fun recordArtistAssociation(mediaId: String, item: Any, rawTitle: String?)

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun knownValues(mediaId: String, field: VisibleTextField): Set<String>

    fun shouldRequestOverride(mediaId: String): Boolean

    fun ensureOverride(
        mediaId: String,
        priority: AppleInternalCatalogResolver.RequestPriority,
    )

    fun localizedText(
        field: VisibleTextField,
        alias: AppleInternalCatalogResolver.Alias,
    ): String

    fun logMetadataIdentity(
        event: String,
        identity: ActivePlaybackMediaIdentity? = null,
        details: String,
    )
}

internal class AppleActionSheetMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleActionSheetMetadataHost,
) {
    private var collectionItemViewClass: Class<*>? = null
    private var collectionItemContract: AppleActionSheetCollectionItemContract? = null
    private val bindingRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    private val bindings =
        io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap<
            Any,
            InAppActionSheetBinding,
            >()

    fun installHooks() {
        runCatching {
            val resolvedBinding = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING
            )
            val resolvedItem = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW,
            )
            collectionItemViewClass = resolvedItem.clazz
            val itemContract = AppleActionSheetCollectionItemContract(
                getContentType = resolvedItem.target.runtimeMemberName(
                    AppleMusicRuntimeMember.COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD
                ),
                getTitle = resolvedItem.target.runtimeMemberName(
                    AppleMusicRuntimeMember.COLLECTION_ITEM_GET_TITLE_METHOD
                ),
                setTitle = resolvedItem.target.runtimeMemberName(
                    AppleMusicRuntimeMember.COLLECTION_ITEM_SET_TITLE_METHOD
                ),
                notifyChange = resolvedItem.target.runtimeMemberName(
                    AppleMusicRuntimeMember.COLLECTION_ITEM_NOTIFY_CHANGE_METHOD
                ),
            )
            collectionItemContract = itemContract
            val bindingClass = resolvedBinding.method.declaringClass
            val bindMethod = resolvedBinding.method
            val itemFieldCandidates = generateSequence(bindingClass) { current ->
                current.superclass
            }.flatMap { current -> current.declaredFields.asSequence() }
                .filter { field ->
                    !Modifier.isStatic(field.modifiers) &&
                        isCollectionItemViewField(field)
                }
                .toList()
            val itemField = itemFieldCandidates.singleOrNull()
                ?.apply { isAccessible = true }
                ?: error(
                    "Action sheet CollectionItemView field unavailable; " +
                        if (BuildConfig.DEBUG) {
                            "binding=${resolvedBinding.target.className}, " +
                                "declaring=${bindingClass.name}, " +
                                "itemClass=${collectionItemViewClass?.name}, " +
                                "candidates=${itemFieldCandidates.joinToString { field ->
                                    "${field.declaringClass.name}.${field.name}:" +
                                        "${field.type.name}:static=" +
                                        Modifier.isStatic(field.modifiers)
                                }}"
                        } else {
                            "binding=${resolvedBinding.target.className}"
                        }
                )
            runtime.hookRegistrar.installHook(bindMethod, after = { chain, _ ->
                val binding = chain.thisObject ?: return@installHook
                val item = runCatching { itemField.get(binding) }.getOrNull()
                    ?: return@installHook
                val contentType = runCatching {
                    AppleReflection.call(item, itemContract.getContentType) as? Int
                }.getOrNull() ?: return@installHook
                val field = fieldForContentType(contentType) ?: return@installHook
                val identity = host.activePlaybackIdentity()
                val mediaId = identity.mediaId ?: return@installHook
                host.markMetadataVisible(listOf(mediaId))
                val rawTitle = runCatching {
                    AppleReflection.call(item, itemContract.getTitle) as? String
                }.getOrNull() ?: host.rawContentItemValue(
                    item,
                    AppleMusicRuntimeMember.CONTENT_ITEM_TITLE_FIELD,
                ) as? String
                if (!itemMatchesMedia(mediaId, field, rawTitle)) {
                    host.logMetadataIdentity(
                        event = "action_sheet_bind_skip",
                        identity = identity,
                        details = "binding=${binding.javaClass.name}, item=${item.javaClass.name}, " +
                            "type=$contentType, field=$field, raw=$rawTitle, " +
                            "reason=active_metadata_mismatch",
                    )
                    return@installHook
                }
                if (field == VisibleTextField.ARTIST) {
                    host.recordArtistAssociation(mediaId, item, rawTitle)
                }
                registerBinding(mediaId, binding, field)
                val alias = host.effectiveAlias(mediaId)
                if (BuildConfig.DEBUG) {
                    val boundText = titleTextViews(binding, rawTitle)
                        .joinToString(prefix = "[", postfix = "]") { view ->
                            view.text?.toString().orEmpty()
                        }
                    host.logMetadataIdentity(
                        event = "action_sheet_bind",
                        identity = identity,
                        details = "binding=${binding.javaClass.name}, " +
                            "item=${item.javaClass.name}, type=$contentType, field=$field, " +
                            "raw=$rawTitle, bound=$boundText, " +
                            "alias=${alias?.title}/${alias?.artist}/${alias?.album}",
                    )
                }
                if (host.shouldRequestOverride(mediaId)) {
                    host.ensureOverride(
                        mediaId = mediaId,
                        priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    )
                }
                alias?.let {
                    applyAliasToBinding(
                        binding = binding,
                        association = InAppActionSheetBinding(mediaId, field),
                        alias = it,
                    )
                }
            })
            ProviderLogger.info(
                "Apple Music 播放菜单元数据 Hook 已安装: " +
                    "binding=${resolvedBinding.target.className}, " +
                    "fallback=${resolvedBinding.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 播放菜单元数据 Hook 安装失败", it)
        }
    }

    fun hasBindings(mediaId: String): Boolean =
        bindingRefs[mediaId]?.any { it.get() != null } == true

    fun applyAlias(mediaId: String, alias: AppleInternalCatalogResolver.Alias) {
        val apply = apply@{
            var liveBindings = 0
            val refs = bindingRefs[mediaId] ?: return@apply
            refs.forEach { ref ->
                val binding = ref.get()
                if (binding == null) {
                    refs.remove(ref)
                } else {
                    val association = bindings[binding]
                        ?.takeIf { it.mediaId == mediaId }
                        ?: return@forEach
                    liveBindings += 1
                    applyAliasToBinding(binding, association, alias)
                }
            }
            if (BuildConfig.DEBUG) {
                host.logMetadataIdentity(
                    event = "action_sheet_refresh",
                    details = "overrideId=$mediaId, liveBindings=$liveBindings, " +
                        "alias=${alias.title}/${alias.artist}/${alias.album}",
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else runtime.mainHandler.post(apply)
    }

    private fun registerBinding(mediaId: String, binding: Any, field: VisibleTextField) {
        bindings[binding] = InAppActionSheetBinding(mediaId, field)
        val refs = bindingRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
        var registered = false
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null) refs.remove(ref) else if (target === binding) registered = true
        }
        if (!registered) refs.add(WeakReference(binding))
    }

    private fun applyAliasToBinding(
        binding: Any,
        association: InAppActionSheetBinding,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        if (bindings[binding] != association) return
        val itemContract = collectionItemContract ?: return
        val item = collectionItem(binding) ?: return
        val contentType = runCatching {
            AppleReflection.call(item, itemContract.getContentType) as? Int
        }.getOrNull() ?: return
        if (fieldForContentType(contentType) != association.field) return
        val value = host.localizedText(association.field, alias)
            .takeIf(String::isNotBlank) ?: return
        val originalTitle = runCatching {
            AppleReflection.call(item, itemContract.getTitle) as? String
        }.getOrNull()
        val titleViews = titleTextViews(binding, originalTitle)
        runCatching {
            AppleReflection.call(item, itemContract.setTitle, value)
            AppleReflection.call(item, itemContract.notifyChange)
        }
        val before = titleViews.map { view -> view.text?.toString().orEmpty() }
        titleViews.forEach { view -> if (view.text?.toString() != value) view.text = value }
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 播放菜单元数据覆盖: id=${association.mediaId}, " +
                    "field=${association.field}, type=$contentType, " +
                    "binding=${System.identityHashCode(binding)}, " +
                    "before=$before, target=$value, " +
                    "after=${titleViews.map { view -> view.text?.toString().orEmpty() }}"
            )
        }
    }

    private fun collectionItem(binding: Any): Any? =
        generateSequence(binding.javaClass) { current -> current.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) &&
                    isCollectionItemViewField(field)
            }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(binding)
                }.getOrNull()
            }

    private fun titleTextViews(binding: Any, expectedTitle: String?): List<TextView> {
        val normalizedTitle = expectedTitle?.trim()?.takeIf(String::isNotEmpty)
            ?: return emptyList()
        return generateSequence(binding.javaClass) { current -> current.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .filter { field ->
                !Modifier.isStatic(field.modifiers) &&
                    TextView::class.java.isAssignableFrom(field.type)
            }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(binding) as? TextView
                }.getOrNull()
            }
            .filter { view -> view.text?.toString()?.trim() == normalizedTitle }
            .distinctBy(System::identityHashCode)
            .toList()
    }

    private fun fieldForContentType(contentType: Int): VisibleTextField? = when (contentType) {
        6 -> VisibleTextField.ARTIST
        3 -> VisibleTextField.ALBUM
        else -> null
    }

    private fun itemMatchesMedia(
        mediaId: String,
        field: VisibleTextField,
        value: String?,
    ): Boolean {
        val text = value?.takeIf(String::isNotBlank) ?: return false
        return text in host.knownValues(mediaId, field)
    }

    private fun isCollectionItemViewField(field: java.lang.reflect.Field): Boolean =
        collectionItemViewClass?.let { itemClass ->
            itemClass.isAssignableFrom(field.type)
        } == true

    private data class AppleActionSheetCollectionItemContract(
        val getContentType: String,
        val getTitle: String,
        val setTitle: String,
        val notifyChange: String,
    )
}
