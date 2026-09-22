package com.wjx.autofill.wjx

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 问卷开放时间（T16）。纯 Kotlin/JVM，可直接被 JVM 单测覆盖。
 *
 * 设计原则：**解析失败绝不影响手动提交** —— 判不出来就是 [Unknown]，一律按"可以尝试提交"处理。
 *
 * 签名由 Lead 2026-09-22 冻结（additive，不改既有契约）。
 */
sealed interface OpenTime {
    /** 解析到开放时间（epoch millis，UTC）；`openAtMillis <= nowMillis` 表示已开放。 */
    data class Known(val openAtMillis: Long) : OpenTime

    /** 解析不到：必须返回本值，**绝不影响手动提交**。 */
    data object Unknown : OpenTime
}

/**
 * 平台可插拔的「开放时间」适配器（用户决策：只做问卷星，但做成适配器，将来加平台只增适配器，不动核心）。
 */
interface SurveyTimeAdapter {
    /**
     * @param html 问卷页 HTML
     * @param nowMillis 当前时间（注入以便单测）
     */
    fun parse(html: String, nowMillis: Long): OpenTime
}

/**
 * 问卷星实现（T16，实测修正版）：
 *
 * **解析优先级**（详见 [parse] 的 KDoc 与 evidence/14-time-fix.md）：
 * 1. **未开放文案** `此问卷将于2026-09-23 09:33（北京时间）开放` —— 只在未开放页出现，最权威且自带本地化时间；
 * 2. **`left` + `nowTime`** —— 未开放页的倒计时秒数 + 服务端当前时间（与文案逐秒一致）；
 * 3. **`qBeginDate`/`BeginDate` 兜底** —— ⚠️ **它是问卷开始/创建时间，不是开放时间**，只能兜底；
 *    实测未开放问卷 tfGAWU4 的它比真实开放时间早 23.98 小时，**单独用它会把未开放判成已开放**；
 * 4. 都解析不到 → [OpenTime.Unknown]（不拦截，绝不影响手动提交）。
 *
 * 注意：未开放页面**仍带 jqnonce/starttime**，只是题目不下发（fieldset/topic 计数 0），
 * 所以必须靠时间判定，否则会被误报成 E_PARSE「可能已关闭或页面改版」。
 */
object WjxTimeAdapter : SurveyTimeAdapter {

    /** `var qBeginDate="1790040856347"`（首选；精确匹配变量名，避免误取其它 BeginDate）。 */
    private val Q_BEGIN_DATE_RE = Regex("""qBeginDate\s*=\s*["']?(\d{10,14})["']?""")

    /** 兜底：任何 `BeginDate=<数字>`（页面换变量名时不至于失效）。 */
    private val BEGIN_DATE_RE = Regex("""BeginDate\s*=\s*["']?(\d{10,14})["']?""")

    /** 未开放标记文案：`此问卷将于2026-09-23 09:33（北京时间）开放`（半角/全角冒号都容忍）。 */
    private val TEXT_RE = Regex("""此问卷将于\s*(\d{4})-(\d{2})-(\d{2})\s*(\d{1,2})[:：](\d{2})""")

    /** 未开放页的倒计时秒数：`<div id='divstarttime' left='85054'>`。 */
    private val LEFT_RE = Regex("""divstarttime['"][^>]*?\bleft\s*=\s*['"]?(\d+)""")

    /** 服务端渲染的当前时间：`nowTime = "2026-09-22 09:55:25"`（北京时间）。 */
    private val NOW_TIME_RE = Regex("""nowTime\s*=\s*["']([^"']+)["']""")

    /** 合理区间守卫：早于 2000-01-01 或晚于 now+20 年的时间戳视为脏数据 → Unknown。 */
    private const val MIN_PLAUSIBLE_MILLIS = 946_684_800_000L
    private const val TWENTY_YEARS_MILLIS = 20L * 365L * 24L * 3600L * 1000L

    /**
     * 解析优先级（**已按 T16 实测修正 Lead 的初版规则**，见 evidence/14-time-fix.md）：
     *
     * 1. **未开放文案**（`此问卷将于 YYYY-MM-DD HH:mm（北京时间）开放`）—— 只在未开放页出现，最权威且自带本地化时间；
     * 2. **`left` + `nowTime`** —— 未开放页的倒计时秒数 + 服务端当前时间，二者相加即开放时刻（与文案逐秒一致）；
     * 3. **`qBeginDate`/`BeginDate` 时间戳** —— ⚠️ 实测**它不是"定时开放时间"**：
     *    未开放的 tfGAWU4 `qBeginDate=1790040856347` = 2026-09-22 09:34(+08)，而 `nowTime=09:55:25`、`left=85054`
     *    → 真实开放时间 = 09:55:25 + 85054s = **2026-09-23 09:33**（与文案一致）。qBeginDate 只是问卷开始/创建时间，
     *    **单独用它会把未开放判成已开放**。因此它只作为最后兜底（通常用于已开放问卷，值为过去时间）。
     * 4. 都解析不到 → [OpenTime.Unknown]（不拦截）。
     */
    override fun parse(html: String, nowMillis: Long): OpenTime {
        if (html.isEmpty()) return OpenTime.Unknown

        // 1) 未开放文案
        TEXT_RE.find(html)?.let { m ->
            val text = m.groupValues[1] + "-" + m.groupValues[2] + "-" + m.groupValues[3] + " " +
                m.groupValues[4].padStart(2, '0') + ":" + m.groupValues[5]
            parseBeijingTime(text)?.let { millis ->
                if (isPlausible(millis, nowMillis)) return OpenTime.Known(millis)
            }
        }

        // 2) left + nowTime（没有文案时的精确兜底）
        val left = LEFT_RE.find(html)?.groupValues?.get(1)?.toLongOrNull()
        val serverNowText = NOW_TIME_RE.find(html)?.groupValues?.get(1)
        if (left != null && left > 0L && !serverNowText.isNullOrBlank()) {
            parseBeijingTime(serverNowText)?.let { base ->
                val millis = base + left * 1000L
                if (isPlausible(millis, nowMillis)) return OpenTime.Known(millis)
            }
        }

        // 3) 时间戳兜底（已开放问卷通常在此）
        for (re in listOf(Q_BEGIN_DATE_RE, BEGIN_DATE_RE)) {
            val digits = re.find(html)?.groupValues?.get(1) ?: continue
            val raw = digits.toLongOrNull() ?: continue
            if (raw <= 0L) continue
            // 位数语义（architect T16）：<=11 位当"秒"，>=12 位当"毫秒"
            val value = if (digits.length <= 11) raw * 1000L else raw
            if (isPlausible(value, nowMillis)) return OpenTime.Known(value)
        }
        return OpenTime.Unknown
    }

    private fun isPlausible(millis: Long, nowMillis: Long): Boolean =
        millis >= MIN_PLAUSIBLE_MILLIS && millis <= nowMillis + TWENTY_YEARS_MILLIS

    /** 未开放时的人类文案（含本地化的北京时间开放时间）。 */
    fun notOpenMessage(openTime: OpenTime): String {
        val at = (openTime as? OpenTime.Known)?.openAtMillis
        return if (at == null) {
            "该问卷暂未开放，请稍后再试"
        } else {
            "该问卷将于 " + formatBeijingTime(at) + " 开放"
        }
    }

    /** 判断是否已开放（[OpenTime.Unknown] 视为已开放，不拦截）。 */
    fun isOpen(openTime: OpenTime, nowMillis: Long): Boolean =
        openTime !is OpenTime.Known || nowMillis >= openTime.openAtMillis

    /** 距离开放还有多久（负值 = 已开放；Unknown → null）。 */
    fun millisUntilOpen(openTime: OpenTime, nowMillis: Long): Long? =
        (openTime as? OpenTime.Known)?.let { it.openAtMillis - nowMillis }

    /** epoch millis → 北京时间 `yyyy-MM-dd HH:mm`。 */
    fun formatBeijingTime(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        fmt.timeZone = TimeZone.getTimeZone("GMT+08:00")
        return fmt.format(Date(millis))
    }

    /** `yyyy-MM-dd HH:mm[:ss]`（北京时间）→ epoch millis；必须整串消费，否则 null。 */
    internal fun parseBeijingTime(text: String): Long? {
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
            val fmt = SimpleDateFormat(pattern, Locale.CHINA)
            fmt.timeZone = TimeZone.getTimeZone("GMT+08:00")
            fmt.isLenient = false
            val pos = ParsePosition(0)
            val date = fmt.parse(text, pos) ?: continue
            if (pos.index == text.length) return date.time
        }
        return null
    }

    /** 测试辅助：由北京时间文本直接构造 millis。 */
    fun beijingMillisOf(text: String): Long? = parseBeijingTime(text)

    /** 测试辅助：把 millis 按北京时间展示。 */
    fun beijingTextOf(millis: Long): String = formatBeijingTime(millis)
}
