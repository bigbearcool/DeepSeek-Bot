package com.deepseek.dshmobile.data

import java.util.Calendar

/** Pure helpers for the batch-1 look: avatar shapes, time words, voice text, list filter. */
object Look {
    /** Shapes a Bot avatar can take; the same Bot always gets the same one. */
    enum class Shape { Circle, Drop, Hexagon, Triangle, Square, Pill, Arch }

    private fun hash(key: String): Int = key.fold(7) { a, ch -> a * 31 + ch.code } and 0x7fffffff

    fun shapeFor(key: String): Shape = Shape.entries[hash(key) % Shape.entries.size]

    /** Colors used when a Bot has none set on the computer (ds-bot ink ids). */
    val FALLBACK_INKS = listOf("violet", "blue", "green", "orange", "magenta", "cyan", "yellow", "red", "brown", "cobalt")

    fun fallbackInk(key: String): String = FALLBACK_INKS[(hash(key) / 7) % FALLBACK_INKS.size]

    /** 上午8:41 / 下午3:05 (no space, like Grok Bot) */
    fun clock(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%s%d:%02d".format(if (h < 12) "上午" else "下午", h12, c.get(Calendar.MINUTE))
    }

    private val WEEK = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    private fun dayStart(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Whole days between the two dates (0 = same day). */
    fun daysBetween(at: Long, now: Long): Int = Math.round((dayStart(now) - dayStart(at)) / 86_400_000.0).toInt()

    /** Day word: 今天 / 昨天 / 星期二 / 10月3日 / 2025年10月3日 */
    fun day(at: Long, now: Long): String {
        val a = Calendar.getInstance().apply { timeInMillis = at }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        val d = daysBetween(at, now)
        return when {
            d == 0 -> "今天"
            d == 1 -> "昨天"
            d in 2..6 -> WEEK[a.get(Calendar.DAY_OF_WEEK) - 1]
            a.get(Calendar.YEAR) == n.get(Calendar.YEAR) -> "${a.get(Calendar.MONTH) + 1}月${a.get(Calendar.DAY_OF_MONTH)}日"
            else -> "${a.get(Calendar.YEAR)}年${a.get(Calendar.MONTH) + 1}月${a.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    /** Divider inside a chat: 今天 上午8:00 */
    fun dividerLabel(at: Long, now: Long = System.currentTimeMillis()): String = day(at, now) + " " + clock(at)

    const val DIVIDER_GAP_MS = 15 * 60_000L

    /**
     * Index -> divider label for a list of message times (null = no time).
     * A divider goes before the first timed message and before any message 15+ minutes after the previous one.
     */
    fun dividers(times: List<Long?>, now: Long = System.currentTimeMillis()): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        var prev: Long? = null
        times.forEachIndexed { i, t ->
            if (t == null || t <= 0) return@forEachIndexed
            if (prev == null || t - prev!! >= DIVIDER_GAP_MS) out[i] = dividerLabel(t, now)
            prev = t
        }
        return out
    }

    /** Put spoken text into the input box after whatever is already typed. */
    fun mergeVoice(existing: String, spoken: String): String {
        val s = spoken.trim()
        if (s.isEmpty()) return existing
        if (existing.isBlank()) return s
        val last = existing.last()
        if (last.isWhitespace()) return existing + s
        val latin = { ch: Char -> ch.code < 0x2E80 && ch.isLetterOrDigit() }
        return if (latin(last) && latin(s.first())) "$existing $s" else existing + s
    }

    /** Placeholder for the input box. */
    fun inputHint(name: String?, inRoom: Boolean): String = when {
        inRoom -> if (name.isNullOrBlank()) "在群里发消息" else "给 $name 发消息"
        name.isNullOrBlank() -> "向助手提问"
        else -> "向 $name 提问"
    }

    /** One plain line for the list: Markdown marks taken out, spaces collapsed. */
    fun plainPreview(text: String): String = text
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)?"), "$1")
        .replace(Regex("```[a-zA-Z0-9]*"), " ")
        .replace(Regex("\\|?(\\s*:?-{2,}:?\\s*\\|)+(\\s*:?-{2,}:?\\s*)?"), " ")
        .replace(Regex("#{1,6}\\s+"), "")
        .replace(Regex("(^|\\s)([-*+]|\\d+\\.)\\s+"), "$1")
        .replace(Regex("(^|\\s)>\\s?"), "$1")
        .replace(Regex("\\*\\*|__|`|~~"), "")
        .replace(Regex("\\s*\\|\\s*"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Simple search over the conversation list: name or last message. */
    fun filter(chats: List<ChatEntry>, query: String): List<ChatEntry> {
        val q = query.trim()
        if (q.isEmpty()) return chats
        return chats.filter { it.name.contains(q, ignoreCase = true) || it.preview.contains(q, ignoreCase = true) }
    }

    // ---- quote reply ----
    /** A short one-line piece of a message for the reply chip and the quote. */
    fun snippet(text: String, max: Int = 40): String {
        val p = plainPreview(text)
        return if (p.length > max) p.take(max) + "…" else p
    }

    /** What is sent when replying to a message: a Markdown quote line, a blank line, then the reply. */
    fun quoted(name: String?, snippet: String, reply: String): String {
        val who = name?.takeIf { it.isNotBlank() }?.let { "$it：" }.orEmpty()
        return "> $who$snippet\n\n$reply"
    }

    /** Splits a sent message back into (quote, rest) for the bubble; quote is null when there is none. */
    fun splitQuote(text: String): Pair<String?, String> {
        if (!text.startsWith("> ")) return null to text
        val end = text.indexOf("\n\n")
        if (end < 0) return null to text
        return text.substring(2, end).trim() to text.substring(end + 2)
    }

    // ---- code blocks ----
    data class Part(val code: Boolean, val text: String, val lang: String = "")

    /** Splits Markdown into plain parts and fenced code blocks (an unfinished block while streaming counts as code). */
    fun splitCode(md: String): List<Part> {
        val out = mutableListOf<Part>()
        val buf = StringBuilder()
        var inCode = false
        var lang = ""
        val code = StringBuilder()
        md.split("\n").forEach { line ->
            val t = line.trimStart()
            if (t.startsWith("```")) {
                if (!inCode) {
                    if (buf.isNotBlank()) out += Part(false, buf.toString().trim('\n'))
                    buf.clear(); inCode = true; lang = t.removePrefix("```").trim()
                } else {
                    out += Part(true, code.toString().trimEnd('\n'), lang)
                    code.clear(); inCode = false; lang = ""
                }
            } else if (inCode) code.append(line).append('\n') else buf.append(line).append('\n')
        }
        if (inCode) out += Part(true, code.toString().trimEnd('\n'), lang)
        if (buf.isNotBlank()) out += Part(false, buf.toString().trim('\n'))
        return out
    }

    /** 「X 正在工作」 for one Bot, 「X 正在输入…」 for group members. */
    fun workingLabel(names: List<String>, inRoom: Boolean): String {
        val who = names.filter { it.isNotBlank() }.distinct().joinToString("、").ifEmpty { "助手" }
        return if (inRoom) "$who 正在输入…" else "$who 正在工作"
    }
}
