package com.adong.adchat.ui.clawd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClawdActivityGateTest {
    @Test
    fun hiddenScheduledActionRunsOnceAfterReturningToForeground() = runBlocking {
        val gate = ClawdActivityGate()
        gate.setActive(false)
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.delayWhileActive(0)
            calls++
        }
        assertEquals(0, calls)
        assertFalse(job.isCompleted)
        gate.setActive(true)
        withTimeout(2_000) { job.join() }
        gate.setActive(false)
        gate.setActive(true)
        assertEquals(1, calls)
    }

    @Test
    fun cancellingHiddenActionPreventsItFromReappearingOnResume() = runBlocking {
        val gate = ClawdActivityGate()
        gate.setActive(false)
        var called = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.delayWhileActive(0)
            called = true
        }
        job.cancelAndJoin()
        gate.setActive(true)
        assertFalse(called)
    }

    @Test
    fun aRunningSequenceStopsAgainOnTheNextBackgroundTransition() = runBlocking {
        val gate = ClawdActivityGate()
        val continueSequence = CompletableDeferred<Unit>()
        val reachedNextStep = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.delayWhileActive(0)
            calls++
            continueSequence.await()
            reachedNextStep.complete(Unit)
            gate.delayWhileActive(0)
            calls++
        }
        assertEquals(1, calls)
        gate.setActive(false)
        continueSequence.complete(Unit)
        withTimeout(2_000) { reachedNextStep.await() }
        assertEquals(1, calls)
        assertFalse(job.isCompleted)
        gate.setActive(true)
        withTimeout(2_000) { job.join() }
        assertEquals(2, calls)
    }
}
