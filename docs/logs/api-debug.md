# api-debug 节点日志（T3 / T3.5 / T4）

> 全部结论均附原始证据路径；证据目录：`tools/wjx-probe/evidence/`

## 2026-09-22 · T3 接口实测 go/no-go（★最高风险）

**目标**：判定纯 HTTP 能否提交样本问卷 https://www.wjx.cn/vm/Q0DQewW.aspx 。

**动作与命令**
1. 写 `tools/wjx-probe/lib/http.mjs`（Node 26，自带 cookie jar + 原始请求/响应捕获，无第三方依赖）。
2. `node stage1-fetch.mjs` → GET 样本页：**HTTP 200 / 89233B / 413ms**，title=测试，cookie: acw_tc、.ASPXANONYMOUS、jac295759055、SERVERID。
   证据：`evidence/01-page.html`、`01-page.headers.txt`、`01-page.request.txt`、`01-cookies.json`、`01-meta.json`
3. `node stage2-fetch-assets.mjs` → 抓 4 个 JS（jqmobo2.js 274KB / wjx_captch.js 4.5KB / matchawardinfmobilenew.js / hintinfo.js）。
   证据：`evidence/02-*.js`、`02-assets.json`
4. 反混淆 jqmobo2.js，确认（全部为**代码事实**，非猜测）：
   - 提交 URL 由 `$("#form1").attr("action")` + `&starttime=` + `&cst=` + `&source=` + `&ktimes=` + `&capt=` + `&t=` + `&jqnonce=` + `&jqsign=` 拼成；
   - body `{submitdata, captchaVerifyParam, sceneId}`，jQuery `$.ajax POST`（Content-Type form-urlencoded、X-Requested-With: XMLHttpRequest）；
   - `dataenc(e)`：`t=ktimes%10; t==0→1; out[i]=charCodeAt(i)^t`；
   - `spChars=["$","}","^","|","!","<"]`、`spToChars=["ξ","｝","ˆ","¦","！","＜"]`；
   - submitdata 拼接：`topic + "$" + value`，多项用 `"}"` 连接（`groupAnswer` 内）；
   - 响应处理 `afterSubmit`：`e.split("〒")` 首段 `10`=成功、`7`=弹阿里云验证码、`22`=`submit_need_validate2`、`11` 也走成功日志分支；
   - 题目列表来自**服务端渲染 HTML**（`#divQuestion` 内 `div[topic][type][req]`），**不需要额外 XHR**。
5. `node stage3-submit.mjs` → 1 次真实提交（与真实浏览器逐字节对齐：参数顺序、UA、Referer/Origin、X-Requested-With、body 的 captchaVerifyParam 为空串 + sceneId=q0hcfsca；提交前等 15s；ktimes=4）。

**结果（决定性）**
- `HTTP/1.1 200 OK`，正文 43 字节：**`7〒需要安全校验，请重新提交！`**
- 判定：**纯接口 NO-GO** —— 服务端业务码 7 = 要求阿里云安全校验；对应页面 JS 的 `7==a → isCaptchaValid=false → loadCaptchShow()`。
- 关键认识：页面加载时 `isCaptchaValid` 被置 true（`NeedVerifyCode` 未定义），所以**真实浏览器第一次提交同样会拿到 code 7**，这不是"我们被识别为机器人"，而是该问卷对所有客户端生效。
- 证据：`evidence/03-submit-request.txt`（完整请求行/头/体）、`03-submit-response.txt`（HTTP 原文）、`03-result.json`、`03-run.log`、`03-cookies-after.json`

**产出**：`stage1-fetch.mjs` / `stage2-fetch-assets.mjs` / `stage3-submit.mjs` / `lib/http.mjs`；已 send_message 给 lead（go/no-go 决策点）。

## 2026-09-22 · T3.5 captchaType 分布抽样（只读）

**动作**：`node t35-captcha-scan.mjs` → `node t35b-captcha-scan.mjs`（后者从 `wjx.cn/newsurveys.aspx` 抓公开问卷链接；全部**只读 GET**，从未调用提交接口）。

**结果（6 个问卷）**

| 问卷 | useAliVerify | captchaType | needLoadAliVerify | 题数 / 题型 |
|---|---|---|---|---|
| Q0DQewW（样本，实测被拦） | **1** | '2' | 1 | 3 / 填空 |
| hPyt0iq | 0 | '2' | 1 | 37 / 1,3,4 |
| PuE9RFq | 0 | '2' | 1 | 18 / 2,3,4 |
| rRESgvn | 0 | '2' | 1 | 22 / 3,4 |
| OPi1TlY | 0 | '2' | 1 | 160 / 2,3,4,5 |
| mpPKpP7 | 0 | '2' | 1 | 29 / 1,3 |

**结论**：`captchaType='2'` 是**页面模板常量**（6/6 都有），不是开关；真正的开关是 **`useAliVerify`**。
JS 证据：wjx_captch.js `if(!h.useAliVerify || isCaptchaValid) return !0;`；jqmobo2.js 提交门 `window.useAliVerify && !isCaptchaValid ? loadCaptchShow() : 进入提交函数`。
→ 该发现推翻了"用 captchaType 做拦截门"的初版裁决（会把 100% 问卷判死），Lead 已采纳改判为 useAliVerify。

**证据**：`evidence/t35-scan.log`、`evidence/t35b-scan.log`、`evidence/t35b/t35b-summary.json`、`evidence/t35b/*.html`、`evidence/t35-README.md`（只读声明）。

## 2026-09-22 · T4 引擎 Kotlin 实现

**产出（唯一写作用域 android/app/src/main/java/com/wjx/autofill/wjx/）**
- `SurveyModel.kt`：QuestionType / Option / SurveyQuestion / SurveyModel（additive：`useAliVerify: Boolean = false`、`sceneId: String? = null`）/ AnswerPair / SubmitResult(ok, httpStatus, message, raw, errorCode=null)
- `WjxErrors.kt`：WjxException + SubmitErrorCode（无 format()/of()）
- `WjxSubmitCodec.kt`：escape / jqSign / encodeSubmitData（纯函数）
- `WjxSurveyClient.kt`：WjxSurveyClient + HttpWjxSurveyClient + WjxPageParser + CookieJar + WjxHttp + WjxUrls
- `WjxSubmitter.kt`：WjxSubmitter + HttpWjxSubmitter + WjxResponseClassifier + WjxAnswerMatcher + MatchOutcome

**硬约束自检**：无 `import android.*`、无 org.json、无 OkHttp（grep 0 命中）；仅 `java.net.HttpURLConnection` + `kotlinx.coroutines` + `java.util.regex`；每次提交独立 CookieJar；不自动重试；除 CancellationException 外不抛异常。

**验证（不占用 Gradle，避免与 qa-build 抢构建）**
- 离线编译：`kotlin-compiler-embeddable 2.1.20 + kotlin-stdlib 2.1.20 + kotlinx-coroutines-core-jvm 1.9.0 + trove4j + annotations` → **KOTLINC_EXIT=0**
- JVM 冒烟：`tools/wjx-probe/SmokeTest.java`（Java 调 Kotlin 编译产物）→ **57 PASS / 0 FAIL**
  - 契约 §3.4 全部向量（escape 4 条、jqSign 4 条、encodeSubmitData 5 条）
  - T3 真实请求对齐：`jqSign("2bc94024-…", 4) == "6fg=0460)e724)0b<4)=`3f)10574e3gff27"`（与 03-result.json 逐字节一致）
  - 分类器：真实响应 `7〒需要安全校验，请重新提交！`→E_CAPTCHA、`10〒`→成功、`5〒…`→E_REJECTED、aliyunwaf/空/html/500/11/22
  - 解析 fixture：样本 3 题 / rRESgvn 22 题纯选择 / hPyt0iq 37 题混合 / 选项 value+label
  - 匹配器：R1/R2/R3、歧义、同题冲突、E_LIMIT、MULTI 的 `|` 拼接
- 证据：`evidence/04-engine-smoke.log`

**fixtures**：`tools/wjx-probe/fixtures/`（Q0DQewW / rRESgvn / hPyt0iq / PuE9RFq + README），全部只读抓取、**从未提交**。

## 2026-09-22 · T4 复审修复（qa-build 报偏差）+ T10 dry-run 工具

**qa-build 报的真实偏差**：DROPDOWN 题拿不到 options（`parseQuestion` 只在 SINGLE/MULTI 时提取，且 `optionsOf` 只看 radio/checkbox）。
- 修复：options 提取条件加 DROPDOWN；`optionsOf` 补 `<select>…</select>` 内 `<option value="x">文本</option>` 解析（value 缺失回退文本、label 空回退 value）。
- 回归用例：合成 HTML → DROPDOWN / 2 options / value=1,2 / label=A,B；匹配器用 label "B" → option.value "2"。

**新增公开纯函数接缝（qa-build §13.10 断言用，签名已冻结）**
```kotlin
object WjxSubmitRequest {
    fun buildSubmitUrl(m: SurveyModel): String?          // null = 非 https/wjx.cn → E_URL
    fun buildSubmitBody(m: SurveyModel, pairs: List<Pair<Int, String>>, captchaToken: String?): String
}
```
`HttpWjxSubmitter.submit()` 改为调用它们，行为不变。

**编码 fidelity**：URL query 用 `urlEncodeQuery`（空格 → `%20`，对齐 encodeURIComponent）；POST body 用 `urlEncode`（空格 → `+`，对齐 jQuery `$.param`）。两者服务端解码等价。

**T10 dry-run 工具（lead 派活）**
- `tools/wjx-probe/dry-run/DryRun.kt`：JVM 入口，`runBlocking` 调**真实引擎**（HttpWjxSurveyClient.fetch + WjxSubmitRequest）。
- `tools/wjx-probe/dry-run/run.sh`：离线 kotlinc 编译引擎源码 + CLI 再运行，**不占 Gradle 槽**。
- `tools/wjx-probe/dry-run/README.md`：用法与用途。
- 默认 dry-run **只 GET 绝不 POST**；`--submit` 必须同时带 `--confirm-own-survey`，否则拒绝（只允许提交自己的问卷）。
- 输出：题目清单、useAliVerify/captchaType/sceneId、jqnonce/ktimes/startTime、cookies、**将要 POST 的 URL 与 body 原文 + 解码可读形式**、字段匹配结果、提交门判定、耗时。
- 样例：`evidence/05-dry-run-sample.txt`。

**sceneId 核实**（evidence/05-sceneid-check.txt）：样本页与 6 个公开问卷页 HTML 内 `captchaSceneid` 出现 **0 次**；常量来自 `image.wjx.cn/joinnew/js/wjx_captch.js` 的 `captchaSceneid="q0hcfsca"`（全局）。引擎解析结果恒为 null，符合契约（不携带、不本地拦截）。

**复跑证据**：`evidence/04-engine-smoke.log` → **PASS=71 FAIL=0**（新增 dropdown 5 条 + 请求构造 9 条）；离线编译 EXIT=0。

**Kotlin vs Node 实测逐字段对照**（原任务交付判定项）：`evidence/06-kotlin-vs-node-parity.md`
- 同一输入下 `submitdata` 与 `jqsign`（解码前）与 T3 真实请求**逐字节相同**；`starttime/ktimes/jqnonce/capt/t` 位置与值一致。
- 差异仅两处且均为契约要求：V1 不发 `cst/source`、不发 `captchaVerifyParam/sceneId`；以及 `)` 的百分号编码集合不同（解码等价）。
- 对照工具：`tools/wjx-probe/ParityCheck.java`，输出 `evidence/06-parity-engine-output.txt`。

## 2026-09-22 · sceneId 断点修复（Lead 裁定：引擎自己补齐）

**断点**：`captchaSceneid` 不在问卷页 HTML（6/6 实测 0 次），而在 `image.wjx.cn/joinnew/js/wjx_captch.js`，
导致 `model.sceneId` 恒为 null → 兜底拿到令牌后 body 仍不带 sceneId。

**实现**（`HttpWjxSurveyClient.fetch`，冻结签名不变）
1. 页面内联 → 用（SceneIdSource.PAGE）；
2. 无内联 **且 useAliVerify=1** → 按需 GET wjx_captch.js 提取常量（SceneIdSource.CAPTCHA_JS）；
3. 仍失败 → null，照常提交、不本地拦截。
- useAliVerify=0 **零额外请求**（注入式单测断言 loader 未被调用）；CDN 请求用独立 CookieJar，不把会话 cookie 带到 CDN。
- 不硬编码：`WjxSceneId.extract` 正则提取，平台改版可跟随。

**新增公开纯函数**：`object WjxSceneId { CAPTCHA_JS_URL; extract(text): String?; resolve(inline, useAliVerify, jsLoader): Pair<String?, SceneIdSource?> }`
**additive 字段**：`enum SceneIdSource { PAGE, CAPTCHA_JS }` + `SurveyModel.sceneIdSource: SceneIdSource? = null`（Java 调用方需传满 13 参）。

**实测**
- 样本 Q0DQewW（useAliVerify=1）：`sceneId=q0hcfsca 来源=wjx_captch.js（按需抓取）` → `evidence/05-dry-run-sample.txt`
- 公开 hPyt0iq（useAliVerify=0）：`sceneId=null 来源=无`，不发 JS 请求 → `evidence/07-dry-run-public-useAliVerify0.txt`
- 冒烟：`evidence/04-engine-smoke.log` → **PASS=84 FAIL=0**（+13 条 sceneId 分支）

## 未验证 / 已知限制
1. 未在 `useAliVerify=0` 的问卷上做真实提交（Lead 裁决：不污染他人问卷）。因此"纯 HTTP 对未开启安全校验的问卷可行"仍是**强假设**，唯一硬证据要等用户在自己问卷上跑通兜底路径（WebView 点一次验证码 → 拿到 code 10）。
2. 服务端业务码 22（`submit_need_validate2`）为 JS 推导，未拿到真实响应；映射表在 `WjxResponseClassifier` 单点可改。
3. 分页问卷（E_PAGED）、矩阵/量表/商品题（E_UNSUPPORTED）只做了静态实现与 fixture 解析，没有端到端提交验证。
4. `sceneId` 实测不在问卷页 HTML 内（来自 wjx_captch.js 的全局常量），解析结果恒为 null —— 按契约该字段缺省即不携带，符合预期。
