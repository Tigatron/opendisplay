package com.terrynamic.opendisplay.transport

/**
 * Pure listen-socket swap planner. On Linux/Android `0.0.0.0:9000` and
 * `127.0.0.1:9000` share the port, so the only viable swap is close-then-bind.
 * A failed desired bind MUST fall back to the previous host so `listen` never
 * points at a closed socket.
 */
object ListenerSwapPolicy {
    val RETRY_DELAYS_MS = longArrayOf(200L, 500L, 1_000L, 2_000L)
    const val RETRY_CAP_MS = 2_000L

    data class Snapshot(
        val host: String?,
        val open: Boolean,
    )

    sealed class Plan {
        data class AlreadyBound(val host: String) : Plan()
        data class BindFresh(val host: String) : Plan()
        data class CloseThenBind(val desired: String, val fallback: String) : Plan()
    }

    sealed class Result {
        data class BoundDesired(val host: String) : Result()
        data class BoundFallback(
            val desired: String,
            val fallback: String,
            val retryDelayMs: Long,
        ) : Result()
        data class Unbound(val desired: String, val retryDelayMs: Long) : Result()
    }

    fun plan(current: Snapshot, desired: String): Plan {
        if (current.open && current.host == desired) return Plan.AlreadyBound(desired)
        val fallback = current.host
        if (fallback == null || !current.open) return Plan.BindFresh(desired)
        return Plan.CloseThenBind(desired = desired, fallback = fallback)
    }

    fun onDesiredBind(desired: String, succeeded: Boolean, fallback: String?, attempt: Int): Result {
        if (succeeded) return Result.BoundDesired(desired)
        val delay = retryDelayMs(attempt)
        if (!fallback.isNullOrEmpty() && fallback != desired) {
            return Result.BoundFallback(desired, fallback, delay)
        }
        return Result.Unbound(desired, delay)
    }

    fun onFallbackBind(
        desired: String,
        fallback: String,
        succeeded: Boolean,
        attempt: Int,
    ): Result {
        val delay = retryDelayMs(attempt)
        return if (succeeded) {
            Result.BoundFallback(desired, fallback, delay)
        } else {
            Result.Unbound(desired, delay)
        }
    }

    fun retryDelayMs(attempt: Int): Long {
        if (attempt < 0) return RETRY_DELAYS_MS.first()
        if (attempt >= RETRY_DELAYS_MS.size) return RETRY_CAP_MS
        return RETRY_DELAYS_MS[attempt]
    }

    fun acceptMustWait(listenNull: Boolean, serverClosed: Boolean): Boolean =
        listenNull || serverClosed
}
