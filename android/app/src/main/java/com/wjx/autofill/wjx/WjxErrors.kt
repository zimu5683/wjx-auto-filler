package com.wjx.autofill.wjx

/**
 * fetch() 失败时 Result.failure 里携带的异常（submit() 不抛异常，只用 [SubmitResult]）。
 *
 * [code] 取 [SubmitErrorCode] 常量；[message] 是**纯人类文案**，不含任何机器码前缀。
 */
class WjxException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
    /**
     * 结构化开放时间（epoch millis，UTC）。**additive（T32，契约 §2.3）**：
     * 仅在 [SubmitErrorCode.NOT_OPEN] 且页面解析到时间时非空；其它错误与解析失败一律为 null。
     *
     * 为什么需要它：开放时间若只活在格式化后的 [message] 里，UI 只能拿到字符串、无法直接填进输入框。
     * **message 文案保持不变**（UI 仍可用 message 展示，两者并存）。
     *
     * 位置说明：放在 [cause] **之后**并带默认值，既有 3 参构造点 `WjxException(code, message, cause)` 全部兼容。
     */
    val openAtMillis: Long? = null,
) : Exception(message, cause)

/**
 * 机器可读错误码。UI 只读码，不做 message 字符串匹配。
 *
 * 只保留常量，**不提供 format()/of()**（Lead 2026-09-22 裁决）。
 */
object SubmitErrorCode {
    const val URL = "E_URL"
    const val NETWORK = "E_NETWORK"
    const val HTTP = "E_HTTP"
    const val PARSE = "E_PARSE"
    const val PAGED = "E_PAGED"

    /** 问卷尚未开放（终态）：在 jqnonce 校验后、题目解析前短路，见 T16。 */
    const val NOT_OPEN = "E_NOT_OPEN"
    const val CAPTCHA = "E_CAPTCHA"
    const val UNMATCHED = "E_UNMATCHED"
    const val EMPTY = "E_EMPTY"
    const val LIMIT = "E_LIMIT"
    const val UNSUPPORTED = "E_UNSUPPORTED"
    const val REJECTED = "E_REJECTED"
    const val UNKNOWN = "E_UNKNOWN"
}
