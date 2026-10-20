package com.deepseek.dshmobile.data

import android.os.Build
import com.deepseek.dshmobile.BuildConfig

/**
 * Pairing + access-token refresh against the bridge.
 */
class AuthRepository(
    private val store: SessionStore,
    private val api: BridgeApi = BridgeApi(BridgeApi.restClient()),
) {
    suspend fun effectiveBaseUrl(): String {
        val saved = store.current().baseUrl
        val base = BridgeApi.normalizeBase(if (saved.isNotBlank()) saved else BuildConfig.DEFAULT_BASE_URL)
        if (base.isBlank()) throw BridgeError("请先扫描电脑上「手机配对」的二维码，或手动填写服务器地址")
        return base
    }

    suspend fun saveBaseUrl(url: String) {
        store.setBaseUrl(BridgeApi.normalizeBase(url))
    }

    suspend fun pair(code: String): PairCompleteResult {
        val base = effectiveBaseUrl()
        val deviceId = store.ensureDeviceId()
        val label = "Android ${Build.MODEL}".take(80)
        val result = api.withBase(base).pairComplete(code, deviceId, label)
        store.savePairing(result.deviceId, result.refreshToken, label)
        // Immediately mint access token
        refreshAccess(force = true)
        return result
    }

    suspend fun refreshAccess(force: Boolean = false): String {
        val snap = store.current()
        if (!force && snap.accessValid()) return snap.accessToken
        if (!snap.isPaired) throw AuthRevokedError("not paired")
        val base = effectiveBaseUrl()
        val issued = try {
            api.withBase(base).issueAccess(snap.deviceId, snap.refreshToken)
        } catch (e: BridgeError) {
            // 401 from /auth/token = device revoked or refresh token rejected. Anything else
            // (network, 429, 5xx) is temporary and must not kick the user back to pairing.
            if (e.status == 401 || e.status == 403) throw AuthRevokedError(e.message ?: "unauthorized")
            throw e
        }
        store.saveAccess(issued.accessToken, issued.expiresAt)
        return issued.accessToken
    }

    /** Run [block] with a valid access token; on 401 refresh once and retry. */
    suspend fun <T> withAccess(block: suspend (accessToken: String, configured: BridgeApi.Configured) -> T): T {
        val base = effectiveBaseUrl()
        val configured = api.withBase(base)
        val token = refreshAccess(force = false)
        return try {
            block(token, configured)
        } catch (e: BridgeError) {
            if (e.status == 401) {
                val again = refreshAccess(force = true)
                block(again, configured)
            } else {
                throw e
            }
        }
    }

    suspend fun logout() {
        store.clearAuth()
    }
}
