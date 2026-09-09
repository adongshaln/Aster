package com.adong.adchat.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocumentFilesTest {
    private fun parts(file: GeneratedFileDraft): Map<String, ByteArray> {
        assertEquals("base64", file.encoding)
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(Base64.getDecoder().decode(file.content).inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes() }
        }
        result.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (path, data) ->
            try { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(data.inputStream()) }
            catch (e: Exception) { throw AssertionError("Invalid XML: $path", e) }
        }
        return result
    }
    @Test fun wordKeepsUnicodeEscapesXmlAndBuildsATable() {
        val files = parts(DocumentFiles.create("plan.docx", DocumentFiles.DOCX,
            "# 项目计划\n中文 <tag> & 引号\n\n| 人物 | 状态 |\n| --- | --- |\n| 爱丽丝 | 已确认 |"))
        val document = files.getValue("word/document.xml").decodeToString()
        assertTrue(document.contains("项目计划")); assertTrue(document.contains("&lt;tag&gt; &amp;"))
        assertTrue(document.contains("<w:tbl>")); assertTrue(files.containsKey("word/styles.xml"))
    }
    @Test fun spreadsheetPreservesNumbersBooleansStringsAndExplicitFormulas() {
        val files = parts(DocumentFiles.create("budget.xlsx", DocumentFiles.XLSX,
            """{"sheets":[{"name":"预算","rows":[["项目","金额"],["木材",12.5],["=HYPERLINK(\"https://example.com\")",true],["合计",{"formula":"SUM(B2:B2)"}]]}]}"""))
        val sheet = files.getValue("xl/worksheets/sheet1.xml").decodeToString()
        assertTrue(sheet.contains("<v>12.5</v>")); assertTrue(sheet.contains("t=\"b\""))
        assertTrue(sheet.contains("<f>SUM(B2:B2)</f>")); assertEquals(1, Regex("<f>").findAll(sheet).count())
        assertTrue(sheet.contains("=HYPERLINK(&quot;https://example.com&quot;)"))
    }
    @Test fun presentationIncludesMastersLayoutsAndEverySlide() {
        val files = parts(DocumentFiles.create("story.pptx", DocumentFiles.PPTX,
            """{"slides":[{"title":"世界观 & 人物","bullets":["设定一","设定二"]},{"title":"第二幕","bullets":["继续前进"]}]}"""))
        assertTrue(files.containsKey("ppt/slideMasters/slideMaster1.xml"))
        assertTrue(files.containsKey("ppt/slideLayouts/slideLayout7.xml"))
        assertTrue(files.getValue("ppt/slides/slide2.xml").decodeToString().contains("继续前进"))
        val rels = files.getValue("ppt/_rels/presentation.xml.rels").decodeToString()
        assertTrue(rels.contains("rIdAster2")); assertEquals(2, Regex("Type=\"[^\"]*/slide\"").findAll(rels).count())
    }
    @Test fun malformedInputNeverProducesAFakeOfficeFile() {
        listOf(
            DocumentFiles.XLSX to """{"sheets":[{"name":"A","rows":[[1]]},{"name":"a","rows":[[2]]}]}""",
            DocumentFiles.XLSX to """{"sheets":[{"name":"A","rows":[[{"formula":"HYPERLINK(\"https://example.com\")"}]]}]}""",
            DocumentFiles.PPTX to """{"slides":[]}"""
        ).forEach { (mime, content) ->
            val result = executeAppTool(PendingToolCall("bad", "bad", CREATE_FILE_TOOL,
                JSONObject().put("filename", "file").put("mime_type", mime).put("content", content).toString()))
            assertEquals(TOOL_STATUS_FAILED, result.activity.status); assertNull(result.generatedFile)
        }
    }
    @Test fun bothProtocolsAdvertiseDocumentFormats() {
        val definitions = listOf(buildChatTools(true).getJSONObject(0).getJSONObject("function"), buildResponsesTools(true, false).getJSONObject(0))
        definitions.forEach { definition ->
            val enums = definition.getJSONObject("parameters").getJSONObject("properties").getJSONObject("mime_type").getJSONArray("enum").toString()
            DocumentFiles.extensions.keys.forEach { assertTrue(enums.contains(it)) }
        }
    }
}
