# 构建与发布指南（wjx-auto-filler）

本工程是标准 Android Gradle 工程（AGP 8.9.1 / Kotlin 2.1.20 / Gradle 8.11.1 / JDK 17 / compileSdk 35 / minSdk 24）。

工程**刻意不配置 `ndk.abiFilters`**，产出**通用包**：依赖的 CameraX 自带 arm64-v8a / armeabi-v7a / x86 /
x86_64 四个 ABI 的原生库（`lib/<abi>/libimage_processing_util_jni.so` 等），四个 ABI 全部打进同一个 APK，
因此**任意 Android 7.0+ 设备可安装**，无需按 ABI 分发多个包。
这是硬约束：**不要**添加 `abiFilters` 去过滤 ABI，否则「换一台全新设备也能装」的保证会被破坏。

---

## 路径 A：普通开发机（Windows / macOS / Linux，推荐）

### A1. 前置

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17**（Temurin/OpenJDK 均可） | AGP 8.9 要求 JDK 17 |
| Android SDK Platform | **android-35** | `compileSdk = 35` |
| Android Build-Tools | **35.0.0** | `aapt2` / `apksigner` |
| Gradle | 8.11.1 | 由 `android/gradlew` 自动下载，无需单独安装 |

安装 SDK 组件（Android Studio 用户可在 SDK Manager 里勾选同名项）：

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

### A2. 指定 SDK 路径（二选一）

```bash
# 方式一：环境变量
export ANDROID_HOME="$HOME/Android/Sdk"          # macOS: $HOME/Library/Android/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

方式二：在 `android/local.properties` 写入（该文件已被 .gitignore 忽略，**不要提交**）：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

### A3. 构建

```bash
cd android
./gradlew assembleRelease          # Windows: gradlew.bat assembleRelease
```

产物：`android/app/build/outputs/apk/release/app-release.apk`

其他常用任务：

```bash
./gradlew testReleaseUnitTest      # 单元测试
./gradlew lintRelease              # 静态检查（NewApi 为 error，保证 minSdk 24 安全）
./gradlew clean                    # 清理
```

### A4. Android Studio

`File → Open` 选择本仓库的 **`android/`** 目录（不是仓库根目录），等待 Gradle Sync 完成后
用 `Build → Build Bundle(s) / APK(s) → Build APK(s)`，或在 Terminal 里跑 `./gradlew assembleRelease`。

---

## 路径 B：本机 Termux（arm64 Android 设备上直接构建）

本机已实测环境：Termux + JDK 17.0.20 + Gradle 8.11.1 wrapper + SDK 位于 `$HOME/android-sdk`
（`platforms/android-35` + `build-tools/35.0.0`）。

### B1. 为什么必须加 aapt2 覆盖参数

SDK `build-tools/35.0.0/aapt2` 是 **x86-64 ELF** 二进制，在 arm64 的 Termux 上无法执行
（报 `cannot execute binary file` / `Exec format error`）。AGP 提供了覆盖入口，指向 Termux 安装的
**AArch64** `aapt2`：

```bash
pkg install aapt2            # 提供 $PREFIX/bin/aapt2（实测 ELF64 AArch64）
```

### B2. 构建命令（必须带 `-Pandroid.aapt2FromMavenOverride`）

```bash
cd ~/wjx-auto-filler/android
./gradlew assembleRelease -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2
```

产物同样在 `android/app/build/outputs/apk/release/app-release.apk`。

### B3. 内存约束（本机可用内存约 2GB + swap）

- **同一时间只跑一个 Gradle 构建**（并行构建会 OOM）
- 构建一律放后台并落日志，不要前台等待：

  ```bash
  cd ~/wjx-auto-filler/android
  nohup ./gradlew assembleRelease -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2 \
    > "$TMPDIR/build-release.log" 2>&1 &
  tail -f "$TMPDIR/build-release.log"
  ```

- 堆参数已在 `android/gradle.properties` 固定为 `-Xmx2560m`（`kotlin.daemon.jvmargs=-Xmx1536m`），
  不要盲目调大

### B4. wrapper 说明

`android/gradle/wrapper/gradle-wrapper.properties` 固定 Gradle **8.11.1** 并带 `distributionSha256Sum`，
下载源为腾讯镜像。wrapper 已在本机解压；离线环境请保证该版本已缓存。

---

## 签名（正式分发必读）

`android/app/build.gradle.kts` 从**环境变量**读取 release 签名，缺失时**回退 debug 签名**
（回退包仅能本地调试，无法覆盖安装正式包，也无法用于应用内更新链路）。

| 环境变量 | 含义 |
| --- | --- |
| `WJX_KEYSTORE_FILE` | keystore 文件路径（绝对路径或相对 `android/`） |
| `WJX_KEYSTORE_PASSWORD` | keystore 口令 |
| `WJX_KEY_ALIAS` | 密钥别名 |
| `WJX_KEY_PASSWORD` | 密钥口令 |

### 1. 生成 keystore（只需一次，务必离线备份）

```bash
mkdir -p ~/wjx-release
keytool -genkeypair -v \
  -keystore ~/wjx-release/wjx-release.keystore \
  -alias wjx -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=wjx-auto-filler, OU=personal, O=personal, C=CN"
```

> `keytool` 会交互式询问口令；也可用 `-storepass` / `-keypass` 传参，但**不要**把口令写进仓库。
> keystore、口令、`*.jks`、`local.properties` 均已在 `.gitignore` 中，**严禁提交**。

### 2. 带签名构建

```bash
export WJX_KEYSTORE_FILE="$HOME/wjx-release/wjx-release.keystore"
export WJX_KEYSTORE_PASSWORD='<你的口令>'
export WJX_KEY_ALIAS='wjx'
export WJX_KEY_PASSWORD='<你的口令>'

cd android
./gradlew assembleRelease -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2   # Termux 才需要后半段
```

### 3. 校验签名与产物

```bash
APK=android/app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs "$APK"
sha256sum "$APK"
# 通用包校验：APK 内应同时包含 4 个 ABI 目录
unzip -l "$APK" | grep -o 'lib/[^/]*/' | sort -u
# 期望输出：lib/arm64-v8a/  lib/armeabi-v7a/  lib/x86/  lib/x86_64/
```

---

## CI（GitHub Actions）

工作流：`.github/workflows/android.yml`，三个 job：

| job | 何时运行 | 作用 |
| --- | --- | --- |
| `preflight` | 总是 | 检测仓库是否配置了 `WJX_KEYSTORE_BASE64`，输出 `has_signing_key` |
| `build` | 总是 | 官方 Gradle 8.11.1 + Android SDK 35 → `test lintRelease assembleDebug` → 校验 4 个 ABI 齐全 → 上传 artifact。**用于证明脱离 Termux 的普通环境也能构建** |
| `release` | 仅 `tag v*` **且** `has_signing_key == true` | 校验 tag 与 `versionName` 一致 → 签名 `assembleRelease` → 上传 APK + sha256 → 创建/更新 Release |

- 触发方式：推送 tag（`v*`）或 `workflow_dispatch` 手动触发
- **本仓库刻意不配置签名 secrets**（密钥不出本机）。因此当前推送 tag 只会跑 `build`，
  `release` 被明确跳过并打印 `::notice::` 说明 —— **不存在「debug 签名包覆盖正式 Release」的代码路径**
- CI 使用官方 Gradle（`gradle/actions/setup-gradle`，版本 8.11.1），不依赖 wrapper 下载
- CI **不要**传 `-Pandroid.aapt2FromMavenOverride`：GitHub runner 是 x86-64，SDK 自带 aapt2 正常可用；
  该参数只用于 arm64 的 Termux

### 如需启用 CI 自动发布（可选，密钥会离开本机）

```bash
base64 -w0 ~/wjx-release/wjx-release.keystore > "$TMPDIR/keystore.b64"
gh secret set WJX_KEYSTORE_BASE64  < "$TMPDIR/keystore.b64"
gh secret set WJX_KEYSTORE_PASSWORD
gh secret set WJX_KEY_ALIAS
gh secret set WJX_KEY_PASSWORD
rm -f "$TMPDIR/keystore.b64"
```

配置后 `release` job 会自动启用。Release 资产命名固定为
`wjx-autofill-<versionName>-universal.apk` + 同名 `.sha256`，
与应用内更新（`AppUpdater` 优先匹配含 `universal`/`arm64` 的 `.apk` 资产）保持一致。

### 手工发布（等价流程，本机可用）

```bash
gh release create v1.0.0 \
  dist/wjx-autofill-1.0.0-universal.apk \
  dist/wjx-autofill-1.0.0-universal.apk.sha256 \
  --title "v1.0.0" --notes "首个正式版本"
```

发布后必须校验应用内更新的数据源：

```bash
curl -s https://api.github.com/repos/zimu5683/wjx-auto-filler/releases/latest \
  | grep -E '"tag_name"|"name": "wjx-autofill' 
```

---

## 版本号规则

版本唯一真源：`android/version.properties`

```properties
versionName=1.0.0
versionCode=10000
```

- `versionCode = major*10000 + minor*100 + patch`（`1.0.1 → 10001`），升级必须递增
- GitHub Release 的 tag 必须与 `versionName` 一致（`v1.0.1`），CI 会强制校验
- 发新版本时：改 `version.properties` → 构建 → 打 tag → 推送 → 发布 Release
