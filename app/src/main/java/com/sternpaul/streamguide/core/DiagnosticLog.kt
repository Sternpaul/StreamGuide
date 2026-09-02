package com.sternpaul.streamguide.core

data class DiagnosticLogEntry(
    val timestampEpochMs: Long,
    val area: String,
    val message: String
)

object DiagnosticMessageSanitizer {
    private val querySecret = Regex("(?i)(username|password|token|api[_-]?key)=([^&\\s]+)")
    private val urlUserInfo = Regex("(https?://)([^/@\\s]+):([^/@\\s]+)@", RegexOption.IGNORE_CASE)

    fun sanitize(raw: String): String {
        val singleLine = raw.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return urlUserInfo.replace(querySecret.replace(singleLine) { "${it.groupValues[1]}=•••" }) {
            "${it.groupValues[1]}•••:•••@"
        }.take(300).ifBlank { "Unknown error" }
    }
}
