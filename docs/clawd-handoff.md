# Aster Clawd 吉祥物 — 交接手册

> 2026-10-16 · v3.5.0 已发布（合并 79e762b，tag v3.5.0）

---

## 1. 本地工作区位置

| 路径 | 分支 | 用途 |
|---|---|---|
| `C:\Users\ad\AppData\Local\Temp\aster` | `main` @ 79e762b | 主工作区（main）。**注意：有 13 个文件的未提交删除 + 3 张截图（clawd_bottom.jpg/png、clawd_check.png），是另一处进行中的工作，与 Clawd 无关，勿动** |
| `C:\Users\ad\AppData\Local\Temp\aster-clawd` | `feature/clawd-mascots` @ 1aa0950 | Clawd 任务 worktree（已合并完毕，保留作参考） |
| `C:\Users\ad\AppData\Local\Temp\clawd-apk\aster-release-signed\` | — | 已下载的 CI 产物 APK（目前是 3.4.0 的；3.5.0 在 main CI 绿后可重新下载） |

两个工作区是同一仓库的 git worktree，共享对象库，互相切换只需 `git -C <路径> <命令>`。

---

## 2. 这是什么

把开源项目 [claudenoshujin/claude-web](https://github.com/claudenoshujin/claude-web) 的吉祥物系统（输入框上三只小 Clawd + 一只能戳能扔的大 Clawd）**完整照搬**进 Aster（Kotlin + Jetpack Compose 聊天应用）。

核心原则：**原版 JS 一字不改**。外形与行为不是重写，是让原版代码在宿主里跑起来。

---

## 3. 架构（5 分钟版）

```
Compose 聊天页 (ChatScreen.kt)
  └─ 量取真实输入框/聊天区的 Window 像素矩形 (onGloballyPositioned)
  └─ ClawdPileWebView (全屏透明 WebView，盖在整个聊天内容之上)
       └─ 加载 assets/clawd/clawd_host.html（全部 JS 内联，219KB）
            ├─ clawd-pile.js   ← 原版：三只小爪子（牛仔/墨镜/巫师）
            ├─ clawd-rig.js    ← 原版：大 Clawd 骨架 + 64 动作 CSS 生成器
            ├─ clawd-rhythm.js ← 原版：打字蹲字 + 生成倍速
            └─ 宿主桥（host-template.html 里手写的一段）
                 └─ 从原版 index.js 移植的行为驱动层
```

**数据流**：
- Compose → JS：输入框矩形（`__clawdLayout`）、聊天区矩形（`__clawdChat`）、输入文本（`__clawdInput`）、焦点（`__clawdFocus`）、生成状态（`__clawdGen`）、最后一条消息文本（`__clawdLastText`）、夜间主题（`__clawdNight`）
- JS → Compose：每 60ms 推送爪子物理像素矩形（`AsterClawd.pushRects` @JavascriptInterface）
- 触摸：`ClawdHostWebView.dispatchTouchEvent` 做命中过滤——事件起点落在爪子矩形内才交给 WebView（原版戳/拖拽生效），否则 `return false` 穿透给 Compose

**关键文件**：

| 文件 | 作用 |
|---|---|
| `tools/clawd/src/clawd-pile.js / clawd-rig.js / clawd-rhythm.js` | 原版 vendor，**禁止修改** |
| `tools/clawd/src/index.js` | 原版行为层的参考源（7849 行），宿主桥抄写出处 |
| `tools/clawd/host-template.html` | 宿主页模板（含宿主桥 + 大 Clawd 按钮 CSS） |
| `tools/clawd/build_host.mjs` | 生成 `assets/clawd/clawd_host.html`（模板 + 原版 JS 拼装） |
| `tools/clawd/test_host.mjs` | Node 全链路模拟（假 DOM + 假 rAF），验证 init 建出 3 小爪 + 大 rig |
| `app/.../ui/clawd/ClawdPileWebView.kt` | WebView 宿主（ui/clawd/ 下唯一文件） |
| `app/.../ui/screens/ChatScreen.kt` | 接线点：量矩形、传状态（搜 `ClawdPileLayer`） |

---

## 4. 修改流程（铁律）

1. **改宿主桥/样式** → 改 `tools/clawd/host-template.html`（不是生成物！）
2. 重新生成：`node tools/clawd/build_host.mjs`
3. 本地验证：`node tools/clawd/test_host.mjs`（必须 PASS：3 只小爪子 + rig 1 + clips 64）
4. 提交推送，**CI 验证**（禁止本地 gradlew 构建/测试）
5. 真机验证装 APK

原版 vendor 三个 JS 永远不改；行为改动一律进 host-template.html 的宿主桥段。

---

## 5. 硬性约束（用户红线）

- **禁止本地 gradle 构建/测试**——一切验证交给 GitHub Actions CI
- **分支纪律**：严格只在当前任务分支工作，不碰其他分支（多任务并行中）
- **视觉照搬**：不允许"差不多"，外形与原版逐像素对齐（这是当初 Compose 自绘方案被推翻、换 WebView 的原因）
- **触摸穿透**：Clawd 层绝不允许挡住聊天页任何点击
- **工作区**：Clawd 相关改动只在 aster-clawd worktree 的 feature 分支做

---

## 6. 行为速查（验收用）

| 交互 | 预期 |
|---|---|
| 戳大 Clawd 1-2 下 | poke1（34% 概率害羞版） |
| 连戳到 3-4 级 | poke2/3/4（越来越烦躁） |
| 戳满 5 级 | turn→t5→face 生闷气 或 rage 暴怒 |
| 按住不动 600ms | 摸头 pet |
| 拖拽后松手 | 弹道飞行、弹跳（最多 6 次）、squash 变形、落地 |
| 扔 3 次内 | 落地后 stomp 跺脚 |
| 扔到 5 次 | 生闷气序列 |
| 扔到小爪头上 | 坐上去，小爪下沉接住 |
| 空闲 22-36s | 随机动作（走路/蝴蝶/胡萝卜/荧光棒/义眼警报/rickroll，11:30-13:00 与 17:30-19:30 爱吃饭） |
| 3 分钟无操作 | drowsy 打瞌睡 → 1.9s 后入睡；再点它醒来 |
| 60s 没被理且没在打字 | neglected 委屈表情 |
| 打字 | compose 动作 + 一字一蹲（粘贴只蹲一下，IM 上屏连蹲） |
| 句末带 ！/？ | wow / tilt 反应 |
| 回复生成中 | write 写字动画，按出字速度 0.6×/1×/1.6× 变速；12s 后变坐姿写字 |
| 生成结束 | done；出错 error；计时徽标定格 4s |
| 滚动聊天 | 大爪扒边 peek，停 700ms 恢复 |
| 夜间主题 | 墨色变暖灰 |

小爪三只：可拎起、拖拽、叠罗汉，位置存 localStorage（WebView domStorage）。

---

## 7. 已知校准点

- **下降 1mm**：用户目测校准，`.clawd-pile{margin-bottom:-5px}` + 大按钮底边 `calc(100% - 5px)`（两者必须同步改，保证并排同线）。原版是 -1px。
- **宿主桥与原版的差异**（有意为之，都有注释）：`isTypingActive()` → 生成标志；`handleClawdComposerEdit` 去掉 `isTrusted`（Android 桥合成事件）；ccSleeping/letter/readingGate/鼠标视线跟随等无事件源特性剪除。
- **首帧丢布局**：页面加载完成前的布局会丢，`onPageFinished` 补发（`resendFormRect`）。

---

## 8. CI / 发布

- 分支推送即触发 Android build；绿 = 编译 + 单测通过
- 发版流程（本次 v3.5.0 实操）：分支上改 `app/build.gradle.kts`（versionCode 77 / versionName 3.5.0）+ CHANGELOG → CI 绿 → 主目录 `git merge --no-ff` → tag `v3.x.0` → `push origin main v3.x.0` → 从 main 的 run 下载 `aster-release-signed` APK → `adb install -r`
- 测试设备：CQEM6TPR59WGD6PZ（已信任 adb）

## 9. 踩过的坑（勿重蹈）

1. `shouldInterceptRequest` 返回空响应会吞掉 `file:///android_asset` 主页面 → 爪子完全不可见。网络隔离用 `blockNetworkLoads=true` 即可，拦截器删掉。
2. WebView 坐标系：`boundsInWindow()` 是 Window 物理 px，必须减 WebView 自身 `positionInWindow()`；CSS 侧除以 dpr。
3. `ConsoleMessage.line()` 不存在 → `lineNumber()`。
4. Node 模拟的假 DOM 需要补：`addEventListener / dispatchEvent / contains / focus / blur`，且选择器匹配要支持 `tag.class` 复合 token。
5. 原版 `toFixed` 是 half-away-from-zero，与 Java `Math.round` 不同（历史遗留，已在合并的早期提交修复）。
