package com.wjx.autofill.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wjx.autofill.MainActivity
import com.wjx.autofill.R

/**
 * 定时提交的通知渠道与推送（常驻 / 提前提醒 / 结果）。
 *
 * 到点遇到人机验证时**只推通知、不弹任何界面**（用户已确认的决策）。
 */
object Notifier {

    const val CHANNEL_ONGOING = "wjx_schedule_service"
    const val CHANNEL_REMIND = "wjx_schedule_reminder"
    const val CHANNEL_RESULT = "wjx_schedule_result"

    /**
     * 到点需要人工验证。渠道的响铃/震动创建后**不可修改**，所以按用户开关分成两个：
     * 响铃震动版与静默版（两者都是高优先级 + 全屏 Intent）。
     */
    const val CHANNEL_CAPTCHA_ALERT = "wjx_schedule_captcha_alert"
    const val CHANNEL_CAPTCHA_SILENT = "wjx_schedule_captcha_silent"

    const val ID_ONGOING = 1001
    const val ID_REMIND = 1002
    const val ID_RESULT = 1003
    const val ID_CAPTCHA = 1004

    /** POST_NOTIFICATIONS 的字面量：minSdk 24 下直接引用 API 33 常量会让 lint NewApi 误报。 */
    const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                context.getString(R.string.schedule_channel_ongoing),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.schedule_channel_ongoing_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMIND,
                context.getString(R.string.schedule_channel_remind),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.schedule_channel_remind_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.schedule_channel_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.schedule_channel_result_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTCHA_ALERT,
                context.getString(R.string.schedule_channel_captcha_alert),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.schedule_channel_captcha_alert_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 500L, 300L, 500L, 300L, 500L)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build(),
                )
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTCHA_SILENT,
                context.getString(R.string.schedule_channel_captcha_silent),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.schedule_channel_captcha_silent_desc)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    /** 是否有权限发通知（Android 13+ 需要运行时授权；更早版本只看系统开关）。 */
    fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, PERMISSION_POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 前台服务的常驻通知。 */
    fun ongoing(context: Context, text: String): Notification =
        base(context, CHANNEL_ONGOING)
            .setContentTitle(context.getString(R.string.schedule_ongoing_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainActivityIntent(context))
            .build()

    fun notifyReminder(context: Context, title: String, text: String) =
        post(context, ID_REMIND, CHANNEL_REMIND, title, text, highPriority = true)

    fun notifyResult(context: Context, title: String, text: String) =
        post(context, ID_RESULT, CHANNEL_RESULT, title, text)

    /** 到点被人机验证拦截：只推通知（高优先级）。 */
    fun notifyCaptcha(context: Context, title: String, text: String) =
        post(context, ID_CAPTCHA, CHANNEL_RESULT, title, text, highPriority = true)

    /**
     * 到点需要人工验证（用户变更 2）：**优先用全屏 Intent 直接拉起验证流程**，并响铃 + 震动。
     *
     * Android 10+ 限制后台启动 Activity，所以走 `setFullScreenIntent`；系统不允许时
     * （Android 14+ 该权限默认不授予非闹钟/通话类应用）自动退化为高优先级通知，用户点击后进入。
     */
    fun notifyCaptchaFullScreen(
        context: Context,
        title: String,
        text: String,
        alertSound: Boolean = true,
        alertVibrate: Boolean = true,
    ) {
        if (!canNotify(context)) return
        val alerting = alertSound || alertVibrate
        val channel = if (alerting) CHANNEL_CAPTCHA_ALERT else CHANNEL_CAPTCHA_SILENT
        val pending = resumeCaptchaIntent(context) ?: run {
            post(context, ID_CAPTCHA, channel, title, text, highPriority = true)
            return
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
        // 用户关掉响铃/震动后仍是高优先级全屏通知，只是安静。
        if (alerting) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        } else {
            builder.setSilent(true)
        }
        if (canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(pending, true)
        }
        try {
            NotificationManagerCompat.from(context).notify(ID_CAPTCHA, builder.build())
        } catch (_: Throwable) {
            // 通知失败不影响主流程
        }
    }

    /** Android 14+ 全屏 Intent 需要系统授权；未授权时退化。 */
    private fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return try {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        } catch (_: Throwable) {
            false
        }
    }

    /** 点通知 → 打开 MainActivity 并让它恢复「待人工验证」现场（自动进入验证页）。 */
    private fun resumeCaptchaIntent(context: Context): PendingIntent? = try {
        PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_RESUME_CAPTCHA, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    } catch (_: Throwable) {
        null
    }

    fun cancel(context: Context, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (_: Throwable) {
            // 取消失败无所谓
        }
    }

    private fun post(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        highPriority: Boolean = false,
    ) {
        if (!canNotify(context)) return
        val notification = base(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setPriority(
                if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setContentIntent(mainActivityIntent(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // 没有 POST_NOTIFICATIONS 时静默：通知不是主流程
        } catch (_: Throwable) {
            // 同上
        }
    }

    private fun base(context: Context, channel: String): NotificationCompat.Builder {
        ensureChannels(context)
        return NotificationCompat.Builder(context, channel)
    }

    private fun mainActivityIntent(context: Context): PendingIntent? = try {
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    } catch (_: Throwable) {
        null
    }
}
