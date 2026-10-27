# 上游问题与补丁

本目录的 `upstream-patches.diff` 是针对 [router-for-me/CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI)
的三个改动，覆盖 `internal/registry/models/models.json` 与
`internal/runtime/executor/antigravity_executor_request.go` 及其测试，可以直接 `git apply` 到上游。

- 基线：上游 `ad8bdd3`（`fix(middleware): bound zstd decompression output and window in request logging`），
  见 `.upstream-sha`。上一版基线是 `3078035`，上游前进后补丁已 rebase 到新基线。
- 校验：在干净的 `ad8bdd3` 工作树上 `git apply --check` 通过、打上后
  `go test ./internal/runtime/executor/ ./internal/translator/antigravity/...` 全绿。
  `internal/registry` 里 `TestCodexConfigurationUpdateCapability` 在**未打补丁的干净上游**上就失败
  （与本补丁无关，已单独确认），所以不列入判据。
- 应用方式：`scripts/apply-upstream-patch.sh`（幂等）。CI 也执行同一步，并在应用后断言修正确实生效。

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

## 补丁清单与验证命令

| 文件 | 改动 | 行数 |
| :--- | :--- | :--- |
| `internal/registry/models/models.json` | antigravity 段补 `gemini-3.8-flash-low` / `-medium` | +68 / −0 |
| `internal/runtime/executor/antigravity_executor_request.go` | 去掉两处 `maxOutputTokens` 删除（`:83`、`:96`） | +8 / −4 |
| `internal/runtime/executor/antigravity_executor_buildrequest_test.go` | 断言从「必须删除」改为「必须保留且为正」 | +6 / −2 |

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
`git apply --3way`，并断言「源码里不再有删除 `maxOutputTokens` 的语句」且
「模型表里有 `gemini-3.8-flash-low`」。任一条不成立就 fail，不会发布丢了修复的内核。
