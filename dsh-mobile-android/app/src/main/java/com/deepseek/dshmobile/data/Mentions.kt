package com.deepseek.dshmobile.data

/**
 * Group-chat "@" picker, matching ds-bot's desktop rule (shared/mentions.js + at-menu.js):
 * typing "@" lists this group's members; picking one inserts "@Name " into the message.
 * The computer (ds-bot) reads the same "@Name" from the text to decide who answers —
 * no separate id list. Plain message (no @) follows the group's mode on the computer
 * (everyone → all members; admin → admin first). "@所有人" / "@everyone" calls everyone.
 */
object Mentions {
    /** Words ds-bot reads as "everyone" (shared/mentions.js ALL_WORDS). */
    val ALL_WORDS = listOf("all", "everyone", "所有人", "全体")

    data class Active(val atIndex: Int, val query: String)

    /** One row in the picker. [id] is null for "everyone". [insert] is what replaces "@query". */
    data class Row(val id: String?, val name: String, val insert: String, val everyone: Boolean = false)

    /**
     * Unfinished "@query" at the end of [text] (typing at the caret end).
     * Skips addresses like "a@b" the same way ds-bot does.
     */
    fun activeAt(text: String): Active? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && isAddressBefore(text[at - 1])) return null
        val query = text.substring(at + 1)
        if (query.contains('\n') || query.contains('\r')) return null
        return Active(at, query)
    }

    /**
     * Members (and optional "所有人") whose names match [query], longest names first so
     * "@新 Bot 2" stays distinct from "@新 Bot" the same way the host parses.
     */
    fun rows(members: List<BotInfo>, query: String, includeEveryone: Boolean = true): List<Row> {
        val needle = query.trim().lowercase()
        val bots = members
            .filter { it.name.isNotBlank() && (needle.isEmpty() || it.name.lowercase().contains(needle)) }
            .sortedByDescending { it.name.length }
            .map { Row(it.id, it.name, "@${it.name} ") }
        val everyone = if (includeEveryone && members.size > 1 && ALL_WORDS.any { word ->
            needle.isEmpty() || word.startsWith(needle) || "所有人".contains(needle)
        }) {
            listOf(Row(null, "所有人", "@所有人 ", everyone = true))
        } else emptyList()
        return bots + everyone
    }

    /** Replace the active "@query" with [insert] (usually "@Name "). */
    fun apply(text: String, active: Active, insert: String): String {
        val end = active.atIndex + 1 + active.query.length
        return text.substring(0, active.atIndex) + insert + text.substring(end.coerceAtMost(text.length))
    }

    /** Bot ids this text calls, by the same longest-name rule ds-bot uses (no references). */
    fun calledIds(text: String, members: List<BotInfo>): Called {
        val value = text
        val byLength = members.filter { it.name.isNotBlank() }.sortedByDescending { it.name.length }
        val found = linkedSetOf<String>()
        var all = false
        var i = 0
        while (true) {
            val at = value.indexOf('@', i)
            if (at < 0) break
            if (at > 0 && isAddressBefore(value[at - 1])) {
                i = at + 1
                continue
            }
            val rest = value.substring(at + 1)
            val bot = byLength.find { fitsAt(rest, it.name) }
            val everyone = if (bot == null) ALL_WORDS.find { fitsAt(rest, it) } else null
            when {
                bot != null -> {
                    found.add(bot.id)
                    i = at + 1 + bot.name.length
                }
                everyone != null -> {
                    all = true
                    i = at + 1 + everyone.length
                }
                else -> i = at + 1
            }
        }
        return Called(all = all, ids = found.toList())
    }

    data class Called(val all: Boolean, val ids: List<String>)

    /** Who should answer this opener, mirroring ds-bot openingSpeakers for a user message. */
    fun whoAnswers(text: String, members: List<BotInfo>, mode: String, adminId: String?): List<String> {
        val called = calledIds(text, members)
        if (called.all) return members.map { it.id }
        if (called.ids.isNotEmpty()) return called.ids
        if (mode == "admin" && adminId != null && members.any { it.id == adminId }) return listOf(adminId)
        val adminFirst = if (adminId != null) {
            listOf(adminId) + members.map { it.id }.filter { it != adminId }
        } else members.map { it.id }
        return adminFirst.filter { id -> members.any { it.id == id } }
    }

    private fun fitsAt(rest: String, word: String): Boolean {
        if (rest.length < word.length) return false
        if (!rest.regionMatches(0, word, 0, word.length, ignoreCase = true)) return false
        val next = rest.getOrNull(word.length) ?: return true
        // NAME_GOES_ON in shared/mentions.js: ASCII word char, path slash, or ".word"
        if (isAsciiWord(next) || next == '-' || next == '/' || next == '\\') return false
        if (next == '.' && rest.getOrNull(word.length + 1)?.let { isAsciiWord(it) || it == '-' } == true) return false
        return true
    }

    /** ds-bot ADDRESS_BEFORE: only ASCII letters/digits and _ . / (not CJK). */
    private fun isAddressBefore(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '.' || ch == '/'

    private fun isAsciiWord(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'
}
