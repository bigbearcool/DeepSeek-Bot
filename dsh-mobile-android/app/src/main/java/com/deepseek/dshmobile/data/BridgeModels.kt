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
    val color: String? = null,
    val shape: String? = null,
)

/** A ds-bot group chat. */
data class RoomInfo(
    val id: String,
    val name: String,
    val members: List<String> = emptyList(),
    val memberNames: List<String> = emptyList(),
    val admin: String? = null,
    val preview: String? = null,
)

data class TeamResponse(
    val available: Boolean,
    val bots: List<BotInfo>,
    val mainBotIds: List<String>,
    val revision: Long?,
    val rooms: List<RoomInfo> = emptyList(),
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
    /** Client-generated id for messages sent from this phone (echoed by bridge >= 0.0.8). */
    val clientMessageId: String? = null,
    /** True while a locally inserted item has not yet been confirmed by the server. */
    val local: Boolean = false,
    /** Group chat: the line was shortened on the computer. */
    val cut: Boolean = false,
    /** Pictures and files in this message. */
    val attachments: List<Attachment> = emptyList(),
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
    val duplicate: Boolean = false,
)

data class RoomHistoryResponse(val roomId: String, val items: List<ChatItem>)

data class ModelOption(val ref: String, val name: String, val providerName: String?, val needsKey: Boolean)
data class NamedOption(val id: String, val label: String)
data class ColorOption(val id: String, val hex: String?)
data class FolderOption(val path: String, val title: String)
data class EffortOption(val id: String, val name: String)

/** What the new-Bot form offers, all read from ds-bot on the computer. */
data class BotOptions(
    val models: List<ModelOption>,
    val defaultModel: String?,
    /** null: this computer offers no choice of working style. */
    val agentPresets: List<NamedOption>?,
    val colors: List<ColorOption>,
    val shapes: List<NamedOption>,
    val teamFolder: String?,
    val folders: List<FolderOption>,
    val canBrowse: Boolean,
    val nameMax: Int = 40,
    val roleMax: Int = 24,
    val roomNameMax: Int = 60,
    val roomMaxMembers: Int = 12,
)

data class FolderListing(val path: String, val entries: List<FolderOption>)

/** New-Bot form values; field names match ds-bot's create-bot endpoint. */
data class NewBotDraft(
    val name: String = "",
    val role: String = "",
    val shape: String = "",
    val color: String = "",
    val image: String? = null,
    val model: String = "",
    val effort: String = "",
    val agentPreset: String = "",
    val workDir: String = "",
    val instructions: String = "",
    val brief: String = "",
)

/** New group chat form, same steps as the desktop: admin, then members, then a name. */
data class NewRoomDraft(
    val admin: String? = null,
    val members: List<String> = emptyList(),
    val name: String = "",
)

class BridgeError(message: String, val status: Int? = null, val zh: String? = null) : Exception(message)

/** Device token can no longer be renewed (revoked, or refresh token rejected). Re-pair needed. */
class AuthRevokedError(message: String) : Exception(message)
