package com.wjx.autofill.schedule

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.wjx.autofill.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 定时提交前台服务（用户已确认：**只做前台常驻服务**，不用 AlarmManager/WorkManager）。
 *
 * - 常驻通知 + START_STICKY：进程被杀后系统重启本服务，onStartCommand 重新从磁盘恢复任务；
 * - 协程 delay 到点 → [ScheduledRunner]（内部复用既有 SubmitCoordinator）；
 * - 清单声明 `foregroundServiceType="specialUse"`；API 34+ 传 FOREGROUND_SERVICE_TYPE_SPECIAL_USE
 *   （**刻意不用 dataSync**：Android 15 对 dataSync 有 6 小时上限，跨夜定时会被掐断）；
 * - 启动失败（SecurityException 等）不崩：捕获后继续以普通服务运行，失败原因由 UI 显式反馈。
 */
class ScheduledSubmitService : Service() {

    companion object {
        const val ACTION_START = "com.wjx.autofill.schedule.action.START"
        const val ACTION_STOP = "com.wjx.autofill.schedule.action.STOP"

        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L

        fun startIntent(context: Context): Intent =
            Intent(context, ScheduledSubmitService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, ScheduledSubmitService::class.java).setAction(ACTION_STOP)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var store: ScheduledTaskStore

    override fun onCreate() {
        super.onCreate()
        store = FileScheduledTaskStore(filesDir)
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            Notifier.cancel(this, Notifier.ID_ONGOING)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSafely()
        scheduleFromStore()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** 前台服务启动失败不抛：返回 false 表示已降级为普通服务继续跑。 */
    private fun startForegroundSafely(): Boolean {
        val notification = Notifier.ongoing(this, getString(R.string.schedule_ongoing_text))
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, Notifier.ID_ONGOING, notification, type)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 从磁盘恢复任务并重排定时（START_STICKY 重启、UI 改任务后都会走到这里）。 */
    private fun scheduleFromStore() {
        job?.cancel()
        val task = store.load()
        if (task == null || task.state == TaskState.CANCELLED) {
            Notifier.cancel(this, Notifier.ID_ONGOING)
            stopSelf()
            return
        }
        if (task.state == TaskState.RUNNING) {
            // 上次执行到一半进程被杀：提交非幂等，**绝不自动重跑**，如实标记失败。
            store.save(task.copy(state = TaskState.DONE_FAIL))
            Notifier.notifyResult(
                this,
                getString(R.string.schedule_result_title),
                getString(R.string.schedule_interrupted),
            )
            stopSelf()
            return
        }
        if (task.state == TaskState.DONE_OK || task.state == TaskState.DONE_FAIL) {
            // 已经跑完，等用户改时间或重开
            stopSelf()
            return
        }

        job = scope.launch {
            val now = System.currentTimeMillis()
            val remindAt = ScheduleMath.remindAtMillis(task)
            if (remindAt > now) {
                delay(remindAt - now)
                Notifier.notifyReminder(
                    this@ScheduledSubmitService,
                    getString(R.string.schedule_remind_title),
                    getString(
                        R.string.schedule_remind_text,
                        task.templateName.ifBlank { getString(R.string.group_default_name) },
                        ScheduleTime.format(task.openAtMillis),
                    ),
                )
                store.save(task.copy(state = TaskState.REMINDED))
            }
            val wait = task.openAtMillis - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            withWakeLock { ScheduledRunner.runOnce(this@ScheduledSubmitService, task) }
            stopSelf()
        }
    }

    /** 到点执行期间持有 partial wake lock，避免 CPU 休眠导致提交被推迟。 */
    private suspend fun withWakeLock(block: suspend () -> Unit) {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val lock = try {
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wjx:scheduled_submit")?.apply {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (_: Throwable) {
            null
        }
        try {
            block()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: Throwable) {
                // 释放失败无所谓
            }
        }
    }
}
