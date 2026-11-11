package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.security.SecureCredentialStore
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SecurePreferencesDataStoreTest {
    private val secret = stringPreferencesKey("mdblist_api_key")
    private val other = stringPreferencesKey("display_name")
    private val raw = TestPreferencesStore()
    private val credentials = mockk<SecureCredentialStore>()
    private val envelopes = mutableMapOf<String, Pair<Triple<Int, String, String>, String>>()
    private var failWrite = false
    private var failRead = false
    private var wrongVerification = false

    init {
        coEvery { credentials.seal(any(), any(), any(), any()) } answers {
            if (failWrite) throw IOException("Write failed")
            UUID.randomUUID().toString().also {
                envelopes[it] = Triple(firstArg<Int>(), secondArg<String>(), thirdArg<String>()) to arg<String>(3)
            }
        }
        coEvery { credentials.unseal(any(), any(), any(), any()) } answers {
            val stored = envelopes[arg<String>(3)]
            when {
                failRead -> SecureCredentialStore.ReadResult.KeyUnavailable(IOException("Key unavailable"))
                stored == null || stored.first != Triple(firstArg<Int>(), secondArg<String>(), thirdArg<String>()) ->
                    SecureCredentialStore.ReadResult.Corrupted(IOException("Wrong owner or corrupt envelope"))
                else -> SecureCredentialStore.ReadResult.Success(if (wrongVerification) "wrong value" else stored.second)
            }
        }
    }

    private fun store(profile: Int = 1) = SecurePreferencesDataStore(
        raw, credentials, profile, "mdblist_settings", setOf(secret.name)
    )

    @Test fun `legacy migration removes plaintext and preserves unrelated fields`() = runTest {
        raw.edit { it[secret] = "private-key"; it[other] = "future value" }
        assertEquals("private-key", store().data.first()[secret])
        assertFalse(raw.value[secret]!!.contains("private-key"))
        assertEquals("future value", raw.value[other])
        assertEquals("private-key", store().data.first()[secret])
    }

    @Test fun `failed migration and verification leave the original and retry`() = runTest {
        raw.edit { it[secret] = "original" }
        failWrite = true
        assertTrue(runCatching { store().data.first() }.exceptionOrNull() is IOException)
        assertEquals("original", raw.value[secret])
        failWrite = false
        wrongVerification = true
        assertTrue(runCatching { store().data.first() }.exceptionOrNull() is IOException)
        assertEquals("original", raw.value[secret])
        wrongVerification = false
        assertEquals("original", store().data.first()[secret])
        assertNotEquals("original", raw.value[secret])
    }

    @Test fun `corruption or key loss cannot clear or replace the stored payload`() = runTest {
        store().edit { it[secret] = "original" }
        val encrypted = raw.value
        failRead = true
        assertTrue(runCatching { store().edit { it.clear() } }.exceptionOrNull() is IOException)
        assertEquals(encrypted, raw.value)
        failRead = false
        assertEquals("original", store().data.first()[secret])
        raw.edit { it[secret] = "__tuvora_secure_v1__:damaged" }
        val damaged = raw.value
        assertTrue(runCatching { store().data.first() }.exceptionOrNull() is IOException)
        assertEquals(damaged, raw.value)
    }

    @Test fun `copying persisted bytes to a different owner is rejected`() = runTest {
        store(1).edit { it[secret] = "profile-one" }
        val encrypted = raw.value
        assertTrue(runCatching { store(2).data.first() }.exceptionOrNull() is IOException)
        assertEquals(encrypted, raw.value)
        assertEquals("profile-one", store(1).data.first()[secret])
    }

    @Test fun `logical copies are reencrypted for the destination profile`() = runTest {
        store(1).edit { it[secret] = "portable key" }
        val targetRaw = TestPreferencesStore()
        val target = SecurePreferencesDataStore(targetRaw, credentials, 2, "mdblist_settings", setOf(secret.name))
        target.updateData { store(1).data.first() }
        assertEquals("portable key", target.data.first()[secret])
        assertNotEquals(raw.value[secret], targetRaw.value[secret])
        val replay = SecurePreferencesDataStore(targetRaw, credentials, 1, "mdblist_settings", setOf(secret.name))
        assertTrue(runCatching { replay.data.first() }.exceptionOrNull() is IOException)
    }

    @Test fun `edits are serialized without losing concurrent fields and delete removes envelope`() = runTest {
        val store = store()
        store.edit { it[secret] = "first" }
        val first = raw.value[secret]
        listOf(
            async { store.edit { it[secret] = "second" } },
            async { store.edit { it[other] = "other value" } }
        ).awaitAll()
        assertEquals("second", store.data.first()[secret])
        assertEquals("other value", store.data.first()[other])
        assertNotEquals(first, raw.value[secret])
        store.edit { it.remove(secret) }
        assertNull(raw.value[secret])
    }

    @Test fun `a failed DataStore commit leaves the old ciphertext readable`() = runTest {
        store().edit { it[secret] = "old" }
        val original = raw.value
        val failing = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by raw {
            override suspend fun updateData(
                transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences
            ): androidx.datastore.preferences.core.Preferences {
                transform(raw.value)
                throw IOException("Disk full")
            }
        }
        val wrapped = SecurePreferencesDataStore(failing, credentials, 1, "mdblist_settings", setOf(secret.name))
        assertTrue(runCatching { wrapped.edit { it[secret] = "new" } }.exceptionOrNull() is IOException)
        assertEquals(original, raw.value)
        assertEquals("old", store().data.first()[secret])
    }

    @Test fun `whole IPTV JSON and sync operations are protected without losing unknown fields`() = runTest {
        for (feature in listOf("xtream_accounts", "xtream_sync_state")) {
            val delegate = TestPreferencesStore()
            val wrapped = SecurePreferencesDataStore(delegate, credentials, 1, feature, null)
            val key = stringPreferencesKey(feature)
            val json = """[{"password":"private","future":{"token":"unknown"}}]"""
            wrapped.edit { it[key] = json }
            assertFalse(delegate.value[key]!!.contains("private"))
            assertEquals(json, wrapped.data.first()[key])
        }
    }

    @Test fun `legacy encrypted features are unwrapped to plaintext and persisted back in clear`() = runTest {
        val delegate = TestPreferencesStore()
        val envelope = "envelope-123"
        envelopes[envelope] = Triple(1, "library_preferences", "favs") to """[{"id":"item1"}]"""
        val key = stringPreferencesKey("favs")
        delegate.edit { it[key] = "__tuvora_secure_v1__:$envelope" }

        val legacyStore = LegacyDecryptingDataStore(delegate, credentials, 1, "library_preferences")
        val read = legacyStore.data.first()[key]
        assertEquals("""[{"id":"item1"}]""", read)
        // Check that delegate now has plaintext on disk
        assertEquals("""[{"id":"item1"}]""", delegate.value[key])
    }

    @Test fun `failed unseal in legacy store never erases or replaces original value`() = runTest {
        val delegate = TestPreferencesStore()
        val key = stringPreferencesKey("favs")
        val damagedValue = "__tuvora_secure_v1__:corrupted_envelope"
        delegate.edit { it[key] = damagedValue }

        val legacyStore = LegacyDecryptingDataStore(delegate, credentials, 1, "library_preferences")
        failRead = true
        val read = legacyStore.data.first()[key]
        // Failed unseal preserves original stored value
        assertEquals(damagedValue, read)
        assertEquals(damagedValue, delegate.value[key])
    }

    @Test fun `new writes to legacy store are saved directly in clear`() = runTest {
        val delegate = TestPreferencesStore()
        val legacyStore = LegacyDecryptingDataStore(delegate, credentials, 1, "library_preferences")
        val key = stringPreferencesKey("favs")
        legacyStore.edit { it[key] = """[{"id":"new_item"}]""" }

        assertEquals("""[{"id":"new_item"}]""", delegate.value[key])
        assertFalse(delegate.value[key]!!.startsWith("__tuvora_secure_v1__:"))
    }
}
