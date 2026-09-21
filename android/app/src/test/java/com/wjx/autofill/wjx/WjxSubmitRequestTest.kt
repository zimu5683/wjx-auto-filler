package com.wjx.autofill.wjx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * 请求构造（契约 §5.3 + §13.10）。WjxSubmitRequest 是 api-debug 2026-09-22 抽出的公开纯函数，
 * 让「提交 URL / body 长什么样」可以在不发任何网络请求的前提下被断言。
 *
 * 口径提醒：
 *   - URL query 用 urlEncodeQuery（空格 → %20，对齐页面 JS 的 encodeURIComponent）；
 *   - POST body 用 urlEncode（空格 → +，对齐 jQuery $.param）。
 */
class WjxSubmitRequestTest {

    private val nonce = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6"

    private fun model(
        submitUrl: String = "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW",
        startTime: String = "2026/9/22 10:00:00",
        ktimes: Int = 3,
        captchaType: Int? = 2,
        sceneId: String? = null,
    ): SurveyModel = SurveyModel(
        url = "https://www.wjx.cn/vm/Q0DQewW.aspx",
        shortId = "Q0DQewW",
        title = "测试",
        questions = listOf(SurveyQuestion(1, "姓名", QuestionType.TEXT, emptyList())),
        submitUrl = submitUrl,
        jqnonce = nonce,
        ktimes = ktimes,
        startTime = startTime,
        captchaType = captchaType,
        cookies = emptyMap(),
        sceneId = sceneId,
    )

    // ------------------------------------------------------------- URL

    @Test
    fun submitUrlCarriesEveryRequiredQueryParameter() {
        val url = WjxSubmitRequest.buildSubmitUrl(model())!!
        assertTrue(url.startsWith("https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW"))
        assertTrue("缺 starttime：" + url, url.contains("&starttime="))
        assertTrue("缺 ktimes：" + url, url.contains("&ktimes=3"))
        assertTrue("缺 t：" + url, url.contains("&t="))
        assertTrue("缺 jqnonce：" + url, url.contains("&jqnonce="))
        assertTrue("缺 jqsign：" + url, url.contains("&jqsign="))
        assertTrue("缺 capt：" + url, url.contains("&capt=2"))
    }

    @Test
    fun submitUrlEncodesJqSignWithTheCodecResult() {
        val url = WjxSubmitRequest.buildSubmitUrl(model())!!
        val encoded = url.substringAfter("&jqsign=").substringBefore("&")
        assertEquals(WjxSubmitCodec.jqSign(nonce, 3), URLDecoder.decode(encoded, "UTF-8"))
    }

    @Test
    fun submitUrlUsesEncodeUriComponentSemantics() {
        val url = WjxSubmitRequest.buildSubmitUrl(model(startTime = "2026/9/22 10:00:00"))!!
        val raw = url.substringAfter("&starttime=").substringBefore("&")
        assertTrue("空格必须编成 %20（不是 +）：" + url, url.contains("%20"))
        assertFalse("URL query 不应出现 +：" + url, raw.contains("+"))
        assertEquals("2026/9/22 10:00:00", URLDecoder.decode(raw, "UTF-8"))
    }

    @Test
    fun submitUrlOmitsStartTimeAndCaptWhenAbsent() {
        val url = WjxSubmitRequest.buildSubmitUrl(model(startTime = "", captchaType = null))!!
        assertFalse("startTime 为空时不应带 starttime", url.contains("&starttime="))
        assertFalse("captchaType 为 null 时不应带 capt", url.contains("&capt="))
    }

    @Test
    fun submitUrlIsNullForNonWjxOrNonHttpsTarget() {
        assertNull(WjxSubmitRequest.buildSubmitUrl(model(submitUrl = "https://example.com/x")))
        assertNull(WjxSubmitRequest.buildSubmitUrl(model(submitUrl = "http://www.wjx.cn/joinnew/processjq.ashx?shortid=x")))
    }

    // ------------------------------------------------------------- body

    @Test
    fun bodyWithoutTokenCarriesOnlySubmitdata() {
        val body = WjxSubmitRequest.buildSubmitBody(model(), listOf(1 to "张三"), null)

        assertTrue("应以 submitdata= 开头：" + body, body.startsWith("submitdata="))
        assertFalse("令牌为空时绝不能带 captchaVerifyParam：" + body, body.contains("captchaVerifyParam"))
        assertFalse("令牌为空时绝不能带 sceneId：" + body, body.contains("sceneId"))
        assertEquals("1\$张三", URLDecoder.decode(body.removePrefix("submitdata="), "UTF-8"))
    }

    @Test
    fun blankTokenBehavesLikeNullToken() {
        val body = WjxSubmitRequest.buildSubmitBody(model(), listOf(1 to "张三"), "   ")
        assertFalse(body.contains("captchaVerifyParam"))
        assertFalse(body.contains("sceneId"))
    }

    @Test
    fun bodyWithTokenButNoSceneIdOmitsSceneIdField() {
        val body = WjxSubmitRequest.buildSubmitBody(model(sceneId = null), listOf(1 to "张三"), "TOKEN-123")

        assertTrue("应带 captchaVerifyParam：" + body, body.contains("captchaVerifyParam=TOKEN-123"))
        assertFalse("sceneId 缺省时不得携带该字段：" + body, body.contains("sceneId"))
        assertTrue(body.startsWith("submitdata="))
    }

    @Test
    fun bodyWithTokenAndSceneIdCarriesBothFields() {
        val body = WjxSubmitRequest.buildSubmitBody(model(sceneId = "q0hcfsca"), listOf(1 to "张三"), "TOKEN-123")

        assertTrue(body.contains("captchaVerifyParam=TOKEN-123"))
        assertTrue(body.contains("sceneId=q0hcfsca"))
        assertTrue(body.indexOf("captchaVerifyParam") < body.indexOf("sceneId"))
    }

    @Test
    fun bodyUsesFormUrlEncodedSemanticsAndKeepsPipe() {
        val body = WjxSubmitRequest.buildSubmitBody(model(), listOf(1 to "张 三", 2 to "1|2"), null)
        val encoded = body.removePrefix("submitdata=")
        assertTrue("body 里空格应编成 +：" + body, encoded.contains("+"))
        assertFalse("body 不应出现 %20：" + body, encoded.contains("%20"))
        assertTrue("多选竖线必须保留：" + body, encoded.contains("%7C"))
        assertEquals("1\$张 三}2\$1|2", URLDecoder.decode(encoded, "UTF-8"))
    }

    @Test
    fun bodyEncodesSpecialCharactersThroughCodecOutput() {
        // 已 escape 的载荷里可能有 ξ 等非 ASCII，必须被 URL 编码后放进 body
        val body = WjxSubmitRequest.buildSubmitBody(model(), listOf(1 to "aξb"), null)
        assertFalse("非 ASCII 不应原样出现：" + body, body.contains("ξ"))
        assertEquals("1\$aξb", URLDecoder.decode(body.removePrefix("submitdata="), "UTF-8"))
    }

    @Test
    fun bodyIsEmptySubmitdataWhenPairsAreEmpty() {
        val body = WjxSubmitRequest.buildSubmitBody(model(), emptyList(), null)
        assertEquals("submitdata=", body)
    }
}
