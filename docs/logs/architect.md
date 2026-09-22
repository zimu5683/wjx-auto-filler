# architect 节点日志（T2 设计文档与冻结接口契约）

> 成员：architect｜模型：opencode-go / deepseek-v4.1-flash
> 写作用域：docs/DESIGN.md、docs/API-CONTRACT.md、docs/logs/architect.md（未越界写任何其他文件）

## 时间线

### 2026-09-22 00:5x — 领取任务
- 动作：`team_task_list` → `team_task_get(task-2)` → `team_task_update(claim, expected_revision=1)`
- 结果：task-2 归 architect，revision=2，status=in_progress
- 证据：team_task_list owner=architect 返回 task-2 in_progress

### 00:5x — 读工程骨架与参考实现（只读）
- 动作：读 `android/app/build.gradle.kts`、`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`version.properties`、`AndroidManifest.xml`、`MainActivity.kt`、`.gitignore`
- 关键事实：minSdk 24 / targetSdk 35 / 无 abiFilters / viewBinding / lint abortOnError / 依赖含 zxing 3.5.3 + CameraX 1.4.1 / release 签名走 WJX_KEYSTORE_* 环境变量，缺失回退 debug
- 动作：读 `yikou-light-food-server/.../AppUpdater.kt`（146 行）与 `.github/workflows/android.yml`（161 行）
- 结论：更新逻辑与 CI 结构可直接对齐（GitHub Releases API → 版本比较 → 选 .apk → FileProvider 安装 → 失败回退浏览器；tag 必须等于 v<versionName>）

### 01:0x–01:15 — 独立实测问卷星样本（只读，不写工程文件）
命令（`$TMPDIR` 下操作，未写入仓库）：
```bash
curl -sS -L -A "<Android Chrome UA>" "https://www.wjx.cn/vm/Q0DQewW.aspx" -o wjx.html -D wjx.headers.txt
curl -sS -L -A "<Windows Chrome UA>" "https://www.wjx.cn/vm/Q0DQewW.aspx" -o wjx-pc.html
curl -sS -L "https://image.wjx.cn/joinnew/js/jqmobo2.js?v=7558" -o jqmobo2.js
```
实测结果（均写入 API-CONTRACT.md 并标注证据等级）：
1. HTTP 200，`Content-Type: text/html; charset=utf-8`，标题「测试」；`Set-Cookie: acw_tc / .ASPXANONYMOUS / jac295759055 / SERVERID`
2. 表单：`<form id="form1" method="post" action="https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW">`；隐藏域 `starttime="2026/9/22 0:27:37"`、`source="directphone"`、`hfAnswerData`
3. `var jqnonce = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6"`（页面第 33 行）
4. 题目：`div.field.ui-field-contain[topic][type]`，共 3 题，全部 `type='1'`（单行文本）：姓名（例：XXX）/ 学号(例：XXXXXXXXXXX）/ 班级（例：生物XX），input name=q1/q2/q3，均 req=1
5. 页面含 `useAliVerify =1`、`captchaType = '2'`、`needLoadAliVerify=1`；`IsOneQuestionPerPage = 0`（非分页）；手机 UA 与桌面 UA 均含全部 3 题
6. `jqmobo2.js`（274215 字节）反混淆确认：
   - `function dataenc(e){for(var t=ktimes%10,i=(0==t&&(t=1),[]),n=0;n<e.length;n++){var a=e.charCodeAt(n)^t;i.push(String.fromCharCode(a))}return i.join("")}`
   - `spChars=["$","}","^","|","!","<"],spToChars=["ξ","｝","ˆ","¦","！","＜"]`；`replace_specialChar` 按序 replace
   - `ktimes=0` 初始化；提交 URL 追加 `&jqnonce=&jqsign=&ktimes=&t=<ms>`，`2==captchaType` 时 `&capt=2`
   - POST data = `{submitdata:a}`（captchaType==2 时加 captchaVerifyParam/sceneId）
   - 序列化：`a=(a+=topic)+spChars[0]; a+=value`，项间 `spChars[1]`（即 `}`），按 topic 升序排序
   - 多选值用 `spChars[3]`（即 `|`）连接
   - 成功判定：`"10"==e.split("〒")[0]`；`aliyunwaf` 出现即为 WAF/验证码拦截
   - 文本题 3000 字上限校验
7. CameraX ABI 核对（验证 Lead 的更正）：`camera-core-1.4.1.aar` 的 `jni/` 含 arm64-v8a / armeabi-v7a / x86 / x86_64 各 2 个 .so → 「4 ABI 齐全 + 未设 abiFilters」才是通用包的判据，**不是**「APK 内无 .so」
8. 测试向量用 node 计算（`$TMPDIR/vec.mjs`）：escape 4 例、jqSign 4 例（nonce=样本实测值）、encodeSubmitData 4 例，全部写入契约 §3.4

### 01:1x — 落盘 docs/API-CONTRACT.md（646 行）
- 内容：冻结签名逐字 + §2.1 additive（WjxException/SubmitErrorCode）+ §2.2 字段语义表 + §3 codec 规范与测试向量 + §4 templates.json JSON Schema/示例/兼容策略 + §5 网络契约 + §6 解析契约（题型判定表/分页检测）+ §7 字段匹配（R1>R2>R3、歧义即失败）+ §8 错误契约（12 个错误码 + 逐字文案 + 响应分类器）+ §9 并发模型 + §10 版本策略 + §11 T3 决策表 + §12 DoD 清单
- 同时 `send_message` 通知 lead（含 3 个契约决策与 3 个契约风险 A/B/C）、android-dev（其索要的 4 项全部给到章节号）、api-debug（实测结论汇总）

### 01:1x — 落盘 docs/DESIGN.md（298 行）
- 内容：目标/非目标（明确不做多平台、App 内日志页、批量压测、验证码破解）、分层架构与依赖方向（ui→config/qr/update/wjx，wjx 为底座且无 android.*）、持久化（原子写/损坏回退/SAF 导入导出/schemaVersion 策略）、错误分类与用户文案表、跨设备兼容性（minSdk 24 / 无 abiFilters / 4 ABI 齐全（含 .so 修正）/ 无 GMS / 无 Termux / lint NewApi 门禁 / exported 显式 / edge-to-edge insets / 照片选择器 AndroidX 回退 / 分区存储 / 明文流量）、安全与隐私（本地存储/https-only/不采集/allowBackup=false/签名一致）、自动更新设计、扫码设计、构建发布与 2GB 内存约束、测试策略、风险表（R1 阿里云验证码为 go/no-go）

### 01:16 — 自检并修复 10 处缺陷
- 发现并修复（关键）：契约 §3.4 的 Kotlin 测试向量里 `$` 未转义（`"a$b"`、`"1$张三"` 会被 Kotlin 当作字符串模板 → **编译失败**），已全部改为 `\$` 并加显式提醒
- 补齐：§2.2 数据类字段语义表（含可空性、questions 按 topic 升序的契约）
- 澄清：URL 正则只锚定前缀（允许 query/fragment）；§4.4 第 13 条「导入=追加 / 内部保存=覆盖」措辞歧义
- 证据：grep 回读 173–193 行确认 `\$` 已生效

## 结论
- T2 产出：`docs/API-CONTRACT.md`（646 行）、`docs/DESIGN.md`（298 行）
- 冻结签名逐字未改；新增内容全部是 additive（WjxException、SubmitErrorCode、ImportReport、SubmitCoordinator、GroupOutcome/BatchReport），并在契约中标注「additive，不修改冻结签名」
- 待 Lead 拍板的 3 个决策（已按推荐值写入，否决即改文档）：message 前缀错误码 / WjxException / SubmitCoordinator 归属 ui 包
- 已上报的契约风险：A) 单测 org.json 需补 testImplementation；B) T6「APK 内无 .so」判据与实测矛盾；C) 样本问卷只有填空题，单选/多选/矩阵仅单测覆盖
- 阻塞项：无。T4/T5 的契约前置已解除。

## 补充：Lead 裁决与 T3 回填后的契约修订（01:20–01:35）

### 裁决 1（否决 message 前缀，改 additive 字段）
- `SubmitResult` 增加 `errorCode: String? = null`；`SubmitErrorCode` 只保留常量，删除 `format()/of()`
- 改动章节：§2 冻结签名、§2.1、§2.2、§5.3、§6.5、§7.2、§7.3、§8.1–8.4、§9、§12；DESIGN.md §4.1 同步
- 自检发现并修复：§8.1 表格仍留着被否决的 `"<CODE>|文案"` 写法（第一轮批量编辑漏改），已改为 errorCode 通道说明

### 裁决 2（SubmitCoordinator 归属）
- 从 `ui/` 迁到新包 `com.wjx.autofill.submit`（文件 `.../submit/SubmitCoordinator.kt`），仍归 T5
- 契约 §1 布局表、§9 标题与 package、§12；DESIGN.md 架构图（改为 6 包 + 依赖方向）、依赖规则第 5/6 条、职责表同步

### 裁决 3（分类器：业务码优先）
- `<业务码>〒<文案>`：`10`=成功、`7`=E_CAPTCHA（终态）、其他数字=E_REJECTED、解析不出→关键词启发式（aliyunwaf/captcha/验证码/安全校验）→E_CAPTCHA、最后 E_PARSE/E_UNKNOWN
- 契约 §8.4 重写 + 新增「真实响应测试向量」表；§12 DoD 向量更新
- 记录待复测分歧：api-debug 的 JS 证据显示 `11` 也走成功分支、`22` 亦弹验证码，但 Lead 裁决归入「其他数字→E_REJECTED」。已在 §8.4 标注「映射表是唯一改动点」并上报 Lead 请求一行确认

### T3 实测回填（api-debug 提供证据）
- D1 验证码**强制拦截**：HTTP 200 + `7〒需要安全校验，请重新提交！` → 样本问卷纯接口不可提交
- D2 成功前缀未直接观测；D3 `starttime` 在 **URL query**（不在 body）；D4 `&capt=2` 确认、sceneId=q0hcfsca 属 body（不发送）；D5 无额外签名参数
- 契约 §0（T3 结论置顶）、§5.3（URL/body 调整）、§8.2（E_CAPTCHA 触发条件加实测证据）、§11（决策表→已解决结论 + 11.2 两条强制规则）、§12 同步
- DESIGN.md：G3 验收口径调整（该类问卷只能给 E_CAPTCHA）、R1 改为「已确认」并给出升级路径、手工联调结论更新、MiniJson/org.json 说明更新

### 产出与状态
- 契约最终规模：约 690 行；DESIGN.md 约 300 行
- task-2 已 `complete`（revision 3），解除 task-4/task-5 的 `blocked_by` 前置
- 未越界写入任何非 write-scope 文件（临时抓取文件只在 `$TMPDIR`）

## 补充 2：T9 验证码兜底契约（01:50–02:05）

### 背景与裁决
- api-debug T3.5 实测 6 个问卷：`captchaType` **全为 '2'**（页面模板常量），只有 `useAliVerify` 决定是否强制校验（样本=1 被拦，5 个公开=0 放行）；JS `if(!h.useAliVerify || isCaptchaValid) return !0;`
- Lead 改判：门控信号 `captchaType` → `useAliVerify`；批准 `11`→成功、`22`→E_CAPTCHA，但要求**标注来源等级**（服务端实测 vs JS 推导）
- 用户批准「仅验证码环节」App 内 WebView 兜底；Lead 建 task-9（architect），已 claim

### 落盘内容
**docs/API-CONTRACT.md（880 行）**
- §2 冻结签名更新（Lead 批准 additive）：`fetch(url, cookies = emptyMap())`、`submit(m, answers, captchaToken = null)`；`SurveyModel` 增 `useAliVerify: Boolean = false`，并**提案** `sceneId: String? = null`（§13 硬前提，待 Lead 确认）
- §2.2/§6.4：`useAliVerify` 解析（`var useAliVerify\s*=\s*(\d+)`，缺失 false）；明确 `captchaType` **不是**门控信号
- §5.3 第 0 步硬门控；第 5 步 body 携带 `captchaVerifyParam`+`sceneId`（仅令牌非空时）
- §8.2/§8.4：E_CAPTCHA 触发条件含 `useAliVerify==true`；业务码映射加来源等级表（10/7=服务端实测，11/22=JS 推导）
- §11.3：T3.5 证据表 + 4 条 normative 结论
- **§13 新增**（13.1–13.10）：触发/入口与 3 次上限、12 步时序、Cookie 双向注入（含清理规则，禁 removeAllCookies）、硬边界（只允许两个注入脚本常量，绝不把答案交给页面提交）、降级表（全部保持 E_CAPTCHA）、additive 签名与 `CaptchaHarvest`、注入脚本常量逐字、WebView 安全配置表、**端到端验证唯一手段**、DoD

**docs/DESIGN.md（379 行）**
- 新增 §12 验证码兜底（WebView）设计（12.1–12.9），原 §12 顺延为 §13
- G3 验收口径、ui 职责（CaptchaActivity）、§4.2 E_CAPTCHA 行、§10 手工联调、R1（改为「已确认且已缓解」）同步

### 过程中的问题与修复（自检）
- 一次批量编辑用了**过期行号**（前序编辑已使行号漂移），导致两处错位：§10 标题被手工联调行覆盖、R7 行被 R1 尾段覆盖
- 用 grep 定位真实内容后**全部修复**（恢复 §10 标题、恢复 R7 行、更新真实的手工联调行与 R1 尾段），复查确认各 1 处
- 教训：跨编辑批次不要用行号定位，一律用唯一内容锚点
- 另一次日志追加用了「空行」作锚点导致工具报错并中断整批操作 → 改为「最后一个非空行」作锚点

### 状态
- task-9 已 complete；契约 880 行 / 设计 379 行
- 待 Lead 一行确认：`SurveyModel.sceneId: String? = null`（若否决，兜底路径无法工作；替代方案是把 sceneId 塞进 captchaToken 字符串，与 §2.1 裁决精神冲突）

## 补充 3：sceneId 获批（02:05）

- Lead 裁决：**批准** `SurveyModel.sceneId: String? = null`（additive，带默认值）；理由：sceneId 是协议层机器数据，塞进字符串违背「机器码只走结构化字段」原则
- 按 Lead 要求的口径统一为「缺省即不带」：提交时 `captchaToken` 非空且 `sceneId` 非空才写入 body；`sceneId` 为 null 时 **不携带该字段、本地不拦截**（由服务端判定）——已据此**移除**原先的本地快速失败（`E_CAPTCHA`「缺少验证场景标识」）
- 同步位置：契约 §2 冻结块注释、§2.2 字段表、§5.3 第 5 步 body 规则、§13.6、§13.10 DoD、§12 T6 用例；DESIGN.md §12.3 与变更记录
- Lead 同时确认 T9 验收通过（§13 十二步时序、硬边界、可机械审查判据）
- 已 send_message 同步 api-debug（fetch 解析 + submit 写入）、android-dev（只需透传 CaptchaHarvest）、qa-build（sceneId 缺省用例）

## 补充 4：兜底次数裁定暂缓（02:45）—— 契约**未改动**

- Lead 指示把 §13.1/§13.5 的「3 次」改为「每组最多 1 次、无跨组上限」，并要求文案对齐 strings.xml
- delivery 请求暂缓：该裁定基于 strings.xml 过渡版本，已请 Lead 复核
- **我核对后的决定性事实（等待裁定期间不改文档）**：
  1. `submit/CaptchaHarvest.kt:22` `MAX_CAPTCHA_FALLBACK_ATTEMPTS = 3`，MainActivity 用 `< 常量` 判定
  2. 计数器是**批级**的：`submitAll()` 里 `fallbackAttempts = 0`，任何一组的兜底尝试都 +1（MainActivity:727/113/123/130/808）→ 现状既不是「每组 3 次」也不是「每组 1 次」，而是「**整批最多 3 次**」
  3. `startCaptchaFallback()` 固定取 `state.outcomes.firstOrNull { errorCode == CAPTCHA }`（MainActivity:787）→ 多组都需验证时**只能重试第一组**
  4. 全仓 grep「每组最多 / 重试一次」**零匹配**：Lead 引用的「每组最多重试一次」这句文案不在当前 strings.xml；`captcha_exhausted` 文案为「人工验证多次失败…」（与 1 次策略语义不符）
  5. MainActivity:81 注释仍是「§13.1：最多 3 次」（相对任何裁定都已过时）
- 已 send_message 给 Lead：列出 A/B/C 三个方案与各自需要的代码/文案改动，请其一并裁定后我**一次改到位**（避免 T6 按契约逐字验收时再次分叉）；同时回复 delivery 确认暂缓
- 结论：§13.1/§13.5 **保持原文（3 次）不动**，等 Lead 最终裁定

## 补充 5：Lead 最终裁定 A 已落盘（02:50–03:00）

- 关键转折：android-dev 在 02:46 已按裁定 A 改完代码（`CAPTCHA_FALLBACK_PER_GROUP = 1`、`captchaRetriedGroups: MutableSet<Int>`、按钮按「存在未兜底组」显示、点击取第一个未兜底组），因此我上一轮发给 Lead 的证据（批级计数 / 只有第一组有入口）**已被修复**；Lead 的最终裁定 A 与代码方向一致
- 我已按 A 改完契约与设计：
  - §13.1：每组 1 次、无跨组上限、入口点击针对第一个未兜底组、结构性失败按钮立即隐藏
  - §13.5：文案表逐条对齐 `res/values/strings.xml` 资源名与文本（新增「文案唯一真源」声明）；按钮显示条件按「未兜底组集合」；多组逐组处理
  - §13.10 DoD（T5/T6）同步
  - DESIGN §4.2 / §12.6 / 变更记录同步
- **发现并上报的一处口径冲突**：A 第 2 条写「该组兜底后（**无论成功/取消/超时/仍失败**）标记为已兜底」，而 android-dev 当前实现是 `if (!cancelled && group >= 0) captchaRetriedGroups += group`（**取消不消耗**）。我按 Lead 的 A 字面落盘为「取消也计入」，并已请 android-dev 去掉 `!cancelled` 条件、同时请 Lead 若本意是「取消免费」则回退这一处
- **另一处待补**：Lead 要求 `captcha_exhausted` 改单次语义「该组已尝试过人工验证，仍未成功」，但该字符串在 02:46 的 strings.xml 中已被删除 → 已请 android-dev 按该文案补回（契约 §13.5 已引用）
- 另发现 MainActivity 两处过时注释（`fallbackAttempts` / 「允许再走一次（≤3 次）」），已列给 android-dev 清理
- 已通知 delivery：USAGE.md 仍有「≤3 次 / 3 次上限 / 连续 3 次失败」表述（第 185/186/190/221 行），需按 A 与新 strings.xml 一次性对齐

## 补充 6：消耗判据最终定稿（03:05）

- Lead 最终口径（唯一未闭合点已闭合）：
  | 情形 | 是否消耗该组机会 |
  |---|---|
  | 用户主动取消 | **不消耗**（每次都要亲手点按钮，无自动循环风险） |
  | 超时（已唤起验证码但未取得令牌） | 消耗 |
  | 取得令牌但提交仍失败 | 消耗 |
  | 结构性失败（无 WebView / 页面改版 / 无验证入口） | **不消耗，但直接终态**（按钮对全部未兜底组隐藏） |
- 已改：契约 §13.1 次数行（新增「消耗判据 normative」）、§13.2 第 12 步（机会结算）、§13.5 表（取消行→不消耗；三处结构性失败→不消耗+全部隐藏；超时/失败保持消耗）、§13.5 UI 判定（取消不加入集合）、§13.10 T5/T6 DoD；DESIGN §12.6 与变更记录
- 代码侧待办（已发 android-dev）：MainActivity 118–125 当前是 `else if (group >= 0) captchaRetriedGroups += group`（连取消也计入）→ 需恢复 `!cancelled` 守卫（`cancelled` 变量在 :115 已读取但未使用）；`captcha_exhausted` 字符串仍缺失需补回；:846 过时注释「允许再走一次（≤3 次）」待清理
- 结构性失败用 `markAllCaptchaGroupsRetried()` 实现「不消耗但立即终态」是可接受实现（契约只约束可观察行为）
- Lead 认可「先报证据再改」的处理方式

## 补充 7：与 strings.xml 逐字交叉核对（03:15）

- 方法：解析 strings.xml 全部 `captcha_*`（16 个），与契约 §13.5 引用的「…」逐字比对
- 结果：**错误表 9 条全部逐字一致**（no_webview / structure_changed / raise_failed / cancelled / no_result / expired / retry_failed / exhausted / fallback_button），无 DIFF
- `captcha_exhausted` 已由 android-dev 补回，文本 =「该组已尝试过人工验证，仍未成功」，与契约一致
- 代码侧核对：MainActivity 现为 `} else if (started && !cancelled && group >= 0) {` → 与 Lead 最终口径「实际发起才消耗、取消不消耗」完全一致；`fallbackAttempts` 与「允许再走一次（≤3 次）」注释均已清除
- 补全契约：§13.5 增加「UI 专属文案」清单（title/hint/status_*/retrying/retry_ok），并注明错误表 9 + UI 专属 7 = 16，与 strings.xml 一一对应，便于 delivery 的 push 前逐字核对
- 已回复 delivery：错误表无差异，无需跟改；push 前可直接用本清单核对

## 补充 8：§13.10 机械判据措辞修正（03:20）

- qa-build 实测指出：原文「evaluateJavascript 只能出现两次固定常量」与实现形态不符
- 实测形态：`CaptchaActivity.kt:250` 唯一调用点（私有 helper `evaluate(script)` 内）+ 2 个固定常量脚本（`JS_RAISE_CAPTCHA`/`JS_HARVEST`）+ 真实 `@JavascriptInterface` 0 个（仅注释提及）
- 已按 Lead 口径改为：「evaluateJavascript 调用点 1 个、脚本仅 2 个固定常量、无动态拼接、无 @JavascriptInterface」
- 同步位置：契约 §13.4 判据、§13.8 JS Bridge 行、§13.10 T5/T6 清单；DESIGN §12.5
- 行为契约未变，仅措辞与判据计数修正

## 补充 9：T14 子域放宽 + useAliVerify 假设被推翻（task-13）

### 落盘内容
- §5.1 shortId 正则 → `^https://([A-Za-z0-9-]+\.)*wjx\.cn/(vm|jq|m)/([A-Za-z0-9]{4,32})\.aspx`（wjx.cn 任意子域）；§5.1 允许的 host 行同步
- §4.2 templates.json JSON Schema 的 `surveyUrl.pattern` 同步放宽；§4.1 MappingTemplate 注释同步
- §8.4：业务码 22 由「JS 推导」升级为**服务端实测确认**（Lead 实测 useAliVerify=0 仍返回 22），并附 JS 证据 `22==a → alertNew(submit_need_validate2) → isCaptchaValid=false; useAliVerify=1; loadCaptchShow()`；来源等级表同步
- §11.3 新增第 5 条：`useAliVerify == false` **不代表免验证**（只是页面初始值，服务端可在任意提交上返回 7/22）→ 响应分类器必需、兜底入口常备
- §0 新增 T14 补充事实段；§13 背景段同步改判
- DESIGN：§12.1 增「T14 重要改判」条目、风险表新增 **R12**、变更记录同步

### 代码侧待跟进（只改文档，未动代码；已定位到行号）
旧正则仍存在于 3 个文件：
- `qr/SurveyLinkValidator.kt:29`（注释 :42）
- `config/TemplatesJson.kt:19-20`（注释 :18）
- `wjx/WjxSurveyClient.kt:153`（注释 :155）
→ 已通知 api-debug / android-dev / qa-build / delivery

### 状态
- task-13 已 complete；契约与设计均为 T14 版本
- 关键结论：**能否纯接口成功只能实发才知道**；兜底路径按常规路径对待

## 补充 10：T11 回填（api-debug 证据）

- **10 与 11 都是成功**（JS 逐字钉死）：`@148535` `10==a` → 成功 UI + complete.aspx；`@156436` `11==a` → clearAnswer/addtolog + `location.replace(n[1])`；`@159634` `11==a return` → §8.4 与来源等级表把 11 从「JS 分支推导」升级为「**JS 逐字证据（T11 钉死）**」
- **7 与 22 同义**（都要求安全校验）：`hintinfo.js` `submit_need_validate2="需要安全校验，请重新提交！"`，两个分支都 `isCaptchaValid=false; useAliVerify=1; loadCaptchShow()`
- **新增分类器规则（重要）**：业务码支持**裸码形态**——T11 实测 `ktimes=0` 时服务端回**裸 22**（无 〒、无文案）→ §8.4 第 4 步改为「先判 `^\d{1,3}$` 裸码，再按 〒 分割」，并新增测试向量 `22`（裸码）→ E_CAPTCHA
- §8.4 测试向量新增 `22`（真实裸码）与 `11〒…`（构造用例）；来源等级表补 T11 证据；标注证据出处 `tools/wjx-probe/evidence/11-t11-conclusion.md`
- §11.1 D2 行改为「T11 已钉死」；**新增 D6（待 Lead 批准）**：`&ktimes=` 是否改为 `max(1, ktimes)`（真实浏览器 ktimes>0，发 0 触发裸 22 风控）；已注明 `ktimes=0→1` 时 XOR key 不变、签名不变，仅 URL 数值变化
- DESIGN §4.1 第 3 条补充「业务码两种形态」说明
- **未擅自改 §5.3**：ktimes 属行为契约变更，等 Lead 批准后与 api-debug 同步落地
## 补充 11：T11 结论反转（V6 单变量 A/B）——撤回 R12 与 §11.3 第 5 条

### 决定性证据（api-debug V6，与 S0 只差 ktimes 0→4）
```text
POST .../processjq.ashx?shortid=P2M09FG&starttime=…&ktimes=4&capt=2&t=…&jqnonce=…&jqsign=…
body: submitdata=1$…}2$…}3$…
→ HTTP 200  10〒/wjx/join/complete.aspx?activityid=P2M09FG&joinid=127844297308&sojumpindex=1&comsign=…
```
→ **纯接口提交成功**；此前「useAliVerify=0 仍返回裸 22」由 **ktimes=0** 引起，不是问卷属性

### 已撤回/改写
- §0：T14 段改为「事实与修正」，明确裸 22 = ktimes=0 风控指纹，不是「问卷需要验证码」
- §11.3 第 5 条：由「useAliVerify=0 不代表免验证」改为「**useAliVerify=0 可纯接口提交**（ktimes>0 + 不补发校验字段）」；§11.1 D1 改为「V6 已分离两种情况」
- DESIGN R12：由「假设被推翻」改为「**请求形态触发风控（不是问卷属性）**」；§12.1 改判条目、§12.8 由「唯一手段」改为「**纯接口已 E2E 成功 + useAliVerify=1 才需兜底**」、§10 手工联调同步
- §13 背景段同步；契约与 DESIGN 变更记录各加一行

### 新增（Lead 批准的 ktimes 修复）
- §5.3 第 4 步：&ktimes = max(1, m.ktimes)，jqsign 用同一 effectiveKtimes；写入 Lead 要求的**变更影响说明**（0 与 1 的 XOR key 相同 → 签名不变，只改 URL 数值）
- §6.4 ktimes 行同步；§11 D6 记为「已批准并由 V6 证实方向」
- §5.3 第 5 步：把「不补发字段」的理由升级为实测（发送 rn/captchaVerifyParam/sceneId 会把 10 变 7；cst/source 已排除，rn/body 类为嫌疑未分离）
- §8.4：新增 **V6 真实成功向量**（10〒/wjx/join/complete.aspx?activityid=P2M09FG&joinid=127844297308&…）；证据出处标注 11-t11-conclusion.md 的旧结论已作废

### 我新发现的未闭合点（已上报 Lead）
- Lead 批准的下限是 **1**，但 V6 的 A/B 用的是 **4** → **ktimes=1 是否等效未验证**。契约已如实写入「未验证点」，并**不声称**该改动能解锁问卷；建议 api-debug 补一次 ktimes=1 单变量测试，若 1 不足再由 Lead 决定是否提高下限
- 另：rn/captchaVerifyParam/sceneId 中究竟哪个字段导致 10→7 尚未分离，V1 维持「一律不发」

### 待外部跟进
- api-debug：重写 11-t11-conclusion.md（旧结论作废）、补 ktimes=1 测试
- qa-build：新增 V6 真实成功向量
- delivery：USAGE.md:157/162 的「与 useAliVerify 无关 / 服务端要求二次校验」叙述需按 V6 改写
## 补充 12：取消本地门控 + ktimes 下限 4 + Cookie origin 修复

### Lead 改判 1：取消 useAliVerify 本地门控
- 口径：无论 useAliVerify 为何值都**先发一次真实提交**；只有响应业务码 7/22（或 aliyunwaf）才走 §13 兜底。理由：本地门控产生**假阴性**（useAliVerify=1 也可能本可提交），代价仅是多一次被拒请求（不产生答卷）
- 契约改动：§5.3 第 0 步（废除门控）、§8.2 E_CAPTCHA 触发条件、§6.4/§2.2 useAliVerify 语义（仅展示）、§11.2 规则 2、§11.3 结论 2（废除）、§12 DoD T4、§13.1 触发、§13.2 第 2 步、§13.10 T4
- DESIGN：R1 重写（风险改为「服务端响应要求校验」）、§12.1 补改判、§12.2 新增第 5 条原则、§12.8 同步

### Lead 改判 2：ktimes 下限取已验证值 4
- `effectiveKtimes = max(4, m.ktimes)`（不设上限）；依据 V6 单变量 A/B（ktimes=4 成功、joinid=127844297308）；**撤销**「ktimes>0 即有效」的推断措辞
- **我主动更正了一处自己写错的说明**：下限从 1 改为 4 后，0/1/2/3 → 4 会**改变 XOR key（1→4）→ jqsign 随之改变**（此前「签名不变」的说法只在下限为 1 时成立）。契约已写明「URL 数值与签名必须同源」
- 代码核对：api-debug 已落地 `WjxSubmitter.kt:106 maxOf(4, m.ktimes)`，且 :112 的 jqsign 用同一变量 ✓；:40 注释已写明不做本地门控 ✓

### android-dev 发现并已批准：§13.3 Cookie 基准
- 原 §13.3 ① 写死 `setCookie("https://www.wjx.cn/", ...)`，对 `v.wjx.cn` 子域验证页失效（方向①收不到引擎 cookie）
- 新口径：基准 = **问卷 URL 的 origin**（`CookieHeader.originOf(url, fallback)`，host 小写，失败兜底 www），方向①与④共用同一 cookieBase；§13.3 ①/④ 已改，§13.10 T5 加静态检查项
- 我复核了 sceneId 解析：`WjxSceneId.resolve` 在 useAliVerify=0 时仍优先用页面内联 sceneId（只有抓 wjx_captch.js 才受开关限制）→ 无需改动

### 外部跟进（已发消息）
- delivery：文档仍写 `max(1, 页面值)` 且 USAGE 错误表仍写「useAliVerify=1 硬门控」→ 两处都要改（floor 4；触发只看响应码）
- qa-build：加 floor 4 用例（ktimes≥4 且 jqsign 同源；ktimes=0 时签名**会变**）、无门控用例（总是发 POST）、cookie origin 用例（v.wjx.cn）
- api-debug：确认已落地；提醒 jqsign 同源（已满足）
## 补充 13：T18 定时提交/时间适配器文档（task-17）

### 落盘
- 契约 §2.3 时间适配器（Lead 冻结签名逐字：SurveyTimeAdapter.parse(html, nowMillis)/OpenTime.Known|Unknown/WjxTimeAdapter）+ 解析规则（BeginDate 优先、文案兜底 +08:00、都失败 Unknown）+ 6 条硬约束（**禁 java.time**：minSdk 24 无 java.time 且未启用 desugaring；nowMillis 只做换算基准；已开放也返回 Known；等号算已开放）+ 测试向量
- SurveyModel additive：openAtMillis: Long? = null / needsCaptchaHint: Boolean = false；§2.2/§6.4 字段语义同步
- **needsCaptchaHint 不可靠性四条铁律**（只读值不读标记；仅提前提示；false ≠ 一定放行；测试须覆盖 useAliVerify=0 + 模板标记存在 → false）
- E_NOT_OPEN：§5.2 第 7 步短路（**jqnonce 之后、题目解析之前**）、§8.2 错误表（httpStatus=200、终态、message「该问卷将于 yyyy-MM-dd HH:mm 开放」Asia/Shanghai）、§8.3 UI 动作、§8.4 声明不经分类器
- DESIGN §13 定时提交与前台服务设计（冻结 schedule 签名 + FileScheduledTaskStore + 触发语义表 + specialUse vs dataSync + ROM 杀进程兜底 + 单任务 + 不做 BOOT_COMPLETED 边界），原 §13 顺延 §14；风险表 R13–R16
- §12 DoD 增 T4/T5/T6 条目（适配器向量、短路位置、hint 规则、状态条/横幅/开关、ScheduleMath 与 store 持久化）

### 复核代码时发现的两个真实问题（已上报）
1. **strings.xml 缺 16 个 schedule_* 字符串**：ScheduledRunner/Notifier/ScheduledSubmitService 引用了 schedule_channel_ongoing(_desc)、schedule_channel_remind(_desc)、schedule_channel_result(_desc)、schedule_ongoing_title/_text、schedule_result_title、schedule_interrupted、schedule_remind_title/_text、schedule_template_missing、schedule_run_failed、schedule_captcha_notify_title/_text —— 资源里**一个都没有**（grep 确认），当前状态**编译必失败**
2. **android-dev 现有 schedule 实现与 Lead 冻结签名不一致**：ScheduledTask 字段集不同（现有 id/surveyUrl/remindBeforeMinutes/enabled/lastRun*，冻结为 leadMinutes/createdAtMillis/state + 新增 TaskState）、ScheduledTaskStore 是 class 而冻结为 interface（save 参数非空、clear 分离）、remindAtMillis 属性应迁到 ScheduleMath、常量应改为 normalizeLeadMinutes(0..1440)
3. 正面确认：Manifest 已含 FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS / WAKE_LOCK + foregroundServiceType="specialUse"，服务侧 START_STICKY 与 startForegroundSafely 降级均已实现，与 DESIGN §13 一致
## 补充 14：Lead 三条回复落地

- 问题 1（strings.xml 缺 16 个 schedule_* + schedule 签名不一致）：Lead 裁定**冻结签名权威**，android-dev 按清单改造；补字符串属 android-dev scope
- 问题 2（lastRun*/结果明细）：Lead 裁定**不加**，ScheduledTask 保持精简，结果由 state（DONE_OK/DONE_FAIL）+ 通知承载、失败原因用既有 errorCode 文案 —— 与 DESIGN §13.2 冻结签名一致，文档无需改
- **新口径（用户决策）**：**不做预检提交** —— 契约 §2.3 新增独立声明「只做页面层读取（useAliVerify 值）+ 响应层判定（7/22），不发起任何额外探测请求」，§11.2 与 DESIGN §12.2 第 6 条同步；两处变更记录已加行；grep 全 docs 确认无任何暗示探测机制的表述
## 补充 15：v1.0.5 契约改写（task-23）

### 1) §7 改为两类规则（用户驱动反转，已明确记录）
- **可跳过（skipped）**：字段名为空 / 未匹配到任何题目 → 记入 skipped，**不失败**
- **仍失败（E_UNMATCHED）**：歧义（多命中）/ 多字段指向同一题 / 取值不在选项中 / **skipped 非空但 pairs 为空**
- 新增 §7.4：`MatchOutcome.Ok(pairs, skipped = emptyList())`、`SubmitResult(..., skippedFields = emptyList())` 两个 additive 字段 + 5 条规则（顺序去重、仅成功时非空、不拼进 message、**UI 必须显式列出**、既有构造点兼容）
- **决策记录写进契约正文**：第一轮「未匹配一律失败」→ v1.0.5 用户超量预填需求反转；「不静默丢弃」精神由 `skippedFields` + 结果区显式列出承载，歧义/冲突仍硬失败
- 边界澄清：pairs 与 skipped **都为空** → `E_EMPTY`（§5.3 第 1 步）；只有 skipped 非空而 pairs 空才是 `E_UNMATCHED`；value 为空在匹配前被过滤，**不计入 skipped**

### 2) §4.4/§4.5 模板 upsert 与删除（按名字）
- 同名（trim、区分大小写）→ 覆盖内容、**保留原 id**、更新 updatedAt；新名 → 新 id 追加；同批重名 → 后者覆盖前者 + warning；id 与**不同名**模板冲突 → 重生成 id + warning
- 实现位置点名：`ui/EditorState.upsertTemplate`（现按 id 匹配，需改为按 name）
- 删除：移除 + 立即原子落盘；删的是当前模板 → 切到剩余第一个/空态；无撤销；不影响 schedule.json（被引用模板缺失时按 DESIGN §13 处理）

### 3) DESIGN 同步
- G4 由「必须报错」改为「可跳过但必须显式可见」；§4.2 错误表拆成两行（仍失败 vs 可跳过）
- 两处变更记录加行

### 现状核对（写文档时查过代码）
- 代码尚未落地这两项：`WjxSubmitter.kt:199 MatchOutcome.Ok(pairs)` 无 skipped、`SurveyModel.kt:110 SubmitResult` 无 skippedFields、`EditorState.kt:55 upsertTemplate` 仍按 id 匹配 —— 契约已先行冻结，待 api-debug / android-dev 实现
## 补充 16：T16 时间解析优先级修正（api-debug 实测）

### 事实（证据 tools/wjx-probe/evidence/14-time-fix.md）
| 来源（未开放 tfGAWU4） | 值 | +08:00 |
|---|---|---|
| qBeginDate | 1790040856347 | 2026-09-22 09:34:16（已过去 = 创建/开始时间） |
| nowTime + left(85054s) | 1790042125000 + 85054s | 2026-09-23 09:33:00 |
| 文案 | — | 2026-09-23 09:33 |

→ 按 qBeginDate 比较会判「已开放」→ 题目为空 → 误报 E_PARSE（正是要修的缺陷）

### 契约改动（§2.3 / §6.4 / 变更记录）
- 解析优先级改为：**未开放文案 → left+nowTime → qBeginDate/BeginDate 兜底 → Unknown**；正则改为先精确匹配 qBeginDate（避免误取其它 BeginDate），再加合理性窗口（≥2000-01-01、≤ now+20 年）
- 新增「qBeginDate 不是开放时间」实测证据表（三源对照）
- 新增 additive 辅助函数（逐字取自实现）：notOpenMessage / isOpen（**Unknown 视为已开放**）/ millisUntilOpen / formatBeijingTime / beijingMillisOf（必须整串消费）/ beijingTextOf
- 测试向量**全部重算**：文案 → 1790127180000；left+nowTime → 1790127179000（±2s 容差）；P2M09FG → 1790034767873；新增「qBeginDate 在过去但存在文案 → 取文案」的回归用例
- DESIGN §13.1 加防复发说明 + 变更记录

### 发现的一处实现注释过期（已告知 api-debug）
WjxTimeAdapter.kt:38 的文件头注释仍写「1. **时间戳优先**」，与同文件 :71-74 的正确实现与说明矛盾 → 建议改注释，避免后人按注释回退。

### 已同步 qa-build
此前我给 qa-build 的旧向量（BeginDate=1790040856347 → Known）**已作废**，新向量已重发。
## 补充 17：skippedFields 命名与空字段名语义对齐（Lead 冻结）

- 代码核对（WjxSubmitter.kt）：:218 `MatchOutcome.Ok(pairs, skippedFields: List<String> = emptyList())`；:248 `if (field.isEmpty()) continue // 空字段名直接忽略，不计入 skipped`；:304 `Fail("全部字段都被跳过，没有可提交的题目：" + detail, E_UNMATCHED)`；:74-75 `skippedFields` 仅在 `ok=true` 且非空时回填 —— 与 Lead 冻结一致
- 契约 §7.2 改：第 ① 条「字段名为空」由「可跳过」改为「**忽略，不计入 skippedFields**」（Lead 理由：空行是「还没填」，不是「问卷没有这个字段」；UI effectivePairs() 已过滤）；表格与注脚中所有 `skipped` 统一为 `skippedFields`
- 契约 §7.4 改：`MatchOutcome.Ok` 字段名 `skipped` → **`skippedFields`**；规则新增第 0 条（空字段名直接忽略）；标题同步
- 变更记录加行；grep 复查无残留 `skipped`/`Ok.skipped` 表述
- 已通知 api-debug 与 qa-build（按冻结签名写测试）
## 补充 18：v1.0.7 契约同步（task-33）

### 落盘
- 契约 §2.1：`WjxException` 增 additive `openAtMillis: Long? = null`（**仅 `E_NOT_OPEN` 时非空**），与 `SurveyModel.openAtMillis` 同源同义
- 契约 §8.2 E_NOT_OPEN 行后补说明：异常携带 openAtMillis，UI **必须**用它自动填入定时任务的开放时间输入框；§8.4 同步
- 契约 §8.3 新增 **UI 状态收敛铁律**（本轮 bug 根因类别）：失败分支不得沿用上一次成功解析的结果（`state.survey`/状态条/题目清单/开放时间框都要置明确值或 UNKNOWN，并与成功分支一样渲染）
- DESIGN §4.1 新增第 6 条原则；§13.1 补开放时间自动填入口径；两处变更记录加行

### 复核代码发现的现状（已上报 lead 与 android-dev）
1. `WjxErrors.kt:8-12` 的 `WjxException` **尚无** `openAtMillis` → 待 api-debug 加（契约已先行冻结）
2. `MainActivity.kt` 的失败分支未收敛状态（正是本轮 bug）：
   - `:585` `state.survey = model` 只在 onSuccess；`:603-608` onFailure **未清空 `state.survey`**、**未调用 `renderSurveyStatus()`** → 状态条继续显示上一份问卷的「已开放，可提交」
   - 连带影响：`:880` `state.survey?.cookies`（验证码兜底注入）会拿到**上一份问卷的旧 cookie** → 会话串号风险
   - `:588-592` 开放时间自动填入只在 onSuccess；`E_NOT_OPEN`（失败路径）不填 → 需用新的 `WjxException.openAtMillis`
3. 建议（已转达）：onFailure 中 `state.survey = null`（或置 UNKNOWN 快照）+ `renderSurveyStatus()` + 用 `WjxException.openAtMillis` 填开放时间
## 补充 19：T32 语义细化（api-debug 落盘后）

- §2.1 补 `WjxException.openAtMillis` 完整语义：仅 `E_NOT_OPEN` **且** `OpenTime.Known` 时非空（epoch ms, UTC）；`Unknown` 与其它错误码一律 null；**message 文案逐字不变**，结构化字段与文案并存（UI 填输入框用 openAtMillis、北京时间展示用 formatBeijingTime）；兼容性注明「字段在 cause 之后带默认值 → Kotlin 2/3 参构造点（引擎内 8 处）无需改动；**Java 调用方需传满 4 参**」；冒烟 126 PASS / 0 FAIL 记录在案
- §2.3 补与 E_NOT_OPEN 的衔接：抛 E_NOT_OPEN 时该字段 = 本次 Known.openAtMillis；Unknown 不会抛 E_NOT_OPEN（isOpen(Unknown)=true）→ 字段与错误码一一对应
- 变更记录加行
### 自检修复（本轮发现）
- 上一轮（task-33）我用单行锚点 `^class WjxException\(` 替换整段，导致 §2.1 **残留一份重复的类字段片段**（val code/message/cause + `) : Exception(...)` 悬空在 SubmitErrorCode 代码块前）
- 本轮补语义块时该片段被分隔成独立代码块而暴露；已删除残留，§2.1 现在是「WjxException 代码块 → 语义引用块 → SubmitErrorCode 代码块 → 为什么需要它」四段结构
- 复查：代码围栏 34 行（偶数、配对），`    val code: String,` 仅 1 处；冻结块（SurveyModel/SubmitResult/WjxSurveyClient/WjxSubmitter）读回逐行确认括号完整
- 教训（第二次同类）：**单行锚点若位于多行结构内部，替换必须包含该结构的闭合部分**；跨轮编辑后应读回结构确认，而不只看工具返回 OK

