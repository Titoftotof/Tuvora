package com.nuvio.tv.core.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.security.KeystoreSecureCredentialStore
import com.nuvio.tv.data.local.SecurePreferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Buffer
import java.io.File

/**
 * Builds encrypted native files off-thread. Installation occurs BEFORE Hilt/DataStore initialize.
 * A durable 'installing' marker means rollback on the next launch, including after power loss.
 */
object RestoreTransaction {
    private fun root(context: Context) = File(context.filesDir, "configuration_restore")
    suspend fun prepare(context: Context, manifest: BackupManifest) {
        val root = root(context)
        check(!File(root, "ready").exists() && !File(root, "installing").exists())
        root.deleteRecursively()
        val stage = File(root, "stage").apply { check(mkdirs()) }
        val credentials = KeystoreSecureCredentialStore(context)
        for ((key, encoded) in manifest.portableStores) {
            var prefs = PortablePreferences.decode(encoded)
            val name: String
            if (key == "profile_settings") {
                name = "profile_settings.preferences_pb"
                prefs = prefs.toMutablePreferences().apply {
                    this[intPreferencesKey("active_profile_id")] = 1
                    this[booleanPreferencesKey("profile_has_ever_selected")] = true
                    this[booleanPreferencesKey("remember_last_profile_enabled")] = false
                }.toPreferences()
            } else {
                val (pidText, feature) = key.split('/')
                val pid = pidText.toInt()
                if (feature == "portable_profile_pin" && prefs.asMap().isNotEmpty()) {
                    prefs = prefs.toMutablePreferences().apply { this[booleanPreferencesKey("imported")] = true }.toPreferences()
                }
                if (SecurePreferencesDataStore.features.containsKey(feature)) {
                    val raw = MemoryPreferences()
                    SecurePreferencesDataStore(raw, credentials, pid, feature, SecurePreferencesDataStore.features[feature])
                        .updateData { prefs }
                    prefs = raw.value.value
                }
                name = PortablePreferences.fileName(pid, feature)
            }
            val buffer = Buffer()
            PreferencesSerializer.writeTo(prefs, buffer)
            durableWrite(File(stage, "datastore/$name"), buffer.readByteArray())
        }
        val locks = manifest.profiles.associate { profile ->
            val p = PortablePreferences.decode(manifest.portableStores.getValue("${profile.profileId}/portable_profile_pin"))
            profile.profileId to !p[stringPreferencesKey("verifier")].isNullOrBlank()
        }
        val lockPrefs = mutablePreferencesOf(stringPreferencesKey("pin_enabled_map") to locks.entries.joinToString(",") { "${it.key}:${it.value}" })
        val buffer = Buffer()
        PreferencesSerializer.writeTo(lockPrefs, buffer)
        durableWrite(File(stage, "datastore/profile_lock_state.preferences_pb"), buffer.readByteArray())
        for ((name, encoded) in manifest.playlistFiles) {
            require(name.matches(Regex("[a-f0-9]{64}\\.m3u")))
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            try {
                val output = File(stage, "playlists/$name").apply { parentFile!!.mkdirs() }
                java.io.FileOutputStream(output).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
            } finally { bytes.fill(0) }
        }
        durableWrite(File(root, "ready"), byteArrayOf(1))
    }

    /** Called from Application.attachBaseContext; no DataStore exists yet. */
    fun installOrRecover(context: Context) {
        val root = root(context)
        val stage = File(root, "stage")
        val old = File(root, "old")
        val live = context.filesDir
        val installing = File(root, "installing")
        val committed = File(root, "committed")
        if (committed.exists()) {
            // Installed state is authoritative. Keep cloud synchronization paused until explicitly resumed.
            RestoreSyncHold.mark(context)
            root.deleteRecursively()
            return
        }
        if (installing.exists()) {
            rollback(root, live)
            return
        }
        if (!File(root, "ready").exists()) return
        val stores = (1..6).flatMap { pid ->
            (PortablePreferences.features + setOf("xtream_sync_state", "startup_sync_state")).map { PortablePreferences.fileName(pid, it) }
        }.toSet() + setOf("profile_settings.preferences_pb", "profile_lock_state.preferences_pb")
        val playlistNames = (File(live, "playlists").listFiles().orEmpty().map { it.name } +
            File(stage, "playlists").listFiles().orEmpty().map { it.name }).filter { it.matches(Regex("[a-f0-9]{64}\\.m3u")) }.toSet()
        // Sessions excluded from the archive must not leak from the previous destination profiles.
        val credentialPrefs = File(live, "shared_prefs").listFiles().orEmpty().filter { file ->
            file.name.endsWith(".xml") && (file.name.startsWith("secure_credentials_") ||
                file.name in setOf("media_servers.xml", "mdblist_auth.xml", "nuvio_simkl_auth.xml", "device_sources.xml",
                    "managed_playlists_v1.xml", "iptv_server_failover.xml", "catchup_dialect_winners.xml"))
        }.map { "shared_prefs/${it.name}" }
        val targets = stores.map { "datastore/$it" } + playlistNames.map { "playlists/$it" } + credentialPrefs
        old.mkdirs()
        // Copy originals and a list BEFORE marking installation begun. Never alter originals during staging.
        for (name in targets) {
            for (suffix in listOf("", ".bak")) {
                val source = File(live, name + suffix)
                if (source.exists()) durableWrite(File(old, name + suffix), source.readBytes())
            }
        }
        durableWrite(File(root, "targets"), targets.joinToString("\n").toByteArray())
        durableWrite(installing, byteArrayOf(1))
        try {
            // Disposable caches must never show data or reuse URLs belonging to the replaced profiles.
            for (name in listOf("iptv_content.db", "xtream_match.db")) {
                if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name))
            }
            val enrichment = File(context.filesDir, "cw_enrichment")
            if (enrichment.exists()) check(enrichment.deleteRecursively())
            for (name in targets) {
                File(live, name).delete()
                File(live, "$name.bak").delete()
                val prepared = File(stage, name)
                if (prepared.exists()) {
                    File(live, name).parentFile!!.mkdirs()
                    check(prepared.renameTo(File(live, name))) { "Unable to install preferences" }
                }
            }
            durableWrite(committed, byteArrayOf(1))
            RestoreSyncHold.mark(context)
            root.deleteRecursively()
        } catch (e: Exception) {
            rollback(root, live)
            throw e
        }
    }

    private fun rollback(root: File, live: File) {
        val targets = File(root, "targets").readLines()
        for (name in targets) {
            require(name.matches(Regex("(datastore/[a-z0-9_]+\\.preferences_pb|playlists/[a-f0-9]{64}\\.m3u|shared_prefs/[A-Za-z0-9_]+\\.xml)")))
            for (suffix in listOf("", ".bak")) {
                val destination = File(live, name + suffix)
                val original = File(File(root, "old"), name + suffix)
                if (original.exists()) durableWrite(destination, original.readBytes()) else destination.delete()
            }
        }
        root.deleteRecursively()
    }

    internal fun durableWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        java.io.FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        val value = MutableStateFlow<Preferences>(emptyPreferences())
        override val data = value
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(value.value).also { value.value = it }
    }
}
