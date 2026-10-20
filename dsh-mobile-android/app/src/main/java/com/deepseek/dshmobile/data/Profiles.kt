package com.deepseek.dshmobile.data

import org.json.JSONObject

/** One Bot as its profile page shows it (from GET /bots/info). */
data class BotProfile(
    val id: String,
    val name: String,
    val role: String,
    val instructions: String,
    val shape: String?,
    val color: String?,
    val main: Boolean,
)

/** One search result: a Bot, a group chat, or a message in one of them. */
data class SearchHit(val kind: String, val id: String, val name: String, val who: String?, val text: String, val message: Boolean)

fun parseProfile(o: JSONObject) = BotProfile(
    id = o.optString("id"),
    name = o.optString("name"),
    role = o.optStringOrNull("role").orEmpty(),
    instructions = o.optString("instructions", ""),
    shape = o.optStringOrNull("shape"),
    color = o.optStringOrNull("color"),
    main = o.optBoolean("main", false),
)

fun parseHits(o: JSONObject): List<SearchHit> = (o.optJSONArray("hits")?.mapObjects() ?: emptyList()).map {
    SearchHit(it.optString("kind"), it.optString("id"), it.optString("name"), it.optStringOrNull("who"), it.optString("text"), message = true)
}

object ProfileLogic {
    /** Bots that can still join a group, filtered by what the user typed. */
    fun addable(bots: List<BotInfo>, members: List<String>, query: String): List<BotInfo> {
        val q = query.trim()
        return bots.filter { it.id !in members && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
    }

    /** Name and chat matches first (from what the phone already has), then messages from the computer. */
    fun results(chats: List<ChatEntry>, bots: List<BotInfo>, query: String, messages: List<SearchHit>): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return chats.map { SearchHit(it.kind, it.id, it.name, null, if (it.kind == "room") "" else bots.find { b -> b.id == it.id }?.role.orEmpty(), false) }
        val named = chats.filter { it.name.contains(q, ignoreCase = true) }
            .map { SearchHit(it.kind, it.id, it.name, null, if (it.kind == "room") "" else bots.find { b -> b.id == it.id }?.role.orEmpty(), false) }
        return named + messages
    }

    /** The label on the right of a search row. */
    fun tag(hit: SearchHit): String = when {
        hit.message -> "消息"
        hit.kind == "room" -> "群聊"
        else -> "Bot"
    }

    /** Body of a POST /bots/update for a look change; null clears back to the default look. */
    fun lookChange(id: String, shape: String?, color: String?): JSONObject = JSONObject().put("id", id).apply {
        if (shape != null) put("shape", shape)
        if (color != null) put("color", color)
    }

    /** 局域网 when the computer is on this network (private address or .local), else 外网. */
    fun network(baseUrl: String): String {
        val host = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        val private = when {
            host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") -> true
            parts.size == 4 && parts[0] == 10 -> true
            parts.size == 4 && parts[0] == 192 && parts[1] == 168 -> true
            parts.size == 4 && parts[0] == 172 && parts[1] in 16..31 -> true
            parts.size == 4 && parts[0] == 127 -> true
            parts.size == 4 && parts[0] == 169 && parts[1] == 254 -> true
            else -> false
        }
        return if (private) "局域网" else "外网"
    }
}
