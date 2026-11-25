package com.juren233.hyperlyricsenhanced.ui.page

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 复现真机现象：三张深浅模式预览卡下的标签，未选中的「浅色」「深色」明显偏右。
 * 原因是 miuix RadioButton 未选中时什么都不画（勾的透明度为 0），却仍占 26dp 宽，加上 6dp 间距，
 * 文字在剩下的空间里居中，于是整体右移 16dp。
 *
 * 这里把标签放进固定宽度的卡片里，断言选中和未选中时文字中心都在卡片正中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ThemePreviewLabelLayoutTest {

    private val cardWidthDp = 120

    /** 返回 (文字中心 x, 卡片中心 x)，单位 px。 */
    private fun measure(label: String, selected: Boolean): Pair<Float, Float> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            Box(Modifier.width(cardWidthDp.dp)) {
                ThemePreviewLabel(label = label, selected = selected, color = Color.Black)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val root = findComposeRoot(activity.window.decorView)
        val textNode = findTextNode(root.semanticsOwner.unmergedRootSemanticsNode, label)
        val bounds = textNode.boundsInRoot
        val density = activity.resources.displayMetrics.density
        return (bounds.left + bounds.right) / 2f to cardWidthDp * density / 2f
    }

    private fun findComposeRoot(view: View): ViewRootForTest {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                runCatching { return findComposeRoot(view.getChildAt(i)) }
            }
        }
        error("没有找到 Compose 根视图")
    }

    private fun findTextNode(node: SemanticsNode, label: String): SemanticsNode {
        val text = node.config.getOrNull(SemanticsProperties.Text)
        if (text?.any { it.text == label } == true) return node
        node.children.forEach { child -> runCatching { return findTextNode(child, label) } }
        error("没有找到文字「$label」")
    }

    private fun assertCentered(label: String, selected: Boolean) {
        val (textCenter, cardCenter) = measure(label, selected)
        assertEquals("「$label」selected=$selected 的文字应在卡片正中", cardCenter, textCenter, 1f)
    }

    @Test
    fun unselectedLabels_areCentered() {
        assertCentered("浅色", selected = false)
        assertCentered("深色", selected = false)
    }

    @Test
    fun selectedLabel_isCentered() {
        assertCentered("跟随系统", selected = true)
    }
}
