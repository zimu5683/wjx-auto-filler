package com.wjx.autofill.schedule

import android.content.Context

/**
 * 定时任务的**到点提醒方式**偏好（用户要求：响铃/震动做成可选项）。
 *
 * 为什么用 SharedPreferences 而不是 [ScheduledTask] 字段：同一时刻只允许 1 个任务，
 * 提醒方式是**用户偏好**而不是任务属性；放在偏好里可以让"改开关"立即生效，
 * 不必改写已持久化的任务，也不会污染冻结的 ScheduledTask 签名。
 *
 * 两者**默认开启**：这个功能的全部意义就是到点提醒用户去完成人工验证，
 * 默认关闭会让人错过开放窗口。关闭后仍会推送**高优先级全屏通知**，只是安静。
 */
class SchedulePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var alertSound: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    var alertVibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE, value).apply()

    /** 任一开启即视为"响铃震动"渠道，两者都关则走静默渠道。 */
    val alerting: Boolean get() = alertSound || alertVibrate

    companion object {
        const val FILE_NAME = "wjx_schedule_prefs"
        const val KEY_SOUND = "alert_sound"
        const val KEY_VIBRATE = "alert_vibrate"
    }
}
