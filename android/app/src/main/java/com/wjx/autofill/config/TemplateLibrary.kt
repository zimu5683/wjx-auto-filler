package com.wjx.autofill.config

/**
 * 模板库的纯函数操作（**可 JVM 单测**，不依赖 android）。
 *
 * 同名判据：`name.trim()` **精确相等**（大小写敏感）。
 *  - 同名 → 覆盖内容并**保留原 id**（用户视角是「更新这份模板」）；
 *  - 异名 → 追加，**用 draft 自己的 id**。
 *
 * `surveyUrl` 不参与身份判定：用户改了链接但没改名时，期望的是更新同一份模板。
 */
object TemplateLibrary {

    /**
     * 同名覆盖（保留原 id），异名追加（用 draft 自己的 id）。
     *
     * @throws IllegalArgumentException 当 `draft.name` 去空白后为空 —— 这是编程错误，
     *         UI 侧在调用前已校验（空名字不允许保存）。
     */
    fun save(templates: List<MappingTemplate>, draft: MappingTemplate): List<MappingTemplate> {
        val name = draft.name.trim()
        require(name.isNotEmpty()) { "模板名不能为空" }
        val normalized = draft.copy(name = name)
        val index = templates.indexOfFirst { it.name.trim() == name }
        if (index >= 0) {
            return templates.toMutableList().also {
                it[index] = normalized.copy(id = templates[index].id)
            }
        }
        return templates + normalized
    }

    /** 按 id 删除；找不到则原样返回。 */
    fun delete(templates: List<MappingTemplate>, id: String): List<MappingTemplate> =
        templates.filterNot { it.id == id }

    /** 按同名判据查找（UI 侧用来决定新模板要不要换 id）。 */
    fun findByName(templates: List<MappingTemplate>, name: String): MappingTemplate? {
        val key = name.trim()
        if (key.isEmpty()) return null
        return templates.firstOrNull { it.name.trim() == key }
    }
}
