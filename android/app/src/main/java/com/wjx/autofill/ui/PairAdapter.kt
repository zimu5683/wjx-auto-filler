package com.wjx.autofill.ui

import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.wjx.autofill.databinding.ItemAnswerPairBinding
import com.wjx.autofill.wjx.AnswerPair

/**
 * 左右双栏字段映射列表：**动态填充的 LinearLayout**（不再用 RecyclerView）。
 *
 * 为什么改：原实现把 RecyclerView 放进 ScrollView，父链全是 wrap_content 且没 setHasFixedSize，
 * 条目超过约 4 行后不会可靠重新测量/增长 → 多出来的行既不可见也不可滚（用户实测「只能看到 4 行」）。
 * **字段条数本身没有上限**（`concurrency` 是内容组并发数，与字段条数无关），
 * 所以这里直接往 LinearLayout 填充 item_answer_pair.xml，高度随内容自然增长，由外层 ScrollView 滚动。
 *
 * 公开方法与改造前保持一致（`setPairs`/`highlightUnmatched` 即原 `submit`/`highlightFields` 的新名字），
 * TextWatcher **每行只挂一次**（行视图随重建而重建，不会叠加）。
 */
class PairAdapter(
    private val container: LinearLayout,
    private val onChanged: (List<AnswerPair>) -> Unit,
) {

    private val pairs = mutableListOf<AnswerPair>()
    private val rows = mutableListOf<ItemAnswerPairBinding>()
    private val selected = mutableSetOf<Int>()
    private val highlighted = mutableSetOf<Int>()

    /** 绑定期间抑制 watcher/勾选回写，避免 setText 触发递归。 */
    private var binding = false

    /** 最近获得焦点的行（题目清单回填时优先填这一行）。 */
    var focusedIndex: Int = -1
        private set

    val itemCount: Int get() = pairs.size

    // ------------------------------------------------------------------ 外部操作

    fun setPairs(newPairs: List<AnswerPair>) {
        pairs.clear()
        pairs.addAll(newPairs)
        selected.clear()
        highlighted.clear()
        focusedIndex = -1
        rebuild()
    }

    fun currentPairs(): List<AnswerPair> = pairs.toList()

    fun addRow(field: String = "", value: String = "") {
        pairs += AnswerPair(field, value)
        rebuild()
        onChanged(currentPairs())
    }

    fun removeAt(index: Int) {
        if (index !in pairs.indices) return
        pairs.removeAt(index)
        selected.clear()
        highlighted.clear()
        rebuild()
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
        rebuild()
        onChanged(currentPairs())
        return count
    }

    fun clearAll() {
        pairs.clear()
        selected.clear()
        highlighted.clear()
        rebuild()
        onChanged(currentPairs())
    }

    fun selectedCount(): Int = selected.size

    /** 第一行左栏为空的行的下标；没有则返回 -1。 */
    fun firstBlankFieldIndex(): Int = pairs.indexOfFirst { it.field.isBlank() }

    /** 把某行左栏设为指定标识（题目清单点选回填）。 */
    fun setFieldAt(index: Int, field: String) {
        if (index !in pairs.indices) return
        pairs[index] = pairs[index].copy(field = field)
        bindRow(index)
        onChanged(currentPairs())
    }

    /** 高亮未匹配/被跳过字段所在行（明细必须在界面上可见，不静默丢弃）。 */
    fun highlightUnmatched(fields: Set<String>) {
        highlighted.clear()
        if (fields.isNotEmpty()) {
            pairs.forEachIndexed { index, pair ->
                if (fields.contains(pair.field.trim())) highlighted += index
            }
        }
        applyHighlight()
    }

    // ------------------------------------------------------------------ 内部

    private fun rebuild() {
        binding = true
        container.removeAllViews()
        rows.clear()
        val inflater = LayoutInflater.from(container.context)
        pairs.forEachIndexed { index, _ ->
            val row = ItemAnswerPairBinding.inflate(inflater, container, false)
            container.addView(row.root)
            rows += row
            attachListeners(index, row)
            bindRowInternal(index, row)
        }
        binding = false
        applyHighlight()
    }

    private fun attachListeners(index: Int, row: ItemAnswerPairBinding) {
        row.targetInput.addTextChangedListener(object : SimpleWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (binding || index !in pairs.indices) return
                pairs[index] = pairs[index].copy(field = s?.toString().orEmpty())
                onChanged(currentPairs())
            }
        })
        row.contentInput.addTextChangedListener(object : SimpleWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (binding || index !in pairs.indices) return
                pairs[index] = pairs[index].copy(value = s?.toString().orEmpty())
                onChanged(currentPairs())
            }
        })
        row.pairSelected.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (checked) selected.add(index) else selected.remove(index)
        }
        row.targetInput.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                focusedIndex = index
                // 聚焦到靠下的行时把它滚进可视区（外层 ScrollView）
                view.post {
                    view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false)
                }
            }
        }
        row.deleteRowButton.setOnClickListener { removeAt(index) }
    }

    private fun bindRow(index: Int) {
        val row = rows.getOrNull(index) ?: return
        binding = true
        bindRowInternal(index, row)
        binding = false
    }

    private fun bindRowInternal(index: Int, row: ItemAnswerPairBinding) {
        val pair = pairs.getOrElse(index) { AnswerPair("", "") }
        if (row.targetInput.text?.toString() != pair.field) {
            row.targetInput.setText(pair.field)
        }
        if (row.contentInput.text?.toString() != pair.value) {
            row.contentInput.setText(pair.value)
        }
        row.pairSelected.isChecked = selected.contains(index)
    }

    private fun applyHighlight() {
        rows.forEachIndexed { index, row ->
            row.root.setBackgroundColor(if (highlighted.contains(index)) 0x22DC2626 else 0x00000000)
        }
    }

    private abstract class SimpleWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    }
}
