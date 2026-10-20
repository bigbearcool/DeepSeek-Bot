package com.deepseek.dshmobile.ui

import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.deepseek.dshmobile.data.Look
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/** Markdown text; fenced code blocks become gray boxes with a copy button (no syntax colors), like Grok Bot. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val parts = remember(text) { Look.splitCode(text) }
    if (parts.none { it.code }) {
        MarkdownView(text, modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { p -> if (p.code) CodeBlock(p.text) else MarkdownView(p.text, Modifier.fillMaxWidth()) }
    }
}

@Composable
fun CodeBlock(code: String) {
    val c = LocalDs.current
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.code)) {
        Text(
            code, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 36.dp, top = 10.dp, bottom = 10.dp),
        )
        Icon(
            Icons.Outlined.ContentCopy, "复制代码", tint = c.ink3,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(RoundedCornerShape(8.dp))
                .clickable {
                    clip.setText(AnnotatedString(code))
                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                }.padding(6.dp),
        )
    }
}

@Composable
private fun MarkdownView(text: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = LocalDs.current
    val markwon = remember(c.dark) {
        Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create { t ->
                // Thin gray lines and a light gray header row.
                t.tableBorderColor(c.line.toArgb())
                    .tableBorderWidth(1)
                    .tableCellPadding((6 * ctx.resources.displayMetrics.density).toInt())
                    .tableHeaderRowBackgroundColor(c.code.copy(alpha = 0.6f).toArgb())
                    .tableOddRowBackgroundColor(0x01000000)
                    .tableEvenRowBackgroundColor(0x01000000)
            })
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeBackgroundColor(c.code.toArgb())
                        .codeBlockBackgroundColor(c.code.toArgb())
                        .codeTextColor(c.ink.toArgb())
                        .codeBlockTextColor(c.ink2.toArgb())
                        .blockQuoteColor(c.ink3.toArgb())
                        .linkColor(c.link.toArgb())
                        .headingBreakHeight(0)
                        .thematicBreakColor(c.line.toArgb())
                }
            })
            .build()
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(0f, 1.25f)
                // Only touches on a link stay here; everything else (long-press, swipe) goes to the bubble.
                setOnTouchListener { v, e -> linkTouch(v as TextView, e) }
            }
        },
        update = { tv ->
            tv.setTextColor(c.ink.toArgb())
            tv.setLinkTextColor(c.link.toArgb())
            // Extra line spacing cuts off the last table row, so tables get plain spacing.
            val hasTable = Regex("(?m)^\\s*\\|?\\s*:?-{3,}").containsMatchIn(text)
            tv.setLineSpacing(0f, if (hasTable) 1f else 1.25f)
            // Markwon tolerates unfinished markdown (e.g. an open ``` while streaming).
            runCatching { markwon.setMarkdown(tv, text) }.onFailure { tv.text = text }
        },
    )
}

private fun linkAt(tv: TextView, e: MotionEvent): ClickableSpan? {
    val text = tv.text as? Spanned ?: return null
    val layout = tv.layout ?: return null
    val x = e.x.toInt() - tv.totalPaddingLeft + tv.scrollX
    val y = e.y.toInt() - tv.totalPaddingTop + tv.scrollY
    val line = layout.getLineForVertical(y)
    if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
    val off = layout.getOffsetForHorizontal(line, x.toFloat())
    return text.getSpans(off, off, ClickableSpan::class.java).firstOrNull()
}

private fun linkTouch(tv: TextView, e: MotionEvent): Boolean {
    val span = linkAt(tv, e) ?: return false
    if (e.action == MotionEvent.ACTION_UP) span.onClick(tv)
    return true
}
