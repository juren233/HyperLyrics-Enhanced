package com.juren233.hyperlyricsenhanced.ui.component

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.ui.utils.ThemeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Duration

/**
 * Cavan：头卡说明文字改在标点处换行，不要把一个词拆成两行（250092 渲染里有「歌词 / 源，」「点 / 击此处去开启」）。
 * 用 Apple Music 头卡的三条提示和首页头卡的真实文案，按设置页里的真实宽度画出来，读出每一行在哪里断：
 * 断点必须落在标点后面或中英文之间的空格上，也不能留下只有一两个字的孤行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w393dp-h1600dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeroCardLineBreakTest {

    private class Scene(
        val overline: String,
        val title: String,
        val subtitle: String,
        val notes: List<String>,
        val tone: HeroTone,
        val icon: ImageVector,
    )

    // 文案照抄 values/strings.xml（单测默认不带资源），版本号用 250092 的实际值。
    private val scenes = listOf(
        Scene(
            "Apple Music", "暂不可用",
            "需要先在歌词设置页选择 Lyricon 歌词源，并在 Lyricon 配置页添加 Apple Music 插件，方可使用体验优化功能，点击此处去切换",
            listOf("版本 1.0.39"), HeroTone.Warning, StatusGlyphs.Warning,
        ),
        Scene(
            "Apple Music", "暂不可用",
            "需要先在 Lyricon 配置页添加 Apple Music 插件，方可使用体验优化功能，点击此处去添加",
            emptyList(), HeroTone.Error, StatusGlyphs.Error,
        ),
        Scene(
            "Apple Music", "暂不可用",
            "Apple Music 插件已添加但未开启，点击此处去开启",
            listOf("版本 1.0.39"), HeroTone.Warning, StatusGlyphs.Warning,
        ),
        Scene(
            "日志分析", "1 个报错 · 2 个警告",
            "标红的项会让模块或部分功能无法正常工作",
            listOf("已检查 8 项 · 5 项通过"), HeroTone.Error, StatusGlyphs.Error,
        ),
        Scene(
            "日志分析", "1 个警告",
            "不影响正常使用，处理后体验更完整",
            listOf("已检查 8 项 · 7 项通过"), HeroTone.Warning, StatusGlyphs.Warning,
        ),
        Scene(
            "歌词源", "SuperLyric",
            "版本 8.5.0-canary-250092",
            listOf("有新版本 8.5.1", "当前为 Debug 包，日常使用请装 Release 包"), HeroTone.Accent, MiuixIcons.Notes,
        ),
    )

    @Test
    fun heroBodyText_breaksAtPunctuationNotInsideWords() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MiuixTheme(controller = ThemeUtils.themeController(1, 0)) {
                Column(
                    Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    scenes.forEach { scene ->
                        HeroCard(
                            overline = scene.overline,
                            title = scene.title,
                            subtitle = scene.subtitle,
                            notes = scene.notes,
                            tone = scene.tone,
                            decorationIcon = scene.icon,
                            onClick = {},
                            modifier = Modifier.settingsHeroCard(),
                        )
                    }
                }
            }
        }
        // 头卡只有 300ms 的配色过渡，没有无限动画，推进固定时长即可。
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        val layouts = textLayouts(activity.window.decorView)
        val problems = mutableListOf<String>()
        val checked = mutableListOf<String>()
        scenes.flatMap { listOf(it.subtitle) + it.notes }.forEach { original ->
            val layout = layouts.firstOrNull { it.layoutInput.text.text.replace("\n", "").replace(" ", "") == original.replace(" ", "") }
            if (layout == null) {
                problems += "没找到「$original」"
                return@forEach
            }
            val lines = (0 until layout.lineCount).map {
                layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim()
            }.filter { it.isNotEmpty() }
            checked += lines.joinToString(" / ")
            assertEquals("「$original」排出来的字不能变", original.replace(" ", ""), lines.joinToString("").replace(" ", ""))
            lines.zipWithNext().forEach { (before, after) ->
                if (!isNaturalBreak(before, after)) problems += "「$before / $after」断在词中间"
            }
            if (lines.size > 1 && lines.last().count { !it.isWhitespace() } <= 2) problems += "「${lines.joinToString(" / ")}」最后一行是孤字"
        }
        println("头卡断行：\n" + checked.joinToString("\n"))
        assertTrue("断行不自然：\n" + problems.joinToString("\n") + "\n全部断行：\n" + checked.joinToString("\n"), problems.isEmpty())
    }

    /** 这些文案里的词，断行不能把它们拆开（250092 里「歌词 / 源」「点 / 击」「正 / 常」都出现过）。 */
    private val words = listOf(
        "需要", "歌词", "歌词源", "设置页", "选择", "配置页", "添加", "插件", "方可", "使用", "体验", "优化", "功能",
        "点击", "此处", "切换", "开启", "标红", "模块", "部分", "无法", "正常", "工作", "影响", "处理", "完整", "日常", "当前",
    )

    /**
     * 自然的断点：前一行以标点结尾；或者断在中英文之间（前后一边是汉字、一边是英文或数字）；
     * 两边都是汉字时，不能把上面列的词拆开。英文、数字之间不断（「Apple / Music」「8.5.0 / canary」都不行）。
     */
    private fun isNaturalBreak(before: String, after: String): Boolean {
        val last = before.last()
        val first = after.first()
        if (last in "，。、；：！？）」』,;:!?") return true
        if (isCjk(last) != isCjk(first)) return true
        if (!isCjk(last)) return false
        val joined = before + after
        val at = before.length
        return words.none { word ->
            generateSequence(joined.indexOf(word)) { joined.indexOf(word, it + 1).takeIf { i -> i >= 0 } }
                .takeWhile { it >= 0 }
                .any { it < at && it + word.length > at }
        }
    }

    private fun isCjk(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'

    /** 从窗口里取出每段文字排版后的结果（与无障碍读到的同一份）。 */
    private fun textLayouts(root: View): List<TextLayoutResult> {
        val composeView = generateSequence(listOf(root)) { level ->
            level.flatMap { v -> if (v is ViewGroup) (0 until v.childCount).map { v.getChildAt(it) } else emptyList() }
                .ifEmpty { null }
        }.flatten().first { it is ViewRootForTest } as ViewRootForTest
        return composeView.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).mapNotNull { node ->
            if (SemanticsActions.GetTextLayoutResult !in node.config) return@mapNotNull null
            val out = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
            out.firstOrNull()
        }
    }
}
