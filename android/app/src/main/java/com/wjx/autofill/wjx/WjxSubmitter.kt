package com.wjx.autofill.wjx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 提交一次答卷。**纯 Kotlin/JVM**：不 import android.*，不依赖 OkHttp，可在 JVM 单元测试直接跑。
 */
interface WjxSubmitter {
    /**
     * 用 [m] 里**本次 fetch 得到的** token（jqnonce/ktimes/cookies）提交 [answers]。
     *
     * [captchaToken] 是 additive 预留参数（Lead 2026-09-22 批准，带默认值）：
     * - 为空（默认）：**照常提交**（不做本地 useAliVerify 门控）；body 只有 `submitdata`，
     *   绝不携带 `captchaVerifyParam`/`sceneId`（T11 实测：补发这些字段反而把成功码 10 变成 `7〒需要安全校验`）。
     *   服务端回 7/22 时由 [WjxResponseClassifier] 判 E_CAPTCHA。
     * - 非空：把 `captchaVerifyParam`（以及 sceneId 非空时的 `sceneId`）放进 POST body；
     *   调用方必须提供真实令牌，绝不伪造。sceneId 为空时不携带该字段，也不本地拦截。
     *
     * 除 [CancellationException] 外不抛异常：所有失败都转成 [SubmitResult]。
     * **绝不自动重试**（提交不幂等，重试可能产生重复答卷）。
     */
    suspend fun submit(m: SurveyModel, answers: List<AnswerPair>, captchaToken: String? = null): SubmitResult
}

/** [WjxSubmitter] 的 HttpURLConnection 实现。每次提交使用独立 CookieJar，不缓存复用 token。 */
class HttpWjxSubmitter(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : WjxSubmitter {

    override suspend fun submit(m: SurveyModel, answers: List<AnswerPair>, captchaToken: String?): SubmitResult =
        withContext(Dispatchers.IO) {
            try {
                val effective = answers.filter { it.value.isNotBlank() }
                if (effective.isEmpty()) {
                    return@withContext SubmitResult(false, 0, "没有可提交的答案", null, SubmitErrorCode.EMPTY)
                }
                // **不做本地 useAliVerify 门控**（Lead 2026-09-22 裁定）：本地判定会产生假阴性——
                // useAliVerify=1 的问卷也可能被服务端接受（T11：V6 形态在 useAliVerify=0 的问卷上拿到成功码 10）。
                // 总是先真实提交；只有服务端回业务码 7/22 才由分类器判 E_CAPTCHA。
                val token = captchaToken?.trim().orEmpty()
                when (val matched = WjxAnswerMatcher.match(m, effective)) {
                    is MatchOutcome.Fail ->
                        return@withContext SubmitResult(false, 0, matched.message, null, matched.code)
                    is MatchOutcome.Ok -> {
                        val target = WjxSubmitRequest.buildSubmitUrl(m)
                            ?: return@withContext SubmitResult(
                                false, 0, WjxText.URL_INVALID, null, SubmitErrorCode.URL
                            )
                        val jar = CookieJar().apply { load(m.cookies) }
                        val body = WjxSubmitRequest.buildSubmitBody(m, matched.pairs, token)
                        val conn = WjxHttp.open(
                            target,
                            "POST",
                            connectTimeoutMs,
                            readTimeoutMs,
                            jar,
                            mapOf(
                                "User-Agent" to WjxHttp.DESKTOP_UA,
                                "Accept" to "*/*",
                                "Accept-Language" to "zh-CN,zh;q=0.9",
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to m.url,
                            ),
                        )
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        val (status, text) = WjxHttp.readText(conn, jar)
                        val result = WjxResponseClassifier.classify(status, text)
                        // 跳过信息只走结构化字段，且只在成功时透传（契约 §7.4 规则 2）
                        if (result.ok && matched.skippedFields.isNotEmpty()) {
                            result.copy(skippedFields = matched.skippedFields)
                        } else {
                            result
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                SubmitResult(false, 0, WjxText.NETWORK, null, SubmitErrorCode.NETWORK)
            }
        }

}

/**
 * 提交请求构造。**纯函数、公开给单测**（qa-build 的 §13.10 body 断言接缝；docs/API-CONTRACT.md §5.3）。
 *
 * 拆出来的目的：让「引擎到底会发什么」在不发任何网络请求的前提下可被断言/复核（见 T10 dry-run 工具）。
 */
object WjxSubmitRequest {

    /**
     * 在页面 action 上追加查询参数（顺序无关，ASP.NET 不校验）。
     *
     * T3 实测：真实页面把 starttime 放在 **URL query**（不是 body），顺序为
     * starttime→cst→source→ktimes→capt→t→jqnonce→jqsign；V1 不发送语义未确认的 cst/source。
     * submitUrl 非 https / 非 wjx.cn → 返回 null（调用方转 E_URL）。
     *
     * **ktimes 下限为 4**（Lead 2026-09-22 裁定，契约 §5.3）：页面初值 0 是真实用户不可能产生的值
     * （浏览器的 field/#ctlNext mouseover 与 loadAnswer 都会 ++）。T11 实测（用户样本 P2M09FG）：
     * `ktimes=0` → 服务端回裸码 `22`（风控判定）；**只把 ktimes 改成 4 → 返回 `10〒/wjx/join/complete.aspx…` 提交成功**。
     * 取 4 而不是 1：**4 是实测有效值，1 未验证**（Lead 裁定以证据为准）。
     */
    fun buildSubmitUrl(m: SurveyModel): String? {
        val base = m.submitUrl.trim()
        if (!WjxUrls.isHttpsWjx(base)) return null
        val ktimes = maxOf(4, m.ktimes)
        val sb = StringBuilder(base)
        if (m.startTime.isNotBlank()) sb.append("&starttime=").append(urlEncodeQuery(m.startTime))
        sb.append("&ktimes=").append(ktimes)
        sb.append("&t=").append(System.currentTimeMillis())
        sb.append("&jqnonce=").append(urlEncodeQuery(m.jqnonce))
        sb.append("&jqsign=").append(urlEncodeQuery(WjxSubmitCodec.jqSign(m.jqnonce, ktimes)))
        if (m.captchaType != null) sb.append("&capt=").append(m.captchaType)
        return sb.toString()
    }

    /**
     * POST body（application/x-www-form-urlencoded 的已编码形式）。
     *
     * - 永远只有 submitdata（唯一必发字段）；
     * - captchaToken 为空 → **绝不携带** captchaVerifyParam / sceneId；
     * - captchaToken 非空 → 携带 captchaVerifyParam；sceneId 非空才携带 sceneId（缺省不携带、不本地拦截）。
     */
    fun buildSubmitBody(m: SurveyModel, pairs: List<Pair<Int, String>>, captchaToken: String?): String {
        val token = captchaToken?.trim().orEmpty()
        val sb = StringBuilder()
        sb.append("submitdata=").append(urlEncode(WjxSubmitCodec.encodeSubmitData(pairs)))
        if (token.isNotEmpty()) {
            sb.append("&captchaVerifyParam=").append(urlEncode(token))
            val scene = m.sceneId
            if (!scene.isNullOrBlank()) sb.append("&sceneId=").append(urlEncode(scene))
        }
        return sb.toString()
    }
}

/**
 * 响应分类器（docs/API-CONTRACT.md §8.4，**全部成功/失败判定集中在这里，单点可改**）。
 *
 * 铁律：**HTTP 200 绝不等于提交成功**。问卷星用 200 返回业务错误，正文形如
 * `<业务码>〒<人类文案>`（T3 真实响应：`7〒需要安全校验，请重新提交！`）。
 */
object WjxResponseClassifier {

    /** raw 截断上限（契约 §5.1）。 */
    const val RAW_LIMIT = 8192

    private const val CODE_SUCCESS = "10"
    private const val CODE_ACCEPTED = "11"
    private const val CODE_CAPTCHA = "7"
    private const val CODE_NEED_VALIDATE = "22"

    fun classify(httpStatus: Int, body: String): SubmitResult {
        val raw = body.take(RAW_LIMIT)

        if (httpStatus !in 200..299) {
            return SubmitResult(
                false, httpStatus, "问卷服务器返回异常（HTTP " + httpStatus + "）", raw, SubmitErrorCode.HTTP
            )
        }
        if (body.isEmpty()) {
            return SubmitResult(false, httpStatus, "响应为空", raw, SubmitErrorCode.PARSE)
        }
        if (body.contains("aliyunwaf", ignoreCase = true)) {
            return SubmitResult(false, httpStatus, WjxText.CAPTCHA, raw, SubmitErrorCode.CAPTCHA)
        }

        // 协议层事实优先：先解析 〒 前的业务码，不得只做关键词匹配。
        val parts = body.split('〒')
        val code = parts.firstOrNull()?.trim().orEmpty()
        if (code.isNotEmpty() && code.all { it.isDigit() }) {
            return when (code) {
                CODE_SUCCESS, CODE_ACCEPTED -> SubmitResult(true, httpStatus, "提交成功", raw, null)
                CODE_CAPTCHA, CODE_NEED_VALIDATE ->
                    SubmitResult(false, httpStatus, WjxText.CAPTCHA, raw, SubmitErrorCode.CAPTCHA)
                else -> {
                    val detail = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { body.take(200) }
                    SubmitResult(
                        false, httpStatus, "问卷服务端拒绝：" + detail, raw, SubmitErrorCode.REJECTED
                    )
                }
            }
        }

        // 解析不出业务码 → 关键词启发式
        if (body.contains("验证码") || body.contains("安全校验") || body.contains("captcha", ignoreCase = true)) {
            return SubmitResult(false, httpStatus, WjxText.CAPTCHA, raw, SubmitErrorCode.CAPTCHA)
        }
        if (body.trimStart().startsWith("<html", ignoreCase = true)) {
            return SubmitResult(false, httpStatus, "服务端返回页面而非结果", raw, SubmitErrorCode.PARSE)
        }
        return SubmitResult(false, httpStatus, "未知错误：" + body.take(200), raw, SubmitErrorCode.UNKNOWN)
    }
}

/**
 * 字段匹配结果（v1.0.5）。
 *
 * 两类规则（契约 §7.2）：**可跳过**（问卷里没有这个字段 → 记入 [Ok.skippedFields]，不失败）
 * vs **仍失败**（歧义 / 同题冲突 / 取值不在选项 → [Fail]）。
 */
sealed class MatchOutcome {
    /**
     * 已解析成 `topic to value`（value 已 escape / 已解析为选项 value）。
     *
     * [skippedFields]：**未匹配到任何题目**的字段名（trim 后原文，按出现顺序、同名只记一次）。
     * **additive（v1.0.5，Lead 冻结）**：带默认值，既有 `Ok(pairs)` 构造点全部兼容。
     * 「不静默丢弃」的精神保留：跳过的字段由 UI 在结果区显式列出（[SubmitResult.skippedFields]）。
     */
    data class Ok(
        val pairs: List<Pair<Int, String>>,
        val skippedFields: List<String> = emptyList(),
    ) : MatchOutcome()

    /** 失败：[code] 取 [SubmitErrorCode]，[message] 是纯人类文案。 */
    data class Fail(val message: String, val code: String) : MatchOutcome()
}

/**
 * 字段匹配器（docs/API-CONTRACT.md §7）。纯函数，可单测。
 *
 * 规则优先级：R1 精确题号 > R2 题干包含 > R3 选项文本（**不变**）。
 *
 * 两类结果（v1.0.5，契约 §7.2 / §7.4）：
 * - **可跳过**：未匹配到任何题目 → 记入 [MatchOutcome.Ok.skippedFields]，**不失败**（支持超量预填）；
 *   字段名为空 → **直接忽略**（那是空行，不是用户意图，UI 侧本就过滤）。
 * - **仍失败**：歧义（多题命中）/ 多字段指向同一题 / 取值不在选项中 / 超长 / 不支持题型 → 整次中止；
 *   且 `pairs` 为空（全部被跳过）→ 仍判失败（没有可提交内容）。
 */
object WjxAnswerMatcher {

    private const val MAX_DETAILS = 5

    fun match(model: SurveyModel, answers: List<AnswerPair>): MatchOutcome {
        val problems = ArrayList<Problem>()
        val skippedFields = ArrayList<String>()
        val assigned = LinkedHashMap<Int, String>()
        val pairs = ArrayList<Pair<Int, String>>()

        for (pair in answers) {
            val field = pair.field.trim()
            if (field.isEmpty()) continue // 空字段名直接忽略，不计入 skipped（Lead 2026-09-22 冻结）
            val resolution = resolveQuestions(model, field)
            val hits = resolution.questions
            if (hits.isEmpty()) {
                if (skippedFields.none { it == field }) skippedFields.add(field)
                continue
            }
            if (hits.size > 1) {
                problems.add(
                    Problem(
                        SubmitErrorCode.UNMATCHED,
                        "字段「" + field + "」匹配到多个题目（" +
                            hits.map { it.topic }.joinToString("、") + "），请改用题号",
                    )
                )
                continue
            }
            val question = hits.first()
            // R3：字段是选项文本且 value 为空 → 取该选项的 value（契约 §7.1）
            val rawValue = if (pair.value.isBlank() && resolution.optionValue != null) {
                resolution.optionValue
            } else {
                pair.value.trim()
            }
            val previous = assigned[question.topic]
            if (previous != null) {
                problems.add(
                    Problem(
                        SubmitErrorCode.UNMATCHED,
                        "多个字段指向同一题（题号 " + question.topic + "）：" + previous + "、" + field,
                    )
                )
                continue
            }
            when (val value = resolveValue(question, rawValue)) {
                is ValueResult.Fail -> problems.add(Problem(value.code, value.message))
                is ValueResult.Ok -> {
                    assigned[question.topic] = field
                    pairs.add(question.topic to value.text)
                }
            }
        }

        if (problems.isNotEmpty()) {
            val code = problems.firstOrNull { it.code != SubmitErrorCode.UNMATCHED }?.code
                ?: SubmitErrorCode.UNMATCHED
            return MatchOutcome.Fail(summarize(problems), code)
        }
        if (pairs.isEmpty()) {
            // 全部字段都被跳过 → 没有可提交内容，仍判失败（契约 §7.2 第 ④ 条）
            val detail = if (skippedFields.isEmpty()) {
                "（没有字段）"
            } else {
                skippedFields.take(MAX_DETAILS).joinToString("、") +
                    if (skippedFields.size > MAX_DETAILS) " 等 " + skippedFields.size + " 项" else ""
            }
            return MatchOutcome.Fail("全部字段都被跳过，没有可提交的题目：" + detail, SubmitErrorCode.UNMATCHED)
        }
        return MatchOutcome.Ok(pairs, skippedFields)
    }

    /** R1 精确题号 > R2 题干包含 > R3 选项文本（先精确再 contains），首个命中的规则即停止。 */
    private fun resolveQuestions(model: SurveyModel, field: String): Resolution {
        val asNumber = field.toIntOrNull()
        if (asNumber != null) {
            val byTopic = model.questions.firstOrNull { it.topic == asNumber }
            if (byTopic != null) return Resolution(listOf(byTopic), null)
        }
        val byTitle = model.questions.filter { it.title.contains(field, ignoreCase = true) }
        if (byTitle.isNotEmpty()) return Resolution(byTitle, null)
        val exactOption = model.questions.filter { q -> q.options.any { it.label == field } }
        if (exactOption.isNotEmpty()) {
            return Resolution(exactOption, exactOption.first().options.first { it.label == field }.value)
        }
        val looseOption = model.questions.filter { q -> q.options.any { it.label.contains(field, ignoreCase = true) } }
        val looseValue = looseOption.firstOrNull()?.options?.firstOrNull { it.label.contains(field, ignoreCase = true) }?.value
        return Resolution(looseOption, looseValue)
    }

    private fun resolveValue(question: SurveyQuestion, raw: String): ValueResult = when (question.type) {
        QuestionType.TEXT -> {
            val escaped = WjxSubmitCodec.escape(raw)
            if (escaped.length > WjxSubmitCodec.MAX_TEXT_LENGTH) {
                ValueResult.Fail(
                    SubmitErrorCode.LIMIT, "答案超过 3000 字上限（题号 " + question.topic + "）"
                )
            } else {
                ValueResult.Ok(escaped)
            }
        }
        QuestionType.SINGLE, QuestionType.DROPDOWN -> {
            val resolved = optionValue(question, raw)
            if (resolved == null) {
                ValueResult.Fail(
                    SubmitErrorCode.UNMATCHED, "题号 " + question.topic + " 的取值「" + raw + "」不在选项中"
                )
            } else {
                ValueResult.Ok(resolved)
            }
        }
        QuestionType.MULTI -> {
            val resolved = ArrayList<String>()
            for (part in raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
                val value = optionValue(question, part)
                    ?: return ValueResult.Fail(
                        SubmitErrorCode.UNMATCHED,
                        "题号 " + question.topic + " 的取值「" + part + "」不在选项中",
                    )
                resolved.add(value)
            }
            if (resolved.isEmpty()) {
                ValueResult.Fail(
                    SubmitErrorCode.UNMATCHED, "题号 " + question.topic + " 的取值「" + raw + "」不在选项中"
                )
            } else {
                ValueResult.Ok(resolved.joinToString("|"))
            }
        }
        QuestionType.MATRIX, QuestionType.SLIDER, QuestionType.OTHER -> ValueResult.Fail(
            SubmitErrorCode.UNSUPPORTED,
            "题号 " + question.topic + "（" + question.type.name + "）暂不支持自动填写",
        )
    }

    private fun optionValue(question: SurveyQuestion, raw: String): String? =
        question.options.firstOrNull { it.value == raw }?.value
            ?: question.options.firstOrNull { it.label == raw }?.value

    private fun summarize(problems: List<Problem>): String {
        val head = problems.take(MAX_DETAILS).joinToString("；") { it.message }
        return if (problems.size > MAX_DETAILS) head + "；等 " + problems.size + " 项" else head
    }

    private data class Problem(val code: String, val message: String)

    /** 命中的题目集合 + 若命中规则是 R3 则带上该选项的 value（供「空 value 取 option.value」使用）。 */
    private data class Resolution(val questions: List<SurveyQuestion>, val optionValue: String?)

    private sealed class ValueResult {
        data class Ok(val text: String) : ValueResult()
        data class Fail(val code: String, val message: String) : ValueResult()
    }
}
