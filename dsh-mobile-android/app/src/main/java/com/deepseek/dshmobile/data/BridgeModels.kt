package com.deepseek.dshmobile.data

data class HealthResponse(val ok: Boolean, val name: String?, val version: String?)

data class PairCompleteResult(
    val deviceId: String,
    val refreshToken: String,
    val refreshExpiresAt: Long?,
)

data class AccessTokenResult(
    val accessToken: String,
    val expiresAt: Long,
    val expiresIn: Long?,
)

data class BotInfo(
    val id: String,
    val name: String,
    val role: String?,
    val sessionId: String?,
    val model: String?,
    val main: Boolean,
)

data class TeamResponse(
    val available: Boolean,
    val bots: List<BotInfo>,
    val mainBotIds: List<String>,
    val revision: Long?,
    val reason: String?,
)

data class ChatItem(
    val kind: String,
    val text: String? = null,
    val time: Long? = null,
    val seq: Long? = null,
    val role: String? = null,
    val botId: String? = null,
    val name: String? = null,
    val images: Int? = null,
)

data class HistoryResponse(
    val available: Boolean,
    val botId: String?,
    val sessionId: String?,
    val items: List<ChatItem>,
    val nextCursor: String?, // JSON string of cursor object, or null
    val stub: Boolean,
    val reason: String?,
)

data class SendResponse(
    val ok: Boolean,
    val botId: String?,
    val sessionId: String?,
    val stub: Boolean,
    val reason: String?,
)

class BridgeError(message: String, val status: Int? = null) : Exception(message)
