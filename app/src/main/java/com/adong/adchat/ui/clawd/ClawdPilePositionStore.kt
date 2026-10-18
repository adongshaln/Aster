package com.adong.adchat.ui.clawd

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** SharedPreferences 实现（原版 localStorage 同语义：按离输入框中线的 dx 存） */
class ClawdPilePositionStore(context: Context) : ClawdPileState.PositionStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("clawd_pile_positions", Context.MODE_PRIVATE)

    override fun load(): Map<String, ClawdPileState.SavedPos>? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { name ->
                val p = o.getJSONObject(name)
                ClawdPileState.SavedPos(
                    dx = p.getInt("dx"),
                    on = p.getJSONArray("on").let { a -> (0 until a.length()).map { a.getString(it) } },
                )
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    override fun save(positions: Map<String, ClawdPileState.SavedPos>) {
        val o = JSONObject()
        for ((name, p) in positions) {
            o.put(name, JSONObject().put("dx", p.dx).put("on", org.json.JSONArray(p.on)))
        }
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    companion object {
        private const val KEY = "positions"
    }
}
