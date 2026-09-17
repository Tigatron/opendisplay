package com.terrynamic.opendisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivenessTickPolicyTest {
    @Test
    fun asleepAndLocked_doesNotResumeOrSend() {
        val decision = LivenessTickPolicy.decide(
            listeningEnabled = true,
            asleep = true,
            stopped = false,
            deviceUnlocked = false,
        )
        assertFalse(decision.send)
        assertFalse(decision.resume)
        assertTrue(decision.reschedule)
    }

    @Test
    fun asleepAndUnlocked_selfHealsAndSends() {
        val decision = LivenessTickPolicy.decide(
            listeningEnabled = true,
            asleep = true,
            stopped = false,
            deviceUnlocked = true,
        )
        assertTrue(decision.resume)
        assertTrue(decision.send)
        assertTrue(decision.reschedule)
    }

    @Test
    fun stopped_doesNotRescheduleOrSelfHeal() {
        val decision = LivenessTickPolicy.decide(
            listeningEnabled = true,
            asleep = true,
            stopped = true,
            deviceUnlocked = true,
        )
        assertFalse(decision.send)
        assertFalse(decision.resume)
        assertFalse(decision.reschedule)
    }

    @Test
    fun sleepThenResumeFromSleep_loopSendsAgain() {
        val slept = LivenessTickPolicy.decide(
            listeningEnabled = true,
            asleep = true,
            stopped = false,
            deviceUnlocked = false,
        )
        assertFalse(slept.send)
        assertFalse(slept.resume)
        assertTrue(slept.reschedule)

        val resumed = LivenessTickPolicy.decide(
            listeningEnabled = true,
            asleep = false,
            stopped = false,
            deviceUnlocked = true,
        )
        assertTrue(resumed.send)
        assertFalse(resumed.resume)
        assertTrue(resumed.reschedule)
    }

    @Test
    fun notListening_doesNotSendButKeepsLoopUntilStopped() {
        val decision = LivenessTickPolicy.decide(
            listeningEnabled = false,
            asleep = false,
            stopped = false,
        )
        assertFalse(decision.send)
        assertFalse(decision.resume)
        assertTrue(decision.reschedule)
        assertEquals(500L, LivenessTickPolicy.INTERVAL_MS)
    }
}
