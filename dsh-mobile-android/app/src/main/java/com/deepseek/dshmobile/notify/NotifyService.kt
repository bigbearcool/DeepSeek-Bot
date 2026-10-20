package com.deepseek.dshmobile.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.deepseek.dshmobile.data.AuthRevokedError
import com.deepseek.dshmobile.data.ChatPrefsStore
import com.deepseek.dshmobile.data.NotifyLogic
import com.deepseek.dshmobile.dsh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps one connection to the computer while paired, and posts a notification for new
 * messages and approvals. No server push (phase 2): the computer must be on and reachable.
 */
class NotifyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var seen: NotifyLogic.Seen? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.channels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, Notifier.ONGOING_ID, Notifier.ongoing(this), type) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        if (job == null) job = scope.launch { run() }
        wake.trySend(Unit)
        return START_STICKY
    }

    private suspend fun run() {
        val app = application.dsh
        val prefs = ChatPrefsStore(this)
        scope.launch { listen() }
        while (scope.isActive) {
            if (!app.sessionStore.snapshot.first().isPaired || !prefs.flow.first().notify) { stopSelf(); return }
            runCatching {
                val now = app.authRepository.withAccess { token, api -> api.chatList(token) }
                NotifyLogic.alerts(seen, now, prefs.flow.first(), AppPresence.visibleKey).forEach { Notifier.post(this, it) }
                seen = NotifyLogic.seen(now)
            }.onFailure { if (it is AuthRevokedError) { stopSelf(); return } }
            // Group lines are read by polling; a Bot's own messages also wake us right away.
            withTimeoutOrNull(POLL_MS) { wake.receive() }
            delay(800) // let a burst of events settle into one check
        }
    }

    /** Live events from the computer just trigger a check; reconnects with backoff. */
    private suspend fun listen() {
        val app = application.dsh
        var backoff = 2_000L
        while (scope.isActive) {
            runCatching {
                app.authRepository.withAccess { token, api ->
                    app.sseClient.connect(api.streamUrl(), token).collect { ev ->
                        backoff = 2_000L
                        if (ev.event == "message" || ev.event == "live") wake.trySend(Unit)
                    }
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val POLL_MS = 20_000L

        /** Run while paired and the 设置 switch is on; stop otherwise. */
        fun sync(ctx: Context, paired: Boolean, on: Boolean) {
            val i = Intent(ctx, NotifyService::class.java)
            if (paired && on) runCatching { ContextCompat.startForegroundService(ctx, i) }
            else ctx.stopService(i)
        }
    }
}
