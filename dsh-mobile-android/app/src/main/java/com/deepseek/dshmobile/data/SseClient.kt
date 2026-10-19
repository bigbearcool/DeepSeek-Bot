package com.deepseek.dshmobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

data class SseEvent(val event: String, val data: String) {
    fun json(): JSONObject = try {
        JSONObject(data.ifBlank { "{}" })
    } catch (_: Exception) {
        JSONObject().put("raw", data)
    }
}

/**
 * Minimal SSE reader for bridge `/stream` (hello / live / message / stream).
 */
class SseClient(
    private val http: OkHttpClient = BridgeApi.defaultClient(),
) {
    fun connect(url: String, accessToken: String): Flow<SseEvent> = callbackFlow {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val call: Call = http.newCall(req)
        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty()
                        close(BridgeError("SSE HTTP ${resp.code}: $err", resp.code))
                        return@use
                    }
                    val body = resp.body ?: run {
                        close(BridgeError("empty SSE body"))
                        return@use
                    }
                    BufferedReader(InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)).use { reader ->
                        var event = "message"
                        val data = StringBuilder()
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            when {
                                line.startsWith(":") -> Unit // keepalive comment
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.removePrefix("data:").trimStart())
                                }
                                line.isEmpty() -> {
                                    if (data.isNotEmpty()) {
                                        trySend(SseEvent(event, data.toString()))
                                    }
                                    event = "message"
                                    data.clear()
                                }
                            }
                        }
                    }
                }
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose {
            call.cancel()
            job.cancel()
        }
    }
}
