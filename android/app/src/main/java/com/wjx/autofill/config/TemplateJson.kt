package com.wjx.autofill.config

/**
 * templates.json 的编解码（纯 Kotlin，可单元测试）。
 *
 * 解码策略是**宽容**的：字段缺失用默认值、类型不符跳过、未知字段忽略，
 * 只有「顶层不是对象 / 文本不是 JSON」才判定失败（返回 null）。
 * 这样用户手工改坏的模板不会让 App 崩，也不会整份丢数据。
 */
object TemplateJson {

    private const val KEY_SCHEMA = "schemaVersion"
    private const val KEY_TEMPLATES = "templates"
    private const val KEY_NAME = "name"
    private const val KEY_URL = "surveyUrl"
    private const val KEY_GROUPS = "groups"
    private const val KEY_PAIRS = "pairs"
    private const val KEY_TARGET = "target"
    private const val KEY_VALUE = "value"
    private const val KEY_UPDATED_AT = "updatedAt"

    fun encode(store: TemplateStore): String {
        val root = linkedMapOf<String, Any?>(
            KEY_SCHEMA to TEMPLATE_SCHEMA_VERSION,
            KEY_TEMPLATES to store.templates.map { encodeTemplate(it) },
        )
        return MiniJson.encode(root)
    }

    private fun encodeTemplate(template: MappingTemplate): Map<String, Any?> =
        linkedMapOf(
            KEY_NAME to template.name,
            KEY_URL to template.surveyUrl,
            KEY_UPDATED_AT to template.updatedAt,
            KEY_GROUPS to template.groups.map { group ->
                linkedMapOf<String, Any?>(
                    KEY_NAME to group.name,
                    KEY_PAIRS to group.pairs.map { pair ->
                        linkedMapOf<String, Any?>(
                            KEY_TARGET to pair.target,
                            KEY_VALUE to pair.value,
                        )
                    },
                )
            },
        )

    /** 解析失败返回 null。 */
    fun decode(text: String): TemplateStore? {
        val root = MiniJson.decode(text) as? Map<*, *> ?: return null
        // 宽容：允许直接导入单个模板对象（顶层就是一份模板）。
        if (root.containsKey(KEY_GROUPS) && !root.containsKey(KEY_TEMPLATES)) {
            val single = decodeTemplate(root) ?: return null
            return TemplateStore(templates = listOf(single))
        }
        val version = (root[KEY_SCHEMA] as? Number)?.toInt() ?: TEMPLATE_SCHEMA_VERSION
        val rawTemplates = root[KEY_TEMPLATES] as? List<*> ?: return TemplateStore(version)
        val templates = rawTemplates.mapNotNull { item ->
            (item as? Map<*, *>)?.let { decodeTemplate(it) }
        }
        return TemplateStore(schemaVersion = version, templates = templates)
    }

    private fun decodeTemplate(raw: Map<*, *>): MappingTemplate? {
        val name = raw[KEY_NAME] as? String ?: ""
        val url = raw[KEY_URL] as? String ?: ""
        val updatedAt = (raw[KEY_UPDATED_AT] as? Number)?.toLong() ?: 0L
        val groups = (raw[KEY_GROUPS] as? List<*>)
            ?.mapNotNull { item -> (item as? Map<*, *>)?.let { decodeGroup(it) } }
            .orEmpty()
        if (name.isBlank() && url.isBlank() && groups.isEmpty()) return null
        return MappingTemplate(
            name = name,
            surveyUrl = url,
            groups = groups.ifEmpty { listOf(AnswerGroup()) },
            updatedAt = updatedAt,
        )
    }

    private fun decodeGroup(raw: Map<*, *>): AnswerGroup {
        val name = raw[KEY_NAME] as? String ?: ""
        val pairs = (raw[KEY_PAIRS] as? List<*>)
            ?.mapNotNull { item -> (item as? Map<*, *>)?.let { decodePair(it) } }
            .orEmpty()
        return AnswerGroup(name = name, pairs = pairs)
    }

    private fun decodePair(raw: Map<*, *>): AnswerPair {
        val target = raw[KEY_TARGET] as? String
            ?: (raw[KEY_TARGET] as? Number)?.toString()
            ?: ""
        val value = raw[KEY_VALUE] as? String
            ?: (raw[KEY_VALUE] as? Number)?.toString()
            ?: (raw[KEY_VALUE] as? Boolean)?.toString()
            ?: ""
        return AnswerPair(target = target, value = value)
    }
}
