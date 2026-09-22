package com.wjx.autofill.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * templates.json 的 surveyUrl 校验 + **子域支持**（T13）。
 *
 * 契约 §5.1：https + wjx.cn 子域 + /(vm|jq|m)/<shortId 4..32>.aspx；
 * shortId 从链接推导（缺失/非法/与链接冲突时以链接为准，见 §4.4）。
 */
class TemplatesJsonSubdomainTest {

    private fun json(url: String): String = """
        {
          "schemaVersion": 1,
          "templates": [
            {"id":"a","name":"子域","surveyUrl":"$url",
             "groups":[{"name":"g","pairs":[{"field":"1","value":"x"}]}]}
          ]
        }
    """.trimIndent()

    private fun import(url: String): ImportReport {
        val report = TemplatesJson.decode(json(url))
        assertNotNull("顶层应是 JSON 对象：" + url, report)
        return report!!
    }

    @Test
    fun importsTemplatesFromWjxSubdomains() {
        for (url in listOf(
            "https://www.wjx.cn/vm/Q0DQewW.aspx",
            "https://wjx.cn/vm/Q0DQewW.aspx",
            "https://v.wjx.cn/vm/Q0DQewW.aspx",
            "https://www2.wjx.cn/vm/Q0DQewW.aspx",
        )) {
            val report = import(url)
            assertEquals("应导入：" + url, 1, report.imported.size)
            assertEquals("shortId 应从链接推导", "Q0DQewW", report.imported[0].shortId)
            assertEquals(url, report.imported[0].surveyUrl)
        }
    }

    @Test
    fun acceptsJqAndMPathsOnSubdomains() {
        for (url in listOf(
            "https://v.wjx.cn/jq/Q0DQewW.aspx",
            "https://v.wjx.cn/m/Q0DQewW.aspx",
        )) {
            assertEquals("应导入：" + url, 1, import(url).imported.size)
        }
    }

    @Test
    fun rejectsLookalikeHostsHttpAndBadPaths() {
        for (url in listOf(
            "https://evilwjx.cn/vm/Q0DQewW.aspx",
            "https://evil-wjx.cn/vm/Q0DQewW.aspx",
            "https://wjx.cn.evil.com/vm/Q0DQewW.aspx",
            "http://v.wjx.cn/vm/Q0DQewW.aspx",
            "https://v.wjx.cn/xx/Q0DQewW.aspx",
            "https://v.wjx.cn/vm/abc.aspx",
            "https://v.wjx.cn/vm/" + "A".repeat(33) + ".aspx",
        )) {
            val report = import(url)
            assertEquals("应拒绝：" + url, 0, report.imported.size)
            assertTrue("应给出失败原因：" + url, report.failures.isNotEmpty())
        }
    }
}
