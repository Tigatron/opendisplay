package com.terrynamic.opendisplay

import com.terrynamic.opendisplay.transport.ListenerGate
import com.terrynamic.opendisplay.transport.ListenerSwapPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerSwapPolicyTest {
    @Test
    fun swapOk_closeThenBindWhenHostsConflict() {
        val plan = ListenerSwapPolicy.plan(
            ListenerSwapPolicy.Snapshot(host = ListenerGate.WILDCARD, open = true),
            desired = ListenerGate.LOOPBACK,
        )
        val step = plan as ListenerSwapPolicy.Plan.CloseThenBind
        assertEquals(ListenerGate.LOOPBACK, step.desired)
        assertEquals(ListenerGate.WILDCARD, step.fallback)
        val result = ListenerSwapPolicy.onDesiredBind(
            desired = step.desired,
            succeeded = true,
            fallback = step.fallback,
            attempt = 0,
        )
        assertEquals(ListenerSwapPolicy.Result.BoundDesired(ListenerGate.LOOPBACK), result)
    }

    @Test
    fun swapBindFailure_fallsBackToPreviousAndRetries() {
        val plan = ListenerSwapPolicy.plan(
            ListenerSwapPolicy.Snapshot(host = ListenerGate.WILDCARD, open = true),
            desired = ListenerGate.LOOPBACK,
        ) as ListenerSwapPolicy.Plan.CloseThenBind
        val failedDesired = ListenerSwapPolicy.onDesiredBind(
            desired = plan.desired,
            succeeded = false,
            fallback = plan.fallback,
            attempt = 0,
        ) as ListenerSwapPolicy.Result.BoundFallback
        assertEquals(ListenerGate.WILDCARD, failedDesired.fallback)
        assertEquals(200L, failedDesired.retryDelayMs)

        val fallbackOk = ListenerSwapPolicy.onFallbackBind(
            desired = plan.desired,
            fallback = plan.fallback,
            succeeded = true,
            attempt = 0,
        ) as ListenerSwapPolicy.Result.BoundFallback
        assertEquals(ListenerGate.WILDCARD, fallbackOk.fallback)
        assertEquals(200L, fallbackOk.retryDelayMs)
    }

    @Test
    fun retrySuccess_bindsDesired() {
        val result = ListenerSwapPolicy.onDesiredBind(
            desired = ListenerGate.LOOPBACK,
            succeeded = true,
            fallback = ListenerGate.WILDCARD,
            attempt = 2,
        )
        assertEquals(ListenerSwapPolicy.Result.BoundDesired(ListenerGate.LOOPBACK), result)
    }

    @Test
    fun alreadyBound_isNoop() {
        val plan = ListenerSwapPolicy.plan(
            ListenerSwapPolicy.Snapshot(host = ListenerGate.LOOPBACK, open = true),
            desired = ListenerGate.LOOPBACK,
        )
        assertTrue(plan is ListenerSwapPolicy.Plan.AlreadyBound)
    }

    @Test
    fun closedOrMissingListen_bindsFresh() {
        val closed = ListenerSwapPolicy.plan(
            ListenerSwapPolicy.Snapshot(host = ListenerGate.WILDCARD, open = false),
            desired = ListenerGate.WILDCARD,
        )
        assertEquals(ListenerSwapPolicy.Plan.BindFresh(ListenerGate.WILDCARD), closed)
        val missing = ListenerSwapPolicy.plan(
            ListenerSwapPolicy.Snapshot(host = null, open = false),
            desired = ListenerGate.LOOPBACK,
        )
        assertEquals(ListenerSwapPolicy.Plan.BindFresh(ListenerGate.LOOPBACK), missing)
    }

    @Test
    fun retryBackoff_thenCapsAtTwoSeconds() {
        assertEquals(200L, ListenerSwapPolicy.retryDelayMs(0))
        assertEquals(500L, ListenerSwapPolicy.retryDelayMs(1))
        assertEquals(1_000L, ListenerSwapPolicy.retryDelayMs(2))
        assertEquals(2_000L, ListenerSwapPolicy.retryDelayMs(3))
        assertEquals(2_000L, ListenerSwapPolicy.retryDelayMs(8))
    }

    @Test
    fun acceptMustWait_whenNullOrClosed() {
        assertTrue(ListenerSwapPolicy.acceptMustWait(listenNull = true, serverClosed = false))
        assertTrue(ListenerSwapPolicy.acceptMustWait(listenNull = false, serverClosed = true))
        assertFalse(ListenerSwapPolicy.acceptMustWait(listenNull = false, serverClosed = false))
    }

    @Test
    fun bothBindsFail_leavesUnboundWithRetry() {
        val result = ListenerSwapPolicy.onFallbackBind(
            desired = ListenerGate.LOOPBACK,
            fallback = ListenerGate.WILDCARD,
            succeeded = false,
            attempt = 1,
        ) as ListenerSwapPolicy.Result.Unbound
        assertEquals(500L, result.retryDelayMs)
    }
}
