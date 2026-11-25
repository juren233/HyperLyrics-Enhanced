package com.juren233.hyperlyricsenhanced.ui.page

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.ui.component.CardPressFeedback
import com.juren233.hyperlyricsenhanced.ui.component.SectionEnter
import com.juren233.hyperlyricsenhanced.ui.component.SectionExit
import com.juren233.hyperlyricsenhanced.ui.component.SettingsTile
import com.juren233.hyperlyricsenhanced.ui.component.SettingsTileSpec
import com.juren233.hyperlyricsenhanced.ui.component.animateToggleWeights
import com.juren233.hyperlyricsenhanced.ui.component.animatedTileColors
import com.juren233.hyperlyricsenhanced.ui.component.settingsCard
import com.juren233.hyperlyricsenhanced.ui.utils.MonetPalette
import com.juren233.hyperlyricsenhanced.ui.utils.ThemeUtils
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowCascadingListPopup

/*
 * 应用设置页「外观」面板：三张深浅模式预览卡、莫奈取色与语言两枚磁贴，莫奈开启时展开强调色色块。
 * 存储不变：KEY_THEME_MODE 仍是 0–5 单值（拆分与合并见 ThemeModeSelection），KEY_MONET_COLOR 仍是色板下标，
 * KEY_APP_LANGUAGE 仍是 0 跟随系统 / 1 简体中文 / 2 English。
 */

private val PanelGap = 12.dp
private val PreviewHeight = 92.dp
private val PreviewCorner = 10.dp
private val SwatchSize = 34.dp
private fun <T> selectionTween() = tween<T>(durationMillis = 300, easing = FastOutSlowInEasing)

/**
 * 预览示意图里的界面配色：页面底色、卡片底色、文字占位条、开关。
 * 与真实页面同一套 miuix 取色：Scaffold 底色 surface、Card 底色 surfaceContainer、
 * 开关开启轨道 primary、关闭轨道 secondary（占位条用它）。
 */
internal class PreviewPalette(val background: Color, val card: Color, val line: Color, val accent: Color)

/**
 * 某个深浅模式下的预览配色，取该模式（连同当前莫奈开关与强调色）真实生成的主题配色，
 * 与 App 主题走同一个 [ThemeUtils.themeController]，莫奈开启时底色、卡片色也随种子色变化。
 */
@Composable
internal fun previewPalette(brightness: Int, monet: Boolean, monetColorIndex: Int): PreviewPalette {
    val mode = ThemeModeSelection.combine(brightness, monet)
    val controller = remember(mode, monetColorIndex) { ThemeUtils.themeController(mode, monetColorIndex) }
    val scheme = ThemeUtils.themeColors(controller, monetColorIndex)
    return PreviewPalette(
        background = scheme.surface,
        card = scheme.surfaceContainer,
        line = scheme.secondary,
        accent = scheme.primary,
    )
}

@Composable
internal fun SettingsAppearancePanel() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
    var themeMode by remember { mutableIntStateOf(prefs.getInt(UIConstants.KEY_THEME_MODE, UIConstants.DEFAULT_THEME_MODE)) }
    var monetColorIndex by remember { mutableIntStateOf(prefs.getInt(UIConstants.KEY_MONET_COLOR, UIConstants.DEFAULT_MONET_COLOR)) }
    var appLanguage by remember { mutableIntStateOf(prefs.getInt(UIConstants.KEY_APP_LANGUAGE, UIConstants.DEFAULT_APP_LANGUAGE)) }

    val brightness = ThemeModeSelection.brightnessOf(themeMode)
    val monet = ThemeModeSelection.monetOf(themeMode)
    fun saveTheme(newBrightness: Int, newMonet: Boolean) {
        val value = ThemeModeSelection.combine(newBrightness, newMonet)
        if (value == themeMode) return
        themeMode = value
        prefs.edit { putInt(UIConstants.KEY_THEME_MODE, value) }
    }

    Column {
        // 深浅模式：三张预览卡并排，点选即切换。
        Row(
            modifier = Modifier.settingsCard(),
            horizontalArrangement = Arrangement.spacedBy(PanelGap),
        ) {
            val labels = listOf(
                stringResource(R.string.theme_system),
                stringResource(R.string.theme_light),
                stringResource(R.string.theme_dark),
            )
            listOf(
                ThemeModeSelection.BRIGHTNESS_SYSTEM,
                ThemeModeSelection.BRIGHTNESS_LIGHT,
                ThemeModeSelection.BRIGHTNESS_DARK,
            ).forEach { option ->
                ThemePreviewCard(
                    brightness = option,
                    monet = monet,
                    monetColorIndex = monetColorIndex,
                    label = labels[option],
                    selected = brightness == option,
                    onClick = { saveTheme(option, monet) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 莫奈取色与语言：两枚磁贴并排。
        Row(
            modifier = Modifier.settingsCard(),
            horizontalArrangement = Arrangement.spacedBy(PanelGap),
        ) {
            SettingsTile(
                tile = SettingsTileSpec(
                    key = "appearance_monet",
                    title = stringResource(R.string.title_theme_monet),
                    status = stringResource(if (monet) R.string.tile_state_on else R.string.tile_state_off),
                    active = monet,
                    onClick = { saveTheme(brightness, !monet) },
                ) { tint, modifier -> Icon(MiuixIcons.Theme, null, modifier, tint) },
                modifier = Modifier.weight(1f),
            )
            LanguageTile(
                selected = appLanguage,
                onSelect = {
                    if (appLanguage != it) {
                        appLanguage = it
                        prefs.edit { putInt(UIConstants.KEY_APP_LANGUAGE, it) }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        // 强调色：莫奈开启时展开一排圆形色块。
        AnimatedVisibility(visible = monet, enter = SectionEnter, exit = SectionExit) {
            AccentSwatchCard(
                themeMode = themeMode,
                selected = monetColorIndex,
                onSelect = {
                    if (monetColorIndex != it) {
                        monetColorIndex = it
                        prefs.edit { putInt(UIConstants.KEY_MONET_COLOR, it) }
                    }
                },
            )
        }
    }
}

@Composable
private fun ThemePreviewCard(
    brightness: Int,
    monet: Boolean,
    monetColorIndex: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val cardColors = animatedPreviewCardColors(selected)
    val container = cardColors.container
    val lightPreview = previewPalette(ThemeModeSelection.BRIGHTNESS_LIGHT, monet, monetColorIndex)
    val darkPreview = previewPalette(ThemeModeSelection.BRIGHTNESS_DARK, monet, monetColorIndex)
    Card(
        modifier = modifier.semantics { this.selected = selected },
        insideMargin = PaddingValues(10.dp),
        colors = CardDefaults.defaultColors(color = container),
        pressFeedbackType = CardPressFeedback,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PreviewHeight)
                .clip(RoundedCornerShape(PreviewCorner))
                .border(1.dp, colors.dividerLine, RoundedCornerShape(PreviewCorner)),
        ) {
            Canvas(Modifier.matchParentSize()) {
                when (brightness) {
                    ThemeModeSelection.BRIGHTNESS_LIGHT -> drawMiniUi(lightPreview)
                    ThemeModeSelection.BRIGHTNESS_DARK -> drawMiniUi(darkPreview)
                    else -> {
                        // 跟随系统：左半浅色、右半深色。
                        drawMiniUi(lightPreview)
                        clipRect(left = size.width / 2f) { drawMiniUi(darkPreview) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ThemePreviewLabel(label = label, selected = selected, color = cardColors.label)
    }
}

/** 预览卡的底色与标签文字色。 */
internal class PreviewCardColors(val container: Color, val label: Color)

/**
 * 预览卡选中/未选中的配色，直接沿用磁贴的开启/关闭配色（[animatedTileColors]）：
 * 选中卡与开启的磁贴同一个底色、同一个文字色，未选中卡与关闭的磁贴一致，外观组里不再出现两套颜色。
 * 只对选中切换做过渡，切换深浅色或强调色时立即到位，与页面背景同一帧变化（见 [animateStateWeights]）。
 */
@Composable
internal fun animatedPreviewCardColors(selected: Boolean): PreviewCardColors {
    val tile = animatedTileColors(selected)
    return PreviewCardColors(container = tile.container, label = tile.content)
}

/**
 * 预览卡下方的标签，三张卡一律文字居中、不放勾选标记，选中态由整卡染色和主题色文字表示。
 * miuix 的 RadioButton 未选中时什么都不画却仍占 26dp 宽，放进来会让未选中的标签整体右移；
 * miuix 的 Checkbox 未选中时有灰色圆底，但它的颜色用 Transition 动画，主题切换时会慢 300ms，又回到时差问题。
 */
@Composable
internal fun ThemePreviewLabel(label: String, selected: Boolean, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier.fillMaxWidth(),
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 预览示意：顶部标题条、两张卡片，第一张卡片右侧一个强调色开关。 */
private fun DrawScope.drawMiniUi(palette: PreviewPalette) {
    val accent = palette.accent
    drawRect(palette.background)
    val pad = size.width * 0.1f
    val unit = size.height / 12f
    drawRoundRect(
        color = palette.line,
        topLeft = Offset(pad, unit * 1.4f),
        size = Size(size.width * 0.42f, unit * 0.9f),
        cornerRadius = CornerRadius(unit),
    )
    val cardWidth = size.width - pad * 2
    val cardRadius = CornerRadius(unit * 1.2f)
    val firstTop = unit * 3.4f
    drawRoundRect(palette.card, Offset(pad, firstTop), Size(cardWidth, unit * 3.6f), cardRadius)
    drawRoundRect(
        color = palette.line,
        topLeft = Offset(pad * 1.8f, firstTop + unit * 1.35f),
        size = Size(cardWidth * 0.38f, unit * 0.9f),
        cornerRadius = CornerRadius(unit),
    )
    val toggleWidth = unit * 2.6f
    drawRoundRect(
        color = accent,
        topLeft = Offset(pad + cardWidth - toggleWidth - pad * 0.8f, firstTop + unit * 1.0f),
        size = Size(toggleWidth, unit * 1.6f),
        cornerRadius = CornerRadius(unit),
    )
    val secondTop = firstTop + unit * 4.4f
    drawRoundRect(palette.card, Offset(pad, secondTop), Size(cardWidth, unit * 3.0f), cardRadius)
    drawRoundRect(
        color = palette.line,
        topLeft = Offset(pad * 1.8f, secondTop + unit * 1.05f),
        size = Size(cardWidth * 0.55f, unit * 0.9f),
        cornerRadius = CornerRadius(unit),
    )
}

@Composable
private fun LanguageTile(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val options = listOf(
        stringResource(R.string.language_system),
        stringResource(R.string.language_simplified_chinese),
        stringResource(R.string.language_english),
    )
    var show by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        SettingsTile(
            tile = SettingsTileSpec(
                key = "appearance_language",
                title = stringResource(R.string.title_app_language),
                status = options.getOrElse(selected) { options[0] },
                active = false,
                onClick = { show = true },
            ) { tint, iconModifier -> Icon(MiuixIcons.Translate, null, iconModifier, tint) },
            modifier = Modifier.fillMaxWidth(),
        )
        WindowCascadingListPopup(
            show = show,
            entries = listOf(
                DropdownEntry(
                    items = options.mapIndexed { index, label ->
                        DropdownItem(text = label, selected = index == selected, onClick = { onSelect(index) })
                    },
                ),
            ),
            alignment = PopupPositionProvider.Align.TopEnd,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            onDismissRequest = { show = false },
        )
    }
}

/**
 * 每个强调色在当前深浅模式下开启莫奈后，主题实际用的主色（miuix colorScheme.primary）。
 * 与 App 主题走同一个 [ThemeUtils.themeController]：固定色以色板值为种子色按 TonalSpot 生成整套配色后取 primary，
 * 「系统」不指定种子色，由 miuix 读取系统调色板。不另算近似值。
 */
@Composable
internal fun accentSwatchColors(themeMode: Int): List<Color> {
    val monetMode = ThemeModeSelection.combine(ThemeModeSelection.brightnessOf(themeMode), monet = true)
    return MonetPalette.keyColors.indices.map { index ->
        key(monetMode, index) {
            val controller = remember(monetMode, index) { ThemeUtils.themeController(monetMode, index) }
            ThemeUtils.themeColors(controller, index).primary
        }
    }
}

/**
 * 「系统」色块的配色：底色是选「系统」后主题实际的主色（壁纸取色），上面盖一层半透明遮罩，
 * 深色模式压暗、浅色模式提亮，中间的色盘图标用主色本身，图标在遮罩后的底色上保持清楚。
 */
internal class SystemSwatchColors(val base: Color, val mask: Color, val icon: Color) {
    /** 遮罩叠在底色上之后实际看到的颜色。 */
    val background: Color get() = mask.compositeOver(base)
}

internal const val SYSTEM_SWATCH_MASK_ALPHA = 0.6f

internal fun systemSwatchColors(primary: Color, dark: Boolean): SystemSwatchColors = SystemSwatchColors(
    base = primary,
    mask = (if (dark) Color.Black else Color.White).copy(alpha = SYSTEM_SWATCH_MASK_ALPHA),
    icon = primary,
)

/** 设置页当前深浅模式下「系统」色块的配色，主色和深浅判断都与 [accentSwatchColors] 用的主题一致。 */
@Composable
internal fun systemSwatchColors(themeMode: Int): SystemSwatchColors {
    val monetMode = ThemeModeSelection.combine(ThemeModeSelection.brightnessOf(themeMode), monet = true)
    val controller = remember(monetMode) { ThemeUtils.themeController(monetMode, 0) }
    val primary = ThemeUtils.themeColors(controller, 0).primary
    val dark = with(ThemeUtils) { controller.isDarkScheme() }
    return remember(primary, dark) { systemSwatchColors(primary, dark) }
}

@Composable
private fun AccentSwatchCard(themeMode: Int, selected: Int, onSelect: (Int) -> Unit) {
    val names = listOf(
        stringResource(R.string.monet_default),
        stringResource(R.string.monet_blue),
        stringResource(R.string.monet_green),
        stringResource(R.string.monet_red),
        stringResource(R.string.monet_yellow),
        stringResource(R.string.monet_orange),
        stringResource(R.string.monet_purple),
        stringResource(R.string.monet_pink),
    )
    val swatchColors = accentSwatchColors(themeMode)
    val systemColors = systemSwatchColors(themeMode)
    Card(modifier = Modifier.settingsCard()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.title_monet),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = names.getOrElse(selected) { names[0] },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                swatchColors.forEachIndexed { index, color ->
                    AccentSwatch(
                        color = color,
                        system = if (index == 0) systemColors else null,
                        name = names.getOrElse(index) { "" },
                        selected = index == selected,
                        onClick = { onSelect(index) },
                    )
                }
            }
        }
    }
}

/** 色块选中外圈的颜色：选中时为色块本身的颜色，未选中时透明。只对选中切换做过渡，色块颜色随主题变化时外圈同一帧跟上。 */
@Composable
internal fun animatedSwatchRing(color: Color, selected: Boolean): Color {
    val weights = animateToggleWeights(selected, label = "swatchSelected")
    return color.copy(alpha = color.alpha * weights[1])
}

/** 色块中间显示什么：「系统」固定显示色盘图标、不显示对勾；其他色块选中时显示对勾。 */
internal enum class SwatchMark { PaletteIcon, Check, None }

internal fun swatchMark(isSystem: Boolean, selected: Boolean): SwatchMark = when {
    isSystem -> SwatchMark.PaletteIcon
    selected -> SwatchMark.Check
    else -> SwatchMark.None
}

/** 强调色色块。[system] 不为 null 时画成「系统」色块：主色底 + 遮罩 + 色盘图标，选中只靠外圈表示。 */
@Composable
internal fun AccentSwatch(color: Color, system: SystemSwatchColors?, name: String, selected: Boolean, onClick: () -> Unit) {
    val ring = animatedSwatchRing(color, selected)
    val checkScale by animateFloatAsState(
        targetValue = if (swatchMark(system != null, selected) == SwatchMark.Check) 1f else 0f,
        animationSpec = selectionTween(),
        label = "swatchCheck",
    )
    val innerSize by animateDpAsState(
        targetValue = if (selected) SwatchSize - 8.dp else SwatchSize - 2.dp,
        animationSpec = selectionTween(),
        label = "swatchInner",
    )
    Box(
        modifier = Modifier
            .size(SwatchSize)
            .clip(CircleShape)
            .border(2.dp, ring, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                contentDescription = name
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(system?.base ?: color)
                .then(if (system != null) Modifier.background(system.mask) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (system != null) {
                Icon(
                    imageVector = MiuixIcons.Background,
                    contentDescription = null,
                    tint = system.icon,
                    modifier = Modifier.size(SystemSwatchIconSize),
                )
            } else {
                Icon(
                    imageVector = SwatchCheckIcon,
                    contentDescription = null,
                    tint = swatchCheckTint(color),
                    modifier = Modifier.size(16.dp).scale(checkScale),
                )
            }
        }
    }
}

private val SystemSwatchIconSize = 20.dp

/**
 * 色块选中对勾。miuix 图标库每个图标有 Light / Normal / Regular / Medium / Demibold 五档粗细，
 * 默认的 MiuixIcons.Ok 就是 Regular。Medium（笔画面积约 +16%）在真机上看不出区别，
 * 所以用最粗的 Demibold（约 +30%），形状不变。
 */
internal val SwatchCheckIcon: ImageVector get() = MiuixIcons.Demibold.Ok

/** 选中对勾的颜色：按色块亮度选深色或白色。 */
internal fun swatchCheckTint(color: Color): Color =
    if (color.luminance() > 0.6f) Color.Black.copy(alpha = 0.75f) else Color.White
