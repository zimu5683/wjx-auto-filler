package com.wjx.autofill.ui

import android.os.Bundle
import com.wjx.autofill.config.AnswerGroup
import com.wjx.autofill.config.MappingTemplate
import com.wjx.autofill.submit.GroupOutcome
import com.wjx.autofill.wjx.AnswerPair
import com.wjx.autofill.wjx.SurveyModel

/**
 * 主界面的可编辑状态。
 *
 * 不放进 ViewModel：Activity 重建时用 Bundle 存/取即可，少一个生命周期依赖，行为也更可预测。
 * （刻意不走 templates.json 编解码 —— 那套 schema 有必填约束，而编辑中的模板允许 name/url 为空。）
 */
class EditorState {

    /** 已保存的模板库（与 filesDir/templates.json 一致）。 */
    var templates: MutableList<MappingTemplate> = mutableListOf()

    /** 当前正在编辑的模板。 */
    var current: MappingTemplate = MappingTemplate.blank()

    /** 最近一次「解析问卷」的结果，仅用于题目清单与字段回填。**解析失败时必须置 null**。 */
    var survey: SurveyModel? = null

    /**
     * 最近一次解析得到的**问卷开放时间**（epoch millis）。
     *
     * 单独存一份而不是从 [survey] 读，是为了让「解析失败也必须收敛状态」成为结构性保证：
     * 失败分支会把 [survey] 与它一起清空，状态条不可能再显示上一份问卷的「已开放」。
     */
    var parsedOpenAtMillis: Long? = null

    /** 最近一次并行提交的结果。 */
    var outcomes: List<GroupOutcome> = emptyList()
    var summary: String = ""
    var submitting: Boolean = false
    var resultVisible: Boolean = false
    var progressText: String = ""

    var groupIndex: Int = 0
    var linkStatus: String = ""
    var templateStatus: String = ""

    fun group(): AnswerGroup =
        current.groups.getOrElse(groupIndex) { AnswerGroup.blank() }

    fun groupNames(): List<String> = current.groups.mapIndexed { index, group ->
        group.name.ifBlank { "方案 ${index + 1}" }
    }

    fun updateGroupPairs(pairs: List<AnswerPair>) {
        val groups = current.groups.toMutableList()
        while (groups.size <= groupIndex) {
            groups += AnswerGroup.blank("方案 ${groups.size + 1}")
        }
        groups[groupIndex] = groups[groupIndex].copy(pairs = pairs)
        current = current.copy(groups = groups)
    }

    /**
     * 按**名字**保存模板：同名覆盖（保留原 id）、异名追加。
     * 纯逻辑在 [com.wjx.autofill.config.TemplateLibrary]（可 JVM 单测）。
     */
    fun saveTemplateByName(draft: MappingTemplate) {
        templates = com.wjx.autofill.config.TemplateLibrary.save(templates, draft).toMutableList()
    }

    /** 按 id 删除模板。 */
    fun removeTemplate(id: String) {
        templates = com.wjx.autofill.config.TemplateLibrary.delete(templates, id).toMutableList()
    }

    /** 按名字查找（决定新模板要不要换 id）。 */
    fun findTemplateByName(name: String): MappingTemplate? =
        com.wjx.autofill.config.TemplateLibrary.findByName(templates, name)

    // ------------------------------------------------------------ 旋转/重建快照

    fun saveTo(bundle: Bundle) {
        bundle.putString(KEY_ID, current.id)
        bundle.putString(KEY_NAME, current.name)
        bundle.putString(KEY_URL, current.surveyUrl)
        bundle.putInt(KEY_CONCURRENCY, current.concurrency)
        bundle.putInt(KEY_GROUP_INDEX, groupIndex)
        bundle.putString(KEY_LINK_STATUS, linkStatus)
        bundle.putString(KEY_TEMPLATE_STATUS, templateStatus)
        // 开放时间用 -1 表示「无」（开放时间恒 > 0）
        bundle.putLong(KEY_PARSED_OPEN_AT, parsedOpenAtMillis ?: -1L)

        val groupNames = ArrayList<String>()
        val groupSizes = ArrayList<Int>()
        val fields = ArrayList<String>()
        val values = ArrayList<String>()
        current.groups.forEach { group ->
            groupNames += group.name
            groupSizes += group.pairs.size
            group.pairs.forEach { pair ->
                fields += pair.field
                values += pair.value
            }
        }
        bundle.putStringArrayList(KEY_GROUP_NAMES, groupNames)
        bundle.putIntegerArrayList(KEY_GROUP_SIZES, groupSizes)
        bundle.putStringArrayList(KEY_FIELDS, fields)
        bundle.putStringArrayList(KEY_VALUES, values)
    }

    companion object {
        private const val KEY_ID = "state_id"
        private const val KEY_NAME = "state_name"
        private const val KEY_URL = "state_url"
        private const val KEY_CONCURRENCY = "state_concurrency"
        private const val KEY_GROUP_INDEX = "state_group_index"
        private const val KEY_LINK_STATUS = "state_link_status"
        private const val KEY_TEMPLATE_STATUS = "state_template_status"
        private const val KEY_PARSED_OPEN_AT = "state_parsed_open_at"
        private const val KEY_GROUP_NAMES = "state_group_names"
        private const val KEY_GROUP_SIZES = "state_group_sizes"
        private const val KEY_FIELDS = "state_fields"
        private const val KEY_VALUES = "state_values"

        fun restoreFrom(bundle: Bundle?): EditorState {
            val state = EditorState()
            if (bundle == null) return state

            val id = bundle.getString(KEY_ID).orEmpty()
            val name = bundle.getString(KEY_NAME).orEmpty()
            val url = bundle.getString(KEY_URL).orEmpty()
            val groupNames = bundle.getStringArrayList(KEY_GROUP_NAMES).orEmpty()
            val groupSizes = bundle.getIntegerArrayList(KEY_GROUP_SIZES).orEmpty()
            val fields = bundle.getStringArrayList(KEY_FIELDS).orEmpty()
            val values = bundle.getStringArrayList(KEY_VALUES).orEmpty()

            val groups = mutableListOf<AnswerGroup>()
            var cursor = 0
            groupNames.forEachIndexed { index, groupName ->
                val size = groupSizes.getOrElse(index) { 0 }
                val pairs = mutableListOf<AnswerPair>()
                for (offset in 0 until size) {
                    val field = fields.getOrElse(cursor) { "" }
                    val value = values.getOrElse(cursor) { "" }
                    pairs += AnswerPair(field, value)
                    cursor++
                }
                groups += AnswerGroup(groupName, pairs)
            }

            state.current = MappingTemplate(
                id = id.ifBlank { java.util.UUID.randomUUID().toString() },
                name = name,
                surveyUrl = url,
                shortId = "",
                groups = groups.ifEmpty { listOf(AnswerGroup.blank()) },
                concurrency = bundle.getInt(KEY_CONCURRENCY, MappingTemplate.DEFAULT_CONCURRENCY),
                updatedAt = 0L,
            )
            state.groupIndex = bundle.getInt(KEY_GROUP_INDEX, 0)
                .coerceIn(0, (state.current.groups.size - 1).coerceAtLeast(0))
            state.linkStatus = bundle.getString(KEY_LINK_STATUS).orEmpty()
            state.templateStatus = bundle.getString(KEY_TEMPLATE_STATUS).orEmpty()
            state.parsedOpenAtMillis = bundle.getLong(KEY_PARSED_OPEN_AT, -1L).takeIf { it > 0L }
            return state
        }
    }
}
