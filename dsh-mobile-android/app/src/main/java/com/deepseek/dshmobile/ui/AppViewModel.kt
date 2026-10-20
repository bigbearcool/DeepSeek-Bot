package com.deepseek.dshmobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.dshmobile.BuildConfig
import com.deepseek.dshmobile.data.AuthRepository
import com.deepseek.dshmobile.data.AuthRevokedError
import com.deepseek.dshmobile.data.BotInfo
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
import com.deepseek.dshmobile.data.WaitState
import com.deepseek.dshmobile.data.parseChatItem
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

enum class Screen { Loading, Pair, Team, Chat }

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
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val auth: AuthRepository = app.dsh.authRepository
    private val store = app.dsh.sessionStore
    private val sse: SseClient = app.dsh.sseClient

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var streamJob: Job? = null

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
                    val placeholder = BotInfo(snap.lastBotId, snap.lastBotId, null, null, null, false)
                    openBot(placeholder)
                    loadTeam(refreshSelected = true)
                }
            }
        }
    }

    // ---- input ----
    fun onBaseUrlChange(v: String) = _state.update { it.copy(baseUrl = v) }
    fun onPairInputChange(v: String) = _state.update { it.copy(pairInput = v) }
    fun onComposeChange(v: String) = _state.update { it.copy(composeText = v) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun friendly(e: Throwable): String = when (e) {
        is AuthRevokedError -> "此设备的配对已失效，请在电脑上重新获取配对码"
        is BridgeError -> when (e.status) {
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
            _state.update { it.copy(notice = "请先扫码，或在下方填写服务器地址") }
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
                    st.copy(
                        busy = false,
                        bots = team.bots,
                        teamLoaded = true,
                        selectedBot = if (refreshSelected || st.selectedBot != null) sel else st.selectedBot,
                        notice = if (!team.available) "电脑端的助手暂时不可用" else st.notice,
                    )
                }
            }
            .onFailure { onFailure(it) }
    }

    fun openBot(bot: BotInfo) = viewModelScope.launch {
        stopStream()
        _state.update {
            it.copy(
                screen = Screen.Chat,
                selectedBot = bot,
                messages = emptyList(),
                nextCursor = null,
                wait = WaitState(),
                notice = null,
            )
        }
        store.setLastBotId(bot.id)
        loadHistory(reset = true)
        startStream(bot.id)
    }

    fun backToTeam() {
        stopStream()
        viewModelScope.launch { store.setLastBotId(null) }
        _state.update { it.copy(screen = Screen.Team, selectedBot = null, messages = emptyList(), wait = WaitState()) }
        if (!_state.value.teamLoaded) loadTeam()
    }

    fun loadMoreHistory() = viewModelScope.launch {
        if (_state.value.nextCursor == null) return@launch
        loadHistory(reset = false)
    }

    private suspend fun loadHistory(reset: Boolean) {
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
        val st = _state.value
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
    private fun startStream(botId: String) {
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
                        .streamUrl(botId = botId, test = false)
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

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        _state.update { it.copy(reconnecting = false) }
    }

    override fun onCleared() {
        stopStream()
        super.onCleared()
    }
}
