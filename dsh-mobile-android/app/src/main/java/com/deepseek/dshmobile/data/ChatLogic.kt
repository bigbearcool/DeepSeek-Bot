package com.deepseek.dshmobile.data

import kotlin.math.abs

/**
 * Pure chat-list reconciliation. The phone inserts the user's bubble optimistically; the
 * bridge later echoes the same message via SSE `message` and in history. Those must
 * replace the optimistic bubble instead of adding a second one.
 */
object ChatMerge {
    /** Echo of an optimistic bubble without clientMessageId is matched by text within this window. */
    const val WINDOW_MS = 120_000L

    private fun norm(t: String?) = t.orEmpty().trim()

    private fun sameServerItem(a: ChatItem, b: ChatItem) =
        !a.local && a.seq != null && a.seq == b.seq && a.kind == b.kind

    private fun matchesLocal(local: ChatItem, incoming: ChatItem): Boolean {
        if (!local.local || local.kind != incoming.kind) return false
        if (local.clientMessageId != null && incoming.clientMessageId != null) {
            return local.clientMessageId == incoming.clientMessageId
        }
        if (norm(local.text) != norm(incoming.text)) return false
        val lt = local.time ?: return true
        val it = incoming.time ?: return true
        return abs(lt - it) <= WINDOW_MS
    }

    /** One bot bubble per turn in history: keep the first slot, winner is highest seq. */
    fun collapseTurns(list: List<ChatItem>): List<ChatItem> {
        val out = mutableListOf<ChatItem>()
        val slot = mutableMapOf<Long, Int>()
        for (item in list) {
            val turn = item.turn
            if (item.kind == "bot" && turn != null) {
                val at = slot[turn]
                if (at == null) {
                    slot[turn] = out.size
                    out.add(item)
                } else {
                    val existing = out[at]
                    val winner = if ((item.seq ?: -1L) >= (existing.seq ?: -1L)) item else existing
                    out[at] = winner
                }
            } else {
                out.add(item)
            }
        }
        return out
    }

    /** Merge one live item (SSE `message`). */
    fun mergeIncoming(list: List<ChatItem>, incoming: ChatItem): List<ChatItem> {
        if (list.any { sameServerItem(it, incoming) }) return list
        if (incoming.kind == "bot" && incoming.turn != null) {
            val j = list.indexOfFirst { it.kind == "bot" && it.turn == incoming.turn }
            if (j >= 0) {
                val existing = list[j]
                val incSeq = incoming.seq ?: -1L
                val exSeq = existing.seq ?: -1L
                return if (incSeq >= exSeq) {
                    list.toMutableList().also { it[j] = incoming }
                } else {
                    list
                }
            }
        }
        val i = list.indexOfFirst { matchesLocal(it, incoming) }
        if (i >= 0) {
            val keepId = incoming.clientMessageId ?: list[i].clientMessageId
            return list.toMutableList().also { it[i] = incoming.copy(clientMessageId = keepId, local = false) }
        }
        return list + incoming
    }

    /** Replace the list with a fresh history page, keeping local items the server has not confirmed yet. */
    fun mergeHistory(current: List<ChatItem>, page: List<ChatItem>): List<ChatItem> {
        var out = page
        for (item in current.filter { it.local }) {
            val matched = page.any { matchesLocal(item, it) } ||
                // A streamed reply is superseded by any server bot reply at/after it.
                (item.kind == "bot" && page.any { p ->
                    p.kind == "bot" && (p.time ?: 0) >= (item.time ?: 0) - WINDOW_MS
                })
            if (!matched) out = out + item
        }
        return collapseTurns(out)
    }

    /** Commit streamed text as a local bot bubble unless the server already delivered it. */
    fun commitStreamed(list: List<ChatItem>, text: String, now: Long): List<ChatItem> {
        if (text.isBlank()) return list
        val dup = list.takeLast(6).any { !it.local && it.kind == "bot" && norm(it.text) == norm(text) }
        return if (dup) list else list + ChatItem(kind = "bot", text = text, time = now, local = true)
    }
}

/** Phase of the "thinking" card shown below the last user message. */
enum class WaitPhase { Idle, Waiting, Streaming, Failed }

data class WaitState(
    val phase: WaitPhase = WaitPhase.Idle,
    val text: String = "",
    val error: String? = null,
    val clientMessageId: String? = null,
) {
    val visible: Boolean get() = phase != WaitPhase.Idle
}

sealed interface WaitEvent {
    data class Submitted(val clientMessageId: String) : WaitEvent
    data class SendFailed(val message: String) : WaitEvent
    data object StreamStart : WaitEvent
    data class Delta(val text: String) : WaitEvent
    /** Stream finished; the reducer returns Idle and the caller commits [WaitState.text]. */
    data object StreamEnd : WaitEvent
    /** A final bot message arrived (e.g. no streaming for this turn). */
    data object BotMessage : WaitEvent
    data object Dismiss : WaitEvent
}

object WaitMachine {
    fun reduce(s: WaitState, e: WaitEvent): WaitState = when (e) {
        is WaitEvent.Submitted -> WaitState(WaitPhase.Waiting, clientMessageId = e.clientMessageId)
        is WaitEvent.SendFailed ->
            if (s.phase == WaitPhase.Waiting) s.copy(phase = WaitPhase.Failed, error = e.message) else s
        WaitEvent.StreamStart -> WaitState(WaitPhase.Streaming, clientMessageId = s.clientMessageId)
        is WaitEvent.Delta -> when (s.phase) {
            WaitPhase.Streaming -> s.copy(text = s.text + e.text)
            WaitPhase.Idle, WaitPhase.Waiting, WaitPhase.Failed ->
                WaitState(WaitPhase.Streaming, text = e.text, clientMessageId = s.clientMessageId)
        }
        WaitEvent.StreamEnd -> WaitState()
        WaitEvent.BotMessage -> if (s.phase == WaitPhase.Streaming) s else WaitState()
        WaitEvent.Dismiss -> WaitState()
    }
}

enum class StartRoute { Loading, Pair, Team, Chat }

object LaunchRouter {
    /** Where to land on launch. Network state is deliberately not an input: offline must not force re-pair. */
    fun route(snapshotLoaded: Boolean, paired: Boolean, lastBotId: String?): StartRoute = when {
        !snapshotLoaded -> StartRoute.Loading
        !paired -> StartRoute.Pair
        !lastBotId.isNullOrBlank() -> StartRoute.Chat
        else -> StartRoute.Team
    }

    /** Only these errors send the user back to pairing. */
    fun requiresRepair(e: Throwable): Boolean = e is AuthRevokedError
}
