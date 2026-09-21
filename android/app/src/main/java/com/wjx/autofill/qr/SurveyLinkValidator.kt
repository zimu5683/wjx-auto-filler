package com.wjx.autofill.qr

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** 链接校验结果。 */
sealed class LinkCheck {
    data class Ok(val url: String, val note: String = "") : LinkCheck()
    data class Bad(val reason: String) : LinkCheck()
}

/**
 * 问卷链接校验。
 *
 * 规则：https + host 属于 wjx.cn / wjx.top / sojump.com 的子域 + GET 200 且含问卷标记。
 *
 * 纯逻辑部分（[extractUrl] / [isAllowedHost] / [checkSyntax]）不 import android.*，
 * qa-build 可以直接单元测试；只有 [verifyReachable] 走网络。
 */
object SurveyLinkValidator {

    private val ALLOWED_HOSTS = listOf("wjx.cn", "wjx.top", "sojump.com")

    /** 问卷页特征串：真实样本页 https://www.wjx.cn/vm/Q0DQewW.aspx 实测包含这些标记。 */
    private val QUESTION_MARKERS = listOf("divQuestion", "processjq", "hfAnswerData", "joinnew")

    private val URL_PATTERN = Regex("""https?://[^\s"'<>()（）【】\[\]]+""", RegexOption.IGNORE_CASE)

    /** 从任意文本（含二维码原始内容）里抽出第一个 http/https 链接；抽不到返回 null。 */
    fun extractUrl(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val match = URL_PATTERN.find(text)?.value ?: return null
        return match.trimEnd('.', ',', ';', ')', '）', '。', '，')
    }

    /** host 是否等于允许域名或其后缀子域。 */
    fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.trim().lowercase()
        return ALLOWED_HOSTS.any { allowed ->
            normalized == allowed || normalized.endsWith(".$allowed")
        }
    }

    /** 离线校验：协议 + 域名。http 链接若是允许域名会自动升级为 https。 */
    fun checkSyntax(raw: String): LinkCheck {
        val url = extractUrl(raw) ?: return LinkCheck.Bad("没有识别到 http/https 链接")
        val uri = try {
            URI(url)
        } catch (_: Throwable) {
            return LinkCheck.Bad("链接格式不正确")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme != "http" && scheme != "https") {
            return LinkCheck.Bad("只支持 http/https 链接")
        }
        if (!isAllowedHost(host)) {
            return LinkCheck.Bad("只支持问卷星域名：wjx.cn / wjx.top / sojump.com")
        }
        if (scheme == "http") {
            // 用户可能粘贴 http 链接：允许域名下自动升级为 https（最终校验的仍是 https）。
            val upgraded = "https://" + url.substringAfter("//")
            return LinkCheck.Ok(upgraded, "已自动改用 https")
        }
        return LinkCheck.Ok(url)
    }

    /**
     * 联网校验：GET 200 且响应体含问卷标记。
     * 网络异常一律转成可读原因，不抛异常。
     */
    fun verifyReachable(url: String, timeoutMs: Int = 12_000): LinkCheck {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return LinkCheck.Bad("链接打不开（HTTP $code）")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(16 * 1024)
                while (builder.length < MAX_BODY_CHARS) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    builder.appendRange(buffer, 0, read)
                }
                builder.toString()
            }
            if (QUESTION_MARKERS.none { body.contains(it) }) {
                return LinkCheck.Bad("链接能打开，但不是问卷星问卷页面")
            }
            LinkCheck.Ok(url)
        } catch (_: java.net.UnknownHostException) {
            LinkCheck.Bad("网络不可用或域名解析失败")
        } catch (_: java.net.SocketTimeoutException) {
            LinkCheck.Bad("连接超时，请检查网络后重试")
        } catch (t: Throwable) {
            LinkCheck.Bad("链接校验失败：${t.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    /** 只读前 200K 字符判断标记，避免把整页 HTML 拉进内存。 */
    private const val MAX_BODY_CHARS = 200_000

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}
