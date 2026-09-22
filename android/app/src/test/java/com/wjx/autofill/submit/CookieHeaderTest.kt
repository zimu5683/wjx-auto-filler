package com.wjx.autofill.submit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 头解析与 origin 口径（契约 §13.3，纯 Kotlin 可单测）。
 *
 * 关键点：cookie 注入（方向①）与清理（方向④）的基准 origin 必须跟随**问卷所在主机**。
 * 写死 https://www.wjx.cn/ 会让 v.wjx.cn 的验证页收不到引擎会话 cookie。
 */
class CookieHeaderTest {

    @Test
    fun parseSkipsEmptyAndMalformedSegments() {
        assertEquals(mapOf("a" to "b", "c" to "d"), CookieHeader.parse("a=b; c=d"))
        assertEquals(mapOf("a" to "b"), CookieHeader.parse("a=b;; ;novalue;"))
        assertEquals(mapOf("k" to ""), CookieHeader.parse("k="))
        assertEquals(emptyMap<String, String>(), CookieHeader.parse(null))
        assertEquals(emptyMap<String, String>(), CookieHeader.parse("   "))
    }

    @Test
    fun entriesRoundTripsThroughParse() {
        val cookies = mapOf("ASP.NET_SessionId" to "abc123", "wjx" to "1")
        assertEquals(cookies, CookieHeader.parse(CookieHeader.entries(cookies).joinToString("; ")))
    }

    @Test
    fun originOfFollowsSurveyHost() {
        assertEquals("https://v.wjx.cn/", CookieHeader.originOf("https://v.wjx.cn/vm/P2M09FG.aspx"))
        assertEquals("https://www.wjx.cn/", CookieHeader.originOf("https://www.wjx.cn/vm/Q0DQewW.aspx"))
        assertEquals("https://www2.wjx.cn/", CookieHeader.originOf("https://www2.wjx.cn/vm/Q0DQewW.aspx"))
    }

    @Test
    fun originOfNormalizesHostCaseAndKeepsPort() {
        assertEquals("https://v.wjx.cn/", CookieHeader.originOf("https://V.WJX.CN/vm/P2M09FG.aspx"))
        assertEquals("https://v.wjx.cn:8443/", CookieHeader.originOf("https://v.wjx.cn:8443/vm/P2M09FG.aspx"))
    }

    @Test
    fun originOfFallsBackOnInvalidInput() {
        val fallback = "https://www.wjx.cn/"
        assertEquals(fallback, CookieHeader.originOf(""))
        assertEquals(fallback, CookieHeader.originOf("   "))
        assertEquals(fallback, CookieHeader.originOf("不是 URL"))
        assertEquals(fallback, CookieHeader.originOf("https:///vm/x.aspx"))
    }

    @Test
    fun injectionAndCleanupUseTheSameOrigin() {
        // 契约 §13.3：方向①注入与方向④清理必须用同一个 cookieBase（同一函数、同一入参）
        val surveyUrl = "https://v.wjx.cn/vm/P2M09FG.aspx"
        val base = CookieHeader.originOf(surveyUrl)
        assertEquals(CookieHeader.originOf(surveyUrl), base)
        assertTrue("必须是问卷主机而不是写死的 www，实际：" + base, base.contains("v.wjx.cn"))
    }
}
