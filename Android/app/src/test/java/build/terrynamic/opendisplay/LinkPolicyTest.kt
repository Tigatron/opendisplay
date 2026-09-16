package build.terrynamic.opendisplay

import build.terrynamic.opendisplay.link.LinkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {
    @Test
    fun freshPort9000_advertisesLoopback() {
        val policy = LinkPolicy { 1_000 }
        val changed = policy.onHeartbeat(port = 9000, helperVersion = "1.0", ttlMs = 15_000, atMs = 1_000)
        assertTrue(changed)
        assertEquals(listOf("127.0.0.1"), policy.addrs(1_000))
        assertTrue(policy.snapshot(1_000).helperActive)
    }

    @Test
    fun expiredOrWrongPort_clearsAddrs() {
        val policy = LinkPolicy { 1_000 }
        policy.onHeartbeat(9000, "1.0", ttlMs = 1_000, atMs = 1_000)
        assertEquals(emptyList<String>(), policy.addrs(2_000))
        policy.onHeartbeat(9001, "1.0", ttlMs = 15_000, atMs = 2_000)
        assertEquals(emptyList<String>(), policy.addrs(2_000))
        assertFalse(policy.isActive(2_000))
    }

    @Test
    fun stateChangeOnlyWhenFreshnessFlips() {
        val policy = LinkPolicy()
        assertTrue(policy.onHeartbeat(9000, "a", 5_000, atMs = 0))
        assertFalse(policy.onHeartbeat(9000, "a", 5_000, atMs = 10))
        assertTrue(policy.onHeartbeat(8000, "a", 5_000, atMs = 20))
    }
}
