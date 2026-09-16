package build.terrynamic.opendisplay.transport

import build.terrynamic.opendisplay.protocol.WireProtocol

/**
 * Pure newcomer-parking state machine (no sockets). PROTOCOL.md §1 plus the
 * official receiver's "prove yourself with bytes" rule for twin dials.
 */
class NewcomerMachine(
    private val parkTimeoutMs: Long = WireProtocol.NEWCOMER_PARK_MS,
) {
    sealed class State {
        data object Idle : State()
        data class Live(val sessionId: Long) : State()
        data class Parking(
            val sessionId: Long,
            val newcomers: Map<Long, Long>,
        ) : State()
    }

    sealed class Action {
        data class SendHello(val connectionId: Long) : Action()
        data class Adopt(
            val connectionId: Long,
            val initialBytes: ByteArray = ByteArray(0),
            val closeSessionId: Long? = null,
        ) : Action()
        data class Discard(val connectionId: Long) : Action()
        data class ArmTimeout(val connectionId: Long, val timeoutMs: Long) : Action()
        data class CancelTimeout(val connectionId: Long) : Action()
    }

    var state: State = State.Idle
        private set

    val liveSessionId: Long?
        get() = when (val current = state) {
            is State.Idle -> null
            is State.Live -> current.sessionId
            is State.Parking -> current.sessionId
        }

    fun onAccept(connectionId: Long, nowMs: Long): List<Action> {
        val hello = Action.SendHello(connectionId)
        return when (val current = state) {
            State.Idle -> {
                state = State.Live(connectionId)
                listOf(hello, Action.Adopt(connectionId))
            }
            is State.Live -> park(current.sessionId, connectionId, nowMs, hello)
            is State.Parking -> park(current.sessionId, connectionId, nowMs, hello, current.newcomers)
        }
    }

    fun onBytes(connectionId: Long, bytes: ByteArray): List<Action> {
        if (bytes.isEmpty()) return emptyList()
        return when (val current = state) {
            State.Idle -> emptyList()
            is State.Live -> if (connectionId == current.sessionId) emptyList() else emptyList()
            is State.Parking -> {
                if (connectionId !in current.newcomers) return emptyList()
                val actions = mutableListOf<Action>(Action.CancelTimeout(connectionId))
                for (other in current.newcomers.keys) {
                    if (other != connectionId) {
                        actions += Action.CancelTimeout(other)
                        actions += Action.Discard(other)
                    }
                }
                actions += Action.Adopt(
                    connectionId = connectionId,
                    initialBytes = bytes,
                    closeSessionId = current.sessionId,
                )
                state = State.Live(connectionId)
                actions
            }
        }
    }

    fun onClosed(connectionId: Long): List<Action> {
        return when (val current = state) {
            State.Idle -> emptyList()
            is State.Live -> {
                if (connectionId != current.sessionId) return emptyList()
                state = State.Idle
                emptyList()
            }
            is State.Parking -> {
                if (connectionId == current.sessionId) {
                    val actions = mutableListOf<Action>()
                    for (id in current.newcomers.keys) {
                        actions += Action.CancelTimeout(id)
                    }
                    state = State.Parking(sessionId = -1L, newcomers = current.newcomers)
                    // Live session died; parked newcomers must still prove themselves.
                    return actions
                }
                if (connectionId !in current.newcomers) return emptyList()
                val remaining = current.newcomers - connectionId
                state = if (remaining.isEmpty() && current.sessionId >= 0) {
                    State.Live(current.sessionId)
                } else if (remaining.isEmpty()) {
                    State.Idle
                } else {
                    current.copy(newcomers = remaining)
                }
                listOf(Action.CancelTimeout(connectionId), Action.Discard(connectionId))
            }
        }
    }

    fun onTimeout(connectionId: Long, nowMs: Long): List<Action> {
        val current = state as? State.Parking ?: return emptyList()
        val deadline = current.newcomers[connectionId] ?: return emptyList()
        if (nowMs < deadline) return emptyList()
        val remaining = current.newcomers - connectionId
        state = when {
            remaining.isEmpty() && current.sessionId >= 0 -> State.Live(current.sessionId)
            remaining.isEmpty() -> State.Idle
            else -> current.copy(newcomers = remaining)
        }
        return listOf(Action.CancelTimeout(connectionId), Action.Discard(connectionId))
    }

    fun reset() {
        state = State.Idle
    }

    private fun park(
        sessionId: Long,
        connectionId: Long,
        nowMs: Long,
        hello: Action.SendHello,
        existing: Map<Long, Long> = emptyMap(),
    ): List<Action> {
        val deadline = nowMs + parkTimeoutMs
        state = State.Parking(sessionId, existing + (connectionId to deadline))
        return listOf(hello, Action.ArmTimeout(connectionId, parkTimeoutMs))
    }
}
