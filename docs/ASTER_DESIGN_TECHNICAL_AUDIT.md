# Aster 设计升级：技术审查与缺陷清单

视觉升级主规划：[ASTER_DESIGN_UPGRADE_PLAN.md](ASTER_DESIGN_UPGRADE_PLAN.md)  
产品任务书：[ASTER_DESIGN_UPGRADE_BRIEF.md](ASTER_DESIGN_UPGRADE_BRIEF.md)

**本文是技术工作支线，保留原方案 0–14 节及原步骤编号供查证。它们不再代表视觉升级的执行顺序。主规划中的 V1–V6 为当前实施主线；提到 T1、T3 等时，对应本文步骤 1、3。**
整理日期：2026-11-10；同日完成方案复审。文中原有行号以提交 `cb60f96` 为准，动手前请用 `rg` 重新定位。

**历史基线：本文正文保留的是实施前的技术审查。** 当时核对了源码、现存 JVM 测试 XML 和颜色计算，未新增设备实测。后续已实施的 HTML 高度/默认颜色、触感、表格与公共设计改动及其新证据见 [视觉升级交付记录](ASTER_VISUAL_UPGRADE_DELIVERY.md)；不要把下文“未验证”“尚未实现”当成最终交付状态。原作者的观察与行号继续保留供追溯。

## 0. 先读这一节

**起点**

- 分支 `design/upgrade-20261110`，当前 `cb60f96`。它在 4.3.4（`db1570f`）之上有三个提交：原先未提交的 RP 与动效工作的原样快照、一个启动崩溃修复、一个截图走查测试。
- `main` 仍是 4.3.4（versionCode 89），没有任何内容被推送。
- 单元测试：现存本地 XML 汇总为 856 项、0 失败、0 错误、0 跳过；本次复审只读取报告，未重跑。
- 设备端测试：原作者记录 37 项里有 12 项在**任何设计改动之前**就失败（清单见第 12 节）。用来识别新增失败，同时区分过期断言、环境问题和实际产品缺陷，不能直接把这些失败全部忽略或删除。

**现状判断**

对话和剧情已经共用顶栏、输入胶囊、阅读排版和工具弹层，主 App 的深浅色外壳已有基础；HTML 深色、星岛交互和真机手感仍有待核实。共享实现只是技术现状，不能据此判断视觉升级已经足够。以下两类是本支线的主要技术问题，视觉重设计的范围与优先级以主规划为准：

1. 两个已在模拟器上测到的缺陷：剧情 HTML 块展开后内容被裁掉（步骤 1），滚动起手误触发震动（步骤 3）。步骤 2 是按代码推断出的疑点，还没有验证。
2. 规则没有落到代码里：同一种东西有十几种取值，原生控件和自有控件混用（步骤 4–7）。

**与视觉主线的关系**

- T1/T3：原作者报告的 HTML 高度与滚动触觉缺陷。阻断样板操作时先修相应部分，其他独立视觉工作可以继续；这些修复单独交付并说明验证依据。
- T2：HTML 深色疑点，用最小夹具验证后决定是否修改。
- T4–T6：令牌与组件迁移的技术候选，待视觉样板形成后从实际设计中提炼，不能把数值归档作为视觉方案。
- T7：文字对比度可按实际背景定向改善，填充与文字的语义不能混用。
- T8：表格头建议作为阅读设计的一个候选，而不是全部审美工作的范围。
- T9：逐页检查线索，页面视觉交付以主规划的逐页要求为准。
- T10–T14：交付边界、工具、历史失败、未验证事项与复审记录。

本清单中的数量和行号用于定位，验收以用户可见行为与实际效果为准。当前仅修改规划文档，不实施、提交或发布应用改动。

**不要碰的东西**（任务书第 9 节）

- 包名、发布签名、既有数据格式。
- 提示词组装、请求参数、模型与服务分配、计费。
- 角色卡沙盒约束：不放宽网络、不放宽脚本授权。步骤 1 会动到这里，改法见该节。
- 旧「故事模式」（`StoryScreen.kt`、`StorySetup.kt`、`ui/story/`）：不扩建、不美化。但它仍在编译，并且复用公共组件（例如 `ConversationWelcome` 现在只有它在用），改公共组件签名时要保证它能编译。

## 1. 剧情 HTML 块展开后高度不更新

**现象（模拟器实测）**

开场白里有一个 `<details>` 状态栏。块的高度是 68dp；点开后内容变成三行，块仍是 68dp，后两行被裁掉。

**原因**

1. `RpViewModel.scriptsAllowed()`（`ui/rp/RpViewModel.kt:650`）只有在 `cardScriptsAllowed == true` 时才为真。
2. 角色卡本身不带脚本时，`needsCardConsent()` 不会弹授权，这个值永远是 `null`。用户点了"不允许"时也一样。
3. 于是 `StoryProseContent(allowScripts = false)`（`ui/rp/RpScreen.kt:795`）。
4. `HtmlPreviewDocument.wrap()` 在这种情况下输出 `script-src 'none'`，iframe 的 sandbox 为空（`ui/components/HtmlPreviewDocument.kt:47–52`）；WebView 关闭 JS（`ui/components/OfflineArtifactPreview.kt:359`），桥也不挂（同文件 `:320`）。
5. 高度上报脚本 `HEIGHT_REPORT_SCRIPT` 虽然被拼进文档，但跑不起来。`HtmlArtifactCard` 的高度（`ui/components/HtmlArtifactCard.kt:69–82`）就一直停在初始值。

初始值来自 `initialHeightDp()`：全是 `<details>` 的块按每个 52dp 加 16dp 计算，其余一律 340dp。按代码推断，不带脚本的卡里任何普通 HTML 块都固定 340dp，内容短就留白，内容长就被截；这一条我没有在设备上验证。

**候选改法：独立的“仅测量”模式**

nonce 方案可以进入实现验证，但目前没有运行证据。需要先明确三种能力，不能仅把 `allowScripts` 改成 `allowScripts || reportHeight` 后复用全部逻辑。

| 模式 | JS 引擎 / iframe | 允许注入与原生能力 |
| --- | --- | --- |
| 未授权且无需测量 | JS 关闭、无 `allow-scripts` | 无脚本与原生桥 |
| 未授权但需要测量 | JS 开启、仅 `allow-scripts`，保持不透明来源 | 固定的可信测量脚本、父页测量监听器；原生接口仅暴露高度上报 |
| 已授权卡片脚本 | 沿用已有授权路径 | 按当前产品约定启用卡片交互、助手和授权后的 CSP |

实现要求：

1. 新文档使用 `SecureRandom` 生成至少 16 字节随机 nonce，编码后用于可信脚本标签与 `script-src`。每份准备好的文档持有同一 nonce，重复重组、`render()` 或更新回调时不重新生成，以免无意义地重载 WebView；固定 nonce 仅供测试。
2. 仅测量模式的 `script-src` 只放行该 nonce，不包含 `'unsafe-inline'`、`'unsafe-eval'` 或脚本 CDN；iframe 不增加 `allow-same-origin`、表单、弹窗或顶层导航权限。保持当前网络、资源和原生 WebView 访问限制。
3. 只给程序自行构造的 `HEIGHT_REPORT_SCRIPT` 与测量用父页监听器加 nonce。**不能用全局替换给卡片自带的 `<script>` 加 nonce。** 这一路径不注入面板点击捕获、helper bundle、助手转发器，不挂接 helper emitter。
4. 当前 `wrap()` 会因 `helperBundle != null` 直接选择含宽松脚本策略的 `HELPER_CSP`。仅测量模式必须明确拒绝或排除这个组合，不能让误传 helper 参数绕开 nonce 策略。
5. 优先创建只含 `@JavascriptInterface onHeight` 的测量桥，与现有助手桥分开。原因是 `addJavascriptInterface` 会向所有 frame 暴露对象，iframe 隔离本身不能充当原生能力授权。若保留共用类，也必须在原生方法入口逐项拒绝 RPC、广播和面板动作，并验证未注册 helper。[Android 原生桥说明](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)
6. 父页只接受目标 iframe 的 `contentWindow` 发出的测量消息，并验证载荷、有限数值、既有高度边界和文档代次；替换或释放文档后丢弃旧消息。普通测量 iframe 是不透明来源，不能依赖固定的 `event.origin` 字符串判断身份。
7. 保留现有脚本标记；`StoryProsePresentation` 的剥离规则要用含 nonce 的夹具复核，而不只看正则外形。缓存、测量桥与助手桥之间的模式切换同样纳入更新/销毁检查。
8. 核查现有高度算法是否把 iframe 当前视口高度当作内容下限，从而只增不减；动态图片、折叠和连续变化时不能形成尺寸反馈循环。必要时调整可信测量逻辑，同时尊重卡片显式的合法尺寸约束，不能只以“脚本开始执行”作为高度问题已修复的依据。

**必要验收**

- 文档级检查：解析并检查 **`script-src` 指令**，确认 nonce、无脚本放宽项和正确 sandbox；保留 `style-src 'unsafe-inline'`，不能用“整份 HTML 不含 unsafe-inline”作为断言，否则会误伤现有卡片样式。[CSP 规范](https://www.w3.org/TR/CSP3/)
- 原生级检查：仅测量时不暴露助手和面板动作；授权模式切换、替换文档、释放后，旧桥和回调失效。复用并扩展相关 `OfflineArtifactViewTest` 用例。
- WebView 行为检查：使用专用本地夹具，验证普通脚本、内联事件、`javascript:` 链接及外部脚本均不能执行；可用尝试发送 `{asterHeight:4321}` 作其中一项观测，但不能仅凭高度没变化就认定所有脚本都被拦。夹具自身不设置 4321px 的真实高度，避免混淆合法测量与伪造消息。
- 正向检查：同一 `<details>` 展开后三行完整可见、收起后高度恢复；再看一段普通长 HTML，确认不再固定裁切。已授权卡片的助手与交互保持有效。
- 截图走查目前拍不到加载后的 HTML，因此上述行为要手动打开 App 验证，或补充能观察真实 WebView 就绪状态的专项用例。仅有文档字符串测试和占位截图不算通过。

**风险与退回条件**

这是打开 JS 引擎后的新信任边界；随机 nonce 是约束的一部分，不等于安全结论。验证不足或出现权限回归时，保留当前脚本限制，不把未经验证的测量模式交付给用户。只读取外层 `WebView.contentHeight` 也不能直接证明已经取得内层 iframe 的真实高度。

## 2. 深色模式下 HTML 块的默认文字颜色（未验证，先确认）

**为什么怀疑**

- WebView 背景是透明的（`ui/components/OfflineArtifactPreview.kt:235`），外层包装页也是透明的，所以 HTML 块直接透出页面底色；深色主题下就是 `#141413`。
- 包装页没有给卡片内容设置文字颜色，也没有声明 `color-scheme`。浅色主题下实测，块里的文字是浏览器默认的纯黑 `#000000` 和默认无衬线字体，而正文是衬线、`#141413`。
- 两条合起来推断：深色主题下，没有自己写颜色的卡片片段会是黑字落在近黑底上，基本看不见。

**我没能验证**

我在模拟器上试过切到深色再测量，但那次自动点按没有把主题切过去，量到的仍是浅色主题。原因没有查，可能只是没点中。走查测试的深色剧情截图也帮不上：测试进程里 HTML 块一直停在「正在加载预览…」。

**先做的确认（几分钟）**

先用没有自带颜色的最小 HTML 片段，在浅色与深色中各看一次，并确认应用主题确实切换。仅一张自带颜色的卡片可读，不能排除默认文字颜色问题。最小片段也未复现时，标为“未复现，暂不改”，记录设备、WebView 版本与夹具。

**如果确认存在，改法**

1. 将主题信息传到内层文档，采用低优先级默认值，例如 `:where(html){color:…;color-scheme:…}`，放在卡片内容之前。原方案的 `:root` 比卡片的 `html` 选择器优先级更高，仅仅排在前面不能保证卡片样式胜出。
2. 默认文字色可使用当前主题的正文色，不重写卡片已声明的 CSS，也不顺手开启 WebView 自动反色。检查链接、表单和透明背景是否一起可读。
3. 主题应取稳定的明暗模式或最终主题值，避免跟随主题动画的每个插值色重建文档。主题切换如需重载，核实草稿、阅读位置和卡片局部状态的影响，不能把局部交互重置直接当作无影响。

**需要你用真实角色卡确认**

先用最小默认片段、自带浅色样式、自带深色样式各一个样例核对继承行为。如果已有常用角色卡样本，再选少量有代表性的卡对照；目前没有样本，不能推断哪类卡更多或宣称已兼容常用卡。

**验收**：深色主题下，没有自带颜色的 HTML 片段文字可读；浅色主题下与现在一致。

## 3. 滚动起手时误触发震动和缩放

**现象（模拟器实测）**

打开对话页的「输入选项」弹层，手指落在一个工具格子上向上滑动 10 次：应用请求了 9–10 次震动。同一个弹层本来只是想滚动。

**原因**

`Modifier.appleSpringPressable`（`ui/theme/AsterApplePhysics.kt`）有两条路径：

- 传了 `interactionSource`：按压状态来自 Compose 的 `clickable`。它在可滚动容器内会延迟发出按压，滚动先发生就不发。
- 没传：用 `awaitFirstDown` 自己观察，手指一落下就缩放并震动，滚动开始后才取消。

59 处调用里有 26 处走第二条，其中 20 处震动是开着的：

以下数量与行号沿用原作者对 `cb60f96` 的统计。本次复审抽查了相关实现和关键调用，尚未逐个重新清点全部调用。

| 文件 | 行 |
| --- | --- |
| `ui/components/ConversationUi.kt` | 552、628、693、718、831 |
| `ui/components/ComposerIndicators.kt` | 65、115、267 |
| `ui/components/AdDialogs.kt` | 264 |
| `ui/components/ConversationWelcome.kt` | 140 |
| `ui/screens/ChatScreen.kt` | 1103 |
| `ui/screens/SettingsScreen.kt` | 996、1450、1539、1591、1949、2077、2213（已关震动） |
| `ui/screens/MediaDownloadScreen.kt` | 360、556、571、503（已关震动） |
| `ui/screens/DrawScreen.kt` | 557、844、869、911（均已关震动） |

其中 `ConversationUi.kt:628` 是消息下方的操作图标，`:718` 是弹层里的工具格子，都在滚动列表里，影响最大。

**改法（视觉与触觉分开）**

1. 这 26 处几乎都挂在 `Surface(onClick = …)` 或 `clickable` 上。逐个改成：
   `val interaction = remember { MutableInteractionSource() }`，同时传给 `Surface`/`clickable` 和 `appleSpringPressable(interactionSource = interaction)`。
2. 对这些可滚动的点击项，触觉优先由“已确认的点击”统一触发，并关闭按压 modifier 中的重复触觉。仅用 `collectIsPressedAsState` 驱动震动，不能保证短点击总能反馈一次，也不能保证超出延迟后才起滑的手势绝无反馈。长按、滑杆吸附等触觉继续绑定各自语义事件。
3. 回退路径只留给确实没有现成点击交互源的元素。若保留按压观察，需要同时处理取消、移动/消费和延迟，不是只增加一个 `delay()`；装饰元素默认不触发震动。检查是否已有 interaction source 可以复用，避免为同一控件建立两套交互源。
4. 按完整调用块核对全部调用；`interactionSource` 可能写在下一行，不能用单行 `rg | ...` 结果证明迁移完成。保留例外清单，确认启用/禁用、点击、长按及键盘操作语义未变。

26 处是原报告定位的即时触发入口，不是触觉策略审查的全部边界。已有共享 source 的可滚动项也要核对是否仍绑定按压触觉，避免只改无 source 的调用却保留同类问题。

**验收（可量化）**

以同一测试设备和相同手势对比：在工具格子上执行 10 次快速上滑，确认没有点击、按压残留和 App 触觉请求；再确认一次正常点击只产生一次相应请求。慢速拖动、中途离开、取消和连续点击分别做少量针对性检查。

优先在测试宿主中统计共用触觉入口的调用或记录对应原生调用，再用系统信息辅助核对。`adb -s <测试设备> shell dumpsys vibrator_manager` 的历史可能有容量、节流、归因和系统事件差异，直接统计包名行数不是可靠的“震动次数”断言。没有可靠计数手段时，应报告观察方法和局限。

改前的数字是用较慢的滑动（160px、300ms）测的：10 次滑动对应 9–10 次震动请求。起手很慢的拖动可能先出现视觉按压，但在触觉迁移到确认点击后，最终变为滚动的手势不应再触发点击触觉。

改后方案仍未实现和实测。模拟器只能核对事件与请求；真实震感、强度和手感需要真机确认。

**说明**

不在可滚动容器里的按钮（例如侧栏顶部的四个入口），按下后拖开也会震一下，这是"按下即反馈"的正常结果，我没有把它算作缺陷。

## 4. 令牌

在 `ui/theme/Theme.kt` 或独立令牌文件集中规则。**现有 `private val shapes`（10/14/20/26/32）已传入 `MaterialTheme(shapes = shapes)`，并非无人使用**；修改它会同时影响依赖默认 shape 的 Material 控件，需要有代表性地核对。

下面是候选档位，用于降低规则分散程度。先在对话、工具弹层和设置行上看效果，再扩散；保留已认可的胶囊与星岛手感，不以消灭数字为目标。

```kotlin
object AsterRadius { val xs = 8.dp; val sm = 12.dp; val md = 16.dp; val lg = 20.dp; val xl = 28.dp }
object AsterShape {
    val xs = RoundedCornerShape(AsterRadius.xs)   // 标签、角标、行内小块
    val sm = RoundedCornerShape(AsterRadius.sm)   // 图标底、行内小控件
    val md = RoundedCornerShape(AsterRadius.md)   // 输入框、列表行、代码/表格/引用块、缩略图
    val lg = RoundedCornerShape(AsterRadius.lg)   // 卡片、工具格子、按钮
    val xl = RoundedCornerShape(AsterRadius.xl)   // 对话框、浮层
    val sheet = RoundedCornerShape(topStart = AsterRadius.xl, topEnd = AsterRadius.xl)
    val pill = CircleShape
}
object AsterSpace { val x1 = 4.dp; val x2 = 8.dp; val x3 = 12.dp; val x4 = 16.dp; val x6 = 24.dp; val x8 = 32.dp
    val gutter = 20.dp        // 普通页面左右边距（创作、下载、设置现在就是 20）
    val readingGutter = 22.dp // 对话与剧情阅读区（现在就是 22）
    val sheetGutter = 20.dp   // 弹层内边距（现在 18 和 20 混用）
}
object AsterStroke { val hairline = 1.dp }
object AsterPress { const val small = 0.96f; const val control = 0.97f; const val surface = 0.985f }
object AsterElevation { val none = 0.dp; val raised = 2.dp; val floating = 6.dp; val modal = 18.dp }
```

**取值依据**（不含旧故事页的统计）

- 圆角：现有 24 种取值、189 处。按就近归档后的分布：8dp 档 19 处，12dp 档 36 处，16dp 档 67 处，20dp 档 52 处，28dp 档 13 处。
- 按压缩放：原统计有 13 种取值（0.86–0.985）。原方案建议小控件统一到 0.90，压缩偏强，且当前 modifier 将压缩量限制在 0.10，0.86 与 0.90 的实际结果未必不同。修订为先试 0.96 / 0.97 / 0.985 三档，属于待真机调整的起点，不机械替换已有认可效果。
- 描边：1dp 用了 76 次，0.8dp 用了 11 次，另有 0.5、1.3、2 各一次，统一为 1dp。
- 阴影：原统计有 0/1/2/4/6/10/18 七种，保留无阴影，并为确实悬浮的表面提供少量档位；普通纸面卡片不因令牌存在就新增阴影。

上述数量沿用原统计，不是验收指标。圆角、描边和阴影的最终值需要结合嵌套边界、背景及控件用途判断。

**例外，保持原样**

输入胶囊（圆角在 30 与 24 之间做动画）、星岛、Clawd、思考档位滑杆（自绘）。

## 5. 公共组件

**5.1 弹层宿主 `AsterSheet`**

三处各自写了一遍 `ModalBottomSheet` 的形状、拖拽条和内边距：`AsterOptionsSheet.kt:24`、`AdChoiceSheets.kt:76`、`QuickModelSwitcher.kt:88`，内边距 18 和 20 混用。抽成一个宿主，三处都改用它。顺带统一：对话页「输入选项」的标题没有图标，剧情页「剧情工具」有，二者取一。

**5.2 输入框 `AsterTextField`**

原统计有 30 处裸 `OutlinedTextField`，圆角有 12/15/16/17/18 五种（另有 3 处用默认形状）。可以包装统一默认形状和颜色，但先核对现有调用需要的行为参数。

包装需保留 `enabled`、`readOnly`、`isError`、键盘类型与 IME action、`keyboardActions`、密码遮罩、行数、`modifier` 的焦点与测试语义，以及必要的文字选区状态。例如设置中的 Key 输入有密码遮罩，预设编辑器有超长错误态，搜索有专用 IME 动作。不能为了简化接口移除这些能力；自定义编辑器不强行套入普通表单组件。

位置：`SettingsScreen.kt` 11 处，`MainActivity.kt` 4 处，`RpScreen.kt` 3 处，`PromptInjectionSheet.kt` 2 处，`ModelContextDialog.kt` 2 处，其余文件各 1 处（`ChatScreen`、`DrawScreen`、`MediaDownloadScreen`、`QuickModelSwitcher`、`AdChoiceSheets`、`SkillLibrarySheet`、`TavernPromptEditor`、`UserNamePromptDialog`）。

**5.3 开关**

设置页用 `AsterSwitch`（陶土色轨道），弹层里用的是原生 `Switch`（打开时是黑色轨道），同一个应用里两种开关。把这四处换成 `AsterSwitch`：
`ConversationUi.kt:862`（`ComposerToolToggle`，对话和剧情的工具弹层都用它）、`ChatScreen.kt:3029`、`PromptInjectionSheet.kt:78` 和 `:198`。

注意：保留 Switch 角色、选中/禁用状态、48dp 触控目标和单次切换行为。若外层整行已处理点击，要避免内外重复切换。`SharedUiInteractionTest` 中受影响的语义用例按名称选择运行，不默认重跑整类里的旧故事相关测试。

**5.4 按钮**

原统计有 21 处原生 `TextButton`/`FilledTonalButton`/`FilterChip`/`AlertDialog`，集中在 `PromptInjectionSheet.kt`（6）、`TavernPromptEditor.kt`（5）、`ChatIllustrationCard.kt`（2）、`ChatIllustrationServiceCard.kt`（2）、`ModelContextPresets.kt`（2）、`ChatScreen.kt`（2），`SettingsScreen.kt` 与 `MainActivity.kt` 各 1。

按语义选择共用组件，而非全部替换成同一种胶囊。选择芯片保留 selected 与选择角色，文本次级动作保留轻量层级，破坏性操作保留确认、取消、返回和外部关闭约定；对话框还需保留可滚动长内容和键盘适配。需要时给公共组件增加合适的变体。

**5.5 进度条**

原统计有 12 处原生进度条，高度有 4/5/6dp 三种。可包装 `AsterProgressBar`，区分线性/圆形、确定/不确定的实际用途。统一同类默认样式，保留进度语义、阶段说明和取消入口；无法测量百分比的任务继续用不定进度，不人为伪造百分比。

## 6. 根据视觉样板迁移圆角与按压规则

待主规划 V1–V2 的视觉样板形成，再结合本清单步骤 4、5 实施。按组件类别或一组相邻调用推进，一批完成后编译；纯数字替换不要求每个文件单独编译。下表保留原候选映射作为定位参考，最终档位随样板更新，不直接作为批量替换指令。

**映射**

| 现有取值 | 替换为 |
| --- | --- |
| 5、6、7、8 | `AsterShape.xs` |
| 10、11、12、13 | `AsterShape.sm` |
| 14、15、16、17 | `AsterShape.md` |
| 18、19、20、22 | `AsterShape.lg` |
| 23、24、26、28、30、32 | `AsterShape.xl` |
| 2、4 | 不动（细线和指示条） |

**规则**

- 按用途选档，不要只看数字。上表是默认值；如果某处的用途明显属于另一档（比如 14dp 的图标底），按步骤 4 里的用途注释来。
- 嵌套时优先维持内外轮廓协调，“外圆角减去内边距”只作几何参考；出现零/负值、同心装饰或图片区裁切时按用途处理，允许命名例外，不硬塞进五档。
- 非对称圆角（弹层、侧栏）手工处理，弹层用 `AsterShape.sheet`。
- 按压缩放同理归到 `AsterPress` 三档。

**建议顺序**：`ui/components/` → `MainActivity.kt` → `ChatScreen.kt` → `RpScreen.kt` → `SettingsScreen.kt` → `DrawScreen.kt` → `MediaDownloadScreen.kt` → `ui/media/`。

**验收**：用 `rg` 辅助发现残留，再核对用途与例外；单行匹配会漏掉多行、非对称和动态圆角，数量不能证明完成。抽查输入框、工具格子、图片裁切、弹层边缘与触控边界，走查受影响页面，无变形、裁切或层级退化。

## 7. 颜色

**7.1 强调色做文字时对比度不足**

陶土色 `#D97757` 在浅色画布上的对比度是 2.81:1，在卡片面上是 2.96:1，低于正文需要的 4.5:1。它现在被直接用作文字色：行内代码（`ChatScreen.kt:2680`）、『』括起的文字（`:2626`），以及 15 处 `Text(color = Accent)`（`DrawScreen.kt` 3 处、`ChatScreen.kt` 2 处，另有 10 个文件各 1 处）。

本次复审按源码中的不透明 sRGB 色值重新计算，上述比例及下方候选色比例一致。这里采用普通文字至少 4.5:1 的目标，必要非文字图形参考 3:1；不把所有装饰色按文字要求替换。[文字对比度](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum)、[非文字对比度](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast)。

改法：`AsterPalette` 增加 `accentInk`，专供文字和线性小图标使用；填充、选中态、开关轨道继续用 `accent`。

- 浅色 `#A84A2E`：画布 5.13，卡片面 5.41，淡陶土底 4.98，凹面 4.61。
- 深色沿用 `#E28769`：画布 6.94。

`AsterPalette` 有三处构造（`Theme.kt:56`、`:78`、`:323` 的主题过渡插值），都要补上新字段。

按真实背景逐处选择：不透明背景的计算不能直接外推到带 alpha、图片、渐变和主题过渡中的组合。保留填充专用的 `OnAccent` 配对，不能把深色文字专用色用于陶土按钮后使字反而不清楚；候选色也需覆盖禁用态与深色面板。

**7.2 平台色没有深色版本**

`MediaDownloadScreen.kt:875–886` 的抖音、X、B 站六个色值是写死的浅色。深色模式下，浅底的平台标签会成为页面上最亮的东西。按现有 `sage`/`sageSoft` 的做法各配一组深色值，放进调色板或就地按 `LocalThemeDark` 取值。

**7.3 不用动的**

`DrawScreen.kt` 里三处 `#BDB8B2` 在全屏看图界面上，那里始终是深色背景。

## 8. 阅读内容块减负（可选，属于审美判断）

每个 Markdown 表格现在都带一个标题行：图标底、「数据表格」、「3 列 · 3 行」和复制按钮（`ChatScreen.kt:2273` 起）。表格本身已经说明了自己是表格，这一行占了阅读流里的位置。

建议：去掉图标底和标题文字，只在右上角保留复制入口；保留可读的操作说明和至少 48dp 触控目标。需要左右滑动时显示提示，数值对齐与表格阅读语义不变。代码块的头部（语言、换行、复制）信息有用，保持不变。

这一条是我的判断，没有客观标准。不认同就跳过。

## 9. 第二优先级页面

前面步骤会影响这些页面的共用控件，但不等于已经覆盖其完整体验。每页先选最影响使用的具体问题落地；未审查的流程明确保留为待办。逐页检查要点：

- **创作**：参考图卡片、画面设置展开与收起、生成/停止/失败恢复、任务进度和结果预览。核对主操作是否容易找到、参数展开是否挤压内容，以及结果出现时的布局衔接。
- **下载**：平台标签行里有 10sp 的字（`MediaDownloadScreen.kt:849`），偏小；另外四处字号在 11sp 以下（`PromptInjectionSheet.kt:276`、`:299`，`StarIslandUsageDemo.kt:384`，以及 10.5sp 的角标），统一提到 11sp 以上或确认确实只是装饰。
- **设置**：重点看搜索到编辑服务的路径、密码与错误态、独立服务分配摘要，以及键盘/大字号下保存操作是否可达；步骤 5.2 只能解决其中一部分。
- **下载补充**：检查输入、解析、下载、预览和保存的主链路，区分真实阶段与百分比，确认错误恢复及播放器切换时的稳定性。
- **星岛**：独立的 View 和窗口实现（`island/`），有自己的 `IslandPalette`，色值与主应用基本一致但各写了一份。原审查只读了调色板，尚未审查交互。后续需要核对常驻/展开/收起/反向、框选和确认坐标、焦点与系统返回、主 App 主题同步及 Clawd 协调。现有窗口与物理实现先保留，定位问题后再决定是否改；无证据时不得称为已完成星岛升级。

按受影响页面选取同内容前后截图；共用组件选代表页面覆盖深色和 1.4 倍字号，避免每次改一个颜色就重复完整矩阵。关键动效用短录屏与手动操作补充。软件渲染模拟器不作为流畅度和真实震感的验收设备。

## 10. 收尾

1. 将第 12 节的失败按证据分类。`AsterUiSmokeTest` 查找已消失的「理清思路」时，更新为当前用户行为的断言；只有确认整个行为已废弃时才删除对应场景。带 story 名称的用例可能仍守护 RP 复用的公共渲染/输入组件，不能仅按名称删除。真实触控目标等缺陷应在相关组件步骤修复。
2. 把最终令牌、组件语义、动画与降级规则写成一页设计说明，与代码对应，保留少量合理例外。
3. 交付记录包含实际提交、针对性检查、前后截图/录屏、未解决项和预览包来源。每步可回退，预览包按阶段生成。
4. 正式合并与发版另定。原文提到的“4.5”不作为本次复审确认的版本目标，也不预先锁定 versionCode 90；真正发版时核对届时 main 与最新发布版本，按明确的版本规则递增，并更新 `CHANGELOG.md` 和 README。本次仅修改方案。

## 11. 验证工具（本机）

**模拟器**

本机没有连接真机。我在仓库的 `/.avdhome/`（已在 `.gitignore` 里）建了一个无窗口模拟器，系统镜像借用的是 Qoder 目录下的 android-34：

```bash
cd "/Users/mac/Documents/all project/aster"
Q=/Users/mac/Documents/Qoder/2026-11-08/af1f2192/apk_work/android-sdk
ANDROID_AVD_HOME="$PWD/.avdhome" ANDROID_SDK_ROOT="$Q" "$Q/emulator/emulator" -avd aster -no-window -no-audio -no-boot-anim -no-snapshot &
```

它是软件渲染，布局、交互和崩溃可以验证，**帧率和手感不能作数**。`.avdhome/ui.sh` 里有几个按文字点按、导出界面文本、截图的小函数。以上工具路径是本机现状，换环境需重新确认，不是仓库的构建依赖。当前复审未启动它。

**截图走查**

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
# 先用 adb devices 确认这是专用模拟器，序列号按实际值填写。
DESIGN_TEST_DEVICE=emulator-5554
adb -s "$DESIGN_TEST_DEVICE" install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s "$DESIGN_TEST_DEVICE" install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$DESIGN_TEST_DEVICE" shell am instrument -w -e class com.adong.adchat.DesignReviewShots com.adong.adchat.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$DESIGN_TEST_DEVICE" pull /sdcard/Android/data/com.adong.adchat/files/design-review
```

它用本地数据走 19 个界面（对话首页、输入展开、阅读、模型选择、工具、侧栏、剧情、创作、下载、设置及深色），不发任何请求。改动前的基线图在 `artifacts/design-upgrade-20261110/before/`。

夹具会写入测试会话、配置与主题，必须在专用模拟器/测试数据环境运行，不能为了截图直接在用户日常手机上执行。测试跳过的站点与仍为占位的内容记录为未覆盖，不能算作通过。

两个已知局限：`04-chat-reading-top` 这一站目前会被跳过；剧情的两张（`09-rp-reading`、`17-dark-rp`）拍到的是 HTML 块的「正在加载预览…」占位，在测试进程里等 6 秒也没有加载出来，手动打开应用时能正常加载。看 HTML 块请手动操作。

**签名注意**

debug 包和 release 包签名不同，不能互相覆盖。在模拟器上可以先卸载再装；**在你的手机上只装 release 包**，卸载会丢掉会话和配置。

**按风险选必要检查**

| 改动 | 必要验证 |
| --- | --- |
| 仅修改方案 | 文件、引用与内容一致性检查，不构建 App、不启动设备 |
| 步骤 1 沙盒与测量 | 定向运行文档与原生桥相关用例；真实 WebView 正反向行为检查；更新/销毁模式检查 |
| 步骤 2 默认颜色 | 最小夹具与少量样式样例的深浅色检查，确认实际加载完成 |
| 步骤 3 触觉与交互源 | 工具弹层的快速滚动、正常点击和取消检查，可靠的请求计数或明确记录局限 |
| 步骤 4–7 共用样式与组件 | 一批改动后编译；对受影响行为运行定向回归，选代表页面核对外观、输入与语义 |
| 步骤 8–9 局部页面 | 改到的场景做一次必要走查；没有新风险不重复整套截图和全量测试 |
| 阶段交付 / 发版 | 按实际范围做相关回归和 Release 构建，正式发布沿用现有 CI 必需检查 |

定向 JVM 检查例如：`./gradlew :app:testDebugUnitTest --tests com.adong.adchat.ui.components.HtmlPreviewDocumentTest --tests com.adong.adchat.ui.components.OfflineArtifactViewTest`。设备端同样按类/方法选择受影响用例。只有跨模块行为改动、出现新失败或正式 CI 要求时，才扩大测试范围；不把 856 项全量测试作为每一步的固定门槛。

## 12. 基线：改动前就失败的设备端测试

在 `cb60f96` 上跑完整设备端测试：37 项，12 项失败。

以上是原作者记录。本次复审未重新运行这 37 项，也未逐项补齐 9 项未排查用例的原因；本地 JVM XML 的 856 项通过不能替代这些设备端结果。

| 测试 | 失败原因 |
| --- | --- |
| `AsterUiSmokeTest.primaryPagesAndKeyboardRemainUsable` | 找不到「理清思路」，该文案已不在源码里 |
| `AsterUiSmokeTest.settingsEditorKeepsAdvancedOptionsCollapsed` | 元素未显示 |
| `OptimizedChatUiTest.longMessageRemainsReadableAndCanExpandWithLargeFonts` | 「展开/收起」高度 42.9dp，要求至少 48dp |
| `ComposerReasoningSliderTest.continuousDragCommitsOnlyNearestSupportedLevelOnRelease` | 未逐项排查 |
| `SharedUiInteractionTest` 的 8 项：`completedStoryReplyKeepsRegenerateAction`、`copyConfirmsAndSelectedTabDoesNotRetrigger`、`drawerKeepsSearchNavigationAndActionsReachable`、`modelPickerPresetsSaveWithoutChangingRoutesOrClosingSheet`、`sharedSkillSelectionPersistsAndDoesNotLeakIntoOtherWorkspaces`、`storyProseUsesNativeSectionsAndRetainsPlanning`、`storyThoughtCollapsesWhenStreamingBodyStarts`、`storyThoughtSectionsAreIndependent` | 未逐项排查 |

12 项的首条报错合计：5 项「Failed to inject touch input」，3 项元素未显示，其余各 1 项（元素不存在、开关状态断言、高度不足、滚动失败）。除了前三行，我没有逐项对应原因；「无法注入触摸」这一类可能与模拟器有关，也可能不是。

后续按以下规则处理：

- 保留基线提交、具体断言和原始日志/截图路径，能够对应到单个用例。缺少原始证据的分类继续标为待查。
- 文案过期：保留被测试的有效操作，用稳定语义或当前标签更新定位。
- 42.9dp 高度项：核对实际触控区域、语义边界及相邻目标是否重叠，作为候选真实可达性问题；不能为通过测试直接调低标准。
- 触摸注入失败及其余未明项：只在改到相关组件或需要证明回归时做针对性排查，先区分窗口/焦点/遮挡/测试条件与产品问题。不能未经定位就归咎于模拟器。
- 已存在的失败用于区分新增回归，不等于产品已达标；有关联的真实缺陷仍应处理，与本次范围无关的保留清单。

## 13. 我没有验证的事

- 没有真机。所有结论来自软件渲染的模拟器和读代码。
- 步骤 1 修订后的独立测量/nonce 方案，以及步骤 3 的共享交互源、确认点击触觉与回退处理，都是待实现设计，没有写成代码跑过。
- 步骤 2 整个是推断：深色主题下 HTML 块的实际样子我没有看到。默认值是否适合你常用的角色卡，也取决于那些卡，我没有样本。
- 步骤 1 里"普通 HTML 块固定 340dp"是按代码推断的，只在 `<details>` 的情况下实测过。
- 启动崩溃修复：只在测试驱动下复现过（修复前 12 次里 3 次，修复后 16 次 0 次）；直接冷启动旧包 25 次没有崩，真实出现频率未知。
- 步骤 4 的候选按压、圆角与阴影档位未做真机审美和手感确认；数量收敛不代表品质验收通过。
- 本次复审没有补齐 HTML 的真实运行检查、深色最小夹具、触觉计数或设备端失败归因，不新增“已验证”结论。

## 14. 本次复审记录

- 根据源码确认并收紧步骤 1 的权限分层，补上 helper CSP 组合、nonce 生命周期、只给可信标签加 nonce、专用桥与真实 WebView 验证要求。
- 更正步骤 2 的 CSS 优先级假设，以及“任一片段可读即可排除”的验证方式。
- 步骤 3 将视觉按压和确认点击触觉区分，包名历史行计数降为辅助证据。
- 更正 `shapes` 已被 MaterialTheme 使用的事实；候选档位须先验证，保留扁平表面和合法几何例外。
- 共用组件迁移补齐密码、IME、错误态、选择/开关语义及进度种类等行为边界。
- 重算颜色比例并确认原数值正确；补充真实背景、前景配对与主题过渡的检查。
- 补充视觉标杆与逐页主链路验收，按风险缩减测试；保留既有失败及未验证事项，正式发布另定。

本清单随视觉主规划分离保存；本次不修改应用源码、测试实现、版本号或 Git 历史。
