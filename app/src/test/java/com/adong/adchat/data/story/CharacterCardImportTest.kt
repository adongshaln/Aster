package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CharacterCardImportTest {
    private val cardJson = """
        {"spec":"chara_card_v2","data":{
          "name":"梅尔文",
          "description":"<style>.hud{color:red}</style><div>住在旧书店。</div>",
          "personality":"寡言",
          "scenario":"Fate/strange Fake 同人",
          "first_mes":"<script>drawHud()</script><div class='panel'>{{user}}，{{char}}在雨里等你。</div><img src='x'>",
          "mes_example":"<START>\n{{user}}：你好\n{{char}}：嗯。",
          "system_prompt":"<b>用短句。</b>",
          "post_history_instructions":"不要写界面"
        }}
    """.trimIndent()

    @Test
    fun jsonCardKeepsProseAndDropsLayout() {
        val imported = CharacterCardFile.parse(cardJson.toByteArray())
        assertEquals("梅尔文", imported.card.name)
        assertTrue(imported.card.imported)
        assertTrue(imported.card.description.contains("住在旧书店"))
        assertFalse(imported.card.description.contains("color"))
        assertFalse(imported.card.description.contains("<"))
        assertEquals("用户，梅尔文在雨里等你。", imported.opening)
        assertFalse(imported.opening.contains("drawHud"))
        assertTrue(imported.card.mesExample.contains("用户：你好"))
        assertTrue(imported.card.mesExample.contains("梅尔文：嗯。"))
        assertEquals("用短句。", imported.card.systemPrompt)
    }

    @Test
    fun pngCardUsesEmbeddedCharaChunk() {
        val imported = CharacterCardFile.parse(pngWithChara(cardJson))
        assertEquals("用户，梅尔文在雨里等你。", imported.opening)
        assertTrue(imported.card.hidesDiscussion())
    }

    @Test
    fun charxReadsCardJson() {
        val zip = java.io.ByteArrayOutputStream()
        ZipOutputStream(zip).use { output ->
            output.putNextEntry(ZipEntry("card.json"))
            output.write(cardJson.toByteArray())
            output.closeEntry()
        }
        val imported = CharacterCardFile.parse(zip.toByteArray())
        assertEquals("梅尔文", imported.card.name)
        assertTrue(imported.card.scenario.contains("Fate/strange Fake"))
    }

    @Test
    fun importedCardTellsTheModelToWriteProseOnly() {
        val block = StoryCastContext(card = CharacterCardFile.parse(cardJson.toByteArray()).card).toPromptBlock()
        assertTrue(block.contains("只输出正文"))
        assertTrue(block.contains("Fate/strange Fake"))
        assertTrue(block.contains("用短句"))
        assertFalse(block.contains("<"))
    }

    private fun StoryCharacterCard.hidesDiscussion() = imported

    @Test
    fun characterBookImportsWithCleanedEntries() {
        val imported = CharacterCardFile.parse(cardJsonWithBook.toByteArray())
        val book = imported.card.worldBook
        assertEquals(2, book.entries.size)
        val constantEntry = book.entries.first { it.constant }
        assertTrue(constantEntry.beforeChar)
        assertTrue(constantEntry.content.contains("世界树的传说"))
        assertFalse(constantEntry.content.contains("<"))
        val keyedEntry = book.entries.first { !it.constant }
        assertTrue(keyedEntry.keys.contains("圣杯"))
        assertFalse(keyedEntry.beforeChar)
        assertTrue(keyedEntry.content.contains("梅尔文另有目的"))
    }

    @Test
    fun characterBookMacrosAreExpanded() {
        val imported = CharacterCardFile.parse(cardJsonWithBook.toByteArray())
        val keyedEntry = imported.card.worldBook.entries.first { !it.constant }
        assertTrue(keyedEntry.content.contains("梅尔文"))
        assertFalse(keyedEntry.content.contains("{{char}}"))
        assertFalse(keyedEntry.content.contains("{{user}}"))
    }

    @Test
    fun characterBookSurvivesRoundTrip() {
        val card = CharacterCardFile.parse(cardJsonWithBook.toByteArray()).card
        val restored = StoryCharacterCard.fromStored(card.toJson())
        val book = requireNotNull(restored).worldBook
        assertEquals(card.worldBook, book)
    }

    @Test
    fun bookOnlyCardStillImports() {
        val json = """
            {"spec":"chara_card_v2","data":{
              "name":"无名",
              "character_book":{"entries":[{"keys":["圣杯"],"content":"圣杯的设定"}]}
            }}
        """.trimIndent()
        val imported = CharacterCardFile.parse(json.toByteArray())
        assertEquals(1, imported.card.worldBook.entries.size)
    }

    private val cardJsonWithBook = """
        {"spec":"chara_card_v2","data":{
          "name":"梅尔文",
          "description":"住在旧书店。",
          "character_book":{
            "scan_depth":3,
            "token_budget":4096,
            "recursive_scanning":true,
            "entries":[
              {"keys":[],"constant":true,"position":"before_char","insertion_order":50,
               "content":"<div>世界树的传说，{{user}}不可不知。</div>"},
              {"keys":["圣杯","Grail"],"secondary_keys":[],"selective":true,"selective_logic":0,
               "position":"after_char","insertion_order":200,
               "content":"圣杯战争中{{char}}另有目的，{{char}}与{{char}}不同。"}
            ]
          }
        }}
    """.trimIndent()

    private fun pngWithChara(json: String): ByteArray {
        val payload = Base64.getEncoder().encodeToString(json.toByteArray())
        val data = "chara\u0000$payload".toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(chunk("tEXt", data))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(intBytes(data.size))
        out.write(type.toByteArray())
        out.write(data)
        out.write(intBytes(0))
        return out.toByteArray()
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )
}
