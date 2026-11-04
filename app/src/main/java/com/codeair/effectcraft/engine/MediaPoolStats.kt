package com.codeair.effectcraft.engine

import org.json.JSONObject

/**
 * Native media-pool cache accounting for one session: decoded-frame
 * count and bytes against the Android budget (256 MiB per session
 * pool, not the upstream 1 GiB default). Parsed where the JSON enters
 * Kotlin; [withinBudget] is false, never hidden, when bytes exceed it.
 */
data class MediaPoolStats(
    val frames: Int,
    val bytes: Long,
    val budgetBytes: Long,
    val hits: Long,
    val misses: Long,
) {
    val withinBudget: Boolean get() = bytes <= budgetBytes

    fun displayText(): String {
        val base = "Media cache · $frames frames · $bytes bytes / $budgetBytes budget"
        return if (withinBudget) base else "$base · OVER BUDGET"
    }

    companion object {
        const val ANDROID_BUDGET_BYTES: Long = 256L * 1024 * 1024

        fun parse(json: String): MediaPoolStats? {
            if (json.isEmpty()) return null
            return runCatching {
                val obj = JSONObject(json)
                val frames = obj.getInt("frames")
                val bytes = obj.getLong("bytes")
                val budget = obj.getLong("budget")
                val hits = obj.getLong("hits")
                val misses = obj.getLong("misses")
                // Treat native accounting as untrusted at the JNI boundary:
                // negative counters and a budget drift (e.g. upstream's 1 GiB
                // default) must not make an invalid pool look within-budget.
                if (frames < 0 || bytes < 0 || budget != ANDROID_BUDGET_BYTES ||
                    hits < 0 || misses < 0
                ) return null
                MediaPoolStats(
                    frames = frames,
                    bytes = bytes,
                    budgetBytes = budget,
                    hits = hits,
                    misses = misses,
                )
            }.getOrNull()
        }
    }
}
