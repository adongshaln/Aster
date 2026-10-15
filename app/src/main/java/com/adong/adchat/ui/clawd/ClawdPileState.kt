package com.adong.adchat.ui.clawd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    )

    private class Inner {
        var x = 0
        var on: MutableList<String> = mutableListOf()
        var eyes = PileEyes.SHUT
        var pose = PilePose.CROUCH
        var mode = Mode.SLEEP
        var face = 0
        var walk: Walk? = null
        var jobs = mutableListOf<Job>()
    }

    private class Walk(val base: Int, val target: Int, val dir: Int, val onArrive: (() -> Unit)?) {
        var off = 0
    }

    private val actors = linkedMapOf<String, Inner>()

    var centerX = 0
        private set
    var width = 0
        private set
    private var laid = false
    private var atHome = false
    private var lastW = 0

    var onChanged: (() -> Unit)? = null

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
            delay(ms)
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
        if (n == "big") BIG_REST else lift(actors.getValue(n)) + liftH

    private fun carriers(k: Inner): List<String> =
        actors.values.filter { it.on.contains(k.name) }.map { it.name }

    private fun grounded(except: String? = null): List<Inner> =
        actors.values.filter { it.name != except && it.on.isEmpty() && it.mode != Mode.HELD }

    private fun posX(o: Inner): Int = o.walk?.base?.plus(o.walk.off) ?: o.x

    private fun freeAt(x: Int, except: String): Boolean {
        val b = bounds()
        if (x < b.first || x > b.last) return false
        return grounded(except).all { abs(posX(it) - x) >= bodyW + gap }
    }

    private fun nearestFree(x: Int, except: String): Int? {
        val b = bounds()
        val cx = clampX(x)
        if (freeAt(cx, except)) return cx
        var d = px
        while (d < width) {
            for (s in intArrayOf(-1, 1)) {
                val t = cx + s * d
                if (t in b && freeAt(t, except)) return t
            }
            d += px
        }
        return null
    }

    private fun clearPath(a: Int, b: Int, except: String): Boolean {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        val apart = { o: Int -> o + bodyW + gap <= lo || o >= hi + bodyW + gap }
        return grounded(except).all { apart(posX(it)) }
    }

    /* ===== 叠放几何（原版 523–535 行 stackX/unstack） ===== */

    private fun depthOf(k: Inner): Int =
        if (k.on.isEmpty()) 0 else 1 + k.on.maxOf { depthOf(actors.getValue(it)) }

    private fun stackX() {
        val stacked = actors.values.filter { it.on.isNotEmpty() }.sortedBy { depthOf(it) }
        for (k in stacked) {
            val cx = k.on.sumOf { actors.getValue(it).x + bodyW / 2 }.toDouble() / k.on.size
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
        for (n in data.names) actors[n] = Inner()
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
        var target: Int? = null
        var i = 0
        while (i < 12 && target == null) {
            val t = Math.round((k.x + (if (random.nextBoolean()) -1 else 1) * rnd(30, 100)).toDouble() / px).toInt() * px
            if (freeAt(t, k.name) && clearPath(k.x, t, k.name)) target = t
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
            changed()
            later(k, 400) { done() }
        }
    }

    /* ===== 戳（原版 293–304 行） ===== */

    fun poke(name: String) {
        val k = actors.getValue(name)
        val was = k.mode
        val eyes = k.eyes
        val wasWalking = k.walk != null
        val dir = k.walk?.dir ?: 1
        endWalk(k)
        if (wasWalking) {
            k.pose = PilePose.STAND
            k.face = 0
        }
        k.eyes = PileEyes.OPEN
        changed()
        later(k, 1000) {
            if (k.mode == Mode.HELD) return@later
            if (wasWalking) {
                k.face = dir
                changed()
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
        k.mode = Mode.HELD
        lowerFrom(carriers(k).map { it }, name)
        k.on = mutableListOf()
        k.pose = PilePose.DANGLE_A
        k.eyes = PileEyes.OPEN
        k.face = 0
        changed()
    }

    fun drop(name: String, cx: Int, onHead: String?) {
        val k = actors.getValue(name)
        if (onHead != null && actors.containsKey(onHead) && actors.getValue(onHead).mode != Mode.HELD) {
            val below = actors.getValue(onHead)
            if (below.walk != null) {
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

    /** 下面那只被拿走：上面的平滑降下来补位（原版 lowerFrom 358–370 行） */
    private fun lowerFrom(list: List<String>, gone: String) {
        if (list.isEmpty()) return
        for (n in list) {
            val t = actors.getValue(n)
            t.on = t.on.filter { it != gone }.toMutableList()
            if (t.on.isEmpty()) {
                endWalk(t)
                t.x = nearestFree(t.x, t.name) ?: clampX(t.x)
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
        )
    }

    fun liftOf(name: String): Int = lift(actors.getValue(name))

    /** 渲染层命中测试：某 x 坐标落在哪只身上（拿去 pickUp），没有返回 null */
    fun actorAt(px: Float): String? {
        val x = px.toInt()
        return actors.values.firstOrNull { k ->
            val left = k.walk?.base?.plus(k.walk.off) ?: k.x
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

    companion object {
        /** 大 Clawd 的座位高度（第 4 步接入；原版 BIG_REST=27） */
        const val BIG_REST = 27
    }
}
