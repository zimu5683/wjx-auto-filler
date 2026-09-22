package com.wjx.autofill.ui

import com.wjx.autofill.config.AnswerGroup
import com.wjx.autofill.config.MappingTemplate
import com.wjx.autofill.wjx.AnswerPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑区状态（T28：映射列表改为可增长列表）。
 *
 * 说明：EditorState 只有 saveTo/restoreFrom 依赖 android.os.Bundle；本测试只碰纯逻辑方法，
 * 在 JVM 单测（mockable android.jar）里可直接跑。
 * **列表在屏幕上的可见性/滚动行为无法 JVM 单测** → 见 VERIFY-REPORT「需真机人工验证」第 5 条。
 */
class EditorStateTest {

    private fun pair(i: Int) = AnswerPair(field = i.toString(), value = "值" + i)

    private fun draft(
        name: String = "草稿",
        id: String = "draft-id",
        pairs: List<AnswerPair> = listOf(pair(1)),
    ): MappingTemplate = MappingTemplate(
        id = id,
        name = name,
        surveyUrl = "https://www.wjx.cn/vm/Q0DQewW.aspx",
        shortId = "Q0DQewW",
        groups = listOf(AnswerGroup("方案 1", pairs)),
        concurrency = 2,
        updatedAt = 1L,
    )

    // ------------------------------------------------------ 映射行（可增长）

    @Test
    fun eightMappingRowsAreAllKept() {
        // 用户要求：加 8 条字段映射应全部保留（数据层）
        val state = EditorState()
        val rows = (1..8).map { pair(it) }
        state.updateGroupPairs(rows)

        assertEquals(8, state.group().pairs.size)
        assertEquals((1..8).map { it.toString() }, state.group().pairs.map { it.field })
        assertEquals((1..8).map { "值" + it }, state.group().pairs.map { it.value })
    }

    @Test
    fun clearingRowsLeavesEmptyGroup() {
        val state = EditorState()
        state.updateGroupPairs((1..8).map { pair(it) })
        state.updateGroupPairs(emptyList())
        assertTrue("清空后应为空列表", state.group().pairs.isEmpty())
    }

    @Test
    fun removingOneRowKeepsTheRestInOrder() {
        val state = EditorState()
        state.updateGroupPairs((1..5).map { pair(it) })
        val without3 = state.group().pairs.filterNot { it.field == "3" }
        state.updateGroupPairs(without3)
        assertEquals(listOf("1", "2", "4", "5"), state.group().pairs.map { it.field })
    }

    @Test
    fun groupIsCreatedOnDemandForHigherIndex() {
        val state = EditorState()
        state.groupIndex = 2
        state.updateGroupPairs(listOf(pair(1)))
        assertEquals(3, state.current.groups.size)
        assertEquals("方案 3", state.current.groups[2].name)
        assertEquals(1, state.group().pairs.size)
    }

    @Test
    fun groupNamesFallBackToPlanNumber() {
        val state = EditorState()
        state.current = state.current.copy(
            groups = listOf(AnswerGroup("", emptyList()), AnswerGroup("自定义", emptyList())),
        )
        assertEquals(listOf("方案 1", "自定义"), state.groupNames())
    }

    @Test
    fun groupFallsBackToBlankWhenIndexOutOfRange() {
        val state = EditorState()
        state.groupIndex = 5
        assertTrue(state.group().pairs.isEmpty())
    }

    // ------------------------------------------------------ 模板库（按名字）

    @Test
    fun saveTemplateByNameOverwritesSameName() {
        val state = EditorState()
        state.saveTemplateByName(draft(name = "我的模板", id = "id-1", pairs = listOf(pair(1))))
        state.saveTemplateByName(draft(name = "我的模板", id = "id-2", pairs = listOf(pair(2), pair(3))))

        assertEquals("同名应只留 1 份", 1, state.templates.size)
        assertEquals("id-1", state.templates[0].id)
        assertEquals(2, state.templates[0].groups[0].pairs.size)
    }

    @Test
    fun saveTemplateByNameAppendsDifferentNames() {
        val state = EditorState()
        state.saveTemplateByName(draft(name = "A", id = "id-1"))
        state.saveTemplateByName(draft(name = "B", id = "id-2"))
        state.saveTemplateByName(draft(name = "C", id = "id-3"))
        assertEquals(listOf("A", "B", "C"), state.templates.map { it.name })
    }

    @Test
    fun removeTemplateDeletesByIdAndKeepsOthers() {
        val state = EditorState()
        state.saveTemplateByName(draft(name = "A", id = "id-1"))
        state.saveTemplateByName(draft(name = "B", id = "id-2"))
        state.removeTemplate("id-1")
        assertEquals(listOf("B"), state.templates.map { it.name })
    }

    @Test
    fun findTemplateByNameUsesTrimmedMatch() {
        val state = EditorState()
        state.saveTemplateByName(draft(name = "目标", id = "id-1"))
        assertEquals("id-1", state.findTemplateByName("  目标  ")?.id)
        assertNull(state.findTemplateByName("不存在"))
    }
}
