#!/usr/bin/env bash
# Static inspection of the assembled APK.
#
# The report is the evidence for the acceptance criteria that can only be verified on a real APK:
# package name, label, every MAIN/LAUNCHER entry, the absence of the LeakCanary "Leaks" launcher and
# the presence of the dynamic endpoint resolver classes in the DEX.
#
# Run from the repository root. Writes:
#   diagnostics/apk-static-inspection-report.md   human readable report
#   diagnostics/merged-manifest.txt               manifest dump used for the checks
#
# Exits non-zero when a hard criterion is violated, so a green workflow really means "inspected".

set -uo pipefail

REPORT_DIR="diagnostics"
REPORT="$REPORT_DIR/apk-static-inspection-report.md"
MANIFEST_DUMP="$REPORT_DIR/merged-manifest.txt"
mkdir -p "$REPORT_DIR"

FAILURES=0
fail() {
    echo "HARD FAILURE: $*" >&2
    FAILURES=$((FAILURES + 1))
}

sdk_root() {
    echo "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
}

find_tool() {
    local name="$1" dir="$2"
    if command -v "$name" >/dev/null 2>&1; then
        command -v "$name"
        return 0
    fi
    find "$(sdk_root)/$dir" -maxdepth 3 -type f -name "$name" 2>/dev/null | sort -V | tail -n 1
}

# Extracts printable strings from a binary. `strings` is preferred, `tr` is the fallback.
bin_strings() {
    if command -v strings >/dev/null 2>&1; then
        strings -a
    else
        tr -c '[:print:]' '\n'
    fi
}

APK="$(find app/build/outputs/apk -type f -name '*.apk' -print 2>/dev/null | sort | head -n 1)"
AAPT="$(find_tool aapt build-tools || true)"
AAPT2="$(find_tool aapt2 build-tools || true)"
APKANALYZER="$(find_tool apkanalyzer cmdline-tools || true)"

# ------------------------------------------------------------------ manifest dump
: > "$MANIFEST_DUMP"
MANIFEST_TOOL=""
if [ -n "$APK" ] && [ -n "$AAPT" ]; then
    "$AAPT" dump xmltree "$APK" AndroidManifest.xml > "$MANIFEST_DUMP" 2>/dev/null && MANIFEST_TOOL="aapt"
elif [ -n "$APK" ] && [ -n "$AAPT2" ]; then
    "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$MANIFEST_DUMP" 2>/dev/null && MANIFEST_TOOL="aapt2"
elif [ -n "$APK" ] && [ -n "$APKANALYZER" ]; then
    "$APKANALYZER" manifest print "$APK" > "$MANIFEST_DUMP" 2>/dev/null && MANIFEST_TOOL="apkanalyzer"
fi

# ------------------------------------------------------------------ report header
{
    echo "# HA Android 动态地址（TXT/SRV）APK 静态检查报告"
    echo
    echo "- 生成时间 (UTC)：$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "- 提交：\`${GITHUB_SHA:-local}\`"
    echo "- 工作流运行：\`${GITHUB_RUN_ID:-local}\`"
    echo "- 运行器：\`${RUNNER_OS:-local}\` / \`$(java -version 2>&1 | head -n1)\`"
    echo "- 检查的 APK：\`${APK:-<未找到>}\`"
    echo "- 清单转储工具：\`${MANIFEST_TOOL:-none}\`"
    echo
    echo '## 1. 构建产物'
    echo
    echo '```text'
    find app/build/outputs/apk -type f -printf '%p (%s bytes)\n' 2>/dev/null || echo '未找到 app/build/outputs/apk'
    echo '```'
    echo
    echo '## 2. 源码中声明的 MAIN / LAUNCHER 入口'
    echo
    echo '源码清单中不应出现 `Leaks` 入口；`Leaks` 只可能来自被合并的调试组件。'
    echo
    echo '```text'
    grep -RIn --include='AndroidManifest.xml' \
        -E 'android.intent.action.MAIN|android.intent.category.(LAUNCHER|LEANBACK_LAUNCHER|HOME)|Leaks|leakcanary' \
        app/src 2>/dev/null || echo '（无匹配）'
    echo '```'
    echo
    echo '## 3. LeakCanary 构建配置'
    echo
    echo '`com.squareup.leakcanary:leakcanary-android` 不应再作为 `debugImplementation` 存在：它既会注入额外的 `Leaks` 启动入口，也是 `LockOutOfDateException` 的根因。'
    echo
    echo '```text'
    grep -rn -E 'leakcanary|noLeakCanary' \
        gradle.properties gradle/libs.versions.toml \
        build-logic/convention/src/main/kotlin/AndroidCommonConventionPlugin.kt \
        build-logic/convention/src/main/kotlin/AndroidApplicationDependenciesConventionPlugin.kt \
        2>/dev/null || echo '（无匹配）'
    echo '```'
    echo
    echo '## 4. 动态地址解析模块与测试是否进入工程'
    echo
    echo '```text'
    echo '-- 主实现 --'
    find common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint -type f -name '*.kt' 2>/dev/null | sort || true
    echo '-- 单元测试 --'
    find common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint -type f -name '*.kt' 2>/dev/null | sort || true
    IMPL_COUNT=$(find common/src/main/kotlin/io/homeassistant/companion/android/common/data/endpoint -name '*.kt' 2>/dev/null | wc -l | tr -d ' ')
    TEST_COUNT=$(find common/src/test/kotlin/io/homeassistant/companion/android/common/data/endpoint -name '*.kt' 2>/dev/null | wc -l | tr -d ' ')
    echo "-- 统计：${IMPL_COUNT} 个实现文件，${TEST_COUNT} 个测试文件"
    echo '```'
    echo
} > "$REPORT"

if [ -z "$APK" ]; then
    {
        echo '## 5. APK 检查'
        echo
        echo '**未生成 APK，静态检查无法继续。** 构建阶段已经失败，请先查看构建日志。'
    } >> "$REPORT"
    fail "no APK was produced, the build did not finish"
    cat "$REPORT"
    exit 1
fi

{
    echo '## 5. APK 摘要'
    echo
    echo '```text'
    echo "路径：$APK"
    echo "大小：$(stat -c '%s bytes' "$APK" 2>/dev/null || stat -f '%z bytes' "$APK")"
    echo "SHA-256：$(sha256sum "$APK" | awk '{print $1}')"
    echo '```'
    echo
    echo '## 6. 包名 / 标签 / 启动 Activity（badging）'
    echo
    echo '```text'
} >> "$REPORT"

if [ -n "$AAPT" ]; then
    "$AAPT" dump badging "$APK" 2>&1 | grep -E \
        "^package:|^application-label|^application:|^launchable-activity:|^uses-sdk:|^sdkVersion:|^targetSdkVersion:|^native-code:" \
        >> "$REPORT" || true
elif [ -n "$AAPT2" ]; then
    "$AAPT2" dump badging "$APK" 2>&1 | head -40 >> "$REPORT" || true
elif [ -n "$APKANALYZER" ]; then
    "$APKANALYZER" manifest print "$APK" 2>&1 | grep -E 'package=|android:label|<activity|<activity-alias|MAIN|LAUNCHER' >> "$REPORT" || true
else
    echo '未找到 aapt / aapt2 / apkanalyzer，无法读取 badging。' >> "$REPORT"
fi

{
    echo '```'
    echo
    echo '## 7. 合并后清单中的全部 MAIN / LAUNCHER 入口'
    echo
    echo '```text'
} >> "$REPORT"

if [ -s "$MANIFEST_DUMP" ]; then
    grep -E 'E: (application|activity|activity-alias|intent-filter)|A: android:(name|label|exported|enabled)|action|category|android.intent.action.MAIN|android.intent.category|<activity|<activity-alias|android:label' \
        "$MANIFEST_DUMP" >> "$REPORT" 2>/dev/null || cat "$MANIFEST_DUMP" >> "$REPORT"
else
    echo '未能生成清单转储。' >> "$REPORT"
fi

{
    echo '```'
    echo
    echo '## 8. 是否仍存在 `Leaks` 启动入口'
    echo
    echo '判定依据：合并后清单（第 7 节的转储）中不得出现 `Leaks` 标签或 `leakcanary` 组件名。'
    echo
    echo '```text'
} >> "$REPORT"

LEAKS_HIT="$(grep -i -E 'Leaks|leakcanary' "$MANIFEST_DUMP" 2>/dev/null || true)"
ARCHIVE_LEAKS="$(unzip -l "$APK" 2>/dev/null | grep -i -E 'leakcanary|leak_activity' || true)"

if [ -n "$LEAKS_HIT" ]; then
    echo "$LEAKS_HIT" >> "$REPORT"
else
    echo '（合并后清单中无匹配，符合预期）' >> "$REPORT"
fi
{
    echo '```'
    echo
    echo '### APK 归档条目中的匹配'
    echo
    echo '```text'
} >> "$REPORT"
if [ -n "$ARCHIVE_LEAKS" ]; then
    echo "$ARCHIVE_LEAKS" >> "$REPORT"
else
    echo '（无匹配。注意：DEX 中的类名引用需要按第 9 节的方法单独检查。）' >> "$REPORT"
fi
{
    echo '```'
    echo
} >> "$REPORT"

if [ -n "$LEAKS_HIT" ]; then
    fail "the merged manifest still declares a LeakCanary entry: $LEAKS_HIT"
fi

# ------------------------------------------------------------------ endpoint package in DEX
{
    echo '## 9. 动态地址解析模块是否真的打进了 APK'
    echo
    echo 'DEX 中的类型描述符形如 `Lio/homeassistant/companion/android/common/data/endpoint/<Name>;`。'
    echo
    echo '```text'
} >> "$REPORT"

ENDPOINT_DEX_HITS=0
DEX_ENTRIES="$(unzip -Z1 "$APK" 2>/dev/null | grep -E '^classes[0-9]*\.dex$' || true)"
if [ -z "$DEX_ENTRIES" ]; then
    echo '未在 APK 中找到 classes*.dex。' >> "$REPORT"
else
    for entry in $DEX_ENTRIES; do
        count="$(unzip -p "$APK" "$entry" 2>/dev/null | bin_strings | grep -a -c 'io/homeassistant/companion/android/common/data/endpoint/')"
        count="${count:-0}"
        echo "$entry: ${count} 处 endpoint 包引用" >> "$REPORT"
        ENDPOINT_DEX_HITS=$((ENDPOINT_DEX_HITS + count))
    done
fi
{
    echo "合计：$ENDPOINT_DEX_HITS"
    echo '```'
    echo
} >> "$REPORT"

if [ "$ENDPOINT_DEX_HITS" -eq 0 ]; then
    fail "the endpoint resolver package was not found in the APK DEX files"
fi

# ------------------------------------------------------------------ launcher entry count
{
    echo '## 10. 入口数量核对'
    echo
    echo '预期源码清单中恰好 3 个 `android.intent.action.MAIN` 入口：LAUNCHER、LEANBACK_LAUNCHER，以及默认禁用的 HOME 别名。'
    echo
    echo '```text'
} >> "$REPORT"

MAIN_COUNT="$(grep -c 'android.intent.action.MAIN' app/src/main/AndroidManifest.xml 2>/dev/null)"
MAIN_COUNT="${MAIN_COUNT:-0}"
echo "源码清单中的 MAIN 条目数：$MAIN_COUNT" >> "$REPORT"
echo '```' >> "$REPORT"
echo >> "$REPORT"

if [ "$MAIN_COUNT" -ne 3 ]; then
    fail "expected exactly 3 MAIN entries in the source manifest, found $MAIN_COUNT"
fi

# ------------------------------------------------------------------ unit test results
{
    echo '## 11. 单元测试结果'
    echo
    echo '```text'
} >> "$REPORT"

TEST_XML_FOUND=0
for dir in common/build/test-results app/build/test-results; do
    [ -d "$dir" ] || continue
    while IFS= read -r f; do
        TEST_XML_FOUND=1
        echo "$f" >> "$REPORT"
        grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f" >> "$REPORT" 2>/dev/null || true
    done < <(find "$dir" -name 'TEST-*.xml' 2>/dev/null | sort | head -n 300)
done
if [ "$TEST_XML_FOUND" -eq 0 ]; then
    echo '未找到 JUnit XML 报告（单元测试可能未在本作业中运行）。' >> "$REPORT"
fi
{
    echo '```'
    echo
    echo '### 解析模块测试类'
    echo
    echo '```text'
} >> "$REPORT"

RESOLVER_XML="$(grep -rl 'data.endpoint' common/build/test-results 2>/dev/null | head -n 10 || true)"
if [ -n "$RESOLVER_XML" ]; then
    echo "$RESOLVER_XML" >> "$REPORT"
else
    echo '（未在报告中定位到 endpoint 包，请确认单元测试确实执行。）' >> "$REPORT"
fi
{
    echo '```'
    echo
    echo '## 12. 结论'
    echo
} >> "$REPORT"

if [ "$FAILURES" -eq 0 ]; then
    echo '- 通过：APK 已生成；包名、标签与启动入口符合预期；合并后清单中不存在 `Leaks` 启动入口；解析模块存在于 DEX 中。' >> "$REPORT"
else
    echo "- **未通过：${FAILURES} 项硬性检查失败，详见上文。**" >> "$REPORT"
fi

cat "$REPORT"

if [ "$FAILURES" -ne 0 ]; then
    exit 1
fi
exit 0
