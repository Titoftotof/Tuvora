package com.nuvio.tv.core.profile

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProfileIptvCopyTest {
    @Test fun `merges missing providers and preserves target credentials and unknown fields`() {
        val merged = JsonParser.parseString(mergeProfileIptvAccounts(
            """[{"id":"same","password":"source"},{"id":"new","future":{"token":"secret"}}]""",
            """[{"id":"same","password":"target","unknown":42}]"""
        )).asJsonArray
        assertEquals(2, merged.size())
        assertEquals("target", merged[0].asJsonObject["password"].asString)
        assertEquals(42, merged[0].asJsonObject["unknown"].asInt)
        assertEquals("secret", merged[1].asJsonObject["future"].asJsonObject["token"].asString)
    }

    @Test fun `malformed snapshots are rejected rather than converted to empty connections`() {
        for (invalid in listOf("broken", "{}", "[{}]")) {
            assertTrue(runCatching { mergeProfileIptvAccounts(invalid, "[]") }.isFailure)
            assertTrue(runCatching { mergeProfileIptvAccounts("[]", invalid) }.isFailure)
        }
    }
}
