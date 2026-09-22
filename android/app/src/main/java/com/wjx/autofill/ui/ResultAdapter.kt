package com.wjx.autofill.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.wjx.autofill.R
import com.wjx.autofill.databinding.ItemSubmitResultBinding
import com.wjx.autofill.submit.GroupOutcome
import com.wjx.autofill.wjx.SubmitErrorCode

/**
 * 结果反馈区：逐条显示成功 / 失败 / 未匹配字段。
 *
 * 分支一律读 `SubmitResult.errorCode`，**禁止用文案做字符串匹配**（契约 §8.3）。
 */
class SubmitResultAdapter : RecyclerView.Adapter<SubmitResultAdapter.Holder>() {

    private val outcomes = mutableListOf<GroupOutcome>()

    class Holder(val binding: ItemSubmitResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSubmitResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = outcomes.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val outcome = outcomes[position]
        val context = holder.binding.root.context
        val result = outcome.result

        val (label, background) = when {
            result.ok -> context.getString(R.string.result_success) to R.drawable.bg_badge_success
            result.errorCode == SubmitErrorCode.UNMATCHED ->
                context.getString(R.string.result_badge_unmatched) to R.drawable.bg_badge_error
            result.errorCode == SubmitErrorCode.CAPTCHA ->
                context.getString(R.string.result_badge_captcha) to R.drawable.bg_badge_error
            result.errorCode == SubmitErrorCode.UNSUPPORTED ->
                context.getString(R.string.result_badge_unsupported) to R.drawable.bg_badge_error
            result.errorCode == SubmitErrorCode.EMPTY ->
                context.getString(R.string.result_badge_empty) to R.drawable.bg_badge_pending
            else -> context.getString(R.string.result_failed) to R.drawable.bg_badge_error
        }

        holder.binding.resultBadge.text = label
        holder.binding.resultBadge.setBackgroundResource(background)
        holder.binding.resultGroup.text =
            outcome.groupName.ifBlank { context.getString(R.string.group_default_name) }
        holder.binding.resultDuration.text =
            if (result.httpStatus > 0) "HTTP ${result.httpStatus}" else ""
        holder.binding.resultMessage.text = buildMessage(context, result.message, result.errorCode)
        holder.binding.resultMessage.setTextColor(
            ContextCompat.getColor(context, if (result.ok) R.color.text_secondary else R.color.error),
        )

        // 用户要求：被跳过的未匹配字段**必须可见**（不是静默丢弃）。
        // 只在成功项展示；失败项的原因已经在 message 里。
        val skipped = result.skippedFields
        if (result.ok && skipped.isNotEmpty()) {
            holder.binding.resultSkipped.visibility = View.VISIBLE
            holder.binding.resultSkipped.text = context.getString(
                R.string.result_skipped_fields,
                skipped.size,
                skipped.joinToString("、"),
            )
        } else {
            holder.binding.resultSkipped.visibility = View.GONE
        }
    }

    private fun buildMessage(context: Context, message: String, code: String?): String {
        val hint = when (code) {
            SubmitErrorCode.UNMATCHED -> context.getString(R.string.result_hint_unmatched)
            SubmitErrorCode.CAPTCHA -> context.getString(R.string.result_hint_captcha)
            SubmitErrorCode.NETWORK, SubmitErrorCode.HTTP -> context.getString(R.string.result_hint_network)
            SubmitErrorCode.UNSUPPORTED -> context.getString(R.string.result_hint_unsupported)
            SubmitErrorCode.REJECTED -> context.getString(R.string.result_hint_rejected)
            SubmitErrorCode.URL -> context.getString(R.string.result_hint_url)
            else -> ""
        }
        return if (hint.isBlank()) message else "$message\n$hint"
    }

    fun submit(items: List<GroupOutcome>) {
        outcomes.clear()
        outcomes.addAll(items)
        notifyDataSetChanged()
    }

    fun clear() {
        outcomes.clear()
        notifyDataSetChanged()
    }
}
