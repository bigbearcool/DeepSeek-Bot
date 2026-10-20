package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.BridgeApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendIdempotencyTest {
    @Test fun sendCarriesClientMessageId() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"clientMessageId":"c1","duplicate":true}"""))
        server.start()
        val api = BridgeApi(BridgeApi.restClient()).withBase(server.url("/").toString())
        val res = api.send("tok", "chief", "hi", "c1")
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("c1", body.getString("clientMessageId"))
        assertTrue(res.ok)
        assertTrue(res.duplicate)
        server.shutdown()
    }

    @Test fun postIsNotReplayedOnConnectionFailure() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.start()
        val api = BridgeApi(BridgeApi.restClient()).withBase(server.url("/").toString())
        val r = runCatching { api.send("tok", "chief", "hi", "c1") }
        assertFalse(r.isSuccess)
        assertEquals(1, server.requestCount)
        server.shutdown()
    }
}
