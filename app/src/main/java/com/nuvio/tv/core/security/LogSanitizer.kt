package com.nuvio.tv.core.security

import com.nuvio.tv.core.diagnostics.LogRedaction

/** Shared redaction policy for network logs and outgoing diagnostics. */
object LogSanitizer {
    fun sanitize(message: String?): String = LogRedaction.text(message)
}
