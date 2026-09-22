package com.wjx.autofill.submit

/**
 * 人工验证（§13）收割到的会话材料。
 *
 * - [captchaVerifyParam]：阿里云 `captchaVerifyParam` 原文，**单次有效且短命**；
 *   收割后必须在 [CAPTCHA_TOKEN_TTL_MS] 内完成「重新 fetch → 带令牌 submit」。
 * - [sceneId]：`captchaSceneid` 原文（引擎自己会从页面解析 sceneId，UI 只负责透传，不校验）。
 * - [cookies]：WebView 会话 cookie 全量快照，交给引擎用**同一会话**重抓页面。
 */
data class CaptchaHarvest(
    val captchaVerifyParam: String,
    val sceneId: String,
    val cookies: Map<String, String>,
    val harvestedAtMillis: Long,
)

/** 令牌有效期（契约 §13.2 normative）：收割后 60 秒内必须完成提交。 */
const val CAPTCHA_TOKEN_TTL_MS = 60_000L

/*
 * 人工验证兜底次数（Lead 2026-09-22 裁定 A）：**每个需要兜底的组各有 1 次机会**，
 * 不设跨组上限；该组兜底后（成功/取消/超时/仍失败）即记为「已兜底」，按钮继续为
 * 其余未兜底组显示；结构性失败（无 WebView / 页面改版）把所有未兜底组一次性标记。
 * 实现见 MainActivity 的 captchaRetriedGroups 集合。
 */

/** Cookie 头解析（纯 Kotlin，可单测）。 */
object CookieHeader {

    /** `"a=b; c=d"` → `{a=b, c=d}`；忽略空段、无 `=` 段与空 name。 */
    fun parse(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        header.split(';').forEach { part ->
            val item = part.trim()
            if (item.isEmpty()) return@forEach
            val index = item.indexOf('=')
            if (index <= 0) return@forEach
            val name = item.substring(0, index).trim()
            val value = item.substring(index + 1).trim()
            if (name.isNotEmpty()) result[name] = value
        }
        return result
    }

    /** `{a=b}` → `"a=b"`（逐条写 CookieManager 用）。 */
    fun entries(cookies: Map<String, String>): List<String> =
        cookies.entries.map { "${it.key}=${it.value}" }

    /**
     * 取 URL 的 origin（`scheme://host[:port]/`），作为 cookie 注入/清理的基准。
     *
     * 契约 §13.3 的 origin 口径：必须与**问卷所在主机**一致 —— 若写死 `https://www.wjx.cn/`，
     * `v.wjx.cn` 的验证页收不到引擎会话 cookie（cookie 绑在 www 主机上），方向①失效。
     * 解析失败（空串 / 非法 URL）返回 [fallback]。
     */
    fun originOf(url: String, fallback: String = "https://www.wjx.cn/"): String {
        return try {
            val uri = java.net.URI(url.trim())
            val host = uri.host.orEmpty()
            if (host.isEmpty()) {
                fallback
            } else {
                // 主机名大小写不敏感，统一小写，避免与 CookieManager 的主机归一化不一致。
                val scheme = uri.scheme?.lowercase() ?: "https"
                val port = if (uri.port > 0) ":" + uri.port else ""
                "$scheme://${host.lowercase()}$port/"
            }
        } catch (_: Throwable) {
            fallback
        }
    }
}
