# 工程细节与踩坑记录

这份文档记录的是**为什么这样做**：实现取舍、真机踩过的坑、以及每条修正的验证方式。
想先知道「这是什么、怎么用」，看 [README.md](../README.md)。
版本变更看 [CHANGELOG.md](../CHANGELOG.md)，上游问题看 [UPSTREAM_ISSUES.md](../UPSTREAM_ISSUES.md)。

---

## 界面结构（2.0 重做）

旧版是一个 WebView 顶一条控制栏，所有东西挤在一起。2.0 拆成「首页看状态、设置改配置、面板干活」：

| 页面 | 内容 |
| :--- | :--- |
| 首页 `MainActivity` | 右上角三个图标按钮：刷新（全局，同时让每个账号的独立刷新按钮进入刷新态）、主题、齿轮。状态卡：运行绿点 / 服务地址（补了 `/v1` 后缀，即 `127.0.0.1:8317/v1`，客户端直接粘贴就能用）/ API 密钥点按复制 / 内核版本 + 热更新状态。AI 提供商卡：按提供商分组聚合所有账号，每组一个整组开关、每账号一个开关（走 `PATCH /v8/management/credentials/status`），账号下方是按模型族分组的额度块 + 独立刷新按钮。卡片最高占屏幕 40%，账号多了在卡片内滚动，「AI 提供商」标题与 `n/m` 计数固定不动（`MaxHeightScrollView`），不溢出时不拦截拖拽。快捷操作四按钮同形（管理面板 / 快捷添加 / 重启网关 / 设置）。 |
| 设置 `SettingsActivity` | 外观：跟随系统（半太阳半月亮专属图标）/ 日间 / 黑夜 / 羊皮纸四选一（当前项打勾）。网络：上游代理（`socks5://…` 或「直连」）、允许局域网访问（经典圆形开关）。引擎：敏感词混淆、编辑 `config.yaml`（等宽字体，保存后热重载）、**思考预算**（三档单选：不思考 / 显示思维链 / 不写规则，默认不思考）。更新：**状态栏显示 5h 额度**（经典圆形开关，默认关闭）、内核热更新开关、检查更新、同步远程规则。系统：忽略电池优化、重启网关。关于：版本 / 架构 / 热更新状态。 |
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

上游面板是 3.2 MB 单文件内联发布，里面带中转站推广（`apikey.fan` 的注册链接、`APIKEY.FUN` 的
导航文案，以及同样带 `aff=` 的 `fenno.ai`）。但它**每次引擎启动都会从上游 Release 重新下载**，
所以在磁盘上打补丁活不过一次启动。改成 `PanelDocument` 在 `shouldInterceptRequest` 里取回原文、
先 `PanelSanitizer.strip()` 再 `PanelPolyfill.inject()`，WebView 永远看不到未净化版本
（推广串在模块脚本内部，只改 DOM 是改不掉的）。真机日志：

```
I CpaPanel: panel raw=3194020 clean=3193699 patched=3197897 sponsor=false theme=DAY
```

#### 净化规则只认域名，不认变量名

第一版按最小化标识符替换，比如「cS=``https://apikey.fan/register?aff=AKCPA``」这种整串字面量。上游下一次构建把
**每一个**名字都换了（`cS` 变成 `GS`），还并排加了第二个赞助商 `fenno.ai`，于是规则一条都匹配不上——
而 `hasSponsorContent` 当时只看 `apikey.fan` 与 `APIKEY`，净化失败也没有任何信号，页面照常渲染，
只是推广回来了。

现在的规则按「重建也改不掉的东西」分类：

| 类别 | 做法 |
| :--- | :--- |
| 赞助商域名 | 一条正则（`apikey.fan` 或 `fenno.ai` 的任意子域、任意路径）整段删掉 |
| 返利参数 | `[?&]aff=[A-Za-z0-9_-]+` 单独删（合法 provider 链接上的也删） |
| provider 数组 | 删的是「,``apikeyFun``」这样带反引号的完整成员，不会吃掉相邻逗号 |
| `/quick-start` 路由 | **不改路由，只改 brand**：`fixedBrand` 指向 `openaiCompatibility`，路由还活着但成了普通表单页 |
| 多语言文案 | 七种语言的 `APIKEY.FUN …` / `FennoAI` 逐条替换 |

不删路由是刻意的：它躺在一条最小化数组字面量里，逗号必须配平，删错一个括号整个面板白屏。

`hasSponsorContent` 现在也查 `aff=` 标记，所以「域名清了但返利参数漏了」不会再被当成干净。
配套 6 条 JVM 单元测试（`app/src/test/java/me/routerfor/cpa/PanelSanitizerTest.kt`），其中一份 fixture
用的就是**和当前 bundle 不同的变量名**——正是之前漏掉的那种情况。

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

### 一个键开可见性，强度不替调用方决定

开可见性只要一个键：

```yaml
request.generationConfig.thinkingConfig.includeThoughts: true
```

- 常见客户端的「只开可见性」写法（顶层 `include_thoughts`、`thinking: {type: enabled}）
  在翻译成 antigravity 协议时就被丢掉了，根本到不了 payload，所以这个键得由服务端补。
  补上之后，客户端**不传** `reasoning_effort` 也能拿到 `reasoning_content`。
- **强度不写死**。早先这里同时补了 `thinkingLevel: high`，理由是「不成对就看不到」。
  在当前上游上重测，这个理由不成立：只写 `includeThoughts` 一样能看到思考内容。
  写死 high 的代价是实打实的——同一 prompt 下思考 token 接近翻倍，正文反而更短；
  SillyTavern 默认 `max_tokens: 300` 时，思考吃掉 285–289，正文只剩 9–42 字，
  在客户端里看着就像被截断或者一直在生成。
- 调用方想指定强度就自己传 `reasoning_effort`（`reasoning_effort: medium` 会翻成
  `includeThoughts: true` + `thinkingLevel: "medium"`），这条规则不覆盖它。
  旧版本写进 `config.yaml` 的 `thinkingLevel: high` 会在引擎启动时被
  `migrateExistingConfig()` 摘掉；用户自己按模型设的强度规则不动。

### 为什么用 payload 规则，而不是改上游代码

做法是往 `config.yaml` 写一条 `requests.payload.override` 规则（`go-bridge/cliproxymobile/mobile.go`
的 `applyThinkingVisibility()`），由内核在请求出站前把可见性键塞进 payload：

```yaml
requests:
  payload:
    override:
      - models:
          - name: "*"
        params:
          generationConfig.thinkingConfig.includeThoughts: true
```

选这条路而不是在 `normalizeAntigravityOpenAIThinkingConfig` 里写死默认值，理由有三条：

1. **可关**。思考内容本身计费，默认全开等于给每次请求加钱。开关关掉时规则被摘掉，
   `config.yaml` 里看得见，不存在「以为关了其实还在扣」。
2. **能热更**。规则是配置，改完引擎文件 watcher 毫秒级重载；改代码得重编 `libgojni.so`
   再走热更或重新安装。
3. **扛上游同步**。`.github/workflows/auto-sync-upstream.yml` 会定期对齐上游，改在 `internal/` 里的
   代码会在同步时冲突或丢失；payload 规则是上游的公开能力，同步多少次都还在。
   （真正落在 `internal/` 下的那几个修正在 CI 里是靠 `upstream-patches.diff` 补回来的，
   见下文「上游补丁」。）

只在开启时写这条规则，关闭时只删自己这一条（`removeThinkingRule()` 按 `includeThoughts`
这个 key 认领），用户自己加的 override 规则原样保留——有回归测试
（`go-bridge/cliproxymobile/thinking_test.go`）盯着这两点。

### 开关位置与默认值

设置 → 引擎 → **「思考预算」**，三档单选，**默认「不思考」**
（`AppPrefs.thinkingMode()` 读 `KEY_THINKING`，缺省 `THINKING_COMPACT`）。
选完由 `applyQuickSettings()` 重写 `config.yaml` 并触发热重载，不需要重启网关。

| 档位 | 写入的规则 | 300 token 预算下的正文 |
| :--- | :--- | ---: |
| 不思考（默认） | `thinkingBudget: 0` | 167–208 字 |
| 显示思维链 | `includeThoughts: true` + `thinkingLevel: low` | 195–206 字（不花思考 token） |
| 不写规则 | 无 | 12–29 字（模型自行思考，预算被吃光） |

早期版本存的是布尔值（`getBoolean`），直接 `getString` 会抛 `ClassCastException` —— 所以
`thinkingMode()` 读 `prefs.all[KEY_THINKING]` 的原始值再按类型解释，`ProxyForegroundService.onCreate`
里调一次 `normalizeThinkingMode()` 把旧值改写成新档位。

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

## 上游补丁

本地有六个改动要落在上游源码上，但又不能改上游仓库：`maxOutputTokens` 被无条件删除、
静态模型表缺 `gemini-3.8-flash-low` / `-medium`、流式结束时上游不给终止帧、`Do()` 之前
没有任何超时、唯一凭据冷却时所有模型从列表消失、目录探针没有超时。它们存在
`upstream-patches.diff`（1801 行 / 86 hunks / 21 个文件），由构建流程打上去：

```bash
scripts/apply-upstream-patch.sh            # 打到 upstream-cliproxy（幂等，已打则跳过）
scripts/apply-upstream-patch.sh --rebase   # 上游前进、补丁打不上时，把修正搬到新 HEAD
scripts/apply-upstream-patch.sh --refresh  # 从工作树重新生成 upstream-patches.diff
```

**CI 也走这一步。** `auto-sync-upstream` 会 clone 一份干净上游，如果只在本地打补丁，
CI 产出的内核就会静默丢掉这些修复，本地包和 CI 包行为不一致而没人知道。
所以 workflow 在 clone 之后立刻 `git apply --3way`，然后**断言修正确实生效**：10 条 marker
（六个改动各自的关键符号）、源码里不再出现任何拼法的 `maxOutputTokens` 删除、
模型表里有 `gemini-3.8-flash-low`。打不上或断言失败就整个 job 失败——发布一个丢了修复的
内核比不发布更糟。

注意 CI 用的是 `git clone --depth 1`，**没有历史 blob，`--3way` 会退化成直连应用**。
所以补丁必须与 `.upstream-sha` 那个 commit 精确对齐：上游一前进就得 `--rebase` 重做，
否则报的是一句很误导人的 `repository lacks the necessary blob to perform 3-way merge`。
2026-11 那五次 CI 全红就是这么来的（上游把 `sjson` 换成 `ojson`，两处
`maxOutputTokens` 的 hunk 全废）。

## 构建

> **先做第 0 步。** `upstream-cliproxy/` 和 `app/libs/cliproxy.aar` 都不在仓库里（前者是上游源码，
> 后者是 39 MB 的编译产物，都由 CI 或本脚本生成）。而 `go-bridge/go.mod` 有一行
> `replace github.com/router-for-me/CLIProxyAPI/v8 => ../upstream-cliproxy`，所以**没做第 0 步时
> `go test`/`go build` 会直接失败**，报错指向一个不存在的目录。

```bash
# 0) 上游源码 + 本地补丁（必须先做）
git clone --depth 1 https://github.com/router-for-me/CLIProxyAPI.git upstream-cliproxy
scripts/apply-upstream-patch.sh          # 幂等；打不上会明确报错

# 1) Go 桥接层。需要 Go >= go-bridge/go.mod 里声明的版本（当前 1.26.10）
cd go-bridge
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
# 下面是本项目的路径，换成你自己的 SDK；NDK 版本要和你装的一致
export ANDROID_HOME=/opt/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
gomobile init
go test -v ./cliproxymobile
gomobile bind -target=android/arm64 -androidapi 28 -javapkg=me.routerfor.cliproxy \
  -ldflags="-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
  -o ../app/libs/cliproxy.aar ./cliproxymobile

# 2) APK
cd ..
printf "sdk.dir=$ANDROID_HOME\n" > local.properties   # 同样按自己的路径改
./gradlew assembleDebug
```

只想读代码、不想构建的话，做完第 0 步就能 `cd go-bridge && go build ./...` 通过；Android 侧
（`app/src/main/java/me/routerfor/cpa/`）是纯 Kotlin，不依赖 AAR 也能读。

### 为什么 `-ldflags=-checklinkname=0`

`internal/client/codex/live` → `pion/ice` → `pion/transport/stdnet` → `github.com/wlynxg/anet`。
`anet` 用 `//go:linkname` 直接引用 `net.zoneCache` 与 `golang.org/x/net/internal/socket.zoneCache`，Go 1.23 起
的 linkname 校验器会拒绝该引用（`link: github.com/wlynxg/anet: invalid reference to net.zoneCache`），
只有编 `c-shared` 时才暴露。关闭该校验只影响这条 linkname，不改变运行语义（`anet` 在 Android 上走自己的
netlink 实现）。

### 为什么 `-ldflags` 里还有 `-s -w`

`gomobile bind` 默认**不剥符号**，内核 `.so` 里带着完整的 DWARF 调试表。实测这份内核：

| 节 | 大小 |
| :--- | ---: |
| `.debug_info` | 13.5 MB |
| `.debug_loclists` | 8.8 MB |
| `.debug_rnglists` | 1.9 MB |
| 合计（含其他 debug 节） | **约 35 MB** |

手机上没有任何东西读它们。加 `-s -w` 之后：

| | `.so` 原始 | 压缩后 |
| :--- | ---: | ---: |
| 不加 | 94.76 MB | 32.47 MB |
| 加 `-s -w` | **59.47 MB** | **18.55 MB** |

16 KB 页对齐不受影响（四个 PT_LOAD 仍全 `0x4000`），`scripts/check16k.py` 会盯着这一点。
于是 release APK 34.4 → **23.2 MB**（debug 变体的对齐填充见下文，别拿它衡量体积）。

R8（`isMinifyEnabled`）保持关闭：内核已经剥过，R8 只能再省约 0.5 MB，却会重命名自己的类并留下反射风险，
不值得。

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
  （`raw.githubusercontent.com/Heccdod/CPA-Android/main/rules/config.yaml`）→ 交给 Go 侧的
  `applyRulePack()`（白名单：`requests.payload` / `requests.streaming` /
  `oauth.providers.antigravity.sensitive-words` / `routing` / `observability`）→ 原地重写
  `filesDir/config.yaml` → 引擎的文件 watcher 立即热重载。同样是手动触发，不再自动跑。

  > 2.7.1 之前这条路是**整份覆盖**，会顺手冲掉 `models.catalog`、账号、密钥——凡是没写进
  > `rules/config.yaml` 的键全部消失。现在只替换白名单里的五个路径，其余节点连同注释、
  > `anchor`/`alias` 原样克隆回来；锚点节点不是从零构造，而是从磁盘上那份克隆的——
  > 从零构造会让 `settings-node` 之类的共享锚点失去定义，整份文件读不回来。
- **L3 整包热更**：`UpdateManager.checkAppRelease()` 读
  `api.github.com/repos/Heccdod/CPA-Android/releases/latest`，用 `compareVersions()` 对
  `tag_name` 与本地 `versionName` 做**有序**比较，只有远端更高才继续（相等比较没有方向：
  `/releases/latest` 停在 v2.5.3 时，2.7.1 会被告知「发现新版本 v2.5.3」并真的降级装回去）；
  `.apk` 下载后经 `FileProvider` 拉起系统安装器覆盖安装。
- **L3 内核热更**：`checkKernelRelease()` 走**另一条**查询 ——
  `releases?per_page=20`，取第一个 `prerelease && tag.startsWith("upstream-")` 的 release 里的
  `.so`，按摘要校验后落到 `getDir("hotlibs")`。

  为什么是两条：CI 用 `gh release create --prerelease` 发布内核包，而 GitHub 的 `/releases/latest`
  **按定义排除 prerelease** —— 只用 `/latest` 的话，内核热更永远看不到任何东西。这是 2.5.0 之前的
  实际状态：仓库里有 `upstream-*` 包，App 一个都拉不到。

  内核通道也加了防降级闸门：`installedKernelTag()` 从 `pending.txt` 反解已装载的 tag，
  `tag <= installed` 直接报「内核已是最新」。以前只按 `published_at` 挑「发布最晚的一个」，
  渠道里重发一个旧内核就会覆盖掉新的；而 `pending.txt` 是粘的（它钉住文件名），
  装上去之后下一次检查还会因为摘要相等报「已是最新」，永久停在旧内核上。

  `installedKernelTag()` 有两种情况返回 `null`（=允许任何候选）：tag 解析不出来；
  或 `pending.txt` 点名的那个 `.so` 已经不在盘上——后者是刻意的，否则用户删掉内核之后就再也下不回来。

  摘要校验是**失败即拒绝**：先看 asset 的 `digest` 字段，为空则回退去读同 release 的
  `SHA256SUMS.txt`；两者都没有就拒绝安装。`HotSoLoader` 同理 —— 没有 manifest 就不加载已暂存的
  `.so`。以前缺清单时直接跳过校验并 `System.load()`。

> 注意摘要只能证明「下载没坏」，证明不了「这是你发的」。仓库地址与 key 都在 `BuildConfig` 里，
> 想换源要重新编译；真正要防的是 GitHub 账号被盗后发一个恶意 Release——所以默认关闭是这道防线的第一层。

开关状态和**实际加载的内核**是两件事，界面上要分开显示。`HotSoLoader.hotState()` 返回
`off` / `pending` / `packaged` / `hot:<name>` 四种，首页引擎行与设置「关于」都读它。
以前两边分别显示「开关开没开」和「loaded 是否为空或 packaged」，于是
「刚打开、还没下过」与「pending 指向的文件已被清掉、静默回落到打包内核」在界面上完全同形——
后者是坏的，但看起来一样，而且回落的那个分支原本一行日志都没有。

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
  -ldflags="-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
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

### 6. 热重载从来没生效过：`os.Rename` 换掉了被 watch 的 inode（真机实测）

**症状**：改上游代理、改敏感词、保存自定义提供商，界面提示「已保存，引擎正在热重载」，但引擎行为不变；
重启网关才生效。

**定位**：设备上改一次设置，数引擎日志里的 `config file changed`——**58 → 58**，一次都没有。
把写入方式单独做对照实验（同进程、同 `config.yaml`）：

```
3 次 in-place 写入 → 3 次 config file changed
3 次 os.Rename 覆盖 → 0 次 config file changed
```

**根因**：`writeFileAtomic` 用 `os.CreateTemp` + `os.Rename` 落盘。rename 本身原子，但它**换掉了 inode**，
而引擎的 fsnotify watch 绑在 inode 上（`internal/watcher/events.go:30` 的 `w.watcher.Add(w.configPath)`）。
watch 跟着旧 inode 一起消失，之后所有写入都看不见。代码注释「the atomic rename triggers the engine file watcher」
从来没成立过。

**修复**：`writeFileAtomic` 改成原地截断重写（`O_WRONLY|O_CREATE|O_TRUNC` + 单次 Write + Sync），保住 inode。
代价是失去「要么全写要么不写」，所以先落一个 `config.yaml.lastgood` 再动主文件，写完删掉备份；
启动时 `recoverConfigFromBackup` 看到残留备份就还原。

判据是**备份文件在不在**，不是「主文件能不能解析」：半截 YAML 仍是合法 YAML（缺的是尾巴），
解析成功区分不出好文件和写了一半的文件。第一版按解析结果判断，`TestWriteIsRecoverableAfterAnInterruptedWrite`
直接失败——截断文件解析通过了，恢复没触发。

**测试**：`go-bridge/cliproxymobile/reload_test.go`。`TestConfigWritesReachTheEngine` 连写三次并要求日志真的出现三次
reload。两个连带细节：

- 必须等引擎自己的 `file watcher started` 行再写。`StartProxy` 在端口就绪、模型列表非空时就返回了，
  那时 watcher 的 goroutine 还没 `Add()` 完，写进去的事件照样丢——旧代码在这里也会「看起来通过」。
- 每次写的内容必须不同。watcher 会用 SHA-256 比对，内容没变就 skip（`config file content unchanged`），
  重复写同样的词测到的是去重逻辑，不是文件句柄。

**真机验证**：加第二个自定义提供商后 `58 → 59`，引擎日志 `+2 OpenAI-compat`，`/v1/models` 立刻多出
`kimi-k2 (kimi-probe)`，全程没重启网关。

### 7. 管理密钥被注入到任意网页（面板 WebView）

**症状**：没有任何外部表现，纯粹是数据外泄的口子。

**机制**：`PanelWebView.PanelClient.onPageFinished` 先调 `seedSession(view)`，再判断 `isPanelUrl(url)`。
`seedSession` 把 `WebBridgeCompat.managementKey()` 写进该页面的 `localStorage`，而
`shouldOverrideUrlLoading` 对非 provider、非回调的 http(s) 链接返回 `false`——WebView 就地导航过去。
面板里任何一个外链被点一下，目标页加载完成即收到管理密钥，脚本读走就拿到了整个管理面
（改配置、读凭据、换 key）。

**修复**（`PanelWebView.kt`）：

- `onPageFinished` 先判 `isPanelUrl`，不是面板文档就直接 return，不再写 localStorage；
- `shouldOverrideUrlLoading` 只放行面板自己的 URL，其余 http(s) 一律 `openCustomTab` 并返回 `true`，
  站外页面根本不进这个持有密钥的 WebView；
- provider 域名从 `host.contains(...)` 改成 `host == it || host.endsWith("." + it)`——
  `claude.ai.evil.com` 不再是 claude.ai。

顺带补上 `PanelActivity` 的 `onDestroy`（原来没有）：`removeCallbacks` + `destroy()`。
它的「网关还没起来就 600 ms 后重试」没有次数上限也没有取消，退出后仍在跑，每轮还在主线程拨一次 loopback。

### 8. 单线程池把状态卡和额度请求排在一起

**症状**：点刷新后状态卡停在「正在启动」，点重启也没反应，几十秒后才跳变。

**机制**：`MainActivity.io` 是 `newSingleThreadExecutor`，`refresh()` 的状态探测和
`fetchQuota()` 的逐账号请求（每个 12 s 预算）排在同一条线程。五个账号就是队列里先排一分钟；
`STARTING` 的 30 次重试 tick 全堆在队尾。`SettingsActivity` 同样：打开「通知栏显示额度」后
再改代理，`updateQuickSettings` 要等额度全部跑完才执行，中间没有任何反馈。

**修复**：两边各加一个 `quotaIo` 单线程池（`bgQuota`/`bgQuota`），额度走它，其余仍走 `io`；
`onDestroy` 两个都 `shutdownNow()`。

### 9. 第二轮回答卡死：`Do()` 之前一个超时都没有

**症状**：第一轮正常，第二轮客户端一直停在「生成中」。SSE 连接是活的（每 15 秒收到
`: keep-alive`），但没有任何内容；杀掉网关闭环，再跑一轮又坏。

**机制**：`antigravity_executor_stream.go` 里读流的 watchdog 有两个——静默 90 秒关 body、
终止帧后 2 秒宽限——但它们都在 `Do()` **返回之后**才创建。`Do()` 自己拿的 client 是
`newAntigravityHTTPClient(ctx, cfg, auth, 0)`，timeout 传 0，而 `helps/proxy_helpers.go`
只在 `timeout > 0` 时才设 `http.Client.Timeout`；**全仓 `ResponseHeaderTimeout` 命中数为 0**。
于是上游吃完 TCP/TLS 却不回响应头时，`Do()` 永不返回，两段 watchdog 都没机会跑。
下游 keep-alive 照常发，客户端看到的就是「连接活着、永远没内容」。

**修复**（`internal/runtime/executor/antigravity_executor.go`）：

- 包级 var `antigravityStreamResponseHeaderTimeout = 2 * time.Minute`；
- `cloneTransportWithHTTP11` 加 `headerTimeout` 参数，在里面
  `if headerTimeout > 0 { clone.ResponseHeaderTimeout = headerTimeout }`——
  直连与代理两条路径都走这一个函数，改一处即全覆盖；
- `antigravityTransportKey` 也加 `headerTimeout`。**这一条容易漏**：键里不含超时的话，
  2 分钟的流式 transport 会被复用给长非流式生成请求，把长生成也一起掐断；
- `newAntigravityHTTPClient` 加末位参数，只有流式那一处传非零，其余 8 处传 0；
- `isTransientTransportMessage` 加 `"timeout awaiting response headers"`——
  这条超时会被降级成 Result 文本再进分类器，不认它就等于把一次网络抖动记成凭据故障。

**测试**：`antigravity_executor_stream_header_test.go` 起一个只 `Accept` 不写字节的 httptest
服务，把包级 var 临时设成 300ms，断言 `ExecuteStream` 数秒内返回。失败注入实测：
把末参改回 0，测试立刻报 `ExecuteStream() never returned: a silent upstream still blocks the
stream forever`——守卫是真的。

顺带一个结论：`sdk/httptransport/decorator.go:56 Decorate` 只替换 `Transport`，不碰
`Timeout`，也不碰 `*http.Transport` 的字段，所以 `ResponseHeaderTimeout` 不会被它抹掉。

### 10. 唯一凭据冷却时，模型列表整个变空

**症状**：跑坏一轮之后客户端模型下拉列表一条都不剩（不是少几个，是全空），重启网关立刻好，
再坏一轮又空。

**机制**：`internal/registry/model_registry.go:1496 modelRegistrationAvailability` 用

```go
effectiveClients := Count - expiredClients - cooldownSuspended - otherSuspended + quotaAndSuspended
available := effectiveClients > 0 || (Count > 0 && (expiredClients > 0 || cooldownSuspended > 0) && otherSuspended == 0)
```

判「是不是 quota 类挂起」的 `isQuotaSuspension` 只认 `quota` 与 `credential_quota`，而挂起原因
是 `conductor_cooldown.go:833 cooldownReason` 从 `quota.Reason` → `StatusMessage` →
`LastError.Code` → `LastError.Message` 依次取的，**基本就是错误原文**。所以唯一那个凭据只要
因为任何非 quota 原因被挂起（401 记 `unauthorized`、404 记 `not_found`、5xx 记
`transient upstream error`），`otherSuspended` 就是 1、`effectiveClients` 归零，
这个凭据下**每一个**模型都从 `/v1/models` 消失。`SuspendedClients` 是纯内存、**没有时间
过期**，只有重新注册才清——所以「重启就好、再跑一轮又坏」。

上游只有 `model_registry_sole_credential_quota_test.go` 覆盖 quota 情形，而且把
`"unauthorized"` 必须隐藏写成了契约。

**修复**：把挂起分成有界与无界。有截止时间（冷却）的期间模型**继续列出**——凭据只是暂时不接活，
模型本身还在，没必要把客户端的选项清空；没有截止时间的（手动禁用、401 这类要人介入的）仍隐藏。

- `ModelRegistration` 加 `SuspendedUntil map[string]time.Time`，新增
  `classifySuspension(reason, until, now)` 返回 `suspensionNone / suspensionCooldown / suspensionOther`。
  截止时间已过就地判成 None——投影前没人清这个 map。
- `modelRegistrationAvailability`、`GetAvailableModelsByProvider`（`:1690` 起逐字复制了同一套
  统计，**漏改一处行为就会不一致**）与 `GetModelCount` 三处统一用该分类；有界时把模型列表缓存
  的 `expiresAt` 收到 `until` 之前，否则缓存会活过冷却。
- `ClientModelProjection` 加 `SuspendUntil`，`ApplyClientModelProjections` 锁步写/删，
  `reason` 或 `until` 任一变化才算 changed（只比 reason 的话每轮都判脏）。
- `clientModelProjectionForAuth` 里非永久挂起时取 `earliestFutureInstant(...)` 作截止时间；
  `hasUnauthorizedAuthFailure` 与 `StatusDisabled` 仍算永久。

**测试**：`model_registry_suspension_deadline_test.go` 五个用例。核心那条断言的是
**「列表里有，但 `GetModelCount()==0`」**——只断言「还在列表里」会漏掉「其实根本没在接活」。
既有契约 `service_auth_sync_test.go` 的两个端到端用例原断言 401 后模型必须消失，改成断言
`GetByID(authID).Unavailable == true` 且模型仍在列表。

## 设备侧验证（scripts/device-smoke.sh）

```
./gradlew assembleDebug -PwithEmulatorAbi=true   # 模拟器需要 x86_64
bash scripts/device-smoke.sh [serial]
```

最近一次结果（主力机 25060RK16C / Android 17 / API 37）：**37 passed, 0 failed**（v2.7.2）。

v2.7.2 新增 D3 五条：跑两轮流式请求，每轮必须在超时前自己结束、必须以 `[DONE]` 收尾，
且跑完后 `/v1/models` 的条目数不能变少。这三件事对应本轮修的两个 bug（`Do()` 之前无超时、
单凭据非 quota 冷却清空模型列表），单测覆盖的是引擎内部，D3 覆盖的是它们在真机上确实成立。

> D3 与面板检查都只断言**本应用自己的契约**：上游没有凭据或者手机离线时，那一轮记为 note 而不是
> fail——上游能不能作答不是这个 App 的承诺。面板检查会先把 MainActivity 拉回前台并确认它是
> `topResumedActivity` 才继续，因为这是真机，中间有人拿起手机切到别的 App 就会让坐标指向别人。
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
# 真机（只打 arm64）
./gradlew clean assembleDebug

# 模拟器（额外打 x86_64）
./gradlew assembleDebug -PwithEmulatorAbi=true
```

`abiFilters` 默认只有 `arm64-v8a`，所以真机包只带一份 `libgojni.so`（剥符号后未压缩 59.5 MB）。

两个变体的体积差别很大，**别用 debug 包判断体积**：

| 变体 | 文件 | 压缩内容 | 对齐填充 | 原始 `.so` |
| :--- | ---: | ---: | ---: | ---: |
| release | **23.2 MB** | 23.1 MB | 0 | 59.5 MB |
| debug | 46.0 MB | 25.7 MB | **20.1 MB** | 59.5 MB |

填充来自 AGP 的 `zipAlignDebug`：debug 变体把 `.so` 以 stored（不压缩）方式存放并留出对齐空间，
release 变体则压缩后紧跟下一条记录。所以「文件比内容大 20 MB」不是打了两遍，而是一段
全零的空洞 —— `unzip -lv` 里 `assets/models.json` 的偏移量比前一条记录的结束位置大出一大截。
真要装到手机上，用 release 包。

## 真机实测

两台机器都跑过，都是 Android 17 / API 37 / arm64-v8a：

| 设备 | 用途 | 结果 |
| :--- | :--- | :--- |
| Redmi Note 12 Turbo（368b07b8，走无线 10.250.233.76:5555） | 第一轮验证 | 16 KB 对齐修复后兼容性弹窗消失，device-smoke.sh 20 passed |
| 25060RK16C（BU8XQGONNBQGFUHQ，USB） | 2.0 验证 | device-smoke.sh **30 passed, 0 failed** |
| 25060RK16C（BU8XQGONNBQGFUHQ，USB） | 2.7.2 验证 | device-smoke.sh **37 passed, 0 failed** |

接 USB 设备踩的坑：WSL 的 adb server 会占着 5037，让 Windows 侧 adb.exe 起不来
（"could not read ok from ADB Server"）；先在 WSL 里 adb kill-server 清场，Windows 侧才能正常枚举。
无线那台反过来要先 adb tcpip 5555 再 adb connect <ip>:5555。

### 从 UNC 路径启动 Windows 工具会静默失败

在 WSL 里直接调 `adb.exe`（以及 `cmd.exe`、`usbipd.exe` 这类 Windows 程序）时，如果当前目录是
`\\wsl.localhost\...`，Windows 那个进程会先打印

```
CMD.EXE 的当前目录无效，因为当前目录是 UNC 路径。CMD.EXE 不支持将 UNC 路径作为当前目录。
```

然后**以默认目录重开**，`adb devices` 返回空列表、`usbipd list` 报的是一片乱码。看起来像设备没插、
`adb` 版本不对、驱动坏了——其实是工作目录。`adb.exe start-server` 的症状更误导人：
`could not read ok from ADB Server` / `failed to start daemon`，与 5037 被占一模一样。

解法：把命令放到 `/mnt/c` 下跑（`workdir` 指过去），或者 `cd /mnt/c && adb.exe ...`。
只有**启动 server** 那一步受它影响；server 已经在跑之后，从任何目录调 adb.exe 都正常。

这台机器上因此有两条并存的通道，别把它们搞混：

- `scripts/adbw.sh` → Windows 的 `adb.exe -s BU8XQGONNBQGFUHQ`（设备插在 Windows 侧时用这条）；
- `scripts/device-smoke.sh` 默认 `adb` → `/opt/android-sdk/platform-tools/adb`（WSL 侧，USB 经 usbipd
  转发进来或无线连上之后可用）。

USB 调试被切成「仅充电」之后 USB 通道会消失，但无线 `adb connect` 和 usbipd 转发都可能还活着，
所以「`adb devices` 空」不一定是设备没连——先用 `/mnt/c` 作工作目录起一次 Windows 侧的 server 再判断。

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
