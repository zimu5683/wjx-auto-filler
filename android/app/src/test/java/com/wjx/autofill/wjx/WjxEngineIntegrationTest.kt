package com.wjx.autofill.wjx

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 端到端集成测试（**默认跳过**，不占常规测试时间，也不产生网络请求）。
 *
 * 开启方式（三选一，任一为真即运行）：
 *   1. 系统属性：-Dintegration=true（直接 java 跑时可用；Gradle 默认不向 test JVM 透传，见下）
 *   2. 环境变量：WJX_INTEGRATION=true（注意 Gradle daemon 会复用旧环境，必要时加 --no-daemon）
 *   3. 标志文件：仓库内 testdata/run-integration.flag 存在
 *      （build-apk.sh --integration 会创建它，跑完删除；这是最确定的一条路）
 *
 * 断言的事实来自 T3 实测（docs/API-CONTRACT.md §11）：
 *   - 样本问卷 https://www.wjx.cn/vm/Q0DQewW.aspx 有 3 道填空题（题号 1/2/3，TEXT），jqnonce 非空；
 *   - 提交被阿里云验证码拦截：HTTP 200 + 正文 7〒需要安全校验，请重新提交！ → errorCode = E_CAPTCHA（终态）。
 * 若问卷方放宽了验证码，提交成功也接受（ok=true、errorCode=null）。
 */
class WjxEngineIntegrationTest {

    private val surveyUrl = "https://www.wjx.cn/vm/Q0DQewW.aspx"

    private fun integrationEnabled(): Boolean {
        if (System.getProperty("integration") == "true") return true
        if (System.getenv("WJX_INTEGRATION") == "true") return true
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "testdata/run-integration.flag").isFile) return true
            dir = dir.parentFile
        }
        return false
    }

    @Test
    fun fetchAndSubmitSampleSurvey() {
        assumeTrue("集成测试默认跳过（见类注释里的三种开启方式）", integrationEnabled())

        runBlocking {
            val client = HttpWjxSurveyClient()
            val fetched = client.fetch(surveyUrl)
            assertTrue("fetch 应成功，实际：" + fetched.exceptionOrNull(), fetched.isSuccess)
            val model = fetched.getOrThrow()

            assertEquals("Q0DQewW", model.shortId)
            assertTrue("jqnonce 不能为空", model.jqnonce.isNotBlank())
            assertEquals("样本问卷应有 3 道题", 3, model.questions.size)
            assertEquals(listOf(1, 2, 3), model.questions.map { it.topic })
            assertTrue("样本问卷都是填空题", model.questions.all { it.type == QuestionType.TEXT })

            val answers = model.questions.map { AnswerPair(it.topic.toString(), "测试答案" + it.topic) }
            val result = HttpWjxSubmitter().submit(model, answers)

            println(
                "[integration] submit -> ok=" + result.ok + " http=" + result.httpStatus +
                    " errorCode=" + result.errorCode + " message=" + result.message +
                    " raw=" + (result.raw ?: ""),
            )
            if (result.ok) {
                assertNull("成功时 errorCode 必须为 null", result.errorCode)
            } else {
                assertEquals(
                    "T3 实测：本样本问卷被阿里云验证码拦截（业务码 7）",
                    SubmitErrorCode.CAPTCHA,
                    result.errorCode,
                )
                // 两条路径都合法：
                //  - useAliVerify=true 且无令牌 → 本地硬门控：httpStatus=0、raw=null，**不发任何请求**（本样本走这条）；
                //  - 令牌非空 / 页面未声明门控 → 真发一次 POST，raw 保留服务端原文（7〒需要安全校验…）。
                if (result.httpStatus == 0) {
                    assertNull("本地硬门控不应有响应体", result.raw)
                } else {
                    assertNotNull("真实响应必须保留原文", result.raw)
                }
            }
        }
    }
}
