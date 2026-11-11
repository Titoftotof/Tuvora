package com.nuvio.tv.core.security

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.nuvio.tv.core.iptv.content.cachePlaylistKey
import com.nuvio.tv.core.iptv.content.secureLegacyCache
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CacheEncryptionCompatibilityTest {
    private val key = SecretKeySpec(ByteArray(32) { 47 }, "AES")
    private val codec = LocalSecretCodec(key)

    @Test fun `new cache cells are authenticated and old cells remain readable`() {
        val plain = "https://provider/movie/user/password/42.mp4"
        val a = codec.seal(plain)!!
        val b = codec.seal(plain)!!
        assertTrue(a.startsWith("__tuvora_cache_v2__:"))
        assertNotEquals(a, b)
        assertEquals(plain, codec.open(a))
        assertEquals(plain, codec.open(plain))
        assertNull(codec.open(null))
        val legacy = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD("cache-string".toByteArray())
            "__tuvora_cache_v1__:" + Base64.encodeToString(iv + doFinal(plain.toByteArray()), Base64.NO_WRAP)
        }
        assertEquals(plain, codec.open(legacy))
        val bytes = Base64.decode(a.substringAfter(':'), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = "__tuvora_cache_v2__:" + Base64.encodeToString(bytes, Base64.NO_WRAP)
        assertTrue(runCatching { codec.open(tampered) }.isFailure)
        assertTrue(runCatching { LocalSecretCodec(SecretKeySpec(ByteArray(32), "AES")).open(a) }.isFailure)
    }

    @Test fun `migration crosses numeric composite key page boundaries without skipping rows`() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE items(provider TEXT NOT NULL, kind TEXT NOT NULL, sid INTEGER NOT NULL, poster TEXT, PRIMARY KEY(provider,kind,sid)) WITHOUT ROWID")
            db.beginTransaction()
            try {
                db.compileStatement("INSERT INTO items VALUES(?,?,?,?)").use { insert ->
                    for (kind in listOf("movie", "series")) for (sid in 1..600) {
                        insert.bindString(1, "provider")
                        insert.bindString(2, kind)
                        insert.bindLong(3, sid.toLong())
                        insert.bindString(4, "https://img/$kind/$sid")
                        insert.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            secureLegacyCache(db, "provider", codec)
            var count = 0
            db.rawQuery("SELECT provider,kind,sid,poster FROM items ORDER BY provider,kind,sid", null).use { c ->
                while (c.moveToNext()) {
                    assertEquals(cachePlaylistKey("provider"), c.getString(0))
                    assertEquals("https://img/${c.getString(1)}/${c.getInt(2)}", codec.open(c.getString(3)))
                    count++
                }
            }
            assertEquals(1200, count)
        }
    }

    @Test fun `legacy catalog migrates multiple protected columns and nulls exactly once`() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE vod(playlist_id TEXT NOT NULL, sid INTEGER NOT NULL, url TEXT, logo TEXT, cmd TEXT, name TEXT, PRIMARY KEY(playlist_id,sid)) WITHOUT ROWID")
            db.execSQL("INSERT INTO vod VALUES(?,?,?,?,?,?)", arrayOf<Any?>("provider", 1, "https://host/user/password", null, "play private", "Film"))
            db.execSQL("INSERT INTO vod VALUES(?,?,?,?,?,?)", arrayOf<Any?>("provider", 2, "https://host/2", "https://img/2", null, "Film 2"))
            secureLegacyCache(db, secrets = codec)
            val owner = cachePlaylistKey("provider")
            var storedUrl = ""
            db.rawQuery("SELECT playlist_id,url,logo,cmd,name FROM vod ORDER BY sid", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(owner, c.getString(0))
                storedUrl = c.getString(1)
                assertTrue(storedUrl.startsWith("__tuvora_cache_v2__:"))
                assertEquals("https://host/user/password", codec.open(storedUrl))
                assertTrue(c.isNull(2))
                assertEquals("play private", codec.open(c.getString(3)))
                assertEquals("Film", c.getString(4))
                assertTrue(c.moveToNext())
                assertEquals("https://img/2", codec.open(c.getString(2)))
                assertTrue(c.isNull(3))
            }
            secureLegacyCache(db, secrets = codec)
            db.rawQuery("SELECT url FROM vod WHERE playlist_id=? AND sid=1", arrayOf(owner)).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(storedUrl, c.getString(0))
            }
            val previousHash = java.security.MessageDigest.getInstance("SHA-256").digest("provider".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("private:$previousHash", owner)
        }
    }
}
