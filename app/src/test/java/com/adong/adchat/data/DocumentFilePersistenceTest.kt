package com.adong.adchat.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentFilePersistenceTest {
    @Test fun binaryDocumentsAndLegacyTextSurviveConversationReload() {
        val file = DocumentFiles.create("test.docx", DocumentFiles.DOCX, "# 中文标题\n完整正文")
        val binary = ChatFileAttachment(name = file.name, mimeType = file.mimeType, content = file.content, encoding = file.encoding)
        val text = ChatFileAttachment(name = "old.txt", mimeType = "text/plain", content = "旧版文本")
        val context = RuntimeEnvironment.getApplication()
        val store = ConversationStore(context)
        store.save(listOf(Conversation(id = "document-test", title = "文档", messages = listOf(ChatMessage(id = 701, role = "assistant", content = "已生成", generatedFiles = listOf(binary, text))))))
        val restored = ConversationStore(context).load().first { it.id == "document-test" }.messages.single().generatedFiles
        assertArrayEquals(binary.bytes(), restored[0].bytes()); assertEquals(binary.bytes().size, restored[0].sizeBytes)
        assertEquals("utf-8", restored[1].encoding); assertEquals("旧版文本", restored[1].bytes().decodeToString())
    }
}
