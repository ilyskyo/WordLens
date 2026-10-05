// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ilyskyo.wordlens.MainActivity
import com.ilyskyo.wordlens.R

/**
 * 复习提醒的通知：渠道、构造与发送。
 *
 * ## 为什么渠道必须显式创建
 *
 * Android 8 起，没有渠道的通知根本发不出去（系统直接丢弃并记一条日志）。这个应用之前只有
 * 桌面小组件、没有任何通知，所以渠道从来没建过——现在要发第一条了，就必须有一个地方
 * 在第一次使用前把它建好，而且要在**任何**发送路径之前。[ReminderScheduler.schedule] 和
 * Application 启动都做这件事，因为渠道创建是幂等的，晚一步就是一次静默失败。
 *
 * ## 为什么是 IMPORTANCE_LOW
 *
 * 每天一次的「你还有几张卡」不该有播放音量级别的打断。IMPORTANCE_LOW 给通知栏一条、
 * 给锁屏一条、不响铃不震动。要响的用户可以在系统设置里给这个渠道加上声音——那是他的选择，
 * 不是我们替他做的。
 */
object ReminderNotifications {

    const val CHANNEL_ID = "wordlens_due_reminder"

    /** 固定 id：同一天的两条通知必须互相覆盖，而不是叠成两条。 */
    private const val NOTIFICATION_ID = 4201

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_reminder),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_reminder_desc)
            // 显式不给声音：IMPORTANCE_LOW 本来就不响，但厂商 ROM 会自作主张，写死更可靠。
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 发一条「今天还剩 N 张」。
     *
     * N 为 0 时**不发**：空的复习队列里冒出一条通知，读起来像广告而不是像助手。
     */
    fun showDue(context: Context, dueCount: Int) {
        if (dueCount <= 0) return
        ensureChannel(context)
        val notification = build(context, dueCount)
        val manager = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS 是运行时权限（Android 13+）。没给的时候 notify 不会崩，
        // 只会静默无效——所以这里必须 catch 安全异常并留下日志，否则「提醒不出现」又是查不到的事。
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { android.util.Log.w(TAG, "reminder notification blocked", it) }
    }

    private fun build(context: Context, dueCount: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_due_title, dueCount))
            .setContentText(context.getString(R.string.notification_due_body))
            .setContentIntent(openRemember(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    /** 点提醒就是要去复习。落到时间轴等于让这次点击白给（与小组件同一条 extras）。 */
    private fun openRemember(context: Context): PendingIntent {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_REMEMBER, true)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            // FLAG_IMMUTABLE 从 Android 12 起是硬性要求，缺了会在点击时抛异常而不是编译期报错。
            PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, launch, flags)
    }

    private const val TAG = "ReminderNotif"
}
