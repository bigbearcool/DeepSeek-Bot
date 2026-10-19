package com.deepseek.dshmobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.dshmobile.BuildConfig
import com.deepseek.dshmobile.data.AuthRepository
import com.deepseek.dshmobile.data.BotInfo
import com.deepseek.dshmobile.data.BridgeError
import com.deepseek.dshmobile.data.ChatItem
import com.deepseek.dshmobile.data.SessionSnapshot
import com.deepseek.dshmobile.data.SseClient
import com.deepseek.dshmobile.data.SseEvent
import com.deepseek.dshmobile.dsh
import com.deepseek.dshmobile.util.PairCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val baseUrl: String = BuildConfig.DEFAULT_BASE_URL,
    val paired: Boolean = false,
    val deviceId: String = "",
    val status: String = "",
    val busy: Boolean = false,
    val bots: List<BotInfo> = emptyList(),
    val selectedBot: BotInfo? = null,
    val messages: List<ChatItem> = emptyList(),
    val nextCursor: String? = null,
    val streamingText: String = "",
    val streamConnected: Boolean = false,
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
    private var streamAttemptId: String? = null

    init {
        viewModelScope.launch {
            store.snapshot.collect { snap -> applySnapshot(snap) }
        }
    }

    private fun applySnapshot(snap: SessionSnapshot) {
        _state.update {
            it.copy(
                baseUrl = snap.baseUrl.ifBlank { BuildConfig.DEFAULT_BASE_URL },
                paired = snap.isPaired,
                deviceId = snap.deviceId,
            )
        }
    }

    fun onBaseUrlChange(v: String) = _state.update { it.copy(baseUrl = v) }
    fun onPairInputChange(v: String) = _state.update { it.copy(pairInput = v) }
    fun onComposeChange(v: String) = _state.update { it.copy(composeText = v) }

    fun saveBaseUrl() = viewModelScope.launch {
        runCatching {
            auth.saveBaseUrl(_state.value.baseUrl)
            _state.update { it.copy(status = "Base URL saved") }
        }.onFailure { e -> _state.update { it.copy(status = e.message ?: "save failed") } }
    }

    fun pair() = viewModelScope.launch {
        val code = PairCode.normalize(_state.value.pairInput)
            ?: run {
                _state.update { it.copy(status = "Need 6-hex code or dshmb:<code>") }
                return@launch
            }
        _state.update { it.copy(busy = true, status = "Pairing…") }
        runCatching {
            auth.saveBaseUrl(_state.value.baseUrl)
            auth.pair(code)
            loadTeam()
            _state.update { it.copy(status = "Paired", pairInput = "", busy = false) }
        }.onFailure { e ->
            _state.update { it.copy(busy = false, status = e.message ?: "pair failed") }
        }
    }

    fun applyScannedPayload(payload: String) {
        val parsed = PairCode.parse(payload)
        _state.update {
            it.copy(
                pairInput = parsed?.code ?: payload,
                baseUrl = parsed?.baseUrl ?: it.baseUrl,
            )
        }
        pair()
    }

    fun logout() = viewModelScope.launch {
        stopStream()
        auth.logout()
        _state.update {
            it.copy(
                paired = false,
                bots = emptyList(),
                selectedBot = null,
                messages = emptyList(),
                status = "Logged out",
            )
        }
    }

    fun loadTeam() = viewModelScope.launch {
        _state.update { it.copy(busy = true, status = "Loading team…") }
        runCatching {
            auth.withAccess { token, api -> api.team(token) }
        }.onSuccess { team ->
            _state.update {
                it.copy(
                    busy = false,
                    bots = team.bots,
                    status = if (!team.available) team.reason ?: "team unavailable"
                    else "Team: ${team.bots.size} bots",
                )
            }
        }.onFailure { e ->
            _state.update { it.copy(busy = false, status = e.message ?: "team failed") }
        }
    }

    fun openBot(bot: BotInfo) = viewModelScope.launch {
        stopStream()
        _state.update {
            it.copy(
                selectedBot = bot,
                messages = emptyList(),
                nextCursor = null,
                streamingText = "",
                status = "Opening ${bot.name}…",
            )
        }
        loadHistory(reset = true)
        startStream(bot.id)
    }

    fun backToTeam() {
        stopStream()
        _state.update { it.copy(selectedBot = null, messages = emptyList(), streamingText = "") }
    }

    fun loadMoreHistory() = viewModelScope.launch {
        if (_state.value.nextCursor == null) return@launch
        loadHistory(reset = false)
    }

    private suspend fun loadHistory(reset: Boolean) {
        val bot = _state.value.selectedBot ?: return
        val cursor = if (reset) null else _state.value.nextCursor
        _state.update { it.copy(busy = true) }
        runCatching {
            auth.withAccess { token, api -> api.history(token, bot.id, cursor) }
        }.onSuccess { page ->
            _state.update { st ->
                val merged = if (reset) page.items else page.items + st.messages
                st.copy(
                    busy = false,
                    messages = merged,
                    nextCursor = page.nextCursor,
                    status = when {
                        page.stub -> page.reason ?: "history stub"
                        else -> "History ${merged.size} items"
                    },
                )
            }
        }.onFailure { e ->
            _state.update { it.copy(busy = false, status = e.message ?: "history failed") }
        }
    }

    fun sendMessage() = viewModelScope.launch {
        val bot = _state.value.selectedBot ?: return@launch
        val text = _state.value.composeText.trim()
        if (text.isEmpty()) return@launch
        _state.update {
            it.copy(
                composeText = "",
                messages = it.messages + ChatItem(kind = "user", text = text, time = System.currentTimeMillis()),
                busy = true,
                status = "Sending…",
            )
        }
        runCatching {
            auth.withAccess { token, api -> api.send(token, bot.id, text) }
        }.onSuccess { res ->
            _state.update {
                it.copy(
                    busy = false,
                    status = if (res.ok) "Sent" else (res.reason ?: "send failed"),
                )
            }
        }.onFailure { e ->
            _state.update { it.copy(busy = false, status = e.message ?: "send failed") }
        }
    }

    private fun startStream(botId: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            _state.update { it.copy(streamConnected = false) }
            runCatching {
                val token = auth.refreshAccess(force = false)
                val base = auth.effectiveBaseUrl()
                val url = com.deepseek.dshmobile.data.BridgeApi(com.deepseek.dshmobile.data.BridgeApi.restClient())
                    .withBase(base)
                    .streamUrl(botId = botId, test = false)
                sse.connect(url, token).collect { ev -> handleSse(ev) }
            }.onFailure { e ->
                val msg = when (e) {
                    is BridgeError -> e.message
                    else -> e.message ?: "stream ended"
                }
                _state.update { it.copy(streamConnected = false, status = "SSE: $msg") }
            }
        }
    }

    private fun handleSse(ev: SseEvent) {
        when (ev.event) {
            "hello" -> _state.update { it.copy(streamConnected = true, status = "SSE live") }
            "live" -> Unit
            "message" -> {
                val itemJson = ev.json().optJSONObject("item") ?: return
                val item = com.deepseek.dshmobile.data.parseChatItem(itemJson)
                _state.update { st ->
                    // Avoid duplicating optimistic user bubble if server echoes soon — keep simple append.
                    st.copy(messages = st.messages + item, streamingText = "")
                }
            }
            "stream" -> {
                val data = ev.json()
                when (data.optString("phase")) {
                    "start" -> {
                        streamAttemptId = data.optString("attemptId")
                        _state.update { it.copy(streamingText = "") }
                    }
                    "end" -> {
                        val text = _state.value.streamingText
                        if (text.isNotBlank()) {
                            _state.update {
                                it.copy(
                                    messages = it.messages + ChatItem(
                                        kind = "bot",
                                        text = text,
                                        time = System.currentTimeMillis(),
                                    ),
                                    streamingText = "",
                                )
                            }
                        }
                        streamAttemptId = null
                    }
                    else -> {
                        val delta = data.optString("text")
                        if (delta.isNotEmpty()) {
                            _state.update { it.copy(streamingText = it.streamingText + delta) }
                        }
                    }
                }
            }
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        streamAttemptId = null
        _state.update { it.copy(streamConnected = false, streamingText = "") }
    }

    override fun onCleared() {
        stopStream()
        super.onCleared()
    }
}
