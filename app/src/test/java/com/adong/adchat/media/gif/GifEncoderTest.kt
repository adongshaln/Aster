package com.adong.adchat.media.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [GifEncoder] / [GifColorQuantizer] 的纯 JVM 单元测试，不依赖 Android 类。
 */
class GifEncoderTest {

    // ── 工具 ──────────────────────────────────────────────────

    /** 生成一帧指定宽高的索引序列（供编码用） */
    private fun frameIndices(width: Int, height: Int, paletteSize: Int): ByteArray {
        val n = width * height
        val indices = ByteArray(n)
        for (i in 0 until n) {
            indices[i] = ((i % paletteSize) + i / width % paletteSize).toInt()
                .rem(paletteSize).toByte()
        }
        return indices
    }

    /** 编码一帧并返回完整输出字节 */
    private fun encodeOneFrame(
        width: Int = 4,
        height: Int = 4,
        paletteSize: Int = 4,
        delay: Int = 0,
        loopCount: Int = 0
    ): ByteArray {
        val palette = GifPalette(IntArray(paletteSize) { 0xFF000000.toInt() or (it * 0x10101) })
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, loopCount = loopCount)
        encoder.writeHeader(width, height, palette)
        encoder.writeFrame(frameIndices(width, height, paletteSize), delay)
        encoder.finish()
        return out.toByteArray()
    }

    // ── 头部 ──────────────────────────────────────────────────

    @Test
    fun headerIsGif89a() {
        val bytes = encodeOneFrame()
        assertTrue("输出过短：${bytes.size}", bytes.size > 6)
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
    }

    // ── LZW 回环 ──────────────────────────────────────────────

    /** 测试专用的最小 GIF LZW 解码器（只解码测试用，与编码器同一套变长码约定） */
    private class MiniLzwDecoder(private val data: ByteArray, initialCodeSize: Int) {
        private val minCodeSize = initialCodeSize
        private val clearCode = 1 shl initialCodeSize
        private val eoiCode = clearCode + 1
        private var codeSize = initialCodeSize + 1
        private var prevCode = -1
        private var nextSlot = eoiCode + 1
        // 字典：码 -> 像素序列
        private val dict = Array(4096) { mutableListOf<Int>() }

        init {
            resetDict()
        }

        private fun resetDict() {
            for (i in 0 until (1 shl minCodeSize)) {
                dict[i] = mutableListOf(i)
            }
            for (i in (1 shl minCodeSize) until 4096) {
                dict[i] = mutableListOf() // 清掉上次留下的陈旧条目
            }
            codeSize = minCodeSize + 1
            prevCode = -1
            nextSlot = eoiCode + 1
        }

        fun decode(): List<Int> {
            val result = mutableListOf<Int>()
            var bitPos = 0
            var totalBits = data.size * 8
            var pendingClear = true
            while (true) {
                if (bitPos + codeSize > totalBits) break
                var code = 0
                for (i in 0 until codeSize) {
                    val bitIdx = bitPos + i
                    val bit = (data[bitIdx / 8].toInt() ushr (bitIdx % 8)) and 1
                    code = code or (bit shl i)
                }
                bitPos += codeSize
                when {
                    code == clearCode -> {
                        resetDict()
                        pendingClear = true
                    }
                    code == eoiCode -> return result
                    pendingClear -> {
                        // 清码后的第一个码：直接输出其像素序列
                        result.addAll(dict[code])
                        prevCode = code
                        pendingClear = false
                    }
                    code < dict.size && dict[code].isNotEmpty() -> {
                        result.addAll(dict[code])
                        // 建立新字典项：prev 序列 + 当前序列首像素。
                        // 解码器比编码器滞后一步；与"分配前先增长"的编码器配对，
                        // nextSlot 达到 1 shl codeSize 即增长
                        if (prevCode >= 0 && nextSlot < 4096) {
                            val entry = mutableListOf<Int>()
                            entry.addAll(dict[prevCode])
                            entry.add(dict[code].first())
                            dict[nextSlot++] = entry
                            if (nextSlot == (1 shl codeSize) && codeSize < 12) codeSize++
                        }
                        prevCode = code
                    }
                    else -> {
                        // 特殊 KwKwK 情形：码尚未在字典中
                        val entry = mutableListOf<Int>()
                        entry.addAll(dict[prevCode])
                        entry.add(dict[prevCode].first())
                        result.addAll(entry)
                        dict[nextSlot++] = entry
                        if (nextSlot == (1 shl codeSize) && codeSize < 12) codeSize++
                        prevCode = code
                    }
                }
            }
            return result
        }
    }

    /** 从完整 GIF 字节流中提取第一帧的 LZW 数据子块并拼接 */
    private fun extractLzwData(bytes: ByteArray): Pair<Int, ByteArray> {
        // 找 GCE 签名 00 21 F9 04，跳过 GCE（10 字节），再找 Image Descriptor 0x2C
        var pos = 0
        var gceIdx = -1
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0x00.toByte() && bytes[i + 1] == 0x21.toByte() &&
                bytes[i + 2] == 0xF9.toByte() && bytes[i + 3] == 0x04.toByte()
            ) {
                gceIdx = i + 1
                break
            }
        }
        assertTrue("未找到 GCE", gceIdx >= 0)
        pos = gceIdx + 8 // 21 F9 04 packed delay(2) transIdx 00 = 8 字节
        assertEquals("GCE 后应为 Image Descriptor", 0x2C.toByte(), bytes[pos].toByte())
        pos += 10 // 2C x(2) y(2) w(2) h(2) packed
        // LZW 最小码长
        val initialCodeSize = bytes[pos].toInt() and 0xFF
        pos++
        // 收集子块；带边界诊断：若真实流缺块终止符，断言输出末尾字节帮助定位
        val chunks = ArrayList<ByteArray>()
        while (true) {
            assertTrue(
                "子块循环越界：pos=$pos size=${bytes.size} " +
                    "末尾16字节=${bytes.drop((bytes.size - 16).coerceAtLeast(0)).joinToString(\" \") { String.format(\"%02X\", it) }}",
                pos < bytes.size
            )
            val len = bytes[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            assertTrue("子块声明 $len 字节但只剩 ${bytes.size - pos}", pos + len <= bytes.size)
            chunks.add(bytes.copyOfRange(pos, pos + len))
            pos += len
        }
        val all = ByteArray(chunks.sumOf { it.size })
        var off = 0
        for (c in chunks) {
            c.copyInto(all, off)
            off += c.size
        }
        return initialCodeSize to all
    }

    @Test
    fun lzwRoundTripSmallAlphabet() {
        val width = 40
        val height = 50 // 2000 像素
        val paletteSize = 4
        val bytes = encodeOneFrame(width, height, paletteSize)
        val (initialCodeSize, lzwData) = extractLzwData(bytes)
        val decoded = MiniLzwDecoder(lzwData, initialCodeSize).decode()
        val expected = frameIndices(width, height, paletteSize)
        assertEquals("解码像素数不符", expected.size, decoded.size)
        for (i in expected.indices) {
            assertEquals("像素 $i 不一致", expected[i].toInt() and 0xFF, decoded[i])
        }
    }

    // ── 量化器 ────────────────────────────────────────────────

    @Test
    fun quantizerRespectsMaxColors() {
        // 生成 1000 种随机色的像素数组
        val pixels = IntArray(1000) { i ->
            0xFF000000.toInt() or
                ((i * 7919 and 0xFF) shl 16) or
                ((i * 104729 and 0xFF) shl 8) or
                ((i * 1299709 and 0xFF))
        }
        val frame = GifColorQuantizer().quantize(pixels, maxColors = 64, dither = false)
        assertTrue("调色板超过 64 色：${frame.palette.size}", frame.palette.size <= 64)
        val distinct = frame.indices.map { it.toInt() and 0xFF }.toSet()
        assertTrue("索引去重后超过 64：${distinct.size}", distinct.size <= 64)
    }

    @Test
    fun quantizerDeterministic() {
        val pixels = IntArray(500) { i ->
            0xFF000000.toInt() or
                ((i * 31 and 0xFF) shl 16) or
                ((i * 97 and 0xFF) shl 8) or
                ((i * 13 and 0xFF))
        }
        val q = GifColorQuantizer()
        val a = q.quantize(pixels, maxColors = 32, dither = false)
        val b = q.quantize(pixels, maxColors = 32, dither = false)
        assertTrue(a.palette.colors.contentEquals(b.palette.colors))
        assertTrue(a.indices.contentEquals(b.indices))
    }

    @Test
    fun quantizerDitherStillWithinBounds() {
        val pixels = IntArray(600) { i ->
            0xFF000000.toInt() or
                ((i * 53 and 0xFF) shl 16) or
                ((i * 181 and 0xFF) shl 8) or
                ((i * 7 and 0xFF))
        }
        val frame = GifColorQuantizer().quantize(pixels, maxColors = 16, dither = true)
        for (i in frame.indices.indices) {
            val idx = frame.indices[i].toInt() and 0xFF
            assertTrue("索引 $idx 越界（位置 $i）", idx in 0 until frame.palette.size)
        }
    }

    // ── 帧/块结构 ─────────────────────────────────────────────

    @Test
    fun frameDelayIsLittleEndian() {
        val bytes = encodeOneFrame(delay = 123)
        // 扫描 GCE 签名 00 21 F9 04
        var gce = -1
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0x00.toByte() && bytes[i + 1] == 0x21.toByte() &&
                bytes[i + 2] == 0xF9.toByte() && bytes[i + 3] == 0x04.toByte()
            ) {
                gce = i
                break
            }
        }
        assertTrue("未找到 GCE 签名", gce >= 0)
        // 签名起：00 21 F9 04 packed delayLo delayHi transIdx term
        assertEquals(0x7B.toByte(), bytes[gce + 5]) // delay 低字节
        assertEquals(0x00.toByte(), bytes[gce + 6]) // delay 高字节
    }

    @Test
    fun trailerWritten() {
        val bytes = encodeOneFrame()
        assertTrue("输出为空", bytes.isNotEmpty())
        assertEquals(0x3B.toByte(), bytes[bytes.size - 1])
    }

    @Test
    fun netscapeLoopExtensionPresent() {
        val bytes = encodeOneFrame(loopCount = 0)
        val sig = "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)
        var found = false
        outer@ for (i in 0..bytes.size - sig.size) {
            for (j in sig.indices) {
                if (bytes[i + j] != sig[j]) continue@outer
            }
            found = true
            // 应用块：03 01 <loop u16 LE> 00
            assertEquals(0x03.toByte(), bytes[i + sig.size])
            assertEquals(0x01.toByte(), bytes[i + sig.size + 1])
            assertEquals(0x00.toByte(), bytes[i + sig.size + 2]) // loop 低字节（无限循环）
            assertEquals(0x00.toByte(), bytes[i + sig.size + 3]) // loop 高字节
            break
        }
        assertTrue("未找到 NETSCAPE2.0 循环扩展", found)
    }

    @Test
    fun palettePaddedToPowerOfTwo() {
        // 3 色调色板 -> 色表 4 项 12 字节，packed = 0x80 | 0x10 | 0x01
        val palette = GifPalette(
            intArrayOf(
                0xFF000000.toInt(), 0xFF112233.toInt(), 0xFF445566.toInt()
            )
        )
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, loopCount = -1) // 不写循环扩展，方便定位
        encoder.writeHeader(2, 2, palette)
        encoder.writeFrame(byteArrayOf(0, 1, 2, 0), 10)
        encoder.finish()
        val bytes = out.toByteArray()
        assertEquals(0x91.toByte(), bytes[10]) // packed：0x80 | (2-1)<<4 | (2-1)
        assertEquals(0x00.toByte(), bytes[11]) // 背景索引
        assertEquals(0x00.toByte(), bytes[12]) // 像素宽高比
        // 色表 4 项，从 13 到 24；最后一项为补零
        assertEquals(0x00.toByte(), bytes[22])
        assertEquals(0x00.toByte(), bytes[23])
        assertEquals(0x00.toByte(), bytes[24])
        // 帧从 GCE 开始：21 F9 04 packed delay(2) transIdx term（8 字节，25..32）
        assertEquals(0x21.toByte(), bytes[25])
        assertEquals(0xF9.toByte(), bytes[26])
        assertEquals(0x04.toByte(), bytes[27])
        // disposal=1、无透明 -> packed = 1 shl 2 = 0x04
        assertEquals(0x04.toByte(), bytes[28])
        assertEquals(0x00.toByte(), bytes[32]) // GCE 块终止
        assertEquals(0x2C.toByte(), bytes[33]) // Image Descriptor 紧随
    }

    @Test
    fun multipleFramesAndFinish() {
        val bytes = encodeOneFrame(loopCount = 1)
        // 两帧验证：GCE 出现两次
        var count = 0
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0x00.toByte() && bytes[i + 1] == 0x21.toByte() &&
                bytes[i + 2] == 0xF9.toByte() && bytes[i + 3] == 0x04.toByte()
            ) count++
        }
        assertEquals(1, count) // encodeOneFrame 只写一帧
        assertEquals(0x3B.toByte(), bytes[bytes.size - 1])
    }

    @Test
    fun quantizerReuseExistingPalette() {
        val q = GifColorQuantizer()
        val first = IntArray(256) { 0xFF000000.toInt() or (it shl 16) or (it shl 8) }
        val frame1 = q.quantize(first, maxColors = 8, dither = false)
        val second = IntArray(64) { 0xFF000000.toInt() or ((it * 4 + 1) shl 16) }
        val frame2 = q.quantize(second, maxColors = 8, dither = false, existing = frame1.palette)
        assertTrue("复用时调色板应完全一致", frame2.palette === frame1.palette)
        for (i in frame2.indices.indices) {
            val idx = frame2.indices[i].toInt() and 0xFF
            assertTrue(idx in 0 until frame2.palette.size)
        }
    }
}
