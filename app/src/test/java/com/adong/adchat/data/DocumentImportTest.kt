package com.adong.adchat.data

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class DocumentImportTest {
    @Test fun unicodeTextAndDraftAppendPreserveContentAndRejectOverflow() {
        assertEquals("世界设定",DocumentImport.decodeText("\uFEFF世界设定".toByteArray()))
        assertEquals("世界设定",DocumentImport.decodeText("\uFEFF世界设定".toByteArray(Charsets.UTF_16LE)))
        assertEquals("旧草稿\n\n新附件",DocumentImport.append("旧草稿","新附件"))
        assertThrows(IllegalArgumentException::class.java) {DocumentImport.append("甲".repeat(40_000),"乙")}
        assertThrows(IllegalArgumentException::class.java) {DocumentImport.decodeText(byteArrayOf(0,1,2))}
        assertThrows(Exception::class.java) {DocumentImport.decodeText(byteArrayOf(0xc3.toByte(),0x28))}
        assertThrows(IllegalArgumentException::class.java) {DocumentImport.boundedBytes(ByteArray(101).inputStream(),100)}
    }
    @Test fun docxExtractsParagraphsAndTableTextWithoutImportingArchiveGarbage() {
        val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<w:document xmlns:w="urn:word"><w:body><w:p><w:r><w:t>北方</w:t></w:r><w:r><w:t>积雪</w:t></w:r></w:p><w:tr><w:tc><w:p><w:r><w:t>人物</w:t></w:r></w:p></w:tc></w:tr></w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
        val text=DocumentImport.docx(bytes.toByteArray())
        assertTrue(text.startsWith("北方积雪\n"));assertTrue(text.contains("人物"));assertFalse(text.contains("<w:"))
    }
    @Test fun scannedOnlyPdfIsRejectedWithoutDependingOnAndroidFontAssets() {
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val file=java.io.File(context.cacheDir,"reference.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { pdf ->
            pdf.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
            pdf.save(file)
        }
        assertThrows(IllegalArgumentException::class.java) {DocumentImport.read(context,android.net.Uri.fromFile(file))}
    }

}
