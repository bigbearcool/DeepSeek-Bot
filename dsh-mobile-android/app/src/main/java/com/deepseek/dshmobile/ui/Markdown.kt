package com.deepseek.dshmobile.ui

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/** Markdown text (bold, italic, lists, headings, code, links, quotes, tables). */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = LocalDs.current
    val markwon = remember(c.dark) {
        Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(ctx))
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeBackgroundColor(c.card.toArgb())
                        .codeBlockBackgroundColor(c.card.toArgb())
                        .codeTextColor(c.ink.toArgb())
                        .codeBlockTextColor(c.ink.toArgb())
                        .blockQuoteColor(c.ink3.toArgb())
                        .linkColor(c.accent.toArgb())
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
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
            }
        },
        update = { tv ->
            tv.setTextColor(c.ink.toArgb())
            tv.setLinkTextColor(c.accent.toArgb())
            // Markwon tolerates unfinished markdown (e.g. an open ``` while streaming).
            runCatching { markwon.setMarkdown(tv, text) }.onFailure { tv.text = text }
        },
    )
}
