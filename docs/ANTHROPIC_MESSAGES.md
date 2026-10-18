# Anthropic Messages 对话格式支持

开发分支：`feature/anthropic-messages`。目标：配置选了「接口格式 = Anthropic」后，**对话也走 Messages API**（目前 Anthropic 格式只在联网搜索后端里用，对话仍走 OpenAI 兼容路径）。

## 范围

基础对话（流式）+ 工具调用 + 提示缓存。标题、输入建议、设定补全三个小请求同样跟随格式。图片生成、漫画分析、原生 Skills 上传不在本次范围（它们本就是 OpenAI/Gemini 协议，配置为 Anthropic 时不受影响）。

## 路由规则

- `usesResponses(model)`：GPT / Grok → Responses API（不变）。
- 新增 `usesAnthropicMessages(profile, model)`：**`profile.apiFormat == anthropic` 且模型非 GPT/Grok** → Messages API。Step 模型也走这里（Step 的 Messages 端点本就是 Anthropic 兼容，但无服务端 web_search 工具，用委托搜索工具替代）。
- 其余 → Chat Completions（不变）。
- 模型切换无需改配置：同一份 Anthropic 配置里填 GPT 模型会自动落到 Responses（Anthropic 网关大多不代理 GPT，此时报错信息里写明实际路径即可）。

## 请求映射（Messages API）

| 项 | 映射 |
|---|---|
| 端点 | `profile.messagesPath`（默认 `/v1/messages`），POST |
| 认证 | `x-api-key` + `anthropic-version: 2023-06-01`（无 key 时不带头，兼容本地网关）；不再带 `Authorization: Bearer` |
| system | 顶层 `system` 字段（字符串），不再是 messages[0] |
| 消息 | role user/assistant；content 为块数组：text / image(base64 source) / tool_use / tool_result |
| max_tokens | 必填；缺省取 `contextLimits.outputTokens` 或 8192 兜底 |
| 采样 | temperature / top_p 直接同名；**不发** frequency_penalty / presence_penalty / seed / stop（Anthropic 拒绝未知参数；stop_sequences 另议） |
| 思考 | 沿用 `ReasoningPolicy` 的 Claude 分支（thinking.budget_tokens），已兼容 |
| 流式 | `"stream": true`，SSE 事件流（无 [DONE] 哨兵，message_stop 结束） |

## SSE 事件解析

- `content_block_start`（block.type = text / thinking / tool_use / server_tool_use）→ 开块
- `content_block_delta`：`text_delta` → 正文；`thinking_delta` → 思考；`input_json_delta` → 工具参数累计
- `content_block_stop` → 收块（tool_use 产出 PendingToolCall）
- `message_delta`：`delta.stop_reason`（end_turn/stop_sequence → 完成；tool_use → 执行工具续轮；max_tokens → outputComplete=false）+ `usage`（output_tokens 累计）
- `message_start` → usage（input/cache 读写在 message_start.usage 与 message_delta.usage 分开）
- `ping` / `error`：ping 忽略；error 抛异常

## 工具调用（基础）

- 客户端工具（create_file / load_skill / read_skill_file / ask_user / 委托 web_search）映射为 Anthropic `tools`：`{name, description, input_schema}`（原 parameters 改名 input_schema，去掉 OpenAI 的 strict/additionalProperties 包装差异：additionalProperties:false 保留）。
- `tool_choice`：forceSkill → `{type:"tool",name:"load_skill"}`；forceNoTools → `{type:"none"}`；默认 `{type:"auto"}`。
- 助手回合回写：`content` 含 text 块 + `tool_use` 块（id/name/input JSON 对象）；工具结果以 **user 角色**消息回写，content 为 `tool_result` 块（tool_use_id, content）。
- Anthropic 原生服务端 web_search 不在对话启用（保持现有「委托搜索后端」架构，Anthropic 格式配置作为搜索后端时已经有原生 web_search）。

## 提示缓存（重点）

- 沿用 `PromptCache.markClaudeMessages`：system + 倒数第二条 + 最后一条会话消息打 `cache_control: {type: ephemeral, ttl: 1h}` 断点。
- 适配：Chat 路径的 system 是 messages[0]，Messages 路径要把断点打到顶层 `system`（转块数组）；工具续轮时**新追加的消息不重新打旧断点**（断点随消息数组重算即可，幂等）。
- usage 解析已兼容 cache_read_input_tokens / cache_creation_input_tokens（parseUsage 无需改动）。
- 断点最多 4 个，当前用 3 个，安全。

## 失败回退

- Messages 请求若返回「不支持/未知参数/404」类错误（`isCacheCompatibilityError` 同款判定），本轮自动去掉 cache_control 断点重试一次，再失败才抛给用户。

## 测试

- `AnthropicMessagesTest`（Robolectric）：请求体映射（system 提升、图片块、max_tokens 兜底）、SSE 事件流解析（text/thinking/tool_use 交叉、stop_reason）、工具续轮的消息回写形状（tool_use/tool_result）、缓存断点位置、usage 汇总。
- 禁止本地 Gradle，CI `testDebugUnitTest assembleRelease` 为准。
