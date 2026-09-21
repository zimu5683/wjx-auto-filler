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
| Release | v1.0.0 / v1.0.1 |
| 交付 APK | `dist/wjx-autofill-1.0.1-universal.apk`（1.0.0 见 §4） |
| 签名密钥 | `~/wjx-release/wjx-release.keystore`（**不入库**），证书 SHA-256 `ED:CC:E5:6E:F5:D1:50:CC:75:97:22:3D:DB:43:80:BB:CE:32:87:56:AB:B4:A8:BD:13:FF:BD:A8:7C:70:83:72` |

### 模型一致性（用户硬性要求）

要求：**全部 Agent 统一使用 opencode go 平台的 deepseek-v4.1-flash，全程不得使用其他模型。**

| 证据 | 结果 |
|---|---|
| `~/.dsh/settings.yaml` → `agent-default-model` | `provider: opencode-go` / `model: deepseek-v4.1-flash` / `reasoningEffort: max` |
| 本会话 `request/header` 事件 | `{"provider":"opencode-go","model":"deepseek-v4.1-flash","reasoningEffort":"max"}` |
| `list_agents`（5 个 Agent 创建后） | 每个成员 `model=deepseek-v4.1-flash` |
| 会话日志全量扫描（424 帧 / 940 行解压后） | `opencode-go` 出现 **456** 次、`deepseek-v4.1-flash` 出现 **404** 次；**其他模型名（kimi/glm/qwen/grok/gpt/minimax/mimo 等）出现 0 次** |

teammate 继承 Lead 的 root model（`dsh-experimental-agent-team` 的 `root.options.model`），故全队同一模型。复核脚本见 §6「模型一致性取证」。

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

依赖链：T1 → T2 → {T3 → T4 ‖ T5} → T6 → T7 → T8；T9 支撑 T5。

---

## 2. 执行时间线（关键节点）

| 时刻 | 节点 | 结果 / 证据 |
|---|---|---|
| 00:17 | Lead 建任务板 T1–T8，spawn 5 个 Agent | `list_agents` 5 成员 `model=deepseek-v4.1-flash` |
| 00:22 | Lead 生成 release 签名密钥 | `keytool` RSA2048/10000 天，指纹 ED:CC:E5:6E:…:83:72 |
| 00:29 | **T1 冒烟构建** `assembleDebug` | BUILD SUCCESSFUL（6m55s），APK 7.3MB |
| 00:29 | Lead 实测纠正 A7 假设 | CameraX 带 8 个 `.so`，覆盖 **4 个 ABI** → 通用包成立；「APK 内无 .so」标准作废 |
| 00:36–00:44 | api-debug 抓页面/资源；delivery 出 README/USAGE/BUILD/CI | `tools/wjx-probe/evidence/01–02` |
| 01:05 | architect 落盘 `docs/API-CONTRACT.md` | 882 行（终稿） |
| 01:27 | **T3 结论：纯接口 NO-GO** | HTTP 200 + 43 字节 `7〒需要安全校验，请重新提交！` |
| 01:40 | T3.5 只读抽样（6 个问卷） | `captchaType` 恒为 `'2'`（模板常量）；真正开关是 `useAliVerify` |
| 01:5x | Lead 用独立 kotlinc 复算 codec | 与真实请求 `jqsign` 逐字节一致 |
| 02:02–02:14 | T4 引擎 5 个文件落盘 | 1237 行纯 Kotlin/JVM |
| 02:26 | Lead 发现兜底路径断点 | `sceneId` 恒为 null → 令牌无法配对 |
| 02:4x | api-debug 修 `sceneId`（按需抓 `wjx_captch.js`） | 样本问卷解析出 `q0hfscsa` 来源 CAPTCHA_JS |
| 03:09 | **Lead 集成编译** `:app:compileDebugKotlin` | BUILD SUCCESSFUL（修完 28 条错配后） |
| 03:17 | android-dev 自查发现真实崩溃缺陷 | `QrScanActivity` 未在 Manifest 声明（点扫码必崩），已修 |
| 03:25 | qa-build 首轮 release 构建 | **FAILED 1 项**：APK 实为 debug 签名（daemon 复用旧环境） |
| 03:29 | qa-build 修复重建（`./gradlew --stop`） | **ALL PASS**，release 签名，sha256 `fd1370a7…` |
| 03:33 | 产物冻结 | 6138222 字节 |
| 03:45 | delivery 建 public 仓库 | https://github.com/zimu5683/wjx-auto-filler |
| 03:5x | delivery push + Release v1.0.0 | 见 §4 |

---

## 3. 关键决策记录（含被 Agent 推翻的裁决）

> 用户要求「遇歧义第一时间确认」；本表记录 Lead 的裁决、依据与被推翻项。**被下属顶回来两次，两次都采纳了**——这是本流程有效性的直接证据。

| # | 决策 | 依据 / 结果 |
|---|---|---|
| D1 | `SubmitResult` 增 `errorCode: String? = null`（additive） | 否决 architect 原提的 `"<CODE>|文案"` 字符串前缀编码；机器数据不进人类字符串 |
| D2 | `SubmitCoordinator` 放新包 `submit/` 而非 `ui/` | 编排逻辑不属于 UI |
| D3 | 响应分类**业务码优先**：10=成功、7/22=E_CAPTCHA、其他=E_REJECTED、再退关键词 | 真实响应 `7〒` 一个关键词都不含，纯关键词匹配会误判 |
| D4 | **被推翻**：拦截门由 `captchaType` 改判为 `useAliVerify` | api-debug 证明 `captchaType='2'` 是模板常量（6/6 全有），按原裁决会让 App 对 **100% 问卷**失效 |
| D5 | **驳回** api-debug 的「向公开问卷发一次提交」请求 | 伦理：污染他人真实数据；改用用户自己的问卷 + 兜底路径作为端到端证据 |
| D6 | 验证码兜底用 App 内 WebView，**仅取令牌** | **用户明确批准**（其原始需求写「不打开内置浏览器」，故必须由用户裁决）；硬边界：不填表、不点提交、答案不进 WebView |
| D7 | 兜底机会：**每组 1 次、不设跨组上限** | 并修掉真实缺陷：原实现只对第一组提供入口，多组时其余组永远无法兜底 |
| D8 | 取消**不消耗**机会；超时/失败**消耗**；结构性失败直接终态 | 取消是用户改主意，不是失败；每次兜底都需用户亲手点，无自动循环风险 |
| D9 | 真实问卷 fixture **不入公开仓库** | 不转分发他人问卷内容；测试改为「缺失可见 skip」，CI 用合成 HTML 覆盖解析 |
| D10 | A7 标准由「APK 内无 .so」更正为「不设 abiFilters + 4 ABI 齐全」 | Lead 原假设被实测证伪 |
| D11 | `sceneId` 由引擎按需补齐（页面 → `wjx_captch.js` → null） | 保持冻结签名；只在 `useAliVerify=1` 时触发，正常路径零额外请求 |

---

## 4. 验收结果

### v1.0.0（Release 基线）

| # | 标准 | 结果 | 证据 |
|---|---|---|---|
| A1 | release 签名 APK | ✅ | apksig `verified=true`(v2)，signer `CN=WJX AutoFill`，证书 SHA-256 与 keystore 逐字节一致 |
| A2 | 编码算法正确 | ✅ | 246 单测 0 失败；`jqsign` 与真实请求逐字节一致 |
| A3 | 二维码解码 | ✅ | `testdata/qr-sample.jpg`(1200×2000) → `https://www.wjx.cn/vm/Q0DQewW.aspx` |
| A4 | 接口级真实提交 | ⚠️ **待用户真机验证** | 样本问卷 `useAliVerify=1` 必然返回码 7；唯一端到端路径 = 用户点一次验证码。见 §5 |
| A5 | 并行提交 | ✅ 单测级 | `SubmitCoordinatorTest`：并发上限/组序/独立会话/失败保码 |
| A6 | 应用内更新链路 | ⚠️ **待用户真机验证** | 两个 Release 已就位；版本比较/资产匹配已单测 |
| A7 | 跨设备可安装 | ✅ | 4 ABI 目录齐全（arm64-v8a/armeabi-v7a/x86/x86_64）、8 个 .so 每库 4 份、无自研 native、minSdk 24 / targetSdk 35 |
| A8 | 无 Termux / 无 GMS | ✅ | 源码无 `Runtime.exec`/`ProcessBuilder`/termux/python/proot；`releaseRuntimeClasspath` 无 `com.google.android.gms` |
| A9 | 新系统 API 合规 | ✅ | `lintRelease` 0 条 Error（含 NewApi） |
| A10 | 脱离 Termux 可构建 | ✅ | `.github/workflows/android.yml`（CI 用官方 Gradle，不传 aapt2 覆盖参数） |
| A11 | 交付完整 | ✅ | 仓库 + Release + `docs/BUILD.md` / `docs/USAGE.md` |
| A12 | 节点日志 | ✅ | 本文件 + `docs/logs/*.md`（5 份） |

### 产物

| 文件 | 大小 | sha256 |
|---|---|---|
| `dist/wjx-autofill-1.0.0-universal.apk` | 6138222 B | `fd1370a7040e805e2e70b438f52d4cb36b26077bf79b54ca29de39b3a433a9ab` |
| `dist/wjx-autofill-1.0.1-universal.apk` | 见 dist/VERIFY-REPORT.md | 见 `.sha256` |

---

## 5. 已知限制与未验证项（如实列出）

1. **端到端提交未在本机验证**。样本问卷启用了阿里云安全校验（`useAliVerify=1`），服务端对不带 `captchaVerifyParam` 的提交一律返回业务码 7——**真实浏览器首次提交同样如此**，不是反爬识别。因此「提交成功（业务码 10）」必须由用户在自己的设备上完成一次验证码后观察。
   - 该路径同时也是**整套提交机制的唯一端到端证明**（兜底路径同样走纯 HTTP，只多一个令牌）。
2. **`useAliVerify=0` 的问卷能否直接提交成功**是强假设。按 D5 未在他人问卷实测；可验证但未验证。
3. **选择类题型**（单选/多选/矩阵/量表）无端到端样本：用户样本问卷只有 3 道填空题。已用 4 个真实页面 fixture + 8 种题型合成 HTML + 匹配器/请求构造单测覆盖，但**未真机提交验证**。用户表示「后续如果有选择的话会再添加功能」。
4. **真机交互未验证**：CameraX 扫码预览/对焦/手电筒、WebView 验证码控件实际行为、应用内更新的一键安装弹窗——均需真机。本机 `adb` 是 x86-64 二进制，无法自装自测。
5. 业务码 **22** 映射为 `E_CAPTCHA` 属 JS 分支推导，无真实响应；映射表在 `WjxResponseClassifier` 单点可改。
6. 分页问卷（`E_PAGED`）与矩阵/量表（`E_UNSUPPORTED`）只有静态实现与解析验证。

---

## 6. 证据索引

| 类别 | 位置 |
|---|---|
| 接口实测原始证据 | `tools/wjx-probe/evidence/03-submit-request.txt`、`03-submit-response.txt`、`03-result.json` |
| 拦截门抽样（只读） | `tools/wjx-probe/evidence/t35b-scan.log`、`t35b/t35b-summary.json` |
| 引擎 JVM 冒烟（84 PASS） | `tools/wjx-probe/evidence/04-engine-smoke.log` |
| Kotlin/Node 一致性对照 | `tools/wjx-probe/evidence/06-kotlin-vs-node-parity.md` |
| dry-run 干跑 | `tools/wjx-probe/dry-run/`、`evidence/05-dry-run-sample.txt` |
| 构建校验报告 | `dist/VERIFY-REPORT.md` |
| 各 Agent 节点日志 | `docs/logs/{architect,api-debug,android-dev,qa-build,delivery}.md` |
| 契约与设计 | `docs/API-CONTRACT.md`、`docs/DESIGN.md` |
| 模型一致性取证 | 见 §0；复算方法：解压 `~/.dsh/sessions/--data-data-com.termux-files-home-wjx--/session-*/session.v3.jsonl.zstd`（多帧 zstd，按 magic `28 B5 2F FD` 逐帧解），统计 `opencode-go` / `deepseek-v4.1-flash` / 其他模型名出现次数 |
