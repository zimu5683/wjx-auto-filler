package com.wjx.autofill.wjx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlin.coroutines.cancellation.CancellationException

/**
 * 抓取并解析问卷页。**纯 Kotlin/JVM**：不 import android.*，不依赖 OkHttp/jsoup，
 * 可以直接在 JVM 单元测试里跑（qa-build 的硬依赖）。
 */
interface WjxSurveyClient {
    /**
     * GET 问卷页并解析为 [SurveyModel]。
     *
     * [cookies] 为可选的回带 cookie（一般传空 Map）；内部始终使用**本次调用独享**的 CookieJar，
     * 因此并发抓取互不干扰。失败一律走 [Result.failure]，携带 [WjxException]。
     */
    suspend fun fetch(url: String, cookies: Map<String, String> = emptyMap()): Result<SurveyModel>
}

/** [WjxSurveyClient] 的 HttpURLConnection 实现。 */
class HttpWjxSurveyClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : WjxSurveyClient {

    override suspend fun fetch(url: String, cookies: Map<String, String>): Result<SurveyModel> =
        withContext(Dispatchers.IO) {
            try {
                val target = url.trim()
                val shortId = WjxUrls.shortIdOf(target)
                    ?: return@withContext Result.failure(
                        WjxException(SubmitErrorCode.URL, WjxText.URL_INVALID)
                    )
                val jar = CookieJar().apply { load(cookies) }
                val conn = WjxHttp.open(target, "GET", connectTimeoutMs, readTimeoutMs, jar, GET_HEADERS)
                val (status, body) = WjxHttp.readText(conn, jar)
                if (status !in 200..299) {
                    return@withContext Result.failure(
                        WjxException(SubmitErrorCode.HTTP, "问卷服务器返回异常（HTTP " + status + "）")
                    )
                }
                val finalUrl = conn.url?.toString().orEmpty()
                if (!WjxUrls.isHttpsWjx(finalUrl)) {
                    return@withContext Result.failure(
                        WjxException(SubmitErrorCode.URL, WjxText.URL_INVALID)
                    )
                }
                val parsed = WjxPageParser.parse(target, shortId, body, jar.snapshot())
                // sceneId 补齐（Lead 2026-09-22 裁定）：页面没内联时，仅当 useAliVerify=1 才按需抓 wjx_captch.js。
                val (resolvedSceneId, sceneIdSource) = WjxSceneId.resolve(parsed.sceneId, parsed.useAliVerify) {
                    fetchCaptchaJsText(connectTimeoutMs, readTimeoutMs)
                }
                Result.success(
                    parsed.copy(sceneId = resolvedSceneId, sceneIdSource = sceneIdSource)
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (wx: WjxException) {
                Result.failure(wx)
            } catch (t: Throwable) {
                Result.failure(WjxException(SubmitErrorCode.NETWORK, WjxText.NETWORK, t))
            }
        }

    /**
     * 抓阿里云验证码常量所在脚本。**只在 useAliVerify=1 且页面没内联时调用**（不给正常路径增加请求）。
     *
     * 用独立 CookieJar：不把 www.wjx.cn 的会话 cookie 带到 CDN 主机。失败一律返回 null，不抛异常。
     */
    private fun fetchCaptchaJsText(connectMs: Int, readMs: Int): String? = try {
        val cdnJar = CookieJar()
        val conn = WjxHttp.open(
            WjxSceneId.CAPTCHA_JS_URL,
            "GET",
            connectMs,
            readMs,
            cdnJar,
            mapOf("User-Agent" to WjxHttp.DESKTOP_UA, "Accept" to "*/*"),
        )
        val (status, text) = WjxHttp.readText(conn)
        if (status in 200..299) text else null
    } catch (_: Throwable) {
        null
    }

    private companion object {
        val GET_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to WjxHttp.DESKTOP_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
    }
}

/** 契约文案集中处（UI 只读 errorCode，文案在这里逐字实现）。 */
internal object WjxText {
    const val URL_INVALID = "链接无效：请填写 https://wjx.cn/vm/xxxx.aspx 形式的问卷链接（支持任意子域，如 v.wjx.cn）"
    const val PARSE = "问卷页面解析失败，可能是问卷已关闭或页面改版"
    const val PAGED = "该问卷为分页/逐题模式，暂不支持自动填写"
    const val NETWORK = "网络连接失败，请检查网络后重试"
    const val CAPTCHA = "该问卷开启了安全校验（阿里云验证码），纯接口无法提交"
}

/**
 * 阿里云验证码 SceneId 解析（Lead 2026-09-22 裁定：引擎自己补齐，保持冻结签名不变）。
 *
 * 顺序：页面内联 → （仅当 useAliVerify=1）抓 wjx_captch.js 提取常量 → 仍失败则 null（照常提交，不本地拦截）。
 * [resolve] 是纯函数（jsLoader 可注入），单测可覆盖「页面已有 / JS 提取成功 / JS 抓取失败」三个分支，无需网络。
 */
object WjxSceneId {

    /** wjx_captch.js 里 `var captchaSceneid="q0hcfsca"`（全局，对所有问卷相同）。 */
    const val CAPTCHA_JS_URL = "https://image.wjx.cn/joinnew/js/wjx_captch.js"

    private val SCENE_RE = Regex("""captchaSceneid\s*=\s*["']([^"']+)["']""")

    /** 从任意文本（页面 HTML 或 wjx_captch.js）提取 captchaSceneid 常量；没有则 null。 */
    fun extract(text: String): String? =
        SCENE_RE.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * @param inline 页面内联的 captchaSceneid（可为 null）
     * @param useAliVerify 是否开启安全校验；false 时**不调用** [jsLoader]（避免多余网络请求）
     * @param jsLoader 抓取 wjx_captch.js 文本；返回 null / 抛异常都视为失败
     * @return (sceneId, 来源)；两者同为 null 表示没有拿到
     */
    fun resolve(
        inline: String?,
        useAliVerify: Boolean,
        jsLoader: () -> String?,
    ): Pair<String?, SceneIdSource?> {
        if (!inline.isNullOrBlank()) return inline to SceneIdSource.PAGE
        if (!useAliVerify) return null to null
        val text = try {
            jsLoader()
        } catch (_: Throwable) {
            null
        }
        val fromJs = text?.let { extract(it) }
        return if (fromJs.isNullOrBlank()) null to null else fromJs to SceneIdSource.CAPTCHA_JS
    }
}

/** 问卷星 URL 规则（docs/API-CONTRACT.md §5.1）。 */
internal object WjxUrls {

    private val URL_RE = Regex("""^https://([A-Za-z0-9-]+\.)*wjx\.cn/(vm|jq|m)/([A-Za-z0-9]{4,32})\.aspx""", RegexOption.IGNORE_CASE)

    /** 从链接前缀捕获 shortId；不匹配返回 null（http、其它域名、其它 path 形态一律拒绝）。 */
    fun shortIdOf(url: String): String? = URL_RE.find(url.trim())?.groupValues?.get(3)

    /** host 是否等于 wjx.cn 或其后缀子域。 */
    fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return h == "wjx.cn" || h.endsWith(".wjx.cn")
    }

    /** 必须是 https 且 host 属于 wjx.cn。 */
    fun isHttpsWjx(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val host = try {
            URL(url).host
        } catch (_: Throwable) {
            null
        }
        return isAllowedHost(host)
    }
}

/** URL 编码（必须用 String 重载：Charset 重载要 API 33，minSdk 24 不可用）。 */
internal fun urlEncode(value: String): String = try {
    URLEncoder.encode(value, "UTF-8")
} catch (_: UnsupportedEncodingException) {
    value
}

/**
 * URL **query** 用编码：把 form-urlencoded 的空格（+）还原成 %20，对齐页面 JS 的 encodeURIComponent。
 *
 * 说明：两者服务端解码等价（ASP.NET 查询串把 + 也解成空格），这里选 %20 只是为了与浏览器
 * DevTools 抓到的请求**逐字节可比**（T3 实测参考请求用的就是 encodeURIComponent）。
 * POST body 仍用 [urlEncode]（jQuery $.param 把空格编成 +）。
 */
internal fun urlEncodeQuery(value: String): String = urlEncode(value).replace("+", "%20")

/** 极简 cookie jar：name→value，忽略 domain/path/expires（同一站点内使用）。 */
internal class CookieJar {

    private val store = LinkedHashMap<String, String>()

    fun load(map: Map<String, String>) {
        for ((k, v) in map) if (k.isNotBlank() && v.isNotBlank()) store[k] = v
    }

    fun absorb(conn: HttpURLConnection) {
        var i = 0
        while (i < 200) {
            val key = conn.getHeaderFieldKey(i)
            val value = conn.getHeaderField(i)
            if (key == null && value == null) break
            if (key != null && key.equals("Set-Cookie", ignoreCase = true) && !value.isNullOrBlank()) {
                val first = value.substringBefore(';')
                val eq = first.indexOf('=')
                if (eq > 0) {
                    val name = first.substring(0, eq).trim()
                    val cookieValue = first.substring(eq + 1).trim()
                    if (name.isNotEmpty() && cookieValue.isNotEmpty()) store[name] = cookieValue
                }
            }
            i++
        }
    }

    fun header(): String? =
        if (store.isEmpty()) null else store.entries.joinToString("; ") { it.key + "=" + it.value }

    fun snapshot(): Map<String, String> = LinkedHashMap(store)
}

/** HttpURLConnection 薄封装（连接不复用：每次提交用新的连接）。 */
internal object WjxHttp {

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun open(
        url: String,
        method: String,
        connectMs: Int,
        readMs: Int,
        jar: CookieJar,
        headers: Map<String, String>,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        conn.useCaches = false
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        jar.header()?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }

    /** 读响应码与正文（按 Content-Type 的 charset 解码，缺省 UTF-8；不用平台默认编码）。 */
    fun readText(conn: HttpURLConnection, jar: CookieJar? = null): Pair<Int, String> {
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        jar?.absorb(conn)
        return status to String(bytes, charsetOf(conn.contentType))
    }

    private fun charsetOf(contentType: String?): Charset {
        if (contentType != null) {
            val idx = contentType.indexOf("charset=", ignoreCase = true)
            if (idx >= 0) {
                val name = contentType.substring(idx + 8)
                    .substringBefore(';')
                    .trim()
                    .trim('"', '\'')
                if (name.isNotEmpty()) {
                    try {
                        return Charset.forName(name)
                    } catch (_: Throwable) {
                        // 非法 charset 名：退回 UTF-8
                    }
                }
            }
        }
        return Charsets.UTF_8
    }
}

/**
 * 问卷页解析器（docs/API-CONTRACT.md §6）。纯字符串扫描，无 jsoup。
 *
 * 题目列表来自**服务端渲染的 HTML**（T3 实测：不需要额外 XHR）：
 * 题目是 `#divQuestion` 内同时带 `topic` 属性且 class 含 field 的 div。
 */
object WjxPageParser {

    /**
     * 单测/复用入口：直接从 URL + HTML 解析（shortId 由 URL 推导）。
     * 解析失败抛 [WjxException]（E_URL / E_PARSE / E_PAGED）。
     */
    fun parse(
        url: String,
        html: String,
        cookies: Map<String, String> = emptyMap(),
        timeAdapter: SurveyTimeAdapter = WjxTimeAdapter,
    ): SurveyModel {
        val target = url.trim()
        val shortId = WjxUrls.shortIdOf(target)
            ?: throw WjxException(SubmitErrorCode.URL, WjxText.URL_INVALID)
        return parse(target, shortId, html, cookies, timeAdapter)
    }

    fun parse(
        url: String,
        shortId: String,
        html: String,
        cookies: Map<String, String>,
        timeAdapter: SurveyTimeAdapter = WjxTimeAdapter,
    ): SurveyModel {
        val title = firstGroup(TITLE_RE, html)
            ?.let { collapse(stripTags(unescapeHtml(it))) }
            ?.takeIf { it.isNotEmpty() }
            ?: "未命名问卷"

        val jqnonce = firstGroup(JQNONCE_RE, html)?.trim().orEmpty()
        if (jqnonce.isEmpty()) throw WjxException(SubmitErrorCode.PARSE, WjxText.PARSE)

        // 开放时间判定（T16）：未开放的问卷**仍带 jqnonce/starttime**，只是不下发题目，
        // 所以必须在这里短路，否则会被误报成 E_PARSE「可能已关闭或页面改版」。
        val nowMillis = System.currentTimeMillis()
        val openTime = timeAdapter.parse(html, nowMillis)
        if (!WjxTimeAdapter.isOpen(openTime, nowMillis)) {
            throw WjxException(SubmitErrorCode.NOT_OPEN, WjxTimeAdapter.notOpenMessage(openTime))
        }

        if (isPaged(html)) throw WjxException(SubmitErrorCode.PAGED, WjxText.PAGED)

        val ktimes = firstGroup(KTIMES_RE, html)?.trim()?.toIntOrNull() ?: 0
        val startTime = inputValueById(html, "starttime").orEmpty()
        val useAliVerify = (firstGroup(USE_ALI_RE, html)?.trim()?.toIntOrNull() ?: 0) != 0
        val needLoad = (firstGroup(NEED_LOAD_RE, html)?.trim()?.toIntOrNull() ?: 0) != 0
        val captchaType = firstGroup(CAPTCHA_TYPE_RE, html)?.trim()?.toIntOrNull()
            ?: if (useAliVerify || needLoad) 2 else null
        val sceneId = firstGroup(CAPTCHA_SCENE_RE, html)?.trim()?.takeIf { it.isNotEmpty() }

        val submitUrl = formAction(html)?.trim()?.takeIf { it.isNotEmpty() }
            ?: ("https://www.wjx.cn/joinnew/processjq.ashx?shortid=" + shortId)

        val questions = parseQuestions(html).sortedBy { it.topic }
        if (questions.isEmpty()) throw WjxException(SubmitErrorCode.PARSE, WjxText.PARSE)

        return SurveyModel(
            url = url,
            shortId = shortId,
            title = title,
            questions = questions,
            submitUrl = submitUrl,
            jqnonce = jqnonce,
            ktimes = ktimes,
            startTime = startTime,
            captchaType = captchaType,
            cookies = cookies,
            useAliVerify = useAliVerify,
            sceneId = sceneId,
            sceneIdSource = if (sceneId != null) SceneIdSource.PAGE else null,
            openAtMillis = (openTime as? OpenTime.Known)?.openAtMillis,
            needsCaptchaHint = useAliVerify,
        )
    }

    // ---------- 头部字段 ----------

    private val TITLE_RE = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val JQNONCE_RE = Regex("""var\s+jqnonce\s*=\s*["']([^"']+)["']""")
    private val KTIMES_RE = Regex("""var\s+ktimes\s*=\s*(\d+)""")
    private val USE_ALI_RE = Regex("""var\s+useAliVerify\s*=\s*(\d+)""")
    private val NEED_LOAD_RE = Regex("""var\s+needLoadAliVerify\s*=\s*(\d+)""")
    private val CAPTCHA_TYPE_RE = Regex("""captchaType\s*=\s*['"]?(\d+)['"]?""")
    private val CAPTCHA_SCENE_RE = Regex("""captchaSceneid\s*=\s*["']([^"']+)["']""")
    private val FORM_TAG_RE = Regex("""<form\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val INPUT_TAG_RE = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SELECT_RE = Regex("""<select\b[^>]*>[\s\S]*?</select>""", RegexOption.IGNORE_CASE)
    private val OPTION_RE = Regex("""<option\b([^>]*)>([\s\S]*?)(?:</option>|$)""", RegexOption.IGNORE_CASE)
    private val DIV_OPEN_RE = Regex("""<div\b""", RegexOption.IGNORE_CASE)
    private val ANY_TAG_RE = Regex("""<[^>]*>""")
    private val BR_RE = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val WS_RE = Regex("""[ \t\n\r]+""")
    private val PAGED_PG_RE = Regex("""<fieldset\b[^>]*\bpg\s*=\s*['"]?(\d+)""", RegexOption.IGNORE_CASE)
    private val PAGED_PART_RE = Regex("""partPages\s*=\s*["']([^"']*)["']""")
    private val PAGED_ONE_RE = Regex("""IsOneQuestionPerPage\s*=\s*(\d+)""")

    private fun firstGroup(re: Regex, s: String): String? = re.find(s)?.groupValues?.get(1)

    private fun formAction(html: String): String? {
        for (m in FORM_TAG_RE.findAll(html)) {
            val tag = m.value
            if (attrOf(tag, "id").equals("form1", ignoreCase = true)) return attrOf(tag, "action")
        }
        return null
    }

    private fun inputValueById(html: String, id: String): String? {
        for (m in INPUT_TAG_RE.findAll(html)) {
            val tag = m.value
            if (attrOf(tag, "id").equals(id, ignoreCase = true)) return attrOf(tag, "value").orEmpty()
        }
        return null
    }

    private fun isPaged(html: String): Boolean {
        if (firstGroup(PAGED_ONE_RE, html)?.trim() == "1") return true
        if (!firstGroup(PAGED_PART_RE, html).isNullOrBlank()) return true
        return PAGED_PG_RE.findAll(html).any { (it.groupValues[1].toIntOrNull() ?: 0) > 1 }
    }

    // ---------- 题目 ----------

    private fun parseQuestions(html: String): List<SurveyQuestion> {
        val scope = divQuestionBlock(html) ?: html
        val out = ArrayList<SurveyQuestion>()
        var i = 0
        while (i < scope.length) {
            val open = scope.indexOf("<div", i, ignoreCase = true)
            if (open < 0) break
            if (!isDivTagStart(scope, open)) {
                i = open + 4
                continue
            }
            val tagEnd = scope.indexOf('>', open)
            if (tagEnd < 0) break
            val tag = scope.substring(open, tagEnd + 1)
            val topic = attrOf(tag, "topic")?.trim()?.toIntOrNull()
            val cls = attrOf(tag, "class").orEmpty()
            if (topic != null && topic >= 1 && cls.contains("field")) {
                val end = endOfDiv(scope, open)
                out.add(parseQuestion(scope.substring(open, end), topic))
                i = end
            } else {
                i = tagEnd + 1
            }
        }
        return out
    }

    private fun parseQuestion(block: String, topic: Int): SurveyQuestion {
        val headTag = block.substring(0, (block.indexOf('>') + 1).coerceAtLeast(1))
        val typeCode = attrOf(headTag, "type")?.trim()?.toIntOrNull()
        val title = innerTextByClass(block, "topichtml")
            ?: innerTextByClass(block, "field-label")
                ?.replace(Regex("""^[\s*]*\d+\s*[.、]?"""), "")
                ?.trim()
                .orEmpty()
        val type = detectType(block, typeCode)
        val options = if (
            type == QuestionType.SINGLE || type == QuestionType.MULTI || type == QuestionType.DROPDOWN
        ) {
            optionsOf(block)
        } else {
            emptyList()
        }
        return SurveyQuestion(topic, title, type, options)
    }

    /**
     * 题型判定（§6.2）。DOM 证据优先，type 码兜底。
     *
     * 顺序说明：矩阵题（type=6/7）的单元格里也有 radio，若按「radio 优先」会误判成 SINGLE，
     * 从而漏掉「MATRIX 不支持」的显式报错。因此 matrix / slider 证据先判。
     */
    private fun detectType(block: String, typeCode: Int?): QuestionType {
        if (typeCode == 6 || typeCode == 7) return QuestionType.MATRIX
        if (typeCode == 8) return QuestionType.SLIDER
        if (Regex("""<table\b""", RegexOption.IGNORE_CASE).containsMatchIn(block) ||
            block.contains("matrix", ignoreCase = true) ||
            block.contains("divMatrix", ignoreCase = true)
        ) {
            return QuestionType.MATRIX
        }
        if (Regex("""<input\b[^>]*type\s*=\s*["']?range""", RegexOption.IGNORE_CASE).containsMatchIn(block) ||
            block.contains("slider", ignoreCase = true)
        ) {
            return QuestionType.SLIDER
        }
        if (Regex("""<input\b[^>]*type\s*=\s*["']?radio""", RegexOption.IGNORE_CASE).containsMatchIn(block)) {
            return QuestionType.SINGLE
        }
        if (Regex("""<input\b[^>]*type\s*=\s*["']?checkbox""", RegexOption.IGNORE_CASE).containsMatchIn(block)) {
            return QuestionType.MULTI
        }
        if (Regex("""<select\b""", RegexOption.IGNORE_CASE).containsMatchIn(block) &&
            Regex("""<option\b""", RegexOption.IGNORE_CASE).containsMatchIn(block)
        ) {
            return QuestionType.DROPDOWN
        }
        if (Regex("""<textarea\b""", RegexOption.IGNORE_CASE).containsMatchIn(block)) return QuestionType.TEXT
        if (Regex("""<input\b""", RegexOption.IGNORE_CASE).containsMatchIn(block)) return QuestionType.TEXT
        return when (typeCode) {
            1, 2 -> QuestionType.TEXT
            3 -> QuestionType.SINGLE
            4 -> QuestionType.MULTI
            6, 7 -> QuestionType.MATRIX
            8 -> QuestionType.SLIDER
            10 -> QuestionType.DROPDOWN
            else -> QuestionType.OTHER
        }
    }

    private fun optionsOf(block: String): List<Option> {
        val out = ArrayList<Option>()
        for (m in INPUT_TAG_RE.findAll(block)) {
            val tag = m.value
            val kind = attrOf(tag, "type")?.lowercase()
            if (kind != "radio" && kind != "checkbox") continue
            val value = attrOf(tag, "value").orEmpty()
            val label = labelFor(block, m.range.first, tag).ifBlank { value }
            out.add(Option(value, label))
        }
        if (out.isEmpty()) {
            // 下拉题（§6.2）：select + option，value = option 的 value 属性原文，label = 去标签文本（空则回退 value）
            for (selectBlock in SELECT_RE.findAll(block)) {
                for (optionMatch in OPTION_RE.findAll(selectBlock.value)) {
                    val attrs = optionMatch.groupValues[1]
                    val text = collapse(stripTags(unescapeHtml(optionMatch.groupValues[2])))
                    val value = attrOf("<option " + attrs + ">", "value").orEmpty().ifBlank { text }
                    if (value.isBlank() && text.isBlank()) continue
                    out.add(Option(value, text.ifBlank { value }))
                }
            }
        }
        return out
    }

    /** 选项 label 提取（§6.3）：最近 ui-radio/ui-checkbox 祖先文本 → dit 属性 → value。 */
    private fun labelFor(block: String, inputStart: Int, inputTag: String): String {
        var searchFrom = inputStart
        repeat(4) {
            val divStart = block.lastIndexOf("<div", searchFrom, ignoreCase = true)
            if (divStart < 0) return@repeat
            val tagEnd = block.indexOf('>', divStart)
            if (tagEnd < 0 || tagEnd > inputStart) return@repeat
            val tag = block.substring(divStart, tagEnd + 1)
            val cls = attrOf(tag, "class").orEmpty()
            if (cls.contains("ui-radio") || cls.contains("ui-checkbox")) {
                val end = endOfDiv(block, divStart)
                val inner = block.substring(tagEnd + 1, end)
                val text = collapse(stripTags(unescapeHtml(inner.replace(inputTag, " "))))
                if (text.isNotEmpty()) return text
                val dit = attrOf(tag, "dit")
                if (!dit.isNullOrBlank()) {
                    val decoded = try {
                        java.net.URLDecoder.decode(dit, "UTF-8")
                    } catch (_: Throwable) {
                        dit
                    }
                    if (decoded.isNotBlank()) return decoded.trim()
                }
                return ""
            }
            searchFrom = divStart - 1
        }
        return ""
    }

    // ---------- DOM 工具 ----------

    private fun divQuestionBlock(html: String): String? {
        val m = Regex("""id\s*=\s*["']divQuestion["']""", RegexOption.IGNORE_CASE).find(html) ?: return null
        var tagStart = m.range.first
        while (tagStart > 0 && html[tagStart] != '<') tagStart--
        if (html[tagStart] != '<' || !html.startsWith("<div", tagStart, ignoreCase = true)) return null
        return html.substring(tagStart, endOfDiv(html, tagStart))
    }

    private fun isDivTagStart(s: String, idx: Int): Boolean {
        if (!s.startsWith("<div", idx, ignoreCase = true)) return false
        val next = idx + 4
        if (next >= s.length) return true
        val c = s[next]
        return c == '>' || c == '/' || c.isWhitespace()
    }

    /** 从 <div 起点做深度配平，返回匹配 </div> 之后的下标（找不到则返回长度）。 */
    private fun endOfDiv(s: String, tagStart: Int): Int {
        var depth = 0
        var i = tagStart
        while (i < s.length) {
            val open = s.indexOf("<div", i, ignoreCase = true)
            val close = s.indexOf("</div", i, ignoreCase = true)
            if (close < 0) return s.length
            if (open in 0 until close && isDivTagStart(s, open)) {
                depth++
                i = open + 4
            } else {
                depth--
                val gt = s.indexOf('>', close + 5)
                i = if (gt < 0) s.length else gt + 1
                if (depth <= 0) return i
            }
        }
        return s.length
    }

    /** 找第一个 class 含 [className] 的元素，返回其内部文本（去标签 + 实体反转义 + 折叠空白）。 */
    private fun innerTextByClass(block: String, className: String): String? {
        val re = Regex(
            """<(div|span|p|td|label)\b[^>]*class\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in re.findAll(block)) {
            val cls = m.groupValues[2].trim('"', '\'')
            if (!cls.contains(className)) continue
            val tag = m.groupValues[1].lowercase()
            val start = m.range.first
            val contentStart = m.range.last + 1
            val end = if (tag == "div") {
                endOfDiv(block, start)
            } else {
                val c = block.indexOf("</" + tag, contentStart, ignoreCase = true)
                if (c < 0) block.length else (block.indexOf('>', c) + 1).coerceAtLeast(contentStart)
            }
            if (end <= contentStart) continue
            val text = collapse(stripTags(unescapeHtml(block.substring(contentStart, end))))
            if (text.isNotEmpty()) return text
        }
        return null
    }

    /** 解析开始标签的属性；支持 name="v" / name='v' / name=v，属性顺序任意。 */
    private fun attrOf(tag: String, name: String): String? {
        val re = Regex(
            """(?:^|\s)""" + Regex.escape(name) + """\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""",
            RegexOption.IGNORE_CASE,
        )
        val m = re.find(tag) ?: return null
        return m.groupValues[1].trim('"', '\'')
    }

    private fun stripTags(s: String): String =
        ANY_TAG_RE.replace(BR_RE.replace(s, "\n"), " ")

    private fun collapse(s: String): String = WS_RE.replace(s, " ").trim()

    /** HTML 实体反转义：命名实体 + 十进制/十六进制数字实体。 */
    private fun unescapeHtml(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) {
                sb.append(c)
                i++
                continue
            }
            val ent = s.substring(i + 1, semi)
            val replacement: String? = when {
                ent == "amp" -> "&"
                ent == "lt" -> "<"
                ent == "gt" -> ">"
                ent == "quot" -> "\""
                ent == "apos" || ent == "#39" -> "'"
                ent == "nbsp" -> " "
                ent.startsWith("#x") || ent.startsWith("#X") ->
                    ent.substring(2).toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
                ent.startsWith("#") ->
                    ent.substring(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
                else -> null
            }
            if (replacement == null) {
                sb.append(c)
                i++
            } else {
                sb.append(replacement)
                i = semi + 1
            }
        }
        return sb.toString()
    }
}
