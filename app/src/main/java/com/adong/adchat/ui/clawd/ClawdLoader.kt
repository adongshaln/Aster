package com.adong.adchat.ui.clawd

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

object ClawdLoader {

    fun colorArgb(spec: String): Int {
        if (spec.startsWith("#")) {
            val hex = spec.substring(1)
            require(hex.length == 6) { "不支持的颜色: $spec" }
            val rgb = hex.toLong(16).toInt()
            return (0xFF shl 24) or rgb
        }
        if (spec.startsWith("rgba")) {
            val inner = spec.substringAfter('(').substringBefore(')')
            val parts = inner.split(',').map { it.trim() }
            require(parts.size == 4) { "不支持的颜色: $spec" }
            val r = parts[0].toDouble().roundToInt().coerceIn(0, 255)
            val g = parts[1].toDouble().roundToInt().coerceIn(0, 255)
            val b = parts[2].toDouble().roundToInt().coerceIn(0, 255)
            val a = (parts[3].toDouble() * 255.0).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        throw IllegalArgumentException("不支持的颜色: $spec")
    }

    fun loadRigData(json: String): RigData {
        val root = JSONObject(json)
        val rig = root.getJSONObject("rig")
        val box = rig.getJSONArray("box")
        return RigData(
            px = rig.getInt("px"),
            tick = rig.getInt("tick"),
            boxX = box.getInt(0),
            boxY = box.getInt(1),
            parts = rig.getJSONArray("parts").toStringList(),
            origin = rig.getJSONObject("origin").let { o ->
                o.keys().asSequence().associateWith { k -> val a = o.getJSONArray(k); a.getInt(0) to a.getInt(1) }
            },
            restF = rig.getJSONObject("restF").let { o ->
                o.keys().asSequence().associateWith { k -> if (o.isNull(k)) null else o.getString(k) }
            },
            restZ = rig.getJSONObject("restZ").let { o ->
                o.keys().asSequence().associateWith { k -> o.getInt(k) }
            },
            upperKids = rig.getJSONArray("upperKids").toStringList().toSet(),
            pal = rig.getJSONObject("pal").let { o ->
                o.keys().asSequence().associateWith { k -> o.getString(k) }
            },
            sprites = rig.getJSONObject("sprites").let { sheets ->
                sheets.keys().asSequence().associate { sheet ->
                    val frames = sheets.getJSONObject(sheet)
                    sheet to frames.keys().asSequence().associate { frame ->
                        val f = frames.getJSONObject(frame)
                        frame to SpriteFrame(
                            rows = f.getJSONArray("rows").toStringList(),
                            ox = f.getInt("ox"),
                            oy = f.getInt("oy"),
                        )
                    }
                }
            },
            clips = rig.getJSONObject("clips").let { clips ->
                clips.keys().asSequence().associate { id -> id to parseClip(clips.getJSONObject(id)) }
            },
        )
    }

    fun loadPileData(json: String): PileData {
        val root = JSONObject(json)
        val pile = root.getJSONObject("pile")
        fun spanList(key: String): Map<String, List<Triple<Int, Int, String>>> {
            val o = pile.getJSONObject(key)
            return o.keys().asSequence().associate { name ->
                name to o.getJSONArray(name).let { arr ->
                    (0 until arr.length()).map { i ->
                        val e = arr.getJSONArray(i)
                        Triple(e.getInt(0), e.getInt(1), e.getString(2))
                    }
                }
            }
        }
        return PileData(
            px = pile.getInt("px"),
            w = pile.getInt("w"),
            h = pile.getInt("h"),
            top = pile.getInt("top"),
            pal = pile.getJSONObject("pal").toStringStringMap(),
            palNight = pile.getJSONObject("palNight").toStringStringMap(),
            body = pile.getJSONArray("body").toStringList(),
            legs = pile.getJSONObject("legs").let { o ->
                o.keys().asSequence().associate { k -> k to o.getJSONArray(k).toStringList() }
            },
            eyes = spanList("eyes"),
            glasses = spanList("glasses"),
            cowboy = pile.getJSONArray("cowboy").toStringList(),
            wizard = pile.getJSONArray("wizard").toStringList(),
            wand = spanList("wand"),
            names = pile.getJSONArray("names").toStringList(),
            bodyW = pile.getInt("bodyW"),
            lift = pile.getInt("lift"),
            gap = pile.getInt("gap"),
            edge = pile.getInt("edge"),
            stepMs = pile.getInt("stepMs"),
            legX = pile.getJSONArray("legX").let { a -> (0 until a.length()).map { a.getInt(it) } },
        )
    }

    private fun parseClip(c: JSONObject): RigClip = RigClip(
        id = c.getString("id"),
        name = c.getString("name"),
        dur = c.getInt("dur"),
        loop = c.getBoolean("loop"),
        intro = c.optInt("intro", 0),
        track = if (c.isNull("track")) null else c.getString("track"),
        group = if (c.isNull("group")) null else c.getString("group"),
        next = if (c.isNull("next")) null else c.getString("next"),
        hold = c.getBoolean("hold"),
        after = if (c.isNull("after")) null else c.getString("after"),
        from = if (c.isNull("from")) null else c.getString("from"),
        clipGround = c.optBoolean("clipGround", false),
        noShadow = c.optBoolean("noShadow", false),
        extentL = c.optJSONObject("extent")?.optInt("l", 0) ?: 0,
        extentR = c.optJSONObject("extent")?.optInt("r", 0) ?: 0,
        flexEase = c.getString("flexEase"),
        smooth = c.getJSONArray("smooth").toStringList().toSet(),
        tr = c.getJSONObject("tr").let { parts ->
            parts.keys().asSequence().associate { part ->
                val props = parts.getJSONObject(part)
                part to props.keys().asSequence().associate { prop ->
                    val ks = props.getJSONArray(prop)
                    prop to (0 until ks.length()).map { i ->
                        val e = ks.getJSONArray(i)
                        RigKeyframe(
                            t = e.getInt(0),
                            v = when {
                                e.isNull(1) -> ClawdValue.None
                                e.get(1) is String -> ClawdValue.Str(e.getString(1))
                                else -> ClawdValue.Num(e.getDouble(1))
                            },
                            ease = e.getString(2),
                        )
                    }
                }
            }
        },
        flexKeys = c.getJSONArray("flexKeys").let { arr ->
            (0 until arr.length()).map { i ->
                val e = arr.getJSONArray(i)
                val v = e.getJSONObject(1)
                e.getInt(0) to FlexValue(
                    sx = v.optDouble("sx", 1.0),
                    sy = v.optDouble("sy", 1.0),
                    r = v.optDouble("r", 0.0),
                    tx = v.optDouble("tx", 0.0),
                    ty = v.optDouble("ty", 0.0),
                )
            }
        },
        beats = c.getJSONArray("beats").let { arr ->
            (0 until arr.length()).map { i ->
                val e = arr.getJSONArray(i)
                e.getInt(0) to e.getString(1)
            }
        },
    )

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONObject.toStringStringMap(): Map<String, String> =
        keys().asSequence().associateWith { getString(it) }
}
