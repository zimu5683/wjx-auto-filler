package com.wjx.autofill.submit

import com.wjx.autofill.config.AnswerGroup
import com.wjx.autofill.config.MappingTemplate
import com.wjx.autofill.wjx.HttpWjxSurveyClient
import com.wjx.autofill.wjx.HttpWjxSubmitter
import com.wjx.autofill.wjx.SubmitErrorCode
import com.wjx.autofill.wjx.SubmitResult
import com.wjx.autofill.wjx.WjxException
import com.wjx.autofill.wjx.WjxSurveyClient
import com.wjx.autofill.wjx.WjxSubmitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 单组提交结果。 */
data class GroupOutcome(val index: Int, val groupName: String, val result: SubmitResult)

/** 一次并行提交的汇总。 */
data class BatchReport(val outcomes: List<GroupOutcome>, val durationMs: Long) {
    val total: Int get() = outcomes.size
    val successCount: Int get() = outcomes.count { it.result.ok }
    val failureCount: Int get() = total - successCount

    /** 例：「成功 2 / 失败 1 / 共 3」 */
    fun summary(): String = "成功 $successCount / 失败 $failureCount / 共 $total"
}

/**
 * 人工验证兜底入口的**唯一判据**（契约 §13.5）：只看 `errorCode` 与「该组是否已用过机会」，
 * **禁止文案匹配**。返回仍需兜底的分组下标（升序）；空列表 = 不显示兜底按钮。
 *
 * 纯函数，qa-build 可直接 JVM 单测。
 */
fun List<GroupOutcome>.pendingCaptchaGroups(retriedGroups: Set<Int>): List<Int> =
    filter { it.result.errorCode == SubmitErrorCode.CAPTCHA && it.index !in retriedGroups }
        .map { it.index }
        .sorted()

/**
 * 并发提交编排（契约 §9 的 11 条语义）：
 *  1. concurrency clamp 到 1..5，Semaphore 控制上限，每组一个协程；
 *  2. **每组独立会话**：每组新建 client（新 CookieJar）并重新 fetch，绝不复用其他组的 SurveyModel；
 *  3. 组内 fetch 失败 → 用 WjxException.code 转成带 errorCode 的 SubmitResult；
 *  4. 组间隔离：一组失败不取消其他组；组内异常一律捕获（CancellationException 必须向上抛）；
 *  5. 结果按 index 升序返回（awaitAll 保持输入顺序，再按 index 排序兜底）；
 *  6. onProgress 每完成一组回调一次，done 单调递增，回调落在调用方 dispatcher；
 *  7. 空组不发请求，结果 = E_EMPTY（明确可见，不静默跳过）；
 *  8. groups 为空 → 直接返回空 BatchReport；
 *  9. 可取消（在途请求的 disconnect 由 T4 的 submitter/fetch 在 finally 负责）；
 * 10. **不重试**（提交非幂等，重试只能由用户显式触发）；
 * 11. 本文件不 import android.*，qa-build 可用 runTest + fake client/submitter 直接单测。
 */
class SubmitCoordinator(
    private val clientFactory: () -> WjxSurveyClient = { HttpWjxSurveyClient() },
    private val submitter: WjxSubmitter = HttpWjxSubmitter(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 各组 fetch 结束时的引擎会话 cookie 快照（§13.3 方向①：注入 WebView 用）。
     * 引擎每次 fetch 都用新 CookieJar，所以必须按组记录。
     */
    private val sessionCookies = ConcurrentHashMap<Int, Map<String, String>>()

    /** 取某组最近一次 fetch 的会话 cookie；没有则空 Map。 */
    fun lastSessionCookies(groupIndex: Int): Map<String, String> =
        sessionCookies[groupIndex] ?: emptyMap()

    suspend fun run(
        template: MappingTemplate,
        concurrency: Int = template.concurrency,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BatchReport = coroutineScope {
        val groups = template.groups
        if (groups.isEmpty()) return@coroutineScope BatchReport(emptyList(), 0L)
        sessionCookies.clear()

        val limit = concurrency.coerceIn(
            MappingTemplate.MIN_CONCURRENCY,
            MappingTemplate.MAX_CONCURRENCY,
        )
        val semaphore = Semaphore(limit)
        // 回调必须落在调用方的 dispatcher 上（契约 §9 第 6 条）：只取 dispatcher，不带调用方 Job。
        val callbackDispatcher =
            coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? CoroutineDispatcher
                ?: Dispatchers.Default
        val completed = AtomicInteger(0)
        val total = groups.size
        val startedAt = System.currentTimeMillis()

        val outcomes = groups.mapIndexed { index, group ->
            async(dispatcher) {
                val result = semaphore.withPermit { runGroup(template, group, index) }
                val done = completed.incrementAndGet()
                withContext(callbackDispatcher) { onProgress(done, total) }
                GroupOutcome(index = index, groupName = group.name, result = result)
            }
        }.awaitAll()

        BatchReport(
            outcomes = outcomes.sortedBy { it.index },
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    /**
     * 人机验证兜底后的**单组**重试（§13.2 第 10–11 步；additive，不改动 run 的签名）。
     *
     * 用 WebView 收割到的同一会话 Cookie **重新 fetch** 页面（拿新的 jqnonce/starttime/ktimes），
     * 再带 captchaVerifyParam 提交。令牌短命（60s），调用方必须自行保证时效。**绝不循环重试**。
     */
    suspend fun retryGroupWithCaptcha(
        template: MappingTemplate,
        groupIndex: Int,
        harvest: CaptchaHarvest,
    ): GroupOutcome = coroutineScope {
        val group = template.groups.getOrNull(groupIndex)
        if (group == null) {
            return@coroutineScope GroupOutcome(
                index = groupIndex,
                groupName = "",
                result = SubmitResult(
                    ok = false,
                    httpStatus = 0,
                    message = "分组不存在",
                    raw = null,
                    errorCode = SubmitErrorCode.UNKNOWN,
                ),
            )
        }
        GroupOutcome(
            index = groupIndex,
            groupName = group.name,
            result = runGroup(
                template = template,
                group = group,
                index = groupIndex,
                cookies = harvest.cookies,
                captchaToken = harvest.captchaVerifyParam,
            ),
        )
    }

    private suspend fun runGroup(
        template: MappingTemplate,
        group: AnswerGroup,
        index: Int,
        cookies: Map<String, String> = emptyMap(),
        captchaToken: String? = null,
    ): SubmitResult {
        // 契约 §5.3.1：value 为空的项视为「该题不填」，先过滤；过滤后为空则明确报 E_EMPTY。
        val answers = group.effectivePairs().filter { it.value.isNotBlank() }
        if (answers.isEmpty()) {
            return SubmitResult(
                ok = false,
                httpStatus = 0,
                message = "没有可提交的答案",
                raw = null,
                errorCode = SubmitErrorCode.EMPTY,
            )
        }

        val model = try {
            clientFactory().fetch(template.surveyUrl, cookies).getOrThrow()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            // 保留 fetch 侧错误码（E_URL/E_NETWORK/E_PARSE/...），UI 只读 errorCode，不做文案匹配。
            val code = (t as? WjxException)?.code ?: SubmitErrorCode.UNKNOWN
            return SubmitResult(
                ok = false,
                httpStatus = 0,
                message = t.message?.takeIf { it.isNotBlank() } ?: "获取问卷失败",
                raw = null,
                errorCode = code,
            )
        }

        // §13.3 方向①：把本次 fetch 的会话快照留给 UI 注入 WebView。
        sessionCookies[index] = model.cookies

        return try {
            submitter.submit(model, answers, captchaToken)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            SubmitResult(
                ok = false,
                httpStatus = 0,
                message = t.message?.takeIf { it.isNotBlank() } ?: "提交失败",
                raw = null,
                errorCode = SubmitErrorCode.UNKNOWN,
            )
        }
    }
}
