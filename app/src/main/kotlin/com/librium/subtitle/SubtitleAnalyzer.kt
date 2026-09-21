package com.librium.subtitle

/**
 * Structured subtitle analysis. Analysis never modifies the document;
 * use [SubtitleTimingOps] or the editor operations for fixes.
 */
enum class IssueSeverity {
    ERROR,
    WARNING,
    INFO,
}

enum class IssueType {
    OVERLAP,
    ZERO_DURATION,
    NEGATIVE_DURATION,
    TOO_SHORT,
    TOO_LONG,
    SHORT_GAP,
    LONG_GAP,
    BEYOND_VIDEO_END,
    EMPTY_TEXT,
    DUPLICATE_TEXT,
    LONG_LINE,
    TOO_MANY_LINES,
    HIGH_CPS,
    HIGH_WPM,
}

data class SubtitleIssue(
    val type: IssueType,
    val severity: IssueSeverity,
    val eventId: Int?,
    val message: String,
    val suggestedFix: String? = null,
)

data class SubtitleStatistics(
    val eventCount: Int,
    /** First start to last end across all events, 0 when empty. */
    val spanMs: Long,
    /** Sum of positive event durations. */
    val visibleMs: Long,
    val avgDurationMs: Double,
    val minDurationMs: Long,
    val maxDurationMs: Long,
    val avgCps: Double,
    val maxCps: Double,
    val maxCpsEventId: Int?,
    val avgWpm: Double,
    val maxWpm: Double,
    val totalCharacters: Long,
    val totalWords: Long,
    val overlapCount: Int,
)

data class SubtitleAnalysisResult(
    val errors: List<SubtitleIssue>,
    val warnings: List<SubtitleIssue>,
    val info: List<SubtitleIssue>,
    val statistics: SubtitleStatistics,
) {
    val all: List<SubtitleIssue> get() = errors + warnings + info
    val hasErrors: Boolean get() = errors.isNotEmpty()
    val issueCount: Int get() = all.size
}

data class AnalyzerOptions(
    val minDurationMs: Long = 800L,
    val maxDurationMs: Long = 7_000L,
    val maxCps: Double = 20.0,
    val maxWpm: Double = 200.0,
    val maxLineLength: Int = 42,
    val maxLinesPerEvent: Int = 2,
    val minGapMs: Long = 120L,
    val maxGapMs: Long = 30_000L,
)

interface SubtitleAnalyzer {
    fun analyze(
        document: SubtitleDocument,
        videoDurationMs: Long? = null,
    ): SubtitleAnalysisResult
}

class DefaultSubtitleAnalyzer(
    private val options: AnalyzerOptions = AnalyzerOptions(),
) : SubtitleAnalyzer {

    override fun analyze(
        document: SubtitleDocument,
        videoDurationMs: Long?,
    ): SubtitleAnalysisResult {
        val errors = mutableListOf<SubtitleIssue>()
        val warnings = mutableListOf<SubtitleIssue>()
        val info = mutableListOf<SubtitleIssue>()
        val events = document.events
        if (events.isEmpty()) {
            return SubtitleAnalysisResult(
                errors = errors,
                warnings = warnings,
                info = info,
                statistics = emptyStatistics(),
            )
        }
        val sorted = events.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.id }))

        var overlapCount = 0
        var totalChars = 0L
        var totalWords = 0L
        var cpsSum = 0.0
        var cpsCount = 0
        var maxCps = 0.0
        var maxCpsEventId: Int? = null
        var wpmSum = 0.0
        var wpmCount = 0
        var maxWpm = 0.0
        var minDuration = Long.MAX_VALUE
        var maxDuration = Long.MIN_VALUE
        var durationSum = 0L
        var visibleSum = 0L

        val seenTexts = linkedMapOf<String, Int>()

        for ((position, event) in sorted.withIndex()) {
            val dur = event.endMs - event.startMs
            durationSum += dur
            if (dur > 0) visibleSum += dur
            if (dur < minDuration) minDuration = dur
            if (dur > maxDuration) maxDuration = dur

            // --- timing issues ---
            when {
                dur < 0 -> errors.add(
                    SubtitleIssue(
                        type = IssueType.NEGATIVE_DURATION,
                        severity = IssueSeverity.ERROR,
                        eventId = event.id,
                        message = "Event #${event.id} ends before it starts " +
                            "(${formatSrtTimestamp(event.startMs)} --> " +
                            "${formatSrtTimestamp(event.endMs)}).",
                        suggestedFix = "Swap the timestamps or re-time the event.",
                    ),
                )
                dur == 0L -> errors.add(
                    SubtitleIssue(
                        type = IssueType.ZERO_DURATION,
                        severity = IssueSeverity.ERROR,
                        eventId = event.id,
                        message = "Event #${event.id} has zero duration.",
                        suggestedFix = "Give the event a positive duration.",
                    ),
                )
                dur < options.minDurationMs -> warnings.add(
                    SubtitleIssue(
                        type = IssueType.TOO_SHORT,
                        severity = IssueSeverity.WARNING,
                        eventId = event.id,
                        message = "Event #${event.id} lasts ${dur} ms, below " +
                            "${options.minDurationMs} ms.",
                        suggestedFix = "Extend the end time or merge with a neighbor.",
                    ),
                )
            }
            if (dur > options.maxDurationMs) {
                warnings.add(
                    SubtitleIssue(
                        type = IssueType.TOO_LONG,
                        severity = IssueSeverity.WARNING,
                        eventId = event.id,
                        message = "Event #${event.id} lasts ${dur} ms, above " +
                            "${options.maxDurationMs} ms.",
                        suggestedFix = "Split the event into shorter cues.",
                    ),
                )
            }
            if (position > 0) {
                val prev = sorted[position - 1]
                if (event.startMs < prev.endMs) {
                    overlapCount++
                    errors.add(
                        SubtitleIssue(
                            type = IssueType.OVERLAP,
                            severity = IssueSeverity.ERROR,
                            eventId = event.id,
                            message = "Event #${event.id} starts ${prev.endMs - event.startMs} ms " +
                                "before event #${prev.id} ends.",
                            suggestedFix = "Move the start to " +
                                "${formatSrtTimestamp(prev.endMs)} or shorten event #${prev.id}.",
                        ),
                    )
                } else {
                    val gap = event.startMs - prev.endMs
                    if (gap < options.minGapMs && dur > 0 && prev.endMs - prev.startMs > 0) {
                        warnings.add(
                            SubtitleIssue(
                                type = IssueType.SHORT_GAP,
                                severity = IssueSeverity.WARNING,
                                eventId = event.id,
                                message = "Only ${gap} ms between event #${prev.id} and " +
                                    "event #${event.id}; viewers may see flashing.",
                                suggestedFix = "Leave at least ${options.minGapMs} ms between cues.",
                            ),
                        )
                    } else if (gap > options.maxGapMs) {
                        info.add(
                            SubtitleIssue(
                                type = IssueType.LONG_GAP,
                                severity = IssueSeverity.INFO,
                                eventId = event.id,
                                message = "Long silence of ${gap / 1_000} s before event #${event.id}.",
                                suggestedFix = null,
                            ),
                        )
                    }
                }
            }
            if (videoDurationMs != null && event.endMs > videoDurationMs) {
                warnings.add(
                    SubtitleIssue(
                        type = IssueType.BEYOND_VIDEO_END,
                        severity = IssueSeverity.WARNING,
                        eventId = event.id,
                        message = "Event #${event.id} ends at " +
                            "${formatSrtTimestamp(event.endMs)}, past the video end " +
                            "(${formatSrtTimestamp(videoDurationMs)}).",
                        suggestedFix = "Trim the event to the video duration.",
                    ),
                )
            }

            // --- text issues ---
            if (event.text.isBlank()) {
                errors.add(
                    SubtitleIssue(
                        type = IssueType.EMPTY_TEXT,
                        severity = IssueSeverity.ERROR,
                        eventId = event.id,
                        message = "Event #${event.id} has no text.",
                        suggestedFix = "Add text or delete the event.",
                    ),
                )
            } else {
                val lines = event.text.split("\n")
                if (lines.size > options.maxLinesPerEvent) {
                    warnings.add(
                        SubtitleIssue(
                            type = IssueType.TOO_MANY_LINES,
                            severity = IssueSeverity.WARNING,
                            eventId = event.id,
                            message = "Event #${event.id} has ${lines.size} lines " +
                                "(max ${options.maxLinesPerEvent}).",
                            suggestedFix = "Split the event.",
                        ),
                    )
                }
                val longest = lines.maxOfOrNull { it.length } ?: 0
                if (longest > options.maxLineLength) {
                    warnings.add(
                        SubtitleIssue(
                            type = IssueType.LONG_LINE,
                            severity = IssueSeverity.WARNING,
                            eventId = event.id,
                            message = "Event #${event.id} has a ${longest}-character line " +
                                "(max ${options.maxLineLength}).",
                            suggestedFix = "Break the line or shorten the text.",
                        ),
                    )
                }
                val normalized = event.text.lowercase().split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }.joinToString(" ")
                val firstSeen = seenTexts[normalized]
                if (firstSeen == null) {
                    seenTexts[normalized] = event.id
                } else {
                    warnings.add(
                        SubtitleIssue(
                            type = IssueType.DUPLICATE_TEXT,
                            severity = IssueSeverity.WARNING,
                            eventId = event.id,
                            message = "Event #${event.id} repeats the text of event #$firstSeen.",
                            suggestedFix = "Remove the duplicate if it is unintentional.",
                        ),
                    )
                }

                // --- reading speed ---
                if (dur > 0) {
                    val seconds = dur / 1_000.0
                    val chars = event.metricText().length
                    val words = event.text.split(Regex("\\s+")).count { it.isNotEmpty() }
                    totalChars += chars
                    totalWords += words
                    val cps = chars / seconds
                    val wpm = words / (seconds / 60.0)
                    cpsSum += cps
                    cpsCount++
                    wpmSum += wpm
                    wpmCount++
                    if (cps > maxCps) {
                        maxCps = cps
                        maxCpsEventId = event.id
                    }
                    if (wpm > maxWpm) maxWpm = wpm
                    if (cps > options.maxCps) {
                        warnings.add(
                            SubtitleIssue(
                                type = IssueType.HIGH_CPS,
                                severity = IssueSeverity.WARNING,
                                eventId = event.id,
                                message = "Event #${event.id} reads at " +
                                    "${"%.1f".format(cps)} chars/sec " +
                                    "(max ${"%.1f".format(options.maxCps)}).",
                                suggestedFix = "Shorten the text or extend the duration.",
                            ),
                        )
                    }
                    if (wpm > options.maxWpm) {
                        warnings.add(
                            SubtitleIssue(
                                type = IssueType.HIGH_WPM,
                                severity = IssueSeverity.WARNING,
                                eventId = event.id,
                                message = "Event #${event.id} reads at " +
                                    "${"%.0f".format(wpm)} words/min " +
                                    "(max ${"%.0f".format(options.maxWpm)}).",
                                suggestedFix = "Shorten the text or extend the duration.",
                            ),
                        )
                    }
                }
            }
        }

        val count = events.size
        return SubtitleAnalysisResult(
            errors = errors,
            warnings = warnings,
            info = info,
            statistics = SubtitleStatistics(
                eventCount = count,
                spanMs = (sorted.maxOf { it.endMs } - sorted.minOf { it.startMs })
                    .coerceAtLeast(0L),
                visibleMs = visibleSum,
                avgDurationMs = durationSum.toDouble() / count,
                minDurationMs = if (minDuration == Long.MAX_VALUE) 0L else minDuration,
                maxDurationMs = if (maxDuration == Long.MIN_VALUE) 0L else maxDuration,
                avgCps = if (cpsCount > 0) cpsSum / cpsCount else 0.0,
                maxCps = maxCps,
                maxCpsEventId = maxCpsEventId,
                avgWpm = if (wpmCount > 0) wpmSum / wpmCount else 0.0,
                maxWpm = maxWpm,
                totalCharacters = totalChars,
                totalWords = totalWords,
                overlapCount = overlapCount,
            ),
        )
    }

    private fun emptyStatistics(): SubtitleStatistics = SubtitleStatistics(
        eventCount = 0,
        spanMs = 0L,
        visibleMs = 0L,
        avgDurationMs = 0.0,
        minDurationMs = 0L,
        maxDurationMs = 0L,
        avgCps = 0.0,
        maxCps = 0.0,
        maxCpsEventId = null,
        avgWpm = 0.0,
        maxWpm = 0.0,
        totalCharacters = 0L,
        totalWords = 0L,
        overlapCount = 0,
    )
}
