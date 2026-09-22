# delivery 节点日志（T7 交付上传）

> 角色：交付上传 Agent（delivery）　模型：opencode-go / deepseek-v4.1-flash
> write scope：README.md、docs/USAGE.md、docs/BUILD.md、.github/**、docs/logs/delivery.md

## 2026-09-22 00:35–00:49 (+08:00)　第一阶段：文档与仓库准备

| 时间 | 动作 | 命令/证据 | 结果 |
| --- | --- | --- | --- |
| 00:36 | 查共享任务 | `team_task_list` / `team_task_get` | task-7 依赖 task-6，`claim` 返回 `not ready to claim`（预期）。按 Lead 指示先做第一阶段，只写自己 scope |
| 00:37 | 环境事实核对 | `java -version`；`readelf -h $PREFIX/bin/aapt2`；`gh auth status` | JDK 17.0.20；`$PREFIX/bin/aapt2` = ELF64 **AArch64**（故 Termux 构建必须覆盖 aapt2）；gh 登录 zimu5683，token scope 含 `repo`/`workflow`；`api.github.com/repos/zimu5683/wjx-auto-filler` = **404**（仓库尚未创建） |
| 00:38 | 读参考实现 | `~/yikou-light-food-server/.github/workflows/android.yml`、`AppUpdater.kt` | 对齐：更新读 `tag_name` + `assets[].browser_download_url`（优先名字含 arm64/universal 的 .apk）；CI 用 `gh release create` 失败时回退 `gh release upload --clobber` |
| 00:40 | 写 README.md | `write` | 功能特性 / 安装 / 构建 / 更新机制 / 目录结构 / 隐私 / 免责声明 |
| 00:41 | 写 docs/USAGE.md | `write` | 扫码·相册·链接导入 → 解析 → 双栏映射 → 多组增删清空 → 模板保存/导入导出 → 并行提交与结果 → 检查更新 → FAQ |
| 00:41 | 写 docs/BUILD.md | `write` | 路径 A（普通开发机/Android Studio）+ 路径 B（Termux 必须 `-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2`）+ keytool 签名 + CI secrets |
| 00:42 | 写 .github/workflows/android.yml | `write` + `js-yaml` 解析校验 | 首版为单 build+release；**YAML 解析通过**（jobs: build, release） |
| 00:45 | 修 YAML 占位符缺陷 | 发现写入时 `${S}` 转义错误（9 处），整文件重写 | 重写后 `js-yaml` 解析通过，`grep '§'` 无残留 |
| 00:50 | 按 Lead 决策重构 workflow | `write` + 校验 | 三 job：`preflight`（检测签名 secret）/ `build`（始终跑：`test lintRelease assembleDebug` + 4 ABI 校验 + artifact）/ `release`（仅 tag 且 `has_signing_key==true`）。**不存在 debug 签名包覆盖正式 Release 的路径** |
| 00:52 | 修正 ABI 表述 | Lead 实测：CameraX 自带 4 个 ABI 原生库 | README/BUILD 删除「APK 内无 .so」的错误表述，改为「4 个 ABI 全部打进同一 APK → 任意 Android 7.0+ 设备可安装」；校验命令改为 `unzip -l $APK \| grep -o 'lib/[^/]*/'` |
| 00:49 | git 仓库准备 | `git init -b main`；`git add -A`；`git ls-files` 逐项审计 | 53 个文件入库，**无 keystore / *.jks / secrets.txt / local.properties / *.apk / *.so**（`grep -Ei` 返回 CLEAN）；`git check-ignore` 证实 `android/local.properties` 与 `dist/` 被忽略 |
| 00:49 | 首次 commit | `git commit` | `2a7c4d7 chore(T7): 项目骨架 + 交付文档（README/USAGE/BUILD）+ CI workflow`（53 files, +3858） |

### 决策与风险记录

1. **未 push**（按任务要求）：本地仓库已就绪，等 task-6 产出 APK 后再创建远端并 push。
2. **平台 JS 证据不入公开仓库**：`tools/wjx-probe/evidence/`（问卷星 `02-jqmobo2.js` 等 274KB 原文）
   属第三方专有代码，我通过 **`.git/info/exclude`（仅本机生效，不改他人文件）** 排除，
   证据仍完整保留在磁盘上。已就此询问 Lead，若 Lead 要求入库可随时撤销该本地排除。
3. **文档中未验证的命令**：路径 A（普通开发机）与 CI 命令本机无法执行；
   路径 B 的 Gradle 构建、`apksigner verify`、ABI 校验命令将在 task-6 出包后
   用真实 APK 逐条复跑后再定稿（见第二阶段日志）。
4. **模板 JSON schema 未在 USAGE.md 展开**：以 docs/API-CONTRACT.md 为准，避免臆造字段。

## 2026-09-22 01:00–01:40　第一阶段续：跨组事实核查与文档纠偏

| 时间 | 动作 | 证据 | 结果 |
| --- | --- | --- | --- |
| 01:00 | 核对 CameraX 是否带原生库 | `unzip -l android/app/build/outputs/apk/debug/app-debug.apk`；`unzip -l ~/.gradle/.../camera-core-1.4.1.aar` | **实测 8 个 .so**：`lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libimage_processing_util_jni.so` + `libsurface_util_jni.so`。故「无 native 代码 / 无 .so」的说法是错的，通用包的正确依据是「4 个 ABI 全打进同一 APK」 |
| 01:01 | 通知 qa-build + Lead | `send_message` | qa-build 的 `scripts/build-apk.sh` 里「APK 内无 .so」「无 native-code」两项校验**必然失败**，已给出 4 ABI 覆盖校验的替代实现 |
| 01:10 | Lead 回复 | 团队消息 | ① 同意 evidence 不入库，要求在文档写明；② `.kotlin/` 已由 Lead 补进 .gitignore，要求我随下次提交带入；③ push 前再核 `local.properties` 与 `dist/` |
| 01:12 | 落实 Lead 要求 | `edit` + `git check-ignore -v` | README 加「evidence 刻意不入库」说明；`dist/probe.apk` 与 `android/local.properties` 均确认被忽略；commit `550de3b` |
| 01:20 | 对齐冻结契约 | 读 `docs/API-CONTRACT.md` §4–§9 | USAGE.md 补齐：匹配优先级 R1/R2/R3、多选用 `\|` 分隔、3000 字上限、并发 1–5（默认 2）、重试失败组、模板 schemaVersion 兼容策略、错误对照表 |
| 01:30 | 读 T3 实测证据 | `tools/wjx-probe/evidence/03-result.json`、`03-run.log` | 提交响应 = `7〒需要安全校验，请重新提交！`（HTTP 200，43B），verdict = **BLOCKED (code 7)** |
| 01:32 | 发现契约缺口并上报 | `send_message` 给 Lead | §8.4 分类器只在正文含 `aliyunwaf`/`captcha`/`验证码` 时判 `E_CAPTCHA`，而真实响应不含任何关键词 → 会落 `E_REJECTED`（可重试语义），与实际「重试必然失败」不符。建议加 `7〒`/`安全校验` 判定或新增 `E_SECURITY_CHECK` |
| 01:35 | 文档如实反映限制 | `edit` + commit `bbda5c5` | README 新增「已知限制（重要）」；USAGE 错误对照表补该案例；均明确写出**不绕过风控** |

第一阶段提交记录（本地 main，未 push）：
`2a7c4d7` 骨架+文档+CI → `cf770a0` USAGE 对齐界面 → `550de3b` evidence 说明+gitignore → `c0f45ae` 契约对齐 → `bbda5c5` 已知限制

## 2026-09-22 03:25–03:35　发布前独立验收（发现阻断项）

qa-build 产出 dist/wjx-autofill-1.0.0-universal.apk（6,138,222 B）+ .sha256 + VERIFY-REPORT.md 后，
我按「push 前独立复验」的要求逐项核对：

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| sha256 | `sha256sum -c dist/wjx-autofill-1.0.0-universal.apk.sha256` | OK（326c1aa90088d7c2…） |
| 4 ABI 通用包 | `unzip -l APK \| grep -o 'lib/[^/]*/' \| sort -u` | arm64-v8a / armeabi-v7a / x86 / x86_64 齐全（8 个 .so 条目） |
| 包名/版本 | `aapt2 dump badging` | com.wjx.autofill 1.0.0 (10000)，targetSdk 35 |
| minSdk | `aapt2 dump xmltree --file AndroidManifest.xml` | **24**（报告里的 FAIL 是脚本 grep 误报） |
| **签名** | `apksigner verify --print-certs` | **FAIL：CN=Android Debug**（不是 release keystore） |

**阻断项**：APK 是 **debug 签名**（sha256 0bd24fdd1e59062bf2351e8bec7b42e7d0e0140f37c8be87bc46286ec60e672a）。
- 证据双重：我的 apksigner 输出 + VERIFY-REPORT.md 自己的 apksig 段均为 `CN=Android Debug`
- 报告漏报：表里「签名配置 PASS」只看环境变量存在；脚本里真正的「签名与配置一致（release）」检查行**未出现在报告中**（未执行）
- 根因（已告知 qa-build）：AGP 在配置阶段读 `providers.environmentVariable("WJX_KEYSTORE_FILE")`，而 Gradle daemon 复用启动时的进程环境 → 本机 daemon 曾在未设 `WJX_*` 时启动 → signingConfigs.release 未创建 → 静默回退 debug
- 处置：**拒绝用该 APK 发 Release**，已上报 Lead 与 qa-build，等 release 签名重跑后再进入第二阶段

## 2026-09-22 03:36–03:55　第二阶段：建仓库、push、发布 v1.0.0（完成）

| 时间 | 动作 | 命令 / 证据 | 结果 |
| --- | --- | --- | --- |
| 03:36 | claim task-7 | `team_task_get` → `team_task_update(claim)` | in_progress，owner=delivery（rev 2） |
| 03:37 | 冻结产物复核 | `cd dist && sha256sum -c …`；`apksigner verify --print-certs` | OK；CN=WJX AutoFill（SHA-256 edcce56e…） |
| 03:38 | fixtures 发布决策 | 发现 `WjxPageParserTest` 强依赖 `tools/wjx-probe/fixtures/` 的 4 个他人问卷页 HTML | 上报 Lead；**裁定：不入库**，改用「缺失可见 skip」（qa-build 已落盘：无夹具 5 skip / 0 fail） |
| 03:40 | 排除第三方内容 | `.git/info/exclude` 增 `tools/wjx-probe/fixtures/`（与 evidence/ 同策略，仅本机） | `git ls-files` 中 fixtures/evidence 均 0 命中 |
| 03:41 | 文档补充重建方法 | `docs/BUILD.md` 新增「本地解析夹具」节：`stage1-fetch.mjs` / `t35b-captcha-scan.mjs` + 4 条 `cp` 映射 | 映射经 `cmp` 实测**逐字节一致**；README 同步说明两个目录不入库 |
| 03:44 | 首次 commit + push 尝试 | `git commit` → `20ab409`；`git push` | **push 挂起**：本机无 git credential helper（`gh auth setup-git` 未执行） |
| 03:45 | 修复并 push | `gh auth setup-git` → `git push -u origin main` | 成功，远端 main = 20ab409 |
| 03:46 | 建 public 仓库 | `gh repo create zimu5683/wjx-auto-filler --public --source=. --remote=origin` | https://github.com/zimu5683/wjx-auto-filler（匿名 API 200，visibility=public） |
| 03:47 | 打 tag 并推送 | `git tag -a v1.0.0` + `git push origin v1.0.0` | 远端 tag 85f4a2d → commit 20ab409；**触发 CI** |
| 03:48 | 发布 Release | `gh release create v1.0.0 dist/wjx-autofill-1.0.0-universal.apk dist/wjx-autofill-1.0.0-universal.apk.sha256 --verify-tag --notes-file …` | https://github.com/zimu5683/wjx-auto-filler/releases/tag/v1.0.0（2 个资产，非 draft/prerelease） |
| 03:50 | 匿名数据源校验（AppUpdater 字段） | `curl -s .../releases/latest` | `tag_name="v1.0.0"` 非空；`assets[].browser_download_url` 两条均非空；size 6138222 / 99 |
| 03:52 | 资产字节校验 | API `assets[].digest` | `sha256:fd1370a7040e805e2e70b438f52d4cb36b26077bf79b54ca29de39b3a433a9ab` **与本地冻结 APK 完全一致** |
| 03:53 | 提交 qa-build 日志并 push | `git commit` → `83df04b`；`git push` | 远端 main = 83df04b（本地/远端一致） |
| 03:54 | CI 结果 | `gh run list --repo …` | tag v1.0.0 的 **Android APK 工作流：completed / success（3m44s）** → A10「脱离 Termux 也能构建」成立 |

**本机网络限制（如实记录）**：从本机 `curl`/`gh release download` 直连 `github.com` 会 connect timeout
（DNS 正常：github.com→20.205.243.166，objects.githubusercontent.com→185.199.x.x；VPN 路由问题），
因此无法在本机下载 Release 资产做字节比对；改用 GitHub 服务端在上传时计算的 `assets[].digest`
（sha256）与本地冻结文件比对，等价且更强（服务端侧计算）。

## 2026-09-22 03:57–04:05　第二阶段续：发布 v1.0.1（应用内更新链路验证版本）

| 时间 | 动作 | 命令 / 证据 | 结果 |
| --- | --- | --- | --- |
| 03:57 | Lead 升版本 | `android/version.properties` → versionName 1.0.1 / versionCode 10001 | qa-build 重建 1.0.1 |
| 03:59 | 独立复验 1.0.1 APK | `sha256sum -c` / `apksigner` / `aapt2 badging` / `unzip -l` | sha256 `0db09f8d1444fb182fb6fceb5c2042c8890ed0b44ad29edcd69cf969d71b3dfb`；1.0.1 (10001)；minSdk 24；4 ABI×2 |
| 04:00 | **跨版本签名一致性（覆盖安装前提）** | `apksigner --print-certs` 对比 1.0.0 与 1.0.1 | 两者证书 SHA-256 **均为** `edcce56ef5d150cc7597223ddb4380bbce328756abb4a8bd13ffbda87c708372` → 可直接覆盖安装 |
| 04:01 | commit + push | `82ad50b release(T7): v1.0.1 …`；`git push origin main` | 远端 main = 82ad50b |
| 04:01 | 打 tag | `git tag -a v1.0.1` + `git push origin v1.0.1` | tag 8b8992a → commit 82ad50b；触发 CI |
| 04:02 | 发布 Release | `gh release create v1.0.1 dist/wjx-autofill-1.0.1-universal.apk dist/…sha256 --verify-tag` | https://github.com/zimu5683/wjx-auto-filler/releases/tag/v1.0.1 |
| 04:03 | 匿名数据源校验 | `curl -s .../releases/latest` | `tag_name="v1.0.1"`；两条 `browser_download_url` 非空；API digest = `sha256:0db09f8d…` **与本地一致** |
| 04:03 | 双 Release 并存 | `GET /releases` | v1.0.1 与 v1.0.0 各自带 APK + .sha256（用户可装 1.0.0 实测更新链路） |
| 04:04 | 入库复核 | `git ls-files testdata/` | `qr-sample.jpg` + `qr-sample.lum.gz` 均已入库（二维码解码用例依赖） |

**最终交付判定**：仓库可访问（public）+ 两个 Release 各含 APK 与 sha256 + 三份文档齐全且命令实测可复现 → **T7 达成**。

## 2026-09-22 08:05–09:25　第三阶段：v1.0.2（叙事两次反转后发布）

| 时间 | 动作 | 证据 | 结果 |
| --- | --- | --- | --- |
| 08:05 | 接到 task-14（T15 发布 v1.0.2），先做文档阶段 | `team_task_get` | task-14 blocked_by task-12，先改文档 |
| 08:12 | 口径修正第 1 轮 | 新样本 `v.wjx.cn/vm/P2M09FG.aspx`、`useAliVerify=0`、实测裸码 `22` | commit `780b631`/`8021373`：三处 shortId 只认 `www.` → 文档写「服务端当次判定」 |
| 08:31 | **口径反转 #1**：V6 单变量 A/B | `11-v6-response.txt`：`ktimes: 0→4` 使同一问卷从裸 `22` 变 `10〒/wjx/join/complete.aspx?joinid=127844297308` | commit `0dbd70a`：`useAliVerify=0` **可纯接口成功**；裸 22 是 `ktimes=0` 风控指纹 |
| 08:45 | **口径反转 #2**：下限与门控 | 契约 §5.3/§6.4 定 `max(4,·)`；本地 `useAliVerify` 门控取消 | commit `6672490`：文档改 `max(4, 页面值)`、改为「总是先发一次提交，仅响应 7/22 触发兜底」 |
| 08:50 | 实现核对（防止文档写空话） | `WjxSubmitter.kt:106 maxOf(4, m.ktimes)`、`:120-130` 无令牌不带校验字段、`:40-41` 无本地门控；三处正则均为 `([A-Za-z0-9-]+\.)*wjx\.cn` | 文档与实现一致 |
| 09:02 | v1.0.2 出包 + 独立复验四项 | `sha256sum -c` OK（`8c0c4758…`）；apksigner `CN=WJX AutoFill`；4 ABI×2 .so；badging `1.0.2/10002` + minSdk 24 | VERIFY-REPORT：ALL PASS(20/0/0)；三版签名指纹完全相同 |
| 09:05 | 板子阻塞 | task-12 未结项 → task-14 `claim` 被拒 | 上报 Lead，由 Lead 代结 task-12 |
| 09:10 | **push 失败 → 定位网络问题** | `git push` 挂起；`curl https://github.com` 25s 超时；`api.github.com` 0.37s 正常 | 非凭据问题：`credential.https://github.com.helper=!gh auth git-credential` 已配置 |
| 09:14 | 找到可用路由并自建代理 | `curl --resolve github.com:443:140.82.112.3` → 200（DNS 解析的 20.205.243.166 超时）；`$TMPDIR/gh-proxy2.mjs` 本地 CONNECT 代理 → 140.82.112.3:443 | `git -c http.proxy=http://127.0.0.1:8901 push` 成功（TLS 端到端，仅绕过坏路由） |
| 09:18 | push main + tag | `d5fc12a..ecee214 main`；tag `v1.0.2` → `ecee214` | 远端 main = ecee214 |
| 09:20 | 发布 Release | `gh release create v1.0.2 dist/… --verify-tag --notes-file` | https://github.com/zimu5683/wjx-auto-filler/releases/tag/v1.0.2 |
| 09:21 | 三项校验 | 匿名 `curl .../releases/latest` | `tag_name="v1.0.2"`；两条 `browser_download_url` 非空；APK digest = `sha256:8c0c4758…` **与本地冻结一致**；签名与 v1.0.0/v1.0.1 同源 |

**本机网络事实（复现用）**：`github.com` 当前 DNS 解析到 `20.205.243.166`（不可达），
而 `140.82.112.3` / `140.82.114.3` 可达；`api.github.com` 始终正常（gh CLI 全部可用）。
因此 git 推送需要本地 CONNECT 代理（`gh-proxy2.mjs`，指向可达 IP），代理只是转发字节、TLS 仍端到端校验。
（v1.0.3 推送时该路由已自行恢复，直接 `git push` 成功，未再启代理。）

## 2026-09-22 09:55–12:10　第四阶段：v1.0.3 发布 → 发现「源码与 APK 不一致」→ 转 v1.0.4

| 时间 | 动作 | 证据 | 结果 |
| --- | --- | --- | --- |
| 09:55 | 接 task-19（T20 发布 v1.0.3） | `team_task_get` | blocked_by task-18，先做文档 |
| 10:00–11:05 | 文档两章 | 逐字取自 `strings.xml`（`schedule_*` 34 条 + `captcha_banner_*` 5 条 + `survey_status_*` 3 条 + `submit_closed_*` 3 条） | commit `204ef54`（README 章节 + USAGE §8/§9 + 顺延 §10–12）、`ed31a85`（权限表/FAQ/隐私） |
| 10:45 | **流水线停滞** | `list_agents`：lead:idle，其余全 inactive；task-15/16 in_progress、task-18 pending 无 owner；dist 无 1.0.3 | 唤醒 lead + qa-build（`send_message`）；Lead 接管关键路径 |
| 11:38 | v1.0.3 出包 + 我四项复验 | `sha256 -c` OK（`5d31ecfd…`）；四版证书 SHA-256 全为 `edcce56e…`；4 ABI×2 .so；badging `1.0.3/10003` | VERIFY-REPORT ALL PASS(20/0/0)，410 单测 0 失败 |
| 11:44 | 用户两项变更落文档 | 核对实现：`MainActivity.autoEnterCaptchaIfNeeded()`（收到 E_CAPTCHA 直接进入验证页）、`Notifier` 响铃/震动通道 | commit `c79e7ef` |
| 11:45 | claim task-19 → 发布 v1.0.3 | commit `8c51d49`；`git push origin main` + tag `v1.0.3`；`gh release create v1.0.3 … --verify-tag` | https://github.com/zimu5683/wjx-auto-filler/releases/tag/v1.0.3；三项校验通过（API digest = `sha256:5d31ecfd…` 与本地一致） |
| 11:46 | qa-build 发来「先不要发」 | lint `MissingPermission`（`Notifier.kt:172`）待 Lead 裁决 | **消息晚于发布**；我立即停止后续动作并上报冲突，未覆盖资产 |
| 11:47 | 我查 v1.0.3 tag 的 CI | `gh run view 35684222347 --log-failed` | **failure**：`:app:lintRelease FAILED` → `Lint found 1 errors, 51 warnings` → `Notifier.kt:172 MissingPermission`；release job 按设计 skipped |
| 11:55 | Lead 改判：v1.0.3 不动、新发 **v1.0.4** | 发现 `Notifier.kt` **11:48:16** 被改（lint 修复），而 dist 的 1.0.3 APK 是 **11:48:25** 由 **11:38 构建输出**复制 → **仓库 HEAD 与已发布 APK 不一致** | 比 lint 本身更严重；version.properties 升 1.0.4/10004；我暂停 push |
| 12:05 | 等 1.0.4 出包 | 发布说明已写好（headline = 源码/APK 一致性修复 + lint 归零 + v1.0.3 CI 红的原因） | 待 qa-build 出包 |

**归档要点**：v1.0.3 的 tag 与资产**保持不动**（已发布 tag 不移动）；其 CI 红是**已知事实**，
原因就是上述 lint Error，不是其他事故；v1.0.4 修复后 CI 应恢复绿色。

## 2026-09-22 15:20–16:20　第五阶段：v1.0.5（用户真机实测三项修改）

| 时间 | 动作 | 证据 | 结果 |
| --- | --- | --- | --- |
| 15:20 | 接 task-25（T26 发布 v1.0.5） | 板上 task-21/22/23 进行中、task-24/25 pending | 先做文档阶段，不空等 |
| 15:27 | 观察变更落地 | `captcha_banner` 在 strings.xml 的计数 6 → **0**（横幅已移除） | 继续等 UI 定稿 |
| 15:48 | UI/引擎定稿 | 新增 `action_delete_template`「删除模板」、`dialog_delete_template_message`、`toast_template_deleted`、`result_skipped_fields`「已跳过 %1$d 个未匹配字段：%2$s…」；移除 import/export 与 banner；引擎 `SurveyModel.skippedFields` + 契约 §7.2/§7.4 | 文档逐字对齐，不臆造 |
| 15:50 | 文档三处更新 | commit `7f5280a`（移除导入/导出 + 删除模板 + 超量预填 + 去横幅）、`82ea3e6`（错误表区分「已跳过」与「仍失败」） | grep 复核无残留（仅保留说明变更本身的句子） |
| 16:01 | v1.0.5 出包 + 六项独立复验 | sha256 `7cbb7982…`；**六版签名同源**；4 ABI×2 .so；badging 1.0.5/10005；**448 单测 0 失败**；lintRelease **0 Error**；APK 资源 `captcha_banner` 计数 **0** | 全部通过 |
| 16:04 | 板子阻塞 → 上报 | task-24 仍 pending → task-25 `claim` 被拒 | Lead 代结 task-24（沿用前几轮的处置方式） |
| 16:06 | claim → 发布 v1.0.5 | commit `1b49790`（`git add -A` 带上全部未提交项，避免重演 v1.0.3 的 HEAD/APK 不一致）；push main + tag `v1.0.5`；`gh release create … --verify-tag` | https://github.com/zimu5683/wjx-auto-filler/releases/tag/v1.0.5 |
| 16:08 | 四项校验 | 匿名 `releases/latest` + apksigner 六版对比 | latest=v1.0.5；两条 URL 非空；APK digest = `sha256:7cbb7982…` **与本地冻结一致**；六版证书 SHA-256 全为 `edcce56e…` |
| 16:20 | CI | `gh run view 35702893194` | **completed / success** |

**交付序列**：v1.0.0（更新链路起点）→ v1.0.1 → v1.0.2 → v1.0.3（历史，CI 红已知且已归档）→ v1.0.4 → **v1.0.5（当前最终）**；
六版共用同一把 release 签名（`edcce56e…`），应用内更新与覆盖安装链路完整。

## 2026-09-22 16:30–17:10　第六阶段：v1.0.6（用户真机实测两处修复）

| 时间 | 动作 | 证据 | 结果 |
| --- | --- | --- | --- |
| 16:30 | 接 task-29（T30 发布 v1.0.6） | 板上 task-27 in_progress、task-28/29 pending | 先做文档阶段，不空等 |
| 16:38 | 等 UI 定稿 | strings.xml 出现 `label_mapping_count`「字段映射（%1$d 条）」、`parse_no_open_time`「未解析到开放时间，请手动填写」 | 逐字对齐，不臆造 |
| 16:40 | 核对实现（防止文档写空话） | `MainActivity:398` 用 `label_mapping_count` 渲染标题；`:587-596` `ScheduleTime.applyParsedOpenTime(current, parsed) = parsed>0 ? format(parsed) : current`；`layout:318-327` 注释记录「ScrollView 内 RecyclerView（wrap_content + 未 setHasFixedSize）超过约 4 行不再可靠重新测量」→ 改为动态填充 LinearLayout | 文档与实现一致 |
| 16:42 | 文档两条 | commit `8d1a49a`：README/USAGE 补「映射**无条数上限** + 标题显示条数」「解析后**自动填入开放时间**（解析不到保留原值并提示）」 | 完成 |
| 16:52 | v1.0.6 出包 + 五项独立复验 | sha256 `6b5672a1…`；**七版签名同源**；4 ABI×2 .so；badging 1.0.6/10006；**482 单测 0 失败**；lintRelease **0 Error**（16:52:45） | 全部通过 |
| 16:53 | 板子阻塞 → 上报 | task-28 仍 pending → task-29 `claim` 被拒 | Lead 代结 task-28（沿用既定处置） |
| 16:55 | claim → 发布 v1.0.6 | commit `5a3c09a`（`git add -A` 带全部未提交项）；push main + tag `v1.0.6`；`gh release create … --verify-tag` | https://github.com/zimu5683/wjx-auto-filler/releases/tag/v1.0.6 |
| 16:57 | 四项校验 | 匿名 `releases/latest` + apksigner 七版对比 | latest=v1.0.6；两条 URL 非空；APK digest = `sha256:6b5672a1…` **与本地冻结一致**；七版证书 SHA-256 全为 `edcce56e…` |
| 17:05 | CI | `gh run view 35707424915` | **completed / success** |

**如实标注（写进发布说明）**：本版「映射行的显示/滚动行为」**没有 JVM 单测覆盖**（UI 行为），
需真机验证「连续加 8 条是否全部可见可编辑、整页可滚、无内嵌滚动条」；
另 `SurveyStatus.fromModel()` 修复了 T16 遗留的 `openAt` 传 null（顶部状态条此前从未真正显示开放状态）。

**交付序列**：v1.0.0 → v1.0.1 → v1.0.2 → v1.0.3（历史）→ v1.0.4 → v1.0.5 → **v1.0.6**，
七版共用同一把 release 签名（`edcce56e…`），应用内更新与覆盖安装链路完整。

## 2026-09-22 18:05–19:20　第七阶段：v1.0.7（未开放问卷自动填时间 + 解析失败收敛状态）

| 时间 | 动作 | 证据 | 结果 |
| --- | --- | --- | --- |
| 18:05 | 接 task-35（T36 发布 v1.0.7） | task-31/32 pending、task-33 in_progress | 先做文档阶段，不空等 |
| 18:20 | 观察修复落地 | `WjxErrors.kt:21` `WjxException.openAtMillis`（additive，仅 E_NOT_OPEN 且解析到时间时非空）；`WjxSurveyClient.kt:326-331/364` 未开放短路携带结构化时间 | — |
| 18:23 | **跨组解阻塞（我主动做的一件事）** | android-dev 的 `// TODO(S1 落盘后)` 卡在等 api-debug 的字段，而该字段**已在磁盘上** → 发消息附 file:line 证据 | 5 分钟后 `MainActivity:620` 改为 `val openAt = wjx.openAtMillis`，TODO 解除 |
| 18:35 | 文档四条 | commit `0b38507` / `362b382` / `64711a3`：未开放也自动填入开放时间、状态条「尚未开放，将于 … 开放」、**解析/提交两处错误表补 E_NOT_OPEN 行**、**北京时间 GMT+08:00 口径**、按钮置灰但手动路径仍可用 | 完成 |
| 18:46 | v1.0.7 出包 + 五项独立复验 | sha256 `7a809d8c…`；**八版签名同源**；4 ABI×2 .so；badging 1.0.7/10007；**530 单测 0 失败**；lintRelease **0 Error**（18:46:52） | 全部通过 |
| 18:48 | 板子阻塞 → 上报 | task-34 仍 pending → task-35 `claim` 被拒 | Lead 代结 task-34 |
| 18:55 | claim → commit → **push 失败** | `fatal: unable to access '…': Failed to connect to github.com:443 after 129246 ms` | **老问题复现**：github.com 不可达、api.github.com 正常 |
| 18:57 | 网络定位 + 本地代理 | `curl --resolve`：140.82.112.3 = 200、20.27.177.113 = 200；140.82.114.3 / 20.205.243.166 = 000 → 起 `gh-proxy2.mjs`（127.0.0.1:8901，只转发字节、TLS 端到端） | push main + tag 成功（main = 9e853b6；tag 68316565 → 9e853b6） |
| 19:00 | 发布 Release | `gh release create v1.0.7 … --verify-tag --notes-file` | https://github.com/zimu5683/wjx-auto-filler/releases/tag/v1.0.7 |
| 19:02 | 四项校验 | 匿名 `releases/latest` + apksigner 八版对比 | latest=v1.0.7；两条 URL 非空；APK digest = `sha256:7a809d8c…` **与本地冻结一致**；八版证书 SHA-256 全为 `edcce56e…` |
| 19:05 | 补提交剩余项 | commit `a8fa0d3`（qa-build 日志）→ 走代理 push | 远端 main = a8fa0d3（与本地一致） |
| 19:12 | CI | `gh run view 35719244835` | **completed / success** |
| 19:15 | 清理 | `job_kill` 本地代理进程 | 已结束（不留后台进程） |

**最终交付序列**：v1.0.0 → v1.0.1 → v1.0.2 → v1.0.3（历史，CI 红已知）→ v1.0.4 → v1.0.5 → v1.0.6 → **v1.0.7（当前最终）**，
八版共用同一把 release 签名（`edcce56e…`），应用内更新与覆盖安装链路完整。
