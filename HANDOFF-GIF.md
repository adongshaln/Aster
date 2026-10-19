# 交接单：视频转 GIF 功能（未完成）

- 日期：2026-10-19
- 分支：`feature/anthropic-messages`（HEAD `f18afe7`，已推送远端，与远端一致）
- 状态：**功能代码全部落盘但编译未通过**，CI 红着收尾。下一个会话接手后先修编译，再走 CI → 装机。

## 一、当前工作树约定（务必遵守）

- 工作区有 **7 项未提交删除**（旧 launcher 资源×5、PromptCacheTest、ReasoningPolicyTest），
  是本会话开始前就存在的现场：**不要恢复、不要 stage、不要 stash、不要顺手清理**。
- 禁止本地 `./gradlew`（compileDebugKotlin / test 等一律不行），验证只走 GitHub Actions CI。
- 推送只推 `git push origin feature/anthropic-messages`；网络走
  `git -c http.proxy=http://127.0.0.1:7897 push ...`，TLS EOF 就等 8 秒重试。
- 新提交 attribution：`Co-Authored-By: Claude Code <noreply@anthropic.com>`。

## 二、本段会话已完成并验证的内容（全部已提交、CI 绿）

1. **称呼模块重设计**（`d81e30d` + `dac24db`）
   - 称呼只用于首页问候语，**不进对话系统提示词**（streamChat 只有自定义提示 + 叙事标记 + 封面图指令）。
   - `UserNamePromptDialog` 拆 Onboarding/Edit 双模式；设置内取消不写配置、留空保存清除称呼。
2. **品牌修正四项**（`0a7191d`）：启动图标前景缩至可见圆 2/3 居中（背景 `#F88621`）、
   应用内品牌星标换用户提供的橙色八芒星（`aster_logo.webp` 原样替换）、问候语上移、首启弹窗文案精简。
3. **首页星标实体化**（`e503f02` + `f245032` + `c02e8c3`）：点击旋转（+120°/s，上限 540°/s，
   摩擦 80°/s²），`graphicsLayer` 绘制相位旋转；headlineSmall 20sp 衬线（主题补了定义）；星标去圆圈。
4. **思考面板正文开始即收起**（`c02e8c3`）：`ConversationReasoningPanel(bodyStarted=)`，
   Chat 按正文非空、Story 按 `prose != null`；Story 独立思考面板不变。
5. **用户气泡 Claude 化 + 折叠**（`ccba087` + `4a03d04` + `01d1e0e`）：
   Accent 8% 淡染底 + 18dp 圆角 + 无边框；超长消息 `onTextLayout.hasVisualOverflow` 检测
   （**不要用 heightIn 截高做比较**）+ 折叠 + 七档渐隐 + 展开/收起（在正文下方，Box 换 Column）。
6. **中断/停止回复双按钮**（`acf0d55`）："继续生成" + "重新回答"（`regenerateMessage`
   已放开 interrupted/stopped 守卫，保留 isStreaming 守卫）。
7. **4.0.0 发布链**：PR #7 合并 main（`0bfa636`），v4.0.0 标签构建 success，
   Release `Aster-v4.0.0.apk`（SHA256 `4d60b17b…`）。4.0 之后的改动（含 GIF）尚未再发版。

## 三、视频转 GIF：已完成部分

### 已通过 CI（绿）

- **GIF89a 编码核心**（`c0ac2b2`，`media/gif/GifEncoder.kt` 506 行 + 11 项单测）：
  GifPalette / GifColorQuantizer（中位切分 + Floyd–Steinberg）/ GifEncoder（流式、Netscape 循环、GCE、LZW）。
  **关键修复 `4bae6dc`：Image Descriptor 后必须写 LZW minimum code size 字节**——曾缺失导致全部解码器错位，
  靠测试断言的字节转储定位。动这个文件前先看单测（含 round-trip）。
- **转换器与保存**（`61631d5` + `3298c6f`）：`VideoToGifConverter`（MediaMetadataRetriever 逐帧、
  首帧关键帧建调色板、协作取消清理半成品）、`GifPreset` 三档（Compact 320/8fps/8s、
  Standard 480/12fps/10s、Smooth 640/15fps/15s）、`MediaGallerySaver` GIF 分支（Pictures/Aster，
  MediaStore.Images + MediaColumns）、`asGifRecord` 扩展、`GifPresetTest`。
  **注意：MediaStore 列引用用 `MediaStore.MediaColumns.*`（`mediaColumn.DISPLAY_NAME` 这种动态引用编译不过）。**

### 编译未过（当前 CI 红，`f18afe7`）

**文件：`ui/media/VideoToGifSheet.kt`**（212 行）——历史遗留问题是被我（交接前的会话）多轮小修弄乱，
**建议整文件重写，不要缝补**。`VideoToGifViewModel.kt`（118 行）本身没问题（已加 `convertInfo` 字段）。

最后一轮 CI 错误（`39194530215`）：
```
VideoToGifSheet.kt:124:21 @Composable invocations can only happen from the context of a @Composable function
（124/125/127/152/153/155 行——content lambda 内的 Surface/Text 等全部报错）
```
另有两处既知问题：
1. `AdModalDialog` 签名（`ui/components/AdDialogs.kt:29`）：
   `content: @Composable ColumnScope.() -> Unit` 在前，`actions: @Composable RowScope.() -> Unit` 在后
   （最后一个参数，可尾随 lambda）。当前文件里两段内容曾来回对调，结构已乱。
2. `Night` 是 `@Composable get()`（Theme.kt:143）——只能在组合期取值再传给
   `ButtonDefaults.buttonColors(containerColor = Night)`；若在非组合上下文取值会炸。
   主题里的组合期颜色 getter（Canvas/Accent/Ink 等）同理，draw lambda 里要先在组合期捕获。

### 建议的重写结构（照 `AdConfirmDialog` 的成熟模式）

```kotlin
@Composable
fun VideoToGifSheet(vm, onShare, onDismiss) {
    val state by vm.state.collectAsState()
    AdModalDialog(
        title = "转为 GIF",
        subtitle = state.source?.title,
        onDismiss = onDismiss,
        content = { /* ColumnScope：主体 */
            when (state.phase) {
                Idle -> 预设三卡（GifPreset.entries，selected 高亮 AccentSoft 底+Accent 边框，
                        vm.update(preset=)）+ "超上限取前 N 秒"说明
                Converting -> AsterLoadingIndicator(30.dp) + 百分比 +
                        LinearProgressIndicator(progress = { state.progress }) + 取消按钮（vm.cancel()）
                Done -> "W×H · N帧 · Ns · 大小"（convertInfo.width/height/frameCount/durationMs
                        + formatGifBytes(state.result?.fileSize)）+ error 行
                Failed -> 错误文案
            }
        },
        actions = { /* RowScope：按钮 */
            when (state.phase) {
                Idle -> 取消(vm.dismiss()+onDismiss) / 开始转换(vm.convert())
                Converting -> 无（或一个取消）
                Done -> 保存到相册(scope.launch { vm.saveToGallery() } + Toast) / 分享(onShare(result); vm.dismiss())
                Failed -> 关闭 / 重试(vm.convert())
            }
        }
    )
}
```
注意 `collectAsState` 需要 `import androidx.compose.runtime.collectAsState` + `getValue`（文件里已有）。
项目其他 VM 用 `mutableStateOf` + `by` 委托（`MediaDownloadViewModel.state`），Flow 收集只有这一处，保持 collectAsState 即可。

## 四、UI 接线（已完成、随 Sheet 一起编译验证）

`ui/screens/MediaDownloadScreen.kt`（`548b73f`）：
- import viewModel / VideoToGifSheet / VideoToGifViewModel
- `gifVm: VideoToGifViewModel = viewModel()` + `gifSource` remember（79-83 行区）
- `DownloadHistoryItem` 尾随回调 `onConvertToGif`（163-168 行区）：`gifSource = record; gifVm.start(record)`
- `VideoToGifSheet` 挂载（185-191 行区）：onShare 转发 `vm.shareDownload`
- 操作面板 "gif" 项（share 后 delete 前，icon `Icons.Rounded.Gif`，material-icons-extended 已引入）

这部分代码看起来正确，等 Sheet 编译过了就一起生效。

## 五、下一步清单（接手即做）

1. **重写 `VideoToGifSheet.kt`**（按上面结构；参照 `AdConfirmDialog`/`UserNamePromptDialog` 的成熟写法）。
2. commit + push，看 CI（`gh run list --branch feature/anthropic-messages`）。
3. 绿了之后：`gh run download <runId> -n aster-release-signed -D <目录>` → `adb install -r`（设备 serial `CQEM6TPR59WGD6PZ`，覆盖安装保留数据）。
4. 真机验收：下载历史 → 视频操作 → 转为 GIF → 选档 → 进度 → 保存/分享。
5. 用户验收通过后走发版（版本号建议 4.1.0 / versionCode 82，CHANGELOG 有"未发布"模板可续写），
   流程同 4.0：PR → main → 打 `v4.1.0` 标签自动构建 Release。

## 六、其他备忘

- GIF 编码核心的 Pillow 交叉验证方法：把 Kotlin 逻辑移植 Python + `PIL.GifImagePlugin` 解码逐像素比对
  （子代理用过，脚本已删；LZW 码长增长"分配前判"与标准解码器配对已实证）。
- CI 单测 550 项；每次失败先 `gh run view <id> --log-failed | grep -E "^build.*e: "`。
- 用户偏好（记忆）：设计-下派-验收分工、子代理用 haiku 单任务 ≤100k、严格分支纪律、禁本地 gradle。
- 本段会话的调试消耗较大（GIF 编译问题反复 5 轮）；交接给新会话重写 UI 文件是收敛成本最低的路径。
