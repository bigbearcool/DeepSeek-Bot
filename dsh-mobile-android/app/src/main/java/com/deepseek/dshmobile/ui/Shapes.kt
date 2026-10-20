package com.deepseek.dshmobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.deepseek.dshmobile.data.Look
import kotlin.math.cos
import kotlin.math.sin

/** The color a Bot shows in: its color from the computer, or a steady one picked from its id. */
@Composable
fun botTint(key: String, color: String?): Color =
    inkColor(color) ?: inkColor(Look.fallbackInk(key)) ?: LocalDs.current.ink

/** A colored shape with two little eyes, like Grok Bot's avatars. */
@Composable
fun ShapeAvatar(key: String, color: String?, size: Int, modifier: Modifier = Modifier) {
    val tint = botTint(key, color)
    val shape = Look.shapeFor(key)
    Canvas(modifier.size(size.dp)) { drawBotShape(shape, tint) }
}

/** Two members' shapes overlapping, for a group chat. */
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

private fun DrawScope.drawBotShape(shape: Look.Shape, tint: Color) {
    val w = size.width
    val h = size.height
    when (shape) {
        Look.Shape.Circle -> drawCircle(tint, radius = w * 0.46f)
        Look.Shape.Square -> drawRoundRect(tint, Offset(w * 0.08f, h * 0.08f), Size(w * 0.84f, h * 0.84f), CornerRadius(w * 0.22f))
        Look.Shape.Pill -> drawRoundRect(tint, Offset(w * 0.02f, h * 0.24f), Size(w * 0.96f, h * 0.52f), CornerRadius(h * 0.26f))
        Look.Shape.Arch -> drawPath(Path().apply {
            moveTo(w * 0.14f, h * 0.94f); lineTo(w * 0.14f, h * 0.44f)
            cubicTo(w * 0.14f, h * 0.02f, w * 0.86f, h * 0.02f, w * 0.86f, h * 0.44f)
            lineTo(w * 0.86f, h * 0.94f); close()
        }, tint)
        Look.Shape.Drop -> drawPath(Path().apply {
            moveTo(w * 0.5f, h * 0.02f)
            cubicTo(w * 0.62f, h * 0.22f, w * 0.9f, h * 0.44f, w * 0.9f, h * 0.62f)
            cubicTo(w * 0.9f, h * 0.84f, w * 0.72f, h * 0.98f, w * 0.5f, h * 0.98f)
            cubicTo(w * 0.28f, h * 0.98f, w * 0.1f, h * 0.84f, w * 0.1f, h * 0.62f)
            cubicTo(w * 0.1f, h * 0.44f, w * 0.38f, h * 0.22f, w * 0.5f, h * 0.02f); close()
        }, tint)
        Look.Shape.Hexagon -> drawPath(Path().apply {
            for (i in 0 until 6) {
                val a = Math.toRadians(60.0 * i - 90)
                val x = w * 0.5f + w * 0.47f * cos(a).toFloat()
                val y = h * 0.5f + h * 0.47f * sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }, tint)
        Look.Shape.Triangle -> drawPath(Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            quadraticBezierTo(w * 0.56f, h * 0.08f, w * 0.6f, h * 0.16f)
            lineTo(w * 0.96f, h * 0.8f)
            quadraticBezierTo(w * 0.98f, h * 0.9f, w * 0.88f, h * 0.9f)
            lineTo(w * 0.12f, h * 0.9f)
            quadraticBezierTo(w * 0.02f, h * 0.9f, w * 0.04f, h * 0.8f)
            lineTo(w * 0.4f, h * 0.16f)
            quadraticBezierTo(w * 0.44f, h * 0.08f, w * 0.5f, h * 0.08f); close()
        }, tint)
    }
    // Eyes: two short white strokes, slightly tilted.
    val eyeY = if (shape == Look.Shape.Triangle || shape == Look.Shape.Drop) h * 0.58f else h * 0.42f
    val len = h * 0.15f
    val stroke = w * 0.07f
    listOf(w * 0.38f, w * 0.6f).forEach { x ->
        drawLine(Color.White.copy(alpha = 0.95f), Offset(x + len * 0.12f, eyeY - len / 2), Offset(x - len * 0.12f, eyeY + len / 2), stroke, StrokeCap.Round)
    }
}
