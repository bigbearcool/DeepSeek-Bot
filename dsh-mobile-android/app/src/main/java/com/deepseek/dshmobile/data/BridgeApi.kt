package com.deepseek.dshmobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for dsh-mobile-bridge `/api/mobile-bridge/v1`.
 * Auth: `Authorization: Bearer <accessToken>`; refresh via POST /auth/token.
 */
class BridgeApi(
    private val http: OkHttpClient = defaultClient(),
) {
    fun withBase(baseUrl: String): Configured = Configured(normalizeBase(baseUrl), http)

    class Configured(
        val baseUrl: String,
        private val http: OkHttpClient,
    ) {
        suspend fun health(): HealthResponse = getJson("/health").let {
            HealthResponse(it.optBoolean("ok"), it.optStringOrNull("name"), it.optStringOrNull("version"))
        }

        suspend fun pairComplete(code: String, deviceId: String, label: String): PairCompleteResult {
            val body = JSONObject()
                .put("code", code)
                .put("deviceId", deviceId)
                .put("label", label)
            val json = postJson("/auth/pair/complete", body)
            return PairCompleteResult(
                deviceId = json.optString("deviceId", deviceId),
                refreshToken = json.getString("refreshToken"),
                refreshExpiresAt = json.optLongOrNull("refreshExpiresAt"),
            )
        }

        suspend fun issueAccess(deviceId: String, refreshToken: String): AccessTokenResult {
            val body = JSONObject()
                .put("deviceId", deviceId)
                .put("refreshToken", refreshToken)
            val json = postJson("/auth/token", body)
            return AccessTokenResult(
                accessToken = json.getString("accessToken"),
                expiresAt = json.getLong("expiresAt"),
                expiresIn = json.optLongOrNull("expiresIn"),
            )
        }

        suspend fun team(accessToken: String): TeamResponse {
            val json = getJson("/team", accessToken)
            val bots = json.optJSONArray("bots")?.mapObjects()?.map(::parseBot).orEmpty()
            val mains = json.optJSONArray("mainBotIds")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            }.orEmpty()
            return TeamResponse(
                available = json.optBoolean("available", true),
                bots = bots,
                rooms = json.optJSONArray("rooms")?.mapObjects()?.map(::parseRoom).orEmpty(),
                mainBotIds = mains,
                revision = json.optLongOrNull("revision"),
                reason = json.optStringOrNull("reason"),
            )
        }

        suspend fun history(accessToken: String, botId: String, cursor: String? = null): HistoryResponse {
            val q = buildString {
                append("/chats/history?botId=").append(java.net.URLEncoder.encode(botId, "UTF-8"))
                if (!cursor.isNullOrBlank()) {
                    append("&cursor=").append(java.net.URLEncoder.encode(cursor, "UTF-8"))
                }
            }
            val json = getJson(q, accessToken)
            val next = when {
                !json.has("nextCursor") || json.isNull("nextCursor") -> null
                else -> {
                    val raw = json.get("nextCursor")
                    if (raw is JSONObject) raw.toString() else raw.toString().takeIf { it != "null" }
                }
            }
            val items = json.optJSONArray("items")?.mapObjects()?.map(::parseChatItem).orEmpty()
            return HistoryResponse(
                available = json.optBoolean("available", true),
                botId = json.optStringOrNull("botId"),
                sessionId = json.optStringOrNull("sessionId"),
                items = items,
                nextCursor = next,
                stub = json.optBoolean("stub", false),
                reason = json.optStringOrNull("reason"),
            )
        }

        suspend fun send(accessToken: String, botId: String, text: String, clientMessageId: String? = null, attachments: List<String> = emptyList()): SendResponse {
            val body = JSONObject().put("botId", botId).put("text", text)
            if (attachments.isNotEmpty()) body.put("attachments", org.json.JSONArray(attachments))
            if (clientMessageId != null) body.put("clientMessageId", clientMessageId)
            val (code, json) = postJsonRaw("/chats/send", body, accessToken)
            return SendResponse(
                ok = json.optBoolean("ok", code in 200..299),
                botId = json.optStringOrNull("botId"),
                sessionId = json.optStringOrNull("sessionId"),
                stub = json.optBoolean("stub", false),
                reason = json.optStringOrNull("reason")
                    ?: json.optJSONObject("error")?.optStringOrNull("message"),
                duplicate = json.optBoolean("duplicate", false),
            )
        }

        suspend fun roomHistory(accessToken: String, roomId: String): RoomHistoryResponse {
            val json = getJson("/rooms/history?roomId=" + enc(roomId), accessToken)
            return RoomHistoryResponse(roomId, json.optJSONArray("items")?.mapObjects()?.map(::parseChatItem).orEmpty())
        }

        suspend fun roomSend(accessToken: String, roomId: String, text: String, clientMessageId: String? = null, attachments: List<String> = emptyList()): SendResponse {
            val body = JSONObject().put("roomId", roomId).put("text", text)
            if (attachments.isNotEmpty()) body.put("attachments", org.json.JSONArray(attachments))
            if (clientMessageId != null) body.put("clientMessageId", clientMessageId)
            val json = postJson("/rooms/send", body, accessToken)
            return SendResponse(ok = json.optBoolean("ok", true), botId = null, sessionId = roomId, stub = false, reason = null,
                duplicate = json.optBoolean("duplicate", false))
        }

        suspend fun botOptions(accessToken: String): BotOptions = parseBotOptions(getJson("/bots/options", accessToken))

        suspend fun efforts(accessToken: String, ref: String): List<EffortOption> =
            getJson("/bots/efforts?ref=" + enc(ref), accessToken).optJSONArray("efforts")?.mapObjects()
                ?.map { EffortOption(it.optString("id"), it.optString("name", it.optString("id"))) }.orEmpty()

        suspend fun listFolder(accessToken: String, path: String): FolderListing {
            val json = getJson("/folders?path=" + enc(path), accessToken)
            val entries = json.optJSONArray("entries")?.mapObjects()?.mapNotNull { e ->
                val p = e.optStringOrNull("path") ?: return@mapNotNull null
                FolderOption(p, e.optString("name", p))
            }.orEmpty()
            return FolderListing(json.optString("path", path), entries)
        }

        suspend fun createBot(accessToken: String, draft: NewBotDraft): BotInfo =
            parseBot(postJson("/bots/create", draft.toJson(), accessToken).getJSONObject("bot"))

        suspend fun createRoom(accessToken: String, draft: NewRoomDraft): RoomInfo =
            parseRoom(postJson("/rooms/create", draft.toJson(), accessToken).getJSONObject("room"))

        suspend fun chatList(accessToken: String): ChatListResponse = parseChatList(getJson("/chats/list", accessToken))

        /** Mark a chat read up to now on this device (opening it clears its count). */
        suspend fun markRead(accessToken: String, kind: String, id: String) {
            postJson("/chats/read", JSONObject().put("kind", kind).put("id", id), accessToken)
        }

        /** Stop a Bot's reply in its own chat. */
        suspend fun stopBot(accessToken: String, botId: String): Boolean =
            postJson("/chats/stop", JSONObject().put("botId", botId), accessToken).optBoolean("stopped", false)

        /** Stop every member still replying in a group chat. */
        suspend fun stopRoom(accessToken: String, roomId: String): Int =
            postJson("/rooms/stop", JSONObject().put("roomId", roomId), accessToken).optInt("stopped", 0)

        /** choice: "allow" | "reject" (ds-bot's own words). */
        suspend fun answerApproval(accessToken: String, id: String, choice: String) {
            postJson("/approvals/answer", JSONObject().put("id", id).put("choice", choice), accessToken)
        }

        suspend fun botInfo(accessToken: String, id: String): BotProfile =
            parseProfile(getJson("/bots/info?id=" + enc(id), accessToken).getJSONObject("bot"))

        /** Change a Bot on the computer; ds-bot checks every value. */
        suspend fun updateBot(accessToken: String, body: JSONObject): BotProfile =
            parseProfile(postJson("/bots/update", body, accessToken).getJSONObject("bot"))

        suspend fun addMembers(accessToken: String, roomId: String, ids: List<String>): RoomInfo =
            parseRoom(postJson("/rooms/update", JSONObject().put("id", roomId).put("add", org.json.JSONArray(ids)), accessToken).getJSONObject("room"))

        /** Remove one member or rename the group (ds-bot update-room via the computer). */
        suspend fun updateRoom(accessToken: String, roomId: String, name: String? = null, remove: List<String> = emptyList()): RoomInfo {
            val body = JSONObject().put("id", roomId)
            if (name != null) body.put("name", name)
            if (remove.isNotEmpty()) body.put("remove", org.json.JSONArray(remove))
            return parseRoom(postJson("/rooms/update", body, accessToken).getJSONObject("room"))
        }

        suspend fun search(accessToken: String, q: String): List<SearchHit> = parseHits(getJson("/search?q=" + enc(q), accessToken))

        /** Hand one picked picture or file to the computer for the chat it will be sent in. */
        suspend fun upload(accessToken: String, kind: String, id: String, name: String, bytes: ByteArray): UploadResult = withContext(Dispatchers.IO) {
            val url = "$baseUrl/uploads?kind=" + enc(kind) + "&id=" + enc(id) + "&name=" + enc(name)
            val req = Request.Builder().url(url)
                .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .header("Authorization", "Bearer $accessToken").build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw parseError(resp.code, text)
                parseUpload(JSONObject(text))
            }
        }

        /** Bytes of a picture or file in a message. */
        suspend fun attachmentBytes(accessToken: String, id: String): ByteArray = withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$baseUrl/attachments?id=" + enc(id)).get().header("Authorization", "Bearer $accessToken").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw parseError(resp.code, resp.body?.string().orEmpty())
                resp.body?.bytes() ?: ByteArray(0)
            }
        }

        private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

        fun streamUrl(botId: String? = null, test: Boolean = false, roomId: String? = null, since: Long? = null): String {
            val b = StringBuilder(baseUrl).append("/stream")
            val params = mutableListOf<String>()
            if (!roomId.isNullOrBlank()) params += "roomId=" + java.net.URLEncoder.encode(roomId, "UTF-8")
            else if (!botId.isNullOrBlank()) params += "botId=" + java.net.URLEncoder.encode(botId, "UTF-8")
            if (!roomId.isNullOrBlank() && since != null && since >= 0) params += "since=$since"
            if (test) params += "mode=test"
            if (params.isNotEmpty()) b.append('?').append(params.joinToString("&"))
            return b.toString()
        }

        private suspend fun getJson(path: String, accessToken: String? = null): JSONObject =
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(baseUrl + path).get()
                if (accessToken != null) req.header("Authorization", "Bearer $accessToken")
                http.newCall(req.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw parseError(resp.code, text)
                    JSONObject(text.ifBlank { "{}" })
                }
            }

        private suspend fun postJson(path: String, body: JSONObject, accessToken: String? = null): JSONObject {
            val (code, json) = postJsonRaw(path, body, accessToken)
            if (code !in 200..299) {
                throw BridgeError(
                    json.optJSONObject("error")?.optString("message")
                        ?: json.optString("message", "HTTP $code"),
                    code,
                    json.optJSONObject("error")?.optStringOrNull("zh"),
                )
            }
            return json
        }

        private suspend fun postJsonRaw(
            path: String,
            body: JSONObject,
            accessToken: String? = null,
        ): Pair<Int, JSONObject> = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl + path)
                .post(body.toString().toRequestBody(JSON))
            if (accessToken != null) req.header("Authorization", "Bearer $accessToken")
            http.newCall(req.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(text.ifBlank { "{}" })
                } catch (_: Exception) {
                    JSONObject().put("raw", text)
                }
                if (resp.code == 401) throw BridgeError(
                    json.optJSONObject("error")?.optString("message") ?: "unauthorized",
                    401,
                )
                resp.code to json
            }
        }

        private fun parseError(code: Int, text: String): BridgeError {
            val zh = try { JSONObject(text).optJSONObject("error")?.optStringOrNull("zh") } catch (_: Exception) { null }
            val msg = try {
                JSONObject(text).optJSONObject("error")?.optString("message")
                    ?: JSONObject(text).optString("message").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            return BridgeError(msg ?: "HTTP $code", code, zh)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val API_PREFIX = "/api/mobile-bridge/v1"

        /** Trim, drop trailing '/', and append [API_PREFIX] unless already present. */
        fun normalizeBase(url: String): String {
            val t = url.trim().trimEnd('/')
            if (t.isEmpty()) return t
            return if (t.endsWith(API_PREFIX, ignoreCase = true)) t else t + API_PREFIX
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // SSE needs long read; per-call override for short requests
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

        /** Short-timeout client for REST (not SSE). */
        fun restClient(): OkHttpClient = OkHttpClient.Builder()
            // Never silently replay a POST (e.g. /chats/send) on a stale connection.
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
