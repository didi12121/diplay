package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionLogger

/**
 * Redaction-aware diagnostics for the CarLink backend.
 *
 * CarLink logs must never contain Wi-Fi passwords, tokens, certificates, private
 * keys, authentication data or user privacy content. Callers pass short event
 * names and non-sensitive scalars only; anything that looks like a secret is
 * dropped by [redact] before logging.
 */
class CarLinkDiagnostics(
    private val logger: ProjectionLogger = ProjectionLogger.NONE,
    private val backendId: String = CarLinkProjectionBackend.ID,
) {
    fun event(name: String, detail: String = "") {
        val safeDetail = redact(detail)
        logger.log("backend=$backendId $name${if (safeDetail.isEmpty()) "" else " $safeDetail"}")
    }

    companion object {
        /** Key/value fragments that must never reach a log line. */
        private val SECRET_KEYS = listOf(
            "password", "passphrase", "psk", "token", "secret", "key", "certificate",
            "credential", "auth", "private", "signature", "pin",
        )

        /**
         * Drops any `key=value` or `key: value` fragment whose key matches a
         * secret-ish name, and drops bare hex blobs that look like key material.
         */
        fun redact(input: String): String {
            if (input.isEmpty()) return input
            val words = input.split(Regex("\\s+"))
            val kept = words.filter { word ->
                val key = word.substringBefore('=', word.substringBefore(':')).lowercase()
                SECRET_KEYS.none { key.contains(it) } && !looksLikeKeyMaterial(word)
            }
            return kept.joinToString(" ")
        }

        private fun looksLikeKeyMaterial(word: String): Boolean {
            val value = word.substringAfter('=', word.substringAfter(':', word))
            return value.length >= 32 && value.all { it.isHex() }
        }

        private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
