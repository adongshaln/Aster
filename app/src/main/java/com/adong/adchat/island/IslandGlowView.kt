package com.adong.adchat.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.SweepGradient
import android.os.Build
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 镜头星冕光环视图（Liquid Chrono-Corona Halo）——「渲染是状态的纯函数」。
 *
 * 视觉层级设计：
 * 1. 光学消光暗舱（Optical Pitch Chamber）：内缘 11.5dp 纯黑遮罩，与挖孔无缝融为一体；
 * 2. 微米级精密倒角（Micro-Chamfer Bezel）：紧贴内孔的 0.5dp 各向异性金属高光圈；
 * 3. 双折射流光主环（Birefringent Liquid Ring）：香槟钛金 + 冷月银 + 冰川青的温润色温散射；
 * 4. 柔雾光冕与边缘消隐（Atmospheric Corona & Vignette）：非对称指数光晕 + 窗口边缘平滑归零，
 *    绝不在 44dp 悬浮窗边界产生方形裁切痕；
 * 5. 事件触发特效（Corona Ignition）：双侧流光星轨对称攀升 -> 顶点碰撞聚变闪光 -> 双层同心光波涟漪。
 */
class IslandGlowView(context: Context) : View(context) {

    companion object {
        /** 光圈基准外径（dp）：严格保持原版 28dp。 */
        const val RING_OUTER_DP = 32f
        /** 悬浮窗边长（dp）：严格保持原版 44dp，确保圆心定位公式 100% 兼容。 */
        const val WINDOW_SIZE_DP = 48f

        /** 圆环固定内缘半径（dp）：挖孔半径约 10dp + 1.5dp 纯黑消光间隙。 */
        private const val INNER_EDGE_DP = 13f
        /** 基础核心环线宽（dp）。 */
        private const val BASE_RING_WIDTH_DP = 1.6f

        // 常驻态调色板：香槟钛金 & 冷月铂金（高定腕表/镜头圈质感）
        private const val COL_IDLE_CORE = 0xFFFFF9EE.toInt()     // 暖白铂金灯丝
        private const val COL_IDLE_PRIMARY = 0xFFE5C384.toInt()  // 香槟钛金主体
        private const val COL_IDLE_SECONDARY = 0xFF94A3B8.toInt()// 冷月银灰偏光
        private const val COL_IDLE_GLOW = 0xFFD99B4E.toInt()     // 暖金柔雾光晕

        // 事件触发调色板：极光冰青 & 翡翠星冕
        private const val COL_EVENT_CORE = 0xFFECFEFF.toInt()    // 极光冰白核心
        private const val COL_EVENT_PRIMARY = 0xFF38BDF8.toInt() // 冰川天青主光
        private const val COL_EVENT_SECONDARY = 0xFF34D399.toInt()// 翡翠流光副色
        private const val COL_EVENT_GLOW = 0xFF0EA5E9.toInt()    // 深海蓝青光冕

        /**
         * AGSL 物理级光学着色器（API 33+）：
         * - 严格限制在 u_maxR 半径内自然消隐，绝不触碰方形窗口边界；
         * - 包含各向异性金属光泽、双侧聚拢星轨、顶点聚变闪光与双层扩散光波。
         */
        private const val AGSL_SRC = """
uniform float2 u_center;
uniform float u_innerEdge;
uniform float u_ringR;
uniform float u_halfW;
uniform float u_maxR;
uniform float u_glow;
uniform float u_orbitDeg;
uniform float u_colorShift;
uniform float u_convergeT;
uniform float u_flash;
uniform float u_rippleT;

layout(color) uniform half4 u_idleCore;
layout(color) uniform half4 u_idlePrimary;
layout(color) uniform half4 u_idleSecondary;
layout(color) uniform half4 u_idleGlow;

layout(color) uniform half4 u_eventCore;
layout(color) uniform half4 u_eventPrimary;
layout(color) uniform half4 u_eventSecondary;
layout(color) uniform half4 u_eventGlow;

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

half4 main(float2 p) {
    float2 delta = p - u_center;
    float r = length(delta);

    // 1. 镜头孔内部纯黑消光舱（含抗锯齿内缘）
    if (r < u_innerEdge - 0.9) {
        return half4(0.0, 0.0, 0.0, 1.0);
    }
    if (r >= u_maxR) {
        return half4(0.0, 0.0, 0.0, 0.0);
    }
    float innerMask = smoothstep(u_innerEdge - 0.7, u_innerEdge + 0.35, r);
    // 窗口外缘安全柔化遮罩：确保光晕和涟漪在到达 44dp 窗口边界前平滑归零
    float outerVignette = 1.0 - smoothstep(u_maxR - 3.2, u_maxR - 0.3, r);

    // 2. 非对称距离场：内侧陡峭防侵蚀镜头，外侧丝滑扩散
    float signedD = r - u_ringR;
    float d = abs(signedD);
    float asymD = signedD < 0.0 ? d * 1.9 : d;

    // 核心环与中心高光灯丝
    float body = 1.0 - smoothstep(0.0, u_halfW, asymD);
    float filament = 1.0 - smoothstep(0.0, u_halfW * 0.42, asymD);

    // 紧贴内缘的微米级精密金属倒角线
    float bezelD = abs(r - (u_innerEdge + 0.55));
    float bezel = (1.0 - smoothstep(0.0, max(0.8, u_halfW * 0.35), bezelD)) * 0.45;

    // 指数衰减柔雾光冕
    float glowFalloff = exp(-asymD / max(1.5, u_halfW * 2.6));
    float halo = glowFalloff * u_glow * innerMask * outerVignette;

    // 3. 各向异性角度偏光（模拟金属拉丝与光学镀膜双峰反射）
    float angRad = atan(delta.y, delta.x);
    float angDeg = degrees(angRad);
    float relRad = radians(angDeg - u_orbitDeg);
    float spec1 = 0.5 + 0.5 * cos(relRad);
    float spec2 = 0.5 + 0.5 * sin(relRad * 2.0 + 0.6);

    half3 coreCol = mix(u_idleCore.rgb, u_eventCore.rgb, half(u_colorShift));
    half3 priCol = mix(u_idlePrimary.rgb, u_eventPrimary.rgb, half(u_colorShift));
    half3 secCol = mix(u_idleSecondary.rgb, u_eventSecondary.rgb, half(u_colorShift));
    half3 haloCol = mix(u_idleGlow.rgb, u_eventGlow.rgb, half(u_colorShift));

    half3 ringTint = mix(secCol, priCol, half(spec1));
    ringTint = mix(ringTint, coreCol, half(filament * 0.82 + spec2 * 0.22));
    half3 baseCol = mix(haloCol, ringTint, half(clamp(body + bezel, 0.0, 1.0)));

    float orbitalMod = 0.78 + 0.22 * (0.65 * spec1 + 0.35 * spec2);
    float alpha = clamp((body * 0.96 + bezel + halo * 0.52) * orbitalMod, 0.0, 1.0);
    half3 rgb = baseCol * half(alpha);

    // 4. 事件第一幕：双侧流光星轨从底部(90°)对称向上(-90°)聚拢
    if (u_convergeT > 0.001 && u_convergeT < 0.999) {
        // 将角度变换为相对于底部(90°)的对称夹角 sym in [0, 180°]
        float fromBottom = angDeg - 90.0;
        fromBottom -= floor((fromBottom + 180.0) / 360.0) * 360.0;
        float symDeg = abs(fromBottom);
        float headDeg = u_convergeT * 180.0;
        float distBehind = headDeg - symDeg;
        float tailLen = 95.0;
        if (distBehind >= -6.0 && distBehind < tailLen) {
            float trail = distBehind < 0.0
                ? (1.0 + distBehind / 6.0)
                : (1.0 - distBehind / tailLen);
            trail = clamp(trail, 0.0, 1.0);
            float radialMask = 1.0 - smoothstep(0.0, u_halfW * 1.75, asymD);
            float boost = trail * trail * radialMask;
            half3 streamCol = mix(u_eventPrimary.rgb, half3(1.0), half(trail * 0.7));
            rgb += streamCol * half(boost * 1.15);
            alpha = clamp(alpha + boost * 0.9, 0.0, 1.0);
        }
    }

    // 5. 事件第二幕：顶点碰撞聚变闪光（Zenith Starburst & Full-Ring Ignition）
    if (u_flash > 0.001) {
        float topDist = abs(angDeg + 90.0);
        topDist = min(topDist, 360.0 - topDist);
        float zenithSpot = exp(-topDist / 38.0) * exp(-asymD / (u_halfW * 3.2));
        float ringFlash = exp(-asymD / (u_halfW * 2.4)) * 0.75;
        float totalFlash = (zenithSpot * 0.85 + ringFlash) * u_flash * innerMask * outerVignette;
        half3 flashCol = mix(u_eventCore.rgb, half3(1.0), half(0.65));
        rgb += flashCol * half(totalFlash);
        alpha = clamp(alpha + totalFlash * 0.85, 0.0, 1.0);
    }

    // 6. 事件第三幕：双重同心冲击波光环（一快一慢向外扩散消散）
    if (u_rippleT > 0.001 && u_rippleT < 0.999) {
        float span = max(2.0, (u_maxR - 1.2) - u_ringR);
        // 主波纹
        float r1 = u_ringR + u_rippleT * span;
        float w1 = max(1.0, u_halfW * (1.15 - 0.65 * u_rippleT));
        float env1 = (1.0 - u_rippleT) * (1.0 - u_rippleT);
        float wave1 = (1.0 - smoothstep(0.0, w1, abs(r - r1))) * env1;

        // 次级回响波纹（延迟触发）
        float t2 = clamp((u_rippleT - 0.18) / 0.82, 0.0, 1.0);
        float r2 = u_ringR + t2 * span * 0.78;
        float w2 = max(0.8, u_halfW * (0.85 - 0.5 * t2));
        float env2 = (1.0 - t2) * (1.0 - t2) * step(0.18, u_rippleT);
        float wave2 = (1.0 - smoothstep(0.0, w2, abs(r - r2))) * env2 * 0.55;

        float waveTotal = (wave1 + wave2) * innerMask * outerVignette;
        half3 waveCol = mix(u_eventSecondary.rgb, u_eventCore.rgb, half(0.45));
        rgb += waveCol * half(waveTotal * 0.9);
        alpha = clamp(alpha + waveTotal * 0.75, 0.0, 1.0);
    }

    // 7. 内孔边缘纯黑过渡 + 蓝噪抖动消除暗部色带
    float holeBlack = (1.0 - innerMask) * outerVignette;
    float dither = (hash12(p) - 0.5) / 255.0;
    float finalA = clamp(alpha + holeBlack + dither, 0.0, 1.0);
    half3 finalRgb = clamp(rgb + half(dither), half3(0.0), half3(finalA));
    return half4(finalRgb, half(finalA));
}
"""
    }

    // ---------- 响应式视觉状态参数（由外部物理动画器驱动） ----------

    /** 弹簧缩放因子：1.0 为基准；按下时弹性外扩至 ~1.45，释放后带阻尼回弹。 */
    var springScale: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 光冕辉度（0..1）：常驻态 ~0.26，按压或触发事件时升至 0.95。 */
    var glowIntensity: Float = 0.26f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 各向异性流光旋转角（度）。 */
    var orbitAngle: Float = -45f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 调色板混合度：0 = 香槟钛金（常驻），1 = 极光冰青（事件触发）。 */
    var colorShift: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 双侧流光星轨聚拢进度（0..1，0 表示不显示）。 */
    var convergeT: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 聚变高光爆发强度（0..1）。 */
    var flashIntensity: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 双重同心涟漪扩散进度（0..1，0 表示不显示）。 */
    var rippleProgress: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 恢复至常驻静谧状态。 */
    fun reset() {
        springScale = 1f
        glowIntensity = 0.26f
        orbitAngle = -45f
        colorShift = 0f
        convergeT = 0f
        flashIntensity = 0f
        rippleProgress = 0f
        alpha = 1f
    }

    // ---------- 渲染引擎 ----------

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF000000.toInt()
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        @Suppress("DEPRECATION")
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private val arcRect = RectF()
    private val shaderMatrix = Matrix()
    private var cachedCx = -1f
    private var cachedCy = -1f
    private var idleSweep: SweepGradient? = null
    private var eventSweep: SweepGradient? = null

    private val agslShader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(AGSL_SRC) }.getOrNull()
        } else null

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy)
        val innerEdge = dp(INNER_EDGE_DP)

        // 内缘固定不缩进，弹簧形变仅向外生长
        val coreWidth = dp(BASE_RING_WIDTH_DP) * springScale
        val extraExpand = max(0f, springScale - 1f) * dp(0.85f)
        val ringR = innerEdge + coreWidth / 2f + extraExpand

        val shader = agslShader
        if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ok = runCatching {
                drawWithAgsl(canvas, shader, cx, cy, innerEdge, ringR, coreWidth / 2f, maxR)
            }.isSuccess
            if (ok) return
        }
        drawWithCanvasFallback(canvas, cx, cy, innerEdge, ringR, coreWidth, maxR)
    }

    private fun drawWithAgsl(
        canvas: Canvas,
        shader: RuntimeShader,
        cx: Float,
        cy: Float,
        innerEdge: Float,
        ringR: Float,
        halfW: Float,
        maxR: Float
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        shader.setFloatUniform("u_center", cx, cy)
        shader.setFloatUniform("u_innerEdge", innerEdge)
        shader.setFloatUniform("u_ringR", ringR)
        shader.setFloatUniform("u_halfW", halfW)
        shader.setFloatUniform("u_maxR", maxR)
        shader.setFloatUniform("u_glow", glowIntensity)
        shader.setFloatUniform("u_orbitDeg", orbitAngle)
        shader.setFloatUniform("u_colorShift", colorShift.coerceIn(0f, 1f))
        shader.setFloatUniform("u_convergeT", convergeT.coerceIn(0f, 1f))
        shader.setFloatUniform("u_flash", flashIntensity.coerceIn(0f, 1f))
        shader.setFloatUniform("u_rippleT", rippleProgress.coerceIn(0f, 1f))

        shader.setColorUniform("u_idleCore", COL_IDLE_CORE)
        shader.setColorUniform("u_idlePrimary", COL_IDLE_PRIMARY)
        shader.setColorUniform("u_idleSecondary", COL_IDLE_SECONDARY)
        shader.setColorUniform("u_idleGlow", COL_IDLE_GLOW)

        shader.setColorUniform("u_eventCore", COL_EVENT_CORE)
        shader.setColorUniform("u_eventPrimary", COL_EVENT_PRIMARY)
        shader.setColorUniform("u_eventSecondary", COL_EVENT_SECONDARY)
        shader.setColorUniform("u_eventGlow", COL_EVENT_GLOW)

        shaderPaint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
        shaderPaint.shader = null
    }

    private fun drawWithCanvasFallback(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        innerEdge: Float,
        ringR: Float,
        coreWidth: Float,
        maxR: Float
    ) {
        ensureFallbackShaders(cx, cy)
        val isEvent = colorShift > 0.5f
        val glowCol = if (isEvent) COL_EVENT_GLOW else COL_IDLE_GLOW
        val coreCol = if (isEvent) COL_EVENT_CORE else COL_IDLE_CORE
        val priCol = if (isEvent) COL_EVENT_PRIMARY else COL_IDLE_PRIMARY

        // 1. 三层柔雾光晕
        val totalGlow = (glowIntensity + flashIntensity * 0.6f).coerceIn(0f, 1.3f)
        if (totalGlow > 0.01f) {
            val widths = floatArrayOf(2.8f, 5.0f, 7.6f)
            val alphas = floatArrayOf(0.32f, 0.16f, 0.07f)
            glowPaint.color = glowCol
            for (i in widths.indices) {
                glowPaint.strokeWidth = dp(widths[i])
                glowPaint.alpha = (alphas[i] * totalGlow * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, ringR, glowPaint)
            }
        }

        // 2. 各向异性流光主环
        val sweep = if (isEvent) eventSweep else idleSweep
        if (sweep != null) {
            shaderMatrix.reset()
            shaderMatrix.setRotate(orbitAngle, cx, cy)
            sweep.setLocalMatrix(shaderMatrix)
        }
        ringPaint.shader = sweep
        ringPaint.strokeWidth = coreWidth
        canvas.drawCircle(cx, cy, ringR, ringPaint)
        ringPaint.shader = null

        // 3. 高色温灯丝内芯
        filamentPaint.color = coreCol
        filamentPaint.strokeWidth = dp(0.6f) * (1f + flashIntensity * 0.5f)
        filamentPaint.alpha = (210 + (flashIntensity * 45f).toInt()).coerceIn(0, 255)
        canvas.drawCircle(cx, cy, ringR, filamentPaint)

        // 4. 双侧对称聚拢星轨
        if (convergeT in 0.01f..0.99f) {
            arcRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            val spanDeg = convergeT * 180f
            val tailDeg = min(spanDeg, 85f)
            arcPaint.strokeWidth = coreWidth * 1.35f
            arcPaint.color = COL_EVENT_CORE
            arcPaint.alpha = 240
            // 右侧上行弧 (从 90° 逆时针到 90° - spanDeg)
            canvas.drawArc(arcRect, 90f - spanDeg, tailDeg, false, arcPaint)
            // 左侧上行弧 (从 90° 顺时针到 90° + spanDeg)
            canvas.drawArc(arcRect, 90f + spanDeg - tailDeg, tailDeg, false, arcPaint)
        }

        // 5. 双层扩散光波涟漪
        if (rippleProgress in 0.01f..0.99f) {
            val span = max(dp(2f), (maxR - dp(1.5f)) - ringR)
            val r1 = ringR + rippleProgress * span
            val env1 = (1f - rippleProgress) * (1f - rippleProgress)
            ripplePaint.color = priCol
            ripplePaint.strokeWidth = dp(1.3f) * (1f - 0.5f * rippleProgress)
            ripplePaint.alpha = (env1 * 190f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r1, ripplePaint)
        }

        // 6. 压紧内孔纯黑消光圆
        canvas.drawCircle(cx, cy, innerEdge, blackPaint)
    }

    private fun ensureFallbackShaders(cx: Float, cy: Float) {
        if (cx == cachedCx && cy == cachedCy && idleSweep != null) return
        cachedCx = cx
        cachedCy = cy
        val pos = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        idleSweep = SweepGradient(
            cx,
            cy,
            intArrayOf(
                COL_IDLE_PRIMARY,
                COL_IDLE_CORE,
                COL_IDLE_SECONDARY,
                COL_IDLE_CORE,
                COL_IDLE_PRIMARY
            ),
            pos
        )
        eventSweep = SweepGradient(
            cx,
            cy,
            intArrayOf(
                COL_EVENT_PRIMARY,
                COL_EVENT_CORE,
                COL_EVENT_SECONDARY,
                COL_EVENT_CORE,
                COL_EVENT_PRIMARY
            ),
            pos
        )
    }
}
