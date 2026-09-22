# 问卷自动填表（wjx-auto-filler）

面向 **问卷星（wjx.cn）** 的 Android **纯接口自动填表**工具：答案构造与提交全程走 HTTP 协议
（不模拟点击、不自动填表、不做浏览器自动化），一次配置多组内容、并行提交。

> 唯一的例外是**人机验证环节**：服务端要求二次校验时（这是**常规路径之一**，见「已知限制」），
> App 会打开一个内置页面，由**用户本人**完成一次验证，再把验证令牌带回接口继续提交 ——
> 验证步骤交给人，提交仍然走纯接口，不绕过风控。详见 [docs/USAGE.md](docs/USAGE.md)。

- 支持 **Android 7.0（API 24）及以上**，产出**通用包**：依赖的 CameraX 自带 arm64-v8a / armeabi-v7a /
  x86 / x86_64 四个 ABI 的原生库，四个 ABI 全部打进同一个 APK，**任意 Android 7.0+ 设备可安装**，无需按 ABI 分发
- **无 GMS**、无广告、无统计 SDK、无第三方追踪；**不依赖 Termux、不需要 root**
- 配置与模板只存本地，不采集、不上传任何用户数据

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 扫码导入 | CameraX 实时识别问卷二维码；也支持从**相册选图**解码（ZXing 本地解码，图片不写入、不上传） |
| 链接导入 | 直接粘贴问卷链接（`https://www.wjx.cn/vm/xxxx.aspx`、`https://v.wjx.cn/vm/xxxx.aspx` 等 **`*.wjx.cn` 子域**短链均可），导入前校验域名与路径 |
| 问卷解析 | 拉取问卷页面，解析题目、选项、题型与必填标记，生成可映射字段列表 |
| 左右双栏映射 | 左栏**目标字段**（题号 `1`/`q1` 或题干关键词），右栏**填充内容**；支持新增/删除/清空行 |
| 多组内容管理 | **新增组 / 重命名 / 删除组**（方案 1、方案 2 …），每组独立映射，一次性**并行提交** |
| 模板 | 保存命名模板、导入 / 导出 JSON 模板（仅本地文件） |
| 提交结果 | 逐组显示耗时、成功/失败与平台返回信息，失败可单独重试 |
| 应用内更新 | 匿名读取 GitHub Releases，发现新版本后下载 APK 并交系统安装器 |
| 验证码兜底 | **常规路径**：服务端要求二次校验（码 7 / 码 22）时，**由你本人**在内置页面完成一次验证，App 用同一会话 + 令牌继续走纯接口提交（不填表、不点提交） |

## 已知限制与验证码兜底（重要，请先读）

### 1. 能否纯接口提交，取决于**服务端当次判定**（页面开关不足以判断）

问卷星在提交时按风控策略决定是否要求二次校验，**与页面变量无关**：

| 实测样本 | 页面 `useAliVerify` | 真实提交结果（HTTP 200） |
| --- | --- | --- |
| `https://www.wjx.cn/vm/Q0DQewW.aspx` | `1` | 正文 `7〒需要安全校验，请重新提交！` → 业务码 **7** |
| `https://v.wjx.cn/vm/P2M09FG.aspx`（标题「测试（无人机验证）」） | `0` | 正文 `22`（**裸业务码，无 `〒` 分隔**） → 业务码 **22**（服务端主动要求二次校验） |

结论：**`useAliVerify` 只是页面初始值，服务端可以对任意一次提交返回码 7 或 22**。
因此「验证码兜底」不是少数情况，而是**常规路径之一**：凡被服务端要求二次校验的提交都会走到下面的流程。
**本工具不绕过风控**，而是把「人机验证」这一步交还给人：

1. App 先用纯 HTTP 拉取问卷页面并提交；
2. 服务端返回码 7 / 22 → 结果区显示「需验证」与 **人工验证后重试** 按钮；
3. 你**主动点击** → App 打开内置验证页面（WebView，加载**真实问卷 URL**，绝不自动打开）；
4. 你在页面上**本人完成一次阿里云验证**（滑块 / 点选）；
5. App 收割验证令牌 `captchaVerifyParam`，并读回验证页面的会话 Cookie（**与提交引擎共享同一会话**）；
6. App 用该会话**重新拉取页面**（取新的 `jqnonce` / `starttime`），再由**纯 HTTP 接口**提交，
   POST 体携带 `captchaVerifyParam` + `sceneId`；成功后服务端返回业务码 `10`。

**硬边界（写死在代码与契约里）**：注入 WebView 的 JS 只有两个固定常量 —— 唤起验证码控件、读取令牌变量。
**绝不填写任何表单字段、绝不点击任何提交入口、绝不把答案数据传给页面**；答案的构造与提交永远由
App 的 HTTP 引擎（`HttpURLConnection`）完成。**每个需要验证的组各有 1 次人工验证机会，不设跨组上限**；
多组都需要验证时逐组处理（按钮显示条件与消耗规则见 [docs/USAGE.md](docs/USAGE.md) 7.1）。

### 2. 端到端成功的唯一途径（必须知情）

两个实测样本（码 7 与码 22）都表明：**纯 HTTP 路径在真实提交上拿不到成功响应**。
只有你亲自完成上面第 4 步，才能观察到业务码 `10`（成功）。若不做这一步，本项目只能提供
「解析 / 匹配 / 编码 / 分类 / 兜底时序」的**单测级证据**，端到端成功**无法证明**。

### 3. 其他限制

- 支持 **`*.wjx.cn` 子域短链**：`https://<子域>.wjx.cn/vm/xxxx.aspx`（如 `www.wjx.cn`、`v.wjx.cn`），
  实测 `https://v.wjx.cn/vm/P2M09FG.aspx` 可正常解析与提交。
- **分页 / 逐题模式问卷不支持**（宁可明确失败，也不提交半份答卷）。
- **矩阵题、滑块题暂不支持**自动填写（会明确报错，不静默跳过）。
- 能否纯接口提交**只能实发才知道**：页面开关不代表服务端一定放行；问卷星页面改版也可能导致解析失败。

## 安装

1. 打开 [Releases](https://github.com/zimu5683/wjx-auto-filler/releases) 下载最新
   `wjx-autofill-<版本>-universal.apk`
2. 在系统设置中允许该来源「安装未知应用」，然后点击 APK 安装
3. 校验完整性（可选）：

   ```bash
   # 与 Release 资产 wjx-autofill-<版本>-universal.apk.sha256 同目录时：
   sha256sum -c wjx-autofill-1.0.0-universal.apk.sha256
   # Windows：
   certutil -hashfile wjx-autofill-1.0.0-universal.apk SHA256
   ```

> 首次安装请**始终从本仓库 Release 安装**。应用内更新要求新旧 APK 使用同一把签名密钥，
> 否则系统会拒绝覆盖安装（只能卸载后重装，本地配置会丢失）。

## 构建

完整步骤（含 Android Studio、命令行、本机 Termux 两条路径、签名与 CI secrets）见 **[docs/BUILD.md](docs/BUILD.md)**。

最短路径（Linux/macOS，已装 JDK 17 + Android SDK 35）：

```bash
cd android
./gradlew assembleRelease
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

本机 Termux（arm64）**必须**追加 aapt2 覆盖参数，否则 SDK 自带的 x86-64 aapt2 无法执行：

```bash
cd android
./gradlew assembleRelease -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2
```

## 更新机制

应用内更新走 **匿名** GitHub Releases API（不带 token）：

1. `GET https://api.github.com/repos/zimu5683/wjx-auto-filler/releases/latest`
2. 读取 `tag_name`（如 `v1.0.1`），去掉 `v` 后与本地 `versionName` 做语义化三段比较，不高于本地则不提示
3. 在 `assets[]` 中优先选择名字含 `universal`（其次 `arm64`）的 `.apk`，取其 `browser_download_url`
4. 下载到应用缓存目录 `cacheDir/updates/`，经 `FileProvider` 交给系统 `PackageInstaller` 安装
5. 没有 APK 资产的 Release 会被忽略，不弹更新提示

> 因此本仓库**必须保持 public**：private 仓库的匿名 API 请求返回 404，应用内更新会静默失效。
> 每次发版必须同时上传 `.apk` 与 `.apk.sha256`。

## 目录结构

```
android/                              Android 工程（AGP 8.9.1 / Kotlin 2.1.20 / minSdk 24 / targetSdk 35）
  app/src/main/java/com/wjx/autofill/
    wjx/                              问卷星协议引擎（纯 Kotlin/JVM，禁止 android.* 依赖，可被 JVM 单测直接跑）
    ui/ config/ qr/ update/           界面（含 CaptchaActivity 验证兜底）、配置与模板、扫码、应用内更新
  app/src/main/res/                   布局、图标、文案
  app/src/test/                       单元测试（编码/签名/字段匹配/模板/版本比较/二维码解码）
  version.properties                  版本单一真源：versionName + versionCode
  gradlew / gradle/wrapper/           Gradle 8.11.1 wrapper
docs/                                 DESIGN.md、API-CONTRACT.md、USAGE.md、BUILD.md、logs/
scripts/                              构建与打包脚本
tools/wjx-probe/                      问卷星接口实测脚本（原始证据刻意不入库，见下方说明）
testdata/                             测试用问卷页面样本
dist/                                 本地构建产物（.gitignore 忽略，不入库）
.github/workflows/android.yml         CI：始终构建 debug 通用包；tag v* 且配置签名 secrets 时发布 Release
```

> 说明：`tools/wjx-probe/evidence/`（问卷星页面与平台 JS 原文）与 `tools/wjx-probe/fixtures/`
> （他人问卷页面抓取）属于第三方内容，刻意**不纳入公开仓库**（仅在本机保留），以免构成对他人内容/代码的再分发。
> 仓库中只保留自研的探测脚本与合成测试数据；需要真实页面夹具时可按 [docs/BUILD.md](docs/BUILD.md) 的
> 「本地解析夹具」一节自行只读重建。

## 隐私说明

- 问卷链接、字段映射与答案模板**全部保存在设备本地**（应用私有存储），不上传任何服务器
- 不收集设备标识、不采集使用统计、不含广告或第三方统计 SDK
- 应用只在两处发起网络请求：
  1. 访问问卷星站点获取问卷页面并提交答案（**由用户主动触发**）
  2. 匿名访问 GitHub Releases API 检查更新（用户可在界面关闭/忽略）
- 验证码兜底页面（WebView）同样只加载 `wjx.cn` 的问卷页面。为让验证令牌与提交会话配对，
  验证页面与提交引擎**共享同一会话 Cookie**；验证结束后立即清理该会话的 Cookie（不使用全局清空，避免影响其他内容组）。
  WebView 按最小权限配置：禁用本地文件访问与多窗口、禁止明文流量、只放行 `wjx.cn` 的 https 跳转、不注册任何 JS Bridge。
- 相册图片仅在本机内存中解码，不写入外部存储、不上传
- 卸载应用即清除全部本地配置与模板

## 免责声明

- 本项目仅供个人在**已获授权**的范围内使用（自测、自己的问卷、已取得问卷所有者许可的场景）
- 使用者应遵守问卷星平台的服务条款及适用法律法规；**不得**用于刷量、伪造数据、
  绕过平台风控（含验证码）、干扰平台正常运营或任何未经授权的用途
- 问卷是否接受纯接口提交由平台风控决定；本工具不提供、也不会提供任何绕过验证码或风控的能力：
  人机验证必须由**用户本人**在内置页面完成，答案提交始终由纯 HTTP 引擎执行
- 使用本工具产生的一切后果由使用者自行承担，作者不对可用性、数据结果或任何间接损失作出担保
