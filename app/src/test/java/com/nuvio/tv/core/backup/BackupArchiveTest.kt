package com.nuvio.tv.core.backup

import android.util.Base64
import androidx.datastore.preferences.core.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*

class BackupArchiveTest {
    private val password = "a sufficiently long password"
    @Before fun base64() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), any()) } answers { java.util.Base64.getEncoder().encodeToString(firstArg()) }
        every { Base64.decode(any<String>(), any()) } answers { java.util.Base64.getDecoder().decode(firstArg<String>()) }
    }
    @After fun cleanup() { unmockkStatic(Base64::class) }
    private suspend fun sample(): BackupManifest {
        val stores = PortablePreferences.features.associate { "1/$it" to PortablePreferences.encode(emptyPreferences()) }.toMutableMap()
        stores["profile_settings"] = PortablePreferences.encode(mutablePreferencesOf(
            stringPreferencesKey("profiles_json") to """[{"id":1,"name":"Main","avatarColorHex":"#ffffff"}]"""
        ))
        return BackupManifest(createdAtMs = 10, appVersionName = "test", appVersionCode = 1,
            profiles = listOf(BackupProfileManifest(1, "Main", "#ffffff")), portableStores = stores)
    }
    @Test fun `round trip authenticates portable manifest and never includes plaintext secrets`() = runTest {
        val source = sample()
        val secret = """[{"id":"account","name":"IPTV","baseUrl":"https://provider","username":"user","password":"super-secret-value"}]"""
        val snapshot = source.copy(portableStores = source.portableStores + ("1/xtream_accounts" to
            PortablePreferences.encode(mutablePreferencesOf(stringPreferencesKey("xtream_accounts") to secret))))
        val encrypted = BackupArchive.write(snapshot, password)
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("super-secret-value"))
        assertEquals(snapshot, BackupArchive.read(encrypted.inputStream(), password))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { BackupArchive.read(encrypted.inputStream(), password) }.isFailure)
    }
    @Test fun `unknown versions and excessive KDF iterations fail before decryption`() = runTest {
        for (header in listOf(
            BackupFormat.Header(2, 1, 600_000, ByteArray(16), ByteArray(12)),
            BackupFormat.Header(1, 1, Int.MAX_VALUE, ByteArray(16), ByteArray(12)),
            BackupFormat.Header(1, 2, 600_000, ByteArray(16), ByteArray(12))
        )) assertTrue(runCatching { BackupArchive.read((header.toBytes() + ByteArray(16)).inputStream(), password) }.isFailure)
    }
    @Test fun `missing stores traversal paths and invalid profile references are rejected`() = runTest {
        val source = sample()
        for (invalid in listOf(
            source.copy(portableStores = source.portableStores - "1/xtream_accounts"),
            source.copy(portableStores = source.portableStores + ("../escape" to "")),
            source.copy(profiles = listOf(BackupProfileManifest(7, "Invalid", "#ffffff"))),
            source.copy(playlistFiles = mapOf("../escape.m3u" to ""))
        )) assertTrue(runCatching { BackupArchive.validate(invalid) }.isFailure)
    }
    @Test fun `a different valid archive cannot replace the one reviewed before confirmation`() = runTest {
        val original = sample()
        val first = BackupArchive.write(original, password)
        val replacement = BackupArchive.write(original.copy(createdAtMs = 20), password)
        val reviewed = java.security.MessageDigest.getInstance("SHA-256").digest(first)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val error = runCatching { BackupArchive.read(replacement.inputStream(), password, reviewed) }.exceptionOrNull()
        assertEquals("BACKUP_CHANGED", error?.message)
    }

    @Test fun `wrong password cannot decode a valid archive`() = runTest {
        val encrypted = BackupArchive.write(sample(), password)
        assertTrue(runCatching { BackupArchive.read(encrypted.inputStream(), "wrong password") }.exceptionOrNull() is javax.crypto.AEADBadTagException)
    }
}
