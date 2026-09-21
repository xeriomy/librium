package com.librium.subtitle

/**
 * Phase 2 placeholder. Do NOT implement timing adjustment in Phase 1.
 * Playback sync stays with libmpv (`sub-delay`); this contract is reserved
 * for the future engine that shifts/documents subtitle timing itself.
 */
interface SubtitleSynchronizer {
    fun shift(document: SubtitleDocument, offsetMs: Long): SubtitleDocument
}
