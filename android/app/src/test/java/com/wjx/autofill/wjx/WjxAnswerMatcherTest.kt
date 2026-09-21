package com.wjx.autofill.wjx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段匹配（契约 §7）：R1 精确题号 > R2 题干包含 > R3 选项文本；歧义与未命中都算失败。
 */
class WjxAnswerMatcherTest {

    private fun model(vararg questions: SurveyQuestion): SurveyModel = SurveyModel(
        url = "https://www.wjx.cn/vm/Q0DQewW.aspx",
        shortId = "Q0DQewW",
        title = "匹配测试",
        questions = questions.toList(),
        submitUrl = "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW",
        jqnonce = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6",
        ktimes = 3,
        startTime = "",
        captchaType = null,
        cookies = emptyMap(),
    )

    private val textQ = SurveyQuestion(1, "姓名", QuestionType.TEXT, emptyList())
    private val singleQ = SurveyQuestion(
        2, "性别", QuestionType.SINGLE,
        listOf(Option("1", "男"), Option("2", "女")),
    )
    private val multiQ = SurveyQuestion(
        3, "爱好", QuestionType.MULTI,
        listOf(Option("1", "读书"), Option("2", "运动"), Option("3", "音乐")),
    )

    private fun okPairs(outcome: MatchOutcome): List<Pair<Int, String>> {
        assertTrue("期望成功，实际：" + outcome, outcome is MatchOutcome.Ok)
        return (outcome as MatchOutcome.Ok).pairs
    }

    private fun fail(outcome: MatchOutcome): MatchOutcome.Fail {
        assertTrue("期望失败，实际：" + outcome, outcome is MatchOutcome.Fail)
        return outcome as MatchOutcome.Fail
    }

    // ------------------------------------------------------------- R1

    @Test
    fun r1ExactTopicNumberWins() {
        val pairs = okPairs(
            WjxAnswerMatcher.match(model(textQ, singleQ, multiQ), listOf(AnswerPair("2", "男"))),
        )
        assertEquals(listOf(2 to "1"), pairs)
    }

    @Test
    fun r1TopicIsTrimmed() {
        val pairs = okPairs(WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("  1  ", "张三"))))
        assertEquals(listOf(1 to "张三"), pairs)
    }

    // ------------------------------------------------------------- R2

    @Test
    fun r2TitleContainsMatchesChinese() {
        val pairs = okPairs(WjxAnswerMatcher.match(model(textQ, singleQ), listOf(AnswerPair("姓名", "张三"))))
        assertEquals(listOf(1 to "张三"), pairs)
    }

    @Test
    fun r2TitleContainsIgnoresAsciiCase() {
        val email = SurveyQuestion(1, "Email Address", QuestionType.TEXT, emptyList())
        val pairs = okPairs(WjxAnswerMatcher.match(model(email), listOf(AnswerPair("email", "a@b.c"))))
        assertEquals(listOf(1 to "a@b.c"), pairs)
    }

    @Test
    fun r1BeatsR2WhenBothCouldMatch() {
        // 题号 2 存在，且题 1 的题干里也含 "2" → 必须走 R1 命中题 2
        val tricky = SurveyQuestion(1, "第2题的说明", QuestionType.TEXT, emptyList())
        val pairs = okPairs(WjxAnswerMatcher.match(model(tricky, singleQ), listOf(AnswerPair("2", "男"))))
        assertEquals(listOf(2 to "1"), pairs)
    }

    // ------------------------------------------------------------- R3

    @Test
    fun r3OptionLabelMatchesAndEmptyValueTakesOptionValue() {
        val pairs = okPairs(WjxAnswerMatcher.match(model(singleQ), listOf(AnswerPair("男", ""))))
        assertEquals(listOf(2 to "1"), pairs)
    }

    @Test
    fun r3OptionLabelWithExplicitValueKeepsValue() {
        val pairs = okPairs(WjxAnswerMatcher.match(model(singleQ), listOf(AnswerPair("女", "2"))))
        assertEquals(listOf(2 to "2"), pairs)
    }

    @Test
    fun r3LooseContainsMatchesOptionLabel() {
        val pairs = okPairs(WjxAnswerMatcher.match(model(multiQ), listOf(AnswerPair("读书", "1"))))
        assertEquals(listOf(3 to "1"), pairs)
    }

    // ------------------------------------------------------- 值解析

    @Test
    fun textValueIsEscaped() {
        val pairs = okPairs(WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("1", "a\$b}c"))))
        assertEquals(listOf(1 to "aξb｝c"), pairs)
    }

    @Test
    fun textValueLongerThanLimitFailsWithLimitCode() {
        val failure = fail(
            WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("1", "x".repeat(3001)))),
        )
        assertEquals(SubmitErrorCode.LIMIT, failure.code)
        assertTrue(failure.message.contains("3000"))
    }

    @Test
    fun textValueExactlyAtLimitIsAccepted() {
        val pairs = okPairs(WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("1", "x".repeat(3000)))))
        assertEquals(3000, pairs.single().second.length)
    }

    @Test
    fun singleValueNotInOptionsFails() {
        val failure = fail(WjxAnswerMatcher.match(model(singleQ), listOf(AnswerPair("2", "不确定"))))
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
        assertTrue(failure.message.contains("不在选项中"))
    }

    @Test
    fun multiValueIsSplitByPipeAndResolvedToOptionValues() {
        val pairs = okPairs(WjxAnswerMatcher.match(model(multiQ), listOf(AnswerPair("3", "读书|音乐"))))
        assertEquals(listOf(3 to "1|3"), pairs)
    }

    @Test
    fun multiAcceptsOptionLabelsAndDropsEmptySegments() {
        val pairs = okPairs(WjxAnswerMatcher.match(model(multiQ), listOf(AnswerPair("3", "运动||音乐|"))))
        assertEquals(listOf(3 to "2|3"), pairs)
    }

    @Test
    fun multiWithUnknownSegmentFails() {
        val failure = fail(WjxAnswerMatcher.match(model(multiQ), listOf(AnswerPair("3", "读书|爬山"))))
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
    }

    @Test
    fun matrixSliderOtherAreUnsupported() {
        val matrix = SurveyQuestion(1, "矩阵", QuestionType.MATRIX, emptyList())
        val slider = SurveyQuestion(2, "量表", QuestionType.SLIDER, emptyList())
        val other = SurveyQuestion(3, "商品", QuestionType.OTHER, emptyList())
        val m = model(matrix, slider, other)

        assertEquals(SubmitErrorCode.UNSUPPORTED, fail(WjxAnswerMatcher.match(m, listOf(AnswerPair("1", "x")))).code)
        assertEquals(SubmitErrorCode.UNSUPPORTED, fail(WjxAnswerMatcher.match(m, listOf(AnswerPair("2", "5")))).code)
        assertEquals(SubmitErrorCode.UNSUPPORTED, fail(WjxAnswerMatcher.match(m, listOf(AnswerPair("3", "x")))).code)
    }

    @Test
    fun dropdownResolvesLikeSingle() {
        val dropdown = SurveyQuestion(1, "城市", QuestionType.DROPDOWN, listOf(Option("1", "北京"), Option("2", "上海")))
        val pairs = okPairs(WjxAnswerMatcher.match(model(dropdown), listOf(AnswerPair("1", "上海"))))
        assertEquals(listOf(1 to "2"), pairs)
    }

    // ------------------------------------------------------- 失败路径

    @Test
    fun unmatchedFieldFails() {
        val failure = fail(WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("不存在的字段", "x"))))
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
        assertTrue(failure.message.contains("未匹配到任何题目"))
    }

    @Test
    fun ambiguousTitleMatchFails() {
        val q1 = SurveyQuestion(1, "姓名（本人）", QuestionType.TEXT, emptyList())
        val q2 = SurveyQuestion(2, "姓名（家长）", QuestionType.TEXT, emptyList())
        val failure = fail(WjxAnswerMatcher.match(model(q1, q2), listOf(AnswerPair("姓名", "张三"))))
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
        assertTrue("message 应列出题号，实际：" + failure.message, failure.message.contains("多个题目"))
        assertTrue(failure.message.contains("1"))
        assertTrue(failure.message.contains("2"))
    }

    @Test
    fun twoFieldsPointingToSameQuestionFails() {
        val failure = fail(
            WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("1", "张三"), AnswerPair("姓名", "李四"))),
        )
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
        assertTrue(failure.message.contains("同一题"))
    }

    @Test
    fun blankFieldFails() {
        val failure = fail(WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("   ", "x"))))
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
    }

    @Test
    fun successKeepsInputOrderOfPairs() {
        val pairs = okPairs(
            WjxAnswerMatcher.match(
                model(textQ, singleQ, multiQ),
                listOf(AnswerPair("3", "读书"), AnswerPair("1", "张三")),
            ),
        )
        assertEquals(listOf(3 to "1", 1 to "张三"), pairs)
    }

    @Test
    fun failureDetailIsCappedAtFiveItems() {
        val qs = (1..8).map { SurveyQuestion(it, "题" + it, QuestionType.TEXT, emptyList()) }
        val answers = (1..8).map { AnswerPair("不存在" + it, "x") }
        val failure = fail(WjxAnswerMatcher.match(model(*qs.toTypedArray()), answers))
        assertTrue("超过 5 条应折叠，实际：" + failure.message, failure.message.contains("等 8 项"))
    }
}
