package com.wjx.autofill.schedule

import com.wjx.autofill.config.MiniJson
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 定时任务状态（Lead 2026-09-22 冻结）。 */
enum class TaskState { ARMED, REMINDED, RUNNING, DONE_OK, DONE_FAIL, CANCELLED }

/**
 * 定时提交任务（**冻结签名**，同一时刻只允许 1 个）。
 *
 * @param leadMinutes 提前提醒分钟数，用户可设，默认 10
 * @param state 见 [TaskState]；服务重启后据此决定是否重排定时
 */
data class ScheduledTask(
    val templateId: String,
    val templateName: String,
    val openAtMillis: Long,
    val leadMinutes: Int = ScheduleMath.DEFAULT_LEAD_MINUTES,
    val createdAtMillis: Long,
    val state: TaskState,
    /** 到点提醒是否响铃（additive，默认开；用户可关）。 */
    val alertSound: Boolean = true,
    /** 到点提醒是否震动（additive，默认开；用户可关）。 */
    val alertVibrate: Boolean = true,
)

/** 定时计算的纯函数（**必须可 JVM 单测**）。 */
object ScheduleMath {

    const val DEFAULT_LEAD_MINUTES = 10
    const val MIN_LEAD_MINUTES = 0
    const val MAX_LEAD_MINUTES = 24 * 60

    /** 提醒时刻 = openAtMillis - leadMinutes*60_000。 */
    fun remindAtMillis(task: ScheduledTask): Long =
        task.openAtMillis - normalizeLeadMinutes(task.leadMinutes) * 60_000L

    /**
     * 归一化提前量（冻结口径）：越界 clamp 到 0..1440；**负值 → 0**。
     * 「默认 10」由 [ScheduledTask.leadMinutes] 的构造默认参数承担，本函数不注入默认值。
     */
    fun normalizeLeadMinutes(input: Int): Int = when {
        input < MIN_LEAD_MINUTES -> MIN_LEAD_MINUTES
        input > MAX_LEAD_MINUTES -> MAX_LEAD_MINUTES
        else -> input
    }

    /** 到点后应该跑的状态。 */
    fun stateAfterRun(success: Boolean): TaskState =
        if (success) TaskState.DONE_OK else TaskState.DONE_FAIL
}

/** `yyyy-MM-dd HH:mm` 与 epoch millis 互转（纯 java.text，可离线单测）。 */
object ScheduleTime {

    private const val PATTERN = "yyyy-MM-dd HH:mm"

    fun parse(text: String?): Long? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        return try {
            SimpleDateFormat(PATTERN, Locale.CHINA).apply { isLenient = false }.parse(value)?.time
        } catch (_: Throwable) {
            null
        }
    }

    fun format(millis: Long): String =
        SimpleDateFormat(PATTERN, Locale.CHINA).format(Date(millis))

    /**
     * 解析结果 → 开放时间输入框的值（纯函数，可 JVM 单测）。
     *
     * 用户明确要求：**每次解析成功且解析到开放时间就强制覆盖**（以页面为准，避免沿用旧值）；
     * 解析不到（null / <= 0）→ **保留原值**（用户手填的不能被清掉）。
     */
    fun applyParsedOpenTime(current: String?, parsed: Long?): String? =
        if (parsed != null && parsed > 0L) format(parsed) else current
}

/** 任务持久化接口（**冻结签名**）。 */
interface ScheduledTaskStore {
    fun load(): ScheduledTask?
    fun save(task: ScheduledTask): Boolean
    fun clear(): Boolean
}

/**
 * 文件实现：`filesDir/schedule.json`，原子写 + 损坏回退（与 config/TemplateStore 同策略）。
 *
 * 只依赖 java.io + config 的 MiniJson，不 import android.* → qa-build 可用临时目录离线单测。
 */
class FileScheduledTaskStore(rootDirectory: File) : ScheduledTaskStore {

    val file: File = File(rootDirectory, FILE_NAME)

    fun readText(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: Throwable) {
        null
    }

    override fun load(): ScheduledTask? {
        val text = readText() ?: return null
        val root = MiniJson.decode(text) as? Map<*, *> ?: run {
            backupCorrupted()
            return null
        }
        val raw = root["task"] as? Map<*, *> ?: return null
        return decodeTask(raw)
    }

    override fun save(task: ScheduledTask): Boolean = writeAtomically(
        file,
        MiniJson.encodePretty(
            linkedMapOf<String, Any?>(
                "schemaVersion" to SCHEMA_VERSION,
                "task" to encodeTask(task),
            ),
        ),
    )

    override fun clear(): Boolean = writeAtomically(
        file,
        MiniJson.encodePretty(
            linkedMapOf<String, Any?>("schemaVersion" to SCHEMA_VERSION, "task" to null),
        ),
    )

    fun backupCorrupted(): File? = try {
        if (!file.isFile) {
            null
        } else {
            val backup = File(file.parentFile, "${FILE_NAME}.bad-${System.currentTimeMillis()}")
            if (file.renameTo(backup)) backup else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun encodeTask(task: ScheduledTask): Map<String, Any?> = linkedMapOf(
        "templateId" to task.templateId,
        "templateName" to task.templateName,
        "openAtMillis" to task.openAtMillis,
        "leadMinutes" to task.leadMinutes,
        "createdAtMillis" to task.createdAtMillis,
        "state" to task.state.name,
        "alertSound" to task.alertSound,
        "alertVibrate" to task.alertVibrate,
    )

    private fun decodeTask(raw: Map<*, *>): ScheduledTask? {
        val templateId = (raw["templateId"] as? String)?.trim().orEmpty()
        val openAt = (raw["openAtMillis"] as? Number)?.toLong() ?: 0L
        if (templateId.isEmpty() || openAt <= 0L) return null
        val state = runCatching {
            TaskState.valueOf((raw["state"] as? String).orEmpty().uppercase(Locale.US))
        }.getOrNull() ?: TaskState.ARMED
        return ScheduledTask(
            templateId = templateId,
            templateName = (raw["templateName"] as? String).orEmpty(),
            openAtMillis = openAt,
            leadMinutes = ScheduleMath.normalizeLeadMinutes(
                (raw["leadMinutes"] as? Number)?.toInt() ?: ScheduleMath.DEFAULT_LEAD_MINUTES,
            ),
            createdAtMillis = (raw["createdAtMillis"] as? Number)?.toLong() ?: 0L,
            state = state,
            alertSound = (raw["alertSound"] as? Boolean) ?: true,
            alertVibrate = (raw["alertVibrate"] as? Boolean) ?: true,
        )
    }

    companion object {
        const val FILE_NAME = "schedule.json"
        const val SCHEMA_VERSION = 1

        /** 原子写：.tmp → flush + fsync → rename（与 TemplateStore.writeAtomically 同策略）。 */
        fun writeAtomically(target: File, content: String): Boolean {
            return try {
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, target.name + ".tmp")
                FileOutputStream(temporary).use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                    output.flush()
                    output.fd.sync()
                }
                var ok = temporary.renameTo(target)
                if (!ok) {
                    target.delete()
                    ok = temporary.renameTo(target)
                }
                if (!ok) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                target.isFile
            } catch (_: Throwable) {
                false
            }
        }
    }
}
