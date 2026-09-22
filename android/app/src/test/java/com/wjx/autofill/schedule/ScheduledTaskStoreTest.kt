package com.wjx.autofill.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 定时任务持久化 + 提前量纯函数（T17，签名由 Lead 2026-09-22 冻结）。
 *
 * FileScheduledTaskStore 收目录不收 Context，纯 java.io + MiniJson，可 JVM 直测。
 */
class ScheduledTaskStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): FileScheduledTaskStore = FileScheduledTaskStore(temp.root)

    private fun task(
        templateId: String = "tpl-1",
        templateName: String = "测试问卷",
        openAtMillis: Long = 1_790_127_180_000L,
        leadMinutes: Int = 10,
        createdAtMillis: Long = 1_790_000_000_000L,
        state: TaskState = TaskState.ARMED,
    ): ScheduledTask = ScheduledTask(
        templateId = templateId,
        templateName = templateName,
        openAtMillis = openAtMillis,
        leadMinutes = leadMinutes,
        createdAtMillis = createdAtMillis,
        state = state,
    )

    // ------------------------------------------------------------ 往返

    @Test
    fun saveThenLoadRoundTripsEverything() {
        val s = store()
        assertTrue(s.save(task()))

        val loaded = s.load()
        assertNotNull(loaded)
        assertEquals("tpl-1", loaded!!.templateId)
        assertEquals("测试问卷", loaded.templateName)
        assertEquals(1_790_127_180_000L, loaded.openAtMillis)
        assertEquals(10, loaded.leadMinutes)
        assertEquals(1_790_000_000_000L, loaded.createdAtMillis)
        assertEquals(TaskState.ARMED, loaded.state)
    }

    @Test
    fun everyTaskStateSurvivesRoundTrip() {
        for (state in TaskState.values()) {
            val s = FileScheduledTaskStore(temp.newFolder())
            assertTrue(s.save(task(state = state)))
            assertEquals(state, s.load()!!.state)
        }
    }

    @Test
    fun fileNameIsScheduleJson() {
        assertEquals("schedule.json", store().file.name)
    }

    @Test
    fun missingFileLoadsAsNull() {
        assertNull(store().load())
    }

    @Test
    fun clearRemovesTaskButKeepsFileValid() {
        val s = store()
        assertTrue(s.save(task()))
        assertTrue(s.clear())
        assertNull("clear 后不应再读到任务", s.load())
        assertTrue("文件仍在（只是 task=null）", s.file.isFile)
    }

    @Test
    fun atomicWriteLeavesNoTemporaryFile() {
        val s = store()
        s.save(task())
        val leftovers = s.file.parentFile.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertEquals(emptyList<Any>(), leftovers)
    }

    // ------------------------------------------------------------ 损坏回退

    @Test
    fun corruptJsonIsBackedUpAndPreserved() {
        val s = store()
        val broken = "{ 这不是 JSON"
        s.file.writeText(broken, Charsets.UTF_8)

        assertNull(s.load())
        val backups = s.file.parentFile.listFiles()?.filter { it.name.startsWith("schedule.json.bad-") } ?: emptyList()
        assertEquals("应生成 1 个备份", 1, backups.size)
        assertEquals("备份必须保留原文", broken, backups[0].readText(Charsets.UTF_8))
        assertFalse("原文件已被改名", s.file.exists())
    }

    @Test
    fun payloadMissingTemplateIdIsIgnored() {
        val s = store()
        s.file.writeText(
            "{\"schemaVersion\":1,\"task\":{\"openAtMillis\":1790127180000}}",
            Charsets.UTF_8,
        )
        assertNull("缺 templateId 应视为无效任务", s.load())
    }

    @Test
    fun payloadWithZeroOpenAtIsIgnored() {
        val s = store()
        s.file.writeText(
            "{\"schemaVersion\":1,\"task\":{\"templateId\":\"a\",\"openAtMillis\":0}}",
            Charsets.UTF_8,
        )
        assertNull("openAtMillis<=0 应视为无效", s.load())
    }

    @Test
    fun unknownStateFallsBackToArmed() {
        val s = store()
        s.file.writeText(
            "{\"schemaVersion\":1,\"task\":{\"templateId\":\"a\",\"openAtMillis\":1790127180000,\"state\":\"WHATEVER\"}}",
            Charsets.UTF_8,
        )
        assertEquals(TaskState.ARMED, s.load()!!.state)
    }

    @Test
    fun leadMinutesIsClampedOnLoad() {
        val s = store()
        s.file.writeText(
            "{\"schemaVersion\":1,\"task\":{\"templateId\":\"a\",\"openAtMillis\":1790127180000,\"leadMinutes\":99999}}",
            Charsets.UTF_8,
        )
        assertEquals(ScheduleMath.MAX_LEAD_MINUTES, s.load()!!.leadMinutes)
    }

    // ------------------------------------------------------------ 提前量

    @Test
    fun defaultLeadMinutesIsTen() {
        assertEquals(10, ScheduleMath.DEFAULT_LEAD_MINUTES)
        assertEquals(10, task().leadMinutes)
    }

    @Test
    fun remindAtIsOpenMinusLeadMinutes() {
        val openAt = 1_790_127_180_000L
        assertEquals(openAt - 10 * 60_000L, ScheduleMath.remindAtMillis(task(openAtMillis = openAt, leadMinutes = 10)))
        assertEquals(openAt, ScheduleMath.remindAtMillis(task(openAtMillis = openAt, leadMinutes = 0)))
        assertEquals(openAt - 60 * 60_000L, ScheduleMath.remindAtMillis(task(openAtMillis = openAt, leadMinutes = 60)))
    }

    @Test
    fun remindAtUsesClampedLeadMinutes() {
        val openAt = 1_790_127_180_000L
        val expected = openAt - ScheduleMath.MAX_LEAD_MINUTES * 60_000L
        assertEquals(expected, ScheduleMath.remindAtMillis(task(openAtMillis = openAt, leadMinutes = 99_999)))
    }

    @Test
    fun normalizeLeadMinutesFollowsFrozenBounds() {
        assertEquals("0 是合法值（不提前提醒）", 0, ScheduleMath.normalizeLeadMinutes(0))
        assertEquals(5, ScheduleMath.normalizeLeadMinutes(5))
        assertEquals(1440, ScheduleMath.normalizeLeadMinutes(1440))
        assertEquals("超过上限 clamp 到 1440", 1440, ScheduleMath.normalizeLeadMinutes(1441))
        assertEquals("负值 clamp 到 0（冻结口径）", 0, ScheduleMath.normalizeLeadMinutes(-1))
        assertEquals("负值 clamp 到 0（冻结口径）", 0, ScheduleMath.normalizeLeadMinutes(Int.MIN_VALUE))
    }

    @Test
    fun stateAfterRunMapsSuccessAndFailure() {
        assertEquals(TaskState.DONE_OK, ScheduleMath.stateAfterRun(true))
        assertEquals(TaskState.DONE_FAIL, ScheduleMath.stateAfterRun(false))
    }

    @Test
    fun scheduleTimeRoundTrips() {
        val millis = 1_790_127_180_000L
        assertEquals(millis, ScheduleTime.parse(ScheduleTime.format(millis)))
        assertNull(ScheduleTime.parse(null))
        assertNull(ScheduleTime.parse(""))
        assertNull(ScheduleTime.parse("不是时间"))
    }
}
