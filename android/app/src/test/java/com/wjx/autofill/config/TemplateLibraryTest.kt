package com.wjx.autofill.config

import com.wjx.autofill.wjx.AnswerPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板库纯函数（T23 冻结签名）：同名覆盖、异名追加、按 id 删除。
 *
 * 同名判据：name.trim() **精确相等**（大小写敏感）；surveyUrl 不参与身份判定。
 */
class TemplateLibraryTest {

    private fun template(
        id: String = "id-a",
        name: String = "模板A",
        url: String = "https://www.wjx.cn/vm/Q0DQewW.aspx",
        pairValue: String = "v1",
    ): MappingTemplate = MappingTemplate(
        id = id,
        name = name,
        surveyUrl = url,
        shortId = "Q0DQewW",
        groups = listOf(AnswerGroup("g", listOf(AnswerPair("1", pairValue)))),
        concurrency = 2,
        updatedAt = 1L,
    )

    // ------------------------------------------------------------ save

    @Test
    fun differentNamesAppendSoThreeSavesGiveThreeTemplates() {
        var list = emptyList<MappingTemplate>()
        list = TemplateLibrary.save(list, template(id = "id-1", name = "第一份"))
        list = TemplateLibrary.save(list, template(id = "id-2", name = "第二份"))
        list = TemplateLibrary.save(list, template(id = "id-3", name = "第三份"))

        assertEquals(3, list.size)
        assertEquals(listOf("第一份", "第二份", "第三份"), list.map { it.name })
        assertEquals(listOf("id-1", "id-2", "id-3"), list.map { it.id })
    }

    @Test
    fun sameNameTwiceKeepsOneTemplateAndOverwritesContent() {
        var list = emptyList<MappingTemplate>()
        list = TemplateLibrary.save(list, template(id = "id-1", name = "我的模板", pairValue = "旧值"))
        list = TemplateLibrary.save(list, template(id = "id-2", name = "我的模板", pairValue = "新值"))

        assertEquals("同名应只留 1 条", 1, list.size)
        assertEquals("应保留原 id（用户视角是更新同一份）", "id-1", list[0].id)
        assertEquals("内容应被覆盖", "新值", list[0].groups[0].pairs[0].value)
    }

    @Test
    fun sameNameMatchesAfterTrimAndIsCaseSensitive() {
        var list = TemplateLibrary.save(emptyList(), template(id = "id-1", name = "  My Template  "))
        assertEquals("保存时 name 应被 trim", "My Template", list[0].name)

        list = TemplateLibrary.save(list, template(id = "id-2", name = "My Template"))
        assertEquals("trim 后同名 → 覆盖", 1, list.size)
        assertEquals("id-1", list[0].id)

        list = TemplateLibrary.save(list, template(id = "id-3", name = "my template"))
        assertEquals("大小写敏感 → 视为不同模板", 2, list.size)
        assertEquals(listOf("My Template", "my template"), list.map { it.name })
    }

    @Test
    fun sameNameDifferentUrlStillOverwrites() {
        var list = TemplateLibrary.save(emptyList(), template(id = "id-1", name = "同一份"))
        list = TemplateLibrary.save(
            list,
            template(id = "id-2", name = "同一份", url = "https://v.wjx.cn/vm/P2M09FG.aspx"),
        )
        assertEquals(1, list.size)
        assertEquals("https://v.wjx.cn/vm/P2M09FG.aspx", list[0].surveyUrl)
    }

    @Test
    fun saveRejectsBlankName() {
        val thrown = try {
            TemplateLibrary.save(emptyList(), template(name = "   "))
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("空名字是编程错误，必须拒绝", thrown != null)
    }

    // ------------------------------------------------------------ delete

    @Test
    fun deleteRemovesOnlyTheGivenId() {
        val list = listOf(
            template(id = "id-1", name = "A"),
            template(id = "id-2", name = "B"),
            template(id = "id-3", name = "C"),
        )
        val after = TemplateLibrary.delete(list, "id-2")
        assertEquals(listOf("id-1", "id-3"), after.map { it.id })
        assertEquals("原列表不应被修改", 3, list.size)
    }

    @Test
    fun deleteUnknownIdReturnsListUnchanged() {
        val list = listOf(template(id = "id-1", name = "A"))
        assertEquals(list, TemplateLibrary.delete(list, "不存在"))
    }

    @Test
    fun saveThenDeleteThenSaveAgainStaysConsistent() {
        var list = TemplateLibrary.save(emptyList(), template(id = "id-1", name = "A"))
        list = TemplateLibrary.save(list, template(id = "id-2", name = "B"))
        list = TemplateLibrary.delete(list, "id-1")
        list = TemplateLibrary.save(list, template(id = "id-3", name = "C"))
        assertEquals(listOf("B", "C"), list.map { it.name })
        assertEquals(listOf("id-2", "id-3"), list.map { it.id })
    }

    // ---------------------------------------------------------- findByName

    @Test
    fun findByNameUsesTrimmedExactMatch() {
        val list = listOf(template(id = "id-1", name = "目标"))
        assertEquals("id-1", TemplateLibrary.findByName(list, "  目标  ")?.id)
        assertNull(TemplateLibrary.findByName(list, "目标2"))
        assertNull("空名字不匹配任何模板", TemplateLibrary.findByName(list, "   "))
    }

    // ------------------------------------------------ 与持久化同步（T23 验收点）

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun listStaysInSyncWithPersistenceAfterSaveAndDelete() {
        // Lead 要求：「删除后列表与持久化同步」。这里把 TemplateLibrary 的纯函数结果
        // 通过 TemplateStore 落盘再读回，验证内存列表与磁盘一致。
        val store = TemplateStore(temp.root)

        var list = emptyList<MappingTemplate>()
        list = TemplateLibrary.save(list, template(id = "id-1", name = "第一份", pairValue = "a"))
        list = TemplateLibrary.save(list, template(id = "id-2", name = "第二份", pairValue = "b"))
        list = TemplateLibrary.save(list, template(id = "id-3", name = "第三份", pairValue = "c"))
        assertEquals(3, list.size)
        assertTrue(store.save(list))

        var loaded = store.load().templates
        assertEquals("落盘后应仍是 3 份", 3, loaded.size)
        assertEquals(listOf("第一份", "第二份", "第三份"), loaded.map { it.name })

        // 同名覆盖后落盘：仍是 3 份，但第二份内容变了
        list = TemplateLibrary.save(list, template(id = "id-9", name = "第二份", pairValue = "b2"))
        assertEquals(3, list.size)
        assertTrue(store.save(list))
        loaded = store.load().templates
        assertEquals(3, loaded.size)
        assertEquals("b2", loaded.first { it.name == "第二份" }.groups[0].pairs[0].value)
        assertEquals("同名覆盖必须保留原 id", "id-2", loaded.first { it.name == "第二份" }.id)

        // 删除后落盘：2 份
        list = TemplateLibrary.delete(list, "id-1")
        assertTrue(store.save(list))
        loaded = store.load().templates
        assertEquals("删除后磁盘应同步为 2 份", 2, loaded.size)
        assertEquals(listOf("第二份", "第三份"), loaded.map { it.name })
    }

}
