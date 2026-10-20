package com.deepseek.dshmobile.data

/** What the background check should tell the user, decided without Android. */
object NotifyLogic {
    fun key(kind: String, id: String) = "$kind:$id"

    data class Alert(
        val kind: String,
        val id: String,
        val name: String,
        val text: String,
        val count: Int,
        val approval: Boolean = false,
        val approvalId: String? = null,
    ) {
        val tag: String get() = if (approval) "ap:$approvalId" else key(kind, id)
    }

    /** What the last check saw: unread count per chat and approval ids. */
    data class Seen(val unread: Map<String, Int>, val approvals: Set<String>)

    fun seen(now: ChatListResponse) = Seen(now.chats.associate { key(it.kind, it.id) to it.unread }, now.approvals.map { it.id }.toSet())

    /**
     * New messages and new approvals since [prev]. The first check after start only remembers
     * (no flood of old messages); muted chats, the chat open on screen and the global switch are respected.
     */
    fun alerts(prev: Seen?, now: ChatListResponse, prefs: ChatPrefs, openKey: String?): List<Alert> {
        if (prev == null || !prefs.notify) return emptyList()
        val out = mutableListOf<Alert>()
        for (c in now.chats) {
            val k = key(c.kind, c.id)
            val before = prev.unread[k] ?: 0
            if (c.unread <= before || k in prefs.muted || k == openKey) continue
            out += Alert(c.kind, c.id, c.name, Look.plainPreview(c.preview).ifBlank { "有新消息" }, c.unread)
        }
        for (a in now.approvals) {
            if (a.id in prev.approvals) continue
            val kind = if (a.roomId != null) "room" else "bot"
            val id = a.roomId ?: a.botId ?: continue
            if (key(kind, id) in prefs.muted) continue
            val who = a.botName ?: "助手"
            out += Alert(kind, id, a.roomName ?: who, "$who 在等你确认：${a.action}", 1, approval = true, approvalId = a.id)
        }
        return out
    }

    /** Second line when several messages piled up. */
    fun countLine(count: Int): String? = if (count > 1) "$count 条新消息" else null
}
