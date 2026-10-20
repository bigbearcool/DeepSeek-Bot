package com.deepseek.dshmobile.data

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

fun JSONArray.mapObjects(): List<JSONObject> =
    (0 until length()).mapNotNull { i -> optJSONObject(i) }

fun parseBot(o: JSONObject) = BotInfo(
    id = o.optString("id"),
    name = o.optString("name", o.optString("id")),
    role = o.optStringOrNull("role"),
    sessionId = o.optStringOrNull("sessionId"),
    model = o.optStringOrNull("model"),
    main = o.optBoolean("main", false),
    color = o.optStringOrNull("color"),
    shape = o.optStringOrNull("shape"),
)

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

fun parseRoom(o: JSONObject) = RoomInfo(
    id = o.optString("id"),
    name = o.optString("name", ""),
    members = o.optJSONArray("members").strings(),
    memberNames = o.optJSONArray("memberNames").strings(),
    admin = o.optStringOrNull("admin"),
    preview = o.optStringOrNull("preview"),
)

fun parseBotOptions(o: JSONObject): BotOptions {
    val folders = o.optJSONObject("folders")
    val limits = o.optJSONObject("limits") ?: JSONObject()
    return BotOptions(
        models = o.optJSONArray("models")?.mapObjects()?.map {
            ModelOption(it.optString("ref"), it.optString("name", it.optString("ref")), it.optStringOrNull("providerName"), it.optBoolean("needsKey", false))
        }.orEmpty(),
        defaultModel = o.optStringOrNull("defaultModel"),
        agentPresets = if (o.isNull("agentPresets") || !o.has("agentPresets")) null else
            o.optJSONArray("agentPresets")?.mapObjects()?.map { NamedOption(it.optString("value"), it.optString("name", it.optString("value"))) },
        colors = o.optJSONArray("colors")?.mapObjects()?.map { ColorOption(it.optString("id"), it.optStringOrNull("hex")) }.orEmpty(),
        shapes = o.optJSONArray("shapes")?.mapObjects()?.map { NamedOption(it.optString("id"), it.optString("label", it.optString("id"))) }.orEmpty(),
        teamFolder = folders?.optStringOrNull("team"),
        folders = folders?.optJSONArray("folders")?.mapObjects()?.map { FolderOption(it.optString("path"), it.optString("title", it.optString("path"))) }.orEmpty(),
        canBrowse = folders?.optStringOrNull("picker") == "browse",
        nameMax = limits.optInt("name", 40),
        roleMax = limits.optInt("role", 24),
        roomNameMax = limits.optInt("roomName", 60),
        roomMaxMembers = limits.optInt("roomMaxMembers", 12),
    )
}

/** Body for POST /bots/create: blank fields are left out so ds-bot applies its defaults. */
fun NewBotDraft.toJson(): JSONObject {
    val o = JSONObject()
    fun put(k: String, v: String) { if (v.isNotBlank()) o.put(k, v.trim()) }
    put("name", name); put("role", role); put("color", color); put("model", model); put("effort", effort)
    put("agentPreset", agentPreset); put("workDir", workDir); put("instructions", instructions); put("brief", brief)
    if (shape.isNotBlank() || image != null) {
        val a = JSONObject()
        if (shape.isNotBlank()) a.put("shape", shape)
        if (image != null) a.put("image", image)
        o.put("avatar", a)
    }
    return o
}

fun NewRoomDraft.toJson(): JSONObject {
    val o = JSONObject()
    val list = JSONArray()
    members.forEach { list.put(it) }
    o.put("members", list)
    admin?.let { o.put("admin", it) }
    if (name.isNotBlank()) o.put("name", name.trim())
    return o
}

fun parseChatItem(o: JSONObject) = ChatItem(
    kind = o.optString("kind", "event"),
    text = o.optStringOrNull("text"),
    time = o.optLongOrNull("time"),
    seq = o.optLongOrNull("seq"),
    role = o.optStringOrNull("role"),
    botId = o.optStringOrNull("botId"),
    name = o.optStringOrNull("name"),
    images = if (o.has("images")) o.optInt("images") else null,
    clientMessageId = o.optStringOrNull("clientMessageId"),
    cut = o.optBoolean("cut", false),
    attachments = o.optJSONArray("attachments")?.mapObjects()?.map { parseAttachment(it) }.orEmpty(),
)

fun cursorToQuery(cursor: Any?): String? = when (cursor) {
    null, JSONObject.NULL -> null
    is String -> cursor.takeIf { it.isNotBlank() }
    is JSONObject -> cursor.toString()
    else -> cursor.toString()
}
