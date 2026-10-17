package com.adong.adchat.ui.clawd

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.random.Random

/**
 * 大 Clawd 运行时。行为逐段对照原版宿主桥（tools/clawd/host-template.html，
 * 由原版 index.js 移植）：
 * - 三轨状态（index.js 1474-1618）：A=生成/事件、B=空闲反应/表情、C=交互（戳/拖/摸）
 * - A2 物理（1619-1680、3147-3470）：抓/拖/弹道/弹跳/squash/落座/边界
 * - 作息调度（3496-3600）：3min 无操作打瞌睡入睡、60s 没被理委屈
 * - 空闲动作（2255-2354）：随机动画池 + 走路
 * - 打字反应（2181-2236）：插入 !/? → wow/tilt；蹲字由输入 diff 驱动
 *
 * 时间源统一用 SystemClock.elapsedRealtime()（单调时钟，避开 Date.now 的墙钟漂移）。
 * 所有状态机 tick 由注入 [scope] 驱动：200ms runtimeTick + rAF 式物理帧（launchWhileActive 60fps）。
 */
class ClawdBigRuntime(
    private val rig: RigData,
    private val pile: ClawdPileState,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val isReducedMotion: () -> Boolean = { false },
) {
    // ── 外部输入（Compose 侧写，runtime 读）──
    var inputText by androidx.compose.runtime.mutableStateOf("")
    var inputFocused by androidx.compose.runtime.mutableStateOf(false)
    var generating by androidx.compose.runtime.mutableStateOf(false)
    var lastAssistantText by androidx.compose.runtime.mutableStateOf("")
    var night by androidx.compose.runtime.mutableStateOf(false)

    // ── 状态（渲染层订阅 onChanged 快照）──
    var onChanged: (() -> Unit)? = null

    /** 三轨可见状态（渲染层按 A→C→B 优先级选 clip 渲染）。 */
    data class Visual(
        /** 当前 clip id（若 C/A 为空、B 为 'idle' 则是 'idle'） */
        val clip: String,
        /** 整只像素偏移（CSS px → dp），与 A2.x/fy 同坐标系 */
        val dx: Int,
        val dy: Int,
        /** rotate 角（°） */
        val rot: Float,
        /** squash 缩放（sx > 1 横拉，sy < 1 压扁） */
        val sx: Float,
        val sy: Float,
        /** 弹性层 flex */
        val flex: FlexValue,
        /** 显示中（A2.seqOwned 期间强制占用 C）*/
        val mirror: Boolean,
        /** 生成徽标：剩余秒数（<0 表示不显示） */
        val timerText: String?,
        /** 夜间墨色调色 */
        val night: Boolean,
    )

    // ═══ 三轨（host-template 177-222）═══
    private var trackA: String? = null   // 'think'|'stream'|'sit'|'done'|'stopped'|'error'
    private var trackB: String = "idle" // 'idle'|各种 pose|'rig:xxx'
    private var trackC: String? = null   // 'press'|'grab'|'drag'|'fly'|'land'|'stomp'|'pet'|t1-t5|'turn'|'face'|'rage'
    private var aUntil = 0L
    private var bUntil = 0L
    private var cUntil = 0L
    private var aStartedAt = 0L
    private var round = 0
    private var activeRound = 0
    private var settledRound = 0
    private var genStartedAt = 0L

    private var pokeT1 = "poke1"  // 每轮 t1 选 poke1/poke1Shy（host 501 行）

    private val clipVariants = mutableMapOf("drag" to "drag", "stomp" to "stomp", "sulk" to "sulk")

    // ═══ A2 物理（host 232-244）═══
    private var petTimer: Job? = null
    private var petting = false
    private var petUntil = 0L
    var x = 0.0; private var fy = 0.0
    private var homeX = 0.0; private var floor = 0.0
    private var feel = .64; private var irr = 0; private var throws = 0
    private var lastThrow = 0L; private var lastDec = 0L
    private var lockUntil = 0L; private var lockName = ""; private var seqRun = 0; private var seqOwned = false
    private var flying = 0; private var dragging = false; private var moved = false
    private var flyingActive = false
    private var held = false; private var pointerId = -1L
    private var wokeOnDown = false
    private var bndReady = false
    private var bndRaf: Job? = null
    private var sx0 = 0.0; private var sy0 = 0.0; private var ox0 = 0.0; private var oy0 = 0.0
    private var vx0 = 0.0; private var vy0 = 0.0; private var lx = 0.0; private var ly = 0.0; private var lt = 0L
    private var rot0 = 0.0; private var sqx = 1.0; private var sqy = 1.0
    private var squashTimer: Job? = null
    private var bnd = Bounds()
    private var bndAt = 0L
    private var tookPointer = false

    private data class Bounds(
        val minx: Double = -9e9, val maxx: Double = 9e9,
        val maxxHome: Double = 9e9, val minxHome: Double = -9e9,
        val miny: Double = -A2_FALLBACK_CEILING, val maxy: Double = 0.0,
    )

    // ═══ 作息（host 3496-3600）═══
    private var lastActivityAt = now()
    private var idleAsleep = false
    private var lastPokeAt = now()
    private var neglected = false
    private var ccDrowsy = false
    private var dozeTimer: Job? = null
    private var neglectShownAt = -1L

    // ═══ 打字反应（host 2181-2236）═══
    private var composerBefore = ""
    private var composerBeforeStart = 0
    private var composerBeforeEnd = 0
    private var composerReaction: Reaction? = null
    private var composerReactionSerial = 0
    private var composerHasSeenText = false

    private data class Reaction(val state: String, val until: Long, val serial: Int)

    // ═══ 空闲调度（host 2255-2354）═══
    private var ambientNextAt = now() + 18000L
    private var lastAmbient = ""
    private val lastPlayed = mutableMapOf<String, Long>()
    private var walkRun = 0
    private var walkNudge: ((Double) -> Unit)? = null

    // ═══ 滚动 peek（host 3725-3760）═══
    private var scrollHolding = false
    private var scrollRestTimer: Job? = null
    private var pileRestTimer: Job? = null
    private var suppressManualScrollUntil = 0L

    // ═══ 帧渲染输出（采样层读）═══
    private var started = false
    private var runtimeJob: Job? = null
    private var physicsJob: Job? = null
    private var timerJob: Job? = null

    private var currentFlex = FlexValue.IDENTITY
    private var timerText: String? = null
    private var lastVisual: Visual? = null

    private fun now() = SystemClock.elapsedRealtime()

    fun start() {
        if (started) return
        started = true
        runtimeJob = scope.launch { runtimeLoop() }
        physicsJob = scope.launch { physicsLoop() }
    }

    fun stop() {
        runtimeJob?.cancel(); runtimeJob = null
        physicsJob?.cancel(); physicsJob = null
        petTimer?.cancel(); dozeTimer?.cancel()
        scrollRestTimer?.cancel(); pileRestTimer?.cancel()
        squashTimer?.cancel(); bndRaf?.cancel()
        started = false
    }

    private suspend fun runtimeLoop() {
        while (scope.isActive) {
            tick()
            delay(RUNTIME_TICK_MS)
        }
    }

    private suspend fun physicsLoop() {
        var last = now()
        while (scope.isActive) {
            val t = now()
            val dt = ((t - last) / 16.6667f).coerceIn(0.5f, 3f)
            last = t
            if (dragging || held || ballisticSteps != null) stepPhysics(dt)
            delay(FRAME_MS)
        }
    }

    // ═══════════════════ 三轨（host setClawdA/B/C）═══════════════════

    private fun renderTracks() = onChanged?.invoke()

    private fun setClawdA(value: String?, duration: Long = 0) {
        if (value != null) composerReactionReset()
        a2CancelSeq()
        if (value != trackA) shakeRiders(value)
        if (value != null) cancelTransientB()
        val t = now()
        trackA = value
        aStartedAt = if (value != null) t else 0
        val dur = if (value == "think" && duration == 0L) 16000L else if (value == "stream" && duration == 0L) 20000L else duration
        aUntil = if (value != null && dur > 0) t + dur else 0
        renderTracks()
        if (value == null && trackB == "idle") syncBState()
    }

    private fun setClawdB(value: String?, duration: Long = 0) {
        val v = value ?: "idle"
        if (v != trackB) shakeRiders(v)
        trackB = v
        bUntil = if (value != null && duration > 0) now() + duration else 0
        renderTracks()
    }

    private fun setClawdC(value: String?, duration: Long = 0) {
        if (value != null) composerReactionReset()
        if (a2Locked() && !seqOwned) return
        if (value != null) cancelTransientB()
        if (value != "grab" && value != "drag" && value != trackC) shakeRiders(value)
        trackC = value
        cUntil = if (value != null && duration > 0) now() + duration else 0
        renderTracks()
        if (value == null && trackB == "idle") syncBState()
    }

    private fun cancelTransientB() {
        if (bUntil == 0L) return
        trackB = "idle"
        bUntil = 0
    }

    private fun a2Locked() = lockUntil > now()

    private fun a2CancelSeq() {
        if (lockUntil == 0L) { seqRun += 1; return }
        seqRun += 1; lockUntil = 0; lockName = ""
        seqOwned = true; setClawdC(null); seqOwned = false
    }

    private fun shakeRiders(value: String?) {
        if (value == null) return
        val base = value.removePrefix("rig:").removeSuffix("-m")
        if (base in QUIET_WITH_RIDERS) return
        pile.unloadBig("hop")
    }

    // ═══════════════════ 生成联动 ═══════════════════

    /** Compose 侧在 generation 状态变化时调：active=生成中, hasText=已有部分输出, outcome=收尾（done/stopped/error） */
    fun setGeneration(active: Boolean, hasText: Boolean, outcome: String?) {
        generationEventActive = active
        if (active && !lastGenActive) {
            beginGeneration()
            lastGenActive = true
        } else if (!active && lastGenActive) {
            settleGeneration(outcome ?: "done")
            lastGenActive = false
        }
        noteActivity()
    }

    private var generationEventActive = false
    private var lastGenActive = false

    private fun beginGeneration() {
        round += 1; activeRound = round
        genStartedAt = now()
        setClawdA("think")
        timerText = "0s"
        timerJob?.cancel()
        timerJob = scope.launch { genTimerLoop() }
    }

    private fun settleGeneration(outcome: String) {
        val r = activeRound
        if (r == 0 || settledRound == r) return
        settledRound = r
        setClawdA(outcome, (rig.clips[outcome]?.dur ?: 1400).toLong())
        timerJob?.cancel()
        timerJob = scope.launch {
            delay(GEN_TIMER_LINGER_MS)
            timerText = null
        }
    }

    private suspend fun genTimerLoop() {
        var lastSecond = -1
        while (scope.isActive) {
            val secs = ((now() - genStartedAt) / 1000).toInt()
            if (secs != lastSecond) {
                lastSecond = secs
                timerText = "${secs}s"
                onChanged?.invoke()
            }
            delay(200)
        }
    }

    // ═══════════════════ 输入（打字联动）═══════════════════

    /**
     * 输入框文本变化。复刻 clawdComposerReaction：检测插入的 !/? → wow/tilt；
     * 非 composition 的单字符插入（打字）也会触一次 noteActivity + 蹲。
     */
    fun onInputText(text: String) {
        if (inputText == text) return
        inputText = text
        // 插入检测：旧值是前中缀
        if (composerHasSeenText && text.isNotEmpty() && composerBefore != text) {
            val addition = computeInserted(composerBefore, text, composerBeforeStart, composerBeforeEnd)
            if (addition != null) commitComposer(addition, null)
        }
        composerBefore = text
        composerBeforeStart = text.length
        composerBeforeEnd = text.length
        composerHasSeenText = true
        noteActivity()
        syncBState()
    }

    fun onInputFocus(focused: Boolean) {
        if (inputFocused == focused) return
        inputFocused = focused
        if (!focused) composerReactionReset()
        syncBState()
    }

    private fun computeInserted(old: String, new: String, selStart: Int, selEnd: Int): String? {
        if (old == new || old.length > new.length) return null
        val prefix = old.take(selStart)
        val suffix = old.drop(selEnd)
        if (!new.startsWith(prefix) || !new.endsWith(suffix) || new.length < prefix.length + suffix.length) return null
        return new.substring(prefix.length, new.length - suffix.length)
    }

    private fun commitComposer(addition: String, expected: String?) {
        if (expected != null && addition != expected) return
        val mark = Regex("[?？!！](?=[^?？!！]*$)").find(addition)?.value
        if (mark != null && !composerBlocked()) {
            val state = if (mark.contains(Regex("[!！]"))) "wow" else "tilt"
            composerReaction = Reaction(state, now() + (rig.clips[state]?.dur ?: 1500).toLong(), ++composerReactionSerial)
        }
    }

    private fun composerBlocked(): Boolean =
        trackA != null || trackC != null || a2Locked() || idleAsleep || ccDrowsy

    private fun composerReactionReset() {
        composerBefore = ""; composerBeforeStart = 0; composerBeforeEnd = 0; composerReaction = null
    }

    // ═══════════════════ B 轨状态同步 ═══════════════════

    private fun syncBState() {
        val reaction = if (inputFocused) currentComposerReaction() else null
        val next = when {
            idleAsleep -> "sleep"
            ccDrowsy -> "drowsy"
            reaction != null -> reaction.state
            inputFocused && inputText.isNotBlank() -> "compose"
            neglected -> "neglected"
            else -> "idle"
        }
        if (reaction != null) {
            composerReactionSerial = reaction.serial
            setClawdB(next, maxOf(1L, reaction.until - now()))
            return
        }
        if (bUntil > now() && next in listOf("idle", "neglected", "drowsy", "sleep")) {
            renderTracks(); return
        }
        setClawdB(next)
    }

    private fun currentComposerReaction(): Reaction? {
        val r = composerReaction ?: return null
        if (composerBlocked() || now() >= r.until) {
            composerReaction = null
            return null
        }
        return r
    }

    // ═══════════════════ 作息（host refreshIdleSleep/noteActivity）═══════════════════

    private fun setSleeping(on: Boolean) {
        if (!on && idleAsleep) idleAsleep = false
        syncBState()
    }

    private fun setDrowsy(on: Boolean) {
        ccDrowsy = on
        syncBState()
    }

    private fun setNeglected(on: Boolean) {
        if (neglected == on) return
        neglected = on
        syncBState()
    }

    private fun refreshIdleSleep() {
        val elapsed = now() - lastActivityAt
        val idle = elapsed > IDLE_SLEEP_MS
        val shouldBeNeglected = elapsed < IDLE_SLEEP_MS * .75 && !generationEventActive && now() - lastPokeAt > NEGLECT_POKE_MS
        if (shouldBeNeglected) {
            if (neglected) {
                if (now() - neglectShownAt > CLAWD_NEGLECT_SHOW_MS) setNeglected(false)
            } else if (now() - neglectShownAt > CLAWD_NEGLECT_REPEAT_MS && trackB == "idle" && bUntil == 0L) {
                neglectShownAt = now()
                setNeglected(true)
            }
        } else setNeglected(false)

        if (!idle) {
            dozeTimer?.cancel(); dozeTimer = null
            if (ccDrowsy) setDrowsy(false)
            if (idleAsleep) {
                idleAsleep = false
                setSleeping(false)
            }
            return
        }
        if (idleAsleep) {
            setSleeping(true)
            return
        }
        if (dozeTimer != null) return
        setNeglected(false)
        setDrowsy(true)
        dozeTimer = scope.launch {
            delay(DOZE_OFF_MS)
            dozeTimer = null
            if (now() - lastActivityAt <= IDLE_SLEEP_MS) return@launch
            ccDrowsy = false
            idleAsleep = true
            setSleeping(true)
        }
    }

    fun noteActivity() {
        lastActivityAt = now()
        dozeTimer?.cancel(); dozeTimer = null
        setDrowsy(false)
        if (!idleAsleep) return
        idleAsleep = false
        setClawdB("wake", (rig.clips["wake"]?.dur ?: 1000).toLong())
    }

    // ═══════════════════ 滚动 peek ═══════════════════

    /** Compose 侧挂 snapshotFlow 调：滚动中持续调，停 700ms 自动恢复。 */
    fun onScroll() {
        if (generationEventActive || trackA != null || now() < suppressManualScrollUntil) return
        val bBase = trackB.removePrefix("rig:").removeSuffix("-m")
        val bigPeeks = trackC == null && !a2Locked() && !held && abs(fy - floor) < .5
            && !(idleAsleep || ccDrowsy || bBase == "sleep" || bBase == "drowsy")
        pile.peek(true, if (bigPeeks && floor == 0.0) 12 else 0, floor != 0.0)
        if (bigPeeks && floor == 0.0) {
            scrollHolding = true
            setClawdB("peek", 60000)
            scheduleAmbient(now())
        }
        armScrollRest()
    }

    private fun armScrollRest() {
        pileRestTimer?.cancel()
        pileRestTimer = scope.launch {
            delay(CLAWD_SCROLL_REST_MS)
            pileRestTimer = null
            pile.peek(false)
        }
        if (!scrollHolding) return
        scrollRestTimer?.cancel()
        scrollRestTimer = scope.launch {
            delay(CLAWD_SCROLL_REST_MS)
            scrollRestTimer = null
            scrollRelease()
        }
    }

    private fun scrollRelease() {
        scrollHolding = false
        if (trackB == "peek") setClawdB("rig:peekOut", (rig.clips["peekOut"]?.dur ?: 900).toLong())
    }

    // ═══════════════════ 空闲调度 ═══════════════════

    private fun scheduleAmbient(t: Long, retry: Boolean = false) {
        ambientNextAt = t + if (retry) 5000L else 22000L + random.nextLong(14000L)
    }

    private fun maybePlayAmbient(nowMs: Long) {
        if (nowMs < ambientNextAt) return
        val blocked = generationEventActive || isReducedMotion() || trackA != null || trackC != null
                || trackB != "idle" || bUntil > 0 || inputFocused || inputText.isNotBlank()
        if (blocked) {
            scheduleAmbient(nowMs, retry = true)
            return
        }
        if (pile.riderCount() > 0) {
            if (random.nextDouble() < .35 && walk(nowMs) > 0) {
                lastAmbient = "rig:walk"
                scheduleAmbient(nowMs + 6000L)
                return
            }
            val quiet = AMBIENT_QUIET_POSES.filter { it.first != lastAmbient }
            val (state, dur) = quiet[random.nextInt(quiet.size)]
            lastAmbient = state
            setClawdB(state, dur)
            scheduleAmbient(nowMs)
            return
        }
        if (random.nextDouble() < .3) {
            val id = pickAmbientClip(nowMs)
            if (id == "walk") {
                lastPlayed["walk"] = nowMs
                val ms = walk(nowMs)
                if (ms > 0) {
                    lastAmbient = "rig:walk"
                    scheduleAmbient(nowMs + ms)
                    return
                }
            } else if (id != null) {
                lastPlayed[id] = nowMs
                val forms = AMBIENT_FORMS[id]
                val play = forms?.let { it[random.nextInt(it.size)] }
                    ?: if (id == "plant" && random.nextDouble() < .5) "plantWilt" else id
                lastAmbient = "rig:$play"
                setClawdB("rig:$play", (rig.clips[play]?.dur ?: 1000).toLong())
                scheduleAmbient(nowMs + (rig.clips[play]?.dur ?: 1000))
                return
            }
        }
        val choices = AMBIENT_POSES.filter { it.first != lastAmbient }
        val (state, dur) = choices[random.nextInt(choices.size)]
        lastAmbient = state
        setClawdB(state, dur)
        scheduleAmbient(nowMs)
    }

    private fun mealTime(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return m in 690..780 || m in 1050..1170
    }

    private fun overComposer(): Boolean {
        if (floor != 0.0 || held || abs(fy) > .5) return false
        if (!bndReady) return true
        return x >= bnd.minxHome - 1 && x <= bnd.maxxHome + 1
    }

    private fun pickAmbientClip(nowMs: Long): String? {
        val meal = mealTime()
        val over = overComposer()
        val ok = AMBIENT_POOL.filter { over || it.id !in ON_COMPOSER_ONLY }
            .filter { lastPlayed[it.id]?.let { lp -> nowMs - lp < it.cool } != true }
            .map { it.id to (if (meal && it.mealWeight > 0) it.mealWeight else it.weight) }
        val total = ok.sumOf { it.second }
        if (total <= 0) return null
        var roll = random.nextDouble() * total
        for ((id, w) in ok) {
            roll -= w
            if (roll <= 0) return id
        }
        return ok.last().first
    }

    private var walkT0 = 0L
    private var walkFrom = 0.0
    private var walkTo = 0.0
    private var walkMs = 0

    /** 大爪自主走路（ambient 池里的 'walk'）。返回总时长 ms；0 = 走不了。 */
    private fun walk(nowMs: Long): Long {
        if (held || fy != 0.0 || floor != 0.0) return 0
        if (!bndReady || nowMs - bndAt > A2_BOUNDS_TTL) {
            bnd = a2Walls(); bndAt = nowMs; bndReady = true
        }
        val lo = maxOf(bnd.minx, bnd.minxHome)
        val hi = bnd.maxxHome
        if (hi - lo <= 40 || hi - lo >= 1e5) return 0
        val roomL = x - lo
        val roomR = hi - x
        val want = (hi - lo) * (.2 + random.nextDouble() * .2)
        var dir = if (roomR > roomL + 20) 1 else if (roomL > roomR + 20 || random.nextBoolean()) -1 else 1
        var dist = minOf(want, if (dir > 0) roomR else roomL)
        if (dist < 24) {
            dir = -dir
            dist = minOf(want, if (dir > 0) roomR else roomL)
        }
        if (roomL < 0) { dir = 1; dist = minOf(hi - x, want / 2 - roomL) }
        else if (roomR < 0) { dir = -1; dist = minOf(x - lo, want / 2 - roomR) }
        // 避让小爪
        for (b in pile.blocks()) {
            if (dir > 0 && b.left >= x + RIG_W - 1) dist = minOf(dist, b.left - (x + RIG_W) - 4)
            if (dir < 0 && b.right <= x - 1) dist = minOf(dist, x - b.right - 4)
        }
        if (dist < 24) return 0
        val steps = maxOf(2, (dist / CLAWD_RIG_CELL).toInt())
        val totalMs = steps * 300
        val intro = rig.clips["walkLoop"]?.intro ?: 0
        walkFrom = x
        walkTo = x + dir * steps * CLAWD_RIG_CELL
        walkMs = totalMs
        walkT0 = nowMs + intro
        val state = if (dir > 0) "rig:walkLoop-m" else "rig:walkLoop"
        setClawdB(state, (intro + totalMs + 50).toLong())
        return (intro + totalMs + 50).toLong()
    }

    /** runtimeTick 里推进走路位移（原版在 requestClawdFrame 里） */
    private fun stepWalk(nowMs: Long) {
        if (trackB != "rig:walkLoop" && trackB != "rig:walkLoop-m") {
            walkNudge = null
            return
        }
        if (trackA != null || trackC != null || held || fy != 0.0) {
            endWalk()
            return
        }
        pile.follow(x - walkFrom)
        val k = ((nowMs - walkT0).toDouble() / walkMs).coerceIn(0.0, 1.0)
        x = walkFrom + (walkTo - walkFrom) * k
        if (k >= 1) endWalk()
    }

    private fun endWalk() {
        walkNudge = null
        homeX = x
        pile.followEnd()
        scheduleBoundsWarm()
    }

    /** 输入框宽度变化时大爪跟着平移（host clawdShiftWithForm） */
    fun shiftWithForm(d: Double) {
        if (x == 0.0 && floor == 0.0 && !held) return
        x += d; homeX += d
        if (held) ox0 += d
        walkNudge?.invoke(d)
        pile.restack()
        scheduleBoundsWarm()
    }

    // ═══════════════════ 触摸（host a2Down/Move/Up/Ballistic）═══════════════════

    /** 大爪被按下。consume 与否由调用方按 pointerInput 命中测试先判。 */
    fun a2Down(px: Float, py: Float, id: Long = 0) {
        if (held && id != pointerId) return
        pointerId = id
        tookPointer = false
        wokeOnDown = idleAsleep
        a2NoteInteraction()
        if (a2Locked()) {
            tookPointer = true
            return
        }
        held = true
        flying += 1
        vx0 = 0.0; vy0 = 0.0
        moved = false
        sx0 = 0.0; sy0 = 0.0
        ox0 = x; oy0 = fy
        lx = px.toDouble(); ly = py.toDouble(); lt = now()
        if (!bndReady || now() - bndAt > A2_BOUNDS_TTL) {
            bnd = a2Walls(); bndAt = now(); bndReady = true
        }
        snapshotProps()
        setClawdC(if (fy < floor - .5) "grab" else "press", 0)
        petTimer?.cancel()
        petting = false
        petTimer = scope.launch {
            delay(CLAWD_PET_HOLD_MS)
            petTimer = null
            if (!held || moved || fy < floor - .5) return@launch
            petting = clawdPet()
        }
    }

    /** 拖拽位移累计：手指相对按下点的总位移（px,py 是绝对位移由调用方累计）。 */
    fun a2Move(dxAcc: Float, dyAcc: Float, px: Float, py: Float, id: Long = 0) {
        if (!held || id != pointerId) return
        val dx = dxAcc
        val dy = dyAcc
        var dragStarted = false
        if (!moved && abs(dx) + abs(dy) > A2_DRAG_THRESHOLD) {
            moved = true; dragging = true; dragStarted = true
            petTimer?.cancel(); petting = false
            pile.unloadBig("lower")
            if (floor != 0.0) {
                floor = 0.0
                pile.bigSat(null)
            }
            setClawdC("grab", 0)
            scope.launch {
                delay(350)
                if (dragging) {
                    pickVariant("drag")
                    setClawdC("drag", 0)
                }
            }
        }
        if (!moved) return
        fy = (oy0 + dy).coerceIn(bnd.miny, bnd.maxy)
        x = a2ClampX(ox0 + dx)
        val t = now()
        val dt = maxOf(12L, t - lt)
        vx0 = vx0 * .45 + ((px.toDouble() - lx) / dt * 16 * .55)
        vy0 = vy0 * .45 + ((py.toDouble() - ly) / dt * 16 * .55)
        lx = px.toDouble(); ly = py.toDouble(); lt = t
        rot0 = (0.7 * -vx0 / feel).coerceIn(-14.0, 14.0)
        onChanged?.invoke()
    }

    fun a2Up(id: Long = 0) {
        if (!held || id != pointerId) return
        held = false
        tookPointer = true
        petTimer?.cancel()
        if (petting && !moved) {
            petting = false; rot0 = 0.0; onChanged?.invoke()
            return
        }
        petting = false
        if (moved || fy < floor - .5) wokeOnDown = false
        if (moved) ballistic()
        else if (fy < floor - .5) {
            rot0 = 0.0
            ballistic(drop = true)
        } else {
            rot0 = 0.0
            onChanged?.invoke()
            a2Poke()
        }
    }

    fun a2Cancel(id: Long = 0) {
        if (!held || id != pointerId) return
        petTimer?.cancel(); petting = false; held = false; tookPointer = true
        dragging = false; rot0 = 0.0
        if (moved || fy < floor - .5) ballistic(drop = true) else setClawdC(null)
    }

    private fun a2Phys(): Phys {
        val t = (feel - .55) / .95
        return Phys(
            g = .26 + t * .4,
            rest = .5 - t * .42,
            drag = .985 + t * .01,
            thr = .78 - t * .12,
            fric = .86 - t * .2,
            spin = .55 - t * .38,
            maxRot = 16 - t * 8,
            sq = .1 - t * .03,
        )
    }

    private data class Phys(
        val g: Double, val rest: Double, val drag: Double, val thr: Double,
        val fric: Double, val spin: Double, val maxRot: Double, val sq: Double,
    )

    private fun ballistic(drop: Boolean = false) {
        val P = a2Phys()
        fun cap(v: Double) = v.coerceIn(-24.0, 24.0)
        var bx = if (drop) 0.0 else cap(vx0) * P.thr
        var by = if (drop) 0.0 else cap(vy0) * P.thr
        var bounces = 0
        val my = ++flying
        if (!drop) {
            throws += 1; lastThrow = now(); lastDec = now()
            irr = minOf(5, irr + if (throws >= 3) 2 else 1)
        }
        dragging = false
        a2NoteInteraction()
        setClawdC("fly", 0)
        if (floor != 0.0) {
            floor = 0.0
            pile.bigSat(null)
        }
        val seats = { pile.trackSeats() }
        val x0 = x
        fun centerAt(xv: Double) = x0 + (xv - x0) + 24.0
        // seats 的 cx 在 pile 坐标系（band 左缘原点），runtime.x 是大爪右缘偏移坐标系：
        // 统一换算成大爪中心：seatsCx - bandRigOrigin（大爪左边 = bandW - RIG_W + x）
        fun seatUnder(xv: Double) = seats().filter { abs((xv + bandLeftOffset) - it.cx) < maxOf(48.0, it.w) * .5 }.maxByOrNull { it.h }
        ballisticSteps = Ballistic(
            my = my, P = P, bx = bx, by = by, bounces = bounces, drop = drop,
            centerAt = ::centerAt, seatUnder = ::seatUnder, x0 = x0,
        )
        flyingActive = true
    }

    private class Ballistic(
        val my: Int, val P: Phys, var bx: Double, var by: Double, var bounces: Int,
        val drop: Boolean, val centerAt: (Double) -> Double,
        val seatUnder: (Double) -> SeatInfo?, val x0: Double,
    ) {
        var last = SystemClock.elapsedRealtime()
    }

    private var ballisticSteps: Ballistic? = null

    /** band 左缘在 window 坐标里的 x（dp），seat cx 在 pile 本地坐标 → 统一到本侧坐标轴用。 */
    var bandLeftOffset = 0.0

    data class SeatInfo(val cx: Double, val h: Double, val w: Double, val name: String)

    private fun stepPhysics(dt: Float) {
        val bal = ballisticSteps ?: return
        if (bal.my != flying) { ballisticSteps = null; return }
        val P = bal.P
        val stamp = now()
        val k = if (bal.last > 0) ((stamp - bal.last) / 16.6667).coerceIn(0.5, 3.0) else 1.0
        bal.last = stamp
        bal.by += P.g * k
        bal.bx *= Math.pow(P.drag, k)
        bal.by *= Math.pow(P.drag, k)
        x += bal.bx * k
        fy += bal.by * k
        val clamped = a2ClampX(x)
        if (clamped != x) {
            x = clamped
            bal.bx = .45 * -bal.bx
        }
        if (fy < bnd.miny) {
            fy = bnd.miny
            bal.by = .45 * -bal.by
        }
        val seat = if (bal.by > 0) bal.seatUnder(x) else null
        val ground = seat?.h?.let { -it } ?: 0.0
        if (fy >= ground) {
            fy = ground
            if (bal.bounces == 0) setClawdC("land", 0)
            if (!(abs(bal.by) > .7 && bal.bounces < 6)) {
                bal.bx = 0.0; bal.by = 0.0
                rot0 = 0.0; sqx = 1.0; sqy = 1.0
                floor = ground
                pile.bigSat(seat?.name)
                homeX = x
                if (seat != null) x += seat.cx - bal.centerAt(x)
                else {
                    val bl = bal.centerAt(x) - 24
                    for (b in pile.blocks()) if (bl < b.right + 4 && b.left < bl + RIG_W + 4) {
                        x += if (bl + 24 < (b.left + b.right) / 2) b.left - 4 - (bl + RIG_W) else b.right + 4 - bl
                        break
                    }
                }
                if ((seat != null && x != homeX) || (seat == null && (x > bnd.maxxHome || x < bnd.minxHome || x != homeX))) {
                    x = x.coerceIn(bnd.minxHome, bnd.maxxHome)
                    homeX = x
                }
                scheduleBoundsWarm()
                if (!bal.drop) {
                    scope.launch {
                        delay(840)
                        if (flying != bal.my) return@launch
                        when {
                            throws >= 5 -> a2SulkSeq()
                            throws >= 3 -> setClawdC("stomp", (rig.clips[pickVariant("stomp")]?.dur ?: 1200).toLong())
                            else -> setClawdC(null)
                        }
                    }
                }
                ballisticSteps = null
                flyingActive = false
                onChanged?.invoke()
                return
            }
            bal.by = -bal.by * P.rest
            bal.bx *= P.fric
            bal.bounces += 1
            val q = P.sq * minOf(1.0, abs(bal.by) / 7)
            sqx = 1 + q; sqy = 1 - q
            squashTimer?.cancel()
            val myBounce = bal.my
            squashTimer = scope.launch {
                delay(110)
                squashTimer = null
                if (flying != myBounce) return@launch
                sqx = 1.0; sqy = 1.0
                onChanged?.invoke()
            }
        }
        rot0 = (-bal.bx * P.spin).coerceIn(-P.maxRot, P.maxRot)
        onChanged?.invoke()
    }

    private fun a2Walls(): Bounds {
        // x 轴 = 相对原位的偏移（0 = 输入框右内缘），与原版 a2Walls 的 offset 语义一致。
        val fallback = Bounds()
        val chat = chatRect ?: return fallback
        if (chat.width <= 0 || chat.height <= 0) return fallback
        val form = formRect ?: return fallback
        // 输入框右内缘在 window 里的位置：大爪原位左边 = form.right - RIG_W（钉右内缘）
        // 屏幕坐标 → 相对右内缘（homeL）的偏移；边界值本身已含位移量，与原版一致
        val homeL = form.right - RIG_W
        val chatL = chat.left + A2_EDGE - homeL   // 聊天区左缘 → offset
        val chatR = chat.right - A2_EDGE - homeL  // 聊天区右缘 → offset
        val formL = maxOf(chat.left, form.left + A2_FORM_INSET) - homeL
        val formR = minOf(chat.right, form.right - A2_FORM_INSET) - homeL
        // fy<0 往上升；天花板 = 聊天区上沿距地面的偏移
        val ceiling = -((form.top - chat.top) + A2_EDGE).toDouble()
        // 原版边界是绝对 px（把 x 与屏幕坐标直接比较）；本侧 x 也是偏移轴，换算：
        // minx/maxx = 聊天区边缘的偏移值；minxHome/maxxHome = 输入框内自由区偏移值
        return Bounds(
            minx = chatL,
            maxx = chatR - RIG_W,
            maxxHome = minOf(0.0, formR - RIG_W),
            minxHome = formL,
            miny = ceiling,
            maxy = 0.0,
        )
    }

    private fun a2ClampX(v: Double) = v.coerceIn(bnd.minx, bnd.maxx)

    private fun scheduleBoundsWarm() {
        bndReady = false
        if (bndRaf != null) return
        bndRaf = scope.launch {
            delay(32)
            bndRaf = null
            if (!held) {
                bnd = a2Walls(); bndAt = now(); bndReady = true
            }
        }
    }

    private fun snapshotProps() {
        // 原版 clawdRigSnapshotProps 存下被干扰前的 transform，以便结束恢复；
        // Compose 侧物理状态机自含，不需要快照 DOM。
    }

    private fun a2NoteInteraction() {
        lastPokeAt = now()
        if (neglected) setNeglected(false)
        noteActivity()
    }

    private fun clawdPet(): Boolean {
        if (a2Locked()) return false
        val t = now()
        if (t < petUntil) return false
        petUntil = t + (rig.clips["pet"]?.dur ?: 1200)
        lastPokeAt = t
        if (neglected) setNeglected(false)
        irr = 0
        setClawdC("pet", (rig.clips["pet"]?.dur ?: 1200).toLong())
        return true
    }

    private fun a2Poke() {
        if (a2Locked()) return
        if (wokeOnDown) {
            wokeOnDown = false
            setClawdC(null)
            pokeReaction()
            return
        }
        lastDec = now()
        a2NoteInteraction()
        if (trackA != null) {
            a2RandomPoke(1)
            pokeReaction()
            return
        }
        irr = minOf(5, irr + 1)
        val tier = irr
        if (tier <= A2_RICH_TIER) {
            a2RandomPoke(tier)
            pokeReaction()
            return
        }
        if (tier >= 5) a2SulkSeq() else a2RandomPoke(tier)
    }

    private fun a2RandomPoke(irr: Int) {
        val pool = (if (irr >= 3) listOf("t2", "t3", "t4") else listOf("t1", "t2", "t3", "t4"))
            .filter { it != lastPoke }
        val t = pool[random.nextInt(pool.size)]
        lastPoke = t
        setClawdC(t, A2_TMS[A2_TIER.indexOf(t)].toLong())
    }

    private var lastPoke = ""

    private fun pokeReaction() {
        lastPokeAt = now()
        if (neglected) setNeglected(false)
    }

    private fun a2SulkSeq() {
        throws = 0
        if (pickVariant("sulk") == "rage") a2PlaySeq(listOf("rage" to (rig.clips["rage"]?.dur ?: 1200).toLong()), "生气")
        else a2PlaySeq(listOf("turn" to 400L, "t5" to 2600L, "face" to 400L), "生气")
    }

    private fun pickVariant(key: String): String {
        val pool = VARIANTS[key]?.filter { rig.clips[it] != null } ?: return key
        val v = pool[random.nextInt(pool.size)]
        clipVariants[key] = v
        return v
    }

    private fun a2PlaySeq(steps: List<Pair<String, Long>>, name: String) {
        val my = ++seqRun
        val total = steps.sumOf { it.second }
        lockUntil = now() + total
        lockName = name
        fun run(index: Int) {
            if (my != seqRun) return
            if (index >= steps.size) {
                lockUntil = 0; lockName = ""; irr = 0
                seqOwned = true; setClawdC(null); seqOwned = false
                return
            }
            seqOwned = true; setClawdC(steps[index].first, 0); seqOwned = false
            scope.launch { delay(steps[index].second); run(index + 1) }
        }
        run(0)
    }

    // ═══════════════════ runtimeTick ═══════════════════

    private fun tick() {
        val t = now()
        val inGen = trackA == "think" || trackA == "stream" || trackA == "sit"
        if (inGen && generationEventActive && t - genStartedAt >= CLAWD_GEN_MAX_MS) settleGeneration("stopped")
        else if (trackA == "think" && generationEventActive && t - aStartedAt >= 900) setClawdA("stream")
        else if (trackA == "stream" && generationEventActive && t - aStartedAt >= CLAWD_TIRED_SIT_MS) {
            trackA = "sit"
            renderTracks()
        } else if (aUntil != 0L && t >= aUntil) {
            if (trackA in listOf("think", "stream", "sit")) {
                if (!generationEventActive) settleGeneration("done")
            } else setClawdA(null)
        }
        if (bUntil != 0L && t >= bUntil) {
            bUntil = 0
            syncBState()
        }
        if (cUntil != 0L && t >= cUntil) setClawdC(null)
        if (irr > 0 && t - lastDec > 1e3 && !a2Locked()) {
            lastDec = t
            irr = maxOf(0, irr - 1)
        }
        if (lockUntil != 0L && !a2Locked()) {
            lockUntil = 0; lockName = ""
        }
        if (throws > 0 && t - lastThrow > 15e3) throws = 0
        if (t - lastIdleTickAt >= 5e3) {
            lastIdleTickAt = t
            refreshIdleSleep()
        }
        stepWalk(t)
        maybePlayAmbient(t)
    }

    private var lastIdleTickAt = 0L

    // ═══════════════════ 帧同步（渲染层调）═══════════════════

    /** 渲染层每帧调：返回当前应渲染的视觉状态（按优先级 A→C→B 选 clip）。 */
    fun visualAt(nowMs: Long): Visual {
        val clip = when {
            trackC != null -> resolveClip(trackC!!, 'C')
            trackA != null -> resolveClip(trackA!!, 'A')
            trackB != "idle" -> resolveClip(trackB, 'B')
            else -> resolveClip("idle", 'B')
        }
        val mirror = clip.endsWith("-m") || clip.startsWith("rig:walkLoop-m")
        val clipId = clip.removePrefix("rig:").removeSuffix("-m")
        val flex = rig.clips[clipId]?.let { ClawdSampler.flexAt(it, clipElapsedMs(clipId, nowMs)) } ?: FlexValue.IDENTITY
        return Visual(
            clip = clipId,
            dx = x.roundToInt(),
            dy = fy.roundToInt(),
            rot = rot0.toFloat(),
            sx = sqx.toFloat(),
            sy = sqy.toFloat(),
            flex = flex,
            mirror = mirror,
            timerText = timerText,
            night = night,
        )
    }

    private fun resolveClip(track: String, owner: Char): String {
        return when (owner) {
            'C' -> {
                if (track == "t1" && trackC != "press") {
                    if (pokeT1 != "poke1" && trackC != "t1") pokeT1 = "poke1"
                    else if (trackC == "t1") pokeT1 = if (random.nextDouble() < .34) "poke1Shy" else "poke1"
                }
                val table = mapOf(
                    "press" to "press", "grab" to "grab", "drag" to clipVariants.getValue("drag"),
                    "fly" to "fly", "land" to "land", "stomp" to clipVariants.getValue("stomp"),
                    "pet" to "pet", "t2" to "poke2", "t3" to "poke3", "t4" to "poke4",
                    "turn" to "sulk", "t5" to "sulk", "face" to "sulk", "rage" to "rage", "t1" to pokeT1,
                )
                table[track] ?: track
            }
            'A' -> (if (floor != 0.0) A_HELD else A_MAP)[track] ?: track
            'B' -> track
            else -> track
        }
    }

    fun clipElapsedMs(clipId: String, nowMs: Long): Int {
        val c = rig.clips[clipId] ?: return 0
        val since = if (trackA != null && clipId == (A_MAP[trackA] ?: "")) nowMs - aStartedAt
            else nowMs - (if (trackC != null) cUntil - (rig.clips[clipId]?.dur ?: 0) else bUntil - (rig.clips[clipId]?.dur ?: 0))
        return (since % maxOf(1, c.dur)).toInt()
    }

    // ═══════════════════ 形态快照（渲染层调）═══════════════════

    /** 当前 clip id（按优先级）。渲染层只负责把它采出来。 */
    fun currentClipId(): String {
        val track = trackC ?: trackA ?: trackB
        return resolveClip(track, if (trackC != null) 'C' else if (trackA != null) 'A' else 'B')
            .removePrefix("rig:").removeSuffix("-m")
    }

    /** 采样该 clip 在 t 时刻的状态，渲染层把它变成像素。 */
    fun sampleClip(clipId: String, t: Int): ClawdSampler.RigState {
        val clip = rig.clips[clipId] ?: return ClawdSampler.RigState(emptyMap(), 0.0, 0.0)
        return ClawdSampler.sampleState(rig, clip, t)
    }

    /** 该 clip 是否镜像（rig:xxx-m） */
    fun currentMirror(): Boolean {
        val track = trackC ?: trackA ?: trackB
        return track.endsWith("-m")
    }

    // ── 边界矩形（调用方填，单位 dp）──
    var formRect: RectF? = null
    var chatRect: RectF? = null
    /** 大爪原位（x=0）时的左边距（dp）：钉在输入框右内缘（原版 .clawd-composer-clawd 钉右侧）。 */
    var homeLeftDp: Float = 0f

    data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    companion object {
        private const val RIG_W = 48.0      // rig 视觉宽度 dp（16格 x px=3）
        private const val RIG_H = 42.0      // rig 视觉高度 dp（14格 x px=3）
        private const val A2_DRAG_THRESHOLD = 5f
        private const val A2_EDGE = 6
        private const val A2_FORM_INSET = 8
        private const val A2_FALLBACK_CEILING = 240.0
        private const val A2_BOUNDS_TTL = 800L
        private const val CLAWD_RIG_CELL = 3
        private const val CLAWD_PET_HOLD_MS = 600L
        private const val CLAWD_SCROLL_REST_MS = 700L
        private const val IDLE_SLEEP_MS = 180_000L
        private const val NEGLECT_POKE_MS = 60_000L
        private const val CLAWD_NEGLECT_SHOW_MS = 6_800L
        private const val CLAWD_NEGLECT_REPEAT_MS = 240_000L
        private const val DOZE_OFF_MS = 1_900L
        private const val CLAWD_TIRED_SIT_MS = 12_000L
        private const val CLAWD_GEN_MAX_MS = 300_000L
        private const val GEN_TIMER_LINGER_MS = 4_000L
        private const val RUNTIME_TICK_MS = 200L
        private const val FRAME_MS = 33L

        private val A2_TIER = listOf("t1", "t2", "t3", "t4", "t5")
        private val A2_TMS = listOf(600, 700, 700, 1100, 0)
        private const val A2_RICH_TIER = 1

        private val A_MAP = mapOf("think" to "write", "stream" to "write", "sit" to "sitWrite",
            "done" to "done", "stopped" to "stopped", "error" to "error")
        private val A_HELD = mapOf("think" to "writeHeld", "stream" to "writeHeld", "sit" to "writeHeld",
            "done" to "doneHeld", "stopped" to "stoppedHeld", "error" to "errorHeld")

        private val VARIANTS = mapOf(
            "drag" to listOf("drag", "dragSwing"),
            "stomp" to listOf("stomp", "stompSlap"),
            "sulk" to listOf("sulk", "rage"),
        )

        private val QUIET_WITH_RIDERS = setOf("idle", "peek", "peekOut", "walkLoop", "around", "point",
            "scratch", "facepalm", "press", "neglected", "drowsy", "sleep", "compose",
            "think", "stream", "sit", "stopped", "error")

        private val ON_COMPOSER_ONLY = setOf("rickroll", "plant")

        private data class Ambient(val id: String, val weight: Double, val mealWeight: Double = 0.0, val cool: Long)
        private val AMBIENT_POOL = listOf(
            Ambient("polish", 3.0, cool = 240_000L),
            Ambient("walk", 3.0, cool = 120_000L),
            Ambient("butterfly", 1.0, cool = 600_000L),
            Ambient("plant", 1.0, cool = 600_000L),
            Ambient("eat", .3, mealWeight = 3.0, cool = 1_800_000L),
            Ambient("onFire", .5, cool = 900_000L),
            Ambient("glowstick", .5, cool = 600_000L),
            Ambient("glowstick2", .5, cool = 600_000L),
            Ambient("rickroll", .3, cool = 300_000L),
            Ambient("siren", .25, cool = 900_000L),
            Ambient("sirenBlue", .25, cool = 900_000L),
        )
        private val AMBIENT_FORMS = mapOf(
            "glowstick" to listOf("glowstick", "glowstickPump"),
            "glowstick2" to listOf("glowstick2", "glowstick2Pump", "glowstick2Alt"),
        )
        private val AMBIENT_POSES = listOf(
            "around" to 0L, "spin" to 0L, "lean" to 0L, "hide" to 0L, "tramp" to 0L,
            "scratch" to 0L, "crouch" to 0L, "heart" to 0L, "point" to 0L, "facepalm" to 0L,
        )
        private val AMBIENT_QUIET_POSES = listOf("around", "point", "scratch", "facepalm")
            .map { it to 0L }

    }
}
