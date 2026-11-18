package com.nuvio.tv.core.security

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SecureAuthSessionManagerTest {
    private val session = UserSession(accessToken = "private-access", refreshToken = "private-refresh", expiresIn = 3600, tokenType = "bearer")
    @Test fun `different servers never load each others stored session`() = runTest {
        val store = mockk<SecureCredentialStore>()
        val saved = mutableMapOf<String, String>()
        coEvery { store.write(0, "main_auth", any(), any()) } answers { saved[arg(2)] = arg(3) }
        coEvery { store.read(0, "main_auth", any()) } answers {
            saved[arg<String>(2)]?.let { SecureCredentialStore.ReadResult.Success(it) }
                ?: SecureCredentialStore.ReadResult.NotFound
        }
        val legacyA = mockk<SessionManager>(relaxed = true)
        val legacyB = mockk<SessionManager>()
        coEvery { legacyB.loadSession() } throws IOException("No session")
        val first = SecureAuthSessionManager(store, "https://personal.test", legacyA)
        val other = SecureAuthSessionManager(store, "https://official.test", legacyB)
        first.saveSession(session)
        assertEquals(session, first.loadSession())
        assertTrue(runCatching { other.loadSession() }.isFailure)
        assertEquals(1, saved.size)
    }

    @Test fun `legacy session is removed only after a verified encrypted write`() = runTest {
        val store = mockk<SecureCredentialStore>()
        val legacy = mockk<SessionManager>()
        var encoded: String? = null
        coEvery { store.read(0, "main_auth", any()) } answers {
            encoded?.let { SecureCredentialStore.ReadResult.Success(it) } ?: SecureCredentialStore.ReadResult.NotFound
        }
        coEvery { store.write(0, "main_auth", any(), any()) } answers { encoded = arg(3) }
        coEvery { legacy.loadSession() } returns session
        coEvery { legacy.deleteSession() } returns Unit
        val manager = SecureAuthSessionManager(store, "https://backend.test", legacy)
        assertEquals(session, manager.loadSession())
        coVerify(exactly = 1) { legacy.deleteSession() }
        assertEquals(session, manager.loadSession())
        coVerify(exactly = 1) { legacy.loadSession() }
    }
    @Test fun `failed migration preserves the legacy session and can retry`() = runTest {
        val store = mockk<SecureCredentialStore>()
        val legacy = mockk<SessionManager>()
        coEvery { store.read(any(), any(), any()) } returns SecureCredentialStore.ReadResult.NotFound
        coEvery { store.write(any(), any(), any(), any()) } throws IOException("disk full")
        coEvery { legacy.loadSession() } returns session
        val manager = SecureAuthSessionManager(store, "https://backend.test", legacy)
        assertTrue(runCatching { manager.loadSession() }.isFailure)
        coVerify(exactly = 0) { legacy.deleteSession() }
    }
    @Test fun `unreadable ciphertext cannot fall back to stale plaintext or delete a session`() = runTest {
        val store = mockk<SecureCredentialStore>()
        val legacy = mockk<SessionManager>()
        coEvery { store.read(any(), any(), any()) } returns SecureCredentialStore.ReadResult.KeyUnavailable(IOException())
        val manager = SecureAuthSessionManager(store, "https://backend.test", legacy)
        assertTrue(runCatching { manager.loadSession() }.isFailure)
        coVerify(exactly = 0) { legacy.loadSession() }
        coVerify(exactly = 0) { store.delete(any(), any(), any()) }
    }
}
