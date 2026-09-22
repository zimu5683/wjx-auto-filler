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
## 2026-09-22 09:07 v1.0.2 收口（报告刷新为新鲜证据）

- Lead 代跑 `./gradlew test` → BUILD SUCCESSFUL（1m53s）：test-results 刷新为 09:05，**15 个 XML（含 CookieHeaderTest）**，
  单变体 157 用例 / 0 失败 / 0 错误 / 1 跳过。**`WjxUrlsTest` 访问 `internal object WjxUrls` 在 AGP 单测里编译+实跑通过**（friend-path 有效）。
- 我跑 `bash scripts/build-apk.sh --skip-build` 重生成报告：**ALL PASS（PASS 18 / FAIL 0 / WARN 0）**，
  **单元测试 314 用例 0 失败 0 错误**；APK 未重新打包，sha256 仍是 8c0c4758e73b8d0c22f51abf416017a0daa81feaf7f5acd92673a022f274d997。
- PASS 由 20 变 18 的原因：`--skip-build` 不执行构建，少了「Gradle 构建」「Gradle daemon 环境刷新」两行。
- 顺手修脚本措辞：`--skip-build` 时报告不再写「构建任务：test lintRelease assembleRelease」，改为「（--skip-build：本次只校验，未执行 Gradle 构建）」。

### T13（task-12）最终状态

- 产物：dist/wjx-autofill-1.0.2-universal.apk + .sha256 + VERIFY-REPORT.md（delivery 已独立复验四项通过，正在发 Release v1.0.2）。
- 测试：Gradle 314 用例全过；独立 kotlinc 侧 152 用例 + QrDecodeTest 5 用例亦全过。
- task-12 已由 Lead 结项；后续只剩 delivery 的 task-14。
## 2026-09-22 10:00–10:30 T19（task-18）新功能测试

### 新增测试文件（android/app/src/test/**）

| 文件 | 覆盖 |
|---|---|
| wjx/WjxTimeAdapterTest.kt | BeginDate 时间戳（13 位毫秒 / 10 位秒×1000）、单双引号与空格、文案兜底（含全角冒号与单位数小时）、缺失/非法/0 → Unknown、合理性守卫（早于 2000 或晚于 now+20 年 → Unknown）、已开放也必须返回 Known、Unknown 不拦截、isOpen 等号边界、+08:00 换算（2026-09-23 09:33 == 1790127180000） |
| wjx/WjxPageOpenTimeTest.kt | E_NOT_OPEN 合成 HTML：未来 BeginDate → 抛 WjxException(NOT_OPEN) 且文案含北京时间；过去/缺失/0 → 正常解析；纯文案页也能触发；**未开放优先于 E_PARSE**（空题目页不误报改版）。needsCaptchaHint：useAliVerify=1 → true；**标记存在但值为 0 → false**；缺失 → false |
| schedule/ScheduledTaskStoreTest.kt | FileScheduledTaskStore 往返（含全部 TaskState）、clear、原子写无 .tmp、坏 JSON → 备份 schedule.json.bad-* 且保留原文、缺 templateId/openAt<=0 → null、未知 state → ARMED、leadMinutes 载入 clamp；ScheduleMath.remindAtMillis（0/正常/超长 clamp）、normalizeLeadMinutes（0/正常/1440/1441→1440/**负值→0**）、stateAfterRun；ScheduleTime 往返 |

### 发现并修掉的问题

1. **真实偏差（已报 android-dev 并修复）**：ScheduleMath.normalizeLeadMinutes 负值原本返回默认 10，冻结口径是 0 → 已改为 MIN_LEAD_MINUTES。
2. 我自己的用例最初传 now=0，被适配器的合理性守卫（now+20 年）判为脏数据 → 改为注入基准 now=1790127180000。
3. 2099 年的合成文案超出 now+20 年守卫 → 改为 2030。
4. 删掉 useAliVerify=2 的边际断言（真实页面只有 0/1，避免过度约束；口径分歧已同步 android-dev/Lead）。

### 验证结果

- 独立 kotlinc + JUnit：**全量 17 个测试类 / 192 用例 OK**（含 T19 新增 40 用例，不含需 android.jar 的 QrDecodeTest）。
- build-apk.sh 报告新增固定小节「## 需真机人工验证（JVM 单测覆盖不到）」：前台服务与通知（含全屏 Intent 响铃震动）、WebView 兜底交互、相机扫码/相册解码、应用内更新安装。

### 阻塞

- android-dev 正在做自动进入验证页 + PendingCaptchaStore + Notifier/Manifest 变更，明确要求等他完成通知后再跑 Gradle 全量（避免编译中间态）。
- 待其通知后：`./gradlew --stop && ./gradlew test` → `scripts/build-apk.sh --quick` 出 1.0.3。
## 2026-09-22 11:00–11:45 T19 构建 v1.0.3

### 过程

- delivery 报「task-18 无 owner、dist 无 1.0.3、Lead 与几位 inactive、流水线卡住」→ 我核对（android-dev/api-debug/architect inactive、源码 5 分钟无改动、无 Gradle 进程、无锁）后接手。
- 第一次 `./gradlew test`：debug 变体 205 用例全过；**release 变体编译失败** → `MainActivity.kt:330/334 Unresolved reference "renderScheduleStatus"`（正确函数名是 renderSchedule()）。
  已冷启动 android-dev 并把精确改法给他；**Lead 同时已自行修好**，并补齐响铃/震动开关（新增 schedule/SchedulePrefs.kt，SharedPreferences，默认都开）。
- 我发现构建期间源码仍在变（MainActivity 11:05 还在改）→ **kill 掉那次构建**（bash-23），等源码 60s 无改动后再重建，避免产物对应中间态。
- 第二次（冻结源码）：`./gradlew --stop && ./gradlew test` → BUILD SUCCESSFUL（20m24s），**debug/release 各 205 用例、0 失败、0 错误、1 跳过**。
- 然后 `scripts/build-apk.sh --quick` → ALL PASS（20/0/0），产出 1.0.3。

### 产物（已独立核验）

- `dist/wjx-autofill-1.0.3-universal.apk`（6182574 B），sha256 `5d31ecfdd5b0629aa1dc974f8e34f9d3b769f6c1365278e9dcd4539cb9d67cde`（sha256sum -c OK）。
- apksigner：CN=WJX AutoFill，SHA-256 `edcce56ef5d150cc7597223ddb4380bbce328756abb4a8bd13ffbda87c708372`（与 1.0.0/1.0.1/1.0.2 同一把密钥）。
- badging：com.wjx.autofill 1.0.3 (10003)、minSdk 24、targetSdk 35、native-code 四 ABI。
- 单元测试 410 用例（debug/release 各 205，含新增 48：WjxTimeAdapter 15 / WjxPageOpenTime 8 / ScheduledTaskStore 17 / PendingCaptchaStore 8）。

### 新发现：A9 卡在 1 条 lint Error（刷新 lint 才暴露）

- 之前报告里「lintRelease 无 Error」的 PASS 读的是 **08:41 的旧 XML**；本轮我主动跑 `lintRelease` 刷新（不重新打包，APK hash 不变）后发现：
  `id=MissingPermission`，位置 `schedule/Notifier.kt:172`（`NotificationManagerCompat.notify`）。
- 运行期安全：`:146` 有 `canNotify(context)`（内部 checkSelfPermission）守卫，`:171-175` 用 try/catch(Throwable) 包住；
  属 lint 跨函数分析限制，不是真 bug；`assembleRelease` 能过是因为 lintVital 只看 fatal。
- 已把两种改法发 android-dev（同方法内显式 checkSelfPermission / @SuppressLint("MissingPermission")），并请 Lead 裁决「修一行重建」还是「记为已知偏差豁免」。
- 已提醒 delivery 在裁决前不要发布；当前 dist/VERIFY-REPORT.md 如实记录为 PASS 17 / FAIL 1。

### 报告增强

- build-apk.sh 报告新增固定小节「需真机人工验证（JVM 单测覆盖不到）」，含 Lead 追加的验收点 1a：响铃/震动必须是可选项（默认开且可关，关闭后仍有高优先级全屏通知）。
## 2026-09-22 11:50–12:10 v1.0.4（按 Lead 改判：lint 真修掉，不再豁免）

### 背景（Lead 改判）

- Lead 发现比 lint 更严重的问题：`schedule/Notifier.kt` 在 11:48:16 被改（lint 修复），而 dist 的 APK 是 `--skip-build` 复制的 11:38 旧包 → **仓库 HEAD 与已发布 v1.0.3 的 APK 不一致**。
- 因此改判：不改源码的豁免方案作废，改为**真正修掉 lint 问题**并重建 v1.0.4，保证「源码 + APK」对应。

### 我做的处理

1. **豁免文件转为历史存档**：`dist/LINT-WAIVER.md` → `dist/LINT-WAIVER-v1.0.3-historical.md`，并在文件头写明「v1.0.4 已消除 + 脚本只认 LINT-WAIVER.md」。
   效果：豁免机制作为可审计能力保留，但**今后任何 lint Error 都会照旧 FAIL**，不会被静默豁免。
2. 按 Lead 给的序列跑：`./gradlew --stop && ./gradlew test lintRelease assembleRelease`（同一轮，7m15s）→ `build-apk.sh --skip-build`。
3. 报告「构建任务」行写清来源：`--skip-build` 只校验，产物与 test/lint 证据来自紧邻的同一轮 Gradle 构建（12:03 完成）。

### 结果：ALL PASS（PASS 18 / FAIL 0 / WARN 0）

- 产物：`dist/wjx-autofill-1.0.4-universal.apk`（6182574 B），sha256 `b522ae12395d3d8c914e5c78da1bd4088d230324adb6a019d5cf954e27e1b96b`（sha256sum -c OK）。
- apksigner：CN=WJX AutoFill，SHA-256 `edcce56ef5d150cc7597223ddb4380bbce328756abb4a8bd13ffbda87c708372`（与 1.0.0–1.0.3 同一把密钥）。
- badging：1.0.4 (10004)、minSdk 24、targetSdk 35、四 ABI；无 GMS；无自研 native。
- 单元测试：**410 用例 0 失败 0 错误**（debug/release 各 205）。
- **lintRelease：0 条 Error**（报告带生成时间 2026-09-22 12:03:37）。
- 源码/产物一致性复核：构建完成后近 12 分钟 main 源码 0 改动 → HEAD 与 APK 对应。

### 修复内容（android-dev，仅 Notifier.kt）

`notifyCaptchaFullScreen()` 在 notify 前加**同方法内**权限检查（lint 要求）：
`SDK < 33` 直接视为已授权（POST_NOTIFICATIONS 在 API<33 不存在，直接查会恒 DENIED，会静掉 Android 7–12 的通知），
否则 `checkSelfPermission(POST_NOTIFICATIONS) == PERMISSION_GRANTED`；并把 catch 细化为 SecurityException + Throwable 两级。
## 2026-09-22 15:30–16:05 T25 构建 v1.0.5

### 测试（按 Lead 冻结契约）

- **WjxAnswerMatcherTest 重写**（23 → 28 用例）：未匹配 → `Ok(skippedFields)`；空字段名 → 忽略且不计入 skippedFields；歧义/同题/取值不在选项/超长/不支持题型 → 仍 Fail；pairs 空 → Fail(E_UNMATCHED) + 「全部字段都被跳过」；skippedFields 去重/保序/trim；SubmitResult.skippedFields additive 默认空列表且不混进 message。
- **新增 TemplateLibraryTest**（10 用例）：不同名 3 次 → 3 个；同名 2 次 → 1 个且内容覆盖 + 保留原 id；trim 同名 / 大小写敏感；同名不同 URL 仍覆盖；空名抛 IAE；delete 按 id / 找不到原样返回；findByName；**列表与持久化同步**（TemplateStore 落盘回读）。
- **WjxTimeAdapterTest 增至 19 用例**：补 architect §2.3 优先级向量（文案优先于过去的 qBeginDate、left+nowTime 兜底 ±2s、无文案无 left 才回落时间戳、beijingMillisOf 整串消费）。
  · 期间我以为 left+nowTime 路径坏了（fixture 的 nowTime 带秒）；实测 api-debug 15:39 已让 parseBeijingTime 同时接受 HH:mm:ss 与 HH:mm，该路径正常，我据此修正断言。
- 独立 kotlinc 全量：**19 个测试类 / 218 用例 OK**（不含需 android.jar 的 QrDecodeTest）。

### 构建：ALL PASS（PASS 18 / FAIL 0 / WARN 0）

- 命令：`./gradlew --stop && ./gradlew test lintRelease assembleRelease`（同一轮，9m27s）→ `bash scripts/build-apk.sh --skip-build`。
- 产物：`dist/wjx-autofill-1.0.5-universal.apk`（6158366 B），sha256 `7cbb7982d77d2d7d20836e2c2f422daab15e2ee5538bf9382a10620ae7ad76d0`（sha256sum -c OK）。
- apksigner：CN=WJX AutoFill，SHA-256 `edcce56ef5d150cc7597223ddb4380bbce328756abb4a8bd13ffbda87c708372`（与 1.0.0–1.0.4 同一把密钥）。
- badging：1.0.5 (10005)、minSdk 24、targetSdk 35、四 ABI；无 GMS；无自研 native。
- 单元测试：**448 用例 0 失败 0 错误**（debug/release 各 224）。
- **lintRelease：0 条 Error**（报告带生成时间 2026-09-22 16:01:48）。
- 一致性：构建后近 12 分钟 main 源码 0 改动 → HEAD 与 APK 对应。

### 备注

- 我的独立预检脚本一度因「编译器 classpath 缺 kotlinx-coroutines」报假失败（harness 问题，非工程问题），已由随后的 Gradle 全量验证取代。
- 已提醒 delivery：push 前带上未提交的 MainActivity/EditorState/ResultAdapter/wjx/config/res 改动，避免 HEAD 与 APK 不一致。
## 2026-09-22 16:30–17:00 T29 构建 v1.0.6

### 测试（新增 17 用例/变体，总数 448 → 482）

- **ScheduleTimeTest（7）**：`applyParsedOpenTime(current, parsed)` —— parsed>0 强制覆盖；null/0/负数保留 current；两边都空返回 null；format/parse 往返；非法输入拒绝；trim。
  · 自我纠错：我最初断言 format(1790127180000)=="2026-09-23 09:33"，实测 JVM 默认时区为 UTC（shell 显示 +08:00）→ 改为只断言往返一致 + 格式正则。ScheduleTime 用设备默认时区；固定北京时间格式化属 WjxTimeAdapter（另有单测）。
- **EditorStateTest（10）**：映射列表数据层（一次加 8 条全保留、清空、删中间行保序、groupIndex 越界按需建组、组名兜底）+ 模板按名字保存/删除/查找。
  · EditorState 仅 saveTo/restoreFrom 依赖 Bundle，纯方法可在 JVM 单测跑（standalone 用 android.jar 验证）。
- 独立 kotlinc 全量：**21 个测试类 / 236 用例 OK**（不含需 android.jar 的 QrDecodeTest）。

### 构建：ALL PASS（PASS 18 / FAIL 0 / WARN 0）

- 命令：`./gradlew --stop && ./gradlew test lintRelease assembleRelease`（同一轮，8m2s）→ `bash scripts/build-apk.sh --skip-build`。
- 产物：`dist/wjx-autofill-1.0.6-universal.apk`（6175666 B），sha256 `6b5672a118dc8cff6e119c2af0222c1f0ddd9c3bf95ded1b45458d132d4fcf8b`。
- apksigner：CN=WJX AutoFill，SHA-256 `edcce56ef5d150cc7597223ddb4380bbce328756abb4a8bd13ffbda87c708372`（与 1.0.0–1.0.5 同一把密钥）。
- badging：1.0.6 (10006)、minSdk 24、targetSdk 35、四 ABI；无 GMS；无自研 native。
- 单元测试 **482 用例 0 失败 0 错误**（debug/release 各 241）；**lintRelease 0 条 Error**（16:52:45）；构建后 15 分钟内 main 源码 0 改动。

### 报告增强（按 Lead 裁定）

- 「需真机人工验证」第 5 条：加 8 条字段映射应全部可见/可编辑/页面可滚/无内嵌滚动条；并注明「映射行本轮无 JVM 覆盖，PairAdapter 依赖 android.view 且问题属真实布局测量，Robolectric 测不到，故不引入」。
- 新增「本版关键修复（v1.0.6，人工维护）」小节：① 映射列表 4 行不可滚（ScrollView 内嵌 RecyclerView 不重测）② SurveyStatus.fromModel() parsed=null 导致顶部状态条从未显示已开放/未开放 ③ 匹配规则可跳过 vs 仍失败（v1.0.5 起）。
## 2026-09-22 18:00–19:00 T35 构建 v1.0.7

### 测试（新增 24 用例/变体，总数 482 → 530）

- **新增 SurveyStatusTest（12）**：`SurveyStatus.of` 收敛口径 —— parsed 未到点 → NOT_OPEN；parsed 空 → **UNKNOWN**（含 `fromModel(null,null,now)` 即使 now=Long.MAX_VALUE 也 UNKNOWN，即本轮 bug 的断言化）；parsed 优先于手填；0/负值视为缺失；等号边界算已开放。
- **新增 SurveySessionTest（7）**：`surveyCookiesFor(model, currentUrl)` 串号回归 —— URL 不一致 / model null / URL 空 → 必须空 Map；同域不同问卷也视为不同；两侧 trim 后才放行。
- **WjxPageOpenTimeTest 扩到 11**：E_NOT_OPEN 的 `WjxException.openAtMillis` == 页面解析值；其它错误码 / Unknown → null；解析失败不得伪造成 NOT_OPEN。
- **WjxPageParserTest 扩到 11**：真实 fixture `tfGAWU4-notopen.html` → NOT_OPEN + openAtMillis==1790127180000 + message 含「2026-09-23 09:33」；`P2M09FG-open.html` → 解析成功且 openAtMillis==1790034767873。
  · fixture 用例带**时间守卫**：真实时间走过该开放时刻后自动跳过并打印原因，避免测试过期变红。
- 独立 kotlinc 全量：**22 个测试类 / 253 用例 OK**。

### 新增静态回归防线（build-apk.sh 第 9 节）

- awk 截取 `submitAll()` 函数体 → grep `state.survey` → 必须 0 处；报告里体现为一行独立检查。
- 本轮实测 PASS：「函数体内 0 处引用（提交由 coordinator 内部 fetch）」。以后有人把 state.survey 塞回提交路径，报告会直接 FAIL。

### 构建：ALL PASS（PASS 19 / FAIL 0 / WARN 0）

- 命令：`./gradlew --stop && ./gradlew test lintRelease assembleRelease`（同一轮，14m3s）→ `bash scripts/build-apk.sh --skip-build`。
- 产物：`dist/wjx-autofill-1.0.7-universal.apk`（6175666 B），sha256 `7a809d8c8f094c6634ac386d90b71ee32fdad108060d0d05a04207028c7da3a3`。
- apksigner：CN=WJX AutoFill，SHA-256 `edcce56ef5d150cc7597223ddb4380bbce328756abb4a8bd13ffbda87c708372`（与 1.0.0–1.0.6 同一把密钥）。
- badging：1.0.7 (10007)、minSdk 24、targetSdk 35、四 ABI；无 GMS；无自研 native。
- 单元测试 **530 用例 0 失败 0 错误**（debug/release 各 265）；**lintRelease 0 条 Error**（18:46:52）；构建后 20 分钟内 main 源码 0 改动。

### 报告口径修正（Lead 采纳 android-dev 的更正）

- 「需真机人工验证」第 7 条改为：① 输入框值应等于**平台提示的北京时间**（期望来源用 `WjxTimeAdapter.formatBeijingTime(millis)` 现算，**不硬编码具体日期**；注明 BeginDate 是问卷创建时间、不是开放时间）② 状态条显示 strings.xml `survey_status_not_open` 逐字文案「尚未开放，将于 %1$s 开放」③ 能基于该时间成功开启定时任务。

### 备注

- Lead 两次以为我未开跑（构建已在后台进行中）；已回复澄清并附最终数字。
- 我的独立预检脚本再次因「编译器 classpath 缺 kotlinx-coroutines」报假失败（harness 问题，非工程问题）；已修正，且 Gradle 全量结果为准。