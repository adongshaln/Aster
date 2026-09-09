# Aster Skills Runtime

当前实现位于 `feature/skills-runtime`，基线为 Aster 2.4.0 / versionCode 58。此文档描述的是实际请求与工具执行边界，不把提示词注入称作 Skill 加载。

## 用户入口

用户可以在普通聊天中发送公开 GitHub Skill 链接并明确要求加载/使用 Skill。支持：

- 仓库根链接：`https://github.com/<owner>/<repo>`，要求仓库根存在 `SKILL.md`；Aster 会通过 GitHub API 解析真实默认分支。
- 文件链接：`https://github.com/<owner>/<repo>/blob/<ref>/.../SKILL.md`。
- 目录链接：`https://github.com/<owner>/<repo>/tree/<ref>/<skill-directory>`，Aster 读取该目录下的 `SKILL.md`；原生 Skills 路径会把该目录的完整文件集打包。
- Raw 链接：`https://raw.githubusercontent.com/<owner>/<repo>/<ref>/.../SKILL.md`。

仅接受 HTTPS 的 GitHub / raw.githubusercontent.com。当前不读取私有仓库 token，也不允许任意外部主机冒充 Skill。

## Responses：优先真实 Skills API

对 Responses 路由，Aster 会先解析用户给出的 GitHub Skill，下载指定 Skill 目录并生成受限 ZIP，然后调用当前 API Profile 对应的真实 `POST /v1/skills` 接口。成功响应必须返回服务端签发的 skill id；Aster 随后在 Responses 请求中注册 `shell` 工具，使用 `container_auto.skills[].type = skill_reference`、该 skill id 和版本，并强制首轮使用 shell。

这与 OpenAI 当前 Skills / Responses beta schema 对齐：`POST /skills` 接受目录文件或单个 ZIP；`container_auto` 支持 `skills`，其中 `skill_reference` 引用由 `/v1/skills` 创建的 skill。

Aster 不会在 `/skills` 失败时伪造成功。404 / 405 / 501 及明确的“不支持该 endpoint”响应会被识别为兼容性不支持；认证、限流、服务端错误、非法成功响应等则直接作为失败暴露。

## 兼容接口：真实 function-call fallback

不是所有 OpenAI 兼容网关都实现 `/skills` 或 Responses 的 shell / container skills。遇到明确的不支持时，Aster 使用 `load_skill(url)` function tool 回退，而不是把 Skill 文本偷偷拼进 system prompt。

完整调用链：

1. Aster 把 `load_skill` 作为真正的 function tool 注册给模型，并在明确的 Skill 加载请求首轮强制调用；其参数 schema 使用 `enum` 只允许本轮用户明确给出的 GitHub URL，或本轮明确点名的已安装 Skill。
2. 模型返回 `load_skill` function call。
3. Aster 通过 HTTPS 实际下载对应 `SKILL.md`，执行大小、UTF-8、目标主机和路径校验，并计算 SHA-256。
4. Aster 将 `name`、原始 URL、最终 raw URL、SHA-256 和下载到的 **原始 SKILL.md 内容**作为 tool result 返回模型。
5. 客户端执行工具时还会再次检查同一允许列表，因此即便兼容网关忽略 JSON Schema，模型也不能自行编造另一个 GitHub URL；校验通过后，模型才在下一轮基于 tool result 继续推理。

Chat Completions 直接使用这条 function-call 路径；Responses 在原生 Skills 不可用时使用同一回退路径。

## 可靠性与安全边界

- 单个直接加载的 `SKILL.md` 最大 256 KiB。
- 原生 GitHub 仓库压缩包最大 16 MiB；选中 Skill 解压内容最大 10 MiB；单文件最大 5 MiB；最多 256 个文件。
- ZIP 重新打包前拒绝空路径、绝对路径、`.` / `..` 与 NUL，避免目录穿越。
- 原生 Skill bundle 必须在选中的 Skill 根目录存在唯一 `SKILL.md`；多 Skill 仓库应发送具体 Skill 的 `tree` 目录链接。
- function fallback 的 tool output 标记 `trust=untrusted_external_instructions`。外部 Skill 内容不能覆盖更高优先级的系统、安全或应用约束。
- Aster 只有在真实网络读取成功，或真实 `/skills` 上传成功并取得 provider skill id 后，才显示对应成功状态。
- 开发分支测试使用可注入 loader/uploader 与 MockWebServer 验证协议，不把 mock 成功描述成真实 OpenAI 项目上传成功；最终仍需真实服务和真机验收。

## 自动验证

测试覆盖：GitHub root/tree/blob/raw 解析、非法目标拒绝、真实 function tool 定义、精确 tool result 回传、Chat 与 Responses 两轮 function-call、`/v1/skills` multipart POST、认证/额外 Header、provider skill id/version 解析、shell `skill_reference` 请求结构，以及原生 Skills 不支持时回退到实际 `load_skill` tool round-trip。

当前代码树已移除所有一次性补丁脚本与临时工作流；后续 Android CI 只验证正式实现文件和测试。