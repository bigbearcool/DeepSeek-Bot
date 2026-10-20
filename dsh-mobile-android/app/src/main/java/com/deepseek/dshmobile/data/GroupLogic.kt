package com.deepseek.dshmobile.data

/** A member's reply being written right now in a group chat. */
data class LiveReply(
    val attemptId: String,
    val botId: String?,
    val name: String?,
    val text: String = "",
    /** Set when the member finished writing; kept until its logged line arrives. */
    val endedAt: Long? = null,
)

/**
 * Pure bookkeeping for group chat streaming. Several members can write at once, so each
 * attempt gets its own live bubble; ds-bot logs the finished line a moment later, which
 * replaces the bubble. A member that stays silent writes "[PASS]", never shown.
 */
object RoomStreams {
    const val KEEP_ENDED_MS = 8_000L
    private val PASS = Regex("""^\s*[\[【(（]\s*pass\s*[\]】)）]|^\s*pass\s*[.。!！]?\s*$""", RegexOption.IGNORE_CASE)

    fun isPass(text: String): Boolean = text.isBlank() || PASS.containsMatchIn(text)

    /** Text worth showing: no "[PASS]" (even half-written), no "Name:" echo at the start. */
    fun shown(reply: LiveReply): String? {
        var t = reply.text
        val n = reply.name
        if (!n.isNullOrBlank()) t = t.replace(Regex("^\\s*\\**" + Regex.escape(n) + "\\**\\s*[:：]\\s*"), "")
        val tt = t.trim()
        if (tt.isEmpty() || PASS.containsMatchIn(tt)) return null
        val up = tt.uppercase()
        if (listOf("[PASS]", "【PASS】", "(PASS)", "（PASS）").any { it.startsWith(up) }) return null
        return t
    }

    fun start(list: List<LiveReply>, attemptId: String, botId: String?, name: String?): List<LiveReply> =
        list.filterNot { it.attemptId == attemptId } + LiveReply(attemptId, botId, name)

    fun delta(list: List<LiveReply>, attemptId: String, text: String, botId: String? = null, name: String? = null): List<LiveReply> {
        if (list.none { it.attemptId == attemptId }) return list + LiveReply(attemptId, botId, name, text)
        return list.map { if (it.attemptId == attemptId) it.copy(text = it.text + text) else it }
    }

    fun end(list: List<LiveReply>, attemptId: String, now: Long): List<LiveReply> =
        list.mapNotNull {
            when {
                it.attemptId != attemptId -> it
                isPass(it.text) -> null
                else -> it.copy(endedAt = now)
            }
        }

    /** A logged line from a member replaces that member's finished bubble. */
    fun onMessage(list: List<LiveReply>, item: ChatItem): List<LiveReply> {
        if (item.kind != "member" || item.botId == null) return list
        val i = list.indexOfFirst { it.botId == item.botId && it.endedAt != null }
        if (i >= 0) return list.toMutableList().also { it.removeAt(i) }
        // Logged while still writing (a long tool run): drop the bubble once the text matches.
        return list.filterNot { it.botId == item.botId && it.text.trim().isNotEmpty() && item.text.orEmpty().contains(it.text.trim().take(40)) }
    }

    fun prune(list: List<LiveReply>, now: Long): List<LiveReply> =
        list.filter { it.endedAt == null || now - it.endedAt < KEEP_ENDED_MS }
}

/**
 * A group chat round (bridge >= 0.0.10 sends `round` start/end from ds-bot's group relay).
 * A member that fails, waits for an approval, keeps working or misses the round leaves no
 * line in the group log, only an event of the round; those, a member line, or the round's
 * end all end the waiting card, so it never stays up forever.
 */
object RoomRound {
    private val ENDS_WAIT = setOf("failed", "approval", "working", "missed")

    fun endsWait(item: ChatItem): Boolean =
        item.kind == "member" || (item.kind == "event" && item.role in ENDS_WAIT)

    /** Round over: no more waiting; nobody said anything and nobody failed → a quiet note. */
    fun onEnd(messages: List<ChatItem>, replied: Int, failed: Int, now: Long): List<ChatItem> =
        if (replied == 0 && failed == 0) messages + ChatItem(kind = "event", role = "quiet", time = now) else messages

    /** Bubbles that never got shown text are dropped when the round ends. */
    fun dropUnshown(live: List<LiveReply>): List<LiveReply> = live.filter { RoomStreams.shown(it) != null }

    /** Highest group log seq the phone holds, sent as `since` when the stream (re)connects. */
    fun since(messages: List<ChatItem>): Long? = messages.filter { !it.local }.mapNotNull { it.seq }.maxOrNull()
}

/** Client-side copy of ds-bot's name rules (shared/mentions.js nameProblem); ds-bot checks again. */
object BotRules {
    private val ALL_WORDS = listOf("all", "everyone", "所有人", "全体")
    private val USER_NAMES = listOf("user", "you")
    const val DEFAULT_NAME = "新助手"

    /** Like ds-bot freshBotName: the default name, numbered when it is taken. */
    fun freshName(taken: List<String>, base: String = DEFAULT_NAME): String {
        val set = taken.map { it.lowercase() }.toSet()
        if (base.lowercase() !in set) return base
        var i = 2
        while ("${base.lowercase()} $i" in set) i++
        return "$base $i"
    }

    fun problem(name: String, taken: List<String>): String? {
        val t = name.trim()
        if (t.isEmpty()) return "助手需要一个名字"
        if (t.any { it == '@' || it == '`' || it == '\n' || it == '\r' }) return "助手名字里不能有 @、` 或换行"
        if (t.lowercase() in ALL_WORDS) return "「$t」在群里表示所有人，不能用作助手名字"
        if (t.lowercase() in USER_NAMES) return "「$t」在对话里表示你本人，不能用作助手名字"
        if (taken.any { it.equals(t, ignoreCase = true) }) return "已经有叫 $t 的助手了"
        return null
    }

    /** The desktop form opens on the computer's default model when it is offered, else the first one. */
    fun startModel(options: BotOptions): String =
        (options.models.find { it.ref == options.defaultModel } ?: options.models.firstOrNull())?.ref.orEmpty()
}

object RoomRules {
    /** Desktop: admin first, then at least one more member; at most [max] in all. */
    fun canCreate(draft: NewRoomDraft, max: Int = 12): Boolean =
        draft.admin != null && draft.members.any { it != draft.admin } && (1 + draft.members.count { it != draft.admin }) <= max

    fun pickAdmin(draft: NewRoomDraft, id: String) = draft.copy(admin = id, members = draft.members.filter { it != id })

    fun toggle(draft: NewRoomDraft, id: String): NewRoomDraft =
        if (id == draft.admin) draft
        else draft.copy(members = if (id in draft.members) draft.members - id else draft.members + id)

}
