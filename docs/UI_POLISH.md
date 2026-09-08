# 全局 UI 与交互打磨

基线：feature/story-mode @ 95b9475（Android Build #145）。

## 本轮体验变化

- 保留 Aster 现有暖白、浅棕视觉。普通聊天与故事共用 ConversationComposer：焦点动画、键盘关闭收缩、发送/停止、触觉反馈、附件预览和导入时禁用规则。
- 胶囊和附件卡片使用细描边，不再叠加投影。保留现有底部渐变，不重新引入曾产生设备差异的实时模糊。
- 工具面板采用两列入口、明确关闭按钮和可滚动内容；长字体/小屏幕可以继续访问下方操作。
- 消息操作提供 48 dp 高度，复制后短暂显示“已复制”；图片移除目标为 48 dp。
- 等待动画、消息图片、回到底部按钮和阅读遮罩均使用公共实现。故事专属的讨论/正文、资料、版本等继续由故事页面提供。
- 故事无有效服务时提供明确的配置按钮并打开故事档案，避免空白发送按钮承担隐含导航。
- 故事工作区与绘图/漫画翻译复用分段控件，选中态使用渐变过渡和描边，避免重复点击触发切换。
- 全局选项面板重新打开定位当前选项；搜索重置滚动位置；设置、模型、绘图等调用处同时生效。选项具有选中语义。
- 下载历史把并排小按钮收进统一操作面板，标题获得更多空间；点击卡片仍直接播放，删除仍经过原有确认。

## 组件边界

通用视觉与基础输入交互放在 ui/components。屏幕只负责业务数据、请求、滚动锚点及专属操作。新增模式优先复用这些组件，不复制一套基础聊天 UI。

本轮不改包名、版本、签名、数据库和模型请求。没有将代码去重视为整个产品已经完成升级；本提交是可验证的一轮全局打磨。

## 验证

验证提交：`c153cbd7042cd0197f18aaaeeca32f99dd01df3e`。

- [Android Build #153](https://github.com/adongshaln/Aster/actions/runs/34168447021)：单元测试、Release 编译、固定签名与 APK 上传全部成功。
- [Native UI preview #42](https://github.com/adongshaln/Aster/actions/runs/34168446933)：Android 35、360 dp 模拟器，6 项交互测试全部通过，没有跳过测试；导出 6 张截图。
- 测试覆盖普通/故事输入框开合与草稿保留、附件导入期间的发送/移除禁用及停止可用性、当前选项定位与搜索滚动重置、大字体下长面板的操作可达性、复制确认和分段控件重复点击。
- 键盘判断已改为在可见高度下降且目标为关闭时清除焦点，避免打开过程中的零目标值造成误收缩。两种模式使用同一个实现。
- UI 测试中显式请求系统软件键盘显示/隐藏，再等待收缩动画完成；没有用焦点清除来代替键盘关闭。之前的搜索节点歧义、动画断言过早和 Action 多行脚本问题均已修复。

[固定签名测试包](https://github.com/adongshaln/Aster/actions/runs/34168447021/artifacts/10034974680)。版本保持 2.3.0 / 57。

验证边界：测试针对公共控件，不等于所有业务页面或真实服务的端到端验收。真实手机仍需检查系统返回手势/实际输入法、长消息底部、附件选择与导入、横屏和整体审美。截图已经上传到 UI 工作流产物；当前执行环境下载产物返回 HTTP 403，未完成截图人工复核。


## Unified conversation refinement — 2026-09-08 (implementation checkpoint)

- Both modes use `ConversationHeader`: stable title/model hierarchy and common action sizing. Ordinary conversation navigation moves into its header menu; story archive, route history and story switching share one menu, removing the standalone history row.
- Both composers expose **展开草稿** while focused. The shared full-screen editor updates the mode-owned draft live; collapse retains text and selection. Enter inserts newlines, and sending remains explicit. Import/send/stop guards match the compact composer. Image previews and removal remain available.
- Opening message details pauses following in both modes, cancels an existing scroll and reveals details above the composer. User dragging pauses following; releasing at the bottom or explicitly returning to the bottom resumes it. Ordinary message insertion no longer animates existing items; its action row wraps for large text.
- Added native regression coverage for editor text/selection persistence, explicit send, and import/stop guards, alongside the six existing interaction checks.
- Development branch only; no version, signing, database or request-protocol changes. CI and visual verification pending at this checkpoint.

- Validation follow-up: explicit selection hand-off prevents Compose blur/disposal callbacks from clearing the editor selection. Scroll interruption no longer terminates the ordinary chat streaming collector. Native run #45 exposed the selection bug and a separate Pixel Launcher ANR (visible in its screenshot) that stole IME focus; the runner now stops only that emulator launcher and test setup dismisses only its specific ANR dialog, leaving Aster errors visible.
