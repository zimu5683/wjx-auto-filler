package com.wjx.autofill.schedule

import android.content.Context
import com.wjx.autofill.R
import com.wjx.autofill.config.TemplateStore
import com.wjx.autofill.submit.SubmitCoordinator
import com.wjx.autofill.wjx.SubmitErrorCode

/**
 * 定时到点后真正执行提交的那一步。
 *
 * **复用既有 [SubmitCoordinator]，绝不重写提交逻辑**；前台服务与降级后台协程共用本对象。
 * 到点遇到人机验证 → **只推通知，不弹任何界面**（用户已确认的决策）。
 */
object ScheduledRunner {

    /** 执行一次；返回人类可读的结果摘要（同时落盘 [TaskState] + 推通知）。 */
    suspend fun runOnce(context: Context, task: ScheduledTask): String {
        val appContext = context.applicationContext
        val store = FileScheduledTaskStore(appContext.filesDir)
        store.save(task.copy(state = TaskState.RUNNING))

        val template = TemplateStore(appContext.filesDir).load().templates
            .firstOrNull { it.id == task.templateId }
        if (template == null) {
            val message = appContext.getString(R.string.schedule_template_missing)
            store.save(task.copy(state = TaskState.DONE_FAIL))
            Notifier.notifyResult(
                appContext,
                appContext.getString(R.string.schedule_result_title),
                message,
            )
            return message
        }

        val coordinator = SubmitCoordinator()
        val report = try {
            coordinator.run(template)
        } catch (t: Throwable) {
            val message = t.message?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.schedule_run_failed)
            store.save(task.copy(state = TaskState.DONE_FAIL))
            Notifier.notifyResult(
                appContext,
                appContext.getString(R.string.schedule_result_title),
                message,
            )
            return message
        }

        val summary = report.summary()
        val captchaBlocked = report.outcomes.any {
            it.result.errorCode == SubmitErrorCode.CAPTCHA
        }
        store.save(
            task.copy(state = ScheduleMath.stateAfterRun(!captchaBlocked && report.failureCount == 0)),
        )
        if (captchaBlocked) {
            // 变更 2：把现场落盘，供 MainActivity 恢复后自动进入验证页。
            val blocked = report.outcomes.firstOrNull {
                it.result.errorCode == SubmitErrorCode.CAPTCHA
            }
            if (blocked != null) {
                PendingCaptchaStore(appContext.filesDir).save(
                    PendingCaptcha(
                        templateId = template.id,
                        groupIndex = blocked.index,
                        surveyUrl = template.surveyUrl,
                        cookies = coordinator.lastSessionCookies(blocked.index),
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
            // 响铃/震动是**用户偏好**（SchedulePrefs），不是任务属性：
            // 这样用户在任务创建前后都能改，且不必改写已持久化的任务。
            val prefs = SchedulePrefs(appContext)
            Notifier.notifyCaptchaFullScreen(
                appContext,
                appContext.getString(R.string.schedule_captcha_notify_title),
                appContext.getString(R.string.schedule_captcha_notify_text, summary),
                alertSound = prefs.alertSound,
                alertVibrate = prefs.alertVibrate,
            )
        } else {
            Notifier.notifyResult(
                appContext,
                appContext.getString(R.string.schedule_result_title),
                summary,
            )
        }
        return summary
    }
}
