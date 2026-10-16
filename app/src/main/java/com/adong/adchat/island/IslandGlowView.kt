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
 * 镜头星冕光环视图（Liquid Chrono-Corona Halo v2）——「渲染是状态的纯函数」。
 *
 * 视觉与反馈强化设计：
 * 1. 光学消光暗舱（Optical Pitch Chamber）：内缘 13dp 纯黑遮罩，与挖孔无缝融为一体；
 * 2. 微米级精密倒角（Micro-Chamfer Bezel）：紧贴内孔的高亮金属高光圈；
 * 3. 双折射流光主环（Birefringent Liquid Ring）：基础线宽增至 2.1dp，支持按压大幅外扩至指尖外缘；
 * 4. 宽幅柔雾光冕与冲击波画布（64dp 窗口）：为按压波纹、空点双闪与星冕聚变留足扩散空间，
 *    外缘平滑归零，绝不在窗口边界产生方形裁切痕；
 * 5. 三态色彩叙事：
 *    - 常驻态：香槟钛金 & 冷月铂金；
 *    - 识链触发态（colorShift -> 1）：极光冰青 & 翡翠星冕 + 双侧星轨聚变 + 双层同心冲击波；
 *    - 空点/重复提醒态（nudgeShift -> 1）：流光琥珀 & 珊瑚暖金 + 双脉冲外扩光环。
 */
class IslandGlowView(context: Context) : View(context) {

    companion object {
        /** 光圈基准外径（dp）。 */
        const val RING_OUTER_DP = 34f
        /**
         * 悬浮窗边长（dp）：扩大至 64dp（半径 32dp），让按压外扩光环与同心冲击波能突破指尖遮挡，
         * 同时配合浮点圆心定位公式，保证圆心与挖孔中心 100% 重合。
         */
        const val WINDOW_SIZE_DP = 64f

        /** 圆环固定内缘半径（dp）：挖孔半径约 11.5dp + 1.5dp 纯黑消光间隙。 */
        private const val INNER_EDGE_DP = 13f
        /** 基础核心环线宽（dp）：由 1.6dp 提升至 2.1dp，增强日常与按压辨识度。 */
        private const val BASE_RING_WIDTH_DP = 2.1f
        /** 常驻态基准光冕辉度。 */
        const val BASE_GLOW_INTENSITY = 0.38f

        // 常驻态调色板：香槟钛金 & 冷月铂金
        private const val COL_IDLE_CORE = 0xFFFFFBF2.toInt()     // 暖白高光灯丝
        private const val COL_IDLE_PRIMARY = 0xFFF0CB85.toInt()  // 高亮香槟钛金主体
        private const val COL_IDLE_SECONDARY = 0xFFA8B8D0.toInt()// 冷月银蓝偏光
        private const val COL_IDLE_GLOW = 0xFFE5A450.toInt()     // 暖金柔雾光晕

        // 事件触发调色板：极光冰青 & 翡翠星冕
        private const val COL_EVENT_CORE = 0xFFF0FDFF.toInt()    // 极光冰白核心
        private const val COL_EVENT_PRIMARY = 0xFF38BDF8.toInt() // 冰川天青主光
        private const val COL_EVENT_SECONDARY = 0xFF34D399.toInt()// 翡翠流光副色
        private const val COL_EVENT_GLOW = 0xFF0EA5E9.toInt()    // 深海蓝青光冕

        // 空点/重复链接反馈调色板：流光琥珀 & 珊瑚暖橙（明确提示用户已点中但无新链接）
        private const val COL_NUDGE_CORE = 0xFFFFF7ED.toInt()    // 暖白核心
        private const val COL_NUDGE_PRIMARY = 0xFFFB923C.toInt() // 琥珀珊瑚橙主光
        private const val COL_NUDGE_SECONDARY = 0xFFFBBF24.toInt()// 灿金副色
        private const val COL_NUDGE_GLOW = 0xFFF97316.toInt()    // 熔岩琥珀光晕

        /**
         * AGSL 物理级光学着色器（API 33+）：
         * - 严格限制在 u_maxR 半径内自然消隐，绝不触碰方形窗口边界；
         * - 包含各向异性金属光泽、按压冲击波、双侧聚拢星轨、顶点聚变闪光与双层扩散光波。
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
uniform float u_nudgeShift;
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

layout(color) uniform half4 u_nudgeCore;
layout(color) uniform half4 u_nudgePrimary;
layout(color) uniform half4 u_nudgeSecondary;
layout(color) uniform half4 u_nudgeGlow;

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
    // 窗口外缘安全柔化遮罩：确保光晕和涟漪在到达窗口边界前平滑归零
    float outerVignette = 1.0 - smoothstep(u_maxR - 4.0, u_maxR - 0.4, r);

    // 2. 非对称距离场：内侧陡峭防侵蚀镜头，外侧丝滑扩散
    float signedD = r - u_ringR;
    float d = abs(signedD);
    float asymD = signedD < 0.0 ? d * 1.85 : d;

    // 核心环与中心高光灯丝
    float body = 1.0 - smoothstep(0.0, u_halfW, asymD);
    float filament = 1.0 - smoothstep(0.0, u_halfW * 0.48, asymD);

    // 紧贴内缘的微米级精密金属倒角线
    float bezelD = abs(r - (u_innerEdge + 0.6));
    float bezel = (1.0 - smoothstep(0.0, max(0.9, u_halfW * 0.38), bezelD)) * 0.55;

    // 指数衰减柔雾光冕
    float glowFalloff = exp(-asymD / max(2.0, u_halfW * 3.1));
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

    coreCol = mix(coreCol, u_nudgeCore.rgb, half(u_nudgeShift));
    priCol = mix(priCol, u_nudgePrimary.rgb, half(u_nudgeShift));
    secCol = mix(secCol, u_nudgeSecondary.rgb, half(u_nudgeShift));
    haloCol = mix(haloCol, u_nudgeGlow.rgb, half(u_nudgeShift));

    half3 ringTint = mix(secCol, priCol, half(spec1));
    ringTint = mix(ringTint, coreCol, half(filament * 0.88 + spec2 * 0.26));
    half3 baseCol = mix(haloCol, ringTint, half(clamp(body + bezel, 0.0, 1.0)));

    float orbitalMod = 0.82 + 0.18 * (0.65 * spec1 + 0.35 * spec2);
    float alpha = clamp((body * 0.98 + bezel + halo * 0.66) * orbitalMod, 0.0, 1.0);
    half3 rgb = baseCol * half(alpha);

    // 4. 事件第一幕：双侧流光星轨从底部(90°)对称向上(-90°)聚拢
    if (u_convergeT > 0.001 && u_convergeT < 0.999) {
        float fromBottom = angDeg - 90.0;
        fromBottom -= floor((fromBottom + 180.0) / 360.0) * 360.0;
        float symDeg = abs(fromBottom);
        float headDeg = u_convergeT * 180.0;
        float distBehind = headDeg - symDeg;
        float tailLen = 110.0;
        if (distBehind >= -8.0 && distBehind < tailLen) {
            float trail = distBehind < 0.0
                ? (1.0 + distBehind / 8.0)
                : (1.0 - distBehind / tailLen);
            trail = clamp(trail, 0.0, 1.0);
            float radialMask = 1.0 - smoothstep(0.0, u_halfW * 2.2, asymD);
            float boost = trail * (0.4 + 0.6 * trail) * radialMask;
            half3 streamCol = mix(priCol, half3(1.0), half(trail * 0.78));
            rgb += streamCol * half(boost * 1.45);
            alpha = clamp(alpha + boost * 0.95, 0.0, 1.0);
        }
    }

    // 5. 高光聚变闪光（按压瞬间爆发 / 顶点碰撞聚变闪光）
    if (u_flash > 0.001) {
        float topDist = abs(angDeg + 90.0);
        topDist = min(topDist, 360.0 - topDist);
        float zenithSpot = exp(-topDist / 46.0) * exp(-asymD / (u_halfW * 3.8));
        float ringFlash = exp(-asymD / (u_halfW * 2.9)) * 0.88;
        float totalFlash = (zenithSpot * 0.85 + ringFlash) * u_flash * innerMask * outerVignette;
        half3 flashCol = mix(coreCol, half3(1.0), half(0.72));
        rgb += flashCol * half(totalFlash * 1.25);
        alpha = clamp(alpha + totalFlash * 0.92, 0.0, 1.0);
    }

    // 6. 双重同心冲击波光环（按压扩散波 & 识链事件冲击波）
    if (u_rippleT > 0.001 && u_rippleT < 0.999) {
        float span = max(3.0, (u_maxR - 1.5) - u_ringR);
        // 主冲击波纹
        float r1 = u_ringR + u_rippleT * span;
        float w1 = max(1.3, u_halfW * (1.35 - 0.65 * u_rippleT));
        float env1 = (1.0 - u_rippleT) * (1.0 - 0.65 * u_rippleT);
        float wave1 = (1.0 - smoothstep(0.0, w1, abs(r - r1))) * env1;

        // 次级回响波纹（延迟触发）
        float t2 = clamp((u_rippleT - 0.16) / 0.84, 0.0, 1.0);
        float r2 = u_ringR + t2 * span * 0.80;
        float w2 = max(1.0, u_halfW * (1.0 - 0.5 * t2));
        float env2 = (1.0 - t2) * (1.0 - t2) * step(0.16, u_rippleT);
        float wave2 = (1.0 - smoothstep(0.0, w2, abs(r - r2))) * env2 * 0.72;

        float waveTotal = (wave1 + wave2) * innerMask * outerVignette;
        half3 waveCol = mix(priCol, coreCol, half(0.52));
        rgb += waveCol * half(waveTotal * 1.15);
        alpha = clamp(alpha + waveTotal * 0.88, 0.0, 1.0);
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

    /** 弹簧缩放因子：1.0 为基准；按下时弹性外扩至 ~1.68，突破指尖遮挡。 */
    var springScale: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 光冕辉度（0..1.2）：常驻态 0.38，按压或触发事件时升至 1.0+。 */
    var glowIntensity: Float = BASE_GLOW_INTENSITY
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

    /** 空点/重复链接调色板混合度：0 = 常驻色，1 = 琥珀珊瑚橙（提示已点中但无新链接）。 */
    var nudgeShift: Float = 0f
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
        glowIntensity = BASE_GLOW_INTENSITY
        orbitAngle = -45f
        colorShift = 0f
        nudgeShift = 0f
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
    private var nudgeSweep: SweepGradient? = null

    private val fallbackGlowWidths = floatArrayOf(3.4f, 6.2f, 9.6f)
    private val fallbackGlowAlphas = floatArrayOf(0.38f, 0.20f, 0.09f)
    private var agslShader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                RuntimeShader(AGSL_SRC).apply {
                    // Palette is immutable: upload once, not twelve times per frame.
                    setColorUniform("u_idleCore", COL_IDLE_CORE)
                    setColorUniform("u_idlePrimary", COL_IDLE_PRIMARY)
                    setColorUniform("u_idleSecondary", COL_IDLE_SECONDARY)
                    setColorUniform("u_idleGlow", COL_IDLE_GLOW)
                    setColorUniform("u_eventCore", COL_EVENT_CORE)
                    setColorUniform("u_eventPrimary", COL_EVENT_PRIMARY)
                    setColorUniform("u_eventSecondary", COL_EVENT_SECONDARY)
                    setColorUniform("u_eventGlow", COL_EVENT_GLOW)
                    setColorUniform("u_nudgeCore", COL_NUDGE_CORE)
                    setColorUniform("u_nudgePrimary", COL_NUDGE_PRIMARY)
                    setColorUniform("u_nudgeSecondary", COL_NUDGE_SECONDARY)
                    setColorUniform("u_nudgeGlow", COL_NUDGE_GLOW)
                }
            }.getOrNull()
        } else null

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy)
        val innerEdge = dp(INNER_EDGE_DP)

        // 内缘固定不缩进，弹簧形变向外大幅生长（放大径向膨胀系数至 2.4dp，突破指肚遮挡）
        val coreWidth = dp(BASE_RING_WIDTH_DP) * springScale
        val extraExpand = max(0f, springScale - 1f) * dp(2.4f)
        val ringR = innerEdge + coreWidth / 2f + extraExpand

        val shader = agslShader
        if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ok = runCatching {
                drawWithAgsl(canvas, shader, cx, cy, innerEdge, ringR, coreWidth / 2f, maxR)
            }.isSuccess
            if (ok) return
            // Disable a broken driver path once instead of allocating an exception every vsync.
            agslShader = null
            shaderPaint.shader = null
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
        shader.setFloatUniform("u_nudgeShift", nudgeShift.coerceIn(0f, 1f))
        shader.setFloatUniform("u_convergeT", convergeT.coerceIn(0f, 1f))
        shader.setFloatUniform("u_flash", flashIntensity.coerceIn(0f, 1f))
        shader.setFloatUniform("u_rippleT", rippleProgress.coerceIn(0f, 1f))

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
        val isEvent = colorShift > 0.45f
        val isNudge = !isEvent && nudgeShift > 0.35f
        val glowCol = when {
            isEvent -> COL_EVENT_GLOW
            isNudge -> COL_NUDGE_GLOW
            else -> COL_IDLE_GLOW
        }
        val coreCol = when {
            isEvent -> COL_EVENT_CORE
            isNudge -> COL_NUDGE_CORE
            else -> COL_IDLE_CORE
        }
        val priCol = when {
            isEvent -> COL_EVENT_PRIMARY
            isNudge -> COL_NUDGE_PRIMARY
            else -> COL_IDLE_PRIMARY
        }

        // 1. 三层柔雾光晕
        val totalGlow = (glowIntensity + flashIntensity * 0.75f).coerceIn(0f, 1.5f)
        if (totalGlow > 0.01f) {
            glowPaint.color = glowCol
            for (i in fallbackGlowWidths.indices) {
                glowPaint.strokeWidth = dp(fallbackGlowWidths[i])
                glowPaint.alpha = (fallbackGlowAlphas[i] * totalGlow * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, ringR, glowPaint)
            }
        }

        // 2. 各向异性流光主环
        val sweep = when {
            isEvent -> eventSweep
            isNudge -> nudgeSweep
            else -> idleSweep
        }
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
        filamentPaint.strokeWidth = dp(0.85f) * (1f + flashIntensity * 0.65f)
        filamentPaint.alpha = (225 + (flashIntensity * 30f).toInt()).coerceIn(0, 255)
        canvas.drawCircle(cx, cy, ringR, filamentPaint)

        // 4. 双侧对称聚拢星轨
        if (convergeT in 0.01f..0.99f) {
            arcRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            val spanDeg = convergeT * 180f
            val tailDeg = min(spanDeg, 100f)
            arcPaint.strokeWidth = coreWidth * 1.5f
            arcPaint.color = COL_EVENT_CORE
            arcPaint.alpha = 250
            canvas.drawArc(arcRect, 90f - spanDeg, tailDeg, false, arcPaint)
            canvas.drawArc(arcRect, 90f + spanDeg - tailDeg, tailDeg, false, arcPaint)
        }

        // 5. 双层扩散光波涟漪
        if (rippleProgress in 0.01f..0.99f) {
            val span = max(dp(3f), (maxR - dp(1.8f)) - ringR)
            val r1 = ringR + rippleProgress * span
            val env1 = (1f - rippleProgress) * (1f - 0.65f * rippleProgress)
            ripplePaint.color = priCol
            ripplePaint.strokeWidth = dp(1.8f) * (1f - 0.45f * rippleProgress)
            ripplePaint.alpha = (env1 * 230f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r1, ripplePaint)

            if (rippleProgress > 0.16f) {
                val t2 = ((rippleProgress - 0.16f) / 0.84f).coerceIn(0f, 1f)
                val r2 = ringR + t2 * span * 0.80f
                val env2 = (1f - t2) * (1f - t2)
                ripplePaint.color = coreCol
                ripplePaint.strokeWidth = dp(1.2f) * (1f - 0.5f * t2)
                ripplePaint.alpha = (env2 * 165f).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, r2, ripplePaint)
            }
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
        nudgeSweep = SweepGradient(
            cx,
            cy,
            intArrayOf(
                COL_NUDGE_PRIMARY,
                COL_NUDGE_CORE,
                COL_NUDGE_SECONDARY,
                COL_NUDGE_CORE,
                COL_NUDGE_PRIMARY
            ),
            pos
        )
    }
}
