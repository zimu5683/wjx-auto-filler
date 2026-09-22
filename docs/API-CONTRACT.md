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

页面同时含 `useAliVerify=1`、`captchaType='2'`、`needLoadAliVerify=1`【实测】。

**T3 结论（2026-09-22 实测）：强制拦截。** 纯接口 POST 得到 HTTP 200 + 正文 `7〒需要安全校验，请重新提交！`（43 B），业务码 `7` 对应页面 JS 的「弹阿里云智能验证」分支 → **本样本问卷无法纯接口提交**，引擎对这类问卷必须返回 `E_CAPTCHA`（见 §11）。

**T14 事实与修正（2026-09-22）：** 新样本 `https://v.wjx.cn/vm/P2M09FG.aspx`（`v.wjx.cn` 短链）页面 `useAliVerify=0`，最初一次提交返回**裸码 22**（曾被解读为「服务端主动要求二次校验」）。**随后 api-debug 的干净单变量 A/B（V6）推翻了该解读**：唯一变量 `ktimes: 0 → 4`（且不补发 `rn`/`captchaVerifyParam`/`sceneId`）后，同一问卷返回 **`10〒/wjx/join/complete.aspx?activityid=P2M09FG&joinid=127844297308&…`** —— **纯接口提交成功**。→ ① URL 正则必须接受 wjx.cn **任意子域**（§5.1）；② 裸 22 是 **`ktimes=0` 触发的风控指纹**，不是「问卷需要验证码」；③ `useAliVerify=0` 的问卷**可以纯接口提交**（前提：`ktimes≥4`（契约取下限 4） 且不补发校验类字段，§5.3）；④ `useAliVerify=1` 的问卷仍被服务端要求安全校验（T3 样本 → 7），走 §13 兜底。 **用户本人也确认：他在微信里扫这张二维码填写，没有弹人机验证** —— 独立佐证「该问卷不需要人机验证」。

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
| `.../config/TemplateStore.kt`、`.../config/TemplatesJson.kt`（MiniJson） | T5 | Android；JSON 用自写 MiniJson（纯 Kotlin），不用 org.json |
| `.../submit/SubmitCoordinator.kt` | T5 | 不 import `android.*`，可 JVM 单测（Lead 2026-09-22 裁决：编排不属于 UI） |
| `.../ui/CaptchaActivity.kt` | T5 | WebView 兜底：仅唤起验证码 + 收割令牌，**不填表不提交**（§13.4） |

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
    val captchaType: Int?, val cookies: Map<String, String>,
    val useAliVerify: Boolean = false,   // Lead 2026-09-22 改判后 additive：页面 var useAliVerify=1 时为 true；**仅作展示，不参与门控**
    val sceneId: String? = null)          // Lead 2026-09-22 批准（additive）：阿里云验证场景标识（captchaSceneid）；缺省时提交不携带该字段
data class AnswerPair(val field: String, val value: String)
data class SubmitResult(val ok: Boolean, val httpStatus: Int, val message: String, val raw: String?,
    val errorCode: String? = null)   // Lead 2026-09-22 裁决新增（additive，带默认值：既有构造点全部兼容）

interface WjxSurveyClient { suspend fun fetch(url: String, cookies: Map<String, String> = emptyMap()): Result<SurveyModel> }
interface WjxSubmitter   { suspend fun submit(m: SurveyModel, answers: List<AnswerPair>, captchaToken: String? = null): SubmitResult }
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
}
```

**为什么需要它**：UI 必须区分「验证码拦截 / 未匹配 / 网络失败」并给出不同操作建议。Lead 2026-09-22 裁决：**不把机器码塞进人类字符串**，而是给 `SubmitResult` 增加 additive 字段 `errorCode: String? = null`：
- 成功：`ok=true`、`errorCode=null`、`message` 为人类文案（「提交成功」）；
- 失败：`errorCode` ∈ `SubmitErrorCode` 常量集合，`message` 是**纯人类文案**，不含任何机器码前缀。

带默认值 ⇒ 既有构造点 `SubmitResult(true, 200, "提交成功", raw)` 全部兼容。UI 直接读 `result.errorCode`（`null` = 成功），**禁止用 message 做字符串匹配**。`SubmitErrorCode` 只保留常量，**不提供 format()/of()**。

### 2.2 数据类字段语义表（含可空性）

| 类型 | 字段 | 类型 | 语义 / 来源 | 可空 |
|---|---|---|---|---|
| `Option` | `value` | String | DOM `value` 属性**原文**（问卷星单选/多选通常是 1 基序号）；提交时写进 submitdata 的就是它 | 否（空串视为无效选项） |
| `Option` | `label` | String | 选项可见文本（去标签 + 实体反转义 + trim）；提取不到时退化为 `value` | 否 |
| `SurveyQuestion` | `topic` | Int | 题号，来自 `div[topic]`，≥ 1，问卷内唯一 | 否 |
| `SurveyQuestion` | `title` | String | 题干纯文本（不含题号前缀） | 否（可为空串） |
| `SurveyQuestion` | `type` | QuestionType | 题型，判定见 §6.2 | 否 |
| `SurveyQuestion` | `options` | List<Option> | 选项；TEXT / MATRIX / SLIDER / OTHER 为**空列表** | 否（可为空列表） |
| `SurveyModel` | `url` | String | 调用 `fetch` 时传入并 trim 的原始 URL（保留 query）；UI 展示与模板保存用 | 否 |
| `SurveyModel` | `shortId` | String | 从 URL 捕获的问卷短 ID | 否 |
| `SurveyModel` | `title` | String | `<title>` 文本；空则 `"未命名问卷"` | 否 |
| `SurveyModel` | `questions` | List<SurveyQuestion> | **按 topic 升序**（解析后显式排序，保证确定性）；非空，否则 `E_PARSE` | 否 |
| `SurveyModel` | `submitUrl` | String | 表单 action（绝对 https）或回退值 | 否 |
| `SurveyModel` | `jqnonce` | String | 本次会话签名随机数；非空，否则 `E_PARSE` | 否 |
| `SurveyModel` | `ktimes` | Int | 页面 `ktimes`，缺失默认 `0` | 否 |
| `SurveyModel` | `startTime` | String | `#starttime` 的 value，缺失空串 | 否 |
| `SurveyModel` | `captchaType` | Int? | 页面 `captchaType`；`null` = 页面未声明验证码。**只用于 URL 的 `&capt=` 参数，不是门控信号** | **是** |
| `SurveyModel` | `useAliVerify` | Boolean | 页面 `var useAliVerify`：`1`→`true`、缺失→`false`。**仅作展示/诊断，不参与判定**（本地门控已废除，§5.3 第 0 步） | 否（默认 `false`） |
| `SurveyModel` | `sceneId` | String? | 阿里云验证场景标识（页面 `captchaSceneid`/验证码初始化配置）。**Lead 已批准 additive**；`captchaToken` 非空且 `sceneId` 非空时写入 body；**缺省 → 不携带该字段**（本地不拦截，由服务端判定） | **是**（默认 `null`） |
| `SurveyModel` | `cookies` | Map<String,String> | fetch 结束时的 cookie 快照（name→value，跳过空值） | 否（可为空 Map） |
| `AnswerPair` | `field` | String | 映射键：题号 / 题干片段 / 选项文本（优先级 R1>R2>R3，§7） | 否（blank 由调用方过滤） |
| `AnswerPair` | `value` | String | 答案载荷；**空串 = 该题不发送** | 否 |
| `SubmitResult` | `ok`/`httpStatus`/`message`/`raw`/`errorCode` | — | 语义见 §8.1；**`errorCode == null` ⟺ 成功** | `raw`、`errorCode` 可为 null |

> `questions` 的排序是契约：UI 左栏/右栏、匹配歧义提示里的「题号列表」都依赖它稳定有序。

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
assertEquals("aξb｝cˆd¦e！f＜g", WjxSubmitCodec.escape("a\$b}c^d|e!f<g"))
assertEquals("正常文本，含ξ和｝与¦", WjxSubmitCodec.escape("正常文本，含\$和}与|"))
assertEquals("已转义ξ｝ˆ¦！＜不应二次转义", WjxSubmitCodec.escape("已转义ξ｝ˆ¦！＜不应二次转义")) // 幂等
assertEquals("ab", WjxSubmitCodec.escape("a\u0001b"))                    // 非法控制字符被删

// jqSign（nonce 取自样本问卷实测页面）
private val N = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6"
assertEquals("d3`9528b,``6c,50e6,c1b6,8b01943e77e7", WjxSubmitCodec.jqSign(N, 0))  // key=1
assertEquals("b5f?34>d*ff0e*36c0*e7d0*>d67?25c11c1", WjxSubmitCodec.jqSign(N, 7))  // key=7
assertEquals("`cb", WjxSubmitCodec.jqSign("abc", 10))   // 10%10==0 -> key=1
assertEquals("ba`", WjxSubmitCodec.jqSign("abc", 3))

// encodeSubmitData
assertEquals("1\$张三}2\$2024001}3\$生物1班",
    WjxSubmitCodec.encodeSubmitData(listOf(1 to "张三", 2 to "2024001", 3 to "生物1班")))
assertEquals("2\$1|3", WjxSubmitCodec.encodeSubmitData(listOf(2 to "1|3")))
assertEquals("1\$aξb}3\$1|3", WjxSubmitCodec.encodeSubmitData(listOf(1 to "aξb", 3 to "1|3")))
assertEquals("1\$A}2\$B}3\$C",   // 内部排序，与传入顺序无关
    WjxSubmitCodec.encodeSubmitData(listOf(3 to "C", 1 to "A", 2 to "B")))
```
**Kotlin 转义提醒（必须遵守，否则编译不过）**：Kotlin 字符串里 `$` 是模板起始符。上面断言中的 `\$` 必须保留反斜杠（`"a\$b"`），否则 `$b` 会被当成变量引用而编译失败；`$张三` 同理。反引号 `\`` 是普通字符，无需转义。

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
    val surveyUrl: String,          // 必填，https://<任意子域>.wjx.cn/(vm|jq|m)/<shortId>.aspx
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
          "surveyUrl":   { "type": "string", "pattern": "^https://([A-Za-z0-9-]+\\.)*wjx\\.cn/(vm|jq|m)/[A-Za-z0-9]{4,32}\\.aspx" },
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
13. 导入是**合并**语义：新模板**追加**到现有列表（第 11 条保证导入的 id 不与现有冲突）；App 内部保存（编辑同一 id）为**覆盖更新**。

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
| 允许的 host | `wjx.cn` 及其**任意子域**（`www.wjx.cn`、`v.wjx.cn` 等）；**必须 https** |
| shortId 正则 | `^https://([A-Za-z0-9-]+\.)*wjx\.cn/(vm\|jq\|m)/([A-Za-z0-9]{4,32})\.aspx`（**wjx.cn 任意子域**：`www.`/`v.` 等短链都接受；只锚定前缀，允许 query/fragment，如 `?q1=Q0DQewW&q2=1`；不允许其它 path 形态） |

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

流程（**第 0 步：不做本地门控，总是先尝试提交**）：
0. **不做本地验证码门控（Lead 2026-09-22 改判）：总是先尝试提交。** 无论 `m.useAliVerify` 为何值、无论是否带 `captchaToken`，都**一律先发一次真实提交**；只有 §8.4 响应分类器判出 `E_CAPTCHA`（业务码 7/22 或响应含 `aliyunwaf`）才进入 §13 兜底。**理由**：本地门控会产生**假阴性**——`useAliVerify=1` 的问卷可能本来能提交，却被引擎在本地直接判 `E_CAPTCHA`、一次请求都没发；而「总是先试」的代价只是多一次被拒的 HTTP 请求（**不产生答卷**）。`m.useAliVerify` 仅作展示/诊断，**不参与任何判定**。
1. 本地校验：`answers` 过滤掉 `value.isBlank()` 的项后为空 → `errorCode=E_EMPTY`、`message="没有可提交的答案"`（`httpStatus=0, raw=null`）。
2. 字段匹配（第 7 章）→ 失败 → `errorCode=E_UNMATCHED`、`message="字段未匹配：<明细>"`。
3. `submitdata = WjxSubmitCodec.encodeSubmitData(pairs)`。
4. 构造 URL：`m.submitUrl` 追加查询参数（都用 `URLEncoder.encode(v,"UTF-8")`），**顺序无关**（ASP.NET 不校验）：
   - `&starttime=<urlenc(m.startTime)>`（仅当 `startTime` 非空白时携带）
   - **`&ktimes=<effectiveKtimes>`，其中 `effectiveKtimes = max(4, m.ktimes)`**（Lead 2026-09-22 裁定：下限取已验证值 4，见 §11 D6）
   - `&t=<System.currentTimeMillis()>&jqnonce=<urlenc(m.jqnonce)>&jqsign=<urlenc(jqSign(m.jqnonce, effectiveKtimes))>`
   - 当 `m.captchaType != null` 时追加 `&capt=<captchaType>`【JS，T3 已实测确认】
   `m.submitUrl` 必须以 https 开头且 host ∈ wjx.cn（含子域），否则 `E_URL`。
   （T3 实测页面构造顺序为 `starttime→cst→source→ktimes→capt→t→jqnonce→jqsign`；`cst`/`source` 语义未确认，**V1 不发送**。）

**ktimes 变更影响说明（下限 4 版本，务必与「下限 1」的旧稿区分）**：XOR key = `ktimes % 10`（0 时取 1）。下限取 **4** 后，**`ktimes=0/1/2/3` 的页面都会变成 4 → key 从 1 变为 4 → `jqsign` 也随之改变**（这是预期行为：URL 数值与签名必须同源）。实现必须用**同一个 `effectiveKtimes`** 生成 `&ktimes=` 与 `jqsign`，否则服务端校验失败。

   **证据（已验证的关键修复，不是保守猜测）**：V6 干净单变量 A/B（与 S0 只差 `ktimes` 0→4）在 `useAliVerify=0` 的问卷上返回 **业务码 10 = 提交成功**（`10〒/wjx/join/complete.aspx?...&joinid=127844297308`）；**下限取已验证值 4**（Lead 2026-09-22 裁定）：`ktimes=1` 从未测过，不拿未验证的值去赌。规则：`effectiveKtimes = max(4, m.ktimes)`，**不设上限**（页面原值更大时保持原值）。**不再使用「ktimes>0 即有效」这类推断措辞**——有效性的直接证据只有 `ktimes=4`。
5. POST body（`application/x-www-form-urlencoded; charset=UTF-8`）：
   - `submitdata=<urlenc(submitdata)>`（**唯一必发字段**）
   - **不发送** `starttime`（T3 实测它在 URL query 上，见第 4 步）
   - 若 `captchaToken` 非空（§13 兜底路径）：追加 `captchaVerifyParam=<urlenc(captchaToken)>`；**仅当 `m.sceneId` 非空时**再追加 `sceneId=<urlenc(m.sceneId)>`（缺省 → 不带该字段；本地不做必填校验，由服务端判定——Lead 2026-09-22 裁决）。
   - 若 `captchaToken` 为空：**绝不携带** `captchaVerifyParam`/`sceneId`，也**不补发 `rn`/`lct`/`jpm`/`cst`/`source`**。**理由已升级为实测（T11/V6）**：发送这些字段会把成功（业务码 10）变成 `7〒需要安全校验，请重新提交！`；其中 `cst`/`source` 已排除，`rn` 与 body 类字段为**嫌疑但尚未分离** —— 因此 V1 一律不发。 **V3/V4/V5 实测再次确认**：补发 `captchaVerifyParam`/`sceneId`/`rn` 会把结果从 10 推向 7 → **最小请求形态（V6）才是正确形态**；这条「不发」的决定是契约硬约束，不得为了「更保真」而补发。
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
| `ktimes` | 正则 `var\s+ktimes\s*=\s*(\d+)` | 默认 `0`（页面 JS 初始化为 0）；**提交时统一取 `max(4, ktimes)`**（Lead 裁定：下限取 V6 已验证值 4）。注意：0/1/2/3 → 4 会**改变 XOR key（1→4）从而改变 `jqsign`**，URL 数值与签名必须同源（见 §5.3 第 4 步） |
| `startTime` | `id="starttime"` 的 `value` 属性 | `""` |
| `captchaType` | 正则 `captchaType\s*=\s*['"]?(\d+)['"]?` | 若 `useAliVerify=1` 或 `needLoadAliVerify=1` → `2`；否则 `null`。**仅用于 `&capt=` 参数，不作门控** |
| `useAliVerify` | 正则 `var\s+useAliVerify\s*=\s*(\d+)` | 缺失 → `false`；`1` → `true`。**仅作展示/诊断，不作门控**（Lead 2026-09-22 改判：总是先尝试提交） |
| `cookies` | fetch 结束后 CookieManager 快照（name→value，跳过空值） | 空 Map |

### 6.5 分页问卷检测（V1 不支持，必须显式报错）

满足任一条件即返回 `errorCode=E_PAGED`、`message="该问卷为分页/逐题模式，暂不支持自动填写"`：
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

- R2/R3 命中多个题目 → `errorCode=E_UNMATCHED`，`message="字段「<field>」匹配到多个题目（<题号列表>），请改用题号"`。
- 全部规则未命中 → `errorCode=E_UNMATCHED`，`message="字段「<field>」未匹配到任何题目"`。
- 两个不同 `field` 命中同一题目 → `errorCode=E_UNMATCHED`，`message="多个字段指向同一题（题号 <N>）：<字段列表>"`。
- 任一失败 → **整次提交中止**（`ok=false, errorCode=E_UNMATCHED, httpStatus=0, raw=null`），不做「部分提交」。理由：缺字段的半份答卷会污染问卷数据，且服务端必答校验也会拒绝。
- 失败明细最多列 5 条，其余用「等 N 项」省略，保证 message 可读且不超长。

### 7.3 值解析（命中题目之后）

| 题型 | 值处理 |
|---|---|
| TEXT | 直接用 `escape(pair.value.trim())`；长度 > 3000 → `errorCode=E_LIMIT`、`message="答案超过 3000 字上限（题号 N）"` |
| SINGLE / DROPDOWN | 若 value 等于某 option.value → 用它；否则若等于某 option.label → 转成该 option.value；都不是 → `errorCode=E_UNMATCHED`、`message="题号 N 的取值「<value>」不在选项中"` |
| MULTI | 按 `\|` 切分，每段按 SINGLE 规则解析成 option.value；空段丢弃；结果为空 → `E_UNMATCHED`；最后 `parts.join("\|")` |
| MATRIX / SLIDER / OTHER | `errorCode=E_UNSUPPORTED`、`message="题号 N（<类型>）暂不支持自动填写"`（V1 明确不支持，不静默跳过） |

- 长度上限 3000 字符【JS】：`validateQ` 对 type=1/2 有 3000 字校验。
- 空 `value` 的 pair 在第 5.3 步已被过滤（不发送）。
- 本地**不做**必答校验（除「无任何答案」外）：服务端返回的校验文案原样进入 `E_REJECTED` 的人类文案部分。

---

## 8. 错误契约

### 8.1 两种失败通道

| 通道 | 使用场景 | 约定 |
|---|---|---|
| `Result.failure(WjxException)` | 只有 `fetch()` 用 | `WjxException.code` ∈ 错误码表；`WjxException.message` 是**纯人类文案** |
| `SubmitResult` | 只有 `submit()` 用 | 失败：`ok=false` + `errorCode` ∈ 错误码表 + `message` 纯人类文案；`submit()` 除 `CancellationException` 外不抛异常 |
| 成功 | 两者 | `fetch` → `Result.success`；`submit` → `ok=true`、`errorCode=null`、`message="提交成功"` |

**铁律**：机器可读信息只走 `errorCode` / `WjxException.code`，人类可读信息只走 `message`；**不得把错误码拼进 message**。

### 8.2 错误码与文案表（文案逐字实现，UI 只读码）

| errorCode | httpStatus | message（人类文案，逐字实现） | 触发条件 |
|---|---|---|---|
| `E_URL` | 0 | 链接无效：请填写 https://www.wjx.cn/vm/xxxx.aspx 形式的问卷链接 | URL 非 https / 非 wjx.cn / 无 shortId |
| `E_NETWORK` | 0 | 网络连接失败，请检查网络后重试 | DNS/连接/读超时/IO |
| `E_HTTP` | 实际码 | 问卷服务器返回异常（HTTP %d） | fetch/submit 非 2xx |
| `E_PARSE` | 200 | 问卷页面解析失败，可能是问卷已关闭或页面改版 | 无 jqnonce / 无题目 / 响应非 HTML |
| `E_PAGED` | 200 | 该问卷为分页/逐题模式，暂不支持自动填写 | 6.5 检测命中 |
| `E_CAPTCHA` | 实际码 | 该问卷开启了安全校验（阿里云验证码），纯接口无法提交 | **仅由响应判定**：业务码 `7`/`22`；或响应含 `aliyunwaf`/验证码特征（T3 实测样本：`7〒需要安全校验，请重新提交！`）。**本地不再有任何门控触发**（§5.3 第 0 步已废除） |
| `E_UNMATCHED` | 0 | 字段未匹配：<明细> | 第 7.2 节 |
| `E_EMPTY` | 0 | 没有可提交的答案 | 过滤空值后 answers 为空 |
| `E_LIMIT` | 0 | 答案超过 3000 字上限（题号 %d） | 文本超长 |
| `E_UNSUPPORTED` | 0 | 题号 %d（%s）暂不支持自动填写 | 命中 MATRIX/SLIDER/OTHER |
| `E_REJECTED` | 实际码 | 问卷服务端拒绝：<服务端文案> | 业务失败（HTTP 200 但响应非成功） |
| `E_UNKNOWN` | 实际码 | 未知错误：<摘要> | 兜底 |

`E_UNMATCHED` / `E_UNSUPPORTED` / `E_EMPTY` / `E_LIMIT` 是**本地错误**：`httpStatus = 0`、`raw = null`，且**不发出任何网络请求**。

> T3 实证（2026-09-22）：样本问卷（`useAliVerify=1`、`captchaType='2'`）的提交被服务端以业务码 `7` 拒绝，而 **HTTP 状态是 200** —— 这是「HTTP 200 ≠ 成功」的铁证，分类器必须按 §8.4 解析正文。同时注意：**`captchaType` 不是门控信号**（6 个问卷实测全为 `'2'`，见 §11.3）。

### 8.3 UI 文案映射（android-dev 用）

- `E_URL` → 输入框红字 + 「请检查链接」
- `E_NETWORK` → 「重试」按钮
- `E_CAPTCHA` → 「该问卷需要人机验证，无法自动提交」（终态，不提供重试）
- `E_UNMATCHED` → 跳到字段映射页，把明细里的字段高亮
- `E_REJECTED` → 原文展示服务端文案（例如「请输入正确的学号」）
- 其余 → 通用错误提示 + 原始 raw 可折叠查看（仅调试用；**不做 App 内日志页**，见 DESIGN.md 非目标）

### 8.4 响应分类器（`WjxResponseClassifier`，T4 实现，单点可改）

输入：HTTP 码 + 响应正文；输出：`SubmitResult`。按顺序判定（规则已按 T3 实测回填）：

1. HTTP 非 2xx → `errorCode=E_HTTP`、`message="问卷服务器返回异常（HTTP %d）"`、`raw`=正文前 8192 字符。
2. 正文为空 → `errorCode=E_PARSE`、`message="响应为空"`。
3. 正文含 `aliyunwaf`（忽略大小写）→ `errorCode=E_CAPTCHA`（与页面 JS 同款判定【JS】）。
4. **解析业务码 `code`（两种形态都要支持；协议层事实优先，不得只做关键词匹配——真实拦截响应里一个关键词都没有）**：
   - **裸码形态**（api-debug T11 实测：`ktimes=0` 时服务端回**裸 `22`**，无 `〒`、无文案）：若 `body.trim()` 匹配 `^\d{1,3}$` → `code` = 该数字；
   - **带文案形态**：以 `〒` 分割取首段并 trim → `code`。

   映射：
   - `code == "10"` **【服务端实测确认】** 或 `code == "11"` **【JS 逐字证据（T11 钉死），未经服务端实测】** → **成功**：`ok=true`、`errorCode=null`、`message="提交成功"`。JS 逐字证据：`@148535` `10==a` → 成功 UI + `complete.aspx`；`@156436` `if(11==a){…clearAnswer(),addtolog(f),…location.replace(f)}`（`n[1]`=跳转 URL、`n[3]`=joinid）；`@159634` `if(11==a)return;`；
   - `code == "7"` 或 `code == "22"` —— **两者同义**（都表示「要求安全校验」，都会弹阿里云验证码）：`hintinfo.js` 定义 `submit_need_validate2="需要安全校验，请重新提交！"`，且 `7==a` 与 `22==a` 两个分支都执行 `isCaptchaValid=false; useAliVerify=1; loadCaptchShow()`。**均已服务端实测确认**（7 = T3 真实响应 `7〒需要安全校验，请重新提交！`；22 = Lead 实测 `useAliVerify=0` 仍返回 22，且 T11 实测 `ktimes=0` 回**裸 22**）→ `errorCode=E_CAPTCHA`、`message="该问卷开启了安全校验（阿里云验证码），纯接口无法提交"`（**终态**：UI 不提供「重试」；服务端文案保留在 `raw`）；
   - 其他纯数字 **【默认规则】** → `errorCode=E_REJECTED`、`message="问卷服务端拒绝：" + 第二段`（无第二段则取正文前 200 字）。
5. 解析不出业务码（正文不含 `〒` 或首段非数字）→ 退回**关键词启发式**：含 `aliyunwaf`/`captcha`/`验证码`/`安全校验` → `errorCode=E_CAPTCHA`。
6. 仍不匹配：正文以 `<html` 开头 → `errorCode=E_PARSE`、`message="服务端返回页面而非结果"`；否则 → `errorCode=E_UNKNOWN`、`message="未知错误：" + 正文前 200 字`。

**测试向量（qa-build 直接照抄，用真实响应，不要造数据）**

| 输入正文 | HTTP | 期望 |
|---|---|---|
| `7〒需要安全校验，请重新提交！`（T3 真实响应） | 200 | `ok=false`、`errorCode=E_CAPTCHA`、`message` 含「安全校验」、`raw` 保留原文 |
| `10〒`（构造用例；业务码来自 JS `@148535` 成功分支） | 200 | `ok=true`、`errorCode=null`、`message="提交成功"` |
| `10〒/wjx/join/complete.aspx?activityid=P2M09FG&joinid=127844297308&sojumpindex=1&comsign=B1CA04F4DA75182824F45478C7B42CDAF5DA2878`（**V6 真实成功响应**） | 200 | `ok=true`、`errorCode=null`、`raw` 保留原文 |
| `22`（**裸码，无 `〒` 无文案**；T11 实测 `ktimes=0` 的真实响应） | 200 | `ok=false`、`errorCode=E_CAPTCHA`、`raw="22"` |
| `11〒https://www.wjx.cn/wjx/join/completemobile2.aspx?...`（构造用例；JS `@156436`） | 200 | `ok=true`、`errorCode=null` |
| `<html><script>...aliyunwaf...</script>` | 200 | `ok=false`、`errorCode=E_CAPTCHA` |
| ``（空正文） | 200 | `ok=false`、`errorCode=E_PARSE` |
| `5〒请输入正确的学号` | 200 | `ok=false`、`errorCode=E_REJECTED`、`message` 含「请输入正确的学号」 |

**映射表来源等级（Lead 要求标注，复测时按此优先级先验）**：

| 业务码 | 判定 | 来源等级 |
|---|---|---|
| `10` | 成功 | **服务端实测确认**（JS 成功分支 + 页面逻辑） |
| `7` | `E_CAPTCHA` | **服务端实测确认**（T3 真实响应 `7〒需要安全校验，请重新提交！`） |
| `11` | 成功 | **JS 逐字证据（T11：`@156436`/`@159634`）**，未经服务端实测 |
| `22` | `E_CAPTCHA` | **服务端实测确认**（Lead：`useAliVerify=0` 的问卷仍返回 22；T11：`ktimes=0` 回**裸 22**）+ JS 逐字证据（`submit_need_validate2`，与 7 同分支） |
| 其他数字 | `E_REJECTED` | 默认规则 |

Lead 2026-09-22 已批准 `11→成功`、`22→E_CAPTCHA`（要求与实测项区分来源等级，即上表）。映射表是**唯一改动点**，复测有出入只改一行。

**证据出处**：`tools/wjx-probe/evidence/` —— T11 业务码矩阵 + JS 逐字证据（`11-t11-conclusion.md`；其中「服务端强制二次校验 / 纯接口不可行」的结论**已作废**，以 V6 干净 A/B 为准）、`11-v1..v5` 与 **v6** 原始请求/响应。

**铁律：HTTP 200 绝不等于提交成功。** 问卷星用 200 返回业务错误（T3 实测：被验证码拦截时 HTTP=200、正文=`7〒需要安全校验，请重新提交！`）。成功判定必须走第 4 条；所有成功/失败判定集中在 `WjxResponseClassifier` 一个函数里。

---

## 9. 并发提交模型（`submit/SubmitCoordinator.kt`，T5，包 `com.wjx.autofill.submit`）

```kotlin
package com.wjx.autofill.submit

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
3. 组内流程：`fetch` → 失败则该组结果 = `SubmitResult(ok=false, httpStatus=0, message=ex.message, raw=null, errorCode=ex.code)`（`ex` 是 fetch 返回的 `WjxException`；码与人类文案分别落到 `errorCode`/`message`）；成功则 `submit`。
4. 组间隔离：一组失败**不取消**其他组；组内异常一律捕获（`CancellationException` 除外，必须向上抛）。
5. 结果按 `index` 升序返回，与模板 `groups` 顺序一致（不按完成时间排序）。
6. `onProgress(done, total)` 每完成一组调用一次，`done` 从 1 单调递增；回调在调用方 dispatcher 上执行。
7. 空组（`pairs` 全为空或过滤后无答案）→ 不发起请求，结果 = `SubmitResult(ok=false, httpStatus=0, message="没有可提交的答案", raw=null, errorCode=E_EMPTY)`（明确可见，不静默跳过）。
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

### 11.1 T3 实测结论（2026-09-22，api-debug 回填）

| # | 问题 | 实测结论 | 对契约的影响 |
|---|---|---|---|
| D1 | 验证码是否强制拦截 | **V6 已把两种情况分离**：`useAliVerify=1`（T3 样本）→ 服务端强制要求安全校验（`7〒需要安全校验，请重新提交！`）；`useAliVerify=0` + `ktimes≥4` + 不补发校验类字段 → **纯接口可成功**（V6 实测 `10〒/complete.aspx?joinid=127844297308`）。**裸 22 由 `ktimes=0` 触发，不是问卷属性** | 引擎：`useAliVerify==true` → 直接 `E_CAPTCHA`；`useAliVerify==false` 正常提交并由 §8.4 分类器兜底；§13 兜底仍是 `useAliVerify=1` 问卷的常规路径 |
| D2 | 成功响应前缀 | **T11 已钉死**：10/11 均成功（11 有 JS 逐字证据）；7/22 同义=要求安全校验；且**裸码无 `〒` 也按业务码解析**（实测 `ktimes=0` 回裸 22） | §8.4：10/11=成功，7/22=`E_CAPTCHA`，其余数字=`E_REJECTED`；已落地 |
| D3 | `starttime` 位置 | **在 URL query**（`&starttime=<urlenc>`），不在 body；服务端接受 | §5.3 第 4/5 步已改：query 携带，body 只发 submitdata |
| D4 | `&capt=2` | 确认在 URL 上；实测仍被码 7 拦下。`captchaVerifyParam`/`sceneId`（sceneId=`q0hcfsca`）属 POST body | §5.3 保留 `&capt`；**不发送** captchaVerifyParam/sceneId |
| D5 | 额外签名参数（rn/lct/jpm） | 本问卷均未出现（`window.rndnum`/`relsign` 未定义） | V1 只发 jqnonce/jqsign/ktimes/t/capt/starttime |
| D6 | `&ktimes=` 下限取值 | **已定案（Lead 2026-09-22）：`max(4, 页面ktimes)`** —— 下限取**已验证值 4**（V6 单变量 A/B：0→4 使同一 `useAliVerify=0` 问卷从裸 22 变为 `10〒` 成功，joinid=127844297308）；**不取未测的 1**。**影响**：0/1/2/3 → 4 会改变 XOR key（1→4）→ **`jqsign` 随之改变**，URL 与签名必须同源（§5.3 第 4 步）。**已撤销**「ktimes>0 即有效」的推断措辞 | 已同步 §5.3/§6.4 + 引擎 + 单测 |

补充实测：题目列表来自服务端渲染 HTML（无需 XHR）；页面构造的 URL 参数顺序为 `starttime→cst→source→ktimes→capt→t→jqnonce→jqsign`（顺序无关；`cst`/`source` 语义未确认，V1 不发送）。

### 11.2 由此产生的两条强制规则

1. **`E_CAPTCHA` 是终态**：UI 不提供重试（重试必然同样被拦），并在会话内记住该 URL 的结论，避免重复无效提交。
2. **不做本地验证码门控（Lead 2026-09-22 改判，废除上一版「`useAliVerify==true` 硬门控」）**：无论 `useAliVerify` 为何值都**先发一次提交**，只有响应业务码 7/22（或 aliyunwaf）才走 §13 兜底。理由：本地门控的**假阴性**代价（把本可提交的问卷判死，一次请求都不发）大于多一次被拒请求的代价（**不产生答卷**）。`useAliVerify` 仅作展示。

**以上全部是 §5.3/§8.4 的行为调整；第 2 章签名只增加了 Lead 批准的 additive 字段/参数（`errorCode`、`useAliVerify`、`captchaToken`，均带默认值），其余签名不变。**

### 11.3 T3.5 证据：门控信号必须是 `useAliVerify`（2026-09-22，api-debug 实测 6 个问卷）

| 事实 | 证据 |
|---|---|
| 6 个问卷（5 个公开 + 本样本）的 `captchaType` **全部为 `'2'`** | 只读 GET 页面 |
| 唯一区别是 `useAliVerify`：样本 = 1（提交被拦），5 个公开问卷 = 0（放行） | 实测提交 + JS 逻辑 |
| `wjx_captch.js`：`if(!h.useAliVerify \|\| isCaptchaValid) return !0;`（`loadCaptchShow` 首行）→ `useAliVerify=0` 直接放行 | JS 反混淆 |
| `jqmobo2.js`：`window.useAliVerify && !isCaptchaValid ? loadCaptchShow() : (进入提交函数)` | JS 反混淆 |
| `&capt=<captchaType>` 与是否强制校验**无关**（所有问卷都带 `capt=2`） | JS + 实测 |

**结论（normative）**：
1. 门控只用 `useAliVerify`；`captchaType` 仅用于构造 `&capt=` 参数。
2. **已废除（Lead 2026-09-22 改判）**：不再做本地门控；`useAliVerify` 只作展示，提交一律先发，响应为 7/22 才走兜底。
3. `useAliVerify` 解析不到（页面改版）→ 默认 `false`，靠 §8.4 响应分类器兜底（此时 `captchaType` 也**不得**作为门控）。
4. **唯一例外**：用户已批准「仅验证码环节」的 App 内 WebView 兜底（§13），此时 `captchaToken != null` 则跳过第 0 步门控。
5. **修正（V6 单变量 A/B，2026-09-22）：`useAliVerify=0` 的问卷可以纯接口提交。** 此前观察到的「`useAliVerify=0` 仍返回裸 22」是 **`ktimes=0` 触发的风控指纹**，不是问卷属性——唯一变量 `ktimes: 0→4` 后，同一问卷返回 `10〒/complete.aspx?joinid=127844297308`（成功）。正确口径：① `useAliVerify` 只是页面**初始值 / 快速门控**；② `useAliVerify=0` + `ktimes≥4` + **不补发 `rn`/`captchaVerifyParam`/`sceneId` 等字段** → 可纯接口成功；③ `useAliVerify=1` 的问卷仍会被要求安全校验，走 §13 兜底（对这类问卷是常规路径）；④ §8.4 响应分类器**仍然必需**（服务端可在响应里返回 7/22）。

---

## 12. 实现者检查清单（DoD）

**T4（api-debug）**
- [ ] `wjx/` 无 `android.*`、无 `org.json`、无新依赖
- [ ] `WjxSubmitCodec` 通过第 3.4 节全部测试向量
- [ ] `fetch` 对样本问卷返回 3 道 TEXT 题、题号 1/2/3、题干正确、`jqnonce` 非空
- [ ] `submit` 对样本问卷的 3 个必填字段能构造出 `1$..}2$..}3$..` 形态的 submitdata（可用 fake 网络层断言）
- [ ] `submit` 除 `CancellationException` 外不抛异常；本地错误不发网络请求
- [ ] 分页问卷检测（6.5）有单测 fixture
- [ ] 响应分类器（8.4）用**真实响应**做向量：`7〒需要安全校验，请重新提交！`→E_CAPTCHA、`10〒`→成功、`11〒`→成功（JS 推导）、`22〒`→E_CAPTCHA（JS 推导）、`5〒请输入正确的学号`→E_REJECTED、aliyunwaf→E_CAPTCHA、空正文→E_PARSE、非 〒 且无关键词→E_UNKNOWN
- [ ] **无本地门控单测**：`useAliVerify=true` 且 `captchaToken=null` 时 `submit` **仍发出一次 POST**（假阴性防护）；仅当响应业务码 7/22 才返回 `E_CAPTCHA`
- [ ] §13 兜底契约：`fetch(url, cookies)` 注入语义、`submit(..., captchaToken)` 分支、`sceneId` 解析（见 §13.10）
- [ ] 单选/多选/下拉的值解析（7.3）有单测 fixture

**T5（android-dev）**
- [ ] `templates.json` 读写符合 4.2–4.4（含原子写、损坏备份、clamp、id 去重）
- [ ] `SubmitCoordinator` 并发上限 = clamp(concurrency,1,5)，每组独立 fetch，结果按组序返回
- [ ] UI 用 `result.errorCode` 分支（`null` = 成功），不用文案匹配
- [ ] 未匹配字段在 UI 可见（高亮 + 明细），不静默丢弃
- [ ] Manifest `allowBackup=false`（或 dataExtractionRules 排除 templates.json）

**T6（qa-build）**
- [ ] 第 3.4 节测试向量全部落地
- [ ] 构造 HTML fixture 覆盖 6.2 的每一种题型判定
- [ ] 版本比较、模板导入导出（含 schemaVersion=2 拒绝、坏 JSON 备份）单测
- [ ] 并发上限断言（fake 层记录最大同时在线请求数 ≤ concurrency）
- [x] `testImplementation("org.json:json:20231013")` 已由 Lead 加入 `android/app/build.gradle.kts`（`config/` 用 MiniJson，`update/AppUpdater.kt` 用 org.json，故该测试依赖仍必要）
- [ ] 用例：`sceneId` 缺省时提交 body 不含 `sceneId` 字段（令牌为空/非空两种情形）

---

## 13. 验证码兜底流程契约（用户已批准：仅验证码环节用 App 内 WebView）

> 背景：`useAliVerify=1` 的问卷（如 T3 样本）纯 HTTP 必被业务码 7 拦截，需要人机验证；`useAliVerify=0` 的问卷在 `ktimes≥4`（契约下限 4）且不补发校验类字段时**可纯接口提交**（V6 实测成功，见 §0/§5.3）。**用户已批准**：**仅**「人机验证」这一步交给 App 内 WebView 由用户人工完成；**问卷数据的提交永远由纯 HTTP 接口完成**。
> **用户已批准**：**仅**「人机验证」这一步交给 App 内 WebView 由用户人工完成；**问卷数据的提交永远由纯 HTTP 接口完成**。
> 本兜底**不绕过风控**，只是把「人机验证」这一步交还给人。

### 13.1 触发条件与入口

| 项 | 规定 |
|---|---|
| 触发 | `submit` 返回 `errorCode == E_CAPTCHA`（**仅由响应判定**：业务码 7/22 或响应含 `aliyunwaf`；本地已无门控） |
| 入口 | **必须用户显式点击**「人工验证后重试」按钮才启动 WebView；**绝不自动打开**。按钮显示条件见 §13.5；点击时针对**第一个「`E_CAPTCHA` 且尚未兜底」的组**（多组需验证时逐组处理，全部处理完按钮隐藏） |
| 次数 | **每组最多 1 次**（`CAPTCHA_FALLBACK_PER_GROUP = 1`），**不设跨组硬上限**。**消耗判据（normative，Lead 最终口径）**：仅当**验证环节实际启动过**（WebView 成功唤起验证码并进入等待/收割；含「超时未取得令牌」与「取得令牌但提交仍失败」）才计入该组；**用户主动取消不消耗**（用户改主意不是失败，且每次都要亲手点按钮，不存在自动循环）；结构性失败（无 WebView / 页面改版 / 无验证入口）**不消耗但直接终态**——按钮对全部未兜底组立即隐藏。计入后该组不再显示入口，其他组不受影响 |
| 终态 | 用户不点则保持 `E_CAPTCHA` 终态文案，不提供普通「重试」（重试必然同样被拦） |

### 13.2 时序（括号内为负责层）

1. （engine）纯 HTTP `fetch` → `SurveyModel`（含 `useAliVerify`、`captchaType`、`cookies`、`sceneId`）。
2. （engine）`submit` 返回 `E_CAPTCHA`（§8.4 响应分类器；**本地无门控，请求已实际发出**）。
3. （ui）展示终态卡片 + 「人工验证后重试」按钮。
4. （ui）用户点击 → 启动 `CaptchaActivity`（WebView），入参 = `model.url` + `model.cookies`。
5. （ui）按 §13.3 把引擎 cookie 注入 WebView 的 `CookieManager`，再 `loadUrl(model.url)`（真实问卷 URL）。
6. （ui）`onPageFinished` 后注入**固定常量脚本** `JS_RAISE_CAPTCHA`（§13.7），调用页面自身的 `loadCaptchShow()` **唤起阿里云验证码控件**。
   - 返回 `NO_FN`（页面没有该函数）→ 直接失败：`E_CAPTCHA`「验证页面结构已变化，无法自动唤起验证码」。
   - **禁止**点击 `#ctlNext`/`#SubmitBtnGroup` 等提交按钮来"顺带"唤起验证码（会触发真实提交，见 §13.4）。
7. （user）用户在 WebView 中完成一次阿里云验证（滑块/点选）。
8. （ui）轮询 `JS_HARVEST`（每 500 ms）收割 `window.captchaVerifyParam` 与 `window.captchaSceneid`；超时 180 s。
9. （ui）收割成功 → 按 §13.3 读回 WebView 全部 cookie → 关闭 Activity，返回 `CaptchaHarvest`（§13.6）。
10. （engine）**用同一会话 cookie 重新抓页面**：`client.fetch(model.url, cookies = harvest.cookies)` → 得到**新的** `SurveyModel`（新 `jqnonce`/`starttime`/`ktimes`）。
    - 为什么必须重抓：`jqnonce`/cookie 与提交会话绑定，验证过程会改写会话 cookie，沿用旧 nonce 会被判失效。
11. （engine）`submitter.submit(newModel, answers, captchaToken = harvest.captchaVerifyParam)` → POST body 携带 `captchaVerifyParam` + `sceneId`（§5.3 第 5 步）。
12. （ui）无论成功/失败：清理 WebView 会话 cookie（§13.3 第 4 条）。成功 → `captcha_retry_ok`；再次 `E_CAPTCHA` → `captcha_retry_failed`（含原因）或 `captcha_expired`。**机会结算**：已实际发起（超时 / 取得令牌后仍失败）→ 计入该组机会，该组不再显示入口（其他组不受影响）；**用户取消 → 不计入**（按钮保留）；结构性失败 → 全部入口立即隐藏。

**令牌时效（normative）**：`captchaVerifyParam` 单次有效且短命 → 第 8 步收割成功后，**必须在 60 秒内完成第 10–11 步**（常量 `CAPTCHA_TOKEN_TTL_MS = 60_000`）；超时按失败处理并提示重新验证。

### 13.3 会话一致性（Cookie 双向注入，否则令牌必然不匹配）

引擎侧：`HttpWjxSurveyClient` 每实例持有独立 `java.net.CookieManager`；`SurveyModel.cookies` 是 fetch 结束时的快照。
WebView 侧：`android.webkit.CookieManager.getInstance()`（全局单例）。

| 方向 | 规则 |
|---|---|
| ① 引擎 → WebView（打开验证页之前） | **基准 origin = 问卷 URL 的 origin**（`scheme://host[:port]/`，host 统一小写；解析失败兜底 `https://www.wjx.cn/`），由纯函数 `submit/CaptchaHarvest.kt` 的 `CookieHeader.originOf(url, fallback)` 计算；`CaptchaActivity` 用它算出 `cookieBase`，**方向①与方向④共用同一个值**。对 `model.cookies` 每一条执行 `CookieManager.getInstance().setCookie(cookieBase, "k=v")`，全部写完后 `flush()`；**必须逐条**（不要手工拼 `Cookie` 头塞进 `loadUrl`）。**为什么不能写死 `https://www.wjx.cn/`**：cookie 绑在 www 主机上时，`v.wjx.cn` 等**子域验证页收不到引擎会话 cookie** → 方向①失效（用户新样本 `https://v.wjx.cn/vm/P2M09FG.aspx` 正是子域；契约 §5.1 已接受任意子域） |
| ② WebView → 引擎（收割令牌之后） | `CookieManager.getInstance().getCookie(model.url)` 取回 `"k=v; k2=v2"`，解析成 `Map<String,String>`，作为 `fetch(url, cookies = map)` 的入参；引擎侧把每条注入自己的 `CookieManager.cookieStore`（`path="/"`、`domain=".wjx.cn"`）后再 GET |
| ③ 引擎侧注入语义 | `fetch(url, cookies)` 的 `cookies` **非空**时：先清空该实例 cookieStore，再逐条注入，再 GET；**空 Map**（默认）= 保持首次 fetch 语义不变（源码级兼容既有调用） |
| ④ 清理（结束即清） | 兜底流程结束后（成功/失败/取消），把 **`cookieBase`（= 方向①的同一个 origin）** 下 WebView 的会话 cookie 逐条置空（`setCookie(cookieBase, "k=; Max-Age=0")`）+ `flush()`。**不得**调用 `removeAllCookies()`（全局单例，会误伤其他会话）；理由：验证会话是一次性的，留着会破坏「每组独立会话」契约 |

**只做单向注入是错的**：只做 ① → 验证令牌来自 WebView 会话、提交来自引擎会话；只做 ② → 验证页看不到引擎的 `acw_tc` 等会话 cookie。两者都会让服务端继续返回业务码 7。

### 13.4 硬边界：绝不把问卷数据交给页面提交

**允许注入 WebView 的 JS 只有两个固定常量**（§13.7）：① 唤起验证码控件；② 读取两个令牌全局变量。**除此之外一律禁止**。

- 禁止在 WebView 中填写任何表单字段（`q1`/`q2`/… 一律不动）。
- 禁止点击/触发任何提交入口：`#ctlNext`、`#SubmitBtnGroup`、`#divSubmit`、页面 submit/ajax 函数。
- 禁止把 `answers`/`AnswerPair`/`submitdata` 以任何形式（URL、JS 字符串、JS Bridge、localStorage）传给 WebView。
- 数据提交**永远**由 `WjxSubmitter`（`HttpURLConnection`）完成。
- 代码审查判据（可机械检查，**与实现形态一致**）：`evaluateJavascript` **调用点恰好 1 个**（`CaptchaActivity` 私有 helper `evaluate(script)` 内）；脚本**恰好 2 个固定常量**（`JS_RAISE_CAPTCHA`/`JS_HARVEST`）；**无任何动态拼接**（不得出现拼接变量的 `evaluateJavascript`/`loadUrl("javascript:…")`）；**真实 `@JavascriptInterface` 注解 0 个**。

> 这条边界就是「纯接口填表」与「模拟前端操作」的分界线：**验证码交给人，数据提交留给引擎。**

### 13.5 失败与降级（全部保持 `E_CAPTCHA` 终态，不新增错误码）

> **文案唯一真源**：`android/app/src/main/res/values/strings.xml`。本表「文案」列逐字引用其中的资源（README/USAGE 亦引用同一批），**不要在契约里另写一套措辞**。

**UI 专属文案（不属错误表，同样以 strings.xml 为准）**：`captcha_title`「人机验证」、`captcha_hint`、`captcha_status_loading`/`captcha_status_waiting`/`captcha_status_done`、`captcha_retrying`「正在用验证凭证重试该组…」、`captcha_retry_ok`「验证通过，该组已提交成功」。（错误表覆盖 9 个 `captcha_*` 资源 + UI 专属 7 个 = 共 16 个，与 strings.xml 一一对应。）

| 场景 | errorCode | 文案（strings.xml 资源名 + 逐字文本） | 该组入口状态 |
|---|---|---|---|
| 设备无 WebView/内核不可用 | `E_CAPTCHA` | `captcha_no_webview`「设备无法打开验证页面，请在浏览器中手工填写该问卷」 | 不消耗，但**全部未兜底组立即隐藏入口**（终态） |
| 页面无 `loadCaptchShow`（改版） | `E_CAPTCHA` | `captcha_structure_changed`「验证页面结构已变化，无法自动唤起验证码」 | 不消耗，但**全部未兜底组立即隐藏入口**（终态） |
| 唤起验证码失败（其他 JS 异常） | `E_CAPTCHA` | `captcha_raise_failed`「无法唤起验证码：%1$s」 | 不消耗，但**全部未兜底组立即隐藏入口**（结构性终态） |
| 用户取消/返回 | `E_CAPTCHA` | `captcha_cancelled`「已取消人工验证」 | **不消耗**（按钮保留，可再次点击） |
| 180 s 未收割到令牌 | `E_CAPTCHA` | `captcha_no_result`「未检测到验证结果，请重试」 | 消耗该组 1 次机会 |
| 令牌为空/空白 | `E_CAPTCHA` | `captcha_no_result` | 消耗该组 1 次机会 |
| 收割后 >60 s 才提交，或服务端再次返回 7/22 | `E_CAPTCHA` | `captcha_expired`「验证已过期，请重新验证」 / `captcha_retry_failed`「验证后仍然失败：%1$s」 | 消耗该组 1 次机会 |
| 该组已用过 1 次机会 | `E_CAPTCHA` | `captcha_exhausted`「该组已尝试过人工验证，仍未成功」（单次语义；仅状态不同步的守卫 toast，正常路径按钮已隐藏） | 该组隐藏，其他组不受影响 |

**UI 判定规则（禁止文案匹配）**：按钮文案 = `captcha_fallback_button`「人工验证后重试」。当**存在任一**满足以下全部条件的结果时显示按钮：① `errorCode == E_CAPTCHA`；② **该组未兜底过**（`captchaRetriedGroups` 不含该 index）；③ 未被结构性失败禁用；④ 当前不在提交中。点击时取**第一个**这样的组。**结算规则**：仅当本次已实际发起（超时 / 取得令牌后仍失败）才把该组加入 `captchaRetriedGroups`；**用户取消不加入**；结构性失败 → 全部未兜底组一次性加入（入口立即隐藏）。按钮继续为其余未兜底组显示，全部处理完 → 隐藏。判定只看 `errorCode + 已兜底组集合`。

### 13.6 签名与数据契约（Lead 2026-09-22 已批准的 additive 变更）

```kotlin
// §2 冻结签名随之更新（additive，均带默认值；既有调用 fetch(url) / submit(m, answers) 源码级兼容）
interface WjxSurveyClient {
    suspend fun fetch(url: String, cookies: Map<String, String> = emptyMap()): Result<SurveyModel>
}
interface WjxSubmitter {
    suspend fun submit(m: SurveyModel, answers: List<AnswerPair>, captchaToken: String? = null): SubmitResult
}
```

- **实现类必须同步改签名**（Kotlin 接口方法带默认参数不会自动兼容 `override`）：`HttpWjxSurveyClient.fetch`、`HttpWjxSubmitter.submit` 都要写出完整参数表。
- `captchaToken` 语义：阿里云 `captchaVerifyParam` 原文（**单次有效**）；`null`/空白 = 无令牌 → §5.3 第 0 步门控生效；非空 = 跳过门控并把 `captchaVerifyParam`+`sceneId` 写入 POST body。
- **`SurveyModel.sceneId: String? = null`（Lead 2026-09-22 已批准，additive）**：引擎在 `fetch` 时从页面解析（`captchaSceneid` 变量/验证码初始化配置）；提交时 `captchaToken` 非空且 `sceneId` 非空才写入 body，**缺省则不携带该字段**（本地不做必填校验，由服务端判定）。理由：sceneId 是协议层机器数据，塞进字符串会违背「机器码只走结构化字段」原则。

UI 侧（T5，`ui/CaptchaActivity.kt`）：
```kotlin
data class CaptchaHarvest(
    val captchaVerifyParam: String,
    val sceneId: String,
    val cookies: Map<String, String>,   // WebView 会话 cookie 全量快照
    val harvestedAtMillis: Long,
)
```
`CaptchaActivity` 返回 `CaptchaHarvest?`（`null` + `reason` 表示取消/失败），由 ui 编排 §13.2 的第 10–12 步。

### 13.7 注入脚本常量（逐字使用，禁止改写/拼接）

```kotlin
// ① 唤起验证码：只调用页面自身的函数，绝不触碰提交入口
internal const val JS_RAISE_CAPTCHA =
    "(function(){try{if(typeof loadCaptchShow==='function'){loadCaptchShow();return 'RAISED';}" +
    "if(window.loadCaptchShow){window.loadCaptchShow();return 'RAISED';}return 'NO_FN';}" +
    "catch(e){return 'ERR:'+e;}})()"

// ② 收割令牌：只读两个全局变量，返回值是 JSON 字符串
internal const val JS_HARVEST =
    "(function(){return JSON.stringify({p:(window.captchaVerifyParam||'')," +
    "s:(window.captchaSceneid||'')});})()"
```
若页面改版导致这两个全局变量改名：兜底失败并如实提示（**不得**用正则去 HTML 里"猜"令牌）。

### 13.8 WebView 安全配置（normative）

| 配置 | 值 | 理由 |
|---|---|---|
| `javaScriptEnabled` | `true` | 页面与验证码控件需要 |
| `domStorageEnabled` | `true` | 阿里云验证码可能使用 localStorage |
| `allowFileAccess` / `allowContentAccess` | `false` | 不需要本地文件能力 |
| `setSupportMultipleWindows` | `false` | 防止弹窗脱离管控 |
| `javaScriptCanOpenWindowsAutomatically` | `false` | 同上 |
| `mixedContentMode` | `MIXED_CONTENT_NEVER_ALLOW` | https-only |
| `setAcceptThirdPartyCookies` | `true` | 验证码来自阿里云域，需要第三方 cookie |
| `shouldOverrideUrlLoading` | 只放行 `https` + host ∈ `wjx.cn`（含子域）；其他一律 `return true`（拦截，不跳外部浏览器） | 防开放重定向/钓鱼 |
| JS Bridge | 真实 `@JavascriptInterface` 注解 **0 个**（收割用 `evaluateJavascript` 轮询即可；注释里提到不算） | 最小攻击面 |
| 明文流量 | 全局 `usesCleartextTraffic=false` 已覆盖 | — |
| 生命周期 | `CaptchaActivity` 为普通 Activity（非 `exported`）；`onDestroy` 中 `webView.destroy()` + 清 cookie | 防泄漏 |

### 13.9 端到端验证价值（Lead 要求写清，交付文档必须引用）

- 用户自己的问卷 `useAliVerify=1`，服务端**必然**返回业务码 7 → **纯 HTTP 路径在真实提交上永远拿不到成功响应**。
- 因此 **§13 兜底流程是本项目唯一的端到端验证手段**：由用户在自己的设备上点一次验证码 → 引擎带令牌提交 → 观察到业务码 `10`（成功）。
- 若用户不执行这一步，项目只能交付「解析 / 匹配 / 编码 / 分类 / 兜底时序」的**单测级证据**，端到端成功**无法证明**。
- 交付文档（README/USAGE「已知限制」）必须原样写明这一点，叙事保持「不绕过风控，只把人机验证交还给人」。

### 13.10 §13 验收清单（DoD）

**T4（api-debug）**
- [ ] `fetch(url, cookies = emptyMap())`：空 Map 行为与旧版完全一致；非空 Map 时清空并注入后再 GET
- [ ] `submit(m, answers, captchaToken = null)`：`captchaToken` 非空 → 跳过第 0 步门控，body 带 `captchaVerifyParam`+`sceneId`；为空 → 绝不携带这两个字段
- [ ] `SurveyModel.sceneId` 解析（缺失 → `null`）；令牌非空且 `sceneId` 非空 → body 带 `sceneId`；`sceneId` 缺省 → body **不含** `sceneId` 字段（不本地拦截）
- [ ] 单测：无论 `useAliVerify` 与 `captchaToken` 取值，`submit` 都**发出一次 POST**（无本地门控）；`captchaToken` 非空时 body 含 `captchaVerifyParam`（及非空时的 `sceneId`）

**T5（android-dev）**
- [ ] `CaptchaActivity`：§13.2 时序、§13.3 双向 cookie、§13.5 降级表、§13.7 两个脚本常量逐字
- [ ] 代码审查：`ui/` 的 `evaluateJavascript` 调用点 1 个且只接受 2 个固定常量、无动态拼接、无 `@JavascriptInterface` 传数据、无点击提交入口
- [ ] 兜底按钮出现/隐藏严格按 `errorCode + captchaRetriedGroups`（每组 1 次；**取消不消耗**，超时/失败消耗），点击针对第一个未兜底组，不用文案匹配
- [ ] Cookie 注入/清理基准 = **问卷 URL 的 origin**（`CookieHeader.originOf`，含子域如 `v.wjx.cn`；方向①与④同源），**不得写死 `https://www.wjx.cn/`**

**T6（qa-build）**
- [ ] 用 fake client/submitter 跑通 §13.2 第 10–12 步的编排（含：每组最多 1 次、**取消不消耗而超时/失败消耗**、多组逐组处理、结构性失败按钮立即隐藏、该组用完后不再给入口）
- [ ] 静态检查：`ui/CaptchaActivity` 中 `evaluateJavascript` **调用点 1 个**、脚本**仅 2 个固定常量**、**无动态拼接**、**无 `@JavascriptInterface`**（qa-build 已实现为 AUDIT=PASS）
- [ ] 用例：`sceneId` 缺省（`null`）时提交 body 不含 `sceneId` 字段（`captchaToken` 为空与非空两种情形都覆盖）

---

## 变更记录

| 日期 | 变更 | 批准 |
|---|---|---|
| 2026-09-22 | 初版：冻结签名 + codec 规范 + templates schema + 网络/解析/匹配/错误/并发契约 | Lead（签名冻结）/ architect（其余） |
| 2026-09-22 | **Lead 裁决**：`SubmitResult` 增加 additive 字段 `errorCode: String? = null`；删除 `SubmitErrorCode.format()/of()`；机器码不再拼进 message。改动章节：§2、§2.1、§2.2、§5.3、§6.5、§7.2、§7.3、§8.1–8.4、§9、§12 | Lead |
| 2026-09-22 | **Lead 裁决**：`SubmitCoordinator` 归属由 `ui/` 改为新包 `com.wjx.autofill.submit/`（编排不属于 UI），仍归 T5 | Lead |
| 2026-09-22 | **T3 实测回填**：验证码强制拦截（码 7）、starttime 在 query、capt=2 确认、无额外签名参数；§0/§5.3/§8.2/§8.4/§11/§12 同步 | api-debug（证据）/ architect（落文） |
| 2026-09-22 | **Lead 裁决（分类器）**：`<业务码>〒<文案>` 先解析业务码——`10`=成功、`7`=E_CAPTCHA（终态）、其他数字=E_REJECTED、解析不出才退回关键词启发式、最后 E_UNKNOWN；§8.1/§8.2/§8.4/§12 更新，并加入真实响应测试向量 | Lead |
| 2026-09-22 | **Lead 改判 + T3.5 证据**：`SurveyModel` 增 additive `useAliVerify: Boolean = false`；E_CAPTCHA 门控由 `captchaType` 改为 `useAliVerify`（6/6 问卷 captchaType='2'，用它判死全部问卷）；`11`→成功、`22`→E_CAPTCHA 并标注来源等级；新增 §11.3 证据表 | Lead / api-debug（证据）/ architect（落文） |
| 2026-09-22 | **T9 新增 §13 验证码兜底契约**（用户已批准仅验证码环节用 App 内 WebView）：§13.1 触发/入口、§13.2 12 步时序、§13.3 Cookie 双向注入、§13.4 硬边界（绝不把数据交给页面提交）、§13.5 降级表、§13.6 additive 签名（fetch/submit）+ `sceneId` 提案、§13.7 注入脚本常量、§13.8 WebView 安全配置、§13.9 端到端验证价值、§13.10 DoD | 用户批准 / Lead 指派 / architect 落文 |
| 2026-09-22 | **Lead 改判（两条）**：① **取消 `useAliVerify` 本地门控**，改为「总是先尝试提交，只有响应 7/22 才走兜底」（避免假阴性）；② `&ktimes` 下限取**已验证值 4**（`max(4,·)`），并更正影响说明：0/1/2/3→4 会改变 XOR key（1→4）→ **`jqsign` 随之改变**，URL 与签名必须同源。§5.3/§6.4/§8.2/§11.1/§11.2/§11.3/§12/§13.1/§13.2/§13.10 同步 | Lead / architect |
| 2026-09-22 | **§13.3 Cookie 基准改为问卷 URL 的 origin**（`CookieHeader.originOf(url, fallback)`；方向①注入与④清理同源），修复写死 `https://www.wjx.cn/` 导致 `v.wjx.cn` 子域验证页收不到会话 cookie 的缺陷；§13.10 T5 加静态检查项 | android-dev（证据）/ Lead（批准）/ architect |
| 2026-09-22 | §0 补用户佐证（微信扫码填写未弹人机验证）；§5.3 第 5 步补 V3/V4/V5 实测（补发校验字段会把 10 推向 7 → 最小请求形态才是正确形态） | Lead / api-debug / architect |
