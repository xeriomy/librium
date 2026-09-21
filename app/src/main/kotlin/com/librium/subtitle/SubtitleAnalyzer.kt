package com.librium.subtitle

/**
 * Phase 2 placeholder. Do NOT implement analysis logic in Phase 1.
 * Captures the intended direction (timing stats, overlap detection) so the
 * future engine has a stable contract to implement against.
 */
interface SubtitleAnalyzer {
    fun analyze(document: SubtitleDocument): SubtitleAnalysis
}

data class SubtitleAnalysis(
    val eventCount: Int,
    val overlappingEventCount: Int = 0,
    val notes: List<String> = emptyList(),
)
