package com.wjx.autofill.config

import com.wjx.autofill.wjx.AnswerPair
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一组填充内容：同一份问卷可以并行提交多组不同内容（契约 §4.1）。 */
data class AnswerGroup(
    val name: String,
    val pairs: List<AnswerPair>,
) {
    /** 目标字段为空的行在提交时必须忽略（契约 §4.1：空 value 才是「该题不填」）。 */
    fun effectivePairs(): List<AnswerPair> = pairs.filter { it.field.isNotBlank() }

    val isUsable: Boolean get() = effectivePairs().isNotEmpty()

    companion object {
        fun blank(name: String = ""): AnswerGroup = AnswerGroup(name = name, pairs = emptyList())
    }
}

/** 一份完整模板（契约 §4.1）。 */
data class MappingTemplate(
    val id: String,
    val name: String,
    val surveyUrl: String,
    val shortId: String,
    val groups: List<AnswerGroup>,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val updatedAt: Long = 0L,
) {
    val isUsable: Boolean get() = groups.any { it.isUsable }

    fun normalized(now: Long = System.currentTimeMillis()): MappingTemplate = copy(
        groups = groups.ifEmpty { listOf(AnswerGroup.blank()) },
        concurrency = concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY),
        updatedAt = now,
    )

    companion object {
        const val DEFAULT_CONCURRENCY = 2
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 5

        fun blank(name: String = "", surveyUrl: String = ""): MappingTemplate = MappingTemplate(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            surveyUrl = surveyUrl,
            shortId = "",
            groups = listOf(AnswerGroup.blank()),
            concurrency = DEFAULT_CONCURRENCY,
            updatedAt = 0L,
        )
    }
}

/** 导入结果（契约 §4.1）。 */
data class ImportReport(
    val imported: List<MappingTemplate>,
    val warnings: List<String>,
    val failures: List<String>,
) {
    val hasFailure: Boolean get() = failures.isNotEmpty()

    companion object {
        fun empty(): ImportReport = ImportReport(emptyList(), emptyList(), emptyList())
    }
}

/** 启动加载结果：模板 + 非致命警告 + 致命失败 + 损坏备份文件。 */
data class LoadOutcome(
    val templates: List<MappingTemplate>,
    val warnings: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val backupFile: File? = null,
)

/**
 * 模板持久化：`filesDir/templates.json`，原子写 + 损坏备份（契约 §4.4）。
 *
 * 只依赖 java.io（构造函数收目录而不是 Context），qa-build 可以用临时目录在 JVM 上直接测。
 * JSON 编解码在 [TemplatesJson]，同样不依赖 android。
 */
class TemplateStore(rootDirectory: File) {

    val file: File = File(rootDirectory, FILE_NAME)
    val exportDirectory: File = File(rootDirectory, EXPORT_DIR_NAME)

    fun readText(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: Throwable) {
        null
    }

    /**
     * 契约 §4.4 读取流程：
     * 1) 文件不存在 → 空配置，不报错；
     * 2) JSON 解析失败 → 绝不覆盖原文件，改名 templates.json.bad-<epochMillis>，空配置启动；
     * 3) schemaVersion > 1 → 整体拒绝（不备份，因为文件本身是合法的）；
     * 其余 → 逐模板宽容解析。
     */
    fun load(): LoadOutcome {
        val text = readText() ?: return LoadOutcome(emptyList())
        val report = TemplatesJson.decode(text)
        if (report == null) {
            val backup = backupCorrupted()
            val where = backup?.name ?: "备份失败"
            return LoadOutcome(
                templates = emptyList(),
                failures = listOf("配置损坏，已备份为 $where"),
                backupFile = backup,
            )
        }
        return LoadOutcome(
            templates = report.imported,
            warnings = report.warnings,
            failures = report.failures,
        )
    }

    /** 整体落盘（内存编辑完成后一次写入，避免半写状态）。 */
    fun save(
        templates: List<MappingTemplate>,
        exportedAt: Long = System.currentTimeMillis(),
        appVersion: String = "",
    ): Boolean = writeAtomically(
        file,
        TemplatesJson.encode(templates, exportedAt = exportedAt, appVersion = appVersion),
    )

    /** 导出到 filesDir/exports/<fileName>（系统分享路径用）。 */
    fun writeExport(fileName: String, content: String): File? {
        exportDirectory.mkdirs()
        val target = File(exportDirectory, fileName)
        return if (writeAtomically(target, content)) target else null
    }

    /** 把损坏的 templates.json 改名保留（契约 §4.4.2）。 */
    fun backupCorrupted(): File? = try {
        if (!file.isFile) {
            null
        } else {
            val backup = File(file.parentFile, "$FILE_NAME.bad-${System.currentTimeMillis()}")
            if (file.renameTo(backup)) backup else null
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val FILE_NAME = "templates.json"
        const val EXPORT_DIR_NAME = "exports"

        /** wjx-templates-<yyyyMMdd-HHmmss>.json（契约 §4.4 写入节）。 */
        fun exportFileName(now: Long = System.currentTimeMillis()): String =
            "wjx-templates-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".json"

        /**
         * 原子写：写同目录 .tmp → flush + fd.sync() → rename 覆盖目标。
         * rename 失败时退化为「删除 + rename」，再失败则 copy；最差情况是这次写入失败，
         * 而不是在目标位置留下半个文件。
         */
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
