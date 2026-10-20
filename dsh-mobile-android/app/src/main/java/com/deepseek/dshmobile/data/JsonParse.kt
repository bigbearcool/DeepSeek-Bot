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
)

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
)

fun cursorToQuery(cursor: Any?): String? = when (cursor) {
    null, JSONObject.NULL -> null
    is String -> cursor.takeIf { it.isNotBlank() }
    is JSONObject -> cursor.toString()
    else -> cursor.toString()
}
