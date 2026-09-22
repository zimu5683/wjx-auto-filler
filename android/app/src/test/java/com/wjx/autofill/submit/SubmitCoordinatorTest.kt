package com.wjx.autofill.submit

import com.wjx.autofill.config.AnswerGroup
import com.wjx.autofill.config.MappingTemplate
import com.wjx.autofill.wjx.AnswerPair
import com.wjx.autofill.wjx.QuestionType
import com.wjx.autofill.wjx.SubmitErrorCode
import com.wjx.autofill.wjx.SubmitResult
import com.wjx.autofill.wjx.SurveyModel
import com.wjx.autofill.wjx.SurveyQuestion
import com.wjx.autofill.wjx.WjxException
import com.wjx.autofill.wjx.WjxSubmitter
import com.wjx.autofill.wjx.WjxSurveyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** 契约 §9 的 11 条语义：并发上限、每组独立会话、组序返回、组间隔离、空组、进度回调。 */
class SubmitCoordinatorTest {

    private fun surveyModel(url: String = "https://www.wjx.cn/vm/Q0DQewW.aspx"): SurveyModel = SurveyModel(
        url = url,
        shortId = "Q0DQewW",
        title = "测试",
        questions = listOf(SurveyQuestion(1, "姓名", QuestionType.TEXT, emptyList())),
        submitUrl = "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW",
        jqnonce = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6",
        ktimes = 3,
        startTime = "2026/9/22 10:00:00",
        captchaType = 2,
        cookies = emptyMap(),
    )

    private class FakeClient(
        private val result: Result<SurveyModel>,
        private val delayMs: Long = 0,
    ) : WjxSurveyClient {
        val fetchCount = AtomicInteger(0)
        override suspend fun fetch(url: String, cookies: Map<String, String>): Result<SurveyModel> {
            fetchCount.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    /** 用答案值当作人工延时（毫秒），这样能精确构造「后发的先完成」。 */
    private class FakeSubmitter(
        private val result: SubmitResult,
    ) : WjxSubmitter {
        val callCount = AtomicInteger(0)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val seen = Collections.synchronizedList(mutableListOf<List<AnswerPair>>())

        override suspend fun submit(
            m: SurveyModel,
            answers: List<AnswerPair>,
            captchaToken: String?,
        ): SubmitResult {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            callCount.incrementAndGet()
            seen.add(answers)
            try {
                val wait = answers.firstOrNull()?.value?.toLongOrNull() ?: 0L
                if (wait > 0) delay(wait)
                return result
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun group(name: String, delayValue: Long): AnswerGroup = AnswerGroup(
        name = name,
        pairs = listOf(AnswerPair("1", delayValue.toString())),
    )

    private fun template(
        groups: List<AnswerGroup>,
        concurrency: Int = 2,
    ): MappingTemplate = MappingTemplate(
        id = "tpl",
        name = "并发测试",
        surveyUrl = "https://www.wjx.cn/vm/Q0DQewW.aspx",
        shortId = "Q0DQewW",
        groups = groups,
        concurrency = concurrency,
    )

    @Test
    fun concurrencyNeverExceedsLimit() = runTest {
        val submitter = FakeSubmitter(SubmitResult(true, 200, "提交成功", "10〒"))
        val coordinator = SubmitCoordinator(
            clientFactory = { FakeClient(Result.success(surveyModel())) },
            submitter = submitter,
            dispatcher = Dispatchers.IO,
        )
        val report = coordinator.run(
            template(List(5) { group("g" + (it + 1), 40L) }, concurrency = 2),
            concurrency = 2,
        )

        assertEquals(5, report.successCount)
        assertTrue("最大同时在线 = " + submitter.maxActive.get(), submitter.maxActive.get() <= 2)
    }

    @Test
    fun concurrencyIsClampedToOne() = runTest {
        val submitter = FakeSubmitter(SubmitResult(true, 200, "提交成功", "10〒"))
        val coordinator = SubmitCoordinator(
            clientFactory = { FakeClient(Result.success(surveyModel())) },
            submitter = submitter,
            dispatcher = Dispatchers.IO,
        )
        val report = coordinator.run(template(List(3) { group("g" + it, 20L) }, concurrency = 0), concurrency = 0)

        assertEquals(3, report.successCount)
        assertEquals(1, submitter.maxActive.get())
    }

    @Test
    fun resultsAreReturnedInGroupOrderNotCompletionOrder() = runTest {
        val submitter = FakeSubmitter(SubmitResult(true, 200, "提交成功", "10〒"))
        val coordinator = SubmitCoordinator(
            clientFactory = { FakeClient(Result.success(surveyModel())) },
            submitter = submitter,
            dispatcher = Dispatchers.IO,
        )
        // 第 1 组最慢、最后完成；返回顺序仍必须是 0,1,2
        val groups = listOf(group("slow", 60L), group("mid", 30L), group("fast", 1L))
        val report = coordinator.run(template(groups, concurrency = 3), concurrency = 3)

        assertEquals(listOf(0, 1, 2), report.outcomes.map { it.index })
        assertEquals(listOf("slow", "mid", "fast"), report.outcomes.map { it.groupName })
    }

    @Test
    fun everyGroupFetchesItsOwnSession() = runTest {
        val clients = Collections.synchronizedList(mutableListOf<FakeClient>())
        val submitter = FakeSubmitter(SubmitResult(true, 200, "提交成功", "10〒"))
        val coordinator = SubmitCoordinator(
            clientFactory = {
                val client = FakeClient(Result.success(surveyModel()))
                clients.add(client)
                client
            },
            submitter = submitter,
            dispatcher = Dispatchers.IO,
        )
        coordinator.run(template(List(3) { group("g" + it, 0L) }, concurrency = 2), concurrency = 2)

        assertEquals(3, clients.size)
        assertEquals(3, clients.sumOf { it.fetchCount.get() })
        assertEquals(3, submitter.callCount.get())
    }

    @Test
    fun fetchFailureKeepsErrorCodeAndSkipsSubmit() = runTest {
        val submitter = FakeSubmitter(SubmitResult(true, 200, "提交成功", "10〒"))
        val failure = WjxException(SubmitErrorCode.PARSE, "问卷页面解析失败，可能是问卷已关闭或页面改版")
        val coordinator = SubmitCoordinator(
            clientFactory = { FakeClient(Result.failure(failure)) },
            submitter = submitter,
            dispatcher = Dispatchers.IO,
        )
        val report = coordinator.run(template(listOf(group("g1", 0L))), concurrency = 1)

        val outcome = report.outcomes.single()
        assertEquals(SubmitErrorCode.PARSE, outcome.result.errorCode)
        assertEquals(0, outcome.result.httpStatus)
        assertEquals("问卷页面解析失败，可能是问卷已关闭或页面改版", outcome.result.message)
        assertEquals(0, submitter.callCount.get())
    }

    @Test
    fun emptyGroupIsReportedWithoutAnyNetworkCall() = runTest {
        val clients = Collections.synchronizedList(mutableListOf<FakeClient>())
        val submitter = FakeSubmitter(SubmitResult(true, 200, "提交成功", "10〒"))
        val coordinator = SubmitCoordinator(
            clientFactory = {
                val client = FakeClient(Result.success(surveyModel()))
                clients.add(client)
                client
            },
            submitter = submitter,
            dispatcher = Dispatchers.IO,
        )
        val emptyGroup = AnswerGroup(name = "空组", pairs = listOf(AnswerPair("1", "")))
        val report = coordinator.run(template(listOf(emptyGroup)), concurrency = 1)

        assertEquals(SubmitErrorCode.EMPTY, report.outcomes.single().result.errorCode)
        assertEquals(0, clients.size)
        assertEquals(0, submitter.callCount.get())
    }

    @Test
    fun noGroupsReturnsEmptyReport() = runTest {
        val coordinator = SubmitCoordinator(
            clientFactory = { FakeClient(Result.success(surveyModel())) },
            submitter = FakeSubmitter(SubmitResult(true, 200, "提交成功", "10〒")),
            dispatcher = Dispatchers.IO,
        )
        val report = coordinator.run(template(emptyList()), concurrency = 2)
        assertEquals(0, report.total)
    }

    @Test
    fun progressIsMonotonicAndCoversEveryGroup() = runTest {
        val seenDone = Collections.synchronizedList(mutableListOf<Int>())
        val submitter = FakeSubmitter(SubmitResult(true, 200, "提交成功", "10〒"))
        val coordinator = SubmitCoordinator(
            clientFactory = { FakeClient(Result.success(surveyModel())) },
            submitter = submitter,
            dispatcher = Dispatchers.IO,
        )
        coordinator.run(
            template(List(4) { group("g" + it, 5L) }, concurrency = 2),
            concurrency = 2,
            onProgress = { done, total -> seenDone.add(done * 100 + total) },
        )

        assertEquals(4, seenDone.size)
        assertEquals(listOf(104, 204, 304, 404), seenDone.sorted())
    }

    @Test
    fun blankValuesAreFilteredBeforeSubmit() = runTest {
        val submitter = FakeSubmitter(SubmitResult(true, 200, "提交成功", "10〒"))
        val coordinator = SubmitCoordinator(
            clientFactory = { FakeClient(Result.success(surveyModel())) },
            submitter = submitter,
            dispatcher = Dispatchers.IO,
        )
        val mixed = AnswerGroup(
            name = "混合",
            pairs = listOf(AnswerPair("1", ""), AnswerPair("2", "x"), AnswerPair("3", "  ")),
        )
        coordinator.run(template(listOf(mixed)), concurrency = 1)

        val sent = submitter.seen.single()
        assertEquals(listOf(AnswerPair("2", "x")), sent)
    }

    @Test
    fun summaryCountsSuccessAndFailure() {
        val report = BatchReport(
            outcomes = listOf(
                GroupOutcome(0, "a", SubmitResult(true, 200, "提交成功", "10〒")),
                GroupOutcome(1, "b", SubmitResult(false, 0, "没有可提交的答案", null, SubmitErrorCode.EMPTY)),
            ),
            durationMs = 12L,
        )
        assertEquals(2, report.total)
        assertEquals(1, report.successCount)
        assertEquals(1, report.failureCount)
        assertEquals("成功 1 / 失败 1 / 共 2", report.summary())
    }

    @Test
    fun aliVerifyModelIsHandedToSubmitterWithoutLocalShortCircuit() = runTest {
        // Lead 裁定：取消 useAliVerify 本地门控。编排层必须把 useAliVerify=true 的模型真的交给 submitter；
        // 若还有短路，submitter 一次都不会被调用，结果直接是 E_CAPTCHA + httpStatus=0。
        val submitter = FakeSubmitter(
            SubmitResult(
                false,
                200,
                "该问卷开启了安全校验（阿里云验证码），纯接口无法提交",
                "7〒需要安全校验，请重新提交！",
                SubmitErrorCode.CAPTCHA,
            ),
        )
        val coordinator = SubmitCoordinator(
            clientFactory = { FakeClient(Result.success(surveyModel().copy(useAliVerify = true))) },
            submitter = submitter,
            dispatcher = Dispatchers.IO,
        )
        val report = coordinator.run(template(listOf(group("g1", 0L))), concurrency = 1)

        assertEquals("submitter 必须被调用一次（不得本地短路）", 1, submitter.callCount.get())
        assertEquals(SubmitErrorCode.CAPTCHA, report.outcomes.single().result.errorCode)
        assertEquals(200, report.outcomes.single().result.httpStatus)
    }

}
