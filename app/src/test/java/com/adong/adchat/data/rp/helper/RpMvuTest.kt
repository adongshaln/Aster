package com.adong.adchat.data.rp.helper

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpMvuTest {
    private fun base(stat: String) = JSONObject().put("stat_data", JSONObject(stat))

    @Test
    fun setUsesLastArgUpdatesVwdAndConvertsNumbers() {
        val baseline = base("""{"角色":{"好感度":[30,"描述"],"金钱":10,"心情":"平静"}}""")
        val text = """正文
<UpdateVariable>
_.set('角色.好感度', 30, 35);//开心
_.set('角色.金钱', "20");
_.set('角色.心情', '愉快');
_.set('角色.不存在', 1);
</UpdateVariable>"""
        val r = RpMvu.process(text, baseline)
        val stat = r.variables!!.getJSONObject("stat_data").getJSONObject("角色")
        assertEquals(35, stat.getJSONArray("好感度").get(0))
        assertEquals("描述", stat.getJSONArray("好感度").get(1))
        assertEquals(20, stat.get("金钱"))
        assertEquals("愉快", stat.get("心情"))
        assertFalse(stat.has("不存在"))
        assertEquals(4, r.commands)
        assertEquals("30->35 (开心)", r.variables!!.getJSONObject("display_data").getJSONObject("角色").getString("好感度"))
        assertTrue(r.variables!!.getJSONObject("delta_data").getJSONObject("角色").has("金钱"))
        assertTrue(r.variables!!.has("schema"))
        assertTrue(r.displayContent.endsWith("\n\n<StatusPlaceHolderImpl/>"))
        // baseline must not be mutated
        assertEquals(30, baseline.getJSONObject("stat_data").getJSONObject("角色").getJSONArray("好感度").get(0))
    }

    @Test
    fun requiresSemicolonAndIgnoresThinking() {
        val baseline = base("""{"hp":10}""")
        val r = RpMvu.process("<think>_.set('hp', 1);</think>_.set('hp', 2)\n_.add('hp', -3*2);", baseline)
        assertEquals(1, r.commands)
        assertEquals(4, r.variables!!.getJSONObject("stat_data").get("hp"))
    }

    @Test
    fun noCommandsReturnsNullButAddsPlaceholderWhenStatExists() {
        val r = RpMvu.process("你好<status_current_variable>{\"a\":1}</status_current_variable>", base("{}"))
        assertNull(r.variables)
        assertEquals("你好\n\n<StatusPlaceHolderImpl/>", r.displayContent)
        val plain = RpMvu.process("你好", null)
        assertEquals("你好", plain.displayContent)
        val already = RpMvu.process("x<StatusPlaceHolderImpl/>", base("{}"))
        assertEquals("x<StatusPlaceHolderImpl/>", already.displayContent)
    }

    @Test
    fun addHandlesExpressionsDatesAndBooleans() {
        val baseline = base("""{"a":1,"b":[5,"d"],"t":"2024-01-01T00:00:00.000Z","f":false}""")
        val r = RpMvu.process("_.add('a', (1+2)/3);_.add('b', 2.5);_.add('t', 60000);_.add('f', 1);_.add('a', 'x');", baseline)
        val s = r.variables!!.getJSONObject("stat_data")
        assertEquals(2, s.get("a"))
        assertEquals(7.5, s.getJSONArray("b").getDouble(0), 0.0)
        assertEquals("2024-01-01T00:01:00.000Z", s.get("t"))
        assertEquals(true, s.get("f"))
    }

    @Test
    fun insertRemoveAndMove() {
        val baseline = base("""{"包":["剑","盾"],"人":{"甲":1},"x":{"y":3}}""")
        val text = """
_.insert('包', '药水');
_.assign('包', 0, '弓');
_.insert('人', '乙', 2);
_.insert('人', {"丙": 3});
_.remove('包', '盾');
_.unset('人.甲');
_.delete('包[0]');
_.move('x.y', 'z');
"""
        val s = RpMvu.process(text, baseline).variables!!.getJSONObject("stat_data")
        assertEquals("""["剑","药水"]""", s.getJSONArray("包").toString())
        assertEquals(2, s.getJSONObject("人").get("乙"))
        assertEquals(3, s.getJSONObject("人").get("丙"))
        assertFalse(s.getJSONObject("人").has("甲"))
        assertEquals(3, s.get("z"))
        assertFalse(s.getJSONObject("x").has("y"))
    }

    @Test
    fun balancedParensAndQuotes() {
        val baseline = base("""{"note":"","list":[]}""")
        val r = RpMvu.process("""_.set('note', "a (b); c");//r
_.insert('list', ["text with _.set('inner',null);"]);""", baseline)
        val s = r.variables!!.getJSONObject("stat_data")
        assertEquals("a (b); c", s.get("note"))
        assertEquals(1, s.getJSONArray("list").length())
        assertTrue(s.getJSONArray("list").get(0) is JSONArray)
        assertEquals(2, r.commands)
    }

    @Test
    fun jsonPatchBlock() {
        val baseline = base("""{"a":{"b":1},"arr":[1]}""")
        val text = """<JSONPatch>
[{"op":"replace","path":"/a/b","value":5},{"op":"add","path":"/arr/-","value":2},{"op":"delta","path":"/a/b","value":1},{"op":"remove","path":"/arr/0"}]
</JSONPatch>"""
        val s = RpMvu.process(text, baseline).variables!!.getJSONObject("stat_data")
        assertEquals(6, s.getJSONObject("a").get("b"))
        assertEquals("[2]", s.getJSONArray("arr").toString())
    }

    @Test
    fun initialMergesEntriesAndGreeting() {
        val e1 = "<initvar>\n```yaml\n角色:\n  好感度: [0, \"好感\"]\n  名字: {{char}}\n```\n</initvar>"
        val e2 = "{\"角色\": {\"金钱\": 5}, \"世界\": {\"时间\": \"早上\"}}"
        val floor = RpMvu.initial(listOf(e1, e2), "开场白 _.set('角色.金钱', 100);", { it.replace("{{char}}", "艾莉丝") })
        val s = floor.getJSONObject("stat_data")
        assertEquals("艾莉丝", s.getJSONObject("角色").get("名字"))
        assertEquals(100, s.getJSONObject("角色").get("金钱"))
        assertEquals(0, s.getJSONObject("角色").getJSONArray("好感度").get(0))
        assertEquals("早上", s.getJSONObject("世界").get("时间"))
        assertTrue(RpMvu.isMvuData(floor))
        assertEquals(0, RpMvu.initial(emptyList(), "普通开场", { it }).length())
    }

    @Test
    fun greetingInitvarReplacesStat() {
        val floor = RpMvu.initial(listOf("a: 1"), "<initvar>\nb: 2\n</initvar>", { it })
        val s = floor.getJSONObject("stat_data")
        assertFalse(s.has("a"))
        assertEquals(2, s.get("b"))
    }
}
