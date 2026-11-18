package com.nuvio.tv.core.network

import org.junit.Assert.*
import org.junit.Test

class BackendWorkspacePolicyTest {
    private fun backend(url: String = "https://example.test", key: String = "public") =
        SyncBackendConfig("hosted", "Personal", url, key, "$url/avatars",
            tvLoginWebBaseUrl = "https://login.example.test/approve",
            allowedQrOrigins = setOf("https://login.example.test"))

    @Test fun keyRotationKeepsWorkspaceButDifferentEndpointDoesNot() {
        assertEquals(BackendWorkspaceStore.identity(backend()), BackendWorkspaceStore.identity(backend(key = "rotated")))
        assertEquals(BackendWorkspaceStore.identity(backend()), BackendWorkspaceStore.identity(backend(url = "https://example.test/")))
        assertNotEquals(BackendWorkspaceStore.identity(backend()), BackendWorkspaceStore.identity(backend(url = "https://other.test")))
        assertNotEquals(BackendWorkspaceStore.identity(backend()), BackendWorkspaceStore.identity(backend().copy(id = "nuvio")))
    }

    @Test fun qrRequiresExactHttpsOriginWithoutCredentials() {
        val config = backend()
        assertTrue(config.acceptsQrUrl("https://login.example.test/approve?code=abc"))
        assertTrue(config.acceptsQrUrl("https://login.example.test:443/approve?code=abc"))
        listOf("http://login.example.test/", "https://login.example.test.evil.test/",
            "https://login.example.test@evil.test/", "https://user@login.example.test/",
            "https://login.example.test:8443/", "https://login.example.test/#token=secret",
            "javascript:alert(1)", "not a url").forEach { assertFalse(it, config.acceptsQrUrl(it)) }
    }
}
