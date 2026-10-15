package com.adong.adchat.ui.clawd

/**
 * 动作时间轴采样器。逐行对照原版 clawd-rig.js：
 * - sampleKey   <- 489–502 行（关键帧插值，默认回退到静止值）
 * - sampleState <- 504–533 行（部件位移合成：upper 挂载链 + att 挂载链）
 * - flexAt      <- 713–727 行（弹性层缓入缓出插值）
 */
object ClawdSampler {

    /**
     * 单部件采样状态。f=null 表示该部件隐藏。
     * dx/dy 是合成后的浮点位移（原版透传，不在采样层取整；raw 依 clip.smooth 决定取整与否已内含）。
     */
    data class PartState(
        val dx: Double,
        val dy: Double,
        val f: String?,
        val z: Int,
        val sc: Double,
        val r: Double,
        val a: Double,
    )

    data class RigState(val parts: Map<String, PartState>, val rootDx: Double, val rootDy: Double)

    private fun ease(name: String, p: Double): Double = when (name) {
        "step" -> 0.0
        "lin" -> p
        "in" -> p * p
        "out" -> 1 - (1 - p) * (1 - p)
        "io" -> p * p * (3 - 2 * p)
        else -> throw IllegalArgumentException("未知缓动: $name")
    }

    /** 部件的属性静止值。root/upper 位移类属性按 propA 的规则回退（原版 restValue 的 part 映射）。 */
    private fun restValue(rig: RigData, part: String, prop: String): ClawdValue = when (prop) {
        "x", "y" -> ClawdValue.Num(0.0)
        "f" -> {
            val rest = rig.restF[part]
            if (rest == null) ClawdValue.None else ClawdValue.Str(rest)
        }
        "z" -> ClawdValue.Num(rig.restZ.getValue(part).toDouble())
        "att" -> ClawdValue.None
        "sc", "a" -> ClawdValue.Num(1.0)
        "r" -> ClawdValue.Num(0.0)
        else -> throw IllegalArgumentException("未知属性: $prop")
    }

    private fun restPartOf(rig: RigData, part: String): String =
        if (part == "root" || part == "upper") "propA" else part

    /**
     * 采样某部件某数值属性在 t 时刻的值（null = 该属性此刻是字符串/隐藏语义，走 sampleStrKey）。
     * raw=false 时结果取整到整格（与原版 Math.round 一致）；raw=true 时保留 3 位小数。
     */
    fun sampleKey(rig: RigData, clip: RigClip, part: String, prop: String, t: Int, raw: Boolean = false): Double? {
        val rest = restValue(rig, restPartOf(rig, part), prop)
        val ks = clip.tr[part]?.get(prop)
        if (ks.isNullOrEmpty()) {
            return when (rest) {
                is ClawdValue.Num -> rest.v
                is ClawdValue.Str -> null   // 字符串值走 sampleStrKey
                ClawdValue.None -> null
            }
        }
        val full = if (ks.first().t == 0) ks else listOf(RigKeyframe(0, rest, "step")) + ks
        var i = 0
        while (i + 1 < full.size && full[i + 1].t <= t) i++
        val k0 = full[i]
        val k1 = full.getOrNull(i + 1)
        val v0 = k0.v
        if (k1 == null || v0 !is ClawdValue.Num || k1.ease == "step" || t < k0.t) {
            return if (v0 is ClawdValue.Num) maybeRound(v0.v, raw) else null
        }
        val p = (t - k0.t).toDouble() / (k1.t - k0.t)
        val v = v0.v + ((k1.v as ClawdValue.Num).v - v0.v) * ease(k1.ease, p)
        return maybeRound(v, raw)
    }

    private fun maybeRound(v: Double, raw: Boolean): Double =
        if (raw) Math.round(v * 1000.0) / 1000.0 else Math.round(v).toDouble()

    /** 字符串属性（f 帧 / att 挂载）的采样：step 语义，取 t 之前最近的关键帧值 */
    fun sampleStrKey(rig: RigData, clip: RigClip, part: String, prop: String, t: Int): String? {
        val rest = restValue(rig, restPartOf(rig, part), prop)
        val ks = clip.tr[part]?.get(prop)
        if (ks.isNullOrEmpty()) return (rest as? ClawdValue.Str)?.v
        val full = if (ks.first().t == 0) ks else listOf(RigKeyframe(0, rest, "step")) + ks
        var i = 0
        while (i + 1 < full.size && full[i + 1].t <= t) i++
        return when (val v = full[i].v) {
            is ClawdValue.Str -> v.v
            ClawdValue.None -> null
            is ClawdValue.Num -> throw IllegalStateException("数值出现在字符串属性 $part.$prop")
        }
    }

    /**
     * 整只采样：每部件的 dx/dy 已合成 upper 与 att 挂载链的位移（浮点透传，不取整）。
     * raw 规则与原版一致：位移取整与否只看部件自己是否在 clip.smooth 里。
     * upper 不在 rig.parts 里，但它有自己的时间轴（tr 里可能出现 upper 键），
     * 与原版一样以 ['upper'] + parts 的顺序先算 own。
     */
    fun sampleState(rig: RigData, clip: RigClip, t: Int): RigState {
        data class Own(
            val x: Double,
            val y: Double,
            val f: String?,
            val z: Int,
            val att: String?,
            val sc: Double,
            val r: Double,
            val a: Double,
        )

        val own = LinkedHashMap<String, Own>()
        for (part in listOf("upper") + rig.parts) {
            val posRaw = part in clip.smooth
            val x = sampleKey(rig, clip, part, "x", t, posRaw) ?: 0.0
            val y = sampleKey(rig, clip, part, "y", t, posRaw) ?: 0.0
            val f = if (part == "upper") null else sampleStrKey(rig, clip, part, "f", t)
            val z = if (part == "upper") 0 else (sampleKey(rig, clip, part, "z", t) ?: 0.0).toInt()
            val att = if (part == "upper") null else sampleStrKey(rig, clip, part, "att", t)
            val sc = sampleKey(rig, clip, part, "sc", t, raw = true) ?: 1.0
            val r = sampleKey(rig, clip, part, "r", t, raw = true) ?: 0.0
            val a = sampleKey(rig, clip, part, "a", t, raw = true) ?: 1.0
            own[part] = Own(x, y, f, z, att, sc, r, a)
        }
        val out = LinkedHashMap<String, PartState>()
        fun resolve(part: String, depth: Int = 0): PartState {
            out[part]?.let { return it }
            check(depth <= 4) { "挂载成环: $part" }
            val o = own.getValue(part)
            var dx = o.x
            var dy = o.y
            if (part in rig.upperKids) {
                dx += own.getValue("upper").x
                dy += own.getValue("upper").y
            }
            o.att?.let { att ->
                if (att == "upper") {
                    dx += own.getValue("upper").x
                    dy += own.getValue("upper").y
                } else {
                    val a = resolve(att, depth + 1)
                    dx += a.dx
                    dy += a.dy
                }
            }
            val ps = PartState(dx, dy, o.f, o.z, o.sc, o.r, o.a)
            out[part] = ps
            return ps
        }
        for (part in rig.parts) resolve(part)
        val rootRaw = "root" in clip.smooth
        val rootDx = sampleKey(rig, clip, "root", "x", t, rootRaw) ?: 0.0
        val rootDy = sampleKey(rig, clip, "root", "y", t, rootRaw) ?: 0.0
        return RigState(out, rootDx, rootDy)
    }

    /** 弹性层采样：关键点之间按缓入缓出插值，循环动作的末值保持到 dur */
    fun flexAt(clip: RigClip, t: Int): FlexValue {
        val ks0 = clip.flexKeys
        if (ks0.isEmpty()) return FlexValue.IDENTITY
        val tail = if (clip.loop) ks0.last().second else FlexValue.IDENTITY
        val ks = buildList {
            if (ks0.first().first != 0) add(0 to FlexValue.IDENTITY)
            addAll(ks0)
            if (ks0.last().first != clip.dur) add(clip.dur to tail)
        }
        var i = 0
        while (i + 1 < ks.size && ks[i + 1].first <= t) i++
        val (t0, a) = ks[i]
        val nx = ks.getOrNull(i + 1) ?: return a
        // 原版 flexAt 固定用 io 缓动插值
        val p = ease("io", (t - t0).toDouble() / (nx.first - t0))
        val b = nx.second
        fun lerp(av: Double, bv: Double) = m3(av + (bv - av) * p)
        return FlexValue(
            sx = lerp(a.sx, b.sx),
            sy = lerp(a.sy, b.sy),
            r = lerp(a.r, b.r),
            tx = lerp(a.tx, b.tx),
            ty = lerp(a.ty, b.ty),
        )
    }

    private fun m3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
