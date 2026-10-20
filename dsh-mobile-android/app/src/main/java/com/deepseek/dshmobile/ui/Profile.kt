package com.deepseek.dshmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dshmobile.data.Characters
import com.deepseek.dshmobile.data.Look
import com.deepseek.dshmobile.data.ProfileLogic

@Composable
private fun TopRow(back: Boolean = true, onClose: () -> Unit, title: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = LocalDs.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(if (back) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, if (back) "返回" else "关闭", onClick = onClose)
        if (title != null) {
            Spacer(Modifier.size(12.dp))
            Text(title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** A one-line text that saves when the user leaves it or presses done. */
@Composable
private fun InlineField(value: String, placeholder: String, big: Boolean, onSave: (String) -> Unit) {
    val c = LocalDs.current
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val style = TextStyle(color = if (big) c.ink else c.ink2, fontSize = if (big) 22.sp else 15.sp,
        fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (big) 16.dp else 14.dp), contentAlignment = Alignment.Center) {
        if (text.isEmpty()) Text(placeholder, color = c.ink3, fontSize = style.fontSize, textAlign = TextAlign.Center)
        BasicTextField(
            text, { text = it }, singleLine = true, textStyle = style, cursorBrush = SolidColor(c.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text != value) onSave(text) }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                if (focused && !f.isFocused && text != value) onSave(text)
                focused = f.isFocused
            },
        )
    }
}

@Composable
fun BotProfileScreen(
    state: UiState,
    onBack: () -> Unit,
    onShape: (String) -> Unit,
    onColor: (String) -> Unit,
    onReset: () -> Unit,
    onText: (String, String) -> Unit,
    onMute: () -> Unit,
    onModel: (String) -> Unit = {},
) {
    val c = LocalDs.current
    val p = state.profile ?: return
    var editInstructions by remember { mutableStateOf(false) }
    var editModel by remember { mutableStateOf(false) }
    if (editInstructions) {
        InstructionsEditor(p.name, p.instructions, state.saving, onClose = { editInstructions = false }) { onText("instructions", it); editInstructions = false }
        return
    }
    if (editModel) {
        ModelPicker(
            models = state.options?.models.orEmpty(),
            current = p.model,
            saving = state.saving,
            onClose = { editModel = false },
            onPick = { onModel(it); editModel = false },
        )
        return
    }
    val (shape, color) = Look.lookOf(p.id, p.shape, p.color)
    val muted = "bot:${p.id}" in state.prefs.muted
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopRow(onClose = onBack) { if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), color = c.ink3, strokeWidth = 2.dp) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                Box {
                    ShapeAvatar(p.id, color, 112, shape = shape)
                    if (p.main) Box(Modifier.align(Alignment.BottomEnd).size(28.dp).clip(CircleShape).background(c.bg).padding(3.dp).clip(CircleShape)
                        .background(Color(0xFFFF9F0A)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Star, "主助手", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
            SheetCard {
                InlineField(p.name, "名字", big = true) { onText("name", it) }
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(c.line))
                InlineField(p.role, "标题（可选）", big = false) { onText("role", it) }
            }
            state.formError?.let { Text(it, color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) }
            GroupLabel("形象")
            SheetCard {
                Column(Modifier.padding(12.dp)) {
                    Characters.ALL.map { it.id }.chunked(5).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { id ->
                                val on = id == shape
                                Box(Modifier.size(56.dp).clip(CircleShape).then(if (on) Modifier.border(2.dp, c.ink3, CircleShape) else Modifier)
                                    .clickable { if (!on) onShape(id) }, contentAlignment = Alignment.Center) {
                                    ShapeAvatar(p.id, color, 40, shape = id)
                                }
                            }
                            repeat(5 - row.size) { Spacer(Modifier.size(56.dp)) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Look.PICKER_COLORS.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { id ->
                                val on = id == color
                                Box(Modifier.size(46.dp).clip(CircleShape).then(if (on) Modifier.border(2.dp, c.ink3, CircleShape) else Modifier)
                                    .clickable { if (!on) onColor(id) }, contentAlignment = Alignment.Center) {
                                    val fill = if (id == "aurora") Modifier.background(Brush.linearGradient(listOf(Color(0xFFA4FFB0), Color(0xFF00F0E4), Color(0xFF2E9EF7))))
                                    else Modifier.background(inkColor(id) ?: c.ink)
                                    Box(Modifier.size(32.dp).clip(CircleShape).then(fill))
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Text("重置为默认", color = c.link, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onReset).padding(16.dp))
            }
            Text("这个助手在手机和电脑上都显示成这样", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            Spacer(Modifier.height(12.dp))
            SheetCard {
                val modelLabel = state.options?.models?.find { it.ref == p.model }?.name
                    ?: p.model?.substringAfterLast('/')?.ifBlank { null }
                    ?: "未选择"
                SheetRow(null, "模型", trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(modelLabel, color = c.ink3, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.ink3)
                    }
                }) { editModel = true }
            }
            Text("正在使用的模型，换了之后下一句话就会用新的", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            Spacer(Modifier.height(12.dp))
            SheetCard {
                SheetRow(Icons.Outlined.Description, "指令", trailing = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.ink3)
                }) { editInstructions = true }
            }
            Spacer(Modifier.height(12.dp))
            SheetCard {
                SheetRow(null, "通知", trailing = {
                    Switch(!muted, { onMute() }, colors = SwitchDefaults.colors(checkedThumbColor = c.bg, checkedTrackColor = c.ink, uncheckedThumbColor = c.ink3, uncheckedTrackColor = c.group, uncheckedBorderColor = c.line))
                }, onClick = onMute)
            }
            Text(if (muted) "已关闭：新消息数字显示为灰色" else "新消息会在列表里显示数字", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<com.deepseek.dshmobile.data.ModelOption>,
    current: String?,
    saving: Boolean,
    onClose: () -> Unit,
    onPick: (String) -> Unit,
) {
    val c = LocalDs.current
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopRow(back = false, onClose = onClose, title = "模型")
        Text("选一个这个助手要用的模型。换了之后，下一句话就会用新的。", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (models.isEmpty()) {
            Text("电脑上还没有可用的模型，请先在电脑上添加", color = c.ink3, fontSize = 15.sp, modifier = Modifier.padding(20.dp))
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
                Spacer(Modifier.height(8.dp))
                SheetCard {
                    models.forEachIndexed { i, m ->
                        val on = m.ref == current
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).clickable(enabled = !saving && !on) { onPick(m.ref) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, color = c.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = listOfNotNull(m.providerName, if (on) "正在使用" else null).joinToString(" · ")
                                if (sub.isNotBlank()) Text(sub, color = if (on) c.accent else c.ink3, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (on) Icon(Icons.Filled.Check, "正在使用", tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                        if (i < models.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(c.line))
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun InstructionsEditor(name: String, value: String, saving: Boolean, onClose: () -> Unit, onSave: (String) -> Unit) {
    val c = LocalDs.current
    var text by remember { mutableStateOf(value) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopRow(back = false, onClose = onClose, title = "指令") {
            Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (text != value) c.ink else c.group)
                .clickable(enabled = text != value && !saving) { onSave(text) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text("保存", color = if (text != value) c.bg else c.ink3, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("告诉 $name 该怎么做事，比如说话的语气、要注意的事", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Box(Modifier.fillMaxSize().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(c.group).padding(16.dp)) {
            if (text.isEmpty()) Text("还没有写指令", color = c.ink3, fontSize = 16.sp)
            BasicTextField(text, { text = it }, textStyle = TextStyle(color = c.ink, fontSize = 16.sp, lineHeight = 24.sp), cursorBrush = SolidColor(c.ink),
                modifier = Modifier.fillMaxSize().focusRequester(focus))
        }
    }
}

@Composable
fun GroupInfoScreen(
    state: UiState, onBack: () -> Unit, onMember: (String) -> Unit, onAdd: () -> Unit, onMute: () -> Unit,
    onRemove: (String) -> Unit = {}, onRename: (String) -> Unit = {},
) {
    val c = LocalDs.current
    val room = state.selectedRoom ?: return
    var removing by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val muted = "room:${room.id}" in state.prefs.muted
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopRow(onClose = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                RoomShapeAvatar(room.members.map { id -> id to state.bots.find { it.id == id }?.color }.ifEmpty { listOf(room.id to null) }, 104)
            }
            SheetCard {
                Text(room.name, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { renaming = true }.padding(16.dp))
            }
            Spacer(Modifier.height(12.dp))
            SheetCard { SheetRow(null, "修改群名称", onClick = { renaming = true }) }
            state.formError?.let { Text(it, color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) }
            GroupLabel("成员 · ${room.members.size}")
            SheetCard {
                room.members.forEachIndexed { i, id ->
                    val bot = state.bots.find { it.id == id }
                    Row(Modifier.fillMaxWidth().height(56.dp).clickable { onMember(id) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ShapeAvatar(id, bot?.color, 30)
                        Spacer(Modifier.size(12.dp))
                        Text(bot?.name ?: "助手", color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (room.admin == id) Text("管理员", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                        else if (room.members.size > 1) Text("移除", color = c.danger, fontSize = 14.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { removing = id }.padding(horizontal = 10.dp, vertical = 6.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.ink3)
                    }
                    if (i < room.members.size) Box(Modifier.fillMaxWidth().padding(start = 58.dp).height(1.dp).background(c.line))
                }
                Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onAdd).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PersonAddAlt, null, tint = c.link, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.size(14.dp))
                    Text("添加成员", color = c.link, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            SheetCard {
                SheetRow(null, "通知", trailing = {
                    Switch(!muted, { onMute() }, colors = SwitchDefaults.colors(checkedThumbColor = c.bg, checkedTrackColor = c.ink, uncheckedThumbColor = c.ink3, uncheckedTrackColor = c.group, uncheckedBorderColor = c.line))
                }, onClick = onMute)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    removing?.let { id ->
        val who = state.bots.find { it.id == id }?.name ?: "这个助手"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除成员") },
            text = { Text("把「$who」移出「${room.name}」？它不会再收到群里的消息。") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { removing = null; onRemove(id) }) { Text("移除", color = c.danger) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removing = null }) { Text("取消", color = c.ink) } },
            containerColor = c.card,
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(room.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("修改群名称") },
            text = {
                androidx.compose.material3.OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = name.isNotBlank() && name.trim() != room.name, onClick = { renaming = false; onRename(name) }) { Text("保存", color = c.ink) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = false }) { Text("取消", color = c.ink) } },
            containerColor = c.card,
        )
    }
}

@Composable
fun AddMemberScreen(state: UiState, onClose: () -> Unit, onToggle: (String) -> Unit, onAdd: () -> Unit) {
    val c = LocalDs.current
    val room = state.selectedRoom ?: return
    var q by remember { mutableStateOf("") }
    val list = ProfileLogic.addable(state.bots, room.members, q)
    val can = state.addPicked.isNotEmpty() && !state.saving
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopRow(back = false, onClose = onClose, title = "添加成员") {
            Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (can) c.ink else c.ink3.copy(alpha = 0.5f))
                .clickable(enabled = can, onClick = onAdd).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                if (state.saving) CircularProgressIndicator(Modifier.size(16.dp), color = c.bg, strokeWidth = 2.dp)
                else Text("添加", color = c.bg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(48.dp).shadow(if (c.dark) 0.dp else 2.dp, RoundedCornerShape(24.dp), clip = false)
            .clip(RoundedCornerShape(24.dp)).background(c.card).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("添加：", color = c.ink3, fontSize = 15.sp)
            Spacer(Modifier.size(8.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("搜索助手", color = c.ink3, fontSize = 15.sp)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = c.ink, fontSize = 15.sp), cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth())
            }
        }
        state.formError?.let { Text(it, color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        if (list.isEmpty()) Text(if (q.isBlank()) "所有助手都已经在群里了" else "没有找到「${q.trim()}」", color = c.ink3, fontSize = 15.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 48.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(list, key = { it.id }) { b ->
                val on = b.id in state.addPicked
                Row(Modifier.fillMaxWidth().height(60.dp).clickable { onToggle(b.id) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShapeAvatar(b.id, b.color, 36)
                    Spacer(Modifier.size(14.dp))
                    Text(b.name, color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.size(24.dp).clip(CircleShape).then(if (on) Modifier.background(c.ink) else Modifier.border(1.5.dp, c.line, CircleShape)),
                        contentAlignment = Alignment.Center) { if (on) Icon(Icons.Filled.Check, "已选", tint = c.bg, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(state: UiState, onClose: () -> Unit, onQuery: (String) -> Unit, onOpen: (com.deepseek.dshmobile.data.SearchHit) -> Unit) {
    val c = LocalDs.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val chats = com.deepseek.dshmobile.data.ChatPrefsLogic.apply(state.chats, state.prefs)
    val rows = ProfileLogic.results(chats, state.bots, state.searchQuery, state.searchHits)
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Filled.Close, "关闭", onClick = onClose)
            Spacer(Modifier.size(10.dp))
            Row(Modifier.weight(1f).height(48.dp).shadow(if (c.dark) 0.dp else 2.dp, RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp)).background(c.card).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = c.ink3, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Box(Modifier.weight(1f)) {
                    if (state.searchQuery.isEmpty()) Text("搜索", color = c.ink3, fontSize = 16.sp)
                    BasicTextField(state.searchQuery, onQuery, singleLine = true, textStyle = TextStyle(color = c.ink, fontSize = 16.sp),
                        cursorBrush = SolidColor(c.ink), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
                if (state.searchingMessages) CircularProgressIndicator(Modifier.size(16.dp), color = c.ink3, strokeWidth = 2.dp)
            }
        }
        if (rows.isEmpty() && state.searchQuery.isNotBlank() && !state.searchingMessages) {
            Text("没有找到「${state.searchQuery.trim()}」", color = c.ink3, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 48.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(rows) { hit ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(hit) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hit.kind == "room") {
                        val room = state.rooms.find { it.id == hit.id }
                        RoomShapeAvatar((room?.members ?: emptyList()).map { id -> id to state.bots.find { it.id == id }?.color }.ifEmpty { listOf(hit.id to null) }, 48)
                    } else ShapeAvatar(hit.id, state.bots.find { it.id == hit.id }?.color, 48)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(hit.name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(ProfileLogic.tag(hit), color = c.ink3, fontSize = 13.sp)
                        }
                        val body = Look.plainPreview(hit.text)
                        val sub = if (hit.message && hit.who != null) "${hit.who}：$body" else body
                        if (sub.isNotBlank()) Text(sub, color = c.ink3, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
