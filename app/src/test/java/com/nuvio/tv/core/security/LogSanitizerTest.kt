package com.nuvio.tv.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun `sanitizes user and password from URL authority`() {
        val input = "http://john_doe:secret123@example.com/playlist.m3u"
        val expected = "http://***@example.com/playlist.m3u"
        assertEquals(expected, LogSanitizer.sanitize(input))
    }

    @Test
    fun `sanitizes query parameter credentials`() {
        val input = "https://example.com/api.php?username=myuser&password=mypassword&type=m3u"
        val expected = "https://example.com/api.php?username=***&password=***&type=m3u"
        assertEquals(expected, LogSanitizer.sanitize(input))
    }

    @Test
    fun `sanitizes debrid and api keys in query`() {
        val input = "https://api.torbox.app/v1/api/torrents/checkcached?api_key=secret-key-value"
        val expected = "https://api.torbox.app/v1/api/torrents/checkcached?api_key=***"
        assertEquals(expected, LogSanitizer.sanitize(input))
    }

    @Test
    fun `sanitizes xtream stream paths`() {
        val input = "http://iptv.server:8080/live/user123/pass456/789.ts"
        val expected = "http://iptv.server:8080/live/***/***/789.ts"
        assertEquals(expected, LogSanitizer.sanitize(input))
    }

    @Test
    fun `leaves safe URLs untouched`() {
        val input = "https://api.themoviedb.org/3/movie/550?language=fr-FR"
        assertEquals(input, LogSanitizer.sanitize(input))
    }

    @Test
    fun `handles null and empty input safely`() {
        assertEquals("", LogSanitizer.sanitize(null))
        assertEquals("", LogSanitizer.sanitize(""))
    }
}
