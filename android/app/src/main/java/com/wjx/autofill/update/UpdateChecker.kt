package com.wjx.autofill.update

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 更新检查的调度与冷却缓存。
 *
 * 匿名 GitHub API 限流 60 次/小时，因此默认 6 小时最多真正请求一次；
 * 任何失败都静默返回 null —— 更新只是加分项，绝不打断主流程。
 */
object UpdateChecker {
    private const val PREFS_NAME = "wjx_update"
    private const val KEY_LAST_ATTEMPT = "last_attempt_at"
    private const val KEY_LAST_TAG = "last_seen_tag"

    /** 冷却窗口：6 小时。 */
    const val COOLDOWN_MS = 6L * 60L * 60L * 1000L

    fun lastAttemptAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ATTEMPT, 0L)

    fun lastSeenTag(context: Context): String =
        prefs(context).getString(KEY_LAST_TAG, "").orEmpty()

    /** 距离上次尝试是否已超过冷却窗口。 */
    fun shouldCheck(
        context: Context,
        now: Long = System.currentTimeMillis(),
        cooldownMs: Long = COOLDOWN_MS,
    ): Boolean = now - lastAttemptAt(context) >= cooldownMs

    /**
     * 检查更新；force = true 时忽略冷却（用户手动点「检查更新」）。
     * 失败返回 null，不抛异常。
     */
    suspend fun check(
        context: Context,
        currentVersion: String,
        force: Boolean = false,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (!force && !shouldCheck(appContext)) return@withContext null
        // 先落盘时间戳再请求：离线/被限流时不会每次启动都重试打满配额。
        markAttempt(appContext)
        val info = try {
            AppUpdater.checkForUpdate(currentVersion)
        } catch (_: Throwable) {
            null
        }
        if (info != null) {
            prefs(appContext).edit { putString(KEY_LAST_TAG, info.tagName) }
        }
        info
    }

    /** 记录一次尝试时间（无论成功失败）。 */
    fun markAttempt(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_LAST_ATTEMPT, now) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
