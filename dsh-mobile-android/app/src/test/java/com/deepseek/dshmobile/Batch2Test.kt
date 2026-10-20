package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.ChatEntry
import com.deepseek.dshmobile.data.ChatPrefs
import com.deepseek.dshmobile.data.ChatPrefsLogic
import com.deepseek.dshmobile.data.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch2Test {
    private val a = ChatEntry("bot", "a", "甲", lastAt = 300)
    private val b = ChatEntry("bot", "b", "乙", lastAt = 200)
    private val r = ChatEntry("room", "r", "群", lastAt = 100)

    @Test fun pinnedGoFirst() {
        val p = ChatPrefsLogic.togglePin(ChatPrefs(), r)
        assertEquals(listOf("r", "a", "b"), ChatPrefsLogic.apply(listOf(a, b, r), p).map { it.id })
        assertTrue(ChatPrefsLogic.isPinned(r, p))
        assertFalse(ChatPrefsLogic.isPinned(r, ChatPrefsLogic.togglePin(p, r)))
    }

    @Test fun hiddenUntilSomethingNew() {
        val p = ChatPrefsLogic.hide(ChatPrefsLogic.togglePin(ChatPrefs(), b), b, now = 250)
        assertEquals(listOf("a", "r"), ChatPrefsLogic.apply(listOf(a, b, r), p).map { it.id })
        assertFalse("hiding unpins", ChatPrefsLogic.isPinned(b, p))
        assertEquals(1, ChatPrefsLogic.hiddenCount(listOf(a, b, r), p))
        val newer = b.copy(lastAt = 400)
        assertEquals(listOf("a", "b", "r"), ChatPrefsLogic.apply(listOf(a, newer, r), p).map { it.id })
        assertEquals(3, ChatPrefsLogic.apply(listOf(a, b, r), ChatPrefsLogic.unhideAll(p)).size)
    }

    @Test fun handUnreadClearsOnOpen() {
        val p = ChatPrefsLogic.toggleUnread(ChatPrefs(), a)
        assertTrue(ChatPrefsLogic.markedUnread(a, p))
        assertFalse(ChatPrefsLogic.markedUnread(a, ChatPrefsLogic.opened(p, "bot", "a")))
        assertFalse(ChatPrefsLogic.markedUnread(a, ChatPrefsLogic.toggleUnread(p, a)))
    }

    @Test fun hiddenRoundTrip() {
        val m = mapOf("bot:a" to 5L, "room:x|y" to 7L)
        assertEquals(m, ChatPrefsLogic.decodeHidden(ChatPrefsLogic.encodeHidden(m)))
        assertTrue(ChatPrefsLogic.decodeHidden(setOf("junk", "x|bot:a")).isEmpty())
    }

    @Test fun themeNames() {
        assertEquals("dark", ChatPrefsLogic.normTheme("dark"))
        assertEquals("system", ChatPrefsLogic.normTheme(null))
        assertEquals("system", ChatPrefsLogic.normTheme("blue"))
    }

    @Test fun quoteReplyRoundTrip() {
        val sent = Look.quoted("小秘", "本周待办（示例） 重点事项", "第二条改到周四")
        assertEquals("> 小秘：本周待办（示例） 重点事项\n\n第二条改到周四", sent)
        val (q, rest) = Look.splitQuote(sent)
        assertEquals("小秘：本周待办（示例） 重点事项", q)
        assertEquals("第二条改到周四", rest)
        assertNull(Look.splitQuote("普通消息").first)
        assertNull(Look.splitQuote("> 没有空行").first)
        assertEquals("一二三…", Look.snippet("## 一二三四五", 3))
    }

    @Test fun codeBlocksSplitOut() {
        val parts = Look.splitCode("说明\n\n```python\nprint(1)\n```\n\n结尾")
        assertEquals(3, parts.size)
        assertEquals(Look.Part(false, "说明"), parts[0])
        assertEquals(Look.Part(true, "print(1)", "python"), parts[1])
        assertEquals(Look.Part(false, "结尾"), parts[2])
        val streaming = Look.splitCode("看这里\n```\nfor i in x:")
        assertEquals(Look.Part(true, "for i in x:", ""), streaming.last())
        assertEquals(listOf(Look.Part(false, "没有代码")), Look.splitCode("没有代码"))
    }

    @Test fun previewLinksShowOnlyText() {
        assertEquals("详情见 日程 好", Look.plainPreview("详情见 [日程](https://example.com) 好"))
        assertEquals("详情见 日程", Look.plainPreview("详情见 [日程](https://exam…"))
    }

    @Test fun workingWords() {
        assertEquals("小秘 正在工作", Look.workingLabel(listOf("小秘"), false))
        assertEquals("工匠、园长 正在输入…", Look.workingLabel(listOf("工匠", "园长", "工匠"), true))
        assertEquals("助手 正在工作", Look.workingLabel(emptyList(), false))
    }

    @Test fun connectionWords() {
        assertEquals("已连接电脑" to true, com.deepseek.dshmobile.ui.ConnectionWords.status(true, false, null, true))
        assertEquals("正在重新连接…" to false, com.deepseek.dshmobile.ui.ConnectionWords.status(true, true, null, true))
        assertEquals("暂时连不上电脑" to false, com.deepseek.dshmobile.ui.ConnectionWords.status(true, false, "x", false))
        assertEquals("没有连接电脑" to false, com.deepseek.dshmobile.ui.ConnectionWords.status(false, false, null, false))
        assertEquals("10.0.2.2:3081", com.deepseek.dshmobile.ui.ConnectionWords.host("http://10.0.2.2:3081/api/mobile-bridge/v1"))
    }
}
