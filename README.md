# Aster

<p align="center">
  <img src="Aster_icon.png" alt="Aster" width="120" />
</p>

<p align="center">
  原生 Android 多 API AI 客户端，面向对话、故事创作、绘图、多模态与内容工具工作流。
</p>

Aster（原 ADChat）是一个使用 Kotlin 与 Jetpack Compose 构建的原生 Android 客户端。它可以将对话与绘图分别路由到不同的 OpenAI 兼容服务，并在统一界面中管理模型、推理、图片、工具调用、故事资料与历史任务。

> 当前稳定版本：**2.4.1** · `versionCode 59`
>
> 完整历史更新请查看 [CHANGELOG.md](CHANGELOG.md)。

## 主要能力

### 对话

- 支持 **OpenAI Responses API** 与 **Chat Completions** 双协议
- SSE 流式输出、多轮上下文、新建对话、复制、重试、继续生成与重新生成
- 支持推理强度、缓存信息、Token / 耗时等运行状态展示
- 支持一次选择最多 4 张图片进行多模态对话
- 流式中断时保留已生成内容，并提供安全续传与手动继续生成
- 每个会话拥有独立草稿，历史任务与生成状态可持久恢复
- 长回复采用适合持续阅读的流式排版与平滑跟随滚动
- 普通聊天与故事模式共享标题栏、输入胶囊、长草稿编辑器、等待动画、附件与阅读基础组件

### 故事模式

- 独立的故事、讨论 / 正文工作区与 SQLite 数据层，不改写普通聊天历史
- 自动整理人物、关系、人物认知、状态、剧情事实、作者计划、候选与长篇摘要
- 资料保留来源，可处理疑似冲突、候选确认、讨论结论应用与来源复核
- 支持历史正文重写、修改旧请求后另写、路线切换，以及受限的撤销 / 恢复
- 请求失败、超时或未完整结束后可直接重新生成，并保留旧失败修订用于追溯
- 故事正文支持导入 SillyTavern JSON 预设；内置 Izumi 1th Anniv1，可按文件默认值逐项选择提示词与 Regex，并执行提示顺序、常用宏及兼容采样参数
- 整理任务、失败恢复、用量与版本状态持久化，完整正文才有资格进入正式记忆
- 固定资料、未整理正文和必要上下文受到预算保护，避免被静默裁掉
- 详细实现边界与验收矩阵见 [故事模式验收说明](docs/STORY_MODE_ACCEPTANCE.md)

### 模型上下文

- 每个 API Profile 下按**精确模型 ID**独立保存上下文窗口和输出上限
- 普通聊天与故事模式共享同一预算逻辑
- 模型选择器只为当前选中的模型展示 **128K / 256K / 512K / 1M** 快捷档位
- 设置页支持精确数值编辑、恢复默认以及配置导入 / 导出
- 上下文估算只用于客户端预算，不会根据模型名称猜测提供商真实容量

### 技能包

- 可从公开 GitHub 地址安装或从 ZIP 导入完整 Skill，并在设置中查看、更新、启用、停用与删除
- 普通聊天、故事讨论和故事正文分别选择本工作区可用的 Skill，每个工作区最多 4 个
- Chat Completions 与 Responses 均支持模型按需加载 Skill 说明及 UTF-8 参考文件
- GitHub Skill 保存固定提交的文件清单，较大仓库按需下载文件；重复读取会复用已有结果
- 单次用户请求最多进行 16 轮、48 次工具调用，防止错误模型行为形成无限循环
- 当前本地运行时不执行 Python、Node、Shell 或 Skill 内脚本；超出能力时会明确说明

### 对话工具与文件

- Responses API 支持原生 `web_search`
- Chat Completions 可兼容支持 `web_search_options` 与函数调用的第三方网关
- Chat 模型可将 Web / X 搜索委托给单独配置的 Responses 兼容搜索模型，并把结果交回原模型完成最终回答
- `create_file` 支持 Markdown、文本、JSON、CSV、HTML、PDF、DOCX、XLSX 与 PPTX
- PDF / Office 由客户端生成真实二进制文件，并随会话持久保存和导出
- 完整 HTML 默认直接显示内容预览，不在卡片主体暴露源码；保留复制代码、保存与全屏
- HTML 预览运行在受限制的离线 WebView 中，外部网络、文件访问、弹窗与父页面导航均受到限制
- 酒馆正则生成的 HTML 使用更严格的无脚本、无网络预览；Tavern Helper JavaScript 仅随原文件保留，不在客户端执行
- 工具执行状态、网页来源和已创建文件会随历史对话保存

> 当前 PDF 在 Android / Skia 下存在已知文字层边界：页面视觉显示正确，但部分中文复制或文本提取不保证逐字符等同于原始 Unicode。DOCX 不受此限制。

### 阅读体验

- 面向小说、角色扮演与日常长回复优化的正文排版与段落节奏
- 标题、列表、引用、代码块、行内代码与常见 Markdown 结构
- 支持 H1–H6 标题，避免较低层级标题直接露出 Markdown 井号
- GitHub 风格表格解析、对齐、换行、宽表横向滚动与 TSV 一键复制
- 流式生成采用连续出字与平滑跟随滚动，用户手动阅读时会停止自动抢滚动
- `『术语』` 会连同符号一起使用 Aster 暖棕色强调，适合名词、设定与专有概念
- 提问导航与快速回到底部，适合长对话阅读
- 底部阅读区域固定采用稳定渲染路径，避免触摸与静止状态之间出现毛玻璃视觉差异

### 多 API Profile

可以创建任意数量的 API 配置，每个 Profile 独立保存：

- 配置名称
- Base URL
- API Key
- 模型列表路径
- Chat Completions / Responses 路径
- 绘图接口路径
- 默认对话模型与绘图模型
- 额外 HTTP 请求头

同时支持：

- 对话与绘图分别选择不同的 API Profile
- 两套路由使用完全不同的 URL、Key 与模型
- `/v1/models` 模型发现、连接测试、延迟与错误诊断
- 手动填写未出现在模型列表中的模型 ID
- Profile JSON 导入 / 导出
- API Key 使用 Android Keystore + AES-GCM 加密保存

### 绘图与图片工作流

- OpenAI Images 兼容协议
- Gemini 图片模型协议，包括文生图、参考图编辑与 SSE 图片返回
- NAI / NovelAI Diffusion 兼容识别
- 最多两张普通参考图，多图编辑按兼容接口格式上传
- 作品历史、参数复用、保存、预览与全屏缩放
- 多任务并发生成，任务之间保持独立模型、提示词、画布与参考图快照

### 漫画翻译

- 辅助视觉模型先分析跨页剧情、人物关系、称谓、语气与术语
- 生成结构化翻译方案后，再交由图片模型逐页执行原位文字替换
- 支持批量页面、全局图片并发容量与失败页重试
- 辅助分析与绘图模型可以使用不同 API / 模型
- 对长任务使用流式分析与独立超时策略，减少网关主动断连造成的失败

### 媒体工作台

- 支持抖音分享文本、短链与常见视频链接解析
- 原生在线播放预览
- Range 分段下载、断点续传、取消与恢复
- 下载完成后执行 MP4 文件头校验
- 本地下载历史、播放、分享与删除

## Aster 2.4 当前体验

2.0 将项目正式从 ADChat 更名为 **Aster**，并继续保留 `com.adong.adchat` 应用 ID、原配置格式与已有数据兼容性。

2.1 统一采用暖白、浅棕与墨色视觉，重新整理聊天、创作、媒体下载、设置与侧栏布局，提升信息层级与操作入口的一致性。

2.2 将设置页收敛为服务列表与按需展开的高级选项，并把 GPT 模型的对话请求统一到 Responses API。

2.3 重做聊天阅读体验，让小说、角色扮演与日常长回复在手机上更接近轻量阅读器。

2.4 将此前独立开发的故事模式、共享聊天 UI、按模型上下文预算、HTML 内容预览以及 PDF / Office 文件生成整合为正式版本。

2.4.1 加入可独立配置的委托 Web / X 搜索，让 Chat 模型能够借助 Grok 兼容 Responses 网关获得实时资料，同时继续与 Skill 和文件工具共存。

当前体验重点包括：

- 普通聊天和故事模式采用同一套核心聊天组件，减少视觉与交互割裂
- Aster Logo 用于模型等待状态，采用原地轻跳与空中 90° 旋转的思考动画
- 共用长草稿编辑器、IME 跟随、附件入口、详情行为和消息阅读层级
- 每个模型可独立配置上下文窗口，快捷档位直接放在模型选择器中
- HTML 生成完成后直接显示内容预览，同时保留复制源码、保存和全屏
- 客户端可生成真实 PDF、DOCX、XLSX、PPTX，并作为二进制附件持久化
- 故事模式提供自动记忆、人物关系、时间线、冲突复核、历史路线和长篇摘要能力
- 支持安装完整 Skill 包，由模型按需读取说明和参考资料，并对重复读取与失控工具循环进行保护
- Chat 模型可委托独立搜索模型执行 Web / X Search，原对话模型仍负责最终回答并保留来源引用
- `『术语』` 使用暖棕色强调，适合世界观名词、专有设定与重要概念
- 底部阅读区域继续使用稳定渲染路径，不重新引入设备表现不一致的实时毛玻璃
- 保留原应用 ID、配置兼容与固定签名更新链路

更详细的 2.4.1 变更见 [CHANGELOG.md](CHANGELOG.md#241--2026-09-12)。

## 技术栈

- Kotlin
- Jetpack Compose
- Android SDK 36
- Java 17
- OkHttp
- Media3
- Haze
- PDFBox Android

应用保持原生 Android 架构，不依赖 WebView 作为主界面。HTML 内容预览及部分媒体解析场景会使用受控 WebView。

## 本地构建

需要 Java 17 与 Android SDK。

```powershell
$env:JAVA_HOME='C:\Java\jdk-17.0.18+8'
.\gradlew.bat testDebugUnitTest assembleDebug
.\gradlew.bat assembleRelease
```

未配置发布签名时：

- Debug 可以正常构建与安装
- Release 会生成未签名 APK

## GitHub Actions 构建

仓库已经配置 Android CI。推送到 `main` 或开发分支后，GitHub Actions 会自动：

1. 配置 Java 17 与 Android SDK 36
2. 恢复 Gradle 缓存
3. 运行单元测试
4. 构建 Release APK
5. 在签名 Secrets 可用时生成并验证正式签名 APK
6. 上传 APK Artifact

因此日常开发可以采用：

```text
创建开发分支
    ↓
修改代码并 push
    ↓
GitHub Actions 自动生成签名测试 APK
    ↓
真机测试
    ↓
确认后合并 main
```

`main` 作为已确认的稳定代码；试验性 UI、性能优化和新功能优先在开发分支验证。

## 发布签名

Release 构建通过本地 `keystore.properties` 或 GitHub Actions Secrets 使用固定签名证书。

本地配置可以从：

```text
keystore.properties.example
```

复制为：

```text
keystore.properties
```

再填写自己的密钥库路径、密码与 Alias。

> 签名私钥、密码和真实 `keystore.properties` 不应提交到仓库。
>
> 已安装版本后续要直接覆盖升级，必须继续使用同一套签名证书。

仓库的 CI 使用以下 Secret 名称读取发布签名：

```text
ASTER_KEYSTORE_BASE64
ASTER_STORE_PASSWORD
ASTER_KEY_ALIAS
ASTER_KEY_PASSWORD
```

## 双 API 本地联调

仓库提供简单的 mock API，可同时模拟对话与绘图服务：

```powershell
python tools/mock_api.py --port 8000 --label chat-api
python tools/mock_api.py --port 8001 --label image-api
```

Android 模拟器内可分别配置：

```text
对话：http://10.0.2.2:8000
绘图：http://10.0.2.2:8001
```

## 更新记录

README 只描述 **Aster 当前版本的能力与使用方式**，不再堆叠所有历史版本。

版本级新增、改进、修复与构建变化统一维护在：

**[CHANGELOG.md](CHANGELOG.md)**

维护约定：只有经过测试并合并到 `main` 的稳定改动进入正式版本记录；开发分支中的试验性修改不计入稳定更新日志。
