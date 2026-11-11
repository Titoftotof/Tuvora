package com.nuvio.tv.core.security

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying [SecureCredentialStore] contract with an in-memory test double.
 */
class SecureCredentialStoreTest {

    private class InMemorySecureCredentialStore : SecureCredentialStore {
        private val storage = mutableMapOf<Triple<Int, String, String>, String>()
        var simulateCorrupted = false
        var simulateKeyUnavailable = false

        override suspend fun read(profileId: Int, service: String, ref: String): SecureCredentialStore.ReadResult {
            if (simulateKeyUnavailable) {
                return SecureCredentialStore.ReadResult.KeyUnavailable(IllegalStateException("Simulated keystore loss"))
            }
            if (simulateCorrupted) {
                return SecureCredentialStore.ReadResult.Corrupted(IllegalArgumentException("Simulated corruption"))
            }
            val key = Triple(profileId, service, ref)
            val value = storage[key] ?: return SecureCredentialStore.ReadResult.NotFound
            return SecureCredentialStore.ReadResult.Success(value)
        }

        override suspend fun write(profileId: Int, service: String, ref: String, value: String) {
            storage[Triple(profileId, service, ref)] = value
        }

        override suspend fun delete(profileId: Int, service: String, ref: String) {
            storage.remove(Triple(profileId, service, ref))
        }

        override suspend fun deleteAllForProfile(profileId: Int) {
            storage.keys.filter { it.first == profileId }.forEach { storage.remove(it) }
        }

        override suspend fun hasAnyForProfile(profileId: Int): Boolean {
            return storage.keys.any { it.first == profileId }
        }

        override suspend fun readAllForProfile(
            profileId: Int,
            onDecryptionError: ((String, String, Throwable) -> Unit)?
        ): Map<Pair<String, String>, String> {
            return storage.filterKeys { it.first == profileId }
                .mapKeys { (k, _) -> k.second to k.third }
        }
    }

    private lateinit var store: InMemorySecureCredentialStore

    @Before
    fun setUp() {
        store = InMemorySecureCredentialStore()
    }

    @Test
    fun `read non-existent credential returns NotFound`() = runTest {
        val result = store.read(profileId = 1, service = "iptv", ref = "account-1")
        assertTrue(result is SecureCredentialStore.ReadResult.NotFound)
    }

    @Test
    fun `write and read credential returns Success with exact value`() = runTest {
        store.write(profileId = 1, service = "iptv", ref = "account-1", value = "super-secret-password")
        val result = store.read(profileId = 1, service = "iptv", ref = "account-1")
        assertTrue(result is SecureCredentialStore.ReadResult.Success)
        assertEquals("super-secret-password", (result as SecureCredentialStore.ReadResult.Success).value)
    }

    @Test
    fun `credentials are isolated between profiles`() = runTest {
        store.write(profileId = 1, service = "iptv", ref = "account-1", value = "profile-1-secret")
        store.write(profileId = 2, service = "iptv", ref = "account-1", value = "profile-2-secret")

        val p1 = store.read(profileId = 1, service = "iptv", ref = "account-1")
        val p2 = store.read(profileId = 2, service = "iptv", ref = "account-1")

        assertEquals("profile-1-secret", (p1 as SecureCredentialStore.ReadResult.Success).value)
        assertEquals("profile-2-secret", (p2 as SecureCredentialStore.ReadResult.Success).value)
    }

    @Test
    fun `delete removes specific credential without affecting others`() = runTest {
        store.write(profileId = 1, service = "iptv", ref = "account-1", value = "secret-1")
        store.write(profileId = 1, service = "iptv", ref = "account-2", value = "secret-2")

        store.delete(profileId = 1, service = "iptv", ref = "account-1")

        assertTrue(store.read(profileId = 1, service = "iptv", ref = "account-1") is SecureCredentialStore.ReadResult.NotFound)
        assertEquals("secret-2", (store.read(profileId = 1, service = "iptv", ref = "account-2") as SecureCredentialStore.ReadResult.Success).value)
    }

    @Test
    fun `deleteAllForProfile removes all credentials for target profile only`() = runTest {
        store.write(profileId = 1, service = "iptv", ref = "account-1", value = "secret-1")
        store.write(profileId = 1, service = "debrid", ref = "torbox", value = "api-key-1")
        store.write(profileId = 2, service = "iptv", ref = "account-2", value = "secret-2")

        assertTrue(store.hasAnyForProfile(1))
        store.deleteAllForProfile(1)
        assertFalse(store.hasAnyForProfile(1))

        assertTrue(store.hasAnyForProfile(2))
        assertEquals("secret-2", (store.read(profileId = 2, service = "iptv", ref = "account-2") as SecureCredentialStore.ReadResult.Success).value)
    }

    @Test
    fun `corrupted ciphertext returns Corrupted result and is not deleted`() = runTest {
        store.write(profileId = 1, service = "iptv", ref = "account-1", value = "secret-1")
        store.simulateCorrupted = true

        val result = store.read(profileId = 1, service = "iptv", ref = "account-1")
        assertTrue(result is SecureCredentialStore.ReadResult.Corrupted)

        store.simulateCorrupted = false
        // Data is still present and decodable after corruption is cleared
        val retry = store.read(profileId = 1, service = "iptv", ref = "account-1")
        assertTrue(retry is SecureCredentialStore.ReadResult.Success)
    }

    @Test
    fun `key unavailable returns KeyUnavailable without wiping data`() = runTest {
        store.write(profileId = 1, service = "iptv", ref = "account-1", value = "secret-1")
        store.simulateKeyUnavailable = true

        val result = store.read(profileId = 1, service = "iptv", ref = "account-1")
        assertTrue(result is SecureCredentialStore.ReadResult.KeyUnavailable)

        store.simulateKeyUnavailable = false
        val retry = store.read(profileId = 1, service = "iptv", ref = "account-1")
        assertTrue(retry is SecureCredentialStore.ReadResult.Success)
    }
}
