# Aster

<p align="center">
  <img src="Aster_icon.png" alt="Aster" width="120" />
</p>

<p align="center">
  原生 Android 多 API AI 客户端，把对话、故事创作、绘图、文件生成、媒体解析与系统级链接捕获装进一个应用。
</p>

Aster（原 ADChat）使用 Kotlin 与 Jetpack Compose 构建。对话与绘图可以分别路由到不同的 API 服务，模型、推理、工具调用、故事资料与历史任务都在统一界面中管理。

> 当前稳定版本：**3.2.0** · `versionCode 74` · 完整历史见 [CHANGELOG.md](CHANGELOG.md)

## 功能版图

| 功能 | 说明 |
| --- | --- |
| [对话](#对话) | OpenAI Responses / Chat Completions 双协议，SSE 流式输出，思考过程独立面板，思考强度与上下文预算 |
| [故事模式](docs/STORY_MODE_ACCEPTANCE.md) | 长篇叙事工作区，自动整理人物、关系与剧情事实，完整支持 SillyTavern 预设 |
| [联网搜索](#联网搜索) | Anthropic Messages 网页搜索后端，可委托独立的 Web / X 搜索模型 |
| [Skill 包](docs/SKILLS.md) | 从 GitHub 或 ZIP 安装，按工作区启用，内置失控循环保护 |
| [文件与预览](docs/DOCUMENT_GENERATION.md) | 真实生成 PDF / DOCX / XLSX / PPTX 二进制，HTML 在受限离线 WebView 中预览 |
| [绘图](#绘图) | OpenAI Images / Gemini / NovelAI 协议，多任务并发生成，漫画翻译流水线 |
| [媒体工作台](#媒体工作台) | 抖音 / B站链接解析，Range 分段下载与断点续传 |
| **制卡工作台**（3.1 新增） | 面向角色卡与酒馆预设的制作流程 |
| **链接灵动岛**（3.2 新增） | 系统级悬浮圆环捕获剪贴板媒体链接，展开询问卡片直达下载 |

### 对话

- OpenAI Responses API 与 Chat Completions 双协议，SSE 流式输出，支持重试、继续生成与重新生成。
- 推理模型的思考过程实时回传，在正文上方独立成面板，不与正文混排，也不再回传给服务端。
- 输入框常驻上下文进度圈与思考强度按钮：前者实时显示输入预算占用，可切换 128K / 256K / 512K / 1M 或自定义数值；后者按模型家族匹配真实参数档位。
- 支持最多 4 张图片的多模态对话；流式中断时保留已生成内容并提供安全续传。

### 联网搜索

- 接口格式可设为 Anthropic，作为联网搜索后端，走 Messages API 的网页搜索。
- Chat 模型可将 Web / X 搜索委托给单独配置的 Responses 兼容搜索模型，结果交回原模型完成最终回答，并保留来源引用。

### 绘图

- OpenAI Images、Gemini 图片模型与 NovelAI Diffusion 兼容协议，支持参考图编辑。
- 多任务并发生成，任务之间保持独立模型、提示词、画布与参考图快照。
- 漫画翻译流水线：辅助视觉模型先分析剧情与人物关系，再由图片模型逐页原位替换文字；两个模型可以使用不同 API。

### 媒体工作台

- 解析抖音分享文本、短链与常见视频链接，原生在线播放预览。
- Range 分段下载、断点续传、取消与恢复，下载完成后校验 MP4 文件头。

## 版本演进

- 2.0 — 项目从 ADChat 更名为 Aster
- 2.5 — 酒馆预设与故事生成恢复
- 3.0 — 阅读重排、Anthropic 联网搜索后端
- 3.1 — 制卡工作台与手势交互优化
- 3.2 — 链接灵动岛

## 技术栈

- Kotlin
- Jetpack Compose
- Android SDK 36
- Java 17
- OkHttp
- Media3
- Haze
- PDFBox Android

应用保持原生 Android 架构，不依赖 WebView 作为主界面。HTML 内容预览及部分媒体解析场景使用受控 WebView。

## 本地构建

需要 Java 17 与 Android SDK。

```powershell
$env:JAVA_HOME='C:\Java\jdk-17.0.18+8'
.\gradlew.bat testDebugUnitTest assembleDebug
.\gradlew.bat assembleRelease
```

未配置发布签名时，Debug 可以正常构建与安装，Release 会生成未签名 APK。

## GitHub Actions 构建

推送到 `main` 或开发分支后，CI 会自动配置 Java 17 与 Android SDK 36、恢复 Gradle 缓存、运行单元测试、构建 Release APK，并在签名 Secrets 可用时生成正式签名 APK 上传为 Artifact。

日常开发流程：创建开发分支 → 修改并 push → CI 自动生成签名测试 APK → 真机测试 → 确认后合并 `main`。`main` 作为稳定代码，试验性修改优先在开发分支验证。

## 发布签名

Release 构建通过本地 `keystore.properties` 或 GitHub Actions Secrets 使用固定签名证书。本地配置可从 `keystore.properties.example` 复制为 `keystore.properties` 后填写密钥库路径、密码与 Alias。

> 签名私钥与密码不应提交到仓库。已安装版本要直接覆盖升级，必须继续使用同一套签名证书。

CI 使用以下 Secrets 读取发布签名：

```text
ASTER_KEYSTORE_BASE64
ASTER_STORE_PASSWORD
ASTER_KEY_ALIAS
ASTER_KEY_PASSWORD
```

## 双 API 本地联调

仓库提供 mock API，可同时模拟对话与绘图服务：

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

README 只描述当前版本的能力与使用方式。版本级新增、改进、修复与构建变化统一维护在 [CHANGELOG.md](CHANGELOG.md)。

维护约定：只有经过测试并合并到 `main` 的稳定改动进入正式版本记录；开发分支中的试验性修改不计入稳定更新日志。
