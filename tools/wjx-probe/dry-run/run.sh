#!/data/data/com.termux/files/usr/bin/bash
# T10 dry-run：用离线 kotlinc 编译「真实引擎源码 + DryRun.kt」并运行。
# 不占用 Gradle 槽位（Gradle 留给 qa-build 跑全流水线）。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
ENGINE="$ROOT/android/app/src/main/java/com/wjx/autofill/wjx"
G="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
TMPBASE="${TMPDIR:-$PREFIX/tmp}"
OUT="$TMPBASE/wjx-dry-run"

pick() { # pick <dir-under-cache> <glob>
  find "$G/$1" -name "$2" 2>/dev/null | sort | tail -1
}
CC="$(pick org.jetbrains.kotlin/kotlin-compiler-embeddable 'kotlin-compiler-embeddable-2.1.20.jar')"
[ -z "$CC" ] && CC="$(pick org.jetbrains.kotlin/kotlin-compiler-embeddable 'kotlin-compiler-embeddable-*.jar')"
STD="$(pick org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2.1.20.jar')"
[ -z "$STD" ] && STD="$(pick org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-*.jar')"
COR="$(pick org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-1.9.0.jar')"
[ -z "$COR" ] && COR="$(pick org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-*.jar')"
TRV="$(pick org.jetbrains.intellij.deps/trove4j 'trove4j-*.jar')"
ANN="$(pick org.jetbrains/annotations 'annotations-*.jar')"

for v in CC STD COR TRV ANN; do
  eval "val=\$$v"
  if [ -z "$val" ]; then echo "缺少依赖 jar：$v（在 $G 下找不到）" >&2; exit 2; fi
done

rm -rf "$OUT"; mkdir -p "$OUT"
echo "[dry-run] 编译引擎 + CLI（离线 kotlinc，不占 Gradle）..." >&2
java -Xmx900m -cp "$CC:$STD:$COR:$TRV:$ANN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$STD:$COR:$ANN" -jvm-target 17 -nowarn -d "$OUT" \
  "$ENGINE/SurveyModel.kt" "$ENGINE/WjxErrors.kt" "$ENGINE/WjxSubmitCodec.kt" \
  "$ENGINE/WjxSurveyClient.kt" "$ENGINE/WjxSubmitter.kt" "$HERE/DryRun.kt" > "$OUT/compile.log" 2>&1 \
  || { grep -v 'jansi\|UnsatisfiedLinkError' "$OUT/compile.log" | head -40 >&2; exit 3; }

exec java -cp "$OUT:$STD:$COR:$ANN" tools.DryRunKt "$@"
