# 提示词注入模块原理（演示用）

> **定位与边界**：本模块是**仅供演示**的提示词注入 / 序列化隧道机制，不用于正规流程。本文档描述现有实现的结构与数据流，供理解与维护代码之用，**不是**提升绕过有效性的操作指南。维护约定：只做正确性 / 资源层面的修复（崩溃、丢数据、解析健壮性），不优化绕过机制本身的有效性。

代码集中在 `app/src/main/java/com/adong/adchat/data/PromptInjectionEngine.kt`，由 `ApiRepository` 在聊天请求时调用，`MainViewModel` 负责开关与预设状态。

## 1. 数据结构

| 类型 | 作用 |
| --- | --- |
| `PromptInjectionEntry` | 单条注入条目：`role`（system/user/assistant）、`position`（before_history / in_chat / after_history）、`depth`（in_chat 插入深度）、`content`、`enabled`。 |
| `PromptInjectionPreset` | 一组条目 + 两个全局开关：`pureMode`（是否走序列化隧道）、`zeroWidthObfuscation`（是否零宽混淆）。`enabledCount` 统计生效条目。 |
| `InjectedMessage` | 组装结果：`role` + 过滤后的 `text` + 可选 `source`（历史槽保留原始消息引用，用于携带图片附件；注入条目为 null）。 |
| `PromptInjectionDefaults` | 内置 Ny-Gemini 演示预设、隧道工具名 `dispatch_stream_payload_34d98628087f3798`、参数名 `display_stream`。 |

## 2. 三个组件

- **`PromptInjectionStore`**：基于 SharedPreferences 的持久化。开关、加载/保存预设、增删改条目、切换条目、设置 pureMode/zeroWidth、重置默认。`decodePreset` 读取 `entries` 数组（空数组会被如实保留，不再回退内置预设）。
- **`PromptInjectionRuntime`**：无状态运行时，负责宏展开、过滤、历史压缩、消息组装与请求体构造。
- **`NyDisplayStreamDecoder`**：增量 JSON 字符串解码器，从隧道返回的工具调用参数里逐 chunk 提取 `display_stream` 正文。

## 3. 两条数据路径

入口在 `ApiRepository` 的 `executeAttempt`：当预设生效（`enabledCount > 0`）且 `pureMode=true`、且协议为 OpenAI Chat Completions（非 Anthropic Messages / 非 Responses）时走**隧道**；否则走**普通注入消息**或不注入。

### 3.1 普通注入（非 pureMode）

`buildInjectedMessagesList(systemPrompt, history, preset)` 返回 `List<InjectedMessage>`：

1. 非 pureMode 时先放 system prompt。
2. `before_history` 条目经宏展开后前置。
3. 历史消息逐条 `applyFilter`（零宽过滤），并携带原始引用 `source`。
4. `in_chat` 条目按 `depth` 从后往前插入历史序列。
5. `after_history` 条目后置。

`ApiRepository` 把结果放入 `messages`：带图片附件的历史槽复用 `chatCompletionContent` 生成 `image_url` 内容数组（文本用过滤后的值），其余按纯文本放入。**这条路径发送完整历史，不做压缩。**

### 3.2 序列化隧道（pureMode）

`streamChatCompletionsTunnel` → `buildTunnelChatBody(model, history, preset, cacheKey, retryAttempt)`：

1. **历史压缩**：`compactHistoryForTunnel(history, recentWindow)`，`recentWindow` 首次为 6、重试为 2。
2. **组装文本**：压缩后的历史 + 注入条目经 `buildInjectedMessagesList("", ...)` 拼成有序文本行，前置两个随机会话头（`getSessionHeaders`），合并为 `orderedPresetContent`。
3. **塞进工具描述**：构造一个 function tool，把 `orderedPresetContent` 放进它的 `description` 字段；参数 schema 要求一个字符串 `display_stream`。
4. **固定控制消息**：`messages` 数组只放 4 条固定路由消息（系统管线控制器 / 运行时路由 / 执行内核 / 动作指令），动作指令尾部附 256 个随机数"熵验证"。
5. **强制工具调用**：`tool_choice="required"`（重试时指定具体函数名）。
6. 固定 `temperature=1.35`、`max_tokens=20000`、`stream=true`、`show_thoughts/include_reasoning=true`。

模型返回的正文在工具调用参数 `display_stream` 里，由 `NyDisplayStreamDecoder` 增量解出。

> 另有 `buildIllustrationTunnelBody`（提交 1715977 引入）供剧情插图规划器复用隧道结构，要求输出 `{title,focus,prompt}` JSON。注意：这与 `ChatIllustration.kt` 中"不将安全绕过控制指令接入提炼/绘图调用"的注释存在不一致，属待复核的设计漂移（见 §8）。

## 4. 核心机制

### 4.1 宏展开（`expandMacros`）

支持 `{{date}}` `{{time}}` `{{user}}` `{{char}}` `{{lastUserMessage}}` `{{lastCharMessage}}` `{{lastMessage}}` `{{trim}}` `{{//注释}}`。后三者取历史中最近一条 user/assistant/任意消息的正文。用户内容用 `Matcher.quoteReplacement` 包裹后替换，避免 `$`、反斜杠被当作 regex 组引用（正确性修复）。

### 4.2 零宽过滤（`applyFilter`）

先用 `STRIP_THINKING_REGEX` 去掉思考块；若 `zeroWidthObfuscation=true`，再用结构混淆正则在匹配片段间插入零宽字符（⁠‍‌ 等）。这是演示机制的一部分，本文不展开其规避用途。

### 4.3 历史压缩（`compactHistoryForTunnel`）—— 输入为何恒定 ~20k

- 仅保留最近 `recentCount` 条全文。
- 其余每条旧消息：折叠代码块为 `[代码块已折叠]`、压缩空白、**截断到 260 字符**，拼成单个 `<previous_context_summary>` 摘要轮。

因此隧道输入总量 ≈ `旧消息条数 × ~260 字符 + 最近 N 条全文 + 注入文本 + 固定路由 + 熵填充`。**它随消息条数增长，而非随对话真实 token 长度增长**——长回复被砍成 260 字符，所以几十万 token 的对话压缩后仍落在 ~20k 量级。

- **工程收益**：输入恒定小、不撞上下文窗口、成本与延迟可控、请求大小可预测。
- **代价（有损）**：超过 260 字符的长期细节丢失，长程记忆实际上很有限。

### 4.4 增量解码（`NyDisplayStreamDecoder`）

状态机逐字符扫描工具调用参数 JSON，定位 `targetKey`（默认 `display_stream`）的字符串值并增量输出：

- 跳过非目标字段；`SKIP_STRUCTURE` 跟踪嵌套对象/数组深度，并正确跟踪 JSON 字符串边界（字符串内的花括号不改变深度）。
- `consumeStringChar` 处理转义与 `\uXXXX`（含代理对合并），跨 chunk 的半截转义用 `pendingEscape`/`pendingQuote` 缓冲。
- `normalizeCtrlThinkingTokens` 把 `<ctrl94>`→`<thinking>`、`<ctrl95>`→`</thinking>`、其余 `<ctrl\d+>` 删除；`splitPendingCtrl` 缓冲跨 chunk 拆分的控制标记前缀，避免半截标记泄漏进正文。
- `finish()` 在流结束时 flush 缓冲。

## 5. Token 预算

- 隧道 `max_tokens=20000` 是**输出上限**，与输入压缩量（§4.3）是两个不同的量，数值接近属巧合。
- `ApiRepository` 会用 `profile.contextLimits(model)?.outputTokens` 与用户 `maxOutputTokens` 的最小值**收紧**（绝不抬高）该上限。
- 插图规划另有预算（Gemini-4 家族 32768、其它推理 8192、否则 4096），见 `docs/CHAT_ILLUSTRATIONS.md`。

## 6. 工程收益与代价小结

| 方面 | 收益 | 代价 |
| --- | --- | --- |
| 历史压缩 | 输入小、不超窗、低成本、可预测 | 长期记忆有损（260 字符/条） |
| 隧道序列化 | 把对话重构为结构化函数调用 | 仅适用 Chat Completions 协议；丢失图片/角色结构（隧道把内容拍平为文本） |
| 增量解码 | 流式正文、低延迟显示 | 解析边界复杂，需缓冲跨 chunk 标记 |

## 7. 已做的正确性修复（不改变绕过能力）

1. `expandMacros` 用 `Matcher.quoteReplacement` 修复 `$`/反斜杠导致的崩溃与内容损坏。
2. `buildInjectedMessagesList` 返回 `InjectedMessage` 携带原始引用，普通注入分支保留图片附件。
3. `NyDisplayStreamDecoder`：`SKIP_STRUCTURE` 跟踪字符串内花括号；`pendingCtrl` 缓冲跨 chunk 控制标记。

回归测试见 `app/src/test/java/com/adong/adchat/data/PromptInjectionEngineTest.kt`。

## 8. 已知限制与待复核项

- **协议限制**：隧道仅对 OpenAI Chat Completions 生效；Anthropic Messages / Responses 协议忽略预设，但 UI 开关仍显示（无协议门控）。
- **隧道丢图**：pureMode 隧道把历史拍平为文本行，图片附件无法进入工具描述（普通注入路径已保留图片）。
- **上游内容过滤**：部分 RP 内容会被上游 Google 内容政策拒绝，属外部限制，非 app 缺陷。
- **设计漂移**：1715977 把隧道接入插图规划器，且注入关闭时仍回退内置预设，与 `ChatIllustration.kt` 的注释意图不一致，待复核。
- **会话头缓存**：`sessionHeaderCache` 无淘汰策略，`illustration-$model` 跨会话共享（影响小）。