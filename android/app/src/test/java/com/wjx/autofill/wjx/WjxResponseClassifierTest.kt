package com.wjx.autofill.wjx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响应分类器（契约 §8.4）。铁律：**HTTP 200 绝不等于提交成功**。
 *
 * 向量全部来自 T3 真实响应（tools/wjx-probe/evidence），不造数据：
 *   - 7〒需要安全校验，请重新提交！  ← 样本问卷被阿里云验证码拦截的真实响应
 *   - 10〒                          ← 页面 JS 的成功业务码
 *   - 5〒请输入正确的学号            ← 服务端业务校验失败
 */
class WjxResponseClassifierTest {

    @Test
    fun realCaptchaResponseIsClassifiedAsCaptchaTerminalState() {
        val result = WjxResponseClassifier.classify(200, "7〒需要安全校验，请重新提交！")

        assertEquals(false, result.ok)
        assertEquals(200, result.httpStatus)
        assertEquals(SubmitErrorCode.CAPTCHA, result.errorCode)
        assertTrue("message 应说明安全校验，实际：" + result.message, result.message.contains("安全校验"))
        assertEquals("raw 必须保留原文", "7〒需要安全校验，请重新提交！", result.raw)
    }

    @Test
    fun businessCodeTenIsSuccess() {
        val result = WjxResponseClassifier.classify(200, "10〒")

        assertEquals(true, result.ok)
        assertNull("成功时 errorCode 必须为 null", result.errorCode)
        assertEquals("提交成功", result.message)
        assertEquals(200, result.httpStatus)
    }

    @Test
    fun businessCodeFiveIsRejectedWithServerText() {
        val result = WjxResponseClassifier.classify(200, "5〒请输入正确的学号")

        assertEquals(false, result.ok)
        assertEquals(SubmitErrorCode.REJECTED, result.errorCode)
        assertTrue("message 应带服务端文案，实际：" + result.message, result.message.contains("请输入正确的学号"))
    }

    @Test
    fun businessCodeElevenIsSuccessPerContract() {
        // 契约 §8.4 第 4 条 + api-debug 2026-09-22 确认：10/11 都是成功
        // （JS afterSubmit 的 addpostlog(v,4,…) 分支；11 未经服务端实测，属 JS 分支推导）
        val result = WjxResponseClassifier.classify(200, "11〒")
        assertEquals(true, result.ok)
        assertNull(result.errorCode)
        assertEquals("提交成功", result.message)
    }

    @Test
    fun businessCodeTwentyTwoIsCaptcha() {
        // 22 = submit_need_validate2，与 7 同属验证码终态（契约 §8.4 第 4 条）
        val result = WjxResponseClassifier.classify(200, "22〒")
        assertEquals(false, result.ok)
        assertEquals(SubmitErrorCode.CAPTCHA, result.errorCode)
    }

    @Test
    fun otherBusinessCodeIsRejected() {
        val result = WjxResponseClassifier.classify(200, "9〒系统繁忙")
        assertEquals(SubmitErrorCode.REJECTED, result.errorCode)
        assertTrue(result.message.contains("系统繁忙"))
    }

    @Test
    fun businessCodeWithoutDetailFallsBackToBodyHead() {
        val result = WjxResponseClassifier.classify(200, "9〒")
        assertEquals(SubmitErrorCode.REJECTED, result.errorCode)
        assertTrue(result.message.contains("9〒"))
    }

    @Test
    fun aliyunWafMarkerIsCaptcha() {
        val result = WjxResponseClassifier.classify(200, "<html><script>var x='aliyunwaf';</script></html>")
        assertEquals(SubmitErrorCode.CAPTCHA, result.errorCode)
    }

    @Test
    fun keywordFallbackWhenNoBusinessCode() {
        val result = WjxResponseClassifier.classify(200, "<div>请完成验证码后重试</div>")
        assertEquals(SubmitErrorCode.CAPTCHA, result.errorCode)
    }

    @Test
    fun emptyBodyIsParseError() {
        val result = WjxResponseClassifier.classify(200, "")
        assertEquals(SubmitErrorCode.PARSE, result.errorCode)
        assertEquals("响应为空", result.message)
    }

    @Test
    fun htmlPageWithoutKeywordsIsParseError() {
        val result = WjxResponseClassifier.classify(200, "<html><body>survey closed</body></html>")
        assertEquals(SubmitErrorCode.PARSE, result.errorCode)
    }

    @Test
    fun unknownBodyIsUnknownError() {
        val result = WjxResponseClassifier.classify(200, "something completely unexpected")
        assertEquals(SubmitErrorCode.UNKNOWN, result.errorCode)
        assertTrue(result.message.startsWith("未知错误："))
    }

    @Test
    fun non2xxIsHttpErrorWithStatus() {
        val result = WjxResponseClassifier.classify(500, "10〒")
        assertEquals(false, result.ok)
        assertEquals(500, result.httpStatus)
        assertEquals(SubmitErrorCode.HTTP, result.errorCode)
        assertTrue("message 应带状态码，实际：" + result.message, result.message.contains("500"))
    }

    @Test
    fun rawIsTruncatedToLimit() {
        val body = "x".repeat(WjxResponseClassifier.RAW_LIMIT + 500)
        val result = WjxResponseClassifier.classify(200, body)
        assertEquals(WjxResponseClassifier.RAW_LIMIT, result.raw!!.length)
    }
}
