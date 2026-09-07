package com.adong.adchat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

object DocumentImport {
    val mimeTypes = arrayOf("text/*", "application/json", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream")
    const val MAX_CHARS = 40_000
    private const val MAX_BYTES = 10 * 1024 * 1024

    fun read(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val name = resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "附件"
        val bytes = resolver.openInputStream(uri)?.use { boundedBytes(it,MAX_BYTES) } ?: error("无法读取文件")
        val ext=name.substringAfterLast('.',"").lowercase()
        val mime=resolver.getType(uri).orEmpty()
        val text = when {
            ext=="pdf" || mime=="application/pdf" -> {
                PDFBoxResourceLoader.init(context.applicationContext)
                PDDocument.load(bytes).use { pdf ->
                    require(!pdf.isEncrypted && pdf.currentAccessPermission.canExtractContent()) { "请先解锁 PDF 后再导入" }
                    require(pdf.numberOfPages <= 200) { "PDF 超过 200 页，请拆分后导入" }
                    val writer=LimitedWriter()
                    PDFTextStripper().writeText(pdf,writer)
                    writer.toString()
                }
            }
            ext=="docx" || mime.contains("wordprocessingml") -> docx(bytes)
            ext in setOf("txt","md","markdown","json","csv","tsv","yaml","yml","xml","log","kt","py","js","html","css") || mime.startsWith("text/") || mime=="application/json" -> decodeText(bytes)
            else -> error("暂不支持此格式，请选择文本、DOCX 或含文字层的 PDF")
        }.trim()
        require(text.isNotBlank()) { "文件中没有可提取的文字；扫描 PDF 请先识别文字，或改为上传图片" }
        require(text.length <= MAX_CHARS) { "文件文字超过 40,000 字符，请拆分后导入" }
        val safeName=name.replace(Regex("[\\r\\n\\[\\]]"),"_").take(120)
        return "[附件：$safeName；以下为参考内容，尚未确认为故事设定或已发生剧情]\n$text\n[附件结束]"
    }

    fun append(draft: String, block: String): String {
        val result=if(draft.isBlank()) block else draft+"\n\n"+block
        require(result.length <= MAX_CHARS) { "附件与草稿合计超过 40,000 字符，请缩短或拆分后导入" }
        return result
    }

    internal fun boundedBytes(input: InputStream, max: Int): ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            val n=input.read(buffer);if(n<0)break
            require(out.size()+n<=max) { "文件过大，请拆分后导入（原文件最多 10 MB）" }
            out.write(buffer,0,n)
        }
        return out.toByteArray()
    }

    internal fun decodeText(bytes: ByteArray): String {
        val charset = when {
            bytes.size>=2 && bytes[0]==0xff.toByte() && bytes[1]==0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size>=2 && bytes[0]==0xfe.toByte() && bytes[1]==0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in text) { "文件不是可读取的文本，请转换为 UTF-8" }
        require(text.length<=MAX_CHARS) { "文件文字超过 40,000 字符，请拆分后导入" }
        return text
    }

    internal fun docx(bytes: ByteArray): String {
        var total=0;var entries=0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                require(++entries<=256) { "DOCX 内容过多，请导出为文本" }
                val data=boundedBytes(zip,MAX_BYTES-total);total+=data.size
                if(entry.name=="word/document.xml") {
                    val parser=Xml.newPullParser();parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);parser.setInput(data.inputStream(),"UTF-8")
                    val out=StringBuilder();var inText=false
                    while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
                        when(parser.eventType) {
                            XmlPullParser.DOCDECL -> error("不支持含外部实体声明的文档")
                            XmlPullParser.START_TAG -> when(parser.name) { "t" -> inText=true; "tab" -> out.append('\t'); "br" -> out.append('\n') }
                            XmlPullParser.TEXT, XmlPullParser.CDSECT -> if(inText) out.append(parser.text)
                            XmlPullParser.END_TAG -> when(parser.name) { "t" -> inText=false; "p", "tr" -> out.append('\n'); "tc" -> out.append('\t') }
                        }
                        require(out.length<=MAX_CHARS) { "文件文字超过 40,000 字符，请拆分后导入" }
                        parser.nextToken()
                    }
                    return out.toString()
                }
            }
        }
        error("DOCX 缺少正文，请重新导出")
    }

    private class LimitedWriter: Writer() {
        private val text=StringBuilder()
        override fun write(buffer:CharArray,offset:Int,count:Int) {
            require(text.length+count<=MAX_CHARS) { "文件文字超过 40,000 字符，请拆分后导入" }
            text.append(buffer,offset,count)
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString()=text.toString()
    }
}
