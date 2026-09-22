package tools

import com.wjx.autofill.wjx.AnswerPair
import com.wjx.autofill.wjx.HttpWjxSubmitter
import com.wjx.autofill.wjx.HttpWjxSurveyClient
import com.wjx.autofill.wjx.QuestionType
import com.wjx.autofill.wjx.SceneIdSource
import com.wjx.autofill.wjx.SubmitResult
import com.wjx.autofill.wjx.SurveyModel
import com.wjx.autofill.wjx.WjxAnswerMatcher
import com.wjx.autofill.wjx.WjxSubmitRequest
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder

/**
 * T10 dry-run 工具：用**真实引擎代码**回答「引擎到底会发什么」。
 *
 * 默认 **只 GET 页面，绝不 POST**；只有同时给出 --submit 与 --confirm-own-survey 才会真的提交。
 * 用法见同目录 README.md。
 */
private val USAGE = """
用法：
  run.sh <问卷URL> [--answers=1=张三,2=2024001] [--submit --confirm-own-survey]

默认（dry-run）：只 GET 页面并解析，打印将要 POST 的 URL 与 body 原文，**不发送任何 POST**。
--submit 必须与 --confirm-own-survey 同时出现，否则拒绝（只允许提交你自己的问卷）。
""".trimIndent()

fun main(args: Array<String>) {
    val url = args.firstOrNull { !it.startsWith("--") }
    if (url == null) {
        println(USAGE)
        return
    }
    val doSubmit = args.contains("--submit")
    val confirmed = args.contains("--confirm-own-survey")
    val answersArg = args.firstOrNull { it.startsWith("--answers=") }?.substringAfter("=")

    if (doSubmit && !confirmed) {
        println("拒绝提交：--submit 必须同时带 --confirm-own-survey（只允许提交你自己的问卷）。")
        return
    }

    runBlocking {
        val client = HttpWjxSurveyClient()
        val t0 = System.currentTimeMillis()
        val fetched = client.fetch(url)
        val fetchMs = System.currentTimeMillis() - t0
        if (fetched.isFailure) {
            val err = fetched.exceptionOrNull()
            println("== 1. 抓取与解析失败 ==")
            println("耗时 " + fetchMs + "ms")
            if (err is com.wjx.autofill.wjx.WjxException) {
                println("errorCode=" + err.code)
                println("message=" + err.message)
            } else {
                println("exception=" + err)
            }
            return@runBlocking
        }
        val m: SurveyModel = fetched.getOrThrow()

        println("== 1. 抓取与解析（真实引擎 HttpWjxSurveyClient.fetch）==")
        println("解析成功  耗时 " + fetchMs + "ms")
        println("title=" + m.title + "   shortId=" + m.shortId)
        val sceneSource = when (m.sceneIdSource) {
            SceneIdSource.PAGE -> "页面内联"
            SceneIdSource.CAPTCHA_JS -> "wjx_captch.js（按需抓取）"
            null -> "无"
        }
        println("useAliVerify=" + m.useAliVerify + "   captchaType=" + m.captchaType)
        println("sceneId=" + m.sceneId + "   来源=" + sceneSource)
        println("jqnonce=" + m.jqnonce + "   ktimes=" + m.ktimes + "   startTime=" + m.startTime)
        println("openAtMillis=" + m.openAtMillis + "   needsCaptchaHint=" + m.needsCaptchaHint)
        println("submitUrl=" + m.submitUrl)
        println("cookies=" + m.cookies.keys)
        println("题目 " + m.questions.size + " 道：")
        for (q in m.questions) {
            val opt = if (q.options.isEmpty()) {
                ""
            } else {
                "  options=" + q.options.size + "  首项=" + q.options[0].value + "/" + q.options[0].label.take(24)
            }
            println("  " + q.topic + ". [" + q.type + "] " + q.title.take(48) + opt)
        }

        val pairs = if (answersArg != null) parseAnswers(answersArg) else synthesize(m)
        println()
        println("== 2. 将要提交的答案（" + (if (answersArg != null) "--answers 指定" else "自动合成，仅用于复核参数") + "）==")
        for ((topic, value) in pairs) println("  题号 " + topic + " => " + value)

        val asAnswerPairs = pairs.map { AnswerPair(it.first.toString(), it.second) }
        when (val matched = WjxAnswerMatcher.match(m, asAnswerPairs)) {
            is com.wjx.autofill.wjx.MatchOutcome.Ok ->
                println("字段匹配：OK，命中 " + matched.pairs.size + " 题")
            is com.wjx.autofill.wjx.MatchOutcome.Fail ->
                println("字段匹配：FAIL code=" + matched.code + " message=" + matched.message)
        }

        val submitUrl = WjxSubmitRequest.buildSubmitUrl(m)
        val body = WjxSubmitRequest.buildSubmitBody(m, pairs, null)
        println()
        println("== 3. 将要 POST 的完整 URL（真实引擎 WjxSubmitRequest.buildSubmitUrl）==")
        println(submitUrl ?: "<invalid：submitUrl 非 https/wjx.cn>")
        println()
        println("== 4. 将要 POST 的 body 原文 ==")
        println(body)
        println()
        println("== 5. body 解码后（可读）==")
        println(URLDecoder.decode(body, "UTF-8"))

        println()
        println("== 6. 提交行为（本地门控已取消，Lead 2026-09-22 裁定）==")
        println("最终将写入 body 的 sceneId：" + (m.sceneId?.takeIf { it.isNotBlank() } ?: "<不携带>") +
            "（来源 " + sceneSource + "；captchaToken 为空时 body 不含该字段）")
        println("ktimes 发送值 = max(4, 页面值)（T11 实测：0 触发服务端裸码 22 风控判定；4 拿到成功码 10）")
        println("useAliVerify=" + m.useAliVerify + " → 无论取值，引擎都会**真的发送上面的 POST**；")
        println("  只有服务端回业务码 7/22 时，分类器才判 E_CAPTCHA（再由 UI 走兜底路径）。")

        if (!doSubmit) {
            println()
            println("DRY-RUN：未发送任何 POST。（要真的提交请加 --submit --confirm-own-survey）")
            return@runBlocking
        }

        println()
        println("== 7. 真实提交（已显式授权）==")
        val t1 = System.currentTimeMillis()
        val r: SubmitResult = HttpWjxSubmitter().submit(m, asAnswerPairs)
        println("耗时 " + (System.currentTimeMillis() - t1) + "ms")
        println("ok=" + r.ok + "  httpStatus=" + r.httpStatus + "  errorCode=" + r.errorCode)
        println("message=" + r.message)
        println("raw=" + r.raw)
    }
}

/** --answers=1=张三,2=2024001 → [(1,"张三"),(2,"2024001")] */
private fun parseAnswers(spec: String): List<Pair<Int, String>> =
    spec.split(',').mapNotNull { item ->
        val idx = item.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        val topic = item.substring(0, idx).trim().toIntOrNull() ?: return@mapNotNull null
        topic to item.substring(idx + 1)
    }

/** 自动合成一份可提交的示例答案（只用于复核参数构造，不代表真实用户答案）。 */
private fun synthesize(m: SurveyModel): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    for (q in m.questions) {
        when (q.type) {
            QuestionType.TEXT -> out.add(q.topic to "示例文本")
            QuestionType.SINGLE, QuestionType.DROPDOWN -> {
                val first = q.options.firstOrNull() ?: continue
                out.add(q.topic to first.value)
            }
            QuestionType.MULTI -> {
                val first = q.options.firstOrNull() ?: continue
                out.add(q.topic to first.value)
            }
            QuestionType.MATRIX, QuestionType.SLIDER, QuestionType.OTHER -> Unit // V1 不支持，跳过
        }
    }
    return out
}
