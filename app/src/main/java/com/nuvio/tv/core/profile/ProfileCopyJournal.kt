package com.nuvio.tv.core.profile

import android.content.Context
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.backup.RestoreTransaction
import com.nuvio.tv.core.security.SecureCredentialStore
import com.nuvio.tv.data.local.SecurePreferencesDataStore
import okio.Buffer
import java.io.File

/** Encrypted rollback images for a multi-store copy, recovered before DataStore opens after a crash. */
internal object ProfileCopyJournal {
    private fun root(context: Context) = File(context.filesDir, "profile_copy_recovery")
    suspend fun prepare(context: Context, pid: Int, snapshots: Map<String, Preferences>, secrets: SecureCredentialStore) {
        val root = root(context)
        check(!File(root, "pending").exists()) { "Restart to recover the previous copy" }
        root.deleteRecursively()
        val originals = File(root, "originals").apply { check(mkdirs()) }
        for ((feature, prefs) in snapshots) {
            val native = prefs.toMutablePreferences()
            if (SecurePreferencesDataStore.features.containsKey(feature)) {
                val keys = SecurePreferencesDataStore.features[feature]
                for ((key, value) in prefs.asMap()) {
                    if (value !is String || (keys != null && key.name !in keys)) continue
                    val envelope = secrets.seal(pid, feature, key.name, value)
                    check((secrets.unseal(pid, feature, key.name, envelope) as? SecureCredentialStore.ReadResult.Success)?.value == value)
                    native[stringPreferencesKey(key.name)] = "__tuvora_secure_v1__:" + envelope
                }
            }
            val name = (if (pid == 1) feature else "${feature}_p$pid") + ".preferences_pb"
            val buffer = Buffer()
            PreferencesSerializer.writeTo(native.toPreferences(), buffer)
            RestoreTransaction.durableWrite(File(originals, name), buffer.readByteArray())
        }
        RestoreTransaction.durableWrite(File(root, "pending"), byteArrayOf(1))
    }
    fun finish(context: Context) {
        val root = root(context)
        if (!root.exists()) return
        RestoreTransaction.durableWrite(File(root, "committed"), byteArrayOf(1))
        root.deleteRecursively()
    }
    fun recover(context: Context) {
        val root = root(context)
        if (File(root, "committed").exists() || !File(root, "pending").exists()) {
            root.deleteRecursively()
            return
        }
        File(root, "originals").listFiles().orEmpty().forEach { original ->
            require(original.name.matches(Regex("[a-z0-9_]+\\.preferences_pb")))
            val target = File(context.filesDir, "datastore/${original.name}")
            RestoreTransaction.durableWrite(target, original.readBytes())
            RestoreTransaction.durableWrite(File(target.parentFile, target.name + ".bak"), original.readBytes())
        }
        finish(context)
    }
}
