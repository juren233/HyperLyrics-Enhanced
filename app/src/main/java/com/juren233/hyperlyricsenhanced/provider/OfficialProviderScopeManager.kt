/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.util.Log
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.root.RootApplication
import io.github.libxposed.service.XposedService
import java.util.concurrent.ConcurrentHashMap

/** Ensures enabled official Provider Packs can actually load into their target apps. */
object OfficialProviderScopeManager {
    private const val TAG = "HLEProvider/Scope"
    private val pendingScopes = ConcurrentHashMap.newKeySet<String>()

    fun requestConfiguredScopes(service: XposedService? = RootApplication.xposedService) {
        service ?: return
        val desiredScopes = OfficialProviderCatalog.definitions
            .filter { definition ->
                definition.targetPackages.any { playerPackageName ->
                    OfficialProviderPreferencePolicy.isOfficialProviderPreferred(
                        preferences = PrefsBridge.getPrefs(),
                        playerPackageName = playerPackageName,
                    )
                }
            }
            .flatMapTo(linkedSetOf()) { it.targetPackages }
        requestMissingScopes(service, desiredScopes)
    }

    fun requestPluginScopes(
        pluginId: String,
        service: XposedService? = RootApplication.xposedService,
    ) {
        service ?: return
        val definition = requireNotNull(OfficialProviderCatalog.definitionForId(pluginId))
        requestMissingScopes(service, definition.targetPackages)
    }

    fun onServiceDied() {
        pendingScopes.clear()
    }

    internal fun missingScopes(
        desiredScopes: Set<String>,
        currentScopes: Set<String>,
    ): Set<String> = desiredScopes - currentScopes

    private fun requestMissingScopes(
        service: XposedService,
        desiredScopes: Set<String>,
    ) {
        if (desiredScopes.isEmpty()) return
        val currentScopes = runCatching { service.scope.toSet() }
            .onFailure { error ->
                Log.e(TAG, "读取 HyperLyrics Enhanced 作用域失败", error)
            }
            .getOrNull() ?: return
        val missing = missingScopes(desiredScopes, currentScopes)
            .filterNotTo(linkedSetOf(), pendingScopes::contains)
        if (missing.isEmpty()) {
            Log.i(TAG, "官方 Provider 作用域已就绪: packages=${desiredScopes.sorted()}")
            return
        }

        pendingScopes += missing
        runCatching {
            service.requestScope(
                missing.sorted(),
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        pendingScopes.removeAll(missing)
                        Log.i(
                            TAG,
                            "官方 Provider 作用域已授权: requested=${missing.sorted()}, " +
                                "approved=${approved.sorted()}",
                        )
                    }

                    override fun onScopeRequestFailed(message: String) {
                        pendingScopes.removeAll(missing)
                        Log.e(
                            TAG,
                            "官方 Provider 作用域授权失败: packages=${missing.sorted()}, " +
                                "reason=$message",
                        )
                    }
                },
            )
            Log.i(TAG, "已请求官方 Provider 作用域: packages=${missing.sorted()}")
        }.onFailure { error ->
            pendingScopes.removeAll(missing)
            Log.e(TAG, "请求官方 Provider 作用域失败: packages=${missing.sorted()}", error)
        }
    }
}
