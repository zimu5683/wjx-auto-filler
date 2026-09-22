package com.wjx.autofill.ui

import com.wjx.autofill.wjx.SurveyModel

/** 问卷开放状态。 */
enum class SurveyOpenState { UNKNOWN, NOT_OPEN, OPEN }

/**
 * 顶部问卷状态条的数据。
 *
 * 纯逻辑（只依赖 wjx 的数据类，不依赖 android），qa-build 可直接 JVM 单测。
 */
data class SurveyStatus(
    val state: SurveyOpenState,
    val openAtMillis: Long?,
) {
    companion object {

        /**
         * 优先用页面解析出的开放时间，其次用用户在定时任务里手填的开放时间；
         * 两者都没有 → UNKNOWN（「解析不到」）。
         */
        fun of(parsedOpenAtMillis: Long?, scheduledOpenAtMillis: Long?, now: Long): SurveyStatus {
            val openAt = parsedOpenAtMillis?.takeIf { it > 0L }
                ?: scheduledOpenAtMillis?.takeIf { it > 0L }
            val state = when {
                openAt == null -> SurveyOpenState.UNKNOWN
                now >= openAt -> SurveyOpenState.OPEN
                else -> SurveyOpenState.NOT_OPEN
            }
            return SurveyStatus(state = state, openAtMillis = openAt)
        }

        /**
         * 从解析结果推导。**这是 T16 的唯接线点**：
         * api-debug 的 SurveyModel.openAtMillis 落地后，把 [parsedOpenAt] 从 null 改成 model?.openAtMillis 即可。
         */
        fun fromModel(
            model: SurveyModel?,
            scheduledOpenAtMillis: Long?,
            now: Long,
        ): SurveyStatus = of(parsedOpenAtMillis = null, scheduledOpenAtMillis = scheduledOpenAtMillis, now = now)
    }
}
