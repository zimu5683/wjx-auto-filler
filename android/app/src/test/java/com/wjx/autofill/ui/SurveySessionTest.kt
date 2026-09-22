package com.wjx.autofill.ui

import com.wjx.autofill.wjx.QuestionType
import com.wjx.autofill.wjx.SurveyModel
import com.wjx.autofill.wjx.SurveyQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析会话 cookie 的归属校验（T33）。
 *
 * 本轮 bug 的断言化：解析 A 问卷成功、再解析 B 问卷失败时，**绝不能把 A 的会话 cookie 注入 B 的验证页**（串号）。
 * 规则：模型为 null / 当前 URL 为空 / **模型 URL 与当前 URL 不一致** → 一律空 Map。
 */
class SurveySessionTest {

    private val urlA = "https://www.wjx.cn/vm/Q0DQewW.aspx"
    private val urlB = "https://v.wjx.cn/vm/P2M09FG.aspx"

    private fun model(url: String, cookies: Map<String, String> = mapOf("ASP.NET_SessionId" to "abc")): SurveyModel =
        SurveyModel(
            url = url,
            shortId = "Q0DQewW",
            title = "测试",
            questions = listOf(SurveyQuestion(1, "姓名", QuestionType.TEXT, emptyList())),
            submitUrl = "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW",
            jqnonce = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6",
            ktimes = 3,
            startTime = "",
            captchaType = null,
            cookies = cookies,
        )

    @Test
    fun matchingUrlReturnsModelCookies() {
        val cookies = mapOf("ASP.NET_SessionId" to "abc", "wjx" to "1")
        assertEquals(cookies, surveyCookiesFor(model(urlA, cookies), urlA))
    }

    @Test
    fun differentUrlReturnsEmptyToPreventCrossContamination() {
        // 核心断言：A 问卷的会话不得注入 B 问卷
        assertTrue(surveyCookiesFor(model(urlA), urlB).isEmpty())
        assertTrue(surveyCookiesFor(model(urlB), urlA).isEmpty())
    }

    @Test
    fun nullModelReturnsEmpty() {
        assertTrue(surveyCookiesFor(null, urlA).isEmpty())
        assertTrue(surveyCookiesFor(null, "").isEmpty())
    }

    @Test
    fun blankCurrentUrlReturnsEmpty() {
        assertTrue(surveyCookiesFor(model(urlA), "").isEmpty())
        assertTrue(surveyCookiesFor(model(urlA), "   ").isEmpty())
    }

    @Test
    fun comparisonTrimsBothSides() {
        assertEquals(
            mapOf("ASP.NET_SessionId" to "abc"),
            surveyCookiesFor(model("  " + urlA + "  "), " " + urlA + " "),
        )
    }

    @Test
    fun modelWithEmptyCookiesReturnsEmpty() {
        assertTrue(surveyCookiesFor(model(urlA, emptyMap()), urlA).isEmpty())
    }

    @Test
    fun sameHostDifferentPathIsStillDifferentSurvey() {
        // 同域名不同问卷也必须视为不同（避免用错会话）
        val other = "https://www.wjx.cn/vm/rRESgvn.aspx"
        assertTrue(surveyCookiesFor(model(urlA), other).isEmpty())
    }
}
