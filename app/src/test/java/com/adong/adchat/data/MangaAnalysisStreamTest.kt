package com.adong.adchat.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaAnalysisStreamTest {
    @Test
    fun responsesEventsKeepConnectionAliveAndAssembleJsonText() {
        val collector = MangaAnalysisStreamCollector(responsesApi = true)

        assertTrue(collector.accept("""{"type":"response.created"}"""))
        assertTrue(collector.accept("""{"type":"response.output_text.delta","delta":"{\"pages\":"}"""))
        assertTrue(collector.accept("""{"type":"response.output_text.delta","delta":"[]}"}"""))
        assertFalse(collector.accept("""{"type":"response.completed","response":{"usage":{}}}"""))

        assertTrue(collector.completed)
        assertEquals("{\"pages\":[]}", collector.requireResult())
    }

    @Test
    fun responsesCompletedEventCanSupplyFinalTextWithoutDeltas() {
        val collector = MangaAnalysisStreamCollector(responsesApi = true)

        assertFalse(collector.accept(
            """{"type":"response.completed","response":{"output":[{"content":[{"type":"output_text","text":"{\"pages\":[]}"}]}]}}"""
        ))

        assertEquals("{\"pages\":[]}", collector.requireResult())
    }

    @Test
    fun chatCompletionEventsAssembleTextUntilDone() {
        val collector = MangaAnalysisStreamCollector(responsesApi = false)

        assertTrue(collector.accept("""{"choices":[{"delta":{"content":"{\"pages\":"},"finish_reason":null}]}"""))
        assertTrue(collector.accept("""{"choices":[{"delta":{"content":"[]}"},"finish_reason":null}]}"""))
        assertFalse(collector.accept("[DONE]"))

        assertEquals("{\"pages\":[]}", collector.requireResult())
    }

    @Test
    fun incompleteStreamIsRejectedInsteadOfSubmittingImageRequests() {
        val collector = MangaAnalysisStreamCollector(responsesApi = true)
        collector.accept("""{"type":"response.output_text.delta","delta":"partial"}""")

        assertThrows(IOException::class.java) { collector.requireResult() }
    }

    @Test
    fun responsesErrorEventStopsTheAnalysisImmediately() {
        val collector = MangaAnalysisStreamCollector(responsesApi = true)

        val error = assertThrows(IllegalStateException::class.java) {
            collector.accept("""{"type":"error","error":{"message":"upstream timeout"}}""")
        }

        assertEquals("upstream timeout", error.message)
    }
}
