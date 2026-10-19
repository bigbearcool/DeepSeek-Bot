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

        suspend fun send(accessToken: String, botId: String, text: String): SendResponse {
            val body = JSONObject().put("botId", botId).put("text", text)
            val (code, json) = postJsonRaw("/chats/send", body, accessToken)
            return SendResponse(
                ok = json.optBoolean("ok", code in 200..299),
                botId = json.optStringOrNull("botId"),
                sessionId = json.optStringOrNull("sessionId"),
                stub = json.optBoolean("stub", false),
                reason = json.optStringOrNull("reason")
                    ?: json.optJSONObject("error")?.optStringOrNull("message"),
            )
        }

        fun streamUrl(botId: String? = null, test: Boolean = false): String {
            val b = StringBuilder(baseUrl).append("/stream")
            val params = mutableListOf<String>()
            if (!botId.isNullOrBlank()) params += "botId=" + java.net.URLEncoder.encode(botId, "UTF-8")
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
            val msg = try {
                JSONObject(text).optJSONObject("error")?.optString("message")
                    ?: JSONObject(text).optString("message").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            return BridgeError(msg ?: "HTTP $code", code)
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
