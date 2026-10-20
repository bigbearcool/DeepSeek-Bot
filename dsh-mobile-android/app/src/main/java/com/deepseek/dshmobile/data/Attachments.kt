package com.deepseek.dshmobile.data

import org.json.JSONObject

/** A picture or file in a message, as the computer stored it (id null: shown by name only). */
data class Attachment(
    val type: String, // "image" | "file"
    val id: String?,
    val name: String?,
    val bytes: Long? = null,
    val mediaType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Set on this phone's own copy before the computer has it (content:// uri). */
    val localUri: String? = null,
) {
    val isImage: Boolean get() = type == "image"
}

/** Something picked for the next message: uploading, ready (uploadId), or failed. */
data class PendingAttachment(
    val localId: String,
    val uri: String,
    val name: String,
    val isImage: Boolean,
    val bytes: Long,
    val uploadId: String? = null,
    val failed: String? = null,
) {
    val ready: Boolean get() = uploadId != null
    fun asAttachment() = Attachment(if (isImage) "image" else "file", null, name, bytes, localUri = uri)
}

data class UploadResult(val uploadId: String, val type: String, val name: String?, val bytes: Long)

fun parseAttachment(o: JSONObject) = Attachment(
    type = if (o.optString("type") == "image") "image" else "file",
    id = o.optStringOrNull("id"),
    name = o.optStringOrNull("name"),
    bytes = o.optLongOrNull("bytes"),
    mediaType = o.optStringOrNull("mediaType"),
    width = if (o.has("width") && !o.isNull("width")) o.optInt("width") else null,
    height = if (o.has("height") && !o.isNull("height")) o.optInt("height") else null,
)

fun parseUpload(o: JSONObject) = UploadResult(o.getString("uploadId"), o.optString("type", "file"), o.optStringOrNull("name"), o.optLong("bytes", 0))

object AttachLogic {
    const val MAX_BYTES = 25L * 1024 * 1024
    const val MAX_PER_MESSAGE = 9

    /** 1.2 MB / 340 KB / 12 B */
    fun size(bytes: Long?): String = when {
        bytes == null || bytes < 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    /** PDF / DOCX / … from the name, for the file card. */
    fun kind(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return if (ext.isEmpty() || ext.length > 5) "文件" else ext.uppercase()
    }

    /** Can the message go: text or at least one picked item, and nothing still uploading or failed. */
    fun canSend(text: String, pending: List<PendingAttachment>): Boolean =
        (text.isNotBlank() || pending.isNotEmpty()) && pending.all { it.ready }

    fun problem(bytes: Long, count: Int): String? = when {
        bytes > MAX_BYTES -> "文件太大了，最多 25 MB"
        bytes == 0L -> "文件是空的"
        count >= MAX_PER_MESSAGE -> "一次最多发 $MAX_PER_MESSAGE 个"
        else -> null
    }

    /** Fit a picture in a box, keeping its shape (thumbnail size). */
    fun fit(width: Int?, height: Int?, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (width == null || height == null || width <= 0 || height <= 0) return maxW to (maxW * 3 / 4)
        val scale = minOf(maxW.toFloat() / width, maxH.toFloat() / height, 1f)
        return maxOf(48, (width * scale).toInt()) to maxOf(48, (height * scale).toInt())
    }
}

/**
 * A Bot's own chat shows its messages with other Bots as one gray line,
 * 「N 条消息，与 [头像] K 个 Bot」, until the user taps it (like Grok Bot).
 */
object Exchanges {
    private val ROLES = setOf("request", "reply", "report")
    fun isExchange(item: ChatItem): Boolean = item.kind == "event" && item.role in ROLES

    /** A run of exchange items: where it starts, how many, and which Bots took part. */
    data class Run(val start: Int, val count: Int, val bots: List<Pair<String?, String?>>)

    /** Runs of consecutive exchange items, by index in [items]. */
    fun runs(items: List<ChatItem>): List<Run> {
        val out = mutableListOf<Run>()
        var i = 0
        while (i < items.size) {
            if (!isExchange(items[i])) { i++; continue }
            val start = i
            while (i < items.size && isExchange(items[i])) i++
            val bots = items.subList(start, i).map { it.botId to it.name }.distinctBy { it.first ?: it.second }
            out += Run(start, i - start, bots)
        }
        return out
    }

    fun words(run: Run): Pair<String, String> = "${run.count} 条消息，与" to "${run.bots.size} 个 Bot"
}
