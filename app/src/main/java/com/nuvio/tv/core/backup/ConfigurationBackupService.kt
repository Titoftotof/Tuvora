package com.nuvio.tv.core.backup

import android.content.Context
import androidx.datastore.preferences.core.*
import com.google.gson.Gson
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.PortableProfilePins
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigurationBackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    private val locks: ProfileLockStateDataStore,
    private val pins: PortableProfilePins,
    private val auth: AuthManager,
    private val playlistFiles: com.nuvio.tv.core.iptv.content.M3UFileStore,
    private val profileSync: com.nuvio.tv.core.sync.ProfileSyncService
) : BackupService, RestoreService {
    private val mutex = Mutex()
    private val gson = Gson()

    private suspend fun snapshot(): BackupManifest = com.nuvio.tv.data.local.ConfigurationSnapshotGate.mutex.withLock {
        check(!com.nuvio.tv.core.profile.ConfigurationMutation.active) { "Configuration is being copied; retry backup" }
        val entries = profiles.profilesList.first()
        val lockStates = if (auth.canSync) profileSync.pullProfileLockStates().getOrThrow() else locks.pinEnabled.first()
        for (profile in entries) {
            check(lockStates[profile.id] != true || pins.has(profile.id)) { "PIN_REQUIRED:${profile.name}" }
        }
        val stores = linkedMapOf<String, String>()
        for (profile in entries) {
            for (feature in PortablePreferences.features) {
                val logical = factory.get(profile.id, feature).data.first().toMutablePreferences()
                if (feature == "player_settings") logical.remove(stringPreferencesKey("last_playback_diagnostics_json"))
                stores["${profile.id}/$feature"] = PortablePreferences.encode(logical.toPreferences())
            }
        }
        val meta = profiles.backupSnapshot().toMutablePreferences()
        stores["profile_settings"] = PortablePreferences.encode(meta.toPreferences())
        check(profiles.profilesList.first() == entries) { "Profiles changed; retry backup" }
        val attachments = linkedMapOf<String, String>()
        var attachmentBytes = 0
        for (profile in entries) {
            val prefs = PortablePreferences.decode(stores.getValue("${profile.id}/xtream_accounts"))
            val accounts = com.nuvio.tv.data.local.decodeXtreamAccountsJson(gson, prefs[stringPreferencesKey("xtream_accounts")])
            for (account in accounts.filter { it.sourceType == com.nuvio.tv.core.iptv.XtreamAccount.SOURCE_FILE }) {
                if (!playlistFiles.exists(account.id)) continue
                val file = playlistFiles.fileFor(account.id)
                if (file.name in attachments) continue
                val bytes = playlistFiles.open(account.id).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(attachmentBytes.toLong() + out.size() + count <= 16 * 1024 * 1024) { "PLAYLIST_TOO_LARGE" }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                attachmentBytes += bytes.size
                attachments[file.name] = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                bytes.fill(0)
            }
        }
        BackupManifest(
            createdAtMs = System.currentTimeMillis(),
            appVersionName = BuildConfig.VERSION_NAME, appVersionCode = BuildConfig.VERSION_CODE,
            profiles = entries.map { BackupProfileManifest(it.id, it.name, it.avatarColorHex, it.avatarId) },
            portableStores = stores, playlistFiles = attachments
        )
    }

    override suspend fun exportBackup(password: String, outputStream: OutputStream): BackupService.BackupResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (password.length < BackupFormat.MIN_PASSWORD_LENGTH)
                    return@withLock BackupService.BackupResult.PasswordTooShort(BackupFormat.MIN_PASSWORD_LENGTH)
                try {
                    val manifest = snapshot()
                    BackupArchive.validate(manifest)
                    val bytes = BackupArchive.write(manifest, password)
                    outputStream.write(bytes)
                    outputStream.flush()
                    BackupService.BackupResult.Success(bytes.size.toLong())
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { BackupService.BackupResult.Failed(e) }
            }
        }

    override suspend fun previewBackup(inputStream: InputStream, password: String): RestoreService.PreviewResult =
        withContext(Dispatchers.IO) {
            try {
                val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                val manifest = BackupArchive.read(java.security.DigestInputStream(inputStream, fingerprint), password)
                val count = manifest.portableStores.filterKeys { it.endsWith("/xtream_accounts") }.values.sumOf { encoded ->
                    val prefs = PortablePreferences.decode(encoded)
                    prefs[stringPreferencesKey("xtream_accounts")]?.let {
                        com.google.gson.JsonParser.parseString(it).asJsonArray.size()
                    } ?: 0
                }
                RestoreService.PreviewResult.Ready(1, manifest.appVersionName, manifest.createdAtMs,
                    manifest.profiles.size, manifest.profiles.map { it.name }, count, 0,
                    listOf("Main account", "OAuth / device-bound services"),
                    fingerprint.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            } catch (e: CancellationException) { throw e }
            catch (_: javax.crypto.AEADBadTagException) { RestoreService.PreviewResult.WrongPassword }
            catch (e: BackupArchive.UnsupportedVersion) { RestoreService.PreviewResult.UnsupportedVersion(e.version, 1) }
            catch (e: Exception) { RestoreService.PreviewResult.Corrupted(e) }
        }

    override suspend fun applyRestore(inputStream: InputStream, password: String, expectedSha256: String): RestoreService.RestoreOutcome =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                var safety = false
                try {
                    check(!auth.isAuthenticated && auth.currentAccessToken() == null) { "SIGN_OUT_REQUIRED" }
                    require(expectedSha256.matches(Regex("[a-f0-9]{64}")))
                    val manifest = BackupArchive.read(inputStream, password, expectedSha256)
                    val emergency = BackupArchive.write(snapshot(), password)
                    val safetyFile = File(context.filesDir, "backup_safety/emergency.tuvora-backup")
                    RestoreTransaction.durableWrite(safetyFile, emergency)
                    safety = true
                    // Authentication and validation finish before any replacement is scheduled.
                    com.nuvio.tv.core.profile.ConfigurationMutation.begin()
                    try {
                        RestoreTransaction.prepare(context, manifest)
                    } finally {
                        com.nuvio.tv.core.profile.ConfigurationMutation.end()
                    }
                    RestoreService.RestoreOutcome.Success
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { RestoreService.RestoreOutcome.Failed(e, safety) }
            }
        }
}
