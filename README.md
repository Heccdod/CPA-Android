# CPA-Android

在手机上跑一个完整的 AI API 网关。把一个 Go 写的多提供商代理内核编进 APK，在本机 `127.0.0.1:8317`
提供 OpenAI / Anthropic / Gemini 兼容的流式接口，任何客户端（SillyTavern、Chatbox、自己的脚本）
填个地址就能用。

用途很具体：把手机上已登录的 AI 账号变成一个标准 API，供局域网内的其他程序调用；
或者在同一台手机上给只认 OpenAI 协议的 App 接上 Gemini 的额度。

> 个人项目，源码公开，可自行构建或从 Releases 下载。签名用的是本机 debug key，不保证长期维护。

---

## 快速开始

```bash
adb install -r CPA-Android-x.y.z.apk
```

打开后：

1. **首页**显示服务地址 `127.0.0.1:8317/v1` 和 API 密钥，点一下密钥就能复制。客户端填这两项。
2. **快捷添加**选提供商，走系统浏览器登录，回调自动送回 App，不用复制粘贴任何 code。
3. 想让同一 Wi-Fi 下的电脑也能用：**设置 → 网络 → 允许局域网访问**，然后把地址换成手机的内网 IP。
4. 需要调模型、看用量、改配置：**管理面板**（内核自带的管理界面，全屏打开，已预登录）。

### 几个不显然的操作

| 想做什么 | 怎么做 |
| :--- | :--- |
| 停止网关 | **长按**首页的「重启网关」按钮（会先确认）。通知栏里也能停 |
| 换主题 | 首页右上角的太阳/月亮/书页图标，四态：跟随系统 / 日间 / 黑夜 / 羊皮纸 |
| 让正文更长 | 设置 → 引擎 → 思考预算 → 选「不思考」（默认已是） |
| 看某账号还剩多少额度 | 账号行下方直接有；点右侧的刷新只问这一个账号 |
| 换了网络连不上 | 不用管，会自动重连；真要手动就点「重启网关」 |

---

## 特色

**多提供商聚合，一个地址**

Antigravity（Gemini / Claude / GPT-OSS）、Claude、Codex、xAI、Kimi 都能挂在一个端口后面，
客户端按模型名选。一个账号挂了不影响其他账号。

**额度真的能看到**

内核没有「剩余额度」接口，所以 App 通过内核的请求代理去调各家的官方额度接口——token 由内核实时代换，
App 自己从不接触凭据。额度按模型族分组（Gemini 和 Claude 是两笔独立预算，不能折成一个数字），
画的是**剩余**而不是已用，重置时间给绝对时刻而不是倒计时。

**连接会自己恢复**

三层：端口存活探测（不是看标志位）、20 秒看门狗（连续两轮探不通才重启）、网络切换自动重连。
手机换 Wi-Fi、切移动网络、休眠唤醒这些场景都不需要手动干预。

**停止是真的停止**

四态状态机（运行中 / 正在启动 / 已停止 / 异常），停止意图持久化——重启 App 或系统拉起服务都不会
把网关偷偷复活。停止后账号列表保留，不会看起来像「我的账号没了」。

**管理面板无缝融合**

内核自带一个约 3 MB 的单文件 Web 面板。App 在 WebView 层拦截并重写这份文档：注入登录态、
去掉赞助商推广、抹平桌面端布局残留的灰条、覆盖配色变量让它跟随 App 的三套主题。
不修改文件本身——面板每次启动会从上游重新下载，改文件活不过一次重启。

**内核可热更新**

CI 每次上游变动都重编内核，App 可以只下载 `.so` 并重启引擎，不用重装 APK。默认关闭，
摘要校验失败即拒绝。

---

## 技术栈

| 层 | 用什么 |
| :--- | :--- |
| 内核 | Go 1.26+（本机用 1.27），[CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) v8 |
| 桥接 | `gomobile bind` → AAR（`c-shared` 交叉编译） |
| 宿主 | Kotlin，minSdk 26 / targetSdk 28 / compileSdk 34 |
| UI | 手写 View + 自绘组件（不用 Compose、不用 M3 默认样式） |
| 面板 | 上游单文件 SPA，WebView + `shouldInterceptRequest` 层重写 |
| 构建 | Gradle 8.9 + AGP 8.5.2，GitHub Actions 定时跟随上游 |

界面全部手写而不是用 Material 组件，原因是这个 App 只有三个页面，而 M3 的默认配色、阴影、
水波纹和这套三主题（含羊皮纸）冲突，覆盖成本高于自己画。

---

## 原理

```
客户端 (SillyTavern 等)
        │  http://127.0.0.1:8317/v1/chat/completions
        ▼
┌───────────────────────────────────────────┐
│  ProxyForegroundService  (前台服务)        │
│    └── libgojni.so  ← gomobile 编出的内核  │
│          · 协议翻译  OpenAI ⇄ Gemini       │
│          · 凭据管理  auths/*.json          │
│          · 额度/限流/重试/敏感词混淆        │
└───────────────────────────────────────────┘
        │  https
        ▼
   Google / Anthropic / OpenAI / xAI / Moonshot
```

**为什么能跑在手机上**：内核是纯 Go，`gomobile bind` 把整个 Go runtime 和一个 `c-shared` 库
编成 `libgojni.so`；Kotlin 侧只通过几十个 `String`/`Boolean` 方法跟它说话（gobind 不支持结构体和
多返回值，所以所有接口都是单返回值 + 错误字符串）。内核跑在同一个进程里，不是子进程——
这样没有 IPC 开销，也不需要为 Android 单独改内核的启动逻辑。

**为什么绑 127.0.0.1**：默认只监听本机。想让别的设备用需要显式开「允许局域网访问」，
并且新装时 API 密钥带随机后缀——这个仓库是公开的，写死一个常量等于把额度送给同 Wi-Fi 的所有人。

**流式怎么工作**：客户端要 `text/event-stream`，上游也发 SSE，但字段名和终止语义都不一样。
内核逐帧翻译（`choices[].delta` ⇄ `candidates[].parts[]`），而**终止**是整条链路里最容易出错的地方——
OpenAI 兼容客户端认 `data: [DONE]`，不认 `finish_reason`；上游却常常发完终止帧就不关连接。
这一层踩的坑在 [UPSTREAM_ISSUES.md](UPSTREAM_ISSUES.md) 里有完整记录。

**OAuth 怎么做到零粘贴**：内核在 `localhost:51121` 起一个回调转发器，浏览器授权后被重定向到那里，
转发器再转给内核的 `/antigravity/callback`，凭据落盘。Android 侧只是把授权页交给 Chrome Custom Tabs。

---

## 关于「创新」

老实说，这个项目的绝大部分是**集成**：内核是别人的，面板是别人的，协议是别人的。
下面几条是自己在做的过程中想出来的、并且实测有效的做法，按「别人大概不会这么做」排序：

**1. 用内核的请求代理去查额度**

各家额度接口都要 OAuth token，而 App 不该持有 token。做法是把请求交给内核的
`POST /v8/management/requests/api-call`，header 里写一个字面量 `Bearer $TOKEN$`，
内核用选定凭据的 access token 替换掉（必要时先刷新）。App 只发一个不含凭据的模板请求，
拿到已鉴权的响应再自己解析。这个接口本来是给「调试上游」用的，当额度通道用算是意外用法，
但它同时解决了「App 不碰 token」和「各家用不同接口」两个问题。

**2. 在 WebView 的文档层改第三方 SPA，而不是运行时注入**

给面板换主题、去广告，最直觉的做法是 `evaluateJavascript` 注一段 CSS。实测不可靠——
这类 SPA 异步挂载且会重渲染，注入时机稍差就丢。改成在 `shouldInterceptRequest` 里拦截
`/management.html`，把 `<style>` 和垫片脚本插进 `<head>` 再交给 WebView，必然早于模块脚本求值。
顺带解决了老 WebView 缺 ES2022 API 导致的纯白屏。

**3. 面板去广告只认域名，不认变量名**

面板是压缩过的单文件，第一版按 `apikeyFun`、`cS` 这类标识符匹配，上游一换版本就全部失配，
而自检还报「干净」——因为检测函数用了同一套标识符。改成只匹配域名（`apikey.fan`、`fenno.ai`）
和返利参数（`?aff=`），与压缩后的命名无关。这件事的教训比实现重要：**自检不能和清理逻辑共用假设**。

**4. 用「引擎构建次数」而不是「端口通不通」写并发测试**

启动流程有多个触发源（界面、看门狗、网络回调）。第一版并发测试断言「端口能答」，
删掉锁之后它照样通过——第二个引擎绑不上端口，失败被吞掉了。改成在引擎构建处插计数器后，
无锁时 8 个并发启动建了 8 个引擎，有锁时 1 个。**断言必须能观测到差异，否则等于没测。**

**5. 配置迁移用「只碰自己的键」的认领规则**

升级要改写 `config.yaml` 里的 payload 规则，但用户可能自己也写了规则。做法是严格认领：
一条规则只有在「模型列表恰好是 `*`」且「参数里每一个键都是本 App 会写的」时才算自己的，
否则原样保留。这样「先删自己的再写新的」不会误删用户手写的东西。

这几条都来自实际踩坑，不是什么架构设计。项目本身没有发明协议、没有发明算法，
如果说有价值，价值在**把一堆不完全兼容的东西拼成一个能每天用的东西**，并且把每个坑记下来。

---

## 想深入看

| 文档 | 内容 |
| :--- | :--- |
| [docs/ENGINEERING.md](docs/ENGINEERING.md) | 实现取舍、真机踩坑、每条修正的验证方式 |
| [UPSTREAM_ISSUES.md](UPSTREAM_ISSUES.md) | 上游三个流式问题的根因（精确到行号）与补丁 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更 |
| [diag/REPORT.md](diag/REPORT.md) | SillyTavern 流式协议的逐项实测 |

### 构建

> `upstream-cliproxy/` 和 `app/libs/cliproxy.aar` 都不在仓库里（前者是上游源码，后者是约 37 MB 的编译产物）。
> `go-bridge/go.mod` 有一行 `replace` 指向 `../upstream-cliproxy`，所以**第 0 步不能跳过**，
> 否则 `go build` 会直接报「目录不存在」。

```bash
# 0) 上游源码 + 本地补丁
git clone --depth 1 https://github.com/router-for-me/CLIProxyAPI.git upstream-cliproxy
scripts/apply-upstream-patch.sh

# 1) Go 内核（需要 Go >= go-bridge/go.mod 声明的版本）
cd go-bridge
go install golang.org/x/mobile/cmd/gomobile@latest && go install golang.org/x/mobile/cmd/gobind@latest
export ANDROID_HOME=/opt/android-sdk   # 换成你的路径
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
gomobile init && go test ./cliproxymobile
gomobile bind -target=android/arm64 -androidapi 28 -javapkg=me.routerfor.cliproxy \
  -ldflags="-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
  -o ../app/libs/cliproxy.aar ./cliproxymobile

# 2) APK
cd .. && printf "sdk.dir=$ANDROID_HOME\n" > local.properties
./gradlew assembleRelease        # 23 MB
```

只想读代码的话，做完第 0 步就能 `cd go-bridge && go build ./...`；Android 侧是纯 Kotlin，
不依赖 AAR 也能读。

`-ldflags` 里那三段的理由（linkname 校验、16 KB 页对齐、剥符号省 35 MB）在
[docs/ENGINEERING.md](docs/ENGINEERING.md) 的「构建」一节。

---

## 存储占用

装上之后占多少、占在哪。数字来自 release APK 的静态解包与代码路径核对；
设备当时没连，所以没有 `du` 实测值，但每一项都有确定的来源。

### 安装本身：约 83 MB

Android 装一个带原生库的 App，会在两处各存一份：

| 位置 | 内容 | 大小 |
| :--- | :--- | ---: |
| `/data/app/…/base.apk` | 完整 APK（压缩态） | **23.2 MB** |
| `/data/app/…/lib/arm64/` | 从 APK 解出的 `libgojni.so` | **59.5 MB** |
| | **合计** | **约 83 MB** |

两倍不是 bug。`useLegacyPackaging = true`（`app/build.gradle.kts`）让 `.so` 以未压缩方式装进 APK，
系统安装时再解压到 `lib/` 供 `dlopen` 使用——压缩副本和解压副本同时留在磁盘上。
这么做是为了绕开 zip 对齐的依赖：解压之后再加载，16 KB 页对齐只需要管 ELF 的 `p_align`。

APK 内部构成：

| 条目 | 压缩后 | 未压缩 |
| :--- | ---: | ---: |
| `lib/arm64-v8a/libgojni.so` | 18.5 MB | 59.5 MB |
| `classes.dex` | 3.3 MB | 8.7 MB |
| `resources.arsc` | 1.0 MB | 1.0 MB |
| `res/` `assets/` `META-INF/` 等 | 0.3 MB | 0.6 MB |

`.so` 占绝对多数：Go runtime、内核和全部依赖都在里面。已经剥过符号（`-s -w`），
否则还要多约 35 MB 的 DWARF 调试表。

### 运行数据：几 MB 起步

都在 `/data/data/me.routerfor.cpa/`（私有目录，非 root 读不到）：

| 路径 | 内容 | 典型大小 |
| :--- | :--- | ---: |
| `files/config.yaml` | 引擎配置 | 约 2 KB |
| `files/models.json` | 模型目录（从 APK assets 复制） | 118 KB |
| `files/auths/*.json` | 账号凭据 | 约 0.5 KB / 账号 |
| `files/cpa-engine.log` | 引擎运行日志 | 上限 4 MB，见下 |
| `files/static/management.html` | 管理面板（首次打开时下载） | 约 3 MB |
| `files/updates/` | 下载的更新 APK（冷启动时清空） | 0 或 23 MB |
| `app_hotlibs/` | 热更新内核 | 0 或 59 MB |
| `shared_prefs/` + WebView 缓存 | 偏好与面板本地存储 | 1–5 MB |

### 会增长的三项，都有上限

| 项目 | 上限 | 怎么约束的 |
| :--- | ---: | :--- |
| 引擎日志 | **4 MB** | 每次启动引擎前检查，超过就把最旧的一半丢掉，从行边界切开 |
| 更新包 | 1 份 | 每次冷启动清空 `files/updates/`；下载新包前也会先清空 |
| 热更新内核 | 1 份 | 关掉开关即清空；保留时约 59 MB |

**引擎日志**是唯一会随使用量增长的：日志级别是 info，每个代理请求写一行，
频繁用酒馆一天几万个请求不奇怪。4 MB 大约能装两万行，够回看最近发生了什么；
再多就是占地方。截断只保留尾部，因为排查问题时看的是最后那几十行，不是上个月的记录。

**更新包**下载完就交给系统安装器，安装器不会替你删源文件，所以每更新一次就多 23 MB。
现在下载前和每次冷启动都会清空该目录。

**热更新内核**：打开开关并下载过一次后，`app_hotlibs/` 会留一份约 59 MB 的 `.so`。
关掉开关会立刻删掉（`HotSoLoader.clearStaged`）；开着就一直占着，这是热更新能生效的前提。

### 怎么清

| 想清什么 | 怎么做 |
| :--- | :--- |
| 热更新内核 | 设置 → 更新 → 关掉「内核热更新」 |
| 引擎日志 | 自动，超过 4 MB 会在下次启动时截断 |
| 更新包 | 自动，冷启动时清空 |
| 全部（含账号） | 系统设置 → 应用 → CPA 网关 → 存储 → 清除数据 |

清除数据会连账号凭据一起删掉——凭据只存在设备本地，没有云端备份，需要重新登录。

### 为什么不做小一点

59.5 MB 的 `.so` 是硬成本：Go 静态链接，没有共享库可依赖。能省的只有符号表（已省）和
未使用的 provider 代码（要给上游做裁剪，风险大于收益）。真要让安装体积降一个量级，
只能把内核改成独立进程 + 单独分发，代价是 IPC 复杂度和进程存活问题，不值得。

---

## 已知限制

- 签名是本机 debug key，换机器编的包不能覆盖安装。
- 只打 `arm64-v8a`；模拟器要 `-PwithEmulatorAbi=true`。
- 看门狗的「引擎静默死亡 → 自动重启」只有单元测试覆盖，真机上没能造出这个状态
  （`kill -STOP` 会连看门狗一起冻住，`am kill` 被 MIUI 保活，`am crash` 会整包 force-stop）。
- OAuth 端到端要真实 Google 账号，自动化只验到「签发真实授权 URL + 拉起浏览器 + 51121 转发器就绪」。
