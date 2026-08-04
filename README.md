<p align="center">
  <img src="assets/hyperlyrics-app-icon-rounded.png" alt="HyperLyrics Enhanced" width="160" />
</p>

<h1 align="center">HyperLyrics Enhanced</h1>

<p align="center">
  <strong>面向 HyperOS 的 Apple Music 深度适配与系统级歌词展示增强工具</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0"/></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Android-13%2B-green.svg" alt="Android Support"/></a>
  <a href="https://github.com/compose-miuix-ui/miuix"><img src="https://img.shields.io/badge/UI--Framework-Miuix--Compose-orange.svg" alt="Miuix UI"/></a>
  <a href="https://github.com/libxposed/api"><img src="https://img.shields.io/badge/Hook--Framework-libxposed-purple.svg" alt="libxposed"/></a>
</p>

---

## 项目定位

HyperLyrics Enhanced 是一个为小米 HyperOS 设备打造的 Android 模块与独立应用，也为其他安卓品牌手机提供 Apple Music 体验优化。

- 将 Apple Music 的逐字歌词、翻译、伴唱和歌曲信息更完整地带入小米 HyperOS 的超级岛、媒体卡片与 AOD。
- 将 Apple Music 体验进一步优化，且面向所有安卓品牌手机。
- 同时兼容原项目支持的其他音乐软件，如 QQ 音乐、网易云音乐等。

本项目基于 [limczhh/HyperLyric](https://github.com/limczhh/HyperLyric) 二次开发，现已形成独立的功能方向和维护边界。

> [!WARNING]
> HyperLyrics Enhanced 不是 HyperLyric 的官方后续版本。仅在本项目中出现的问题，请在本项目的 Issue 中反馈，不要打扰原项目维护者。

## 相较原项目的主要优化

### 1. Apple Music 从外部 Provider 变成内置能力

- 内置 Apple Music 逐字歌词 Provider。Apple Music 用户不需要额外安装 Lyricon、Lyricon Central 或独立 LyricProvider，即可使用推荐作用域配置。
- 直接传递歌词、播放状态和进度，并在 SystemUI 重连、切歌或数据延迟后重新同步当前歌曲。
- 支持在 Apple Music App 内进行更细粒度的体验调整：内容 UI 地区语言、歌曲信息地区化、原地区名称恢复、元数据检索缓存、繁体歌词转简体、歌词模糊效果、跟随系统字体粗细等优化。
- Apple Music 的原生歌词翻译和发音数据优先显示；缺失内容可按配置从在线歌词源补全。

### 2. 统一在线、离线与兜底歌词管线

- 移除原有在线/离线构建分支，将在线歌词能力整合进统一版本。
- Apple Music 原生歌词优先；确认没有可用原生歌词后，才从 QQ 音乐或网易云音乐获取逐行歌词兜底。
- 根据标题、歌手、时长和版本特征进行匹配，尽量区分同名歌曲、Live、Remastered、翻唱和不同剪辑版本。
- 处理 LRC/QRC、逐行歌词和逐字歌词之间的结构转换，并修复无效行、重复时间戳、缺失结束时间、相邻重复歌词不刷新等常见问题。
- 在线歌词和译文按时间轴与结构进行匹配，支持一行对多行、多行对一行等差异，减少翻译错位。

### 3. 翻译优先级更明确，也更可控

- Apple Music 原生译文优先。
- 已匹配的 QQ 音乐/网易云音乐在线译文优先于 AI 补全；可以配置在线翻译的偏好来源，系统仍会根据覆盖率和匹配质量选择更合适的结果。
- AI 翻译只补充缺失内容；需要时也可以开启强制 AI 翻译覆盖已有译文。

### 4. 米系 AOD 也纳入同一套歌词体验

- 提供两套 AOD 歌词路径：锁屏 AOD 与经典 AOD。
- AOD 支持主句、伴唱、翻译、下一句歌词、对唱居中、暂停行为、下首歌曲预览和显示位置配置。
- 经典 AOD 还可以选择显示歌曲信息的样式：焦点通知样式或嵌入式文本样式。

## 运行模式

| 模式 | 适合人群 | 主要能力 | 依赖 |
| :--- | :--- | :--- | :--- |
| **Xposed / LSPosed 模式** | 已 Root、希望使用原生超级岛/系统界面注入 | HyperOS 超级岛、SystemUI 媒体卡片、Apple Music 深度适配、AOD 与系统白名单增强 | LSPosed v2.0+；HyperOS 3 相关功能需要对应 SystemUI |
| **通知歌词模式** | 未 Root 或不使用 Xposed 的设备 | 实时通知/焦点通知歌词、通知型灵动岛、基础媒体信息展示 | 通知发送权限、通知使用权；部分功能可选 Shizuku |

两种模式可以分别配置。Apple Music 内置 Provider 与体验优化功能均属于 Xposed 侧能力；通知模式则依赖播放器提供媒体信息或歌词通知数据。

## 安装与配置

### Xposed / LSPosed 模式

1. 从本项目的 [Releases](https://github.com/juren233/HyperLyrics-Enhanced/releases) 下载并安装 APK。
2. 在 LSPosed 中启用 HyperLyrics Enhanced，并勾选推荐作用域：
   - com.android.systemui
   - miui.systemui.plugin
   - com.apple.android.music
3. 打开应用主页的“超级岛歌词”，在“歌词设置”中选择歌词模式和歌词来源。
4. Apple Music 用户可以直接使用内置 Provider；其他播放器仍可能需要 Lyricon Central （词幕服务） 与对应 LyricProvider。
5. 根据提示重启 SystemUI 和音乐 App。

### 通知歌词模式

1. 打开应用主页的“通知型灵动岛歌词”。
2. 授予发送通知权限和通知使用权。
3. 在歌词白名单中添加需要显示歌词的音乐 App。
4. 选择实时通知或焦点通知，并按需配置图标、进度条、歌曲信息和点击行为。
5. 如果设备后台限制较严格，可以在页面内配置自启动、电池优化；焦点通知限制绕过功能需要运行中的 Shizuku。

具体系统版本、权限入口和通知表现可能因 HyperOS/Android 版本而变化，请以应用内“使用帮助”和设备实际行为为准。

## 兼容性说明

项目最低支持 Android 13（API 33），但不同功能依赖不同的系统界面实现：

| 功能 | 当前目标环境 | 备注 |
| :--- | :--- | :--- |
| HyperOS 超级岛歌词与 SystemUI 注入 | Android 15+ / HyperOS 3 | 需要 LSPosed v2.0+ 以及 miui.systemui.plugin |
| Apple Music 内置 Provider | Android 13+，配合 LSPosed | 作用于 com.apple.android.music；Apple Music 版本变化可能影响兼容性 |
| 锁屏 AOD / 经典 AOD | 米系 Android 13+ | 取决于设备的 AOD 实现和对应作用域 |
| 通知型灵动岛歌词 | Android 13+ | 需要通知发送权限、通知使用权和播放器数据 |
| 小米焦点通知增强 | HyperOS 2 / HyperOS 3 | 可能需要移除焦点通知白名单 |
| 下拉小窗白名单增强 | Android 16 / HyperOS 3.0.300+ | 依赖对应系统版本 |
| Android 实时通知 | Android 16；部分 HyperOS 3 / ColorOS 16 | 是否显示为系统级实时通知由系统决定 |

系统更新、SystemUI 插件更新和 Apple Music 更新都可能改变内部实现。遇到不适配时，建议先导出应用日志，并在 Issue 中附上设备型号、Android/HyperOS 版本、音乐 App 版本和复现步骤。

## 歌词来源与外部依赖

| 歌词源 | 说明 | 额外依赖 |
| :--- | :--- | :--- |
| **Lyricon** | Apple Music 内置 Provider 使用的统一歌词管线；其他播放器可以通过 Lyricon 接入 | Apple Music 无需额外 Provider；其他播放器通常需要 [Lyricon Central](https://github.com/tomakino/lyricon/releases/tag/core) 与 [LyricProvider](https://github.com/proify/LyricProvider/releases) |
| **SuperLyric** | 获取逐行或逐字歌词以及更细粒度的时间轴 | [SuperLyric](https://github.com/HChenX/SuperLyric)，并按其说明开启广播 |
| **LyricInfo** | 读取媒体会话中的 lyricinfo 数据 | [LyricInfo](https://github.com/limczhh/LyricInfo)，可选 |
| **在线兜底** | Apple Music 没有原生歌词时，从 QQ 音乐或网易云音乐匹配逐行歌词与译文 | 需要联网；可在歌词设置中关闭或调整优先来源 |
| **AI 翻译** | 对缺失译文进行补全，支持 OpenAI 兼容接口 | 需要用户自行提供 API Key、模型和接口地址 |

## 构建

本项目使用 Gradle 和 Kotlin/Compose。常用本地检查命令：

~~~bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
~~~

Release 构建需要在项目根目录提供 keystore.properties，或设置构建脚本读取的 Release 签名环境变量。APK 输出名称会包含版本名和 versionCode。

## 数据、权限与使用边界

- Xposed 模式需要 LSPosed 及相应作用域；通知模式不要求 Root。
- 在线歌词、在线译文和 AI 翻译仅在对应功能开启时使用网络。
- AI 翻译使用的 API Key、模型和服务地址由用户自行配置，第三方服务的隐私政策和费用由用户自行承担。
- 焦点通知、实时通知、AOD 和 SystemUI 注入均受设备厂商实现、系统版本、电池策略和后台限制影响。

## 致谢与许可证

本项目采用 **GNU General Public License v3.0** 开源协议发布。

感谢以下项目和贡献者：

- [HyperLyric](https://github.com/limczhh/HyperLyric)：本项目的基础项目，原有能力和上游贡献仍归原作者及其贡献者。
- [miuix-kmp](https://github.com/compose-miuix-ui/miuix)：HyperOS 风格 Compose 组件库。
- [lyricon](https://github.com/tomakino/lyricon)：歌词订阅、数据模型和部分歌词动画基础。
- [SuperLyric](https://github.com/HChenX/SuperLyric)
- [LyricInfo](https://github.com/limczhh/LyricInfo)
- [libxposed](https://github.com/libxposed/api)

请在提交 Issue 或 Pull Request 时保留相关上游项目、第三方库和原作者的归属信息。
