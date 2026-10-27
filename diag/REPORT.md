# CPA-Android 流式协议排查报告（SillyTavern 卡「生成中」）

设备：REDMI 25060RK16C（BU8XQGONNBQGFUHQ），App 引擎 127.0.0.1:8317，经 adb forward 18317 直连。
方法：为拿到**可控的 SSE 字节**，用同一份 Go 源码在 WSL 起了一个完整 CPA 服务端（/tmp/cpasrv）+ 可编排的假上游（diag/mock_upstream.py，17 个场景），用 curl 实测；同时用真机引擎 + 真实 Gemini 上游交叉验证。

---

## 一、结论速览

| # | 项 | 状态 |
|---|---|---|
| 0 | max_tokens 被无条件丢弃 | **已修，实测确认** |
| 1 | 每次流式是否一定有 [DONE] | **不一定 —— 新问题 A** |
| 2 | 空正文（思维链吃满预算） | **干净**，content=null + finish_reason=max_tokens + [DONE] |
| 3 | usage 块位置 | **干净**，只在终止帧；缺 usage 也能正确收尾 |
| 4 | keep-alive 帧格式 | **格式干净**（SSE 注释行），但**会掩盖问题 A** |
| 5 | SAFETY → finish_reason | **干净**，映射为 content_filter |
| 6 | 中途 error 帧 | **新问题 B：error 帧后没有 [DONE]** |
| — | 上游静默/半开连接 | **新问题 C：无任何超时保护，永久 keep-alive** |
| — | 配置层面 | **新问题 D：默认 thinkingLevel=high 把 ST 的 300 token 预算吃光** |

---

## 二、逐项实测

### 1) 终止帧 —— **不一定有 [DONE]**

**正常情况有**（假上游正常关闭）：
```
data: {"id":"resp-1",...,"choices":[{"index":0,"delta":{"role":"assistant","content":"你好，这是正常正文。"},"finish_reason":null,...}]}
data: {"id":"resp-1",...,"choices":[{"index":0,"delta":{"role":null,...},"finish_reason":"stop","native_finish_reason":"stop"}],"usage":{...}}
data: [DONE]
```

**上游发出终止帧但不关连接时，没有 [DONE]，只有 keep-alive** —— 这是本轮最关键的发现：
```
[21.106] data: {...,"content":"正文说完了"...,"finish_reason":"max_tokens",...}
[36.098] : keep-alive          ← 15 秒后
[41.096] data: [DONE]          ← 上游 FIN 到达后才补发
```
（场景 finish_then_hold，delay=20s；[DONE] 严格出现在上游关连接之后）

**上游 RST / 半开静默时，永远没有 [DONE]**：
```
[09.088] data: {...,"content":"这是会被截断的正文"...}
[24.097] : keep-alive
[39.102] : keep-alive
[54.095] : keep-alive
curl: (28) Operation timed out after 45004 ms with 309 bytes received
```

**根因（Go 行号）**：
- `internal/runtime/executor/antigravity_executor_stream.go:263` `for scanner.Scan()` —— 循环只在 EOF / 读错误时退出。
- `antigravity_executor_stream.go:371-373`：`if isTerminalChunk { terminalDelivered = true }` **只记账，不 break**。
- `antigravity_executor_stream.go:397-401`：`[DONE]` 只在 `else if ctx.Err() == nil`（即 scanner 已因 EOF 退出）时合成。
- 连接无读超时：`antigravity_executor_stream.go:113` `newAntigravityHTTPClient(ctx, e.cfg, auth, 0)` —— timeout 传 0。

**ST 侧为什么因此卡死**：
`public/scripts/openai.js:3173` `if (rawData === '[DONE]') return;` —— ST 的 `streamData()` **只认 [DONE] 和 reader done**。我把 sse-stream.js / openai.js / script.js / chat-completions.js 四份源码全 grep 过，`finish_reason` 出现 **0 次**。所以：CPA 不发 [DONE]，ST 的 `for await` 就不返回，`streamingProcessor.isFinished` 一直是 false，UI 永远「生成中」。

用 ST 的解析逻辑（等价移植 sse-stream.js + streamData()）回放我的抓包：
```
sse-hold.txt  events=2 sawDONE=true  finish=max_tokens   ← 等到 FIN 才结束
sse-rst.txt   events=1 sawDONE=false finish=null         ← 永远不结束
sse-hang.txt  events=1 sawDONE=false error={"message":"empty_stream..."}
```

**修复建议**：在 `antigravity_executor_stream.go:371` 处，当 `isTerminalChunk` 且响应格式为 OpenAI chat-completions（该格式不需要后续帧）时，主动 `break` 出 scanner 循环并走 397 的 [DONE] 合成路径。注意 `terminal_disconnect_test.go` 覆盖的 "客户端在终止帧后断开" 场景仍要保留现有用法统计逻辑。

---

### 2) 空正文（思维链吃满 max_tokens）—— **干净**

请求 `max_tokens:20, reasoning_effort:high`，假上游只回 thought 文本 + MAX_TOKENS：
```
data: {...,"delta":{"role":"assistant","content":null,"reasoning_content":"先想一想。…"},"finish_reason":null,...}
data: {...,"delta":{"role":"assistant","content":null,"reasoning_content":"…"},"finish_reason":null,...}
data: {...,"delta":{"role":null,"content":null,"reasoning_content":null},"finish_reason":"max_tokens","native_finish_reason":"max_tokens"},"usage":{"completion_tokens":200,...,"reasoning_tokens":200}}
data: [DONE]
```
- `content` 字段存在但为 `null`（不是空字符串，也不是缺失）—— 符合 OpenAI 语义。
- `finish_reason: "max_tokens"`，有 `[DONE]`。
- 结论：**空正文不会导致卡住**，协议层是对的。

（相关代码：`internal/translator/antigravity/openai/chat-completions/antigravity_openai_response.go:68-91` 的 [DONE] 分支；`:233` `isFinalChunk := params.UpstreamFinishReason != "" && usageMetadata.Exists()`）

---

### 3) usage 块位置 —— **干净，只在终止帧**

- 中间内容帧：`"usage"` 键完全不存在（`FilterSSEUsageMetadata` 把非终止帧的 usage 改名成 `cpaUsageMetadata` 后剥掉）。
- 终止帧：`usage:{completion_tokens,total_tokens,prompt_tokens,completion_tokens_details.reasoning_tokens}`。
- `[DONE]` 帧：**不带 usage**。
- ST 完全不读 usage（grep 无命中），所以「缺 usage 导致不结束」这个假设**不成立**。

额外验证了两种分帧：
- `usage 帧在前、finish 帧在后` → 合并到终止帧正确输出 `finish_reason: max_tokens` + usage + [DONE]。
- `只有 finish 不带 usage` → 仍正确输出 `finish_reason: max_tokens` + [DONE]。

---

### 4) keep-alive —— **格式干净，但会掩盖问题 1**

真机配置 `requests.streaming.keepalive-seconds: 15`，实测间隔正好 15.0s：
```
: keep-alive
```
是一个标准 SSE **注释行**（冒号开头），不是 `data:` 帧。

- ST 的 `sse-stream.js:37` `if (eventData === '') continue;` 会直接丢弃 —— **不会干扰解析**。
- 但它的副作用是致命的：在上游静默时，客户端看到的是「连接活着、一直在心跳」，于是**永远不会超时**。问题 A/C 因此表现为「无限生成中」而不是「报错退出」。

---

### 5) SAFETY / content_filter —— **干净**

```
data: {...,"content":"部分内容"...,"finish_reason":null,...}
data: {...,"delta":{"role":null,...},"finish_reason":"content_filter","native_finish_reason":"safety"},"usage":{...}}
data: [DONE]
```
映射表在 `antigravity_openai_response.go:292`：`SAFETY, RECITATION, BLOCKLIST, PROHIBITED_CONTENT, SPII, IMAGE_SAFETY, MALFORMED_RESPONSE, MALFORMED_FUNCTION_CALL` → `content_filter`。ST 见 [DONE] 正常收尾。

---

### 6) 中途 error 帧 —— **新问题 B：没有 [DONE]**

```
data: {...,"content":"写了一半"...,"finish_reason":null,...}
data: {"error":{"code":503,"message":"backend capacity exhausted","status":"UNAVAILABLE"}}
（连接结束，没有 [DONE]）
```

**根因**：`sdk/api/handlers/stream_forwarder.go:242-252` 的 `case errMsg, ok := <-errs` 分支走 `WriteTerminalError`（`openai_handlers.go:838-849`）后直接 `cancel(execErr); return`，从不调用 `WriteDone`（`openai_handlers.go:862`）。

**影响**：ST 侧 `tryParseStreamingError`（openai.js:1648）会 `throw`，被 `generate()` 的 catch（script.js:3899）接住 → `onErrorStreaming()` 收场，**ST 能自愈**。但任何「只认 [DONE]」的严格客户端会卡住。

**建议**：`WriteTerminalError` 之后补一次 `WriteDone()`，让错误也以 `data: [DONE]` 收尾。注意 `apply_patch_repair_test.go:102` 断言了「error 时不得出现 [DONE]」——那条断言针对的是 Responses 格式，OpenAI chat 格式应分开处理。

---

## 三、其他新发现

### C. 上游半开/静默 —— 无超时保护（永久 keep-alive）

`antigravity_executor_stream.go:113` 传 `timeout=0`；AGENTS.md:60 明确「连接建立后不得设网络超时」。这是设计取舍，但对移动 NAT 掉线是硬伤：客户端永远等不到结束。

**建议**：在 **handler 层**（而不是 executor 层）加一个「终止帧已到但上游未 EOF，超过 N 秒主动收尾」的守卫，绕开 AGENTS.md 对 executor 超时的禁令。这与问题 A 是同一个修复点。

### D. 默认 thinkingLevel=high 把 ST 的预算吃光（**最像「截断」的根因**）

`go-bridge/cliproxymobile/mobile.go:622` `applyThinkingVisibility` 写入 override：
```yaml
params:
  generationConfig.thinkingConfig.includeThoughts: true
  generationConfig.thinkingConfig.thinkingLevel: "high"     # mobile.go:33 thinkingLevelDefault
```

真机实测（真实 Gemini 上游）：

| max_tokens | reasoning_tokens | 正文 | finish_reason |
|---|---|---|---|
| 32 | 29 | 0 字 | max_tokens |
| 64 | 59 | 2 字 | max_tokens |
| 128 | 119 | 13 字 | max_tokens |
| 300（**ST 默认**，openai.js:423） | 285–289 | 9–42 字 | max_tokens |
| 600 | 572 | 25–33 字 | max_tokens |
| 1500 | 1267 完成 | 完整 | **stop** |

**结论**：ST 默认 `openai_max_tokens = 300` 时，95–97% 的预算被思维链吃掉，用户每次只能拿到几十个字，`finish_reason` 恒为 `max_tokens`。这在 ST 里看起来就是「生成到一半被截断」。**不是协议 bug，是默认配置与 ST 默认值的冲突。**

**建议**（三选一）：
1. 把 `thinkingLevelDefault` 降到 `low`；
2. 或者默认关闭 `includeThoughts`（关掉后 reasoning 仍消耗 572/596，因为思考是模型行为，只影响是否回传内容——实测 OFF 时 reasoning_tokens 一样高，正文一样短，所以**这条不管用**）；
3. **最优**：在 `applyThinkingVisibility` 之外，对 `max_tokens < 800` 的请求自动降 `thinkingLevel` 到 `low`，或在 README 里明确要求 ST 用户把 Response Length 调到 ≥1500。

### E. `/v8/management/config` PUT 是整体覆写，不是合并

我用 `PUT /v8/management/config` 只传 `{"requests":{"payload":...}}`，结果整份 config.yaml 被削成 11 行，丢了 server/access/management/oauth/routing/observability。深挖发现是 `config_v8.go` 的 `ConfigV8` 语义（写什么存什么）。App 侧用的是 yaml.Node 原地 `setPath`，不受影响，但如果面板走 v8 API 改配置会踩坑。**排查中我误伤了设备配置，已用完整备份还原并重启验证（68 行，models=200，流式正常）。**

### F. 设备日志里的 `/sdcard/.cli-proxy-api` 报错

`error reading auth directory: open /sdcard/.cli-proxy-api: no such file or directory`（每次 reload 刷两条）。这是某条 reload 路径回退到默认 auth-dir 造成的，不影响主流程，但建议在 `mobile.go` 里把默认路径改成 `filesDir`。

---

## 四、复现环境（已留在仓库）

- `diag/mock_upstream.py`：17 个可编排场景（normal / thinking_only_max / safety / mid_error / no_finish / split_usage / finish_then_hold / hang_no_data / truncate_rst / mid_silence / empty_body / empty_candidates / envelope_only / usage_only_close / e429 / e500 / json200_error / plain_text_200 / slow_then_429）
- `diag/drive.py`：干净重启 + 单次请求 + 存档
- `diag/evidence/sse-*.txt`：全部原始抓包
- 端口用 28317/9911，不占真机的 8317/18317

---

## 五、给 ST 用户的立即可用建议

1. **Response Length 调到 1500 以上**（默认 300 会被思维链吃光）——这是最立竿见影的。
2. 如果仍偶发卡住，重发一次即可（问题 A 的触发条件是上游 FIN 延迟）。

---

**未改动任何 Go 代码**（按要求只诊断）。设备配置已还原并验证。