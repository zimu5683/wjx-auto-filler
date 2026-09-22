package com.wjx.autofill.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「待人工验证」记录持久化（T17 自动进入验证页依赖它）。
 *
 * 前台服务无法直接把令牌交回 UI，所以把现场（模板 id / 组号 / URL / cookies）落盘，
 * 通知打开 MainActivity 后由它读回恢复状态机。纯 java.io + MiniJson，可 JVM 直测。
 */
class PendingCaptchaStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): PendingCaptchaStore = PendingCaptchaStore(temp.root)

    private fun record(
        templateId: String = "tpl-1",
        groupIndex: Int = 2,
        surveyUrl: String = "https://v.wjx.cn/vm/P2M09FG.aspx",
        cookies: Map<String, String> = mapOf("ASP.NET_SessionId" to "abc", "wjx" to "1"),
        createdAtMillis: Long = 1_790_127_180_000L,
    ): PendingCaptcha = PendingCaptcha(
        templateId = templateId,
        groupIndex = groupIndex,
        surveyUrl = surveyUrl,
        cookies = cookies,
        createdAtMillis = createdAtMillis,
    )

    @Test
    fun saveThenLoadRoundTripsEverything() {
        val s = store()
        assertTrue(s.save(record()))

        val loaded = s.load()
        assertEquals("tpl-1", loaded!!.templateId)
        assertEquals(2, loaded.groupIndex)
        assertEquals("https://v.wjx.cn/vm/P2M09FG.aspx", loaded.surveyUrl)
        assertEquals(mapOf("ASP.NET_SessionId" to "abc", "wjx" to "1"), loaded.cookies)
        assertEquals(1_790_127_180_000L, loaded.createdAtMillis)
    }

    @Test
    fun fileNameIsPendingCaptchaJson() {
        assertEquals("pending-captcha.json", store().file.name)
    }

    @Test
    fun missingFileLoadsAsNull() {
        assertNull(store().load())
    }

    @Test
    fun clearRemovesRecord() {
        val s = store()
        assertTrue(s.save(record()))
        assertTrue(s.clear())
        assertNull(s.load())
    }

    @Test
    fun corruptJsonLoadsAsNullWithoutCrashing() {
        val s = store()
        s.file.writeText("{ 这不是 JSON", Charsets.UTF_8)
        assertNull(s.load())
    }

    @Test
    fun payloadWithoutTemplateIdIsIgnored() {
        val s = store()
        s.file.writeText("{\"schemaVersion\":1,\"pending\":{\"groupIndex\":1}}", Charsets.UTF_8)
        assertNull(s.load())
    }

    @Test
    fun emptyCookiesAndMissingOptionalFieldsAreTolerated() {
        val s = store()
        s.file.writeText(
            "{\"schemaVersion\":1,\"pending\":{\"templateId\":\"tpl-9\"}}",
            Charsets.UTF_8,
        )
        val loaded = s.load()!!
        assertEquals("tpl-9", loaded.templateId)
        assertEquals(0, loaded.groupIndex)
        assertEquals("", loaded.surveyUrl)
        assertEquals(0L, loaded.createdAtMillis)
        assertTrue(loaded.cookies.isEmpty())
    }

    @Test
    fun atomicWriteLeavesNoTemporaryFile() {
        val s = store()
        s.save(record())
        val leftovers = s.file.parentFile.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertEquals(emptyList<Any>(), leftovers)
    }
}
