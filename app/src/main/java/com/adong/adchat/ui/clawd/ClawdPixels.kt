package com.adong.adchat.ui.clawd

/**
 * 像素引擎：把字符画数据展开成品素列表。纯函数，无状态。
 * 逻辑逐行对照原版：
 * - rigFramePixels  <- clawd-rig.js pixelsOf()（329–339 行）
 * - pileCells       <- clawd-pile.js cells()（48–60 行）
 */
object ClawdPixels {

    /**
     * 大 Clawd：某部件某帧（可镜像）的全部像素。
     * 坐标 = 帧内格子 + 部件原点 + BOX 平移；镜像先以 16 格框中线翻转（x' = 15 - x'）再加 BOX。
     */
    fun rigFramePixels(rig: RigData, part: String, frame: String?, mirror: Boolean): List<ClawdPixel> {
        if (frame == null) return emptyList()
        val sheet = rig.sprites[rig.sheetOf(part)]
            ?: throw IllegalStateException("未知精灵表: ${rig.sheetOf(part)}")
        val fr = sheet[frame] ?: throw IllegalStateException("没有这一帧: $part.$frame")
        val origin = rig.origin[part] ?: throw IllegalStateException("未知部件: $part")
        val out = ArrayList<ClawdPixel>()
        for ((y, row) in fr.rows.withIndex()) {
            for ((x, ch) in row.withIndex()) {
                if (ch == '.' || ch == ' ') continue
                val colorSpec = rig.pal[ch.toString()]
                    ?: throw IllegalStateException("调色板缺字符 '$ch' ($part.$frame)")
                val cx = origin.first + fr.ox + x
                val cy = origin.second + fr.oy + y
                val fx = if (mirror) 15 - cx else cx
                out.add(ClawdPixel(fx + rig.boxX, cy + rig.boxY, ClawdLoader.colorArgb(colorSpec)))
            }
        }
        out.sortWith(compareBy({ it.y }, { it.x }))
        return out
    }

    /**
     * 小 Clawd：一只在指定形态下的全部像素（22x18 格内）。
     * 同格重复时后写的颜色覆盖先写的（与原版 DOM 叠放顺序一致）。
     */
    fun pileCells(pile: PileData, name: String, eyes: PileEyes, pose: PilePose, face: Int): List<ClawdPixel> {
        val up = when {
            pose == PilePose.CROUCH -> 0
            pose == PilePose.DANGLE_A || pose == PilePose.DANGLE_B -> 3
            else -> 2
        }
        val by = pile.top + 7 - up
        val cells = ArrayList<Triple<Int, Int, Char>>()
        if (name == "cowboy") cells.addAll(grid(pile.cowboy, 0, by - 4))
        if (name == "wizard") cells.addAll(grid(pile.wizard, 0, by - 7))
        var body = grid(pile.body, 0, by)
        if (face != 0) {
            val dark = if (face > 0) 2 else 13
            body = body.map { (x, y, ch) -> Triple(x, y, if (x == dark) 's' else ch) }
        }
        cells.addAll(body)
        if (pose == PilePose.STAND || pose == PilePose.DANGLE_A || pose == PilePose.DANGLE_B) {
            cells.addAll(grid(pile.legs.getValue(pose.key), 0, by + 8))
        }
        if (name == "shades") {
            if (eyes == PileEyes.OPEN) {
                cells.addAll(spans(pile.eyes.getValue("open"), face, by))
                cells.addAll(spans(pile.glasses.getValue("down"), face, by))
            } else {
                cells.addAll(spans(pile.glasses.getValue("on"), face, by))
            }
        } else {
            cells.addAll(spans(pile.eyes.getValue(eyes.name.lowercase()), face, by))
        }
        if (name == "wizard") {
            val wand = if (eyes == PileEyes.OPEN) pile.wand.getValue("shine") else pile.wand.getValue("rest")
            cells.addAll(spans(wand, 15, by))
        }
        val pal = pile.pal
        val merged = LinkedHashMap<Long, Int>()
        for ((x, y, ch) in cells) {
            val colorSpec = pal[ch.toString()]
                ?: throw IllegalStateException("小只调色板缺字符 '$ch' ($name/$eyes/$pose/$face)")
            merged[(y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)] = ClawdLoader.colorArgb(colorSpec)
        }
        val out = merged.entries.map { (key, argb) ->
            ClawdPixel((key and 0xFFFFFFFFL).toInt(), (key shr 32).toInt(), argb)
        }.toMutableList()
        out.sortWith(compareBy({ it.y }, { it.x }))
        return out
    }

    /** 字符画 -> 像素（'.' 与 ' ' 透明），ox/oy 平移 */
    private fun grid(rows: List<String>, ox: Int, oy: Int): List<Triple<Int, Int, Char>> {
        val out = ArrayList<Triple<Int, Int, Char>>()
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch -> if (ch != '.') out.add(Triple(x + ox, y + oy, ch)) }
        }
        return out
    }

    /** 坐标串列表 [[x, y, "串"], ...] -> 像素，串内第 i 个字符落在 (x+i, y) */
    private fun spans(
        list: List<Triple<Int, Int, String>>,
        ox: Int,
        oy: Int,
    ): List<Triple<Int, Int, Char>> {
        val out = ArrayList<Triple<Int, Int, Char>>()
        for ((x, y, s) in list) {
            s.forEachIndexed { i, ch -> if (ch != '.') out.add(Triple(x + i + ox, y + oy, ch)) }
        }
        return out
    }
}
