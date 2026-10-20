package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.ApprovalInfo
import com.deepseek.dshmobile.data.AttachLogic
import com.deepseek.dshmobile.data.ChatEntry
import com.deepseek.dshmobile.data.ChatListResponse
import com.deepseek.dshmobile.data.ChatPrefs
import com.deepseek.dshmobile.data.NotifyLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch5Test {
    private fun list(vararg c: ChatEntry, approvals: List<ApprovalInfo> = emptyList()) = ChatListResponse(true, c.toList(), approvals)
    private val mail = ChatEntry("bot", "m", "邮件助手", preview = "**收到**", unread = 0)
    private val room = ChatEntry("room", "r", "周报小组", preview = "幕僚长：好", unread = 0)
    private fun ap(id: String) = ApprovalInfo(id, "m", "邮件助手", null, null, "运行一条命令", "", "", true)

    @Test fun firstCheckOnlyRemembers() {
        assertTrue(NotifyLogic.alerts(null, list(mail.copy(unread = 3)), ChatPrefs(), null).isEmpty())
    }

    @Test fun newMessagesAlert() {
        val prev = NotifyLogic.seen(list(mail, room))
        val a = NotifyLogic.alerts(prev, list(mail.copy(unread = 2), room), ChatPrefs(), null)
        assertEquals(1, a.size)
        assertEquals("邮件助手", a[0].name)
        assertTrue(a[0].text.contains("收到"))
        assertEquals("bot:m", a[0].tag)
        assertEquals("2 条新消息", NotifyLogic.countLine(a[0].count))
        assertNull(NotifyLogic.countLine(1))
    }

    @Test fun mutedOpenAndGlobalOffAreQuiet() {
        val prev = NotifyLogic.seen(list(mail, room))
        val now = list(mail.copy(unread = 1), room.copy(unread = 1))
        assertEquals(listOf("room:r"), NotifyLogic.alerts(prev, now, ChatPrefs(muted = setOf("bot:m")), null).map { it.tag })
        assertEquals(listOf("bot:m"), NotifyLogic.alerts(prev, now, ChatPrefs(), "room:r").map { it.tag })
        assertTrue(NotifyLogic.alerts(prev, now, ChatPrefs(notify = false), null).isEmpty())
    }

    @Test fun sameOrFewerUnreadIsQuiet() {
        val prev = NotifyLogic.seen(list(mail.copy(unread = 2)))
        assertTrue(NotifyLogic.alerts(prev, list(mail.copy(unread = 2)), ChatPrefs(), null).isEmpty())
        assertTrue(NotifyLogic.alerts(prev, list(mail.copy(unread = 1)), ChatPrefs(), null).isEmpty())
    }

    @Test fun newApprovalsOnce() {
        val prev = NotifyLogic.seen(list(mail, approvals = listOf(ap("a1"))))
        val a = NotifyLogic.alerts(prev, list(mail, approvals = listOf(ap("a1"), ap("a2"))), ChatPrefs(), null)
        assertEquals(1, a.size)
        assertTrue(a[0].approval)
        assertEquals("ap:a2", a[0].tag)
        assertEquals("邮件助手 在等你确认：运行一条命令", a[0].text)
        assertEquals("bot", a[0].kind)
    }

    @Test fun galleryNames() {
        assertEquals("图片_20261020_112233.png", AttachLogic.niceName("1000000019.png", "image/png", "20261020_112233", 0))
        assertEquals("图片_20261020_112233_2.jpg", AttachLogic.niceName("1000000020", "image/jpeg", "20261020_112233", 1))
        assertEquals("IMG_0001.HEIC", AttachLogic.niceName("IMG_0001.HEIC", "image/heic", "x", 0))
        assertEquals("图片_s.webp", AttachLogic.niceName(null, "image/webp", "s", 0))
        assertEquals("相册", AttachLogic.saveFolder(true))
    }
}
