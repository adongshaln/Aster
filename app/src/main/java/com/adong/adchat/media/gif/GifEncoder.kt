package com.adong.adchat.media.gif

import java.io.OutputStream
import kotlin.math.max

/**
 * GIF 调色板：最多 256 项 ARGB 颜色。
 * 颜色按索引精确或最近邻匹配（供 LZW 前把像素映射到索引）。
 */
class GifPalette(val colors: IntArray) {

    val size: Int get() = colors.size
    private val exactIndices = HashMap<Int, Int>(colors.size)
    private val nearestColorTree: NearestColorNode?

    init {
        require(colors.isNotEmpty()) { "调色板不能为空" }
        require(colors.size <= 256) { "GIF 调色板最多 256 色，实际 ${colors.size}" }
        colors.forEachIndexed { index, color -> exactIndices.putIfAbsent(color, index) }
        nearestColorTree = buildTree(colors.indices.toList(), depth = 0)
    }

    /**
     * 返回 [argb] 对应的调色板索引：先做精确匹配，否则按 RGB 平方距离最近邻匹配。
     * Alpha 不参与最近邻比较（GIF 不支持半透明，只有索引级单色透明）。
     */
    fun indexFor(argb: Int): Int = indexFor(argb, IntArray(2))

    internal fun indexFor(argb: Int, searchState: IntArray): Int {
        exactIndices[argb]?.let { return it }
        val targetR = (argb ushr 16) and 0xFF
        val targetG = (argb ushr 8) and 0xFF
        val targetB = argb and 0xFF
        searchState[0] = 0
        searchState[1] = Int.MAX_VALUE
        nearestColorTree?.let { search(it, targetR, targetG, targetB, searchState) }
        return searchState[0]
    }

    private data class NearestColorNode(val index: Int, val axis: Int, val left: NearestColorNode?, val right: NearestColorNode?)

    private fun buildTree(indices: List<Int>, depth: Int): NearestColorNode? {
        if (indices.isEmpty()) return null
        val axis = depth % 3
        val sorted = indices.sortedBy { channel(colors[it], axis) }
        val middle = sorted.size / 2
        return NearestColorNode(
            index = sorted[middle],
            axis = axis,
            left = buildTree(sorted.subList(0, middle), depth + 1),
            right = buildTree(sorted.subList(middle + 1, sorted.size), depth + 1)
        )
    }

    private fun search(node: NearestColorNode?, r: Int, g: Int, b: Int, best: IntArray) {
        if (node == null) return
        val color = colors[node.index]
        val dr = r - ((color ushr 16) and 0xFF)
        val dg = g - ((color ushr 8) and 0xFF)
        val db = b - (color and 0xFF)
        val distance = dr * dr + dg * dg + db * db
        if (distance < best[1]) {
            best[0] = node.index
            best[1] = distance
        }

        val delta = when (node.axis) {
            0 -> dr
            1 -> dg
            else -> db
        }
        val near = if (delta <= 0) node.left else node.right
        val far = if (delta <= 0) node.right else node.left
        search(near, r, g, b, best)
        if (delta * delta < best[1]) search(far, r, g, b, best)
    }

    private fun channel(color: Int, axis: Int): Int = when (axis) {
        0 -> (color ushr 16) and 0xFF
        1 -> (color ushr 8) and 0xFF
        else -> color and 0xFF
    }
}

/** 量化结果：调色板 + 每像素对应的调色板索引。 */
data class GifQuantizedFrame(
    val palette: GifPalette,
    /** 每像素对应的调色板索引，与输入像素同长度 */
    val indices: ByteArray
)

/**
 * 中位切分量化器：把一帧 ARGB 像素量化到 [maxColors] 色内，可选 Floyd–Steinberg 误差扩散抖动。
 * 首帧不传 [GifColorQuantizer.quantize] 的 existing 参数以新建全局调色板；
 * 后续帧传入既有调色板则仅做映射不重建，减少帧间颜色漂移与局部色表开销。
 */
class GifColorQuantizer {

    fun quantize(
        pixels: IntArray,
        maxColors: Int,
        dither: Boolean,
        existing: GifPalette? = null,
        rowWidth: Int = pixels.size
    ): GifQuantizedFrame {
        require(maxColors in 2..256) { "maxColors 须在 2..256，实际 $maxColors" }
        require(pixels.isNotEmpty()) { "像素数组不能为空" }
        require(rowWidth > 0 && pixels.size % rowWidth == 0) { "行宽 $rowWidth 与像素数量 ${pixels.size} 不匹配" }
        // 有既有调色板时仅做映射，不重建，保证帧间调色板一致
        return if (existing != null) {
            GifQuantizedFrame(existing, mapToPalette(pixels, existing, dither, rowWidth))
        } else {
            val palette = buildMedianCutPalette(pixels, maxColors)
            GifQuantizedFrame(palette, mapToPalette(pixels, palette, dither, rowWidth))
        }
    }

    // ── 中位切分 ──────────────────────────────────────────────

    /** 直方图条目：一个 distinct 颜色及其出现次数 */
    private class HistogramEntry(val argb: Int, val count: Int) {
        val r: Int get() = (argb ushr 16) and 0xFF
        val g: Int get() = (argb ushr 8) and 0xFF
        val b: Int get() = argb and 0xFF
    }

    /** 颜色盒：直方图的闭区间 [start, end)，记录最长颜色通道作为切分依据 */
    private class ColorBox(val start: Int, val end: Int) {
        var longestChannel = -1
        var longestRange = -1

        /** 计算区间内最长的颜色通道（绿色权重略高，人眼对绿色最敏感） */
        fun measure(entries: List<HistogramEntry>) {
            var rMin = 255; var rMax = 0
            var gMin = 255; var gMax = 0
            var bMin = 255; var bMax = 0
            for (i in start..end) {
                val e = entries[i]
                if (e.r < rMin) rMin = e.r
                if (e.r > rMax) rMax = e.r
                if (e.g < gMin) gMin = e.g
                if (e.g > gMax) gMax = e.g
                if (e.b < bMin) bMin = e.b
                if (e.b > bMax) bMax = e.b
            }
            val rRange = rMax - rMin
            val gRange = gMax - gMin
            val bRange = bMax - bMin
            longestChannel = when {
                gRange * 3 >= rRange * 2 && gRange * 3 >= bRange -> 1
                rRange * 2 >= bRange -> 0
                else -> 2
            }
            longestRange = when (longestChannel) {
                0 -> rRange
                1 -> gRange
                else -> bRange
            }
        }
    }

    /** 对像素做直方图统计，返回按颜色值升序排序的条目列表 */
    private fun buildHistogram(pixels: IntArray): MutableList<HistogramEntry> {
        val map = HashMap<Int, Int>()
        for (p in pixels) map[p] = (map[p] ?: 0) + 1
        val list = map.entries.map { HistogramEntry(it.key, it.value) }.toMutableList()
        list.sortBy { it.argb }
        return list
    }

    /** 中位切分：把直方图反复切分到 [maxColors] 个盒，每盒取像素数加权平均色 */
    private fun buildMedianCutPalette(pixels: IntArray, maxColors: Int): GifPalette {
        val entries = buildHistogram(pixels)
        // distinct 颜色数不超过 maxColors 时全部保留，无需切分
        if (entries.size <= maxColors) {
            return GifPalette(IntArray(entries.size) { entries[it].argb })
        }
        val boxes = ArrayList<ColorBox>()
        boxes.add(ColorBox(0, entries.size - 1))
        while (boxes.size < maxColors) {
            // 找出最长通道范围最大且可切分的盒
            var bestIdx = -1
            var bestRange = 0
            for (i in boxes.indices) {
                val box = boxes[i]
                box.measure(entries)
                if (box.longestRange > bestRange && box.end > box.start) {
                    bestRange = box.longestRange
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break // 已无可切分的盒
            val box = boxes[bestIdx]
            // 按最长通道对该区间重排序
            when (box.longestChannel) {
                0 -> entries.subList(box.start, box.end + 1).sortBy { it.r }
                1 -> entries.subList(box.start, box.end + 1).sortBy { it.g }
                else -> entries.subList(box.start, box.end + 1).sortBy { it.b }
            }
            // 按像素计数找中位切分点，保证两侧均非空
            val total = (box.start..box.end).sumOf { entries[it].count }
            var acc = 0
            var split = box.start
            for (i in box.start until box.end) {
                acc += entries[i].count
                if (acc * 2 >= total) {
                    split = i
                    break
                }
            }
            boxes.removeAt(bestIdx)
            boxes.add(ColorBox(box.start, split))
            boxes.add(ColorBox(split + 1, box.end))
        }
        // 每盒按出现次数加权求平均色
        val colors = IntArray(boxes.size)
        for ((i, box) in boxes.withIndex()) {
            var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0L
            for (j in box.start..box.end) {
                val e = entries[j]
                rSum += e.r.toLong() * e.count
                gSum += e.g.toLong() * e.count
                bSum += e.b.toLong() * e.count
                n += e.count
            }
            val r = (rSum / n).toInt().coerceIn(0, 255)
            val g = (gSum / n).toInt().coerceIn(0, 255)
            val b = (bSum / n).toInt().coerceIn(0, 255)
            colors[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        return GifPalette(colors)
    }

    // ── 映射（含可选 Floyd–Steinberg 抖动） ───────────────────

    /** 把像素映射到调色板索引；开启 [dither] 时使用 Floyd–Steinberg 误差扩散 */
    private fun mapToPalette(pixels: IntArray, palette: GifPalette, dither: Boolean, rowWidth: Int): ByteArray {
        return if (dither) mapWithDithering(pixels, palette, rowWidth) else mapDirect(pixels, palette)
    }

    /** 直接最近邻映射，无抖动 */
    private fun mapDirect(pixels: IntArray, palette: GifPalette): ByteArray {
        val indices = ByteArray(pixels.size)
        val searchState = IntArray(2)
        for (i in pixels.indices) {
            indices[i] = palette.indexFor(pixels[i], searchState).toByte()
        }
        return indices
    }

    /** Floyd–Steinberg diffusion with row-aware error buffers; memory stays O(width). */
    private fun mapWithDithering(pixels: IntArray, palette: GifPalette, rowWidth: Int): ByteArray {
        val indices = ByteArray(pixels.size)
        var currentR = FloatArray(rowWidth + 2)
        var currentG = FloatArray(rowWidth + 2)
        var currentB = FloatArray(rowWidth + 2)
        var nextR = FloatArray(rowWidth + 2)
        var nextG = FloatArray(rowWidth + 2)
        var nextB = FloatArray(rowWidth + 2)
        val searchState = IntArray(2)

        for (y in pixels.indices step rowWidth) {
            for (x in 0 until rowWidth) {
                val position = y + x
                val errorPosition = x + 1
                val argb = pixels[position]
                val r = (((argb ushr 16) and 0xFF) + currentR[errorPosition]).coerceIn(0f, 255f)
                val g = (((argb ushr 8) and 0xFF) + currentG[errorPosition]).coerceIn(0f, 255f)
                val b = ((argb and 0xFF) + currentB[errorPosition]).coerceIn(0f, 255f)
                val candidate = 0xFF000000.toInt() or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
                val index = palette.indexFor(candidate, searchState)
                indices[position] = index.toByte()
                val color = palette.colors[index]
                val errorR = r - ((color ushr 16) and 0xFF)
                val errorG = g - ((color ushr 8) and 0xFF)
                val errorB = b - (color and 0xFF)

                currentR[errorPosition + 1] += errorR * (7f / 16f)
                currentG[errorPosition + 1] += errorG * (7f / 16f)
                currentB[errorPosition + 1] += errorB * (7f / 16f)
                if (x > 0) {
                    nextR[errorPosition - 1] += errorR * (3f / 16f)
                    nextG[errorPosition - 1] += errorG * (3f / 16f)
                    nextB[errorPosition - 1] += errorB * (3f / 16f)
                }
                nextR[errorPosition] += errorR * (5f / 16f)
                nextG[errorPosition] += errorG * (5f / 16f)
                nextB[errorPosition] += errorB * (5f / 16f)
                if (x + 1 < rowWidth) {
                    nextR[errorPosition + 1] += errorR * (1f / 16f)
                    nextG[errorPosition + 1] += errorG * (1f / 16f)
                    nextB[errorPosition + 1] += errorB * (1f / 16f)
                }
            }
            val oldR = currentR; currentR = nextR; nextR = oldR; nextR.fill(0f)
            val oldG = currentG; currentG = nextG; nextG = oldG; nextG.fill(0f)
            val oldB = currentB; currentB = nextB; nextB = oldB; nextB.fill(0f)
        }
        return indices
    }
}

/**
 * GIF89a 逐帧流式编码器。
 * 用法：
 * ```
 * val encoder = GifEncoder(outputStream, loopCount = 0 /* 0 = 无限循环 */)
 * encoder.writeHeader(width, height, globalPalette)
 * repeat(frames) {
 *     encoder.writeFrame(indices, delayCentis, transparentIndex = -1)
 * }
 * encoder.finish()
 * ```
 * 逐帧处理：[writeFrame] 只接收索引数组，编码器内部不做整帧大缓冲累积。
 */
class GifEncoder(
    private val output: OutputStream,
    private val log: (String) -> Unit = {},
    /** Netscape 循环次数：0 = 无限循环；负数表示不写循环扩展 */
    private val loopCount: Int = 0
) {

    private var headerWritten = false
    private var finished = false
    private var canvasWidth = 0
    private var canvasHeight = 0
    private var globalPaletteBits = 1

    /**
     * 写文件签名 + 逻辑屏幕描述符 +（可选）全局色表 +（loopCount >= 0 时）Netscape 循环扩展。
     * [globalPalette] 为 null 时不写全局色表（此时每帧须自带局部色表，当前实现不支持，一般不为 null）。
     */
    fun writeHeader(width: Int, height: Int, globalPalette: GifPalette?) {
        check(!headerWritten) { "writeHeader 只能调用一次" }
        require(width in 1..0xFFFF) { "宽 $width 超出 GIF 范围" }
        require(height in 1..0xFFFF) { "高 $height 超出 GIF 范围" }
        headerWritten = true
        canvasWidth = width
        canvasHeight = height

        // 签名 GIF89a
        for (b in "GIF89a".toByteArray(Charsets.US_ASCII)) output.write(b.toInt())

        val hasGlobal = globalPalette != null
        if (hasGlobal) globalPaletteBits = bitsForPalette(globalPalette!!.size)
        // packed：全局色表标志 | 颜色分辨率（位深-1） | 排序位 0 | 全局色表大小（位深-1）
        val packed = if (hasGlobal) 0x80 or ((globalPaletteBits - 1) shl 4) or (globalPaletteBits - 1) else 0
        // 逻辑屏幕描述符：宽、高（小端 u16）、packed、背景色索引 0、像素宽高比 0
        writeU16LE(width)
        writeU16LE(height)
        output.write(packed)
        output.write(0) // 背景色索引
        output.write(0) // 像素宽高比

        // 全局色表：每色 3 字节 RGB，数量向上取整到 2 的幂，空位补零
        if (hasGlobal) {
            val colors = globalPalette!!.colors
            for (i in 0 until (1 shl globalPaletteBits)) {
                if (i < colors.size) {
                    val c = colors[i]
                    output.write((c ushr 16) and 0xFF)
                    output.write((c ushr 8) and 0xFF)
                    output.write(c and 0xFF)
                } else {
                    output.write(0); output.write(0); output.write(0)
                }
            }
        }

        // Netscape 循环扩展：21 FF 0B "NETSCAPE2.0" 03 01 <loop u16 LE> 00
        if (loopCount >= 0) {
            output.write(0x21)
            output.write(0xFF)
            output.write(0x0B)
            for (b in "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)) output.write(b.toInt())
            output.write(0x03)
            output.write(0x01)
            writeU16LE(loopCount)
            output.write(0x00)
        }
        log("GIF 头已写入 ${width}x$height，全局色表=${if (hasGlobal) globalPalette!!.size else 0} 色，循环=$loopCount")
    }

    /**
     * 写一帧：Graphic Control Extension + Image Descriptor + LZW 压缩的图像数据。
     * [indices] 为调色板索引数组（长度须等于画布宽 × 高）；
     * [delayCentiseconds] 为帧延时（单位 1/100 秒，u16 小端）；
     * [transparentIndex] 为透明索引（-1 表示无透明）。
     */
    fun writeFrame(indices: ByteArray, delayCentiseconds: Int, transparentIndex: Int = -1) {
        check(headerWritten) { "须先调用 writeHeader" }
        check(!finished) { "finish 已调用，不能再写帧" }
        require(indices.isNotEmpty()) { "帧索引数组不能为空" }
        require(indices.size == canvasWidth * canvasHeight) {
            "帧索引数 ${indices.size} 与画布 ${canvasWidth}x$canvasHeight 不符"
        }
        require(delayCentiseconds in 0..0xFFFF) { "帧延时 $delayCentiseconds 超出 u16 范围" }
        require(transparentIndex in -1..0xFF) { "透明索引非法：$transparentIndex" }

        // ── Graphic Control Extension：21 F9 04 <packed> <delay u16 LE> <transparentIdx> 00 ──
        // packed：disposal 方法 1（不处理，直接覆盖）<<2 | 透明标志
        val gcePacked = (1 shl 2) or (if (transparentIndex >= 0) 1 else 0)
        output.write(0x21)
        output.write(0xF9)
        output.write(0x04)
        output.write(gcePacked)
        writeU16LE(delayCentiseconds)
        output.write(if (transparentIndex >= 0) transparentIndex else 0)
        output.write(0x00)

        // ── Image Descriptor：2C <x=0> <y=0> <w> <h> <packed=0x00>（用全局色表）──
        output.write(0x2C)
        writeU16LE(0) // x
        writeU16LE(0) // y
        writeU16LE(canvasWidth)
        writeU16LE(canvasHeight)
        output.write(0x00) // 无局部色表、非隔行

        // ── LZW 图像数据 ──
        val initialCodeSize = max(2, globalPaletteBits)
        // GIF 规范：图像数据以 LZW 最小码长字节开头，再接子块序列
        output.write(initialCodeSize)
        // encode 内部自带块终止符 0x00
        GifLzwEncoder(initialCodeSize, output).encode(indices)
        log("帧已写入：${indices.size} 像素，延时=$delayCentiseconds，透明索引=$transparentIndex")
    }

    /** 写 trailer（0x3B）并 flush，之后不可再写帧 */
    fun finish() {
        if (finished) return
        check(headerWritten) { "须先调用 writeHeader" }
        finished = true
        output.write(0x3B)
        output.flush()
        log("GIF 编码完成，trailer 已写入")
    }

    private fun writeU16LE(v: Int) {
        output.write(v and 0xFF)
        output.write((v ushr 8) and 0xFF)
    }

    /** 计算表示 [paletteSize] 色所需的最小位深（至少 1，最多 8） */
    private fun bitsForPalette(paletteSize: Int): Int {
        require(paletteSize in 1..256)
        var bits = 1
        while ((1 shl bits) < paletteSize) bits++
        return bits
    }

    /**
     * GIF LZW 编码器：变长码流 + 255 字节子块 + 位打包（低位在前）。
     * 码长从 initialCodeSize+1 起；分配新码前若 nextCode 已达 1 shl codeSize 则码长 +1
     * （上限 12 位，达 4096 时发清码重置字典，与 Pillow/giflib 解码器配对）。
     * 数据写完后发结束码并写子块长度序列与块终止符 0x00。
     */
    private class GifLzwEncoder(initialCodeSize: Int, private val out: OutputStream) {

        private val clearCode = 1 shl initialCodeSize
        private val eoiCode = clearCode + 1
        private var codeSize = initialCodeSize + 1
        private var nextCode = eoiCode + 1

        // 字典：(前缀码 shl 8) or 下个像素索引 -> 新码
        private val dict = HashMap<Int, Int>()

        // 位打包状态：低位在前，凑满 8 位才输出
        private var bitBuffer = 0L
        private var bitCount = 0

        // 子块缓冲（最多 255 字节）
        private val subBlock = ByteArray(255)
        private var subBlockLen = 0

        fun encode(indices: ByteArray) {
            emitCode(clearCode)
            if (indices.isEmpty()) {
                emitCodeAndFlushEoi()
                return
            }
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val pixel = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or pixel
                val found = dict[key]
                if (found != null) {
                    prefix = found
                } else {
                    emitCode(prefix)
                    if (nextCode < 4096) {
                        // 标准 GIF LZW 约定：分配新码前先判断码长增长
                        //（与 Pillow/giflib 解码器的 nextCode == 1 shl codeSize 规则配对）
                        if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                        dict[key] = nextCode
                        nextCode++
                    } else {
                        // 字典满：发清码重置
                        emitCode(clearCode)
                        resetDict()
                    }
                    prefix = pixel
                }
            }
            emitCode(prefix)
            emitCodeAndFlushEoi()
        }

        /** 发结束码，把残余位写出，并写子块长度序列与块终止符 */
        private fun emitCodeAndFlushEoi() {
            emitCode(eoiCode)
            if (bitCount > 0) {
                pushByte((bitBuffer and 0xFF).toInt())
                bitBuffer = 0L
                bitCount = 0
            }
            out.write(subBlockLen)
            out.write(subBlock, 0, subBlockLen)
            subBlockLen = 0
            out.write(0x00) // 块终止符
        }

        private fun resetDict() {
            dict.clear()
            // 码长回到初始值：initialCodeSize + 1
            codeSize = Integer.numberOfTrailingZeros(clearCode) + 1
            nextCode = eoiCode + 1
        }

        private fun emitCode(code: Int) {
            bitBuffer = bitBuffer or (code.toLong() shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                pushByte((bitBuffer and 0xFF).toInt())
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        private fun pushByte(b: Int) {
            subBlock[subBlockLen++] = b.toByte()
            if (subBlockLen == 255) {
                out.write(255)
                out.write(subBlock, 0, 255)
                subBlockLen = 0
            }
        }
    }
}
