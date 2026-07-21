<h1 align="center">HyperLyric</h1>

<p align="center">
  <strong>HyperOS 超级岛歌词增强工具 & 独立应用通知歌词服务</strong>
</p>

<p align="center">
  <a href="https://github.com/limczhh/HyperLyric/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0"/></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Android-13.0%20--%2016-green.svg" alt="Android Support"/></a>
  <a href="https://github.com/compose-miuix-ui/miuix"><img src="https://img.shields.io/badge/UI--Framework-Miuix--Compose-orange.svg" alt="Miuix UI"/></a>
  <a href="https://github.com/libxposed/api"><img src="https://img.shields.io/badge/Hook--Framework-libxposed%20101-purple.svg" alt="libxposed"/></a>
</p>

---

HyperLyric 是一款专为小米 HyperOS 量身定制的歌词显示增强工具。项目提供双模运行机制，既支持以 **Xposed 模块** 方式注入 SystemUI 媒体超级岛提供贴合原生风格的逐字动态歌词，也支持作为 **独立应用** 接收系统通知栏/小米焦点通知实现零 Root、免模块的歌词显示。

---

## 🌟 双模运行机制与核心黑科技

项目为了适配不同权限、不同玩家的定制化需求，提供了两种完全解耦的运行模式：

### 1. Xposed 模式 (SystemUI 进程)
对于已解锁 Root 并启用 Xposed 框架（如 LSPosed）的极客用户，HyperLyric 作为 Xposed 模块，通过 Modern Xposed API (libxposed API 101) 深度注入 SystemUI 插件：
- **原生级别的视图注入**：动态拦截 `BaseDexClassLoader` 并在 `SystemUIHookRegistry` 中统一入口，挂钩超级岛插槽（Slot），注入自定义 Canvas 绘制的富歌词渲染器（`RichLyricLineView`）。
- **完全运行时热更新**：通过 `OnSharedPreferenceChangeListener` 监听偏好变动，对超级岛样式与动效实现 **免重启 SystemUI 立即生效**。

### 2. 独立应用模式 (App 进程) — 免 Root 首选
对于未解锁设备，HyperLyric 作为普通独立应用运行。

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

HyperLyric 解耦了歌词来源。无论是哪种数据源，均由项目底层的 `RootLyricSink` 统一分发并驱动 AI 翻译。

| 歌词源 | 工作原理（通俗解释） | 适用音乐播放器示例 | 额外依赖与下载 |
| :--- | :--- | :--- | :--- |
| **Lyricon** (`lyricon`) | 读取词幕状态栏歌词转发的各音乐软件歌词数据。 | 网易云音乐、QQ音乐、酷狗音乐等绝大多数主流软件 | 需安装 [Lyricon central](https://github.com/tomakino/lyricon/releases/tag/core) 以及对应播放器的 [LyricProvider](https://github.com/proify/LyricProvider/releases) |
| **SuperLyric** (`superlyric`) | 获取 SuperLyric 高精度的逐字/词级时间戳歌词。 | 酷我音乐、QQ音乐、汽水音乐等 | 需安装 [SuperLyric](https://github.com/HChenX/SuperLyric) 并开启其广播 |
| **LyricInfo** (`lyricinfo`) | 读取 mediasession 内的 lyricinfo 内置歌词。 | QQ音乐、椒盐音乐 (Salt Player) 等 | 建议安装 [LyricInfo](https://github.com/limczhh/LyricInfo) (可选) |

---

## 📸 截图

<table>
  <tr>
    <td><img src="https://github.com/limczhh/HyperLyric/blob/main/assets/001.webp?raw=true" width="300" alt="截图 001"/></td>
    <td><img src="https://github.com/limczhh/HyperLyric/blob/main/assets/002.webp?raw=true" width="300" alt="截图 002"/></td>
    <td><img src="https://github.com/limczhh/HyperLyric/blob/main/assets/003.webp?raw=true" width="300" alt="截图 003"/></td>
  </tr>
  <tr>
    <td><img src="https://github.com/limczhh/HyperLyric/blob/main/assets/004.webp?raw=true" width="300" alt="截图 004"/></td>
    <td><img src="https://github.com/limczhh/HyperLyric/blob/main/assets/005.webp?raw=true" width="300" alt="截图 005"/></td>
    <td><img src="https://github.com/limczhh/HyperLyric/blob/main/assets/006.webp?raw=true" width="300" alt="截图 006"/></td>
  </tr>
</table>

---

## 📎 致谢与开源协议

- 本项目采用 **GNU General Public License v3.0** 开源协议发布。
- 特别感谢以下项目：
 - [miuix-kmp](https://github.com/compose-miuix-ui/miuix) — HyperOS 风格的 Compose 组件库。
 - [lyricon](https://github.com/tomakino/lyricon) — 本项目大多数歌词动画均移植于 lyricon 项目。
 - [SuperLyric](https://github.com/HChenX/SuperLyric) 
 - [LyricInfo](https://github.com/limczhh/LyricInfo) 
 - [libxposed](https://github.com/libxposed/api)