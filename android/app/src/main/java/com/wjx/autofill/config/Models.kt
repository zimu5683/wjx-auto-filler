package com.wjx.autofill.config

/** templates.json 的 schema 版本；字段不兼容变更时递增。 */
const val TEMPLATE_SCHEMA_VERSION = 1

/**
 * 一行字段映射：左栏 = 目标字段（题号 / 题干关键词），右栏 = 填充内容。
 *
 * [target] 支持三种写法，由 wjx/ 的匹配器解析：
 *  - 纯数字题号：`1`、`12`
 *  - 带前缀题号：`q1`、`Q12`
 *  - 题干关键词：`姓名`（包含匹配，忽略大小写与空白）
 */
data class AnswerPair(
    val target: String = "",
    val value: String = "",
) {
    val isBlank: Boolean get() = target.isBlank()
}

/** 一组填充内容：同一份问卷可以并行提交多组不同内容。 */
data class AnswerGroup(
    val name: String = "",
    val pairs: List<AnswerPair> = emptyList(),
) {
    /** 去掉目标字段为空的行（UI 上允许留空行，提交时必须忽略）。 */
    fun effectivePairs(): List<AnswerPair> =
        pairs.filter { it.target.isNotBlank() }

    val isUsable: Boolean get() = effectivePairs().isNotEmpty()
}

/** 一份完整模板：问卷链接 + 若干内容组。 */
data class MappingTemplate(
    val name: String = "",
    val surveyUrl: String = "",
    val groups: List<AnswerGroup> = emptyList(),
    val updatedAt: Long = 0L,
) {
    /** 落盘前的规范化：至少一组，剔除空行。 */
    fun normalized(): MappingTemplate {
        val cleaned = groups
            .map { it.copy(pairs = it.effectivePairs()) }
            .ifEmpty { listOf(AnswerGroup(name = "")) }
        return copy(groups = cleaned)
    }
}

/** templates.json 的根对象。 */
data class TemplateStore(
    val schemaVersion: Int = TEMPLATE_SCHEMA_VERSION,
    val templates: List<MappingTemplate> = emptyList(),
) {
    fun findByName(name: String): MappingTemplate? =
        templates.firstOrNull { it.name == name }

    /** 同名覆盖，否则追加。 */
    fun upsert(template: MappingTemplate): TemplateStore {
        val index = templates.indexOfFirst { it.name == template.name }
        val next = if (index >= 0) {
            templates.toMutableList().also { it[index] = template }
        } else {
            templates + template
        }
        return copy(templates = next)
    }

    fun remove(name: String): TemplateStore =
        copy(templates = templates.filterNot { it.name == name })
}
