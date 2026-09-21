package com.wjx.autofill.update

/**
 * 语义化版本比较。
 *
 * 刻意 **不依赖任何 android.***：qa-build 可以直接在 JVM 单元测试里跑它，
 * 也保证「版本比较」这条业务逻辑不受 Android 运行时影响。
 *
 * 行为与 yikou-light-food 的 AppUpdater.compareVersions 完全一致：
 * 任意一侧不是 x.y.z（三段纯数字）形式时返回 -1（不可比）。
 */
object VersionCompare {

    /** left > right 返回 1，left < right 返回 -1，相等返回 0；不可比返回 -1。 */
    fun compare(left: String, right: String): Int {
        val lhs = parse(left) ?: return -1
        val rhs = parse(right) ?: return -1
        for (index in 0 until maxOf(lhs.size, rhs.size)) {
            val a = lhs.getOrElse(index) { 0 }
            val b = rhs.getOrElse(index) { 0 }
            if (a != b) return if (a > b) 1 else -1
        }
        return 0
    }

    /** 候选版本是否严格新于当前版本；不可比时按「不是更新」处理。 */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** 解析 "v1.2.3" / "1.2.3" 为 [1, 2, 3]；其它形式返回 null。 */
    fun parse(value: String): List<Int>? {
        val parts = value.trim().removePrefix("v").split(".")
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return if (numbers.size == 3) numbers else null
    }
}
