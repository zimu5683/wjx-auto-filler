package com.wjx.autofill.wjx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WjxSubmitCodec 契约测试：向量逐字来自 docs/API-CONTRACT.md §3.4。
 *
 * 注意 Kotlin 里 $ 是字符串模板起始符，所以断言里的美元号必须写成 \$。
 */
class WjxSubmitCodecTest {

    // 样本问卷实测页面里的 jqnonce
    private val nonce = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6"

    // ---------------------------------------------------------------- escape

    @Test
    fun escapeReplacesAllSixSpecialCharacters() {
        assertEquals("aξb｝cˆd¦e！f＜g", WjxSubmitCodec.escape("a\$b}c^d|e!f<g"))
        assertEquals("正常文本，含ξ和｝与¦", WjxSubmitCodec.escape("正常文本，含\$和}与|"))
    }

    @Test
    fun escapeIsIdempotent() {
        val once = WjxSubmitCodec.escape("已转义ξ｝ˆ¦！＜不应二次转义")
        assertEquals("已转义ξ｝ˆ¦！＜不应二次转义", once)
        assertEquals(once, WjxSubmitCodec.escape(once))
    }

    @Test
    fun escapeDropsIllegalControlCharacters() {
        assertEquals("ab", WjxSubmitCodec.escape("a\u0001b"))
        assertEquals("ab", WjxSubmitCodec.escape("a\uFFFEb"))
        assertEquals("ab", WjxSubmitCodec.escape("a\uFFFFb"))
    }

    @Test
    fun escapeKeepsTabNewlineCarriageReturnAndDoesNotTrim() {
        assertEquals("a\tb\nc\rd", WjxSubmitCodec.escape("a\tb\nc\rd"))
        assertEquals("  a  ", WjxSubmitCodec.escape("  a  "))
    }

    // ---------------------------------------------------------------- jqSign

    @Test
    fun jqSignMatchesSampleSurveyVectors() {
        // ktimes=0 → key = 1（0 归一化为 1）
        assertEquals("d3`9528b,``6c,50e6,c1b6,8b01943e77e7", WjxSubmitCodec.jqSign(nonce, 0))
        // ktimes=7 → key = 7
        assertEquals("b5f?34>d*ff0e*36c0*e7d0*>d67?25c11c1", WjxSubmitCodec.jqSign(nonce, 7))
    }

    @Test
    fun jqSignUsesKeyOneWhenKtimesIsMultipleOfTen() {
        assertEquals("`cb", WjxSubmitCodec.jqSign("abc", 10))
        assertEquals("`cb", WjxSubmitCodec.jqSign("abc", 0))
        assertEquals("`cb", WjxSubmitCodec.jqSign("abc", 20))
    }

    @Test
    fun jqSignXorIsPerCharacterAndKeepsLength() {
        assertEquals("ba`", WjxSubmitCodec.jqSign("abc", 3))
        assertEquals(nonce.length, WjxSubmitCodec.jqSign(nonce, 5).length)
    }

    @Test
    fun jqSignNormalizesNegativeKtimes() {
        // floorMod(-1, 10) = 9
        assertEquals(WjxSubmitCodec.jqSign("abc", 9), WjxSubmitCodec.jqSign("abc", -1))
    }

    // ------------------------------------------------------ encodeSubmitData

    @Test
    fun encodeSubmitDataJoinsWithDollarAndBrace() {
        assertEquals(
            "1\$张三}2\$2024001}3\$生物1班",
            WjxSubmitCodec.encodeSubmitData(listOf(1 to "张三", 2 to "2024001", 3 to "生物1班")),
        )
    }

    @Test
    fun encodeSubmitDataKeepsPipeInsideValue() {
        // | 是多选分隔符，绝不能 escape
        assertEquals("2\$1|3", WjxSubmitCodec.encodeSubmitData(listOf(2 to "1|3")))
        assertEquals("1\$aξb}3\$1|3", WjxSubmitCodec.encodeSubmitData(listOf(1 to "aξb", 3 to "1|3")))
    }

    @Test
    fun encodeSubmitDataSortsByTopicRegardlessOfInputOrder() {
        assertEquals(
            "1\$A}2\$B}3\$C",
            WjxSubmitCodec.encodeSubmitData(listOf(3 to "C", 1 to "A", 2 to "B")),
        )
    }

    @Test
    fun encodeSubmitDataReturnsEmptyStringForEmptyInput() {
        assertEquals("", WjxSubmitCodec.encodeSubmitData(emptyList()))
    }

    @Test
    fun encodeSubmitDataRejectsTopicBelowOne() {
        assertThrows(IllegalArgumentException::class.java) {
            WjxSubmitCodec.encodeSubmitData(listOf(0 to "x"))
        }
    }

    @Test
    fun encodeSubmitDataLetsLaterDuplicateTopicWin() {
        assertEquals("1\$B", WjxSubmitCodec.encodeSubmitData(listOf(1 to "A", 1 to "B")))
    }

    @Test
    fun jqSignKeyIsOneForBothKtimesZeroAndOne() {
        // **codec 层事实**：key(0) == key(1) == 1，所以这两个入参签名相同。
        // 注意提交器发的是 effectiveKtimes = max(4, pageKtimes)，页面 ktimes=0 时签名用 key=4（与 key=1 不同）。
        assertEquals(WjxSubmitCodec.jqSign(nonce, 0), WjxSubmitCodec.jqSign(nonce, 1))
        assertEquals(WjxSubmitCodec.jqSign("abc", 0), WjxSubmitCodec.jqSign("abc", 1))
    }

    @Test
    fun jqSignOutputChangesWhenKeyChanges() {
        // 只断言「key 不同 -> 输出不同」，不绑定具体字符（避免过度约束）
        assertTrue(WjxSubmitCodec.jqSign("abc", 1) != WjxSubmitCodec.jqSign("abc", 2))
    }

}
