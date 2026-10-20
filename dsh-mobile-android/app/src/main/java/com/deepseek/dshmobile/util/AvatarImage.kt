package com.deepseek.dshmobile.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Same as ds-bot desktop avatarImage(): a centered square, 160×160, WebP (then PNG),
 * as a data URL under 150 000 characters (ds-bot refuses above 160 000).
 */
object AvatarImage {
    private const val SIDE = 160
    private const val MAX_URL = 150_000

    fun fromUri(context: Context, uri: Uri): String {
        val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("这张图片打不开，请换一张")
        val crop = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - crop) / 2, (source.height - crop) / 2, crop, crop)
        val scaled = Bitmap.createScaledBitmap(square, SIDE, SIDE, true)
        @Suppress("DEPRECATION")
        val webp = Bitmap.CompressFormat.WEBP
        for (q in listOf(90, 75, 60)) {
            val url = encode(scaled, webp, q, "image/webp")
            if (url.length < MAX_URL) return url
        }
        val png = encode(scaled, Bitmap.CompressFormat.PNG, 100, "image/png")
        if (png.length < MAX_URL) return png
        throw IllegalArgumentException("这张图片太大了，请换一张")
    }

    private fun encode(b: Bitmap, f: Bitmap.CompressFormat, q: Int, mime: String): String {
        val out = ByteArrayOutputStream()
        b.compress(f, q, out)
        return "data:$mime;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
