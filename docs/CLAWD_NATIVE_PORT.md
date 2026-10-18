# Clawd 原生 Compose 移植设计（feature/clawd-native）

## 目标

用 Jetpack Compose + Kotlin 状态机完整复刻 WebView 方案的 Clawd 吉祥物系统：三只小爪（作息/走路/叠罗汉/拖拽）+ 大 Clawd（戳五级/拖拽投掷/弹道弹跳/坐小爪/生成联动/打字反应/滚动 peek/夜间变色/空闲动作/计时徽标）。

**验收基准**：`ClawdPixelGoldenTest`（493 组 rig 帧 + 135 组小爪形态 + 1860 采样点逐位对比）在 CI 上必须全绿；视觉上与现网版逐像素一致（外形）、行为上对照 `tools/clawd/host-template.html`（宿主桥，由原版 index.js 移植而来，保留全部裁剪注释）。

## 架构（文件规划）

```
ui/clawd/
├─ ClawdLoader.kt           [已恢复] org.json → RigData/PileData
├─ ClawdModels.kt           [已恢复] 数据类（SpriteFrame/RigClip/PileData/ClawdPixel 等）
├─ ClawdPixels.kt           [已恢复] rigFramePixels / pileCells（逐位对照原版 pixelsOf/cells）
├─ ClawdSampler.kt          [已恢复] sampleKey/sampleState/flexAt（489–533 行对照）
├─ ClawdPileState.kt        [已恢复] 小爪作息/走路/碰撞/叠放状态机（631 行）
├─ ClawdPileView.kt         [已恢复] 小爪渲染/触摸层（56dp 活动带，需改 anchor 接入）
├─ ClawdPilePositionStore.kt[已恢复] SharedPreferences 持久化
│
├─ ClawdBigRuntime.kt       [新] 大 Clawd 状态机——三轨 A/B/C + A2 物理 + 空闲调度 + 打字反应
├─ ClawdBigView.kt          [新] 大 Clawd 渲染/触摸——rigFramePixels 采帧成 Bitmap，graphicsLayer 平移/旋转/缩放
├─ ClawdHost.kt             [新] 单一挂载点：三只小爪 + 大爪同画布共存，一张 Canvas 完成绘制，
│                              触摸层共用 pointerInput 命中测试（小爪→ClawdPileOverlay Box，大爪→独立 Box）
└─ ClawdPileWebView.kt      [删] WebView 方案退役
```

## 关键设计决策

**1. 一张画布而不是两个叠加层。** 原版大爪和小爪在同一 DOM 平面上互相避让（walk 避开 blocks、落座 seats、shake riders）。Compose 里把它们放同一 `Box` 最忠实：大爪坐小爪头上是**同一坐标系**直接算高度，不再需要 JS 桥同步 rect。

**2. 坐标约定沿用原版：CSS px = dp。** 小爪状态机已经是 dp 单位；大爪宿主桥的 `A2.x/fy` 也是 dp（JS 里 CSS px）。Compose 只用 `density` 转 px 绘制，状态机纯 dp 运行。

**3. 采样驱动而不是 CSS 动画。** `ClawdSampler` 已经在 JVM 里重写了原版 64 个 clip 的时间轴求值。大爪渲染 = `TimeAnimator` 每帧按 `clawdTracks` 状态查当前 clip → `sampleState` 出每部件 dx/dy/f/z/sc/r/a → `rigFramePixels` 取对应帧像素 → Bitmap 缓存（key = clipId|t|mirror）。进 0-1000ms 缓动的平滑由 `flexAt` 处理。

**4. 触摸模型完全复刻原版"事件起点命中才捕获"**：
- 小爪：`ClawdPileOverlay` 现有 pointerInput（每只 44x36dp Box）——直接保留
- 大爪：新 Box 48x48dp（rig 视觉尺寸），`awaitFirstDown` 命中大爪当前 rect → 状态机 `a2Down/Move/Up`；未命中直接不消费，穿透聊天页（Compose 天然如此）

**5. 状态接线替换 evaluateJavascript**：
| WebView 桥 | Compose 版 |
|---|---|
| `__clawdInput(text)` | `ClawdBigRuntime.inputText = text`（MutableState）|
| `__clawdFocus(bool)` | `runtime.focused = bool` |
| `__clawdGen(active,hasText,outcome)` | `runtime.setGeneration(...)` |
| `__clawdLastText(text)` | `runtime.lastAssistantText = text` |
| `__clawdScroll` | LazyListState `snapshotFlow` on scroll offset |
| `__clawdNight(bool)` | 调色板切 `pile.palNight` / rig 重画 |
| `__clawdLayout(x,y,w,h)` | `formAnchor` 只是参考矩形，不画 DOM |

## 第一批范围（本次交付）

- 三小爪全部行为（作息/散步/碰撞/叠罗汉/拖拽/持久化）——`ClawdPileState` 完整
- 大爪全部交互（戳五级 tier/变体、拖拽飞行、弹道弹跳+落地、坐小爪、丢 5 次生闷气/rage）
- 全部 64 个动作帧采样与渲染（含 clip variant：drag/dragSwing、stomp/stompSlap、sulk/rage）
- 昼夜主题（pile palNight + rig 后续）
- 弹窗生成计时徽标（genTimer，原生 44dp 方形贴纸）
- 打字联动（compose/wow/tilt/蹲字）

## 第二批范围（独立小改）

- 生成中 write/sitWrite 变速（0.6×/1×/1.6×）、done/error 收尾、停止标志
- 滚动 peek（`__clawdScroll` → setClawdB('peek')）
- 空置委屈（neglected 表情）

## 删除项

- `ClawdPileWebView.kt`、`assets/clawd/clawd_host.html`（219KB）
- `tools/clawd/host-template.html` 保留作行为参考（不打包进 APK）、`build_host.mjs`/`test_host.mjs` 删
- `extract.mjs` 保留（改数据时重跑），`clawd_data.json` / 金标准保留
