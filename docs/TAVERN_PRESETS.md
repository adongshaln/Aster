# 酒馆预设兼容

Aster 的故事正文工作区支持 SillyTavern / 酒馆 JSON 预设。入口位于「故事选项 → 酒馆预设」。

## 已实现

- 导入、选择、停用和删除本地 JSON 预设
- 内置 `Izumi 1th Anniv1`
- 配置页完整列出提示词与 Regex，明确标注每一项的文件默认状态，并支持名称 / 内容搜索、启用状态筛选、逐项开关、内容查看与恢复默认
- 用户选择按预设分别持久化；更新单项不会改写原始 JSON，切换预设后仍保留各自配置
- 按 `prompt_order` 与各条目的启用状态组装提示
- 保留 system / developer / user / assistant 角色顺序，并在 `chatHistory` 标记处插入 Aster 故事上下文
- 展开 `setvar`、`getvar`、`random`、`roll`、`user`、`char`、`lastUserMessage`、`lastCharMessage`、`date`、`time` 与 `trim`
- 映射 `temperature`、`top_p`、`frequency_penalty`、`presence_penalty`、`seed` 与 `openai_max_tokens`
- Regex 支持角色范围、Min / Max Depth、`g/i/m/s/u` 标志、捕获组、`{{match}}`、Trim Out，以及 Alter Outgoing Prompt / Alter Chat Display 语义
- 请求正则只修改发给模型的临时副本；显示正则只修改当前渲染，不改写故事数据库

`prompt_order` 是提示词是否进入请求的权威默认值。文件中存在、但没有出现在 `prompt_order` 的提示词默认视为停用；用户手动启用后会追加到有效顺序末尾。Regex 的逐条开关与全局 Regex 开关相互独立：关闭全局开关不会丢失逐条选择。

## 安全边界

预设是用户选择的创作配置，不能覆盖 Aster 的故事工作区隔离、正式资料、工具和安全规则。

Regex 替换生成的 HTML 会在禁用 JavaScript、网络、文件、表单和父页面访问的离线沙箱中预览。预设内的 Tavern Helper / STscript 内容会原样保留在导入文件或内置资源中，但 Aster 不执行这些第三方脚本，也不会把“已保存脚本”误报为“已运行脚本”。

## 当前差异

Aster 尚未实现角色卡、World Info 和用户 Persona，因此对应 marker 会保留顺序位置但不注入虚构的空数据。供应商专用的 `top_k`、`top_a`、`min_p` 与 `repetition_penalty` 会保留在原 JSON 中，但不会盲目发送给不兼容的 OpenAI 风格接口。
