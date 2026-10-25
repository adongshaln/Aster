package com.adong.adchat.ui.clawd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.random.Random

/**
 * 三只小 Clawd 的运行时状态机。行为逐段对照原版 clawd-pile.js：
 * 作息（sleep/wake/blink/doze/stroll/wander/pause，177–216 行）、走路碰撞（walkTo/stepOnce/bump，218–283 行）、
 * 拖拽叠放（pickUp/drop/lowerFrom/settle/hopDown，331–410 行）、摆位（stackX/relayout，521–566 行）。
 * 纯逻辑：定时用注入的 [scope]，时间到调自己的方法；渲染层订阅 [onChanged] 重画。
 *
 * 坐标约定：x 是"离输入框左边界的 CSS px"（Android 上 1 CSS px = 1 dp，渲染层负责 density），
 * 1 美术格 = [PileData.px] px。
 */
class ClawdPileState(
    val data: PileData,
    private val store: PositionStore,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
) {
    /** 位置持久化：状态机不知道 Android，存取由外部注入 */
    interface PositionStore {
        fun load(): Map<String, SavedPos>?
        fun save(positions: Map<String, SavedPos>)
    }

    data class SavedPos(val dx: Int, val on: List<String>)

    enum class Mode { SLEEP, SIT, WALK, HELD }

    /** 每只的可观察状态 */
    data class Actor(
        val name: String,
        val x: Int,
        val on: List<String>,
        val eyes: PileEyes,
        val pose: PilePose,
        val mode: Mode,
        val face: Int,
        /** mode=WALK 时的视觉位移（px）：视觉 x = walkBase + walkOff */
        val walkOff: Int = 0,
        val walkBase: Int = 0,
        /** 戳跳（原版 poke 的 HOP，460ms）：开始时刻 elapsedRealtime，0 = 没在跳 */
        val hopAt: Long = 0,
        /** 落地 squash（320ms）：开始时刻，0 = 没在压 */
        val squashAt: Long = 0,
    )

    private class Inner(val name: String) {
        var x = 0
        var on: MutableList<String> = mutableListOf()
        var eyes = PileEyes.SHUT
        var pose = PilePose.CROUCH
        var mode = Mode.SLEEP
        var face = 0
        var walk: Walk? = null
        var jobs = mutableListOf<Job>()
        var hopAt = 0L
        var squashAt = 0L
    }

    private class Walk(val base: Int, val target: Int, val dir: Int, val onArrive: (() -> Unit)?) {
        var off = 0
    }

    private val actors = linkedMapOf<String, Inner>()

    var centerX = 0
        private set
    var width = 0
        private set
    /** 原版 data-unlaid：量到宽度、摆好之前先藏着（build() 571 行） */
    val isLaid: Boolean get() = laid
    private var laid = false
    private var atHome = false
    private var lastW = 0

    var onChanged: (() -> Unit)? = null

    private val activityGate = ClawdActivityGate()

    fun setForeground(active: Boolean) = activityGate.setActive(active)

    private val px: Int get() = data.px
    private val bodyW: Int get() = data.bodyW
    private val liftH: Int get() = data.lift
    private val gap: Int get() = data.gap
    private val edge: Int get() = data.edge

    private fun changed() {
        onChanged?.invoke()
    }

    private fun rnd(a: Int, b: Int): Int = a + random.nextInt(b - a + 1)

    /** 原版 later/clear（78–79 行）：每只独立管理自己的定时器，动的时候一键清 */
    private fun later(k: Inner, ms: Long, block: () -> Unit): Job {
        val job = scope.launch {
            activityGate.delayWhileActive(ms)
            block()
        }
        k.jobs.add(job)
        return job
    }

    private fun clear(k: Inner) {
        for (j in k.jobs) j.cancel()
        k.jobs.clear()
        k.walk = null
    }

    /* ===== 位置几何（原版 81–133 行） ===== */

    private fun bounds(): IntRange {
        val max = max(edge, width - edge - bodyW)
        return edge..max
    }

    private fun clampX(x: Int): Int {
        val b = bounds()
        val v = x.coerceIn(b.first, b.last)
        return Math.round(v.toDouble() / px).toInt() * px
    }

    private fun homeLayout(): Map<String, SavedPos> {
        val r = width - edge - 60
        return mapOf(
            "cowboy" to SavedPos(r, emptyList()),
            "shades" to SavedPos(r + 28, emptyList()),
            "wizard" to SavedPos(r + 14, listOf("cowboy", "shades")),
        )
    }

    private fun save(keepHome: Boolean = false) {
        if (!keepHome) atHome = false
        if (width == 0) return
        store.save(actors.mapValues { (_, k) -> SavedPos(k.x - width / 2, k.on.toList()) })
    }

    private fun lift(k: Inner): Int =
        if (k.on.isEmpty()) 0 else k.on.maxOf { supportTop(it) }

    private fun supportTop(n: String): Int =
        if (n == "big") (bigSatName?.let { supportTop(it) } ?: 0) + BIG_REST else lift(actors.getValue(n)) + liftH

    private fun carriers(k: Inner): List<String> =
        actors.values.filter { it.on.contains(k.name) }.map { it.name } +
            if (bigSatName == k.name) listOf("big") else emptyList()

    private fun grounded(except: String? = null): List<Inner> =
        actors.values.filter { it.name != except && it.on.isEmpty() && it.mode != Mode.HELD }

    private fun posX(o: Inner): Int = o.walk?.let { it.base + it.off } ?: o.x

    /**
     * 大爪此刻在带坐标系里的左缘（原版 bigX）：只有「站在输入框上、没坐在小爪头上」才算障碍；
     * 被拎着/飞着/坐着都不算。null = 不在。
     */
    private fun bigX(): Double? = bigLeftDp?.takeIf { bigGrounded && bigSatName == null }

    private fun clashBig(x: Int, bx: Double?): Boolean =
        bx != null && x < bx + BIG_W + gap && bx < x + bodyW + gap

    private fun freeAt(x: Int, except: String, bx: Double? = bigX()): Boolean {
        val b = bounds()
        if (x < b.first || x > b.last || clashBig(x, bx)) return false
        return grounded(except).all { abs(posX(it) - x) >= bodyW + gap }
    }

    private fun nearestFree(x: Int, except: String): Int? {
        val b = bounds()
        val bx = bigX()
        val cx = clampX(x)
        if (freeAt(cx, except, bx)) return cx
        var d = px
        while (d < width) {
            for (s in intArrayOf(-1, 1)) {
                val t = cx + s * d
                if (t in b && freeAt(t, except, bx)) return t
            }
            d += px
        }
        return null
    }

    private fun clearPath(a: Int, b: Int, except: String, bx: Double?): Boolean {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        val apart = { o: Int -> o + bodyW + gap <= lo || o >= hi + bodyW + gap }
        val apartBig = { o: Double -> o + BIG_W + gap <= lo || o >= hi + bodyW + gap }
        return (bx == null || apartBig(bx)) && grounded(except).all { apart(posX(it)) }
    }

    /* ===== 叠放几何（原版 523–535 行 stackX/unstack） ===== */

    private fun depthOf(k: Inner): Int =
        if (k.on.isEmpty()) 0
        else 1 + k.on.maxOf { if (it == "big") 0 else depthOf(actors.getValue(it)) }

    private fun stackX() {
        val stacked = actors.values.filter { it.on.isNotEmpty() }.sortedBy { depthOf(it) }
        val bl = bigLeftDp
        for (k in stacked) {
            // 支撑是 'big' 时用大爪中心（原版 cxOf：bl + BIG_W/2）
            val cx = k.on.sumOf { m ->
                if (m == "big") (bl ?: 0.0) + BIG_W / 2.0 else actors.getValue(m).x + bodyW / 2.0
            } / k.on.size
            k.x = Math.round((cx - bodyW / 2) / px).toInt() * px
        }
    }

    private fun unstack(moved: List<Inner>) {
        for (k in moved) nearestFree(k.x, k.name)?.let { k.x = it }
    }

    /* ===== 生命周期（原版 relayout/build 536–574 行） ===== */

    fun ensureLaid(w: Int) {
        if (laid || w <= 0) return
        width = w
        centerX = w / 2
        for (n in data.names) actors[n] = Inner(n)
        val saved = store.load()
        atHome = saved == null
        val layout = saved ?: homeLayout()
        for (n in data.names) {
            val k = actors.getValue(n)
            k.x = clampX(centerX + (layout[n]?.dx ?: 0))
            k.on = layout[n]?.on
                ?.filter { it != n && it in data.names }
                ?.toMutableList()
                ?: mutableListOf()
        }
        stackX()
        laid = true
        lastW = w
        for (n in data.names) sleep(actors.getValue(n), first = true)
        changed()
    }

    fun relayout(w: Int) {
        if (!laid || w <= 0 || w == width) return
        val old = width
        width = w
        centerX = w / 2
        if (atHome) {
            val h = homeLayout()
            for (n in data.names) {
                val k = actors.getValue(n)
                k.x = clampX(h[n]?.dx ?: 0)
                k.on = h[n]?.on?.toMutableList() ?: mutableListOf()
            }
            stackX()
            changed()
            return
        }
        val d = Math.round((w - old).toDouble() / 2 / px).toInt() * px
        val moved = mutableListOf<Inner>()
        for (k in actors.values) {
            if (k.mode == Mode.HELD || k.on.isNotEmpty()) continue
            endWalk(k)
            val want = k.x + d
            k.x = clampX(want)
            if (k.x != want) moved.add(k)
        }
        unstack(moved)
        stackX()
        save(keepHome = true)
        changed()
    }

    /* ===== 作息（原版 177–216 行） ===== */

    private fun sleep(k: Inner, first: Boolean = false) {
        k.mode = Mode.SLEEP
        k.eyes = PileEyes.SHUT
        k.pose = PilePose.CROUCH
        k.face = 0
        changed()
        later(k, (if (first) rnd(8000, 60000) else rnd(40000, 110000)).toLong()) { wake(k) }
    }

    private fun wake(k: Inner) {
        if (k.mode != Mode.SLEEP) return
        k.mode = Mode.SIT
        k.eyes = PileEyes.HALF
        changed()
        later(k, 700) {
            if (k.mode != Mode.SIT) return@later
            k.eyes = PileEyes.OPEN
            changed()
            blink(k, 3)
        }
        later(k, rnd(1200, 2500).toLong()) { stroll(k) }
    }

    private fun blink(k: Inner, n: Int) {
        if (n == 0 || k.mode != Mode.SIT) return
        later(k, rnd(1200, 2600).toLong()) {
            if (k.mode != Mode.SIT) return@later
            k.eyes = PileEyes.SHUT
            changed()
            later(k, 140) {
                if (k.mode != Mode.SIT) return@later
                k.eyes = PileEyes.OPEN
                changed()
                blink(k, n - 1)
            }
        }
    }

    private fun doze(k: Inner) {
        k.eyes = PileEyes.HALF
        changed()
        later(k, 900) { sleep(k) }
    }

    private fun stroll(k: Inner) {
        clear(k)
        if (carriers(k).isNotEmpty()) {
            k.mode = Mode.SIT
            k.eyes = PileEyes.OPEN
            changed()
            blink(k, 3)
            later(k, rnd(5000, 9000).toLong()) { doze(k) }
            return
        }
        if (k.on.isNotEmpty()) {
            // 坐在正在走路的大爪头上：等它停下再下来（原版 197 行）
            if (onBig(k) && bigWalking) {
                later(k, 1500) { stroll(k) }
                return
            }
            hopDown(k) { stroll(k) }
            return
        }
        wander(k, 2 + random.nextInt(3))
    }

    private fun wander(k: Inner, n: Int) {
        if (n <= 0) {
            k.pose = PilePose.CROUCH
            k.face = 0
            k.mode = Mode.SIT
            changed()
            save()
            later(k, rnd(1200, 2500).toLong()) { doze(k) }
            return
        }
        val bx = bigX()
        var target: Int? = null
        var i = 0
        while (i < 12 && target == null) {
            val t = Math.round((k.x + (if (random.nextBoolean()) -1 else 1) * rnd(30, 100)).toDouble() / px).toInt() * px
            if (freeAt(t, k.name, bx) && clearPath(k.x, t, k.name, bx)) target = t
            i++
        }
        if (target == null) {
            pause(k) { wander(k, 0) }
            return
        }
        walkTo(k, target) { pause(k) { wander(k, n - 1) } }
    }

    private fun pause(k: Inner, next: () -> Unit) {
        k.mode = Mode.SIT
        if (k.pose != PilePose.CROUCH) k.pose = PilePose.STAND
        k.eyes = PileEyes.OPEN
        changed()
        blink(k, 2)
        if (random.nextBoolean()) {
            val side = if (random.nextBoolean()) -1 else 1
            later(k, rnd(500, 1200).toLong()) {
                if (k.mode == Mode.SIT) {
                    k.face = side
                    changed()
                }
            }
            later(k, rnd(1600, 2400).toLong()) {
                if (k.mode == Mode.SIT) {
                    k.face = 0
                    changed()
                }
            }
        }
        later(k, rnd(1500, 4000).toLong()) { next() }
    }

    /* ===== 走路（原版 218–283 行） ===== */

    private fun walkTo(k: Inner, target: Int, done: () -> Unit) {
        endWalk(k)
        atHome = false
        k.mode = Mode.WALK
        k.eyes = PileEyes.OPEN
        k.face = (sign((target - k.x).toDouble()).toInt()).let { if (it == 0) 1 else it }
        val standing = k.pose == PilePose.STAND
        if (!standing) {
            k.pose = PilePose.CROUCH
            changed()
        }
        later(k, (if (standing) 0 else 300).toLong()) {
            k.pose = PilePose.STAND
            changed()
        }
        later(k, (if (standing) 300 else 650).toLong()) { steps(k, target, done) }
    }

    private fun steps(k: Inner, target: Int, done: () -> Unit) {
        val dir = (sign((target - k.x).toDouble()).toInt()).let { if (it == 0) 1 else it }
        k.walk = Walk(k.x, target, dir, done)
        k.pose = PilePose.RAISED
        changed()
        stepOnce(k)
    }

    private fun stepOnce(k: Inner) {
        val w = k.walk ?: return
        val x = w.base + w.off
        val b = bounds()
        if (x == w.target) {
            val done = w.onArrive
            endWalk(k)
            arrive(k, done)
            return
        }
        val nx = x + w.dir * px
        if (nx < b.first || nx > b.last) {
            val done = w.onArrive
            endWalk(k)
            arrive(k, done)
            return
        }
        val hit = grounded(k.name).find { o ->
            sign((posX(o) - x).toDouble()).toInt() == w.dir && abs(posX(o) - nx) < bodyW + gap
        }
        if (hit != null) {
            bump(k, hit, w)
            return
        }
        // 大爪也是会动的障碍（原版 253-255 行）
        val bx = bigX()
        val bigDir = if (bx != null) sign(bx - x).toInt() else 0
        if (bx != null && bigDir == w.dir && clashBig(nx, bx)) {
            bump(k, null, w)
            return
        }
        w.off += w.dir * px
        changed()
        later(k, data.stepMs.toLong()) { stepOnce(k) }
    }

    private fun endWalk(k: Inner) {
        val w = k.walk ?: return
        k.x = w.base + w.off
        k.walk = null
    }

    private fun arrive(k: Inner, done: (() -> Unit)?) {
        k.pose = PilePose.STAND
        changed()
        later(k, 300) {
            k.face = 0
            changed()
            done?.invoke()
        }
    }

    private fun bump(k: Inner, o: Inner?, w: Walk) {
        endWalk(k)
        k.pose = PilePose.STAND
        val back = k.x - w.dir * px
        if (freeAt(back, k.name)) k.x = back
        changed()
        if (o != null && o.mode == Mode.SLEEP) {
            o.eyes = PileEyes.HALF
            changed()
            later(o, 700) {
                if (o.mode == Mode.SLEEP) {
                    o.eyes = PileEyes.SHUT
                    changed()
                }
            }
        }
        later(k, 450) { arrive(k, w.onArrive) }
    }

    private fun settle(k: Inner, done: () -> Unit) {
        if (k.on.isNotEmpty()) {
            done()
            return
        }
        val t = nearestFree(k.x, k.name)
        if (t == null || t == k.x) {
            done()
            return
        }
        k.x = t
        changed()
        done()
    }

    private fun hopDown(k: Inner, done: () -> Unit) {
        val side = if (random.nextBoolean()) -1 else 1
        val t = nearestFree(k.x + side * (bodyW + 4 * px), k.name)
        if (t == null) {
            doze(k)
            return
        }
        k.on = mutableListOf()
        k.x = t
        k.mode = Mode.WALK
        k.pose = PilePose.STAND
        k.eyes = PileEyes.OPEN
        changed()
        later(k, 520) {
            k.pose = PilePose.CROUCH
            k.squashAt = android.os.SystemClock.elapsedRealtime()
            changed()
            later(k, 400) { done() }
        }
    }

    /* ===== 戳（原版 293–304 行） ===== */

    fun poke(name: String) {
        val k = actors.getValue(name)
        val was = k.mode
        val eyes = k.eyes
        val w = k.walk
        val wasWalking = w != null
        val dir = w?.dir ?: 1
        endWalk(k)
        if (wasWalking) {
            k.pose = PilePose.STAND
            k.face = 0
        }
        k.eyes = PileEyes.OPEN
        k.hopAt = android.os.SystemClock.elapsedRealtime()
        changed()
        later(k, 1000) {
            if (k.mode == Mode.HELD) return@later
            if (wasWalking) {
                // 走到一半被戳：看你一眼，1s 后继续走（原版 300-302 行）
                k.face = dir
                changed()
                later(k, 300) {
                    if (k.mode == Mode.WALK && w != null) steps(k, w.target, w.onArrive ?: {})
                }
                return@later
            }
            if (was == Mode.SLEEP) {
                k.eyes = PileEyes.HALF
                changed()
                later(k, 400) {
                    if (k.mode == Mode.SLEEP) {
                        k.eyes = PileEyes.SHUT
                        changed()
                    }
                }
            } else {
                k.eyes = eyes
                changed()
            }
        }
    }

    /* ===== 拖拽（原版 331–410 行） ===== */

    fun pickUp(name: String) {
        val k = actors.getValue(name)
        atHome = false
        clear(k)
        flights.remove(name)
        k.mode = Mode.HELD
        lowerFrom(carriers(k).map { it }, name)
        k.on = mutableListOf()
        // 支撑被抽走：大爪如果坐在这只头上，座位失效，runtime 会重新落座（原版 pickUp 341 行 lowerBig）
        if (bigSatName == k.name) bigSeatDirty = true
        k.pose = PilePose.DANGLE_A
        k.eyes = PileEyes.OPEN
        k.face = 0
        changed()
    }

    fun drop(name: String, cx: Int, onHead: String?) {
        val k = actors.getValue(name)
        k.squashAt = android.os.SystemClock.elapsedRealtime()
        if (onHead == "big") {
            // 放在大爪头上（原版 drop 的 onBigHead 分支）
            k.x = clampX(cx)
            k.on = mutableListOf("big")
            stackX()
            k.mode = Mode.SIT
            k.pose = PilePose.DANGLE_A
            changed()
            later(k, 300) {
                k.pose = PilePose.CROUCH
                k.eyes = PileEyes.OPEN
                changed()
                save()
                later(k, rnd(1500, 3000).toLong()) { doze(k) }
            }
            return
        }
        if (onHead != null && actors.containsKey(onHead) && actors.getValue(onHead).mode != Mode.HELD) {
            val below = actors.getValue(onHead)
            if (below.walk != null) {
                endWalk(below)
                clear(below)
                below.mode = Mode.SIT
                below.pose = PilePose.CROUCH
                below.face = 0
                changed()
                later(below, 2500) { doze(below) }
            }
            k.x = below.x
            k.on = mutableListOf(onHead)
        } else {
            k.x = clampX(cx)
            k.on = mutableListOf()
        }
        stackX()
        k.mode = Mode.SIT
        k.pose = PilePose.DANGLE_A
        changed()
        later(k, 300) {
            k.pose = PilePose.CROUCH
            k.eyes = PileEyes.OPEN
            changed()
            settle(k) {
                save()
                later(k, rnd(1500, 3000).toLong()) { doze(k) }
            }
        }
    }

    /* ===== 弹道（松手后飞行，与大 Clawd 同公式、速度更快） =====
       渲染层在拖拽松手时调 throwAt：状态机存飞行状态，渲染层按帧调 stepFlights 积分。
       落地后由回调归位到 drop() 的座位/地面逻辑。 */

    /** 弹道状态（渲染层按帧读；位置 = 画布左上角 dp，相对带；速度 = dp/16.7ms 帧）。 */
    class Flight(
        var x: Double, var y: Double,
        var vx: Double, var vy: Double,
        var bounces: Int = 0,
    )

    private val flights = HashMap<String, Flight>()
    fun flightOf(name: String): Flight? = flights[name]
    fun isFlying(name: String): Boolean = flights.containsKey(name)
    val hasFlights: Boolean get() = flights.isNotEmpty()

    /** 松手抛出。onLand(name, 落地x, 落到哪只身上或null——精确接触面，不再用临近猜测)。 */
    fun throwAt(
        name: String, x: Double, y: Double, vx: Double, vy: Double,
        onLand: (String, Int, String?) -> Unit,
    ) {
        actors.getValue(name)   // 校验存在
        flights[name] = Flight(x, y, vx, vy)
        flightCallbacks[name] = onLand
        changed()
    }

    private val flightCallbacks = HashMap<String, (String, Int, String?) -> Unit>()

    /**
     * 碰撞体清单（飞行扫掠用）：每个站稳的小爪一个 hull
     * [posX, -lift-liftH, posX+bodyW, -lift]（地面=0，向上为负）。
     * except 排除自己；includeBig=false 时排除骑在大爪头上的（大爪不能撞自己头上的乘客）。
     * 大爪站稳（含坐着时）也算：底 = -(bigSat 的 supportTop)，高 BIG_REST。
     */
    internal fun collisionBodies(except: String? = null, includeBig: Boolean = true): List<ClawdCollision.Solid> {
        val solids = ArrayList<ClawdCollision.Solid>()
        for (k in actors.values) {
            if (k.name == except || k.mode == Mode.HELD || flights.containsKey(k.name)) continue
            if (!includeBig && onBig(k)) continue
            val l = posX(k).toDouble()
            val b = -lift(k).toDouble()
            solids.add(ClawdCollision.Solid(k.name, ClawdCollision.Box(l, b - liftH, l + bodyW, b)))
        }
        if (includeBig && bigGrounded && bigLeftDp != null) {
            val bottom = -(bigSatName?.let { supportTop(it) } ?: 0).toDouble()
            solids.add(
                ClawdCollision.Solid(
                    "big",
                    ClawdCollision.Box(bigLeftDp!!, bottom - BIG_REST, bigLeftDp!! + BIG_W, bottom),
                )
            )
        }
        return solids
    }

    /** 渲染层驱动的物理帧（原版在 rAF 里）。dt = 相对 16.7ms 的倍数。返回 true = 还有在飞的。 */
    fun stepFlights(dt: Float, bandHeightDp: Float): Boolean {
        if (flights.isEmpty()) return false
        val P = flightPhys()
        val hPx = actorHeightPx.toDouble()
        val it = flights.entries.iterator()
        while (it.hasNext()) {
            val (name, f) = it.next()
            f.vy += P.g * dt
            f.vx *= Math.pow(P.drag, dt.toDouble())
            f.vy *= Math.pow(P.drag, dt.toDouble())
            // 移动盒（地面坐标系：底边 y，向上为负；地面 = 带底）
            var left = f.x
            var bottom = f.y + hPx - bandHeightDp
            val wallR = max(width, bodyW).toDouble()
            val solids = collisionBodies(except = name) + listOf(
                ClawdCollision.Solid("__ground", ClawdCollision.Box(-10000.0, 0.0, 10000.0, 10000.0)),
                ClawdCollision.Solid("__wallL", ClawdCollision.Box(-10000.0, -10000.0, 0.0, 10000.0)),
                ClawdCollision.Solid("__wallR", ClawdCollision.Box(wallR, -10000.0, wallR + 10000.0, 10000.0)),
            )
            var remainX = f.vx * dt
            var remainY = f.vy * dt
            var settled = false
            var contact: String? = null
            loop@ for (i in 0 until 4) {
                if (remainX == 0.0 && remainY == 0.0) break
                val box = ClawdCollision.Box(left, bottom - liftH, left + bodyW, bottom)
                val hit = ClawdCollision.firstHit(box, remainX, remainY, solids)
                if (hit == null) {
                    left += remainX; bottom += remainY
                    break
                }
                left += remainX * hit.time
                bottom += remainY * hit.time
                val tangX = remainX * (1 - hit.time)
                val tangY = remainY * (1 - hit.time)
                left += hit.pushX
                bottom += hit.pushY
                when {
                    hit.normalY == -1 -> {
                        if (f.vy > 0) {
                            // 落在顶面：弹跳或落稳
                            if (abs(f.vy) > .7 && f.bounces < 6) {
                                f.vy = -f.vy * P.rest
                                f.vx *= P.fric
                                f.bounces += 1
                            } else {
                                f.vx = 0.0; f.vy = 0.0
                                settled = true
                                contact = hit.solid.name
                                break@loop
                            }
                        }
                        remainX = tangX
                        remainY = if (tangY < 0.0) tangY else 0.0
                    }
                    hit.normalX != 0 -> {
                        if (f.vx * hit.normalX < 0) f.vx = .45 * -f.vx
                        remainX = if (tangX * hit.normalX > 0.0) tangX else 0.0
                        remainY = tangY
                    }
                    else -> {
                        if (f.vy * hit.normalY < 0) f.vy = .45 * -f.vy
                        remainY = if (tangY > 0.0) tangY else 0.0
                        remainX = tangX
                    }
                }
            }
            f.x = left
            f.y = bottom + bandHeightDp - hPx
            if (settled) {
                it.remove()
                val cb = flightCallbacks.remove(name)
                cb?.invoke(name, f.x.toInt(), contact?.takeIf { c -> c != "__ground" })
                changed()
                continue
            }
        }
        changed()
        return flights.isNotEmpty()
    }

    /** 与大 Clawd 同一套弹道参数，速度更快（feel .85 vs 大爪 .64：g/rest/thr 更大、drag 更小）。 */
    private data class FlightPhys(val g: Double, val rest: Double, val drag: Double, val fric: Double)

    private fun flightPhys(): FlightPhys {
        val t = (0.85 - .55) / .95
        return FlightPhys(
            g = .26 + t * .4,
            rest = .5 - t * .42,
            drag = .985 + t * .01,
            fric = .86 - t * .2,
        )
    }

    /** 下面那只被拿走：上面的平滑降下来补位（原版 lowerFrom 358–370 行） */
    private fun lowerFrom(list: List<String>, gone: String, hop: Boolean = false) {
        if (list.isEmpty()) return
        for (n in list) {
            // 'big' 不是小爪 actor（carriers 会算进来）：座位失效即可
            if (n == "big") {
                bigSeatDirty = true
                continue
            }
            val t = actors.getValue(n)
            t.on = t.on.filter { it != gone }.toMutableList()
            if (t.on.isEmpty()) {
                endWalk(t)
                // hop：大爪要做别的动作，头上的往旁边跳开（原版 364 行）
                val side = if (hop) (if (random.nextBoolean()) -1 else 1) * (BIG_W / 2 + bodyW) else 0
                t.x = nearestFree(t.x + side, t.name) ?: clampX(t.x + side)
            }
        }
        stackX()
        save()
    }

    /* ===== 查询（渲染层） ===== */

    fun snapshot(): List<Actor> = actors.map { (_, k) ->
        Actor(
            name = k.name,
            x = k.x,
            on = k.on.toList(),
            eyes = k.eyes,
            pose = k.pose,
            mode = k.mode,
            face = k.face,
            walkOff = k.walk?.off ?: 0,
            walkBase = k.walk?.base ?: k.x,
            hopAt = k.hopAt,
            squashAt = k.squashAt,
        )
    }

    fun liftOf(name: String): Int = lift(actors.getValue(name))

    /** 渲染层命中测试：某 x 坐标落在哪只身上（拿去 pickUp），没有返回 null */
    fun actorAt(px: Float): String? {
        val x = px.toInt()
        return actors.values.firstOrNull { k ->
            val left = k.walk?.let { it.base + it.off } ?: k.x
            x >= left && x < left + bodyW
        }?.name
    }

    /** 命中测试带 y：只认身体矩形（y 从地面往上 actorHeightPx） */
    fun actorAt(pxX: Float, pxYFromGround: Float): String? {
        val name = actorAt(pxX) ?: return null
        val h = actorHeightPx + liftOf(name)
        return if (pxYFromGround >= -h) name else null
    }

    val actorWidthPx: Int get() = bodyW
    val actorHeightPx: Int get() = data.h * data.px

    /* ===== 大 Clawd 支撑（原版 seats/blocks/follow/bigSat/unloadBig/restack，2.0.300+） ===== */

    /** 当前坐在大 Clawd 头上的那只（大 Clawd 只是一个「支撑」）。 */
    var bigSatName: String? = null
        private set

    /** 大 Clawd 在 pile 坐标系里的左缘（视觉左缘，dp）。由 runtime 推进时填；null 表示大爪不在。 */
    var bigLeftDp: Double? = null

    /** 大爪的座位因为支撑被抽走而失效（runtime 读走后清零，重新 lower）。 */
    var bigSeatDirty: Boolean = false
        private set

    /** runtime 取走「座位失效」标记（取一次清一次）。 */
    fun consumeBigSeatDirty(): Boolean {
        val v = bigSeatDirty
        bigSeatDirty = false
        return v
    }

    /** 大 Clawd 正站在输入框上（没被拎、没飞、没坐在小的头上）才算障碍。 */
    private var bigGrounded: Boolean = false
    /** 大 Clawd 正在走路（原版 bigState().walking）：骑它头上的要等它停下再下来。 */
    var bigWalking: Boolean = false
        private set

    fun setBigGrounded(grounded: Boolean, leftDp: Double?, walking: Boolean = false) {
        bigGrounded = grounded
        bigLeftDp = leftDp
        bigWalking = walking
    }

    /** 间接或直接坐在大 Clawd 头上（on 链里含 'big'）。 */
    private fun onBig(k: Inner): Boolean = k.on.any { it == "big" || onBig(actors.getValue(it)) }

    /** 大 Clawd 掉下来时，哪些小爪头上能坐：屏幕中心 x、身宽、坐上去离地多高（dp）。 */
    fun seats(): List<ClawdBigRuntime.SeatInfo> {
        if (!laid) return emptyList()
        return actors.values
            .filter { it.mode != Mode.HELD && carriers(it).isEmpty() && !onBig(it) }
            .map { k ->
                ClawdBigRuntime.SeatInfo(
                    cx = posX(k) + bodyW / 2.0,
                    h = (lift(k) + liftH).toDouble(),
                    w = bodyW.toDouble(),
                    name = k.name,
                )
            }
    }

    /** 飞行逐帧取座位（origin 固定）。 */
    fun trackSeats(): List<ClawdBigRuntime.SeatInfo> = seats()

    /** 大爪走到要避开：站在输入框上的小爪的左右边（dp）。 */
    fun blocks(): List<ClawdBigRuntime.RectF> {
        if (!laid) return emptyList()
        return grounded(null).map { k ->
            val x = posX(k).toFloat()
            ClawdBigRuntime.RectF(x, 0f, x + bodyW.toFloat(), 0f)
        }
    }

    /** 坐在大 Clawd 头上的小爪数量。 */
    fun riderCount(): Int = actors.values.count { onBig(it) }

    /** 大 Clawd 坐到某只头上（name）/ 落回地上（null）。 */
    fun bigSat(name: String?) {
        bigSatName = name?.takeIf { actors.containsKey(it) }
        if (bigSatName != null) atHome = false
        val k = bigSatName?.let { actors.getValue(it) }
        if (k != null && k.walk != null) {
            endWalk(k)
            clear(k)
            arrive(k) { doze(k) }
        }
        changed()
    }

    /** 大 Clawd 被拎起（lower：头上的降下来）/ 要做别的动作（hop：头上的跳开）。 */
    fun unloadBig(kind: String = "hop") {
        val list = actors.values.filter { it.on.contains("big") }.toList()
        if (list.isEmpty()) return
        for (k in list) clear(k)
        lowerFrom(list.map { it.name }, "big", hop = kind == "hop")
        for (k in list) {
            if (k.mode != Mode.SLEEP) {
                later(k, 1600) { doze(k) }
            } else sleep(k)
        }
        changed()
    }

    /** 大 Clawd 走路：头上的跟着挪（translate 渲染层换算）。 */
    var followDx = 0.0
        private set

    fun follow(dx: Double) {
        followDx = dx
        changed()
    }

    /** 输入框宽度变化时按新位置重摆（大爪坐着的跟着）。 */
    fun restack() {
        if (!laid) return
        stackX()
        changed()
    }

    fun followEnd() {
        if (followDx == 0.0 && actors.values.none { it.on.contains("big") }) return
        followDx = 0.0
        stackX()
        save(keepHome = true)
        changed()
    }

    /** 大 Clawd 不在（被移除/还没建好）：骑在 'big' 头上的落回地上。 */
    fun dropBigRiders() {
        val fell = actors.values.filter { it.on.contains("big") }.toList()
        if (fell.isEmpty()) return
        for (k in fell) {
            k.on = k.on.filter { it != "big" }.toMutableList()
        }
        for (k in fell) if (k.on.isEmpty()) nearestFree(k.x, k.name)?.let { k.x = it }
        stackX()
        changed()
    }

    companion object {
        /** 大 Clawd 的座位高度（第 4 步接入；原版 BIG_REST=27） */
        const val BIG_REST = 27
        /** 大 Clawd 身子宽（16 格 × px=3） */
        const val BIG_W = 48
    }
}
