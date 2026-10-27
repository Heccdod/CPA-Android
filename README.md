# CPA-Android

个人自用 Android 反向代理：把 [router-for-me/CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) 的 Go 内核
经 `gomobile` 打进前台服务，在本机 `127.0.0.1:8317` 暴露 OpenAI / Anthropic / Gemini 兼容的流式 API。

配套文档：[CHANGELOG.md](CHANGELOG.md)（版本变更）、[UPSTREAM_ISSUES.md](UPSTREAM_ISSUES.md)
（上游三个问题的根因与补丁，补丁本体是 [upstream-patches.diff](upstream-patches.diff)）。

## 终态交付契约与验证方式

| 契约 | 物证 | 验证命令 |
| :--- | :--- | :--- |
| A 桥接产物 | `app/libs/cliproxy.aar`（62 MB，含 `jni/arm64-v8a/libgojni.so` + `classes.jar` → `me/routerfor/cliproxy/cliproxymobile/Cliproxymobile.class`） | `unzip -l app/libs/cliproxy.aar` |
| B 流式与防风控 | `go-bridge/cliproxymobile/{mobile.go,bridge_test.go}` + 内置 v8 `config.yaml` 模板 | `cd go-bridge && go test -v ./cliproxymobile` |
| C 宿主保活 / OAuth | `ProxyForegroundService.kt`、`MainActivity.kt`、`PanelActivity.kt`、`SettingsActivity.kt`、`HotSoLoader.kt`、`OauthLauncher.kt` | `aapt dump badging app/build/outputs/apk/debug/app-debug.apk` |
| D 双通道热更 | `UpdateManager.kt`、`rules/config.yaml`、`.github/workflows/auto-sync-upstream.yml` | 见下 |
| E 物理产物 | `app/build/outputs/apk/debug/app-debug.apk`（37 MB，含 `lib/arm64-v8a/libgojni.so`） | `unzip -l ...app-debug.apk \| grep libgojni.so` |

## 目录

```text
CPA-Android/
├── upstream-cliproxy/     # 克隆的 CLIProxyAPI 源码（module github.com/router-for-me/CLIProxyAPI/v8）
├── go-bridge/             # gomobile 桥接模块，replace 指向 ../upstream-cliproxy
│   └── cliproxymobile/
│       ├── mobile.go          # 仅导出 string / bool 单返回值
│       ├── bridge_test.go     # 启动 / 端口 / 热重载 / 快速设置 / 停止
│       └── thinking_test.go   # 思维链 payload 规则：成对写入、只删自己那条、catalog 键共存
├── rules/config.yaml      # L2 远程规则下发的源文件
├── scripts/
│   ├── check16k.py            # ELF PT_LOAD p_align >= 16384 回归检查
│   ├── device-smoke.sh        # 真机端到端断言
│   ├── tap.py                 # 按文本/content-desc 找 bounds 再点，不写死坐标
│   ├── ui_text.py             # uiautomator dump 的文本抽取
│   └── notif_expand.py        # 展开通知栏，用于核对状态栏额度正文
├── app/
│   ├── libs/cliproxy.aar  # gomobile bind 产物
│   ├── src/main/assets/models.json  # 修正后的模型目录：APK 资产，启动时复制到 filesDir，由 models.catalog 指向
│   └── src/main/java/me/routerfor/cpa/
│       ├── MainActivity.kt          # 首页：刷新 + 主题 + 齿轮三个图标按钮、状态卡、提供商聚合卡（含按族分组的额度）、四颗快捷按钮
│       ├── SettingsActivity.kt      # 设置：外观 / 网络 / 引擎 / 更新 / 系统 / 关于
│       ├── PanelActivity.kt         # 全屏管理面板（返回直接回主界面）
│       ├── Palette.kt               # 三套调色板 + ThemeManager（四态主题、系统栏上色）
│       ├── UiKit.kt                 # 手绘卡片 / 按钮 / 分隔线 / ClassicSwitch 开关
│       ├── Provider.kt              # 提供商枚举（id 对齐上游 provider 参数）+ 品牌图标
│       ├── PanelWebView.kt          # 面板 WebView：预登录 + 净化 + 挂载轮询 + Custom Tabs
│       ├── PanelDocument.kt         # /management.html 的取回与重写（净化 + 皮肤 + 垫片）
│       ├── PanelSanitizer.kt        # 剥离中转站推广文案
│       ├── PanelSkin.kt             # 隐藏桌面灰渐变 / 去阴影 / 覆盖 CSS 变量与主题种子
│       ├── PanelTheme.kt            # 把 App palette 映射到面板的 theme store
│       ├── PanelPolyfill.kt         # ES2022 垫片（老 WebView 白屏修复）
│       ├── PanelJs.kt               # 预置管理面板 localStorage 凭据
│       ├── ProxyForegroundService.kt# 常驻通知 / WakeLock / 引擎启停
│       ├── ProxyActionReceiver.kt   # 通知动作路由
│       ├── HotSoLoader.kt           # 热更开关 + 私有目录 libgojni.so 优先加载
│       ├── UpdateManager.kt         # L2 规则热同步 + L3 GitHub Release(.so/.apk)
│       ├── AppPrefs.kt              # 设置持久化（热更新默认关闭）
│       ├── Credentials.kt           # 经 /v8/management/credentials 读写账号与启停
│       ├── OauthLauncher.kt         # 走 /v8/management/oauth/auth-url 取授权链接
│       ├── Quota.kt                 # 各家额度接口的代理调用与解析，按模型族把窗口分组（byFamily）
│       ├── QuotaNotifier.kt         # 状态栏通知里的 5h 额度快照
│       └── WebBridgeCompat.kt       # gomobile 绑定的安全包装
└── .github/workflows/auto-sync-upstream.yml
```

## 界面结构（2.0 重做）

旧版是一个 WebView 顶一条控制栏，所有东西挤在一起。2.0 拆成「首页看状态、设置改配置、面板干活」：

| 页面 | 内容 |
| :--- | :--- |
| 首页 `MainActivity` | 右上角三个图标按钮：刷新（全局，同时让每个账号的独立刷新按钮进入刷新态）、主题、齿轮。状态卡：运行绿点 / 服务地址（补了 `/v1` 后缀，即 `127.0.0.1:8317/v1`，客户端直接粘贴就能用）/ API 密钥点按复制 / 内核版本 + 热更新状态。AI 提供商卡：按提供商分组聚合所有账号，每组一个整组开关、每账号一个开关（走 `PATCH /v8/management/credentials/status`），账号下方是按模型族分组的额度块 + 独立刷新按钮。快捷操作四按钮同形（管理面板 / 快捷添加 / 重启网关 / 设置）。 |
| 设置 `SettingsActivity` | 外观：跟随系统（半太阳半月亮专属图标）/ 日间 / 黑夜 / 羊皮纸四选一（当前项打勾）。网络：上游代理（`socks5://…` 或「直连」）、允许局域网访问（经典圆形开关）。引擎：敏感词混淆、编辑 `config.yaml`（等宽字体，保存后热重载）、**显示思维链**（经典圆形开关，默认开启）。更新：**状态栏显示 5h 额度**（经典圆形开关，默认关闭）、内核热更新开关、检查更新、同步远程规则。系统：忽略电池优化、重启网关。关于：版本 / 架构 / 热更新状态。 |
| 面板 `PanelActivity` | 全屏 `/management.html`，预置登录态直接进；供应商 OAuth 与 `localhost:51121` 回调交给 Chrome Custom Tabs，全程零粘贴。返回箭头与系统返回键都直接回主界面，不走 SPA 历史。 |

全中文，无英文残留。界面不再用 M3 的默认配色与阴影，改为手写视图 + `UiKit` 统一绘制：
卡片、分隔线、按钮、开关全部取自 `Palette` 的三套 ARGB 调色板。状态栏与导航栏由
`ThemeManager.applySystemBars()` 填成当前 palette 的 surface 色（透明栏会露出主题白底），
图标明暗跟随 `lightStatusBar`。

`ClassicSwitch` 的轨道尺寸从 52×32 一路收到 **34×21**（`TRACK_WIDTH_DP = 34f`），
但视图本身靠 padding 撑到约 46×33 —— 轨道要小、点击区不能小，否则一排开关点起来全是
「差一点点」，手指落在缝里就变成滚动了。

### 三套主题与面板同步

`Palette.Mode` 是 `SYSTEM / DAY / NIGHT / PAPER` 四态，实际解析成三种 palette：
跟随系统时读 `uiMode` 决定 day 还是 night，`onConfigurationChanged` 里重建界面。

两处容易踩的细节：

- **切主题不弹 toast**。首页右上角的主题按钮点一下就 `recreate()`，整屏配色立刻变，
  再叠一条「已切换」的提示纯属噪音，已删掉。
- **从设置页改主题后主页必须重建，而且不能只比配色**。日间 → 跟随系统（此时系统是浅色）
  解析出的 palette 完全一样，只比较颜色就发现不了变化。`SettingsActivity` 因此比较的是
  `Palette.Mode`，并在 `onBackPressed` 里 `setResult(RESULT_THEME_CHANGED)`；
  `MainActivity.onActivityResult` 收到后 `recreate()`。
- 「跟随系统」有专属的半太阳半月亮图标（`ic_theme_auto`），不再借用日间/黑夜图标——
  否则「跟随系统且系统为浅色」和「明确选了日间」在按钮上长得一模一样。

难点在管理面板：它是一个独立 SPA，自带 zustand theme store（`localStorage['cli-proxy-theme']`，
明文 JSON，未混淆），只有 `auto / white / light / dark` 四种，**没有羊皮纸**，而且主色被硬编码成灰色
`#8b8680`。所以只写它的 store 不够——羊皮纸下主页米黄、面板纯白。做法是两层：

1. `PanelTheme` 在 `<head>` 开头注入脚本写 `cli-proxy-theme`，让面板的 store 与 App 逻辑一致
   （night → `dark`，paper → `white`，其余 → `light`），避免它按系统主题乱选；
2. `PanelSkin.paletteStyle()` 直接覆盖它的 CSS 自定义属性（`--bg-primary`、`--text-primary`、
   `--border-color`、`--primary-color` 等 20 多个），选择器写成
   `html,html[data-theme],html[data-theme=dark],…` + `!important`，压过它自己的 `:root` 与
   `[data-theme=…]` 规则。

两层都在文档里，不是运行时 `evaluateJavascript` 注 style —— 后者对这类异步挂载 + 重渲染的 SPA 不可靠。
真机日志会打印解析出的 palette：

```
I CpaPanel: panel raw=3160171 clean=3159826 patched=3163076 sponsor=false theme=PAPER
```

品牌图标（antigravity / claude / codex / gemini / kimi / xai）直接从面板 bundle 里的 base64 SVG 提取成
Android VectorDrawable。两个坑：`antigravity` 的源 SVG **没有 viewBox**（只有 `width=64 height=59`），
按 24×24 处理会把图形裁掉大半只剩右上角——要按 width/height 建视口；它的 24 条路径每条带
`transform='translate(x,y)'`，转换器必须把位移写成 `<group android:translateX/Y>`，否则所有形状叠在原点。
另外 Android 的 `PathParser` 不认 SVG 的紧贴弧标志写法（`a2.97 2.97 0 01-.104` 里 `01` 会被读成一个数字 1），
解析器必须按「flag 位置只吃单字符 0/1」处理。

### 面板净化：为什么不是在文件上改

上游面板是 3.35 MB 单文件内联发布，里面带中转站推广（`apikey.fan` 的注册链接、`APIKEY.FUN` 的导航文案）。
但它**每次引擎启动都会从上游 Release 重新下载**，所以在磁盘上打补丁活不过一次启动。改成 `PanelDocument`
在 `shouldInterceptRequest` 里取回原文、先 `PanelSanitizer.strip()` 再 `PanelPolyfill.inject()`，
WebView 永远看不到未净化版本（推广串在模块脚本内部，只改 DOM 是改不掉的）。真机日志：

```
I CpaPanel: panel raw=3160171 clean=3159826 patched=3162391 sponsor=false
```

## 额度显示

内核没有现成的「还剩多少额度」接口：`/v8/management/credentials` 里的 `quota.signals` 只是上一次真实请求带回来的响应头水位，新账号是空的。App 的做法是**代理请求各家的官方额度接口**，走的正是 `POST /v8/management/requests/api-call`：内核用选定凭据的 access token 替换请求头里的字面量 `Bearer $TOKEN$`（必要时先刷新），所以 App 自己从不碰 token。

各家返回格式不同，解析按 provider 分开：

| provider | 接口 | 取什么 |
| :--- | :--- | :--- |
| antigravity | `POST v1internal:retrieveUserQuotaSummary`（三个 host 依次回退） | `groups[].buckets[].remainingFraction` |
| claude | `GET https://api.anthropic.com/api/oauth/usage` | `five_hour` / `seven_day` 等的 `utilization` |
| codex | `GET https://chatgpt.com/backend-api/wham/usage` | `rate_limit.primary/secondary.used_percent` |
| xai / kimi | 各自的 usage 接口 | `used_percent` |
| 其他 | — | 显示「该提供商不提供额度查询」，不算错误 |

**踩过的坑**：antigravity 的额度接口按 `User-Agent` 认客户端，少带这个头会返回 403「You do not have a valid license of this product」——看起来像账号没权限，其实只是少一个 header。用 curl 手测时 UA 是 curl 默认值所以能通，App 里就得显式带上 `antigravity/hub/2.23.0 darwin/arm64`。

### 按模型族分组，一族两条进度条

antigravity 会把同一份额度按模型族各报一遍：`Gemini Models` 和 `Claude and GPT models`
各有一对 5 小时 / 每周窗口。这两对是**两笔独立的预算**（Gemini 见底时 Claude 可能还剩满），
所以不能折成一个数字。`Quota.byFamily()` 按族名归组，首页每个账号下方就变成：

```text
Gemini
  ▓▓▓▓▓▓▓▓▓▓▓▓░░  5 小时额度 余 99% · 15:43 重置
  ▓▓▓▓▓▓▓▓▓▓▓▓░░  每周额度   余 98% · 10月31日 14:12 重置
Claude
  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓  5 小时额度 余 100% · 18:51 重置
  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓  每周额度   余 100% · 10月31日 18:51 重置
```

几个刻意的取舍：

- **一条窗口一条进度条**，族名作为小标题（`friendlyGroup()` 把 `Gemini Models` 缩成 `Gemini`，
  `Claude and GPT models` 缩成 `Claude`）。族内固定按 5 小时、再每周排序，不吃接口返回顺序。
- **进度条画的是「剩余」不是「已用」**（`UiKit.QuotaBar` 取 `100 - used`）。一根满绿条一眼就是
  「还够用」，画已用量则反过来要脑内换算。颜色按剩余量分档：≥50% 绿、≥20% 黄、其余红。
  这里踩过一个语义坑：`usedPercent == null`（没有数据）和 `== 0`（一点没用）曾被同一个早退吃掉，
  结果是全新 Claude 账号（剩余 100%）只画出一条灰底。现在 null 只画槽、0 画满格，
  填充宽度还夹了一个下限，剩 1% 时不会缩成一个比圆角还小的点。
- **重置时间是绝对时刻，不是倒计时**。「还有 4 天」看着顺口，但决定「现在要不要开一个长任务」时
  没用；面板上显示的也是绝对时间。今天内的显示 `HH:mm`，其他日期显示 `M月d日 HH:mm`。
- 只报单一预算的 provider（claude / codex / xai / kimi）归成一组、族名留空，不额外画标题。

刷新机制分三层，都不轮询：

1. **自动**：进入首页且引擎已就绪时，只补拉还没有缓存值的账号。
2. **全局刷新**：右上角那颗按钮，重拉所有账号，同时让每个账号的独立刷新按钮一起进入刷新态。
3. **单账号刷新**：账号行里的按钮，只重问这一个凭据，不阻塞其他账号。

### 状态栏只显示 5 小时额度

设置 → 更新 → **「状态栏显示 5h 额度」**，默认关闭：把账号名放到锁屏上不该是默认行为。
开启后下拉展开网关通知，每个已启用账号两行——

```text
Antigravity · someone@example.com
   Gemini · 5h 额度 99% 15:43 重置
   Claude · 5h 额度 100% 18:51 重置
更新于 14:07
```

- 第一行是「提供商 · 邮箱」：同一提供商挂多个账号时，只写提供商根本分不清是谁的额度。
- 只取 5 小时窗口：通知是一眼扫的，每周额度变得慢，回 App 里看更合适。
- 余额行按模型族各一行并缩进，多族的 provider 才不会把第二行顶到和账号名齐平。
- 正文是**缓存快照**，由额度拉取完成或网关状态刷新触发重绘，不跑定时器——前台服务里的轮询
  正是 Android 会掐掉的东西，而一个悄悄停住的数字比一个带时间戳的旧数字更糟。关掉开关会立刻
  清掉缓存，避免留着一条过期正文。

## 思维链

antigravity 的 gemini 默认**思考但不回传思考内容**：上游确实产生了 thought token
（`usageMetadata.thoughtsTokenCount` 非零），可响应流里 `delta.reasoning_content` 是空的，
客户端自然什么都显示不出来。

### 触发条件是「两个键成对」

必须同时给到下面两个键，缺一不可：

```yaml
request.generationConfig.thinkingConfig.includeThoughts: true
request.generationConfig.thinkingConfig.thinkingLevel: high
```

- **只有 `includeThoughts` 不行**。实测：payload 里带着这个键、但没有 level/budget 时，
  上游照常思考（`thoughtsTokenCount` 不为零），`delta.reasoning_content` 却始终为空——
  思考 token 扣了，内容一个字节也拿不到。
- **只有 `thinkingLevel` 也不行**。有强度但没打开可见性，表现相同。
- **客户端传 `reasoning_effort` 确实能出内容**，因为翻译层会顺带把 `includeThoughts` 一起写进去
  （`reasoning_effort: medium` 翻出来的就是 `includeThoughts: true` + `thinkingLevel: "medium"`）。
  但要求每个客户端都去配 `reasoning_effort` 不现实（SillyTavern 之外未必有这个旋钮）。
- 而常见客户端的「只开可见性」写法（顶层 `include_thoughts`、`thinking: {type: enabled}`）
  在翻译成 antigravity 协议时就被丢掉了，根本到不了 payload——所以「让客户端自己传」这条路
  对多数客户端不成立，必须由服务端补齐成对的两个键。
- 实测结论：开关打开后，客户端**不传** `reasoning_effort` 也能拿到 `reasoning_content`。

### 为什么用 payload 规则，而不是改上游代码

做法是往 `config.yaml` 写一条 `requests.payload.override` 规则（`go-bridge/cliproxymobile/mobile.go`
的 `applyThinkingVisibility()`），由内核在请求出站前把这两个键塞进 payload：

```yaml
requests:
  payload:
    override:
      - models:
          - name: "*"
        params:
          generationConfig.thinkingConfig.includeThoughts: true
          generationConfig.thinkingConfig.thinkingLevel: high
```

选这条路而不是在 `normalizeAntigravityOpenAIThinkingConfig` 里写死默认值，理由有三条：

1. **可关**。思考内容本身计费，默认全开等于给每次请求加钱。开关关掉时规则被摘掉，
   `config.yaml` 里看得见，不存在「以为关了其实还在扣」。
2. **能热更**。规则是配置，改完引擎文件 watcher 毫秒级重载；改代码得重编 `libgojni.so`
   再走热更或重新安装。
3. **扛上游同步**。`.github/workflows/auto-sync-upstream.yml` 会定期对齐上游，改在 `internal/` 里的
   代码会在同步时冲突或丢失；payload 规则是上游的公开能力，同步多少次都还在。

只在开启时写这条规则，关闭时只删自己这一条（`removeThinkingRule()` 按 `includeThoughts`
这个 key 认领），用户自己加的 override 规则原样保留——有回归测试
（`go-bridge/cliproxymobile/thinking_test.go`）盯着这两点。

### 开关位置与默认值

设置 → 引擎 → **「显示思维链」**，经典圆形开关，**默认开启**
（`AppPrefs.thinkingVisible()` 读 `KEY_THINKING = "thinking-visible"`，缺省 `true`）。
拨动后 `applyQuickSettings()` 重写 `config.yaml` 并触发热重载，不需要重启网关。

## 模型目录

上游的静态模型表 `internal/registry/models/models.json` 里，antigravity 段只有
`gemini-3.8-flash-high` 一条；远程目录（`raw.githubusercontent.com/router-for-me/models`）也是同一份，
所以启动时的自动更新补不上 low / medium。模型本身是支持的（`gemini-3.8-flash-high(low)`
用 thinking 后缀寻址能 200），缺的只是目录条目。

修法是 App 自带一份修好的目录：`app/src/main/assets/models.json` 补了
`gemini-3.8-flash-low` 与 `gemini-3.8-flash-medium`，`ProxyForegroundService.installModelsCatalog()`
每次启动把它复制到 `filesDir/models.json`（覆盖旧副本，因为它不含用户状态），
`config.yaml` 里 `models.catalog` 指向该路径，于是远程目录再也覆盖不掉它。
Go 读不了 APK 里的 asset，所以复制这一步必须在 Kotlin 侧。

> 上游侧的同款修复也在 `upstream-patches.diff` 里（补 models.json 两条），
> 两处并存是刻意的：App 资产保证自己的 APK 一定对，补丁保证裸跑上游也对。

## 构建

```bash
# 1) Go 桥接层（需要 Go >= 上游 go.mod 声明的版本）
cd go-bridge
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
export ANDROID_HOME=/opt/android-sdk ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
gomobile init
go test -v ./cliproxymobile
gomobile bind -target=android/arm64 -androidapi 28 -javapkg=me.routerfor.cliproxy \
  -ldflags="-checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
  -o ../app/libs/cliproxy.aar ./cliproxymobile

# 2) APK
cd ..
printf "sdk.dir=$ANDROID_HOME\n" > local.properties
./gradlew assembleDebug
```

### 为什么 `-ldflags=-checklinkname=0`

`internal/client/codex/live` → `pion/ice` → `pion/transport/stdnet` → `github.com/wlynxg/anet`。
`anet` 用 `//go:linkname` 直接引用 `net.zoneCache` 与 `golang.org/x/net/internal/socket.zoneCache`，Go 1.23 起
的 linkname 校验器会拒绝该引用（`link: github.com/wlynxg/anet: invalid reference to net.zoneCache`），
只有编 `c-shared` 时才暴露。关闭该校验只影响这条 linkname，不改变运行语义（`anet` 在 Android 上走自己的
netlink 实现）。

## 运行

1. 首次启动 `CpaApp` 先经 `HotSoLoader` 装载内核（热更的 `.so` 优先），随后 `ProxyForegroundService`
   按顺序做三件事：`installModelsCatalog()` 把 `assets/models.json` 复制成 `filesDir/models.json`、
   经 `ensureDefaultConfig()` 写出 `filesDir/config.yaml` 并建 `filesDir/auths/`、最后 `startProxy(filesDir)`。
   顺序不能反——`config.yaml` 里的 `models.catalog` 指向那个 json，文件先落地才不会让引擎读到空目录。
2. Go 侧等待 `net.DialTimeout("tcp", "127.0.0.1:8317")` 成功后才返回，`WebView` 不会撞 `ERR_CONNECTION_REFUSED`。
3. 顶部「Add Antigravity」→ `GET /v8/management/oauth/auth-url?provider=antigravity&is_webui=1`
   → Chrome Custom Tab 打开授权页 → 浏览器重定向到 `http://localhost:51121/oauth-callback`
   → Go 内核自带的 forwarder 转给 `/antigravity/callback` → 写 `filesDir/auths/`。全程零复制粘贴。
4. 通知栏常驻显示监听地址，带「重启网关」「停止服务」两个动作。

## 更新策略（2.0 起默认全关）

热更新以前是默认开启的，等于把「应用会自己从网上换掉自己的内核」当成常态。2.0 改成默认关闭、显式开启：

- **默认状态**：`AppPrefs.hotUpdateEnabled()` 默认 `false`。`HotSoLoader.loadEngine()` 先读这个开关，
  关闭时直接 `clearStaged()` 清掉 `getDir("hotlibs")` 里已暂存的内核，然后 `System.loadLibrary("gojni")`
  加载 APK 自带的那份。真机 logcat 实锤：

  ```
  I CpaHotSo: hot update disabled, loaded packaged kernel libgojni.so
  ```

- **显式开启**：设置 → 更新 → 「内核热更新」开关（`MaterialSwitch`）。关闭时立刻清暂存；开启后才允许
  L3 落 .so 并注入。首页状态卡与设置「关于」都会显示当前状态，不会出现「以为在热更其实没更」。
- **主动检查**：设置 → 更新 → 「检查更新」，或首页齿轮进去点。开关关着也能查，查到 .so 会明确提示
  「发现新内核，但「内核热更新」已关闭，已跳过」，不会静默吞掉。
- **L2 规则热重载**：`UpdateManager.syncRules()` 拉取 `BuildConfig.RULES_URL`
  （`raw.githubusercontent.com/Heccdod/CPA-Android/main/rules/config.yaml`）→ 原子覆写
  `filesDir/config.yaml` → 引擎的文件 watcher 毫秒级热重载。同样是手动触发，不再自动跑。

  > 注意这条路是**整份覆盖**：远程规则里没有 `requests.payload.override`，也没有 `models.catalog`，
  > 同步一次就会把「显示思维链」的规则和自定义模型目录一起冲掉。同步完回设置页拨一下
  > 「显示思维链」（或重启网关）即可让 `applyThinkingVisibility()` / `ensureCatalogKey()` 把两者补回去。
  > 想让它们长期共存，得在 `rules/config.yaml` 里也写上——见 [rules/config.yaml](rules/config.yaml)。
- **L3 内核/整包热更**：`UpdateManager.checkForUpdates()` 读 `api.github.com/repos/Heccdod/CPA-Android/releases/latest`，
  比对 `tag_name` 与本地 `versionName`；`.so` 按 assets 的 `digest`（`sha256:...`）校验后落到
  `getDir("hotlibs")`；`.apk` 下载后经 `FileProvider` 拉起系统安装器覆盖安装。

> 注意 `digest` 只能证明「下载没坏」，证明不了「这是你发的」。仓库地址与小写 key 都在 `BuildConfig` 里，
> 想换源要重新编译；真正要防的是 GitHub 账号被盗后发一个恶意 Release——所以默认关闭是这道防线的第一层。

## 默认配置里预置的防风控项

- `oauth.providers.antigravity.signature-cache-enabled: true` —— 思考块签名缓存与校验。
- `oauth.providers.antigravity.antigravity-credits: true` —— 免费额度耗尽后回落到 Google One AI credits。
- `oauth.providers.antigravity.sensitive-words` —— 8 个触发 WAF 假 429 的字面量，由
  `ObfuscateSensitiveWords` 在请求出站前插入 U+200B。
- `requests.streaming.keepalive-seconds: 15` —— 长思考阶段每 15 秒发 SSE 心跳，避免移动网络 NAT 掐断。

## 运行时踩坑与修复（实测记录）

下面几条都是真机/模拟器上跑出来的，不是推测；前两条会直接让交付物在目标设备上失效。

### 1. Go 1.23+ linkname 校验锁死 gomobile bind

`internal/client/codex/live` → `pion/ice` → `pion/transport/v4/stdnet` → `github.com/wlynxg/anet`
（`interface_android.go:164/167`）用 `//go:linkname` 指向标准库私有符号 `net.zoneCache`，Go 1.23+ 在
`-buildmode=c-shared` 下判定为非法引用：

```
link: github.com/wlynxg/anet: invalid reference to net.zoneCache
```

修复：所有 `gomobile bind` 与 `go test` 统一加 `-ldflags="-checklinkname=0"`。

### 2. AAR 清单 minSdk 28 撞上宿主 minSdk 26

`gomobile -androidapi 28` 产出的 AAR 声明 `minSdkVersion 28`，与宿主 26 合并失败：

```
uses-sdk:minSdkVersion 26 cannot be smaller than version 28 declared in library [cliproxy.aar]
```

修复：`AndroidManifest.xml` 预置 `<uses-sdk tools:overrideLibrary="go.cliproxymobile.gojni" />`，
`compileSdk` 提到 34（AGP 8.5 编 Java 17 需 ≥30），`minSdk` 保持 26、`targetSdk` 保持 28（保留私有目录
`System.load()` 热更 .so 的权限）。

### 3. 管理面板白屏：老 WebView 缺 ES2022 API（本项目真实 bug）

模拟器自带 WebView 是 **Chromium 83.0.4103.106**，而面板 bundle 用到 `Object.hasOwn`、
`String.prototype.replaceAll`、`Array.prototype.at`、`crypto.randomUUID`。控制台实锤：

```
I CpaWeb: ERROR Uncaught TypeError: Object.hasOwn is not a function @157
I CpaWeb: dom "root=0 body=0"     ← #root 一个子节点都没有，纯白屏
```

面板走的是单文件内联发布（3.35 MB，无外部 JS 可替换），所以修复放在宿主侧：
`PanelPolyfill.kt` 提供 ES2022 垫片，`MainActivity.PanelClient.shouldInterceptRequest` 拦
`/management.html`、把垫片插到 `<head>` 之后（必然早于 SPA 模块脚本求值）再回给 WebView。
修复后同一台设备：

```
I CpaWeb: panel intercepted, html=3160171 patched=3162736
I CpaWeb: dom "root=2 body=1581"  ← 仪表盘渲染完成
```

对 minSdk 26 的机器这是必需品，不是模拟器特例。

**2.0 追加的一个误报**：`onPageFinished` 里只查一次 `#root.childNodes.length`，一旦为 0 就判定渲染失败。
但面板把 JS 全内联在同一个文档里，冷启动时模块脚本的求值可能明显晚于 `onPageFinished`（实测同一台机
`root=0` 后 ~2 s 才 `root=2`），于是「面板无法渲染」的提示被叠在已经渲染好的内容上。改成
`PanelWebView.awaitMount()`：每 250 ms 轮询一次，最多 60 次（15 s），真正挂上才回调 `onPanelReady`。

另外面板 bundle 自带 Cloudflare RUM 信标，会不断 POST `/cdn-cgi/rum`，打到本地引擎上是每几秒一条 404；
`shouldInterceptRequest` 里对 `/cdn-cgi/` 直接返回 204 掐掉。

### 4. 引擎异步启动导致面板永不加载

原来只在 `onResume` 一次性 `loadPanel()`，而 `StartProxy` 是异步的（等端口 listen 才返回），首帧必然
早于端口就绪，面板一次都没请求过。修复：`retries/MAX_PANEL_RETRIES=40`、`PANEL_RETRY_MS=700L`，
`refreshStatus()` 在 `webView.url == null` 且引擎未就绪时自我 `postDelayed`；`loadPanel` 在未 running 时
也排重试。

### 5. Android 17 拒绝加载：ELF 段只有 4 KB 对齐（真机实测）

装到 Redmi Note 12 Turbo（Android 17 / API 37）首次启动，系统直接弹"Android 应用兼容性"：

```
此应用不符合 16 KB 对齐要求。ELF 文件对齐检查失败。
以下库未进行 16 KB 对齐：
  • lib/arm64-v8a/libgojni.so：LOAD 区段未对齐
```

实测原 AAR 里四个 PT_LOAD 段的 `p_align` 全是 `0x1000`（4 KB）——Go/NDK 默认 arm64 链接就这样。
这台机器 `getconf PAGE_SIZE` 是 4096 所以还能跑，但 Android 17 起 16 KB 页设备是硬要求。

修复在**链接期**，不是打包期：

```bash
gomobile bind -target=android/arm64 -androidapi 28 \
  -javapkg=me.routerfor.cliproxy \
  -ldflags="-checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
  -o ../app/libs/cliproxy.aar ./cliproxymobile
```

修后 `p_align` 全变 `0x4000`。另外两个连带要注意的点：

- **`-javapkg` 不能漏**。重绑时只给 `-extldflags` 而漏了 `-javapkg=me.routerfor.cliproxy`，Classes 会从
  `me.routerfor.cliproxy.cliproxymobile` 掉回裸 `cliproxymobile`，Kotlin 侧立刻 `Unresolved reference: cliproxy`。
- **`extractNativeLibs=true` 时** .so 是解压到文件系统再 dlopen 的，所以 zip 对齐不影响加载，只有 ELF 段的
  `p_align` 要紧；`zipalign -P 16` 是另一条路，本项目用前者。

回归检查脚本（AAR 与 APK 都验）：

```bash
python3 scripts/check16k.py app/libs/cliproxy.aar app/build/outputs/apk/debug/app-debug.apk
# OK   app/libs/cliproxy.aar:jni/arm64-v8a/libgojni.so LOAD align=['0x4000', '0x4000', '0x4000', '0x4000']
```

CI 里已在 bind 后与 assemble 后各加一道 `scripts/check16k.py`，防止上游同步把这条退化掉。

## 设备侧验证（scripts/device-smoke.sh）

```
./gradlew assembleDebug -PwithEmulatorAbi=true   # 模拟器需要 x86_64
bash scripts/device-smoke.sh [serial]
```

最近一次结果（主力机 25060RK16C / Android 17 / API 37）：**30 passed, 0 failed**。
C 组覆盖前台服务 + WakeLock、/management.html 200、落盘配置、匿名 401 / 带 key 200、
auth-url 的 redirect_uri=localhost:51121、:51121 转发器 302 转内核消费回调、D1 改 keepalive
15 到 25 引擎 fsnotify 热重载；D2 组覆盖 2.0 的新契约：热更新默认关闭、关闭时无暂存内核、
loader 走内置内核分支、面板日志 sponsor=false、SPA 真正挂载、RUM 信标被掐、三个 Activity 均已注册。

D2 里有一处刻意绕开的坑：PanelActivity 是 exported="false"，系统会直接拒绝
am start -n .../.PanelActivity（SecurityException: not exported from uid）。所以脚本改成
uiautomator dump 找到「管理面板」按钮的 bounds、算出中心点、input tap 点进去 —— 和真人操作同一条路径。

OAuth 端到端的人机验证（点「添加账号」→ 授权页登录 → 凭据落盘）需要真实 Google 账号，
脚本只验证到「引擎起 51121 转发器 + 签发真实 consent URL + Custom Tabs 被拉起」这一步；
真机上已确认点「添加账号」后 mark.via/.CustomTab 被拉起、引擎日志出现
callback forwarder for antigravity listening on 0.0.0.0:51121。

## 构建参数备忘

```bash
# 真机（只打 arm64，产物约 37 MB）
./gradlew clean assembleDebug

# 模拟器（额外打 x86_64，产物约 68 MB）
./gradlew assembleDebug -PwithEmulatorAbi=true
```

## 真机实测

两台机器都跑过，都是 Android 17 / API 37 / arm64-v8a：

| 设备 | 用途 | 结果 |
| :--- | :--- | :--- |
| Redmi Note 12 Turbo（368b07b8，走无线 10.250.233.76:5555） | 第一轮验证 | 16 KB 对齐修复后兼容性弹窗消失，device-smoke.sh 20 passed |
| 25060RK16C（BU8XQGONNBQGFUHQ，USB） | 2.0 验证 | device-smoke.sh **30 passed, 0 failed** |

接 USB 设备踩的坑：WSL 的 adb server 会占着 5037，让 Windows 侧 adb.exe 起不来
（"could not read ok from ADB Server"）；先在 WSL 里 adb kill-server 清场，Windows 侧才能正常枚举。
无线那台反过来要先 adb tcpip 5555 再 adb connect <ip>:5555。

在 WSL2 下手机挂在 Windows 侧，WSL 自己的 adb 根本看不到设备；把 `ADB_SERVER_SOCKET` 指过去也没用
（跨 WSL/Windows 边界的 adb 握手会被 reset）。所以设备命令一律走 `scripts/adbw.sh`，它固定调用
`adb.exe -s <serial>`，用之前确认 5037 没被 WSL 的 adb 抢走：

```bash
pkill -f platform-tools/adb            # 放掉 5037
adb.exe start-server                   # Windows 侧重新拉起
scripts/adbw.sh shell getprop ro.product.model
```

界面截图在 docs/screens/：home-day.png / home-dark.png / home-paper.png / home-follow-system.png
（首页四个主题态）、home-quota.png（额度条与账号行）、home-refreshing.png（全局刷新态）、
settings-day.png / settings-quota.png（设置页与「状态栏显示 5h 额度」开关）、
panel-day.png / panel-dark.png / panel-paper.png（面板跟随 App 主题）、
quick-add.png（快捷添加的品牌图标）。

> 提醒：装新包前先 am force-stop 再截屏，否则 QQ 之类的悬浮窗和软键盘会压在截图上。

## 调试脚本：按文本定位，别用硬编码坐标

`scripts/tap.py "管理面板"` 会先 `uiautomator dump` 解析出该文本（或 content-desc）的 bounds，
再算中心点 `input tap`。原因很实际：首页在登录账号后会多出整张提供商卡，按钮整体下移；
设置页会滚动；一击点偏落到返回箭头就会把 App 退到桌面，之后所有点击都打在 launcher 上，
截图全是桌面，看起来像「App 挂了」。每次 tap 后用 `dumpsys window | grep mCurrentFocus`
确认焦点仍在自家 Activity，是这套脚本必须做的一步。

另外设备会自己进 Dozing（`mWakefulness=Dozing`）让 `screencap` 全黑，需先
`input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard`；下拉的通知栏会盖住首页，需
`cmd statusbar collapse`。
