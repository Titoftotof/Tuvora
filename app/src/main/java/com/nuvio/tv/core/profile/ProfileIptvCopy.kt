package com.nuvio.tv.core.profile

import com.google.gson.JsonArray
import com.google.gson.JsonParser

/** Copy missing connections without losing unknown fields or replacing target credentials. */
internal fun mergeProfileIptvAccounts(source: String, target: String?): String {
    fun parse(json: String): List<com.google.gson.JsonElement> {
        val array = JsonParser.parseString(json)
        require(array.isJsonArray) { "Invalid IPTV connections" }
        return array.asJsonArray.map { item ->
            require(item.isJsonObject && item.asJsonObject.get("id")?.asString?.isNotBlank() == true) {
                "IPTV connection has no identity"
            }
            item
        }
    }
    val targetItems = target?.takeIf { it.isNotBlank() }?.let(::parse).orEmpty()
    val sourceItems = parse(source)
    val merged = JsonArray()
    (targetItems + sourceItems).distinctBy { it.asJsonObject.get("id").asString }.forEach(merged::add)
    return merged.toString()
}
