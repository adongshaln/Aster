package com.adong.adchat.ui.clawd

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 外形一致性金标准测试：Kotlin 引擎输出的像素与提取自原版 JS 的 golden JSON 逐位对比。
 * golden.rig.frames / golden.pile 是"外形完全相同"的最终裁判；
 * golden.rig.clips 是时间轴采样数学的裁判（为播放器铺路）。
 */
class ClawdPixelGoldenTest {

    companion object {
        private lateinit var rig: RigData
        private lateinit var pile: PileData
        private lateinit var golden: JSONObject

        @JvmStatic
        @BeforeClass
        fun setup() {
            val dataJson = javaClass.getResourceAsStream("/clawd/clawd_data.json")!!
                .readBytes().decodeToString()
            val goldenJson = javaClass.getResourceAsStream("/clawd/clawd_golden.json")!!
                .readBytes().decodeToString()
            rig = ClawdLoader.loadRigData(dataJson)
            pile = ClawdLoader.loadPileData(dataJson)
            golden = JSONObject(goldenJson)
        }
    }

    @Test
    fun rigFramesMatchGolden() {
        val frames = golden.getJSONObject("rig").getJSONArray("frames")
        val failures = mutableListOf<String>()
        for (i in 0 until frames.length()) {
            val f = frames.getJSONObject(i)
            val part = f.getString("part")
            val name = f.getString("name")
            val mirror = f.getBoolean("mirror")
            val want = f.getJSONArray("px").toPixels()
            val got = ClawdPixels.rigFramePixels(rig, part, name, mirror)
            val diff = firstDiff(want, got)
            if (diff != null) {
                failures.add("$part.$name(mirror=$mirror): $diff")
            }
        }
        assertTrue("共 ${failures.size}/${frames.length()} 组不一致，前 10 组:\n${failures.take(10).joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun pileCombosMatchGolden() {
        val combos = golden.getJSONArray("pile")
        val failures = mutableListOf<String>()
        for (i in 0 until combos.length()) {
            val c = combos.getJSONObject(i)
            val want = c.getJSONArray("px").toPixels()
            val got = ClawdPixels.pileCells(
                pile,
                c.getString("name"),
                PileEyes.from(c.getString("eyes")),
                PilePose.from(c.getString("pose")),
                c.getInt("face"),
            )
            val diff = firstDiff(want, got)
            if (diff != null) {
                failures.add("${c.getString("name")}/${c.getString("eyes")}/${c.getString("pose")}/face=${c.getInt("face")}: $diff")
            }
        }
        assertTrue("共 ${failures.size}/${combos.length()} 组不一致，前 10 组:\n${failures.take(10).joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun rigClipSamplesMatchGolden() {
        val clips = golden.getJSONObject("rig").getJSONObject("clips")
        val failures = mutableListOf<String>()
        for (id in clips.keys()) {
            val g = clips.getJSONObject(id)
            val clip = rig.clips.getValue(id)
            assertEquals("dur 不一致: $id", g.getInt("dur").toLong(), clip.dur.toLong())
            val samples = g.getJSONArray("samples")
            for (i in 0 until samples.length()) {
                val s = samples.getJSONObject(i)
                val t = s.getInt("t")
                val state = ClawdSampler.sampleState(rig, clip, t)
                val wantRoot = s.getJSONObject("st").getJSONObject("root")
                if (!numEq(wantRoot.getDouble("dx"), state.rootDx, false) || !numEq(wantRoot.getDouble("dy"), state.rootDy, false)) {
                    failures.add("$id t=$t root: want(${wantRoot.getDouble("dx")},${wantRoot.getDouble("dy")}) got(${state.rootDx},${state.rootDy})")
                    continue
                }
                val wantSt = s.getJSONObject("st")
                for (part in rig.parts) {
                    val w = wantSt.getJSONObject(part)
                    val got = state.parts.getValue(part)
                    val wf = if (w.isNull("f")) null else w.getString("f")
                    if (wf != got.f) {
                        failures.add("$id t=$t $part.f: want=$wf got=${got.f}")
                        continue
                    }
                    if (wf != null || got.f != null) {
                        checkNum(id, t, part, "dx", w.getDouble("dx"), got.dx, failures)
                        checkNum(id, t, part, "dy", w.getDouble("dy"), got.dy, failures)
                        checkNum(id, t, part, "z", w.getDouble("z"), got.z.toDouble(), failures)
                        checkNum(id, t, part, "sc", w.getDouble("sc"), got.sc, failures)
                        checkNum(id, t, part, "r", w.getDouble("r"), got.r, failures)
                        checkNum(id, t, part, "a", w.getDouble("a"), got.a, failures)
                    }
                }
            }
        }
        assertTrue("共 ${failures.size} 处采样不一致，前 10 处:\n${failures.take(10).joinToString("\n")}", failures.isEmpty())
    }

    private fun checkNum(id: String, t: Int, part: String, prop: String, want: Double, got: Double, failures: MutableList<String>) {
        val isFloat = prop == "sc" || prop == "r" || prop == "a" || prop == "dx" || prop == "dy"
        if (!numEq(want, got, isFloat)) failures.add("$id t=$t $part.$prop: want=$want got=$got")
    }

    private fun numEq(want: Double, got: Double, isFloat: Boolean): Boolean =
        if (isFloat) Math.abs(want - got) < 1e-6 else want == got

    /** golden 的 [[x,y,"颜色"],...] -> Pair<坐标键, ARGB> 有序列表 */
    private fun JSONArray.toPixels(): List<Pair<Long, Int>> {
        val list = (0 until length()).map { i ->
            val e = getJSONArray(i)
            val key = (e.getInt(1).toLong() shl 32) or (e.getInt(0).toLong() and 0xFFFFFFFFL)
            key to ClawdLoader.colorArgb(e.getString(2))
        }
        return list.sortedBy { it.first }
    }

    private fun firstDiff(want: List<Pair<Long, Int>>, got: List<ClawdPixel>): String? {
        val gotKeys = got.map { (it.y.toLong() shl 32) or (it.x.toLong() and 0xFFFFFFFFL) }
        if (want.map { it.first } != gotKeys) {
            val wantSet = want.map { it.first }.toSet()
            val gotSet = gotKeys.toSet()
            val missing = wantSet - gotSet
            val extra = gotSet - wantSet
            fun fmt(k: Long) = "(${k and 0xFFFFFFFFL},${k shr 32})"
            return "像素集不同: 缺 ${missing.take(5).joinToString { fmt(it) }} 多 ${extra.take(5).joinToString { fmt(it) }} (want ${want.size} got ${got.size})"
        }
        for ((i, w) in want.withIndex()) {
            val g = got[i]
            if (w.second != g.argb) {
                return "颜色不同 @(${g.x},${g.y}): want #${Integer.toHexString(w.second)} got #${Integer.toHexString(g.argb)}"
            }
        }
        return null
    }
}
