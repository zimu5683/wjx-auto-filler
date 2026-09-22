package com.wjx.autofill.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开放时间输入框的取值逻辑（T28 冻结签名）。
 *
 * 用户要求（Lead 转述并冻结）：
 * - 解析到开放时间（非空且 > 0）→ **强制覆盖**输入框（以页面为准，避免沿用旧值）；
 * - 解析不到（null / <= 0）→ **保留原值**（用户手填的不能被清掉）。
 */
class ScheduleTimeTest {

    private val openAt = 1_790_127_180_000L   // 2026-09-23 09:33（北京时间）

    // ------------------------------------------- applyParsedOpenTime

    @Test
    fun parsedValueOverwritesWhateverWasThere() {
        val applied = ScheduleTime.applyParsedOpenTime("2026-01-01 00:00", openAt)
        assertEquals(ScheduleTime.format(openAt), applied)
    }

    @Test
    fun parsedValueFillsEmptyInput() {
        assertEquals(ScheduleTime.format(openAt), ScheduleTime.applyParsedOpenTime(null, openAt))
        assertEquals(ScheduleTime.format(openAt), ScheduleTime.applyParsedOpenTime("", openAt))
        assertEquals(ScheduleTime.format(openAt), ScheduleTime.applyParsedOpenTime("   ", openAt))
    }

    @Test
    fun nullParsedKeepsUserInputUntouched() {
        assertEquals("2026-01-01 00:00", ScheduleTime.applyParsedOpenTime("2026-01-01 00:00", null))
        assertEquals("用户手改的值", ScheduleTime.applyParsedOpenTime("用户手改的值", null))
        assertNull("两边都空 → 仍是 null", ScheduleTime.applyParsedOpenTime(null, null))
    }

    @Test
    fun nonPositiveParsedAlsoKeepsUserInput() {
        // 0 / 负数不是合法开放时间，等同于「解析不到」
        assertEquals("2026-01-01 00:00", ScheduleTime.applyParsedOpenTime("2026-01-01 00:00", 0L))
        assertEquals("2026-01-01 00:00", ScheduleTime.applyParsedOpenTime("2026-01-01 00:00", -1L))
        assertNull(ScheduleTime.applyParsedOpenTime(null, 0L))
    }

    // ------------------------------------------- parse / format

    @Test
    fun formatAndParseRoundTrip() {
        // 注意：ScheduleTime 用 **JVM 默认时区**（设备上即手机时区），不是固定 +08:00；
        // 所以只断言往返一致，不断言具体字符串（JVM/CI 默认时区可能是 UTC）。
        // 固定北京时间格式化在 WjxTimeAdapter.formatBeijingTime（另有单测）。
        val text = ScheduleTime.format(openAt)
        assertTrue("格式应为 yyyy-MM-dd HH:mm，实际：" + text, Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(text))
        assertEquals(openAt, ScheduleTime.parse(text))
    }

    @Test
    fun parseRejectsGarbageAndBlank() {
        assertNull(ScheduleTime.parse(null))
        assertNull(ScheduleTime.parse(""))
        assertNull(ScheduleTime.parse("   "))
        assertNull(ScheduleTime.parse("不是时间"))
        assertNull(ScheduleTime.parse("2026-09-23"))
    }

    @Test
    fun parseTrimsInput() {
        // 与时区无关：两侧用同一时区解析
        assertEquals(ScheduleTime.parse("2026-09-23 09:33"), ScheduleTime.parse("  2026-09-23 09:33  "))
    }
}
