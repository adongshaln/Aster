package com.adong.adchat.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RuntimeShader
import android.graphics.SweepGradient
import android.os.Build
import android.view.View
import kotlin.math.cos

/**
 * 灵动岛光圈自绘视图 —— 「渲染是状态的纯函数」。
 *
 * 本类不持有任何动画/逻辑，只存几个浮点参数；[onDraw] 只按参数画，不写逻辑。
 * 动画编排（呼吸、弹簧、扫描弧）全部在 [IslandView] 里，只负责改这里的参数。
 *
 * 四层质感（设计规格 docs/GLOW_ISLAND_SPEC.md）：
 * 1. 黑底：内侧 1.5dp 间隙纯黑（OLED 像素熄灭，与镜头孔融为一体），外侧黑垫
 *    2.5dp（发光要有暗处可发）；
 * 2. 核心线：1.5dp 主体 + 中心 0.6dp 灯丝线（中心偏白、边缘偏饱和的色温层次）；
 * 3. 光晕：3 层渐宽渐淡描边 ADD 叠加（[glow] 控制强度）；
 * 4. 流光：SweepGradient 首尾同色，只转 local/canvas matrix（[headAngle]）。
 *
 * 渲染分两个分支：API 33+ 用一个 AGSL [RuntimeShader] 画整个光环
 * （距离场 d = |length(p-c) - R|，核心 1-smoothstep，光晕 exp(-d·k)，
 * 无层叠台阶）；API 26..32 回退到分层描边实现。两个分支视觉参数同源。
 *
 * 待机零重绘：本 View 设 LAYER_TYPE_HARDWARE，待机时外部只改 View.alpha
 * （不触发 onDraw），只有交互和处理中才逐帧改参数。
 */
class IslandGlowView(context: Context) : View(context) {

    companion object {
        /** 光圈外径（dp）：比挖孔（约 20dp）大一圈。窗口 y 定位以此对准挖孔中心。 */
        const val RING_OUTER_DP = 28f
        /** 悬浮窗边长（dp）：光圈外径 + 光晕约 6dp + 黑垫 2.5dp，上下各留一份。 */
        const val WINDOW_SIZE_DP = 44f

        /** 圆环内缘（dp）：挖孔半径约 10dp + 1.5dp 纯黑间隙。形变只向外长，内缘固定。 */
        private const val INNER_EDGE_DP = 11.5f
        private const val CORE_LINE_DP = 1.5f
        private const val FILAMENT_LINE_DP = 0.6f
        private const val OUTER_PAD_DP = 2.5f

        // 待机调色板：橙（中心偏白 → 主体橙 → 光晕深橙）
        private const val COLOR_FILAMENT = 0xFFF3D6.toInt()
        private const val COLOR_BODY = 0xFFB020.toInt()
        private const val COLOR_AMBER = 0xFF9400.toInt()
        private const val COLOR_PALE = 0xFFE9A8.toInt()
        private const val COLOR_GLOW = 0xFF6A00.toInt()

        // 识别成功换色调色板：换色只发生在收束最亮帧，不插值（避免混浊过渡色）
        private const val COLOR2_FILAMENT = 0xFFE4FFF2.toInt()
        private const val COLOR2_BODY = 0xFF34D399.toInt()
        private const val COLOR2_AMBER = 0xFF0FA968.toInt()
        private const val COLOR2_PALE = 0xFFA7F3D0.toInt()
        private const val COLOR2_GLOW = 0xFF0E9F6E.toInt()

        // 光晕 3 层描边：宽度（dp）与基准 alpha，乘 glow 后使用
        private val GLOW_WIDTHS_DP = floatArrayOf(3f, 5f, 8f)
        private val GLOW_ALPHAS = floatArrayOf(0.35f, 0.18f, 0.08f)

        /**
         * AGSL：一个 shader 画整个光环。
         * - 距离场 d = |length(p - c) - R|，核心 1-smoothstep(0, w, d)，光晕 exp(-d*k)*glow；
         * - 色温 mix(灯丝白, 主体色, smoothstep(0, w*4, d))：中心偏白、边缘偏饱和；
         * - 流光 = 角度相对 headAngle 的余弦亮度调制；
         * - 拖尾弧 = 头亮尾暗的角度 bump（u_arcSweep <= 0.5 表示关闭）。
         */
        private const val AGSL_SRC = """
uniform float2 ucenter;
uniform float u_ringR;
uniform float u_lineW;
uniform float u_glowK;
uniform float u_glow;
uniform float u_headAngle;
uniform float u_arcSweep;
uniform float u_colorMix;
// AGSL 要求：setColorUniform 对应的 uniform 必须带 layout(color) 声明，
// 否则抛 IllegalArgumentException（HyperOS 实测崩溃）。
layout(color) uniform half4 u_colFilament;
layout(color) uniform half4 u_colBody;
layout(color) uniform half4 u_colGlow;
layout(color) uniform half4 u_colFilament2;
layout(color) uniform half4 u_colBody2;
layout(color) uniform half4 u_colGlow2;
half4 main(float2 p) {
    float d = abs(length(p - ucenter) - u_ringR);
    float core = 1.0 - smoothstep(0.0, u_lineW, d);
    float glow = exp(-d * u_glowK) * u_glow;
    float temp = smoothstep(0.0, u_lineW * 4.0, d);
    half4 filamentCol = mix(u_colFilament, u_colFilament2, half(u_colorMix));
    half4 bodyCol = mix(u_colBody, u_colBody2, half(u_colorMix));
    half4 glowCol = mix(u_colGlow, u_colGlow2, half(u_colorMix));
    half3 col = mix(filamentCol.rgb, bodyCol.rgb, temp);
    float ang = degrees(atan(p.y - ucenter.y, p.x - ucenter.x));
    float rel = u_headAngle - ang;
    rel -= floor(rel / 360.0) * 360.0;
    float flow = 0.82 + 0.28 * (0.5 + 0.5 * cos(6.28318 * (1.0 - rel / 360.0)));
    float a = clamp((core + glow * 0.45) * flow, 0.0, 1.0);
    half3 rgb = col * a;
    if (u_arcSweep > 0.5) {
        if (rel < u_arcSweep) {
            float t = 1.0 - rel / u_arcSweep;
            float bump = t * t;
            half3 headCol = mix(bodyCol.rgb, half3(1.0), 0.55 * t);
            rgb += headCol * bump * 0.8;
            a = clamp(a + bump * 0.8, 0.0, 1.0);
            rgb = min(rgb, half3(a));
        }
    }
    return half4(rgb, a);
}
"""
    }

    // ---------- 状态参数（动画只改这些，onDraw 只读） ----------

    /** 光晕强度：0..1。待机基线 0.2，按下/扫描时 1。 */
    var glow: Float = 0.2f
        set(value) { field = value; invalidate() }

    /** 线宽缩放：按下弹簧驱动 1 -> 1.4（带过冲）。形变只向外长，内缘固定。 */
    var lineWidthScale: Float = 1f
        set(value) { field = value; invalidate() }

    /** 0 = 橙色待机；1 = 识别成功换色。只做 0/1 硬切（在最亮帧换色，不插值）。 */
    var colorMix: Float = 0f
        set(value) { field = value; invalidate() }

    /** 流光头角度（度），同时是进度弧的亮头位置。SweepGradient 首尾同色可无缝旋转。 */
    var headAngle: Float = 0f
        set(value) { field = value; invalidate() }

    /** -1 = 无进度弧；0..1 = 弧（见 [arcIndeterminate]）。 */
    var progress: Float = -1f
        set(value) { field = value; invalidate() }

    /**
     * progress 处于 0..1 时：true = Material 不确定弧（弧长在 40°..220° 间正弦伸缩，
     * 头部随 [headAngle] 旋转）；false = 确定弧（弧长 = progress * 360°）。
     */
    var arcIndeterminate: Boolean = false
        set(value) { field = value; invalidate() }

    /** 恢复待机参数（收起/隐藏时调用）。呼吸不在此处理，外部只改 alpha。 */
    fun reset() {
        glow = 0.2f
        lineWidthScale = 1f
        colorMix = 0f
        headAngle = 0f
        progress = -1f
        arcIndeterminate = false
        alpha = 1f
    }

    // ---------- 渲染 ----------

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_GLOW
        // ADD 混合：叠加处自然变亮发白。Paint.blender(BrandMode.PLUS) 是 API 31+
        // 符号，编译目标下不可直接引用，统一用全版本可用的 PorterDuff.ADD。
        @Suppress("DEPRECATION")
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val filamentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_FILAMENT
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** API 33+ 的 AGSL 渲染器；创建失败（厂商 shader 编译异常）则回退分层实现。 */
    private val agslShader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(AGSL_SRC) }.getOrNull()
        } else null

    /** 光晕衰减系数（px^-1）：1/e 衰减长度约 4dp。 */
    private val glowK = 0.25f / density

    private val filamentColor: Int
        get() = if (colorMix > 0.5f) COLOR2_FILAMENT else COLOR_FILAMENT
    private val bodyColor: Int
        get() = if (colorMix > 0.5f) COLOR2_BODY else COLOR_BODY
    private val paleColor: Int
        get() = if (colorMix > 0.5f) COLOR2_PALE else COLOR_PALE
    private val glowColor: Int
        get() = if (colorMix > 0.5f) COLOR2_GLOW else COLOR_GLOW

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outerR = dp(RING_OUTER_DP) / 2f
        val innerEdge = dp(INNER_EDGE_DP)
        val coreWidth = dp(CORE_LINE_DP) * lineWidthScale
        // 内缘固定，形变只向外长（往里缩会被镜头孔“吃掉”）
        val ringR = innerEdge + coreWidth / 2f

        // 1. 黑底：一个实心黑圆同时充当内间隙与外垫（半径 = 光圈外径 + 2.5dp）
        canvas.drawCircle(cx, cy, outerR + dp(OUTER_PAD_DP), blackPaint)

        val sweep = arcSweepDeg()
        val shader = agslShader
        // 厂商 ROM 的 shader 运行时异常也回退到分层渲染，绝不让光圈崩掉进程
        if (shader != null) {
            val drew = runCatching { drawAgsl(canvas, shader, cx, cy, ringR, coreWidth, sweep) }.isSuccess
            if (!drew) drawLayered(canvas, cx, cy, ringR, coreWidth, sweep)
        } else {
            drawLayered(canvas, cx, cy, ringR, coreWidth, sweep)
        }
    }

    /** 当前弧长（度）：progress < 0 无弧；不确定模式 40°..220° 正弦伸缩；确定模式 progress*360°。 */
    private fun arcSweepDeg(): Float = when {
        progress < 0f -> 0f
        arcIndeterminate -> 40f + 90f * (1f - cos(2.0 * Math.PI * progress.toDouble()).toFloat()) // 40..220
        else -> progress.coerceIn(0f, 1f) * 360f
    }

    // ---------- AGSL 分支（API 33+） ----------

    private fun drawAgsl(
        canvas: Canvas,
        shader: RuntimeShader,
        cx: Float, cy: Float, ringR: Float, coreWidth: Float, sweep: Float
    ) {
        shader.setFloatUniform("ucenter", cx, cy)
        shader.setFloatUniform("u_ringR", ringR)
        shader.setFloatUniform("u_lineW", coreWidth / 2f)
        shader.setFloatUniform("u_glowK", glowK)
        shader.setFloatUniform("u_glow", glow)
        shader.setFloatUniform("u_headAngle", headAngle)
        shader.setFloatUniform("u_arcSweep", sweep)
        shader.setFloatUniform("u_colorMix", if (colorMix > 0.5f) 1f else 0f)
        shader.setColorUniform("u_colFilament", COLOR_FILAMENT)
        shader.setColorUniform("u_colBody", COLOR_BODY)
        shader.setColorUniform("u_colGlow", COLOR_GLOW)
        shader.setColorUniform("u_colFilament2", COLOR2_FILAMENT)
        shader.setColorUniform("u_colBody2", COLOR2_BODY)
        shader.setColorUniform("u_colGlow2", COLOR2_GLOW)
        shaderPaint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
        shaderPaint.shader = null
    }

    // ---------- 分层回退分支（API 26..32） ----------

    private fun drawLayered(
        canvas: Canvas,
        cx: Float, cy: Float, ringR: Float, coreWidth: Float, sweep: Float
    ) {
        // 3. 光晕：3 层渐宽渐淡描边，ADD 混合
        if (glow > 0.01f) {
            glowPaint.color = glowColor
            for (i in GLOW_WIDTHS_DP.indices) {
                glowPaint.strokeWidth = dp(GLOW_WIDTHS_DP[i])
                glowPaint.alpha = (GLOW_ALPHAS[i] * glow * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, ringR, glowPaint)
            }
        }

        // 4. 核心线：SweepGradient 首尾同色，转 canvas 即流光（不重建 shader）
        corePaint.shader = sweepGradient(cx, cy)
        corePaint.strokeWidth = coreWidth
        canvas.save()
        canvas.rotate(headAngle, cx, cy)
        canvas.drawCircle(cx, cy, ringR, corePaint)
        // 灯丝线：同圆更细的描边
        filamentPaint.color = filamentColor
        filamentPaint.strokeWidth = dp(FILAMENT_LINE_DP)
        canvas.drawCircle(cx, cy, ringR, filamentPaint)
        canvas.restore()
        corePaint.shader = null

        // 5. 识别弧：头亮尾暗的渐变描边。旋转 canvas 使渐变 headFrac 处对齐弧头（headAngle）。
        if (sweep > 0.5f) {
            val headFrac = (sweep / 360f).coerceIn(0.02f, 0.98f)
            val tail = bodyColor
            setArcAlpha(arcPaint, tail, filamentColor, headFrac)
            arcPaint.strokeWidth = coreWidth * 1.6f
            canvas.save()
            canvas.rotate(headAngle - sweep, cx, cy)
            val r = maxOf(cx, cy)
            val bounds = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(bounds, 0f, 360f, false, arcPaint)
            canvas.restore()
            arcPaint.shader = null
        }
    }

    /** 识别弧 SweepGradient：弧尾透明 → 渐亮 → 弧头灯丝白 → 头后立即归零。 */
    private fun setArcAlpha(paint: Paint, tailColor: Int, headColor: Int, headFrac: Float) {
        val tailA = (0.55f * 255f).toInt()
        val headA = 255
        val colors = intArrayOf(
            tailColor and 0x00FFFFFF,
            (tailColor and 0x00FFFFFF) or (tailA shl 24),
            (headColor and 0x00FFFFFF) or (headA shl 24),
            tailColor and 0x00FFFFFF,
            tailColor and 0x00FFFFFF
        )
        val positions = floatArrayOf(
            0f,
            headFrac * 0.7f,
            headFrac,
            (headFrac + 0.02f).coerceAtMost(1f),
            1f
        )
        val cx = width / 2f
        val cy = height / 2f
        paint.shader = SweepGradient(cx, cy, colors, positions)
    }

    /** 核心线 SweepGradient：橙 → 琥珀 → 淡黄 → 橙（首尾同色）。 */
    private fun sweepGradient(cx: Float, cy: Float): SweepGradient {
        val body = bodyColor
        return if (colorMix > 0.5f) {
            SweepGradient(
                cx, cy,
                intArrayOf(body, COLOR2_AMBER, COLOR2_PALE, body),
                floatArrayOf(0f, 0.33f, 0.66f, 1f)
            )
        } else {
            SweepGradient(
                cx, cy,
                intArrayOf(body, COLOR_AMBER, COLOR_PALE, body),
                floatArrayOf(0f, 0.33f, 0.66f, 1f)
            )
        }
    }
}
