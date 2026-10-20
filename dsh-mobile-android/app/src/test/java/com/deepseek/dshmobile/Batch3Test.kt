package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.BotInfo
import com.deepseek.dshmobile.data.ChatEntry
import com.deepseek.dshmobile.data.Characters
import com.deepseek.dshmobile.data.Look
import com.deepseek.dshmobile.data.ProfileLogic
import com.deepseek.dshmobile.data.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch3Test {
    /** Expected values printed by ds-bot's own characters.js defaultShape / defaultColor. */
    @Test fun defaultLookMatchesDsBot() {
        val expected = listOf(
            Triple("chief", "star", "blue"), Triple("writer", "star", "red"), Triple("bot-1", "gumdrop", "blue"),
            Triple("abc", "gumdrop", "cyan"), Triple("招聘", "kitty", "red"), Triple("session-a2d5e4ab", "kitty", "cyan"),
            Triple("x", "moon", "green"),
        )
        for ((id, shape, color) in expected) {
            assertEquals(id, shape, Look.defaultShape(id))
            assertEquals(id, color, Look.defaultColor(id))
        }
    }

    @Test fun storedLookWinsLikeDsBot() {
        assertEquals("whale" to "violet", Look.lookOf("chief", "whale", "violet"))
        assertEquals("star" to "blue", Look.lookOf("chief", "nope", "not-a-color"))
        assertEquals("star" to "#123abc", Look.lookOf("chief", null, "#123abc"))
        assertEquals("gumdrop" to "black", Look.lookOf(null, null, null))
        assertTrue(Look.isColor("qwen"))
    }

    @Test fun allTwentyCharactersAndTwelveColors() {
        assertEquals(20, Characters.ALL.size)
        assertEquals(12, Look.PICKER_COLORS.size)
        Characters.ALL.forEach { assertTrue(it.id, it.body.startsWith("M") && it.eyes.size == 2) }
        assertEquals(Characters.ALL.map { it.id }.toSet(), Look.SHAPE_LABELS.keys)
    }

    @Test fun networkWords() {
        assertEquals("局域网", ProfileLogic.network("http://10.0.2.2:3081/api/mobile-bridge/v1"))
        assertEquals("局域网", ProfileLogic.network("http://192.168.1.8:3081"))
        assertEquals("局域网", ProfileLogic.network("http://mac-mini.local:3081"))
        assertEquals("外网", ProfileLogic.network("https://bot.example.com/api/mobile-bridge/v1"))
        assertEquals("外网", ProfileLogic.network("http://172.40.0.1:3081"))
    }

    @Test fun addableSkipsMembersAndFilters() {
        val bots = listOf(BotInfo("a", "邮件助手", null, null, null, false), BotInfo("b", "招聘助手", null, null, null, false), BotInfo("c", "幕僚长", null, null, null, false))
        assertEquals(listOf("b", "c"), ProfileLogic.addable(bots, listOf("a"), "").map { it.id })
        assertEquals(listOf("b"), ProfileLogic.addable(bots, listOf("a"), "招聘").map { it.id })
    }

    @Test fun searchRowsAndTags() {
        val chats = listOf(ChatEntry("bot", "a", "邮件助手"), ChatEntry("room", "r", "周报小组"))
        val bots = listOf(BotInfo("a", "邮件助手", "收发邮件", null, null, false))
        val msg = SearchHit("room", "r", "周报小组", "你", "周报怎么样", true)
        val rows = ProfileLogic.results(chats, bots, "周报", listOf(msg))
        assertEquals(listOf("群聊", "消息"), rows.map { ProfileLogic.tag(it) })
        val all = ProfileLogic.results(chats, bots, "", emptyList())
        assertEquals(listOf("Bot", "群聊"), all.map { ProfileLogic.tag(it) })
        assertEquals("收发邮件", all[0].text)
    }

    @Test fun lookChangeBody() {
        val j = ProfileLogic.lookChange("a", "star", null)
        assertEquals("star", j.getString("shape"))
        assertFalse(j.has("color"))
    }
}
