# 上游问题与补丁

本目录的 `upstream-patches.diff` 是针对 [router-for-me/CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI)
的三个改动，覆盖 `internal/registry/models/models.json` 与
`internal/runtime/executor/antigravity_executor_request.go` 及其测试，可以直接 `git apply` 到上游。

- 基线：上游 `HEAD = 3078035`（`fix(aistudio): translate every SSE frame of a relay stream chunk`，
  tag 对应见 `.upstream-sha`）。
- 校验：`git apply --check` 在干净的 `HEAD` 工作树上通过；打上后
  `go test ./internal/runtime/executor/ ./internal/translator/antigravity/... ./internal/registry/...` 全绿。

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

## 3. 思维链：`includeThoughts` 与 `thinkingLevel` **必须成对**

**现象**：antigravity 的 gemini 在 SillyTavern 里看不到思维链。

**根因**：不是「上游不支持思考」，而是触发条件没被满足，且**两个键必须同时在 payload 里**：

| 请求里的东西 | `delta.reasoning_content` |
| :--- | :--- |
| 什么都不传 | 空（有 `reasoning_tokens`，内容被丢弃） |
| `reasoning_effort: "low"` | 空 |
| `reasoning_effort: "medium"` | **有** |
| `reasoning_effort: "high"` | **有** |
| 只给 `generationConfig.thinkingConfig.includeThoughts: true` | 空 |
| `includeThoughts: true` + `thinkingLevel: "high"` | **有** |
| `thinking: {type: enabled}` | 空（OpenAI 路径不认这个字段） |

关键结论：**单独给 `includeThoughts` 无效**。翻译层确实认这个字段，但只有当 payload 同时
带着思考强度（`thinkingLevel` 或 `thinkingBudget`）时，上游才会把 thought 文本回传；
只有可见性、没有强度时，上游照常思考（`thoughtsTokenCount` 非零）却一个字节也不外显——
钱花了，什么都看不到。

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
   前者的响应里 `parts[].thought` 全为 false / 缺失，后者出现 `thought: true` 的 part。
2. 走 App 的 OpenAI 兼容口，开关关掉时 `curl -N ... | grep reasoning_content` 无输出；
   打开后同一条命令能抓到 `"reasoning_content":"…"` 帧，且**客户端没有传 `reasoning_effort`**。

**修复方案（本项目采用：App 侧 payload 规则，不改上游代码）**

往 `config.yaml` 写一条 `requests.payload.override` 规则，由内核在请求出站前把两个键塞进 payload。
规则由 `go-bridge/cliproxymobile/mobile.go` 的 `applyThinkingVisibility()` 生成
（`thinkingParamPath = "generationConfig.thinkingConfig.includeThoughts"`、
`thinkingLevelPath = "generationConfig.thinkingConfig.thinkingLevel"`、`thinkingLevelDefault = "high"`），
最终写出来的 YAML 是：

```yaml
requests:
  payload:
    override:
      - models:
          - name: '*'
        params:
          generationConfig.thinkingConfig.includeThoughts: true
          generationConfig.thinkingConfig.thinkingLevel: high
```

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
- 开启时是幂等的：已经有这条规则就不再追加，避免 `config.yaml` 越切越长。
- 与 `models.catalog` 打补丁共存：`ensureCatalogKey()` 只 set 一个路径，不整份重写，
  两者先后执行都不会把对方的键冲掉（`TestEnsureCatalogKeySurvivesThinkingPatch`）。

**如果将来要改上游**（本项目没做，记在这里供上游参考）：更合适的落点是
`internal/translator/antigravity/openai/chat-completions/antigravity_openai_request.go` 的
`normalizeAntigravityOpenAIThinkingConfig`（`545` 起）：当 payload 有 `includeThoughts` 但没有任何
强度键时，补一个默认 `thinkingLevel`（或反过来，在没有可见性时不要凭空造 `thinkingConfig`）。
无论改哪边，**成对**这条约束都不能只在一边实现。

---

## 补丁清单与验证命令

| 文件 | 改动 | 行数 |
| :--- | :--- | :--- |
| `internal/registry/models/models.json` | antigravity 段补 `gemini-3.8-flash-low` / `-medium` | +68 / −0 |
| `internal/runtime/executor/antigravity_executor_request.go` | 去掉两处 `maxOutputTokens` 删除（`:83`、`:96`） | +8 / −4 |
| `internal/runtime/executor/antigravity_executor_buildrequest_test.go` | 断言从「必须删除」改为「必须保留且为正」 | +6 / −2 |

```bash
# 1) 应用到上游
cd upstream-cliproxy && git apply --check ../upstream-patches.diff && git apply ../upstream-patches.diff

# 2) 跑相关测试
go test ./internal/runtime/executor/ ./internal/translator/antigravity/... ./internal/registry/...

# 3) 重新生成（前提：工作树里只有这三个文件的改动）
cd upstream-cliproxy && git diff > ../upstream-patches.diff
```
