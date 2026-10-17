package com.adong.adchat.ui.clawd

/**
 * Clawd 吉祥物数据模型（纯 JVM，无 Android 依赖）。
 * 数据来源：tools/clawd/extract.mjs 从原版 clawd-rig.js / clawd-pile.js 提取的 JSON。
 *
 * 坐标系约定：
 * - rig 像素坐标是"框内绝对坐标"：帧内格子 + 部件原点(origin) + BOX 平移，16 格宽的框中线在 7.5，
 *   镜像翻转用 x' = 15 - x'（不含 BOX，翻转后再加 BOX）。
 * - pile 像素坐标是 22x18 格内坐标，无需平移。
 */

data class SpriteFrame(val rows: List<String>, val ox: Int, val oy: Int)

/** 时间轴关键帧值：数字(x/y/z/sc/r/a)、字符串(f 帧名 / att 挂载层)或 null(f/att 隐藏) */
sealed class ClawdValue {
    data class Num(val v: Double) : ClawdValue()
    data class Str(val v: String) : ClawdValue()
    data object None : ClawdValue()
}

data class RigKeyframe(val t: Int, val v: ClawdValue, val ease: String)

data class FlexValue(val sx: Double, val sy: Double, val r: Double, val tx: Double, val ty: Double) {
    companion object {
        val IDENTITY = FlexValue(1.0, 1.0, 0.0, 0.0, 0.0)
    }
}

data class RigClip(
    val id: String,
    val name: String,
    val dur: Int,
    val loop: Boolean,
    val intro: Int,
    val track: String?,
    val group: String?,
    val next: String?,
    val hold: Boolean,
    val after: String?,
    val from: String?,
    val flexEase: String,
    val smooth: Set<String>,
    val tr: Map<String, Map<String, List<RigKeyframe>>>,
    val flexKeys: List<Pair<Int, FlexValue>>,
    val beats: List<Pair<Int, String>>,
)

data class RigData(
    val px: Int,
    val tick: Int,
    val boxX: Int,
    val boxY: Int,
    val parts: List<String>,
    val origin: Map<String, Pair<Int, Int>>,
    val restF: Map<String, String?>,
    val restZ: Map<String, Int>,
    val upperKids: Set<String>,
    val pal: Map<String, String>,
    val sprites: Map<String, Map<String, SpriteFrame>>,
    val clips: Map<String, RigClip>,
) {
    /** 部件 -> 精灵表（propA/propB 共用 prop 表，其余同名） */
    fun sheetOf(part: String): String = if (part == "propA" || part == "propB") "prop" else part
}

data class PileData(
    val px: Int,
    val w: Int,
    val h: Int,
    val top: Int,
    val pal: Map<String, String>,
    val palNight: Map<String, String>,
    val body: List<String>,
    val legs: Map<String, List<String>>,
    val eyes: Map<String, List<Triple<Int, Int, String>>>,
    val glasses: Map<String, List<Triple<Int, Int, String>>>,
    val cowboy: List<String>,
    val wizard: List<String>,
    val wand: Map<String, List<Triple<Int, Int, String>>>,
    val names: List<String>,
    val bodyW: Int,
    val lift: Int,
    val gap: Int,
    val edge: Int,
    val stepMs: Int,
    val legX: List<Int>,
)

enum class PileEyes {
    SHUT, HALF, OPEN;

    companion object {
        fun from(s: String): PileEyes = valueOf(s.uppercase())
    }
}

enum class PilePose {
    CROUCH, STAND, RAISED, DANGLE_A, DANGLE_B;

    val key: String
        get() = when (this) {
            CROUCH -> "crouch"
            STAND -> "stand"
            RAISED -> "raised"
            DANGLE_A -> "dangleA"
            DANGLE_B -> "dangleB"
        }

    companion object {
        fun from(s: String): PilePose = when (s) {
            "crouch" -> CROUCH
            "stand" -> STAND
            "raised" -> RAISED
            "dangleA" -> DANGLE_A
            "dangleB" -> DANGLE_B
            else -> throw IllegalArgumentException("未知 pose: $s")
        }
    }
}

/** 成品像素：x/y 为成品坐标（rig 已含 BOX 平移），argb 为 0xAARRGGBB */
data class ClawdPixel(val x: Int, val y: Int, val argb: Int)
