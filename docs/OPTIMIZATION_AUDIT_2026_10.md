# Aster 请求与界面优化审查

日期：2026-10-25。基线：4.1.0 / `f3cf4e7`。工作分支：`codex/project-optimization`。

本轮针对可以从代码、协议和回归测试确认的问题进行修复。保留现有产品功能、模型协议和视觉主题；没有通过升级全部依赖、减少测试或隐藏 Lint 错误来获得表面改善。

## 问题、处理与验证

| 范围 | 原有问题及影响 | 本轮处理 | 回归证据 |
| --- | --- | --- | --- |
| HTTP 生命周期 | 同步 `execute`/读取响应体没有统一取消绑定；取消监听存在调度窗口，阻塞线程饱和时可能无法关闭连接 | 所有模型请求与远程媒体解析复用 `withCancellableResponse`；取消在独立于阻塞线程的监听路径执行，响应体统一关闭 | `RequestLifecycleTest`、`RemoteMediaResolverTest`，包括单线程调度器和无响应服务器 |
| 重试与计费 | “尚无正文”不能证明服务端尚未处理；隐式重试和应用重试可能在推理已经发生时重复 POST | 模型 POST 标记为 one-shot，禁止发送后的隐式重放，同时保留建立连接前的地址回退；移除首段正文前的自动原样重发，保留用户显式配置的断流续写 | `ModelRequestTransportTest` 验证地址回退与收到 POST 后断连不重放；断流与仅有推理片段的测试检查实际请求次数 |
| 流协议 | Anthropic `message_stop` 被忽略；usage 尾帧、HTTP 200 错误体、坏 JSON 可能被误判为成功 | 显式处理结束、错误、截断和未知合法事件；错误保留 request ID；坏 JSON 中断而不静默丢片段 | `RequestLifecycleTest`、`StoryCompletionContractTest`、`AnthropicMessagesTest` |
| SSE 内存 | 无界行/事件读取可能被异常服务端持续扩大 | 有界 SSE 行与事件，兼容 BOM、CRLF、多行和已有网关形式 | `ServerSentEventsTest` |
| 用量 | Anthropic 缓存读写输入与输出起始值统计不准确 | 总输入包含普通输入、缓存读取和缓存写入；避免重复累加起始输出 | `AnthropicMessagesTest` |
| 图片重试 | 重新回答、失败重试仅传正文，丢失原图，纯图片消息可能不再发请求 | 保留原轮次附件与续写标志，独立保留下一轮草稿及其附件 | `MainViewModelStateTest` 对真实 MockWebServer 请求 JSON 验证 |
| 建议与标题 | 用户已输入时仍可能发建议；在途建议可能在输入被清空后回填；标题生成可并发重复请求 | 输入/附件变更取消预测，按会话和消息验证结果，非空草稿不预测；按会话去重标题任务 | `MainViewModelStateTest` |
| 草稿与恢复 | 编辑首条消息后空会话不保存；正文和恢复点分别异步写入，存在恢复点先清除的窗口 | 保存已有空会话及编辑草稿；正文确认落盘后再清理恢复点 | `MainViewModelStateTest` 及原有会话持久化测试 |
| 故事流式刷新 | 每约 110 ms 写 SQLite 并重新加载工作区 | UI 在内存中更新当前 revision；恢复检查点间隔约 1.4 s；完成/停止强制保存并刷新后再允许下一次生成 | `StoryViewModelStreamTest`、`StoryStreamStateTest` |
| 故事取消与读取 | 重生成首段文本前停止会删掉原回复；迟到的异步读取可能覆盖新状态 | 事务恢复原 revision；旧读取使用 epoch/序号失效；取消落盘并清除加载态 | `StoryInterruptedRegenerationTest`、`StoryViewModelStreamTest` |
| 动画 | 首页星标没有旋转、小爪无弹道时仍逐帧计算物理状态；后台仍有吉祥物定时工作；等待动画在组合阶段读帧值 | 空闲时退出旋转/弹道计算循环，相关视觉工作受前台生命周期控制；定时任务后台挂起；动画值移到绘制阶段读取 | `ClawdActivityGateTest`、`ClawdFlightContactTest` 与原有吉祥物测试 |
| 长消息与滚动 | 固定像素高度裁切大字号文本，展开按钮触控区域小，惯性滚动到底后跟随恢复不完整 | 按完整 12 行折叠，可恢复展开状态，48 dp 按钮；观察滚动结束恢复跟随；上下文估算轻度防抖 | `OptimizedChatUiTest`、现有输入框与阅读交互测试 |
| 空态文案 | 相邻时段寒暄重复，多数句子围绕时间和姓名，还会猜测用户困倦或失眠 | 按用户反馈精简为 12 句开场，覆盖问题、想法、写作、选择和日常；四个宽时段只影响话题倾向，不强制称呼，不评判作息 | `EmptyChatGreetingTest` 检查轮换边界和文案约束；原生测试接受有效开场句，不绑定 CI 时钟或姓名 |
| 冷启动 | 组合阶段复制约 25 MB 字体并同步构建 Typeface；吉祥物 JSON 在组合中读取解析 | Compose 异步资源字体；悬浮层通过字体资源 XML 保留字重 560；吉祥物数据异步共享缓存 | 编译、Lint、原生 UI 字体及阅读回归 |
| HTML / Mermaid | source 改变时创建了新 WebView，但原 AndroidView 不更新；3.4 MB Mermaid 脚本逐卡在主线程读取处理 | factory 创建单个引擎，update 更新文档，释放时销毁；脚本异步一次缓存；文档后台构造；失败可见 | `OfflineArtifactViewTest`、`MermaidPreviewDocumentTest`、HTML 原生 UI 测试 |
| 预览回调 | JavaScript bridge 在 Chromium 线程回调；旧页面排队动作可能进入新页面回调 | 主线程交付，限制长度，不同文档的 bridge 失效；保持离线网络和 iframe 限制 | `OfflineArtifactViewTest`、`HtmlPreviewDocumentTest` |
| 媒体交互 | 无效新输入/取消后旧页面仍可能更新结果；播放器暂停和后台仍轮询并常亮 | 解析回调校验请求 token；共享播放器连接池，按生命周期/播放状态轮询，只在播放时常亮；修正 Scaffold insets | `MediaDownloadViewModelTest`、`RemoteMediaResolverTest`、Lint |

## 验证记录

- 原始静态检查：4 个错误、37 个警告，其中 3 个错误来自字体资源使用，1 个来自下载页未应用 Scaffold padding。
- 最终静态检查：0 个错误、37 个警告；没有关闭检查或新增 suppress 来掩盖这 4 个错误。
- 完整 JVM / Robolectric / MockWebServer 单测：607 项通过，0 失败，0 跳过。欢迎语改版将旧的 12 项姓名/寒暄断言替换为 6 项轮换、边界与文案约束测试；请求、状态和动画回归测试保留。
- 本地检查命令：`./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest` 全部成功；覆盖 Release 构建与 Android instrumentation 测试包编译。
- 原生 UI 通过仓库 `Native UI preview` 工作流运行；手动触发可使用 `test_class` 选择测试，避免为不同修复硬编码更多分支。
- 云端完整测试及签名 Release 构建已通过：[Android build](https://github.com/adongshaln/Aster/actions/runs/40157144795)。签名 APK 留在 Actions 制品，本地生成的 APK 已清理。
- 原生验收范围为 `OptimizedChatUiTest` 和 `SharedUiInteractionTest` 全部 25 项；真实字体和两倍字号测试首轮通过。既有共享夹具会被昵称首启弹窗挡住焦点，已改为通过实际“稍后再说”按钮正常关闭引导，保留焦点检查和全部用例。
- 最终原生验收结果、截图检查记录与对应运行链接统一记录在 [PR #8](https://github.com/adongshaln/Aster/pull/8)。

## 取舍与未覆盖范围

- 故事检查点从约 110 ms 调整为 1.4 s，降低写放大；正常完成、停止、ViewModel 取消会保存尾部。操作系统直接杀进程时，最后一个检查点之后的尾部仍可能丢失，这个上限是明确的持久化取舍。
- 阻止应用继续读取并关闭 socket，不代表提供商已经停止计算或退款；本轮没有向真实付费模型发测试请求。
- 本轮没有实测真机帧率、冷启动耗时和耗电，不能给出性能提升百分比。异步字体第一次加载时可能短暂显示系统回退字体。
- WebView 生命周期 `onPause` 不是任意网页 JavaScript 定时器的全面冻结。没有调用会影响所有 WebView 的全局 `pauseTimers`。
- 大型 ViewModel/屏幕仍有进一步拆分空间；依赖升级、数据库迁移、发布混淆与资源压缩需要各自的兼容性验证，未夹带到本轮。

## 依据

- [Android Compose：在 Compose 中使用 AndroidView](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)
- [Android：生命周期与协程](https://developer.android.com/topic/libraries/architecture/coroutines)
- [Android Compose 字体](https://developer.android.com/develop/ui/compose/text/fonts)
- [OpenAI Chat streaming events](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events)
- [Anthropic 官方 SDK 的用量定义](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/message.py)
- [WHATWG Server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [OkHttp 客户端重试语义](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/-builder/retry-on-connection-failure/)
