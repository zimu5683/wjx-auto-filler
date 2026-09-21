package com.wjx.autofill.config

import com.wjx.autofill.wjx.AnswerPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * templates.json 往返 + 容错（契约 §4.2–§4.4）。
 *
 * TemplateStore 构造函数收的是目录（不是 Context），TemplatesJson 是自写纯 Kotlin JSON，
 * 所以这些测试全部跑在 JVM 上，不需要 Robolectric，也不依赖 org.json。
 */
class TemplateStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun template(
        id: String = "tpl-1",
        name: String = "测试问卷",
        url: String = "https://www.wjx.cn/vm/Q0DQewW.aspx",
        shortId: String = "Q0DQewW",
        concurrency: Int = 2,
        groups: List<AnswerGroup> = listOf(
            AnswerGroup(
                name = "第一组",
                pairs = listOf(AnswerPair("1", "张三"), AnswerPair("2", "2024001")),
            ),
            AnswerGroup(
                name = "第二组",
                pairs = listOf(AnswerPair("1", "李四"), AnswerPair("2", "2024002")),
            ),
        ),
    ): MappingTemplate = MappingTemplate(
        id = id,
        name = name,
        surveyUrl = url,
        shortId = shortId,
        groups = groups,
        concurrency = concurrency,
        updatedAt = 1234L,
    )

    private fun store(): TemplateStore = TemplateStore(temp.root)

    // ------------------------------------------------------------ 往返

    @Test
    fun saveThenLoadRoundTripsEverything() {
        val store = store()
        assertTrue(store.save(listOf(template()), exportedAt = 99L, appVersion = "1.0.0"))

        val outcome = store.load()
        assertEquals(1, outcome.templates.size)
        assertEquals(emptyList<String>(), outcome.failures)

        val loaded = outcome.templates[0]
        assertEquals("tpl-1", loaded.id)
        assertEquals("测试问卷", loaded.name)
        assertEquals("https://www.wjx.cn/vm/Q0DQewW.aspx", loaded.surveyUrl)
        assertEquals("Q0DQewW", loaded.shortId)
        assertEquals(2, loaded.concurrency)
        assertEquals(1234L, loaded.updatedAt)
        assertEquals(2, loaded.groups.size)
        assertEquals("第一组", loaded.groups[0].name)
        assertEquals(listOf(AnswerPair("1", "张三"), AnswerPair("2", "2024001")), loaded.groups[0].pairs)
        assertEquals(listOf(AnswerPair("1", "李四"), AnswerPair("2", "2024002")), loaded.groups[1].pairs)
    }

    @Test
    fun savedFileIsPrettyJsonWithSchemaVersionOne() {
        val store = store()
        store.save(listOf(template()))
        val text = store.file.readText(Charsets.UTF_8)

        assertTrue("应含 schemaVersion", text.contains("\"schemaVersion\": 1"))
        assertTrue("应是 2 空格缩进", text.contains("\n  \"exportedAt\""))
        assertTrue("应含 groups", text.contains("\"groups\""))
    }

    @Test
    fun atomicWriteLeavesNoTemporaryFile() {
        val store = store()
        store.save(listOf(template()))
        val leftovers = store.file.parentFile.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertEquals(emptyList<Any>(), leftovers)
    }

    // ------------------------------------------------------------ 容错

    @Test
    fun missingFileLoadsAsEmptyWithoutFailure() {
        val outcome = store().load()
        assertEquals(emptyList<MappingTemplate>(), outcome.templates)
        assertEquals(emptyList<String>(), outcome.failures)
        assertNull(outcome.backupFile)
    }

    @Test
    fun corruptJsonIsBackedUpAndOriginalContentIsPreserved() {
        val store = store()
        val broken = "{ 这不是 JSON"
        store.file.writeText(broken, Charsets.UTF_8)

        val outcome = store.load()
        assertEquals(emptyList<MappingTemplate>(), outcome.templates)
        assertTrue("应有致命失败说明", outcome.failures.isNotEmpty())
        val backup = outcome.backupFile
        assertNotNull("损坏文件必须被改名备份", backup)
        assertTrue("备份文件名应带 .bad- 前缀", backup!!.name.startsWith("templates.json.bad-"))
        assertEquals("备份必须保留原文", broken, backup.readText(Charsets.UTF_8))
        assertFalse("原文件已被改名", store.file.exists())
    }

    @Test
    fun schemaVersionTwoIsRejectedWithoutTouchingTheFile() {
        val store = store()
        val text = "{\"schemaVersion\":2,\"templates\":[]}"
        store.file.writeText(text, Charsets.UTF_8)

        val outcome = store.load()
        assertEquals(emptyList<MappingTemplate>(), outcome.templates)
        assertTrue(outcome.failures.isNotEmpty())
        assertNull("合法 JSON 不该被备份", outcome.backupFile)
        assertTrue("原文件必须保留", store.file.exists())
        assertEquals(text, store.file.readText(Charsets.UTF_8))
    }

    @Test
    fun missingShortIdIsDerivedFromUrlWithWarning() {
        val json = """
            {
              "schemaVersion": 1,
              "templates": [
                {"id":"a","name":"缺 shortId","surveyUrl":"https://www.wjx.cn/vm/Q0DQewW.aspx",
                 "groups":[{"name":"g","pairs":[{"field":"1","value":"x"}]}]}
              ]
            }
        """.trimIndent()

        val report = TemplatesJson.decode(json)
        assertNotNull(report)
        assertEquals(1, report!!.imported.size)
        assertEquals("Q0DQewW", report.imported[0].shortId)
        assertTrue(report.warnings.any { it.contains("shortId") })
    }

    @Test
    fun invalidSurveyUrlFailsOnlyThatTemplate() {
        val json = """
            {
              "schemaVersion": 1,
              "templates": [
                {"id":"bad","name":"坏链接","surveyUrl":"https://example.com/x.aspx",
                 "groups":[{"name":"g","pairs":[{"field":"1","value":"x"}]}]},
                {"id":"good","name":"好链接","surveyUrl":"https://www.wjx.cn/vm/Q0DQewW.aspx",
                 "groups":[{"name":"g","pairs":[{"field":"1","value":"x"}]}]}
              ]
            }
        """.trimIndent()

        val report = TemplatesJson.decode(json)!!
        assertEquals(1, report.imported.size)
        assertEquals("good", report.imported[0].id)
        assertEquals(1, report.failures.size)
        assertTrue(report.failures[0].contains("坏链接"))
    }

    @Test
    fun concurrencyIsClampedWithWarning() {
        val json = """
            {
              "schemaVersion": 1,
              "templates": [
                {"id":"a","name":"越界并发","surveyUrl":"https://www.wjx.cn/vm/Q0DQewW.aspx",
                 "concurrency":99,
                 "groups":[{"name":"g","pairs":[{"field":"1","value":"x"}]}]}
              ]
            }
        """.trimIndent()

        val report = TemplatesJson.decode(json)!!
        assertEquals(MappingTemplate.MAX_CONCURRENCY, report.imported[0].concurrency)
        assertTrue(report.warnings.any { it.contains("concurrency") })
    }

    @Test
    fun duplicateIdIsRegenerated() {
        val json = """
            {
              "schemaVersion": 1,
              "templates": [
                {"id":"dup","name":"重复","surveyUrl":"https://www.wjx.cn/vm/Q0DQewW.aspx",
                 "groups":[{"name":"g","pairs":[{"field":"1","value":"x"}]}]}
              ]
            }
        """.trimIndent()

        val report = TemplatesJson.decode(json, existingIds = setOf("dup"))!!
        assertEquals(1, report.imported.size)
        assertTrue(report.imported[0].id != "dup")
        assertTrue(report.warnings.any { it.contains("id 重复") })
    }

    @Test
    fun missingRequiredNameFailsThatTemplate() {
        val json = """
            {
              "schemaVersion": 1,
              "templates": [
                {"id":"a","surveyUrl":"https://www.wjx.cn/vm/Q0DQewW.aspx",
                 "groups":[{"name":"g","pairs":[{"field":"1","value":"x"}]}]}
              ]
            }
        """.trimIndent()

        val report = TemplatesJson.decode(json)!!
        assertEquals(0, report.imported.size)
        assertTrue(report.failures.any { it.contains("name") })
    }

    @Test
    fun topLevelNonObjectIsTreatedAsCorrupt() {
        assertNull(TemplatesJson.decode("[1,2,3]"))
        assertNull(TemplatesJson.decode("not json at all"))
    }

    // ------------------------------------------------------------ 纯逻辑

    @Test
    fun blankFieldPairsAreFilteredOut() {
        val group = AnswerGroup(
            name = "g",
            pairs = listOf(AnswerPair("", "x"), AnswerPair("2", "y"), AnswerPair("  ", "z")),
        )
        assertEquals(listOf(AnswerPair("2", "y")), group.effectivePairs())
        assertTrue(group.isUsable)
        assertFalse(AnswerGroup.blank().isUsable)
    }

    @Test
    fun normalizedFillsEmptyGroupsAndClampsConcurrency() {
        val empty = MappingTemplate(
            id = "a",
            name = "空",
            surveyUrl = "https://www.wjx.cn/vm/Q0DQewW.aspx",
            shortId = "Q0DQewW",
            groups = emptyList(),
            concurrency = 99,
        ).normalized(now = 7L)

        assertEquals(1, empty.groups.size)
        assertEquals(MappingTemplate.MAX_CONCURRENCY, empty.concurrency)
        assertEquals(7L, empty.updatedAt)
    }

    @Test
    fun exportFileGoesToExportsDirectory() {
        val store = store()
        val written = store.writeExport("wjx-templates-20260922-010203.json", "{}")
        assertNotNull(written)
        assertTrue(written!!.isFile)
        assertEquals(store.exportDirectory, written.parentFile)
    }

    @Test
    fun exportFileNameMatchesContractPattern() {
        val name = TemplateStore.exportFileName(0L)
        assertTrue("实际：" + name, Regex("""wjx-templates-\d{8}-\d{6}\.json""").matches(name))
    }
}
