package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.BotInfo
import com.deepseek.dshmobile.data.Mentions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionsTest {
    private fun bot(id: String, name: String) = BotInfo(id, name, null, null, null, false)

    private val members = listOf(
        bot("a", "新 Bot"),
        bot("b", "新 Bot 2"),
        bot("c", "Chief"),
        bot("k", "K"),
    )

    @Test fun activeAtFindsQueryAtEnd() {
        val a = Mentions.activeAt("你好 @Chi")!!
        assertEquals(3, a.atIndex)
        assertEquals("Chi", a.query)
        assertNull(Mentions.activeAt("mail a@K.com"))
        assertNull(Mentions.activeAt("no mention"))
    }

    @Test fun rowsFilterAndOfferEveryone() {
        val all = Mentions.rows(members, "")
        assertTrue(all.any { it.everyone })
        assertEquals("@所有人 ", all.first { it.everyone }.insert)
        val filtered = Mentions.rows(members, "新")
        assertEquals(listOf("新 Bot 2", "新 Bot"), filtered.filter { !it.everyone }.map { it.name })
        assertTrue(Mentions.rows(listOf(bot("a", "Alone")), "").none { it.everyone })
    }

    @Test fun applyReplacesActiveSpan() {
        val text = "请 @Chi"
        val active = Mentions.activeAt(text)!!
        assertEquals("请 @Chief ", Mentions.apply(text, active, "@Chief "))
    }

    @Test fun calledIdsLongestNameWins() {
        assertEquals(listOf("a"), Mentions.calledIds("@新 Bot 提权", members).ids)
        assertEquals(listOf("b", "a", "c"), Mentions.calledIds("@新 Bot 2 和 @新 Bot，还有@chief", members).ids)
        assertTrue(Mentions.calledIds("@everyone 来", members).all)
        assertTrue(Mentions.calledIds("mail a@K.com", members).ids.isEmpty())
    }

    @Test fun whoAnswersAlignsWithDesktopModes() {
        // No @: everyone mode → all; admin mode → admin only
        assertEquals(listOf("c", "a", "b", "k"), Mentions.whoAnswers("大家好", members, "everyone", "c"))
        assertEquals(listOf("c"), Mentions.whoAnswers("大家好", members, "admin", "c"))
        // With @: only mentioned
        assertEquals(listOf("k"), Mentions.whoAnswers("@K 看一下", members, "admin", "c"))
        assertEquals(listOf("a", "b"), Mentions.whoAnswers("@新 Bot @新 Bot 2", members, "everyone", "c"))
        // @所有人 → all
        assertEquals(listOf("a", "b", "c", "k"), Mentions.whoAnswers("@所有人", members, "admin", "c"))
    }

    @Test fun multiPickKeepsEarlierMentions() {
        val once = Mentions.apply("hi @", Mentions.activeAt("hi @")!!, "@Chief ")
        assertEquals("hi @Chief ", once)
        val twice = Mentions.apply(once + "@", Mentions.activeAt(once + "@")!!, "@K ")
        assertEquals("hi @Chief @K ", twice)
        assertFalse(Mentions.calledIds(twice, members).all)
        assertEquals(listOf("c", "k"), Mentions.calledIds(twice, members).ids)
    }

    @Test fun fullwidthAtOpensPickerAndCalls() {
        val a = Mentions.activeAt("请 ＠Chi")!!
        assertEquals("Chi", a.query)
        assertEquals("请 @Chief ", Mentions.apply("请 ＠Chi", a, "@Chief "))
        assertEquals(listOf("c"), Mentions.calledIds("请＠Chief 看", members).ids)
    }
}
