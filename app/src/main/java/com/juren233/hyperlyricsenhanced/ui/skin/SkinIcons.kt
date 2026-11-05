/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune

/**
 * 一个语义图标在两种风格下的各自资源：Miuix 用其矢量图标，Glasmio 用 SF Symbols 名称
 * （Glasmio 规范以 SF Symbols 为图标体系）。两处的对应关系只在这里定义。
 */
class SkinIcon(val miuix: ImageVector, val sfSymbol: String)

object SkinIcons {
    val Back = SkinIcon(MiuixIcons.Back, "chevron.backward")
    val ArrowRight = SkinIcon(MiuixIcons.Basic.ArrowRight, "chevron.right")
    val Search = SkinIcon(MiuixIcons.Basic.Search, "magnifyingglass")
    val SearchCleanup = SkinIcon(MiuixIcons.Basic.SearchCleanup, "xmark.circle.fill")
    val ChevronBackward = SkinIcon(MiuixIcons.ChevronBackward, "chevron.backward")
    val ChevronForward = SkinIcon(MiuixIcons.ChevronForward, "chevron.forward")
    val Close = SkinIcon(MiuixIcons.Close, "xmark")
    val Delete = SkinIcon(MiuixIcons.Delete, "trash")
    val Download = SkinIcon(MiuixIcons.Download, "arrow.down.circle")
    val Help = SkinIcon(MiuixIcons.Help, "questionmark.circle")
    val Info = SkinIcon(MiuixIcons.Info, "info.circle")
    val Messages = SkinIcon(MiuixIcons.Messages, "message")
    val More = SkinIcon(MiuixIcons.More, "ellipsis")
    val Music = SkinIcon(MiuixIcons.Music, "music.note")
    val Ok = SkinIcon(MiuixIcons.Ok, "checkmark")
    val Refresh = SkinIcon(MiuixIcons.Refresh, "arrow.clockwise")
    val Settings = SkinIcon(MiuixIcons.Settings, "gearshape")
    val Tune = SkinIcon(MiuixIcons.Tune, "slider.horizontal.3")
}
