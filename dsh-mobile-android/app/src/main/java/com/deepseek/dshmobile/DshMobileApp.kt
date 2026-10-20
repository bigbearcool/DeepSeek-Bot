package com.deepseek.dshmobile

import android.app.Application
import com.deepseek.dshmobile.data.AuthRepository
import com.deepseek.dshmobile.data.BridgeApi
import com.deepseek.dshmobile.data.SessionStore
import com.deepseek.dshmobile.data.SseClient

class DshMobileApp : Application() {
    lateinit var sessionStore: SessionStore
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var sseClient: SseClient
        private set

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        authRepository = AuthRepository(sessionStore)
        sseClient = SseClient(BridgeApi.defaultClient())
    }
}

val Application.dsh: DshMobileApp get() = this as DshMobileApp
