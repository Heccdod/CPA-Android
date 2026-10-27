# 上游问题与补丁

本目录的 `upstream-patches.diff` 是针对 `router-for-me/CLIProxyAPI` 的三个改动，
可以直接 `git apply` 到上游仓库。这里记录每个问题的现象、根因和结论，
方便转交给上游或让别人评估。

---

## 1. max_tokens 被无条件丢弃（导致客户端卡在「生成中」）

**现象**：在 SillyTavern 里用 `gemini-3.8-flash-high` 生成对话，被截断或返回空内容时，
客户端状态一直停在「模型生成中」，不会结束。

**根因**：`internal/runtime/executor/antigravity_executor_request.go` 里，
所有**非 Claude** 的 antigravity 模型都会把 `request.generationConfig.maxOutputTokens`
无条件删掉（两处：schema 清洗分支和普通分支）。后果有两层：

1. 客户端传的 `max_tokens` 完全无效。实测 `max_tokens: 1` 的请求，上游实际生成了
   3289 个 completion token。
2. 因为上游没拿到上限，它不会以 `MAX_TOKENS` 结束；而一旦流因为网络或客户端超时中断，
   翻译层仍然按常规路径收尾，客户端拿到的 `finish_reason` 是 `stop` 或干脆没有终止帧，
   于是它以为还在生成。

**验证**：把 `maxOutputTokens` 直接经 `/v8/management/requests/api-call` 打给
`v1internal:generateContent`，上游正确返回：

```json
{"finishReason": "MAX_TOKENS", "usageMetadata": {"thoughtsTokenCount": 17}}
```

也就是说上游**是支持**这个字段的，删除纯属多余。

**修复**：保留 `maxOutputTokens`。改完后同一请求：

```
data: {... "finish_reason":"max_tokens","native_finish_reason":"max_tokens" ...}
data: [DONE]
```

原测试 `antigravity_executor_buildrequest_test.go:394` 断言「必须删除」，已改为断言保留。

---

## 2. 静态模型表缺 antigravity 的 3.8 flash 档位

**现象**：把 antigravity 反代到 SillyTavern，模型列表里 `gemini-3.8-flash` 只有 `-high` 一档。

**根因**：`internal/registry/models/models.json` 的 `antigravity` 段只定义了
`gemini-3.8-flash-high`。远程目录
（`raw.githubusercontent.com/router-for-me/models/.../models.json`）同样是这一档，
所以启动时的自动更新也不会补上。

**注意**：模型本身是支持的。用 thinking 后缀可以直接寻址：

```
gemini-3.8-flash-high(low)     -> 200
gemini-3.8-flash-high(medium)  -> 200
gemini-3.8-flash-low           -> unknown provider
```

缺的只是目录条目，导致客户端从 `/v1/models` 里选不到。

**修复**：在 `models.json` 的 antigravity 段补 `gemini-3.8-flash-low` 与
`gemini-3.8-flash-medium`（复制 high 条目改 id/name/description）。
本项目另外把这份表打进 APK 资产，用 `models.catalog` 指向它，
以免启动时被远程目录覆盖。

---

## 3. 思维链需要显式 reasoning_effort

**现象**：antigravity 的 gemini 在 SillyTavern 里看不到思维链。

**结论**：不是 bug，是触发条件。上游确实思考了（`reasoning_tokens: 62`），
但 `delta.reasoning_content` 只在请求带 `reasoning_effort` 时才回填：

| 请求参数 | reasoning_content |
| :--- | :--- |
| 不传 | null（有 reasoning_tokens，但内容被丢弃） |
| `reasoning_effort: "low"` | 空 |
| `reasoning_effort: "medium"` | 有 |
| `reasoning_effort: "high"` | 有 |
| `include_thoughts: true` | 空（这个字段在 OpenAI 路径上不生效） |
| `thinking: {type: enabled}` | 空 |

翻译层本身是对的：`antigravity_openai_response.go:169` 会把
`part.thought == true` 的文本写进 `choices.0.delta.reasoning_content`。
问题只在于 `includeThoughts` 没有被自动打开。

**建议**：客户端侧在请求里加 `reasoning_effort`（SillyTavern 的
「Reasoning Effort」或自定义请求体）。若要服务端默认开启，
可以在 `normalizeAntigravityOpenAIThinkingConfig` 里，当请求没有任何 thinking 字段时
默认写入 `request.generationConfig.thinkingConfig.includeThoughts = true`。
本项目暂未这样改，因为它会改变所有客户端的 token 消耗。
