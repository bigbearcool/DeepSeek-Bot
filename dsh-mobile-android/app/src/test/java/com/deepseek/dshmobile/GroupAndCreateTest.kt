package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.BotOptions
import com.deepseek.dshmobile.data.BotRules
import com.deepseek.dshmobile.data.ChatItem
import com.deepseek.dshmobile.data.LiveReply
import com.deepseek.dshmobile.data.ModelOption
import com.deepseek.dshmobile.data.NewBotDraft
import com.deepseek.dshmobile.data.NewRoomDraft
import com.deepseek.dshmobile.data.RoomRules
import com.deepseek.dshmobile.data.RoomStreams
import com.deepseek.dshmobile.data.parseBotOptions
import com.deepseek.dshmobile.data.parseChatItem
import com.deepseek.dshmobile.data.parseRoom
import com.deepseek.dshmobile.data.toJson
import com.deepseek.dshmobile.ui.inkArgb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAndCreateTest {
    @Test fun parsesRoomAndMemberItem() {
        val r = parseRoom(JSONObject("""{"id":"r1","name":"周会","members":["a","b"],"memberNames":["A","B"],"admin":"a","preview":"A：hi"}"""))
        assertEquals("周会", r.name)
        assertEquals(listOf("A", "B"), r.memberNames)
        val m = parseChatItem(JSONObject("""{"kind":"member","botId":"a","name":"A","text":"yo","seq":3,"cut":true}"""))
        assertEquals("member", m.kind)
        assertTrue(m.cut)
    }

    @Test fun parsesBotOptions() {
        val o = parseBotOptions(JSONObject("""{
          "models":[{"ref":"d/chat","name":"Chat","providerName":"D"},{"ref":"x/y","name":"Y","needsKey":true}],
          "defaultModel":"d/chat","agentPresets":[{"value":"coder","name":"coder"}],
          "colors":[{"id":"blue","hex":"#2A92FE"}],"shapes":[{"id":"star","label":"星星"}],
          "folders":{"team":"/t","picker":"browse","folders":[{"path":"/w","title":"w"}]},
          "limits":{"name":40,"role":24,"roomName":60,"roomMaxMembers":12}}"""))
        assertEquals(2, o.models.size)
        assertTrue(o.models[1].needsKey)
        assertEquals("coder", o.agentPresets!!.single().id)
        assertTrue(o.canBrowse)
        assertEquals("/w", o.folders.single().path)
        assertEquals("d/chat", BotRules.startModel(o))
        val none = parseBotOptions(JSONObject("""{"models":[],"agentPresets":null}"""))
        assertNull(none.agentPresets)
        assertEquals("", BotRules.startModel(none))
    }

    @Test fun newBotJsonUsesDsBotFieldNamesAndSkipsBlanks() {
        val j = NewBotDraft(name = " A ", shape = "star", color = "blue", model = "d/chat", instructions = "中文", workDir = "", image = null).toJson()
        assertEquals("A", j.getString("name"))
        assertEquals("star", j.getJSONObject("avatar").getString("shape"))
        assertFalse(j.has("workDir"))
        assertFalse(j.has("role"))
        assertEquals("中文", j.getString("instructions"))
        val p = NewBotDraft(name = "B", image = "data:image/webp;base64,AA").toJson()
        assertEquals("data:image/webp;base64,AA", p.getJSONObject("avatar").getString("image"))
    }

    @Test fun nameRulesMatchDsBot() {
        val taken = listOf("Chief", "新助手")
        assertEquals("新助手 2", BotRules.freshName(taken))
        assertEquals("新助手", BotRules.freshName(emptyList()))
        assertTrue(BotRules.problem("chief", taken)!!.contains("已经有"))
        assertTrue(BotRules.problem("a@b", taken) != null)
        assertTrue(BotRules.problem("所有人", taken) != null)
        assertTrue(BotRules.problem("You", taken) != null)
        assertNull(BotRules.problem("Writer", taken))
    }

    @Test fun roomDraftFollowsDesktopSteps() {
        var d = NewRoomDraft()
        assertFalse(RoomRules.canCreate(d))
        d = RoomRules.toggle(d, "b")
        d = RoomRules.pickAdmin(d, "b") // admin leaves the member list
        assertEquals("b", d.admin)
        assertTrue(d.members.isEmpty())
        assertFalse(RoomRules.canCreate(d))
        d = RoomRules.toggle(d, "c")
        assertTrue(RoomRules.canCreate(d))
        assertFalse(RoomRules.canCreate(d, max = 1))
        assertEquals(d, RoomRules.toggle(d, "b"))
        val j = d.copy(name = " 周会 ").toJson()
        assertEquals("b", j.getString("admin"))
        assertEquals("周会", j.getString("name"))
        assertEquals("c", j.getJSONArray("members").getString(0))
    }

    @Test fun liveRepliesStreamAndHandOverToLoggedLine() {
        var live = RoomStreams.start(emptyList(), "a1", "w", "Writer")
        live = RoomStreams.delta(live, "a1", "Writer: 收")
        live = RoomStreams.delta(live, "a1", "到")
        assertEquals("收到", RoomStreams.shown(live.single()))
        live = RoomStreams.end(live, "a1", 1000)
        assertEquals(1000L, live.single().endedAt)
        live = RoomStreams.onMessage(live, ChatItem(kind = "member", botId = "w", text = "收到"))
        assertTrue(live.isEmpty())
    }

    @Test fun passIsNeverShownAndDropsOnEnd() {
        var live = RoomStreams.start(emptyList(), "p", "c", "Chief")
        live = RoomStreams.delta(live, "p", "[PA")
        assertNull(RoomStreams.shown(live.single()))
        live = RoomStreams.delta(live, "p", "SS]")
        assertNull(RoomStreams.shown(live.single()))
        assertTrue(RoomStreams.end(live, "p", 1).isEmpty())
        assertTrue(RoomStreams.isPass("pass。"))
        assertFalse(RoomStreams.isPass("passport"))
    }

    @Test fun twoMembersStreamAtOnceAndEndedRepliesExpire() {
        var live = RoomStreams.start(emptyList(), "1", "a", "A")
        live = RoomStreams.start(live, "2", "b", "B")
        live = RoomStreams.delta(live, "1", "x")
        live = RoomStreams.delta(live, "2", "y")
        assertEquals(listOf("x", "y"), live.map { it.text })
        live = RoomStreams.end(live, "1", 0)
        assertEquals(2, RoomStreams.prune(live, RoomStreams.KEEP_ENDED_MS - 1).size)
        assertEquals(listOf("2"), RoomStreams.prune(live, RoomStreams.KEEP_ENDED_MS).map { it.attemptId })
        // delta for an unknown attempt (missed start) still shows
        assertEquals(1, RoomStreams.delta(emptyList(), "z", "hi", "a", "A").size)
    }

    @Test fun inkColorsFollowDsBot() {
        assertEquals(0xFF2A92FEL, inkArgb("blue"))
        assertEquals(0xFF0E74E0L, inkArgb("blue", dark = true))
        assertEquals(0xFF082DFFL, inkArgb("qwen"))
        assertEquals(0xFF123456L, inkArgb("#123456"))
        assertNull(inkArgb("nope"))
        assertNull(inkArgb(null))
    }
}
