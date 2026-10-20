package com.deepseek.dshmobile.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.deepseek.dshmobile.MainActivity
import com.deepseek.dshmobile.R
import com.deepseek.dshmobile.data.NotifyLogic

/** Which chat is on screen right now (no notification for it). */
object AppPresence {
    @Volatile var openKey: String? = null
    @Volatile var resumed: Boolean = false
    val visibleKey: String? get() = if (resumed) openKey else null
}

/** System notifications: channels, new message / approval, saved file, the keep-connected line. */
object Notifier {
    const val CH_MESSAGES = "messages"
    const val CH_APPROVALS = "approvals"
    const val CH_SERVICE = "service"
    const val CH_FILES = "files"
    const val ONGOING_ID = 1
    const val EXTRA_KIND = "openKind"
    const val EXTRA_ID = "openId"
    const val EXTRA_NAME = "openName"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_MESSAGES, "新消息", NotificationManager.IMPORTANCE_HIGH).apply { description = "助手和群聊的新消息" },
            NotificationChannel(CH_APPROVALS, "等你确认", NotificationManager.IMPORTANCE_HIGH).apply { description = "助手要做某件事前，先问你" },
            NotificationChannel(CH_FILES, "保存的文件", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "文件存到手机后提醒你" },
            NotificationChannel(CH_SERVICE, "保持连接", NotificationManager.IMPORTANCE_MIN).apply { description = "后台连着电脑，才能收到新消息"; setShowBadge(false) },
        ))
    }

    private fun openChat(ctx: Context, kind: String, id: String, name: String, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun ongoing(ctx: Context) = NotificationCompat.Builder(ctx, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle("正在接收新消息")
        .setContentText("连着你的电脑，有新消息会提醒你")
        .setOngoing(true).setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    fun post(ctx: Context, a: NotifyLogic.Alert) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val b = NotificationCompat.Builder(ctx, if (a.approval) CH_APPROVALS else CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(a.name)
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setSubText(NotifyLogic.countLine(a.count))
            .setAutoCancel(true)
            .setCategory(if (a.approval) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openChat(ctx, a.kind, a.id, a.name, a.tag.hashCode()))
        runCatching { nm.notify(a.tag, 0, b.build()) }
    }

    /** The chat was opened: its message notification is read. */
    fun cancelChat(ctx: Context, kind: String, id: String) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(NotifyLogic.key(kind, id), 0) }
    }

    fun saved(ctx: Context, name: String, where: String, open: Intent) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val pi = PendingIntent.getActivity(ctx, name.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_FILES).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("已保存「$name」").setContentText("在手机的「$where」里，点这里打开")
            .setAutoCancel(true).setContentIntent(pi).build()
        runCatching { nm.notify("file:$name", 0, n) }
    }

    fun enabled(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
}
