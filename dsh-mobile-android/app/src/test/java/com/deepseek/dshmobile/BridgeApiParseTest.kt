package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.parseBot
import com.deepseek.dshmobile.data.parseChatItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeApiParseTest {
    @Test fun botFields() {
        val bot = parseBot(JSONObject("""{"id":"chief","name":"Chief","role":"lead","main":true}"""))
        assertEquals("chief", bot.id)
        assertEquals("Chief", bot.name)
        assertTrue(bot.main)
    }

    @Test fun chatItemKinds() {
        val user = parseChatItem(JSONObject("""{"kind":"user","text":"hi","seq":1}"""))
        assertEquals("user", user.kind)
        assertEquals("hi", user.text)
        val bot = parseChatItem(JSONObject("""{"kind":"bot","text":"yo"}"""))
        assertEquals("bot", bot.kind)
        assertEquals(null, bot.turn)
        val withTurn = parseChatItem(JSONObject("""{"kind":"bot","text":"yo","turn":7}"""))
        assertEquals(7L, withTurn.turn)
    }
}
