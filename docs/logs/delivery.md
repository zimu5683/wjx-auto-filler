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

### 待办（第二阶段，依赖 task-6）

- [ ] 领取 task-7（task-6 完成后 `team_task_update(claim)`）
- [ ] 核对 AppUpdater 仓库常量与 UI 文案，回填 USAGE.md 实际按钮名
- [ ] `gh repo create zimu5683/wjx-auto-filler --public` + push main
- [ ] Release v1.0.0（`wjx-autofill-1.0.0-universal.apk` + `.sha256`）
- [ ] 配合 lead/qa-build 发 v1.0.1（验证应用内更新链路）
- [ ] `curl -s .../releases/latest` 校验 `tag_name` 与 `assets[].browser_download_url` 非空
