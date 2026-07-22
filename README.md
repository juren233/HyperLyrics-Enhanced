<p align="center">
  <img src="assets/hyperlyrics-app-icon-rounded.png" alt="HyperLyrics Enhanced" width="160" />
</p>

<h1 align="center">HyperLyrics Enhanced</h1>

<p align="center">
  <strong>基于 HyperLyric 针对性优化 Apple Music 的 HyperOS 超级岛歌词增强工具 & 独立应用通知歌词服务</strong>
</p>

<p align="center">
  <a href="https://github.com/limczhh/HyperLyric/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0"/></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Android-13.0%20--%2016-green.svg" alt="Android Support"/></a>
  <a href="https://github.com/compose-miuix-ui/miuix"><img src="https://img.shields.io/badge/UI--Framework-Miuix--Compose-orange.svg" alt="Miuix UI"/></a>
  <a href="https://github.com/libxposed/api"><img src="https://img.shields.io/badge/Hook--Framework-libxposed%20101-purple.svg" alt="libxposed"/></a>
</p>

---

HyperLyrics Enhanced 是一款专为小米 HyperOS 量身定制且针对性优化 Apple Music 歌词体验的歌词显示增强工具。项目基于 [limczhh/HyperLyric](https://github.com/limczhh/HyperLyric) 开发，在保留原项目主要能力的基础上，重点优化 **Apple Music 歌词体验**、补全**无歌词场景**（将online与offline合并且补进超级岛歌词里），并改善在线歌词与 AI 翻译的匹配、补充和显示体验。

项目提供双模运行机制，既支持以 **Xposed 模块** 方式注入 SystemUI 媒体超级岛，提供贴合原生风格的逐字动态歌词，也支持作为 **独立应用** 接收系统通知栏/小米焦点通知，实现零 Root、免模块的歌词显示。

> [!WARNING]
> 本项目不是原项目的官方后续版本。遇到仅在 HyperLyrics Enhanced 中出现的问题，请在本项目的 Issue 中反馈，不要打扰原项目维护者！

## 相较原项目的主要优化

### Apple Music 专项增强

- **内置 Apple Music 逐字歌词 Provider**：无需另行安装 Lyricon、Lyricon Central 或独立 LyricProvider，只需在 LSPosed 中勾选推荐作用域即可做到开箱即用。
- **歌词直连传输**：Apple Music 可将歌词、播放状态和进度直接传递给 SystemUI；连接恢复后会自动补发当前歌曲，降低切歌或 SystemUI 重连后的歌词丢失概率。
- **完整保留 Apple Music 歌词信息**：支持逐字时间轴、原生翻译、背景人声、伴唱翻译、合唱标记和多人演唱布局，并处理不同的对唱方向。你现在还可以将合唱歌词居中显示。
- **无原生歌词时自动兜底**：优先等待 Apple Music 原生歌词；确认无歌词后，可从 QQ 音乐或网易云音乐获取逐行歌词，并允许设置优先歌词源。

### 歌词补全与同步修复

- **合并 Online 与 Offline 版本**：移除原有 `online`、`offline` 构建变体，将在线歌词能力整合进统一版本，并接入超级岛歌词管线，在 Apple Music 缺少原生歌词时自动补全。
- **补全无歌词场景**：根据歌曲标题、歌手、时长及版本特征匹配在线歌词，降低同名歌曲、Live、Remastered 或翻唱版本的误匹配。
- **改善重复歌词刷新**：即使相邻歌词文本相同，也会依据时间轴切换到正确歌词行，避免重复句不刷新。
- **规范兜底歌词时间轴**：过滤无效行、按时间排序并去除重复时间戳，同时补齐行结束时间，减少歌词跳行和停留时间异常。
- **按时间戳绑定译文**：在线歌词与译文按对应时间匹配，避免将其他时间点的翻译错误附加到当前歌词行。

### 歌词翻译体验优化

- **Apple Music 同款间奏动画**：识别前奏、较长间奏和普通歌词间隔，在适用时显示随时长推进的三点动画。
- **在线译文智能补全**：当 Apple Music 原生歌词没有翻译时，可从 QQ 音乐或网易云音乐匹配译文；支持一行对多行、多行对一行等结构差异。
- **翻译源软优先与质量选择**：可独立设置优先使用 QQ 音乐或网易云音乐翻译（但如果自行设置的优先源质量不如另一个源，默认会使用另一个源）；系统综合覆盖率和匹配可信度选择质量更好的结果，并使用另一来源补齐仍然缺失的译文。
- **按优先级补全译文**：优先保留 Apple Music 原生译文和匹配到的在线译文，AI 翻译仅补充剩余缺失行；即使开启强制 AI 翻译，已匹配的在线译文仍具有更高优先级。
- **更灵活的第二行显示策略**：支持原文与译文交换、仅显示译文；自动切换时优先显示翻译或伴唱，没有可用内容时显示下一句歌词（如开启了“显示下一句歌词”）。在适用的逐字歌词布局中，还可将伴唱译文临时显示到相邻歌词槽位。

---

## 🌟 双模运行机制与核心黑科技

项目为了适配不同权限、不同玩家的定制化需求，提供了两种完全解耦的运行模式：

### 1. Xposed 模式 (SystemUI 进程)
对于已解锁 Root 并启用 Xposed 框架（如 LSPosed）的极客用户，HyperLyrics Enhanced 作为 Xposed 模块，通过 Modern Xposed API (libxposed API 101) 深度注入 SystemUI 插件：
- **原生级别的视图注入**：动态拦截 `BaseDexClassLoader` 并在 `SystemUIHookRegistry` 中统一入口，挂钩超级岛插槽（Slot），注入自定义 Canvas 绘制的富歌词渲染器（`RichLyricLineView`）。
- **完全运行时热更新**：通过 `OnSharedPreferenceChangeListener` 监听偏好变动，对超级岛样式与动效实现 **免重启 SystemUI 立即生效**。

### 2. 独立应用模式 (App 进程) — 免 Root 首选
对于未解锁设备，HyperLyrics Enhanced 作为普通独立应用运行。

---


## 📱 适配与兼容性说明

> ⚠️ 注意：各系统与安卓版本的插件更新频繁，实际效果以具体设备环境为准。

| 功能模块 | 支持安卓版本 | 支持系统版本 | 说明 |
| :--- | :--- | :--- | :--- |
| **Xposed 超级岛歌词** | Android 15+ | HyperOS 3 | 需要注入 `miui.systemui.plugin` |
| **Xposed 移除焦点通知白名单** | Android 13+ | HyperOS 2、HyperOS 3 | 拦截 `com.xiaomi.xmsf` 进行判定 |
| **Xposed 移除媒体超级岛下拉白名单** | Android 16 | HyperOS 3.0.300+ | 突破下拉扩展岛的使用限制 |
| **Live update 安卓实时通知歌词** | Android 16 | HyperOS 3.0.300+、ColorOS 16 | 采用常规安卓实时通知接口推送歌词 |
| **Notification Spotlight 焦点通知歌词** | Android 13+ | HyperOS 2、HyperOS 3 | 独立应用配合 Shizuku 绕过发送 |

---

## 🔌 歌词源详解 (Lyric Sources)

HyperLyrics Enhanced 解耦了歌词来源。无论是哪种数据源，均由项目底层的 `RootLyricSink` 统一分发并驱动 AI 翻译。

| 歌词源 | 工作原理（通俗解释） | 适用音乐播放器示例 | 额外依赖与下载 |
| :--- | :--- | :--- | :--- |
| **Lyricon** (`lyricon`) | Apple Music 通过内置 Provider 直接取词；其他播放器读取词幕转发的数据。 | Apple Music、网易云音乐、QQ音乐、酷狗音乐等 | Apple Music 无需额外 Provider 或 Lyricon central；其他播放器仍需 [Lyricon central](https://github.com/tomakino/lyricon/releases/tag/core) 和对应 [LyricProvider](https://github.com/proify/LyricProvider/releases) |
| **SuperLyric** (`superlyric`) | 获取 SuperLyric 高精度的逐字/词级时间戳歌词。 | 酷我音乐、QQ音乐、汽水音乐等 | 需安装 [SuperLyric](https://github.com/HChenX/SuperLyric) 并开启其广播 |
| **LyricInfo** (`lyricinfo`) | 读取 mediasession 内的 lyricinfo 内置歌词。 | QQ音乐、椒盐音乐 (Salt Player) 等 | 建议安装 [LyricInfo](https://github.com/limczhh/LyricInfo) (可选) |

---

## 📎 致谢与开源协议

- 本项目采用 **GNU General Public License v3.0** 开源协议发布。
- 特别感谢以下项目：

  - [HyperLyric](https://github.com/limczhh/HyperLyric) — 本项目基于其开发，感谢原作者提供的完整基础实现。
  - [miuix-kmp](https://github.com/compose-miuix-ui/miuix) — HyperOS 风格的 Compose 组件库。
  - [lyricon](https://github.com/tomakino/lyricon) — 本项目大多数歌词动画均移植于 lyricon 项目。
  - [SuperLyric](https://github.com/HChenX/SuperLyric)
  - [LyricInfo](https://github.com/limczhh/LyricInfo)
  - [libxposed](https://github.com/libxposed/api)
