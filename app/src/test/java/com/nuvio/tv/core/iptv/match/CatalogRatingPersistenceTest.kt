package com.nuvio.tv.core.iptv.match

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nuvio.tv.core.security.TestCacheSecrets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CatalogRatingPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun index() = XtreamMatchIndex(context, TestCacheSecrets.codec())
    private fun movie(rating: Float?) = IndexedItem(
        sid = 1, name = "Arrival", year = 2016, tmdb = 329865, ext = "mkv",
        categoryId = "7", rating = rating
    )

    @Test
    fun `scores survive storage and change through regular and streaming sync`() = runBlocking {
        val index = index()
        index.rebuild("acct", MatchKind.MOVIE, listOf(movie(7.4f)))
        assertEquals(7.4f, index.itemsFor("acct", MatchKind.MOVIE, "7", 0, 10).single().rating)
        assertEquals(7.4f, index.item("acct", MatchKind.MOVIE, 1)?.rating)
        assertEquals(7.4f, index.byTmdb("acct", MatchKind.MOVIE, 329865).single().rating)
        assertEquals(7.4f, index.searchByName("acct", MatchKind.MOVIE, "Arrival", 10).single().rating)
        assertEquals(0, index.sync("acct", MatchKind.MOVIE, listOf(movie(7.4f))).changed)
        assertEquals(1, index.sync("acct", MatchKind.MOVIE, listOf(movie(8.2f))).changed)
        assertEquals(8.2f, index.itemsFor("acct", MatchKind.MOVIE, null, 0, 10).single().rating)
        val session = index.beginSync("acct", MatchKind.MOVIE)
        session.accept(movie(null))
        assertEquals(1, session.finish().changed)
        assertNull(index.item("acct", MatchKind.MOVIE, 1)?.rating)
    }

    @Test
    fun `version seven upgrade keeps catalog rows and schedules a score refresh`() = runBlocking {
        val old = object : SQLiteOpenHelper(context, "xtream_match.db", null, 7) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE items(provider TEXT NOT NULL, kind TEXT NOT NULL, sid INTEGER NOT NULL, name TEXT NOT NULL, year INTEGER, tmdb INTEGER, ext TEXT, poster TEXT, category_id TEXT, epg_id TEXT, tv_archive INTEGER NOT NULL DEFAULT 0, pos INTEGER NOT NULL DEFAULT 0, entity_id TEXT, PRIMARY KEY(provider, kind, sid)) WITHOUT ROWID")
                db.execSQL("CREATE TABLE idx_meta(provider TEXT NOT NULL, kind TEXT NOT NULL, built_at INTEGER NOT NULL, item_count INTEGER NOT NULL, last_added_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider, kind)) WITHOUT ROWID")
                val provider = com.nuvio.tv.core.iptv.content.cachePlaylistKey("acct")
                db.execSQL("INSERT INTO items(provider,kind,sid,name) VALUES(?,'movie',1,'Arrival')", arrayOf(provider))
                db.execSQL("INSERT INTO idx_meta VALUES(?,'movie',123,1,99)", arrayOf(provider))
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        old.writableDatabase
        old.close()
        val index = index()
        assertEquals("Arrival", index.item("acct", MatchKind.MOVIE, 1)?.name)
        assertNull(index.item("acct", MatchKind.MOVIE, 1)?.rating)
        assertEquals(0L, index.builtAt("acct", MatchKind.MOVIE))
        assertEquals(99L, index.lastAddedAt("acct", MatchKind.MOVIE))
    }
}
