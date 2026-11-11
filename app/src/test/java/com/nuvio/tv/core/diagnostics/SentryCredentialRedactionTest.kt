package com.nuvio.tv.core.diagnostics

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import org.junit.Assert.*
import org.junit.Test

class SentryCredentialRedactionTest {
    @Test fun `outgoing diagnostics redact URL credentials and structured secrets`() {
        val url = "https://host/live/alice/private/1.ts?token=secret"
        val event = SentryEvent()
        event.message = Message().apply { message = url; formatted = url; params = listOf(url) }
        event.exceptions = listOf(SentryException().apply { value = "Failed $url" })
        event.addBreadcrumb(Breadcrumb().apply {
            message = url
            setData("refresh_token", "unstructured-secret")
            setData("nested", mapOf("api_key" to "another-secret", "url" to url))
        })
        event.setExtra("password", "unstructured-password")
        event.setExtra("requests", listOf(mapOf("url" to url)))
        event.setTag("access_token", "unstructured-token")

        SentryInitializer.redactEvent(event)

        assertFalse(event.message!!.message!!.contains("private"))
        assertFalse(event.exceptions!!.single().value!!.contains("secret"))
        assertEquals("***", event.breadcrumbs!!.single().data["refresh_token"])
        assertEquals("***", event.getExtra("password"))
        assertEquals("***", event.getTag("access_token"))
        assertFalse(event.breadcrumbs!!.single().data["nested"].toString().contains("another-secret"))
        assertFalse(event.getExtra("requests").toString().contains("alice"))
    }
}
