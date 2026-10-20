package com.deepseek.dshmobile.util

/**
 * Parse pairing input: raw 6-hex code, `dshmb:<code>`, or `dshmb:<code>@<http(s) url>`.
 */
object PairCode {
    private val CODE = Regex("^[0-9a-fA-F]{6}$")

    data class Parsed(val code: String, val baseUrl: String?)

    fun normalize(raw: String): String? = parse(raw)?.code

    fun parse(raw: String): Parsed? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("dshmb:", ignoreCase = true)) {
            return if (CODE.matches(trimmed)) Parsed(trimmed.lowercase(), null) else null
        }
        var rest = trimmed.substring(6).trim().removePrefix("//")
        var url: String? = null
        val at = rest.indexOf('@')
        if (at >= 0) {
            val u = rest.substring(at + 1).trim()
            rest = rest.substring(0, at)
            if (!isHttpUrl(u)) return null
            url = u
        } else {
            rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        }
        val code = rest.trim()
        return if (CODE.matches(code)) Parsed(code.lowercase(), url) else null
    }

    private fun isHttpUrl(u: String): Boolean {
        val lower = u.lowercase()
        val hostPart = when {
            lower.startsWith("https://") -> u.substring(8)
            lower.startsWith("http://") -> u.substring(7)
            else -> return false
        }
        return hostPart.isNotBlank() && !hostPart.startsWith("/") && hostPart.none { it.isWhitespace() }
    }
}
