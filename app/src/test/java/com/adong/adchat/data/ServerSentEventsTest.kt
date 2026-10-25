package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ServerSentEventsTest {
    @Test fun readsBomCrLfHeartbeatsAndMultilineData() = runBlocking {
        val source = Buffer().writeUtf8("\uFEFF: heartbeat\r\nevent: message\r\ndata: {\r\ndata: \"text\":\"你好\"}\r\n\r\ndata: [DONE]\r\n\r\n")
        val events = mutableListOf<String>()
        readSsePayloads(source) { events += it; true }
        assertEquals(2, events.size)
        assertEquals("你好", JSONObject(events.first()).getString("text"))
        assertEquals("[DONE]", events.last())
    }

    @Test fun preservesGatewaySupportForMissingBlankSeparatorsAndFinalNewline() = runBlocking {
        val source = Buffer().writeUtf8("data: {\"n\":1}\ndata: {\"n\":2}\ndata: [DONE]")
        val events = mutableListOf<String>()
        readSsePayloads(source) { events += it; true }
        assertEquals(listOf("{\"n\":1}", "{\"n\":2}", "[DONE]"), events)
    }

    @Test fun supportsNewlineDelimitedJsonAndStopsAtTerminalCallback() = runBlocking {
        val source = Buffer().writeUtf8("{\"n\":1}\n[DONE]\n{\"n\":2}\n")
        val events = mutableListOf<String>()
        readSsePayloads(source) { events += it; it != "[DONE]" }
        assertEquals(listOf("{\"n\":1}", "[DONE]"), events)
        assertEquals("{\"n\":2}\n", source.readUtf8())
    }

    @Test fun rejectsUnterminatedOversizedLinesBeforeReadingTheWholeBody() = runBlocking {
        val body = Buffer().writeUtf8("data: " + "x".repeat(100_000))
        var readBytes = 0L
        val source = object : ForwardingSource(body) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                return super.read(sink, byteCount).also { if (it > 0) readBytes += it }
            }
        }.buffer()
        val failure = runCatching { readSsePayloads(source, maxEventChars = 16) { true } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue("read $readBytes bytes", readBytes <= 8192)
    }

    @Test fun rejectsOversizedMultiLineEventsAndNdjson() = runBlocking {
        val multiline = Buffer().writeUtf8("data: {\ndata: \"long\":\ndata: \"123456789012345\"}\n\n")
        val rawJson = Buffer().writeUtf8("{\"long\":\"123456789012345\"}\n")
        listOf(multiline, rawJson).forEach { source ->
            val failure = runCatching { readSsePayloads(source, maxEventChars = 20) { true } }.exceptionOrNull()
            assertTrue(failure is IOException)
        }
    }

    @Test fun validUtf8IsNotCountedAsAsciiBytes() = runBlocking {
        val source = Buffer().writeUtf8("data: {\"v\":\"你好世界\"}\n\n")
        val events = mutableListOf<String>()
        readSsePayloads(source, maxEventChars = 16) { events += it; true }
        assertEquals("你好世界", JSONObject(events.single()).getString("v"))
    }
}
