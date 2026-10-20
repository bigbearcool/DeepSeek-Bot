package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.BridgeApi
import com.deepseek.dshmobile.data.ChatItem
import com.deepseek.dshmobile.data.LiveReply
import com.deepseek.dshmobile.data.RoomRound
import com.deepseek.dshmobile.data.parseChatItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.4 bug: a group message whose member failed left the waiting card up forever. */
class RoomRoundTest {
    // Captured from bridge 0.0.10 + real ds-bot (member model call failed).
    private val failedEvent = """{"roomId":"session-5416a2ec","item":{"kind":"event","role":"failed","botId":"session-03954678","name":"阿甲"}}"""
    private val roundEnd = """{"roomId":"session-5416a2ec","phase":"end","callId":"toolu_relay_b6a2","replied":0,"failed":["阿甲"],"passed":[]}"""

    @Test fun failedMemberEventEndsWait() {
        val item = parseChatItem(JSONObject(failedEvent).getJSONObject("item"))
        assertEquals("failed", item.role)
        assertTrue(RoomRound.endsWait(item))
        assertTrue(RoomRound.endsWait(ChatItem(kind = "member", text = "好")))
        assertFalse(RoomRound.endsWait(ChatItem(kind = "user", text = "hi")))
        assertFalse(RoomRound.endsWait(ChatItem(kind = "event", role = "done")))
    }

    @Test fun roundEndWithFailureAddsNoQuietNote() {
        val d = JSONObject(roundEnd)
        val out = RoomRound.onEnd(emptyList(), d.optInt("replied"), d.getJSONArray("failed").length(), 1L)
        assertTrue(out.isEmpty())
    }

    @Test fun silentRoundAddsQuietNote() {
        val out = RoomRound.onEnd(listOf(ChatItem(kind = "user", text = "hi")), 0, 0, 5L)
        assertEquals("quiet", out.last().role)
        assertEquals(2, out.size)
    }

    @Test fun unshownBubblesDropOnRoundEnd() {
        val live = listOf(LiveReply("a1", "b", "阿甲"), LiveReply("a2", "c", "阿乙", text = "你好"), LiveReply("a3", "d", "丙", text = "[PASS]"))
        assertEquals(listOf("a2"), RoomRound.dropUnshown(live).map { it.attemptId })
    }

    @Test fun sinceIsHighestServerSeq() {
        val msgs = listOf(ChatItem(kind = "user", seq = 4), ChatItem(kind = "member", seq = 7), ChatItem(kind = "user", text = "x", local = true))
        assertEquals(7L, RoomRound.since(msgs))
        assertEquals(null, RoomRound.since(emptyList()))
    }

    @Test fun streamUrlCarriesSinceForRoomsOnly() {
        val api = BridgeApi(BridgeApi.restClient()).withBase("https://h/api/mobile-bridge/v1")
        assertEquals("https://h/api/mobile-bridge/v1/stream?roomId=r1&since=7", api.streamUrl(roomId = "r1", since = 7))
        assertEquals("https://h/api/mobile-bridge/v1/stream?botId=b", api.streamUrl(botId = "b", since = 7))
    }
}
