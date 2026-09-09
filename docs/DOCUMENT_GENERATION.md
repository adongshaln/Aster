# PDF 与 Office 文档生成

普通聊天开启“创建文件”后，Chat Completions 和 Responses 的 create_file 工具都支持 PDF、DOCX、XLSX、PPTX，并保留 HTML / Markdown / 文本 / JSON / CSV。

模型提交内容，应用本地生成真实二进制文件。PDF / DOCX 使用 Markdown 源码（标题、段落、列表、简单管线表格）。XLSX 使用 sheets/name/rows JSON，可包含文本、数值、布尔值、空单元格以及显式 formula 对象。PPTX 使用 slides/title/bullets JSON。模型不需要也不能提交 ZIP、宏、外部资源或二进制编码。

- PDF：A4、中文系统字体、自动换行与分页、页码；长表格按行分页。单个表格行无法放入一页时返回明确错误，不裁掉文字。
- Word：可编辑 DOCX，正文、标题层级、列表文字、表格；保留 Unicode 并转义 XML。
- Excel：可编辑 XLSX、多工作表、数字/布尔类型、冻结首行和表头样式。基础公式（SUM/AVERAGE/MIN/MAX/COUNT/ROUND/ABS、算术及单元格引用）由办公软件打开时重新计算，不伪造缓存结果。普通文字即使以 = 开头也保留为文字。
- PowerPoint：可编辑 PPTX，16:9 页面、标题/要点布局、完整 master/layout/theme 包关系。文字过多时返回错误，要求拆页，避免裁切。

边界：源内容 200,000 字符；单文件 10 MB；PDF 200 页；XLSX 20 个工作表、50,000 个单元格；PPTX 60 页。此版本没有 DOC/XLS/PPT 旧二进制格式、宏、图片排版、图表或完整 Markdown 富文本转换。故事模式仍保留禁用有副作用工具的既有规则；本次 HTML 默认预览同时作用于普通与故事模式。

附件新增可选 encoding 字段（旧文件默认 utf-8，新二进制使用 base64）。请求结束后落入现有 ConversationStore，导出时解码为原始 bytes，文件卡片显示真实文件大小；不把二进制编码写成纯文本文件。不改变旧聊天存储结构或原有签名。

PowerPoint 空白模板位于 app/src/main/resources/document-templates/presentation.pptx，由 tools/generate_document_template.py 生成；应用运行时不依赖 Python 或额外大型 Office 库。

验证包括：OOXML 包/XML 结构、中文/特殊字符、单元格类型与公式隔离、非法输入失败、两个 API 的工具枚举、二进制附件保存与恢复；Android 模拟器实际生成四种格式并验证 PDF 分页和保存前后字节一致。CI 样本将再由独立 Python 文档库读取。

## Build 169 验证记录

产品提交 cc16910b65a12f36a51b88d3bd53ace330199d5f。
Android Build #169（run 34329928823）单测、Release 和签名成功；Native UI #57（run 34329928799）14 tests，0 failures/errors/skipped。

独立工具验证 Android 生成的真实样本：PDF 3 页且保留最后一段；DOCX 92 段及表格；XLSX 两张表、数值与公式；PPTX 两张可编辑页面。全部 OOXML XML 和包关系可读取且目标完整。HTML 默认内容预览及全屏截图已人工核对。

已知 PDF 文字层边界：Android/Skia 可能把相同中文字形映射为 Unicode 兼容字或部首。页面视觉显示正确，但提取/复制所得编码不保证逐字等于原始文本；独立标题核对使用 NFKC 规范化，同时检查原始英文尾段和实际页面渲染。后续需要继续优化文字复制与检索，不把这个测试描述成原始 Unicode 的完全无损回读。DOCX 内容保留原始 Unicode。

APK：Aster-build169.apk，24,246,567 bytes；SHA-256：1bfa3d11ce01c7173abbce211eb8d64ca5231117bd46acd7ae1177b24a0cb8f7。
签名证书 SHA-256：3e4da1d062819d9f1065f85654de71ae5f0ad93aa10c52fc85faad0338a4e3b1，与 Build 168 一致。main、2.3.0 / versionCode 57、原签名配置未改动。
