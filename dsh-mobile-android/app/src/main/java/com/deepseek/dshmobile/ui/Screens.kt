package com.deepseek.dshmobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dshmobile.data.BotInfo
import com.deepseek.dshmobile.data.ChatItem
import com.deepseek.dshmobile.data.LiveReply
import com.deepseek.dshmobile.data.RoomInfo
import com.deepseek.dshmobile.data.RoomStreams
import com.deepseek.dshmobile.data.WaitPhase
import com.deepseek.dshmobile.data.WaitState

// ---------- shared pieces ----------

@Composable
internal fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    val c = LocalDs.current
    Column(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = c.ink) }
            } else Spacer(Modifier.size(12.dp))
            Text(
                title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
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
        modifier = Modifier.fillMaxWidth().background(c.surface).padding(vertical = 8.dp, horizontal = 16.dp),
    )
}

@Composable
internal fun Avatar(name: String, size: Int = 40, color: String? = null) {
    val c = LocalDs.current
    val tint = inkColor(color)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(tint?.copy(alpha = 0.18f) ?: c.bubble),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase(), color = tint ?: c.ink, fontWeight = FontWeight.SemiBold, fontSize = (size * 0.42).sp)
    }
}

/** Two overlapping initials for a group chat. */
@Composable
internal fun RoomAvatar(names: List<String>, size: Int = 44) {
    val c = LocalDs.current
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(c.bubble), contentAlignment = Alignment.Center) {
        Text(
            names.take(2).joinToString("") { it.trim().take(1).uppercase() }.ifEmpty { "群" },
            color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = (size * 0.32).sp,
        )
    }
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
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
            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------- pairing ----------

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
    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Text("连接到电脑", color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "在电脑上打开 DS Bot，点左下角「手机配对」，\n然后扫描二维码或输入 6 位配对码。",
            color = c.ink2, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 21.sp,
        )
        state.pairReason?.let {
            Spacer(Modifier.height(18.dp))
            Text(it, color = c.danger, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(36.dp))
        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(c.user).clickable(onClick = onScan),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.QrCodeScanner, null, tint = c.userInk, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("扫码配对", color = c.userInk, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("或输入配对码", color = c.ink3, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Field(
            state.pairInput, onPairInputChange, "6 位配对码",
            KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done), big = true,
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp))
                .border(1.dp, c.line, RoundedCornerShape(25.dp))
                .clickable(enabled = !state.busy, onClick = onPair),
            contentAlignment = Alignment.Center,
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), color = c.ink, strokeWidth = 2.dp)
            else Text("配对", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        state.notice?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = c.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (manual) "收起服务器地址" else "手动填写服务器地址",
            color = c.ink3, fontSize = 13.sp,
            modifier = Modifier.clickable { manual = !manual }.padding(8.dp),
        )
        AnimatedVisibility(manual) {
            Column {
                Field(state.baseUrl, onBaseUrlChange, "例如 https://example.com:8443",
                    KeyboardOptions(keyboardType = KeyboardType.Uri))
                Spacer(Modifier.height(6.dp))
                Text("扫码会自动填好，一般不需要手动填写。", color = c.ink3, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ---------- bot list ----------

@Composable
fun TeamScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpen: (BotInfo) -> Unit,
    onLogout: () -> Unit,
    onOpenRoom: (RoomInfo) -> Unit = {},
    onNewBot: () -> Unit = {},
    onNewRoom: () -> Unit = {},
) {
    val c = LocalDs.current
    var menu by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar("助手") {
            Box {
                IconButton(onClick = { add = true }) { Icon(Icons.Filled.Add, "新建", tint = c.ink) }
                DropdownMenu(expanded = add, onDismissRequest = { add = false }) {
                    DropdownMenuItem(text = { Text("新建助手") }, onClick = { add = false; onNewBot() })
                    DropdownMenuItem(
                        text = { Text("新建群聊", color = if (state.bots.size >= 2) Color.Unspecified else c.ink3) },
                        enabled = state.bots.size >= 2,
                        onClick = { add = false; onNewRoom() },
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多", tint = c.ink2) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("刷新") }, onClick = { menu = false; onRefresh() })
                    DropdownMenuItem(text = { Text("取消配对", color = c.danger) }, onClick = { menu = false; onLogout() })
                }
            }
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
                Text("还没有助手", color = c.ink3, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("新建助手", onClick = onNewBot)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (state.rooms.isNotEmpty()) {
                item(key = "h-rooms") { SectionLabel("群聊") }
                items(state.rooms, key = { "room-" + it.id }) { room ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenRoom(room) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoomAvatar(room.memberNames)
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(room.name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                room.preview ?: room.memberNames.joinToString("、"),
                                color = c.ink3, fontSize = 13.sp, maxLines = 1,
                            )
                        }
                    }
                }
                item(key = "h-bots") { SectionLabel("助手") }
            }
            items(state.bots, key = { it.id }) { bot ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(bot) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(bot.name, 44, bot.color)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(bot.name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            if (bot.main) {
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    "主助手", color = c.ink2, fontSize = 11.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.tagBg)
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                        bot.role?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(it, color = c.ink3, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    val c = LocalDs.current
    Text(text, color = c.ink3, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
}

// ---------- chat ----------

@Composable
fun ChatScreen(
    state: UiState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onComposeChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismissWait: () -> Unit,
) {
    val c = LocalDs.current
    val list = rememberLazyListState()
    val liveShown = state.live.mapNotNull { r -> RoomStreams.shown(r)?.let { r to it } }
    val count = state.messages.size + (if (state.wait.visible) 1 else 0) + liveShown.size
    LaunchedEffect(count, state.wait.text.length / 80, liveShown.sumOf { it.second.length } / 80) {
        val n = list.layoutInfo.totalItemsCount
        if (count > 0 && n > 0) list.animateScrollToItem(n - 1)
    }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar(state.chatTitle, onBack = onBack)
        state.selectedRoom?.takeIf { it.memberNames.isNotEmpty() }?.let {
            Text(
                it.memberNames.joinToString("、"), color = c.ink3, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (state.reconnecting) Banner("连接中断，正在重连…")
        state.notice?.let { Banner(it, danger = true) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
            items(state.messages) { MessageRow(it, colorOf = { id -> state.bots.find { b -> b.id == id }?.color }) }
            items(liveShown, key = { "live-" + it.first.attemptId }) { (reply, text) ->
                MemberBubble(reply.name ?: "助手", text, inkColor(state.bots.find { it.id == reply.botId }?.color), live = reply.endedAt == null)
            }
            val typing = state.live.filter { it.endedAt == null && RoomStreams.shown(it) == null }.mapNotNull { it.name }.distinct()
            if (typing.isNotEmpty() && liveShown.isEmpty()) item(key = "typing") {
                Text("${typing.joinToString("、")} 正在回复…", color = c.ink3, fontSize = 13.sp)
            }
            if (state.wait.visible && !(state.inRoom && state.wait.phase == WaitPhase.Streaming)) item(key = "wait") { WaitCard(state.wait, onDismissWait) }
        }
        Composer(state.composeText, state.sending, onComposeChange, onSend)
    }
}

@Composable
private fun MessageRow(item: ChatItem, colorOf: (String) -> String? = { null }) {
    val c = LocalDs.current
    when (item.kind) {
        "member" -> MemberBubble(
            item.name ?: "助手",
            item.text.orEmpty() + if (item.cut) "\n\n（内容较长，完整内容请在电脑上查看）" else "",
            inkColor(item.botId?.let(colorOf)),
        )
        "user", "answer" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                item.text.orEmpty() + (if ((item.images ?: 0) > 0) "  [图片×${item.images}]" else ""),
                color = c.userInk, fontSize = 16.sp, lineHeight = 22.sp,
                modifier = Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(c.user).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        "bot" -> MarkdownText(item.text.orEmpty(), Modifier.fillMaxWidth().padding(end = 12.dp))
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
                "approval" -> "${item.name ?: "助手"} 在等你在电脑上确认"
                "working" -> "${item.name ?: "助手"} 还在忙，做完会回到群里"
                "missed" -> "${item.name ?: "助手"} 没赶上这一轮"
                "quiet" -> "这一轮没有成员回复"
                else -> null
            }
            if (label != null) Text(
                label, color = c.ink3, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A member's message in a group chat: small avatar and name above the text. */
@Composable
private fun MemberBubble(name: String, text: String, tint: Color?, live: Boolean = false) {
    val c = LocalDs.current
    Column(Modifier.fillMaxWidth().padding(end = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, 22, null)
            Spacer(Modifier.size(6.dp))
            Text(name, color = tint ?: c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (live) {
                Spacer(Modifier.size(6.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
            }
        }
        Spacer(Modifier.height(4.dp))
        MarkdownText(text, Modifier.fillMaxWidth().padding(start = 28.dp))
    }
}

/** "Thinking" card with a light-sweep bar, like ds-bot desktop's wait card. */
@Composable
fun WaitCard(wait: WaitState, onDismiss: () -> Unit) {
    val c = LocalDs.current
    when (wait.phase) {
        WaitPhase.Streaming -> if (wait.text.isNotBlank()) {
            MarkdownText(wait.text, Modifier.fillMaxWidth().padding(end = 12.dp))
            return
        }
        WaitPhase.Failed -> {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(c.danger.copy(alpha = 0.08f)).clickable(onClick = onDismiss).padding(14.dp),
            ) {
                Text(wait.error ?: "发送失败", color = c.danger, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("点击关闭", color = c.ink3, fontSize = 12.sp)
            }
            return
        }
        else -> Unit
    }
    val t = rememberInfiniteTransition(label = "sweep")
    val x by t.animateFloat(
        initialValue = -0.4f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "x",
    )
    val dot by t.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot",
    )
    Column(
        Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent.copy(alpha = dot)))
            Spacer(Modifier.size(10.dp))
            Text("思考中", color = c.ink2, fontSize = 14.sp)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        (x - 0.25f).coerceIn(0f, 1f) to Color.Transparent,
                        x.coerceIn(0f, 1f) to c.accent,
                        (x + 0.25f).coerceIn(0f, 1f) to Color.Transparent,
                        1f to Color.Transparent,
                        startX = 0f, endX = Float.POSITIVE_INFINITY,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun Composer(text: String, sending: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    val c = LocalDs.current
    val canSend = text.isNotBlank() && !sending
    Row(
        Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (text.isEmpty()) Text("发消息…", color = c.ink3, fontSize = 16.sp)
            BasicTextField(
                text, onChange, maxLines = 6,
                textStyle = TextStyle(color = c.ink, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(c.accent),
                // Newline on Enter: sending only via the button, so there is a single send path.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                keyboardActions = KeyboardActions(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (canSend) c.user else c.bubble)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            if (sending) CircularProgressIndicator(Modifier.size(18.dp), color = c.ink3, strokeWidth = 2.dp)
            else Icon(Icons.Filled.ArrowUpward, "发送", tint = if (canSend) c.userInk else c.ink3)
        }
    }
}

@Composable
fun LoadingScreen() {
    val c = LocalDs.current
    Box(Modifier.fillMaxSize().background(c.bg))
}

