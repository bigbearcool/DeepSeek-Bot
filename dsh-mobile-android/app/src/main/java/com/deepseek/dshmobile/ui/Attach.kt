package com.deepseek.dshmobile.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deepseek.dshmobile.data.AttachLogic
import com.deepseek.dshmobile.data.Attachment
import com.deepseek.dshmobile.data.PendingAttachment

/** What message bubbles need to show and open pictures and files. */
class AttachUi(
    val picture: suspend (Attachment, Int) -> Bitmap? = { _, _ -> null },
    val openImage: (Attachment) -> Unit = {},
    val openFile: (Attachment) -> Unit = {},
    val removePending: (String) -> Unit = {},
    val toggleRun: (String) -> Unit = {},
    val closeViewer: () -> Unit = {},
    val save: (Attachment) -> Unit = {},
)

val LocalAttach = staticCompositionLocalOf { AttachUi() }

@Composable
private fun rememberPicture(a: Attachment, maxPx: Int): Bitmap? {
    val ui = LocalAttach.current
    val bmp by produceState<Bitmap?>(null, a.id, a.localUri, maxPx) { value = ui.picture(a, maxPx) }
    return bmp
}

/** Pictures (thumbnails) then file cards, aligned to the sender's side. */
@Composable
fun AttachmentList(items: List<Attachment>, alignEnd: Boolean) {
    if (items.isEmpty()) return
    val pics = items.filter { it.isImage }
    val files = items.filterNot { it.isImage }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pics.size == 1) Thumb(pics[0], single = true)
        else if (pics.isNotEmpty()) pics.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { Thumb(it, single = false) } }
        }
        files.forEach { FileCard(it) }
    }
}

@Composable
private fun Thumb(a: Attachment, single: Boolean) {
    val c = LocalDs.current
    val (w, h) = if (single) AttachLogic.fit(a.width, a.height, 220, 260) else 96 to 96
    val px = with(LocalDensity.current) { maxOf(w, h).dp.roundToPx() }
    val bmp = if (a.id != null || a.localUri != null) rememberPicture(a, px) else null
    val ui = LocalAttach.current
    Box(
        Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(14.dp)).background(c.bubble)
            .clickable(enabled = bmp != null) { ui.openImage(a) },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) Image(bmp.asImageBitmap(), a.name ?: "图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Image, null, tint = c.ink3, modifier = Modifier.size(26.dp))
            if (a.id == null && a.localUri == null) Text("图片在电脑上", color = c.ink3, fontSize = 11.sp)
        }
    }
}

/** A file in a message: document icon, name, type and size (like the 文件 tab rows). */
@Composable
fun FileCard(a: Attachment) {
    val c = LocalDs.current
    val ui = LocalAttach.current
    Row(
        Modifier.widthIn(min = 200.dp, max = 280.dp).clip(RoundedCornerShape(16.dp)).background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .clickable(enabled = a.id != null || a.localUri != null) { ui.openFile(a) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.tagBg), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Description, null, tint = c.ink2, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(a.name ?: "文件", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val size = AttachLogic.size(a.bytes)
            Text(AttachLogic.kind(a.name) + if (size.isNotEmpty()) " · $size" else "", color = c.ink3, fontSize = 13.sp)
        }
        if (a.id != null || a.localUri != null) {
            Spacer(Modifier.size(6.dp))
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { ui.save(a) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.FileDownload, "保存到手机", tint = c.ink3, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Picked items above the input bar, each with a remove button; a spinner while it goes to the computer. */
@Composable
fun PendingStrip(items: List<PendingAttachment>) {
    if (items.isEmpty()) return
    val c = LocalDs.current
    val ui = LocalAttach.current
    Row(
        Modifier.fillMaxWidth().background(c.bg).horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { p ->
            Box {
                if (p.isImage) {
                    val a = p.asAttachment()
                    val px = with(LocalDensity.current) { 72.dp.roundToPx() }
                    val bmp = rememberPicture(a, px)
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(c.bubble), contentAlignment = Alignment.Center) {
                        if (bmp != null) Image(bmp.asImageBitmap(), p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                } else Row(
                    Modifier.height(64.dp).widthIn(max = 220.dp).clip(RoundedCornerShape(12.dp)).background(c.card)
                        .border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Description, null, tint = c.ink2, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(p.name, color = c.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(AttachLogic.kind(p.name) + " · " + AttachLogic.size(p.bytes), color = c.ink3, fontSize = 12.sp)
                    }
                    Spacer(Modifier.size(18.dp))
                }
                if (!p.ready) Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                        .clickable { ui.removePending(p.localId) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "移除", tint = Color.White, modifier = Modifier.size(13.dp)) }
            }
        }
    }
}

/** 「N 条消息，与 [头像] K 个 Bot」: a Bot's talk with other Bots, folded into one gray line. */
@Composable
fun ExchangeLine(run: com.deepseek.dshmobile.data.Exchanges.Run, open: Boolean, colorOf: (String?) -> String?, onClick: () -> Unit) {
    val c = LocalDs.current
    val (head, tail) = com.deepseek.dshmobile.data.Exchanges.words(run)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(head, color = c.ink3, fontSize = 13.sp)
        Spacer(Modifier.size(6.dp))
        Box {
            run.bots.take(3).forEachIndexed { i, (id, name) ->
                Box(Modifier.padding(start = (i * 12).dp).clip(CircleShape).background(c.bg).padding(1.dp)) {
                    Avatar(name ?: "助手", 18, colorOf(id), key = id ?: name)
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(tail + if (open) "  收起" else "", color = c.ink3, fontSize = 13.sp)
    }
}

/** A picture full screen on black; pinch to zoom, tap ✕ or back to close. */
@Composable
fun ImageViewer(a: Attachment, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val px = with(LocalDensity.current) { 1600 }
        val bmp = rememberPicture(a, px)
        var scale by remember { mutableFloatStateOf(1f) }
        var dx by remember { mutableFloatStateOf(0f) }
        var dy by remember { mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (bmp == null) CircularProgressIndicator(color = Color.White)
            else Image(
                bmp.asImageBitmap(), a.name ?: "图片",
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale == 1f) { dx = 0f; dy = 0f } else { dx += pan.x; dy += pan.y }
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = dx, translationY = dy),
                contentScale = ContentScale.Fit,
            )
            Box(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp).size(40.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, "关闭", tint = Color.White, modifier = Modifier.size(22.dp)) }
            val ui = LocalAttach.current
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).size(40.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)).clickable { ui.save(a) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.FileDownload, "保存到手机", tint = Color.White, modifier = Modifier.size(22.dp)) }
            a.name?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) }
        }
    }
}
