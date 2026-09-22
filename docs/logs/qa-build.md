# qa-build（T6 测试与打包）节点日志

> 只写本文件；时间线按实际操作追加。

## 2026-09-22 01:00–02:00 准备阶段（不依赖 T4/T5 代码）

### 已完成

| 时间 | 动作 | 命令 / 产物 | 结果 |
|---|---|---|---|
| 01:20 | 复制用户二维码截图成夹具 | cp 附件 → testdata/qr-sample.jpg | 79183 B，sha256 85d83066eb1b799e855c8a61d8c22f435cc6e2398c8e72aa1a199ce3dee57fd3 |
| 01:22 | 纯 JVM 验证 zxing 能解该 JPEG | javac+java 内联探针（$TMPDIR/qrprobe） | image=1200x2000，TEXT=https://www.wjx.cn/vm/Q0DQewW.aspx |
| 01:2x | 写 scripts/build-apk.sh | 477 行，bash -n 通过 | 构建 + apksig 验签 + badging + 4ABI 通用包 + 无 GMS + 报告 |
| 01:2x | 从脚本抽出内嵌验签程序实测 | javac -cp apksig-8.9.1.jar + java 跑 app-debug.apk | verified=true v1=false v2=true v3=false，signer=CN=Android Debug → **apksig 在 arm64/Termux 可用** |
| 01:24 | 生成亮度夹具 scripts/gen-qr-fixture.sh | testdata/qr-sample.lum.gz | 203326 B；image=1200x2000；decoded=…Q0DQewW.aspx；OK |
| 01:5x | 独立 kotlinc 编译 + JUnit 运行（不占 Gradle 槽） | $TMPDIR/qacheck | **OK (46 tests)** |

### 关键发现（已同步相关人）

1. **Android 单测编译类路径没有 java.desktop**：AGP/Kotlin 编译单元测试用 android.jar（java.io 有、java.awt/javax.imageio 没有），
   所以 QrDecodeTest 不能直接 ImageIO 解 JPEG（首轮编译报 Unresolved reference 'image'/'imageio'）。
   → 改为：scripts/gen-qr-fixture.sh（纯 JVM，有 java.desktop）把 JPEG 转成亮度矩阵 testdata/qr-sample.lum.gz
   （格式 'WJX1' + int32 宽 + int32 高 + w*h 灰度，gzip），单测再喂给生产解码器 QrDecoder.decodeLuminance。
   JPEG→URL 的断言仍在流水线里跑（build-apk.sh 第 7 步调 gen-qr-fixture.sh --check）。
2. **「APK 内无 .so」不成立**：debug APK 实测含 8 个 .so（arm64-v8a/armeabi-v7a/x86/x86_64 各 2 个，
   来自 androidx.camera:camera-core:1.4.1 的 jni/），badging native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'。
   → 与 delivery 证据一致、Lead 已确认 A7 改判：build-apk.sh 改为校验「不设 abiFilters + 4 ABI 目录齐全 +
   每个 .so 在 4 个 ABI 各一份 + 工程无自研 native 代码」，并保留「依赖树无 com.google.android.gms」。
3. **android-dev 的 qr/SurveyLinkValidator.kt 有编译错误**（checkSyntax 内两次 val host，第 66/79 行）→ 已 send_message 告知并给出修法。

### 已写单测（android/app/src/test/java/com/wjx/autofill/）

| 文件 | 覆盖 | 状态 |
|---|---|---|
| wjx/WjxSubmitCodecTest.kt | §3.4 全部向量 + 边界（幂等、控制字符、负 ktimes、重复 topic、topic<1、空输入） | 独立跑通（对桩实现） |
| config/TemplateStoreTest.kt | 往返、原子写、缺文件、坏 JSON 备份、schemaVersion=2 拒绝、shortId 推导、坏链接、clamp、id 去重、导出 | 独立跑通（对真实实现） |
| submit/SubmitCoordinatorTest.kt | 并发上限、clamp、组序返回、每组独立 fetch、fetch 失败保码、空组 E_EMPTY、空 groups、进度、空值过滤、summary | 独立跑通（对真实实现） |
| update/VersionCompareTest.kt | 1.0.1>1.0.0、v 前缀、数值比较、不可比 -1、parse | 独立跑通（对真实实现） |
| qr/QrDecodeTest.kt | 夹具魔数、亮度夹具→URL、生产解码器、空输入、采样率 | 待 Gradle（需 android.jar 环境） |
| qr/SurveyLinkValidatorTest.kt | extractUrl/isAllowedHost/checkSyntax | 等 android-dev 修编译错误 |

### 证据文件

- $TMPDIR/qacheck/：独立编译产物 + 桩（不进仓库）
- $TMPDIR/apksig-probe/：验签程序 + 实测输出
- dist/：待 release 构建后产出

### 阻塞 / 待办

- T4（api-debug 的 wjx/ 包）未落地 → 主源码 compileDebugKotlin 失败（MainActivity 等引用 wjx.*），Gradle 侧 test/lint/assemble 都跑不了。
- 需要 api-debug 提供三个测试接缝的确切签名：WjxResponseClassifier、HTML 解析入口（§6.2 题型 fixture）、字段匹配（§7）。
- Lead 新增：useAliVerify 才是拦截门（不是 captchaType）；SurveyModel 可能加字段 → 等契约更新后补用例。
## 2026-09-22 02:00–03:00 T4 落地后的补测

### 独立 kotlinc 全量验证（不占 Gradle 槽）

- wjx/ + config/ + update/VersionCompare + 我的单测：**83 用例全过**（JUnit 4.13.2）。
- 单独编译 T4 的 wjx/ 五个文件：**编译通过**（kotlin-compiler-embeddable 2.1.20 + stdlib + coroutines 1.9.0 + trove4j + annotations）。
- QrDecodeTest 用 SDK android.jar + camera-core classes.jar 单独跑：**5 用例全过**（证明生产解码器在 JVM 单测环境可用）。

### 本轮新增/修正

| 项 | 说明 |
|---|---|
| 新增 WjxResponseClassifierTest | §8.4 全部向量：真实响应 7〒→E_CAPTCHA、10〒→成功、11〒→成功、22〒→E_CAPTCHA、5〒→E_REJECTED、9〒→E_REJECTED、aliyunwaf→E_CAPTCHA、空→E_PARSE、html→E_PARSE、其他→E_UNKNOWN、非 2xx→E_HTTP、raw 截断 8192 |
| 新增 WjxAnswerMatcherTest | R1 题号（含 trim、优先级压过 R2）、R2 题干 contains（中文/ASCII 忽略大小写）、R3 选项 label（空 value 取 option.value）、TEXT escape、3000 字上限、MULTI 的竖线拼接、DROPDOWN、MATRIX/SLIDER/OTHER→E_UNSUPPORTED、未匹配/歧义/同题冲突、明细折叠「等 N 项」 |
| 新增 WjxPageParserTest | 4 个真实 fixture 的题数/题型分布/选项/useAliVerify/captchaType/submitUrl + 合成 HTML 覆盖 8 种题型 + E_URL/E_PARSE/E_PAGED |
| 新增 WjxEngineIntegrationTest | 默认跳过（assumeTrue），开启方式：-Dintegration=true / WJX_INTEGRATION=true / testdata/run-integration.flag（build-apk.sh --integration 会创建） |
| 修正 SubmitCoordinatorTest | 接口 additive 变更：fetch(url, cookies)、submit(m, answers, captchaToken)，两个 fake 的 override 签名已同步 |
| 修正分类器用例 | 原先按 Lead 早期口误写 11→E_REJECTED；契约 §8.4 第 4 条 + api-debug 确认为 10/11→成功、7/22→E_CAPTCHA |
| build-apk.sh 增强 | --integration 开关；CaptchaActivity 静态审查（1 个 evaluateJavascript 调用点 + 2 个固定常量脚本 + 0 个真实 @JavascriptInterface）；实测 AUDIT=PASS |

### 报给 api-debug 的真实偏差（待修）

- **DROPDOWN 题拿不到选项**：契约 §6.2 要求 options = option 的 value + 文本；实测 WjxSurveyClient.kt 第 346 行只给 SINGLE/MULTI 取选项，
  且 optionsOf 只遍历 radio/checkbox。影响：下拉题取值解析恒 E_UNMATCHED。已在 WjxPageParserTest 里做条件断言并注明，修好后自动收紧。
- 已确认接缝：WjxResponseClassifier.classify / WjxPageParser.parse / WjxAnswerMatcher.match / WjxSubmitRequest.buildSubmitBody+buildSubmitUrl（api-debug 已按请求抽出）。

### 静态审查实测（CaptchaActivity）

| 判据 | 实测 | 结论 |
|---|---|---|
| evaluateJavascript 调用点 | 1（私有 helper evaluate(script) 内） | 符合脚本固定化意图 |
| 动态拼接（$ 或 +） | 0 | 通过 |
| 固定常量脚本调用 | 2（JS_RAISE_CAPTCHA / JS_HARVEST） | 通过 |
| 真实 @JavascriptInterface 注解 | 0（仅注释里提到） | 通过 |

> architect 原话「evaluateJavascript 只能出现两次固定常量」的字面计数与实现形态（1 调用点 + 2 常量）不一致，已按意图实现机械判据并回报。

### 当前阻塞

- 等 Lead 释放 Gradle 槽 → 跑 scripts/build-apk.sh（test + lintRelease + assembleRelease 一把梭）。
- 队友仍在改：submit/CaptchaHarvest.kt 已落盘；wjx/WjxSubmitRequest 已落盘（我的旧编译命令未包含新文件导致过假报错）。
## 2026-09-22 03:00–03:50 构建与校验（最终）

### 流水线结果：ALL PASS（PASS 21 / FAIL 0 / WARN 0）

命令（后台任务，带 release 签名环境变量）：

~~~bash
export WJX_KEYSTORE_FILE=.../wjx-release.keystore WJX_KEYSTORE_PASSWORD=*** WJX_KEY_ALIAS=wjx WJX_KEY_PASSWORD=***
bash scripts/build-apk.sh            # test + lintRelease + assembleRelease + 全部校验
~~~

| 检查项 | 结果 |
|---|---|
| apksig 验签 | verified=true v2=true；signer CN=WJX AutoFill，SHA-256 edcce56e…8372（= Lead 给的指纹） |
| 签名与配置一致 | PASS（CN=WJX，非 Android Debug） |
| 包名/版本 | com.wjx.autofill，1.0.0 (10000) 与 version.properties 一致 |
| minSdk / targetSdk | 24 / 35 |
| 通用包 | 4 个 ABI 目录齐全、8 个 .so、每个库 4 份、无自研 native |
| 无 GMS | releaseRuntimeClasspath 0 命中 |
| 单元测试 | 246 用例 0 失败 0 错误（debug+release 两个变体各 123） |
| lintRelease | 0 条 Error（含 NewApi） |
| 二维码夹具解码 | https://www.wjx.cn/vm/Q0DQewW.aspx（1200x2000） |
| CaptchaActivity 静态审查 | 1 调用点 / 0 动态拼接 / 2 固定常量 / 0 真实 @JavascriptInterface |

产物：dist/wjx-autofill-1.0.0-universal.apk（6138222 B，sha256 fd1370a7040e805e2e70b438f52d4cb36b26077bf79b54ca29de39b3a433a9ab）+ .sha256 + VERIFY-REPORT.md。

### 第一轮失败与修复（真实教训）

| 现象 | 根因 | 修复 |
|---|---|---|
| APK 被 debug 证书签名 | Gradle daemon 复用它启动时的环境变量，AGP 在配置阶段读 providers.environmentVariable → signingConfigs.release 未创建 | 配置了 keystore 时构建前 ./gradlew --stop；新增「签名与配置一致」硬校验（CN=Android Debug → FAIL） |
| minSdk 校验误报 | 新版 aapt2 输出 minSdkVersion:'24'，脚本只匹配旧版 sdkVersion:' | 两种格式都接受 |
| 集成测试失败 | 我的断言 raw 必非空；但 useAliVerify 硬门控路径 raw=null（不发请求） | 修正为两条路径都接受；集成测试默认跳过 |

### 集成测试（可选，默认跳过）真实网络证据

- fetch 线上样本问卷成功：3 题、题号 1/2/3、全 TEXT、jqnonce 非空。
- submit：ok=false、http=0、errorCode=E_CAPTCHA、raw=null → useAliVerify 硬门控生效，**未发 POST**。
- 强制执行方式：touch testdata/run-integration.flag + --tests 过滤 + --no-build-cache（build-apk.sh --integration 已支持）。

### 产物冻结

- Lead 要求冻结：03:35 的测试运行未重新打包，APK mtime 03:33:20、sha256 未变。
- delivery 已独立复验（sha256 -c / apksigner / 4 ABI / badging）通过。
- task-6 已由 Lead 标记 completed；下一轮 1.0.1 由 Lead 通知后重建。
## 2026-09-22 03:50–04:00 夹具缺失改为 skip（Lead 派活，CI 需要）

背景：真实问卷页 fixture 不进公开仓库（别人的问卷内容），CI / 全新克隆上没有 tools/wjx-probe/fixtures/，
原实现找不到夹具会 throw AssertionError → test job 红，A10「脱离 Termux 也能构建」挂。

改动（android/app/src/test/java/com/wjx/autofill/wjx/WjxPageParserTest.kt）：
- 新增 fixturesDir()：优先 -Dwjx.fixtures.dir / 环境变量 WJX_FIXTURES_DIR，否则从 user.dir 向上找；找不到返回 null。
- 新增 fixtureOrSkip(name)：缺失时 println 可见原因 + Assume.assumeTrue(false) 跳过（不静默、不判失败）。
- 合成 HTML 8 种题型用例、E_URL/E_PARSE/E_PAGED 错误路径用例**不依赖夹具，始终运行**。

验证（只跑 test，未跑 assembleRelease；APK sha256 前后一致 = fd1370a7040e805e2e70b438f52d4cb36b26077bf79b54ca29de39b3a433a9ab）：

| 场景 | 命令 | 结果 |
|---|---|---|
| A 有夹具 | ./gradlew test | BUILD SUCCESSFUL；debug/release 各 123 用例、0 失败、1 跳过（默认跳过的集成测试） |
| B 模拟 CI 无夹具 | ./gradlew --stop; WJX_FIXTURES_DIR=/nonexistent ./gradlew :app:testDebugUnitTest --tests WjxPageParserTest --no-build-cache | BUILD SUCCESSFUL；9 用例中 5 跳过 0 失败，并打印 [skip] fixtures 未提供… 提示 |

收尾：已 ./gradlew --stop 清掉带 WJX_FIXTURES_DIR 的 daemon，避免污染后续构建环境。
## 2026-09-22 03:55–04:00 重建 1.0.1（最短路径）

Lead 升版后执行：`bash scripts/build-apk.sh --quick`（只 assembleRelease，不重跑 test/lint），带 release keystore 环境变量。

- 结果：PASS 19 / FAIL 0 / WARN 1（WARN = --quick 未跑测试，报告里已注明测试证据来自 1.0.0 完整流水线）。
- dist/wjx-autofill-1.0.1-universal.apk：6138222 B，sha256 0db09f8d1444fb182fb6fceb5c2042c8890ed0b44ad29edcd69cf969d71b3dfb。
- 签名（apksigner 独立复核）：CN=WJX AutoFill，SHA-256 edcce56ef5d150cc7597223ddb4380bbce328756abb4a8bd13ffbda87c708372 —— 与 1.0.0 **同一把密钥**，可覆盖安装。
- 包名 com.wjx.autofill、1.0.1 (10001)、minSdk 24、targetSdk 35、4 ABI 齐全、无 GMS、无自研 native。
- 1.0.0 产物未被动：dist/wjx-autofill-1.0.0-universal.apk 仍是 fd1370a7…。
- 构建前先清掉了 1.0.0 遗留的部分 test-results（避免 1.0.1 报告出现误导性用例计数），并 ./gradlew --stop 刷新 daemon 环境。
- 注意：dist/VERIFY-REPORT.md 是**每次运行覆盖生成**的，现在描述的是 1.0.1；1.0.0 的报告内容（含集成测试证据）已被本轮覆盖。
## 2026-09-22 04:20 更正与 T13 最终用例（重要：旧断言已作废）

### ⚠️ 作废声明：本文件前面「集成测试」一节里的断言已被推翻

前面写的「submit：ok=false、http=0、errorCode=E_CAPTCHA、raw=null → useAliVerify 硬门控生效，未发 POST」**已作废**：
Lead 2026-09-22 裁定**取消 useAliVerify 本地门控**。现行口径：

- `useAliVerify=true` 且 `captchaToken=null` 时**也必须真的发出一次 POST**（本地判定会产生假阴性：T11 实测最小请求形态在 useAliVerify=0 的问卷上拿到成功码 10）；
- 只有服务端响应业务码 **7 / 22** 才返回 `E_CAPTCHA`（终态），此时 `httpStatus` 为实际响应码、`raw` 为服务端正文；
- 本地错误（E_URL / E_UNMATCHED / E_EMPTY）才是 `httpStatus=0`、`raw=null`。

已按此反转：`WjxEngineIntegrationTest` 的断言改为「必须真的发出 POST：httpStatus ∈ 200..299 且 raw 非空」；
`HttpWjxSubmitterTest` 删掉两个「零网络 E_CAPTCHA」用例，改为用 E_UNMATCHED / E_URL 证明它走到了匹配与 URL 构造步骤；
`SubmitCoordinatorTest` 新增 fake submitter 用例，断言 useAliVerify=true 的模型**确实被交给 submitter**（callCount=1）。

### 另一处口径更正：ktimes 下限是 4（不是 1）

- `WjxSubmitRequest.buildSubmitUrl`：`ktimes = maxOf(4, m.ktimes)`，`&ktimes=` 与 `jqSign(m.jqnonce, ktimes)` 用**同一个变量**。
- 页面 ktimes=0/1/2/3 → URL 发 4；ktimes=6/7 → 保持 6/7（不设上限）。
- **页面 ktimes=0 时签名会变**（XOR key 由 1 变 4）——不要沿用「0 与 1 签名相同」的说法（那只是 codec 层 key(0)==key(1) 的事实）。

### T13 最终用例（本轮新增/修改，均已独立 kotlinc 验证）

| 文件 | 新增/修改 |
|---|---|
| wjx/WjxUrlsTest.kt（新） | WjxUrls.shortIdOf / isAllowedHost / isHttpsWjx 的子域矩阵：v / www2 / survey 子域、大小写、/jq/ 与 /m/、4/32 边界；负例 evilwjx.cn、evil-wjx.cn、wjx.cn.evil.com、http、非 vm|jq|m、3/33 字符 |
| config/TemplatesJsonSubdomainTest.kt（新） | templates.json surveyUrl 子域导入 + shortId 推导 + 全部负例 |
| qr/SurveyLinkValidatorTest.kt | shortIdOf/checkSyntax 子域、http→https 升级、evil 变体负例 |
| wjx/WjxSubmitRequestTest.kt | ktimes floor 4（0/1/2/3→4，6/7 保持）、jqsign 与实际发送 ktimes 同源、页面 0 时用 key=4 |
| wjx/WjxResponseClassifierTest.kt | 裸码 22 → E_CAPTCHA(raw="22")、裸码 7、11〒完成页 URL → 成功、V6 真实成功向量（10〒/complete.aspx…comsign=…） |
| wjx/WjxSubmitCodecTest.kt | codec 层 key(0)==key(1)；key 不同则输出不同 |
| wjx/HttpWjxSubmitterTest.kt | 删除本地门控用例，改为「无本地短路」证明 |
| submit/SubmitCoordinatorTest.kt | fake submitter 证明 useAliVerify=true 不被短路（callCount=1、httpStatus=200） |
| submit/CookieHeaderTest.kt（新） | originOf 跟随问卷主机（v/www/www2）、大小写与端口归一、非法输入兜底、注入与清理同源 |

### 自查中修掉的两个自身缺陷

1. 我在新增分类器向量时把业务码分隔符误打成 `〓`(U+3013)，正确是 `〒`(U+3012) → 两个成功向量误判失败；已全仓库替换并复核 0 残留。
2. 两条旧断言仍写 ktimes=3（floor 改为 4 后失效）→ 已改为 4。

### 验证结果（独立 kotlinc + JUnit，不占 Gradle 槽）

- **152 用例全部通过（OK (152 tests)）**，覆盖上述全部文件（QrDecodeTest 因需 android.jar 单独跑，见前面 5/5 通过记录）。
- Gradle 侧最终构建由 Lead 直接执行；本 agent 不再启动 Gradle，避免构建互等锁。
## 2026-09-22 09:00–09:05 v1.0.2 产物与证据新鲜度

### 产物（Lead 直接执行最终构建；我已独立复核）

- `dist/wjx-autofill-1.0.2-universal.apk`（6138246 B，09:02:43），sha256 `8c0c4758e73b8d0c22f51abf416017a0daa81feaf7f5acd92673a022f274d997`（sha256sum -c OK）。
- apksigner：CN=WJX AutoFill，SHA-256 `edcce56ef5d150cc7597223ddb4380bbce328756abb4a8bd13ffbda87c708372`（与 1.0.0/1.0.1 同一把密钥）。
- badging：com.wjx.autofill 1.0.2 (10002)、minSdkVersion 24、targetSdkVersion 35、native-code 四 ABI。
- VERIFY-REPORT.md：ALL PASS（PASS 20 / FAIL 0 / WARN 0），构建任务 assembleRelease。

### 发现：报告的「单元测试 282」来自 08:36 的旧一轮

- 证据：test-results/testDebugUnitTest/ 下 14 个 XML 全部 mtime=08:36，且**没有 CookieHeaderTest**；
  各文件用例数也是旧的（ResponseClassifier=14/现 18、SubmitRequest=12/现 15、SubmitCoordinator=10/现 11）。
- 原因：最终构建走的是 `build-apk.sh --quick`（日志只有 assembleRelease），test 任务未执行，报告沿用了旧 XML。
- 我的兜底验证：最新测试源码用独立 kotlinc + JUnit 跑 **152 用例全过**（QrDecodeTest 需 android.jar，单独 5/5 过）。
- 风险点：`WjxUrlsTest` 访问 `internal object WjxUrls`；AGP 单测有 friend-path 应可访问，但**未在 Gradle 实跑验证**。
- 已向 Lead 提议：补跑一次 `./gradlew test`（不跑 assembleRelease → APK 字节不变）+ `build-apk.sh --skip-build` 刷新报告；等裁决。
- APK 本身不受影响，已把核验数据同步给 delivery。