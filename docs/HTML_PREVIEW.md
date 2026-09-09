# HTML 文件与预览

普通聊天和故事的讨论/正文工作区复用 `StructuredMessageText` → `HtmlArtifactCard`。

- 完整的 html / htm fenced code block 默认显示内容预览，提供全屏、复制代码、保存 `.html`。
- 流式/未闭合代码块显示生成中提示，不外显代码、不运行脚本。完成后自动创建内联 WebView；离开卡片和关闭全屏会销毁对应 WebView。全屏期间释放内联预览。
- Chat / Responses 的 create_file 白名单增加 `text/html`，保留 UTF-8 原文，现有文件持久化无需 migration。HTML 工具附件使用同一卡片。
- 故事沿用现有禁止副作用工具的规则，通过 HTML 代码块提供预览与导出；无需开启创建文件工具。
- 应提示模型输出自包含 HTML：内嵌 CSS/JS、SVG/Canvas/data 图片，不依赖 CDN。旧聊天中的完整 HTML 代码块也可预览。

预览边界：WebView 离线，禁用文件/content 访问、DOM 存储和新窗口，无 JavaScript/native bridge；HTML 放入只有 allow-scripts 的 srcdoc sandbox，禁止同源、表单、弹窗、父页面导航。CSP 和网络拦截同时禁用外部资源/请求。保存导出的是未经包装的原始 HTML，在外部浏览器打开时遵循该浏览器的行为。百万字符以上不内嵌预览，但仍支持导出。

Android 参考：https://developer.android.com/reference/android/webkit/WebSettings

验证：ToolProtocol 单测覆盖两种接口白名单与文件内容/文件名；包装单测覆盖 srcdoc 逃逸与尺寸上限；原生 UI 测试通过实际 WebView 验证完成后预览、JS/CSS 交互、父页面/存储/网络隔离及全屏切换。

## 历史验证记录：Build 168（旧版手动切换预览）

- 产品提交：`79fadf9c69afb934915b4ecfcaf987460fc0a9c5`（feature/story-mode）。
- Android Build #168：单元测试、Release 编译、固定签名构建成功，run `34296710923`。
- Native UI preview #56：13 tests，0 failures/errors/skipped，run `34296710904`。
- 人工核对内联与全屏截图，HTML 背景、文本、脚本按钮和顶部关闭栏均正常显示。
- 原生测试不仅检查 DOM/JavaScript，还检查屏幕上的 HTML 像素及全屏关闭栏文字像素。Build 166/167 的首帧空白问题已修复，不作为交付版本。
- APK：`Aster-build168.apk`，24,224,161 bytes。
- SHA-256：`6a0b9ac158d3eb6768b007a053ea01a4d6a9a561689842698f7bdd21a5df6a95`。
- 签名证书 SHA-256：`3e4da1d062819d9f1065f85654de71ae5f0ad93aa10c52fc85faad0338a4e3b1`，与 Build 165 一致。
- main、versionName 2.3.0 / versionCode 57 和原签名配置均未改动。
