package com.terrynamic.opendisplay

data class LivenessTickDecision(
    val send: Boolean,
    val reschedule: Boolean,
    val resume: Boolean = false,
)

/**
 * Ping/stats/link loop. Sending pauses while asleep, but the loop itself
 * must keep scheduling until [stopped] so resume cannot find a dead tick.
 * A missed unlock broadcast is healed when the device is actually unlocked.
 */
object LivenessTickPolicy {
    const val INTERVAL_MS = 500L

    fun decide(
        listeningEnabled: Boolean,
        asleep: Boolean,
        stopped: Boolean,
        deviceUnlocked: Boolean = false,
    ): LivenessTickDecision {
        if (stopped) {
            return LivenessTickDecision(send = false, reschedule = false, resume = false)
        }
        val resume = asleep && deviceUnlocked
        return LivenessTickDecision(
            send = listeningEnabled && (!asleep || resume),
            reschedule = true,
            resume = resume,
        )
    }
}
