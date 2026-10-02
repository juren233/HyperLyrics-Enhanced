/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Custom View search rows have no DataBinding; retain their own model and adapter position. */
internal class AppleBrowseEpoxyRows(private val runtime: AppleMusicProviderRuntime) {
    private class Row(val controller: WeakReference<Any>, val model: WeakReference<Any>, val position: Int, val id: String, var value: Any?) {
        var queued = false
    }
    private val rows = WeakHashMap<Any, Row>()
    private var adapter: Method? = null
    private var model: Method? = null
    private var notify: Method? = null

    fun initialize() {
        val resolver = runtime.hookResolver
        adapter = resolver.resolveMethod(AppleMusicHookPoint.SEARCH_CONTROLLER_ADAPTER).method
        model = resolver.resolveMethod(AppleMusicHookPoint.SEARCH_ADAPTER_MODEL).method
        notify = resolver.resolveMethod(AppleMusicHookPoint.RECYCLER_NOTIFY_ITEM_CHANGED).method
    }

    fun bind(holder: Any, controller: Any, nativeModel: Any, position: Int, id: String, value: Any?) {
        rows[holder] = Row(WeakReference(controller), WeakReference(nativeModel), position, id, value)
    }

    fun forget(holder: Any) { rows.remove(holder) }

    fun changed(id: String, value: Any?) {
        rows.entries.toList().filter { it.value.id == id && it.value.value != value }.forEach { (holder, row) ->
            row.value = value
            refresh(holder, row)
        }
    }

    private fun refresh(holder: Any, row: Row) {
        if (row.queued) return
        row.queued = true
        val weak = WeakReference(holder)
        runtime.mainHandler.post {
            row.queued = false
            val currentHolder = weak.get() ?: return@post
            if (rows[currentHolder] !== row) return@post
            val controller = row.controller.get() ?: return@post
            val expected = row.model.get() ?: return@post
            runCatching {
                val currentAdapter = adapter?.invoke(controller) ?: return@runCatching
                if (isCurrentBrowseRow(expected, model?.invoke(currentAdapter, row.position))) {
                    notify?.invoke(currentAdapter, row.position)
                }
            }.onFailure { ProviderLogger.error("Apple Music 搜索行元数据刷新失败", it) }
        }
    }

    fun clear() {
        rows.entries.toList().forEach { (holder, row) -> refresh(holder, row) }
        // Keep ownership until the posted native rebind; its callback replaces the row snapshot.
        rows.values.forEach { it.value = null }
    }
}

internal fun isCurrentBrowseRow(expected: Any?, actual: Any?): Boolean = expected != null && expected === actual
