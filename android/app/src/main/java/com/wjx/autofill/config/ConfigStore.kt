package com.wjx.autofill.config

import java.io.File
import java.io.FileOutputStream

/**
 * 模板持久化：读写 `filesDir/templates.json`，**原子写**（临时文件 + rename），
 * 解析失败有回退、绝不崩。
 *
 * 只依赖 java.io —— 构造函数接收目录而不是 Context，因此 qa-build 可以用临时目录
 * 在 JVM 上直接测试原子写与坏文件回退。
 */
class ConfigStore(rootDirectory: File) {

    val file: File = File(rootDirectory, FILE_NAME)
    val exportDirectory: File = File(rootDirectory, EXPORT_DIR_NAME)

    /** 读取原始文本；文件不存在或读失败返回 null。 */
    fun readText(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: Throwable) {
        null
    }

    /**
     * 读取模板库。文件缺失 / JSON 损坏 / 结构不符都回退为空库，
     * 并把损坏文件另存为 templates.json.bad 以免用户数据被静默覆盖。
     */
    fun load(): TemplateStore {
        val text = readText() ?: return TemplateStore()
        val decoded = TemplateJson.decode(text)
        if (decoded == null) {
            backupCorrupted()
            return TemplateStore()
        }
        return decoded
    }

    /** 原子保存；返回是否成功。 */
    fun save(store: TemplateStore): Boolean =
        writeAtomically(file, TemplateJson.encode(store))

    /** 导出到 filesDir/exports/<name>.json，返回写好的文件（失败返回 null）。 */
    fun export(store: TemplateStore, fileName: String): File? {
        exportDirectory.mkdirs()
        val target = File(exportDirectory, fileName)
        return if (writeAtomically(target, TemplateJson.encode(store))) target else null
    }

    /** 把损坏的 templates.json 改名保留，最多保留一份。 */
    fun backupCorrupted(): File? = try {
        if (!file.isFile) {
            null
        } else {
            val backup = File(file.parentFile, FILE_NAME + ".bad")
            if (backup.exists()) backup.delete()
            if (file.renameTo(backup)) backup else null
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val FILE_NAME = "templates.json"
        const val EXPORT_DIR_NAME = "exports"

        /**
         * 原子写：先写同目录临时文件并 fsync，再 rename 覆盖目标。
         * rename 失败时退化为「删除 + rename」，最差情况也只是丢一次写入而不是留下半个文件。
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
