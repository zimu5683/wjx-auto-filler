package com.wjx.autofill.wjx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问卷星 URL 规则（契约 §5.1）+ **子域支持**（T13）。
 *
 * 背景：用户新样本是 v.wjx.cn 短链，原正则只认 www.wjx.cn / 裸域名 → 引擎报「链接无效」。
 * 规则：https + host 为 wjx.cn 或其任意子域 + path 为 /(vm|jq|m)/<shortId>.aspx。
 */
class WjxUrlsTest {

    private val shortId = "Q0DQewW"

    private fun url(host: String, path: String = "vm"): String =
        "https://" + host + "/" + path + "/" + shortId + ".aspx"

    // ---------------------------------------------------------------- 正例

    @Test
    fun extractsShortIdFromWwwAndBareDomain() {
        assertEquals(shortId, WjxUrls.shortIdOf(url("www.wjx.cn")))
        assertEquals(shortId, WjxUrls.shortIdOf(url("wjx.cn")))
    }

    @Test
    fun extractsShortIdFromSubdomains() {
        assertEquals(shortId, WjxUrls.shortIdOf(url("v.wjx.cn")))
        assertEquals(shortId, WjxUrls.shortIdOf(url("www2.wjx.cn")))
        assertEquals(shortId, WjxUrls.shortIdOf(url("survey.wjx.cn")))
    }

    @Test
    fun hostMatchingIsCaseInsensitive() {
        assertEquals(shortId, WjxUrls.shortIdOf("https://V.WJX.CN/vm/Q0DQewW.aspx"))
        assertEquals(shortId, WjxUrls.shortIdOf("HTTPS://V.WJX.CN/VM/Q0DQewW.aspx"))
    }

    @Test
    fun acceptsJqAndMPathsOnSubdomains() {
        assertEquals(shortId, WjxUrls.shortIdOf(url("v.wjx.cn", "jq")))
        assertEquals(shortId, WjxUrls.shortIdOf(url("v.wjx.cn", "m")))
        assertEquals(shortId, WjxUrls.shortIdOf(url("www2.wjx.cn", "jq")))
    }

    @Test
    fun ignoresTrailingQueryAndWhitespace() {
        assertEquals(shortId, WjxUrls.shortIdOf("  " + url("v.wjx.cn") + "?from=qr  "))
        assertEquals(shortId, WjxUrls.shortIdOf(url("v.wjx.cn") + "#hash"))
    }

    // ---------------------------------------------------------------- 负例

    @Test
    fun rejectsHttpAndLookalikeHosts() {
        assertNull("http 必须拒绝", WjxUrls.shortIdOf("http://v.wjx.cn/vm/Q0DQewW.aspx"))
        assertNull("evilwjx.cn 必须拒绝", WjxUrls.shortIdOf(url("evilwjx.cn")))
        assertNull("evil-wjx.cn 必须拒绝（子域与 wjx.cn 之间必须有 .）", WjxUrls.shortIdOf(url("evil-wjx.cn")))
        assertNull("wjx.cn.evil.com 必须拒绝", WjxUrls.shortIdOf(url("wjx.cn.evil.com")))
        assertNull("其它域名必须拒绝", WjxUrls.shortIdOf(url("example.com")))
        assertNull("wjx.top 不是引擎支持的域名", WjxUrls.shortIdOf(url("v.wjx.top")))
    }

    @Test
    fun rejectsNonSurveyPathsAndBadShortIds() {
        assertNull("非 vm/jq/m 路径", WjxUrls.shortIdOf("https://v.wjx.cn/xx/Q0DQewW.aspx"))
        assertNull("3 字符 shortId 越界", WjxUrls.shortIdOf("https://v.wjx.cn/vm/abc.aspx"))
        assertNull("33 字符 shortId 越界", WjxUrls.shortIdOf("https://v.wjx.cn/vm/" + "A".repeat(33) + ".aspx"))
        assertNull("非法字符 -", WjxUrls.shortIdOf("https://v.wjx.cn/vm/Q0DQ-wW.aspx"))
        assertNull("后缀不是 .aspx", WjxUrls.shortIdOf("https://v.wjx.cn/vm/Q0DQewW.html"))
    }

    @Test
    fun shortIdBoundariesAreInclusive() {
        assertEquals("A".repeat(4), WjxUrls.shortIdOf("https://v.wjx.cn/vm/AAAA.aspx"))
        assertEquals("A".repeat(32), WjxUrls.shortIdOf("https://v.wjx.cn/vm/" + "A".repeat(32) + ".aspx"))
    }

    // ------------------------------------------------------- host 判定

    @Test
    fun isAllowedHostAcceptsWjxCnSubdomainsOnly() {
        assertTrue(WjxUrls.isAllowedHost("wjx.cn"))
        assertTrue(WjxUrls.isAllowedHost("v.wjx.cn"))
        assertTrue(WjxUrls.isAllowedHost("www2.wjx.cn"))
        assertTrue(WjxUrls.isAllowedHost("WWW2.WJX.CN"))
        assertFalse("evilwjx.cn 不是子域", WjxUrls.isAllowedHost("evilwjx.cn"))
        assertFalse("wjx.cn.evil.com 不是子域", WjxUrls.isAllowedHost("wjx.cn.evil.com"))
        assertFalse("引擎只支持 wjx.cn", WjxUrls.isAllowedHost("wjx.top"))
        assertFalse(WjxUrls.isAllowedHost(null))
        assertFalse(WjxUrls.isAllowedHost(""))
    }

    @Test
    fun isHttpsWjxRequiresHttpsAndWjxCnSubdomain() {
        assertTrue(WjxUrls.isHttpsWjx("https://v.wjx.cn/vm/Q0DQewW.aspx"))
        assertTrue(WjxUrls.isHttpsWjx("https://www2.wjx.cn/jq/Q0DQewW.aspx"))
        assertFalse("http 不算", WjxUrls.isHttpsWjx("http://v.wjx.cn/vm/Q0DQewW.aspx"))
        assertFalse("仿冒域名不算", WjxUrls.isHttpsWjx("https://evilwjx.cn/vm/Q0DQewW.aspx"))
        assertFalse(WjxUrls.isHttpsWjx("https://wjx.cn.evil.com/vm/Q0DQewW.aspx"))
    }
}
