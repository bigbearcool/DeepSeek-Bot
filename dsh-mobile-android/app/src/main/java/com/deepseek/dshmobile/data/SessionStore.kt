package com.deepseek.dshmobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore("dsh_mobile_session")

class SessionStore(private val context: Context) {
    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val deviceId = stringPreferencesKey("device_id")
        val refreshToken = stringPreferencesKey("refresh_token")
        val accessToken = stringPreferencesKey("access_token")
        val accessExpiresAt = longPreferencesKey("access_expires_at")
        val deviceLabel = stringPreferencesKey("device_label")
    }

    val snapshot: Flow<SessionSnapshot> = context.dataStore.data.map { prefs ->
        SessionSnapshot(
            baseUrl = prefs[Keys.baseUrl].orEmpty(),
            deviceId = prefs[Keys.deviceId].orEmpty(),
            refreshToken = prefs[Keys.refreshToken].orEmpty(),
            accessToken = prefs[Keys.accessToken].orEmpty(),
            accessExpiresAt = prefs[Keys.accessExpiresAt] ?: 0L,
            deviceLabel = prefs[Keys.deviceLabel].orEmpty(),
        )
    }

    suspend fun current(): SessionSnapshot = snapshot.first()

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.baseUrl] = url.trim().trimEnd('/') }
    }

    suspend fun ensureDeviceId(): String {
        val cur = current()
        if (cur.deviceId.isNotBlank()) return cur.deviceId
        val id = "android-" + UUID.randomUUID().toString().replace("-", "").take(16)
        context.dataStore.edit { it[Keys.deviceId] = id }
        return id
    }

    suspend fun savePairing(deviceId: String, refreshToken: String, label: String) {
        context.dataStore.edit {
            it[Keys.deviceId] = deviceId
            it[Keys.refreshToken] = refreshToken
            it[Keys.deviceLabel] = label
            it.remove(Keys.accessToken)
            it.remove(Keys.accessExpiresAt)
        }
    }

    suspend fun saveAccess(accessToken: String, expiresAt: Long) {
        context.dataStore.edit {
            it[Keys.accessToken] = accessToken
            it[Keys.accessExpiresAt] = expiresAt
        }
    }

    suspend fun clearAuth() {
        context.dataStore.edit {
            it.remove(Keys.refreshToken)
            it.remove(Keys.accessToken)
            it.remove(Keys.accessExpiresAt)
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}

data class SessionSnapshot(
    val baseUrl: String,
    val deviceId: String,
    val refreshToken: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val deviceLabel: String,
) {
    val isPaired: Boolean get() = deviceId.isNotBlank() && refreshToken.isNotBlank()
    fun accessValid(now: Long = System.currentTimeMillis(), skewMs: Long = 60_000): Boolean =
        accessToken.isNotBlank() && accessExpiresAt > now + skewMs
}
