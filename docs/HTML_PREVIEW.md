# HTML 文件与预览

普通聊天和故事的讨论/正文工作区复用 `StructuredMessageText` → `HtmlArtifactCard`。

- 完整的 html / htm fenced code block 提供源码、预览、全屏、复制、保存 `.html`。
- 流式/未闭合代码块仅显示源码，不运行脚本。切换预览才创建 WebView；切回源码、离开卡片和关闭全屏会销毁对应 WebView。全屏期间释放内联预览。
- Chat / Responses 的 create_file 白名单增加 `text/html`，保留 UTF-8 原文，现有文件持久化无需 migration。HTML 工具附件使用同一卡片。
- 故事沿用现有禁止副作用工具的规则，通过 HTML 代码块提供预览与导出；无需开启创建文件工具。
- 应提示模型输出自包含 HTML：内嵌 CSS/JS、SVG/Canvas/data 图片，不依赖 CDN。旧聊天中的完整 HTML 代码块也可预览。

预览边界：WebView 离线，禁用文件/content 访问、DOM 存储和新窗口，无 JavaScript/native bridge；HTML 放入只有 allow-scripts 的 srcdoc sandbox，禁止同源、表单、弹窗、父页面导航。CSP 和网络拦截同时禁用外部资源/请求。保存导出的是未经包装的原始 HTML，在外部浏览器打开时遵循该浏览器的行为。百万字符以上不内嵌预览，但仍支持导出。

Android 参考：https://developer.android.com/reference/android/webkit/WebSettings

验证：ToolProtocol 单测覆盖两种接口白名单与文件内容/文件名；包装单测覆盖 srcdoc 逃逸与尺寸上限；原生 UI 测试通过实际 WebView 验证完成后预览、JS/CSS 交互、父页面/存储/网络隔离及全屏切换。
