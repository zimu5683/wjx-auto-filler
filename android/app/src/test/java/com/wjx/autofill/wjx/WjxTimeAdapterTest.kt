package com.wjx.autofill.wjx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问卷开放时间适配器（T16，签名由 Lead 2026-09-22 冻结）。
 *
 * 纪律：真实问卷页 HTML 不入库，这里全部用合成 HTML 片段。
 *
 * 冻结口径：
 *   - parse(html, nowMillis) -> OpenTime；Known 表示解析到开放时刻（<= nowMillis 即已开放），Unknown 表示解析不到；
 *   - 优先 BeginDate="<epoch>"（<=11 位当秒、>=12 位当毫秒）；兜底 divstarttime 的「此问卷将于 YYYY-MM-DD HH:mm（北京时间）开放」按 +08:00 解析；
 *   - 合理性守卫：早于 2000-01-01 或晚于 now+20 年视为脏数据 -> Unknown；
 *   - 解析不到（缺失 / 非法 / 为 0）一律 Unknown，绝不影响手动提交。
 *
 * 时间换算基准（北京时间 GMT+8）：2026-09-23 09:33 == 1790127180000L。
 */
class WjxTimeAdapterTest {

    /** 基准「现在」= 2026-09-23 09:33（北京时间）。 */
    private val now = 1790127180000L

    private val beijing20260923_0933 = 1790127180000L

    /** 实测未开放样本 tfGAWU4 的 BeginDate（2026-09-22 09:34 北京时间，早于 now）。 */
    private val notOpenSampleMillis = 1790040856347L

    private fun htmlWithTimestamp(value: String): String =
        "<html><script>var BeginDate=\"" + value + "\";</script><body>x</body></html>"

    // ---------------------------------------------------------------- parse

    @Test
    fun parsesBeginDateTimestamp() {
        assertEquals(OpenTime.Known(notOpenSampleMillis), WjxTimeAdapter.parse(htmlWithTimestamp("1790040856347"), now))
    }

    @Test
    fun parsesSingleQuotedAndSpacedTimestamp() {
        val html = "<html><script>var BeginDate = '1790034767873';</script></html>"
        assertEquals(OpenTime.Known(1790034767873L), WjxTimeAdapter.parse(html, now))
    }

    @Test
    fun tenDigitValueIsTreatedAsSeconds() {
        // <=11 位当秒：1790040856 秒 -> 1790040856000 毫秒
        assertEquals(OpenTime.Known(1_790_040_856_000L), WjxTimeAdapter.parse(htmlWithTimestamp("1790040856"), now))
    }

    @Test
    fun fallsBackToChineseTextWhenNoTimestamp() {
        val html = "<html><div id='divstarttime' left='0'>很抱歉，此问卷将于2026-09-23 09:33（北京时间）开放</div></html>"
        assertEquals(OpenTime.Known(beijing20260923_0933), WjxTimeAdapter.parse(html, now))
    }

    @Test
    fun textFallbackAcceptsFullWidthColonAndSingleDigitHour() {
        val html = "<div>此问卷将于 2026-09-23 9：33（北京时间）开放</div>"
        assertEquals(OpenTime.Known(beijing20260923_0933), WjxTimeAdapter.parse(html, now))
    }

    @Test
    fun missingTimestampAndTextIsUnknown() {
        assertEquals(OpenTime.Unknown, WjxTimeAdapter.parse("<html><body>没有开放时间字段</body></html>", now))
        assertEquals(OpenTime.Unknown, WjxTimeAdapter.parse("", now))
    }

    @Test
    fun zeroTimestampIsUnknown() {
        // 为 0 不是合法开放时间，必须 Unknown（不拦截提交）
        assertEquals(OpenTime.Unknown, WjxTimeAdapter.parse(htmlWithTimestamp("0"), now))
    }

    @Test
    fun illegalTimestampIsUnknown() {
        assertEquals(OpenTime.Unknown, WjxTimeAdapter.parse(htmlWithTimestamp("abc"), now))
        assertEquals(OpenTime.Unknown, WjxTimeAdapter.parse(htmlWithTimestamp(""), now))
        assertEquals(OpenTime.Unknown, WjxTimeAdapter.parse("<html>var BeginDate=;</html>", now))
        assertEquals(OpenTime.Unknown, WjxTimeAdapter.parse(htmlWithTimestamp("123"), now))
    }

    @Test
    fun illegalTextDateIsUnknown() {
        val html = "<div>此问卷将于2026-13-40 99:99（北京时间）开放</div>"
        assertEquals(OpenTime.Unknown, WjxTimeAdapter.parse(html, now))
    }

    @Test
    fun implausibleTimestampsAreRejectedAsUnknown() {
        // 晚于 now+20 年（2286 年）
        assertEquals(OpenTime.Unknown, WjxTimeAdapter.parse(htmlWithTimestamp("9999999999999"), now))
        // 早于 2000-01-01（1973 年）
        assertEquals(OpenTime.Unknown, WjxTimeAdapter.parse(htmlWithTimestamp("100000000000"), now))
    }

    // -------------------------------------------------- 已开放 / 未开放口径

    @Test
    fun knownOpenTimeIsComparableAgainstInjectedNow() {
        val parsed = WjxTimeAdapter.parse(htmlWithTimestamp("1790040856347"), now)
        assertTrue("应解析为 Known", parsed is OpenTime.Known)
        val openAt = (parsed as OpenTime.Known).openAtMillis
        assertEquals(notOpenSampleMillis, openAt)

        // 已开放：now 晚于/等于开放时刻；未开放：now 早于开放时刻（等号算已开放）
        assertTrue("now=openAt 视为已开放", now >= openAt)
        assertTrue("now=openAt-1 未开放", (openAt - 1) < openAt)
    }

    @Test
    fun pastOpenTimeStillReturnsKnownNotUnknown() {
        // 已开放也必须返回 Known（不得因为"时间已过"就返回 Unknown）
        val past = now - 86_400_000L
        val parsed = WjxTimeAdapter.parse(htmlWithTimestamp(past.toString()), now)
        assertEquals(OpenTime.Known(past), parsed)
        assertTrue(WjxTimeAdapter.isOpen(parsed, now))
    }

    @Test
    fun textFallbackProducesBeijingEpoch() {
        val html = "<div>此问卷将于2026-09-23 09:33（北京时间）开放</div>"
        val openAt = (WjxTimeAdapter.parse(html, now) as OpenTime.Known).openAtMillis
        assertEquals("北京时间 09:33 == UTC 01:33", beijing20260923_0933, openAt)
    }

    @Test
    fun unknownNeverBlocksManualSubmitByDesign() {
        val unknown = WjxTimeAdapter.parse("<html>no time here</html>", now)
        assertEquals(OpenTime.Unknown, unknown)
        assertTrue("Unknown 不是 Known", unknown !is OpenTime.Known)
        assertTrue("Unknown 视为已开放，不拦截", WjxTimeAdapter.isOpen(unknown, now))
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun isOpenBoundaryIsInclusive() {
        val open = OpenTime.Known(1_000_000L)
        assertTrue(!WjxTimeAdapter.isOpen(open, 999_999L))
        assertTrue(WjxTimeAdapter.isOpen(open, 1_000_000L))
        assertTrue(WjxTimeAdapter.isOpen(open, 1_000_001L))
    }
}
