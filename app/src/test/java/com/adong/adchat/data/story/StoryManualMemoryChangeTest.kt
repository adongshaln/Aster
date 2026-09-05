package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Test

class StoryManualMemoryChangeTest {
    @Test
    fun manualOperationsRoundTripTheirPersistedValues() {
        StoryManualMemoryOperation.entries.forEach { operation ->
            assertEquals(operation, StoryManualMemoryOperation.fromDb(operation.dbValue))
        }
    }
}
