package com.wjx.autofill.schedule

import com.wjx.autofill.config.MiniJson
import java.io.File

/**
 * 到点提交被人机验证拦截时留下的「待人工验证」记录。
 *
 * 前台服务无法直接把令牌交回 UI（后台启动 Activity 受限），因此把**现场**落盘：
 * 通知（全屏 Intent）打开 MainActivity 后，由它读取本记录恢复兜底状态机，自动进入验证页。
 */
data class PendingCaptcha(
    val templateId: String,
    val groupIndex: Int,
    val surveyUrl: String,
    val cookies: Map<String, String>,
    val createdAtMillis: Long,
)

/** `filesDir/pending-captcha.json`：原子写 + 损坏回退（复用 FileScheduledTaskStore 的写策略）。 */
class PendingCaptchaStore(rootDirectory: File) {

    val file: File = File(rootDirectory, FILE_NAME)

    fun load(): PendingCaptcha? {
        val text = try {
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        } catch (_: Throwable) {
            null
        } ?: return null
        val root = MiniJson.decode(text) as? Map<*, *> ?: return null
        val raw = root["pending"] as? Map<*, *> ?: return null
        val templateId = (raw["templateId"] as? String)?.trim().orEmpty()
        if (templateId.isEmpty()) return null
        val cookies = LinkedHashMap<String, String>()
        (raw["cookies"] as? Map<*, *>)?.forEach { (key, value) ->
            val name = key?.toString().orEmpty()
            if (name.isNotEmpty()) cookies[name] = value?.toString().orEmpty()
        }
        return PendingCaptcha(
            templateId = templateId,
            groupIndex = (raw["groupIndex"] as? Number)?.toInt() ?: 0,
            surveyUrl = (raw["surveyUrl"] as? String).orEmpty(),
            cookies = cookies,
            createdAtMillis = (raw["createdAtMillis"] as? Number)?.toLong() ?: 0L,
        )
    }

    fun save(record: PendingCaptcha): Boolean = FileScheduledTaskStore.writeAtomically(
        file,
        MiniJson.encodePretty(
            linkedMapOf<String, Any?>(
                "schemaVersion" to SCHEMA_VERSION,
                "pending" to linkedMapOf<String, Any?>(
                    "templateId" to record.templateId,
                    "groupIndex" to record.groupIndex,
                    "surveyUrl" to record.surveyUrl,
                    "cookies" to record.cookies,
                    "createdAtMillis" to record.createdAtMillis,
                ),
            ),
        ),
    )

    fun clear(): Boolean = FileScheduledTaskStore.writeAtomically(
        file,
        MiniJson.encodePretty(
            linkedMapOf<String, Any?>("schemaVersion" to SCHEMA_VERSION, "pending" to null),
        ),
    )

    companion object {
        const val FILE_NAME = "pending-captcha.json"
        const val SCHEMA_VERSION = 1
    }
}
