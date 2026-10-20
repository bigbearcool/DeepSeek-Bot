package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.ApprovalInfo
import com.deepseek.dshmobile.data.ChatEntry
import com.deepseek.dshmobile.data.ChatListLogic
import com.deepseek.dshmobile.data.parseChatList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ChatListTest {
    private val sample = """
        {"ok":true,"available":true,"chats":[
          {"kind":"bot","id":"w","name":"Writer","preview":"床前明月光","lastAt":9000,"unread":2,"running":true},
          {"kind":"room","id":"r1","name":"周会","members":["a","b"],"preview":"Chief：你好","lastAt":7000,"unread":0},
          {"kind":"bot","id":"c","name":"Chief","main":true,"preview":"","lastAt":null}
        ],"approvals":[
          {"id":"a1","botId":"w","botName":"Writer","roomId":"r1","roomName":"周会","action":"运行一条命令","reason":"跑测试","detail":"测试","answerable":true},
          {"id":"a2","botId":"c","botName":"Chief","roomId":null,"action":"修改文件","reason":"","detail":"","answerable":false}
        ]}
    """.trimIndent()

    @Test fun parsesListAndApprovals() {
        val list = parseChatList(JSONObject(sample))
        assertEquals(listOf("w", "r1", "c"), list.chats.map { it.id })
        assertTrue(list.chats[1].isRoom)
        assertEquals(2, list.chats[0].unread)
        assertTrue(list.chats[0].running)
        assertNull(list.chats[2].lastAt)
        assertTrue(list.chats[2].main)
        assertEquals(2, list.approvals.size)
        assertFalse(list.approvals[1].answerable)
    }

    @Test fun sortsNewestFirstKeepsUndatedLast() {
        val chats = listOf(ChatEntry("bot", "x", "X"), ChatEntry("bot", "a", "A", lastAt = 5), ChatEntry("room", "r", "R", lastAt = 9))
        assertEquals(listOf("r", "a", "x"), ChatListLogic.sorted(chats).map { it.id })
    }

    @Test fun badgeAndClearOpen() {
        assertNull(ChatListLogic.badge(0))
        assertEquals("7", ChatListLogic.badge(7))
        assertEquals("99+", ChatListLogic.badge(120))
        val chats = listOf(ChatEntry("bot", "w", "W", unread = 3), ChatEntry("room", "w", "R", unread = 1))
        val cleared = ChatListLogic.clearOpen(chats, "bot", "w")
        assertEquals(listOf(0, 1), cleared.map { it.unread })
    }

    @Test fun approvalsBelongToTheirChat() {
        val all = parseChatList(JSONObject(sample)).approvals
        assertEquals(listOf("a1"), ChatListLogic.approvalsFor(all, "room", "r1").map { it.id })
        assertEquals(listOf("a2"), ChatListLogic.approvalsFor(all, "bot", "c").map { it.id })
        // A member's group request is not shown in that Bot's own chat.
        assertTrue(ChatListLogic.approvalsFor(all, "bot", "w").isEmpty())
        assertEquals("Writer（周会）", ChatListLogic.whereText(all[0]))
    }

    @Test fun stopShowsWhileRunningOrWaiting() {
        assertTrue(ChatListLogic.showStop(running = true, waiting = false))
        assertTrue(ChatListLogic.showStop(running = false, waiting = true))
        assertFalse(ChatListLogic.showStop(running = false, waiting = false))
    }

    @Test fun approvalDefaultsToPlainWords() {
        val a = com.deepseek.dshmobile.data.parseApproval(JSONObject("""{"id":"x"}"""))
        assertEquals("做一个需要你同意的操作", a.action)
        assertFalse(a.answerable)
        val ap: ApprovalInfo = a
        assertNull(ap.roomId)
    }
}
