# 2026-10-27 待处理清单

手机当前**没有安装这个 App**（我执行 uninstall 后重新安装被 MIUI 拦下）。
解锁后到「设置 → 更多设置 → 开发者选项 → USB 安装」打开，然后：

```bash
cd /root/AgyAndriod
scripts/adbw.sh install -r app/build/outputs/apk/debug/app-debug.apk
```

产物已经构建好，就在上面那个路径（67 MB，含 arm64 + x86_64 双 ABI）。

---

## 本轮已改完、但没能上机验证的

| 需求 | 状态 | 说明 |
| :--- | :--- | :--- |
| 开关再缩到 75% | 已改 | 34×21，加了 padding 保触摸区 |
| 额度拆两条 + 具体重置时间 | 已改 | 只留 5 小时 / 每周两行，时间显示 `14:12` 或 `10月31日 14:12` |
| 状态栏额度标账号、换行 | 已改 | `Antigravity · 邮箱` 一行，额度缩进一行 |
| 服务地址补 /v1 | 已改 | 状态卡显示 `127.0.0.1:8317/v1` |
| 切主题不弹提示 | 已改 | 删掉 toast |
| 白天→跟随系统图标不更新 | 已改 | 比较 mode 而非仅比较解析后的配色，并用 setResult 通知主页重建 |
| 模型列表缺 3.8 flash 档 | 已改 | 见下 |
| 思维链在 ST 不可见 | 已查清 | 见下 |
| 酒馆卡在「生成中」 | 已修 | 见下 |

---

## 三个上游问题（已写进 UPSTREAM_ISSUES.md，补丁在 upstream-patches.diff）

### A. max_tokens 被无条件丢弃 —— 这就是「生成中」卡住的原因

`antigravity_executor_request.go` 把所有非 Claude 模型的
`request.generationConfig.maxOutputTokens` 删掉了。实测 `max_tokens: 1` 的请求
上游生成了 3289 token。删掉之后上游不会以 `MAX_TOKENS` 结束，一旦流中断，
客户端收到的 `finish_reason` 还是 `stop`（甚至没有终止帧），于是它以为还在生成。

直接打上游验证过：`maxOutputTokens` 是**被上游支持**的，会正确返回
`finishReason: "MAX_TOKENS"`。删它纯属多余。

已改为保留，改后实测：

```
"finish_reason":"max_tokens","native_finish_reason":"max_tokens"
data: [DONE]
```

原测试断言「必须删除」，已反向改成断言保留。

### B. 模型目录只有 3.8 flash high

`models.json` 的 antigravity 段只定义了 `gemini-3.8-flash-high`，远程目录同样如此。
但模型本身支持低中档 —— 用 thinking 后缀能寻址：

```
gemini-3.8-flash-high(low)     -> 200
gemini-3.8-flash-high(medium)  -> 200
gemini-3.8-flash-low           -> unknown provider
```

缺的只是目录条目。已在 models.json 补上 low / medium 两条，
并把这份表打进 APK 资产（`app/src/main/assets/models.json`），
用 `models.catalog` 指向 `filesDir/models.json`，避免启动时被远程目录覆盖。

顺带修了一个自己引入的 bug：给已有 config.yaml 打补丁时把 `DocumentNode`
直接传给 setPath，导致整份配置被清空（引擎起在 `:0`）。已修并加了两条回归测试。

### C. 思维链需要显式 reasoning_effort

不是 bug，是触发条件。上游确实思考了（`reasoning_tokens: 62`），
但 `reasoning_content` 只在带 `reasoning_effort` 时回填：

| 请求参数 | reasoning_content |
| :--- | :--- |
| 不传 | null |
| `reasoning_effort: "low"` | 空 |
| `reasoning_effort: "medium"` | **有** |
| `reasoning_effort: "high"` | **有** |
| `include_thoughts: true` | 空（OpenAI 路径不认这个字段） |
| `thinking: {type: enabled}` | 空 |

翻译层是对的（`antigravity_openai_response.go:169` 会把 `thought: true` 的文本
写进 `delta.reasoning_content`），只是 `includeThoughts` 没被自动打开。

**给 ST 的解法**：请求里带 `reasoning_effort`（SillyTavern 的 Reasoning Effort
设成 medium 或 high，或用自定义请求体）。

**服务端默认开启的做法**（我没做，因为会改变所有客户端的 token 消耗）：
在 `normalizeAntigravityOpenAIThinkingConfig` 里，请求没有任何 thinking 字段时
默认写入 `request.generationConfig.thinkingConfig.includeThoughts = true`。

---

## 需要你决定的两件事

1. **思维链要不要服务端默认开启？** 默认开会增加每次请求的输出 token（思考内容本身
   也计费），但省掉在 ST 里手动配 reasoning_effort。
2. **要不要把这些上游补丁提到 router-for-me/CLIProxyAPI？** 我可以整理成 issue + PR
   的形式，也可以只留在本地。
