# 上游问题与补丁

本目录的 `upstream-patches.diff` 是针对 [router-for-me/CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI)
的六个改动，覆盖 21 个文件：模型目录、请求构造、流式收尾、凭据冷却、模型列表可见性与
antigravity 目录探针及其测试，可以直接 `git apply` 到上游。

- 基线：上游 `01b24f1`（见 `.upstream-sha`）。上一版基线是 `ad8bdd3`，上游把 `sjson` 换成
  `ojson` 之后 `maxOutputTokens` 的补丁 hunk 全废，已随 `--rebase` 重做——这也是 CI 连续五次
  在「Apply the local upstream patches」失败的真正原因。
- 校验：在干净的 `01b24f1` 工作树上 `git apply --3way` 全部 clean（21 个文件），打上后
  `go build ./...` 通过，`go test ./internal/registry/ ./internal/runtime/executor/
  ./sdk/cliproxy/ ./sdk/cliproxy/auth/ ./sdk/api/handlers/openai/` 全绿。
  `internal/registry` 里 `TestCodexConfigurationUpdateCapability` 在**未打补丁的干净上游**上就失败
  （与本补丁无关，已单独确认），所以不列入判据。
- 应用方式：`scripts/apply-upstream-patch.sh`（幂等）。CI 也执行同一步，并在应用后断言修正确实生效
  （10 条 marker，覆盖六个改动各自的关键符号）。

每个问题按 **现象 / 根因（精确到文件行号）/ 验证方式 / 修复方案** 记录，方便直接转成 issue。

---

## 1. max_tokens 被无条件丢弃（客户端卡在「生成中」）

**现象**：在 SillyTavern 里用 `gemini-3.8-flash-high` 生成对话，被截断或返回空内容时，
客户端状态一直停在「模型生成中」，不会结束。

**根因**：`internal/runtime/executor/antigravity_executor_request.go`。同一个删除动作有两处：

- `antigravity_executor_request.go:83`（需要 schema 清洗的分支）：
  `payloadStr, _ = sjson.Delete(payloadStr, "request.generationConfig.maxOutputTokens")`
- `antigravity_executor_request.go:96`（普通分支）：
  `payload, _ = sjson.DeleteBytes(payload, "request.generationConfig.maxOutputTokens")`

两处都在 `if strings.Contains(modelName, "claude") { … } else { <删除> }` 的 else 里，
即**所有非 Claude** 的 antigravity 模型都会被删。后果有两层：

1. 客户端传的 `max_tokens` 完全无效。实测 `max_tokens: 1` 的请求，上游实际生成了
   3289 个 completion token。
2. 因为上游没拿到上限，它不会以 `MAX_TOKENS` 结束；而一旦流因为网络或客户端超时中断，
   翻译层仍然按常规路径收尾，客户端拿到的 `finish_reason` 是 `stop` 或干脆没有终止帧，
   于是它以为还在生成。

注意同一函数里 `antigravity_executor_request.go:61-68` 还有一段「把 `maxOutputTokens` 夹到
registry 的 `max_completion_tokens`」的逻辑——先费劲夹好，十行之后又整个删掉，这本身就是矛盾的。

**验证方式**：把 `maxOutputTokens` 直接经 `/v8/management/requests/api-call` 打给
`v1internal:generateContent`，上游正确返回：

```json
{"finishReason": "MAX_TOKENS", "usageMetadata": {"thoughtsTokenCount": 17}}
```

也就是说上游**是支持**这个字段的，删除纯属多余。

**修复方案**：保留 `maxOutputTokens`（补丁删掉两个 else 分支的删除语句，留下说明性注释）。
改完后同一请求：

```
data: {... "finish_reason":"max_tokens","native_finish_reason":"max_tokens" ...}
data: [DONE]
```

原测试 `antigravity_executor_buildrequest_test.go:393-397` 断言「必须删除」：

```go
if _, ok := generationConfig["maxOutputTokens"]; ok {
    t.Fatalf("maxOutputTokens should still be removed for non-Claude requests")
}
```

补丁把它反向改成断言保留，并且要求值是正数（`v <= 0` 也判失败），
防止「保留了一个 0」这种假修好。

---

## 2. 静态模型表缺 antigravity 的 3.8 flash 档位

**现象**：把 antigravity 反代到 SillyTavern，模型列表里 `gemini-3.8-flash` 只有 `-high` 一档。

**根因**：`internal/registry/models/models.json` 的 `antigravity` 段（数组起点在 `3974`）只定义了
`gemini-3.8-flash-high`（`id` 在 `4106`，`name` 在 `4112`），随后数组就结束了（`4291` 是该条目的 `}`，
`4292` 是 `],`，`4293` 就进 `"xai": [`）。远程目录
（`raw.githubusercontent.com/router-for-me/models/.../models.json`）同样是这一档，
所以启动时的自动更新也不会补上。

**注意**：模型本身是支持的。用 thinking 后缀可以直接寻址：

```
gemini-3.8-flash-high(low)     -> 200
gemini-3.8-flash-high(medium)  -> 200
gemini-3.8-flash-low           -> unknown provider
```

缺的只是目录条目，导致客户端从 `/v1/models` 里选不到。

**验证方式**：

1. `curl -s http://127.0.0.1:8317/v1/models` 里，`owned_by: antigravity` 的条目里
   `gemini-3.8-flash*` 只有 `-high` 一条（其他家族如 `claude-opus-4-6-thinking` 正常存在，
   所以不是整个 antigravity 段缺失）。
2. 直接拿 `gemini-3.8-flash-high(medium)` 发一轮对话，返回 200——证明上游接受 medium 档，
   是目录没登记。

**修复方案**：在 `models.json` 的 antigravity 段补 `gemini-3.8-flash-low` 与
`gemini-3.8-flash-medium`（复制 high 条目，改 `id` / `name` / `description`；补丁的 hunk 是
`@@ -4288,6 +4288,74 @@`，即在 `4291` 那条 `}` 之后插入，共 68 行）。补丁不改 `thinking.levels` 与 `max_completion_tokens`，
因为三档共用同一套能力。

本项目另外把这份表打进 APK 资产（`app/src/main/assets/models.json`），用 `config.yaml` 的
`models.catalog` 指向 `filesDir/models.json`，以免启动时被远程目录覆盖——见 README 的「模型目录」。

---

## 3. 思维链：可见性由 `includeThoughts` 单独决定，强度不该被写死

**现象**：antigravity 的 gemini 在 SillyTavern 里看不到思维链；后来发现打开可见性之后，
又在 300 token 的预算下几乎只吐思考、正文被挤掉。

**根因**：两件事，别混在一起。

**（a）可见性**：`generationConfig.thinkingConfig.includeThoughts: true` 就是开关。翻译层
（`antigravity_openai_response.go:169-170`）只把 `part.thought == true` 的 part 写进
`choices.0.delta.reasoning_content`，所以只要上游把 thought part 发回来就能看到。
`reasoning_effort`、`thinking: {type: enabled}`、顶层 `include_thoughts` 在 OpenAI →
antigravity 的翻译阶段就被丢掉，对这些客户端不成立。

> **修正**：本文件早先记的是「`includeThoughts` 与 `thinkingLevel` 必须成对，单独给
> `includeThoughts` 无效」。在当前上游上重测，只有可见性、不写强度时 thought part 照样回传。
> 当时的结论混入了另一个变量（`thinkingLevel: high` 同时把预算放大），把「看得见」和
> 「看得多」当成了同一件事。下面的表是新测量结果：

| 请求里的东西 | `delta.reasoning_content` |
| :--- | :--- |
| 什么都不传 | 空（有 `reasoning_tokens`，内容被丢弃） |
| `reasoning_effort: "low"` / `"medium"` / `"high"` | **有** |
| `generationConfig.thinkingConfig.includeThoughts: true` | **有** |
| `includeThoughts: true` + `thinkingLevel: "high"` | **有**，但思考长度明显变长 |
| `thinking: {type: enabled}` | 空（OpenAI 路径不认这个字段） |

**（b）强度**：本项目的开关一度同时写 `thinkingLevel: high`，理由是「不写强度就看不到」。
实测反了：写死 high 会在同一 prompt 下把思考拉长到接近两倍 token，而正文反而更短——
在 SillyTavern 默认 `max_tokens: 300` 下，思考吃掉 285–289 token，正文只剩 9–42 字，
表现就是「被截断」或「一直在生成」。现在规则只写 `includeThoughts`，强度交回调用方：
客户端传 `reasoning_effort` 就按它的来，不传就用上游默认。

另外，`include_thoughts`（顶层）与 `thinking: {type: enabled}` 这类常见客户端写法，在
OpenAI → antigravity 的翻译阶段就被丢掉了，所以「让客户端自己传」对多数客户端不成立。

翻译层本身是对的：`internal/translator/antigravity/openai/chat-completions/antigravity_openai_response.go:169-170`
会把 `part.thought == true` 的文本写进 `choices.0.delta.reasoning_content`：

```go
if partResult.Get("thought").Bool() {
    template, _ = sjson.SetBytes(template, "choices.0.delta.reasoning_content", textContent)
}
```

**验证方式**：

1. 用 `/v8/management/requests/api-call` 直接打 `v1internal:generateContent`，
   对比「只 includeThoughts」与「includeThoughts + thinkingLevel」两种 body：
   两者都出现 `thought: true` 的 part，但后者 `thoughtsTokenCount` 约为前者的两倍，
   正文长度反而更短——这就是把强度写死的代价。
2. 走 App 的 OpenAI 兼容口，开关关掉时 `curl -N ... | grep reasoning_content` 无输出；
   打开后同一条命令能抓到 `"reasoning_content":"…"` 帧，且**客户端没有传 `reasoning_effort`**。

**修复方案（本项目采用：App 侧 payload 规则，不改上游代码）**

往 `config.yaml` 写一条 `requests.payload.override` 规则，由内核在请求出站前把可见性键塞进 payload。
规则由 `go-bridge/cliproxymobile/mobile.go` 的 `applyThinkingVisibility()` 生成
（`thinkingParamPath = "generationConfig.thinkingConfig.includeThoughts"`），最终写出来的 YAML 是：

```yaml
requests:
  payload:
    override:
      - models:
          - name: '*'
        params:
          generationConfig.thinkingConfig.includeThoughts: true
```

只写可见性，不写强度。调用方要指定强度就自己传 `reasoning_effort`，这边不覆盖；
旧版本写进去的 `thinkingLevel: high` 会在引擎启动时被 `migrateExistingConfig()` 摘掉
（只摘带 `includeThoughts` 的那条规则，用户自己按模型设的强度规则不动，
见 `TestApplyThinkingVisibilityLeavesUserLevelAlone`）。

为什么这比改上游更合适：

1. **默认值不该由服务端替所有客户端决定**。思考内容本身计费，写死在
   `normalizeAntigravityOpenAIThinkingConfig` 里等于给每次请求加钱，用户还不知道。
   做成开关，关掉时规则被摘掉，`config.yaml` 里看得见。
2. **能热更**。规则是配置，改完引擎文件 watcher 毫秒级重载；改代码要重编 `libgojni.so`
   再走热更或整包重装。
3. **扛上游同步**。`.github/workflows/auto-sync-upstream.yml` 会定期对齐上游，
   `internal/` 下的改动会在同步时冲突或丢失；payload 规则是上游的公开能力，同步多少次都还在。
4. **粒度可控**。规则带 `models` 匹配，将来要只对某几个模型开启，改配置即可。

工程细节（都有回归测试盯着，`go-bridge/cliproxymobile/thinking_test.go`）：

- 关闭时**只删自己那一条**规则——按 `params` 里是否存在 `includeThoughts` 这个 key 认领
  （`hasThinkingRule()` / `removeThinkingRule()`），用户手写的 override 规则原样保留。
- 开启时是幂等的：已经有这条规则就不再追加，避免 `config.yaml` 越切越长；
  已有规则里若还留着旧版本写的强度，顺手摘掉（`stripForcedThinkingLevel`）。
- 与 `models.catalog` 打补丁共存：`migrateExistingConfig()` 只 set 需要的路径，不整份重写，
  两者先后执行都不会把对方的键冲掉（`TestEnsureCatalogKeySurvivesThinkingPatch`）。

**如果将来要改上游**（本项目没做，记在这里供上游参考）：更合适的落点是
`internal/translator/antigravity/openai/chat-completions/antigravity_openai_request.go` 的
`normalizeAntigravityOpenAIThinkingConfig`（`545` 起）：当 payload 带可见性却没有任何强度键时，
**不要**替调用方补一个默认强度。默认强度是成本决策，应该由调用方做。

---

## 4. 流式回答结束不了：没有终止帧，也没有首字节超时

**现象**：网关跑一轮正常，第二轮开始客户端一直显示「生成中」，SSE 连接是活的（每 15 秒还有
keep-alive 心跳），但再也不出内容。杀掉网关闭环。

**根因**：两件独立的事，第一件已经在本项目的旧补丁里修掉，第二件没有。

**（a）上游正常结束却不给终止帧**。`internal/runtime/executor/antigravity_executor_stream.go`
的读取循环逐行扫描 SSE，但上游常常在发出最后一条 data 帧后就保持连接不关，或干脆直接关 socket
而不发任何终止帧。客户端无法判断「说完了」，只能等自己超时。旧补丁给循环加了：

- `antigravityStreamIdleTimeout = 90 * time.Second`——每读到一行就 `idle.Reset`，静默超过 90 秒
  就主动关掉 body；
- `antigravityStreamGraceTimeout = 2 * time.Second`——识别到终止帧（`finishReason` / `[DONE]` /
  `response.completed` / `message_stop`）后起一个 2 秒宽限，到点关连接，把「上游不关连接」
  变成干净结束；
- `closedOnPurpose := errScan != nil && terminalDelivered && graceClosed.Load()`——自己关掉导致的
  `Err` 不算失败，不进冷却；`emitSyntheticTail()` 先置 `tailEmitted = true` 再补终止帧，
  防止客户端中途断开时重复终止。

**（b）`Do()` 之前完全没有超时，这一条以前是漏的**。`antigravity_executor_stream.go:153` 的
`helps.WithAntigravityHTTPClientTrace(httpClient, auth, "generate_stream").Do(httpReq)` 建立连接、
发完请求就阻塞在等响应头上。client 由 `newAntigravityHTTPClient(ctx, cfg, auth, 0)` 生成，
`timeout = 0`（`antigravity_executor.go:440-483`），`helps/proxy_helpers.go:36-41` 只在
`timeout > 0` 时设 `http.Client.Timeout`，而**全仓 `ResponseHeaderTimeout` 命中数为 0**。
watchdog 是在 `Do` 返回**之后**才创建的（`:234-238`），所以上游吃完 TCP/TLS 却不回响应头时
`Do` 永不返回，两段 watchdog 都没机会跑。下游 keep-alive 照常发，客户端看到的是「连接活着、
永远没有内容」——正是用户说的「卡了」。

**修复方案**：

1. `antigravity_executor.go` 新增包级 var `antigravityStreamResponseHeaderTimeout = 2 * time.Minute`；
   `cloneTransportWithHTTP11` 加 `headerTimeout time.Duration` 参数并在 clone 上
   `if headerTimeout > 0 { clone.ResponseHeaderTimeout = headerTimeout }`——
   直连与代理两条路径都经过这一个函数，改一处即全覆盖。
2. `antigravityTransportKey` 也加 `headerTimeout` 字段。这一条是必需的：键里不含超时的话，
   2 分钟的流式 transport 会被复用给非流式的长生成请求，把长生成也一起掐断。
3. `newAntigravityHTTPClient` 加末位参数，**只有流式一处（`antigravity_executor_stream.go:122`）传非零**，
   另外 8 处（execute / tokens / credits / auth）传 0，保持长非流式请求不设限。
4. `conductor_cooldown.go` 的 `isTransientTransportMessage` 加
   `strings.Contains(lower, "timeout awaiting response headers")`——这条超时错误会被降级成
   Result 文本再进分类器，不认它就等于把一次网络抖动记成凭据故障。

**验证方式**：`antigravity_executor_stream_header_test.go` 起一个只 `Accept` 不写字节的
`httptest` 服务，把包级 var 临时设成 300ms，断言 `ExecuteStream` 在数秒内返回且错误里带
`timeout awaiting response headers`；另有
`TestAntigravityTransportResponseHeaderTimeoutIsPoolScoped` 断言 300ms 会透传、传 0 时尊重 base
上已有的 45s、两种 transport 指针不同。失败注入实测：把 `:122` 的末参改回 0，测试立刻报
`ExecuteStream() never returned: a silent upstream still blocks the stream forever`。

---

## 5. 唯一一个凭据被冷却时，所有模型从 `/v1/models` 消失

**现象**：跑坏一轮之后，客户端的模型下拉列表整个变空（不是「某个模型没了」，是一条都不剩），
重启网关立刻恢复，再跑坏一轮又空。

**根因**：`internal/registry/model_registry.go:1496 modelRegistrationAvailability` 的判定是

```go
effectiveClients := Count - expiredClients - cooldownSuspended - otherSuspended + quotaAndSuspended
available := effectiveClients > 0 ||
    (Count > 0 && (expiredClients > 0 || cooldownSuspended > 0) && otherSuspended == 0)
```

`isQuotaSuspension`（`:1492`）只认 `quota` 与 `credential_quota` 两个 reason。而 reason 是
`conductor_cooldown.go:833 cooldownReason` 依次从 `quota.Reason` → `StatusMessage` → `LastError.Code`
→ `LastError.Message` 里取的，**基本就是错误原文**。所以只要唯一那个凭据因为任何非 quota 的原因
被挂起（401 记 `unauthorized`、404 记 `not_found`、5xx 记 `transient upstream error`、
其余记 `request failed`），`otherSuspended` 就变成 1、`effectiveClients` 归零，
**该凭据下的每一个模型都从 `/v1/models`、`GetAvailableModelsByProvider`、`GetAvailableModelInfos`
里消失**。写入口只有一个：`MarkResult` 尾部 → `clientModelProjectionForAuth` →
`ApplyClientModelProjections`；`conductor_models.go:255-261` 在
`len(auth.ModelStates)==0 && auth.Unavailable && auth.NextRetryAfter.After(now)` 时给每一个模型置
suspended。`SuspendedClients` 是纯内存（`model_registry.go:175`），**没有任何时间过期**，
只有重新注册才清——这就是「重启就好、再跑一轮又坏」。

上游只有 `model_registry_sole_credential_quota_test.go` 覆盖了 quota 情形，
非 quota 的单凭据情形是空的（而且测试把 `"unauthorized"` 必须隐藏写成了契约，见 `:50-61`）。

**修复方案**：把「挂起」分成有界与无界两种。有截止时间的冷却（cooling）期间模型**继续列出**——
凭据只是暂时不接活，模型本身是存在的，客户端没必要把选项清空；没有截止时间的挂起
（手动禁用、401 这类需要人介入的）仍然隐藏。

- `ModelRegistration` 加 `SuspendedUntil map[string]time.Time`；新增
  `classifySuspension(reason, until, now)` 返回 `suspensionNone / suspensionCooldown / suspensionOther`：
  截止时间已过即视作已恢复（投影前没人清 map，得就地判），
  `isQuotaSuspension(reason) || !until.IsZero()` 算有界。
- `modelRegistrationAvailability`、`GetAvailableModelsByProvider`（`:1690` 起逐字复制了同一套统计）
  与 `GetModelCount` 三处都改用这个分类；有界时把模型列表缓存的 `expiresAt` 收到 `until` 之前，
  免得缓存活过冷却。
- `ClientModelProjection` 加 `SuspendUntil`，`ApplyClientModelProjections` 锁步写/删，
  reason 或 until 任一变化才算 changed（否则每轮都判脏）。
- `clientModelProjectionForAuth` 里非永久挂起时取
  `earliestFutureInstant(now, modelUntil, modelQuotaUntil, auth.NextRetryAfter, credentialUntil)`
  作为截止时间；`hasUnauthorizedAuthFailure`（401 且没有任何重试时间）与 `StatusDisabled` 仍算永久。

**验证方式**：新增 `model_registry_suspension_deadline_test.go` 五个用例——有界冷却期间
`/v1/models`、provider 目录、metadata 目录都仍列出而 `GetModelCount()==0`（说明真的没在接活），
截止时间过后自动恢复成 1；无界挂起仍然隐藏（保持既有契约）；清除/手动挂起/Resume 不留残留
截止时间；缓存随截止时间过期；`classifySuspension` 表驱动。既有契约测试
`service_auth_sync_test.go` 的两个用例原断言「401 后模型必须消失」，改成断言
`GetByID(authID).Unavailable == true`（凭据确实停止服务）且模型仍在列表。

---

## 6. 目录探针没有超时，一个卡住的账号会拖住模型同步

**现象**：不常显；表现为某个账号的模型列表长时间不更新，或整个模型同步比预期慢很多。

**根因**：`sdk/cliproxy/antigravity_models.go:464` 的探针 client 是裸 `&http.Client{}`，
**没有 Timeout**。探针是并发上限 5 的（`antigravityProbeSlots`，
`service_plugins.go:21 modelRegistrationMaxWorkersPerCategory`），每条协程就这么无限期挂着，
槽位不会被释放，后面排队的账号全部堵在门口。

**修复方案**：新增包级 var `antigravityProbeTimeout = 30 * time.Second`（做成 var 而不是 const，
测试要能置零），client 改成 `&http.Client{Timeout: antigravityProbeTimeout}`；置零时显式保留
`client.Timeout = 0`，给需要永不超时的测试用。

**验证方式**：`antigravity_models_probe_timeout_test.go` 两个用例——一个断言 200ms 超时下探针返回
transient 错误、无 hints、耗时落在 [200ms, 5s] 且 transport 被取消；一个断言正常目录照常返回。
另外三个既有 synctest 用例（`TestAntigravityStalledAccountDoesNotBlockOtherRetries` 等）
靠新 helper `stalledProbeTimeout(t)` 把超时置零，否则合成时钟会把它们要观察的探针提前取消。

---

## 补丁清单与验证命令

整个补丁 1801 行 / 86 个 hunk / 21 个文件，按改动分组：

| 文件 | 改动 |
| :--- | :--- |
| `internal/registry/models/models.json` | antigravity 段补 `gemini-3.8-flash-low` / `-medium` |
| `internal/runtime/executor/antigravity_executor_request.go` | 去掉 `maxOutputTokens` 删除（上游迁到 `ojson` 后只剩一处 `doc.Delete`） |
| `internal/runtime/executor/antigravity_executor_buildrequest_test.go` | 断言从「必须删除」改为「必须保留且为正」 |
| `internal/runtime/executor/antigravity_executor_stream.go` | idle / grace 两个 watchdog、终止帧识别、干净 EOF 补尾帧 |
| `internal/runtime/executor/antigravity_executor_stream_hold_test.go` | 上游挂住连接时必须结束（`TestAntigravityStreamEndsWhenUpstreamHoldsTheSocket`） |
| `internal/runtime/executor/antigravity_executor.go` | `ResponseHeaderTimeout` 从 transport key 到 client 全链路透传 |
| `internal/runtime/executor/antigravity_executor_{auth,credits,execute,tokens}.go` | 跟随新签名传 0（非流式不设限） |
| `internal/runtime/executor/antigravity_executor_{transport,transport_decorator}_test.go` | 跟随新签名 |
| `internal/runtime/executor/antigravity_executor_stream_header_test.go` | 首字节超时两个新用例 |
| `internal/registry/model_registry.go` | `classifySuspension` / `SuspendedUntil` / 三处判定与投影锁步 |
| `internal/registry/model_registry_suspension_deadline_test.go` | 有界冷却五个新用例 |
| `sdk/cliproxy/auth/conductor_models.go` | `SuspendUntil` 与 `earliestFutureInstant` |
| `sdk/cliproxy/auth/conductor_cooldown.go` | `timeout awaiting response headers` 归入 transient transport |
| `sdk/cliproxy/service_auth_sync_test.go` | 两个端到端用例改为断言「凭据停但有界冷却」 |
| `sdk/cliproxy/antigravity_models.go` | 探针 30 秒超时 |
| `sdk/cliproxy/antigravity_models_probe_timeout_test.go` | 探针超时两个新用例 |
| `sdk/cliproxy/antigravity_models_refresh_test.go` | `stalledProbeTimeout` helper 与三处调用 |

```bash
# 1) 应用到上游（幂等；已打则跳过）
scripts/apply-upstream-patch.sh

# 2) 跑相关测试
cd upstream-cliproxy
go test ./internal/runtime/executor/ ./internal/translator/antigravity/...

# 3) 上游前进、补丁打不上时，把修正搬到新 HEAD
scripts/apply-upstream-patch.sh --rebase     # 冲突则手工解决再继续

# 4) 从工作树重新生成补丁
scripts/apply-upstream-patch.sh --refresh
```

CI 的 `.github/workflows/auto-sync-upstream.yml` 在 clone 之后立刻做第 1 步的
`git apply --3way`，随后断言 10 条 marker（六个改动各自的关键符号，例如
`antigravityStreamResponseHeaderTimeout`、`classifySuspension`、`TestBoundedSuspensionKeepsSoleCredentialModelListed`、
`earliestFutureInstant`、`antigravityProbeTimeout`），外加「源码里不再有任何拼法的
`maxOutputTokens` 删除」与「模型表里有 `gemini-3.8-flash-low`」。任一条不成立就 fail，
不会发布丢了修复的内核。

注意 CI 用的是 `git clone --depth 1`，没有历史 blob，`git apply --3way` 会退化成直连应用。
所以补丁必须与 `.upstream-sha` 指向的那个 commit 精确对齐——上游一前进就得 `--rebase` 重做，
否则报的是一句很误导人的 `repository lacks the necessary blob to perform 3-way merge`。
