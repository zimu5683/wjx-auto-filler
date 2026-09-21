# 问卷自动填表（wjx-auto-filler）

面向 **问卷星（wjx.cn）** 的 Android **纯接口自动填表**工具：不使用 WebView、不模拟点击、不做浏览器自动化，
而是直接按问卷星网页表单协议构造并提交答案，一次配置多组内容、并行提交。

- 支持 **Android 7.0（API 24）及以上**，产出**通用包**：依赖的 CameraX 自带 arm64-v8a / armeabi-v7a /
  x86 / x86_64 四个 ABI 的原生库，四个 ABI 全部打进同一个 APK，**任意 Android 7.0+ 设备可安装**，无需按 ABI 分发
- **无 GMS**、无广告、无统计 SDK、无第三方追踪；**不依赖 Termux、不需要 root**
- 配置与模板只存本地，不采集、不上传任何用户数据

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 扫码导入 | CameraX 实时识别问卷二维码；也支持从**相册选图**解码（ZXing 本地解码，图片不写入、不上传） |
| 链接导入 | 直接粘贴问卷链接（`https://www.wjx.cn/vm/xxxx.aspx`），导入前校验域名与路径 |
| 问卷解析 | 拉取问卷页面，解析题目、选项、题型与必填标记，生成可映射字段列表 |
| 左右双栏映射 | 左栏**目标字段**（题号 `1`/`q1` 或题干关键词），右栏**填充内容**；支持新增/删除/清空行 |
| 多组内容管理 | **新增组 / 重命名 / 删除组**（方案 1、方案 2 …），每组独立映射，一次性**并行提交** |
| 模板 | 保存命名模板、导入 / 导出 JSON 模板（仅本地文件） |
| 提交结果 | 逐组显示耗时、成功/失败与平台返回信息，失败可单独重试 |
| 应用内更新 | 匿名读取 GitHub Releases，发现新版本后下载 APK 并交系统安装器 |

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
    ui/ config/ qr/ update/           界面、配置与模板、扫码、应用内更新
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

> 说明：`tools/wjx-probe/evidence/` 保存的是问卷星页面与平台 JS 的**原文**，属于第三方专有内容，
> 刻意**不纳入公开仓库**（仅在本机保留），以免构成对他人代码的再分发。仓库中只保留自研的探测脚本。

## 隐私说明

- 问卷链接、字段映射与答案模板**全部保存在设备本地**（应用私有存储），不上传任何服务器
- 不收集设备标识、不采集使用统计、不含广告或第三方统计 SDK
- 应用只在两处发起网络请求：
  1. 访问问卷星站点获取问卷页面并提交答案（**由用户主动触发**）
  2. 匿名访问 GitHub Releases API 检查更新（用户可在界面关闭/忽略）
- 相册图片仅在本机内存中解码，不写入外部存储、不上传
- 卸载应用即清除全部本地配置与模板

## 免责声明

- 本项目仅供个人在**已获授权**的范围内使用（自测、自己的问卷、已取得问卷所有者许可的场景）
- 使用者应遵守问卷星平台的服务条款及适用法律法规；**不得**用于刷量、伪造数据、
  绕过平台风控（含验证码）、干扰平台正常运营或任何未经授权的用途
- 问卷是否接受纯接口提交由平台风控决定；本工具不提供、也不会提供任何绕过验证码或风控的能力
- 使用本工具产生的一切后果由使用者自行承担，作者不对可用性、数据结果或任何间接损失作出担保
