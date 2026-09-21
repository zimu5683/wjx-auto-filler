package com.wjx.autofill.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wjx.autofill.databinding.ItemAnswerPairBinding
import com.wjx.autofill.wjx.AnswerPair

/**
 * 左右双栏字段映射列表：左栏 = 目标字段（题号 / 题干关键词），右栏 = 填充内容。
 *
 * 列表是当前组 pairs 的唯一编辑入口，每次改动通过 [onChanged] 回写给 EditorState。
 * TextWatcher 在 onCreateViewHolder 里**只挂一次**（绑定阶段再挂会随复用不断叠加）。
 * 数据量小（个位数到几十行），用 notifyDataSetChanged 换取位置与勾选状态的一致性。
 */
class PairAdapter(
    private val onChanged: (List<AnswerPair>) -> Unit,
) : RecyclerView.Adapter<PairAdapter.Holder>() {

    private val pairs = mutableListOf<AnswerPair>()
    private val selected = mutableSetOf<Int>()
    private val highlighted = mutableSetOf<Int>()

    /** 最近获得焦点的行（题目清单回填时优先填这一行）。 */
    var focusedIndex: Int = -1
        private set

    class Holder(val binding: ItemAnswerPairBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val holder = Holder(ItemAnswerPairBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        holder.binding.targetInput.addTextChangedListener(object : SimpleWatcher() {
            override fun afterTextChanged(s: Editable?) {
                val index = holder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION || index !in pairs.indices) return
                pairs[index] = pairs[index].copy(field = s?.toString().orEmpty())
                onChanged(currentPairs())
            }
        })
        holder.binding.contentInput.addTextChangedListener(object : SimpleWatcher() {
            override fun afterTextChanged(s: Editable?) {
                val index = holder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION || index !in pairs.indices) return
                pairs[index] = pairs[index].copy(value = s?.toString().orEmpty())
                onChanged(currentPairs())
            }
        })
        return holder
    }

    override fun getItemCount(): Int = pairs.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val pair = pairs.getOrElse(position) { AnswerPair("", "") }
        val binding = holder.binding

        if (binding.targetInput.text?.toString() != pair.field) {
            binding.targetInput.setText(pair.field)
        }
        if (binding.contentInput.text?.toString() != pair.value) {
            binding.contentInput.setText(pair.value)
        }

        binding.pairSelected.setOnCheckedChangeListener(null)
        binding.pairSelected.isChecked = selected.contains(position)
        binding.pairSelected.setOnCheckedChangeListener { _, checked ->
            val index = holder.bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
            if (checked) selected.add(index) else selected.remove(index)
        }

        binding.targetInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                focusedIndex = holder.bindingAdapterPosition
            }
        }

        binding.deleteRowButton.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) removeAt(index)
        }

        binding.root.setBackgroundColor(
            if (highlighted.contains(position)) 0x22DC2626 else 0x00000000,
        )
    }

    // ------------------------------------------------------------------ 外部操作

    fun submit(newPairs: List<AnswerPair>) {
        pairs.clear()
        pairs.addAll(newPairs)
        selected.clear()
        highlighted.clear()
        focusedIndex = -1
        notifyDataSetChanged()
    }

    fun currentPairs(): List<AnswerPair> = pairs.toList()

    fun addRow(field: String = "", value: String = "") {
        pairs += AnswerPair(field, value)
        notifyItemInserted(pairs.size - 1)
        onChanged(currentPairs())
    }

    fun removeAt(index: Int) {
        if (index !in pairs.indices) return
        pairs.removeAt(index)
        selected.clear()
        highlighted.clear()
        notifyDataSetChanged()
        onChanged(currentPairs())
    }

    /** 删除勾选行，返回删除数量。 */
    fun deleteSelected(): Int {
        if (selected.isEmpty()) return 0
        val count = selected.size
        selected.sortedDescending().forEach { index ->
            if (index in pairs.indices) pairs.removeAt(index)
        }
        selected.clear()
        highlighted.clear()
        notifyDataSetChanged()
        onChanged(currentPairs())
        return count
    }

    fun clearAll() {
        pairs.clear()
        selected.clear()
        highlighted.clear()
        notifyDataSetChanged()
        onChanged(currentPairs())
    }

    fun selectedCount(): Int = selected.size

    /** 第一行左栏为空的行的下标；没有则返回 -1。 */
    fun firstBlankFieldIndex(): Int = pairs.indexOfFirst { it.field.isBlank() }

    /** 把某行左栏设为指定标识（题目清单点选回填）。 */
    fun setFieldAt(index: Int, field: String) {
        if (index !in pairs.indices) return
        pairs[index] = pairs[index].copy(field = field)
        notifyItemChanged(index)
        onChanged(currentPairs())
    }

    /** 高亮未匹配字段所在行（契约 §8.3：E_UNMATCHED 的明细必须在界面上可见）。 */
    fun highlightFields(fields: Set<String>) {
        highlighted.clear()
        if (fields.isNotEmpty()) {
            pairs.forEachIndexed { index, pair ->
                if (fields.contains(pair.field.trim())) highlighted += index
            }
        }
        notifyDataSetChanged()
    }

    private abstract class SimpleWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    }
}
