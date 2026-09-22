#!/usr/bin/env bash
# =============================================================================
# scripts/build-apk.sh — wjx-auto-filler 一键构建 + 完整校验 + 报告
#
# 做四件事：
#   1. 构建 release APK（签名从 WJX_KEYSTORE_* 环境变量读；缺失则警告并回退 debug 签名）
#   2. 复制为 dist/wjx-autofill-<versionName>-universal.apk 并生成 .sha256
#   3. 校验：apksig 验签 / aapt2 badging（包名·版本·minSdk 24·targetSdk 35）
#            / 通用包保证：arm64-v8a + armeabi-v7a + x86 + x86_64 四个 ABI 目录齐全、
#              每个 .so 在 4 个 ABI 下都有、且工程内无自研 native 代码（A7 口径）
#            / 依赖树无 com.google.android.gms / 二维码夹具解码（A7）
#   4. 把全部结果写成 dist/VERIFY-REPORT.md
#
# 用法：
#   scripts/build-apk.sh [--quick] [--skip-tests] [--skip-lint] [--skip-build] [--clean]
#
# 本机（Termux/arm64）约束：
#   - 必须带 -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2（SDK 自带 aapt2 是 x86-64）
#   - 可用内存约 2GB：同一时间只允许一个 Gradle 构建，本脚本用 dist/.build.lock 串行化
# =============================================================================
set -o pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
DIST_DIR="$ROOT_DIR/dist"
LOG_DIR="$DIST_DIR/logs"
VERSION_FILE="$ANDROID_DIR/version.properties"
RAW_APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
REPORT="$DIST_DIR/VERIFY-REPORT.md"
LOCK_DIR="$DIST_DIR/.build.lock"

RUN_TESTS=1
RUN_LINT=1
RUN_BUILD=1
RUN_CLEAN=0
RUN_INTEGRATION=0

usage() {
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
    case "$arg" in
        --quick)      RUN_TESTS=0; RUN_LINT=0 ;;
        --skip-tests) RUN_TESTS=0 ;;
        --skip-lint)  RUN_LINT=0 ;;
        --skip-build) RUN_BUILD=0 ;;
        --clean)      RUN_CLEAN=1 ;;
        --integration) RUN_INTEGRATION=1 ;;
        -h|--help)    usage; exit 0 ;;
        *) echo "未知参数：$arg（用 --help 查看用法）" >&2; exit 2 ;;
    esac
done

mkdir -p "$DIST_DIR" "$LOG_DIR"

# ---------------------------------------------------------------------------
# 结果收集 + 报告（先定义，前置检查失败时也能出报告）
# ---------------------------------------------------------------------------
RESULTS_FILE="$(mktemp)"
PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0
VERSION_NAME="?"
VERSION_CODE="?"
APK_NAME="wjx-autofill-<version>-universal.apk"
APK_SHA="<未生成>"
APK_SHA_SHORT="?"
APK_SIZE="?"
SIGN_MODE="release"
APKSIG_OUT=""
BADGING_TXT=""
SO_COUNT="?"
NATIVE_LINES="?"
GMS_COUNT="?"
BADGING_ABIS="?"
ABI_LIST="?"
GRADLE_TASK_STR="（--skip-build）"

record() { # record <检查项> <PASS|FAIL|WARN> <详情>
    local name="$1" status="$2" detail="$3"
    case "$status" in
        PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
        FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
        WARN) WARN_COUNT=$((WARN_COUNT + 1)) ;;
    esac
    printf '| %s | **%s** | %s |\n' "$name" "$status" "$detail" >> "$RESULTS_FILE"
    printf '[%-4s] %s — %s\n' "$status" "$name" "$detail"
}

write_report() { # write_report <结论>
    local verdict="$1"
    {
        echo "# wjx-auto-filler 构建校验报告"
        echo
        echo "- 生成时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
        echo "- 版本：**$VERSION_NAME**（versionCode $VERSION_CODE）"
        echo "- APK：dist/$APK_NAME"
        echo "- sha256：$APK_SHA"
        echo "- 大小：$APK_SIZE 字节"
        echo "- 签名：$SIGN_MODE"
        if [ "$RUN_BUILD" = 1 ]; then
            echo "- 构建任务：$GRADLE_TASK_STR"
        else
            echo "- 构建任务：（--skip-build：本次只校验，未执行 Gradle 构建，复用已有产物）"
        fi
        echo "- 结论：**$verdict**（PASS $PASS_COUNT / FAIL $FAIL_COUNT / WARN $WARN_COUNT）"
        echo
        echo "## 校验项"
        echo
        echo "| 检查项 | 结果 | 详情 |"
        echo "| --- | --- | --- |"
        cat "$RESULTS_FILE"
        echo
        if [ -n "$APKSIG_OUT" ]; then
            echo "## apksig 验签输出"
            echo
            echo '~~~'
            printf '%s\n' "$APKSIG_OUT"
            echo '~~~'
            echo
        fi
        if [ -n "$BADGING_TXT" ] && [ -f "$BADGING_TXT" ]; then
            echo "## aapt2 dump badging（关键行）"
            echo
            echo '~~~'
            grep -E '^(package|sdkVersion|targetSdkVersion|uses-permission|native-code|application-label)' "$BADGING_TXT" | head -30
            echo '~~~'
            echo
        fi
        echo "## 通用包保证（换一台设备也能装）"
        echo
        echo "- APK 内 ABI 目录：$ABI_LIST"
        echo "- 必备 4 个 ABI：arm64-v8a / armeabi-v7a / x86 / x86_64（缺一不可，见校验表）"
        echo "- APK 内 .so 条目数：**$SO_COUNT**（全部位于 lib/<abi>/；这些是 androidx.camera 的 JNI 库，非自研 native 代码）"
        echo "- badging native-code：$BADGING_ABIS"
        echo "- releaseRuntimeClasspath 中 com.google.android.gms 行数：**$GMS_COUNT**（0 = 无 GMS）"
        echo "- minSdk 24 → Android 7.0 及以上设备均可安装"
        echo
        echo "## 需真机人工验证（JVM 单测覆盖不到）"
        echo
        echo "以下能力依赖 Android 运行时（Service / Notification / WebView / Camera / 系统安装器），"
        echo "单元测试无法覆盖，**发布前需在真机上人工验证**："
        echo
        echo "1. 前台常驻服务与通知：常驻通知可见、进程不被杀、到点自动进入验证页（高优先级全屏 Intent）。"
        echo "   1a. **响铃/震动必须是可选项**：开关默认开启且用户可关闭；关闭后仍必须有高优先级全屏通知（不得变成静默/普通通知）。"
        echo "2. WebView 人工验证兜底：触发兜底 → WebView 内完成验证 → 收割 captchaVerifyParam/sceneId → 自动继续提交。"
        echo "   （可机械审查的部分已由第 8 节静态审查覆盖：脚本固定化、无 JS 桥、Cookie 双向注入的纯逻辑有单测。）"
        echo "3. 相机实时扫码与相册选图解码（QrDecoder 的核心解码逻辑已由 QrDecodeTest 用真实截图夹具覆盖）。"
        echo "4. 应用内更新：下载 APK → FileProvider → 系统安装器 → 覆盖安装（需与正式签名包同签名）。"
        echo
        echo "## 复现命令"
        echo
        echo '~~~bash'
        echo "cd android && ./gradlew test lintRelease assembleRelease \\"
        echo "  -Pandroid.aapt2FromMavenOverride=\$PREFIX/bin/aapt2   # 仅 arm64 Termux 需要"
        echo "scripts/build-apk.sh --skip-build                       # 只重跑校验"
        echo '~~~'
    } > "$REPORT"
}

fail_hard() {
    record "$1" FAIL "$2"
    write_report "ABORTED"
    exit 1
}

# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------
command -v java  >/dev/null 2>&1 || { echo "缺少 java" >&2; exit 1; }
command -v javac >/dev/null 2>&1 || { echo "缺少 javac" >&2; exit 1; }

VERSION_NAME="$(sed -n 's/^versionName=//p' "$VERSION_FILE" | tr -d '\r' | head -1)"
VERSION_CODE="$(sed -n 's/^versionCode=//p' "$VERSION_FILE" | tr -d '\r' | head -1)"
[ -n "$VERSION_NAME" ] || fail_hard "读取 version.properties" "versionName 为空"
APK_NAME="wjx-autofill-$VERSION_NAME-universal.apk"
APK_PATH="$DIST_DIR/$APK_NAME"

# aapt2 覆盖（arm64 Termux 必需；x86-64 机器上 SDK 自带的可直接用，此时 AAPT2 环境变量可留空）
if [ -z "$AAPT2" ]; then
    AAPT2="$PREFIX/bin/aapt2"
fi
if [ ! -x "$AAPT2" ]; then
    AAPT2="$HOME/android-sdk/build-tools/35.0.0/aapt2"
fi
AAPT2_ARG=""
if [ -x "$AAPT2" ]; then
    AAPT2_ARG="-Pandroid.aapt2FromMavenOverride=$AAPT2"
else
    record "aapt2 覆盖参数" WARN "未找到可执行 aapt2，改用 SDK 自带（仅 x86-64 机器可用）"
fi

APKSIG_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.android.tools.build/apksig" \
    -name 'apksig-*.jar' 2>/dev/null | sort | tail -1)"
[ -n "$APKSIG_JAR" ] || record "apksig jar" WARN "Gradle 缓存里没有 apksig，跳过验签"

# 签名环境变量
if [ -n "$WJX_KEYSTORE_FILE" ] && [ -n "$WJX_KEYSTORE_PASSWORD" ] \
   && [ -n "$WJX_KEY_ALIAS" ] && [ -n "$WJX_KEY_PASSWORD" ]; then
    case "$WJX_KEYSTORE_FILE" in
        /*) : ;;
        *) WJX_KEYSTORE_FILE="$ANDROID_DIR/$WJX_KEYSTORE_FILE"; export WJX_KEYSTORE_FILE ;;
    esac
    if [ -f "$WJX_KEYSTORE_FILE" ]; then
        record "签名配置" PASS "release 签名：$(basename "$WJX_KEYSTORE_FILE") alias=$WJX_KEY_ALIAS"
    else
        SIGN_MODE="debug"
        record "签名配置" WARN "WJX_KEYSTORE_FILE 指向的文件不存在 → 回退 debug 签名（不可用于正式分发）"
    fi
else
    SIGN_MODE="debug"
    record "签名配置" WARN "缺少 WJX_KEYSTORE_FILE / WJX_KEYSTORE_PASSWORD / WJX_KEY_ALIAS / WJX_KEY_PASSWORD → 回退 debug 签名（不可用于正式分发）"
fi

# 串行化：本机内存只够一个 Gradle
if [ -d "$LOCK_DIR" ]; then
    echo "另一个构建正在进行（$LOCK_DIR 存在）。等它结束后删除该目录再重试。" >&2
    exit 3
fi
OTHER_GRADLE="$(ps -A 2>/dev/null | grep -c 'GradleWrapperMain' || true)"
if [ "$OTHER_GRADLE" -gt 0 ]; then
    echo "检测到其它 Gradle 构建进程（GradleWrapperMain x $OTHER_GRADLE）。本机内存有限，拒绝并发构建。" >&2
    exit 3
fi
mkdir -p "$LOCK_DIR"
trap 'rmdir "$LOCK_DIR" 2>/dev/null' EXIT

# ---------------------------------------------------------------------------
# 构建
# ---------------------------------------------------------------------------
GRADLE_TASK_STR=""
if [ "$RUN_TESTS" = 1 ]; then GRADLE_TASK_STR="$GRADLE_TASK_STR test"; fi
if [ "$RUN_LINT" = 1 ]; then GRADLE_TASK_STR="$GRADLE_TASK_STR lintRelease"; fi
GRADLE_TASK_STR="$GRADLE_TASK_STR assembleRelease"
if [ "$RUN_CLEAN" = 1 ]; then GRADLE_TASK_STR="clean $GRADLE_TASK_STR"; fi
GRADLE_TASK_STR="$(printf '%s' "$GRADLE_TASK_STR" | sed 's/^ *//')"

run_gradle() { # run_gradle <日志文件> <gradle 参数...>
    local log="$1"; shift
    ( cd "$ANDROID_DIR" && ./gradlew "$@" $AAPT2_ARG ) >"$log" 2>&1
}

INTEGRATION_FLAG="$ROOT_DIR/testdata/run-integration.flag"
if [ "$RUN_INTEGRATION" = 1 ]; then
    : > "$INTEGRATION_FLAG"
    record "集成测试开关" PASS "已放 $INTEGRATION_FLAG（会真发一次网络请求）"
    # 集成测试用 assumeTrue 跳过/开启，Gradle 看不到标志文件 → 删掉测试结果强制重跑
    rm -rf "$ANDROID_DIR/app/build/test-results"
fi

# Gradle daemon 会复用它**启动时**的环境变量：如果 daemon 是别的 shell 起的，
# WJX_KEYSTORE_* 不会生效，release 构建会静默回退 debug 签名。先停掉 daemon。
if [ "$SIGN_MODE" = "release" ] && [ "$RUN_BUILD" = 1 ]; then
    ( cd "$ANDROID_DIR" && ./gradlew --stop ) > "$LOG_DIR/gradle-stop.log" 2>&1
    record "Gradle daemon 环境刷新" PASS "已 ./gradlew --stop，确保新 daemon 看到 WJX_KEYSTORE_*"
fi

if [ "$RUN_BUILD" = 1 ]; then
    echo "==> 构建：./gradlew $GRADLE_TASK_STR"
    BUILD_LOG="$LOG_DIR/gradle-build.log"
    if run_gradle "$BUILD_LOG" $GRADLE_TASK_STR; then
        record "Gradle 构建（$GRADLE_TASK_STR）" PASS "日志：dist/logs/gradle-build.log"
    else
        record "Gradle 构建（$GRADLE_TASK_STR）" FAIL "见 dist/logs/gradle-build.log 末尾：$(tail -3 "$BUILD_LOG" | tr '\n' ' ')"
    fi
fi

if [ "$RUN_INTEGRATION" = 1 ]; then
    rm -f "$INTEGRATION_FLAG"
fi

# ---------------------------------------------------------------------------
# 产物落地
# ---------------------------------------------------------------------------
if [ ! -f "$RAW_APK" ]; then
    fail_hard "release APK 产物" "不存在：$RAW_APK"
fi

cp -f "$RAW_APK" "$APK_PATH"
( cd "$DIST_DIR" && sha256sum "$APK_NAME" > "$APK_NAME.sha256" )
APK_SHA="$(awk '{print $1}' "$APK_PATH.sha256")"
APK_SHA_SHORT="$(printf '%s' "$APK_SHA" | cut -c1-16)"
APK_SIZE="$(wc -c < "$APK_PATH" | tr -d ' ')"
record "产物复制 + sha256" PASS "$APK_NAME（$APK_SIZE 字节，sha256=$APK_SHA_SHORT…）"

WORK_DIR="$(mktemp -d)"
trap 'rmdir "$LOCK_DIR" 2>/dev/null; rm -rf "$WORK_DIR" 2>/dev/null' EXIT

# ---------------------------------------------------------------------------
# 1) apksig 验签（纯 JVM，arm64 可跑）
# ---------------------------------------------------------------------------
if [ -n "$APKSIG_JAR" ]; then
    cat > "$WORK_DIR/VerifyApkSignature.java" <<'JAVA'
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;

public class VerifyApkSignature {
    public static void main(String[] args) throws Exception {
        ApkVerifier.Result r = new ApkVerifier.Builder(new File(args[0])).build().verify();
        System.out.println("verified=" + r.isVerified()
                + " v1=" + r.isVerifiedUsingV1Scheme()
                + " v2=" + r.isVerifiedUsingV2Scheme()
                + " v3=" + r.isVerifiedUsingV3Scheme());
        List<X509Certificate> certs = r.getSignerCertificates();
        for (X509Certificate c : certs) {
            System.out.println("signer=" + c.getSubjectX500Principal().getName()
                    + " sha256=" + hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded())));
        }
        System.out.println("signers=" + certs.size());
        for (ApkVerifier.IssueWithParams e : r.getErrors())   System.out.println("ERROR " + e);
        for (ApkVerifier.IssueWithParams w : r.getWarnings()) System.out.println("WARN " + w);
        if (!r.isVerified()) System.exit(1);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
JAVA
    if javac -cp "$APKSIG_JAR" -d "$WORK_DIR" "$WORK_DIR/VerifyApkSignature.java" > "$WORK_DIR/javac.log" 2>&1; then
        if APKSIG_OUT="$(java -cp "$APKSIG_JAR:$WORK_DIR" VerifyApkSignature "$APK_PATH" 2>&1)"; then
            SIGNER_LINE="$(printf '%s\n' "$APKSIG_OUT" | sed -n 's/^signer=//p' | head -1)"
            SIGNER_CN="$(printf '%s' "$SIGNER_LINE" | sed 's/ sha256=.*//')"
            record "apksig 验签" PASS "$(printf '%s\n' "$APKSIG_OUT" | head -1)；signer=$SIGNER_CN"
        else
            record "apksig 验签" FAIL "$(printf '%s\n' "$APKSIG_OUT" | tr '\n' ' ' | head -c 300)"
        fi
    else
        record "apksig 验签" FAIL "javac 编译验签程序失败：$(tail -2 "$WORK_DIR/javac.log" | tr '\n' ' ')"
    fi
fi

# ---------------------------------------------------------------------------
# 2) aapt2 dump badging
# ---------------------------------------------------------------------------
# 签名一致性：配置了 release keystore 却拿到 Debug 证书 = Gradle daemon 复用了旧环境变量
if [ "$SIGN_MODE" = "release" ] && [ -n "$APKSIG_OUT" ]; then
    SIGNER_SUBJECT="$(printf '%s\n' "$APKSIG_OUT" | grep -m1 '^signer=' | cut -d= -f2- | cut -d' ' -f1)"
    if printf '%s\n' "$APKSIG_OUT" | grep -q 'CN=Android Debug'; then
        record "签名与配置一致（release）" FAIL "配置了 release keystore，但 APK 用 Android Debug 证书签名；通常是 Gradle daemon 复用了启动时的旧环境变量 → 先 ./gradlew --stop 再构建（脚本已自动处理）"
    else
        record "签名与配置一致（release）" PASS "$SIGNER_SUBJECT"
    fi
elif [ "$SIGN_MODE" = "debug" ]; then
    record "签名与配置一致（debug 回退）" WARN "未配置 WJX_KEYSTORE_*，APK 为 debug 签名，不能用于正式分发或覆盖安装"
fi

BADGING_TXT="$WORK_DIR/badging.txt"
if [ -x "$AAPT2" ] && "$AAPT2" dump badging "$APK_PATH" > "$BADGING_TXT" 2>&1; then
    PKG="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" "$BADGING_TXT" | head -1)"
    VER="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" "$BADGING_TXT" | head -1)"
    VCODE="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" "$BADGING_TXT" | head -1)"
    MINSDK="$(grep -m1 '^minSdkVersion:' "$BADGING_TXT" | tr -d "'" | cut -d: -f2)"
    if [ -z "$MINSDK" ]; then
        # 老版 aapt2 打印 sdkVersion:，新版打印 minSdkVersion:，两者都接受
        MINSDK="$(grep -m1 '^sdkVersion:' "$BADGING_TXT" | tr -d "'" | cut -d: -f2)"
    fi
    TARGETSDK="$(sed -n "s/^targetSdkVersion:'\([^']*\)'/\1/p" "$BADGING_TXT" | head -1)"
    NATIVE_LINES="$(grep -c '^native-code' "$BADGING_TXT" || true)"
    BADGING_ABIS="$(sed -n 's/^native-code: //p' "$BADGING_TXT" | tr -d "'" | head -1)"

    if [ "$PKG" = "com.wjx.autofill" ]; then
        record "包名 package=com.wjx.autofill" PASS "$PKG"
    else
        record "包名 package=com.wjx.autofill" FAIL "实际：$PKG"
    fi
    if [ "$VER" = "$VERSION_NAME" ] && [ "$VCODE" = "$VERSION_CODE" ]; then
        record "versionName/versionCode 与 version.properties 一致" PASS "$VER ($VCODE)"
    else
        record "versionName/versionCode 与 version.properties 一致" FAIL "APK=$VER($VCODE) 文件=$VERSION_NAME($VERSION_CODE)"
    fi
    if [ "$MINSDK" = "24" ]; then
        record "minSdk=24（Android 7.0+）" PASS "sdkVersion:'$MINSDK'"
    else
        record "minSdk=24（Android 7.0+）" FAIL "实际 sdkVersion:'$MINSDK'"
    fi
    if [ "$TARGETSDK" = "35" ]; then
        record "targetSdk=35" PASS "targetSdkVersion:'$TARGETSDK'"
    else
        record "targetSdk=35" FAIL "实际 targetSdkVersion:'$TARGETSDK'"
    fi
    MISSING_BADGING_ABI=""
    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
        case " $BADGING_ABIS " in
            *" $abi "*) : ;;
            *) MISSING_BADGING_ABI="$MISSING_BADGING_ABI $abi" ;;
        esac
    done
    if [ -z "$MISSING_BADGING_ABI" ]; then
        record "badging native-code 覆盖 4 个 ABI（通用包）" PASS "native-code: $BADGING_ABIS"
    else
        record "badging native-code 覆盖 4 个 ABI（通用包）" FAIL "缺：$MISSING_BADGING_ABI（实际 native-code: $BADGING_ABIS）"
    fi
else
    record "aapt2 dump badging" FAIL "aapt2 执行失败（$AAPT2）"
fi

# ---------------------------------------------------------------------------
# 3) APK 内不得含 .so
# ---------------------------------------------------------------------------
unzip -l "$APK_PATH" > "$WORK_DIR/unzip-list.txt" 2>&1
grep -oE 'lib/[^/]+/[^ ]+' "$WORK_DIR/unzip-list.txt" | sort -u > "$WORK_DIR/libs.txt" || true
SO_LIST="$WORK_DIR/libs.txt"
SO_COUNT="$(grep -c '\.so$' "$SO_LIST" || true)"
UNZIP_LINES="$(wc -l < "$WORK_DIR/unzip-list.txt" | tr -d ' ')"
ABI_LIST="$(sed 's|^lib/||; s|/.*||' "$SO_LIST" | sort -u | tr '\n' ' ' | sed 's/ *$//')"

# 6a) 4 个 ABI 目录必须齐全（A7 口径：不设 abiFilters + 四 ABI 全覆盖 = 通用包）
MISSING_ABI=""
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    case " $ABI_LIST " in
        *" $abi "*) : ;;
        *) MISSING_ABI="$MISSING_ABI $abi" ;;
    esac
done
if [ -z "$MISSING_ABI" ]; then
    record "通用包：4 个 ABI 目录齐全" PASS "ABI：$ABI_LIST（共 $SO_COUNT 个 .so 条目）"
else
    record "通用包：4 个 ABI 目录齐全" FAIL "缺 ABI：$MISSING_ABI（实际：$ABI_LIST）"
fi

# 6b) 每个 .so 必须在 4 个 ABI 下各有一份（防止某个库只打了一半）
PARTIAL_LIBS=""
for base in $(grep -oE '[^/]+\.so$' "$SO_LIST" | sort -u); do
    cnt="$(grep -c "/$base$" "$SO_LIST" || true)"
    [ "$cnt" = "4" ] || PARTIAL_LIBS="$PARTIAL_LIBS $base($cnt/4)"
done
if [ -z "$PARTIAL_LIBS" ]; then
    record "每个 .so 覆盖 4 个 ABI" PASS "所有 native 库在 4 个 ABI 下各一份"
else
    record "每个 .so 覆盖 4 个 ABI" FAIL "覆盖不全：$PARTIAL_LIBS"
fi

# 6c) 工程内不得有自研 native 代码（.so 只应来自依赖 AAR）
NATIVE_SRC="$(find "$ANDROID_DIR/app/src/main" \( -name '*.c' -o -name '*.cpp' -o -name '*.cc' -o -name 'CMakeLists.txt' -o -name '*.mk' \) 2>/dev/null | wc -l | tr -d ' ')"
NATIVE_CFG="$(grep -c 'externalNativeBuild' "$ANDROID_DIR/app/build.gradle.kts" || true)"
if [ "$NATIVE_SRC" = "0" ] && [ "$NATIVE_CFG" = "0" ]; then
    record "无自研 native 代码（A7）" PASS "src/main 无 .c/.cpp/CMakeLists.txt，build.gradle.kts 无 externalNativeBuild"
else
    record "无自研 native 代码（A7）" FAIL "native 源文件 $NATIVE_SRC 个，externalNativeBuild 配置 $NATIVE_CFG 处"
fi

# ---------------------------------------------------------------------------
# 4) 依赖树无 GMS
# ---------------------------------------------------------------------------
DEPS_LOG="$LOG_DIR/gradle-dependencies.log"
if run_gradle "$DEPS_LOG" :app:dependencies --configuration releaseRuntimeClasspath; then
    GMS_COUNT="$(grep -c 'com\.google\.android\.gms' "$DEPS_LOG" || true)"
    if [ "$GMS_COUNT" = "0" ]; then
        record "依赖树无 com.google.android.gms" PASS "releaseRuntimeClasspath 无 GMS"
    else
        record "依赖树无 com.google.android.gms" FAIL "$GMS_COUNT 行命中：$(grep -m1 'com\.google\.android\.gms' "$DEPS_LOG")"
    fi
else
    GMS_COUNT="?"
    record "依赖树无 com.google.android.gms" FAIL "gradle :app:dependencies 执行失败"
fi

# ---------------------------------------------------------------------------
# 5) 单元测试结果
# ---------------------------------------------------------------------------
TEST_XML_DIR="$ANDROID_DIR/app/build/test-results"
sum_attr() { # sum_attr <tests|failures|errors>
    grep -ho "$1=\"[0-9]*\"" "$TEST_XML_DIR"/*/TEST-*.xml 2>/dev/null \
        | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}'
}
if ls "$TEST_XML_DIR"/*/TEST-*.xml >/dev/null 2>&1; then
    TEST_TOTAL="$(sum_attr tests)"
    TEST_FAIL="$(sum_attr failures)"
    TEST_ERR="$(sum_attr errors)"
    if [ "$TEST_FAIL" = "0" ] && [ "$TEST_ERR" = "0" ] && [ "$TEST_TOTAL" -gt 0 ]; then
        record "单元测试" PASS "共 $TEST_TOTAL 个用例，0 失败 0 错误"
    else
        record "单元测试" FAIL "共 $TEST_TOTAL 个用例，失败 $TEST_FAIL，错误 $TEST_ERR"
    fi
else
    record "单元测试" WARN "没有测试结果（可能用了 --skip-tests）"
fi

# ---------------------------------------------------------------------------
# 6) lintRelease 结果
# ---------------------------------------------------------------------------
LINT_XML="$ANDROID_DIR/app/build/reports/lint-results-release.xml"
if [ -f "$LINT_XML" ]; then
    LINT_ERRORS="$(grep -c 'severity="Error"' "$LINT_XML" || true)"
    if [ "$LINT_ERRORS" = "0" ]; then
        record "lintRelease 无 Error（含 NewApi）" PASS "app/build/reports/lint-results-release.xml"
    else
        record "lintRelease 无 Error（含 NewApi）" FAIL "$LINT_ERRORS 条 Error"
    fi
else
    record "lintRelease 无 Error（含 NewApi）" WARN "没有 lint 报告（可能用了 --quick/--skip-lint）"
fi

# ---------------------------------------------------------------------------
# 7) 二维码夹具解码（A7：JPEG → zxing → 问卷链接）
#    纯 JVM 跑（javac/java 有 java.desktop），Gradle 单测侧用同一张图转出的亮度矩阵。
# ---------------------------------------------------------------------------
QR_OUT="$(bash "$ROOT_DIR/scripts/gen-qr-fixture.sh" --check 2>&1)"
if printf '%s\n' "$QR_OUT" | grep -q '^OK$'; then
    record "二维码夹具解码（A7）" PASS "$(printf '%s\n' "$QR_OUT" | sed -n 's/^decoded=//p' | head -1)（$(printf '%s\n' "$QR_OUT" | sed -n 's/^image=//p' | head -1)）"
else
    record "二维码夹具解码（A7）" FAIL "$(printf '%s\n' "$QR_OUT" | tr '\n' ' ' | head -c 300)"
fi

# ---------------------------------------------------------------------------
# 8) 静态审查：验证码兜底页（architect §13.10「可机械审查判据」）
#    - ui/CaptchaActivity 只允许 1 个 evaluateJavascript 调用点，且脚本只能是 2 个固定常量
#      （JS_RAISE_CAPTCHA / JS_HARVEST），不得动态拼接（$ 或 +）
#    - 不得有真实的 @JavascriptInterface 注解（不允许页面回调把数据传回；注释里的提及不算）
# ---------------------------------------------------------------------------
CAPTCHA_ACTIVITY="$ANDROID_DIR/app/src/main/java/com/wjx/autofill/ui/CaptchaActivity.kt"
if [ -f "$CAPTCHA_ACTIVITY" ]; then
    EVAL_SITES="$(grep -c 'evaluateJavascript(' "$CAPTCHA_ACTIVITY" || true)"
    EVAL_LINE="$(grep 'evaluateJavascript(' "$CAPTCHA_ACTIVITY" | head -1)"
    EVAL_DYNAMIC=0
    case "$EVAL_LINE" in
        *'$'*|*+*) EVAL_DYNAMIC=1 ;;
    esac
    EVAL_CONST_CALLS="$(grep -c 'evaluate(JS_' "$CAPTCHA_ACTIVITY" || true)"
    JS_BRIDGE="$(grep '@JavascriptInterface' "$CAPTCHA_ACTIVITY" | grep -v '^[[:space:]]*[*]' | grep -vc '^[[:space:]]*//' || true)"
    if [ "$EVAL_SITES" = "1" ] && [ "$EVAL_DYNAMIC" = "0" ] && [ "$EVAL_CONST_CALLS" = "2" ]; then
        record "CaptchaActivity 脚本注入固定化" PASS "evaluateJavascript 调用点 $EVAL_SITES 个、动态拼接 $EVAL_DYNAMIC 处、固定常量脚本 $EVAL_CONST_CALLS 个（JS_RAISE_CAPTCHA/JS_HARVEST）"
    else
        record "CaptchaActivity 脚本注入固定化" FAIL "调用点 $EVAL_SITES（期望 1）、动态拼接 $EVAL_DYNAMIC（期望 0）、固定常量脚本 $EVAL_CONST_CALLS（期望 2）"
    fi
    if [ "$JS_BRIDGE" = "0" ]; then
        record "CaptchaActivity 无 @JavascriptInterface 回传" PASS "0 处注解"
    else
        record "CaptchaActivity 无 @JavascriptInterface 回传" FAIL "$JS_BRIDGE 处 @JavascriptInterface，需人工确认未回传答案数据"
    fi
else
    record "CaptchaActivity 静态审查" WARN "文件不存在（$CAPTCHA_ACTIVITY），跳过"
fi

# ---------------------------------------------------------------------------
# 报告
# ---------------------------------------------------------------------------
if [ "$FAIL_COUNT" = 0 ]; then
    write_report "ALL PASS"
else
    write_report "FAILED（$FAIL_COUNT 项）"
fi

echo
echo "=============================================="
echo " APK   : dist/$APK_NAME"
echo " sha256: $APK_SHA"
echo " 报告  : dist/VERIFY-REPORT.md"
echo " 结果  : PASS=$PASS_COUNT FAIL=$FAIL_COUNT WARN=$WARN_COUNT"
echo "=============================================="

[ "$FAIL_COUNT" = 0 ] || exit 1
exit 0
