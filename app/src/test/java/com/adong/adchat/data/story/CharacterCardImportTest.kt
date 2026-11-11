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
    fun jsonCardCleansDefinitionButKeepsRawGreeting() {
        val imported = CharacterCardFile.parse(cardJson.toByteArray())
        assertEquals("梅尔文", imported.card.name)
        assertTrue(imported.card.imported)
        assertTrue(imported.card.description.contains("住在旧书店"))
        assertFalse(imported.card.description.contains("color"))
        assertFalse(imported.card.description.contains("<"))
        // Greetings keep raw HTML for the sandbox frontend; only macros are expanded.
        assertTrue(imported.opening.contains("用户，梅尔文在雨里等你。"))
        assertTrue(imported.opening.contains("<script>drawHud()</script>"))
        assertTrue(imported.opening.contains("<div class='panel'>"))
        assertFalse(imported.opening.contains("{{char}}"))
        assertEquals(imported.card.firstMes, imported.opening)
        assertTrue(imported.card.mesExample.contains("用户：你好"))
        assertTrue(imported.card.mesExample.contains("梅尔文：嗯。"))
        assertEquals("用短句。", imported.card.systemPrompt)
    }

    @Test
    fun pngCardUsesEmbeddedCharaChunk() {
        val imported = CharacterCardFile.parse(pngWithChara(cardJson))
        assertTrue(imported.opening.contains("用户，梅尔文在雨里等你。"))
        assertTrue(imported.opening.contains("<script>"))
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
    fun frontendCardDefersToCardFormat() {
        val card = CharacterCardFile.parse(cardJson.toByteArray()).card
        assertTrue(card.hasFrontend)
        val block = StoryCastContext(card = card).toPromptBlock()
        assertTrue(block.contains("按角色卡约定的格式"))
        assertTrue(block.contains("Fate/strange Fake"))
        assertTrue(block.contains("用短句"))
        assertFalse(block.contains("只输出正文"))
        assertFalse(block.contains("<"))
    }

    @Test
    fun plainImportedCardWritesProseOnly() {
        val json = """
            {"spec":"chara_card_v2","data":{
              "name":"路人",
              "description":"住在街角。",
              "first_mes":"他抬起头。"
            }}
        """.trimIndent()
        val card = CharacterCardFile.parse(json.toByteArray()).card
        assertFalse(card.hasFrontend)
        val block = StoryCastContext(card = card).toPromptBlock()
        assertTrue(block.contains("只输出正文"))
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

    @Test
    fun multiGreetingsAreKeptInSwipeOrder() {
        val imported = CharacterCardFile.parse(v3CardJson.toByteArray())
        assertEquals(listOf("开场一", "开场二", "开场三"), imported.greetings)
        assertEquals("开场一", imported.opening)
        assertEquals(listOf("开场二", "开场三"), imported.card.alternateGreetings)
        assertEquals(listOf("群聊开场"), imported.card.groupOnlyGreetings)
        assertEquals(listOf("开场一", "开场二", "开场三"), imported.card.greetings)
    }

    @Test
    fun v3NicknameWinsCharMacro() {
        val imported = CharacterCardFile.parse(v3CardJson.toByteArray())
        assertEquals("朝凪", imported.card.name)
        assertEquals("小凪", imported.card.nickname)
        assertEquals("小凪", imported.card.macroName)
        // {{char}} in the greeting resolves to the nickname, not the name.
        assertTrue(imported.card.description.contains("小凪"))
        assertFalse(imported.card.description.contains("{{char}}"))
    }

    @Test
    fun v3MetadataIsPreservedForDisplay() {
        val imported = CharacterCardFile.parse(v3CardJson.toByteArray())
        assertEquals("作者备注", imported.card.creatorNotes)
        assertEquals(listOf("同人", "日常"), imported.card.tags)
        assertEquals("作者", imported.card.creator)
        assertEquals("1.2", imported.card.characterVersion)
        assertEquals(listOf("https://example.com/card"), imported.card.source)
        assertEquals(1000L, imported.card.creationDate)
        assertEquals(2000L, imported.card.modificationDate)
    }

    @Test
    fun extensionsAndDepthPromptSurviveImport() {
        val imported = CharacterCardFile.parse(v3CardJson.toByteArray())
        val depth = requireNotNull(imported.card.depthPrompt)
        assertEquals(3, depth.depth)
        assertEquals("保持小凪的口吻", depth.prompt)
        assertEquals("system", depth.role)
        assertEquals(0.7, imported.card.talkativeness!!, 0.0001)
        // Unknown extension keys (regex_scripts / tavern_helper) must not be destroyed.
        assertTrue(imported.card.extensionsRaw.contains("regex_scripts"))
        assertTrue(imported.card.extensionsRaw.contains("tavern_helper"))
    }

    @Test
    fun cardRoundTripPreservesNewFields() {
        val card = CharacterCardFile.parse(v3CardJson.toByteArray()).card
        val restored = requireNotNull(StoryCharacterCard.fromStored(card.toJson()))
        assertEquals(card.nickname, restored.nickname)
        assertEquals(card.macroName, restored.macroName)
        assertEquals(card.firstMes, restored.firstMes)
        assertEquals(card.alternateGreetings, restored.alternateGreetings)
        assertEquals(card.groupOnlyGreetings, restored.groupOnlyGreetings)
        assertEquals(card.tags, restored.tags)
        assertEquals(card.creatorNotes, restored.creatorNotes)
        assertEquals(card.source, restored.source)
        assertEquals(card.creationDate, restored.creationDate)
        assertEquals(card.modificationDate, restored.modificationDate)
        assertEquals(card.depthPrompt, restored.depthPrompt)
        assertEquals(card.talkativeness!!, restored.talkativeness!!, 0.0001)
        assertEquals(card.extensionsRaw, restored.extensionsRaw)
    }

    private val v3CardJson = """
        {"spec":"chara_card_v3","spec_version":"3.0","data":{
          "name":"朝凪",
          "nickname":"小凪",
          "description":"{{char}}是图书管理员。",
          "personality":"温和",
          "scenario":"旧图书馆",
          "first_mes":"开场一",
          "alternate_greetings":["开场二","开场三"],
          "group_only_greetings":["群聊开场"],
          "creator_notes":"作者备注",
          "tags":["同人","日常"],
          "creator":"作者",
          "character_version":"1.2",
          "source":["https://example.com/card"],
          "creation_date":1000,
          "modification_date":2000,
          "extensions":{
            "depth_prompt":{"depth":3,"prompt":"保持{{char}}的口吻","role":"system"},
            "talkativeness":0.7,
            "regex_scripts":[{"findRegex":"a","replaceString":"b"}],
            "tavern_helper":{"scripts":[{"name":"init.js"}]}
          }
        }}
    """.trimIndent()

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

    @Test
    fun v2BookEntryExtensionsMapLikeSillyTavern() {
        val json = """
            {"spec":"chara_card_v2","data":{"name":"梅尔文","description":"住在旧书店。","character_book":{
              "name":"书","scan_depth":3,"token_budget":800,"recursive_scanning":false,"entries":[
                {"id":7,"keys":["龙"],"secondary_keys":["火"],"selective":true,"insertion_order":30,"enabled":true,
                 "position":"before_char","content":"@@activate\n{{char}}的龙","comment":"龙",
                 "extensions":{"position":4,"depth":2,"role":1,"probability":50,"useProbability":true,
                   "group":"兽","group_override":true,"group_weight":20,"sticky":2,"cooldown":3,"delay":4,
                   "scan_depth":5,"match_whole_words":true,"case_sensitive":true,"exclude_recursion":true,
                   "prevent_recursion":true,"delay_until_recursion":true,"selectiveLogic":3,
                   "use_group_scoring":true,"automation_id":"qr","triggers":["normal"],"ignore_budget":true,
                   "match_scenario":true,"outlet_name":"o","vendor_key":1}},
                {"keys":["门"],"content":"门","position":"before_char","insertion_order":5}
              ]}}}
        """.trimIndent()
        val imported = CharacterCardFile.parse(json.toByteArray())
        val e = imported.rawCard.worldBook.entries[0]
        assertEquals(7, e.uid); assertEquals(4, e.position); assertEquals(2, e.depth); assertEquals(1, e.role)
        assertEquals(50, e.probability); assertEquals("兽", e.group); assertTrue(e.groupOverride); assertEquals(20, e.groupWeight)
        assertEquals(2, e.sticky); assertEquals(3, e.cooldown); assertEquals(4, e.delay); assertEquals(5, e.scanDepth)
        assertEquals(true, e.matchWholeWords); assertEquals(true, e.caseSensitiveOverride)
        assertTrue(e.excludeRecursion); assertTrue(e.preventRecursion); assertEquals(1, e.delayUntilRecursion)
        assertEquals(3, e.selectiveLogic); assertEquals(WorldLogic.AND_ALL, e.logic); assertEquals(true, e.useGroupScoring)
        assertEquals("qr", e.automationId); assertEquals(listOf("normal"), e.triggers); assertTrue(e.ignoreBudget)
        assertTrue(e.matchScenario); assertEquals("o", e.outletName); assertTrue(e.selective)
        assertEquals("@@activate\n{{char}}的龙", e.content)
        val second = imported.rawCard.worldBook.entries[1]
        assertEquals(1, second.uid); assertEquals(0, second.position); assertFalse(second.selective)
        val book = imported.rawCard.worldBook
        assertEquals(3, book.bookScanDepth); assertEquals(800, book.tokenBudget); assertEquals(false, book.recursiveScanning)
        assertEquals(book, StoryWorldBook.parse(book.toJson()))
        assertTrue(imported.card.worldBook.entries[0].content.contains("梅尔文的龙"))
    }

    @Test
    fun rawCardKeepsUnexpandedText() {
        val json = """
            {"spec":"chara_card_v2","data":{"name":"梅尔文",
              "description":"<div>{{user}}的朋友</div>\nstatus: 正常\n{\"hp\": 10}",
              "mes_example":"<START>\n{{user}}：你好\n{{char}}：嗯。",
              "first_mes":"{{char}}看着{{user}}"}}
        """.trimIndent()
        val imported = CharacterCardFile.parse(json.toByteArray())
        val raw = imported.rawCard
        assertEquals("<div>{{user}}的朋友</div>\nstatus: 正常\n{\"hp\": 10}", raw.description)
        assertTrue(raw.mesExample.startsWith("<START>"))
        assertEquals("{{char}}看着{{user}}", raw.firstMes)
        assertFalse(imported.card.description.contains("{{user}}"))
        assertEquals("梅尔文看着用户", imported.card.firstMes)
    }

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
