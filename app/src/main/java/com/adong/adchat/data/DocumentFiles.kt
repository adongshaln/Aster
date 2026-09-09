package com.adong.adchat.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Local, deterministic file generation. Model content is never treated as a script or raw ZIP. */
internal object DocumentFiles {
    const val PDF = "application/pdf"
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    val extensions = mapOf(PDF to "pdf", DOCX to "docx", XLSX to "xlsx", PPTX to "pptx")
    const val MAX_SOURCE = 200_000
    const val MAX_BYTES = 10 * 1024 * 1024
    const val CONTENT_HELP = "For text/HTML use complete UTF-8 source. For PDF or DOCX use Markdown (headings, paragraphs, lists, pipe tables). For XLSX use a JSON string: {\"sheets\":[{\"name\":\"Sheet1\",\"rows\":[[\"Name\",\"Amount\"],[\"Example\",12.5]]}]}. Cells are text, numbers, booleans, null, or {\"formula\":\"SUM(B2:B5)\"}; formula results recalculate when opened. For PPTX use a JSON string: {\"slides\":[{\"title\":\"Title\",\"bullets\":[\"Point one\",\"Point two\"]}]}. Each slide: title at most 100 characters, at most 8 bullets, each at most 180 characters. Do not send base64, binary, macros, external resources or fake file links."

    fun create(name: String, mime: String, source: String): GeneratedFileDraft {
        require(source.isNotBlank() && source.length <= MAX_SOURCE) { "文档正文不能为空，且不能超过 200,000 字符" }
        require(source.codePoints().allMatch { it == 9 || it == 10 || it == 13 || it in 0x20..0xD7FF || it in 0xE000..0xFFFD || it in 0x10000..0x10FFFF }) { "文档包含无效控制字符" }
        val bytes = when (mime) {
            PDF -> pdf(blocks(source))
            DOCX -> docx(blocks(source))
            XLSX -> xlsx(JSONObject(source))
            PPTX -> pptx(JSONObject(source))
            else -> error("不支持的文档格式")
        }
        require(bytes.size <= MAX_BYTES) { "生成文件超过 10 MB，请拆分内容" }
        return GeneratedFileDraft(name, mime, Base64.getEncoder().encodeToString(bytes), "base64")
    }

    private data class Block(val text: String = "", val heading: Int = 0, val rows: List<List<String>> = emptyList())
    private fun plain(value: String) = value.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1").replace(Regex("`([^`]+)`"), "$1")
    private fun blocks(source: String): List<Block> {
        val lines = source.replace("\r\n", "\n").lines()
        val result = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            if (line.trim().startsWith('|') && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("[| :\\-]+"))) {
                val rows = mutableListOf<List<String>>()
                fun cells(s: String) = s.trim().trim('|').split('|').map { plain(it.trim()) }
                rows += cells(line); i += 2
                while (i < lines.size && lines[i].trim().startsWith('|')) rows += cells(lines[i++])
                val columns = rows.maxOf { it.size }
                require(columns in 1..12) { "表格最多支持 12 列，请拆分表格" }
                result += Block(rows = rows.map { it + List(columns - it.size) { "" } })
                continue
            }
            val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
            if (line.isNotBlank()) result += if (heading != null) Block(plain(heading.groupValues[2]), heading.groupValues[1].length)
                else Block(plain(line.replace(Regex("^\\s*[-*+]\\s+"), "• ")))
            i++
        }
        require(result.isNotEmpty() && result.size <= 3000) { "文档内容为空或段落过多，请拆分" }
        return result
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
    private fun relationships(entries: List<Triple<String, String, String>>) =
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            entries.joinToString("") { (id, type, target) -> """<Relationship Id="$id" Type="$REL$type" Target="${xml(target)}"/>""" } + "</Relationships>"
    private fun types(overrides: List<Pair<String, String>>) =
        """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""" +
            overrides.joinToString("") { (path, type) -> """<Override PartName="/$path" ContentType="$type"/>""" } + "</Types>"
    private fun zip(parts: Map<String, String>): ByteArray = zipBytes(parts.mapValues { it.value.toByteArray(Charsets.UTF_8) })
    private fun zipBytes(parts: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> parts.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0 }); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    private fun docx(blocks: List<Block>): ByteArray {
        fun paragraph(text: String, heading: Int = 0, bold: Boolean = false): String =
            "<w:p><w:pPr>" + (if (heading > 0) """<w:pStyle w:val="Heading${heading.coerceAtMost(3)}"/>""" else "") +
                """<w:spacing w:after="120" w:line="320" w:lineRule="auto"/></w:pPr><w:r>""" +
                (if (bold) "<w:rPr><w:b/></w:rPr>" else "") + "<w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>"
        val body = blocks.joinToString("") { block ->
            if (block.rows.isEmpty()) paragraph(block.text, block.heading)
            else """<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/><w:tblBorders>""" +
                listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:color=\"D9D4CC\"/>" } +
                "</w:tblBorders></w:tblPr><w:tblGrid>" + block.rows.first().joinToString("") { "<w:gridCol w:w=\"${9000 / block.rows.first().size}\"/>" } + "</w:tblGrid>" +
                block.rows.mapIndexed { index, row -> "<w:tr>" + row.joinToString("") { cell ->
                    "<w:tc><w:tcPr>" + (if (index == 0) "<w:shd w:fill=\"F0ECE5\"/>" else "") + "</w:tcPr>" + paragraph(cell, bold = index == 0) + "</w:tc>"
                } + "</w:tr>" }.joinToString("") + "</w:tbl>"
        }
        val styles = """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Microsoft YaHei"/><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>""" +
            (1..3).joinToString("") { level -> """<w:style w:type="paragraph" w:styleId="Heading$level"><w:name w:val="heading $level"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:outlineLvl w:val="${level - 1}"/></w:pPr><w:rPr><w:b/><w:sz w:val="${42 - level * 4}"/></w:rPr></w:style>""" } + "</w:styles>"
        return zip(mapOf(
            "[Content_Types].xml" to types(listOf("word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml", "word/styles.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml")),
            "_rels/.rels" to relationships(listOf(Triple("rId1", "officeDocument", "word/document.xml"))),
            "word/_rels/document.xml.rels" to relationships(listOf(Triple("rId1", "styles", "styles.xml"))),
            "word/styles.xml" to styles,
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1080" w:right="1080" w:bottom="1080" w:left="1080" w:header="360" w:footer="360" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        ))
    }

    private fun xlsx(root: JSONObject): ByteArray {
        val sheets = root.getJSONArray("sheets")
        require(sheets.length() in 1..20) { "工作簿需要 1–20 个工作表" }
        val names = mutableSetOf<String>(); var cells = 0
        val parts = linkedMapOf<String, String>()
        for (i in 0 until sheets.length()) {
            val sheet = sheets.getJSONObject(i); val name = sheet.getString("name")
            require(name.isNotBlank() && name.length <= 31 && name.none { it in "[]:*?/\\" } && !name.startsWith("'") && !name.endsWith("'") && names.add(name.lowercase())) { "工作表名称不合法或重复" }
            val rows = sheet.getJSONArray("rows"); require(rows.length() in 1..10_000) { "工作表需要 1–10,000 行" }
            val data = (0 until rows.length()).joinToString("") { r ->
                val row = rows.getJSONArray(r); require(row.length() <= 256) { "工作表最多 256 列" }
                cells += row.length(); require(cells <= 50_000) { "工作簿最多 50,000 个单元格" }
                "<row r=\"${r + 1}\">" + (0 until row.length()).joinToString("") { c ->
                    val address = column(c) + (r + 1); val value = row.opt(c)
                    when {
                        value == null || value == JSONObject.NULL -> ""
                        value is Number -> { require(value.toDouble().isFinite()); "<c r=\"$address\"><v>$value</v></c>" }
                        value is Boolean -> "<c r=\"$address\" t=\"b\"><v>${if (value) 1 else 0}</v></c>"
                        value is JSONObject -> {
                            val formula = value.getString("formula").removePrefix("=").uppercase()
                            require(formula.length <= 512 && formula.matches(Regex("[A-Z0-9 +*/(),.:$\\-]+")) &&
                                Regex("([A-Z]+)\\s*\\(").findAll(formula).all { it.groupValues[1] in setOf("SUM", "AVERAGE", "MIN", "MAX", "COUNT", "ROUND", "ABS") }) { "仅支持不含外部引用的基础公式" }
                            "<c r=\"$address\"><f>${xml(formula)}</f></c>"
                        }
                        value is String -> {
                            require(value.length <= 32767) { "单元格文字超过 Excel 上限" }
                            "<c r=\"$address\" t=\"inlineStr\"${if (r == 0) " s=\"1\"" else ""}><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
                        }
                        else -> error("单元格只能是文字、数字、布尔值或公式")
                    }
                } + "</row>"
            }
            parts["xl/worksheets/sheet${i + 1}.xml"] = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetFormatPr defaultColWidth="20" defaultRowHeight="20"/><sheetData>$data</sheetData></worksheet>"""
        }
        parts["xl/workbook.xml"] = """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="${REL.removeSuffix("/")}"><sheets>""" +
            (0 until sheets.length()).joinToString("") { "<sheet name=\"${xml(sheets.getJSONObject(it).getString("name"))}\" sheetId=\"${it + 1}\" r:id=\"rId${it + 1}\"/>" } + "</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\" forceFullCalc=\"1\"/></workbook>"
        parts["xl/styles.xml"] = """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF0ECE5"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
        parts["[Content_Types].xml"] = types(listOf("xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml", "xl/styles.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml") +
            (1..sheets.length()).map { "xl/worksheets/sheet$it.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml" })
        parts["_rels/.rels"] = relationships(listOf(Triple("rId1", "officeDocument", "xl/workbook.xml")))
        parts["xl/_rels/workbook.xml.rels"] = relationships((1..sheets.length()).map { Triple("rId$it", "worksheet", "worksheets/sheet$it.xml") } + Triple("rIdStyles", "styles", "styles.xml"))
        return zip(parts)
    }
    private fun column(index: Int): String { var n = index + 1; var name = ""; while (n > 0) { n--; name = ('A' + n % 26) + name; n /= 26 }; return name }

    private fun pptx(root: JSONObject): ByteArray {
        val slides = root.getJSONArray("slides"); require(slides.length() in 1..60) { "演示文稿需要 1–60 页" }
        val parts = linkedMapOf<String, ByteArray>()
        val template = requireNotNull(javaClass.getResourceAsStream("/document-templates/presentation.pptx"))
        ZipInputStream(template).use { zip -> while (true) { val entry = zip.nextEntry ?: break; parts[entry.name] = zip.readBytes() } }
        fun put(path: String, text: String) { parts[path] = text.toByteArray(Charsets.UTF_8) }
        fun text(path: String) = parts.getValue(path).toString(Charsets.UTF_8)
        val ids = (1..slides.length()).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"rIdAster$it\"/>" }
        put("ppt/presentation.xml", text("ppt/presentation.xml").replace(Regex("<p:sldIdLst>.*?</p:sldIdLst>", RegexOption.DOT_MATCHES_ALL), "<p:sldIdLst>$ids</p:sldIdLst>"))
        var rels = text("ppt/_rels/presentation.xml.rels").replace(Regex("<Relationship[^>]*Type=\"[^\"]*/slide\"[^>]*/>"), "")
        rels = rels.replace("</Relationships>", (1..slides.length()).joinToString("") { "<Relationship Id=\"rIdAster$it\" Type=\"${REL}slide\" Target=\"slides/slide$it.xml\"/>" } + "</Relationships>")
        put("ppt/_rels/presentation.xml.rels", rels)
        var contentTypes = text("[Content_Types].xml").replace(Regex("<Override PartName=\"/ppt/slides/slide[0-9]+.xml\"[^>]*/>"), "")
        contentTypes = contentTypes.replace("</Types>", (1..slides.length()).joinToString("") { "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } + "</Types>")
        put("[Content_Types].xml", contentTypes)
        for (i in 0 until slides.length()) {
            val slide = slides.getJSONObject(i); val title = slide.getString("title"); val bullets = slide.getJSONArray("bullets")
            require(title.isNotBlank() && title.length <= 100 && bullets.length() <= 8) { "每页标题最多 100 字符，正文最多 8 条" }
            val items = (0 until bullets.length()).map { bullets.getString(it).also { value -> require(value.length <= 180) { "每条要点最多 180 字符，请拆成更多页" } } }
            // Conservative wrapping prevents CJK text from overflowing the fixed 16:9 page.
            fun wrap(value: String, width: Int): List<String> {
                val lines = mutableListOf<String>(); var line = StringBuilder(); var cost = 0
                for (cp in value.codePoints().toArray()) {
                    val next = if (cp > 127) 2 else 1
                    if (cp == 10 || cost + next > width) { lines += line.toString(); line = StringBuilder(); cost = 0 }
                    if (cp != 10) { line.appendCodePoint(cp); cost += next }
                }; lines += line.toString(); return lines
            }
            val titleLines = wrap(title, 54); require(titleLines.size <= 2) { "标题太长，请缩短" }
            val bodyLines = items.flatMap { wrap("• $it", 86) }; require(bodyLines.size <= 14) { "本页文字过多，请拆成更多页" }
            fun shape(id: Int, y: Int, h: Int, size: Int, lines: List<String>, bold: Boolean): String =
                """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Text $id"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="685800" y="$y"/><a:ext cx="10820400" cy="$h"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square"/><a:lstStyle/>""" +
                    lines.joinToString("") { """<a:p><a:pPr><a:lnSpc><a:spcPts val="${size + 500}"/></a:lnSpc></a:pPr><a:r><a:rPr lang="zh-CN" sz="$size" b="${if (bold) 1 else 0}"><a:solidFill><a:srgbClr val="302D28"/></a:solidFill><a:latin typeface="Calibri"/><a:ea typeface="Microsoft YaHei"/></a:rPr><a:t>${xml(it)}</a:t></a:r><a:endParaRPr lang="zh-CN" sz="$size"/></a:p>""" } + "</p:txBody></p:sp>"
            val slideXml = """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="${REL.removeSuffix("/")}" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="F7F4EE"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
                shape(2, 457200, 1371600, 2800, titleLines, true) + shape(3, 2057400, 4389120, 1800, bodyLines, false) + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
            put("ppt/slides/slide${i + 1}.xml", slideXml)
            put("ppt/slides/_rels/slide${i + 1}.xml.rels", relationships(listOf(Triple("rId1", "slideLayout", "../slideLayouts/slideLayout7.xml"))))
        }
        return zipBytes(parts)
    }

    private fun pdf(blocks: List<Block>): ByteArray {
        val document = PdfDocument(); var page: PdfDocument.Page? = null; var number = 0; var y = 48f
        val width = 499; val bottom = 785f
        fun finish() {
            page?.let { current ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 9f }
                current.canvas.drawText(number.toString(), 294f, 814f, paint)
                document.finishPage(current); page = null
            }
        }
        fun next() {
            finish(); require(++number <= 200) { "PDF 超过 200 页，请拆分" }
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create()); y = 48f
        }
        fun layout(text: String, size: Float, bold: Boolean, w: Int): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = Color.rgb(48, 45, 40); typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL) }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f, 1f).build()
        }
        try {
            next()
            blocks.forEach { block ->
                if (block.rows.isEmpty()) {
                    val size = if (block.heading == 0) 11f else (23 - block.heading.coerceAtMost(4) * 2).toFloat()
                    val text = layout(block.text, size, block.heading > 0, width)
                    for (line in 0 until text.lineCount) {
                        val top = text.getLineTop(line); val height = text.getLineBottom(line) - top
                        if (y + height > bottom) next()
                        val canvas = page!!.canvas; canvas.save(); canvas.clipRect(48f, y, 547f, y + height)
                        canvas.translate(48f, y - top); text.draw(canvas); canvas.restore(); y += height
                    }; y += if (block.heading > 0) 10 else 6
                } else {
                    val cellWidth = width / block.rows.first().size
                    block.rows.forEachIndexed { index, row ->
                        val cells = row.map { layout(it, 10f, index == 0, cellWidth - 12) }
                        val h = (cells.maxOf { it.height } + 12).toFloat()
                        require(h <= bottom - 48) { "PDF 表格单行太长，请拆分单元格内容" }
                        if (y + h > bottom) next()
                        val canvas = page!!.canvas
                        cells.forEachIndexed { column, cell ->
                            val x = 48f + column * cellWidth
                            val paint = Paint().apply { color = if (index == 0) Color.rgb(240, 236, 229) else Color.WHITE }
                            canvas.drawRect(x, y, x + cellWidth, y + h, paint)
                            paint.color = Color.LTGRAY; paint.style = Paint.Style.STROKE; paint.strokeWidth = .5f
                            canvas.drawRect(x, y, x + cellWidth, y + h, paint)
                            canvas.save(); canvas.translate(x + 6, y + 6); cell.draw(canvas); canvas.restore()
                        }; y += h
                    }; y += 10
                }
            }
            finish(); return ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
        } finally { finish(); document.close() }
    }
}
