package com.terrynamic.opendisplay.protocol

object Percentiles {
    /**
     * Nearest-rank percentile matching the official receiver:
     * `sorted[min(n-1, Int(n * p))]`. Empty → 0.
     */
    fun of(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = ((sorted.size * p).toInt()).coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }
}
