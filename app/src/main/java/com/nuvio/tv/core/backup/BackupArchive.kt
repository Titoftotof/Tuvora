package com.nuvio.tv.core.backup

import com.google.gson.Gson
import com.nuvio.tv.data.local.canFullReplace
import java.io.InputStream

/** Bounded authenticated decoding. Header policy is checked before expensive key derivation. */
object BackupArchive {
    const val MAX_BYTES = 64 * 1024 * 1024
    private val gson = Gson()
    class UnsupportedVersion(val version: Short) : Exception()
    suspend fun read(input: InputStream, password: String, expectedSha256: String? = null): BackupManifest {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size().toLong() + n <= MAX_BYTES) { "Backup exceeds 64 MB" }
            out.write(buffer, 0, n)
        }
        val bytes = out.toByteArray()
        if (expectedSha256 != null) {
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            require(expectedSha256 == actual) { "BACKUP_CHANGED" }
        }
        require(bytes.size >= BackupFormat.HEADER_LENGTH_BYTES + 16) { "Truncated backup" }
        val header = BackupFormat.parseHeader(bytes) ?: error("Invalid backup header")
        if (header.version != BackupFormat.CURRENT_FORMAT_VERSION) throw UnsupportedVersion(header.version)
        require(header.kdfId == BackupFormat.KDF_PBKDF2_SHA256 && header.iterations in 600_000..1_200_000) { "Invalid KDF parameters" }
        val plain = BackupCrypto.decrypt(bytes.copyOfRange(40, bytes.size), password, header)
        return try {
            gson.fromJson(plain.toString(Charsets.UTF_8), BackupManifest::class.java).also { validate(it) }
        } finally { plain.fill(0) }
    }
    fun write(manifest: BackupManifest, password: String): ByteArray {
        require(password.length >= BackupFormat.MIN_PASSWORD_LENGTH) { "Use at least 12 characters" }
        val header = BackupFormat.Header(BackupFormat.CURRENT_FORMAT_VERSION, BackupFormat.KDF_PBKDF2_SHA256,
            BackupFormat.KDF_ITERATIONS, BackupCrypto.generateSalt(), BackupCrypto.generateNonce())
        val plain = gson.toJson(manifest).toByteArray(Charsets.UTF_8)
        return try {
            require(plain.size < MAX_BYTES - 56)
            header.toBytes() + BackupCrypto.encrypt(plain, password, header)
        } finally { plain.fill(0) }
    }
    suspend fun validate(manifest: BackupManifest) {
        require(manifest.manifestVersion == 1) { "Unsupported manifest" }
        require(manifest.profiles.size in 1..6 && manifest.profiles.any { it.profileId == 1 })
        val ids = manifest.profiles.map { it.profileId }
        require(ids.distinct().size == ids.size && ids.all { it in 1..6 })
        require(manifest.profiles.all { it.name.isNotBlank() && it.name.length <= 200 })
        val allowed = ids.flatMap { pid -> PortablePreferences.features.map { "$pid/$it" } }.toSet() + "profile_settings"
        require(manifest.portableStores.keys.all { it in allowed })
        require(manifest.portableStores.containsKey("profile_settings"))
        // Version 1 is a complete replacement, so missing stores must never silently erase secrets.
        require(manifest.portableStores.keys.containsAll(allowed))
        manifest.portableStores.values.forEach { PortablePreferences.decode(it) }
        var attachmentBytes = 0L
        manifest.playlistFiles.forEach { (name, data) ->
            require(name.matches(Regex("[a-f0-9]{64}\\.m3u")))
            attachmentBytes += android.util.Base64.decode(data, android.util.Base64.NO_WRAP).size
            require(attachmentBytes <= 16 * 1024 * 1024) { "Playlist attachments exceed limit" }
        }
        ids.forEach { pid ->
            val pin = PortablePreferences.decode(manifest.portableStores.getValue("$pid/portable_profile_pin"))[androidx.datastore.preferences.core.stringPreferencesKey("verifier")]
            if (pin != null) require(pin.matches(Regex("[A-Za-z0-9+/]{22}==:[A-Za-z0-9+/]{43}="))) { "Invalid PIN verifier" }
            val prefs = PortablePreferences.decode(manifest.portableStores.getValue("$pid/xtream_accounts"))
            val raw = prefs[androidx.datastore.preferences.core.stringPreferencesKey("xtream_accounts")]
            require(raw == null || com.nuvio.tv.data.local.decodeXtreamAccountsOutcome(Gson(), raw).canFullReplace) { "Invalid IPTV accounts" }
        }
        val meta = PortablePreferences.decode(manifest.portableStores.getValue("profile_settings"))
        val rawProfiles = meta[androidx.datastore.preferences.core.stringPreferencesKey("profiles_json")]
            ?: error("Missing profiles")
        val profiles = com.google.gson.JsonParser.parseString(rawProfiles).asJsonArray
        require(profiles.size() == ids.size && profiles.map { it.asJsonObject["id"].asInt }.toSet() == ids.toSet())
        profiles.forEach { element ->
            val profile = element.asJsonObject
            val manifestProfile = manifest.profiles.single { it.profileId == profile["id"].asInt }
            require(profile["name"]?.asJsonPrimitive?.isString == true && profile["name"].asString == manifestProfile.name)
            require(profile["avatarColorHex"]?.asJsonPrimitive?.isString == true)
            for (flag in listOf("usesPrimaryAddons", "usesPrimaryPlugins")) {
                require(profile[flag] == null || profile[flag].asJsonPrimitive.isBoolean)
            }
            for (field in listOf("avatarId", "avatarUrl", "profileBackgroundId", "profileBackgroundUrl")) {
                require(profile[field] == null || profile[field].isJsonNull || profile[field].asJsonPrimitive.isString)
            }
        }
    }
}
