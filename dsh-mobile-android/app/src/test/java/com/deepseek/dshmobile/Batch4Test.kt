package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.AttachLogic
import com.deepseek.dshmobile.data.ChatItem
import com.deepseek.dshmobile.data.Exchanges
import com.deepseek.dshmobile.data.PendingAttachment
import com.deepseek.dshmobile.data.parseChatItem
import com.deepseek.dshmobile.data.parseUpload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch4Test {
    private fun p(ready: Boolean) = PendingAttachment("l", "content://x", "a.pdf", false, 10, if (ready) "u1" else null)

    @Test fun sizesReadLikeAPhone() {
        assertEquals("12 B", AttachLogic.size(12))
        assertEquals("340 KB", AttachLogic.size(340 * 1024))
        assertEquals("1.5 MB", AttachLogic.size(1572864))
        assertEquals("", AttachLogic.size(null))
    }

    @Test fun fileKindFromName() {
        assertEquals("PDF", AttachLogic.kind("周报.pdf"))
        assertEquals("文件", AttachLogic.kind("README"))
        assertEquals("文件", AttachLogic.kind(null))
    }

    @Test fun sendNeedsTextOrReadyAttachments() {
        assertFalse(AttachLogic.canSend("  ", emptyList()))
        assertTrue(AttachLogic.canSend("", listOf(p(true))))
        assertFalse(AttachLogic.canSend("hi", listOf(p(false))))
        assertTrue(AttachLogic.canSend("hi", emptyList()))
    }

    @Test fun limits() {
        assertEquals("文件太大了，最多 25 MB", AttachLogic.problem(26L * 1024 * 1024, 0))
        assertEquals("一次最多发 9 个", AttachLogic.problem(10, 9))
        assertNull(AttachLogic.problem(10, 0))
    }

    @Test fun thumbnailsKeepShape() {
        assertEquals(220 to 110, AttachLogic.fit(2000, 1000, 220, 260))
        assertEquals(130 to 260, AttachLogic.fit(1000, 2000, 220, 260))
        assertEquals(100 to 50, AttachLogic.fit(100, 50, 220, 260))
        assertEquals(220 to 165, AttachLogic.fit(null, null, 220, 260))
    }

    @Test fun parsesAttachmentsOnItems() {
        val item = parseChatItem(JSONObject("""{"kind":"user","text":"看看","attachments":[
            {"type":"image","id":"a1","name":"p.png","mediaType":"image/png","bytes":10,"width":4,"height":3},
            {"type":"file","id":null,"name":"r.pdf","bytes":2048}]}"""))
        assertEquals(2, item.attachments.size)
        assertTrue(item.attachments[0].isImage)
        assertEquals(4, item.attachments[0].width)
        assertNull(item.attachments[1].id)
        assertEquals(2048L, item.attachments[1].bytes)
        assertTrue(parseChatItem(JSONObject("""{"kind":"bot","text":"x"}""")).attachments.isEmpty())
        val up = parseUpload(JSONObject("""{"uploadId":"u9","type":"image","name":"p.png","bytes":5}"""))
        assertEquals("u9", up.uploadId)
    }

    private fun ev(role: String, bot: String) = ChatItem(kind = "event", role = role, botId = bot, name = bot)

    @Test fun exchangesFoldIntoRuns() {
        val items = listOf(
            ChatItem(kind = "user", text = "a"),
            ev("request", "b1"), ev("reply", "b2"), ev("report", "b1"),
            ChatItem(kind = "bot", text = "ok"),
            ev("done", "b1"),
            ev("reply", "b3"),
        )
        val runs = Exchanges.runs(items)
        assertEquals(2, runs.size)
        assertEquals(1, runs[0].start)
        assertEquals(3, runs[0].count)
        assertEquals(2, runs[0].bots.size)
        assertEquals("3 条消息，与" to "2 个 Bot", Exchanges.words(runs[0]))
        assertEquals(6, runs[1].start)
        assertFalse(Exchanges.isExchange(items[5]))
    }
}
