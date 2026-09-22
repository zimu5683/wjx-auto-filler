package com.wjx.autofill.wjx

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提交器的**零网络**分支（契约 §5.3 本地错误 + §7 字段匹配）。
 *
 * 关键口径（Lead 2026-09-22 裁定）：**useAliVerify 本地门控已取消** ——
 * 引擎总是先尝试提交，只有服务端返回业务码 7/22 才判 E_CAPTCHA。
 * 所以这里不再有「useAliVerify=true + 无令牌 → 本地 E_CAPTCHA」的用例；
 * 改为证明它**确实走到了匹配 / URL 构造步骤**（若还有短路，这两种情况都会返回 E_CAPTCHA）：
 *   - useAliVerify=true + 未匹配字段 → E_UNMATCHED
 *   - useAliVerify=true + 非 wjx submitUrl → E_URL
 *   - 无有效答案 → E_EMPTY
 * 真正联网（真的发出 POST）由 SubmitCoordinatorTest 的 fake submitter 与
 * WjxEngineIntegrationTest（默认跳过）覆盖。
 */
class HttpWjxSubmitterTest {

    private fun model(
        useAliVerify: Boolean = false,
        submitUrl: String = "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW",
        questions: List<SurveyQuestion> = listOf(SurveyQuestion(1, "姓名", QuestionType.TEXT, emptyList())),
    ): SurveyModel = SurveyModel(
        url = "https://www.wjx.cn/vm/Q0DQewW.aspx",
        shortId = "Q0DQewW",
        title = "测试",
        questions = questions,
        submitUrl = submitUrl,
        jqnonce = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6",
        ktimes = 3,
        startTime = "",
        captchaType = if (useAliVerify) 2 else null,
        cookies = emptyMap(),
        useAliVerify = useAliVerify,
    )

    @Test
    fun useAliVerifyNoLongerShortCircuitsLocally() = runBlocking {
        // 取消本地门控后：useAliVerify=true + 无令牌也必须走到匹配步骤（未匹配字段 → E_UNMATCHED，而不是 E_CAPTCHA）
        val result = HttpWjxSubmitter().submit(model(useAliVerify = true), listOf(AnswerPair("不存在的字段", "x")), null)
        assertEquals(SubmitErrorCode.UNMATCHED, result.errorCode)
        assertEquals(0, result.httpStatus)
    }

    @Test
    fun useAliVerifyStillReachesUrlValidation() = runBlocking {
        // 走到 URL 构造步骤：非 wjx 域名 → E_URL（同样证明没有被本地验证码门控短路）
        val result = HttpWjxSubmitter().submit(
            model(useAliVerify = true, submitUrl = "https://example.com/processjq.ashx"),
            listOf(AnswerPair("1", "张三")),
            null,
        )
        assertEquals(SubmitErrorCode.URL, result.errorCode)
        assertEquals(0, result.httpStatus)
    }

    @Test
    fun emptyAnswersAreReportedBeforeAnythingElse() = runBlocking {
        val result = HttpWjxSubmitter().submit(model(), listOf(AnswerPair("1", "")), null)
        assertEquals(SubmitErrorCode.EMPTY, result.errorCode)
        assertEquals(0, result.httpStatus)
    }

    @Test
    fun unmatchedFieldFailsLocally() = runBlocking {
        val result = HttpWjxSubmitter().submit(model(), listOf(AnswerPair("不存在的字段", "x")), null)
        assertEquals(SubmitErrorCode.UNMATCHED, result.errorCode)
        assertEquals(0, result.httpStatus)
        assertNull(result.raw)
    }

    @Test
    fun nonWjxSubmitUrlFailsWithUrlError() = runBlocking {
        val result = HttpWjxSubmitter().submit(
            model(submitUrl = "https://example.com/processjq.ashx"),
            listOf(AnswerPair("1", "张三")),
            null,
        )
        assertEquals(SubmitErrorCode.URL, result.errorCode)
        assertEquals(0, result.httpStatus)
    }

    @Test
    fun tokenPresenceDoesNotChangeLocalOutcomes() = runBlocking {
        // 门控删除后，令牌有无不再产生本地分支差异：两种情况都走到匹配 → E_UNMATCHED
        val withoutToken = HttpWjxSubmitter().submit(
            model(useAliVerify = true),
            listOf(AnswerPair("不存在的字段", "x")),
            null,
        )
        val withToken = HttpWjxSubmitter().submit(
            model(useAliVerify = true),
            listOf(AnswerPair("不存在的字段", "x")),
            "TOKEN-123",
        )
        assertEquals(SubmitErrorCode.UNMATCHED, withoutToken.errorCode)
        assertEquals(SubmitErrorCode.UNMATCHED, withToken.errorCode)
    }
}
