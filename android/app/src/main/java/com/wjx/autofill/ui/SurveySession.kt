package com.wjx.autofill.ui

import com.wjx.autofill.wjx.SurveyModel

/**
 * 解析会话（cookie）能否用于**当前这次**验证码兜底注入。
 *
 * 为什么需要：验证码兜底会把「引擎会话 cookie」注入 WebView（契约 §13.3 方向①）。
 * 若拿的是**上一份问卷**的模型 cookie，就会把 A 问卷的会话带到 B 问卷 —— **串号**。
 * 触发场景：用户解析 A 成功 → 换链接解析 B 失败 → state.survey 被清空/或仍是 A 的模型。
 *
 * 规则：模型为 null、当前 URL 为空、或**模型 URL 与当前问卷 URL 不一致** → 一律返回空 Map
 * （宁可不注入，也不能注入错的会话）。
 *
 * 纯函数，qa-build 可直接 JVM 单测。
 */
fun surveyCookiesFor(model: SurveyModel?, currentUrl: String): Map<String, String> {
    if (model == null) return emptyMap()
    val target = currentUrl.trim()
    if (target.isEmpty()) return emptyMap()
    return if (model.url.trim() == target) model.cookies else emptyMap()
}
