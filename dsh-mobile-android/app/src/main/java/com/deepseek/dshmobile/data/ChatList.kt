package com.deepseek.dshmobile.data

import org.json.JSONObject
import java.util.Calendar

/** One row of the conversation list: a Bot chat or a group chat (bridge >= 0.0.11). */
data class ChatEntry(
    val kind: String, // "bot" | "room"
    val id: String,
    val name: String,
    val color: String? = null,
    val main: Boolean = false,
    val preview: String = "",
    val lastAt: Long? = null,
    val unread: Int = 0,
    val running: Boolean = false,
    val members: List<String> = emptyList(),
) {
    val isRoom: Boolean get() = kind == "room"
}

/** A request waiting for the user's yes/no, already in plain words from the bridge. */
data class ApprovalInfo(
    val id: String,
    val botId: String?,
    val botName: String?,
    val roomId: String?,
    val roomName: String?,
    val action: String,
    val reason: String,
    val detail: String,
    val answerable: Boolean,
)

data class ChatListResponse(val available: Boolean, val chats: List<ChatEntry>, val approvals: List<ApprovalInfo>)

fun parseChatEntry(o: JSONObject) = ChatEntry(
    kind = if (o.optString("kind") == "room") "room" else "bot",
    id = o.optString("id"),
    name = o.optString("name", o.optString("id")),
    color = o.optStringOrNull("color"),
    main = o.optBoolean("main", false),
    preview = o.optString("preview", ""),
    lastAt = o.optLongOrNull("lastAt")?.takeIf { it > 0 },
    unread = o.optInt("unread", 0).coerceAtLeast(0),
    running = o.optBoolean("running", false),
    members = o.optJSONArray("members").strings(),
)

fun parseApproval(o: JSONObject) = ApprovalInfo(
    id = o.optString("id"),
    botId = o.optStringOrNull("botId"),
    botName = o.optStringOrNull("botName"),
    roomId = o.optStringOrNull("roomId"),
    roomName = o.optStringOrNull("roomName"),
    action = o.optStringOrNull("action") ?: "做一个需要你同意的操作",
    reason = o.optString("reason", ""),
    detail = o.optString("detail", ""),
    answerable = o.optBoolean("answerable", false),
)

fun parseChatList(o: JSONObject) = ChatListResponse(
    available = o.optBoolean("available", true),
    chats = o.optJSONArray("chats")?.mapObjects()?.map(::parseChatEntry).orEmpty(),
    approvals = o.optJSONArray("approvals")?.mapObjects()?.map(::parseApproval).orEmpty(),
)

object ChatListLogic {
    /** Newest activity first; chats without any activity keep their order at the end. */
    fun sorted(chats: List<ChatEntry>): List<ChatEntry> =
        chats.withIndex().sortedWith(compareByDescending<IndexedValue<ChatEntry>> { it.value.lastAt ?: Long.MIN_VALUE }.thenBy { it.index }).map { it.value }

    fun badge(unread: Int): String? = when {
        unread <= 0 -> null
        unread > 99 -> "99+"
        else -> unread.toString()
    }

    /** The open chat is being read: its count stays at zero on this phone. */
    fun clearOpen(chats: List<ChatEntry>, kind: String?, id: String?): List<ChatEntry> =
        if (kind == null || id == null) chats else chats.map { if (it.kind == kind && it.id == id) it.copy(unread = 0) else it }

    /** Approvals that belong to one chat: a group's members there, or a Bot's own chat. */
    fun approvalsFor(all: List<ApprovalInfo>, kind: String?, id: String?): List<ApprovalInfo> = when (kind) {
        "room" -> all.filter { it.roomId == id }
        "bot" -> all.filter { it.roomId == null && it.botId == id }
        else -> emptyList()
    }

    fun whereText(a: ApprovalInfo): String = when {
        a.roomName != null -> "${a.botName ?: "助手"}（${a.roomName}）"
        else -> a.botName ?: "助手"
    }

    /** Short time for the list: 刚刚 / 上午 8:41 / 昨天 / 星期二 / 10月18日 / 2025/10/18. */
    fun timeLabel(at: Long?, now: Long = System.currentTimeMillis()): String {
        if (at == null || at <= 0) return ""
        if (now - at in 0 until 60_000) return "刚刚"
        val d = Look.daysBetween(at, now)
        return when {
            d <= 0 -> Look.clock(at)
            d in 1..6 -> Look.day(at, now)
            else -> {
                val a = Calendar.getInstance().apply { timeInMillis = at }
                val n = Calendar.getInstance().apply { timeInMillis = now }
                if (a.get(Calendar.YEAR) == n.get(Calendar.YEAR)) "${a.get(Calendar.MONTH) + 1}月${a.get(Calendar.DAY_OF_MONTH)}日"
                else "${a.get(Calendar.YEAR)}/${a.get(Calendar.MONTH) + 1}/${a.get(Calendar.DAY_OF_MONTH)}"
            }
        }
    }

    /** Stop shows while this phone waits for a reply, or the computer says a reply is running. */
    fun showStop(running: Boolean, waiting: Boolean): Boolean = running || waiting
}
