package com.wjx.autofill.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 问卷链接校验的纯逻辑部分（不联网）。 */
class SurveyLinkValidatorTest {

    @Test
    fun extractsUrlFromRawQrText() {
        assertEquals(
            "https://www.wjx.cn/vm/Q0DQewW.aspx",
            SurveyLinkValidator.extractUrl("https://www.wjx.cn/vm/Q0DQewW.aspx"),
        )
        assertEquals(
            "https://www.wjx.cn/vm/Q0DQewW.aspx",
            SurveyLinkValidator.extractUrl("扫码填写：https://www.wjx.cn/vm/Q0DQewW.aspx"),
        )
    }

    @Test
    fun trimsTrailingChinesePunctuation() {
        assertEquals(
            "https://www.wjx.cn/vm/Q0DQewW.aspx",
            SurveyLinkValidator.extractUrl("https://www.wjx.cn/vm/Q0DQewW.aspx。"),
        )
    }

    @Test
    fun returnsNullWhenNoUrlPresent() {
        assertNull(SurveyLinkValidator.extractUrl("这不是一个链接"))
        assertNull(SurveyLinkValidator.extractUrl(""))
    }

    @Test
    fun allowsWjxDomainsAndSubdomainsOnly() {
        assertTrue(SurveyLinkValidator.isAllowedHost("www.wjx.cn"))
        assertTrue(SurveyLinkValidator.isAllowedHost("wjx.cn"))
        assertTrue(SurveyLinkValidator.isAllowedHost("www.wjx.top"))
        assertTrue(SurveyLinkValidator.isAllowedHost("sojump.com"))
        assertFalse(SurveyLinkValidator.isAllowedHost("evil-wjx.cn"))
        assertFalse(SurveyLinkValidator.isAllowedHost("wjx.cn.evil.com"))
        assertFalse(SurveyLinkValidator.isAllowedHost(null))
    }

    @Test
    fun upgradesHttpToHttpsForAllowedHost() {
        val check = SurveyLinkValidator.checkSyntax("http://www.wjx.cn/vm/Q0DQewW.aspx")
        assertTrue("应判定为可接受链接，实际：" + check, check is LinkCheck.Ok)
        assertEquals("https://www.wjx.cn/vm/Q0DQewW.aspx", (check as LinkCheck.Ok).url)
    }

    @Test
    fun acceptsTheSampleSurveyUrl() {
        val check = SurveyLinkValidator.checkSyntax("https://www.wjx.cn/vm/Q0DQewW.aspx")
        assertTrue(check is LinkCheck.Ok)
        assertEquals("https://www.wjx.cn/vm/Q0DQewW.aspx", (check as LinkCheck.Ok).url)
    }

    @Test
    fun rejectsNonWjxHostAndGarbage() {
        assertTrue(SurveyLinkValidator.checkSyntax("https://example.com/vm/Q0DQewW.aspx") is LinkCheck.Bad)
        assertTrue(SurveyLinkValidator.checkSyntax("随便一段文字") is LinkCheck.Bad)
        assertTrue(SurveyLinkValidator.checkSyntax("ftp://www.wjx.cn/x") is LinkCheck.Bad)
    }
}
