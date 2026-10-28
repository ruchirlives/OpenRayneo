package com.openrayneo.android

/** Only a leading explicit address to Codex can become a task. */
internal object GlassesVoiceCommand {
    private val prefix = Regex("^(?:hey\\s+)?code(?:x|cs|cks|\\s+x)\\b[\\s,.:;!?-]*", RegexOption.IGNORE_CASE)
    fun request(transcript: String): String? {
        val trimmed = transcript.trim()
        val match = prefix.find(trimmed) ?: return null
        return trimmed.substring(match.range.last + 1).trim().takeIf { it.isNotBlank() }
    }
}
