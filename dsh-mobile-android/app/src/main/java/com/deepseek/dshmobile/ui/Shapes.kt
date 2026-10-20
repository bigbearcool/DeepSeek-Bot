package com.deepseek.dshmobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.deepseek.dshmobile.data.Character
import com.deepseek.dshmobile.data.Characters
import com.deepseek.dshmobile.data.Look

/** Each Bot's stored shape (from the computer), by Bot id; filled from the team list. */
val LocalBotShapes = compositionLocalOf<Map<String, String?>> { emptyMap() }

/** The color a Bot shows in: its color from the computer, else the one ds-bot picks from its id. */
@Composable
fun botTint(key: String, color: String?): Color =
    inkColor(Look.lookOf(key, null, color).second) ?: LocalDs.current.ink

private val AURORA = listOf(0f to Color(0xFFA4FFB0), 0.42f to Color(0xFF00F0E4), 1f to Color(0xFF2E9EF7))

private class Drawn(val c: Character) {
    private fun parse(d: String) = PathParser().parsePathString(d).toPath().apply { fillType = PathFillType.EvenOdd }
    val body: Path = parse(c.body)
    val paints = c.paints.map { Triple(parse(it.d), Color(it.argb), it.opacity) }
    val shade: Path = parse(c.shade)
    val light: Path = parse(c.light)
}

private val cache = HashMap<String, Drawn>()
private fun drawnOf(id: String): Drawn = cache.getOrPut(id) { Drawn(Characters.BY_ID[id] ?: Characters.BY_ID.getValue(Look.FALLBACK_SHAPE)) }

/** A ds-bot character in the Bot's color, eyes cut out — the same avatar the computer shows. */
@Composable
fun ShapeAvatar(key: String, color: String?, size: Int, modifier: Modifier = Modifier, shape: String? = null) {
    val stored = shape ?: LocalBotShapes.current[key]
    val (shapeId, colorId) = Look.lookOf(key, stored, color)
    val tint = inkColor(colorId) ?: LocalDs.current.ink
    val d = remember(shapeId) { drawnOf(shapeId) }
    Canvas(modifier.size(size.dp).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        scale(this.size.width / 100f, this.size.height / 100f, pivot = Offset.Zero) {
            if (colorId == "aurora") drawPath(d.body, Brush.linearGradient(*AURORA.toTypedArray(), start = Offset(0f, 0f), end = Offset(100f, 100f)))
            else drawPath(d.body, tint)
            d.paints.forEach { (p, col, a) -> drawPath(p, col, alpha = a) }
            drawPath(d.shade, Color.Black, alpha = 0.14f)
            drawPath(d.light, Color.White, alpha = 0.16f)
            d.c.eyes.forEach { e ->
                val (cx, cy, w, h) = listOf(e[0], e[1], e[2], e[3])
                translate(cx, cy) {
                    rotate(e[4], pivot = Offset.Zero) {
                        drawRoundRect(Color.Black, Offset(-w / 2, -h / 2), Size(w, h), CornerRadius(w / 2), blendMode = BlendMode.Clear)
                    }
                }
            }
        }
    }
}

/** Two members' characters overlapping, for a group chat. */
@Composable
fun RoomShapeAvatar(keys: List<Pair<String, String?>>, size: Int) {
    val first = keys.getOrNull(0) ?: ("group" to null)
    val second = keys.getOrNull(1) ?: ("group-2" to null)
    val small = (size * 0.68f).toInt()
    Box(Modifier.size(size.dp)) {
        ShapeAvatar(second.first, second.second, small, Modifier.offset(x = (size - small).dp, y = 0.dp))
        ShapeAvatar(first.first, first.second, small, Modifier.offset(x = 0.dp, y = (size - small).dp))
    }
}
