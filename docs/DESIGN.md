# DESIGN.md — 问卷星纯接口自动填表（wjx-auto-filler）总体设计

> 版本：v1.0（2026-09-22）｜作者：architect（T2）
> 配套文档：`docs/API-CONTRACT.md`（冻结接口契约，**所有签名/字段/错误码以它为准**）
> 本文件回答「为什么这样做、边界在哪、风险怎么兜」，契约文件回答「代码写成什么样」。

---

## 1. 目标与非目标

### 1.1 目标（可验收）

| # | 目标 | 验收方式 |
|---|---|---|
| G1 | 粘贴或扫码得到问卷链接 → 纯 HTTP 解析出题目清单（题号/题干/题型/选项） | 对样本问卷 `https://www.wjx.cn/vm/Q0DQewW.aspx` 解析出 3 题：姓名/学号/班级，题型 TEXT |
| G2 | 双栏字段映射（左：用户字段，右：问卷题目），保存为模板 | 模板可新建/复制/改名/删除，重启后仍在 |
| G3 | 一次提交多组答案，默认并发 2，**每组独立会话** | 组数 N 的报告逐组可见，结果按组序返回；对 `useAliVerify=1` 的问卷：无令牌时明确返回 `E_CAPTCHA`，用户可走 §12 人工验证兜底后重试 |
| G4 | 未匹配字段**不得静默丢弃**，必须报错并可定位 | 构造未匹配字段 → 结果码 `E_UNMATCHED`，UI 高亮该字段 |
| G5 | 通用包：Android 7.0+（minSdk 24）任意 ABI 设备可安装 | `aapt`/apkanalyzer 校验 minSdk=24、无 abiFilters、4 ABI 齐全 |
| G6 | 应用内自动更新（对齐 yikou 项目 AppUpdater 逻辑） | 检查 GitHub Release → 下载 APK → 系统安装器 |
| G7 | 模板导入/导出（JSON 文件），可跨设备迁移 | 导出→另一台设备导入，题目映射与分组一致 |

### 1.2 非目标（明确不做，避免范围蔓延）

- **不做多平台**：只有 Android APK。不做 iOS / 桌面 / Web / 微信小程序，也不做服务端。
- **不做 App 内日志页**：不提供日志浏览/导出界面；错误现场通过 `SubmitResult.raw` 在结果卡片里折叠展示，进程重启即丢。
- **不做批量压测**：不支持成百上千份、不支持并发 > 5；定位是「教师/班干部手工整理几十份」的量级。并发上限硬编码 clamp 到 5。
- 不做验证码破解/打码平台对接；不伪造 `captchaVerifyParam`。
- 不做代理池 / IP 轮换 / 多账号池 / 请求指纹伪装。
- 不做问卷结果抓取与统计（只提交，不读结果）。
- 不做需要登录/口令/微信授权的问卷（遇到即明确报错）。
- 不做分页/逐题问卷（V1 直接报 `E_PAGED`，不提交半份）。

---

## 2. 分层架构与模块边界

### 2.1 包结构与依赖方向（单向，无环）

```text
        ┌────────────────────────────────────────────────┐
        │  ui/   （T5，唯一允许 import android.*）        │
        │  MainActivity + 页面/适配器 + 权限 + insets      │
        └───────┬──────────┬──────────┬────────┬─────────┘
                │          │          │        │
        ┌───────▼──┐ ┌─────▼────┐ ┌───▼────┐ ┌─▼────────┐
        │ config/  │ │  qr/     │ │ update/│ │ submit/  │
        │ 模板存储  │ │ 扫码解码  │ │ 自动更新│ │ 批量编排  │
        │  (T5)    │ │  (T5)    │ │  (T5)  │ │  (T5)    │
        └──────────┘ └──────────┘ └────────┘ └────┬─────┘
                                                   │
                                            ┌──────▼─────┐
                                            │  wjx/ (T4) │
                                            │  接口引擎   │
                                            └────────────┘
   依赖方向：ui → {config, qr, update, submit}
             submit → {wjx, config}（编排需要引擎与模板类型）
             config → wjx（仅 AnswerPair 数据类型）
             qr / update → 无业务依赖（qr 只输出 URL 字符串）
             wjx → 无（依赖底座，且不 import android.*）
```

**规则（可机械检查）**
1. `wjx/` **不依赖任何其他业务包**，且不 import `android.*`（保证 JVM 单测可跑）。它是全项目的依赖底座。
2. `config/` 只依赖 `wjx.AnswerPair`（数据类型），不依赖 ui/qr/update/submit。
3. `qr/` 只做「图片/相机帧 → 字符串」，**不解析问卷语义**；URL 合法性判断由 ui 调用 `wjx` 的 URL 校验逻辑完成，避免 qr 依赖解析器。
4. `update/` 只做「查版本 → 下载 → 拉起安装器」，不碰问卷逻辑。
5. `submit/`（Lead 2026-09-22 裁决新增）：批量提交编排——并发信号量、每组独立会话、结果聚合与进度回调。依赖 `wjx`（引擎）+ `config`（`MappingTemplate` 类型），**不 import `android.*`**（可 JVM 单测）。
6. `ui/` 是唯一允许 import `android.*` 且直接依赖全部业务包的地方；它**不含任何编排逻辑**（编排在 `submit/`）。

### 2.2 各包职责

| 包 | 职责 | 关键类型 | 不做什么 |
|---|---|---|---|
| `wjx/` | 抓页面 → 解析 `SurveyModel`；字段匹配；构造并发送提交；响应分类 | `SurveyModel`、`WjxSurveyClient`、`WjxSubmitter`、`WjxSubmitCodec`、`WjxErrors` | 不碰 UI、不落盘、不重试 |
| `config/` | 模板 CRUD、`templates.json` 原子读写、导入导出（SAF）、内存 `StateFlow` | `MappingTemplate`、`AnswerGroup`、`TemplateStore`、`ImportReport` | 不发起网络请求 |
| `qr/` | CameraX 实时扫码 + 相册图片解码（zxing），输出候选 URL | `QrScanner`、`ImageDecoder` | 不校验问卷、不联网 |
| `update/` | GitHub Release 检查、APK 下载、FileProvider 安装、版本比较 | `UpdateInfo`、`AppUpdater` | 不自动安装、不静默下载大文件 |
| `ui/` | 三页流程、双栏映射、进度与结果、错误文案、权限申请、insets、验证码兜底 Activity | `MainActivity`、页面适配器、`CaptchaActivity` | 不做并发编排、不直接拼 HTTP、不直接读写 JSON、不替页面提交数据 |
| `submit/` | 批量提交编排：`Semaphore` 并发控制、每组独立会话、结果聚合、进度回调 | `SubmitCoordinator`、`GroupOutcome`、`BatchReport` | 不 import `android.*`、不重试、不碰 UI |

### 2.3 主流程（一次完整使用）

```text
[链接] 扫码/粘贴 ──► wjx.fetch ──► SurveyModel（题目清单）
                                      │
[左栏字段] ──► 双栏映射（ui）◄────────┘   未匹配字段高亮（不静默）
                                      │
[分组答案] ──► config 保存模板 ──► submit/SubmitCoordinator.run(template)
                                      │   Semaphore(clamp(concurrency,1,5))，默认 2
                        ┌─────────────┴─────────────┐
                        ▼（每组：独立 client + 独立 fetch）▼
                   fetch → submit → 分类 → GroupOutcome
                        └─────────────┬─────────────┘
                                      ▼
                          BatchReport（按组序）+ 进度条
                                      ▼
                    失败组可「重试」；E_CAPTCHA 为终态
```

---

## 3. 数据模型与持久化

### 3.1 模型（与 `API-CONTRACT.md` §2/§4 完全一致，此处不重复字段表）

- 运行时：`SurveyModel / SurveyQuestion / Option / AnswerPair`（wjx 包）、`MappingTemplate / AnswerGroup`（config 包）。
- `AnswerPair.field` 是**映射键**：题号字符串、题干片段、或选项文本（匹配优先级 R1>R2>R3，见契约 §7）。
- `AnswerPair.value` 是**答案载荷**：文本原文 / 选项值或选项文本 / 多选用 `|` 连接；空串 = 该题不发送。

### 3.2 持久化：`templates.json`

| 项 | 设计 |
|---|---|
| 位置 | `context.filesDir/templates.json`（App 内部存储，卸载即删） |
| 格式 | UTF-8 无 BOM，2 空格缩进；`schemaVersion=1`；见契约 §4.2/§4.3 |
| 原子写 | 写 `templates.json.tmp` → `flush()` + `fd.sync()` → `renameTo(templates.json)`；任一步失败则删 tmp 并保留旧文件（内存状态不变） |
| 损坏回退 | 启动/导入解析失败 → 原文件改名 `templates.json.bad-<epochMillis>` → 以空配置启动 + UI 提示。**绝不静默清空**，**绝不用空配置覆盖坏文件** |
| 并发写 | 全部写操作串行化在单一 `Mutex`/单线程 dispatcher 内；内存为唯一真源（`StateFlow`），编辑完成后整体落盘 |
| 导入/导出 | SAF：`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`（mime `application/json`）。导出文件名 `wjx-templates-<yyyyMMdd-HHmmss>.json`。**无需任何存储权限**（minSdk 24 起 SAF 全可用） |
| 兼容策略 | 缺失 `schemaVersion` 视为 1；`>1` 整体拒绝并提示升级；未知字段忽略；缺可选字段取默认；单模板失败不影响同批其他模板（`ImportReport.failures`） |
| 隐私 | 仅本地；`allowBackup` 建议改 `false`（详见 §6） |

**为什么不用数据库**：模板数量在几十量级，单文件 JSON 便于导入导出、便于人工检查、便于测试；Room 会引入注解处理器与构建负担（本机 2GB 内存，构建资源紧张）。

**为什么必须原子写**：问卷配置含用户逐字录入的答案，半写文件（尤其 Android 低电量/被杀进程）会导致整个配置丢失，代价远高于一次 `rename` 的复杂度。

---

## 4. 错误分类与用户可读文案

契约 §8 是权威表（错误码 + 逐字文案 + httpStatus）。设计要点：

### 4.1 分类原则

1. **机器码与人类文案分离**：`SubmitResult` 有 additive 字段 `errorCode: String? = null`（Lead 2026-09-22 裁决）——`errorCode == null` ⟺ 成功；`message` 只放人类文案。UI 只读 `errorCode`，**禁止文案匹配**（文案会改，码不会）。
2. **本地错误不发请求**：`E_URL / E_EMPTY / E_UNMATCHED / E_LIMIT / E_UNSUPPORTED` 一律 `httpStatus=0`、`raw=null`，避免无意义网络流量与风控计数。
3. **HTTP 200 ≠ 成功**：问卷星用 200 返回业务错误；成功判定集中在 `WjxResponseClassifier`（契约 §8.4）。业务码有**两种形态**：`<码>〒<文案>`（如 `7〒需要安全校验，请重新提交！`）与**裸码**（T11 实测 `ktimes=0` 时回裸 `22`，无 `〒` 无文案）——解析器必须两种都支持，且**先解析业务码再退回关键词启发式**。
4. **失败必须可定位**：未匹配字段要带字段名与候选题号；服务端拒绝要带服务端原文。
5. **不自动重试**：提交非幂等，重试由用户显式触发。

### 4.2 文案与用户动作

| 分类 | 错误码 | 用户可见文案（要点） | UI 动作 |
|---|---|---|---|
| 链接无效 | `E_URL` | 链接无效：请填写 https://www.wjx.cn/vm/xxxx.aspx 形式的问卷链接 | 输入框红字；扫码页保持扫描 |
| 网络失败 | `E_NETWORK` | 网络连接失败，请检查网络后重试 | 「重试」按钮 |
| 服务器异常 | `E_HTTP` | 问卷服务器返回异常（HTTP 404/502…） | 「重试」按钮 + 可展开 raw |
| 解析失败 | `E_PARSE` | 问卷页面解析失败，可能是问卷已关闭或页面改版 | 提示换链接；可展开 raw |
| 分页问卷 | `E_PAGED` | 该问卷为分页/逐题模式，暂不支持自动填写 | 终态提示（不提供重试） |
| 验证码拦截 | `E_CAPTCHA` | 该问卷开启了安全校验（阿里云验证码），纯接口无法提交 | **终态**提示；提供「人工验证后重试」（§12，**每组最多 1 次**；文案见 `res/values/strings.xml`）；不引导绕过。T3 实测样本问卷返回 `7〒需要安全校验，请重新提交！`（HTTP 200） |
| 未匹配字段 | `E_UNMATCHED` | 字段未匹配：「学号」未匹配到任何题目 / 匹配到多个题目（1,2） | 跳回映射页并高亮该字段 |
| 无答案 | `E_EMPTY` | 没有可提交的答案 | 跳回分组编辑 |
| 超长 | `E_LIMIT` | 答案超过 3000 字上限（题号 2） | 定位到该题 |
| 题型不支持 | `E_UNSUPPORTED` | 题号 5（MATRIX）暂不支持自动填写 | 提示手工填写该题 |
| 服务端拒绝 | `E_REJECTED` | 问卷服务端拒绝：<服务端原文，如「请输入正确的学号」> | 原文展示；可重试 |
| 兜底 | `E_UNKNOWN` | 未知错误：<摘要> | 展示 raw |

---

## 5. 跨设备兼容性设计（「全新设备也能装」）

> 用户原话要求：全新设备也要能装。以下每条都是结构性保证，改动会破坏该保证，须在评审中显式确认。

### 5.1 安装与 ABI

| 项 | 设计 | 依据 |
|---|---|---|
| minSdk | **24**（Android 7.0） | 覆盖 2016 年以后设备 |
| targetSdk / compileSdk | 35 | 与 SDK 平台一致 |
| abiFilters | **不设置** | 不裁剪 ABI，产物为通用包 |
| 自研 native 代码 | **零**（无 NDK、无 CMake、无 .so 源码） | 工程内无 `externalNativeBuild` |
| 依赖带来的 .so | CameraX `camera-core:1.4.1` 自带 2 个 .so × **4 个 ABI**（arm64-v8a / armeabi-v7a / x86 / x86_64） | 本机实测 `camera-core-1.4.1.aar` 的 `jni/` 目录含 4 个 ABI 各 2 个 .so |
| 结论表述（修正） | **「不设 abiFilters + 4 个 ABI 齐全」**，而不是「APK 内无 .so」 | CameraX 的 .so 必须随包分发，否则相机不可用 |
| 64 位支持 | arm64-v8a 在包内 | 满足新设备/应用商店 64 位要求 |
| 其他 .so 来源 | 无（zxing 是纯 Java；AndroidX 无 native） | 依赖清单核对 |

> ⚠️ 与 T6 验收标准的冲突（已上报 Lead）：T6 描述里的「APK 内无 .so」与实测矛盾。正确判据应为「无自研 native 代码 + 4 ABI 齐全 + 未设 abiFilters」。若坚持「无 .so」，只能移除 CameraX（放弃实时扫码），与 G1/G5 冲突。

### 5.2 运行时依赖

- **无 GMS**：只依赖 AndroidX + `com.google.zxing:core`（纯 Java）+ `kotlinx-coroutines-android`。不引 `play-services-*`、不引 Firebase；无 GMS 的国行机/平板/模拟器均可运行。
- **无 Termux**：APK 自包含，不依赖 Termux、不依赖外部二进制、不 exec 任何进程。
- **无网络框架**：`java.net.HttpURLConnection`，避免 OkHttp 版本与 TLS 兼容负担。
- 相机声明 `android.hardware.camera.any` 为 **required=false**：无相机设备也能安装，自动降级为「相册导入 + 手动粘贴链接」。

### 5.3 API 级别与静态门禁

| 风险点 | 处理 |
|---|---|
| 调用高于 minSdk 的 API | `lint { abortOnError = true; checkReleaseBuilds = true }`，**NewApi 为 error**；所有新 API 调用必须 `Build.VERSION.SDK_INT` 守卫或 `@RequiresApi` |
| Android 12+ 组件必须显式 `exported` | Manifest 已显式声明：`MainActivity exported=true`（有 LAUNCHER intent-filter）、FileProvider `exported=false` |
| Android 14+（targetSdk 34+）隐式 intent 限制 | 只发显式/系统标准 intent（`ACTION_VIEW` + package-archive mime、SAF、`ACTION_VIEW` 打开浏览器），不做隐式广播 |
| Android 15/16 强制边到边（targetSdk 35） | 布局不依赖状态栏高度；用 `ViewCompat.setOnApplyWindowInsetsListener` 把 `systemBars` insets 转成根布局 padding；根布局 `fitsSystemWindows=false`；列表底部额外留 IME/导航栏间距（`adjustResize` 已在 Manifest） |
| Android 13+ 照片选择器 | 用 `ActivityResultContracts.PickVisualMedia`（AndroidX 1.9.3）：13+ 走系统照片选择器，**< 13 自动回退到 `ACTION_OPEN_DOCUMENT`**；不需要 `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE` 权限 |
| Android 6+ 运行时权限 | 相机权限 `CAMERA` 运行时申请；拒绝后仍可用相册与手输（功能降级而非崩溃） |
| Android 8+ 未知来源安装 | 声明 `REQUEST_INSTALL_PACKAGES`；未授权时系统安装器会自行引导，App 不尝试绕过 |
| Android 10+ 分区存储 | 不写外部存储；导出走 SAF；因此**不申请任何存储权限** |
| 明文流量 | `android:usesCleartextTraffic="false"`；所有请求 https（契约 §5） |
| 主题兼容 | AppCompat 主题（`Theme.WjxAutofill`），不用 Material3 动态取色，避免新系统外观漂移 |
| 深色模式 | 不单独做深色资源（V1），保证浅色在深色系统下仍可读（避免硬编码白字白底） |

---

## 6. 安全与隐私

| 主题 | 设计 |
|---|---|
| 数据范围 | 仅本地保存用户自己录入的答案与问卷链接；**不采集设备标识、不上报、无埋点、无广告、无崩溃上报 SDK** |
| 存储位置 | `filesDir/templates.json`（内部存储，其他 App 不可读） |
| 系统备份 | **建议 `android:allowBackup="false"`**：配置含姓名/学号等个人信息，避免被系统云备份带离设备。若必须保留备份能力，则用 `dataExtractionRules`/`fullBackupContent` 显式排除 `templates.json` |
| 传输安全 | **https-only**：链接校验强制 https + `usesCleartextTraffic=false` + 重定向后校验最终 host ∈ wjx.cn（防开放重定向） |
| 凭据 | 不保存问卷星账号/cookie 到磁盘；cookie 仅存在于单次 `fetch→submit` 会话的内存 CookieJar 中，会话结束即丢 |
| 更新链路 | 只信任 `https://api.github.com/repos/zimu5683/wjx-auto-filler/releases/latest` 与 `browser_download_url`；APK 由系统安装器校验签名后才可覆盖安装；可选加固：若 Release 附带 `.sha256`，下载后校验不一致则拒绝安装 |
| 签名一致性 | release 必须用固定 keystore（本机 keystore 不入库；CI 从 secrets 还原）；签名不一致会导致「无法覆盖安装」，这是更新链路的第一大坑 |
| 边界行为 | 不破解验证码、不伪造 `captchaVerifyParam`、不做请求指纹伪装；遇到验证码明确失败并如实告知用户 |
| 权限最小化 | INTERNET / ACCESS_NETWORK_STATE / CAMERA / REQUEST_INSTALL_PACKAGES（前两项必需；CAMERA 可拒绝降级；安装权限仅用于自更新） |

---

## 7. 自动更新设计（对齐 yikou 参考实现）

逻辑与 `yikou-light-food-server/.../AppUpdater.kt` 对齐（同一套语义，换仓库名与包名）：

1. `GET https://api.github.com/repos/<owner>/<repo>/releases/latest`（`Accept: application/vnd.github+json`、自定义 UA、10s 超时）。
2. `tag_name` 去 `v` 前缀 → 语义化版本 `x.y.z`；与 `BuildConfig.VERSION_NAME` 做逐段比较，**不更新则静默返回**。
3. 遍历 `assets`，取第一个 `.apk`（本工程只有一个通用包，不做 arm64 优先分支）；**没有 APK 资产的 Release 不提示更新**（避免用户点了下不到可安装文件）。
4. 下载到 `cacheDir/updates/wjx-autofill-<version>.apk`（15s/60s 超时）；失败则回退「打开 `html_url` 浏览器页」。
5. 安装：`FileProvider.getUriForFile(context, "${packageName}.fileprovider", apk)` → `ACTION_VIEW` + `application/vnd.android.package-archive` + `FLAG_GRANT_READ_URI_PERMISSION|FLAG_ACTIVITY_NEW_TASK`。
6. 触发时机：启动后静默检查（节流：24h 一次）+ 设置页「检查更新」手动触发；GitHub API 未认证限流 60 次/小时，节流是必需的。
7. 版本真源：`android/version.properties`（`versionName`/`versionCode`），CI 校验 Release tag == `v<versionName>`。

---

## 8. 扫码与图片解码设计

- **实时扫码**：CameraX `PreviewView` + `ImageAnalysis`（`STRATEGY_KEEP_ONLY_LATEST`，分析节流约 5 fps）→ YUV_420_888 → `PlanarYUVLuminanceSource` → zxing `MultiFormatReader`（只开 `QR_CODE` 提示，降低误识别）→ 后台 Executor 解码。
- **相册导入**：`PickVisualMedia` 取图 → `BitmapFactory`（`inSampleSize` 降到最长边 ≤1600px，防 OOM）→ `RGBLuminanceSource` → zxing 解码。
- **同一套解码器**服务两条路径（`qr/` 内单一 `QrDecoder`），避免行为不一致。
- **链接提取**：二维码内容可能是纯 URL、也可能夹带文字/多个 URL。规则：对 payload 做 `https?://[^\s]+` 提取，逐个用问卷链接正则匹配，取**第一个**命中者；命中即停止相机并回填。
- **失败路径**：非问卷星二维码 → 提示「不是问卷星链接，请扫描问卷二维码」并继续扫描；相机权限被拒 → 引导到相册/手输。
- **只读不改**：图片只解码，不写入相册、不上传。

---

## 9. 构建、发布与资源约束

| 项 | 约束 |
|---|---|
| 本机内存 | 约 2GB 可用（有 swap）→ **同一时间只允许一个 Gradle 构建**，构建必须用后台任务；`gradle.properties` 已限制 `-Xmx2560m`、`kotlin.daemon.jvmargs=-Xmx1536m`、`org.gradle.parallel=false` |
| 本机特殊参数 | 构建必须带 `-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2`（SDK 自带 aapt2 是 x86-64，本机跑不了） |
| 工具链 | JDK 17 / Gradle 8.11.1 / AGP 8.9.1 / Kotlin 2.1.20 / compileSdk 35 / build-tools 35.0.0 |
| 签名 | 本机 keystore 与 CI secrets 必须是同一把；`*.jks/*.keystore` 已 gitignore，严禁入库 |
| 产物 | `dist/wjx-auto-filler-<version>.apk` + `.sha256`；Release tag `v<versionName>` |
| CI | GitHub Actions（ubuntu-latest）：`test` → `lintRelease` → `assembleRelease` → 校验 minSdk/ABI/无 GMS → 上传 artifact；tag 触发发布 Release |
| 发布验证 | 先发 v1.0.0 提供 APK，再发 v1.0.1 验证应用内更新链路（G6） |

---

## 10. 测试策略（与 T6 对齐）

| 层 | 内容 | 依赖 |
|---|---|---|
| 单元测试（JVM） | codec 测试向量（契约 §3.4）、HTML fixture 题型判定（§6.2）、字段匹配与值解析（§7）、错误分类器（§8.4）、版本比较、模板导入导出（含 schemaVersion=2 拒绝、坏 JSON 备份）、`SubmitCoordinator` 并发上限与结果顺序 | 无网络、无 Android 运行时 |
| 契约测试 | 用 fake `WjxSurveyClient`/`WjxSubmitter` 驱动 ui 层，断言错误码与 UI 分支 | 无网络 |
| 手工联调 | 对样本问卷真实 `fetch`（只读）验证解析；提交实测：T3（`useAliVerify=1` 样本）被业务码 7 拦截；**V6 干净 A/B 在 `useAliVerify=0` 样本上取得纯 HTTP 成功**（`10〒/complete.aspx?joinid=127844297308`）。对 `useAliVerify=1` 问卷的端到端验证仍需走 §12 兜底 | 真实网络（仅 `useAliVerify=1` 时需用户配合） |
| 打包校验 | `aapt dump badging`：package/minSdk=24/targetSdk=35、`native-code` 列出 4 ABI、无 GMS 依赖；APK 内 `.so` 仅来自 CameraX | 构建产物 |

> 注意：JVM 单测里 `org.json` 是空壳（`unitTests.isReturnDefaultValues = true` 会让它返回 null）。Lead 已给 `android/app/build.gradle.kts` 加 `testImplementation("org.json:json:20231013")`。补充事实：`config/` 用自写 MiniJson（纯 Kotlin，刻意不用 org.json），但 `update/AppUpdater.kt` 用 org.json，故该测试依赖仍然必要。

---

## 11. 风险与缓解

| # | 风险 | 影响 | 概率 | 缓解 / 兜底 | 责任 |
|---|---|---|---|---|---|
| **R1** | **服务端在响应中要求安全校验（业务码 7/22）** —— `useAliVerify=1` 的样本（T3）必被拦；T14/V6 证明 `useAliVerify=0` 在 `ktimes≥4` + 最小请求形态下**可纯接口成功**（joinid=127844297308）；用户本人也确认该问卷微信扫码填写未弹验证 | 被拦时纯接口拿不到成功响应（仅影响这类问卷） | 中 | ① **不做本地门控，总是先试一次**（避免假阴性：`useAliVerify=1` 也可能本可提交；多一次被拒请求不产生答卷）；② 收到 7/22 → §12 兜底（每组 1 次）；③ `ktimes` 下限 4（已验证值）+ 不补发校验类字段 | api-debug（证据）/ Lead（决策）/ architect（契约） |
| R2 | 问卷星页面改版导致解析失败 | 全部问卷不可用 | 中 | 解析与分类逻辑集中在 2 处；失败返回 `E_PARSE` 并保留 raw；URL/表单/隐藏域解析带多重回退（契约 §6.4） | T4 |
| R3 | 服务端风控（同 IP 高频提交） | 提交被拒/账号受限 | 中 | 默认并发 2（上限 5）、每组独立会话、**不自动重试**、用户显式触发；可选进一步缓解：组间 300–800ms 随机延迟（需 Lead 批准） | T5 |
| R4 | 本机 2GB 内存下 Gradle OOM | 构建失败、拖慢全队 | 高 | 单构建串行、后台任务、堆已限制；`--no-daemon` 备选；失败重试前先确认无并发构建 | 全队 |
| R5 | 签名不一致导致更新无法覆盖安装 | G6 失败 | 中 | 固定 keystore；CI 从 secrets 还原；本地与 CI 用同一把；Release 前校验 `apksigner verify` | T6/T7 |
| R6 | 问卷需要登录/口令/微信授权 | 该问卷不可用 | 中 | 明确报错（`E_PARSE`/`E_REJECTED`）；列入非目标 | T4/T5 |
| R7 | 分页/逐题问卷 | 只提交首页，数据污染 | 中 | 契约 §6.5 检测 → `E_PAGED`，**不提交半份** | T4 |
| R8 | 样本问卷只有填空题，单选/多选/矩阵无法端到端验证 | 隐性 bug 带到用户 | 高 | 单测 HTML fixture 覆盖每种题型；文档与 README 明示「已在填空题上端到端验证，其他题型为单测覆盖」 | T6/T7 |
| R9 | GitHub API 限流（未认证 60/h） | 检查更新失败 | 低 | 24h 节流 + 手动触发；失败静默（更新不是主流程） | T5 |
| R10 | 用户答案含个人信息，合规风险 | 隐私投诉 | 低 | 仅本地存储、不上传、不埋点；`allowBackup=false`；不保存 cookie | T5 |
| R11 | 相机权限被拒/无相机设备 | 扫码不可用 | 中 | `required=false`；降级到相册与手输链接；不崩溃 | T5 |
| **R13** | **前台服务被国产 ROM 杀掉**（EMUI/MagicOS/MIUI 等），到点不准时 | 自动提交失败或延迟，用户以为已提交 | 高 | `START_STICKY` 重建 + 恢复任务 + **catch-up**（已过点立即提交）；常驻通知 + 白名单引导；UI 如实提示；**不承诺 100% 准时** | T5 / T7（文档） |
| **R14** | 通知权限被拒（Android 13+ `POST_NOTIFICATIONS`） | 用户看不到任务状态与提醒 | 中 | 服务仍可运行；UI 提示开启通知权限；状态条在 App 内可见 | T5 |
| **R15** | 前台服务启动被系统拒绝（`SecurityException`/FGS 限制） | 自动提交不生效 | 中 | **不得崩 App**：降级普通后台协程 + 通知 + UI 显式反馈「可能不准时」 | T5 |
| **R16** | 设备重启后任务不恢复（**刻意不做 `BOOT_COMPLETED`**） | 用户以为任务还在，实际已失效 | 中 | 交付文档写明；App 启动时提示「有未完成任务，需重新开启自动提交」；UI 不谎报状态 | T5 / T7（文档） |
| **R12** | **请求形态触发风控（不是问卷属性）**：早期观测「`useAliVerify=0` 仍返回裸 22」曾被解读为「服务端主动要求二次校验」；**V6 单变量 A/B 证明裸 22 由 `ktimes=0` 触发**（0→4 后同一问卷返回 `10〒` 成功）。另实测**补发 `rn`/`captchaVerifyParam`/`sceneId` 会把成功（10）变成 `7〒需要安全校验`** | 请求形态不对，本可成功的问卷也会失败；还会误导我们判定「该问卷需要验证码」 | **已发生（已修正）** | ① `&ktimes = max(4, pageKtimes)`（契约 §5.3；下限取 V6 **已验证值 4**）；② V1 **不补发** `rn`/`lct`/`jpm`/`cst`/`source`/`captchaVerifyParam`/`sceneId`；③ 交付文档不得声称「页面开关决定能否提交」 | api-debug（证据）/ architect（契约）/ T5 |

**R1 已实测确认且已缓解**：T3 实测样本问卷被业务码 7 拦截（HTTP 200 + `7〒需要安全校验，请重新提交！`），T3.5 进一步确认门控信号是 `useAliVerify`（6/6 问卷 `captchaType='2'`，用它门控会判死全部问卷）。用户已批准仅验证码环节用 App 内 WebView 兜底（§12），这也是**唯一的端到端验证手段**（§12.8）。

---

## 12. 验证码兜底（WebView）设计（T9，用户已批准）

### 12.1 为什么需要

- T3.5 实测：6 个问卷的 `captchaType` 全为 `'2'`，只有 `useAliVerify=1` 会被强制安全校验；用户自己的问卷正是 `useAliVerify=1`。
- **T14 / V6 修正（api-debug 单变量 A/B）**：`useAliVerify=0` 的问卷**可以纯接口提交**——早期「`useAliVerify=0` 仍返回裸 22」由 **`ktimes=0`** 引起（0→4 后同一问卷返回 `10〒/complete.aspx?joinid=127844297308` 成功）。`useAliVerify=1` 的问卷仍需兜底。**兜底既不是万能钥匙，也不是唯一出路。** **Lead 2026-09-22 改判：本地 `useAliVerify` 门控已取消** —— 无论 `useAliVerify` 为何值都**先发一次提交**，只有响应 7/22 才走兜底（假阴性防护）；`useAliVerify` 仅作展示。
- 纯 HTTP 提交被服务端以业务码 7 拦截（HTTP 200 + `7〒需要安全校验，请重新提交！`），**没有纯 HTTP 的绕法**（也不该有）。
- 用户已批准：**仅验证码环节**用 App 内 WebView 由用户人工完成；**数据提交仍走纯 HTTP 接口**。这是「纯接口填表」定位下唯一可行且不越界的补救路径。

### 12.2 设计原则

1. **验证码交给人，数据提交留给引擎**：WebView 只做两件事——唤起页面自身的验证码控件、读回令牌；引擎继续用 `HttpURLConnection` 提交。
2. **不绕过风控**：不伪造令牌、不打码、不做请求指纹伪装；只是把「人机验证」这一步交还给人。
3. **显式触发**：必须用户点「人工验证后重试」；不自动弹 WebView、不自动重试。
4. **失败可读**：所有兜底失败仍是 `E_CAPTCHA` 终态 + 明确的人类文案（不新增错误码，保持错误表稳定）。
5. **不做本地门控（避免假阴性）**：`useAliVerify` 只是页面初始值，本地判 `E_CAPTCHA` 会把本来能提交的问卷判死（一次请求都不发）；正确做法是**总是先尝试提交**，由服务端响应决定是否走兜底。代价仅是多一次被拒请求（不产生答卷）。
6. **不做预检提交（用户决策）**：只做**页面层读取**（`useAliVerify` 值 → `needsCaptchaHint`）+ **响应层判定**（7/22 → `E_CAPTCHA`），**不发起任何额外探测请求**（不为探安全门而故意发缺答/空答）。`needsCaptchaHint=false` 只是「页面层未提示」，权威结论只来自真实提交的响应。

### 12.3 组件与时序

组件：`ui/CaptchaActivity.kt`（WebView，T5）+ 引擎的 `fetch(url, cookies)` / `submit(m, answers, captchaToken)`（T4）。
时序严格按契约 §13.2 的 12 步：抓页面 → 判 `E_CAPTCHA` → 用户点按钮 → WebView 载入真实问卷 URL → 注入 `loadCaptchShow()` 唤起验证码 → 用户完成验证 → 收割 `captchaVerifyParam`/`captchaSceneid` → **同会话 cookie 重抓页面**（拿新 jqnonce） → 带令牌 POST → 清理会话 cookie。

关键约束：**令牌单次有效**，收割后 60 秒内必须完成重抓+提交；否则按过期处理，提示重新验证。

additive 字段 `sceneId`（Lead 2026-09-22 已批准）由引擎在 `fetch` 时解析、令牌非空时写入提交 body；**缺省则不携带该字段**（本地不拦截，由服务端判定）。兜底 UI 只需透传 `CaptchaHarvest`，不需感知该字段。

### 12.4 Cookie 会话一致性

WebView（`android.webkit.CookieManager` 全局单例）与引擎（每实例独立 `java.net.CookieManager`）之间必须**双向**注入：
- 打开验证页前：引擎 cookie → WebView（逐条 `setCookie("https://www.wjx.cn/", "k=v")` + `flush()`）；
- 收割令牌后：WebView 全量 cookie → `fetch(url, cookies = map)`；
- 结束即清理 wjx 域会话 cookie（逐条置空，**不用** `removeAllCookies()`，避免误伤其他会话）。

只做单向 = 令牌与会话不匹配 = 服务端继续回 7。规则细节见契约 §13.3。

### 12.5 硬边界（与「模拟前端操作」的分界线）

- WebView 中**不填任何表单字段、不点任何提交入口、不把答案数据传给页面**；注入脚本只有两个固定常量（唤起验证码 / 读令牌）。
- 数据提交永远由 `WjxSubmitter` 完成。
- 可机械审查（与实现形态一致）：`evaluateJavascript` 调用点 **1 个**（私有 helper 内）、脚本仅 **2 个固定常量**、**无动态拼接**、真实 `@JavascriptInterface` **0 个**、无点击提交入口。
- 这条边界写进契约 §13.4 与 §13.10 的 DoD，qa-build 做静态检查。

### 12.6 降级与体验

| 场景 | 表现 |
|---|---|
| 无 WebView / 内核不可用 | `E_CAPTCHA`「设备无法打开验证页面，请在浏览器中手工填写该问卷」，隐藏兜底按钮 |
| 用户取消 | `E_CAPTCHA`「已取消人工验证」，**不消耗**该组机会（按钮保留，可再次点击） |
| 页面改版（无 `loadCaptchShow`） | `E_CAPTCHA`「验证页面结构已变化…」，隐藏按钮 |
| 180 s 未收割到令牌 | `E_CAPTCHA`「未检测到验证结果，请重试」，保留按钮 |
| 令牌过期 / 再次回 7 | `E_CAPTCHA`「验证已过期，请重新验证」，保留按钮 |
| 该组已用过 1 次机会 | 该组隐藏入口（`captcha_exhausted` 单次语义文案，其他组不受影响） |

UI 判定只看 `errorCode + 已兜底组集合`（每组最多 1 次；**取消不消耗，超时/失败消耗**；结构性失败按钮立即隐藏；多组逐组处理），不做文案匹配；文案以 `res/values/strings.xml` 为唯一真源。

### 12.7 安全

https-only + 顶层导航白名单（仅 `*.wjx.cn`）、禁用文件访问/多窗口、`MIXED_CONTENT_NEVER_ALLOW`、允许第三方 cookie（阿里云验证码需要）、无 JS Bridge、Activity 不 exported、`onDestroy` 销毁 WebView 并清理会话 cookie。完整配置见契约 §13.8。

### 12.8 端到端验证状态（交付文档必须写明）

- **纯接口端到端已实测成功（V6）**：`useAliVerify=0` 的样本 `https://v.wjx.cn/vm/P2M09FG.aspx`，在 `ktimes≥4`（契约下限）且不补发 `rn`/`captchaVerifyParam`/`sceneId` 的条件下返回 `10〒/wjx/join/complete.aspx?...joinid=127844297308` —— 这是本项目第一条**纯 HTTP 成功提交**证据（单变量 A/B，见契约 §0/§5.3/§11.3）。
- **`useAliVerify=1` 的问卷（如 T3 样本）仍必须走 §12 兜底**：服务端强制安全校验，纯 HTTP 必然被业务码 7 拦截；对这类问卷，兜底是**唯一**的端到端手段（用户在自己设备上点一次验证码 → 引擎带令牌提交 → 观察业务码 `10`）。
- 用户不执行兜底时，对 `useAliVerify=1` 的问卷只能提供「解析/匹配/编码/分类/兜底时序」的**单测级证据**。
- README/USAGE 需保持「不绕过风控，只把人机验证交还给人」的叙事，并如实写明：**能否纯接口成功取决于请求形态（`ktimes>0`、不补发校验类字段），不取决于页面开关本身**。

### 12.9 新增风险

| 风险 | 缓解 |
|---|---|
| 令牌短命导致「验证完还是失败」 | 60 s TTL 常量 + 明确文案 + 每组 1 次重试机会（取消不消耗） |
| 页面改版（`loadCaptchShow`/全局变量改名） | 固定常量 + `NO_FN` 明确失败，不用正则"猜"令牌 |
| 用户不配合验证 | 单测级证据 + 交付文档如实说明（§12.8） |
| WebView cookie 与引擎不一致 | 双向注入 + 结束清理（契约 §13.3） |

---

## 13. 定时提交与前台服务设计（T17）

### 13.1 用户需求与决策
用户已确认：**只做前台常驻服务**（不做 `AlarmManager`/`WorkManager`）；到点遇人机验证**只推通知、不弹界面**；提前提醒**可配置、默认 10 分钟**；**同一时刻只允许 1 个定时任务**。
目标：问卷未开放时用户可设「到点自动提交」，App 在前台服务里等到开放时间，再调用既有 `SubmitCoordinator`（**复用，绝不重写提交逻辑**）。

### 13.2 冻结签名（Lead 2026-09-22）

```kotlin
package com.wjx.autofill.schedule

enum class TaskState { ARMED, REMINDED, RUNNING, DONE_OK, DONE_FAIL, CANCELLED }

data class ScheduledTask(
    val templateId: String,
    val templateName: String,
    val openAtMillis: Long,
    val leadMinutes: Int = 10,          // 提前提醒分钟数，用户可设
    val createdAtMillis: Long,
    val state: TaskState,
)

/** 纯函数，必须可 JVM 单测 */
object ScheduleMath {
    /** 提醒时刻 = openAtMillis - leadMinutes*60_000 */
    fun remindAtMillis(task: ScheduledTask): Long
    /** 归一化提前量：越界 clamp 到 0..1440（默认值 10 由 ScheduledTask 构造默认值承担） */
    fun normalizeLeadMinutes(input: Int): Int
}

interface ScheduledTaskStore {
    fun load(): ScheduledTask?
    fun save(task: ScheduledTask): Boolean
    fun clear(): Boolean
}
```

唯一实现：`FileScheduledTaskStore(rootDirectory: File) : ScheduledTaskStore` —— 纯 `java.io` + `config/MiniJson`，**不 import `android.*`**，可 JVM 单测；文件 `filesDir/schedule.json`，`schemaVersion = 1`，原子写（`.tmp` → `flush` + `fd.sync()` → `rename`），损坏回退备份 `schedule.json.bad-<epochMillis>`。

**触发语义（qa-build 按此写测试）**

| 情形 | 行为 |
|---|---|
| `now < remindAtMillis` | 等到 `remindAtMillis` → 推提前提醒 → `REMINDED` |
| `remindAtMillis <= now < openAtMillis` | **立即补一次提前提醒**（只推一次，不重复） |
| `now >= openAtMillis`（服务重建/创建时已过点） | **直接执行提交**（catch-up），不再提醒 |
| 提交成功 / 失败 | `DONE_OK` / `DONE_FAIL`（结果落盘 + 通知） |
| 用户取消 | `CANCELLED`（终态） |

**提前量边界**：`0` → 提醒时刻 = 开放时刻；负值 → clamp 到 `0`；`> 1440` → clamp 到 `1440`。

### 13.3 为什么用 `specialUse` 而不是 `dataSync`

| 类型 | 结论 |
|---|---|
| `dataSync` | **不用**。语义是「数据同步/上传下载」；且 Android 15+ 对 `dataSync` 前台服务有**每日累计时长上限**（约 6 小时）——我们的任务可能数小时甚至数天后才触发一次，长挂起会被系统掐断，用途声明也不实 |
| `shortService` | 不用：只适合分钟级一次性工作，覆盖不了「等待开放时间」 |
| **`specialUse`** | **采用**。清单 `android:foregroundServiceType="specialUse"` + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="scheduled_survey_submission"/>`；API 34+ 启动时用 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`。这是「不适用既有分类的合法前台用途」的标准做法，语义如实 |

### 13.4 生命周期与降级
- `START_STICKY`；`onStartCommand` 启动时**从持久化恢复任务**（`FileScheduledTaskStore.load()`）。
- **启动失败不得崩 App**（`SecurityException` / `ForegroundServiceStartNotAllowedException` 等）→ 降级为普通后台协程 + 通知，并在 UI 显式反馈「自动提交可能不准时」。
- 权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`（Android 13+ 运行时申请）、`WAKE_LOCK`（到点执行期间短时持有）。
- 通知渠道 3 条：任务常驻 / 提前提醒 / 结果（含「需要人机验证」）。
- 到点遇验证码：**只推通知、不弹界面**（用户已确认）。

### 13.5 被国产 ROM 杀掉的风险与兜底
国产 ROM（EMUI / MagicOS / MIUI 等）会激进清理后台，**即使有前台通知**也可能被杀。缓解（**不承诺 100% 准时**）：
1. 常驻通知 + 引导用户加白名单（跳系统电池优化/自启动设置）；
2. `START_STICKY` 被杀后重建 → 恢复任务 → **catch-up**（已过开放时间立即提交）；
3. UI 如实提示「服务可能被系统回收，建议保持 App 在最近任务中」；
4. 降级路径（普通后台协程）保证 App 进程存活期间仍能触发。

### 13.6 只允许 1 个任务
`FileScheduledTaskStore` 为**单任务**语义：`save(task)` **覆盖**现有任务；UI 新建时若已有任务，必须先确认覆盖（或先 `clear()`）。同一时刻只有 1 个等待协程、1 条常驻通知。

### 13.7 边界：不做 `BOOT_COMPLETED`
**刻意不注册** `RECEIVE_BOOT_COMPLETED`、不监听开机广播。后果（**必须写进交付文档**）：**设备重启后定时任务不会自动恢复**，用户需重新打开 App 才会恢复调度。理由：不再多要一个权限、避免开机自启带来的后台启动限制与厂商拦截，符合「只做前台常驻服务」的用户决策。

---

## 14. 与冻结契约的关系

- 本文件**不重复**签名、schema、错误码逐字文案：全部以 `docs/API-CONTRACT.md` 为唯一真源。
- 若本文件与契约冲突，**以契约为准**，并回写本文件（维护者：architect）。
- 任何签名变更：先 `send_message` 给 lead → 获批后同时更新契约 §2 与「变更记录」→ 再通知 T4/T5/T6。

---

## 变更记录

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-09-22 | 初版：目标/非目标、分层架构、持久化、错误分类、跨设备兼容（修正 .so 表述）、安全隐私、更新/扫码设计、构建发布、测试策略、风险表 | architect |
| 2026-09-22 | Lead 裁决：`SubmitResult` 增 `errorCode`；`SubmitCoordinator` 迁到新包 `submit/`（架构图/依赖规则/职责表同步）；T3 实测回填 R1（验证码强制拦截，G3 验收口径调整、手工联调结论更新） | Lead / api-debug / architect |
| 2026-09-22 | Lead 改判：E_CAPTCHA 门控改为 `useAliVerify`（T3.5 证据）；新增 §12 验证码兜底（WebView）设计（用户已批准），R1 改为「已确认且已缓解」，原 §12 顺延为 §13 | Lead / 用户 / architect |
| 2026-09-22 | Lead 批准 `SurveyModel.sceneId: String? = null`（additive）；提交时令牌非空且 sceneId 非空才写入 body，缺省不携带（T9 验收通过） | Lead / architect |
| 2026-09-22 | Lead 最终裁定：每组 1 次兜底、不设跨组上限、按钮按「未兜底组集合」显示并逐组处理；**消耗判据：取消不消耗，超时/失败消耗，结构性失败不消耗但立即终态**；修正多组需验证时只有第一组有入口的功能缺陷；`captcha_exhausted` 改单次语义 | Lead / android-dev / architect |
| 2026-09-22 | T14：URL 正则放宽为 wjx.cn 任意子域（`v.wjx.cn` 短链实测暴露缺陷）；业务码 22 补为服务端实测确认；新增 R12（`useAliVerify=0` ≠ 免验证，兜底为常规路径） | Lead / architect |
| 2026-09-22 | T14/V6 修正：**撤回**「`useAliVerify=0` ≠ 免验证」结论（裸 22 实为 `ktimes=0` 风控）；`ktimes` 下限定为 `max(4,·)`（取已验证值 4）；补发 `rn`/`captchaVerifyParam`/`sceneId` 会把 10 变 7；R12 重写、§12.8 改为「纯接口已 E2E 成功」、§10 手工联调同步 | Lead / api-debug / architect |
| 2026-09-22 | Lead 改判：**取消 `useAliVerify` 本地门控**（总是先试，避免假阴性）；`ktimes` 下限改为**已验证值 4**（`max(4,·)`，注意 0/1/2/3→4 会改变 jqsign）；§13.3 Cookie 基准改为**问卷 URL 的 origin**（`CookieHeader.originOf`，修复 `v.wjx.cn` 子域注入失效） | Lead / android-dev（证据）/ architect |
| 2026-09-22 | **T18：新增 §13 定时提交与前台服务设计**（冻结签名、`specialUse` vs `dataSync` 理由、ROM 杀进程与 catch-up 兜底、单任务约束、**不做 BOOT_COMPLETED 的边界**）；原 §13 顺延为 §14；风险表新增 R13–R16 | Lead（冻结签名）/ architect |
| 2026-09-22 | 用户决策：**不做预检提交**，§12.2 新增第 6 条原则（只做页面层读取 + 响应层判定，不发起额外探测请求） | 用户 / Lead / architect |
