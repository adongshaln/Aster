package com.adong.adchat.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.view.View
import kotlin.math.cos

/**
 * 灵动岛光圈自绘视图 —— 「渲染是状态的纯函数」。
 *
 * 本类不持有任何动画或业务逻辑，仅暴露一组纯视觉状态参数：
 * - [glow]：光晕强度 (0..1)
 * - [lineWidthScale]：核心线宽缩放（按下弹簧驱动 1 -> 1.4，内缘固定只向外扩）
 * - [colorMix]：调色板切换（0 = 待机琥珀橙，1 = 识别成功翡翠绿；收束最亮帧硬切）
 * - [headAngle]：流光与扫描弧头部角度 (0..360°)
 * - [progress]：进度弧控制（-1 = 关闭；0..1 = 进度或不确定弧相位）
 * - [arcIndeterminate]：true = Material 风格正弦伸缩扫描弧 (40°..220°)，false = 确定进度弧
 * - [flash]：收束闪光强度 (0..1)，换色瞬间的白芯爆发
 * - [rippleT]：识别成功向外扩散的脉冲波进度 (0..1，-1 表示无涟漪)
 *
 * 四层光学质感（严格对齐 GLOW_ISLAND_SPEC.md）：
 * 1. 黑底与暗垫：内侧 1.5dp 纯黑间隙（OLED 像素熄灭，严防光晕向内渗入镜头孔），
 *    外侧 2.5dp 柔和暗晕衬底（保证浅色壁纸/界面上发光依然有对比度，且不显死黑边）；
 * 2. 色温核心线：1.5dp 主体琥珀橙 + 0.6dp 中心高色温灯丝白，模拟真实光源色温梯度；
 * 3. 指数光晕：API 33+ 采用非对称距离场 AGSL [RuntimeShader]（内侧陡峭截断、外侧指数衰减
 *    + 蓝噪抖动消除暗部色带）；API 26..32 回退到 3 层 ADD 混合描边；
 * 4. 零分配流光：SweepGradient 首尾同色，通过 [Matrix.setRotate] 旋转，onDraw 零对象分配。
 */
class IslandGlowView(context: Context) : View(context) {

    companion object {
        /** 光圈基准外径（dp）：比挖孔（约 20dp）大一圈。 */
        const val RING_OUTER_DP = 28f
        /** 悬浮窗边长（dp）：容纳光圈外径 + 扩散光晕 + 按下外扩 + 脉冲涟漪。 */
        const val WINDOW_SIZE_DP = 48f

        /** 圆环默认内缘半径（dp）：挖孔半径约 10dp + 1.5dp 纯黑间隙。 */
        private const val DEFAULT_INNER_EDGE_DP = 11.5f
        private const val CORE_LINE_DP = 1.5f
        private const val FILAMENT_LINE_DP = 0.65f
        private const val OUTER_DARK_PAD_DP = 3.0f

        // 待机调色板：琥珀流光（注意完整 0xFF Alpha 通道）
        private const val COLOR_FILAMENT = 0xFFFFF3D6.toInt()
        private const val COLOR_BODY = 0xFFFFB020.toInt()
        private const val COLOR_AMBER = 0xFFFF8C00.toInt()
        private const val COLOR_PALE = 0xFFFFE6A3.toInt()
        private const val COLOR_GLOW = 0xFFFF6A00.toInt()

        // 识别成功调色板：翡翠流光（最亮帧瞬切，避免混浊过渡色）
        private const val COLOR2_FILAMENT = 0xFFE8FFF5.toInt()
        private const val COLOR2_BODY = 0xFF34D399.toInt()
        private const val COLOR2_AMBER = 0xFF10B981.toInt()
        private const val COLOR2_PALE = 0xFFA7F3D0.toInt()
        private const val COLOR2_GLOW = 0xFF059669.toInt()

        // 低版本分层光晕：线宽（dp）与基准透明度
        private val GLOW_WIDTHS_DP = floatArrayOf(3.0f, 5.5f, 9.0f)
        private val GLOW_ALPHAS = floatArrayOf(0.38f, 0.20f, 0.09f)

        /**
         * AGSL 单 Pass 光学着色器（API 33+）：
         * - 内孔保护：r < u_innerEdge 时严禁光晕渗入，确保与前摄镜头纯黑融合；
         * - 外侧暗衬：在光环外侧铺一层柔和暗场，使浅色背景下发光依旧通透；
         * - 三阶色温：灯丝白 -> 主体色 -> 外缘深色光晕；
         * - 拖尾亮弧：头部增白增亮、尾部二次曲线衰减；
         * - 扩散涟漪：识别成功时的外扩光波（u_rippleT in 0..1）；
         * - 噪声抖动：消除 8-bit 暗部渐变色带（banding）。
         */
        private const val AGSL_SRC = """
uniform float2 u_center;
uniform float u_innerEdge;
uniform float u_ringR;
uniform float u_lineW;
uniform float u_glowK;
uniform float u_glow;
uniform float u_headAngle;
uniform float u_arcSweep;
uniform float u_colorMix;
uniform float u_flash;
uniform float u_rippleT;

layout(color) uniform half4 u_colFilament;
layout(color) uniform half4 u_colBody;
layout(color) uniform half4 u_colGlow;
layout(color) uniform half4 u_colFilament2;
layout(color) uniform half4 u_colBody2;
layout(color) uniform half4 u_colGlow2;

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

half4 main(float2 p) {
    float2 delta = p - u_center;
    float r = length(delta);

    // 1. 镜头孔内侧纯黑区（含 1.5dp 熄屏黑缝），内缘抗锯齿过渡
    if (r < u_innerEdge - 1.0) {
        return half4(0.0, 0.0, 0.0, 1.0);
    }
    float innerMask = smoothstep(u_innerEdge - 0.8, u_innerEdge + 0.4, r);

    // 2. 非对称距离场：内侧快速收敛防吞孔，外侧指数自然衰减
    float signedD = r - u_ringR;
    float d = abs(signedD);
    float asymD = signedD < 0.0 ? d * 1.85 : d;

    float core = 1.0 - smoothstep(0.0, u_lineW, asymD);
    float filament = 1.0 - smoothstep(0.0, u_lineW * 0.42, asymD);
    float halo = exp(-asymD * u_glowK) * u_glow * innerMask;

    // 3. 三阶色温混合（灯丝白 -> 主体色 -> 深色光晕）
    half3 filCol = mix(u_colFilament.rgb, u_colFilament2.rgb, half(u_colorMix));
    half3 bodyCol = mix(u_colBody.rgb, u_colBody2.rgb, half(u_colorMix));
    half3 glowCol = mix(u_colGlow.rgb, u_colGlow2.rgb, half(u_colorMix));

    float tBody = smoothstep(0.0, u_lineW * 1.1, asymD);
    float tGlow = smoothstep(u_lineW * 0.8, u_lineW * 4.2, asymD);
    half3 ringCol = mix(filCol, bodyCol, half(tBody));
    ringCol = mix(ringCol, glowCol, half(tGlow));

    // 4. 角度流光调制（首尾无缝余弦波）
    float ang = degrees(atan(delta.y, delta.x));
    float rel = u_headAngle - ang;
    rel -= floor(rel / 360.0) * 360.0;
    float flow = 0.84 + 0.24 * (0.5 + 0.5 * cos(6.2831853 * (1.0 - rel / 360.0)));

    float lightA = clamp((core + halo * 0.58) * flow, 0.0, 1.0);
    half3 lightRgb = ringCol * half(lightA);

    // 5. 扫描彗星亮弧（头亮白、尾渐隐）
    if (u_arcSweep > 0.5 && rel < u_arcSweep) {
        float t = 1.0 - rel / u_arcSweep;
        float arcProfile = 1.0 - smoothstep(0.0, u_lineW * 1.65, asymD);
        float headBoost = t * t * arcProfile;
        half3 arcCol = mix(bodyCol, half3(1.0), half(0.65 * t));
        lightRgb += arcCol * half(headBoost * 0.95);
        lightA = clamp(lightA + headBoost * 0.85, 0.0, 1.0);
    }

    // 6. 收束闪光（换色瞬间高光爆发）
    if (u_flash > 0.001) {
        float flashProfile = exp(-asymD * (u_glowK * 0.65)) * u_flash * innerMask;
        lightRgb += mix(filCol, half3(1.0), half(0.6)) * half(flashProfile * 0.9);
        lightA = clamp(lightA + flashProfile * 0.75, 0.0, 1.0);
    }

    // 7. 识别完成向外扩散的涟漪光环
    if (u_rippleT >= 0.0 && u_rippleT <= 1.0) {
        float rippleR = u_ringR + u_rippleT * (u_lineW * 6.5);
        float rippleD = abs(r - rippleR);
        float rippleEnv = (1.0 - u_rippleT) * (1.0 - u_rippleT);
        float rippleRing = (1.0 - smoothstep(0.0, u_lineW * 1.4, rippleD)) * rippleEnv * innerMask;
        lightRgb += mix(bodyCol, filCol, half(0.4)) * half(rippleRing * 0.65);
        lightA = clamp(lightA + rippleRing * 0.55, 0.0, 1.0);
    }

    // 8. 外侧柔和暗衬（2.5dp 暗晕，给浅色界面提供发光对比底）+ 内孔纯黑融合
    float outerDark = exp(-max(0.0, signedD) * (u_glowK * 1.35)) * 0.28 * innerMask;
    float holeBlack = 1.0 - innerMask;
    float darkA = clamp(holeBlack + outerDark * (1.0 - lightA), 0.0, 1.0);

    // 极轻微抖动抹除暗部色带
    float dither = (hash12(p) - 0.5) / 255.0;
    float finalA = clamp(lightA + darkA + dither, 0.0, 1.0);
    half3 finalRgb = clamp(lightRgb + half(dither), half3(0.0), half3(finalA));
    return half4(finalRgb, half(finalA));
}
"""
    }

    // ---------- 纯视觉状态参数（动画只改这些，onDraw 只读） ----------

    /** 动态校准的圆环内缘半径（dp），可根据系统 DisplayCutout 自动微调。 */
    var innerEdgeDp: Float = DEFAULT_INNER_EDGE_DP
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 光晕强度：0..1。待机基线 0.22，按下/扫描时升至 1.0。 */
    var glow: Float = 0.22f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 核心线宽缩放：按下弹簧驱动 1 -> 1.42（带阻尼过冲），内缘固定只向外扩。 */
    var lineWidthScale: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 0 = 待机琥珀色；1 = 识别成功翡翠绿。在收束最亮帧硬切。 */
    var colorMix: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 流光与扫描弧头部角度（度）。 */
    var headAngle: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** -1 = 无进度弧；0..1 = 进度值或不确定扫描弧相位。 */
    var progress: Float = -1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** true = Material 不确定伸缩弧 (40°..220°)；false = 确定进度弧 (0°..360°)。 */
    var arcIndeterminate: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 收束闪光强度：0..1，换色瞬间拉高随后快速衰减。 */
    var flash: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 扩散涟漪进度：-1 = 关闭；0..1 = 向外扩散并消散。 */
    var rippleT: Float = -1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 恢复待机参数（收起/隐藏时调用）。 */
    fun reset() {
        glow = 0.22f
        lineWidthScale = 1f
        colorMix = 0f
        headAngle = 0f
        progress = -1f
        arcIndeterminate = false
        flash = 0f
        rippleT = -1f
        alpha = 1f
    }

    // ---------- 渲染资源（预分配，onDraw 零 GC） ----------

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF000000.toInt()
    }
    private val darkHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x44000000
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        @Suppress("DEPRECATION")
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val filamentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val arcBounds = RectF()
    private val shaderMatrix = Matrix()

    // 缓存低版本 SweepGradient，仅在尺寸变化时创建，帧内只旋转 localMatrix
    private var cachedCx = -1f
    private var cachedCy = -1f
    private var orangeSweepShader: SweepGradient? = null
    private var greenSweepShader: SweepGradient? = null

    /** API 33+ 的 AGSL 渲染器；编译或运行异常时自动回退分层渲染。 */
    private val agslShader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(AGSL_SRC) }.getOrNull()
        } else null

    /** 光晕衰减系数（px^-1）：1/e 衰减长度约 3.8dp。 */
    private val glowK = 0.26f / density

    private val filamentColor: Int
        get() = if (colorMix > 0.5f) COLOR2_FILAMENT else COLOR_FILAMENT
    private val bodyColor: Int
        get() = if (colorMix > 0.5f) COLOR2_BODY else COLOR_BODY
    private val glowColor: Int
        get() = if (colorMix > 0.5f) COLOR2_GLOW else COLOR_GLOW

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val innerEdge = dp(innerEdgeDp)
        val coreWidth = dp(CORE_LINE_DP) * lineWidthScale
        // 内缘固定，形变只向外长，绝不向内吞噬镜头孔
        val ringR = innerEdge + coreWidth / 2f
        val sweep = arcSweepDeg()

        val shader = agslShader
        if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val drew = runCatching {
                drawAgsl(canvas, shader, cx, cy, innerEdge, ringR, coreWidth, sweep)
            }.isSuccess
            if (drew) return
        }
        drawLayered(canvas, cx, cy, innerEdge, ringR, coreWidth, sweep)
    }

    /** 当前弧长（度）：progress < 0 无弧；不确定模式 40°..220° 正弦伸缩；确定模式 progress*360°。 */
    private fun arcSweepDeg(): Float = when {
        progress < 0f -> 0f
        arcIndeterminate -> 40f + 90f * (1f - cos(2.0 * Math.PI * progress.toDouble()).toFloat())
        else -> progress.coerceIn(0f, 1f) * 360f
    }

    // ---------- AGSL 分支（API 33+） ----------

    private fun drawAgsl(
        canvas: Canvas,
        shader: RuntimeShader,
        cx: Float,
        cy: Float,
        innerEdge: Float,
        ringR: Float,
        coreWidth: Float,
        sweep: Float
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        shader.setFloatUniform("u_center", cx, cy)
        shader.setFloatUniform("u_innerEdge", innerEdge)
        shader.setFloatUniform("u_ringR", ringR)
        shader.setFloatUniform("u_lineW", coreWidth / 2f)
        shader.setFloatUniform("u_glowK", glowK)
        shader.setFloatUniform("u_glow", glow)
        shader.setFloatUniform("u_headAngle", headAngle)
        shader.setFloatUniform("u_arcSweep", sweep)
        shader.setFloatUniform("u_colorMix", if (colorMix > 0.5f) 1f else 0f)
        shader.setFloatUniform("u_flash", flash)
        shader.setFloatUniform("u_rippleT", rippleT)
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
        cx: Float,
        cy: Float,
        innerEdge: Float,
        ringR: Float,
        coreWidth: Float,
        sweep: Float
    ) {
        ensureSweepShaders(cx, cy)

        // 1. 外侧柔和暗衬（2.5dp 暗垫，增强浅色背景下的发光对比度）
        darkHaloPaint.strokeWidth = dp(OUTER_DARK_PAD_DP)
        canvas.drawCircle(cx, cy, ringR + dp(1.2f), darkHaloPaint)

        // 2. 光晕：3 层渐宽渐淡描边，ADD 混合
        val effectiveGlow = (glow + flash * 0.7f).coerceIn(0f, 1.4f)
        if (effectiveGlow > 0.01f) {
            glowPaint.color = glowColor
            for (i in GLOW_WIDTHS_DP.indices) {
                glowPaint.strokeWidth = dp(GLOW_WIDTHS_DP[i])
                glowPaint.alpha = (GLOW_ALPHAS[i] * effectiveGlow * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, ringR, glowPaint)
            }
        }

        // 3. 核心流光线：复用缓存 SweepGradient，仅通过 localMatrix 旋转
        val activeSweep = if (colorMix > 0.5f) greenSweepShader else orangeSweepShader
        if (activeSweep != null) {
            shaderMatrix.reset()
            shaderMatrix.setRotate(headAngle, cx, cy)
            activeSweep.setLocalMatrix(shaderMatrix)
        }
        corePaint.shader = activeSweep
        corePaint.strokeWidth = coreWidth
        canvas.drawCircle(cx, cy, ringR, corePaint)
        corePaint.shader = null

        // 4. 中心高色温灯丝线
        filamentPaint.color = filamentColor
        filamentPaint.strokeWidth = dp(FILAMENT_LINE_DP) * (1f + flash * 0.5f)
        filamentPaint.alpha = (225 + (flash * 30f).toInt()).coerceIn(0, 255)
        canvas.drawCircle(cx, cy, ringR, filamentPaint)

        // 5. 扫描彗星弧：严格贴合 ringR 半径（修复原实现误用窗口半宽的问题）
        if (sweep > 0.5f) {
            arcBounds.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            arcPaint.strokeWidth = coreWidth * 1.35f
            arcPaint.color = filamentColor
            // 分三段画渐变亮弧（尾淡 -> 中段主体色 -> 头部高亮灯丝白），免每帧新建 SweepGradient
            val startDeg = headAngle - sweep
            arcPaint.color = bodyColor
            arcPaint.alpha = 110
            canvas.drawArc(arcBounds, startDeg, sweep * 0.55f, false, arcPaint)

            arcPaint.color = bodyColor
            arcPaint.alpha = 200
            canvas.drawArc(arcBounds, startDeg + sweep * 0.45f, sweep * 0.40f, false, arcPaint)

            arcPaint.color = filamentColor
            arcPaint.alpha = 255
            canvas.drawArc(arcBounds, startDeg + sweep * 0.78f, sweep * 0.22f, false, arcPaint)
        }

        // 6. 扩散脉冲涟漪
        if (rippleT in 0f..1f) {
            val rippleR = ringR + rippleT * dp(7f)
            val env = (1f - rippleT) * (1f - rippleT)
            ripplePaint.color = bodyColor
            ripplePaint.strokeWidth = dp(1.4f) * (1f - 0.4f * rippleT)
            ripplePaint.alpha = (env * 180f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, rippleR, ripplePaint)
        }

        // 7. 最后压内孔纯黑圆：确保任何描边/光晕绝不向内侵入镜头孔与 1.5dp 黑缝
        canvas.drawCircle(cx, cy, innerEdge, blackPaint)
    }

    private fun ensureSweepShaders(cx: Float, cy: Float) {
        if (cx == cachedCx && cy == cachedCy && orangeSweepShader != null) return
        cachedCx = cx
        cachedCy = cy
        val positions = floatArrayOf(0f, 0.35f, 0.70f, 1f)
        orangeSweepShader = SweepGradient(
            cx,
            cy,
            intArrayOf(COLOR_BODY, COLOR_AMBER, COLOR_PALE, COLOR_BODY),
            positions
        )
        greenSweepShader = SweepGradient(
            cx,
            cy,
            intArrayOf(COLOR2_BODY, COLOR2_AMBER, COLOR2_PALE, COLOR2_BODY),
            positions
        )
    }
}
