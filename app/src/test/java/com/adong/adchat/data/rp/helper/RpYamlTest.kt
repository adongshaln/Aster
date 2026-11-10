package com.adong.adchat.data.rp.helper

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpYamlTest {
    @Test
    fun parsesNestedMapsListsAndScalars() {
        val obj = RpYaml.parseObject(
            """
            # 角色状态
            角色:
              好感度: [30, "0-100 的好感"]
              名字: "艾莉丝"   # 注释
              在场: true
              金钱: 12.5
              物品:
                - 剑
                - 名称: 药水
                  数量: 3
              空: ~
            标签: [a, 'b c', 3]
            内联: {x: 1, y: "z"}
            """.trimIndent()
        )!!
        val role = obj.getJSONObject("角色")
        assertEquals(30, role.getJSONArray("好感度").get(0))
        assertEquals("0-100 的好感", role.getJSONArray("好感度").get(1))
        assertEquals("艾莉丝", role.getString("名字"))
        assertEquals(true, role.get("在场"))
        assertEquals(12.5, role.getDouble("金钱"), 0.0)
        val items = role.getJSONArray("物品")
        assertEquals("剑", items.get(0))
        assertEquals(3, items.getJSONObject(1).get("数量"))
        assertEquals("药水", items.getJSONObject(1).get("名称"))
        assertEquals(JSONObject.NULL, role.get("空"))
        assertEquals("b c", obj.getJSONArray("标签").get(1))
        assertEquals(3, obj.getJSONArray("标签").get(2))
        assertEquals("z", obj.getJSONObject("内联").get("y"))
    }

    @Test
    fun blockScalarsAndEscapes() {
        val obj = RpYaml.parseObject("a: |\n  第一行\n  第二行\nb: >-\n  折叠\n  文本\nc: \"x\\ny \\\"q\\\"\"\nd: 'it''s'\n")!!
        assertEquals("第一行\n第二行\n", obj.getString("a"))
        assertEquals("折叠 文本", obj.getString("b"))
        assertEquals("x\ny \"q\"", obj.getString("c"))
        assertEquals("it's", obj.getString("d"))
    }

    @Test
    fun listOfListsAndSiblingLists() {
        val obj = RpYaml.parseObject("k:\n- 1\n- - 2\n  - 3\nm: x:y\nurl: http://a.b\n")!!
        val k = obj.getJSONArray("k")
        assertEquals(1, k.get(0))
        assertEquals(3, (k.get(1) as JSONArray).get(1))
        assertEquals("x:y", obj.getString("m"))
        assertEquals("http://a.b", obj.getString("url"))
    }

    @Test
    fun flowValuesAndEmpty() {
        assertNull(RpYaml.parse("   \n# only comment"))
        val arr = RpYaml.parseFlow("[1, {a: [2, 3]}, \"s\"]") as JSONArray
        assertEquals(3, (arr.getJSONObject(1).getJSONArray("a")).get(1))
        assertTrue(RpYaml.parse("{\"a\": 1}") is JSONObject)
        assertEquals(-4, RpYaml.scalar("-4"))
        assertEquals("abc", RpYaml.scalar("abc"))
    }
}
