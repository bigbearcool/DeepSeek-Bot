package com.deepseek.dshmobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.dshmobile.BuildConfig
import com.deepseek.dshmobile.data.AuthRepository
import com.deepseek.dshmobile.data.AuthRevokedError
import com.deepseek.dshmobile.data.BotInfo
import com.deepseek.dshmobile.data.BotOptions
import com.deepseek.dshmobile.data.BotRules
import com.deepseek.dshmobile.data.EffortOption
import com.deepseek.dshmobile.data.FolderListing
import com.deepseek.dshmobile.data.LiveReply
import com.deepseek.dshmobile.data.NewBotDraft
import com.deepseek.dshmobile.data.NewRoomDraft
import com.deepseek.dshmobile.data.RoomInfo
import com.deepseek.dshmobile.data.RoomRules
import com.deepseek.dshmobile.data.RoomStreams
import com.deepseek.dshmobile.data.BridgeApi
import com.deepseek.dshmobile.data.BridgeError
import com.deepseek.dshmobile.data.ChatItem
import com.deepseek.dshmobile.data.ChatMerge
import com.deepseek.dshmobile.data.LaunchRouter
import com.deepseek.dshmobile.data.SseClient
import com.deepseek.dshmobile.data.SseEvent
import com.deepseek.dshmobile.data.StartRoute
import com.deepseek.dshmobile.data.WaitEvent
import com.deepseek.dshmobile.data.WaitMachine
import com.deepseek.dshmobile.data.WaitPhase
import com.deepseek.dshmobile.data.RoomRound
import com.deepseek.dshmobile.data.WaitState
import com.deepseek.dshmobile.data.parseChatItem
import com.deepseek.dshmobile.data.ApprovalInfo
import com.deepseek.dshmobile.data.ChatEntry
import com.deepseek.dshmobile.data.ChatListLogic
import com.deepseek.dshmobile.dsh
import com.deepseek.dshmobile.util.PairCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { Loading, Pair, Team, Chat, NewBot, NewRoom }

const val ROOM_KEY_PREFIX = "room:"

data class UiState(
    val screen: Screen = Screen.Loading,
    val baseUrl: String = BuildConfig.DEFAULT_BASE_URL,
    val paired: Boolean = false,
    /** Plain-language reason shown on the pairing screen (e.g. after revocation). */
    val pairReason: String? = null,
    /** Short plain-language message (errors only). */
    val notice: String? = null,
    val busy: Boolean = false,
    val bots: List<BotInfo> = emptyList(),
    val teamLoaded: Boolean = false,
    val selectedBot: BotInfo? = null,
    val messages: List<ChatItem> = emptyList(),
    val nextCursor: String? = null,
    val wait: WaitState = WaitState(),
    /** True only when the live connection dropped and we are retrying. */
    val reconnecting: Boolean = false,
    val sending: Boolean = false,
    val composeText: String = "",
    val pairInput: String = "",
    // ---- group chats ----
    val rooms: List<RoomInfo> = emptyList(),
    val selectedRoom: RoomInfo? = null,
    /** Members writing right now in the open group chat. */
    val live: List<LiveReply> = emptyList(),
    // ---- new Bot / new group chat forms ----
    val options: BotOptions? = null,
    val optionsLoading: Boolean = false,
    val botDraft: NewBotDraft = NewBotDraft(),
    val efforts: List<EffortOption> = emptyList(),
    val roomDraft: NewRoomDraft = NewRoomDraft(),
    val folderListing: FolderListing? = null,
    val creating: Boolean = false,
    val formError: String? = null,
    // ---- conversation list, unread, stop, approvals ----
    /** Bot chats and group chats, newest activity first. */
    val chats: List<ChatEntry> = emptyList(),
    val approvals: List<ApprovalInfo> = emptyList(),
    /** Approvals being answered right now (buttons locked). */
    val answering: Set<String> = emptySet(),
    val stopping: Boolean = false,
) {
    val openKind: String? get() = when { selectedRoom != null -> "room"; selectedBot != null -> "bot"; else -> null }
    val openId: String? get() = selectedRoom?.id ?: selectedBot?.id
    /** The computer says a reply is running in the open chat. */
    val openRunning: Boolean get() = chats.any { it.kind == openKind && it.id == openId && it.running }
    val openApprovals: List<ApprovalInfo> get() = ChatListLogic.approvalsFor(approvals, openKind, openId)
    val showStop: Boolean get() = ChatListLogic.showStop(openRunning, wait.visible && wait.phase != WaitPhase.Failed)
    val inRoom: Boolean get() = selectedRoom != null
    val chatTitle: String get() = selectedRoom?.name ?: selectedBot?.name.orEmpty()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val auth: AuthRepository = app.dsh.authRepository
    private val store = app.dsh.sessionStore
    private val sse: SseClient = app.dsh.sseClient

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            val snap = store.snapshot.first()
            _state.update { it.copy(baseUrl = snap.baseUrl.ifBlank { BuildConfig.DEFAULT_BASE_URL }, paired = snap.isPaired) }
            when (LaunchRouter.route(snap.loaded, snap.isPaired, snap.lastBotId)) {
                StartRoute.Pair, StartRoute.Loading -> _state.update { it.copy(screen = Screen.Pair) }
                StartRoute.Team -> {
                    _state.update { it.copy(screen = Screen.Team) }
                    loadTeam()
                }
                StartRoute.Chat -> {
                    // Open the last chat right away from cache-less placeholder; team fills in later.
                    val last = snap.lastBotId.orEmpty()
                    if (last.startsWith(ROOM_KEY_PREFIX)) {
                        val id = last.removePrefix(ROOM_KEY_PREFIX)
                        openRoom(RoomInfo(id, ""))
                    } else {
                        openBot(BotInfo(last, last, null, null, null, false))
                    }
                    loadTeam(refreshSelected = true)
                }
            }
        }
    }

    // ---- input ----
    fun onBaseUrlChange(v: String) = _state.update { it.copy(baseUrl = v) }
    fun onPairInputChange(v: String) = _state.update { it.copy(pairInput = v) }
    fun onComposeChange(v: String) = _state.update { it.copy(composeText = v) }
    /** Text from the phone's own voice input goes after what is already typed. */
    fun addVoiceText(spoken: String) = _state.update { it.copy(composeText = com.deepseek.dshmobile.data.Look.mergeVoice(it.composeText, spoken)) }
    fun showNotice(text: String) = _state.update { it.copy(notice = text) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun friendly(e: Throwable): String = when (e) {
        is AuthRevokedError -> "此设备的配对已失效，请在电脑上重新获取配对码"
        is BridgeError -> if (e.zh != null) e.zh else when (e.status) {
            400 -> "配对码无效或已过期，请在电脑上刷新后重试"
            404 -> "找不到这个助手，可能已被删除"
            429 -> "尝试次数过多，请稍后再试"
            in 500..599 -> "电脑端暂时出错了，请稍后再试"
            null -> e.message ?: "出错了，请稍后再试"
            else -> "请求失败（${e.status}），请稍后再试"
        }
        is java.net.UnknownHostException, is java.net.ConnectException,
        is java.net.SocketTimeoutException, is java.io.IOException -> "网络不通，请检查网络后重试"
        else -> e.message ?: "出错了，请稍后再试"
    }

    /** Central failure handling: only a revoked device goes back to pairing. */
    private suspend fun onFailure(e: Throwable) {
        if (e is CancellationException) throw e
        if (LaunchRouter.requiresRepair(e)) {
            stopStream()
            auth.logout()
            _state.update {
                UiState(
                    screen = Screen.Pair,
                    baseUrl = it.baseUrl,
                    pairReason = friendly(e),
                )
            }
        } else {
            _state.update { it.copy(busy = false, notice = friendly(e)) }
        }
    }

    // ---- pairing ----
    fun pair() = viewModelScope.launch {
        PairCode.parse(_state.value.pairInput)?.baseUrl?.let { u -> _state.update { it.copy(baseUrl = u) } }
        val code = PairCode.normalize(_state.value.pairInput) ?: run {
            _state.update { it.copy(notice = "请输入电脑上显示的 6 位配对码，或直接扫码") }
            return@launch
        }
        if (_state.value.baseUrl.isBlank()) {
            _state.update { it.copy(notice = "请先扫码，或在下方填写电脑地址") }
            return@launch
        }
        _state.update { it.copy(busy = true, notice = null) }
        runCatching {
            auth.saveBaseUrl(_state.value.baseUrl)
            auth.pair(code)
        }.onSuccess {
            _state.update { it.copy(busy = false, paired = true, pairInput = "", pairReason = null, screen = Screen.Team) }
            loadTeam()
        }.onFailure { e ->
            // During pairing a 401 just means the code was wrong; never treat as revoke loop.
            _state.update { it.copy(busy = false, notice = if (e is AuthRevokedError) "配对失败，请重试" else friendly(e)) }
        }
    }

    fun applyScannedPayload(payload: String) {
        val parsed = PairCode.parse(payload)
        _state.update { it.copy(pairInput = parsed?.code ?: payload, baseUrl = parsed?.baseUrl ?: it.baseUrl) }
        pair()
    }

    fun logout() = viewModelScope.launch {
        stopStream()
        auth.logout()
        _state.update { UiState(screen = Screen.Pair, baseUrl = it.baseUrl) }
    }

    // ---- team ----
    fun loadTeam(refreshSelected: Boolean = false) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        runCatching { auth.withAccess { token, api -> api.team(token) } }
            .onSuccess { team ->
                _state.update { st ->
                    val sel = st.selectedBot?.let { cur -> team.bots.find { it.id == cur.id } ?: cur }
                    val room = st.selectedRoom?.let { cur -> team.rooms.find { it.id == cur.id } ?: cur }
                    st.copy(
                        busy = false,
                        bots = team.bots,
                        rooms = team.rooms,
                        selectedRoom = room,
                        teamLoaded = true,
                        selectedBot = if (refreshSelected || st.selectedBot != null) sel else st.selectedBot,
                        notice = if (!team.available) "电脑端的助手暂时不可用" else st.notice,
                    )
                }
            }
            .onFailure { onFailure(it) }
        refreshChats()
        startPolling()
    }

    // ---- conversation list (sorted, previews, unread, running, approvals) ----
    private suspend fun refreshChats() {
        runCatching { auth.withAccess { token, api -> api.chatList(token) } }
            .onSuccess { list ->
                val st = _state.value
                val openUnread = list.chats.any { it.kind == st.openKind && it.id == st.openId && it.unread > 0 }
                _state.update {
                    it.copy(
                        chats = ChatListLogic.clearOpen(ChatListLogic.sorted(list.chats), it.openKind, it.openId),
                        approvals = list.approvals,
                        answering = it.answering.intersect(list.approvals.map { a -> a.id }.toSet()),
                    )
                }
                // Messages that arrived while the chat is on screen are read.
                if (openUnread && st.screen == Screen.Chat) markOpenRead()
            }
            .onFailure { e -> if (e is CancellationException) throw e; if (LaunchRouter.requiresRepair(e)) onFailure(e) }
    }

    /** Keeps the list, running state and approvals fresh while the app is open. */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(if (_state.value.screen == Screen.Chat) 3_000L else 5_000L)
                if (_state.value.paired && _state.value.screen in setOf(Screen.Team, Screen.Chat)) refreshChats()
            }
        }
    }

    private suspend fun markOpenRead() {
        val st = _state.value
        val kind = st.openKind ?: return
        val id = st.openId ?: return
        _state.update { it.copy(chats = ChatListLogic.clearOpen(it.chats, kind, id)) }
        runCatching { auth.withAccess { token, api -> api.markRead(token, kind, id) } }
    }

    fun stopReply() {
        val st = _state.value
        if (st.stopping) return
        val kind = st.openKind ?: return
        val id = st.openId ?: return
        _state.update { it.copy(stopping = true, notice = null) }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> if (kind == "room") api.stopRoom(token, id) else api.stopBot(token, id) } }
                .onSuccess {
                    _state.update { s ->
                        s.copy(
                            stopping = false,
                            chats = s.chats.map { c -> if (c.kind == kind && c.id == id) c.copy(running = false) else c },
                            wait = if (s.wait.phase == WaitPhase.Streaming || s.wait.phase == WaitPhase.Waiting) WaitMachine.reduce(s.wait, WaitEvent.Dismiss) else s.wait,
                            live = s.live.map { r -> if (r.endedAt == null) r.copy(endedAt = System.currentTimeMillis()) else r },
                        )
                    }
                    if (kind == "bot") loadHistory(reset = true)
                    refreshChats()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (LaunchRouter.requiresRepair(e)) { onFailure(e); return@onFailure }
                    _state.update { it.copy(stopping = false, notice = "没能停下来：" + friendly(e)) }
                }
        }
    }

    /** allow = 同意, reject = 拒绝. */
    fun answerApproval(id: String, allow: Boolean) {
        if (id in _state.value.answering) return
        _state.update { it.copy(answering = it.answering + id, notice = null) }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> api.answerApproval(token, id, if (allow) "allow" else "reject") } }
                .onSuccess { _state.update { it.copy(approvals = it.approvals.filter { a -> a.id != id }, answering = it.answering - id) } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (LaunchRouter.requiresRepair(e)) { onFailure(e); return@onFailure }
                    _state.update { it.copy(answering = it.answering - id, notice = friendly(e)) }
                }
            refreshChats()
        }
    }

    /** From the list: open whichever chat a row is. */
    fun openChat(entry: ChatEntry) {
        if (entry.isRoom) {
            val room = _state.value.rooms.find { it.id == entry.id } ?: RoomInfo(entry.id, entry.name, members = entry.members)
            openRoom(room)
        } else {
            val bot = _state.value.bots.find { it.id == entry.id } ?: BotInfo(entry.id, entry.name, null, null, null, entry.main, entry.color)
            openBot(bot)
        }
    }

    fun openBot(bot: BotInfo) = viewModelScope.launch {
        stopStream()
        _state.update {
            it.copy(
                screen = Screen.Chat,
                selectedBot = bot,
                selectedRoom = null,
                live = emptyList(),
                messages = emptyList(),
                nextCursor = null,
                wait = WaitState(),
                notice = null,
            )
        }
        store.setLastBotId(bot.id)
        loadHistory(reset = true)
        startStream(bot.id)
        markOpenRead()
    }

    fun backToTeam() {
        stopStream()
        // What arrived while the chat was open has been seen.
        viewModelScope.launch { markOpenRead(); refreshChats() }
        viewModelScope.launch { store.setLastBotId(null) }
        _state.update {
            it.copy(screen = Screen.Team, selectedBot = null, selectedRoom = null, live = emptyList(), messages = emptyList(), wait = WaitState(), formError = null)
        }
        if (!_state.value.teamLoaded) loadTeam()
    }

    fun loadMoreHistory() = viewModelScope.launch {
        if (_state.value.nextCursor == null) return@launch
        loadHistory(reset = false)
    }

    private suspend fun loadHistory(reset: Boolean) {
        _state.value.selectedRoom?.let { loadRoomHistory(it.id); return }
        val bot = _state.value.selectedBot ?: return
        val cursor = if (reset) null else _state.value.nextCursor
        _state.update { it.copy(busy = true) }
        runCatching { auth.withAccess { token, api -> api.history(token, bot.id, cursor) } }
            .onSuccess { page ->
                _state.update { st ->
                    if (st.selectedBot?.id != bot.id) return@update st
                    val merged = if (reset) ChatMerge.mergeHistory(st.messages, page.items) else page.items + st.messages
                    st.copy(busy = false, messages = merged, nextCursor = page.nextCursor)
                }
            }
            .onFailure { onFailure(it) }
    }

    // ---- send ----
    fun sendMessage() {
        val st0 = _state.value
        if (st0.selectedRoom != null) { sendRoomMessage(); return }
        val st = st0
        val bot = st.selectedBot ?: return
        val text = st.composeText.trim()
        if (text.isEmpty() || st.sending) return // guard double tap / IME + button
        val clientId = "m-" + UUID.randomUUID().toString()
        _state.update {
            it.copy(
                composeText = "",
                sending = true,
                notice = null,
                messages = it.messages + ChatItem(
                    kind = "user", text = text, time = System.currentTimeMillis(),
                    clientMessageId = clientId, local = true,
                ),
                wait = WaitMachine.reduce(it.wait, WaitEvent.Submitted(clientId)),
            )
        }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> api.send(token, bot.id, text, clientId) } }
                .onSuccess { res ->
                    _state.update {
                        if (res.ok) it.copy(sending = false)
                        else it.copy(
                            sending = false,
                            wait = WaitMachine.reduce(it.wait, WaitEvent.SendFailed("发送失败：电脑端暂时无法处理这条消息")),
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (LaunchRouter.requiresRepair(e)) { onFailure(e); return@onFailure }
                    _state.update {
                        it.copy(sending = false, wait = WaitMachine.reduce(it.wait, WaitEvent.SendFailed("发送失败：" + friendly(e))))
                    }
                }
        }
    }

    fun dismissWait() = _state.update { it.copy(wait = WaitMachine.reduce(it.wait, WaitEvent.Dismiss)) }

    // ---- live stream with auto-reconnect ----
    private fun startStream(botId: String?, roomId: String? = null) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var attempt = 0
            var forceRefresh = false
            while (isActive) {
                var gotHello = false
                try {
                    val token = auth.refreshAccess(force = forceRefresh)
                    forceRefresh = false
                    val url = BridgeApi(BridgeApi.restClient()).withBase(auth.effectiveBaseUrl())
                        .streamUrl(botId = botId, test = false, roomId = roomId,
                            since = if (roomId != null) RoomRound.since(_state.value.messages) else null)
                    sse.connect(url, token).collect { ev ->
                        if (ev.event == "hello") {
                            gotHello = true
                            val wasReconnecting = _state.value.reconnecting
                            attempt = 0
                            _state.update { it.copy(reconnecting = false) }
                            if (wasReconnecting) loadHistory(reset = true) // catch up on missed messages
                        }
                        handleSse(ev)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AuthRevokedError) {
                    onFailure(e)
                    return@launch
                } catch (e: BridgeError) {
                    if (e.status == 401) forceRefresh = true
                } catch (_: Exception) {
                    // network blip; retry below
                }
                if (!isActive) break
                if (gotHello) attempt = 0
                _state.update { it.copy(reconnecting = true) }
                val backoff = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
                attempt++
                delay(backoff)
            }
        }
    }

    private fun handleSse(ev: SseEvent) {
        if (_state.value.selectedRoom != null) { handleRoomSse(ev); return }
        when (ev.event) {
            "message" -> {
                val itemJson = ev.json().optJSONObject("item") ?: return
                val item = parseChatItem(itemJson)
                _state.update { st ->
                    val wait = if (item.kind == "bot") WaitMachine.reduce(st.wait, WaitEvent.BotMessage) else st.wait
                    st.copy(messages = ChatMerge.mergeIncoming(st.messages, item), wait = wait)
                }
            }
            "stream" -> {
                val data = ev.json()
                when (data.optString("phase")) {
                    "start" -> _state.update { it.copy(wait = WaitMachine.reduce(it.wait, WaitEvent.StreamStart)) }
                    "end" -> _state.update { st ->
                        val text = st.wait.text
                        st.copy(
                            messages = if (st.wait.phase == WaitPhase.Streaming)
                                ChatMerge.commitStreamed(st.messages, text, System.currentTimeMillis()) else st.messages,
                            wait = WaitMachine.reduce(st.wait, WaitEvent.StreamEnd),
                        )
                    }
                    else -> {
                        val delta = data.optString("text")
                        if (delta.isNotEmpty()) _state.update { it.copy(wait = WaitMachine.reduce(it.wait, WaitEvent.Delta(delta))) }
                    }
                }
            }
        }
    }

    // ---- group chats ----
    fun openRoom(room: RoomInfo) = viewModelScope.launch {
        stopStream()
        _state.update {
            it.copy(
                screen = Screen.Chat, selectedRoom = room, selectedBot = null, live = emptyList(),
                messages = emptyList(), nextCursor = null, wait = WaitState(), notice = null,
            )
        }
        store.setLastBotId(ROOM_KEY_PREFIX + room.id)
        loadRoomHistory(room.id)
        startStream(botId = null, roomId = room.id)
        markOpenRead()
    }

    private suspend fun loadRoomHistory(roomId: String) {
        _state.update { it.copy(busy = true) }
        runCatching { auth.withAccess { token, api -> api.roomHistory(token, roomId) } }
            .onSuccess { page ->
                _state.update { st ->
                    if (st.selectedRoom?.id != roomId) return@update st
                    st.copy(busy = false, messages = ChatMerge.mergeHistory(st.messages, page.items), nextCursor = null)
                }
            }
            .onFailure { onFailure(it) }
    }

    private fun sendRoomMessage() {
        val st = _state.value
        val room = st.selectedRoom ?: return
        val text = st.composeText.trim()
        if (text.isEmpty() || st.sending) return
        val clientId = "m-" + UUID.randomUUID().toString()
        _state.update {
            it.copy(
                composeText = "", sending = true, notice = null,
                messages = it.messages + ChatItem(kind = "user", text = text, time = System.currentTimeMillis(), clientMessageId = clientId, local = true),
                wait = WaitMachine.reduce(it.wait, WaitEvent.Submitted(clientId)),
            )
        }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> api.roomSend(token, room.id, text, clientId) } }
                .onSuccess { _state.update { it.copy(sending = false) } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (LaunchRouter.requiresRepair(e)) { onFailure(e); return@onFailure }
                    _state.update { it.copy(sending = false, wait = WaitMachine.reduce(it.wait, WaitEvent.SendFailed("发送失败：" + friendly(e)))) }
                }
        }
    }

    private fun handleRoomSse(ev: SseEvent) {
        val data = ev.json()
        val now = System.currentTimeMillis()
        when (ev.event) {
            "message" -> {
                val item = parseChatItem(data.optJSONObject("item") ?: return)
                _state.update { st ->
                    val wait = if (RoomRound.endsWait(item)) WaitMachine.reduce(st.wait, WaitEvent.Dismiss) else st.wait
                    st.copy(
                        messages = ChatMerge.mergeIncoming(st.messages, item),
                        live = RoomStreams.prune(RoomStreams.onMessage(st.live, item), now),
                        wait = wait,
                    )
                }
            }
            "round" -> {
                if (data.optString("phase") != "end") return
                val replied = data.optInt("replied", 0)
                val failed = data.optJSONArray("failed")?.length() ?: 0
                _state.update { st ->
                    st.copy(
                        messages = if (st.wait.phase == WaitPhase.Waiting) RoomRound.onEnd(st.messages, replied, failed, now) else st.messages,
                        live = RoomRound.dropUnshown(st.live),
                        wait = if (st.wait.phase == WaitPhase.Waiting) WaitMachine.reduce(st.wait, WaitEvent.Dismiss) else st.wait,
                    )
                }
            }
            "stream" -> {
                val attempt = data.optString("attemptId").ifBlank { return }
                val botId = data.optString("botId").ifBlank { null }
                val name = data.optString("name").ifBlank { null }
                _state.update { st ->
                    val live = when (data.optString("phase")) {
                        "start" -> RoomStreams.start(st.live, attempt, botId, name)
                        "end" -> RoomStreams.end(st.live, attempt, now)
                        else -> data.optString("text").let { d -> if (d.isEmpty()) st.live else RoomStreams.delta(st.live, attempt, d, botId, name) }
                    }
                    val anyShown = live.any { RoomStreams.shown(it) != null }
                    st.copy(
                        live = RoomStreams.prune(live, now),
                        wait = if (anyShown && st.wait.phase == WaitPhase.Waiting) WaitMachine.reduce(st.wait, WaitEvent.Dismiss) else st.wait,
                    )
                }
            }
        }
    }

    // ---- new Bot ----
    fun openNewBot() {
        _state.update { it.copy(screen = Screen.NewBot, botDraft = NewBotDraft(), efforts = emptyList(), formError = null, creating = false, folderListing = null) }
        loadOptions { opts ->
            val shapes = opts.shapes.map { it.id }.filter { it != "whale" }.ifEmpty { listOf("gumdrop") }
            val colors = opts.colors.map { it.id }.filter { it !in listOf("black", "gray", "brown", "charcoal", "graphite") }.ifEmpty { listOf("blue") }
            val model = BotRules.startModel(opts)
            _state.update { it.copy(botDraft = it.botDraft.copy(shape = shapes.random(), color = colors.random(), model = model)) }
            if (model.isNotBlank()) loadEfforts(model)
        }
    }

    private fun loadOptions(then: (BotOptions) -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(optionsLoading = true) }
        runCatching { auth.withAccess { token, api -> api.botOptions(token) } }
            .onSuccess { opts -> _state.update { it.copy(options = opts, optionsLoading = false) }; then(opts) }
            .onFailure { e ->
                if (e is CancellationException) throw e
                if (LaunchRouter.requiresRepair(e)) { onFailure(e); return@onFailure }
                _state.update { it.copy(optionsLoading = false, formError = friendly(e)) }
            }
    }

    fun updateBotDraft(change: (NewBotDraft) -> NewBotDraft) {
        val before = _state.value.botDraft
        val after = change(before)
        _state.update { it.copy(botDraft = after, formError = null) }
        if (after.model != before.model) {
            _state.update { it.copy(efforts = emptyList(), botDraft = it.botDraft.copy(effort = "")) }
            if (after.model.isNotBlank()) loadEfforts(after.model)
        }
    }

    private fun loadEfforts(ref: String) = viewModelScope.launch {
        runCatching { auth.withAccess { token, api -> api.efforts(token, ref) } }
            .onSuccess { list -> _state.update { if (it.botDraft.model == ref) it.copy(efforts = list) else it } }
    }

    fun browseFolder(path: String) = viewModelScope.launch {
        runCatching { auth.withAccess { token, api -> api.listFolder(token, path) } }
            .onSuccess { listing -> _state.update { it.copy(folderListing = listing) } }
            .onFailure { e -> if (e is CancellationException) throw e; _state.update { it.copy(formError = friendly(e)) } }
    }

    fun closeFolderBrowser() = _state.update { it.copy(folderListing = null) }

    fun setAvatarImage(dataUrl: String?) = updateBotDraft { it.copy(image = dataUrl) }

    fun showFormError(message: String) = _state.update { it.copy(formError = message) }

    fun createBot() {
        val st = _state.value
        if (st.creating) return
        val taken = st.bots.map { it.name }
        val draft = st.botDraft.let { d -> if (d.name.isBlank()) d.copy(name = BotRules.freshName(taken)) else d.copy(name = d.name.trim()) }
        BotRules.problem(draft.name, taken)?.let { p -> _state.update { it.copy(formError = p) }; return }
        _state.update { it.copy(creating = true, formError = null) }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> api.createBot(token, draft) } }
                .onSuccess { bot ->
                    _state.update { it.copy(creating = false, bots = it.bots + bot) }
                    openBot(bot)
                    loadTeam(refreshSelected = true)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (LaunchRouter.requiresRepair(e)) { onFailure(e); return@onFailure }
                    _state.update { it.copy(creating = false, formError = friendly(e)) }
                }
        }
    }

    // ---- new group chat ----
    fun openNewRoom() {
        _state.update { it.copy(screen = Screen.NewRoom, roomDraft = NewRoomDraft(), formError = null, creating = false) }
        if (!_state.value.teamLoaded) loadTeam()
        if (_state.value.options == null) loadOptions()
    }

    fun pickRoomAdmin(id: String) = _state.update { it.copy(roomDraft = RoomRules.pickAdmin(it.roomDraft, id), formError = null) }
    fun toggleRoomMember(id: String) = _state.update { it.copy(roomDraft = RoomRules.toggle(it.roomDraft, id), formError = null) }
    fun setRoomName(v: String) = _state.update { it.copy(roomDraft = it.roomDraft.copy(name = v)) }

    fun createRoom() {
        val st = _state.value
        val max = st.options?.roomMaxMembers ?: 12
        if (st.creating) return
        if (!RoomRules.canCreate(st.roomDraft, max)) {
            _state.update { it.copy(formError = if (st.roomDraft.members.size + 1 > max) "一个群聊最多 $max 个助手" else "请先选管理员，再至少选一位成员") }
            return
        }
        _state.update { it.copy(creating = true, formError = null) }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> api.createRoom(token, st.roomDraft) } }
                .onSuccess { room ->
                    _state.update { it.copy(creating = false, rooms = it.rooms + room) }
                    openRoom(room)
                    loadTeam(refreshSelected = true)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (LaunchRouter.requiresRepair(e)) { onFailure(e); return@onFailure }
                    _state.update { it.copy(creating = false, formError = friendly(e)) }
                }
        }
    }

    fun closeForm() {
        _state.update { it.copy(screen = Screen.Team, formError = null, creating = false, folderListing = null) }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        _state.update { it.copy(reconnecting = false) }
    }

    override fun onCleared() {
        stopStream()
        pollJob?.cancel()
        super.onCleared()
    }
}
