package com.wjx.autofill.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wjx.autofill.R
import com.wjx.autofill.databinding.ActivityCaptchaBinding
import com.wjx.autofill.submit.CaptchaHarvest
import com.wjx.autofill.submit.CookieHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject

// ---------------------------------------------------------------------------
// 契约 §13.7：注入脚本常量，**逐字使用，禁止改写/拼接**。
// ---------------------------------------------------------------------------

/** ① 唤起验证码：只调用页面自身的函数，绝不触碰提交入口。 */
internal const val JS_RAISE_CAPTCHA =
    "(function(){try{if(typeof loadCaptchShow==='function'){loadCaptchShow();return 'RAISED';}" +
        "if(window.loadCaptchShow){window.loadCaptchShow();return 'RAISED';}return 'NO_FN';}" +
        "catch(e){return 'ERR:'+e;}})()"

/** ② 收割令牌：只读两个全局变量，返回值是 JSON 字符串。 */
internal const val JS_HARVEST =
    "(function(){return JSON.stringify({p:(window.captchaVerifyParam||'')," +
        "s:(window.captchaSceneid||'')});})()"

/**
 * 人工验证兜底页（契约 §13）。
 *
 * 边界（写死，qa-build 会静态检查）：
 *  - 只加载**真实问卷 URL**，只注入 §13.7 的两个固定常量脚本；
 *  - **绝不填表、绝不点击提交入口**（#ctlNext / #SubmitBtnGroup / #divSubmit / 页面 ajax 函数）；
 *  - **绝不把 answers / AnswerPair / submitdata 以任何形式传给 WebView**；
 *  - 不注册任何 `@JavascriptInterface`；
 *  - 数据提交永远由引擎（HttpURLConnection）完成。
 *
 * 会话一致性（§13.3）：打开前把引擎 cookie 逐条写进 WebView 的 CookieManager；
 * 收割后把 WebView 全量 cookie 交回引擎；无论成功/失败/取消都清理本次会话 cookie
 * （逐条置空，**不得** removeAllCookies —— 全局单例会误伤其他会话）。
 */
class CaptchaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_captcha_url"
        const val EXTRA_COOKIE_PAIRS = "extra_captcha_cookie_pairs"
        const val EXTRA_TOKEN = "extra_captcha_token"
        const val EXTRA_SCENE_ID = "extra_captcha_scene_id"
        const val EXTRA_COOKIES = "extra_captcha_cookies"
        const val EXTRA_HARVESTED_AT = "extra_captcha_harvested_at"
        const val EXTRA_ERROR = "extra_captcha_error"
        const val EXTRA_FATAL = "extra_captcha_fatal"
        const val EXTRA_CANCELLED = "extra_captcha_cancelled"

        /** 验证环节是否**真正发起过**（验证码已成功唤起）。UI 据此决定是否消耗该组机会。 */
        const val EXTRA_STARTED = "extra_captcha_started"

        private const val BASE_URL = "https://www.wjx.cn/"
        private const val POLL_MS = 500L

        /** 契约 §13.2：等待验证的总时长 180 秒。 */
        const val TIMEOUT_MS = 180_000L

        fun intent(context: Context, url: String, cookies: Map<String, String>): Intent =
            Intent(context, CaptchaActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putStringArrayListExtra(EXTRA_COOKIE_PAIRS, ArrayList(CookieHeader.entries(cookies)))

        /** 从 Activity 结果里还原 [CaptchaHarvest]；失败/取消返回 null（原因在 EXTRA_ERROR）。 */
        fun harvestFrom(data: Intent?): CaptchaHarvest? {
            if (data == null) return null
            val token = data.getStringExtra(EXTRA_TOKEN).orEmpty()
            if (token.isBlank()) return null
            val entries = data.getStringArrayListExtra(EXTRA_COOKIES).orEmpty()
            return CaptchaHarvest(
                captchaVerifyParam = token,
                sceneId = data.getStringExtra(EXTRA_SCENE_ID).orEmpty(),
                cookies = CookieHeader.parse(entries.joinToString("; ")),
                harvestedAtMillis = data.getLongExtra(EXTRA_HARVESTED_AT, 0L),
            )
        }
    }

    private lateinit var binding: ActivityCaptchaBinding
    private var finished = false
    private var harvestStarted = false

    /** 只有验证码成功唤起后才置 true —— 「没真正发起」的情形保留该组兜底机会。 */
    private var verificationStarted = false
    private var surveyUrl: String = ""
    private var injectedCookies: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surveyUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        injectedCookies = CookieHeader.parse(
            intent.getStringArrayListExtra(EXTRA_COOKIE_PAIRS).orEmpty().joinToString("; "),
        )

        binding = try {
            ActivityCaptchaBinding.inflate(layoutInflater)
        } catch (_: Throwable) {
            // §13.5：设备无 WebView / 内核不可用 → E_CAPTCHA 终态，并隐藏兜底按钮。
            finishFailure(getString(R.string.captcha_no_webview), fatal = true)
            return
        }
        setContentView(binding.root)

        binding.cancelButton.setOnClickListener {
            finishFailure(getString(R.string.captcha_cancelled), fatal = false, cancelled = true)
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishFailure(getString(R.string.captcha_cancelled), fatal = false, cancelled = true)
                }
            },
        )
        binding.captchaStatus.text = getString(R.string.captcha_status_loading)

        if (surveyUrl.isBlank()) {
            finishFailure(getString(R.string.captcha_structure_changed), fatal = true)
            return
        }
        configureWebView()
        // §13.3 方向①：先注入引擎会话 cookie，再加载真实问卷页。
        injectEngineCookies(injectedCookies)
        binding.captchaWebView.loadUrl(surveyUrl)
    }

    override fun onDestroy() {
        super.onDestroy()
        clearSessionCookies()
        binding.captchaWebView.destroy()
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webView = binding.captchaWebView
        val settings = webView.settings
        // §13.8 安全配置表
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // 只放行 https + host ∈ wjx.cn（含子域）；其他一律拦截，不跳外部浏览器。
                val target = request.url
                val host = target.host.orEmpty().lowercase()
                val allowed = target.scheme == "https" &&
                    (host == "wjx.cn" || host.endsWith(".wjx.cn"))
                return !allowed
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                harvestStarted = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (finished) return
                binding.captchaStatus.text = getString(R.string.captcha_status_waiting)
                raiseCaptcha()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                finishFailure(getString(R.string.captcha_structure_changed), fatal = true)
            }
        }
    }

    /** §13.2 第 6 步：注入 JS_RAISE_CAPTCHA，按返回值分流。 */
    private fun raiseCaptcha() {
        lifecycleScope.launch {
            when (val result = evaluate(JS_RAISE_CAPTCHA)) {
                "RAISED" -> startHarvesting()
                "NO_FN" -> finishFailure(
                    getString(R.string.captcha_structure_changed),
                    fatal = true,
                )
                else -> finishFailure(
                    getString(R.string.captcha_raise_failed, result),
                    fatal = true,
                )
            }
        }
    }

    /** §13.2 第 8 步：每 500ms 轮询 JS_HARVEST，180s 超时。 */
    private fun startHarvesting() {
        if (harvestStarted) return
        harvestStarted = true
        verificationStarted = true
        binding.captchaStatus.text = getString(R.string.captcha_status_waiting)
        lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (!finished && System.currentTimeMillis() < deadline) {
                val raw = evaluate(JS_HARVEST)
                val parsed = parseHarvest(raw)
                if (parsed != null) {
                    finishSuccess(parsed.first, parsed.second)
                    return@launch
                }
                delay(POLL_MS)
            }
            if (!finished) {
                // 180s 未收割到令牌 → 由 UI 侧按「每组 1 次」语义处理。
                finishFailure(getString(R.string.captcha_no_result), fatal = false)
            }
        }
    }

    /**
     * 唯一的 `evaluateJavascript` 调用点：只有 [JS_RAISE_CAPTCHA] 与 [JS_HARVEST]
     * 两个常量会作为入参（契约 §13.4：不得拼接变量）。
     */
    private suspend fun evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
        binding.captchaWebView.evaluateJavascript(script) { raw ->
            if (continuation.isActive) continuation.resume(decodeJsString(raw))
        }
    }

    /** evaluateJavascript 回传的是 JSON 编码值（字符串带引号 + 转义），这里还原成原文。 */
    private fun decodeJsString(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.length < 2 || !text.startsWith("\"") || !text.endsWith("\"")) return text
        val builder = StringBuilder()
        var index = 1
        val last = text.length - 1
        while (index < last) {
            val ch = text[index]
            if (ch != '\\' || index + 1 >= last) {
                builder.append(ch)
                index++
                continue
            }
            when (val next = text[index + 1]) {
                '"' -> { builder.append('"'); index += 2 }
                '\\' -> { builder.append('\\'); index += 2 }
                '/' -> { builder.append('/'); index += 2 }
                'n' -> { builder.append('\n'); index += 2 }
                'r' -> { builder.append('\r'); index += 2 }
                't' -> { builder.append('\t'); index += 2 }
                'b' -> { builder.append('\b'); index += 2 }
                'f' -> { builder.append('\u000C'); index += 2 }
                'u' -> {
                    val hex = if (index + 6 <= last) text.substring(index + 2, index + 6) else null
                    val code = hex?.toIntOrNull(16)
                    if (code != null) {
                        builder.append(code.toChar())
                        index += 6
                    } else {
                        builder.append(next)
                        index += 2
                    }
                }
                else -> { builder.append(next); index += 2 }
            }
        }
        return builder.toString()
    }

    /** JS_HARVEST 的返回：`{"p":"<token>","s":"<sceneId>"}`；token 为空返回 null。 */
    private fun parseHarvest(json: String): Pair<String, String>? {
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            val token = obj.optString("p").trim()
            if (token.isEmpty()) null else token to obj.optString("s").trim()
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ Cookie

    private fun injectEngineCookies(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        try {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            // §13.3 方向①：必须逐条 setCookie，不要手工拼 Cookie 头塞进 loadUrl。
            CookieHeader.entries(cookies).forEach { pair -> manager.setCookie(BASE_URL, pair) }
            manager.flush()
        } catch (_: Throwable) {
            // 注入失败不致命：验证页自己也会建立会话。
        }
    }

    /** §13.3 方向④：逐条置空（不得 removeAllCookies）。 */
    private fun clearSessionCookies() {
        try {
            val manager = CookieManager.getInstance()
            val names = LinkedHashSet<String>()
            names.addAll(injectedCookies.keys)
            names.addAll(CookieHeader.parse(manager.getCookie(BASE_URL)).keys)
            names.forEach { name -> manager.setCookie(BASE_URL, "$name=; Max-Age=0") }
            manager.flush()
        } catch (_: Throwable) {
            // 清理失败不阻断退出流程。
        }
    }

    // ------------------------------------------------------------------ 结束

    private fun finishSuccess(token: String, sceneId: String) {
        if (finished) return
        finished = true
        binding.captchaStatus.text = getString(R.string.captcha_status_done)
        // §13.3 方向②：读回 WebView 全量会话 cookie。
        val cookieHeader = try {
            CookieManager.getInstance().getCookie(surveyUrl).orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val pairs = ArrayList(CookieHeader.entries(CookieHeader.parse(cookieHeader)))
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_SCENE_ID, sceneId)
                .putStringArrayListExtra(EXTRA_COOKIES, pairs)
                .putExtra(EXTRA_HARVESTED_AT, System.currentTimeMillis())
                .putExtra(EXTRA_STARTED, true),
        )
        finish()
    }

    /**
     * [fatal] = true 表示结构性失败（§13.5 要求隐藏兜底按钮）。
     * [cancelled] = true 表示用户主动取消 —— UI 侧据此**不消耗**该组的兜底机会。
     */
    private fun finishFailure(reason: String, fatal: Boolean, cancelled: Boolean = false) {
        if (finished) return
        finished = true
        setResult(
            RESULT_CANCELED,
            Intent()
                .putExtra(EXTRA_ERROR, reason)
                .putExtra(EXTRA_FATAL, fatal)
                .putExtra(EXTRA_CANCELLED, cancelled)
                .putExtra(EXTRA_STARTED, verificationStarted),
        )
        finish()
    }
}
