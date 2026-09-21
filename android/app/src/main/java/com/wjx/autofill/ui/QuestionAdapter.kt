package com.wjx.autofill.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wjx.autofill.databinding.ItemQuestionBinding
import com.wjx.autofill.wjx.QuestionType
import com.wjx.autofill.wjx.SurveyQuestion

/** 题目清单：点选某题把它的题号回填到左栏（省去手打）。 */
class QuestionAdapter(
    private val onPick: (SurveyQuestion) -> Unit,
) : RecyclerView.Adapter<QuestionAdapter.Holder>() {

    private val questions = mutableListOf<SurveyQuestion>()

    class Holder(val binding: ItemQuestionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemQuestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = questions.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val question = questions[position]
        holder.binding.questionIndex.text = question.topic.toString()
        holder.binding.questionTitle.text = question.title.ifBlank { "（无题干）" }
        holder.binding.questionMeta.text = metaOf(question)
        holder.binding.root.setOnClickListener { onPick(question) }
    }

    fun submit(items: List<SurveyQuestion>) {
        questions.clear()
        questions.addAll(items)
        notifyDataSetChanged()
    }

    private fun metaOf(question: SurveyQuestion): String {
        val type = when (question.type) {
            QuestionType.SINGLE -> "单选"
            QuestionType.MULTI -> "多选"
            QuestionType.TEXT -> "填空"
            QuestionType.DROPDOWN -> "下拉"
            QuestionType.MATRIX -> "矩阵（暂不支持自动填写）"
            QuestionType.SLIDER -> "评分（暂不支持自动填写）"
            QuestionType.OTHER -> "其它（暂不支持自动填写）"
        }
        val options = if (question.options.isEmpty()) {
            ""
        } else {
            "　选项：" + question.options.joinToString(" / ") { it.label.ifBlank { it.value } }
                .take(80)
        }
        return "题号 ${question.topic}　$type$options"
    }
}
