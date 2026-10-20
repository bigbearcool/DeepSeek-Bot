package com.deepseek.dshmobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.Animatable
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import com.deepseek.dshmobile.data.ChatPrefsLogic
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dshmobile.data.ApprovalInfo
import com.deepseek.dshmobile.data.BotInfo
import com.deepseek.dshmobile.data.ChatEntry
import com.deepseek.dshmobile.data.ChatItem
import com.deepseek.dshmobile.data.ChatListLogic
import com.deepseek.dshmobile.data.Look
import com.deepseek.dshmobile.data.RoomInfo
import com.deepseek.dshmobile.data.RoomStreams
import com.deepseek.dshmobile.data.WaitPhase
import com.deepseek.dshmobile.data.WaitState

// ---------- shared pieces ----------

/** 36dp round button on a light gray disc, used in all top bars. */
@Composable
internal fun RoundButton(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalDs.current
    Box(
        Modifier.size(36.dp).shadow(if (c.dark) 0.dp else 2.dp, CircleShape, clip = false).clip(CircleShape).background(c.card)
            .then(if (c.dark) Modifier.border(1.dp, c.line, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) c.ink else c.ink3, modifier = Modifier.size(20.dp)) }
}

/** Plain top bar for forms: round back button and a title. */
@Composable
internal fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    val c = LocalDs.current
    Row(
        Modifier.fillMaxWidth().background(c.bg).statusBarsPadding().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack)
            Spacer(Modifier.size(12.dp))
        }
        Text(title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
internal fun Banner(text: String, danger: Boolean = false) {
    val c = LocalDs.current
    Text(
        text,
        color = if (danger) c.danger else c.ink2,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.surface).padding(vertical = 8.dp, horizontal = 12.dp),
    )
}

/** A Bot's avatar: its colored shape with eyes. */
@Composable
internal fun Avatar(name: String, size: Int = 40, color: String? = null, key: String? = null) {
    ShapeAvatar(key ?: name, color, size)
}

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val c = LocalDs.current
    Box(
        Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp))
            .background(if (enabled) c.user else c.bubble)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = c.userInk, strokeWidth = 2.dp)
        else Text(text, color = if (enabled) c.userInk else c.ink3, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    big: Boolean = false,
) {
    val c = LocalDs.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.input)
            .border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val style = TextStyle(
            color = c.ink, fontSize = if (big) 24.sp else 15.sp,
            letterSpacing = if (big) 6.sp else 0.sp,
            textAlign = if (big) TextAlign.Center else TextAlign.Start,
        )
        if (value.isEmpty()) Text(
            placeholder, style = style.copy(color = c.ink3, letterSpacing = 0.sp, fontSize = if (big) 17.sp else 15.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        BasicTextField(
            value, onChange, singleLine = true, textStyle = style, keyboardOptions = keyboard,
            cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun reduceMotion(ctx: android.content.Context): Boolean = runCatching {
    android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

// ---------- welcome / pairing ----------

@Composable
fun PairScreen(
    state: UiState,
    onBaseUrlChange: (String) -> Unit,
    onPairInputChange: (String) -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit,
) {
    val c = LocalDs.current
    var manual by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf(state.pairInput.isNotEmpty()) }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        FloatingShapes()
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text("DS Bot", color = c.ink, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "连上电脑，随时在手机上\n和你的助手们说话。",
                color = c.ink2, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 22.sp,
            )
            state.pairReason?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = c.danger, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(c.user).clickable(onClick = onScan)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.QrCodeScanner, null, tint = c.userInk, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("扫码连接电脑", color = c.userInk, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.size(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = c.userInk, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (code) "收起配对码" else "输入配对码",
                color = c.ink2, fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { code = !code }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            AnimatedVisibility(code) {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        "在电脑上打开 DS Bot，点左下角「手机配对」，把电脑上显示的 6 位配对码填在下面。",
                        color = c.ink3, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Field(
                        state.pairInput, onPairInputChange, "6 位配对码",
                        KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done), big = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("连接", busy = state.busy, onClick = onPair)
                }
            }
            state.notice?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = c.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (manual) "收起电脑地址" else "手动填写电脑地址",
                color = c.ink3, fontSize = 13.sp,
                modifier = Modifier.clickable { manual = !manual }.padding(8.dp),
            )
            AnimatedVisibility(manual) {
                Column {
                    Field(state.baseUrl, onBaseUrlChange, "例如 https://example.com:8443", KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Spacer(Modifier.height(6.dp))
                    Text("扫码会自动填好，一般不需要手动填写。", color = c.ink3, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Colored shapes drifting slowly around the welcome title. */
@Composable
private fun FloatingShapes() {
    val still = reduceMotion(LocalContext.current)
    val t = rememberInfiniteTransition(label = "float")
    val a by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(6f, -6f, infiniteRepeatable(tween(5600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val spots = listOf(
        Triple(0.52f, 0.10f, "tri" to "magenta"), Triple(0.14f, 0.18f, "c1" to "violet"), Triple(0.80f, 0.24f, "drop2" to "orange"),
        Triple(0.06f, 0.36f, "sq" to "gray"), Triple(0.82f, 0.58f, "hex" to "cyan"), Triple(0.16f, 0.72f, "drop" to "blue"),
        Triple(0.70f, 0.84f, "arch" to "yellow"),
    )
    val keys = listOf("bot-3", "bot-1", "bot-6", "bot-12", "bot-2", "bot-9", "bot-4")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        spots.forEachIndexed { i, (x, y, kc) ->
            val dy = if (still) 0f else if (i % 2 == 0) a else b
            ShapeAvatar(
                keys[i], kc.second, if (i % 3 == 0) 40 else 48,
                Modifier.offset(x = maxWidth * x, y = maxHeight * y + dy.dp),
            )
        }
    }
}

// ---------- home: conversation list ----------

@Composable
fun TeamScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpen: (BotInfo) -> Unit,
    onLogout: () -> Unit,
    onOpenRoom: (RoomInfo) -> Unit = {},
    onNewBot: () -> Unit = {},
    onNewRoom: () -> Unit = {},
    onOpenChat: (ChatEntry) -> Unit = {},
    onAnswer: (String, Boolean) -> Unit = { _, _ -> },
    onSettings: () -> Unit = {},
    onTogglePin: (ChatEntry) -> Unit = {},
    onToggleUnread: (ChatEntry) -> Unit = {},
    onHide: (ChatEntry) -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val c = LocalDs.current
    var add by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Icons.Filled.Person, "设置", onClick = onSettings)
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Filled.Search, "搜索", onClick = onSearch)
            Spacer(Modifier.size(10.dp))
            Box {
                RoundButton(Icons.Filled.Add, "新建", onClick = { add = true })
                DropdownMenu(expanded = add, onDismissRequest = { add = false }) {
                    DropdownMenuItem(text = { Text("新建助手", fontSize = 16.sp) }, onClick = { add = false; onNewBot() })
                    DropdownMenuItem(
                        text = { Text("新建群聊", fontSize = 16.sp, color = if (state.bots.size >= 2) Color.Unspecified else c.ink3) },
                        enabled = state.bots.size >= 2,
                        onClick = { add = false; onNewRoom() },
                    )
                }
            }
        }
        AnimatedVisibility(searching) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Field(query, { query = it }, "搜索助手和群聊") }
        }
        state.notice?.let { Banner(it, danger = true) }
        if (!state.teamLoaded && state.busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = c.ink3, strokeWidth = 2.dp)
            }
            return@Column
        }
        if (state.teamLoaded && state.bots.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有助手", color = c.ink3, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("新建助手", onClick = onNewBot)
            }
            return@Column
        }
        val chats = if (state.chats.isNotEmpty()) state.chats else fallbackChats(state)
        val shown = Look.filter(ChatPrefsLogic.apply(chats, state.prefs), query)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.approvals.isNotEmpty() && query.isBlank()) {
                item(key = "h-approvals") { SectionLabel("等你确认") }
                items(state.approvals, key = { "ap-" + it.id }) { a ->
                    ApprovalCard(a, busy = a.id in state.answering, showWhere = true, onAnswer = onAnswer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                item(key = "h-chats") { Spacer(Modifier.height(8.dp)) }
            }
            if (shown.isEmpty() && query.isNotBlank()) item(key = "none") {
                Text("没有找到「${query.trim()}」", color = c.ink3, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp))
            }
            items(shown, key = { it.kind + "-" + it.id }) { chat ->
                ChatRow(chat, state, onTogglePin = onTogglePin, onToggleUnread = onToggleUnread, onHide = onHide, onClick = {
                    if (state.chats.isNotEmpty()) onOpenChat(chat)
                    else if (chat.isRoom) state.rooms.find { it.id == chat.id }?.let(onOpenRoom)
                    else state.bots.find { it.id == chat.id }?.let(onOpen)
                })
            }
            item(key = "pad") { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

/** Older computers do not send a conversation list: build one from Bots and groups. */
private fun fallbackChats(state: UiState): List<ChatEntry> =
    state.rooms.map { ChatEntry("room", it.id, it.name, preview = it.preview.orEmpty()) } +
        state.bots.map { ChatEntry("bot", it.id, it.name, color = it.color, main = it.main, preview = it.role.orEmpty()) }

@Composable
private fun ChatAvatar(chat: ChatEntry, state: UiState, size: Int) {
    if (chat.isRoom) {
        val ids = state.rooms.find { it.id == chat.id }?.let { r -> chat.members.ifEmpty { r.members } } ?: chat.members
        RoomShapeAvatar(ids.map { id -> id to state.bots.find { it.id == id }?.color }.ifEmpty { listOf(chat.id to null) }, size)
    } else Avatar(chat.name, size, chat.color ?: state.bots.find { it.id == chat.id }?.color, key = chat.id)
}

/** One conversation: shape avatar, name, time, one line of the last message, unread count. Long-press: 标为未读 / 置顶 / 隐藏. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatEntry, state: UiState, onClick: () -> Unit,
    onTogglePin: (ChatEntry) -> Unit = {}, onToggleUnread: (ChatEntry) -> Unit = {}, onHide: (ChatEntry) -> Unit = {},
) {
    val c = LocalDs.current
    var menu by remember { mutableStateOf(false) }
    val pinned = ChatPrefsLogic.isPinned(chat, state.prefs)
    val handUnread = ChatPrefsLogic.markedUnread(chat, state.prefs)
    val unread = chat.unread > 0 || handUnread
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatAvatar(chat, state, 44)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chat.name, color = c.ink, fontSize = 16.sp, lineHeight = 21.sp,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    if (pinned) {
                        Icon(Icons.Filled.PushPin, "已置顶", tint = c.ink3, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.size(4.dp))
                    }
                    Text(ChatListLogic.timeLabel(chat.lastAt), color = c.ink3, fontSize = 12.sp, maxLines = 1)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val waiting = state.approvals.any { a -> if (chat.isRoom) a.roomId == chat.id else a.roomId == null && a.botId == chat.id }
                    val line = when {
                        waiting -> "等你确认"
                        chat.running -> if (chat.isRoom) "正在输入…" else "正在工作"
                        chat.preview.isNotBlank() -> Look.plainPreview(chat.preview)
                        chat.isRoom -> state.rooms.find { it.id == chat.id }?.memberNames?.joinToString("、").orEmpty()
                        else -> state.bots.find { it.id == chat.id }?.role.orEmpty()
                    }
                    Text(
                        line, color = if (waiting) c.danger else c.ink2,
                        fontSize = 14.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    val badge = ChatListLogic.badge(chat.unread)
                    if (badge != null) {
                        Spacer(Modifier.size(8.dp))
                        Box(
                            Modifier.height(20.dp).widthIn(min = 20.dp).clip(RoundedCornerShape(10.dp))
                                .background(if ("${chat.kind}:${chat.id}" in state.prefs.muted) c.ink3.copy(alpha = 0.6f) else c.ink).padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(badge, color = c.bg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                    } else if (handUnread) {
                        Spacer(Modifier.size(8.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(c.ink))
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = androidx.compose.ui.unit.DpOffset(120.dp, 0.dp)) {
            MenuRow(Icons.Outlined.MarkChatUnread, if (unread) "标为已读" else "标为未读") { menu = false; onToggleUnread(chat) }
            MenuRow(Icons.Outlined.PushPin, if (pinned) "取消置顶" else "置顶") { menu = false; onTogglePin(chat) }
            MenuRow(Icons.Outlined.VisibilityOff, "隐藏", danger = true) { menu = false; onHide(chat) }
        }
    }
}

@Composable
internal fun MenuRow(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = LocalDs.current
    DropdownMenuItem(
        leadingIcon = { Icon(icon, null, tint = if (danger) c.danger else c.ink2, modifier = Modifier.size(20.dp)) },
        text = { Text(text, color = if (danger) c.danger else c.ink, fontSize = 16.sp) },
        onClick = onClick,
    )
}

/** A request from a Bot that needs the user's yes or no. */
@Composable
internal fun ApprovalCard(
    a: ApprovalInfo,
    busy: Boolean,
    showWhere: Boolean,
    onAnswer: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalDs.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.bubble).padding(12.dp)) {
        Text(
            (if (showWhere) ChatListLogic.whereText(a) + " " else "") + "想" + a.action,
            color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
        if (a.reason.isNotBlank() || a.detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).padding(12.dp)) {
                if (a.reason.isNotBlank()) Text(a.reason, color = c.ink, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 4)
                if (a.detail.isNotBlank()) {
                    if (a.reason.isNotBlank()) Spacer(Modifier.height(6.dp))
                    Text(a.detail, color = c.ink2, fontSize = 13.sp, maxLines = 4)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!a.answerable) {
            Text("这个请求需要在电脑上确认", color = c.ink2, fontSize = 13.sp)
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.user)
                    .clickable(enabled = !busy) { onAnswer(a.id, true) },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = c.userInk, strokeWidth = 2.dp)
                else Text("同意", color = c.userInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.secondary)
                    .clickable(enabled = !busy) { onAnswer(a.id, false) },
                contentAlignment = Alignment.Center,
            ) { Text("拒绝", color = c.ink, fontSize = 15.sp) }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    val c = LocalDs.current
    Text(text, color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
}

// ---------- chat ----------

/** The message the user is answering (shown above the input box, quoted when sent). */
data class ReplyTarget(val name: String?, val snippet: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onComposeChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismissWait: () -> Unit,
    onStop: () -> Unit = {},
    onAnswer: (String, Boolean) -> Unit = { _, _ -> },
    onVoice: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onReply: (ReplyTarget?) -> Unit = {},
    onAttach: (String) -> Unit = {},
    onInfo: () -> Unit = {},
) {
    val c = LocalDs.current
    val list = rememberLazyListState()
    val liveShown = state.live.mapNotNull { r -> RoomStreams.shown(r)?.let { r to it } }
    val count = state.messages.size + (if (state.wait.visible) 1 else 0) + liveShown.size + state.openApprovals.size
    LaunchedEffect(count, state.wait.text.length / 80, liveShown.sumOf { it.second.length } / 80) {
        val n = list.layoutInfo.totalItemsCount
        if (count > 0 && n > 0) list.animateScrollToItem(n - 1)
    }
    val dividers = remember(state.messages) { Look.dividers(state.messages.map { it.time }) }
    // A Bot's own chat folds its talk with other Bots into 「N 条消息…」 lines.
    val runAt = remember(state.messages, state.inRoom) {
        if (state.inRoom) emptyMap() else com.deepseek.dshmobile.data.Exchanges.runs(state.messages)
            .flatMap { r -> (r.start until r.start + r.count).map { it to r } }.toMap()
    }
    val runKeyOf = { r: com.deepseek.dshmobile.data.Exchanges.Run -> state.messages[r.start].let { (it.seq ?: it.time ?: r.start).toString() } }
    val shownIdx = remember(state.messages, runAt, state.expanded) {
        state.messages.indices.filter { i -> runAt[i]?.let { r -> r.start == i || runKeyOf(r) in state.expanded } ?: true }
    }
    val attachUi = LocalAttach.current
    val LocalAttachToggle = attachUi.toggleRun
    val colorOf = { id: String? -> id?.let { i -> state.bots.find { it.id == i }?.color } }
    var menu by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<ChatItem?>(null) }
    var selectText by remember { mutableStateOf<String?>(null) }
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val replyOf = { m: ChatItem ->
        val who = when (m.kind) { "user", "answer" -> "你"; "member" -> m.name ?: "助手"; else -> state.selectedBot?.name }
        onReply(ReplyTarget(who, Look.snippet(Look.splitQuote(m.text.orEmpty()).second)))
    }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack)
            Spacer(Modifier.size(8.dp))
            Row(
                Modifier.weight(1f, fill = false).height(36.dp).shadow(if (c.dark) 0.dp else 2.dp, RoundedCornerShape(18.dp), clip = false)
                    .clip(RoundedCornerShape(18.dp)).background(c.card).clickable(onClick = onInfo).padding(start = 6.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val room = state.selectedRoom
                if (room != null) RoomShapeAvatar(room.members.map { id -> id to colorOf(id) }.ifEmpty { listOf(room.id to null) }, 24)
                else state.selectedBot?.let { Avatar(it.name, 24, it.color, key = it.id) }
                Spacer(Modifier.size(8.dp))
                Text(state.chatTitle, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Box {
                RoundButton(Icons.Filled.MoreHoriz, "更多", onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow(Icons.Outlined.Info, if (state.inRoom) "群聊信息" else "查看资料") { menu = false; onInfo() }
                    MenuRow(Icons.Filled.Refresh, "刷新") { menu = false; onRefresh() }
                }
            }
        }
        if (state.reconnecting) Banner("正在重新连接…")
        state.notice?.let { Banner(it, danger = true) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.nextCursor != null) item(key = "more") {
                Text(
                    "查看更早的消息", color = c.ink3, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onLoadMore).padding(8.dp),
                )
            }
            if (state.messages.isEmpty() && !state.wait.visible && !state.busy) item(key = "empty") {
                Text(
                    if (state.inRoom) "在群里说点什么吧，可以用 @名字 点名" else "有什么想让 ${state.selectedBot?.name ?: "助手"} 做的？",
                    color = c.ink3, fontSize = 15.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                )
            }
            items(shownIdx) { i ->
                val m = state.messages[i]
                val run = runAt[i]
                val runKey = run?.let { runKeyOf(it) }
                val folded = run != null && runKey !in state.expanded
                Column {
                    dividers[i]?.let { TimeDivider(it) }
                    if (run != null && i == run.start) ExchangeLine(run, !folded, colorOf) { LocalAttachToggle(runKey!!) }
                    if (folded) return@Column
                    val prev = state.messages.getOrNull(i - 1)
                    val next = state.messages.getOrNull(i + 1)
                    val firstOfRun = m.kind == "member" && (prev?.kind != "member" || prev.botId != m.botId || dividers[i] != null)
                    val lastOfRun = m.kind == "member" && (next?.kind != "member" || next.botId != m.botId || dividers[i + 1] != null)
                    SwipeToReply(enabled = m.kind in setOf("member", "bot", "user", "answer"), onReply = { replyOf(m) }) {
                        MessageRow(m, colorOf, firstOfRun, lastOfRun, onLongPress = { sheetFor = m })
                    }
                }
            }
            items(liveShown, key = { "live-" + it.first.attemptId }) { (reply, text) ->
                MemberBubble(reply.name ?: "助手", reply.botId, text, colorOf(reply.botId), showName = true, showAvatar = true)
            }
            val typing = state.live.filter { it.endedAt == null && RoomStreams.shown(it) == null }
            if (typing.isNotEmpty() && liveShown.isEmpty()) item(key = "typing") {
                WorkingLine(typing.mapNotNull { it.name }, typing.firstOrNull()?.botId, colorOf(typing.firstOrNull()?.botId), inRoom = true)
            }
            if (state.wait.visible && !(state.inRoom && state.wait.phase == WaitPhase.Streaming)) item(key = "wait") {
                WaitCard(state.wait, onDismissWait, state.selectedBot, inRoom = state.inRoom, hideWorking = typing.isNotEmpty())
            }
            items(state.openApprovals, key = { "ap-" + it.id }) { a ->
                ApprovalCard(a, busy = a.id in state.answering, showWhere = state.inRoom, onAnswer = onAnswer,
                    modifier = Modifier.padding(top = 8.dp).widthIn(max = 340.dp))
            }
        }
        state.replyTo?.let { r -> ReplyChip(r, onClose = { onReply(null) }) }
        PendingStrip(state.pending)
        Composer(
            state.composeText, state.sending, onComposeChange, onSend,
            hint = Look.inputHint(if (state.inRoom) state.chatTitle else state.selectedBot?.name, state.inRoom),
            showStop = state.showStop, stopping = state.stopping, onStop = onStop, onVoice = onVoice, onAttach = onAttach,
            hasAttachments = state.pending.isNotEmpty(), uploading = state.pending.any { !it.ready },
        )
    }
    state.viewer?.let { v -> ImageViewer(v, attachUi.closeViewer) }
    sheetFor?.let { m ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        val close = { after: () -> Unit -> scope.launch { sheet.hide() }.invokeOnCompletion { sheetFor = null; after() }; Unit }
        val body = Look.splitQuote(m.text.orEmpty()).second
        ModalBottomSheet(onDismissRequest = { sheetFor = null }, sheetState = sheet, containerColor = c.bg) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetCard {
                    SheetRow(Icons.AutoMirrored.Outlined.Reply, "回复") { close { replyOf(m) } }
                }
                SheetCard {
                    SheetRow(Icons.Outlined.ContentCopy, "复制") {
                        clip.setText(AnnotatedString(body))
                        android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                        close {}
                    }
                    SheetDivider()
                    SheetRow(Icons.Outlined.TextFields, "选择文本") { close { selectText = body } }
                }
            }
        }
    }
    selectText?.let { t ->
        AlertDialog(
            onDismissRequest = { selectText = null },
            confirmButton = { TextButton(onClick = { selectText = null }) { Text("完成", color = c.ink) } },
            containerColor = c.bg,
            text = {
                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                    Text(t, color = c.ink, fontSize = 16.sp, lineHeight = 22.sp)
                }
            },
        )
    }
}

/** Grouped rounded card, like the sheets and settings in Grok Bot. */
@Composable
internal fun SheetCard(content: @Composable () -> Unit) {
    val c = LocalDs.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.group)) { content() }
}

@Composable
internal fun SheetRow(icon: ImageVector?, text: String, danger: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)?) {
    val c = LocalDs.current
    Row(
        Modifier.fillMaxWidth().heightIn52().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) c.danger else c.ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
        }
        Text(text, color = if (danger) c.danger else c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

private fun Modifier.heightIn52(): Modifier = this.then(Modifier.height(52.dp))

@Composable
internal fun SheetDivider() {
    val c = LocalDs.current
    Box(Modifier.fillMaxWidth().padding(start = 48.dp).height(1.dp).background(c.line))
}

/** Swipe a message to the right to answer it. */
@Composable
private fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val c = LocalDs.current
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val limit = with(LocalDensity.current) { 72.dp.toPx() }
    Box(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val hit = offset.value >= limit * 0.8f
                    scope.launch { offset.animateTo(0f, tween(180)) }
                    if (hit) onReply()
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, tween(180)) } },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    scope.launch { offset.snapTo((offset.value + dx).coerceIn(0f, limit)) }
                },
            )
        },
    ) {
        if (offset.value > 8f) Icon(
            Icons.AutoMirrored.Outlined.Reply, null, tint = c.ink3.copy(alpha = (offset.value / limit).coerceIn(0f, 1f)),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(20.dp),
        )
        Box(Modifier.graphicsLayer { translationX = offset.value }) { content() }
    }
}

@Composable
private fun ReplyChip(r: ReplyTarget, onClose: () -> Unit) {
    val c = LocalDs.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.group)
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("回复 ${r.name ?: ""}".trim(), color = c.ink3, fontSize = 12.sp)
            Text(r.snippet, color = c.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "不回复了", tint = c.ink3, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TimeDivider(label: String) {
    val c = LocalDs.current
    Text(label, color = c.ink3, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp))
}

/** Gray bubble for a Bot's words (Markdown inside). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotBubble(text: String, modifier: Modifier = Modifier, maxFraction: Float = 0.85f, onLongPress: (() -> Unit)? = null) {
    val c = LocalDs.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(
            modifier.widthIn(max = maxWidth * maxFraction).clip(RoundedCornerShape(18.dp)).background(c.bubble)
                .then(if (onLongPress != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { MarkdownText(text) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(item: ChatItem, colorOf: (String?) -> String?, firstOfRun: Boolean, lastOfRun: Boolean, onLongPress: () -> Unit) {
    val c = LocalDs.current
    when (item.kind) {
        "member" -> Column {
          if (item.attachments.isNotEmpty()) Box(Modifier.padding(start = 34.dp, top = if (firstOfRun) 8.dp else 0.dp)) { AttachmentList(item.attachments, alignEnd = false) }
          if (!item.text.isNullOrBlank() || item.attachments.isEmpty()) MemberBubble(
            item.name ?: "助手", item.botId,
            item.text.orEmpty() + if (item.cut) "\n\n（内容较长，完整内容请在电脑上查看）" else "",
            colorOf(item.botId), showName = firstOfRun && item.attachments.isEmpty(), showAvatar = lastOfRun, onLongPress = onLongPress,
          )
        }
        "user", "answer" -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
          AttachmentList(item.attachments, alignEnd = true)
          val (quote, body) = Look.splitQuote(item.text.orEmpty())
          if (body.isNotBlank() || quote != null || item.attachments.isEmpty()) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.widthIn(max = maxWidth * 0.8f).clip(RoundedCornerShape(18.dp)).background(c.user)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (quote != null) {
                    Row(Modifier.padding(bottom = 6.dp)) {
                        Box(Modifier.width3().height(18.dp).background(c.userInk.copy(alpha = 0.4f)))
                        Spacer(Modifier.size(8.dp))
                        Text(quote, color = c.userInk.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    body + (if ((item.images ?: 0) > 0 && item.attachments.isEmpty()) "  [图片×${item.images}]" else ""),
                    color = c.userInk, fontSize = 16.sp, lineHeight = 22.sp,
                )
            }
          }
        }
        "bot" -> BotBubble(item.text.orEmpty(), onLongPress = onLongPress)
        else -> {
            val label = when (item.role) {
                "request" -> "${item.name ?: "其他助手"} 发来请求"
                "reply" -> "${item.name ?: "其他助手"} 回复了"
                "report" -> "${item.name ?: "其他助手"} 汇报了进展"
                "stopped" -> "${item.name ?: "助手"} 已停止"
                "done" -> "${item.name ?: "助手"} 完成了交代的事"
                "lookup" -> "${item.name ?: "助手"} 翻看了自己的聊天"
                "resumed" -> "${item.name ?: "助手"} 的工作被电脑重启打断，正在继续"
                "cut" -> "${item.name ?: "助手"} 的工作再次被重启打断，已停下"
                "failed" -> "${item.name ?: "助手"} 这次没能回复，请稍后再试，或在电脑上查看原因"
                "approval" -> "${item.name ?: "助手"} 在等你确认"
                "working" -> "${item.name ?: "助手"} 还在忙，做完会回到群里"
                "missed" -> "${item.name ?: "助手"} 没赶上这一轮"
                "quiet" -> "这一轮没有成员回复"
                else -> null
            }
            if (label != null) Text(
                label, color = c.ink3, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
        }
    }
}

private fun Modifier.width3(): Modifier = this.then(Modifier.size(width = 3.dp, height = 18.dp))

/** A member's message in a group chat: name above the first bubble of a run, small avatar at the bottom-left of the last. */
@Composable
private fun MemberBubble(
    name: String, botId: String?, text: String, color: String?, showName: Boolean, showAvatar: Boolean,
    onLongPress: (() -> Unit)? = null,
) {
    val c = LocalDs.current
    Column(Modifier.fillMaxWidth().padding(top = if (showName) 8.dp else 0.dp)) {
        if (showName) Text(name, color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(start = 34.dp, bottom = 4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.BottomStart) {
                if (showAvatar) Avatar(name, 20, color, key = botId ?: name)
            }
            Spacer(Modifier.size(10.dp))
            Box(Modifier.weight(1f)) { BotBubble(text, maxFraction = 0.88f, onLongPress = onLongPress) }
        }
    }
}

@Composable
private fun BreathingDots() {
    val c = LocalDs.current
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200)), label = "p")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val on = (p.toInt() % 3) == i
            Box(Modifier.size(5.dp).clip(CircleShape).background(c.link.copy(alpha = if (on) 0.95f else 0.35f)))
        }
    }
}

/** No thinking card: one gray line with the small avatar, 「X 正在工作」 (or dots + 「X 正在输入…」 in a group). */
@Composable
private fun WorkingLine(names: List<String>, key: String?, color: String?, inRoom: Boolean) {
    val c = LocalDs.current
    val t = rememberInfiniteTransition(label = "work")
    val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (inRoom) BreathingDots() else Avatar(names.firstOrNull() ?: "助手", 20, color, key = key ?: names.firstOrNull())
        Spacer(Modifier.size(8.dp))
        Text(Look.workingLabel(names, inRoom), color = c.ink3.copy(alpha = if (inRoom) 1f else a), fontSize = 14.sp)
    }
}

/** Waiting for a reply: the working line, the reply in a bubble once it streams, a note if it failed. */
@Composable
fun WaitCard(wait: WaitState, onDismiss: () -> Unit, bot: BotInfo? = null, inRoom: Boolean = false, hideWorking: Boolean = false) {
    val c = LocalDs.current
    when (wait.phase) {
        WaitPhase.Streaming -> if (wait.text.isNotBlank()) {
            BotBubble(wait.text)
            return
        }
        WaitPhase.Failed -> {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(c.danger.copy(alpha = 0.08f)).clickable(onClick = onDismiss).padding(14.dp),
            ) {
                Text(wait.error ?: "发送失败", color = c.danger, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text("点击关闭", color = c.ink3, fontSize = 13.sp)
            }
            return
        }
        else -> Unit
    }
    if (!hideWorking) WorkingLine(listOfNotNull(bot?.name), bot?.id, bot?.color, inRoom)
}

/** Input bar: round + on the left (files / camera / pictures); white pill with hint and mic; black round button: voice, send, or stop. */
@Composable
private fun Composer(
    text: String, sending: Boolean, onChange: (String) -> Unit, onSend: () -> Unit,
    hint: String,
    showStop: Boolean = false, stopping: Boolean = false, onStop: () -> Unit = {}, onVoice: () -> Unit = {},
    onAttach: (String) -> Unit = {},
    hasAttachments: Boolean = false,
    uploading: Boolean = false,
) {
    val c = LocalDs.current
    val canSend = (text.isNotBlank() || hasAttachments) && !uploading && !sending
    val stopMode = showStop && text.isBlank() && !hasAttachments && !sending
    var plus by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.padding(bottom = 2.dp)) {
            Box(
                Modifier.size(40.dp).shadow(if (c.dark) 0.dp else 2.dp, CircleShape, clip = false).clip(CircleShape).background(c.card)
                    .clickable { plus = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, "添加", tint = c.ink, modifier = Modifier.size(22.dp)) }
            DropdownMenu(expanded = plus, onDismissRequest = { plus = false }) {
                MenuRow(Icons.Outlined.Folder, "选择文件") { plus = false; onAttach("file") }
                MenuRow(Icons.Outlined.PhotoCamera, "拍照") { plus = false; onAttach("camera") }
                MenuRow(Icons.Outlined.Image, "附加图片") { plus = false; onAttach("image") }
            }
        }
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier.weight(1f).shadow(if (c.dark) 0.dp else 2.dp, RoundedCornerShape(22.dp), clip = false)
                .clip(RoundedCornerShape(22.dp)).background(c.input)
                .then(if (c.dark) Modifier.border(1.dp, c.line, RoundedCornerShape(22.dp)) else Modifier)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (text.isEmpty()) Text(hint, color = c.ink3, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    text, onChange, maxLines = 6,
                    textStyle = TextStyle(color = c.ink, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(c.ink),
                    // Newline on Enter: sending only via the button, so there is a single send path.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (text.isEmpty() && !sending && !hasAttachments) Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onVoice),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Mic, "说话输入", tint = c.ink3, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.size(4.dp))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(c.user).clickable(
                    enabled = !sending && !(stopMode && stopping),
                    onClick = when { stopMode -> onStop; canSend -> onSend; else -> onVoice },
                ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    sending || (stopMode && stopping) -> CircularProgressIndicator(Modifier.size(14.dp), color = c.userInk, strokeWidth = 2.dp)
                    stopMode -> Icon(Icons.Filled.Stop, "停止", tint = c.userInk, modifier = Modifier.size(18.dp))
                    canSend -> Icon(Icons.Filled.ArrowUpward, "发送", tint = c.userInk, modifier = Modifier.size(20.dp))
                    else -> Icon(Icons.Filled.GraphicEq, "语音", tint = c.userInk, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    val c = LocalDs.current
    Box(Modifier.fillMaxSize().background(c.bg))
}
