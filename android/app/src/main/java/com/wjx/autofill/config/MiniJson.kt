package com.wjx.autofill.config

/**
 * 极简 JSON 读写器：纯 Kotlin，**不 import android.*，也不依赖 org.json**。
 *
 * 为什么不用 org.json：Android 单元测试里 org.json 是 android.jar 的 stub，
 * 要么抛 "not mocked"，要么在 isReturnDefaultValues = true 下返回默认值 ——
 * 那样「模板导入导出」就无法在 JVM 上被真实验证。这里实现最小可用子集，
 * 让 config/ 的全部逻辑都能被 qa-build 直接单元测试。
 *
 * 支持的值类型：null / Boolean / Long / Double / String / Map / List。
 * 编码时 Map 保持插入顺序；解码时对象用 LinkedHashMap、数组用 ArrayList。
 */
object MiniJson {

    // ------------------------------------------------------------------ 编码

    fun encode(value: Any?): String {
        val builder = StringBuilder()
        writeValue(builder, value)
        return builder.toString()
    }

    private fun writeValue(builder: StringBuilder, value: Any?) {
        when (value) {
            null -> builder.append("null")
            is String -> writeString(builder, value)
            is Boolean -> builder.append(if (value) "true" else "false")
            is Int -> builder.append(value.toString())
            is Long -> builder.append(value.toString())
            is Double -> builder.append(formatDouble(value))
            is Float -> builder.append(formatDouble(value.toDouble()))
            is Number -> builder.append(value.toString())
            is Map<*, *> -> writeObject(builder, value)
            is Iterable<*> -> writeArray(builder, value)
            else -> writeString(builder, value.toString())
        }
    }

    private fun formatDouble(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val asLong = value.toLong()
        return if (asLong.toDouble() == value) asLong.toString() else value.toString()
    }

    private fun writeObject(builder: StringBuilder, map: Map<*, *>) {
        builder.append('{')
        var first = true
        for ((key, item) in map) {
            if (!first) builder.append(',')
            first = false
            writeString(builder, key?.toString() ?: "")
            builder.append(':')
            writeValue(builder, item)
        }
        builder.append('}')
    }

    private fun writeArray(builder: StringBuilder, items: Iterable<*>) {
        builder.append('[')
        var first = true
        for (item in items) {
            if (!first) builder.append(',')
            first = false
            writeValue(builder, item)
        }
        builder.append(']')
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

    // ------------------------------------------------------------------ 解码

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
                else -> throw IllegalArgumentException("未知转义：\\$ch")
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
