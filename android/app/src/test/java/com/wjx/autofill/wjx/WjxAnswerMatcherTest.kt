package com.wjx.autofill.wjx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段匹配（v1.0.5 契约 §7.2 / §7.4）。
 *
 * 两类结果：
 * - **可跳过**：未匹配到任何题目 → 记入 MatchOutcome.Ok.skippedFields，不失败；
 *   字段名为空（空行）→ 直接忽略（不计入 skippedFields、不失败）。
 * - **仍失败**：歧义（多题命中）/ 多字段指向同一题 / 取值不在选项 / 超长 / 不支持题型；
 *   且 pairs 为空（全部被跳过）→ 仍 Fail(E_UNMATCHED)。
 *
 * 规则优先级 R1 精确题号 > R2 题干包含 > R3 选项文本（不变）。
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

    private fun ok(outcome: MatchOutcome): MatchOutcome.Ok {
        assertTrue("期望 Ok，实际：" + outcome, outcome is MatchOutcome.Ok)
        return outcome as MatchOutcome.Ok
    }

    private fun fail(outcome: MatchOutcome): MatchOutcome.Fail {
        assertTrue("期望 Fail，实际：" + outcome, outcome is MatchOutcome.Fail)
        return outcome as MatchOutcome.Fail
    }

    // ------------------------------------------------------------- R1

    @Test
    fun r1ExactTopicNumberWins() {
        val result = ok(WjxAnswerMatcher.match(model(textQ, singleQ, multiQ), listOf(AnswerPair("2", "男"))))
        assertEquals(listOf(2 to "1"), result.pairs)
        assertTrue(result.skippedFields.isEmpty())
    }

    @Test
    fun r1TopicIsTrimmed() {
        assertEquals(listOf(1 to "张三"), ok(WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("  1  ", "张三")))).pairs)
    }

    // ------------------------------------------------------------- R2

    @Test
    fun r2TitleContainsMatchesChinese() {
        assertEquals(listOf(1 to "张三"), ok(WjxAnswerMatcher.match(model(textQ, singleQ), listOf(AnswerPair("姓名", "张三")))).pairs)
    }

    @Test
    fun r2TitleContainsIgnoresAsciiCase() {
        val email = SurveyQuestion(1, "Email Address", QuestionType.TEXT, emptyList())
        assertEquals(listOf(1 to "a@b.c"), ok(WjxAnswerMatcher.match(model(email), listOf(AnswerPair("email", "a@b.c")))).pairs)
    }

    @Test
    fun r1BeatsR2WhenBothCouldMatch() {
        val tricky = SurveyQuestion(1, "第2题的说明", QuestionType.TEXT, emptyList())
        assertEquals(listOf(2 to "1"), ok(WjxAnswerMatcher.match(model(tricky, singleQ), listOf(AnswerPair("2", "男")))).pairs)
    }

    // ------------------------------------------------------------- R3

    @Test
    fun r3OptionLabelMatchesAndEmptyValueTakesOptionValue() {
        assertEquals(listOf(2 to "1"), ok(WjxAnswerMatcher.match(model(singleQ), listOf(AnswerPair("男", "")))).pairs)
    }

    @Test
    fun r3OptionLabelWithExplicitValueKeepsValue() {
        assertEquals(listOf(2 to "2"), ok(WjxAnswerMatcher.match(model(singleQ), listOf(AnswerPair("女", "2")))).pairs)
    }

    @Test
    fun r3LooseContainsMatchesOptionLabel() {
        assertEquals(listOf(3 to "1"), ok(WjxAnswerMatcher.match(model(multiQ), listOf(AnswerPair("读书", "1")))).pairs)
    }

    // ------------------------------------------------------- 值解析

    @Test
    fun textValueIsEscaped() {
        assertEquals(listOf(1 to "aξb｝c"), ok(WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("1", "a\$b}c")))).pairs)
    }

    @Test
    fun textValueExactlyAtLimitIsAccepted() {
        assertEquals(3000, ok(WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("1", "x".repeat(3000))))).pairs.single().second.length)
    }

    @Test
    fun multiValueIsSplitByPipeAndResolvedToOptionValues() {
        assertEquals(listOf(3 to "1|3"), ok(WjxAnswerMatcher.match(model(multiQ), listOf(AnswerPair("3", "读书|音乐")))).pairs)
    }

    @Test
    fun multiAcceptsOptionLabelsAndDropsEmptySegments() {
        assertEquals(listOf(3 to "2|3"), ok(WjxAnswerMatcher.match(model(multiQ), listOf(AnswerPair("3", "运动||音乐|")))).pairs)
    }

    @Test
    fun dropdownResolvesLikeSingle() {
        val dropdown = SurveyQuestion(1, "城市", QuestionType.DROPDOWN, listOf(Option("1", "北京"), Option("2", "上海")))
        assertEquals(listOf(1 to "2"), ok(WjxAnswerMatcher.match(model(dropdown), listOf(AnswerPair("1", "上海")))).pairs)
    }

    // --------------------------------------------- v1.0.5：可跳过（不失败）

    @Test
    fun matchedPlusUnmatchedGivesOkWithSkippedFields() {
        // Lead 给的典型用例：3 题问卷 + 一个问卷里没有的字段
        val result = ok(
            WjxAnswerMatcher.match(
                model(textQ, singleQ, multiQ),
                listOf(AnswerPair("1", "张三"), AnswerPair("2", "男"), AnswerPair("3", "读书"), AnswerPair("身份证", "123")),
            ),
        )
        assertEquals(3, result.pairs.size)
        assertEquals(listOf("身份证"), result.skippedFields)
    }

    @Test
    fun skippedFieldsAreTrimmedAndDeduplicatedInOrder() {
        val result = ok(
            WjxAnswerMatcher.match(
                model(textQ),
                listOf(
                    AnswerPair("  不存在A  ", "x"),
                    AnswerPair("不存在B", "y"),
                    AnswerPair("不存在A", "z"),
                    AnswerPair("1", "张三"),
                ),
            ),
        )
        assertEquals(listOf("不存在A", "不存在B"), result.skippedFields)
        assertEquals(1, result.pairs.size)
    }

    @Test
    fun blankFieldIsIgnoredAndNotListedAsSkipped() {
        // 空行不是用户意图：既不失败、也不计入 skippedFields
        val result = ok(
            WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("", "x"), AnswerPair("   ", "y"), AnswerPair("1", "张三"))),
        )
        assertEquals(listOf(1 to "张三"), result.pairs)
        assertTrue("空字段名不得出现在 skippedFields：" + result.skippedFields, result.skippedFields.isEmpty())
    }

    @Test
    fun allFieldsSkippedStillFails() {
        val failure = fail(
            WjxAnswerMatcher.match(
                model(textQ),
                listOf(AnswerPair("不存在A", "x"), AnswerPair("不存在B", "y")),
            ),
        )
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
        assertTrue("message 应说明全部被跳过，实际：" + failure.message, failure.message.contains("全部字段都被跳过"))
    }

    @Test
    fun onlyBlankFieldsAlsoFailsBecauseNothingToSubmit() {
        val failure = fail(WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("", "x"))))
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
    }

    @Test
    fun allSkippedMessageCapsDetailAtFivePlusCount() {
        val qs = (1..8).map { SurveyQuestion(it, "题" + it, QuestionType.TEXT, emptyList()) }
        val answers = (1..8).map { AnswerPair("不存在" + it, "x") }
        val failure = fail(WjxAnswerMatcher.match(model(*qs.toTypedArray()), answers))
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
        assertTrue("应折叠为「等 8 项」，实际：" + failure.message, failure.message.contains("等 8 项"))
    }

    // --------------------------------------------- v1.0.5：仍失败的情形

    @Test
    fun ambiguousTitleMatchStillFails() {
        val q1 = SurveyQuestion(1, "姓名（本人）", QuestionType.TEXT, emptyList())
        val q2 = SurveyQuestion(2, "姓名（家长）", QuestionType.TEXT, emptyList())
        val failure = fail(WjxAnswerMatcher.match(model(q1, q2), listOf(AnswerPair("姓名", "张三"))))
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
        assertTrue(failure.message.contains("多个题目"))
    }

    @Test
    fun ambiguityFailsEvenWhenAnotherFieldMatched() {
        val q1 = SurveyQuestion(1, "姓名（本人）", QuestionType.TEXT, emptyList())
        val q2 = SurveyQuestion(2, "姓名（家长）", QuestionType.TEXT, emptyList())
        val q3 = SurveyQuestion(3, "电话", QuestionType.TEXT, emptyList())
        val failure = fail(
            WjxAnswerMatcher.match(model(q1, q2, q3), listOf(AnswerPair("3", "123"), AnswerPair("姓名", "张三"))),
        )
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
    }

    @Test
    fun twoFieldsPointingToSameQuestionStillFails() {
        val failure = fail(
            WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("1", "张三"), AnswerPair("姓名", "李四"))),
        )
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
        assertTrue(failure.message.contains("同一题"))
    }

    @Test
    fun singleValueNotInOptionsStillFails() {
        val failure = fail(WjxAnswerMatcher.match(model(singleQ), listOf(AnswerPair("2", "不确定"))))
        assertEquals(SubmitErrorCode.UNMATCHED, failure.code)
        assertTrue(failure.message.contains("不在选项中"))
    }

    @Test
    fun multiWithUnknownSegmentStillFails() {
        assertEquals(SubmitErrorCode.UNMATCHED, fail(WjxAnswerMatcher.match(model(multiQ), listOf(AnswerPair("3", "读书|爬山")))).code)
    }

    @Test
    fun textValueLongerThanLimitStillFails() {
        val failure = fail(WjxAnswerMatcher.match(model(textQ), listOf(AnswerPair("1", "x".repeat(3001)))))
        assertEquals(SubmitErrorCode.LIMIT, failure.code)
        assertTrue(failure.message.contains("3000"))
    }

    @Test
    fun matrixSliderOtherStillUnsupported() {
        val matrix = SurveyQuestion(1, "矩阵", QuestionType.MATRIX, emptyList())
        val slider = SurveyQuestion(2, "量表", QuestionType.SLIDER, emptyList())
        val other = SurveyQuestion(3, "商品", QuestionType.OTHER, emptyList())
        val m = model(matrix, slider, other)
        assertEquals(SubmitErrorCode.UNSUPPORTED, fail(WjxAnswerMatcher.match(m, listOf(AnswerPair("1", "x")))).code)
        assertEquals(SubmitErrorCode.UNSUPPORTED, fail(WjxAnswerMatcher.match(m, listOf(AnswerPair("2", "5")))).code)
        assertEquals(SubmitErrorCode.UNSUPPORTED, fail(WjxAnswerMatcher.match(m, listOf(AnswerPair("3", "x")))).code)
    }

    @Test
    fun successKeepsInputOrderOfPairs() {
        val result = ok(
            WjxAnswerMatcher.match(
                model(textQ, singleQ, multiQ),
                listOf(AnswerPair("3", "读书"), AnswerPair("1", "张三")),
            ),
        )
        assertEquals(listOf(3 to "1", 1 to "张三"), result.pairs)
    }

    // ------------------------------------------------- SubmitResult 透传口径

    @Test
    fun submitResultSkippedFieldsIsAdditiveWithEmptyDefault() {
        val base = SubmitResult(true, 200, "提交成功", null)
        assertTrue("默认必须是空列表", base.skippedFields.isEmpty())
        assertEquals(listOf("身份证"), base.copy(skippedFields = listOf("身份证")).skippedFields)
        assertEquals("skippedFields 不得混进 message", "提交成功", base.copy(skippedFields = listOf("身份证")).message)
    }
}
