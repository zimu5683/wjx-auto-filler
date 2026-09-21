# API-CONTRACT.md — 问卷星自动填表（wjx-auto-filler）冻结接口契约

> 版本：v1.0（2026-09-22）
> 状态：**FROZEN**。第 2 章的 Kotlin 签名由 Lead 冻结，任何人不得擅自改动；确需变更必须先 send_message 给 lead 取得同意，并在本文件「变更记录」追加一行。
> 读者：api-debug（T4 引擎实现）、android-dev（T5 界面/配置/扫码/更新）、qa-build（T6 测试）。
> 目标：只看本文件即可写出代码，不需要再问设计者。

---

## 0. 事实来源与证据等级

| 标记 | 含义 |
|---|---|
| 【实测】 | 本次在样本问卷 `https://www.wjx.cn/vm/Q0DQewW.aspx` 上抓取/解码得到，可直接依赖 |
| 【JS】 | 来自问卷星前端 `jqmobo2.js`（v=7558）反混淆阅读，语义确定 |
| 【T3】 | 依赖 api-debug 的 T3 提交实测结论；实现时按第 11 章决策表分支处理 |

样本问卷实测结构【实测】：标题「测试」，共 3 题，全部为单行填空题（type='1'）：
1. 姓名（例：XXX）  req=1  input name='q1'
2. 学号(例：XXXXXXXXXXX） req=1  input name='q2'
3. 班级（例：生物XX） req=1  input name='q3'

页面同时含 `useAliVerify=1`、`captchaType='2'`、`needLoadAliVerify=1`【实测】→ 阿里云验证码是否强制拦截由 T3 判定（见第 11 章）。

**注意：样本问卷只有填空题，没有单选/多选/矩阵题。** 单选/多选/下拉/矩阵分支无法在该样本上端到端验证，qa-build 必须用单元测试 + 构造的 HTML fixture 覆盖（见第 12 章）。

---

## 1. 包与文件布局（写作用域）

| 文件 | 归属任务 | 约束 |
|---|---|---|
| `android/app/src/main/java/com/wjx/autofill/wjx/SurveyModel.kt` | T4 | 纯 Kotlin/JVM |
| `.../wjx/WjxSubmitCodec.kt` | T4 | 纯 Kotlin/JVM |
| `.../wjx/WjxSurveyClient.kt`（接口 + `HttpWjxSurveyClient`） | T4 | 纯 Kotlin/JVM |
| `.../wjx/WjxSubmitter.kt`（接口 + `HttpWjxSubmitter`） | T4 | 纯 Kotlin/JVM |
| `.../wjx/WjxErrors.kt`（`WjxException` + `SubmitErrorCode`） | T4 | 纯 Kotlin/JVM |
| `.../config/TemplateStore.kt`、`.../config/TemplatesJson.kt` | T5 | Android（org.json） |
| `.../ui/SubmitCoordinator.kt` | T5 | 不 import `android.*`，可 JVM 单测 |

**wjx/ 包的硬约束（T4）**：不得出现 `import android.*`、不得使用 `org.json`（JVM 单测里 org.json 是空壳）、不得引入任何新依赖（OkHttp/jsoup 一律禁止）。只用 `java.net.HttpURLConnection` + `kotlinx.coroutines` + `java.util.regex`。

---

## 2. 冻结签名（逐字，不得改动）

```kotlin
package com.wjx.autofill.wjx

enum class QuestionType { SINGLE, MULTI, TEXT, DROPDOWN, MATRIX, SLIDER, OTHER }
data class Option(val value: String, val label: String)
data class SurveyQuestion(val topic: Int, val title: String, val type: QuestionType, val options: List<Option>)
data class SurveyModel(
    val url: String, val shortId: String, val title: String,
    val questions: List<SurveyQuestion>, val submitUrl: String,
    val jqnonce: String, val ktimes: Int, val startTime: String,
    val captchaType: Int?, val cookies: Map<String, String>)
data class AnswerPair(val field: String, val value: String)
data class SubmitResult(val ok: Boolean, val httpStatus: Int, val message: String, val raw: String?)

interface WjxSurveyClient { suspend fun fetch(url: String): Result<SurveyModel> }
interface WjxSubmitter   { suspend fun submit(m: SurveyModel, answers: List<AnswerPair>): SubmitResult }
object WjxSubmitCodec {
    fun escape(v: String): String
    fun jqSign(jqnonce: String, ktimes: Int): String
    fun encodeSubmitData(pairs: List<Pair<Int, String>>): String
}
```

### 2.1 契约补充（additive，不修改上面的签名；T4 必须实现）

```kotlin
package com.wjx.autofill.wjx

/** fetch 失败时 Result.failure 里携带的异常。code 取 SubmitErrorCode 常量。 */
class WjxException(
    val code: String,
    override val message: String,          // 人类可读文案（不含 code 前缀）
    cause: Throwable? = null,
) : Exception(message, cause)

object SubmitErrorCode {
    const val URL = "E_URL"; const val NETWORK = "E_NETWORK"; const val HTTP = "E_HTTP"
    const val PARSE = "E_PARSE"; const val PAGED = "E_PAGED"; const val CAPTCHA = "E_CAPTCHA"
    const val UNMATCHED = "E_UNMATCHED"; const val EMPTY = "E_EMPTY"; const val LIMIT = "E_LIMIT"
    const val UNSUPPORTED = "E_UNSUPPORTED"; const val REJECTED = "E_REJECTED"; const val UNKNOWN = "E_UNKNOWN"

    /** 统一消息格式： "<CODE>|<人类可读文案>"；成功为 "OK|提交成功" */
    fun format(code: String, human: String): String = code + "|" + human
    /** 从 SubmitResult.message 取机器可读错误码；解析不到返回 UNKNOWN。 */
    fun of(result: SubmitResult): String =
        result.message.substringBefore('|').trim().ifBlank { UNKNOWN }
}
```

**为什么需要它**：冻结的 `SubmitResult` 没有 errorCode 字段，而 UI 必须区分「验证码拦截 / 未匹配 / 网络失败」并给出不同操作建议。因此把 `message` 的前缀固定为稳定错误码，UI 只用 `SubmitErrorCode.of(result)` 判断，**禁止用文案做字符串匹配**。

---

## 3. WjxSubmitCodec 规范

### 3.1 escape(v) —— 内容转义【JS】

分字符表：

| 序号 | 原文 | 转义后 |
|---|---|---|
| 0 | `$` | `ξ` (U+03BE) |
| 1 | `}` | `｝` (U+FF5D) |
| 2 | `^` | `ˆ` (U+02C6) |
| 3 | `\|` | `¦` (U+00A6) |
| 4 | `!` | `！` (U+FF01) |
| 5 | `<` | `＜` (U+FF1C) |

规范行为（必须按此实现，顺序即语义）：
1. 逐字符左到右扫描；命中上表则替换为对应转义字符，否则原样输出。
2. 然后删除非法 XML 字符：删除 `char < 0x20` 且不是 `\t(0x09) \n(0x0A) \r(0x0D)` 的字符，以及 `U+FFFE`、`U+FFFF`。（【JS】对应 `replace_specialChar` 之后的 `replace(/[^\x09\x0A\x0D\x20-\uD7FF\uE000-\uFFFD\ud800-\udfff\u10000-\u10FFFF]/gi,"")`）
3. **不做 trim，不做空白折叠，不做长度截断**（trim/空白折叠由调用方在构造答案时做：见 3.4）。
4. 幂等：转义结果中的字符不在源表内，二次 escape 不改变结果。

### 3.2 jqSign(jqnonce, ktimes) —— 签名【JS】

```text
key = ktimes % 10 ; 若 key == 0 则 key = 1        // ktimes 为负时用 floorMod 归一化到 0..9
output[i] = charCodeAt(jqnonce, i) XOR key        // 逐 UTF-16 码元异或
```
- 输出与输入**等长**，不是 hex，不要大写化、不要 Base64。
- 输出的字符可能落在非 URL 安全区（反引号、逗号、空格等）→ 放进 URL 时必须 `URLEncoder.encode(jqsign, "UTF-8")`。
- `jqnonce` 必须来自本次 fetch 的页面（或同源 cookie），不得缓存复用跨问卷。

### 3.3 encodeSubmitData(pairs) —— 答案序列化【JS】

```text
按 topic 升序排列（稳定排序）
结果 = pairs.join("}")，其中每项 = topic.toString() + "$" + value
```
- `value` 必须是**已 escape 的最终载荷**：文本答案是 escape(用户文本)；多选是各选项值分别 escape 后用 `|` 连接（`|` 是分隔符，绝不 escape）。
- topic 必须 ≥ 1，否则抛 `IllegalArgumentException`（编程错误，不是运行时错误）。
- 重复 topic：后出现的覆盖先出现的（确定性行为，不做校验；匹配器负责在更上层拒绝重复，见第 7 章）。
- 排序：topic 升序，与页面 JS 一致（`l.sort((a,b)=>a._topic-b._topic)`）。**不得依赖调用方传入顺序**。
- 输入为空 → 返回空串；由调用方保证非空（第 6 章 E_EMPTY）。

### 3.4 测试向量（qa-build 直接照抄成单测）

```kotlin
// escape
assertEquals("aξb｝cˆd¦e！f＜g", WjxSubmitCodec.escape("a$b}c^d|e!f<g"))
assertEquals("正常文本，含ξ和｝与¦", WjxSubmitCodec.escape("正常文本，含$和}与|"))
assertEquals("已转义ξ｝ˆ¦！＜不应二次转义", WjxSubmitCodec.escape("已转义ξ｝ˆ¦！＜不应二次转义")) // 幂等
assertEquals("ab", WjxSubmitCodec.escape("a\u0001b"))                    // 非法控制字符被删

// jqSign（nonce 取自样本问卷实测页面）
private val N = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6"
assertEquals("d3`9528b,``6c,50e6,c1b6,8b01943e77e7", WjxSubmitCodec.jqSign(N, 0))  // key=1
assertEquals("b5f?34>d*ff0e*36c0*e7d0*>d67?25c11c1", WjxSubmitCodec.jqSign(N, 7))  // key=7
assertEquals("`cb", WjxSubmitCodec.jqSign("abc", 10))   // 10%10==0 -> key=1
assertEquals("ba`", WjxSubmitCodec.jqSign("abc", 3))

// encodeSubmitData
assertEquals("1$张三}2$2024001}3$生物1班",
    WjxSubmitCodec.encodeSubmitData(listOf(1 to "张三", 2 to "2024001", 3 to "生物1班")))
assertEquals("2$1|3", WjxSubmitCodec.encodeSubmitData(listOf(2 to "1|3")))
assertEquals("1$aξb}3$1|3", WjxSubmitCodec.encodeSubmitData(listOf(1 to "aξb", 3 to "1|3")))
assertEquals("1$A}2$B}3$C",   // 内部排序，与传入顺序无关
    WjxSubmitCodec.encodeSubmitData(listOf(3 to "C", 1 to "A", 2 to "B")))
```
（`` ` `` 在 Kotlin 字符串里是普通字符，无需转义。）

---

## 4. 配置模型与 templates.json schema

### 4.1 运行时模型（T5 实现，包 `com.wjx.autofill.config`）

```kotlin
package com.wjx.autofill.config

import com.wjx.autofill.wjx.AnswerPair

data class AnswerGroup(val name: String, val pairs: List<AnswerPair>)
data class MappingTemplate(
    val id: String,                 // UUID 字符串，1..64
    val name: String,               // 1..60
    val surveyUrl: String,          // 必填，https://(www.)?wjx.cn/(vm|jq|m)/<shortId>.aspx
    val shortId: String,            // ^[A-Za-z0-9]{4,32}$，可与 surveyUrl 互推
    val groups: List<AnswerGroup>,  // 可为空（空模板允许保存，提交时明确报错）
    val concurrency: Int = 2,       // 1..5，默认 2
    val updatedAt: Long = 0L,       // epoch millis，0 = 未知；用于列表排序
)

data class ImportReport(
    val imported: List<MappingTemplate>,
    val warnings: List<String>,     // 非致命：字段被 clamp/被推导/被忽略
    val failures: List<String>,     // 致命：单个模板导入失败的原因（其他模板不受影响）
)
```

**`AnswerPair.field` 语义（关键，UI 左栏与存储的唯一表示）**：可以是
- 题号字符串，如 `"3"`（匹配规则 R1，最高优先级）；
- 题干片段/全称，如 `"姓名"`（匹配规则 R2）；
- 某个选项文本，如 `"男"`（匹配规则 R3）。

**`AnswerPair.value` 语义**：单选/下拉 = 选项值或选项文本（见 7.3）；多选 = 选项值/文本用 `|` 连接；文本题 = 原文。**空字符串 = 该题不发送**（视为用户未填）。

### 4.2 templates.json —— JSON Schema（draft-07 子集）

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "WjxAutoFill templates",
  "type": "object",
  "required": ["schemaVersion", "templates"],
  "additionalProperties": true,
  "properties": {
    "schemaVersion": { "type": "integer", "minimum": 1, "maximum": 1, "default": 1 },
    "exportedAt":    { "type": "integer", "minimum": 0, "description": "导出时间 epoch millis，可选" },
    "appVersion":    { "type": "string", "maxLength": 32, "description": "导出时的 versionName，可选" },
    "templates": {
      "type": "array", "default": [],
      "items": {
        "type": "object",
        "required": ["id", "name", "surveyUrl", "groups"],
        "additionalProperties": true,
        "properties": {
          "id":          { "type": "string", "minLength": 1, "maxLength": 64 },
          "name":        { "type": "string", "minLength": 1, "maxLength": 60 },
          "surveyUrl":   { "type": "string", "pattern": "^https://(www\\.)?wjx\\.cn/(vm|jq|m)/[A-Za-z0-9]{4,32}\\.aspx" },
          "shortId":     { "type": "string", "pattern": "^[A-Za-z0-9]{4,32}$" },
          "concurrency": { "type": "integer", "minimum": 1, "maximum": 5, "default": 2 },
          "updatedAt":   { "type": "integer", "minimum": 0, "default": 0 },
          "groups": {
            "type": "array", "default": [],
            "items": {
              "type": "object",
              "required": ["pairs"],
              "additionalProperties": true,
              "properties": {
                "name":  { "type": "string", "maxLength": 60, "default": "" },
                "pairs": {
                  "type": "array", "default": [],
                  "items": {
                    "type": "object",
                    "required": ["field", "value"],
                    "additionalProperties": true,
                    "properties": {
                      "field": { "type": "string", "minLength": 1, "maxLength": 200 },
                      "value": { "type": "string", "maxLength": 3000 }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
```

### 4.3 示例文件

```json
{
  "schemaVersion": 1,
  "exportedAt": 1790008056000,
  "appVersion": "1.0.0",
  "templates": [
    {
      "id": "6f1c0b7e-2a4d-4a9b-9d31-2b7f0f2c5a10",
      "name": "测试问卷-3人",
      "surveyUrl": "https://www.wjx.cn/vm/Q0DQewW.aspx",
      "shortId": "Q0DQewW",
      "concurrency": 2,
      "updatedAt": 1790008056000,
      "groups": [
        { "name": "张三", "pairs": [
            { "field": "姓名", "value": "张三" },
            { "field": "学号", "value": "2024001" },
            { "field": "班级", "value": "生物1班" } ] },
        { "name": "李四", "pairs": [
            { "field": "1", "value": "李四" },
            { "field": "2", "value": "2024002" },
            { "field": "3", "value": "生物2班" } ] }
      ]
    }
  ]
}
```

### 4.4 读写与向后兼容策略（T5 必须逐条实现）

**读取（导入 / 启动加载）**
1. 文件不存在 → 视为空配置（`templates = []`），**不报错**。
2. JSON 解析失败 → **绝不覆盖原文件**；把原文件改名为 `templates.json.bad-<epochMillis>`，以空配置启动，UI 提示「配置损坏，已备份为 …」。
3. `schemaVersion` 缺失 → 视为 1（v0 兼容）。`schemaVersion > 1` → 整体拒绝导入，返回 `ImportReport(failures = ["配置由更新版本（schemaVersion=N）导出，请升级 App"]) `。
4. `schemaVersion < 1` → 走迁移链 `migrate(from, to)`；当前只有 1，迁移链为空实现（预留函数签名）。
5. 未知字段：**忽略且不报错**（前向兼容）。不做 round-trip 保留（导出即当前 schema 全量重写）。
6. 类型不符（如 `concurrency` 为字符串）：尝试宽容转换（`"2"` → 2）；转换失败 → 该模板计入 `failures`，其他模板继续导入。
7. 缺失可选字段 → 用默认值（`concurrency=2`、`updatedAt=0`、`group.name=""`、`pairs=[]`、`groups=[]`）。
8. 缺失必填字段（`id`/`name`/`surveyUrl`/`groups` 或 `pair.field`）→ 该模板失败，原因写入 `failures`。
9. `surveyUrl` 不匹配正则 → 该模板失败。`shortId` 缺失 → 从 `surveyUrl` 推导 + warning；两者冲突 → **以 surveyUrl 为准** + warning。
10. `concurrency` 越界 → clamp 到 1..5 + warning。`name` 超长 → 截断到 60 + warning。
11. `id` 与现有配置或同批导入重复 → 重新生成 UUID + warning。
12. `pair.value` 空字符串 → 合法（语义：该题不填），不报错。
13. 导入是**合并**语义：新模板追加，同 `id` 视为覆盖（但第 11 条已保证新导入的 id 不冲突）。

**写入（保存 / 导出）**
- 位置：`context.filesDir/templates.json`（内部存储）。
- 原子写：写 `templates.json.tmp` → `flush()` + `FileOutputStream.fd.sync()` → `File.renameTo(templates.json)`；失败则删除 tmp 并抛出（UI 提示保存失败，内存配置不变）。
- 编码：UTF-8 **无 BOM**，2 空格缩进，字段顺序按 4.2，`schemaVersion` 恒为 1。
- 导出走 SAF（`ACTION_CREATE_DOCUMENT`，mime `application/json`），文件名 `wjx-templates-<yyyyMMdd-HHmmss>.json`；导入走 `ACTION_OPEN_DOCUMENT`（minSdk 24 全部可用，无需存储权限）。
- 启动加载一次到内存（`StateFlow<List<MappingTemplate>>`），所有编辑在内存完成后整体落盘，避免半写状态。

**隐私**：配置含姓名/学号等个人信息，只存 App 内部目录；**不联网同步、不上传**。建议把 Manifest 的 `android:allowBackup` 改为 `false`（否则会被系统云备份带走）——见 DESIGN.md 安全章，需 android-dev 在 T5 落地。

---

## 5. 网络契约

### 5.1 通用常量

| 常量 | 值 |
|---|---|
| `DESKTOP_UA` | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36` |
| fetch 超时 | connect 10s / read 20s |
| submit 超时 | connect 10s / read 30s |
| `RAW_LIMIT` | 8192 字符（`SubmitResult.raw` 与日志截断上限） |
| 允许的 host | `wjx.cn`、`www.wjx.cn`（含子域 `*.wjx.cn`）；**必须 https** |
| shortId 正则 | `^https://(www\.)?wjx\.cn/(vm\|jq\|m)/([A-Za-z0-9]{4,32})\.aspx` |

### 5.2 fetch(url)：Result<SurveyModel>

1. URL 校验：trim 后必须匹配 shortId 正则 → 否则 `Result.failure(WjxException(E_URL, "链接无效：请填写 https://www.wjx.cn/vm/xxxx.aspx 形式的问卷链接"))`。`http://` 一律拒绝（https-only）。
2. 新建**本次实例独享**的内存 CookieJar（`CookieManager(null, CookiePolicy.ACCEPT_ALL)`）并 `setCookies` 到 `HttpURLConnection`；`instanceFollowRedirects = true`。
3. GET，请求头：`User-Agent: DESKTOP_UA`、`Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8`、`Accept-Language: zh-CN,zh;q=0.9`。不设 Referer。
4. 重定向后 `connection.url` 必须仍是 https 且 host ∈ wjx.cn → 否则 E_URL。
5. 读取响应：HTTP 非 200 → `E_HTTP`（message：「问卷服务器返回异常（HTTP %d）」）；IO 异常 → `E_NETWORK`（「网络连接失败，请检查网络后重试」）。
6. 解码：优先 `Content-Type; charset=`，无则 UTF-8；**不要**用系统默认编码。
7. 解析（第 6 章）→ 失败 → `E_PARSE` / `E_PAGED`。
8. 成功返回 `Result.success(SurveyModel)`。
9. `fetch` 内部**不重试**（重试由 UI 决定）。

### 5.3 submit(m, answers)：SubmitResult

流程：
1. 本地校验：`answers` 过滤掉 `value.isBlank()` 的项后为空 → `E_EMPTY|没有可提交的答案`（`httpStatus=0, raw=null`）。
2. 字段匹配（第 7 章）→ 失败 → `E_UNMATCHED|字段未匹配：<明细>`。
3. `submitdata = WjxSubmitCodec.encodeSubmitData(pairs)`。
4. 构造 URL：`m.submitUrl` 追加查询参数（都用 `URLEncoder.encode(v,"UTF-8")`）：
   `&jqnonce=<jqnonce>&jqsign=<jqSign(jqnonce, ktimes)>&ktimes=<ktimes>&t=<System.currentTimeMillis()>`，
   且当 `m.captchaType != null` 时追加 `&capt=<captchaType>`【JS】。
   `m.submitUrl` 必须以 https 开头且 host ∈ wjx.cn，否则 `E_URL`。
5. POST body（`application/x-www-form-urlencoded; charset=UTF-8`）：
   - `submitdata=<urlenc(submitdata)>`（**必带**）
   - `starttime=<urlenc(m.startTime)>`（仅当 `startTime` 非空白时携带；页面 AJAX 路径本身不发它，但服务端表单声明了该字段，作为保真项携带。若 T3 实测「带 starttime 反而被拒」，删除这一行即可——见第 11 章）
   - **绝不携带** `captchaVerifyParam`/`sceneId`（无验证码令牌；伪造令牌是禁止行为）。
6. Cookie：把 `m.cookies` 以 `Cookie: k=v; k2=v2` 形式附上；用**新的** `HttpURLConnection`（不复用 fetch 的连接）。
7. 响应分类（第 8.4 节）→ `SubmitResult`。
8. **绝不自动重试**：提交不是幂等操作，重试可能产生重复答卷。重试只能由用户显式点击触发（`SubmitCoordinator` 不重试）。
9. 除 `CancellationException` 外**不得抛异常**：所有异常都要转成 `ok=false` 的结果。

---

## 6. 页面解析契约（fetch 第 7 步）

### 6.1 总规则

- 纯字符串扫描实现（无 jsoup）。必须容忍单引号/双引号、属性顺序任意、属性间多空格、大小写差异。
- 步骤：
  1. 找 `id="divQuestion"` 的元素（`id` 值可用单/双引号），按 `<div`/`</div>` 深度配平取块；找不到则用整页。
  2. 在块内扫描所有 `<div ...>` 开始标签，凡同时含 `topic` 属性且 `class` 含 `field` 者视为题目起点（`topic` 值必须是十进制整数）。对该起点做 div 深度配平得到题目块（题目块内嵌套 div，**必须配平，不能用正则贪婪截断**）。
  3. 题干：块内 `class` 含 `topichtml` 的元素的 innerText（去标签 + HTML 实体反转义 + trim）。缺失则用 `field-label` 文本去掉前导题号。
  4. 题型与选项：DOM 证据优先，`type` 属性码作为交叉校验（冲突时以 DOM 为准）。
- HTML 实体反转义至少支持：`&amp; &lt; &gt; &quot; &#39; &nbsp;` 以及十进制 `&#NNN;`、十六进制 `&#xHH;`。

### 6.2 题型判定表

| DOM 证据（块内） | QuestionType | options |
|---|---|---|
| 有 `input[type=radio]` | SINGLE | 每个 radio：`value` 属性原文；label 见 6.3 |
| 有 `input[type=checkbox]` | MULTI | 同上 |
| 有 `select` + `option` | DROPDOWN | option 的 `value` + 文本 |
| 有 `textarea` | TEXT | 空列表 |
| 有 `input[type=text]`/`input`（无上述控件） | TEXT | 空列表 |
| 有 `input[type=range]` 或 class 含 `slider` | SLIDER | 空列表 |
| 有 `table`/`class` 含 `matrix`/`divMatrix` | MATRIX | 空列表 |
| 其他 | OTHER | 空列表 |

`type` 属性码参考映射【JS】：1/2→TEXT、3→SINGLE、4→MULTI、6/7→MATRIX、8→SLIDER、10→DROPDOWN、21/22→OTHER（商品/预约）。

### 6.3 选项 label 提取

1. 从 `input` 向上找最近的、`class` 含 `ui-radio`/`ui-checkbox` 的祖先（最多 4 层）；
2. label = 该祖先的文本内容去掉 input 自身后 trim（去标签 + 实体反转义）；
3. 为空则取 `input` 之后最近的文本节点/兄弟元素文本；
4. 仍为空则 label = `value` 属性值。
`Option.value` **必须存 DOM `value` 属性原文**（问卷星单选/多选的 value 通常是 1 基序号，直接使用，不要用 label 反推序号）。

### 6.4 头部字段解析

| 字段 | 来源 | 缺失时 |
|---|---|---|
| `title` | `<title>` 文本（实体反转义 + trim）；空则 `"未命名问卷"` | 用默认值 |
| `shortId` | 由 URL 正则捕获组 | 必填（否则 E_URL） |
| `submitUrl` | `<form id="form1" ... action="...">` 的 action（页面给的是绝对 https 地址）；否则回退 `https://www.wjx.cn/joinnew/processjq.ashx?shortid=<shortId>` | 回退值 |
| `jqnonce` | 正则 `var\s+jqnonce\s*=\s*["']([^"']+)["']` | **缺失 → E_PARSE** |
| `ktimes` | 正则 `var\s+ktimes\s*=\s*(\d+)` | 默认 `0`（页面 JS 初始化为 0，XOR key 变成 1） |
| `startTime` | `id="starttime"` 的 `value` 属性 | `""` |
| `captchaType` | 正则 `captchaType\s*=\s*['"]?(\d+)['"]?` | 若 `useAliVerify=1` 或 `needLoadAliVerify=1` → `2`；否则 `null` |
| `cookies` | fetch 结束后 CookieManager 快照（name→value，跳过空值） | 空 Map |

### 6.5 分页问卷检测（V1 不支持，必须显式报错）

满足任一条件即返回 `E_PAGED|该问卷为分页/逐题模式，暂不支持自动填写`：
- 页面含 `IsOneQuestionPerPage = 1`；
- 页面含非空的 `window.partPages`；
- `#divQuestion` 内存在多个 `fieldset` 且存在 `pg` 属性 > 1 的 fieldset。

理由：分页问卷需要按页提交（`partpages` 参数），一次性提交全部答案会被服务端拒绝或只记录首页。**宁可明确失败，不可提交半份答卷。**
（样本问卷 `IsOneQuestionPerPage = 0`【实测】，不受影响。）

---

## 7. 字段匹配契约（submit 第 2 步）

### 7.1 规则与优先级

对每个 `AnswerPair`（按列表顺序），依次尝试；**首个命中即停止**：

| 规则 | 条件 | 结果 |
|---|---|---|
| **R1 精确题号** | `field.trim()` 匹配 `^\d+$` 且存在该 `topic` | 命中该题 |
| **R2 题干包含** | 存在 `question.title.contains(field.trim())`（中文按原文子串；ASCII 忽略大小写） | 命中该题 |
| **R3 选项文本** | 存在某题的某个 option `label == field.trim()`（先精确、再 contains） | 命中该题；若 `pair.value` 为空，则取值 = 该 option 的 `value` |

### 7.2 歧义与未命中（都算失败，绝不静默丢弃）

- R2/R3 命中多个题目 → `E_UNMATCHED|字段「<field>」匹配到多个题目（<题号列表>），请改用题号`。
- 全部规则未命中 → `E_UNMATCHED|字段「<field>」未匹配到任何题目`。
- 两个不同 `field` 命中同一题目 → `E_UNMATCHED|多个字段指向同一题（题号 <N>）：<字段列表>`。
- 任一失败 → **整次提交中止**（`ok=false, httpStatus=0, raw=null`），不做「部分提交」。理由：缺字段的半份答卷会污染问卷数据，且服务端必答校验也会拒绝。
- 失败明细最多列 5 条，其余用「等 N 项」省略，保证 message 可读且不超长。

### 7.3 值解析（命中题目之后）

| 题型 | 值处理 |
|---|---|
| TEXT | 直接用 `escape(pair.value.trim())`；长度 > 3000 → `E_LIMIT|答案超过 3000 字上限（题号 N）` |
| SINGLE / DROPDOWN | 若 value 等于某 option.value → 用它；否则若等于某 option.label → 转成该 option.value；都不是 → `E_UNMATCHED|题号 N 的取值「<value>」不在选项中` |
| MULTI | 按 `\|` 切分，每段按 SINGLE 规则解析成 option.value；空段丢弃；结果为空 → `E_UNMATCHED`；最后 `parts.join("\|")` |
| MATRIX / SLIDER / OTHER | `E_UNSUPPORTED|题号 N（<类型>）暂不支持自动填写`（V1 明确不支持，不静默跳过） |

- 长度上限 3000 字符【JS】：`validateQ` 对 type=1/2 有 3000 字校验。
- 空 `value` 的 pair 在第 5.3 步已被过滤（不发送）。
- 本地**不做**必答校验（除「无任何答案」外）：服务端返回的校验文案原样进入 `E_REJECTED` 的人类文案部分。

---

## 8. 错误契约

### 8.1 两种失败通道

| 通道 | 使用场景 | 约定 |
|---|---|---|
| `Result.failure(WjxException)` | 只有 `fetch()` 用 | `code` ∈ 错误码表；`message` 是**纯人类文案**（不含 code 前缀） |
| `SubmitResult(ok=false, ...)` | 只有 `submit()` 用 | `message` = `"<CODE>|<人类文案>"`；`submit()` 除 `CancellationException` 外不抛异常 |
| 成功 | 两者 | `fetch` → `Result.success`；`submit` → `ok=true` 且 `message = "OK|提交成功"` |

### 8.2 错误码与文案表（文案逐字实现，UI 只读码）

| code | httpStatus | 人类文案（message 的 `\|` 之后部分） | 触发条件 |
|---|---|---|---|
| `E_URL` | 0 | 链接无效：请填写 https://www.wjx.cn/vm/xxxx.aspx 形式的问卷链接 | URL 非 https / 非 wjx.cn / 无 shortId |
| `E_NETWORK` | 0 | 网络连接失败，请检查网络后重试 | DNS/连接/读超时/IO |
| `E_HTTP` | 实际码 | 问卷服务器返回异常（HTTP %d） | fetch/submit 非 2xx |
| `E_PARSE` | 200 | 问卷页面解析失败，可能是问卷已关闭或页面改版 | 无 jqnonce / 无题目 / 响应非 HTML |
| `E_PAGED` | 200 | 该问卷为分页/逐题模式，暂不支持自动填写 | 6.5 检测命中 |
| `E_CAPTCHA` | 实际码 | 该问卷要求人机验证（阿里云验证码），纯接口无法提交 | 响应含 `aliyunwaf`/验证码特征 |
| `E_UNMATCHED` | 0 | 字段未匹配：<明细> | 第 7.2 节 |
| `E_EMPTY` | 0 | 没有可提交的答案 | 过滤空值后 answers 为空 |
| `E_LIMIT` | 0 | 答案超过 3000 字上限（题号 %d） | 文本超长 |
| `E_UNSUPPORTED` | 0 | 题号 %d（%s）暂不支持自动填写 | 命中 MATRIX/SLIDER/OTHER |
| `E_REJECTED` | 实际码 | 问卷服务端拒绝：<服务端文案> | 业务失败（HTTP 200 但响应非成功） |
| `E_UNKNOWN` | 实际码 | 未知错误：<摘要> | 兜底 |

`E_UNMATCHED` / `E_UNSUPPORTED` / `E_EMPTY` / `E_LIMIT` 是**本地错误**：`httpStatus = 0`、`raw = null`，且**不发出任何网络请求**。

### 8.3 UI 文案映射（android-dev 用）

- `E_URL` → 输入框红字 + 「请检查链接」
- `E_NETWORK` → 「重试」按钮
- `E_CAPTCHA` → 「该问卷需要人机验证，无法自动提交」（终态，不提供重试）
- `E_UNMATCHED` → 跳到字段映射页，把明细里的字段高亮
- `E_REJECTED` → 原文展示服务端文案（例如「请输入正确的学号」）
- 其余 → 通用错误提示 + 原始 raw 可折叠查看（仅调试用；**不做 App 内日志页**，见 DESIGN.md 非目标）

### 8.4 响应分类器（`WjxResponseClassifier`，T4 实现，单点可改）

输入：HTTP 码 + 响应正文；输出：`SubmitResult`。按顺序判定：
1. HTTP 非 2xx → `E_HTTP`（`raw` = 正文前 8192 字符）。
2. 正文为空 → `E_PARSE|响应为空`。
3. 正文含 `aliyunwaf`（忽略大小写）→ `E_CAPTCHA`（与页面 JS 同款判定【JS】）。
4. 正文含 `captcha`/`验证码`/`AliyunCaptcha` → `E_CAPTCHA`。
5. 以 `〒` 分割取第一段【JS】：`== "10"` → `ok=true`；否则 `E_REJECTED`，人类文案取第二段（无第二段则取正文前 200 字）。
6. 正文以 `<html` 开头 → `E_PARSE|服务端返回页面而非结果`。
7. 兜底 → `E_REJECTED|问卷服务端拒绝：<正文前 200 字>`。

**铁律：HTTP 200 绝不等于提交成功。** 问卷星用 200 返回业务错误。成功判定必须走第 5 条（T3 实测若发现成功响应不是 `10` 前缀，只改这一个函数）。

---

## 9. 并发提交模型（`ui/SubmitCoordinator.kt`，T5）

```kotlin
package com.wjx.autofill.ui

import com.wjx.autofill.config.MappingTemplate
import com.wjx.autofill.wjx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class GroupOutcome(val index: Int, val groupName: String, val result: SubmitResult)
data class BatchReport(val outcomes: List<GroupOutcome>, val durationMs: Long) {
    val total: Int get() = outcomes.size
    val successCount: Int get() = outcomes.count { it.result.ok }
    val failureCount: Int get() = total - successCount
    /** 例：「成功 2 / 失败 1 / 共 3」 */
    fun summary(): String = "成功 $successCount / 失败 $failureCount / 共 $total"
}

class SubmitCoordinator(
    private val clientFactory: () -> WjxSurveyClient = { HttpWjxSurveyClient() },
    private val submitter: WjxSubmitter = HttpWjxSubmitter(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * 逐组「独立会话」提交：每组重新 fetch 页面拿新的 jqnonce/cookie，再 submit。
     * @param concurrency 1..5，越界自动 clamp；默认取 template.concurrency
     */
    suspend fun run(
        template: MappingTemplate,
        concurrency: Int = template.concurrency,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BatchReport
}
```

语义（逐条为验收点）：
1. `concurrency.clamp(1, 5)`；默认 2。用 `Semaphore(concurrency)` 控制，每组一个协程。
2. **每组独立会话**：每组调用 `clientFactory()` 新建 client（新 CookieJar）并**重新 fetch**，不得复用其他组的 `SurveyModel`。原因：jqnonce/cookie 与一次提交绑定，复用会串号。
3. 组内流程：`fetch` → 失败则该组结果 = `SubmitResult(false, 0, "E_XXX|...", null)`（由 fetch 的 `WjxException` 转换，码前缀保留）；成功则 `submit`。
4. 组间隔离：一组失败**不取消**其他组；组内异常一律捕获（`CancellationException` 除外，必须向上抛）。
5. 结果按 `index` 升序返回，与模板 `groups` 顺序一致（不按完成时间排序）。
6. `onProgress(done, total)` 每完成一组调用一次，`done` 从 1 单调递增；回调在调用方 dispatcher 上执行。
7. 空组（`pairs` 全为空或过滤后无答案）→ 不发起请求，结果 = `E_EMPTY|没有可提交的答案`（明确可见，不静默跳过）。
8. `template.groups` 为空 → 直接返回 `BatchReport(emptyList(), 0)`，不报错（UI 侧提示「没有分组」）。
9. 取消：`run` 必须可取消；在途请求要在 `readTimeout` 内结束，实现需在 `finally` 里 `connection.disconnect()`（HttpURLConnection 不响应线程中断，必须显式断开）。
10. **不重试**（见 5.3 第 8 条）；UI 提供「重试失败组」按钮，重试 = 用同一 template 再跑一次 `run`。
11. 该文件不 import `android.*`（`Dispatchers.IO` 来自 kotlinx-coroutines），qa-build 可用 `runTest` + 注入的 fake client/submitter + `StandardTestDispatcher` 验证并发上限与结果顺序。

---

## 10. 版本与兼容策略

| 对象 | 版本载体 | 兼容规则 |
|---|---|---|
| 配置 schema | `templates.json#/schemaVersion`（当前 1） | 见 4.4：缺失视为 1；>1 拒绝导入；<1 走迁移链 |
| App 版本 | `android/version.properties`（`versionName`/`versionCode`，code = major\*10000+minor\*100+patch） | GitHub Release tag 必须等于 `v<versionName>`（CI 校验） |
| 问卷星页面改版 | 无版本 | 解析失败必须走 `E_PARSE` 并保留 raw 证据；分类/解析逻辑集中在 6/8.4 两处，便于单点修复 |

---

## 11. T3 依赖项与决策表（实现时按结论分支）

| # | 待确认 | 默认（无 T3 结论时） | 若 T3 结论相反 |
|---|---|---|---|
| D1 | `captchaType=2` 是否强制拦截纯接口提交 | 照常 POST；由分类器判 `E_CAPTCHA` | 可在 `HttpWjxSubmitter` 开头加快速路径：`captchaType==2 → E_CAPTCHA`（省一次无效请求，需 Lead 批准） |
| D2 | 成功响应是否以 `10` 开头 | 按 8.4 第 5 条 | 只改 `WjxResponseClassifier` |
| D3 | POST body 是否需要 `starttime` | 携带（非空时） | 删掉该行（第 5.3 步 5） |
| D4 | 是否需要 `&capt=2` 查询参数 | 按 `captchaType != null` 携带 | 删除 |
| D5 | 是否存在 `jqsign` 之外的服务端签名（如 `rn`/`lct`/`jpm`） | V1 只发 jqnonce/jqsign/ktimes/t/capt | 按证据追加固定参数 |

**任何一项被 T3 证伪，api-debug 只需改上表指定的一处代码，不得改动第 2 章签名。**

---

## 12. 实现者检查清单（DoD）

**T4（api-debug）**
- [ ] `wjx/` 无 `android.*`、无 `org.json`、无新依赖
- [ ] `WjxSubmitCodec` 通过第 3.4 节全部测试向量
- [ ] `fetch` 对样本问卷返回 3 道 TEXT 题、题号 1/2/3、题干正确、`jqnonce` 非空
- [ ] `submit` 对样本问卷的 3 个必填字段能构造出 `1$..}2$..}3$..` 形态的 submitdata（可用 fake 网络层断言）
- [ ] `submit` 除 `CancellationException` 外不抛异常；本地错误不发网络请求
- [ ] 分页问卷检测（6.5）有单测 fixture
- [ ] 单选/多选/下拉的值解析（7.3）有单测 fixture

**T5（android-dev）**
- [ ] `templates.json` 读写符合 4.2–4.4（含原子写、损坏备份、clamp、id 去重）
- [ ] `SubmitCoordinator` 并发上限 = clamp(concurrency,1,5)，每组独立 fetch，结果按组序返回
- [ ] UI 用 `SubmitErrorCode.of(result)` 分支，不用文案匹配
- [ ] 未匹配字段在 UI 可见（高亮 + 明细），不静默丢弃
- [ ] Manifest `allowBackup=false`（或 dataExtractionRules 排除 templates.json）

**T6（qa-build）**
- [ ] 第 3.4 节测试向量全部落地
- [ ] 构造 HTML fixture 覆盖 6.2 的每一种题型判定
- [ ] 版本比较、模板导入导出（含 schemaVersion=2 拒绝、坏 JSON 备份）单测
- [ ] 并发上限断言（fake 层记录最大同时在线请求数 ≤ concurrency）
- [ ] 单测若要解析 JSON，必须加 `testImplementation("org.json:json:20231013")`（build.gradle.kts 在 T1 写作用域，需 Lead 落地）

---

## 变更记录

| 日期 | 变更 | 批准 |
|---|---|---|
| 2026-09-22 | 初版：冻结签名 + codec 规范 + templates schema + 网络/解析/匹配/错误/并发契约 | Lead（签名冻结）/ architect（其余） |
