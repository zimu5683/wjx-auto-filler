package com.wjx.autofill.wjx

/**
 * 题型。判定规则见 docs/API-CONTRACT.md §6.2。
 *
 * 本文件是 T4 冻结契约的一部分：字段名与顺序不得改动（additive 字段只能追加到末尾并带默认值）。
 */

/** 单选题 / 多选题 / 下拉题 / 填空题 / 矩阵题 / 量表题 / 其他（商品、预约等）。 */
enum class QuestionType { SINGLE, MULTI, TEXT, DROPDOWN, MATRIX, SLIDER, OTHER }

/**
 * [SurveyModel.sceneId] 的来源（排障 / dry-run 展示用）。
 *
 * 问卷页 HTML **不内联** captchaSceneid（T3.5 实测 6/6 问卷都是 0 次），
 * 常量实际在 `image.wjx.cn/joinnew/js/wjx_captch.js` 里，故引擎在 useAliVerify=1 时会按需去抓。
 */
enum class SceneIdSource { PAGE, CAPTCHA_JS }

/**
 * 选项。
 *
 * [value] 是 DOM `value` 属性**原文**（问卷星单选/多选通常是 1 基序号），提交时写进 submitdata 的就是它；
 * [label] 是选项可见文本，提取不到时退化为 [value]。
 */
data class Option(val value: String, val label: String)

/**
 * 一道题。
 *
 * [topic] 来自 `div[topic]`，≥ 1 且问卷内唯一；[title] 是题干纯文本（不含题号前缀）。
 * [options] 对 TEXT / MATRIX / SLIDER / OTHER 恒为空列表。
 */
data class SurveyQuestion(
    val topic: Int,
    val title: String,
    val type: QuestionType,
    val options: List<Option>,
)

/**
 * 一次 fetch 的问卷快照。
 *
 * [questions] 按 [topic] 升序（解析后显式排序，保证 UI 与匹配器行为确定）；
 * [cookies] 是 fetch 结束时的 cookie 快照，submit 时原样回带（每次提交使用独立会话）。
 */
data class SurveyModel(
    val url: String,
    val shortId: String,
    val title: String,
    val questions: List<SurveyQuestion>,
    val submitUrl: String,
    val jqnonce: String,
    val ktimes: Int,
    val startTime: String,
    val captchaType: Int?,
    val cookies: Map<String, String>,
    /**
     * 页面 `var useAliVerify=1`：该问卷开启了阿里云安全校验。
     * **additive 字段（Lead 2026-09-22 批准）**：带默认值，既有 9 参构造点全部兼容。
     *
     * 为什么需要它：T3.5 实测 6/6 个问卷（含 5 个公开问卷）的 `captchaType` 全是 '2'，
     * 它是页面模板常量而不是开关；真正的服务端开关是 `useAliVerify`。
     * 详见 tools/wjx-probe/evidence/t35b-scan.log 与 docs/API-CONTRACT.md §11。
     */
    val useAliVerify: Boolean = false,
    /**
     * 阿里云验证码 SceneId（页面内联 `captchaSceneid` 时抓到，否则为 null）。
     * **additive 字段**：仅在调用方显式传入 captchaToken 的路径上使用。
     * 实测样本页不内联该常量（它来自 image.wjx.cn/joinnew/js/wjx_captch.js 的全局 `captchaSceneid="q0hcfsca"`），
     * 契约口径（architect T9 / Lead 批准）：submit 时 captchaToken 非空**且**本字段非空才在 body 追加 sceneId；
     * 本字段为 null → 不携带该字段，也**不做本地拦截**（由服务端判定）。
     */
    val sceneId: String? = null,
    /**
     * [sceneId] 的来源；null = 没有拿到（此时不携带 sceneId 字段，也不本地拦截）。
     * **additive 字段（Lead 2026-09-22 批准）**：带默认值，既有构造点全部兼容。
     */
    val sceneIdSource: SceneIdSource? = null,
    /**
     * 问卷开放时间（epoch millis，UTC）；null = 页面没给 / 解析失败（**不拦截**）。
     * **additive 字段（T16）**：由 [SurveyTimeAdapter] 解析，用于 E_NOT_OPEN 判定。
     */
    val openAtMillis: Long? = null,
    /**
     * 页面层的人机验证**提示**（`var useAliVerify=1`）——**不是权威结论**。
     *
     * 权威判定在接口层：[WjxResponseClassifier] 收到业务码 7/22 → E_CAPTCHA。
     * 注意 useAliVerify / captchaWrap / wjx_captch / needLoadAliVerify 这些标记在**所有问卷页**都存在
     * （模板常量），只能读 useAliVerify 的**值**；看标记是否存在会 100% 误报。
     * **additive 字段（T16）**：带默认值，既有构造点兼容。
     */
    val needsCaptchaHint: Boolean = false,
)

/**
 * 一条「字段 → 答案」映射。
 *
 * [field] 可以是题号字符串（"3"）、题干片段（"姓名"）或选项文本（"男"）；
 * [value] 空串表示该题不发送（由调用方过滤，见 [HttpWjxSubmitter]）。
 */
data class AnswerPair(val field: String, val value: String)

/**
 * 提交结果。
 *
 * **`errorCode == null` ⟺ 成功**。机器可读信息只走 [errorCode]，人类可读信息只走 [message]；
 * 绝不把错误码拼进 [message]。成功时 [ok]=true、[errorCode]=null、[message]="提交成功"。
 */
data class SubmitResult(
    val ok: Boolean,
    val httpStatus: Int,
    val message: String,
    val raw: String?,
    val errorCode: String? = null,
)
