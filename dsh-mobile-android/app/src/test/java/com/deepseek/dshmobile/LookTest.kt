package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.ChatEntry
import com.deepseek.dshmobile.data.ChatListLogic
import com.deepseek.dshmobile.data.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class LookTest {
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance().apply { set(y, m - 1, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun sameBotSameShapeAndColor() {
        assertEquals(Look.shapeFor("bot-a"), Look.shapeFor("bot-a"))
        assertEquals(Look.fallbackInk("bot-a"), Look.fallbackInk("bot-a"))
        val shapes = (1..40).map { Look.shapeFor("bot-$it") }.toSet()
        assertTrue("shapes vary", shapes.size >= 4)
        assertTrue(Look.FALLBACK_INKS.contains(Look.fallbackInk("x")))
    }

    @Test fun clockWords() {
        assertEquals("上午 8:05", Look.clock(at(2026, 10, 20, 8, 5)))
        assertEquals("下午 3:30", Look.clock(at(2026, 10, 20, 15, 30)))
        assertEquals("上午 12:10", Look.clock(at(2026, 10, 20, 0, 10)))
        assertEquals("下午 12:00", Look.clock(at(2026, 10, 20, 12, 0)))
    }

    @Test fun dayWords() {
        val now = at(2026, 10, 20, 15, 0) // Tuesday
        assertEquals("今天", Look.day(at(2026, 10, 20, 1, 0), now))
        assertEquals("昨天", Look.day(at(2026, 10, 19, 23, 0), now))
        assertEquals("星期六", Look.day(at(2026, 10, 17, 9, 0), now))
        assertEquals("10月3日", Look.day(at(2026, 10, 3, 9, 0), now))
        assertEquals("2025年12月31日", Look.day(at(2025, 12, 31, 9, 0), now))
    }

    @Test fun dividersEvery15Minutes() {
        val now = at(2026, 10, 20, 15, 0)
        val t0 = at(2026, 10, 20, 8, 0)
        val d = Look.dividers(listOf(null, t0, t0 + 60_000, t0 + 16 * 60_000, null, t0 + 20 * 60_000), now)
        assertEquals(setOf(1, 3), d.keys)
        assertEquals("今天 上午 8:00", d[1])
        assertEquals("今天 上午 8:16", d[3])
        assertTrue(Look.dividers(emptyList(), now).isEmpty())
    }

    @Test fun voiceGoesAfterTypedText() {
        assertEquals("你好", Look.mergeVoice("", " 你好 "))
        assertEquals("帮我看看今天的邮件", Look.mergeVoice("帮我", "看看今天的邮件"))
        assertEquals("hello world", Look.mergeVoice("hello", "world"))
        assertEquals("abc", Look.mergeVoice("abc", "  "))
        assertEquals("a b", Look.mergeVoice("a ", "b"))
    }

    @Test fun inputHints() {
        assertEquals("问问 小助手", Look.inputHint("小助手", false))
        assertEquals("问问助手", Look.inputHint(null, false))
        assertEquals("在群里说点什么", Look.inputHint("群", true))
    }

    @Test fun searchByNameOrPreview() {
        val chats = listOf(
            ChatEntry("bot", "1", "邮件助手", preview = "已整理 3 封"),
            ChatEntry("room", "2", "周报群", preview = "Markdown 示例"),
        )
        assertEquals(2, Look.filter(chats, " ").size)
        assertEquals(listOf("1"), Look.filter(chats, "邮件").map { it.id })
        assertEquals(listOf("2"), Look.filter(chats, "markdown").map { it.id })
        assertTrue(Look.filter(chats, "没有").isEmpty())
    }

    @Test fun previewWithoutMarkdown() {
        val md = "## 本周进展\n\n- **收件箱**：整理了 42 封\n\n| 项目 | 状态 |\n|---|---|\n| 官网 | 进行中 |\n\n```bash\nnpm run x\n```"
        assertEquals("本周进展 收件箱：整理了 42 封 项目 状态 官网 进行中 npm run x", Look.plainPreview(md))
        assertEquals("幕僚长：本周进展", Look.plainPreview("幕僚长：## 本周进展"))
        assertEquals("a*b", Look.plainPreview("a*b"))
        // The computer already sends the preview as one line.
        assertEquals("幕僚长：本周进展 收件箱：整理了 42 封 项目 状态 官网", Look.plainPreview("幕僚长：## 本周进展 - **收件箱**：整理了 42 封 | 项目 | 状态 | |---|---| | 官网 |"))
    }

    @Test fun listTimeWords() {
        val now = at(2026, 10, 20, 15, 30)
        assertEquals("刚刚", ChatListLogic.timeLabel(now - 10_000, now))
        assertEquals("上午 9:05", ChatListLogic.timeLabel(at(2026, 10, 20, 9, 5), now))
        assertEquals("昨天", ChatListLogic.timeLabel(at(2026, 10, 19, 23, 0), now))
        assertEquals("星期六", ChatListLogic.timeLabel(at(2026, 10, 17, 12, 0), now))
        assertEquals("10月3日", ChatListLogic.timeLabel(at(2026, 10, 3, 12, 0), now))
        assertEquals("", ChatListLogic.timeLabel(null, now))
    }
}
