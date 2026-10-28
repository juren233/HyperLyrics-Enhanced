<p align="center">
  <strong>简体中文</strong> · <a href="README.en.md">English</a>
</p>

<p align="center">
  <img src="assets/hyperlyrics-app-icon-rounded.png" alt="HyperLyrics Enhanced 应用图标" width="112" />
</p>

<h1 align="center">HyperLyrics Enhanced</h1>

<p align="center">
  <strong>让歌词，融入每一处播放体验。</strong><br />
  Apple Music 深度优化 · HyperOS 超级岛 · 媒体卡片 · 息屏歌词
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-13%2B-3DDC84?style=flat-square" alt="最低 Android 13" />
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-64748B?style=flat-square" alt="arm64-v8a" />
  <a href="#compatibility"><img src="https://img.shields.io/badge/Apple%20Music-6.5.0%E2%80%936.5.3-FA243C?style=flat-square" alt="已支持 Apple Music 6.5.0–6.5.3" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-2563EB?style=flat-square" alt="GPL-3.0 许可证" /></a>
</p>

<p align="center">
  <a href="https://github.com/juren233/HyperLyrics-Enhanced/releases"><strong>下载与更新</strong></a> ·
  <a href="#preview">界面展示</a> ·
  <a href="#quick-start">快速开始</a> ·
  <a href="#compatibility">兼容性</a> ·
  <a href="https://github.com/juren233/HyperLyrics-Enhanced/issues">问题反馈</a>
</p>

---

HyperLyrics Enhanced 是为**小米 HyperOS 设备**打造的**超级岛 / 息屏歌词显示 LSPosed 模块**，同时提供**安卓通用的 Apple Music 体验优化**。内置 Lyricon Central （词幕服务）与 Apple Music Provider，可通过插件接入其他音乐App；不使用 LSPosed 的设备也可选择通知歌词模式。

本项目基于 [limczhh/HyperLyric](https://github.com/limczhh/HyperLyric) 二次开发，独立维护。

> [!IMPORTANT]
> 本项目不是 HyperLyric 的官方后续版本。仅在 HyperLyrics Enhanced 中出现的问题，请在**本仓库**反馈，错误的反馈会降低问题解决效率。

<a id="preview"></a>
## 界面展示

<p align="center">
  <a href="assets/screenshots/渐变封面样式超级岛.jpg"><img src="assets/screenshots/渐变封面样式超级岛.jpg" alt="超级岛: 渐变封面 · 封面渐变色" width="55%" /></a>
  <a href="assets/screenshots/AM歌词发音翻译弹窗.jpg"><img src="assets/screenshots/AM歌词发音翻译弹窗.jpg" alt="Apple Music: 歌词 · 发音 · 翻译来源切换" width="43%" /></a>
</p>
<p align="center">
  <sub><strong>超级岛</strong> · 渐变封面 · 封面渐变色</sub><br />
  <sub><strong>Apple Music</strong> · 歌词 · 发音 · 翻译来源切换</sub>
</p>

<p align="center">
  <a href="assets/screenshots/两种AOD示例.jpg"><img src="assets/screenshots/两种AOD示例.jpg" alt="息屏歌词: 左：锁屏 AOD · 右：经典 AOD" width="36%" /></a>
  <a href="assets/screenshots/平行窗口与Lyricon插件.jpg"><img src="assets/screenshots/平行窗口与Lyricon插件.jpg" alt="平行窗口与插件: 宽屏双栏 · Lyricon 插件管理" width="60%" /></a>
</p>
<p align="center">
  <sub><strong>息屏歌词</strong> · 左：锁屏 AOD · 右：经典 AOD</sub><br />
  <sub><strong>平行窗口与插件</strong> · 宽屏双栏 · Lyricon 插件管理</sub>
</p>

点击图片可查看原图。

<sub>截图来自不同设备与配置，实际效果可能因版本、系统和个人设置而异。</sub>

<a id="features"></a>
## 功能一览

### 相比原版，多了什么？

在 HyperLyric 的系统歌词展示基础上，Enhanced 更侧重于**减少额外模块、深入优化 Apple Music，以及补齐多来源与息屏歌词体验**。

| 方向 | 原仓库已有能力 / 使用方式 | Enhanced 新增或增强 |
| :--- | :--- | :--- |
| **更少的外部模块** | Lyricon 源需另装 Central 词幕服务模块与对应音乐 App 的 Lyricon Provider | **内置 Central 词幕服务模块与 Apple Music Provider**；其他音乐App的官方插件可在应用内添加、更新、启停和修复，无需逐个安装独立 APK 模块 |
| **Apple Music 应用内增强** | 通过歌词提供器向系统界面传递歌词 | 不止获取歌词，还能**在 Apple Music 内补充歌词、翻译与发音、切换来源**，并调整字体、模糊、地区化歌曲信息及播放体验 |
| **多来源歌词与翻译补全** | 已有网易云音乐、QQ 音乐在线取词路径，以及来源自带的翻译数据 | 扩展至**网易云、QQ、酷狗、酷我四源**，支持来源排序、按应用配置与自动择优；其中 Apple Music 默认原生内容优先，缺失部分再补全 |
| **息屏歌词** | 未提供独立的息屏歌词功能 | 提供**锁屏 AOD 与经典 AOD 两套息屏歌词方案**，支持主句、伴唱、翻译、下一句、对唱布局和暂停行为配置 |
| **按设备与需求设计界面** | 单一的功能开关与设备适配 | 增加**平板平行窗口 UI**，以及超级岛、息屏歌词、通知歌词和 Apple Music 优化的**功能入口管理**，让不同设备只保留需要的入口 |

> **需要注意**：以上对比仅用于说明本项目的功能侧重，不代表对原项目的全面评价或优劣结论。双方均在持续更新，功能差异可能随版本变化；实际可用性受设备、系统及音乐 App 版本影响，请以各项目的版本说明和实际使用情况为准。

### Apple Music，不止于歌词获取

- **内置歌词接入**：同步逐字歌词、伴唱、播放状态和歌曲信息，无需另装 Apple Music Provider 模块。
- **补充歌词、翻译与发音**：为缺少歌词的歌曲检索在线来源；在播放界面切换来源并查看匹配情况。默认优先保留 Apple 原生内容，缺失部分再通过三方在线源以及AI补全。
- **逐字歌词增强**：可通过 **LunaBeat TTML 歌词站**为部分逐行歌词提供逐字版本（属于实验性功能，取决于曲库收录与匹配结果）。
- **文字与显示**：繁体转简体、隐藏国语歌拼音、歌词模糊效果、跟随系统字体；部分字体粗细适配跟随 HyperOS。
- **歌曲信息与播放体验**：内容 UI 地区语言、歌曲信息地区化、恢复中日韩歌曲原地区名称、检索缓存、点击媒体通知直达播放页，以及动态提升 Dolby Atmos 外放音量。

> 地区语言调整只改变内容展示，不会解锁账号所在地区不可用的歌曲或服务。Apple Music 优化不限定小米品牌，但仍需 LSPosed 环境与适配的 Apple Music 版本。

### 从超级岛到息屏，保持同一首歌的歌词体验

| 展示位置 | 可配置能力 |
| :--- | :--- |
| **超级岛** | 逐字歌词、伴唱与对唱、翻译与发音；动态长度、动态上限调节、对唱固定长度、解除长度限制；封面、配色与音频律动 |
| **媒体卡片** | 分别配置通知中心与超级岛展开卡片，提供封面背景、模糊、柔光、动态流光等外观选项 |
| **锁屏 AOD / 经典 AOD** | 主句、伴唱、翻译、下一句、对唱布局、暂停行为与显示位置；经典 AOD 可选择焦点通知或嵌入式文本歌曲信息 |
| **通知型灵动岛** | 无 Root 歌词展示路径，支持通知样式、图标、进度条、歌曲信息、点击行为和播放器白名单 |

具体样式与可用选项受系统、设备和播放器数据限制；通知型灵动岛模式不会把其他设备变成原生 HyperOS 超级岛。

### 多家音乐App接入，多来源补全

- **Lyricon 已内置**：无需额外安装 Central 词幕服务模块；官方 Provider 插件可在应用内添加、更新、启停和修复，也保留独立 Lyricon Provider 模块接入方式。
- **官方插件适配广**：网易云音乐、QQ 音乐（含HD）、小米音乐、酷狗音乐（含概念版）、酷我音乐、Spotify、椒盐音乐、汽水音乐。（实际可添加插件以应用内目录为准，不同播放器提供的数据能力并不完全一致。）
- **在线歌词与翻译**：网易云音乐、QQ 音乐、酷狗音乐、酷我音乐四家来源，支持启停、排序、按应用配置及自动选择更合适的匹配结果。
- **可选 AI 翻译**：通过用户配置的 OpenAI 兼容接口补全译文；也可显式开启“强制 AI 翻译”覆盖已有译文。需要自行提供服务地址、模型和 API Key。
- **其他接入方式**：仍可选用 [SuperLyric](https://github.com/HChenX/SuperLyric) 或 [LyricInfo](https://github.com/limczhh/LyricInfo)，需按各自说明安装和配置。

### 按设备、按习惯配置

**功能开关**可管理超级岛、息屏歌词、通知型灵动岛和 Apple Music 优化入口；宽屏设备可开启**平行窗口 UI**。另有主题与配色、隐藏应用图标、系统设置入口、配置备份与恢复、日志查看与导出等应用设置。

<a id="quick-start"></a>
## 快速开始

先从 [Releases](https://github.com/juren233/HyperLyrics-Enhanced/releases) 下载 APK。日常使用优先选择正式版；Beta / Canary 为预发布版本，使用前阅读对应更新说明。

### 1 · 选择你的使用方式

| 你的需求 | 使用方式 | 前提 |
| :--- | :--- | :--- |
| 在 HyperOS 超级岛、媒体卡片或 AOD 显示歌词 | **LSPosed 模式** | LSPosed v2.0+、相应作用域及兼容的系统实现 |
| 只优化 Apple Music，不需要米系系统功能 | **LSPosed 模式** | LSPosed、适配的 Apple Music；可关闭不需要的米系入口 |
| 不使用 Root / LSPosed，只需要通知歌词 | **通知模式** | 通知权限、通知使用权和可用的播放器数据 |

两种展示路径可分别配置。通知模式不提供 Apple Music Hook 或官方 Provider 注入能力。

### 2 · LSPosed 模式配置

1. 安装应用，在 **LSPosed** 中启用 HyperLyrics Enhanced，按所需功能勾选作用域。
2. 在“**歌词设置**”选择歌词源。Apple Music 及已适配的音乐App推荐使用 **Lyricon**。
3. 配置播放器接入：
   - **Apple Music**：开箱即用，无需添加额外插件与模块。
   - **其他播放器**：进入“**Lyricon配置**”，添加并启用对应插件，按提示核对作用域。
4. 按需打开“**米系超级岛歌词**”“**米系息屏歌词**”或“**Apple Music体验优化**”，再调整对应页面中的选项。
5. 按应用提示重启系统界面和相关音乐 App，使模块与插件生效。

<details>
<summary><strong>展开查看：常用 LSPosed 作用域</strong></summary>

只勾选你需要的组件和播放器；以模块推荐作用域与应用内提示为准。

| 功能 / 播放器 | 包名 |
| :--- | :--- |
| HyperOS 系统界面及插件 | `com.android.systemui`、`miui.systemui.plugin` |
| Apple Music | `com.apple.android.music` |
| 网易云音乐 | `com.netease.cloudmusic`、`com.hihonor.cloudmusic` |
| QQ 音乐（含HD、小米音乐） | `com.tencent.qqmusic`、`com.tencent.qqmusicpad`、`com.miui.player` |
| 酷狗音乐（含概念版） | `com.kugou.android`、`com.kugou.android.lite` |
| 酷我音乐 | `cn.kuwo.player` |
| Spotify | `com.spotify.music` |
| 椒盐音乐 | `com.salt.music` |
| 系统设置中的应用入口（可选） | `com.android.settings` |

**汽水音乐是例外**：当前官方插件通过系统媒体路径工作，不需要勾选汽水音乐 App 作用域；仍需配置其依赖的系统侧模块环境。它不提供下一首歌曲预览。

只使用 Apple Music 优化时，不必为了米系功能额外勾选系统组件。“设置”作用域也仅在需要系统设置入口时使用。

</details>

### 3 · 通知模式配置

1. 打开“**通知型灵动岛歌词**”，授予发送通知权限和通知使用权。
2. 在歌词白名单中加入目标音乐 App，选择适合播放器的歌词来源。
3. 按系统能力选择通知样式，再配置图标、进度条、歌曲信息与点击行为。
4. 如有后台限制，按页面提示设置自启动和电池优化；使用 Shizuku 绕过焦点通知限制时，需要先启动并授权 Shizuku。

> [!TIP]
> 找不到功能入口？前往“**应用设置 → 功能开关**”检查。米系入口会按设备品牌设置初始状态，Apple Music 入口会参考应用是否已安装。手动开启入口不等于设备获得了对应系统能力。

<a id="compatibility"></a>
## 兼容性与使用边界

**应用安装基线：Android 13（API 33）及以上，`arm64-v8a`（64位ARM架构）。** 系统增强功能需要额外满足以下条件。

| 功能 | 环境与限制 |
| :--- | :--- |
| 超级岛与媒体卡片增强 | 面向 HyperOS 3 / 4 与 LSPosed v2.0+；依赖具体 SystemUI 及系统插件实现 |
| Apple Music 深度适配 | Android 13+ 与 LSPosed；当前代码包含 Apple Music **6.5.0-6.5.3** 等版本的适配配置，不能据此保证任意版本可用 |
| 官方 Provider 插件 | 需要对应App、插件及作用域；可用版本和能力以插件目录及更新说明为准 |
| 锁屏 AOD / 经典 AOD | 米系设备及兼容的 AOD 实现；不同系统的呈现方式可能不同 |
| 通知歌词 | Android 13+；依赖权限、播放器输出及后台存活情况 |
| 焦点通知 / Android 实时通知 | 由系统决定实际呈现；Android 实时通知需要 Android 16+ 及厂商支持 |
| 下拉小窗白名单增强 | 面向 Android 16 / HyperOS 3.0.300+ 对应实现；系统更新后需重新确认兼容性 |

- **系统版本号不是完整的兼容性保证。** SystemUI 插件、ROM 分支、音乐 App 更新都可能改变内部实现；不要同时启用多个模块的同类增强功能。
- **在线匹配可能缺失或错位。** 同名歌曲、现场版、重制版、翻唱及不同剪辑可能使用不同时间轴；可尝试切换来源并反馈具体歌曲。
- **网络与数据由使用的功能决定。** 插件下载、更新检查、在线歌词和翻译等需要联网；AI 翻译会向所配置服务发送待翻译内容，请自行确认其数据政策与费用，不要公开 API Key。
- **地区与账号权限不会被绕过。** Apple Music 内容 UI 地区语言调整不改变订阅或曲库授权。

<a id="faq"></a>
## 常见问题

<details>
<summary><strong>还需要安装 Lyricon Central （词幕服务模块）或独立 Apple Music Provider 模块吗？</strong></summary>

不需要。两者的对应能力已内置。其他音乐App优先从“Lyricon配置”添加官方插件；目录未覆盖的音乐App仍可尝试兼容的独立 Lyricon Provider 模块，无需再装独立 Central 词幕服务模块。选择 SuperLyric 或 LyricInfo 时，则需配置相应外部模块。

</details>

<details>
<summary><strong>插件装好了，为什么还没有歌词？</strong></summary>

依次检查模块是否启用、插件是否启用、作用域是否正确、歌词源是否选对，以及系统界面与音乐 App 是否已按提示重启。再确认该歌曲本身是否有可用歌词、播放器版本是否在适配范围内。通知模式还需要检查权限、白名单和后台限制。

</details>

<details>
<summary><strong>非小米设备或无 Root 设备可以用吗？</strong></summary>

可以使用适合自身环境的功能：非小米设备可在兼容的 LSPosed 环境中使用 Apple Music 优化；不使用 Root / LSPosed 的设备可尝试通知歌词。HyperOS 超级岛与 AOD 息屏歌词并不支持在其他系统上使用。

</details>

<details>
<summary><strong>关闭功能入口只是把页面藏起来吗？</strong></summary>

不是。相关入口开关也会停用其管理的功能，并暂存原开关状态，在重新开启入口时恢复。只想微调显示效果时，请进入对应功能页面修改具体选项。

</details>

<details>
<summary><strong>浏览器、PWA 等没有歌词的播放器，怎么用在线源取词？</strong></summary>

进入「歌词设置 → 三方在线源配置」，在「启用App」卡片底部点「更多App」，搜索并勾选需要的应用，包含没有桌面入口的 WebAPK。只对已勾选的应用取词，默认不勾选任何应用，此时不会查询。主源没有歌词时，按该页「平台来源」的顺序取词，主源歌词到达后优先使用主源，歌词源可继续选 Lyricon。

播放器需提供可信的歌曲名、歌手和时长；在线候选的标题、歌手、版本标记（如现场、翻唱）也要一致，时长相差小于 1.5 秒，没有对应版本时可能无词。不确定该勾选哪个应用时，播放中执行 `adb shell dumpsys media_session`，找到当前会话的 `package`（PWA 可能使用独立 WebAPK 包名，也可能使用浏览器包名），在「更多App」中核对每个应用下方显示的包名。

</details>

### 提交反馈

请先查看 [已有 Issues](https://github.com/juren233/HyperLyrics-Enhanced/issues)，再提交可复现的问题。建议附上：

- **环境**：设备型号、Android / HyperOS 版本、LSPosed 版本。
- **版本**：HyperLyrics Enhanced 版本名与构建号、音乐 App 版本、Provider 插件版本（如适用）。
- **路径**：使用的模式、歌词源、相关开关与作用域。
- **复现**：歌曲信息、操作步骤、预期结果、实际结果及发生时间。
- **日志**：复现后从应用设置中导出完整调试日志；如有帮助，可附截图或录屏。上传前检查并移除凭据、账号信息等敏感内容。

<a id="development"></a>
## 开发与构建

<details>
<summary><strong>展开查看：环境、验证命令与签名要求</strong></summary>

项目使用 Kotlin / Jetpack Compose、Miuix 与 Gradle Wrapper。当前构建配置为 **compileSdk / targetSdk 37**，CI 使用 **JDK 21**；请准备相应 Android SDK，并配置 SDK 路径。

本地单元测试，不生成 APK：

```bash
bash ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest
```

以下命令为二选一示例，每次执行前均需完成上述版本号调整：

```bash
# Debug APK
bash ./gradlew --no-daemon --max-workers=2 :app:assembleDebug

# 或：Release APK
bash ./gradlew --no-daemon --max-workers=2 :app:assembleRelease
```

签名通过项目根目录的 `keystore.properties` 或以下环境变量配置，环境变量优先：

| `keystore.properties` 属性 | 环境变量 |
| :--- | :--- |
| `storeFile` | `RELEASE_STORE_FILE` |
| `storePassword` | `RELEASE_STORE_PASSWORD` |
| `keyAlias` | `RELEASE_KEY_ALIAS` |
| `keyPassword` | `RELEASE_KEY_PASSWORD` |

配置完整后，Debug 和 Release 都会使用该签名。用于覆盖已安装正式包的诊断 Debug APK 必须使用相同项目签名，不可退回默认 debug keystore；没有匹配密钥的本地构建不能作为官方包的直接覆盖更新。

产物位于 `app/build/outputs/apk/`，文件名包含版本名与 `versionCode`。请核对产物元数据中的构建号；签名文件、密码和 API Key 不应提交到仓库。

</details>

## 致谢与许可证

本项目以 [GNU General Public License v3.0](LICENSE) 发布。原有代码与第三方实现的版权、许可证和署名归各自权利人；二次开发不改变其归属。

感谢以下项目及其贡献者：

- [HyperLyric](https://github.com/limczhh/HyperLyric) — 本项目的上游基础。
- [Miuix](https://github.com/compose-miuix-ui/miuix) — HyperOS 风格的 Compose 组件库。
- [Lyricon](https://github.com/tomakino/lyricon) — 歌词订阅、数据模型与部分歌词动画基础。
- [SuperLyric](https://github.com/HChenX/SuperLyric) · [LyricInfo](https://github.com/limczhh/LyricInfo) — 可选歌词接入方案。
- [libxposed](https://github.com/libxposed/api) — Xposed API。

---

<p align="center">
  <a href="https://github.com/juren233/HyperLyrics-Enhanced/releases">下载与更新</a> ·
  <a href="https://github.com/juren233/HyperLyrics-Enhanced/issues">反馈问题</a> ·
  <a href="#preview">返回界面展示</a>
</p>
