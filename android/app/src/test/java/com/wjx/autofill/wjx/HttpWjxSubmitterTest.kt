package com.wjx.autofill.wjx

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提交器的**零网络**分支（契约 §5.3 第 0 步门控 + §7 本地错误）。
 *
 * 这些用例全部在发起网络请求之前返回，所以可以安全地在单测里跑：
 *   - useAliVerify=true 且无令牌 → E_CAPTCHA（httpStatus=0、raw=null）
 *   - 无有效答案 → E_EMPTY
 *   - 字段未匹配 → E_UNMATCHED
 *   - submitUrl 非 https wjx.cn → E_URL
 *   - useAliVerify=true 但**有**令牌 → 跳过门控（用未匹配字段证明它走到了匹配步骤，仍不发请求）
 * 真正联网的那条路径由 WjxEngineIntegrationTest 覆盖（默认跳过）。
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
    fun aliVerifyGateReturnsCaptchaWithoutToken() = runBlocking {
        val result = HttpWjxSubmitter().submit(model(useAliVerify = true), listOf(AnswerPair("1", "张三")), null)

        assertEquals(false, result.ok)
        assertEquals(SubmitErrorCode.CAPTCHA, result.errorCode)
        assertEquals("硬门控不应发请求 → httpStatus 0", 0, result.httpStatus)
        assertNull("硬门控不应有响应体", result.raw)
        assertTrue(result.message.contains("安全校验"))
    }

    @Test
    fun aliVerifyGateTreatsBlankTokenAsNoToken() = runBlocking {
        val result = HttpWjxSubmitter().submit(model(useAliVerify = true), listOf(AnswerPair("1", "张三")), "   ")
        assertEquals(SubmitErrorCode.CAPTCHA, result.errorCode)
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
    fun tokenBypassesGateAndReachesMatching() = runBlocking {
        // 有令牌 → 跳过门控；用未匹配字段证明它确实走到了匹配步骤（若被门控会是 E_CAPTCHA），且仍未发请求
        val result = HttpWjxSubmitter().submit(
            model(useAliVerify = true),
            listOf(AnswerPair("不存在的字段", "x")),
            "TOKEN-123",
        )
        assertEquals(SubmitErrorCode.UNMATCHED, result.errorCode)
    }
}
