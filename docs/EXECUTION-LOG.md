# 执行节点日志 — 问卷自动填表 APK（wjx-auto-filler）

> 由 Lead 汇总。各 Agent 的原始节点日志见 `docs/logs/<member>.md`（architect / api-debug / android-dev / qa-build / delivery）。
> 本文件记录**决策、证据与验收**，不重复各 Agent 的过程细节。

---

## 0. 总览

| 项目 | 值 |
|---|---|
| 目标 | 问卷星（wjx.cn）问卷「纯接口自动填表」Android APK，任意 Android 7.0+ 设备可装 |
| 应用名 / 包名 | 问卷自动填表 / `com.wjx.autofill` |
| 团队 | Lead + 5 个 Agent：architect（方案设计）、api-debug（接口调试）、android-dev（安卓开发）、qa-build（测试打包）、delivery（交付上传） |
| 仓库 | https://github.com/zimu5683/wjx-auto-filler （public） |
| Release | v1.0.0 / v1.0.1 / v1.0.2 / v1.0.3 / **v1.0.4（最终交付）** |
| 最终 APK | `dist/wjx-autofill-1.0.4-universal.apk`（6182574 B，sha256 `b522ae12395d3d8c…`） |
| 功能范围 | 二维码识别 → 问卷星纯接口自动填表 → 自定义字段映射 → 应用内更新 → **定时自动提交（前台常驻）** → **人机验证检测提醒** |
| 签名密钥 | `~/wjx-release/wjx-release.keystore`（**不入库**），证书 SHA-256 `ED:CC:E5:6E:F5:D1:50:CC:75:97:22:3D:DB:43:80:BB:CE:32:87:56:AB:B4:A8:BD:13:FF:BD:A8:7C:70:83:72` |

### 模型一致性（用户硬性要求）

要求：**全部 Agent 统一使用 opencode go 平台的 deepseek-v4.1-flash，全程不得使用其他模型。**

| 证据 | 结果 |
|---|---|
| `~/.dsh/settings.yaml` → `agent-default-model` | `provider: opencode-go` / `model: deepseek-v4.1-flash` / `reasoningEffort: max` |
| 本会话 `request/header` 事件 | `{"provider":"opencode-go","model":"deepseek-v4.1-flash","reasoningEffort":"max"}` |
| `list_agents`（5 个 Agent 创建后） | 每个成员 `model=deepseek-v4.1-flash` |
| 会话日志全量扫描（424 帧解压后） | `opencode-go` 出现 **456** 次、`deepseek-v4.1-flash` 出现 **404** 次；**其他模型名（kimi/glm/qwen/grok/gpt/minimax/mimo 等）出现 0 次** |

teammate 继承 Lead 的 root model（`dsh-experimental-agent-team` 的 `root.options.model`），故全队同一模型。

---

## 1. 任务板与分工（写作用域互不重叠）

| 任务 | 负责人 | 状态 | 写作用域 |
|---|---|---|---|
| T1 项目骨架与构建冒烟 | Lead | ✅ | `android/*.kts`、`gradle/`、`version.properties` |
| T2 设计文档与冻结接口契约 | architect | ✅ | `docs/DESIGN.md`、`docs/API-CONTRACT.md` |
| T3 问卷星接口实测 go/no-go | api-debug | ✅ | `tools/wjx-probe/**` |
| T3.5 拦截门抽样（只读） | api-debug | ✅ | 同上 |
| T4 接口引擎 Kotlin 实现 | api-debug | ✅ | `.../autofill/wjx/**` |
| T5 App 界面/配置/扫码/更新/验证码兜底 | android-dev | ✅ | `.../autofill/{ui,submit,config,qr,update}/**`、`res/**`、`AndroidManifest.xml` |
| T6 测试与打包 | qa-build | ✅ | `app/src/test/**`、`scripts/**`、`dist/**`、`testdata/**` |
| T7 交付上传 | delivery | ✅ | `README.md`、`docs/{USAGE,BUILD}.md`、`.github/**` |
| T8 总验收 | Lead | ✅ | `docs/EXECUTION-LOG.md` |
| T9 验证码兜底流程契约 | architect | ✅ | `docs/API-CONTRACT.md`、`docs/DESIGN.md` |
| T10 dry-run 干跑工具 | api-debug | ✅ | `tools/wjx-probe/dry-run/**` |
| **T11 业务码 22 深挖（决定性反转）** | api-debug | ✅ | `tools/wjx-probe/**` |
| **T12 `v.wjx.cn` 短链域名支持** | android-dev | ✅ | `.../qr/**`、`.../config/**` |
| **T13 子域测试 + v1.0.2 构建** | qa-build | ✅ | `app/src/test/**`、`scripts/**`、`dist/**` |
| **T14 契约同步子域放宽 + 改判** | architect | ✅ | `docs/API-CONTRACT.md`、`docs/DESIGN.md` |
| **T15 发布 v1.0.2** | delivery | ✅ | `README.md`、`docs/USAGE.md`、`.github/**` |

依赖链：T1 → T2 → {T3 → T4 ‖ T5} → T6 → T7 → T8；T9 支撑 T5；T11–T15 为用户第二轮样本触发的修复链。

---

## 2. 执行时间线（关键节点）

| 时刻 | 节点 | 结果 / 证据 |
|---|---|---|
| 00:17 | Lead 建任务板 T1–T8，spawn 5 个 Agent | `list_agents` 5 成员 `model=deepseek-v4.1-flash` |
| 00:22 | Lead 生成 release 签名密钥 | `keytool` RSA2048/10000 天，指纹 ED:CC:E5:6E:…:83:72 |
| 00:29 | **T1 冒烟构建** `assembleDebug` | BUILD SUCCESSFUL（6m55s） |
| 00:29 | Lead 实测纠正 A7 假设 | CameraX 带 8 个 `.so`，覆盖 **4 个 ABI** → 通用包成立；「APK 内无 .so」标准作废 |
| 01:05 | architect 落盘 `docs/API-CONTRACT.md` | 终稿 909 行 |
| 01:27 | **T3 结论：样本问卷纯接口被拦** | HTTP 200 + `7〒需要安全校验，请重新提交！` |
| 01:40 | T3.5 只读抽样（6 个问卷） | `captchaType` 恒为 `'2'`（模板常量）；开关是 `useAliVerify` |
| 02:02–02:14 | T4 引擎 5 个文件落盘 | 1237 行纯 Kotlin/JVM |
| 02:26 | Lead 发现兜底路径断点 | `sceneId` 恒为 null → 令牌无法配对；api-debug 按需抓 `wjx_captch.js` 修复 |
| 03:09 | **Lead 集成编译** `:app:compileDebugKotlin` | BUILD SUCCESSFUL（修完 28 条错配后） |
| 03:17 | android-dev 自查发现真实崩溃缺陷 | `QrScanActivity` 未在 Manifest 声明（点扫码必崩），已修 |
| 03:25 | qa-build 首轮 release 构建 | **FAILED 1 项**：APK 实为 debug 签名（daemon 复用旧环境） |
| 03:29 | qa-build 修复重建（`./gradlew --stop`） | ALL PASS，release 签名 |
| 03:45 | delivery 建 public 仓库 | https://github.com/zimu5683/wjx-auto-filler |
| 03:52 | Release **v1.0.0** 发布 | Lead 独立下载核对 sha256 逐字节一致 |
| 04:04 | Release **v1.0.1** 发布 | 与 1.0.0 签名同源 |
| **07:53** | **用户提供新样本「测试（无人机验证）」** | 二维码 → `https://v.wjx.cn/vm/P2M09FG.aspx` |
| 07:58 | Lead 只读核查 | 页面 `useAliVerify=0`；但引擎报「链接无效」→ 发现 `v.wjx.cn` 子域正则缺陷 |
| 08:00 | Lead 打补丁副本实跑 S0 | 真发 1 次提交 → **裸码 `22`** |
| 08:08–08:25 | api-debug 变体矩阵 V1–V5 | 均要求校验（22 / 7） |
| **08:34** | **api-debug V6 干净单变量 A/B（仅 ktimes 0→4）** | **`10〒/wjx/join/complete.aspx?…&joinid=127844297308` = 提交成功** |
| 08:39 | V6q 对照（Q0DQewW，useAliVerify=1，同款最小形态） | 仍 `7〒需要安全校验` → 该类问卷确为真拦截 |
| 08:40 | 引擎两处修复落盘 | `ktimes = maxOf(4, …)` + 取消本地 `useAliVerify` 门控 |
| 08:12–08:47 | 三处子域正则统一 + CaptchaActivity origin 修复 | android-dev 另抓到 UI 侧正则**双反斜杠**导致 `shortIdOf` 恒 null |
| 09:02 | **v1.0.2 构建** | ALL PASS(20/0/0)，sha256 `8c0c4758e73b8d0c…` |
| 09:05 | Lead 实跑 `./gradlew test` | BUILD SUCCESSFUL，157×2 = **314 用例 0 失败** |
| 09:0x | Release **v1.0.2** 发布 | 与 1.0.0/1.0.1 签名同源 |

---

## 3. 关键决策记录（含被 Agent 推翻的裁决）

> 用户要求「遇歧义第一时间确认」；本表记录 Lead 的裁决、依据与被推翻项。**被下属顶回来三次，三次都采纳了。**

| # | 决策 | 依据 / 结果 |
|---|---|---|
| D1 | `SubmitResult` 增 `errorCode: String? = null`（additive） | 否决 architect 原提的 `"<CODE>|文案"` 字符串前缀编码；机器数据不进人类字符串 |
| D2 | `SubmitCoordinator` 放新包 `submit/` 而非 `ui/` | 编排逻辑不属于 UI |
| D3 | 响应分类**业务码优先**：10/11=成功、7/22=E_CAPTCHA、其他=E_REJECTED | 真实响应 `7〒` 一个关键词都不含，纯关键词匹配会误判 |
| D4 | **被推翻**：拦截门由 `captchaType` 改判为 `useAliVerify` | api-debug 证明 `captchaType='2'` 是模板常量（6/6 全有），按原裁决会让 App 对 **100% 问卷**失效 |
| D5 | **驳回**「向公开问卷发一次提交」的请求 | 伦理：污染他人真实数据；只允许在用户自己的问卷上实测 |
| D6 | 验证码兜底用 App 内 WebView，**仅取令牌** | **用户明确批准**；硬边界：不填表、不点提交、答案不进 WebView（有静态检查强制） |
| D7 | 兜底机会：**每组 1 次、不设跨组上限** | 并修掉真实缺陷：原实现只对第一组提供入口，多组时其余组永远无法兜底 |
| D8 | 取消**不消耗**机会；超时/失败**消耗**；结构性失败直接终态 | 取消是用户改主意，不是失败 |
| D9 | 真实问卷 fixture **不入公开仓库** | 不转分发他人问卷内容；测试改为「缺失可见 skip」，CI 用合成 HTML 覆盖解析 |
| D10 | A7 标准由「APK 内无 .so」更正为「不设 abiFilters + 4 ABI 齐全」 | Lead 原假设被实测证伪 |
| D11 | `sceneId` 由引擎按需补齐（页面 → `wjx_captch.js` → null） | 保持冻结签名；只在需要时触发 |
| D12 | **批准 1 次对照提交**（Q0DQewW，最小形态） | 判定 `useAliVerify=1` 是否真拦截 → 结论：**是**，兜底路径必需 |
| D13 | **取消 `useAliVerify` 本地门控**，改为「总是先尝试提交，7/22 才兜底」 | 本地门控会产生**假阴性**（连请求都不发）；代价仅一次被拒请求 |
| D14 | `ktimes` 下限取**已验证值 4**（而非最小改动 1） | 1 未测；不拿未验证的值去赌。architect 指出下限改 4 后签名**会**变（key 1→4），实现已确认同源 |
| D15 | 三处子域正则统一放宽为「`wjx.cn` 任意子域」 | `v.wjx.cn` 短链是真实存在的主流形态；负例（`evilwjx.cn`/`wjx.cn.evil.com`）必须仍被拒 |
| D16 | 引擎正则由 **Lead 接手修改** | api-debug 忙于 22 调查，该行是重建的最后一环，Lead 上关键路径 |
| D17 | `CaptchaActivity` cookie 基准改为**问卷 URL 的 origin** | android-dev 发现：写死 `www.wjx.cn` 时 `v.wjx.cn` 验证页收不到引擎会话 cookie |
| D18 | 报告证据新鲜度：Lead 亲自跑 `./gradlew test` 验证最新测试源码可编译 | qa-build 主动指出报告里的测试数字来自旧一轮；实测 314 用例 0 失败 |

---

## 4. 验收结果（v1.0.2）

| # | 标准 | 结果 | 证据 |
|---|---|---|---|
| A1 | release 签名 APK | ✅ | apksig `verified=true`(v2)，signer `CN=WJX AutoFill`，指纹与 keystore 逐字节一致 |
| A2 | 编码算法正确 | ✅ | 314 单测 0 失败；`jqsign` 与真实请求逐字节一致 |
| A3 | 二维码解码 | ✅ | 用户两张截图均解码成功（`Q0DQewW` 与 `P2M09FG`） |
| **A4** | **接口级真实提交** | ✅ **已端到端成功** | `10〒/wjx/join/complete.aspx?activityid=P2M09FG&joinid=127844297308&comsign=…` |
| A5 | 并行提交 | ✅ | `SubmitCoordinatorTest`：并发上限/组序/独立会话/失败保码 |
| A6 | 应用内更新链路 | ✅（数据面）/ ⏳（真机点击） | 三个 Release 同源签名、`releases/latest` 返回 v1.0.2；安装弹窗需真机 |
| A7 | 跨设备可安装 | ✅ | 4 ABI 齐全、8 个 .so 每库 4 份、无自研 native、minSdk 24 / targetSdk 35 |
| A8 | 无 Termux / 无 GMS | ✅ | 源码无 `Runtime.exec`/`ProcessBuilder`/termux/python/proot；依赖树无 `com.google.android.gms` |
| A9 | 新系统 API 合规 | ✅ | `lintRelease` 0 条 Error（含 NewApi） |
| A10 | 脱离 Termux 可构建 | ✅ | CI 三个 tag 均 success；测试源码在 CI 环境可编译（friend-path 已验证） |
| A11 | 交付完整 | ✅ | 仓库 + 三个 Release + `docs/BUILD.md` / `docs/USAGE.md` |
| A12 | 节点日志 | ✅ | 本文件 + `docs/logs/*.md`（5 份） |

### 产物

| 文件 | 大小 | sha256 |
|---|---|---|
| `dist/wjx-autofill-1.0.0-universal.apk` | 6138222 B | `fd1370a7040e805e2e70b438f52d4cb36b26077bf79b54ca29de39b3a433a9ab` |
| `dist/wjx-autofill-1.0.1-universal.apk` | 6138222 B | `0db09f8d1444fb182fb6fceb5c2042c8890ed0b44ad29edcd69cf969d71b3dfb` |
| **`dist/wjx-autofill-1.0.2-universal.apk`** | **6138246 B** | **`8c0c4758e73b8d0c22f51abf416017a0daa81feaf7f5acd92673a022f274d997`** |

---

## 4.5 第二轮：新功能（定时自动提交 + 人机验证提醒）

用户确认 **v1.0.2 真机验证通过**后，提出两项新功能。**用户已确认的四项决策**：仍只做问卷星（时间解析做成可插拔适配器）／只做**前台常驻服务**（不做闹钟/WorkManager）／到点遇验证**只推送通知**／提前提醒**可配置、默认 10 分钟**。后续又两次变更：**响铃/震动做成可选项**、**检测到验证直接进验证页**（不再先弹「人工验证后重试」按钮），并明确**不做自主人机验证**。

| 任务 | 负责人 | 结果 |
|---|---|---|
| T16 时间适配器 + `E_NOT_OPEN` + 人机验证检测 | api-debug | ✅ `WjxTimeAdapter`（`BeginDate` 优先/文案兜底/Unknown 不拦截）、`needsCaptchaHint`（只读 `useAliVerify` **值**） |
| T17 前台服务 + 定时提交 + 通知 + UI | android-dev | ✅ `specialUse` 前台常驻 + `START_STICKY`、`ScheduledTaskStore`、`Notifier` 三渠道 + 验证双渠道、`PendingCaptchaStore`、直接进验证页 |
| T18 契约与设计文档 | architect | ✅ 契约 §2.3/§5.2/§8.x + DESIGN §13（含 `java.time` 禁用坑） |
| T19 测试 + 构建 | qa-build | ✅ 410 用例 0 失败（新增 48）、v1.0.3/v1.0.4 |
| T20 发布 | delivery | ✅ v1.0.3 / v1.0.4 |
| T21 总验收 + 本日志 | Lead | ✅ |

### 本轮关键事实（实测）

| 事实 | 证据 |
|---|---|
| `BeginDate="<epoch ms>"` **所有问卷页都有** | 未开放 `tfGAWU4`=`1790040856347`（2026-09-23 09:33）；已开放 `P2M09FG`/`Q0DQewW` 为过去时间 → 用时间戳比较判定，不猜文案 |
| 未开放页仍带 `jqnonce`/`starttime`，只是题目不下发 | `fieldset`/`topic=` 计数 0 → 原 `E_PARSE`「可能已关闭或页面改版」是误报，新增 `E_NOT_OPEN` |
| ⚠️ **人机验证标记是模板常量** | `useAliVerify`/`captchaWrap`/`wjx_captch` **所有问卷都有**（含从未被拦的 P2M09FG）→ **只能读值**，看标记存在会 100% 误报 |
| 用户选择**不做预检提交** | 我提出「故意缺答探安全门」方案，用户选零风险路线 → 契约写入禁止性声明：不做任何额外探测请求 |

### 本轮事故：三个 Agent 同时掉线（Lead 接管）

`android-dev` / `api-debug` / `qa-build` **在同一时间段失败退出，均无收尾消息**（环境层面问题）。android-dev 停在半成品，源码**编译不过**。Lead 接管并修复：

1. `MainActivity:1142` 把 `CharSequence` 传给 `toast(String)` → `toString()`
2. `MainActivity:330/334` 调用不存在的 `renderScheduleStatus()` → `renderSchedule()`
3. **补完 android-dev 未完成的响铃/震动开关**：新增 `schedule/SchedulePrefs.kt`（SharedPreferences，默认都开）+ MainActivity 接线两个 Switch + `ScheduledRunner` 改从偏好读取（替代其引用的不存在的 `task.alertSound` 字段，避免动冻结签名）

### 本轮发现的真缺陷

| # | 缺陷 | 后果 | 发现者 |
|---|---|---|---|
| 11 | `Notifier.kt` 缺同方法内权限检查（lint `MissingPermission`） | lintRelease 1 Error，CI 红 | qa-build |
| 12 | **`Notifier.kt` 在 11:48:16 被改，而 dist 的 APK 是 11:48:25 从 11:38 构建输出复制的** | **仓库 HEAD 与已发布 v1.0.3 的 APK 不一致**（比 lint 本身严重） | Lead |
| 13 | `normalizeLeadMinutes` 负值返回 10 ≠ 冻结口径 0 | 边界行为不符契约 | qa-build |
| 14 | 报告里 lint 的 PASS 来自 08:41 的旧 XML | 旧证据冒充本次结果 | qa-build |

**处置**：v1.0.3 的 tag 与资产**不动**（已发布的 tag 不应移动），**新发 v1.0.4** 由修复后源码构建（`./gradlew test lintRelease assembleRelease` 同一轮），lint Error 归零、源码与 APK 对齐。`dist/LINT-WAIVER-v1.0.3-historical.md` 保留为 v1.0.3 豁免决策的归档。

---

## 5. 团队自查发现的真缺陷（非测试发现，是成员主动核出来的）

| # | 缺陷 | 后果 | 发现者 |
|---|---|---|---|
| 1 | `QrScanActivity` 未在 Manifest 声明 | 点「扫码」必崩 `ActivityNotFoundException` | android-dev |
| 2 | 首轮 APK 实为 **debug 签名**（Gradle daemon 复用旧环境，AGP 配置阶段读不到 `WJX_*`） | 覆盖安装/应用内更新全废；且报告漏报 | delivery + qa-build |
| 3 | 兜底路径 `sceneId` 恒为 null | 验证码令牌无法与 sceneId 配对 | Lead |
| 4 | 多组都需要验证时**只有第一组有兜底入口** | 其余组永远无法兜底，与「并行提交多组」核心用法冲突 | Lead 裁决时 architect 定位 |
| 5 | **`ktimes=0` 是机器人指纹** | 服务端回裸码 22 风控 → 纯接口本可成功的问卷被误判 | api-debug |
| 6 | 三处 shortId 正则只认 `www.wjx.cn` | `v.wjx.cn` 短链被引擎判「链接无效」，App 完全不可用 | Lead（dry-run 实跑） |
| 7 | UI 侧正则原始字符串里是**双反斜杠** | `shortIdOf()` 对任何真实链接恒返回 null（被模板 url 推导掩盖） | android-dev |
| 8 | `CaptchaActivity` cookie 基准写死 `www.wjx.cn` | `v.wjx.cn` 问卷的验证页收不到引擎会话 cookie | android-dev |
| 9 | `originOf` 只小写 scheme 不小写 host | 大写主机名得到 `https://V.WJX.CN/` | android-dev（自验时） |
| 10 | 校验脚本 minSdk 用旧格式 grep | 误报 FAIL（新版 aapt2 输出 `minSdkVersion:`） | delivery |

---

## 6. 已知限制与未验证项（如实列出）

1. **真机交互未验证**（本机 `adb` 是 x86-64 二进制，无法自装自测）：CameraX 扫码预览/对焦/手电筒、WebView 验证码控件实际行为、应用内更新的一键安装弹窗。
2. **`useAliVerify=1` 的问卷是否全部拦截**：只测了 1 个样本（`Q0DQewW`）→ 确为真拦截。不能推断所有此类问卷。
3. **业务码细节**：`rn` 与 body 的 `captchaVerifyParam`/`sceneId` 二者中，**谁**把结果从 10 推向 7 尚未分离（对引擎无影响——两者都不发）；`ktimes=1` 是否等效未测（已用实测值 4 绕开）。
4. **选择类题型**无端到端样本：用户两张问卷均为填空题。已用真实页面 fixture + 8 种题型合成 HTML + 匹配器/请求构造单测覆盖，但**未真机提交验证**。
5. 分页问卷（`E_PAGED`）与矩阵/量表（`E_UNSUPPORTED`）只有静态实现与解析验证。
6. 两个样本问卷各写入 1 次测试回答（用户提供用于联调）；`P2M09FG` 成功提交 `joinid=127844297308`。

---

## 7. 证据索引

| 类别 | 位置 |
|---|---|
| 接口实测原始证据 | `tools/wjx-probe/evidence/03-submit-request.txt`、`03-submit-response.txt`、`03-result.json` |
| **业务码 22 完整变体矩阵（8 次提交）** | `tools/wjx-probe/evidence/11-t11-conclusion.md`、`11-v1..v6q-*.json` |
| **V6 成功响应原文** | `tools/wjx-probe/evidence/11-v6-response.txt`（`10〒/wjx/join/complete.aspx…joinid=127844297308`） |
| 拦截门抽样（只读） | `tools/wjx-probe/evidence/t35b-scan.log`、`t35b/t35b-summary.json` |
| 引擎 JVM 冒烟（93 PASS） | `tools/wjx-probe/evidence/04-engine-smoke.log` |
| Kotlin/Node 一致性对照 | `tools/wjx-probe/evidence/06-kotlin-vs-node-parity.md` |
| dry-run 干跑 | `tools/wjx-probe/dry-run/`、`evidence/05-dry-run-sample.txt`、`12-dry-run-v-subdomain.txt` |
| 构建校验报告 | `dist/VERIFY-REPORT.md`（v1.0.2，ALL PASS） |
| 各 Agent 节点日志 | `docs/logs/{architect,api-debug,android-dev,qa-build,delivery}.md` |
| 契约与设计 | `docs/API-CONTRACT.md`（909 行）、`docs/DESIGN.md`（390 行） |
| 模型一致性取证 | 见 §0；复算方法：解压 `~/.dsh/sessions/--data-data-com.termux-files-home-wjx--/session-*/session.v3.jsonl.zstd`（多帧 zstd，按 magic `28 B5 2F FD` 逐帧解），统计 `opencode-go` / `deepseek-v4.1-flash` / 其他模型名出现次数 |