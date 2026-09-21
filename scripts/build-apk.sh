#!/usr/bin/env bash
# =============================================================================
# scripts/build-apk.sh — wjx-auto-filler 一键构建 + 完整校验 + 报告
#
# 做四件事：
#   1. 构建 release APK（签名从 WJX_KEYSTORE_* 环境变量读；缺失则警告并回退 debug 签名）
#   2. 复制为 dist/wjx-autofill-<versionName>-universal.apk 并生成 .sha256
#   3. 校验：apksig 验签 / aapt2 badging（包名·版本·minSdk 24·targetSdk 35·无 native-code）
#            / APK 内无 .so（跨设备通用包保证）/ 依赖树无 com.google.android.gms
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
        echo "- 构建任务：$GRADLE_TASK_STR"
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
        echo "- APK 内 .so 数量：**$SO_COUNT**（0 = 不绑定任何 ABI）"
        echo "- badging native-code 行数：**$NATIVE_LINES**（0 = 通用包）"
        echo "- releaseRuntimeClasspath 中 com.google.android.gms 行数：**$GMS_COUNT**（0 = 无 GMS）"
        echo "- minSdk 24 → Android 7.0 及以上设备均可安装"
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

if [ "$RUN_BUILD" = 1 ]; then
    echo "==> 构建：./gradlew $GRADLE_TASK_STR"
    BUILD_LOG="$LOG_DIR/gradle-build.log"
    if run_gradle "$BUILD_LOG" $GRADLE_TASK_STR; then
        record "Gradle 构建（$GRADLE_TASK_STR）" PASS "日志：dist/logs/gradle-build.log"
    else
        record "Gradle 构建（$GRADLE_TASK_STR）" FAIL "见 dist/logs/gradle-build.log 末尾：$(tail -3 "$BUILD_LOG" | tr '\n' ' ')"
    fi
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
BADGING_TXT="$WORK_DIR/badging.txt"
if [ -x "$AAPT2" ] && "$AAPT2" dump badging "$APK_PATH" > "$BADGING_TXT" 2>&1; then
    PKG="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" "$BADGING_TXT" | head -1)"
    VER="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" "$BADGING_TXT" | head -1)"
    VCODE="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" "$BADGING_TXT" | head -1)"
    MINSDK="$(sed -n "s/^sdkVersion:'\([^']*\)'/\1/p" "$BADGING_TXT" | head -1)"
    TARGETSDK="$(sed -n "s/^targetSdkVersion:'\([^']*\)'/\1/p" "$BADGING_TXT" | head -1)"
    NATIVE_LINES="$(grep -c '^native-code' "$BADGING_TXT" || true)"

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
    if [ "$NATIVE_LINES" = "0" ]; then
        record "badging 无 native-code 行" PASS "通用包（不绑定 ABI）"
    else
        record "badging 无 native-code 行" FAIL "出现 native-code：$(grep '^native-code' "$BADGING_TXT" | head -1)"
    fi
else
    record "aapt2 dump badging" FAIL "aapt2 执行失败（$AAPT2）"
fi

# ---------------------------------------------------------------------------
# 3) APK 内不得含 .so
# ---------------------------------------------------------------------------
SO_LIST="$WORK_DIR/so.txt"
unzip -l "$APK_PATH" > "$WORK_DIR/unzip-list.txt" 2>&1
grep -E '\.so$' "$WORK_DIR/unzip-list.txt" > "$SO_LIST" || true
SO_COUNT="$(wc -l < "$SO_LIST" | tr -d ' ')"
UNZIP_LINES="$(wc -l < "$WORK_DIR/unzip-list.txt" | tr -d ' ')"
if [ "$SO_COUNT" = "0" ]; then
    record "APK 内无 .so（跨设备通用包）" PASS "unzip -l 共 $UNZIP_LINES 行，0 个 .so"
else
    record "APK 内无 .so（跨设备通用包）" FAIL "$SO_COUNT 个：$(head -3 "$SO_LIST" | tr '\n' ' ')"
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
