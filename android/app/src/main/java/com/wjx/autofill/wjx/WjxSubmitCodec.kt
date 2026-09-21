package com.wjx.autofill.wjx

/**
 * 问卷星提交载荷编解码。**纯函数、无状态、无 IO**，可直接被 JVM 单元测试覆盖。
 *
 * 全部规则来自页面 JS `image.wjx.cn/joinnew/js/jqmobo2.js`（T3 已实测反混淆）：
 * `spChars/spToChars`、`dataenc`、`groupAnswer` 里的 submitdata 拼接。
 * 规范见 docs/API-CONTRACT.md §3。
 */
object WjxSubmitCodec {

    /** 分隔符原文：$ } ^ | ! < （JS `spChars`）。 */
    private val SP_CHARS = charArrayOf('\$', '}', '^', '|', '!', '<')

    /** 内容转义字符：ξ ｝ ˆ ¦ ！ ＜ （JS `spToChars`）。 */
    private val SP_TO_CHARS = charArrayOf('ξ', '｝', 'ˆ', '¦', '！', '＜')

    /** 文本答案长度上限（JS `validateQ` 对 type=1/2 的 3000 字校验）。 */
    const val MAX_TEXT_LENGTH = 3000

    /**
     * 内容转义：先按 `spChars → spToChars` 逐字符左到右替换，
     * 再删除非法 XML 字符（`< 0x20` 且非 \t \n \r，以及 U+FFFE/U+FFFF）。
     *
     * **不 trim、不折叠空白、不截断**（由调用方在构造答案时处理）；幂等。
     */
    fun escape(v: String): String {
        if (v.isEmpty()) return v
        val sb = StringBuilder(v.length)
        for (ch in v) {
            var replaced = false
            for (i in SP_CHARS.indices) {
                if (ch == SP_CHARS[i]) {
                    sb.append(SP_TO_CHARS[i])
                    replaced = true
                    break
                }
            }
            if (!replaced && isAllowedChar(ch)) sb.append(ch)
        }
        return sb.toString()
    }

    private fun isAllowedChar(ch: Char): Boolean {
        val c = ch.code
        if (c == 0x09 || c == 0x0A || c == 0x0D) return true
        if (c < 0x20) return false
        if (c <= 0xD7FF) return true
        if (c in 0xD800..0xDFFF) return true   // 代理对：UTF-16 码元原样保留
        if (c in 0xE000..0xFFFD) return true
        return false                            // U+FFFE / U+FFFF
    }

    /**
     * jqsign 签名：`key = ktimes % 10`（0 则取 1），逐 UTF-16 码元 XOR。
     *
     * 输出与输入**等长**，不是 hex/Base64；结果可能落在 URL 非安全区，
     * 放进 URL 时必须 URL-encode（[HttpWjxSubmitter] 已做）。
     * 负数 ktimes 用 floorMod 归一化到 0..9。
     */
    fun jqSign(jqnonce: String, ktimes: Int): String {
        var key = Math.floorMod(ktimes, 10)
        if (key == 0) key = 1
        val sb = StringBuilder(jqnonce.length)
        for (ch in jqnonce) sb.append((ch.code xor key).toChar())
        return sb.toString()
    }

    /**
     * 答案序列化：按 topic 升序，每项 `topic + "$" + value`，用 `"}"` 连接。
     *
     * 重复 topic 时后出现的覆盖先出现的；topic < 1 抛 [IllegalArgumentException]（编程错误）。
     * 输入为空返回空串（非空由调用方保证，见 E_EMPTY）。
     */
    fun encodeSubmitData(pairs: List<Pair<Int, String>>): String {
        if (pairs.isEmpty()) return ""
        val merged = LinkedHashMap<Int, String>()
        for ((topic, value) in pairs.sortedBy { it.first }) {
            require(topic >= 1) { "topic 必须 >= 1，实际为 $topic" }
            merged[topic] = value
        }
        val sb = StringBuilder()
        var first = true
        for ((topic, value) in merged) {
            if (!first) sb.append(SP_CHARS[1])
            first = false
            sb.append(topic).append(SP_CHARS[0]).append(value)
        }
        return sb.toString()
    }
}
