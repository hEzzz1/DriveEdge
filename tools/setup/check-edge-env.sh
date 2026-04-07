#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

pass_count=0
warn_count=0
fail_count=0

pass() {
  echo "[PASS] $1"
  pass_count=$((pass_count + 1))
}

warn() {
  echo "[WARN] $1"
  warn_count=$((warn_count + 1))
}

fail() {
  echo "[FAIL] $1"
  fail_count=$((fail_count + 1))
}

check_cmd() {
  local cmd="$1"
  local label="$2"
  if command -v "$cmd" >/dev/null 2>&1; then
    pass "$label: $(command -v "$cmd")"
  else
    fail "$label 未安装或未加入 PATH"
  fi
}

check_env_non_empty() {
  local key="$1"
  local value="${!key:-}"
  if [[ -n "$value" ]]; then
    pass "$key=$value"
  else
    warn "$key 未设置"
  fi
}

check_dir_exists() {
  local path="$1"
  local label="$2"
  if [[ -d "$path" ]]; then
    pass "$label 存在: $path"
  else
    warn "$label 不存在: $path"
  fi
}

echo "== DriveEdge 边缘开发环境自检 =="
echo "ROOT_DIR=$ROOT_DIR"
echo

echo "## 1) 基础命令检查"
check_cmd git "git"
check_cmd python3 "python3"
check_cmd java "java"
check_cmd adb "adb"
check_cmd cmake "cmake"
check_cmd sdkmanager "sdkmanager"
echo

echo "## 2) Java 版本检查"
if command -v java >/dev/null 2>&1; then
  java_version="$(java -version 2>&1 | head -n 1 || true)"
  if [[ "$java_version" == *"17."* || "$java_version" == *" 17"* ]]; then
    pass "Java 版本符合建议: $java_version"
  else
    warn "Java 建议使用 JDK 17，当前: $java_version"
  fi
fi
echo

echo "## 3) Android 环境变量检查"
check_env_non_empty JAVA_HOME
check_env_non_empty ANDROID_HOME
check_env_non_empty ANDROID_SDK_ROOT
check_env_non_empty ANDROID_NDK_HOME
echo

echo "## 4) Android SDK 目录检查"
if [[ -n "${ANDROID_HOME:-}" ]]; then
  check_dir_exists "$ANDROID_HOME/platform-tools" "platform-tools"
  check_dir_exists "$ANDROID_HOME/cmdline-tools/latest" "cmdline-tools/latest"
  check_dir_exists "$ANDROID_HOME/build-tools/34.0.0" "build-tools 34.0.0"
  check_dir_exists "$ANDROID_HOME/platforms/android-34" "platform android-34"
  check_dir_exists "$ANDROID_HOME/ndk/26.1.10909125" "ndk 26.1.10909125"
fi
echo

echo "## 5) 仓库目录检查"
for dir in models models/raw models/deploy logs artifacts; do
  if [[ -d "$ROOT_DIR/$dir" ]]; then
    pass "目录存在: $dir"
  else
    warn "目录缺失: $dir（可执行 mkdir -p $ROOT_DIR/$dir）"
  fi
done
echo

echo "## 6) ADB 连接检查"
if command -v adb >/dev/null 2>&1; then
  adb_output="$(adb devices 2>/dev/null | tail -n +2 | sed '/^\s*$/d' || true)"
  if [[ -n "$adb_output" ]]; then
    pass "检测到设备:"
    echo "$adb_output" | sed 's/^/  - /'
  else
    warn "未检测到可用设备（可忽略，仅真机调试需要）"
  fi
fi
echo

echo "== 自检结果 =="
echo "PASS=$pass_count WARN=$warn_count FAIL=$fail_count"

if [[ $fail_count -gt 0 ]]; then
  echo "存在关键失败项，请先处理 FAIL 再继续。"
  exit 1
fi

echo "环境基础检查完成。"
