package com.wjx.autofill.config

import com.wjx.autofill.wjx.AnswerPair
import java.util.UUID

/**
 * templates.json 编解码（契约 §4.2–§4.4）。
 *
 * 纯 Kotlin，**不 import android.*，也不用 org.json** —— Android 单元测试里 org.json
 * 是 android.jar 的空壳（要么 "not mocked"，要么在 isReturnDefaultValues 下返回默认值），
 * 那样「模板导入导出」就无法在 JVM 上被真实验证。这里自带最小 JSON 解析/序列化，
 * 让 config/ 的全部逻辑都能被 qa-build 直接单测，也省掉一条 build.gradle.kts 依赖改动。
 */
object TemplatesJson {

    const val SCHEMA_VERSION = 1

    /** surveyUrl 正则：^https://([A-Za-z0-9-]+\.)*wjx\.cn/(vm|jq|m)/([A-Za-z0-9]{4,32})\.aspx（允许 wjx.cn 的任意子域，忽略大小写） */
    private val SURVEY_URL_REGEX =
        Regex("""^https://([A-Za-z0-9-]+\.)*wjx\.cn/(vm|jq|m)/([A-Za-z0-9]{4,32})\.aspx""", RegexOption.IGNORE_CASE)

    private val SHORT_ID_REGEX = Regex("""^[A-Za-z0-9]{4,32}$""")

    private const val MAX_NAME = 60
    private const val MAX_ID = 64
    private const val MAX_FIELD = 200

    // ------------------------------------------------------------------ 编码

    fun encode(
        templates: List<MappingTemplate>,
        exportedAt: Long = 0L,
        appVersion: String = "",
    ): String {
        val root = linkedMapOf<String, Any?>(
            "schemaVersion" to SCHEMA_VERSION,
            "exportedAt" to exportedAt,
            "appVersion" to appVersion,
            "templates" to templates.map { encodeTemplate(it) },
        )
        return MiniJson.encodePretty(root)
    }

    private fun encodeTemplate(template: MappingTemplate): Map<String, Any?> = linkedMapOf(
        "id" to template.id,
        "name" to template.name,
        "surveyUrl" to template.surveyUrl,
        "shortId" to template.shortId,
        "concurrency" to template.concurrency,
        "updatedAt" to template.updatedAt,
        "groups" to template.groups.map { group ->
            linkedMapOf<String, Any?>(
                "name" to group.name,
                "pairs" to group.pairs.map { pair ->
                    linkedMapOf<String, Any?>(
                        "field" to pair.field,
                        "value" to pair.value,
                    )
                },
            )
        },
    )

    // ------------------------------------------------------------------ 解码

    /**
     * 顶层不是 JSON 对象（语法错误 / 不是对象）→ 返回 null，调用方据此走「损坏备份」流程。
     * 其余情况都返回 [ImportReport]，单个模板的问题进 failures，不影响其他模板。
     */
    fun decode(text: String, existingIds: Set<String> = emptySet()): ImportReport? {
        val root = MiniJson.decode(text) as? Map<*, *> ?: return null

        val version = intOf(root["schemaVersion"]) ?: SCHEMA_VERSION
        if (version > SCHEMA_VERSION) {
            return ImportReport(
                imported = emptyList(),
                warnings = emptyList(),
                failures = listOf("配置由更新版本（schemaVersion=$version）导出，请升级 App"),
            )
        }

        val rawTemplates = when (val raw = root["templates"]) {
            null -> emptyList<Any?>()
            is List<*> -> raw
            else -> return ImportReport(
                imported = emptyList(),
                warnings = emptyList(),
                failures = listOf("配置里 templates 不是数组"),
            )
        }

        val warnings = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val imported = mutableListOf<MappingTemplate>()
        val usedIds = existingIds.toMutableSet()
        if (version < SCHEMA_VERSION) {
            warnings += "配置由旧版本（schemaVersion=$version）导出，已按当前版本迁移"
        }

        rawTemplates.forEachIndexed { index, item ->
            val map = item as? Map<*, *>
            if (map == null) {
                failures += "第 ${index + 1} 个模板不是 JSON 对象，已跳过"
                return@forEachIndexed
            }
            val label = "模板「${(map["name"] as? String)?.take(MAX_NAME) ?: "#${index + 1}"}」"
            val template = decodeTemplate(map, label, warnings, failures) ?: return@forEachIndexed
            val finalTemplate = if (template.id in usedIds) {
                val fresh = UUID.randomUUID().toString()
                warnings += "$label：id 重复，已重新生成"
                template.copy(id = fresh)
            } else {
                template
            }
            usedIds += finalTemplate.id
            imported += finalTemplate
        }

        return ImportReport(imported = imported, warnings = warnings, failures = failures)
    }

    private fun decodeTemplate(
        raw: Map<*, *>,
        label: String,
        warnings: MutableList<String>,
        failures: MutableList<String>,
    ): MappingTemplate? {
        // 必填：id / name / surveyUrl / groups（契约 §4.4.8）
        val id = (raw["id"] as? String)?.trim().orEmpty()
        if (id.isEmpty()) {
            failures += "$label：缺少必填字段 id"
            return null
        }
        var finalId = id
        if (finalId.length > MAX_ID) {
            finalId = finalId.take(MAX_ID)
            warnings += "$label：id 超长，已截断"
        }

        val name = (raw["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) {
            failures += "$label：缺少必填字段 name"
            return null
        }
        var finalName = name
        if (finalName.length > MAX_NAME) {
            finalName = finalName.take(MAX_NAME)
            warnings += "$label：name 超长，已截断到 $MAX_NAME 字"
        }

        val surveyUrl = (raw["surveyUrl"] as? String)?.trim().orEmpty()
        if (surveyUrl.isEmpty()) {
            failures += "$label：缺少必填字段 surveyUrl"
            return null
        }
        val urlMatch = SURVEY_URL_REGEX.find(surveyUrl)
        if (urlMatch == null) {
            failures += "$label：surveyUrl 不是合法的问卷链接（$surveyUrl）"
            return null
        }
        val derivedShortId = urlMatch.groupValues[3]

        // shortId：缺失 → 推导；非法或与 URL 冲突 → 以 surveyUrl 为准
        val rawShortId = (raw["shortId"] as? String)?.trim().orEmpty()
        val shortId = when {
            rawShortId.isEmpty() -> {
                warnings += "$label：shortId 缺失，已从链接推导为 $derivedShortId"
                derivedShortId
            }
            !SHORT_ID_REGEX.matches(rawShortId) -> {
                warnings += "$label：shortId 非法，已改为从链接推导的 $derivedShortId"
                derivedShortId
            }
            rawShortId != derivedShortId -> {
                warnings += "$label：shortId（$rawShortId）与链接不一致，以链接为准（$derivedShortId）"
                derivedShortId
            }
            else -> rawShortId
        }

        // concurrency：宽容转换 + clamp（契约 §4.4.6 / §4.4.10）
        val rawConcurrency = raw["concurrency"]
        var concurrency = intOf(rawConcurrency) ?: MappingTemplate.DEFAULT_CONCURRENCY
        if (rawConcurrency != null && intOf(rawConcurrency) == null) {
            warnings += "$label：concurrency 不是整数，已用默认值 ${MappingTemplate.DEFAULT_CONCURRENCY}"
        }
        if (concurrency !in MappingTemplate.MIN_CONCURRENCY..MappingTemplate.MAX_CONCURRENCY) {
            val clamped = concurrency.coerceIn(
                MappingTemplate.MIN_CONCURRENCY,
                MappingTemplate.MAX_CONCURRENCY,
            )
            warnings += "$label：concurrency 越界（$concurrency），已改为 $clamped"
            concurrency = clamped
        }

        val updatedAt = longOf(raw["updatedAt"]) ?: 0L

        val rawGroups = raw["groups"]
        if (rawGroups == null) {
            failures += "$label：缺少必填字段 groups"
            return null
        }
        if (rawGroups !is List<*>) {
            failures += "$label：groups 不是数组"
            return null
        }
        val groups = mutableListOf<AnswerGroup>()
        for ((groupIndex, groupItem) in rawGroups.withIndex()) {
            val groupMap = groupItem as? Map<*, *>
            if (groupMap == null) {
                failures += "$label：第 ${groupIndex + 1} 组不是 JSON 对象"
                return null
            }
            val groupName = (groupMap["name"] as? String)?.take(MAX_NAME).orEmpty()
            val rawPairs = groupMap["pairs"] ?: emptyList<Any?>()
            if (rawPairs !is List<*>) {
                failures += "$label：第 ${groupIndex + 1} 组的 pairs 不是数组"
                return null
            }
            val pairs = mutableListOf<AnswerPair>()
            for ((pairIndex, pairItem) in rawPairs.withIndex()) {
                val pairMap = pairItem as? Map<*, *>
                if (pairMap == null) {
                    failures += "$label：第 ${groupIndex + 1} 组第 ${pairIndex + 1} 项不是 JSON 对象"
                    return null
                }
                val field = stringOf(pairMap["field"])
                if (field.isBlank()) {
                    failures += "$label：第 ${groupIndex + 1} 组第 ${pairIndex + 1} 项缺少必填字段 field"
                    return null
                }
                val finalField = if (field.length > MAX_FIELD) {
                    warnings += "$label：字段「${field.take(12)}…」超长，已截断到 $MAX_FIELD 字"
                    field.take(MAX_FIELD)
                } else {
                    field
                }
                // 契约 §4.4.12：value 为空串合法（语义 = 该题不填）
                pairs += AnswerPair(field = finalField, value = stringOf(pairMap["value"]))
            }
            groups += AnswerGroup(name = groupName, pairs = pairs)
        }

        return MappingTemplate(
            id = finalId,
            name = finalName,
            surveyUrl = surveyUrl,
            shortId = shortId,
            groups = groups,
            concurrency = concurrency,
            updatedAt = updatedAt,
        )
    }

    /** 契约 §4.4.4 预留的迁移链；当前只有 schemaVersion 1，迁移为空实现。 */
    @Suppress("UNUSED_PARAMETER")
    private fun migrate(from: Int, to: Int, templates: List<MappingTemplate>): List<MappingTemplate> =
        templates

    private fun intOf(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun longOf(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun stringOf(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> ""
    }
}

/**
 * 最小 JSON 读写器（**internal**：同模块可复用，例如 schedule/ 的任务持久化；不对外暴露）。
 *
 * 支持 null / Boolean / Long / Double / String / Map / List；
 * 编码保持插入顺序，[encodePretty] 输出 2 空格缩进的稳定格式（契约 §4.4 写入节）。
 */
internal object MiniJson {

    fun encodePretty(value: Any?, indent: String = "  "): String {
        val builder = StringBuilder()
        writeValue(builder, value, indent, 0)
        return builder.toString()
    }

    private fun writeValue(builder: StringBuilder, value: Any?, indent: String?, depth: Int) {
        when (value) {
            null -> builder.append("null")
            is String -> writeString(builder, value)
            is Boolean -> builder.append(if (value) "true" else "false")
            is Int -> builder.append(value.toString())
            is Long -> builder.append(value.toString())
            is Double -> builder.append(formatNumber(value))
            is Float -> builder.append(formatNumber(value.toDouble()))
            is Number -> builder.append(value.toString())
            is Map<*, *> -> writeObject(builder, value, indent, depth)
            is Iterable<*> -> writeArray(builder, value, indent, depth)
            else -> writeString(builder, value.toString())
        }
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val asLong = value.toLong()
        return if (asLong.toDouble() == value) asLong.toString() else value.toString()
    }

    private fun writeObject(builder: StringBuilder, map: Map<*, *>, indent: String?, depth: Int) {
        if (map.isEmpty()) {
            builder.append("{}")
            return
        }
        builder.append('{')
        var first = true
        for ((key, item) in map) {
            if (!first) builder.append(',')
            first = false
            newline(builder, indent, depth + 1)
            writeString(builder, key?.toString() ?: "")
            builder.append(':')
            if (indent != null) builder.append(' ')
            writeValue(builder, item, indent, depth + 1)
        }
        newline(builder, indent, depth)
        builder.append('}')
    }

    private fun writeArray(builder: StringBuilder, items: Iterable<*>, indent: String?, depth: Int) {
        val list = items.toList()
        if (list.isEmpty()) {
            builder.append("[]")
            return
        }
        builder.append('[')
        list.forEachIndexed { index, item ->
            if (index > 0) builder.append(',')
            newline(builder, indent, depth + 1)
            writeValue(builder, item, indent, depth + 1)
        }
        newline(builder, indent, depth)
        builder.append(']')
    }

    private fun newline(builder: StringBuilder, indent: String?, depth: Int) {
        if (indent == null) return
        builder.append('\n')
        repeat(depth) { builder.append(indent) }
    }

    private fun writeString(builder: StringBuilder, text: String) {
        builder.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> if (ch < ' ') {
                    builder.append("\\u")
                    builder.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(ch)
                }
            }
        }
        builder.append('"')
    }

    /** 解析失败返回 null（调用方负责回退，绝不抛异常给 UI）。 */
    fun decode(text: String): Any? = try {
        Parser(text).parseDocument()
    } catch (_: Throwable) {
        null
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseDocument(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos != text.length) throw IllegalArgumentException("JSON 末尾有多余内容")
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) throw IllegalArgumentException("JSON 内容不完整")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expectLiteral("true"); true }
                'f' -> { expectLiteral("false"); false }
                'n' -> { expectLiteral("null"); null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when (val ch = peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("对象里出现意外字符：$ch")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val items = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return items }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (val ch = peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return items }
                    else -> throw IllegalArgumentException("数组里出现意外字符：$ch")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (pos >= text.length) throw IllegalArgumentException("字符串没有结束引号")
                val ch = text[pos++]
                when {
                    ch == '"' -> return builder.toString()
                    ch == '\\' -> builder.append(parseEscape())
                    else -> builder.append(ch)
                }
            }
        }

        private fun parseEscape(): Char {
            if (pos >= text.length) throw IllegalArgumentException("转义不完整")
            return when (val ch = text[pos++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > text.length) throw IllegalArgumentException("\\u 转义不完整")
                    val hex = text.substring(pos, pos + 4)
                    pos += 4
                    hex.toIntOrNull(16)?.toChar()
                        ?: throw IllegalArgumentException("\\u 转义非法：$hex")
                }
                else -> throw IllegalArgumentException("未知转义")
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.' ||
                        text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')
            ) {
                pos++
            }
            if (start == pos) throw IllegalArgumentException("不是合法数字")
            val token = text.substring(start, pos)
            if (!token.contains('.') && !token.contains('e') && !token.contains('E')) {
                token.toLongOrNull()?.let { return it }
            }
            return token.toDoubleOrNull() ?: throw IllegalArgumentException("不是合法数字：$token")
        }

        private fun expectLiteral(literal: String) {
            if (pos + literal.length > text.length ||
                text.substring(pos, pos + literal.length) != literal
            ) {
                throw IllegalArgumentException("期望 $literal")
            }
            pos += literal.length
        }

        private fun expect(ch: Char) {
            skipWhitespace()
            if (pos >= text.length || text[pos] != ch) {
                throw IllegalArgumentException("期望 '$ch'")
            }
            pos++
        }

        private fun peek(): Char {
            skipWhitespace()
            if (pos >= text.length) throw IllegalArgumentException("JSON 内容不完整")
            return text[pos]
        }

        private fun skipWhitespace() {
            while (pos < text.length) {
                val ch = text[pos]
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') pos++ else break
            }
        }
    }
}
