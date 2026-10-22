package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.AuthRevokedError
import com.deepseek.dshmobile.data.BridgeError
import com.deepseek.dshmobile.data.ChatItem
import com.deepseek.dshmobile.data.ChatMerge
import com.deepseek.dshmobile.data.LaunchRouter
import com.deepseek.dshmobile.data.StartRoute
import com.deepseek.dshmobile.data.WaitEvent
import com.deepseek.dshmobile.data.WaitMachine
import com.deepseek.dshmobile.data.WaitPhase
import com.deepseek.dshmobile.data.WaitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMergeTest {
    private val local = ChatItem(kind = "user", text = "你好", time = 1_000_000, clientMessageId = "c1", local = true)

    @Test fun sseEchoReplacesOptimisticById() {
        val echo = ChatItem(kind = "user", text = "你好", time = 1_000_500, seq = 10, clientMessageId = "c1")
        val out = ChatMerge.mergeIncoming(listOf(local), echo)
        assertEquals(1, out.size)
        assertFalse(out[0].local)
        assertEquals(10L, out[0].seq)
    }

    @Test fun sseEchoWithoutIdMatchesByTextInWindow() {
        val echo = ChatItem(kind = "user", text = " 你好 ", time = 1_030_000, seq = 10)
        val out = ChatMerge.mergeIncoming(listOf(local), echo)
        assertEquals(1, out.size)
        assertEquals("c1", out[0].clientMessageId)
    }

    @Test fun sameTextOutsideWindowIsNewMessage() {
        val echo = ChatItem(kind = "user", text = "你好", time = 1_000_000 + ChatMerge.WINDOW_MS + 1, seq = 10)
        assertEquals(2, ChatMerge.mergeIncoming(listOf(local), echo).size)
    }

    @Test fun differentIdNotMerged() {
        val echo = ChatItem(kind = "user", text = "你好", time = 1_000_100, seq = 11, clientMessageId = "c2")
        assertEquals(2, ChatMerge.mergeIncoming(listOf(local), echo).size)
    }

    @Test fun repeatedSeqIgnored() {
        val a = ChatItem(kind = "bot", text = "hi", seq = 5)
        assertEquals(1, ChatMerge.mergeIncoming(listOf(a), a.copy()).size)
    }

    @Test fun sameTurnBotReplacedByHigherSeq() {
        val older = ChatItem(kind = "bot", text = "draft", seq = 10, turn = 3)
        val newer = ChatItem(kind = "bot", text = "final", seq = 11, turn = 3)
        val out = ChatMerge.mergeIncoming(listOf(older), newer)
        assertEquals(1, out.size)
        assertEquals("final", out[0].text)
        assertEquals(11L, out[0].seq)
        val otherTurn = ChatItem(kind = "bot", text = "next", seq = 12, turn = 4)
        assertEquals(2, ChatMerge.mergeIncoming(out, otherTurn).size)
    }

    @Test fun historyKeepsUnconfirmedAndDropsConfirmed() {
        val other = ChatItem(kind = "user", text = "还没到", time = 1_000_200, clientMessageId = "c9", local = true)
        val page = listOf(ChatItem(kind = "user", text = "你好", time = 1_000_300, seq = 10))
        val out = ChatMerge.mergeHistory(listOf(local, other), page)
        assertEquals(listOf("你好", "还没到"), out.map { it.text })
        assertFalse(out[0].local)
    }

    @Test fun streamedReplyReplacedByServerBot() {
        val streamed = ChatMerge.commitStreamed(emptyList(), "**答**", 2_000_000)
        assertTrue(streamed.single().local)
        val server = ChatItem(kind = "bot", text = "**答**", time = 2_000_100, seq = 20)
        assertEquals(1, ChatMerge.mergeIncoming(streamed, server).size)
        // and the reverse order: server first, then stream end
        assertEquals(1, ChatMerge.commitStreamed(listOf(server), "**答**", 2_000_200).size)
    }
}

class WaitMachineTest {
    private fun run(vararg e: WaitEvent) = e.fold(WaitState()) { s, ev -> WaitMachine.reduce(s, ev) }

    @Test fun submitShowsThinking() {
        val s = run(WaitEvent.Submitted("c1"))
        assertEquals(WaitPhase.Waiting, s.phase)
        assertTrue(s.visible)
    }

    @Test fun deltasStreamThenEndClears() {
        val s = run(WaitEvent.Submitted("c1"), WaitEvent.StreamStart, WaitEvent.Delta("a"), WaitEvent.Delta("b"))
        assertEquals(WaitPhase.Streaming, s.phase)
        assertEquals("ab", s.text)
        assertEquals(WaitPhase.Idle, WaitMachine.reduce(s, WaitEvent.StreamEnd).phase)
    }

    @Test fun deltaWithoutStartStillStreams() {
        assertEquals("x", run(WaitEvent.Submitted("c1"), WaitEvent.Delta("x")).text)
    }

    @Test fun sendFailureBecomesReadableError() {
        val s = run(WaitEvent.Submitted("c1"), WaitEvent.SendFailed("发送失败：网络不通"))
        assertEquals(WaitPhase.Failed, s.phase)
        assertEquals("发送失败：网络不通", s.error)
        assertEquals(WaitPhase.Idle, WaitMachine.reduce(s, WaitEvent.Dismiss).phase)
    }

    @Test fun lateFailureDoesNotOverrideStreaming() {
        val s = run(WaitEvent.Submitted("c1"), WaitEvent.StreamStart, WaitEvent.SendFailed("x"))
        assertEquals(WaitPhase.Streaming, s.phase)
    }

    @Test fun botMessageWithoutStreamClearsWaiting() {
        assertEquals(WaitPhase.Idle, run(WaitEvent.Submitted("c1"), WaitEvent.BotMessage).phase)
        assertEquals(WaitPhase.Streaming, run(WaitEvent.StreamStart, WaitEvent.BotMessage).phase)
    }
}

class LaunchRouterTest {
    @Test fun routes() {
        assertEquals(StartRoute.Loading, LaunchRouter.route(false, true, "a"))
        assertEquals(StartRoute.Pair, LaunchRouter.route(true, false, "a"))
        assertEquals(StartRoute.Team, LaunchRouter.route(true, true, ""))
        assertEquals(StartRoute.Chat, LaunchRouter.route(true, true, "chief"))
    }

    @Test fun onlyRevocationForcesRepair() {
        assertTrue(LaunchRouter.requiresRepair(AuthRevokedError("unknown device")))
        assertFalse(LaunchRouter.requiresRepair(java.net.UnknownHostException("x")))
        assertFalse(LaunchRouter.requiresRepair(BridgeError("rate", 429)))
        assertFalse(LaunchRouter.requiresRepair(BridgeError("server", 503)))
        assertFalse(LaunchRouter.requiresRepair(BridgeError("unauthorized", 401)))
    }
}
