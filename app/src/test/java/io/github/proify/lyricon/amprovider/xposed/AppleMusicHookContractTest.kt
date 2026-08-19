/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import android.view.View

class AppleMusicHookContractTest {

    // --- 模拟测试类 ---

    // 模拟 6.5.2 中的 PlayerLyricsViewFragment 与菜单监听器
    class MockPlayerLyricsViewFragment

    class MockClickListenerD0 {
        @JvmField
        val a: MockPlayerLyricsViewFragment = MockPlayerLyricsViewFragment()

        fun onClick(view: View) {}
    }

    class MockClickListenerE0 {
        @JvmField
        val otherField: String = "not_a_fragment"

        fun onClick(view: View) {}
    }

    // 模拟 6.5.2 与 6.5.0/6.5.1 的 NeverEqualPolicy
    class MockNeverEqualPolicyS0 {
        companion object {
            @JvmField
            val a: MockNeverEqualPolicyS0 = MockNeverEqualPolicyS0()
        }

        fun equivalent(a: Any?, b: Any?): Boolean = false
    }

    class MockNeverEqualPolicyV0 {
        companion object {
            @JvmField
            val notSingleton: String = "dummy"
        }

        fun equivalent(a: Any?, b: Any?): Boolean = false
    }

    // 模拟 CollectionItemView 与 ActionSheet 绑定
    class MockCollectionItemView

    class MockActionSheetBindingValid {
        @JvmField
        val itemView: MockCollectionItemView = MockCollectionItemView()

        fun l() {}
    }

    class MockActionSheetBindingInvalid {
        @JvmField
        val dummy: String = "none"

        fun l() {}
    }

    // 模拟 Library Epoxy Controller 的两个 5 参重载
    class MockLibraryMainContentController {
        // 真实 5 参重载
        fun buildModels(
            state: String,
            items1: List<Any>,
            items2: List<Any>,
            action: Any,
            config: Any,
        ) {}

        // Typed5EpoxyController 的桥接方法
        fun buildModelsBridge(
            state: Any,
            items1: List<Any>,
            items2: List<Any>,
            action: Any,
            config: Any,
        ) {}
    }

    @Test
    fun `menu click listener contract accepts d0 with fragment field and rejects e0 without fragment`() {
        val contract = AppleMusicHookContracts.forHookPoint(
            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER,
        ) ?: error("Contract missing")

        // 验证 d0 含有 PlayerLyricsViewFragment 类型的字段
        val contextD0 = HookContractContext(
            hookPoint = AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER,
            target = AppleMusicHookTarget(
                className = MockClickListenerD0::class.java.name,
                methodName = "onClick",
                parameterCount = 1,
            ),
            clazz = MockClickListenerD0::class.java,
            method = MockClickListenerD0::class.java.getDeclaredMethod("onClick", View::class.java),
        )
        // 动态使用自定义契约指定 Mock 类的 Fragment 字段类型
        val customContract = AllOfContract(
            RequireNonBridgeMethod(),
            RequireFieldOfType(fieldTypeClassName = MockPlayerLyricsViewFragment::class.java.name),
        )
        val resultD0 = customContract.validate(contextD0)
        assertTrue(resultD0 is ContractResult.Passed)

        // 验证 e0（不包含 Fragment 字段）被拒绝
        val contextE0 = HookContractContext(
            hookPoint = AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER,
            target = AppleMusicHookTarget(
                className = MockClickListenerE0::class.java.name,
                methodName = "onClick",
                parameterCount = 1,
            ),
            clazz = MockClickListenerE0::class.java,
            method = MockClickListenerE0::class.java.getDeclaredMethod("onClick", View::class.java),
        )
        val resultE0 = customContract.validate(contextE0)
        assertTrue(resultE0 is ContractResult.Rejected)
        assertTrue((resultE0 as ContractResult.Rejected).reason.contains("does not contain field of type"))
    }

    @Test
    fun `compose never equal policy contract accepts s0 with self-typed singleton and rejects v0`() {
        val contract = RequireStaticSelfTypedSingleton()

        val contextS0 = HookContractContext(
            hookPoint = AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY,
            target = AppleMusicHookTarget(className = MockNeverEqualPolicyS0::class.java.name),
            clazz = MockNeverEqualPolicyS0::class.java,
            method = null,
        )
        val resultS0 = contract.validate(contextS0)
        assertTrue(resultS0 is ContractResult.Passed)

        val contextV0 = HookContractContext(
            hookPoint = AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY,
            target = AppleMusicHookTarget(className = MockNeverEqualPolicyV0::class.java.name),
            clazz = MockNeverEqualPolicyV0::class.java,
            method = null,
        )
        val resultV0 = contract.validate(contextV0)
        assertTrue(resultV0 is ContractResult.Rejected)
        assertTrue((resultV0 as ContractResult.Rejected).reason.contains("lacks static self-typed singleton"))
    }

    @Test
    fun `action sheet binding contract accepts class with CollectionItemView field and rejects class without it`() {
        val contract = RequireFieldOfType(
            fieldTypeClassName = MockCollectionItemView::class.java.name,
        )

        val contextValid = HookContractContext(
            hookPoint = AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING,
            target = AppleMusicHookTarget(
                className = MockActionSheetBindingValid::class.java.name,
                methodName = "l",
            ),
            clazz = MockActionSheetBindingValid::class.java,
            method = MockActionSheetBindingValid::class.java.getDeclaredMethod("l"),
        )
        assertTrue(contract.validate(contextValid) is ContractResult.Passed)

        val contextInvalid = HookContractContext(
            hookPoint = AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING,
            target = AppleMusicHookTarget(
                className = MockActionSheetBindingInvalid::class.java.name,
                methodName = "l",
            ),
            clazz = MockActionSheetBindingInvalid::class.java,
            method = MockActionSheetBindingInvalid::class.java.getDeclaredMethod("l"),
        )
        assertTrue(contract.validate(contextInvalid) is ContractResult.Rejected)
    }

    @Test
    fun `resolver skips candidate that fails contract and falls back to candidate that passes contract`() {
        val classes = mapOf(
            "com.apple.android.music.player.fragment.e0" to MockClickListenerE0::class.java,
            "com.apple.android.music.player.fragment.d0" to MockClickListenerD0::class.java,
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment" to MockPlayerLyricsViewFragment::class.java,
        )

        val resolver = AppleMusicHookResolver(
            version = AppleMusicVersion("6.5.2", 1586L),
            classLookup = { name -> classes[name] ?: throw ClassNotFoundException(name) },
        )

        // 验证 resolveMethod 会拒绝 e0（不包含 Fragment 字段）并成功解析并选择 d0
        val resolved = resolver.resolveMethod(AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER)
        assertEquals("com.apple.android.music.player.fragment.d0", resolved.target.className)
        assertEquals("onClick", resolved.method.name)
        assertFalse(resolved.compatibilityFallback)
    }
}
