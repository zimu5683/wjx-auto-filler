package com.wjx.autofill.wjx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 页面解析（契约 §6）。输入是 tools/wjx-probe/fixtures/ 下的**只读抓取**的真实问卷页 HTML
 * （见该目录 README：从未向这些问卷提交过任何数据），因此题型/题数断言都是真实数据。
 *
 * 覆盖：
 *   - 4 个真实 fixture 的题数、题号连续升序、题型分布、选项、useAliVerify、captchaType、submitUrl；
 *   - 合成 HTML 覆盖 §6.2 的 8 种题型判定（真实 fixture 里没有 DROPDOWN/MATRIX/SLIDER/OTHER）；
 *   - §6.5 分页检测与 E_URL / E_PARSE 三条错误路径。
 */
class WjxPageParserTest {

    /**
     * 夹具目录：优先 -Dwjx.fixtures.dir / 环境变量 WJX_FIXTURES_DIR，否则从 user.dir 向上找
     * tools/wjx-probe/fixtures。找不到返回 null。
     *
     * 为什么允许缺失：真实问卷页 HTML 是**别人的问卷内容**，不进公开仓库（不转分发）。
     * 因此 CI / 全新克隆上 fixtures/ 不存在，依赖它的用例必须**跳过而不是失败**，
     * 否则 A10「脱离 Termux 也能构建」会被 test job 卡红。
     * 重新抓取方式见 tools/wjx-probe/fixtures/README.md（api-debug 的只读抓取脚本）。
     */
    private fun fixturesDir(): File? {
        val override = System.getProperty("wjx.fixtures.dir") ?: System.getenv("WJX_FIXTURES_DIR")
        if (!override.isNullOrBlank()) {
            val dir = File(override)
            return if (dir.isDirectory) dir else null
        }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/wjx-probe/fixtures")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    /** 取夹具；缺失时打印可见原因并 assumeTrue(false) 跳过（不静默、不判失败）。 */
    private fun fixtureOrSkip(name: String): File {
        val file = fixturesDir()?.let { File(it, name) }
        if (file == null || !file.isFile) {
            println(
                "[skip] fixtures 未提供（找不到 tools/wjx-probe/fixtures/" + name + "），跳过真实页面解析用例。" +
                    "解析覆盖仍由「合成 HTML 8 种题型」用例保证。" +
                    "需要真实页面夹具时：见 tools/wjx-probe/fixtures/README.md 的只读抓取方式重新生成。",
            )
            assumeTrue("fixtures 未提供，跳过：" + name, false)
        }
        return file!!
    }

    private fun parseFixture(fileName: String, url: String): SurveyModel =
        WjxPageParser.parse(url, fixtureOrSkip(fileName).readText(Charsets.UTF_8))

    // ------------------------------------------------------- 真实 fixture

    @Test
    fun sampleSurveyHasThreeTextQuestionsAndCaptchaGateOn() {
        val model = parseFixture(
            "Q0DQewW-3q-text-captcha-enabled.html",
            "https://www.wjx.cn/vm/Q0DQewW.aspx",
        )

        assertEquals("测试", model.title)
        assertEquals("Q0DQewW", model.shortId)
        assertEquals(3, model.questions.size)
        assertEquals(listOf(1, 2, 3), model.questions.map { it.topic })
        assertTrue("样本问卷全是填空题", model.questions.all { it.type == QuestionType.TEXT })
        assertTrue("填空题没有选项", model.questions.all { it.options.isEmpty() })
        assertTrue("题干不能为空", model.questions.all { it.title.isNotBlank() })

        assertTrue("样本问卷开启了阿里云校验", model.useAliVerify)
        assertEquals(2, model.captchaType)
        assertNull("样本页不内联 captchaSceneid", model.sceneId)
        assertTrue("jqnonce 非空", model.jqnonce.isNotBlank())
        assertTrue("submitUrl 指向 processjq.ashx", model.submitUrl.contains("processjq.ashx"))
        assertTrue("submitUrl 带 shortid", model.submitUrl.contains("Q0DQewW"))
    }

    @Test
    fun choiceFixtureDetectsSingleAndMultiWithOptions() {
        val model = parseFixture(
            "rRESgvn-22q-choice.html",
            "https://www.wjx.cn/vm/rRESgvn.aspx",
        )

        assertEquals("Questionnaire Design", model.title)
        assertEquals(22, model.questions.size)
        assertEquals((1..22).toList(), model.questions.map { it.topic })
        val types = model.questions.groupingBy { it.type }.eachCount()
        assertEquals(19, types[QuestionType.SINGLE])
        assertEquals(3, types[QuestionType.MULTI])
        assertTrue("纯选择题每题都必须有选项", model.questions.all { it.options.isNotEmpty() })
        assertTrue("选项 value 不能为空", model.questions.all { q -> q.options.all { it.value.isNotBlank() } })
        assertFalse(model.useAliVerify)
        assertEquals(2, model.captchaType)
    }

    @Test
    fun mixedFixtureCoversTextSingleAndMulti() {
        val model = parseFixture(
            "hPyt0iq-37q-text-single-multi.html",
            "https://www.wjx.cn/vm/hPyt0iq.aspx",
        )

        assertEquals(37, model.questions.size)
        assertEquals((1..37).toList(), model.questions.map { it.topic })
        val types = model.questions.groupingBy { it.type }.eachCount()
        assertEquals(2, types[QuestionType.TEXT])
        assertEquals(22, types[QuestionType.SINGLE])
        assertEquals(13, types[QuestionType.MULTI])
        // 题型 1（填空）的两题就是没有选项的两题
        assertEquals(listOf(1, 2), model.questions.filter { it.options.isEmpty() }.map { it.topic })
        val single = model.questions.first { it.type == QuestionType.SINGLE }
        assertEquals(listOf("1" to "男", "2" to "女"), single.options.map { it.value to it.label })
        assertFalse(model.useAliVerify)
    }

    @Test
    fun russianFixtureParsesAllQuestions() {
        val model = parseFixture(
            "PuE9RFq-18q.html",
            "https://www.wjx.cn/vm/PuE9RFq.aspx",
        )

        assertEquals(18, model.questions.size)
        assertEquals((1..18).toList(), model.questions.map { it.topic })
        val types = model.questions.groupingBy { it.type }.eachCount()
        assertEquals(4, types[QuestionType.TEXT])
        assertEquals(11, types[QuestionType.SINGLE])
        assertEquals(3, types[QuestionType.MULTI])
        assertEquals(listOf(5, 16, 17, 18), model.questions.filter { it.options.isEmpty() }.map { it.topic })
        assertFalse(model.useAliVerify)
    }

    @Test
    fun everyFixtureKeepsTopicsSortedAscending() {
        val cases = listOf(
            "Q0DQewW-3q-text-captcha-enabled.html" to "https://www.wjx.cn/vm/Q0DQewW.aspx",
            "hPyt0iq-37q-text-single-multi.html" to "https://www.wjx.cn/vm/hPyt0iq.aspx",
            "PuE9RFq-18q.html" to "https://www.wjx.cn/vm/PuE9RFq.aspx",
            "rRESgvn-22q-choice.html" to "https://www.wjx.cn/vm/rRESgvn.aspx",
        )
        for ((file, url) in cases) {
            val model = parseFixture(file, url)
            val topics = model.questions.map { it.topic }
            assertEquals(file + " 题号必须升序", topics.sorted(), topics)
            assertEquals(file + " 题号必须唯一", topics.size, topics.toSet().size)
        }
    }

    // ------------------------------------------------------- 合成题型

    private val syntheticTypesHtml = """
        <html><head><title>合成题型</title></head><body>
        <script>var jqnonce="abc"; var ktimes=3;</script>
        <form id="form1" action="https://www.wjx.cn/joinnew/processjq.ashx?shortid=SYNTH01"></form>
        <div id="divQuestion">
        <div class="field ui-field-contain" topic="1"><div class="topichtml">下拉题</div><select><option value="1">A</option><option value="2">B</option></select></div>
        <div class="field ui-field-contain" topic="2"><div class="topichtml">矩阵题</div><table><tr><td>x</td></tr></table></div>
        <div class="field ui-field-contain" topic="3"><div class="topichtml">量表题</div><input type="range" value="5"></div>
        <div class="field ui-field-contain" topic="4"><div class="topichtml">其他题</div><div>商品预约</div></div>
        <div class="field ui-field-contain" topic="5"><div class="topichtml">填空题</div><textarea></textarea></div>
        <div class="field ui-field-contain" topic="6"><div class="topichtml">单选</div><span class="ui-radio"><input type="radio" value="1"><label>A</label></span><span class="ui-radio"><input type="radio" value="2"><label>B</label></span></div>
        <div class="field ui-field-contain" topic="7"><div class="topichtml">多选</div><span class="ui-checkbox"><input type="checkbox" value="1"><label>A</label></span></div>
        <div class="field ui-field-contain" topic="8"><div class="topichtml">文本输入</div><input type="text"></div>
        </div></body></html>
    """.trimIndent()

    @Test
    fun syntheticHtmlCoversEveryQuestionTypeInContract() {
        val model = WjxPageParser.parse("https://www.wjx.cn/vm/SYNTH01.aspx", syntheticTypesHtml)

        assertEquals("合成题型", model.title)
        assertEquals(3, model.ktimes)
        assertFalse(model.useAliVerify)
        assertNull("页面没声明验证码 → captchaType 为 null", model.captchaType)
        assertEquals("https://www.wjx.cn/joinnew/processjq.ashx?shortid=SYNTH01", model.submitUrl)

        val byTopic = model.questions.associateBy { it.topic }
        assertEquals(QuestionType.DROPDOWN, byTopic.getValue(1).type)
        // 契约 §6.2：DROPDOWN 的 options = option 的 value + 文本
        // （2026-09-22 曾报偏差给 api-debug，已修复；本断言为其回归保护）
        assertEquals(listOf("1", "2"), byTopic.getValue(1).options.map { it.value })
        assertEquals(listOf("A", "B"), byTopic.getValue(1).options.map { it.label })
        assertEquals(QuestionType.MATRIX, byTopic.getValue(2).type)
        assertEquals(QuestionType.SLIDER, byTopic.getValue(3).type)
        assertEquals(QuestionType.OTHER, byTopic.getValue(4).type)
        assertEquals(QuestionType.TEXT, byTopic.getValue(5).type)
        assertEquals(QuestionType.SINGLE, byTopic.getValue(6).type)
        assertEquals(listOf("1", "2"), byTopic.getValue(6).options.map { it.value })
        assertEquals(QuestionType.MULTI, byTopic.getValue(7).type)
        assertEquals(listOf("1"), byTopic.getValue(7).options.map { it.value })
        assertEquals(QuestionType.TEXT, byTopic.getValue(8).type)
        assertTrue("非选择题不应带选项", byTopic.getValue(5).options.isEmpty())
        assertTrue(byTopic.getValue(2).options.isEmpty())
    }

    // ------------------------------------------------------- 错误路径

    @Test
    fun invalidUrlThrowsUrlError() {
        val thrown = try {
            WjxPageParser.parse("https://example.com/vm/xxxx.aspx", "<html>var jqnonce=\"a\";</html>")
            null
        } catch (e: WjxException) {
            e
        }
        assertNotNull("非 wjx 链接必须报 E_URL", thrown)
        assertEquals(SubmitErrorCode.URL, thrown!!.code)
    }

    @Test
    fun missingJqnonceThrowsParseError() {
        val thrown = try {
            WjxPageParser.parse("https://www.wjx.cn/vm/Q0DQewW.aspx", "<html></html>")
            null
        } catch (e: WjxException) {
            e
        }
        assertNotNull(thrown)
        assertEquals(SubmitErrorCode.PARSE, thrown!!.code)
    }

    @Test
    fun pagedSurveyThrowsPagedError() {
        val html = "<html>var jqnonce=\"abc\"; IsOneQuestionPerPage = 1;</html>"
        val thrown = try {
            WjxPageParser.parse("https://www.wjx.cn/vm/Q0DQewW.aspx", html)
            null
        } catch (e: WjxException) {
            e
        }
        assertNotNull("分页问卷必须显式报 E_PAGED", thrown)
        assertEquals(SubmitErrorCode.PAGED, thrown!!.code)
        assertTrue(thrown.message.contains("分页"))
    }
}
