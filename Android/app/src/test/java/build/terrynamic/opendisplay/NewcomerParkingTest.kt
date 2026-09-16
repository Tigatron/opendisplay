package build.terrynamic.opendisplay

import build.terrynamic.opendisplay.transport.NewcomerMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewcomerParkingTest {
    @Test
    fun idleAccept_sendsHelloAndAdopts() {
        val machine = NewcomerMachine(parkTimeoutMs = 3_000)
        val actions = machine.onAccept(1, nowMs = 0)
        assertTrue(actions.any { it is NewcomerMachine.Action.SendHello && it.connectionId == 1L })
        assertTrue(actions.any { it is NewcomerMachine.Action.Adopt && it.connectionId == 1L && it.closeSessionId == null })
        assertEquals(NewcomerMachine.State.Live(1), machine.state)
    }

    @Test
    fun liveAccept_parksUntilBytesThenAdopts() {
        val machine = NewcomerMachine(parkTimeoutMs = 3_000)
        machine.onAccept(1, 0)
        val parked = machine.onAccept(2, 100)
        assertTrue(parked.any { it is NewcomerMachine.Action.SendHello && it.connectionId == 2L })
        assertTrue(parked.any { it is NewcomerMachine.Action.ArmTimeout && it.connectionId == 2L })
        assertTrue(parked.none { it is NewcomerMachine.Action.Adopt })

        val proved = machine.onBytes(2, byteArrayOf(1, 2, 3))
        val adopt = proved.filterIsInstance<NewcomerMachine.Action.Adopt>().single()
        assertEquals(2L, adopt.connectionId)
        assertEquals(1L, adopt.closeSessionId)
        assertEquals(3, adopt.initialBytes.size)
        assertEquals(NewcomerMachine.State.Live(2), machine.state)
    }

    @Test
    fun parkedTimeout_discardsNewcomerAndKeepsSession() {
        val machine = NewcomerMachine(parkTimeoutMs = 3_000)
        machine.onAccept(1, 0)
        machine.onAccept(2, 100)
        val actions = machine.onTimeout(2, nowMs = 3_200)
        assertTrue(actions.any { it is NewcomerMachine.Action.Discard && it.connectionId == 2L })
        assertEquals(NewcomerMachine.State.Live(1), machine.state)
    }

    @Test
    fun parkedEof_discardsNewcomerAndKeepsSession() {
        val machine = NewcomerMachine(parkTimeoutMs = 3_000)
        machine.onAccept(1, 0)
        machine.onAccept(2, 100)
        val actions = machine.onClosed(2)
        assertTrue(actions.any { it is NewcomerMachine.Action.Discard && it.connectionId == 2L })
        assertEquals(NewcomerMachine.State.Live(1), machine.state)
    }
}
