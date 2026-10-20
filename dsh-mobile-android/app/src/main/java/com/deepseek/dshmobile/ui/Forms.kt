package com.deepseek.dshmobile.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dshmobile.data.BotRules
import com.deepseek.dshmobile.data.NewBotDraft
import com.deepseek.dshmobile.data.RoomRules

// ---------- shared form pieces ----------

@Composable
private fun Label(text: String, hint: String? = null) {
    val c = LocalDs.current
    Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(text, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        hint?.let { Text(it, color = c.ink3, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalDs.current
    Row(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (on) c.user else c.surface)
            .border(1.dp, if (on) c.user else c.line, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.size(6.dp)) }
        Text(text, color = if (on) c.userInk else if (enabled) c.ink else c.ink3, fontSize = 14.sp)
    }
}

@Composable
private fun MultiField(value: String, onChange: (String) -> Unit, placeholder: String, minHeight: Int = 96) {
    val c = LocalDs.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val style = TextStyle(color = c.ink, fontSize = 15.sp, lineHeight = 21.sp)
        if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.ink3))
        BasicTextField(value, onChange, textStyle = style, cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FormFoot(primary: String, enabled: Boolean, busy: Boolean, error: String?, onCancel: () -> Unit, onGo: () -> Unit) {
    val c = LocalDs.current
    Column(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        error?.let {
            Text(it, color = c.danger, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(25.dp)).border(1.dp, c.line, RoundedCornerShape(25.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text("取消", color = c.ink, fontSize = 16.sp) }
            Box(Modifier.weight(1f)) { PrimaryButton(primary, enabled = enabled, busy = busy, onClick = onGo) }
        }
    }
}

private fun decodeDataUrl(url: String) = runCatching {
    val bytes = Base64.decode(url.substringAfter(","), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

// ---------- new Bot ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewBotScreen(
    state: UiState,
    onChange: ((NewBotDraft) -> NewBotDraft) -> Unit,
    onPickPhoto: () -> Unit,
    onBrowse: (String) -> Unit,
    onCloseBrowse: () -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalDs.current
    val d = state.botDraft
    val opts = state.options
    var more by rememberSaveable { mutableStateOf(false) }
    val taken = state.bots.map { it.name }
    val fallback = BotRules.freshName(taken)
    val finalName = d.name.trim().ifEmpty { fallback }
    val problem = BotRules.problem(finalName, taken)
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar("新建助手", onBack = onCancel)
        if (opts == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.optionsLoading) CircularProgressIndicator(Modifier.size(24.dp), color = c.ink3, strokeWidth = 2.dp)
                else Text(state.formError ?: "暂时读不到电脑上的设置", color = c.ink3, fontSize = 14.sp)
            }
            FormFoot("创建", false, false, null, onCancel) {}
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            // preview
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val image = remember(d.image) { d.image?.let(::decodeDataUrl) }
                if (image != null) {
                    Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp).clip(CircleShape))
                } else Avatar(finalName, 88, d.color.ifBlank { null })
                Spacer(Modifier.height(8.dp))
                Text(finalName, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                opts.shapes.find { it.id == d.shape }?.let { Text("形象：${it.label}", color = c.ink3, fontSize = 12.sp) }
            }

            Label("形象")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                opts.shapes.forEach { s -> Chip(s.label, on = d.shape == s.id && d.image == null) { onChange { it.copy(shape = s.id, image = null) } } }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                opts.colors.forEach { col ->
                    val tint = inkColor(col.id) ?: col.hex?.let { inkColor(it) } ?: c.bubble
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(tint)
                            .border(2.dp, if (d.color == col.id) c.ink else Color.Transparent, CircleShape)
                            .clickable { onChange { it.copy(color = col.id, image = null) } },
                        contentAlignment = Alignment.Center,
                    ) { if (d.color == col.id) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("换一个", on = false) {
                    onChange { cur ->
                        val shapes = opts.shapes.map { it.id }.filter { it != cur.shape && it != "whale" }
                        val colors = opts.colors.map { it.id }.filter { it != cur.color && it !in listOf("black", "gray", "brown", "charcoal", "graphite") }
                        cur.copy(shape = shapes.randomOrNull() ?: cur.shape, color = colors.randomOrNull() ?: cur.color, image = null)
                    }
                }
                Chip(if (d.image != null) "换照片" else "上传照片", on = false, onClick = onPickPhoto)
                if (d.image != null) Chip("移除照片", on = false) { onChange { it.copy(image = null) } }
            }

            Label("名字")
            Field(d.name, { v -> onChange { it.copy(name = v.take(opts.nameMax)) } }, fallback)
            if (d.name.isNotBlank() && problem != null) Text(problem, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

            Label("模型", if (opts.models.isEmpty()) "电脑上还没有可用的模型，请先在电脑上添加" else "助手用这个模型工作，之后可以在电脑上改")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                opts.models.forEach { m ->
                    val on = d.model == m.ref
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) c.surface else c.bg)
                            .border(1.dp, if (on) c.ink else c.line, RoundedCornerShape(12.dp))
                            .clickable { onChange { it.copy(model = m.ref) } }.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = c.ink, fontSize = 15.sp)
                            val sub = listOfNotNull(m.providerName, if (m.needsKey) "需要先在电脑上完成设置" else null).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, color = if (m.needsKey) c.danger else c.ink3, fontSize = 12.sp)
                        }
                        if (on) Icon(Icons.Filled.Check, null, tint = c.ink, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (state.efforts.isNotEmpty()) {
                Label("思考强度")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("默认", on = d.effort.isBlank()) { onChange { it.copy(effort = "") } }
                    state.efforts.forEach { e -> Chip(e.name, on = d.effort == e.id) { onChange { it.copy(effort = e.id) } } }
                }
            }

            Text(
                if (more) "收起更多设置" else "更多设置（职责、工作说明、文件夹、工作方式）",
                color = c.accent, fontSize = 14.sp,
                modifier = Modifier.padding(top = 22.dp).clickable { more = !more }.padding(vertical = 6.dp),
            )
            AnimatedVisibility(more) {
                Column {
                    Label("职责", "一句话说明它负责什么")
                    Field(d.role, { v -> onChange { it.copy(role = v.take(opts.roleMax)) } }, "例如：资料整理")

                    Label("工作说明", "希望它怎么做事，会一直记着")
                    MultiField(d.instructions, { v -> onChange { it.copy(instructions = v) } }, "例如：回答用中文，先说结论再说理由")

                    Label("工作文件夹", "它在电脑上的哪个文件夹里干活")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("默认", on = d.workDir.isBlank()) { onChange { it.copy(workDir = "") } }
                        opts.folders.forEach { f -> Chip(f.title, on = d.workDir == f.path) { onChange { it.copy(workDir = f.path) } } }
                        if (opts.canBrowse) Chip("浏览…", on = false) { onBrowse(d.workDir.ifBlank { opts.teamFolder ?: "" }) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Field(d.workDir, { v -> onChange { it.copy(workDir = v) } }, "或填写文件夹的完整路径")

                    opts.agentPresets?.takeIf { it.isNotEmpty() }?.let { presets ->
                        Label("工作方式", "新对话按这种方式开始")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("默认", on = d.agentPreset.isBlank()) { onChange { it.copy(agentPreset = "") } }
                            presets.forEach { p -> Chip(p.label, on = d.agentPreset == p.id) { onChange { it.copy(agentPreset = p.id) } } }
                        }
                    }

                    Label("第一句话（可选）", "创建后它会先回应这句话")
                    MultiField(d.brief, { v -> onChange { it.copy(brief = v) } }, "例如：帮我整理这周的会议记录", minHeight = 64)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        FormFoot(if (state.creating) "创建中…" else "创建", enabled = problem == null && !state.creating, busy = state.creating, error = state.formError, onCancel = onCancel, onGo = onCreate)
    }

    state.folderListing?.let { listing ->
        AlertDialog(
            onDismissRequest = onCloseBrowse,
            title = { Text(listing.path, fontSize = 14.sp, maxLines = 2) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    val parent = listing.path.trimEnd('/').substringBeforeLast('/', "")
                    if (parent.isNotEmpty() || listing.path.count { it == '/' } > 1) {
                        Text("上一级", color = c.accent, modifier = Modifier.fillMaxWidth().clickable { onBrowse(parent.ifEmpty { "/" }) }.padding(vertical = 10.dp))
                    }
                    if (listing.entries.isEmpty()) Text("里面没有文件夹", color = c.ink3, modifier = Modifier.padding(vertical = 10.dp))
                    listing.entries.forEach { e ->
                        Text(e.title, color = c.ink, modifier = Modifier.fillMaxWidth().clickable { onBrowse(e.path) }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onChange { it.copy(workDir = listing.path) }; onCloseBrowse() }) { Text("使用这个文件夹") } },
            dismissButton = { TextButton(onClick = onCloseBrowse) { Text("取消") } },
        )
    }
}

// ---------- new group chat ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewRoomScreen(
    state: UiState,
    onPickAdmin: (String) -> Unit,
    onToggle: (String) -> Unit,
    onName: (String) -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalDs.current
    val d = state.roomDraft
    val max = state.options?.roomMaxMembers ?: 12
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar("新建群聊", onBack = onCancel)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (state.bots.size < 2) {
                Text("请先添加一个助手：群聊至少要两个。", color = c.ink3, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp))
            }
            Label("选择群管理员", "管理员可以用 @名字 点名成员，也能调整这个群")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.bots.forEach { b ->
                    Chip(b.name, on = d.admin == b.id, leading = if (d.admin == b.id) ({ Icon(Icons.Filled.Star, null, tint = c.userInk, modifier = Modifier.size(14.dp)) }) else null) { onPickAdmin(b.id) }
                }
            }
            if (d.admin != null) {
                Label("想和谁聊…", "至少再选一位，一个群最多 $max 个助手")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.bots.filter { it.id != d.admin }.forEach { b ->
                        Chip(b.name, on = b.id in d.members, leading = if (b.id in d.members) ({ Icon(Icons.Filled.Check, null, tint = c.userInk, modifier = Modifier.size(14.dp)) }) else null) { onToggle(b.id) }
                    }
                }
            }
            if (d.admin != null && d.members.isNotEmpty()) {
                Label("群名")
                Field(d.name, { onName(it.take(state.options?.roomNameMax ?: 60)) }, "按成员命名")
            }
            Spacer(Modifier.height(24.dp))
        }
        FormFoot(
            if (state.creating) "创建中…" else "新建群聊",
            enabled = RoomRules.canCreate(d, max) && !state.creating, busy = state.creating,
            error = state.formError, onCancel = onCancel, onGo = onCreate,
        )
    }
}
