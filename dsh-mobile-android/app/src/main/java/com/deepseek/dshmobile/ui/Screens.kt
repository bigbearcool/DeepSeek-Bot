package com.deepseek.dshmobile.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepseek.dshmobile.data.BotInfo
import com.deepseek.dshmobile.data.ChatItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    state: UiState,
    onBaseUrlChange: (String) -> Unit,
    onSaveBaseUrl: () -> Unit,
    onPairInputChange: (String) -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit,
    onContinueIfPaired: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("DS Bot · Pair") }) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Bridge base URL", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("http://nas:port/api/mobile-bridge/v1") },
            )
            OutlinedButton(onClick = onSaveBaseUrl, modifier = Modifier.fillMaxWidth()) {
                Text("Save URL")
            }
            Spacer(Modifier.height(8.dp))
            Text("6-digit code or dshmb:…", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = state.pairInput,
                onValueChange = onPairInputChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("a1b2c3 or dshmb:a1b2c3") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPair, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text(if (state.busy) "…" else "Pair")
                }
                OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Text(" Scan", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (state.paired) {
                Button(onClick = onContinueIfPaired, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue to team")
                }
            }
            StatusLine(state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpen: (BotInfo) -> Unit,
    onLogout: () -> Unit,
    onBackToPair: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Team") },
                navigationIcon = {
                    IconButton(onClick = onBackToPair) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    OutlinedButton(onClick = onLogout) { Text("Unpair") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatusLine(state, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (state.busy && state.bots.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.bots, key = { it.id }) { bot ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(bot) },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    bot.name + if (bot.main) " · main" else "",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(bot.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                bot.role?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onComposeChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val bot = state.selectedBot
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.streamingText) {
        val last = state.messages.size + if (state.streamingText.isNotEmpty()) 1 else 0
        if (last > 0) listState.animateScrollToItem(last - 1)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(bot?.name ?: "Chat")
                        Text(
                            if (state.streamConnected) "SSE live" else "SSE…",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.nextCursor != null) {
                        OutlinedButton(onClick = onLoadMore) { Text("Older") }
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.composeText,
                    onValueChange = onComposeChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                )
                IconButton(onClick = onSend, enabled = state.composeText.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatusLine(state, Modifier.padding(horizontal = 12.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages.size) { idx ->
                    Bubble(state.messages[idx])
                }
                if (state.streamingText.isNotEmpty()) {
                    item {
                        Bubble(ChatItem(kind = "bot", text = state.streamingText + "▍"))
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(item: ChatItem) {
    val isUser = item.kind == "user"
    val bg = when (item.kind) {
        "user" -> Color(0xFF2563EB)
        "bot", "answer" -> Color(0xFF1F2937)
        "divider" -> Color.Transparent
        else -> Color(0xFF374151)
    }
    val fg = if (item.kind == "divider") Color.Gray else Color.White
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val label = when (item.kind) {
                "divider" -> "— earlier —"
                "event" -> "[${item.role ?: "event"}] ${item.name ?: ""}"
                else -> item.text.orEmpty()
            }
            Text(label, color = fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatusLine(state: UiState, modifier: Modifier = Modifier) {
    Text(
        text = buildString {
            append(state.status)
            if (state.deviceId.isNotBlank()) append(" · ").append(state.deviceId.take(12))
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}
