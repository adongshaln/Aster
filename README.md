# Aster

<p align="center">
  <img src="Aster_icon.png" alt="Aster" width="120" />
</p>

<p align="center">
  原生 Android 多 API AI 客户端，面向对话、绘图、多模态与内容工具工作流。
</p>

Aster（原 ADChat）是一个使用 Kotlin 与 Jetpack Compose 构建的原生 Android 客户端。它可以将对话与绘图分别路由到不同的 OpenAI 兼容服务，并在统一界面中管理模型、推理、图片、工具调用与历史任务。

> 当前稳定版本：**2.1.0** · `versionCode 54`
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
- 长回复采用分块 Markdown 渲染，减少流式生成期间的重复排版

### 对话工具

- Responses API 支持原生 `web_search`
- 支持模型调用 `create_file` 创建 Markdown、文本、JSON 与 CSV 文件
- 工具执行状态、网页来源和已创建文件会随历史对话保存
- Chat Completions 可兼容支持 `web_search_options` 与函数调用的第三方网关

### Markdown 阅读体验

- 标题、列表、引用、代码块、行内代码与常见 Markdown 结构
- GitHub 风格表格解析、对齐、换行、宽表横向滚动与 TSV 一键复制
- 长文阅读优化、流式阶段轻量渲染与完成后的完整排版
- 提问导航与快速回到底部，适合长对话阅读

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

## Aster 2.1 当前体验

2.0 将项目正式从 ADChat 更名为 **Aster**，并继续保留 `com.adong.adchat` 应用 ID、原配置格式与已有数据兼容性。

2.1 统一采用暖白、浅棕与墨色视觉，重新整理聊天、创作、媒体下载、设置与侧栏布局，提升信息层级与操作入口的一致性。

当前体验重点包括：

- 全新聊天欢迎页与灵感建议入口，统一留白、字体、图标与卡片样式
- 加号集中提供图片、模型、思考强度与工具选项，不再显示含义不清晰的工具数量角标
- 创作、媒体下载与设置页采用清晰分组，侧栏统一组织主要功能与历史入口

- 悬浮输入胶囊，聚焦后平滑展开为更大的多行输入区
- 输入法打开时聊天内容与输入栏同步向上移动，并持续锚定当前对话底部
- 收起输入法后输入栏自动恢复紧凑状态
- 胶囊下方采用渐进式毛玻璃：上沿保持清晰，越接近屏幕底部模糊越强
- 滚动、键盘动画和流式生成期间自动降低实时模糊负载，改善聊天页帧率
- Adaptive Icon 使用安全边距，避免 Aster Logo 在不同启动器蒙版下被裁切

更详细的 2.1.0 变更见 [CHANGELOG.md](CHANGELOG.md#210--2026-09-05)。

## 技术栈

- Kotlin
- Jetpack Compose
- Android SDK 36
- Java 17
- OkHttp
- Media3
- Haze

应用保持原生 Android 架构，不依赖 WebView 作为主界面。部分媒体解析场景会使用附着式 WebView 完成网页侧验证与资源发现。

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
