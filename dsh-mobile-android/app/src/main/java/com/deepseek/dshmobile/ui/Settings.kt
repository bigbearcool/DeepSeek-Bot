package com.deepseek.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.dshmobile.BuildConfig
import com.deepseek.dshmobile.data.ChatPrefs
import com.deepseek.dshmobile.data.ChatPrefsLogic

/** What the connection card says, in plain words. */
object ConnectionWords {
    fun status(paired: Boolean, reconnecting: Boolean, notice: String?, loaded: Boolean): Pair<String, Boolean> = when {
        !paired -> "没有连接电脑" to false
        reconnecting -> "正在重新连接…" to false
        notice != null && !loaded -> "暂时连不上电脑" to false
        else -> "已连接到电脑" to true
    }

    /** Only the computer's name or address, without the scheme, path or port noise. */
    fun host(baseUrl: String): String = baseUrl.substringAfter("://").substringBefore('/').ifBlank { "—" }
}

@Composable
fun SettingsScreen(
    state: UiState,
    onClose: () -> Unit,
    onTheme: (String) -> Unit,
    onUnhideAll: () -> Unit,
    onLogout: () -> Unit,
    onNotify: (Boolean) -> Unit = {},
    onAllowNotify: () -> Unit = {},
    onBattery: () -> Unit = {},
) {
    val c = LocalDs.current
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Filled.Close, "关闭", onClick = onClose)
            Spacer(Modifier.size(12.dp))
            Text("设置", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            val (words, ok) = ConnectionWords.status(state.paired, state.reconnecting, state.notice, state.teamLoaded)
            SheetCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) c.green else c.danger))
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(words, color = c.ink, fontSize = 16.sp)
                        if (state.paired) Text(com.deepseek.dshmobile.data.ProfileLogic.network(state.baseUrl), color = c.ink3, fontSize = 13.sp)
                    }
                }
            }
            GroupLabel("外观")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeTile(Icons.Outlined.Contrast, "系统", state.prefs.theme == ChatPrefs.THEME_SYSTEM, Modifier.weight(1f)) { onTheme(ChatPrefs.THEME_SYSTEM) }
                ThemeTile(Icons.Outlined.LightMode, "浅色", state.prefs.theme == ChatPrefs.THEME_LIGHT, Modifier.weight(1f)) { onTheme(ChatPrefs.THEME_LIGHT) }
                ThemeTile(Icons.Outlined.DarkMode, "深色", state.prefs.theme == ChatPrefs.THEME_DARK, Modifier.weight(1f)) { onTheme(ChatPrefs.THEME_DARK) }
            }
            GroupLabel("通知")
            SheetCard {
                SheetRow(null, "新消息提醒", trailing = {
                    androidx.compose.material3.Switch(state.prefs.notify, { onNotify(it) }, colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = c.bg, checkedTrackColor = c.ink, uncheckedThumbColor = c.ink3, uncheckedTrackColor = c.group, uncheckedBorderColor = c.line))
                }, onClick = { onNotify(!state.prefs.notify) })
                if (state.prefs.notify && !state.notifyAllowed) NotifyHint("手机还没允许 DS Bot 发通知，现在收不到提醒。", "去允许", onAllowNotify)
                if (state.prefs.notify && state.notifyAllowed && !state.batteryFree)
                    NotifyHint("有些手机为了省电会停掉后台连接。想在锁屏时也收到提醒，请让 DS Bot 不受省电限制。", "去设置", onBattery)
            }
            Text(
                if (state.prefs.notify) "电脑开着、手机连得上它时才会提醒。每个对话也可以在资料页单独关掉通知。"
                else "关掉后不会再弹出新消息和确认请求的提醒。",
                color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            GroupLabel("对话")
            val hidden = ChatPrefsLogic.hiddenCount(state.chats, state.prefs)
            SheetCard {
                SheetRow(null, "已隐藏的对话", trailing = {
                    Text(if (hidden > 0) "$hidden 个 · 全部显示" else "没有", color = if (hidden > 0) c.link else c.ink3, fontSize = 15.sp)
                }, onClick = if (hidden > 0) onUnhideAll else null)
            }
            Spacer(Modifier.height(8.dp))
            SheetCard { SheetRow(null, "断开这台电脑", danger = true) { confirm = true } }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ShapeAvatar("bot-1", "black", 40)
                Spacer(Modifier.height(8.dp))
                Text("DS Bot", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = c.ink3, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = c.bg,
        title = { Text("断开这台电脑？", color = c.ink) },
        text = { Text("断开后要在电脑上重新扫码才能再用。", color = c.ink2) },
        confirmButton = { TextButton(onClick = { confirm = false; onLogout() }) { Text("断开", color = c.danger) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消", color = c.ink) } },
    )
}

@Composable
private fun NotifyHint(text: String, action: String, onClick: () -> Unit) {
    val c = LocalDs.current
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(c.line))
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = c.ink2, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(12.dp))
        Text(action, color = c.link, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun GroupLabel(text: String) {
    val c = LocalDs.current
    Text(text, color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp))
}

@Composable
private fun ThemeTile(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalDs.current
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(if (on) c.group else c.bg)
                .border(1.dp, if (on) c.group else c.line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (on) c.ink else c.ink3, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (on) c.ink else c.ink3, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}
