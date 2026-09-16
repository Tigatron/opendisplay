package build.terrynamic.opendisplay

import build.terrynamic.opendisplay.protocol.WireProtocol
import build.terrynamic.opendisplay.video.KeyframeRequestAction
import build.terrynamic.opendisplay.video.KeyframeRequestMetadata
import build.terrynamic.opendisplay.video.KeyframeRequestThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class KeyframeRequestThrottleTest {
    @Test
    fun throttledRequestBecomesOneDelayedRetryInsteadOfBeingDropped() {
        val throttle = KeyframeRequestThrottle(intervalMs = 1_000L)
        assertSame(KeyframeRequestAction.SendNow, throttle.request(generation = 7L, nowMs = 100L))
        assertEquals(KeyframeRequestAction.RetryAfter(700L), throttle.request(generation = 7L, nowMs = 400L))
        assertSame(KeyframeRequestAction.Coalesced, throttle.request(generation = 7L, nowMs = 500L))
        assertEquals(KeyframeRequestAction.RetryAfter(1L), throttle.retry(generation = 7L, nowMs = 1_099L))
        assertSame(KeyframeRequestAction.SendNow, throttle.retry(generation = 7L, nowMs = 1_100L))
    }

    @Test
    fun invalidatedConnectionCannotFireRetryAndReplacementStartsFresh() {
        val throttle = KeyframeRequestThrottle(intervalMs = 1_000L)
        assertSame(KeyframeRequestAction.SendNow, throttle.request(generation = 1L, nowMs = 10L))
        assertEquals(KeyframeRequestAction.RetryAfter(900L), throttle.request(generation = 1L, nowMs = 110L))
        throttle.invalidate(generation = 1L)
        assertSame(KeyframeRequestAction.Ignored, throttle.retry(generation = 1L, nowMs = 1_010L))
        assertSame(KeyframeRequestAction.SendNow, throttle.request(generation = 2L, nowMs = 1_010L))
    }

    @Test
    fun failedSchedulingCanBeRetriedWithoutRemovingThrottleWindow() {
        val throttle = KeyframeRequestThrottle(intervalMs = 1_000L)
        assertSame(KeyframeRequestAction.SendNow, throttle.request(generation = 4L, nowMs = 0L))
        assertEquals(KeyframeRequestAction.RetryAfter(900L), throttle.request(generation = 4L, nowMs = 100L))
        throttle.abandonRetry(generation = 4L)
        assertEquals(KeyframeRequestAction.RetryAfter(800L), throttle.request(generation = 4L, nowMs = 200L))
    }

    @Test
    fun pendingBareRequestUpgradesToBackpressureAndCannotBeDowngraded() {
        val bare = KeyframeRequestMetadata(reason = null, logReason = "surface attached")
        val backpressure = bare.upgradedWith(
            reason = WireProtocol.KEYFRAME_REASON_BACKPRESSURE,
            logReason = "video backpressure",
        )
        assertEquals(WireProtocol.KEYFRAME_REASON_BACKPRESSURE, backpressure.reason)
        assertEquals("video backpressure", backpressure.logReason)
        assertSame(backpressure, backpressure.upgradedWith(reason = null, logReason = "foreground resume"))
    }
}
