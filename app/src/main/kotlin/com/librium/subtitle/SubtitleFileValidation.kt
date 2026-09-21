package com.librium.subtitle

/** Shown when the picked file is not a supported subtitle. */
const val UNSUPPORTED_SUBTITLE_MESSAGE =
    "Unsupported subtitle file. Supported formats: SRT, ASS, SSA, VTT."

/** Shown when a supported file still cannot be loaded or parsed. */
const val SUBTITLE_LOAD_FAILED_MESSAGE =
    "Could not load subtitle. Your current subtitle was not changed."

enum class SubtitleRejectReason {
    MISSING_NAME,
    UNSUPPORTED_EXTENSION,
}

sealed interface SubtitleFileDecision {
    data class Accept(val format: SubtitleFormat) : SubtitleFileDecision
    data class Reject(val reason: SubtitleRejectReason, val detail: String) : SubtitleFileDecision
}

/**
 * Final in-app gate for subtitle selection. Picker MIME filtering varies
 * across Storage Access Framework providers, so this validation — extension
 * based, case-insensitive, never trusting MIME alone — is what actually
 * decides. Pure and unit-tested.
 *
 * Accepted: .srt .ass .ssa .vtt (any case). Everything else — images,
 * video, audio, PDFs, archives, extensionless names — is rejected, and a
 * missing name is rejected unless the URI itself carries a valid extension.
 */
object SubtitleFileValidation {

    fun validate(displayName: String?, uriString: String): SubtitleFileDecision {
        val fromName = extensionOf(displayName)
        if (fromName != null) return decide(fromName)
        val fromUri = extensionOf(uriLastSegment(uriString))
        if (fromUri != null) return decide(fromUri)
        return SubtitleFileDecision.Reject(
            SubtitleRejectReason.MISSING_NAME,
            "no filename with a subtitle extension",
        )
    }

    private fun decide(extension: String): SubtitleFileDecision {
        val format = formatForExtension(extension)
        return if (format != null) {
            SubtitleFileDecision.Accept(format)
        } else {
            SubtitleFileDecision.Reject(
                SubtitleRejectReason.UNSUPPORTED_EXTENSION,
                ".$extension",
            )
        }
    }

    internal fun extensionOf(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val base = name.substringBefore('?').trim()
        if (base.isEmpty() || base.endsWith('.')) return null
        val ext = base.substringAfterLast('.', "")
        if (ext.isEmpty() || ext == base) return null
        return ext.lowercase()
    }

    private fun uriLastSegment(uriString: String): String? {
        if (uriString.isBlank()) return null
        return uriString.substringBefore('?').substringAfterLast('/').ifBlank { null }
    }
}

/** Maps a lowercased extension to its format, or null when unsupported. */
fun formatForExtension(extension: String): SubtitleFormat? = when (extension.lowercase()) {
    "srt" -> SubtitleFormat.SRT
    "ass" -> SubtitleFormat.ASS
    "ssa" -> SubtitleFormat.SSA
    "vtt" -> SubtitleFormat.VTT
    else -> null
}
