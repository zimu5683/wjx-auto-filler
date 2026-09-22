package com.wjx.autofill.wjx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开放时间判定（E_NOT_OPEN）与页面层人机验证提示（needsCaptchaHint）（T16）。
 *
 * 纪律：真实问卷页 HTML 不入库，这里全部用**合成 HTML**。
 * 契约要点：
 *   - E_NOT_OPEN 在 jqnonce 校验之后、题目解析之前短路（未开放页仍带 jqnonce/starttime，只是不下发题目）；
 *   - needsCaptchaHint 只读 useAliVerify 的**值**，不读标记是否存在（标记是所有问卷的模板常量）。
 */
class WjxPageOpenTimeTest {

    private val url = "https://www.wjx.cn/vm/Q0DQewW.aspx"

    private fun page(scriptVars: String = "", head: String = ""): String =
        "<html><head><title>合成问卷</title></head><body>" +
            "<script>var jqnonce='e2a8439c-aa7b-41d7-b0c7-9c10852d66d6'; var ktimes=0; " + scriptVars + "</script>" +
            head +
            "<form id='form1' action='https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW'></form>" +
            "<div id='divQuestion'>" +
            "<div class='field ui-field-contain' topic='1'><div class='topichtml'>姓名</div><input type='text'></div>" +
            "</div></body></html>"

    private fun parseOrThrow(html: String): WjxException? = try {
        WjxPageParser.parse(url, html)
        null
    } catch (e: WjxException) {
        e
    }

    // ------------------------------------------------------------ E_NOT_OPEN

    @Test
    fun futureBeginDateThrowsNotOpen() {
        val future = System.currentTimeMillis() + 86_400_000L
        val thrown = parseOrThrow(page(scriptVars = "var BeginDate='" + future + "';"))
        assertNotNull("未开放问卷必须短路，不能落到 E_PARSE", thrown)
        assertEquals(SubmitErrorCode.NOT_OPEN, thrown!!.code)
        assertTrue("文案应含开放时间，实际：" + thrown.message, thrown.message.contains("开放"))
        assertTrue("文案应是北京时间格式", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").containsMatchIn(thrown.message))
    }

    @Test
    fun pastBeginDateParsesNormally() {
        val past = System.currentTimeMillis() - 86_400_000L
        val model = WjxPageParser.parse(url, page(scriptVars = "var BeginDate='" + past + "';"))
        assertEquals(1, model.questions.size)
        assertEquals(past, model.openAtMillis)
    }

    @Test
    fun missingOrZeroBeginDateDoesNotBlock() {
        // 解析不到开放时间 → Unknown → 不拦截（宁可让用户尝试，也不误报未开放）
        val none = WjxPageParser.parse(url, page())
        assertEquals(1, none.questions.size)
        assertNull(none.openAtMillis)

        val zero = WjxPageParser.parse(url, page(scriptVars = "var BeginDate='0';"))
        assertEquals(1, zero.questions.size)
        assertNull(zero.openAtMillis)
    }

    @Test
    fun textOnlyPageAlsoTriggersNotOpen() {
        val thrown = parseOrThrow(
            page(head = "<div id='divstarttime'>此问卷将于2030-09-23 09:33（北京时间）开放</div>"),
        )
        assertNotNull(thrown)
        assertEquals(SubmitErrorCode.NOT_OPEN, thrown!!.code)
        assertTrue(thrown.message.contains("2030-09-23"))
    }

    @Test
    fun notOpenTakesPrecedenceOverQuestionParse() {
        // 未开放页题目为空：若没有 E_NOT_OPEN 短路，会误报 E_PARSE「可能已关闭或页面改版」
        val future = System.currentTimeMillis() + 86_400_000L
        val html = "<html><script>var jqnonce='abc'; var BeginDate='" + future + "';</script>" +
            "<div id='divQuestion'></div></html>"
        val thrown = parseOrThrow(html)
        assertEquals(SubmitErrorCode.NOT_OPEN, thrown!!.code)
    }

    // ------------------------------------------------------- needsCaptchaHint

    @Test
    fun needsCaptchaHintTrueWhenUseAliVerifyIsOne() {
        val model = WjxPageParser.parse(url, page(scriptVars = "var useAliVerify=1;"))
        assertTrue("useAliVerify=1 应提示需要验证码", model.needsCaptchaHint)
        assertTrue(model.useAliVerify)
    }

    @Test
    fun needsCaptchaHintFalseWhenMarkersExistButValuesAreZero() {
        // 关键防回归：这些标记在所有问卷页都有（模板常量），看"存在"会 100% 误报
        val model = WjxPageParser.parse(
            url,
            page(
                scriptVars = "var useAliVerify=0; var needLoadAliVerify=0; var captchaWrap=0;",
                head = "<script src='https://image.wjx.cn/joinnew/js/wjx_captch.js'></script>" +
                    "<div class='captchaWrap'></div>",
            ),
        )
        assertTrue("标记存在但值为 0 → 必须 false", !model.needsCaptchaHint)
        assertTrue(!model.useAliVerify)
    }

    @Test
    fun needsCaptchaHintFalseWhenUseAliVerifyAbsent() {
        val model = WjxPageParser.parse(url, page())
        assertTrue("缺失 useAliVerify → false", !model.needsCaptchaHint)
    }
}
