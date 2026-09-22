package com.wjx.autofill.ui

import com.wjx.autofill.wjx.QuestionType
import com.wjx.autofill.wjx.SurveyModel
import com.wjx.autofill.wjx.SurveyQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 顶部问卷状态条（T33 收敛口径）。
 *
 * 本轮 bug 的断言化：**解析不到开放时间时必须 UNKNOWN，绝不能因为上一次解析残留的 model 而显示 OPEN**。
 * 优先级：页面解析值（parsed，>0）> 定时任务里用户手填值（scheduled，>0）> UNKNOWN。
 */
class SurveyStatusTest {

    private val now = 1_790_127_180_000L          // 2026-09-23 09:33（北京时间）
    private val future = now + 3_600_000L          // 一小时后
    private val past = now - 3_600_000L            // 一小时前

    private fun model(openAtMillis: Long?): SurveyModel = SurveyModel(
        url = "https://www.wjx.cn/vm/Q0DQewW.aspx",
        shortId = "Q0DQewW",
        title = "测试",
        questions = listOf(SurveyQuestion(1, "姓名", QuestionType.TEXT, emptyList())),
        submitUrl = "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW",
        jqnonce = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6",
        ktimes = 3,
        startTime = "",
        captchaType = null,
        cookies = emptyMap(),
        openAtMillis = openAtMillis,
    )

    // ------------------------------------------------------------ of()

    @Test
    fun parsedFutureMeansNotOpen() {
        val status = SurveyStatus.of(future, null, now)
        assertEquals(SurveyOpenState.NOT_OPEN, status.state)
        assertEquals(future, status.openAtMillis)
    }

    @Test
    fun parsedPastMeansOpen() {
        assertEquals(SurveyOpenState.OPEN, SurveyStatus.of(past, null, now).state)
    }

    @Test
    fun boundaryIsInclusive() {
        assertEquals("now == openAt 视为已开放", SurveyOpenState.OPEN, SurveyStatus.of(now, null, now).state)
        assertEquals(SurveyOpenState.NOT_OPEN, SurveyStatus.of(now + 1, null, now).state)
    }

    @Test
    fun noParsedAndNoScheduledIsUnknown() {
        // 本轮 bug 的核心断言：没有解析值 → UNKNOWN，绝不因残留状态而 OPEN
        val status = SurveyStatus.of(null, null, now)
        assertEquals(SurveyOpenState.UNKNOWN, status.state)
        assertNull(status.openAtMillis)
    }

    @Test
    fun parsedZeroOrNegativeCountsAsAbsent() {
        assertEquals(SurveyOpenState.UNKNOWN, SurveyStatus.of(0L, null, now).state)
        assertEquals(SurveyOpenState.UNKNOWN, SurveyStatus.of(-1L, null, now).state)
        assertEquals(SurveyOpenState.UNKNOWN, SurveyStatus.of(0L, 0L, now).state)
    }

    @Test
    fun scheduledIsUsedWhenParsedMissing() {
        assertEquals(SurveyOpenState.NOT_OPEN, SurveyStatus.of(null, future, now).state)
        assertEquals(SurveyOpenState.OPEN, SurveyStatus.of(null, past, now).state)
        assertEquals(future, SurveyStatus.of(null, future, now).openAtMillis)
    }

    @Test
    fun parsedWinsOverScheduled() {
        // 页面解析值优先：即使手填值已过，页面说没开放就是 NOT_OPEN
        val status = SurveyStatus.of(future, past, now)
        assertEquals(SurveyOpenState.NOT_OPEN, status.state)
        assertEquals(future, status.openAtMillis)
    }

    // ------------------------------------------------------ fromModel()

    @Test
    fun nullModelIsUnknownEvenWhenNowIsLate() {
        // 防回归：旧 model 被清空/解析失败后，状态条必须回到 UNKNOWN
        assertEquals(SurveyOpenState.UNKNOWN, SurveyStatus.fromModel(null, null, now).state)
        assertEquals(SurveyOpenState.UNKNOWN, SurveyStatus.fromModel(null, null, Long.MAX_VALUE).state)
    }

    @Test
    fun modelWithoutOpenTimeIsUnknown() {
        assertEquals(SurveyOpenState.UNKNOWN, SurveyStatus.fromModel(model(null), null, now).state)
    }

    @Test
    fun modelOpenTimeDrivesState() {
        assertEquals(SurveyOpenState.NOT_OPEN, SurveyStatus.fromModel(model(future), null, now).state)
        assertEquals(SurveyOpenState.OPEN, SurveyStatus.fromModel(model(past), null, now).state)
        assertEquals(future, SurveyStatus.fromModel(model(future), null, now).openAtMillis)
    }

    @Test
    fun modelWithoutOpenTimeFallsBackToScheduled() {
        val status = SurveyStatus.fromModel(model(null), future, now)
        assertEquals(SurveyOpenState.NOT_OPEN, status.state)
        assertEquals(future, status.openAtMillis)
    }

    @Test
    fun modelWithoutOpenTimeDoesNotInheritStaleScheduledState() {
        // 解析不到 + 没有定时任务 → UNKNOWN（不是 OPEN，也不是 NOT_OPEN）
        val status = SurveyStatus.fromModel(model(null), null, now)
        assertEquals(SurveyOpenState.UNKNOWN, status.state)
        assertNull(status.openAtMillis)
    }
}
