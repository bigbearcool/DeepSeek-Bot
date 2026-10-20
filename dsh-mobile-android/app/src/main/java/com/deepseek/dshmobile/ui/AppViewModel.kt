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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { Loading, Pair, Team, Chat, NewBot, NewRoom, Settings, BotProfile, GroupInfo, AddMember, Search }

const val ROOM_KEY_PREFIX = "room:"
const val NO_NETWORK = "网络不通，请检查网络后重试"

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
    // ---- kept on this phone only ----
    val prefs: com.deepseek.dshmobile.data.ChatPrefs = com.deepseek.dshmobile.data.ChatPrefs(),
    /** The message being answered (quoted when sent). */
    val replyTo: ReplyTarget? = null,
    // batch 3: profile, group info, search
    val profile: com.deepseek.dshmobile.data.BotProfile? = null,
    val profileBack: Screen = Screen.Chat,
    val saving: Boolean = false,
    val searchQuery: String = "",
    val searchHits: List<com.deepseek.dshmobile.data.SearchHit> = emptyList(),
    val searchingMessages: Boolean = false,
    val addPicked: Set<String> = emptySet(),
    // batch 4: pictures and files
    val pending: List<com.deepseek.dshmobile.data.PendingAttachment> = emptyList(),
    /** Picture open full screen. */
    val viewer: com.deepseek.dshmobile.data.Attachment? = null,
    /** Exchange runs the user opened, by the seq/time of their first item. */
    val expanded: Set<String> = emptySet(),
    // batch 5: what the phone allows (read on every return to the app)
    val notifyAllowed: Boolean = true,
    val batteryFree: Boolean = true,
) {
    val canSend: Boolean get() = com.deepseek.dshmobile.data.AttachLogic.canSend(composeText, pending) && !sending
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

    private val prefsStore = com.deepseek.dshmobile.data.ChatPrefsStore(app)
    private var streamJob: Job? = null
    private var pollJob: Job? = null

    init {
        viewModelScope.launch { prefsStore.flow.collect { p -> _state.update { it.copy(prefs = p) }; syncService() } }
        // The chat on screen gets no notification, and opening it clears its notification.
        viewModelScope.launch {
            state.map { s -> if (s.screen == Screen.Chat) s.openKind?.let { k -> s.openId?.let { id -> k to id } } else null }
                .distinctUntilChanged().collect { open ->
                    com.deepseek.dshmobile.notify.AppPresence.openKey = open?.let { com.deepseek.dshmobile.data.NotifyLogic.key(it.first, it.second) }
                    if (open != null) com.deepseek.dshmobile.notify.Notifier.cancelChat(getApplication(), open.first, open.second)
                }
        }
        viewModelScope.launch { state.map { it.paired }.distinctUntilChanged().collect { syncService() } }
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

    /** Insert "@Name " from the group @ picker (same text ds-bot reads to pick who answers). */
    fun pickMention(insert: String) = _state.update { st ->
        val active = com.deepseek.dshmobile.data.Mentions.activeAt(st.composeText) ?: return@update st
        st.copy(composeText = com.deepseek.dshmobile.data.Mentions.apply(st.composeText, active, insert))
    }
    /** Text from the phone's own voice input goes after what is already typed. */
    fun addVoiceText(spoken: String) = _state.update { it.copy(composeText = com.deepseek.dshmobile.data.Look.mergeVoice(it.composeText, spoken)) }
    fun showNotice(text: String) = _state.update { it.copy(notice = text) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ---- phone-only list settings, look, settings page ----
    private fun editPrefs(change: (com.deepseek.dshmobile.data.ChatPrefs) -> com.deepseek.dshmobile.data.ChatPrefs) {
        val next = change(_state.value.prefs)
        _state.update { it.copy(prefs = next) }
        viewModelScope.launch { prefsStore.save(next) }
    }
    fun togglePin(c: ChatEntry) = editPrefs { com.deepseek.dshmobile.data.ChatPrefsLogic.togglePin(it, c) }
    fun hideChat(c: ChatEntry) = editPrefs { com.deepseek.dshmobile.data.ChatPrefsLogic.hide(it, c, System.currentTimeMillis()) }
    fun unhideAll() = editPrefs { com.deepseek.dshmobile.data.ChatPrefsLogic.unhideAll(it) }
    fun setTheme(t: String) = editPrefs { it.copy(theme = com.deepseek.dshmobile.data.ChatPrefsLogic.normTheme(t)) }
    /** 标为未读 is kept on the phone; 标为已读 on a chat with real unread messages tells the computer too. */
    fun toggleUnread(c: ChatEntry) {
        val marked = com.deepseek.dshmobile.data.ChatPrefsLogic.markedUnread(c, _state.value.prefs)
        if (c.unread > 0 || marked) {
            if (marked) editPrefs { com.deepseek.dshmobile.data.ChatPrefsLogic.toggleUnread(it, c) }
            if (c.unread > 0) {
                _state.update { s -> s.copy(chats = s.chats.map { if (it.kind == c.kind && it.id == c.id) it.copy(unread = 0) else it }) }
                viewModelScope.launch { runCatching { auth.withAccess { token, api -> api.markRead(token, c.kind, c.id) } } }
            }
        } else editPrefs { com.deepseek.dshmobile.data.ChatPrefsLogic.toggleUnread(it, c) }
    }
    fun openSettings() = _state.update { it.copy(screen = Screen.Settings) }

    // ---- batch 5: notifications ----
    fun setNotify(on: Boolean) = editPrefs { it.copy(notify = on) }
    private fun syncService() {
        val s = _state.value
        com.deepseek.dshmobile.notify.NotifyService.sync(getApplication(), s.paired, s.prefs.notify)
    }
    /** Called when the app comes back to the screen: the user may have changed phone settings. */
    fun refreshSystem() {
        val app = getApplication<Application>()
        val pm = app.getSystemService(android.os.PowerManager::class.java)
        _state.update { it.copy(notifyAllowed = com.deepseek.dshmobile.notify.Notifier.enabled(app), batteryFree = pm?.isIgnoringBatteryOptimizations(app.packageName) ?: true) }
        syncService()
    }
    /** A tapped notification opens its chat. */
    fun openFromNotification(kind: String, id: String, name: String?) {
        val entry = _state.value.chats.find { it.kind == kind && it.id == id } ?: ChatEntry(kind, id, name.orEmpty())
        openChat(entry)
    }

    // ---- batch 3: Bot profile ----
    fun toggleMute(kind: String, id: String) = editPrefs {
        val k = "$kind:$id"
        it.copy(muted = if (k in it.muted) it.muted - k else it.muted + k)
    }

    /** Opens a Bot's profile; back returns to where it was opened from. */
    fun openProfile(botId: String) {
        val bot = _state.value.bots.find { it.id == botId }
        val back = _state.value.screen.let { if (it == Screen.BotProfile) Screen.Chat else it }
        _state.update {
            it.copy(
                screen = Screen.BotProfile, profileBack = back, formError = null,
                profile = bot?.let { b -> com.deepseek.dshmobile.data.BotProfile(b.id, b.name, b.role.orEmpty(), "", b.shape, b.color, b.main, b.model) },
            )
        }
        viewModelScope.launch {
            // Options list the models the computer offers (same catalog as create-bot).
            if (_state.value.options == null) runCatching {
                auth.withAccess { token, api -> api.botOptions(token) }
            }.onSuccess { opts -> _state.update { it.copy(options = opts) } }
            runCatching { auth.withAccess { token, api -> api.botInfo(token, botId) } }
                .onSuccess { p -> _state.update { if (it.profile?.id == p.id || it.profile == null) it.copy(profile = p) else it } }
                .onFailure { e -> if (e is CancellationException) throw e; _state.update { it.copy(formError = friendly(e)) } }
        }
    }

    fun closeProfile() = _state.update { it.copy(screen = it.profileBack, formError = null) }

    /** Saves one change to the Bot on the computer, then shows what the computer kept. */
    fun saveProfile(change: org.json.JSONObject) {
        val p = _state.value.profile ?: return
        change.put("id", p.id)
        _state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> api.updateBot(token, change) } }
                .onSuccess { next ->
                    _state.update { s ->
                        s.copy(
                            saving = false, profile = next.copy(main = p.main),
                            bots = s.bots.map { if (it.id == next.id) it.copy(name = next.name, role = next.role, color = next.color, shape = next.shape, model = next.model) else it },
                            selectedBot = s.selectedBot?.let { if (it.id == next.id) it.copy(name = next.name, color = next.color, shape = next.shape, model = next.model) else it },
                            chats = s.chats.map { if (it.kind == "bot" && it.id == next.id) it.copy(name = next.name, color = next.color) else it },
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(saving = false, formError = friendly(e)) }
                }
        }
    }

    fun setShape(shape: String) = saveProfile(org.json.JSONObject().put("shape", shape))
    fun setColor(color: String) = saveProfile(org.json.JSONObject().put("color", color))
    fun resetLook() = saveProfile(org.json.JSONObject().put("shape", org.json.JSONObject.NULL).put("color", org.json.JSONObject.NULL))
    fun saveText(field: String, value: String) = saveProfile(org.json.JSONObject().put(field, value))
    /** Same as desktop Bot settings → Model: write the ref through update-bot; next message uses it. */
    fun setModel(ref: String) = saveProfile(org.json.JSONObject().put("model", ref))

    // ---- batch 3: group info, add member ----
    fun openGroupInfo() = _state.update { it.copy(screen = Screen.GroupInfo, formError = null) }
    fun closeGroupInfo() = _state.update { it.copy(screen = Screen.Chat, formError = null) }
    fun openAddMember() = _state.update { it.copy(screen = Screen.AddMember, addPicked = emptySet(), formError = null) }
    fun closeAddMember() = _state.update { it.copy(screen = Screen.GroupInfo, formError = null) }
    fun toggleAddPick(id: String) = _state.update { it.copy(addPicked = if (id in it.addPicked) it.addPicked - id else it.addPicked + id) }

    fun addMembers() {
        val st = _state.value
        val room = st.selectedRoom ?: return
        if (st.addPicked.isEmpty() || st.saving) return
        _state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> api.addMembers(token, room.id, st.addPicked.toList()) } }
                .onSuccess { r ->
                    _state.update { s ->
                        val next = (s.selectedRoom ?: room).copy(members = r.members.ifEmpty { room.members + st.addPicked })
                        s.copy(saving = false, screen = Screen.GroupInfo, addPicked = emptySet(), selectedRoom = next,
                            rooms = s.rooms.map { if (it.id == next.id) next else it })
                    }
                    loadTeam()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(saving = false, formError = friendly(e)) }
                }
        }
    }

    // ---- batch 3: search ----
    private var searchJob: kotlinx.coroutines.Job? = null
    fun openSearch() = _state.update { it.copy(screen = Screen.Search, searchQuery = "", searchHits = emptyList()) }
    fun closeSearch() { searchJob?.cancel(); _state.update { it.copy(screen = Screen.Team, searchQuery = "", searchHits = emptyList(), searchingMessages = false) } }
    fun onSearch(q: String) {
        _state.update { it.copy(searchQuery = q) }
        searchJob?.cancel()
        if (q.trim().length < 1) { _state.update { it.copy(searchHits = emptyList(), searchingMessages = false) }; return }
        searchJob = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(searchingMessages = true) }
            val hits = runCatching { auth.withAccess { token, api -> api.search(token, q.trim()) } }.getOrElse { e -> if (e is CancellationException) throw e; emptyList() }
            _state.update { if (it.searchQuery == q) it.copy(searchHits = hits, searchingMessages = false) else it }
        }
    }
    fun openHit(hit: com.deepseek.dshmobile.data.SearchHit) {
        searchJob?.cancel()
        val entry = _state.value.chats.find { it.kind == hit.kind && it.id == hit.id } ?: ChatEntry(hit.kind, hit.id, hit.name)
        _state.update { it.copy(searchQuery = "", searchHits = emptyList()) }
        openChat(entry)
    }
    fun closeSettings() = _state.update { it.copy(screen = Screen.Team) }
    fun setReply(r: ReplyTarget?) = _state.update { it.copy(replyTo = r) }
    // ---- batch 4: pictures and files ----
    private val images = android.util.LruCache<String, android.graphics.Bitmap>(24)

    /** Picked pictures/files go to the computer right away, so sending is quick. */
    fun addPicked(uris: List<android.net.Uri>, forceName: String? = null) {
        val st = _state.value
        val kind = st.openKind ?: return
        val id = st.openId ?: return
        val cr = getApplication<Application>().contentResolver
        for (uri in uris) {
            val count = _state.value.pending.size
            var name = forceName ?: "文件"
            var size = -1L
            runCatching {
                cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        if (forceName == null) c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val type = cr.getType(uri).orEmpty()
            val isImage = type.startsWith("image/") || forceName != null
            if (isImage && forceName == null) name = com.deepseek.dshmobile.data.AttachLogic.niceName(name, type,
                java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()), uris.indexOf(uri))
            com.deepseek.dshmobile.data.AttachLogic.problem(if (size < 0) 1 else size, count)?.let { p -> showNotice(p); return }
            val p = com.deepseek.dshmobile.data.PendingAttachment("p-" + UUID.randomUUID(), uri.toString(), name, isImage, maxOf(size, 0))
            _state.update { it.copy(pending = it.pending + p) }
            viewModelScope.launch {
                runCatching {
                    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cr.openInputStream(uri)?.use { it.readBytes() } ?: error("unreadable")
                    }
                    if (bytes.size > com.deepseek.dshmobile.data.AttachLogic.MAX_BYTES) throw BridgeError("too_large", 413, "文件太大了，最多 25 MB")
                    auth.withAccess { token, api -> api.upload(token, kind, id, name, bytes) } to bytes.size.toLong()
                }.onSuccess { (r, n) ->
                    _state.update { s -> s.copy(pending = s.pending.map { if (it.localId == p.localId) it.copy(uploadId = r.uploadId, bytes = n) else it }) }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { s -> s.copy(pending = s.pending.filterNot { it.localId == p.localId }, notice = "没能添加「$name」：" + friendly(e)) }
                }
            }
        }
    }

    fun removePending(localId: String) = _state.update { s -> s.copy(pending = s.pending.filterNot { it.localId == localId }) }
    fun openViewer(a: com.deepseek.dshmobile.data.Attachment?) = _state.update { it.copy(viewer = a) }
    fun toggleExpanded(key: String) = _state.update { it.copy(expanded = if (key in it.expanded) it.expanded - key else it.expanded + key) }

    /** A picture in a message, scaled to at most [max] px on its long side; cached. */
    suspend fun picture(a: com.deepseek.dshmobile.data.Attachment, max: Int): android.graphics.Bitmap? {
        val key = (a.id ?: a.localUri ?: return null) + "@" + max
        images.get(key)?.let { return it }
        val bytes = runCatching {
            if (a.localUri != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(android.net.Uri.parse(a.localUri))?.use { it.readBytes() }
            } else auth.withAccess { token, api -> api.attachmentBytes(token, a.id!!) }
        }.getOrElse { e -> if (e is CancellationException) throw e; null } ?: return null
        val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        images.put(key, bmp)
        return bmp
    }

    /** Saves a picture to 相册 or a file to 下载 (in a DS Bot folder), then says where. */
    fun saveAttachment(a: com.deepseek.dshmobile.data.Attachment) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val bytes = if (a.localUri != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.contentResolver.openInputStream(android.net.Uri.parse(a.localUri))?.use { it.readBytes() } ?: error("unreadable")
                } else auth.withAccess { token, api -> api.attachmentBytes(token, a.id ?: error("gone")) }
                val name = (a.name ?: if (a.isImage) "图片.png" else "文件").replace('/', '_')
                val type = a.mediaType ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveToPhone(app, name, type, a.isImage, bytes) }
                val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                val where = com.deepseek.dshmobile.data.AttachLogic.saveFolder(a.isImage)
                com.deepseek.dshmobile.notify.Notifier.saved(app, name, where, view)
                showNotice("已保存到手机的「$where」")
            }.onFailure { e -> if (e is CancellationException) throw e; showNotice("没能保存：" + friendly(e)) }
        }
    }

    private fun saveToPhone(app: Application, name: String, type: String, image: Boolean, bytes: ByteArray): android.net.Uri {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, type)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, (if (image) android.os.Environment.DIRECTORY_PICTURES else android.os.Environment.DIRECTORY_DOWNLOADS) + "/DS Bot")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val coll = if (image) android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = app.contentResolver.insert(coll, values) ?: error("no room")
            app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("unwritable")
            app.contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }
        // Android 8–9: the app's own folder (no storage permission needed).
        val dir = java.io.File(app.getExternalFilesDir(if (image) android.os.Environment.DIRECTORY_PICTURES else android.os.Environment.DIRECTORY_DOWNLOADS), "DS Bot").apply { mkdirs() }
        val f = java.io.File(dir, name).apply { writeBytes(bytes) }
        return androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", f)
    }

    /** Opens a file from a message in another app on the phone. */
    fun openFile(a: com.deepseek.dshmobile.data.Attachment) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val uri = if (a.localUri != null) android.net.Uri.parse(a.localUri) else {
                    val bytes = auth.withAccess { token, api -> api.attachmentBytes(token, a.id ?: error("gone")) }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val dir = java.io.File(app.cacheDir, "open/" + a.id!!.takeLast(12)).apply { mkdirs() }
                        val f = java.io.File(dir, (a.name ?: "文件").replace('/', '_'))
                        f.writeBytes(bytes)
                        androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", f)
                    }
                }
                val type = a.mediaType ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.name?.substringAfterLast('.', "")?.lowercase()) ?: "*/*"
                val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.startActivity(android.content.Intent.createChooser(view, a.name ?: "打开文件").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                showNotice(if (e is android.content.ActivityNotFoundException) "手机上没有能打开这个文件的应用" else "没能打开文件：" + friendly(e))
            }
        }
    }

    // ---- batch 4: remove member, rename group; group @ fix: reply mode ----
    fun removeMember(botId: String) = changeRoom(remove = listOf(botId))
    fun renameRoom(name: String) { if (name.isNotBlank()) changeRoom(name = name.trim()) }
    /** Align with ds-bot everyone/admin: everyone → all reply; admin → admin first. */
    fun setRoomMode(mode: String) { if (mode == "everyone" || mode == "admin") changeRoom(mode = mode) }
    private fun changeRoom(name: String? = null, remove: List<String> = emptyList(), mode: String? = null) {
        val room = _state.value.selectedRoom ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> api.updateRoom(token, room.id, name, remove, mode) } }
                .onSuccess { r ->
                    _state.update { s ->
                        val next = (s.selectedRoom ?: room).copy(
                            name = r.name.ifBlank { name ?: room.name },
                            members = r.members.ifEmpty { room.members - remove.toSet() },
                            mode = r.mode ?: mode ?: room.mode,
                            admin = r.admin ?: room.admin,
                        )
                        s.copy(saving = false, selectedRoom = next, rooms = s.rooms.map { if (it.id == next.id) next else it },
                            chats = s.chats.map { if (it.kind == "room" && it.id == next.id) it.copy(name = next.name) else it })
                    }
                    loadTeam()
                }
                .onFailure { e -> if (e is CancellationException) throw e; _state.update { it.copy(saving = false, formError = friendly(e)) } }
        }
    }

    /** Sends the typed text; when answering a message, the quote goes first. */
    fun sendWithReply() {
        val r = _state.value.replyTo
        if (r != null && _state.value.composeText.isNotBlank() && !_state.value.sending) {
            _state.update { it.copy(composeText = com.deepseek.dshmobile.data.Look.quoted(r.name, r.snippet, it.composeText.trim()), replyTo = null) }
        }
        sendMessage()
    }

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
        is java.net.SocketTimeoutException, is java.io.IOException -> NO_NETWORK
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
        _state.update { UiState(screen = Screen.Pair, baseUrl = it.baseUrl, prefs = it.prefs) }
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
                        // The list came back, so an old "no network" note is stale.
                        notice = if (it.notice == NO_NETWORK) null else it.notice,
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
        if (com.deepseek.dshmobile.data.ChatPrefsLogic.markedUnread(entry, _state.value.prefs)) {
            editPrefs { com.deepseek.dshmobile.data.ChatPrefsLogic.opened(it, entry.kind, entry.id) }
        }
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
                selectedRoom = null, pending = emptyList(), expanded = emptySet(),
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
            it.copy(screen = Screen.Team, selectedBot = null, selectedRoom = null, live = emptyList(), messages = emptyList(), wait = WaitState(), formError = null, replyTo = null, pending = emptyList(), expanded = emptySet())
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
        if (!st.canSend) return // guard double tap / IME + button, and uploads still going
        val picked = st.pending
        val clientId = "m-" + UUID.randomUUID().toString()
        _state.update {
            it.copy(
                composeText = "", pending = emptyList(),
                sending = true,
                notice = null,
                messages = it.messages + ChatItem(
                    kind = "user", text = text, time = System.currentTimeMillis(),
                    clientMessageId = clientId, local = true, attachments = picked.map { p -> p.asAttachment() },
                ),
                wait = WaitMachine.reduce(it.wait, WaitEvent.Submitted(clientId)),
            )
        }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> api.send(token, bot.id, text, clientId, picked.mapNotNull { it.uploadId }) } }
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
                screen = Screen.Chat, selectedRoom = room, selectedBot = null, live = emptyList(), pending = emptyList(), expanded = emptySet(),
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
        // ds-bot only parses ASCII @; Chinese IMEs often insert ＠ — normalize before send.
        val text = st.composeText.trim().replace('＠', '@')
        if (!st.canSend) return
        val picked = st.pending
        val clientId = "m-" + UUID.randomUUID().toString()
        _state.update {
            it.copy(
                composeText = "", sending = true, notice = null, pending = emptyList(),
                messages = it.messages + ChatItem(kind = "user", text = text, time = System.currentTimeMillis(), clientMessageId = clientId, local = true, attachments = picked.map { p -> p.asAttachment() }),
                wait = WaitMachine.reduce(it.wait, WaitEvent.Submitted(clientId)),
            )
        }
        viewModelScope.launch {
            runCatching { auth.withAccess { token, api -> api.roomSend(token, room.id, text, clientId, picked.mapNotNull { it.uploadId }) } }
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
